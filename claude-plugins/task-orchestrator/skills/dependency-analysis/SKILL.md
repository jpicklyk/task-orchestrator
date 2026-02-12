---
name: dependency-analysis
description: Manage task dependencies, identify blockers, and resolve dependency chains using MCP Task Orchestrator. Use when creating dependencies, checking blocked tasks, or analyzing work ordering.
---

# Dependency Analysis

Dependencies control work ordering and block status transitions until prerequisites are met.

## Dependency Types

| Type | Meaning | Blocking? |
|------|---------|-----------|
| `BLOCKS` | from-task blocks to-task | Yes |
| `IS_BLOCKED_BY` | from-task is blocked by to-task | Yes (inverse) |
| `RELATES_TO` | Informational link | No |

`BLOCKS` and `IS_BLOCKED_BY` are directional inverses — use whichever reads naturally. `fromTaskId` is the prerequisite, `toTaskId` is the dependent.

## Querying Dependencies

- `query_dependencies(taskId=...)` — all dependencies for a task
- Filter with `direction="incoming"` (what blocks this task) or `direction="outgoing"` (what this task blocks)
- Add `includeTaskInfo=true` for full task details on related tasks

## Finding Blocked Tasks

- `get_blocked_tasks(featureId=...)` — all blocked tasks in a feature
- `get_blocked_tasks(projectId=...)` — all blocked tasks across a project
- Response includes each blocked task, its blockers, and their statuses

## Resolving Blockers

1. Identify blockers with `get_blocked_tasks`
2. Complete the blocking task with `request_transition(trigger="complete")`
3. The dependent task can now progress — verify with `get_next_status`

## Smart Task Ordering

`get_next_task` automatically excludes blocked tasks and sorts by priority then complexity (quick wins first). Use `featureId` or `projectId` to scope recommendations.

## Common Patterns

**Linear chain:** A -> B -> C -> D — each task blocks the next

**Fan-out / Fan-in:** A blocks B, C, D (parallel work); B, C, D all block E (convergence)

## Automatic Validation

`request_transition(trigger="complete")` validates that all blocking dependencies are resolved. Unresolved dependencies cause the transition to fail with details about what's still blocking.

## Deleting Dependencies

Use `manage_dependencies(operation="delete")` — by dependency ID, by task pair (`fromTaskId` + `toTaskId`), or all dependencies for a task (`deleteAll=true`).

## Batch Creation

`manage_dependencies` supports creating multiple dependencies in a single call:

- **Explicit array:** `dependencies=[{fromTaskId, toTaskId, type?}, ...]` — full control over each edge
- **Pattern shortcuts:** Generate common topologies from task ID lists:
  - `pattern="linear"` + `taskIds=[A,B,C,D]` → A blocks B blocks C blocks D
  - `pattern="fan-out"` + `source=A` + `targets=[B,C,D]` → A blocks B, C, and D
  - `pattern="fan-in"` + `sources=[B,C,D]` + `target=E` → B, C, D all block E

Batch creation is atomic — if any dependency fails validation (cycle, duplicate, missing task), none are created.

## Graph Traversal

`query_dependencies` supports full graph analysis with `neighborsOnly=false`:

- Default (`neighborsOnly=true`): Returns only immediate neighbors of the queried task
- `neighborsOnly=false`: Adds a `graph` object with chain depth, critical path, bottlenecks, and parallelizable task groups

Use graph traversal for dependency audits, sprint planning, and bottleneck identification.
