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

Use `manage_container(operation="setStatus")` for statuses that don't map to a trigger. Prefer `request_transition` for normal workflow.

## Cascade Events

Transition responses may include `cascadeEvents` suggesting parent entity status updates (e.g., all tasks completed -> advance feature). Always check and act on these.

## Cascade Events

Transition responses may include `unblockedTasks` listing downstream tasks now available. Check `get_next_task` after completing work to pick up newly unblocked items.
