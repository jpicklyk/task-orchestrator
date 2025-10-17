# delete_dependency Tool - Detailed Documentation

## Overview

Deletes task dependencies with flexible targeting options. Supports deletion by specific dependency ID, by task relationship (from/to pair), or bulk deletion of all dependencies for a task.

**Resource**: `task-orchestrator://docs/tools/delete-dependency`

## Key Concepts

### Deletion Methods

**By Dependency ID** (Most Precise)
- Delete a specific dependency when you know its UUID
- Safest method, no ambiguity
- Recommended for targeted removal

**By Task Relationship** (Contextual)
- Delete dependencies between two specific tasks
- Optional type filtering for precision
- Useful when you know the task relationship but not the dependency ID

**Bulk Deletion** (Cleanup)
- Remove all dependencies for a specific task
- Useful when deleting a task or restructuring dependencies
- Optional type filtering to preserve certain relationships

### Safety Considerations

Dependencies are **critical for workflow management**. Consider impact before deletion:
- Will dependent tasks become unblocked prematurely?
- Are you removing important relationship context?
- Should you update task status after removing blockers?

## Parameter Reference

### Deletion by Dependency ID

**Required**:
- **id** (UUID): Specific dependency UUID to delete

**Mutually Exclusive**: Cannot use with `fromTaskId`, `toTaskId`, or `deleteAll`

### Deletion by Task Relationship

**Required**:
- **fromTaskId** (UUID): Source task ID
- **toTaskId** (UUID): Target task ID

**Optional**:
- **type** (enum): Filter by dependency type
  - `BLOCKS`
  - `IS_BLOCKED_BY`
  - `RELATES_TO`

**Note**: Both tasks must be specified unless using `deleteAll`

### Bulk Deletion

**Required**:
- **deleteAll** (boolean): Set to `true`
- **fromTaskId** OR **toTaskId** (UUID): Task to delete dependencies for

**Optional**:
- **type** (enum): Filter by dependency type (only delete specific types)

**Note**: Specify only ONE of `fromTaskId` or `toTaskId`, not both

## Response Structure

### Successful Deletion

```json
{
  "success": true,
  "message": "Dependency deleted successfully",
  "data": {
    "deletedCount": 1,
    "deletedDependencies": [
      {
        "id": "dep-uuid-1",
        "fromTaskId": "task-uuid-1",
        "toTaskId": "task-uuid-2",
        "type": "BLOCKS"
      }
    ]
  }
}
```

### Bulk Deletion

```json
{
  "success": true,
  "message": "3 dependencies deleted successfully",
  "data": {
    "deletedCount": 3,
    "deletedDependencies": [
      {
        "id": "dep-uuid-1",
        "fromTaskId": "task-uuid-1",
        "toTaskId": "task-uuid-2",
        "type": "BLOCKS"
      },
      {
        "id": "dep-uuid-2",
        "fromTaskId": "task-uuid-1",
        "toTaskId": "task-uuid-3",
        "type": "BLOCKS"
      },
      {
        "id": "dep-uuid-3",
        "fromTaskId": "task-uuid-4",
        "toTaskId": "task-uuid-1",
        "type": "RELATES_TO"
      }
    ]
  }
}
```

## Common Usage Patterns

### Pattern 1: Delete Specific Dependency by ID

Most precise deletion method when you know the dependency UUID.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**When to use**:
- You have the exact dependency ID from a previous query
- Removing a specific dependency from a set
- Most precise and safest deletion method

**Example workflow**:
```javascript
// Step 1: Get dependencies
const deps = await get_task_dependencies({
  taskId: myTask,
  direction: "incoming",
  includeTaskInfo: true
});

// Step 2: Identify specific dependency to remove
const blockerToRemove = deps.data.dependencies.find(dep =>
  dep.relatedTask.title === "Old prerequisite task"
);

// Step 3: Delete it
await delete_dependency({
  id: blockerToRemove.id
});
```

### Pattern 2: Delete Relationship Between Two Tasks

Remove dependencies between specific tasks without knowing dependency ID.

```json
{
  "fromTaskId": "550e8400-e29b-41d4-a716-446655440000",
  "toTaskId": "661f9511-f30c-52e5-b827-557766551111"
}
```

**When to use**:
- You know the task relationship but not the dependency ID
- Removing a known prerequisite relationship
- Cleaning up after task reorganization

**Example workflow**:
```javascript
// Remove "Database schema" blocking "API implementation"
await delete_dependency({
  fromTaskId: schemaTaskId,
  toTaskId: apiTaskId
});
```

### Pattern 3: Delete Specific Type Between Tasks

Remove only specific dependency types between tasks.

```json
{
  "fromTaskId": "550e8400-e29b-41d4-a716-446655440000",
  "toTaskId": "661f9511-f30c-52e5-b827-557766551111",
  "type": "BLOCKS"
}
```

**When to use**:
- Multiple dependency types exist between same tasks
- Want to remove BLOCKS but keep RELATES_TO
- Precision cleanup

**Example scenario**:
```
Task A -> Task B has:
- BLOCKS dependency (remove this)
- RELATES_TO dependency (keep this)

Solution: Filter by type=BLOCKS
```

### Pattern 4: Remove All Dependencies for a Task

Bulk cleanup when deleting or restructuring a task.

```json
{
  "fromTaskId": "550e8400-e29b-41d4-a716-446655440000",
  "deleteAll": true
}
```

**When to use**:
- Deleting a task and cleaning up dependencies first
- Resetting all dependencies for a task
- Removing a task from dependency chains

**Example workflow**:
```javascript
// Before deleting a task, remove all its dependencies
await delete_dependency({
  fromTaskId: taskToDelete,
  deleteAll: true
});

await delete_task({ id: taskToDelete });
```

### Pattern 5: Remove Only Blocking Dependencies

Clean up BLOCKS dependencies while preserving relationships.

```json
{
  "fromTaskId": "550e8400-e29b-41d4-a716-446655440000",
  "deleteAll": true,
  "type": "BLOCKS"
}
```

**When to use**:
- Task structure changed, blocking no longer needed
- Keep RELATES_TO for context but remove constraints
- Parallel work enablement

**Example scenario**:
```
Task had 5 dependencies:
- 3 BLOCKS (remove)
- 2 RELATES_TO (keep for context)

Result: Only BLOCKS removed, RELATES_TO preserved
```

## Common Workflows

### Workflow 1: Safe Dependency Removal

Check impact before removing a dependency.

```javascript
// Step 1: Check what will be affected
const deps = await get_task_dependencies({
  taskId: taskId,
  direction: "outgoing",
  type: "BLOCKS",
  includeTaskInfo: true
});

// Step 2: Identify dependency to remove
const depToRemove = deps.data.dependencies.find(dep =>
  dep.toTaskId === targetTaskId
);

// Step 3: Warn if target task is in progress
if (depToRemove.relatedTask.status === "in-progress") {
  console.warn("Warning: Removing blocker from in-progress task");
}

// Step 4: Delete dependency
await delete_dependency({
  id: depToRemove.id
});

// Step 5: Optionally notify or update affected task
console.log(`Removed blocker from: ${depToRemove.relatedTask.title}`);
```

### Workflow 2: Task Deletion Cleanup

Properly clean up dependencies before deleting a task.

```javascript
// Step 1: Check impact
const deps = await get_task_dependencies({
  taskId: taskToDelete,
  direction: "all",
  includeTaskInfo: true
});

console.log(`Task has:`);
console.log(`- ${deps.data.counts.incoming} incoming dependencies`);
console.log(`- ${deps.data.counts.outgoing} outgoing dependencies`);

// Step 2: Warn about impact
if (deps.data.counts.outgoing > 0) {
  console.warn("Deleting this task will unblock:");
  deps.data.dependencies.outgoing.forEach(dep => {
    console.warn(`- ${dep.relatedTask.title}`);
  });
}

// Step 3: Remove all dependencies
await delete_dependency({
  fromTaskId: taskToDelete,
  deleteAll: true
});

await delete_dependency({
  toTaskId: taskToDelete,
  deleteAll: true
});

// Step 4: Delete the task
await delete_task({ id: taskToDelete });
```

### Workflow 3: Restructure Feature Dependencies

Change dependency structure when requirements change.

```javascript
// Original: Task A -> Task B -> Task C
// New: Task A -> Task C (remove B from chain)

// Step 1: Remove old dependencies
await delete_dependency({
  fromTaskId: taskA,
  toTaskId: taskB,
  type: "BLOCKS"
});

await delete_dependency({
  fromTaskId: taskB,
  toTaskId: taskC,
  type: "BLOCKS"
});

// Step 2: Create new dependency
await create_dependency({
  fromTaskId: taskA,
  toTaskId: taskC,
  type: "BLOCKS"
});

console.log("Dependency chain restructured: A -> C");
```

### Workflow 4: Convert BLOCKS to RELATES_TO

Change blocking dependencies to relationships.

```javascript
// Step 1: Get all blocking dependencies
const deps = await get_task_dependencies({
  taskId: taskId,
  direction: "outgoing",
  type: "BLOCKS",
  includeTaskInfo: true
});

// Step 2: For each BLOCKS dependency
for (const dep of deps.data.dependencies) {
  // Delete BLOCKS dependency
  await delete_dependency({
    id: dep.id
  });

  // Create RELATES_TO instead
  await create_dependency({
    fromTaskId: dep.fromTaskId,
    toTaskId: dep.toTaskId,
    type: "RELATES_TO"
  });

  console.log(`Converted to RELATES_TO: ${dep.relatedTask.title}`);
}

console.log("Tasks can now be worked on in parallel");
```

### Workflow 5: Cleanup After Task Completion

Remove dependencies after task completion (optional optimization).

```javascript
// After completing a task
await set_status({ id: taskId, status: "completed" });

// Optional: Remove outgoing BLOCKS dependencies
// (They're no longer needed since task is complete)
const result = await delete_dependency({
  fromTaskId: taskId,
  deleteAll: true,
  type: "BLOCKS"
});

console.log(`Cleaned up ${result.data.deletedCount} completed blockers`);
```

## Validation Rules

### Mutual Exclusivity

**Cannot combine**:
- `id` with `fromTaskId` or `toTaskId`
- Reason: Different deletion methods

**Error example**:
```json
{
  "id": "dep-uuid",
  "fromTaskId": "task-uuid"  // ❌ Error
}
```

### DeleteAll Requirements

When `deleteAll=true`:
- Must specify EITHER `fromTaskId` OR `toTaskId` (not both)
- Cannot specify `id` parameter

**Valid**:
```json
{
  "fromTaskId": "task-uuid",
  "deleteAll": true
}
```

**Invalid**:
```json
{
  "fromTaskId": "task-uuid",
  "toTaskId": "task-uuid-2",  // ❌ Can't specify both
  "deleteAll": true
}
```

### Task Relationship Requirements

When NOT using `deleteAll`:
- Must specify BOTH `fromTaskId` AND `toTaskId`
- Optional `type` filter

**Valid**:
```json
{
  "fromTaskId": "task-uuid-1",
  "toTaskId": "task-uuid-2",
  "type": "BLOCKS"
}
```

**Invalid**:
```json
{
  "fromTaskId": "task-uuid-1"  // ❌ Missing toTaskId
}
```

## Error Responses

### Dependency Not Found (404)

```json
{
  "success": false,
  "message": "Dependency not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No dependency exists with ID 550e8400-e29b-41d4-a716-446655440000"
  }
}
```

### No Matching Dependencies (404)

```json
{
  "success": false,
  "message": "No matching dependencies found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No dependencies found between tasks 550e8400... and 661f9511... of type BLOCKS"
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
    "details": "Cannot specify both 'id' and task relationship parameters (fromTaskId/toTaskId)"
  }
}
```

## Best Practices

1. **Check Impact First**: Use `get_task_dependencies` to understand what will be affected
2. **Prefer Deletion by ID**: Most precise and safest method
3. **Use Type Filters**: Preserve RELATES_TO while removing BLOCKS
4. **Clean Up on Task Deletion**: Remove dependencies before deleting tasks
5. **Consider Dependent Tasks**: Check if removal will prematurely unblock work
6. **Document Reasons**: Note why dependencies are being removed in commit messages
7. **Bulk Operations with Care**: Use `deleteAll` cautiously, consider type filters

## Common Mistakes to Avoid

### ❌ Mistake 1: Deleting Without Checking Impact

```javascript
// BAD: Delete without checking what's affected
await delete_dependency({
  fromTaskId: taskA,
  toTaskId: taskB
});
```

**Problem**: Might unblock task B prematurely

### ✅ Solution: Check Impact First

```javascript
// GOOD: Check impact before deleting
const taskB = await get_task({ id: taskBId });
if (taskB.data.status !== "pending") {
  console.warn("Task is already started, dependency may be obsolete");
}

await delete_dependency({
  fromTaskId: taskA,
  toTaskId: taskB
});
```

### ❌ Mistake 2: Wrong Direction for deleteAll

```javascript
// BAD: Trying to delete dependencies in both directions at once
await delete_dependency({
  fromTaskId: taskId,
  toTaskId: taskId,  // ❌ Can't specify both
  deleteAll: true
});
```

### ✅ Solution: Two Separate Calls

```javascript
// GOOD: Delete incoming and outgoing separately
await delete_dependency({
  fromTaskId: taskId,
  deleteAll: true
});

await delete_dependency({
  toTaskId: taskId,
  deleteAll: true
});
```

### ❌ Mistake 3: Deleting All When Only Need Specific Type

```javascript
// BAD: Delete all dependencies
await delete_dependency({
  fromTaskId: taskId,
  deleteAll: true  // Removes RELATES_TO too!
});
```

**Problem**: Loses relationship context

### ✅ Solution: Filter by Type

```javascript
// GOOD: Only delete BLOCKS, keep RELATES_TO
await delete_dependency({
  fromTaskId: taskId,
  deleteAll: true,
  type: "BLOCKS"
});
```

### ❌ Mistake 4: Not Handling "Not Found" Gracefully

```javascript
// BAD: Assumes dependency exists
await delete_dependency({
  fromTaskId: taskA,
  toTaskId: taskB
});
```

**Problem**: Might fail if dependency already deleted

### ✅ Solution: Handle Errors

```javascript
// GOOD: Handle "not found" gracefully
try {
  await delete_dependency({
    fromTaskId: taskA,
    toTaskId: taskB
  });
  console.log("Dependency removed");
} catch (error) {
  if (error.code === "RESOURCE_NOT_FOUND") {
    console.log("Dependency already removed");
  } else {
    throw error;
  }
}
```

### ❌ Mistake 5: Mixing Deletion Methods

```javascript
// BAD: Mixing id with task relationship
await delete_dependency({
  id: depId,
  fromTaskId: taskA  // ❌ Mutually exclusive
});
```

### ✅ Solution: Choose One Method

```javascript
// GOOD: Use one deletion method
await delete_dependency({
  id: depId
});

// OR

await delete_dependency({
  fromTaskId: taskA,
  toTaskId: taskB
});
```

## Performance Considerations

### Deletion by ID (Fastest)
```json
{ "id": "dep-uuid" }
```
- Single database lookup
- Fastest method
- Recommended when ID is known

### Deletion by Relationship (Medium)
```json
{
  "fromTaskId": "task-uuid-1",
  "toTaskId": "task-uuid-2"
}
```
- Requires finding matching dependencies
- Filters applied in code
- Moderate performance

### Bulk Deletion (Slowest)
```json
{
  "fromTaskId": "task-uuid",
  "deleteAll": true
}
```
- Finds all dependencies for task
- Multiple deletions
- Most comprehensive but slowest

## Related Tools

- **create_dependency**: Create new dependencies between tasks
- **get_task_dependencies**: Query dependencies before deletion (recommended)
- **get_task**: Check task status before removing dependencies
- **delete_task**: Delete tasks (remove dependencies first)
- **update_task**: Update task details after dependency changes

## Integration Patterns

### With Task Deletion

```javascript
// Proper task deletion with dependency cleanup
const deps = await get_task_dependencies({
  taskId: taskToDelete,
  direction: "all"
});

// Remove all dependencies (incoming and outgoing)
if (deps.data.counts.incoming > 0) {
  await delete_dependency({
    toTaskId: taskToDelete,
    deleteAll: true
  });
}

if (deps.data.counts.outgoing > 0) {
  await delete_dependency({
    fromTaskId: taskToDelete,
    deleteAll: true
  });
}

// Now safe to delete task
await delete_task({ id: taskToDelete });
```

### With Dependency Restructuring

```javascript
// Get current dependencies
const deps = await get_task_dependencies({
  taskId: taskId,
  direction: "all",
  includeTaskInfo: true
});

// Analyze and restructure
for (const dep of deps.data.dependencies.outgoing) {
  if (shouldRemove(dep)) {
    await delete_dependency({ id: dep.id });
  }
}

// Create new dependencies as needed
await create_dependency({
  fromTaskId: taskId,
  toTaskId: newTaskId,
  type: "BLOCKS"
});
```

## See Also

- Dependency Management Guide: `task-orchestrator://guidelines/dependency-management`
- Create Dependency: `task-orchestrator://docs/tools/create-dependency`
- Get Task Dependencies: `task-orchestrator://docs/tools/get-task-dependencies`
- Task Deletion: `task-orchestrator://docs/tools/delete-task`
