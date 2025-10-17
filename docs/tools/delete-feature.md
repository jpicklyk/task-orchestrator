# delete_feature Tool - Detailed Documentation

## Overview

Deletes a feature by ID with options for handling associated tasks. Provides safety mechanisms to prevent accidental deletion of features with active work.

**Resource**: `task-orchestrator://docs/tools/delete-feature`

## Key Concepts

### Safety-First Design
- **Prevents accidental deletion**: Blocks deletion if feature has tasks (unless force=true)
- **Cascade option**: Optionally delete all associated tasks
- **Soft delete support**: Planned future feature (currently hard delete only)
- **Locking**: Prevents concurrent modifications during deletion

### Deletion Strategies

**Strategy 1: Safe Delete (default)**
- Requires feature to have no tasks
- Best for features created by mistake

**Strategy 2: Force Delete**
- Deletes feature even with tasks
- Tasks remain, but lose featureId association
- Use when restructuring project

**Strategy 3: Cascade Delete**
- Deletes feature AND all associated tasks
- Use when completely removing a feature and its work

## Parameter Reference

### Required Parameters
- **id** (UUID): Feature identifier

### Optional Parameters
- **cascade** (boolean, default: false): Delete associated tasks
- **force** (boolean, default: false): Delete even with active tasks
- **hardDelete** (boolean, default: false): Permanently remove (vs soft delete)

**Note**: Currently only hard delete is implemented. Soft delete is planned for future release.

## Usage Patterns

### Pattern 1: Safe Delete (No Tasks)
Delete a feature that has no associated tasks.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**When to use**:
- Feature created by mistake
- Feature no longer needed and has no tasks
- Cleaning up test data

**What happens**:
- ✅ Deletes if feature has no tasks
- ❌ Fails if feature has any tasks

### Pattern 2: Force Delete (Keep Tasks)
Delete feature but keep tasks (they become independent tasks).

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "force": true
}
```

**When to use**:
- Restructuring project organization
- Feature grouping no longer makes sense
- Want to keep tasks but remove feature

**What happens**:
- ✅ Deletes feature
- ✅ Tasks remain in database
- ✅ Tasks lose featureId association (become independent)

### Pattern 3: Cascade Delete (Remove Everything)
Delete feature AND all associated tasks.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "cascade": true
}
```

**When to use**:
- Completely removing a feature
- Feature work is obsolete
- Major project restructuring

**What happens**:
- ✅ Deletes feature
- ✅ Deletes all associated tasks
- ✅ Returns count of deleted tasks

**WARNING**: This is destructive. Cannot be undone.

### Pattern 4: Hard Delete (Permanent Removal)
Permanently remove from database (default behavior currently).

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "hardDelete": true,
  "cascade": true
}
```

**When to use**:
- Removing test data
- Compliance requirements (data deletion)
- Database cleanup

**Future**: When soft delete is implemented, this will be optional.

## Response Structure

### Successful Deletion
```json
{
  "success": true,
  "message": "Feature deleted successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "deleteType": "hard",
    "cascaded": false,
    "tasksAffected": 0
  }
}
```

### Successful Cascade Deletion
```json
{
  "success": true,
  "message": "Feature deleted with 8 associated tasks",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "deleteType": "hard",
    "cascaded": true,
    "tasksAffected": 8
  }
}
```

### Failed Deletion (Has Tasks)
```json
{
  "success": false,
  "message": "Cannot delete feature with associated tasks",
  "error": {
    "code": "DEPENDENCY_ERROR",
    "details": "Feature with ID ... has 8 associated tasks. Use 'force=true' to delete anyway, or 'cascade=true' to delete tasks as well."
  }
}
```

## Common Workflows

### Workflow 1: Check Before Delete
Verify feature state before deletion.

```javascript
// Check what we're deleting
const feature = await get_feature({
  id: featureId,
  includeTaskCounts: true
});

console.log(`Feature: ${feature.data.name}`);
console.log(`Tasks: ${feature.data.taskCounts.total}`);

// Decide deletion strategy
if (feature.data.taskCounts.total === 0) {
  // Safe delete
  await delete_feature({ id: featureId });
} else {
  // Ask user or use cascade
  await delete_feature({
    id: featureId,
    cascade: true  // or force: true
  });
}
```

### Workflow 2: Cascade Delete with Confirmation
Get task count before cascade delete.

```javascript
// Get task count
const feature = await get_feature({
  id: featureId,
  includeTaskCounts: true
});

const taskCount = feature.data.taskCounts.total;

if (taskCount > 0) {
  // User confirmation would go here
  console.log(`WARNING: This will delete ${taskCount} tasks`);

  // Proceed with cascade
  const result = await delete_feature({
    id: featureId,
    cascade: true
  });

  console.log(`Deleted feature and ${result.data.tasksAffected} tasks`);
}
```

### Workflow 3: Archive Instead of Delete
Safer alternative to deletion.

```javascript
// Instead of deleting, archive the feature
await update_feature({
  id: featureId,
  status: "archived"
});

// Can still access later if needed
const archivedFeature = await get_feature({ id: featureId });
```

### Workflow 4: Cleanup Completed Features
Delete old completed features.

```javascript
// Find completed features older than 6 months
const sixMonthsAgo = new Date();
sixMonthsAgo.setMonth(sixMonthsAgo.getMonth() - 6);

const features = await search_features({
  status: "completed",
  createdBefore: sixMonthsAgo.toISOString()
});

// Delete or archive each feature
for (const feature of features.data.items) {
  // Option 1: Archive
  await update_feature({
    id: feature.id,
    status: "archived"
  });

  // Option 2: Delete (if tasks are done)
  // await delete_feature({
  //   id: feature.id,
  //   force: true
  // });
}
```

## Safety Considerations

### Before Deleting

**Check these first**:
1. ✅ Feature status (completed vs in-progress)
2. ✅ Number of associated tasks
3. ✅ Task completion status
4. ✅ Whether archive is better than delete
5. ✅ Impact on project structure

### Deletion Impact

**What gets deleted**:
- ✅ Feature entity
- ✅ Associated tasks (if cascade=true)
- ✅ Feature sections (always deleted with feature)

**What remains**:
- ✅ Tasks (if cascade=false and force=true)
- ✅ Project (feature deletion doesn't affect parent project)

### Cannot Be Undone

**Hard delete is permanent**:
- ❌ No recovery mechanism
- ❌ Data is removed from database
- ❌ History is lost

**Best practice**: Archive instead of delete when possible.

## Common Mistakes to Avoid

### ❌ Mistake 1: Delete Without Checking Tasks
```json
{
  "id": "...",
  "cascade": true  // Oops, didn't check how many tasks!
}
```
**Problem**: Might delete important work.

### ✅ Solution: Check Task Count First
```javascript
const feature = await get_feature({
  id: featureId,
  includeTaskCounts: true
});
console.log(`Will delete ${feature.data.taskCounts.total} tasks`);
```

### ❌ Mistake 2: Using Delete for Cleanup
```json
{
  "id": "...",
  "cascade": true  // Wrong! Use archive instead
}
```
**Problem**: Loses historical data.

### ✅ Solution: Archive Instead
```json
{
  "id": "...",
  "status": "archived"
}
```

### ❌ Mistake 3: Forgetting Force Flag
```json
{
  "id": "..."  // Has tasks, will fail!
}
```
**Problem**: Deletion fails if feature has tasks.

### ✅ Solution: Use Force or Cascade
```json
{
  "id": "...",
  "force": true  // or cascade: true
}
```

### ❌ Mistake 4: Cascade Without Backup
**Problem**: Permanent data loss without recovery option.

### ✅ Solution: Export Before Delete
```javascript
// Export feature to markdown first
const markdown = await feature_to_markdown({ id: featureId });
// Save markdown as backup

// Then delete
await delete_feature({
  id: featureId,
  cascade: true
});
```

## Error Handling

### Feature Not Found
```json
{
  "success": false,
  "message": "Feature not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No feature exists with ID ..."
  }
}
```

**Cause**: Feature already deleted or wrong ID

### Has Associated Tasks (No Force/Cascade)
```json
{
  "success": false,
  "message": "Cannot delete feature with associated tasks",
  "error": {
    "code": "DEPENDENCY_ERROR",
    "details": "Feature has 8 associated tasks. Use force=true or cascade=true"
  }
}
```

**Solution**: Add `force: true` or `cascade: true`

### Resource Locked
```json
{
  "success": false,
  "message": "Resource is locked",
  "error": {
    "code": "RESOURCE_LOCKED",
    "details": "Feature is currently locked by another operation"
  }
}
```

**Solution**: Retry after brief delay

## Locking Behavior

This tool uses locking to prevent concurrent modifications:

- **Automatic locking**: Acquires lock on feature during deletion
- **Prevents conflicts**: Another operation can't modify feature during delete
- **Lock duration**: Held only during deletion operation
- **Cascade locking**: Also locks tasks if cascade=true

## Decision Tree

```
Do you need to delete the feature?
├─ NO → Consider update_feature to archive instead
└─ YES
   └─ Does feature have tasks?
      ├─ NO → Use basic delete { id: "..." }
      └─ YES
         └─ Do you need the tasks?
            ├─ YES → Use force delete { id: "...", force: true }
            │        (keeps tasks, removes feature)
            └─ NO → Use cascade delete { id: "...", cascade: true }
                    (deletes feature AND tasks)
```

## Best Practices

1. **Check First**: Always check task count before deletion
2. **Prefer Archive**: Use `update_feature` to archive instead of delete
3. **Export Before Delete**: Create markdown backup for important features
4. **Use Cascade Carefully**: Understand it deletes ALL associated tasks
5. **Force for Restructure**: Use force when reorganizing, not removing
6. **Confirm Destructive Ops**: Get user confirmation for cascade deletes
7. **Handle Lock Errors**: Retry after delay if locked

## Alternatives to Deletion

### Archive Instead (Recommended)
```javascript
await update_feature({
  id: featureId,
  status: "archived"
});
```

**Benefits**:
- ✅ Preserves data
- ✅ Reversible
- ✅ Maintains history
- ✅ Can search archived features

### Move to Different Project
```javascript
await update_feature({
  id: featureId,
  projectId: "archive-project-id"
});
```

## Related Tools

- **update_feature**: Archive instead of delete (recommended)
- **get_feature**: Check before deleting
- **search_features**: Find features to delete
- **feature_to_markdown**: Export before deletion
- **delete_task**: Delete individual tasks (if using force delete)

## See Also

- Feature Management Patterns: `task-orchestrator://guidelines/feature-management`
- Data Lifecycle: `task-orchestrator://guidelines/data-lifecycle`
- Archival Strategy: `task-orchestrator://guidelines/archival-strategy`
