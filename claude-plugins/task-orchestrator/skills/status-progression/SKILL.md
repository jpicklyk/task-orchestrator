---
name: status-progression
description: Navigate status workflows using get_next_status and request_transition tools. Use when changing entity status, checking workflow readiness, or handling blocked/emergency transitions.
---

# Status Progression

Status changes are config-driven with validation, prerequisites, and cascade detection.

## Two Tools, Two Purposes

- `get_next_status` — read-only check of available transitions, blockers, active flow, and role annotations
- `request_transition` — apply a status change with validation using named triggers

**Pattern:** Check readiness first, then transition.

## Named Triggers

| Trigger | Resolves to | Typical Role Change | Notes |
|---------|-------------|-------------------|-------|
| `start` | Next in flow | queue→work OR work→review | Advances one step based on entity's active flow |
| `complete` | `completed` | review→terminal (or work→terminal) | Terminal status |
| `cancel` | `cancelled` | any→terminal | Emergency: from any status |
| `block` | `blocked` | any→blocked | Emergency: from any status |
| `hold` | `on-hold` | any→blocked | Emergency: from any status |

Emergency triggers bypass normal flow. Always include `summary` for emergency transitions.

### Trigger-to-Role Mapping

Triggers resolve to statuses with specific roles. Common patterns:
- **start (queue→work):** `pending → in-progress` (backlog items entering active work)
- **start (work→review):** `in-progress → testing` (dev complete, entering validation)
- **complete (review→terminal):** `testing → completed` (validation passed)
- **complete (work→terminal):** `in-progress → completed` (short flows without review phase)
- **block/hold (any→blocked):** Lateral move to impediment tracking
- **cancel (any→terminal):** Emergency exit from any status

## Status Flows

### Tasks
**Default:** backlog -> pending -> in-progress -> completed

| Tags | Flow |
|------|------|
| `bug`, `bugfix`, `fix` | pending -> in-progress -> completed |
| `documentation`, `docs` | pending -> in-progress -> in-review -> completed |
| `hotfix`, `emergency` | in-progress -> testing -> completed |
| `qa-required`, `manual-test` | backlog -> pending -> in-progress -> testing -> completed |

### Features
**Default:** draft -> planning -> in-development -> testing -> validating -> completed

| Tags | Flow |
|------|------|
| `prototype`, `poc`, `spike` | draft -> in-development -> completed |
| `experiment`, `research` | draft -> in-development -> archived |

### Projects
**Default:** planning -> in-development -> completed -> archived

## Status Roles

| Role | Meaning | Examples |
|------|---------|---------|
| `queue` | Waiting | backlog, pending, draft, planning |
| `work` | Active | in-progress, changes-requested |
| `review` | Validation | testing, in-review, validating |
| `blocked` | Impediment | blocked, on-hold |
| `terminal` | Done | completed, cancelled, deferred |

### Role-Based Behavior

**Ordering:** `queue` → `work` → `review` → `terminal` (with `blocked` as a lateral state).

Status transitions include `previousRole` and `newRole` fields to indicate phase changes. Use these to select appropriate behavior:
- **queue → work:** Task pickup, start timer, notify assignee
- **work → review:** Trigger code review, run tests, request QA
- **review → terminal:** Archive artifacts, close tickets, update metrics
- **Any → blocked:** Escalate blocker, notify team, track impediments

**Role Filter:** Use the `role` parameter in `query_container` to filter by semantic phase:
```
query_container(operation="search", containerType="task", role="work")
```

Useful for queries like "show me what's actively being worked on" without listing specific status names.

## Validation Rules

- Sequential flow enforced (can't skip steps)
- Backward transitions allowed (e.g., testing -> in-progress for rework)
- Dependencies must be resolved before completing
- Emergency transitions bypass normal flow

### Dependency Unblocking

Dependencies can specify when they unblock with the `unblockAt` field:
- **`null` (default):** Unblocks when blocker reaches terminal status (backward compatible)
- **`"work"`:** Unblocks when blocker enters work role (e.g., `in-progress`)
- **`"review"`:** Unblocks when blocker enters review role (e.g., `testing`)

Example: Task B depends on Task A with `unblockAt: "work"` — Task B can start as soon as Task A enters `in-progress`, without waiting for Task A to complete.

Use `manage_dependencies` to set `unblockAt` when creating dependencies.

## Transition Mechanics

The `complete` trigger resolves directly to `completed` status. With `enforce_sequential: true` (the default), `complete` only succeeds from the **penultimate status** in the active flow — one step before `completed`.

### Default Flow (2 calls)

For the default task flow (`pending -> in-progress -> completed`):
1. `request_transition(trigger="start")` — pending to in-progress
2. `request_transition(trigger="complete")` — in-progress to completed

### Longer Flows (3+ calls)

For tagged flows with additional statuses (e.g., `qa-required` uses `with_testing_flow`):
1. Use `start` repeatedly to advance through intermediate statuses
2. Use `complete` only from the penultimate status

Example (`with_testing_flow`):
1. `start` — pending to in-progress
2. `start` — in-progress to testing
3. `complete` — testing to completed

### Recovery

If `complete` fails with "Cannot skip statuses. Must transition through: ...", use `start` to advance to the next status, then retry `complete` when you reach the penultimate position.

## Direct Override

Use `manage_container(operation="update", containers=[{id, status}])` for statuses that don't map to a trigger. Prefer `request_transition` for normal workflow with validation and cascade detection.

## Post-Transition Response

Transition responses include important follow-up data. Always check for:

- **`previousRole` / `newRole`** — Role before and after transition. Compare to detect phase changes (e.g., work→review triggers code review).
- **`cascadeEvents`** — suggestions to advance parent entity status (e.g., all tasks completed → advance feature). Act on these by calling `request_transition` on the parent.
- **`unblockedTasks`** — downstream tasks whose blocking dependencies are now resolved. Use `get_next_task` to pick them up.
- **Flow context** — `activeFlow`, `flowSequence`, `flowPosition` indicate the entity's current workflow position.

### Role Change Detection

Use `previousRole` and `newRole` to trigger custom behavior:
- **work → review:** Run tests, request code review, deploy to staging
- **review → terminal:** Archive artifacts, close tickets, update metrics
- **queue → work:** Start timer, notify assignee, move Kanban card
- **any → blocked:** Escalate blocker, notify team lead

See [Hook Integration Guide](../../../docs/hook-integration-guide.md) for PostToolUse hook examples.

## Completion Cleanup

When a feature reaches terminal status (`completed` or `archived`), its child tasks are **automatically deleted** — including their sections and dependencies. This is expected lifecycle behavior, not data loss.

**What survives cleanup:**
- The feature itself and its sections (preserved as the durable record)
- Tasks tagged with retain tags: `bug`, `bugfix`, `fix`, `hotfix`, `critical`
- Standalone tasks (no `featureId`)
- Projects (never deleted)

**Preservation pattern:** Before completing a feature, use `query_container(operation="export", containerType="feature", id=...)` to generate a full markdown snapshot of the feature and all its tasks.

**Disabling cleanup:** Set `completion_cleanup.enabled: false` in `.taskorchestrator/config.yaml`.

## Configuration Override

Status flows, validation rules, cascade behavior, and cleanup settings can be customized via `.taskorchestrator/config.yaml` in the project root (or the directory pointed to by `AGENT_CONFIG_DIR`).

Configurable areas:
- `status_progression` — custom flows, tag-to-flow mappings, terminal statuses, emergency transitions, status roles
- `status_validation` — `enforce_sequential`, `allow_backward`, `allow_emergency`, `validate_prerequisites`
- `completion_cleanup` — `enabled`, `retain_tags` for tasks that survive cleanup
- `auto_cascade` — `enabled`, `max_depth` for automatic cascade application

The server ships with sensible defaults. Override only what you need.
