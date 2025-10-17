# update_task Tool - Detailed Documentation

## Overview

Updates an existing task with new values for specified fields. Supports partial updates (only send fields being changed) and includes concurrency protection via locking system.

**Resource**: `task-orchestrator://docs/tools/update-task`

## Key Concepts

### Partial Update Pattern
Only send the fields you want to change - unchanged fields are automatically preserved from the existing task. This reduces token usage and prevents accidental overwriting of data.

### Locking System
The tool uses a locking mechanism to prevent concurrent modifications. If another operation is modifying the same task, the update will wait or fail gracefully.

### Tag Replacement Behavior
**CRITICAL**: When updating tags, the entire tag set is replaced. To add a tag, you must include all existing tags plus the new one.

## Parameter Reference

### Required Parameters
- **id** (UUID): Task identifier

### Optional Parameters
- **title** (string): New task title
- **summary** (string, max 500 chars): Brief summary of what was accomplished (agent-generated)
- **description** (string): Detailed description of what needs to be done (user-provided)
- **status** (enum): pending | in-progress | completed | cancelled | deferred
- **priority** (enum): high | medium | low
- **complexity** (integer, 1-10): Task complexity rating
- **featureId** (UUID): New feature association (or empty string to orphan)
- **projectId** (UUID): New project association
- **tags** (string): Comma-separated tags (replaces entire tag set)

## Common Usage Patterns

### Pattern 1: Start Work on Task
Mark a task as in-progress when you begin working on it.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "in-progress"
}
```

**When to use**: Beginning work on a pending task
**Best practice**: Only update status, let other fields remain unchanged

### Pattern 2: Complete Task
Mark task as completed, typically with a summary of what was accomplished.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "completed",
  "summary": "Implemented JWT-based OAuth authentication with Google provider integration. Added token refresh logic and session management. All 15 unit tests passing."
}
```

**When to use**: Task work is finished and meets acceptance criteria
**Best practice**: Include concise summary (300-500 chars) of what was accomplished

### Pattern 3: Update Priority/Complexity
Adjust task priority or complexity as understanding improves.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "priority": "high",
  "complexity": 8
}
```

**When to use**:
- Initial estimate was wrong
- Requirements changed
- Blocker discovered
- Urgency increased

### Pattern 4: Associate with Feature
Move an orphaned task into a feature.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "featureId": "661e8511-f30c-41d4-a716-557788990000"
}
```

**When to use**: Organizing orphaned tasks discovered via `get_overview`

### Pattern 5: Add Tags (Careful!)
Add a new tag to existing tags.

```javascript
// Step 1: Get current task to see existing tags
const task = await get_task({ id: "550e8400-e29b-41d4-a716-446655440000" });
// task.tags = ["authentication", "backend"]

// Step 2: Update with ALL tags (existing + new)
await update_task({
  id: "550e8400-e29b-41d4-a716-446655440000",
  tags: "authentication,backend,oauth,security"  // Include all existing tags
});
```

**When to use**: Adding tags to categorize tasks
**Critical**: Must include existing tags, or they'll be removed

### Pattern 6: Defer Task
Postpone a task that can't be completed now.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "deferred",
  "summary": "Deferring due to blocked dependency on database schema migration (Task T-123). Will resume after migration completes."
}
```

**When to use**: Task blocked by external dependency or deprioritized

### Pattern 7: Reassign to Different Feature
Move task from one feature to another.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "featureId": "772f9622-g41d-52e5-b827-668899101111"
}
```

**When to use**: Task scope changed or feature reorganization

### Pattern 8: Orphan Task (Remove from Feature)
Remove task from its feature without deleting it.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "featureId": ""
}
```

**When to use**: Task doesn't belong to feature after all

## Response Structure

Minimal response optimized for efficiency (only essential fields):

```json
{
  "success": true,
  "message": "Task updated successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "completed",
    "modifiedAt": "2025-05-10T15:45:00Z"
  }
}
```

**Why minimal?** To reduce token usage and improve performance. Use `get_task` if you need full details.

## Status Transitions

### Valid Transitions
```
pending → in-progress → completed
         ↓
         cancelled / deferred
```

### Transition Guidelines
- **pending → in-progress**: Starting work
- **in-progress → completed**: Work finished successfully
- **in-progress → deferred**: Work blocked or postponed
- **in-progress → cancelled**: Work abandoned
- **deferred → in-progress**: Resuming deferred work
- **completed → in-progress**: Reopening completed task (avoid when possible)

## Common Mistakes to Avoid

### ❌ Mistake 1: Sending Unchanged Fields
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "Same title as before",
  "description": "Same description as before",
  "status": "in-progress"  // Only this is changing
}
```
**Problem**: Wastes tokens and bandwidth

### ✅ Solution: Send Only Changed Fields
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "in-progress"
}
```

### ❌ Mistake 2: Forgetting Existing Tags
```json
// Task currently has tags: ["authentication", "backend", "api"]
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "tags": "security"  // ❌ This REPLACES all tags, doesn't add
}
// Result: Tags are now only ["security"] - lost the others!
```

### ✅ Solution: Include All Tags
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "tags": "authentication,backend,api,security"  // All tags including new one
}
```

### ❌ Mistake 3: Invalid Feature ID
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "featureId": "non-existent-uuid"
}
```
**Problem**: Tool validates feature existence and will reject invalid IDs

### ✅ Solution: Verify Feature Exists
```javascript
// Verify feature first
const feature = await get_feature({ id: "661e8511-f30c-41d4-a716-557788990000" });
if (feature.success) {
  await update_task({
    id: "550e8400-e29b-41d4-a716-446655440000",
    featureId: "661e8511-f30c-41d4-a716-557788990000"
  });
}
```

### ❌ Mistake 4: Summary Too Long
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "summary": "This is a very long summary that exceeds 500 characters and will be rejected..."  // 600+ chars
}
```
**Problem**: Summary field limited to 500 characters

### ✅ Solution: Keep Summary Concise
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "summary": "Implemented OAuth authentication with Google provider. Added JWT token management and refresh logic. All 15 tests passing."  // ~140 chars
}
```

## Efficiency Comparison

### update_task vs set_status

For status-only updates, `set_status` is more efficient:

**update_task approach** (~150 tokens):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "completed"
}
```

**set_status approach** (~100 tokens):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "completed"
}
```

**Recommendation**: Use `set_status` for status-only updates, use `update_task` when updating multiple fields.

### update_task vs bulk_update_tasks

For updating 3+ tasks, `bulk_update_tasks` is more efficient:

**Individual updates** (~450 tokens for 3 tasks):
```javascript
await update_task({ id: "task-1", status: "completed" });
await update_task({ id: "task-2", status: "completed" });
await update_task({ id: "task-3", status: "completed" });
```

**Bulk update** (~200 tokens for 3 tasks):
```json
{
  "tasks": [
    { "id": "task-1", "status": "completed" },
    { "id": "task-2", "status": "completed" },
    { "id": "task-3", "status": "completed" }
  ]
}
```

**70-90% token savings!**

## Error Handling

### Task Not Found
```json
{
  "success": false,
  "message": "Failed to retrieve task",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No task exists with ID ..."
  }
}
```

**Common causes**:
- Task was deleted
- Wrong UUID
- Typo in ID

**Solution**: Verify task exists with `get_task` or `search_tasks`

### Invalid Status Value
```json
{
  "success": false,
  "message": "Invalid status: in_progres. Must be one of: pending, in-progress, completed, cancelled, deferred",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Common causes**:
- Typo in status value
- Using underscores instead of hyphens

**Solution**: Use exact values: `pending`, `in-progress`, `completed`, `cancelled`, `deferred`

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

**Solution**: List features with `search_features` or `get_overview` first

## Best Practices

1. **Send Only Changed Fields**: Minimize token usage and prevent accidental changes
2. **Use set_status for Status-Only**: More efficient than update_task
3. **Use bulk_update_tasks for 3+ Tasks**: 70-90% token savings
4. **Include Summary on Completion**: Document what was accomplished
5. **Preserve Existing Tags**: Include all tags when adding new ones
6. **Verify Feature IDs**: Check feature exists before associating
7. **Keep Summaries Under 300 Chars**: Leave room for details in sections
8. **Update modifiedAt Automatically**: Tool handles timestamp updates

## Integration Patterns

### Pattern: Complete Task with Summary
```javascript
// Complete task and document results
await update_task({
  id: taskId,
  status: "completed",
  summary: "Implemented authentication API with OAuth Google provider. Added JWT token refresh logic. All 12 unit tests + 5 integration tests passing."
});
```

### Pattern: Escalate Priority
```javascript
// Increase priority when blocker discovered
await update_task({
  id: taskId,
  priority: "high",
  summary: "Escalating - discovered critical security vulnerability in current implementation. Needs immediate attention."
});
```

### Pattern: Organize Orphaned Tasks
```javascript
// Move orphaned task into feature
const overview = await get_overview();
const orphanedTasks = overview.data.orphanedTasks;

for (const task of orphanedTasks) {
  if (task.tags.includes("authentication")) {
    await update_task({
      id: task.id,
      featureId: authFeatureId  // Discovered via overview
    });
  }
}
```

## Related Tools

- **set_status**: More efficient for status-only updates
- **bulk_update_tasks**: More efficient for updating 3+ tasks
- **get_task**: Retrieve current task state before updating
- **create_task**: Create new tasks
- **delete_task**: Remove tasks
- **update_section**: Update task content (sections)
- **update_section_text**: Update specific text within sections

## See Also

- Task Management Patterns: `task-orchestrator://guidelines/task-management`
- Status Workflow Guide: `task-orchestrator://guidelines/status-transitions`
- Efficiency Guide: `task-orchestrator://guidelines/usage-overview`
