---
layout: default
title: request_transition Tool
---

# request_transition - Trigger-Based Status Transitions

**Permission**: ✏️ WRITE

**Category**: Status Progression Tools (v2.0)

**Purpose**: Apply status transitions using named triggers with full validation, instead of specifying raw status values.

## Overview

The `request_transition` tool provides a higher-level interface for status changes by using named triggers (e.g., `start`, `complete`, `cancel`) instead of raw status values. It:

1. **Resolves the trigger** to a target status based on current state and workflow configuration
2. **Validates the transition** against the active workflow flow
3. **Checks prerequisites** (dependencies resolved, tasks completed, summary populated)
4. **Applies the status change** if valid
5. **Detects cascade events** (e.g., feature can now progress because all tasks completed)

Unlike `get_next_status` which is read-only, this tool **actually applies** the status change.

## Quick Start

### Basic Usage

Progress a task to its next status:

```json
{
  "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "containerType": "task",
  "trigger": "start"
}
```

### Complete a task:

```json
{
  "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "containerType": "task",
  "trigger": "complete"
}
```

### Block a task (emergency):

```json
{
  "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "containerType": "task",
  "trigger": "block",
  "summary": "Waiting on external API availability"
}
```

## Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `containerId` | UUID string | **Yes** | ID of the task, feature, or project to transition |
| `containerType` | enum | **Yes** | Type of container: `task`, `feature`, or `project` |
| `trigger` | string | **Yes** | Named trigger (see Built-in Triggers below) |
| `summary` | string | No | Note about why the transition is happening |

## Built-in Triggers

| Trigger | Description | Target Status |
|---------|-------------|---------------|
| `start` | Progress to next status in workflow flow | Next in sequence |
| `complete` | Move to completed terminal status | `completed` |
| `cancel` | Move to cancelled (emergency transition) | `cancelled` |
| `block` | Move to blocked (emergency transition) | `blocked` |
| `hold` | Move to on-hold (emergency transition) | `on-hold` |

### How Triggers Resolve

- **`start`**: Looks at the active workflow flow sequence and advances to the next status. If at `pending`, moves to `in-progress`. If at `in-progress`, moves to `testing`, etc.
- **`complete`/`cancel`/`block`/`hold`**: Always resolve to their fixed target regardless of flow position. Emergency triggers (`cancel`, `block`, `hold`) can be used from any status.

## Response Schema

### Successful Transition

```json
{
  "success": true,
  "message": "Transitioned task from 'in-progress' to 'testing'",
  "data": {
    "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
    "containerType": "task",
    "previousStatus": "in-progress",
    "newStatus": "testing",
    "trigger": "start",
    "applied": true,
    "summary": "Beginning test phase",
    "advisory": "Consider running integration tests before marking complete",
    "cascadeEvents": []
  }
}
```

**Optional response fields:**
- `summary` (string): Echoed back from the request's `summary` parameter, if provided
- `advisory` (string): Advisory message from the status validator, when applicable
- `cleanup` (object): Cleanup details when a container deletion trigger fires (e.g., cancelling a feature may clean up tasks). Fields: `performed`, `tasksDeleted`, `tasksRetained`, `retainedTaskIds`, `sectionsDeleted`, `dependenciesDeleted`, `reason`
- `unblockedTasks` (array): List of downstream tasks that became fully unblocked as a result of this transition. Each entry has `taskId` and `title`. Only present when `containerType` is `task`, trigger is `complete` or `cancel`, and at least one downstream task became unblocked

### Transition Blocked

```json
{
  "success": false,
  "message": "Transition blocked: 1 incomplete blocking dependency",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": {
      "trigger": "complete",
      "currentStatus": "in-progress",
      "targetStatus": "completed",
      "reason": "1 incomplete blocking dependency",
      "suggestions": ["Complete blocking task 'Setup database schema' first"]
    }
  }
}
```

### No Transition Needed

```json
{
  "success": true,
  "message": "No transition needed - already at 'completed'",
  "data": {
    "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
    "containerType": "task",
    "currentStatus": "completed",
    "trigger": "complete",
    "applied": false
  }
}
```

### With Cascade Events

When a transition causes downstream effects:

```json
{
  "success": true,
  "message": "Transitioned task from 'in-progress' to 'completed'. 1 cascade event(s) detected.",
  "data": {
    "containerId": "a1b2c3d4-...",
    "previousStatus": "in-progress",
    "newStatus": "completed",
    "applied": true,
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
}
```

### With Cleanup (e.g., cancelling a feature)

```json
{
  "success": true,
  "message": "Transitioned feature from 'in-development' to 'cancelled'. Cleanup: 3 task(s) deleted, 1 retained.",
  "data": {
    "containerId": "f8a3c1e9-...",
    "containerType": "feature",
    "previousStatus": "in-development",
    "newStatus": "cancelled",
    "trigger": "cancel",
    "applied": true,
    "summary": "Feature deprioritized",
    "cleanup": {
      "performed": true,
      "tasksDeleted": 3,
      "tasksRetained": 1,
      "retainedTaskIds": ["a1b2c3d4-..."],
      "sectionsDeleted": 9,
      "dependenciesDeleted": 5,
      "reason": "Feature cancelled - cleaning up child tasks"
    }
  }
}
```

### With Unblocked Tasks (completing a blocker)

When completing or cancelling a task that blocks other tasks, the response includes any downstream tasks that are now fully unblocked (all their blockers are complete or cancelled):

```json
{
  "success": true,
  "message": "Transitioned task from 'in-progress' to 'completed'. 2 task(s) now unblocked.",
  "data": {
    "containerId": "a1b2c3d4-...",
    "containerType": "task",
    "previousStatus": "in-progress",
    "newStatus": "completed",
    "trigger": "complete",
    "applied": true,
    "previousRole": "work",
    "newRole": "terminal",
    "unblockedTasks": [
      { "taskId": "b2c3d4e5-...", "title": "Implement login endpoint" },
      { "taskId": "c3d4e5f6-...", "title": "Write integration tests" }
    ]
  }
}
```

This lets you discover newly available work without a separate `get_next_task` call. The `unblockedTasks` array only appears when `containerType` is `task`, the trigger is `complete` or `cancel`, and at least one downstream task became fully unblocked.

## Use Cases

### Use Case 1: Start Working on a Task

```json
{
  "containerId": "task-uuid",
  "containerType": "task",
  "trigger": "start",
  "summary": "Beginning implementation"
}
```

Moves task from `pending` to `in-progress` (or whatever the next status is in the active flow).

### Use Case 2: Complete a Bug Fix

```json
{
  "containerId": "task-uuid",
  "containerType": "task",
  "trigger": "complete"
}
```

Validates all prerequisites (summary length, dependencies resolved) before marking complete.

### Use Case 3: Block on External Dependency

```json
{
  "containerId": "task-uuid",
  "containerType": "task",
  "trigger": "block",
  "summary": "Blocked on third-party API approval"
}
```

Emergency transition - works from any status without following the normal flow.

### Use Case 4: Put Feature on Hold

```json
{
  "containerId": "feature-uuid",
  "containerType": "feature",
  "trigger": "hold",
  "summary": "Deprioritized for Q2"
}
```

## request_transition vs manage_container(setStatus)

| Aspect | request_transition | manage_container(setStatus) |
|--------|-------------------|---------------------------|
| **Input** | Named trigger (`start`, `complete`) | Raw status value (`in-progress`) |
| **Validation** | Full workflow validation + prerequisites | Basic status validation |
| **Flow awareness** | Resolves next status from workflow config | You specify exact target status |
| **Cascade detection** | Reports downstream effects | No cascade detection |
| **Best for** | Workflow-driven progression | Direct status override |

**Recommendation**: Use `request_transition` for normal workflow progression. Use `manage_container(setStatus)` only when you need to set a specific status that doesn't map to a trigger.

## Error Handling

### Common Errors

| Error | Cause | Fix |
|-------|-------|-----|
| `RESOURCE_NOT_FOUND` | Entity doesn't exist | Verify container ID |
| `VALIDATION_ERROR` (unknown trigger) | Invalid trigger name | Use: start, complete, cancel, block, hold |
| `VALIDATION_ERROR` (blocked) | Prerequisites not met | Check blockers in response, resolve them |
| `VALIDATION_ERROR` (flow violation) | Transition not allowed | Use `get_next_status` to see valid transitions |

## Best Practices

### DO

- **Use `get_next_status` first** to check readiness before transitioning
- **Include `summary`** for emergency transitions (block, hold, cancel) to explain why
- **Check `cascadeEvents`** in the response to handle downstream effects
- **Check `unblockedTasks`** after completing or cancelling a blocker to find newly available work
- **Use triggers** instead of raw status values for consistent workflow compliance

### DON'T

- **Don't use `complete` trigger** without checking prerequisites first
- **Don't ignore cascade events** - they indicate work items that may need attention
- **Don't use `cancel` lightly** - it's a terminal status; prefer `hold` for temporary pauses

## Related Tools

| Tool | Purpose | When to Use |
|------|---------|------------|
| **get_next_status** | Read-only recommendations | Check readiness before transitioning |
| **manage_container** (setStatus) | Direct status change | When you need a specific status value |
| **query_container** (get) | Get entity details | To understand entity context |

## References

- **Source code**: `src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/status/RequestTransitionTool.kt`
- **Workflow config**: `src/main/resources/configuration/default-config.yaml`
- **Status validation**: `src/main/kotlin/io/github/jpicklyk/mcptask/application/service/StatusValidator.kt`
- **Tests**: `src/test/kotlin/io/github/jpicklyk/mcptask/application/tools/status/RequestTransitionToolTest.kt`

### Related Documentation

- [Status Progression Guide](../status-progression.md) - Detailed workflow examples
- [get_next_status](get-next-status.md) - Read-only status recommendations
- [manage_container](manage-container.md) - Direct status changes
