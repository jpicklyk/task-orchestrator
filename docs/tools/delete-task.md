# delete_task Tool - Detailed Documentation

## Overview

Deletes a task by ID with cascade deletion support and dependency chain handling. Provides safety mechanisms to prevent accidental deletion of tasks with active dependencies.

**Resource**: `task-orchestrator://docs/tools/delete-task`

## Key Concepts

### Dependency Protection
By default, the tool prevents deletion of tasks that have dependencies (incoming or outgoing). This protects dependency chains from being broken accidentally.

### Force Deletion
When `force=true`, the tool will delete the task even if dependencies exist, breaking dependency chains and providing detailed warnings about affected tasks.

### Cascade Deletion
Automatically deletes associated resources (sections, dependencies) when deleting a task to maintain database integrity.

### Locking System
Uses concurrency locking to prevent simultaneous deletion attempts and ensure data consistency.

## Parameter Reference

### Required Parameters
- **id** (UUID): Task identifier to delete

### Optional Parameters
- **deleteSections** (boolean, default: true): Delete associated section content
- **cascade** (boolean, default: false): Delete subtasks (experimental feature)
- **force** (boolean, default: false): Delete even if dependencies exist
- **hardDelete** (boolean, default: false): Permanently remove vs soft delete

## Safety Levels

### Level 1: Safe Delete (Default)
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Behavior**:
- Fails if task has dependencies
- Deletes sections by default
- Returns error with dependency information

**When to use**: Normal cleanup of completed or cancelled tasks

### Level 2: Force Delete
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "force": true
}
```

**Behavior**:
- Deletes task even with dependencies
- Breaks dependency chains
- Deletes all associated dependencies
- Returns warning about broken chains

**When to use**: Removing task that's blocking cleanup, reorganizing work

### Level 3: Preserve Sections
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "deleteSections": false
}
```

**Behavior**:
- Deletes task but preserves section content
- Sections become orphaned (for potential recovery)

**When to use**: Rare - only if section content has archival value

## Common Usage Patterns

### Pattern 1: Delete Completed Task
Remove a finished task that's no longer needed.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Prerequisites**:
- Task status is `completed` or `cancelled`
- No other tasks depend on this one
- No incoming or outgoing dependencies

**Response**:
```json
{
  "success": true,
  "message": "Task deleted successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "deleted": true,
    "sectionsDeleted": 3,
    "dependenciesDeleted": 0
  }
}
```

### Pattern 2: Handle Dependency Rejection
When deletion fails due to dependencies, analyze and decide next steps.

```javascript
// Attempt deletion
const result = await delete_task({
  id: "550e8400-e29b-41d4-a716-446655440000"
});

if (!result.success && result.error.code === "VALIDATION_ERROR") {
  // Deletion failed due to dependencies
  console.log(result.error.details);
  // "Task has 3 dependencies. Use 'force=true' to delete anyway and break dependency chains."

  // Review dependencies before forcing
  const deps = await get_task_dependencies({
    taskId: "550e8400-e29b-41d4-a716-446655440000"
  });

  // Decide: remove dependencies or force delete
}
```

### Pattern 3: Force Delete with Dependencies
Delete a task that's blocking cleanup, accepting broken dependency chains.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "force": true
}
```

**Response with warnings**:
```json
{
  "success": true,
  "message": "Task deleted successfully with 3 dependencies and 4 sections",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "deleted": true,
    "sectionsDeleted": 4,
    "dependenciesDeleted": 3,
    "warningsBrokenDependencies": true,
    "brokenDependencyChains": {
      "incomingDependencies": 1,
      "outgoingDependencies": 2,
      "affectedTasks": 2
    }
  }
}
```

**Interpretation**:
- **incomingDependencies (1)**: One task was blocking this task
- **outgoingDependencies (2)**: This task was blocking two other tasks
- **affectedTasks (2)**: Two other tasks are affected by broken chains

### Pattern 4: Clean Delete Chain
Properly delete a chain of dependent tasks in correct order.

```javascript
// Delete in dependency order (leaf to root)
// Task A blocks Task B blocks Task C
// Delete order: C → B → A

// Step 1: Delete leaf task (C)
await delete_task({ id: taskC });

// Step 2: Delete middle task (B)
await delete_task({ id: taskB });

// Step 3: Delete root task (A)
await delete_task({ id: taskA });
```

### Pattern 5: Bulk Cleanup of Completed Tasks
Remove multiple completed tasks that have no dependencies.

```javascript
// Get completed tasks
const tasks = await search_tasks({
  status: "completed",
  limit: 50
});

// Delete each completed task
for (const task of tasks.data.tasks) {
  // Check for dependencies first
  const deps = await get_task_dependencies({ taskId: task.id });

  if (deps.data.counts.total === 0) {
    await delete_task({ id: task.id });
  } else {
    console.log(`Skipping ${task.id} - has ${deps.data.counts.total} dependencies`);
  }
}
```

### Pattern 6: Reorganization Force Delete
Delete tasks during feature reorganization where dependency chains need restructuring.

```javascript
// Reorganizing feature - deleting old tasks and creating new structure
// Force delete old tasks that have dependencies
await delete_task({
  id: oldTaskId,
  force: true  // Accept broken chains - new tasks will replace them
});

// Create replacement tasks with new dependencies
const newTask = await create_task({
  title: "Refactored implementation",
  featureId: featureId
});
```

## Response Structure

### Successful Deletion (No Dependencies)
```json
{
  "success": true,
  "message": "Task deleted successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "deleted": true,
    "sectionsDeleted": 2,
    "dependenciesDeleted": 0
  }
}
```

### Successful Force Deletion (With Dependencies)
```json
{
  "success": true,
  "message": "Task deleted successfully with 5 dependencies and 3 sections",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "deleted": true,
    "sectionsDeleted": 3,
    "dependenciesDeleted": 5,
    "warningsBrokenDependencies": true,
    "brokenDependencyChains": {
      "incomingDependencies": 2,
      "outgoingDependencies": 3,
      "affectedTasks": 4
    }
  }
}
```

### Failed Deletion (Dependencies Exist)
```json
{
  "success": false,
  "message": "Cannot delete task with existing dependencies",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Task has 5 dependencies. Use 'force=true' to delete anyway and break dependency chains.",
    "additionalData": {
      "totalDependencies": 5,
      "incomingDependencies": 2,
      "outgoingDependencies": 3,
      "affectedTasks": 4
    }
  }
}
```

## Dependency Chain Analysis

### Understanding the Response

**Incoming Dependencies**: Other tasks that block this task
```
Task A (blocker) --BLOCKS--> This Task
```

**Outgoing Dependencies**: Tasks that this task blocks
```
This Task --BLOCKS--> Task B (blocked)
```

**Affected Tasks**: Unique tasks involved in dependency chains (excluding the deleted task)

### Example Scenario
```
Task Schema (A) --BLOCKS--> Task API (This) --BLOCKS--> Task Tests (B)
                                           --BLOCKS--> Task Docs (C)
```

If deleting "Task API" with `force=true`:
```json
{
  "incomingDependencies": 1,    // Schema blocks API
  "outgoingDependencies": 2,    // API blocks Tests and Docs
  "affectedTasks": 3            // Schema, Tests, Docs (all affected)
}
```

## Common Mistakes to Avoid

### ❌ Mistake 1: Force Deleting Without Checking Dependencies
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "force": true  // ❌ Using force without understanding impact
}
```
**Problem**: May break critical dependency chains

### ✅ Solution: Check Dependencies First
```javascript
// Step 1: Check what would be affected
const result = await delete_task({
  id: "550e8400-e29b-41d4-a716-446655440000"
});

if (!result.success) {
  // Review error details
  console.log(result.error.additionalData);

  // Step 2: Decide if force is appropriate
  // Only use force if you understand the impact
}
```

### ❌ Mistake 2: Deleting in Wrong Order
```javascript
// ❌ Wrong: Deleting blocker task first
await delete_task({ id: blockerTaskId, force: true });
await delete_task({ id: blockedTaskId });  // Now orphaned!
```

### ✅ Solution: Delete Leaf-to-Root
```javascript
// ✅ Correct: Delete blocked tasks first (leaf-to-root)
await delete_task({ id: blockedTaskId });
await delete_task({ id: blockerTaskId });
```

### ❌ Mistake 3: Not Handling Deletion Failures
```javascript
// ❌ Assuming deletion always succeeds
delete_task({ id: taskId });
// Continue without checking success...
```

### ✅ Solution: Check Results
```javascript
// ✅ Handle failures appropriately
const result = await delete_task({ id: taskId });

if (result.success) {
  console.log(`Deleted task ${taskId}`);
} else {
  console.error(`Failed to delete: ${result.error.details}`);
  // Handle failure (retry, force, or skip)
}
```

### ❌ Mistake 4: Forgetting About Sections
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "deleteSections": false  // ❌ Usually not what you want
}
```
**Problem**: Orphaned sections clutter database

### ✅ Solution: Delete Sections by Default
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000"
  // deleteSections defaults to true
}
```

## Safety Checklist

Before deleting a task, ask:

1. **Is the task completed or cancelled?**
   - ✅ Safe to delete
   - ❌ In-progress tasks should be completed first

2. **Does the task have dependencies?**
   - ✅ No dependencies: safe to delete
   - ⚠️ Has dependencies: review before forcing

3. **Are other tasks blocked by this one?**
   - ✅ No outgoing dependencies: safe to delete
   - ⚠️ Blocks others: may need to complete or transfer work

4. **Is this task part of a feature still in development?**
   - ⚠️ May want to keep for feature context
   - ✅ Standalone or feature completed: safe to delete

5. **Have you documented important learnings?**
   - ✅ Important information preserved elsewhere
   - ❌ Task contains unique insights: archive first

## Error Handling

### Task Not Found
```json
{
  "success": false,
  "message": "Failed to retrieve task",
  "error": {
    "code": "RESOURCE_NOT_FOUND"
  }
}
```

**Common causes**:
- Task already deleted
- Wrong UUID
- Typo in ID

**Solution**: Verify task exists with `get_task` or `search_tasks`

### Dependency Protection
```json
{
  "success": false,
  "message": "Cannot delete task with existing dependencies",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Task has 3 dependencies. Use 'force=true' to delete anyway and break dependency chains."
  }
}
```

**Solution Options**:
1. Delete dependencies first
2. Use `force=true` if appropriate
3. Complete the task instead of deleting

## Best Practices

1. **Check Dependencies First**: Review dependency impact before force deletion
2. **Delete Leaf-to-Root**: Remove dependent tasks before blockers
3. **Handle Failures**: Check success and handle dependency rejections
4. **Use Force Sparingly**: Only when you understand the impact
5. **Delete Sections by Default**: Keep database clean
6. **Document Broken Chains**: Log which tasks were affected by force deletion
7. **Prefer Completion Over Deletion**: Mark as completed/cancelled instead
8. **Batch Cleanup Carefully**: Check dependencies for each task individually

## Alternatives to Deletion

### Option 1: Mark as Cancelled
Instead of deleting, cancel the task.

```javascript
await update_task({
  id: taskId,
  status: "cancelled",
  summary: "Task cancelled due to change in requirements. Work not needed."
});
```

**Advantages**:
- Preserves history
- Maintains dependency chains
- Can be uncancelled if needed

### Option 2: Archive in Feature
Move to an "Archive" feature instead of deleting.

```javascript
await update_task({
  id: taskId,
  status: "cancelled",
  featureId: archiveFeatureId
});
```

**Advantages**:
- Preserves work context
- Easy to review later
- Doesn't break dependencies

### Option 3: Export Before Deleting
Save task as markdown before deletion.

```javascript
const markdown = await task_to_markdown({ id: taskId });
// Save markdown to file system
await delete_task({ id: taskId });
```

**Advantages**:
- Preserves documentation
- Can be referenced later
- Cleanly removes from active work

## Integration Patterns

### Pattern: Safe Bulk Deletion
```javascript
async function safeDeleteCompleted() {
  const completed = await search_tasks({ status: "completed" });

  for (const task of completed.data.tasks) {
    // Check dependencies
    const result = await delete_task({ id: task.id });

    if (!result.success) {
      console.log(`Skipped ${task.title}: ${result.error.details}`);
    }
  }
}
```

### Pattern: Force Delete with Logging
```javascript
async function forceDeleteWithLogging(taskId) {
  const result = await delete_task({ id: taskId, force: true });

  if (result.success && result.data.warningsBrokenDependencies) {
    // Log broken dependency chains for review
    logger.warn("Broken dependency chains:", {
      taskId: taskId,
      affected: result.data.brokenDependencyChains
    });
  }

  return result;
}
```

## Related Tools

- **delete_dependency**: Remove specific dependency relationships
- **update_task**: Alternative to deletion (mark as cancelled)
- **get_task_dependencies**: Check dependencies before deletion
- **search_tasks**: Find tasks to delete
- **task_to_markdown**: Export task before deletion
- **create_task**: Create replacement tasks

## See Also

- Task Management Patterns: `task-orchestrator://guidelines/task-management`
- Dependency Management: `task-orchestrator://guidelines/dependency-management`
- Data Cleanup: `task-orchestrator://guidelines/data-maintenance`
