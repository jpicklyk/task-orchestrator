# set_status Tool - Detailed Documentation

## Overview

Updates the status of any entity (task, feature, or project) with automatic entity type detection and validation. This unified tool simplifies status updates across all entity types.

**Resource**: `task-orchestrator://docs/tools/set-status`

## Key Concepts

### Unified Status Management
A single tool handles status updates for all entity types:
- **Tasks**: pending, in-progress, completed, cancelled, deferred
- **Features**: planning, in-development, completed, archived
- **Projects**: planning, in-development, completed, archived

### Auto-Detection
The tool automatically determines whether the provided ID belongs to a task, feature, or project by checking each repository. No need to specify entity type.

### Smart Features
- **Dependency Warnings**: For tasks, warns if marking complete while blocking other tasks
- **Timestamp Updates**: Automatically updates modifiedAt timestamp
- **Status Validation**: Validates status values based on detected entity type

## Parameter Reference

### Required Parameters
- **id** (UUID): Entity identifier (task, feature, or project)
- **status** (string): New status value (validated based on entity type)

### Status Values by Entity Type

**Tasks**:
- `pending` - Task not yet started
- `in-progress` - Task actively being worked on
- `completed` - Task finished successfully
- `cancelled` - Task abandoned/no longer needed
- `deferred` - Task postponed to later

**Features**:
- `planning` - Feature being planned/designed
- `in-development` - Feature being implemented
- `completed` - Feature finished and deployed
- `archived` - Feature archived/deprecated

**Projects**:
- `planning` - Project in planning phase
- `in-development` - Project being actively developed
- `completed` - Project finished and delivered
- `archived` - Project archived/deprecated

## Common Usage Patterns

### Pattern 1: Start Work on Task
Mark a task as in-progress when beginning work.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "in-progress"
}
```

**When to use**: Starting work on a pending task
**Token usage**: ~100 tokens (60% more efficient than update_task)

### Pattern 2: Complete Task
Mark task as completed when work is finished.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "completed"
}
```

**Response includes**:
- Task ID and status
- Modified timestamp
- Warning if task blocks others (with count)

**When to use**: Task meets all acceptance criteria

### Pattern 3: Defer Task
Postpone a task that can't be completed now.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "deferred"
}
```

**When to use**:
- Blocked by external dependency
- Deprioritized by stakeholders
- Waiting for additional information
- Resource constraints

**Best practice**: Update task summary separately to explain why deferred

### Pattern 4: Move Feature to Development
Progress a feature from planning to active development.

```json
{
  "id": "661e8511-f30c-41d4-a716-557788990000",
  "status": "in-development"
}
```

**When to use**: Feature planning complete, ready for implementation

### Pattern 5: Complete Feature
Mark feature as completed when all tasks are done.

```json
{
  "id": "661e8511-f30c-41d4-a716-557788990000",
  "status": "completed"
}
```

**Best practice**: Verify all feature tasks are completed first

### Pattern 6: Archive Project
Archive a completed or cancelled project.

```json
{
  "id": "772f9622-g41d-52e5-b827-668899101111",
  "status": "archived"
}
```

**When to use**: Project no longer active or relevant

## Response Structure

### Successful Task Update
```json
{
  "success": true,
  "message": "Task status updated successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "entityType": "TASK",
    "status": "completed",
    "modifiedAt": "2025-10-17T15:30:00Z",
    "blockingTasksCount": 2,
    "warning": "This task blocks 2 other task(s)"
  }
}
```

### Successful Feature Update
```json
{
  "success": true,
  "message": "Feature status updated successfully",
  "data": {
    "id": "661e8511-f30c-41d4-a716-557788990000",
    "entityType": "FEATURE",
    "status": "in-development",
    "modifiedAt": "2025-10-17T15:30:00Z"
  }
}
```

### Successful Project Update
```json
{
  "success": true,
  "message": "Project status updated successfully",
  "data": {
    "id": "772f9622-g41d-52e5-b827-668899101111",
    "entityType": "PROJECT",
    "status": "completed",
    "modifiedAt": "2025-10-17T15:30:00Z"
  }
}
```

## Status Transition Workflows

### Task Status Flow
```
pending → in-progress → completed
         ↓
         cancelled / deferred

deferred → in-progress (resuming work)
```

### Feature/Project Status Flow
```
planning → in-development → completed → archived

planning → archived (cancelled before development)
```

## Integration Patterns

### Pattern: Complete Task with Summary
Combine status update with summary documentation.

```javascript
// Step 1: Update status
await set_status({
  id: taskId,
  status: "completed"
});

// Step 2: Add completion summary
await update_task({
  id: taskId,
  summary: "Implemented OAuth with Google provider. All 15 tests passing."
});
```

**Best practice**: Update status first, then add detailed summary

### Pattern: Check Dependencies Before Completing
Verify task won't block others when completing.

```javascript
// Step 1: Complete the task
const result = await set_status({
  id: taskId,
  status: "completed"
});

// Step 2: Check for blocking warning
if (result.data.blockingTasksCount > 0) {
  console.log(`Warning: This task blocks ${result.data.blockingTasksCount} other tasks`);
  // Optionally notify team or update dependent tasks
}
```

### Pattern: Feature Workflow Automation
Progress feature through its lifecycle stages.

```javascript
// Planning phase
await set_status({ id: featureId, status: "planning" });
// ... planning work ...

// Development phase
await set_status({ id: featureId, status: "in-development" });
// ... implementation work ...

// Completion
const tasks = await get_feature_tasks({ featureId });
const allComplete = tasks.data.tasks.every(t => t.status === "completed");

if (allComplete) {
  await set_status({ id: featureId, status: "completed" });
}
```

### Pattern: Bulk Status Updates
Update multiple tasks efficiently.

```javascript
// For 3+ tasks, use bulk_update_tasks instead
const taskIds = ["id-1", "id-2", "id-3", "id-4", "id-5"];

// Less efficient (5 separate calls):
for (const id of taskIds) {
  await set_status({ id, status: "completed" });
}

// More efficient (single call):
await bulk_update_tasks({
  tasks: taskIds.map(id => ({ id, status: "completed" }))
});
```

**70-90% token savings with bulk_update_tasks for 3+ tasks**

## Error Handling

### Entity Not Found
```json
{
  "success": false,
  "message": "Entity not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No task, feature, or project exists with ID ..."
  }
}
```

**Common causes**:
- Entity was deleted
- Wrong UUID provided
- Typo in ID

**Solution**: Verify entity exists with `get_task`, `get_feature`, or `get_project`

### Invalid Status for Entity Type
```json
{
  "success": false,
  "message": "Invalid task status: archived",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Valid task statuses: pending, in-progress, completed, cancelled, deferred"
  }
}
```

**Common causes**:
- Using feature/project status on task (or vice versa)
- Typo in status value

**Solution**: Check status values table above for correct values per entity type

### Invalid UUID Format
```json
{
  "success": false,
  "message": "Invalid ID format. Must be a valid UUID.",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Solution**: Ensure ID is a valid UUID format (e.g., "550e8400-e29b-41d4-a716-446655440000")

## Efficiency Comparison

### set_status vs update_task

**For status-only updates, set_status is 60% more efficient:**

**set_status approach** (~100 tokens):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "completed"
}
```

**update_task approach** (~150 tokens):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "completed"
}
```

**Recommendation**: Always use `set_status` for status-only updates

### When to Use Each Tool

**Use set_status**:
- ✅ Status-only updates
- ✅ Simple workflow transitions
- ✅ Bulk status changes (1-2 tasks)
- ✅ Any entity type (task/feature/project)

**Use update_task/update_feature/update_project**:
- ✅ Updating multiple fields (status + summary + tags)
- ✅ Complex updates requiring validation
- ✅ When entity type is known upfront

**Use bulk_update_tasks**:
- ✅ Updating 3+ tasks
- ✅ Batch operations
- ✅ 70-90% token savings

## Common Mistakes to Avoid

### ❌ Mistake 1: Using Wrong Status for Entity Type
```json
{
  "id": "task-uuid",
  "status": "archived"  // ❌ Tasks don't have "archived" status
}
```
**Problem**: Tasks use different status values than features/projects

### ✅ Solution: Check Entity Type Status Values
```json
{
  "id": "task-uuid",
  "status": "completed"  // ✅ Valid task status
}
```

### ❌ Mistake 2: Ignoring Blocking Task Warnings
```javascript
await set_status({ id: taskId, status: "completed" });
// ❌ Didn't check if task blocks others
```
**Problem**: May need to notify team or update dependent tasks

### ✅ Solution: Handle Blocking Warnings
```javascript
const result = await set_status({ id: taskId, status: "completed" });
if (result.data.blockingTasksCount > 0) {
  // Notify team, update dependent tasks, etc.
  console.warn(`Task blocks ${result.data.blockingTasksCount} other tasks`);
}
```

### ❌ Mistake 3: Using set_status for Multiple Fields
```javascript
await set_status({ id: taskId, status: "completed" });
await update_task({ id: taskId, summary: "Work complete" });
await update_task({ id: taskId, tags: "completed,tested" });
// ❌ Three separate calls
```

### ✅ Solution: Use update_task for Multiple Fields
```javascript
await update_task({
  id: taskId,
  status: "completed",
  summary: "Work complete",
  tags: "completed,tested"
});
// ✅ Single call with all updates
```

### ❌ Mistake 4: Completing Feature Before Tasks
```javascript
// Feature has incomplete tasks
await set_status({ id: featureId, status: "completed" });
// ❌ Marked feature complete with unfinished tasks
```

### ✅ Solution: Verify All Tasks Complete First
```javascript
const tasks = await get_feature_tasks({ featureId });
const allComplete = tasks.data.tasks.every(t => t.status === "completed");

if (!allComplete) {
  console.log("Cannot complete feature - some tasks still in progress");
} else {
  await set_status({ id: featureId, status: "completed" });
}
```

## Best Practices

1. **Use for Status-Only Updates**: Most efficient tool for changing just status
2. **Check Blocking Warnings**: Handle dependency notifications for completed tasks
3. **Verify Entity State**: Check current state before status transitions
4. **Use Bulk Operations**: For 3+ entities, use bulk_update_tasks
5. **Document Deferrals**: Update summary separately when deferring tasks
6. **Validate Transitions**: Ensure status transitions make logical sense
7. **Complete Tasks Before Features**: Finish all feature tasks before marking feature complete
8. **Auto-Detection Benefit**: No need to know entity type upfront

## Related Tools

- **update_task**: Update multiple task fields at once
- **update_feature**: Update feature metadata and status
- **update_project**: Update project metadata and status
- **bulk_update_tasks**: Efficiently update 3+ tasks
- **get_task**: Get current task status before updating
- **get_blocked_tasks**: Check for blocking dependencies
- **get_next_task**: Find next task after completing current one

## See Also

- Status Workflow Guide: `task-orchestrator://guidelines/status-transitions`
- Task Management Patterns: `task-orchestrator://guidelines/task-management`
- Efficiency Guide: `task-orchestrator://guidelines/usage-overview`
