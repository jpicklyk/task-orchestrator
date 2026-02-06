---
name: dependency-analysis
description: Manage task dependencies, identify blockers, and resolve dependency chains using MCP Task Orchestrator. Use when creating dependencies, checking blocked tasks, or analyzing work ordering.
---

# Dependency Analysis

Patterns for managing task dependencies in the MCP Task Orchestrator. Dependencies control work ordering and block status transitions until prerequisites are met.

## Dependency Types

| Type | Meaning | Example |
|------|---------|---------|
| `BLOCKS` | Task A blocks Task B | "Design schema" blocks "Implement API" |
| `IS_BLOCKED_BY` | Task A is blocked by Task B | Inverse of BLOCKS |
| `RELATES_TO` | Informational link | No blocking behavior |

`BLOCKS` and `IS_BLOCKED_BY` are directional inverses. Use whichever reads naturally.

## Create Dependencies

```
manage_dependency(
  operation="create",
  fromTaskId="<design-task-uuid>",
  toTaskId="<implement-task-uuid>",
  type="BLOCKS"
)
```

This means: the design task must be completed before the implementation task can be completed.

### Dependency Direction

- `fromTaskId` = the task that **blocks** (prerequisite)
- `toTaskId` = the task that **is blocked** (dependent)
- Type `BLOCKS`: from blocks to
- Type `IS_BLOCKED_BY`: from is blocked by to

## Query Dependencies

**All dependencies for a task:**
```
query_dependencies(taskId="<uuid>")
```

**Only incoming (what blocks this task):**
```
query_dependencies(taskId="<uuid>", direction="incoming")
```

**Only outgoing (what this task blocks):**
```
query_dependencies(taskId="<uuid>", direction="outgoing")
```

**With full task details:**
```
query_dependencies(taskId="<uuid>", includeTaskInfo=true)
```

## Find Blocked Tasks

**All blocked tasks in a feature:**
```
get_blocked_tasks(featureId="<feature-uuid>", includeTaskDetails=true)
```

**All blocked tasks in a project:**
```
get_blocked_tasks(projectId="<project-uuid>")
```

Response includes:
- Each blocked task with its blocking dependencies
- The status of each blocking task
- Which blockers are resolved vs unresolved

## Resolve Blockers

1. **Identify blockers:**
   ```
   get_blocked_tasks(featureId="<uuid>", includeTaskDetails=true)
   ```

2. **Find the blocking task and complete it:**
   ```
   request_transition(containerId="<blocking-task-uuid>", containerType="task", trigger="complete")
   ```

3. **The blocked task can now progress.** Check with:
   ```
   get_next_status(containerId="<blocked-task-uuid>", containerType="task")
   ```

## Smart Task Ordering

`get_next_task` respects dependencies automatically:

```
get_next_task(featureId="<feature-uuid>", limit=3, includeDetails=true)
```

This returns tasks that:
1. Are not blocked by incomplete dependencies
2. Are in a workable status (pending, backlog)
3. Are sorted by priority and complexity

## Delete Dependencies

**By dependency ID:**
```
manage_dependency(operation="delete", id="<dependency-uuid>")
```

**Between two specific tasks:**
```
manage_dependency(operation="delete", fromTaskId="<uuid-a>", toTaskId="<uuid-b>")
```

**All dependencies for a task:**
```
manage_dependency(operation="delete", fromTaskId="<uuid>", deleteAll=true)
```

## Common Patterns

### Linear Chain
Tasks must execute in order:
```
A -> B -> C -> D
```
Create: A blocks B, B blocks C, C blocks D.

### Fan-Out / Fan-In
Multiple tasks can run in parallel, then converge:
```
     B
A -> C -> E
     D
```
Create: A blocks B, A blocks C, A blocks D, B blocks E, C blocks E, D blocks E.

### Prerequisite Check Before Completion

The `request_transition(trigger="complete")` automatically validates that all blocking dependencies are resolved. If they aren't, it returns an error with details about what's still blocking.

## Dependency Impact on Status

- A task with **unresolved blocking dependencies** cannot be completed via `request_transition(trigger="complete")`
- `get_next_status` reports unresolved dependencies as blockers
- `get_next_task` excludes tasks with unresolved dependencies
- Dependencies are directional: completing a blocker unblocks all dependent tasks
