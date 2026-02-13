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

| Trigger | Resolves to | Notes |
|---------|-------------|-------|
| `start` | Next in flow | Advances one step based on entity's active flow |
| `complete` | `completed` | Terminal status |
| `cancel` | `cancelled` | Emergency: from any status |
| `block` | `blocked` | Emergency: from any status |
| `hold` | `on-hold` | Emergency: from any status |

Emergency triggers bypass normal flow. Always include `summary` for emergency transitions.

## Status Flows

### Tasks
**Default:** backlog -> pending -> in-progress -> testing -> completed

| Tags | Flow |
|------|------|
| `bug`, `bugfix`, `fix` | pending -> in-progress -> testing -> completed |
| `documentation`, `docs` | pending -> in-progress -> in-review -> completed |
| `hotfix`, `emergency` | in-progress -> testing -> completed |

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

## Validation Rules

- Sequential flow enforced (can't skip steps)
- Backward transitions allowed (e.g., testing -> in-progress for rework)
- Dependencies must be resolved before completing
- Emergency transitions bypass normal flow

## Direct Override

Use `manage_container(operation="update", containers=[{id, status}])` for statuses that don't map to a trigger. Prefer `request_transition` for normal workflow with validation and cascade detection.

## Post-Transition Response

Transition responses include important follow-up data. Always check for:

- **`cascadeEvents`** — suggestions to advance parent entity status (e.g., all tasks completed → advance feature). Act on these by calling `request_transition` on the parent.
- **`unblockedTasks`** — downstream tasks whose blocking dependencies are now resolved. Use `get_next_task` to pick them up.
- **Flow context** — `activeFlow`, `flowSequence`, `flowPosition` indicate the entity's current workflow position.

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
