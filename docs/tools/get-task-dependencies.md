# get_task_dependencies Tool - Detailed Documentation

## Overview

Retrieves all dependencies for a specific task with powerful filtering options by direction and type. This tool provides comprehensive dependency information to understand what blocks a task, what tasks it blocks, and all related dependencies.

**Resource**: `task-orchestrator://docs/tools/get-task-dependencies`

## Key Concepts

### Dependency Directions

**Incoming Dependencies**: Dependencies pointing TO this task
- Other tasks that BLOCK this task
- Prerequisites that must complete before this task can start
- Use case: "What is blocking me from starting this task?"

**Outgoing Dependencies**: Dependencies FROM this task
- Tasks that THIS task BLOCKS
- Work that depends on this task completing
- Use case: "What work is waiting on me to finish?"

**All Dependencies**: Both incoming and outgoing
- Complete picture of task relationships
- Use case: "Show me all dependencies for this task"

### Dependency Types

**BLOCKS**: Task A blocks Task B from starting
**IS_BLOCKED_BY**: Task A is blocked by Task B (inverse of BLOCKS)
**RELATES_TO**: Tasks are related but no strict ordering required

## Parameter Reference

### Required Parameters
- **taskId** (UUID): Task ID to retrieve dependencies for

### Optional Parameters
- **direction** (enum): Filter by direction
  - `incoming` - Dependencies pointing to this task
  - `outgoing` - Dependencies from this task
  - `all` - Both directions (default)

- **type** (enum): Filter by dependency type
  - `BLOCKS` - Only blocking dependencies
  - `IS_BLOCKED_BY` - Only blocked-by dependencies
  - `RELATES_TO` - Only relationship dependencies
  - `all` - All types (default)

- **includeTaskInfo** (boolean): Include related task details (default: false)
  - When true: Adds `relatedTask` object with `id`, `title`, `status`
  - When false: Only includes task IDs

## Response Structure

### With direction="all" (Default)

```json
{
  "success": true,
  "message": "Dependencies retrieved successfully",
  "data": {
    "taskId": "550e8400-e29b-41d4-a716-446655440000",
    "dependencies": {
      "incoming": [
        {
          "id": "dep-uuid-1",
          "fromTaskId": "task-uuid-2",
          "toTaskId": "550e8400-e29b-41d4-a716-446655440000",
          "type": "BLOCKS",
          "createdAt": "2025-10-17T10:30:00Z"
        }
      ],
      "outgoing": [
        {
          "id": "dep-uuid-2",
          "fromTaskId": "550e8400-e29b-41d4-a716-446655440000",
          "toTaskId": "task-uuid-3",
          "type": "BLOCKS",
          "createdAt": "2025-10-17T11:00:00Z"
        }
      ]
    },
    "counts": {
      "total": 2,
      "incoming": 1,
      "outgoing": 1,
      "byType": {
        "BLOCKS": 2,
        "IS_BLOCKED_BY": 0,
        "RELATES_TO": 0
      }
    },
    "filters": {
      "direction": "all",
      "type": "all",
      "includeTaskInfo": false
    }
  }
}
```

### With direction="incoming" or "outgoing"

When filtering to a specific direction, dependencies are returned as a flat array:

```json
{
  "success": true,
  "message": "Dependencies retrieved successfully",
  "data": {
    "taskId": "550e8400-e29b-41d4-a716-446655440000",
    "dependencies": [
      {
        "id": "dep-uuid-1",
        "fromTaskId": "task-uuid-2",
        "toTaskId": "550e8400-e29b-41d4-a716-446655440000",
        "type": "BLOCKS",
        "createdAt": "2025-10-17T10:30:00Z"
      }
    ],
    "counts": {
      "total": 1,
      "incoming": 1,
      "outgoing": 0,
      "byType": {
        "BLOCKS": 1,
        "IS_BLOCKED_BY": 0,
        "RELATES_TO": 0
      }
    },
    "filters": {
      "direction": "incoming",
      "type": "all",
      "includeTaskInfo": false
    }
  }
}
```

### With includeTaskInfo=true

Related task information is enriched with title and status:

```json
{
  "id": "dep-uuid-1",
  "fromTaskId": "task-uuid-2",
  "toTaskId": "550e8400-e29b-41d4-a716-446655440000",
  "type": "BLOCKS",
  "createdAt": "2025-10-17T10:30:00Z",
  "relatedTask": {
    "id": "task-uuid-2",
    "title": "Create database schema",
    "status": "completed"
  }
}
```

## Common Usage Patterns

### Pattern 1: Check What's Blocking a Task

Before starting work, check if any dependencies block the task.

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "direction": "incoming",
  "type": "BLOCKS",
  "includeTaskInfo": true
}
```

**When to use**: Before starting a task to ensure prerequisites are complete

**Response analysis**:
```javascript
// Check if task is blocked
const blockedBy = response.data.dependencies.filter(dep =>
  dep.relatedTask.status !== 'completed'
);

if (blockedBy.length > 0) {
  console.log("Task is blocked by:");
  blockedBy.forEach(dep => {
    console.log(`- ${dep.relatedTask.title} (${dep.relatedTask.status})`);
  });
} else {
  console.log("Task is ready to start!");
}
```

### Pattern 2: Find Tasks Waiting on Completion

Check what work is waiting for this task to finish.

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "direction": "outgoing",
  "type": "BLOCKS",
  "includeTaskInfo": true
}
```

**When to use**: After completing a task to see what becomes unblocked

**Response analysis**:
```javascript
const waitingTasks = response.data.dependencies.map(dep => ({
  id: dep.toTaskId,
  title: dep.relatedTask.title,
  status: dep.relatedTask.status
}));

console.log(`Completing this task unblocks ${waitingTasks.length} task(s):`);
waitingTasks.forEach(task => console.log(`- ${task.title}`));
```

### Pattern 3: Get Complete Dependency Picture

Understand all relationships for planning and context.

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "direction": "all",
  "type": "all",
  "includeTaskInfo": true
}
```

**When to use**: Planning work order, understanding task context

**Response includes**:
- All incoming dependencies (what blocks this task)
- All outgoing dependencies (what this task blocks)
- Relationship dependencies (related work)
- Complete counts by type

### Pattern 4: Filter by Dependency Type

Find only specific types of relationships.

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "direction": "all",
  "type": "RELATES_TO"
}
```

**When to use**: Finding related work without blocking relationships

**Use case**: "Show me related tasks for context, but not strict dependencies"

### Pattern 5: Lightweight Query (Without Task Info)

Get dependency structure without fetching related task details.

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "direction": "all",
  "type": "all",
  "includeTaskInfo": false
}
```

**When to use**:
- Checking dependency counts
- Performance-sensitive queries
- When you only need task IDs

**Benefits**: Faster response, less data transfer

## Common Workflows

### Workflow 1: Pre-Work Validation

Before starting a task, ensure all blocking dependencies are complete.

```javascript
// Step 1: Get blocking dependencies
const deps = await get_task_dependencies({
  taskId: targetTaskId,
  direction: "incoming",
  type: "BLOCKS",
  includeTaskInfo: true
});

// Step 2: Check if any are incomplete
const incompleteBlockers = deps.data.dependencies.filter(dep =>
  dep.relatedTask.status !== 'completed'
);

// Step 3: Take action
if (incompleteBlockers.length === 0) {
  await set_status({ id: targetTaskId, status: "in-progress" });
  console.log("Task started!");
} else {
  console.log("Cannot start - blocked by:");
  incompleteBlockers.forEach(dep => {
    console.log(`- ${dep.relatedTask.title} (${dep.relatedTask.status})`);
  });
}
```

### Workflow 2: Dependency Chain Analysis

Analyze dependency chains for a feature.

```javascript
// Get all tasks for a feature
const feature = await get_feature({
  id: featureId,
  includeTasks: true
});

// For each task, get its dependencies
for (const task of feature.data.tasks) {
  const deps = await get_task_dependencies({
    taskId: task.id,
    direction: "all",
    includeTaskInfo: true
  });

  console.log(`\nTask: ${task.title}`);
  console.log(`Blocked by: ${deps.data.counts.incoming}`);
  console.log(`Blocks: ${deps.data.counts.outgoing}`);
}
```

### Workflow 3: Find Next Available Task

Identify the next task that can be started (no incomplete blockers).

```javascript
// Get all pending tasks for a feature
const tasks = await search_tasks({
  status: "pending",
  featureId: featureId
});

// Check each for blockers
for (const task of tasks.data.tasks) {
  const deps = await get_task_dependencies({
    taskId: task.id,
    direction: "incoming",
    type: "BLOCKS",
    includeTaskInfo: true
  });

  const hasBlockers = deps.data.dependencies.some(dep =>
    dep.relatedTask.status !== 'completed'
  );

  if (!hasBlockers) {
    console.log(`Ready to start: ${task.title}`);
    // This task has no incomplete blockers
    break;
  }
}
```

### Workflow 4: Impact Analysis Before Deletion

Understand the impact before deleting a task or dependency.

```javascript
// Before deleting a task, check what depends on it
const deps = await get_task_dependencies({
  taskId: taskToDelete,
  direction: "outgoing",
  type: "BLOCKS",
  includeTaskInfo: true
});

if (deps.data.counts.outgoing > 0) {
  console.log(`WARNING: Deleting this task will affect:`);
  deps.data.dependencies.forEach(dep => {
    console.log(`- ${dep.relatedTask.title} (currently ${dep.relatedTask.status})`);
  });
  // Prompt for confirmation
} else {
  // Safe to delete
  await delete_task({ id: taskToDelete });
}
```

## Understanding Counts

The `counts` object provides statistical breakdown:

```json
{
  "total": 5,           // Total dependencies (filtered)
  "incoming": 2,        // Dependencies TO this task
  "outgoing": 3,        // Dependencies FROM this task
  "byType": {
    "BLOCKS": 4,        // Blocking dependencies
    "IS_BLOCKED_BY": 0, // Blocked-by dependencies
    "RELATES_TO": 1     // Relationship dependencies
  }
}
```

**Note**: Counts respect applied filters (direction and type).

## Error Responses

### Task Not Found (404)

```json
{
  "success": false,
  "message": "Task not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No task exists with ID 550e8400-e29b-41d4-a716-446655440000"
  }
}
```

### Invalid Parameters (400)

```json
{
  "success": false,
  "message": "Invalid input",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid taskId format. Must be a valid UUID."
  }
}
```

## Best Practices

1. **Check Blockers Before Starting**: Always verify incoming BLOCKS dependencies before starting work
2. **Use includeTaskInfo Wisely**: Enable only when you need task details (performance consideration)
3. **Filter by Direction**: Use specific direction to reduce data and improve clarity
4. **Monitor Outgoing Dependencies**: Before completing, check what will be unblocked
5. **Validate Before Deletion**: Check dependencies before removing tasks or dependencies
6. **Use Counts for Quick Checks**: The counts object is perfect for quick validation
7. **Combine with get_task**: Use with `get_task(includeDependencies=true)` for complete task context

## Common Mistakes to Avoid

### ❌ Mistake 1: Ignoring Incomplete Blockers

```javascript
// BAD: Starting work without checking blockers
await set_status({ id: taskId, status: "in-progress" });
```

**Problem**: Task might be blocked by incomplete prerequisites

### ✅ Solution: Check Blockers First

```javascript
const deps = await get_task_dependencies({
  taskId: taskId,
  direction: "incoming",
  type: "BLOCKS",
  includeTaskInfo: true
});

const isBlocked = deps.data.dependencies.some(dep =>
  dep.relatedTask.status !== 'completed'
);

if (!isBlocked) {
  await set_status({ id: taskId, status: "in-progress" });
}
```

### ❌ Mistake 2: Unnecessary includeTaskInfo

```javascript
// BAD: Fetching task info when only counting dependencies
const deps = await get_task_dependencies({
  taskId: taskId,
  direction: "all",
  includeTaskInfo: true  // Unnecessary overhead
});

if (deps.data.counts.incoming === 0) {
  // Only using counts, didn't need task info
}
```

### ✅ Solution: Use includeTaskInfo Only When Needed

```javascript
// Good for counts only
const deps = await get_task_dependencies({
  taskId: taskId,
  direction: "incoming",
  includeTaskInfo: false
});

if (deps.data.counts.incoming === 0) {
  console.log("No dependencies!");
}
```

### ❌ Mistake 3: Not Filtering Direction

```javascript
// BAD: Fetching all when only need incoming
const deps = await get_task_dependencies({
  taskId: taskId,
  direction: "all"
});

// Then manually filtering incoming
const incoming = deps.data.dependencies.incoming;
```

### ✅ Solution: Filter at Query Time

```javascript
// GOOD: Filter on server side
const deps = await get_task_dependencies({
  taskId: taskId,
  direction: "incoming"
});

// Results already filtered
```

### ❌ Mistake 4: Confusing Direction Meaning

```javascript
// BAD: Wrong interpretation
const deps = await get_task_dependencies({
  taskId: myTask,
  direction: "outgoing"  // Thinking: "What blocks me?"
});
```

**Problem**: Outgoing = what THIS task blocks, not what blocks this task

### ✅ Solution: Remember Direction Meaning

```
incoming = What blocks THIS task (prerequisites)
outgoing = What THIS task blocks (dependents)
```

## Related Tools

- **create_dependency**: Create new dependencies between tasks
- **delete_dependency**: Remove dependencies
- **get_task**: Get task with embedded dependency information (`includeDependencies=true`)
- **search_tasks**: Find tasks by status (useful for finding next available work)
- **set_status**: Update task status after validating dependencies

## Integration Patterns

### With get_task

```javascript
// Option 1: Embedded dependencies in get_task
const task = await get_task({
  id: taskId,
  includeDependencies: true
});
// Returns task with dependencies embedded

// Option 2: Separate query for more control
const task = await get_task({ id: taskId });
const deps = await get_task_dependencies({
  taskId: taskId,
  direction: "incoming",
  type: "BLOCKS",
  includeTaskInfo: true
});
// More control over filters and options
```

### With Feature Workflows

```javascript
// Get feature tasks
const feature = await get_feature({
  id: featureId,
  includeTasks: true
});

// Analyze dependencies for all tasks
const analysis = await Promise.all(
  feature.data.tasks.map(task =>
    get_task_dependencies({
      taskId: task.id,
      direction: "all"
    })
  )
);

// Find tasks with no incoming dependencies (can start immediately)
const readyTasks = analysis
  .filter(deps => deps.data.counts.incoming === 0)
  .map(deps => deps.data.taskId);
```

## See Also

- Dependency Management Guide: `task-orchestrator://guidelines/dependency-management`
- Task Workflow Patterns: `task-orchestrator://guidelines/task-workflows`
- Create Dependency: `task-orchestrator://docs/tools/create-dependency`
- Delete Dependency: `task-orchestrator://docs/tools/delete-dependency`
