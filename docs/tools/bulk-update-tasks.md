# bulk_update_tasks Tool - Detailed Documentation

## Overview

Updates multiple tasks in a single atomic operation. Provides 70-90% token savings compared to individual `update_task` calls through single network round-trip and optimized response format.

**Resource**: `task-orchestrator://docs/tools/bulk-update-tasks`

## Key Concepts

### Atomic Transaction
All task updates execute within a single database transaction. Either all updates succeed or detailed failure information is provided for each failed task.

### Token Efficiency
**Massive token savings** compared to individual updates:
- **1 task**: Use `update_task` (no benefit)
- **2 tasks**: Use `bulk_update_tasks` (~40% savings)
- **3+ tasks**: Use `bulk_update_tasks` (70-90% savings)
- **10 tasks**: Use `bulk_update_tasks` (~90% savings)

### Minimal Response Format
Returns only essential fields (id, status, modifiedAt) to minimize response tokens.

### Partial Update Support
Each task in the batch can update different fields. Only send fields being changed.

## Parameter Reference

### Required Parameters
- **tasks** (array): Array of task update objects (1-100 tasks max)

### Task Object Fields
Each task object in the array:
- **id** (UUID, required): Task identifier
- **title** (string, optional): New title
- **summary** (string, optional): New summary (max 500 chars)
- **description** (string, optional): New description
- **status** (enum, optional): pending | in-progress | completed | cancelled | deferred
- **priority** (enum, optional): high | medium | low
- **complexity** (integer, optional): 1-10
- **featureId** (UUID, optional): New feature association
- **tags** (string, optional): Comma-separated tags (replaces entire set)

## Common Usage Patterns

### Pattern 1: Sprint Completion
Mark multiple tasks as completed at end of sprint.

```json
{
  "tasks": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "status": "completed",
      "summary": "Implemented OAuth authentication with Google provider"
    },
    {
      "id": "661e8511-f30c-41d4-a716-557788990000",
      "status": "completed",
      "summary": "Added JWT token refresh mechanism"
    },
    {
      "id": "772f9622-g41d-52e5-b827-668899101111",
      "status": "completed",
      "summary": "Implemented session management with Redis"
    }
  ]
}
```

**Token savings**: ~75% compared to 3 individual `update_task` calls

### Pattern 2: Bulk Priority Adjustment
Reprioritize multiple tasks when urgent issue discovered.

```json
{
  "tasks": [
    {
      "id": "task-1-uuid",
      "priority": "high"
    },
    {
      "id": "task-2-uuid",
      "priority": "high"
    },
    {
      "id": "task-3-uuid",
      "priority": "medium"
    }
  ]
}
```

**When to use**: Emergency reprioritization, backlog grooming

### Pattern 3: Feature Reassignment
Move multiple tasks from one feature to another during reorganization.

```json
{
  "tasks": [
    {
      "id": "task-1-uuid",
      "featureId": "new-feature-uuid"
    },
    {
      "id": "task-2-uuid",
      "featureId": "new-feature-uuid"
    },
    {
      "id": "task-3-uuid",
      "featureId": "new-feature-uuid"
    }
  ]
}
```

**When to use**: Feature scope changes, work reorganization

### Pattern 4: Bulk Status Transition
Move related tasks through workflow together.

```json
{
  "tasks": [
    {
      "id": "task-1-uuid",
      "status": "in-progress"
    },
    {
      "id": "task-2-uuid",
      "status": "in-progress"
    },
    {
      "id": "task-3-uuid",
      "status": "in-progress"
    }
  ]
}
```

**When to use**: Starting work on related tasks, batch workflow transitions

### Pattern 5: Mixed Field Updates
Update different fields for different tasks in single operation.

```json
{
  "tasks": [
    {
      "id": "task-1-uuid",
      "status": "completed",
      "summary": "Implementation finished"
    },
    {
      "id": "task-2-uuid",
      "priority": "high",
      "complexity": 8
    },
    {
      "id": "task-3-uuid",
      "featureId": "feature-uuid",
      "tags": "backend,api,authentication"
    }
  ]
}
```

**When to use**: Batch maintenance, cleanup operations

### Pattern 6: Complexity Recalibration
Adjust complexity ratings after better understanding emerges.

```json
{
  "tasks": [
    {
      "id": "task-1-uuid",
      "complexity": 8  // Was 5, discovered more work
    },
    {
      "id": "task-2-uuid",
      "complexity": 3  // Was 7, simpler than expected
    },
    {
      "id": "task-3-uuid",
      "complexity": 9  // Was 6, blocking issues found
    }
  ]
}
```

**When to use**: Retrospectives, estimation improvements

### Pattern 7: Bulk Tag Addition
Add tags to multiple related tasks.

```javascript
// For each task, fetch current tags first, then add new tag
const taskIds = ["task-1", "task-2", "task-3"];
const updates = [];

for (const id of taskIds) {
  const task = await get_task({ id });
  const existingTags = task.data.tags.join(",");
  updates.push({
    id: id,
    tags: `${existingTags},new-tag`  // Append new tag
  });
}

await bulk_update_tasks({ tasks: updates });
```

**When to use**: Batch categorization, adding missing tags

## Response Structure

### Full Success
All tasks updated successfully.

```json
{
  "success": true,
  "message": "5 tasks updated successfully",
  "data": {
    "items": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "status": "completed",
        "modifiedAt": "2025-05-10T15:45:00Z"
      },
      {
        "id": "661e8511-f30c-41d4-a716-557788990000",
        "status": "completed",
        "modifiedAt": "2025-05-10T15:45:01Z"
      }
      // ... remaining tasks
    ],
    "updated": 5,
    "failed": 0
  }
}
```

### Partial Success
Some tasks updated, some failed.

```json
{
  "success": true,
  "message": "3 tasks updated successfully, 2 failed",
  "data": {
    "items": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "status": "completed",
        "modifiedAt": "2025-05-10T15:45:00Z"
      }
      // ... successful updates
    ],
    "updated": 3,
    "failed": 2,
    "failures": [
      {
        "index": 1,
        "id": "invalid-uuid",
        "error": {
          "code": "RESOURCE_NOT_FOUND",
          "details": "Task not found"
        }
      },
      {
        "index": 3,
        "id": "another-uuid",
        "error": {
          "code": "VALIDATION_ERROR",
          "details": "Invalid status value"
        }
      }
    ]
  }
}
```

### Complete Failure
All tasks failed to update.

```json
{
  "success": false,
  "message": "Failed to update any tasks",
  "error": {
    "code": "OPERATION_FAILED",
    "details": "All 5 tasks failed to update",
    "additionalData": {
      "failures": [
        {
          "index": 0,
          "id": "task-uuid",
          "error": {
            "code": "RESOURCE_NOT_FOUND",
            "details": "Task not found"
          }
        }
        // ... all failures
      ]
    }
  }
}
```

## Token Efficiency Analysis

### Comparison: Individual vs Bulk Updates

**Scenario**: Update status for 5 tasks

**Individual updates** (5 × `update_task`):
```
Request tokens: 5 × 150 = 750 tokens
Response tokens: 5 × 200 = 1000 tokens
Total: ~1750 tokens
```

**Bulk update** (1 × `bulk_update_tasks`):
```
Request tokens: 350 tokens
Response tokens: 300 tokens
Total: ~650 tokens
```

**Savings: 63% (1100 tokens saved)**

### Scaling Efficiency

| Tasks | Individual | Bulk | Savings | % Saved |
|-------|-----------|------|---------|---------|
| 2     | ~700      | ~420 | 280     | 40%     |
| 3     | ~1050     | ~500 | 550     | 52%     |
| 5     | ~1750     | ~650 | 1100    | 63%     |
| 10    | ~3500     | ~900 | 2600    | 74%     |
| 20    | ~7000     | ~1400| 5600    | 80%     |
| 50    | ~17500    | ~2800| 14700   | 84%     |

**Key takeaway**: Efficiency increases with task count

## Common Mistakes to Avoid

### ❌ Mistake 1: Using for Single Task
```json
{
  "tasks": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "status": "completed"
    }
  ]
}
```
**Problem**: No efficiency benefit for 1 task, adds complexity

### ✅ Solution: Use update_task for Single Task
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "completed"
}
```

### ❌ Mistake 2: Exceeding Maximum
```json
{
  "tasks": [
    // ... 150 tasks
  ]
}
```
**Problem**: Maximum 100 tasks per request

### ✅ Solution: Batch in Groups of 100
```javascript
const tasks = [...]; // 150 tasks
const batch1 = tasks.slice(0, 100);
const batch2 = tasks.slice(100);

await bulk_update_tasks({ tasks: batch1 });
await bulk_update_tasks({ tasks: batch2 });
```

### ❌ Mistake 3: Not Handling Partial Failures
```javascript
const result = await bulk_update_tasks({ tasks: updates });
// ❌ Assuming all succeeded without checking
console.log("All tasks updated!");
```

### ✅ Solution: Check for Failures
```javascript
const result = await bulk_update_tasks({ tasks: updates });

if (result.data.failed > 0) {
  console.log(`${result.data.updated} succeeded, ${result.data.failed} failed`);

  // Handle failures
  result.data.failures.forEach(failure => {
    console.error(`Task ${failure.id} failed: ${failure.error.details}`);
  });
}
```

### ❌ Mistake 4: Forgetting Existing Tags
```json
{
  "tasks": [
    {
      "id": "task-1",
      "tags": "new-tag"  // ❌ Replaces all existing tags
    }
  ]
}
```

### ✅ Solution: Preserve Existing Tags
```javascript
// Fetch current tags first
const task = await get_task({ id: "task-1" });
const currentTags = task.data.tags.join(",");

// Include all tags
await bulk_update_tasks({
  tasks: [{
    id: "task-1",
    tags: `${currentTags},new-tag`
  }]
});
```

### ❌ Mistake 5: No Field Updates
```json
{
  "tasks": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000"
      // ❌ No fields to update!
    }
  ]
}
```
**Problem**: Validation error - at least one field required

### ✅ Solution: Include Update Fields
```json
{
  "tasks": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "status": "completed"  // ✅ At least one field
    }
  ]
}
```

## Error Handling

### Task Not Found
```json
{
  "index": 2,
  "id": "invalid-uuid",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "Task not found"
  }
}
```

**Common causes**:
- Task deleted since last check
- Typo in UUID
- Wrong UUID copied

**Solution**: Verify task exists before bulk update

### Feature Not Found
```json
{
  "index": 1,
  "id": "task-uuid",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "Feature not found: feature-uuid"
  }
}
```

**Solution**: Validate feature IDs before associating

### Invalid Status
```json
{
  "index": 0,
  "id": "task-uuid",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid status at index 0: in_progres. Must be one of: pending, in-progress, completed, cancelled, deferred"
  }
}
```

**Solution**: Use exact status values: `pending`, `in-progress`, `completed`, `cancelled`, `deferred`

### Complexity Out of Range
```json
{
  "index": 3,
  "id": "task-uuid",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid complexity at index 3: 15. Must be an integer between 1 and 10."
  }
}
```

**Solution**: Ensure complexity is 1-10

## Best Practices

1. **Use for 2+ Tasks**: No benefit for single task updates
2. **Batch in Groups of 50-100**: Optimal balance of efficiency and manageability
3. **Handle Partial Failures**: Check failures array and retry
4. **Preserve Existing Tags**: Fetch current tags before updating
5. **Validate Before Bulk**: Verify UUIDs and values to avoid failures
6. **Use for Common Operations**: Status changes, priority adjustments, reassignments
7. **Monitor Performance**: Large batches may take longer
8. **Log Failures**: Track which tasks failed for debugging

## Decision Guide: When to Use

### ✅ Use bulk_update_tasks When:
- Updating 2+ tasks
- Sprint completion (marking multiple done)
- Feature reassignment (moving tasks between features)
- Priority rebalancing (adjusting multiple priorities)
- Workflow transitions (moving batch through states)
- Batch maintenance operations

### ❌ Don't Use bulk_update_tasks When:
- Updating single task (use `update_task`)
- Status-only update (use `set_status`)
- Need detailed individual responses
- Updates are unrelated (may want separate calls for tracking)

## Integration Patterns

### Pattern: Sprint Completion Workflow
```javascript
async function completeSprintTasks(sprintId) {
  // Get all completed work from sprint
  const tasks = await search_tasks({
    tags: `sprint-${sprintId}`,
    status: "in-progress"
  });

  // Build bulk update for completion
  const updates = tasks.data.tasks.map(task => ({
    id: task.id,
    status: "completed",
    summary: `Completed during sprint ${sprintId}`
  }));

  // Execute bulk update
  const result = await bulk_update_tasks({ tasks: updates });

  console.log(`Sprint ${sprintId}: ${result.data.updated} completed, ${result.data.failed} failed`);

  return result;
}
```

### Pattern: Feature Migration
```javascript
async function migrateTasksToFeature(taskIds, newFeatureId) {
  // Validate feature exists
  const feature = await get_feature({ id: newFeatureId });

  if (!feature.success) {
    throw new Error("Target feature not found");
  }

  // Build bulk update
  const updates = taskIds.map(id => ({
    id: id,
    featureId: newFeatureId
  }));

  // Execute with retry for failures
  const result = await bulk_update_tasks({ tasks: updates });

  if (result.data.failed > 0) {
    console.warn(`${result.data.failed} tasks failed migration`);
    // Retry failures individually if needed
  }

  return result;
}
```

### Pattern: Batch Status with Validation
```javascript
async function batchUpdateStatus(taskIds, newStatus) {
  // Validate all tasks exist first
  const validTasks = [];

  for (const id of taskIds) {
    const task = await get_task({ id });
    if (task.success) {
      validTasks.push({ id, status: newStatus });
    }
  }

  if (validTasks.length === 0) {
    throw new Error("No valid tasks to update");
  }

  // Execute bulk update with validated tasks
  return await bulk_update_tasks({ tasks: validTasks });
}
```

## Performance Considerations

### Network Efficiency
- **Single round-trip**: One request/response instead of N
- **Reduced overhead**: ~70-90% fewer network calls
- **Connection pooling**: Better utilization of HTTP connections

### Database Efficiency
- **Single transaction**: Atomic operation ensures consistency
- **Batch writes**: Database optimizes bulk operations
- **Reduced lock contention**: Single lock instead of N locks

### Token Efficiency
- **Request compression**: Shared structure amortized across tasks
- **Response minimalism**: Only id, status, modifiedAt returned
- **Metadata reduction**: Single error/metadata block for entire batch

## Related Tools

- **update_task**: Update single task (use for 1 task)
- **set_status**: Status-only updates (more efficient for status)
- **bulk_create_sections**: Efficient section creation
- **bulk_update_sections**: Efficient section updates
- **search_tasks**: Find tasks to update
- **get_overview**: Understand current state before bulk operations

## See Also

- Task Management Patterns: `task-orchestrator://guidelines/task-management`
- Bulk Operations Guide: `task-orchestrator://guidelines/bulk-operations`
- Performance Optimization: `task-orchestrator://guidelines/performance`
