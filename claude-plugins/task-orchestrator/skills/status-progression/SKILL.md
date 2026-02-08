---
name: status-progression
description: Navigate status workflows using get_next_status and request_transition tools. Use when changing entity status, checking workflow readiness, or handling blocked/emergency transitions.
---

# Status Progression

Patterns for managing status workflows in the MCP Task Orchestrator. Status changes are config-driven with validation, prerequisites, and cascade detection.

## Two Tools, Two Purposes

| Tool | Purpose | Modifies state? |
|------|---------|----------------|
| `get_next_status` | Check what transitions are available | No (read-only) |
| `request_transition` | Apply a status change with validation | Yes |

**Pattern:** Check readiness first, then transition.

## Check Readiness

```
get_next_status(containerId="<uuid>", containerType="task")
```

Returns:
- **Next status options** with role annotations (queue, work, review, blocked, terminal)
- **Blockers** preventing transition (incomplete dependencies, missing content)
- **Active flow** being used (based on tags)
- **Emergency transitions** available from current status

## Apply Transitions with Triggers

Use named triggers instead of raw status values:

```
request_transition(containerId="<uuid>", containerType="task", trigger="start")
```

### Built-in Triggers

| Trigger | Resolves to | Description |
|---------|-------------|-------------|
| `start` | Next in flow sequence | Advance to next status (e.g., pending -> in-progress) |
| `complete` | `completed` | Move to terminal completed status |
| `cancel` | `cancelled` | Emergency: cancel from any status |
| `block` | `blocked` | Emergency: block from any status |
| `hold` | `on-hold` | Emergency: pause from any status |

### How `start` Resolves

The `start` trigger looks at the entity's active flow and advances one step:

- Task at `pending` with default_flow -> moves to `in-progress`
- Task at `in-progress` with default_flow -> moves to `testing`
- Task at `testing` with default_flow -> moves to `completed`

### Emergency Transitions

Emergency triggers (`cancel`, `block`, `hold`) work from **any** status, bypassing normal flow:

```
request_transition(
  containerId="<uuid>",
  containerType="task",
  trigger="block",
  summary="Waiting on third-party API approval"
)
```

Always include `summary` for emergency transitions to explain why.

## Cascade Events

When a transition causes downstream effects, the response includes cascade events:

```json
{
  "cascadeEvents": [
    {
      "event": "all_tasks_completed",
      "targetType": "feature",
      "targetId": "f8a3c1e9-...",
      "suggestedStatus": "testing",
      "reason": "All tasks in feature completed"
    }
  ]
}
```

**Always check cascade events** — they indicate parent entities that may need status updates.

## Status Flows by Entity Type

### Tasks

**Default:** backlog -> pending -> in-progress -> testing -> completed

| Tags | Flow |
|------|------|
| `bug`, `bugfix`, `fix` | pending -> in-progress -> testing -> completed |
| `documentation`, `docs` | pending -> in-progress -> in-review -> completed |
| `hotfix`, `emergency` | in-progress -> testing -> completed |

**Emergency transitions:** blocked, on-hold, cancelled, deferred

### Features

**Default:** draft -> planning -> in-development -> testing -> validating -> completed

| Tags | Flow |
|------|------|
| `prototype`, `poc`, `spike` | draft -> in-development -> completed |
| `experiment`, `research` | draft -> in-development -> archived |

**Emergency transitions:** blocked, on-hold, archived

### Projects

**Default:** planning -> in-development -> completed -> archived

**Emergency transitions:** on-hold, cancelled

## Status Roles

Each status has a semantic role for context:

| Role | Meaning | Example statuses |
|------|---------|-----------------|
| `queue` | Waiting to start | backlog, pending, draft, planning |
| `work` | Active effort | in-progress, changes-requested |
| `review` | Validation/QA | testing, in-review, validating |
| `blocked` | Impediment | blocked, on-hold |
| `terminal` | Done | completed, cancelled, deferred |

## Validation Rules

The system enforces:
- **Sequential flow**: Must follow defined flow steps (can't skip)
- **Backward transitions**: Allowed (e.g., testing -> in-progress for rework)
- **Prerequisites**: Dependencies must be resolved before completing
- **Emergency override**: Emergency transitions bypass normal flow

## Direct Status Override

When you need a specific status that doesn't map to a trigger:

```
manage_container(operation="setStatus", containerType="task", id="<uuid>", status="in-review")
```

Use `request_transition` for normal workflow. Use `manage_container(setStatus)` only for direct overrides.

## CC Task Mirror Sync

After any status transition, update the mirrored CC task if one exists. See `task-mirroring` skill for patterns and hook behavior.
