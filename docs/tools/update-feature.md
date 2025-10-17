# update_feature Tool - Detailed Documentation

## Overview

Updates an existing feature with partial updates. Only send fields you want to change for optimal efficiency.

**Resource**: `task-orchestrator://docs/tools/update-feature`

## Key Concepts

### Partial Updates
- Send only changed fields to minimize token usage
- Unspecified fields remain unchanged
- Optimized for incremental modifications
- Uses locking for concurrent update safety

### Common Update Scenarios
- **Status transitions**: Move feature through lifecycle
- **Priority adjustments**: Re-prioritize work
- **Project association**: Link to project structure
- **Tag updates**: Reorganize categorization
- **Summary updates**: Refine feature description

## Parameter Reference

### Required Parameters
- **id** (UUID): Feature identifier

### Optional Parameters (all are optional - send only what you want to change)
- **name** (string): New feature name
- **summary** (string, max 500 chars): New summary
- **description** (string): New detailed description
- **status** (enum): New status (planning | in-development | completed | archived)
- **priority** (enum): New priority (high | medium | low)
- **projectId** (UUID): New parent project (empty string to remove)
- **tags** (string): New comma-separated tags (replaces existing tags)

## Usage Patterns

### Pattern 1: Status Update Only
Most common update - moving feature through its lifecycle.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "in-development"
}
```

**When to use**:
- Starting work on a feature
- Completing a feature
- Archiving old features

**Alternative**: Use `set_status` for simpler status-only updates

### Pattern 2: Priority Adjustment
Re-prioritizing work based on changing needs.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "priority": "high"
}
```

**When to use**:
- Business priorities change
- Urgent issues discovered
- Release planning adjustments

### Pattern 3: Summary Update
Refining feature description after initial creation.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "summary": "Multi-provider OAuth with Google, GitHub, Facebook, plus email/password and 2FA"
}
```

**When to use**:
- Initial summary was too vague
- Feature scope expanded or contracted
- Adding clarity for team members

### Pattern 4: Tag Update
Reorganizing feature categorization.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "tags": "authentication,oauth,security,high-priority"
}
```

**When to use**:
- Refining organization system
- Adding new categorization
- Standardizing tag names

**IMPORTANT**: Tags parameter replaces all existing tags, not append.

### Pattern 5: Project Association
Moving feature to/from a project.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "projectId": "661e8511-f30c-41d4-a716-557788990000"
}
```

**To remove project association**:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "projectId": ""
}
```

**When to use**:
- Feature moved to different project
- Project structure reorganization
- Feature no longer tied to project

### Pattern 6: Multiple Field Update
Update several fields at once efficiently.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "completed",
  "priority": "medium",
  "summary": "OAuth authentication completed with Google, GitHub, and Facebook providers"
}
```

**When to use**:
- Feature completion (status + summary update)
- Major reorganization
- Batch updates during sprint planning

## Status Transition Guide

### Valid Status Transitions

```
planning → in-development
planning → archived (cancelled)

in-development → completed
in-development → planning (rollback)
in-development → archived (cancelled)

completed → archived (cleanup)
completed → in-development (reopened)

archived → planning (reactivation)
```

### Status Transition Examples

**Starting work**:
```json
{
  "id": "...",
  "status": "in-development"
}
```

**Completing feature**:
```json
{
  "id": "...",
  "status": "completed",
  "summary": "Completed authentication with all OAuth providers integrated"
}
```

**Archiving old feature**:
```json
{
  "id": "...",
  "status": "archived"
}
```

## Response Structure

Minimal response for efficiency (only essential fields):

```json
{
  "success": true,
  "message": "Feature updated successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "in-development",
    "modifiedAt": "2025-10-17T14:30:00Z"
  }
}
```

**Note**: Response only includes id, status, and modifiedAt for bandwidth optimization. Use `get_feature` if you need complete updated feature data.

## Common Workflows

### Workflow 1: Feature Status Progression
```javascript
// Start feature
await update_feature({
  id: featureId,
  status: "in-development"
});

// Later, complete feature
await update_feature({
  id: featureId,
  status: "completed",
  summary: "Completed OAuth authentication with all providers integrated and tested"
});

// Eventually, archive
await update_feature({
  id: featureId,
  status: "archived"
});
```

### Workflow 2: Sprint Planning Updates
```javascript
// Re-prioritize features for sprint
const highPriorityFeatures = [feature1Id, feature2Id, feature3Id];

for (const featureId of highPriorityFeatures) {
  await update_feature({
    id: featureId,
    priority: "high",
    tags: "sprint-1,high-priority"
  });
}
```

### Workflow 3: Project Restructuring
```javascript
// Move features to new project
const newProjectId = "661e8511-f30c-41d4-a716-557788990000";
const featureIds = ["id1", "id2", "id3"];

for (const featureId of featureIds) {
  await update_feature({
    id: featureId,
    projectId: newProjectId
  });
}
```

### Workflow 4: Feature Completion
```javascript
// Check if all tasks are done
const feature = await get_feature({
  id: featureId,
  includeTaskCounts: true
});

if (feature.data.taskCounts.byStatus.completed === feature.data.taskCounts.total) {
  // Mark feature as completed
  await update_feature({
    id: featureId,
    status: "completed",
    summary: `Completed ${feature.data.name} - all ${feature.data.taskCounts.total} tasks finished`
  });
}
```

## Common Mistakes to Avoid

### ❌ Mistake 1: Sending Unchanged Fields
```json
{
  "id": "...",
  "name": "User Authentication",
  "summary": "Same summary as before",
  "status": "in-development",
  "priority": "high",
  "tags": "authentication,oauth"
}
```
**Problem**: Wastes tokens sending unchanged data.

### ✅ Solution: Send Only Changed Fields
```json
{
  "id": "...",
  "status": "completed"
}
```

### ❌ Mistake 2: Forgetting Tags Are Replaced
```json
// Current tags: "authentication,oauth,security"
{
  "id": "...",
  "tags": "high-priority"  // Loses authentication,oauth,security!
}
```
**Problem**: Tags parameter replaces all existing tags.

### ✅ Solution: Include All Desired Tags
```json
{
  "id": "...",
  "tags": "authentication,oauth,security,high-priority"
}
```

Or retrieve current tags first:
```javascript
const feature = await get_feature({ id: featureId });
const updatedTags = [...feature.data.tags, "high-priority"].join(",");
await update_feature({ id: featureId, tags: updatedTags });
```

### ❌ Mistake 3: Invalid Project ID
```json
{
  "id": "...",
  "projectId": "non-existent-uuid"
}
```
**Problem**: Tool validates that project exists before updating.

### ✅ Solution: Verify Project Exists
```javascript
// Verify project exists first
const project = await get_project({ id: projectId });
if (project.success) {
  await update_feature({
    id: featureId,
    projectId: projectId
  });
}
```

### ❌ Mistake 4: Summary Exceeds Limit
```json
{
  "id": "...",
  "summary": "Very long summary that exceeds 500 characters..."  // Too long!
}
```
**Problem**: Summary limited to 500 characters.

### ✅ Solution: Keep Summary Concise
```json
{
  "id": "...",
  "summary": "Multi-provider OAuth with Google, GitHub, Facebook plus email/password login and 2FA"
}
```

## Locking Behavior

This tool uses locking to prevent concurrent modifications:

- **Automatic locking**: Tool acquires lock on feature during update
- **Prevents conflicts**: Two agents can't update same feature simultaneously
- **Lock duration**: Held only during update operation (milliseconds)
- **Lock failures**: Returns error if feature is locked by another operation

**Lock Error Example**:
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

**Solution**: Retry after brief delay or wait for other operation to complete.

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

### Invalid Status Value
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid status: in_progress. Must be one of: planning, in-development, completed, archived"
  }
}
```

### Project Not Found
```json
{
  "success": false,
  "message": "Project not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No project exists with ID ..."
  }
}
```

## Best Practices

1. **Minimal Updates**: Send only fields that changed
2. **Use set_status**: For status-only updates (simpler)
3. **Verify References**: Check project exists before setting projectId
4. **Preserve Tags**: Include all desired tags (replaces, not appends)
5. **Keep Summary Brief**: 500 character limit
6. **Update Summary on Completion**: Reflect final state
7. **Handle Lock Errors**: Retry after delay if locked
8. **Batch Similar Updates**: Group similar changes together

## Performance Tips

1. **Token Efficiency**: Only send changed fields (saves 70-90% tokens)
2. **Use set_status**: 50% fewer tokens for status-only updates
3. **Avoid Get-Then-Update**: Update directly when possible
4. **Batch Updates**: Update multiple features in sequence efficiently

## Related Tools

- **set_status**: Simpler status-only updates (recommended for status changes)
- **get_feature**: Retrieve current feature state
- **create_feature**: Create new features
- **delete_feature**: Remove features
- **search_features**: Find features to update
- **update_task**: Update tasks within feature

## See Also

- Feature Management Patterns: `task-orchestrator://guidelines/feature-management`
- Status Lifecycle: `task-orchestrator://guidelines/feature-lifecycle`
- Locking Mechanism: `task-orchestrator://guidelines/concurrency-control`
