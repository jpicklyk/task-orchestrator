# get_feature_tasks Tool - Detailed Documentation

## Overview

Retrieves all tasks associated with a specific feature with flexible filtering, sorting, and pagination. Optimized for viewing and managing tasks within a feature context.

**Resource**: `task-orchestrator://docs/tools/get-feature-tasks`

## Key Concepts

### Feature-Centric Task View
- **Scoped to feature**: Only returns tasks for specified feature
- **Rich filtering**: Filter by status, priority, complexity
- **Flexible sorting**: Order by various criteria
- **Pagination support**: Handle features with many tasks
- **Summary truncation**: Efficient token usage

### Comparison with get_feature
| Tool | get_feature_tasks | get_feature (includeTasks=true) |
|------|-------------------|--------------------------------|
| **Filtering** | ✅ Full (status, priority, complexity) | ❌ Limited |
| **Sorting** | ✅ Full (6 fields) | ❌ Limited |
| **Pagination** | ✅ Yes (limit/offset) | ✅ Limited (maxTaskCount) |
| **Summary Truncation** | ✅ Yes (auto at 100 chars) | ❌ Full text |
| **Use Case** | Task management | Feature overview |

**Use get_feature_tasks when**:
- Need to filter/sort tasks
- Working with many tasks
- Task-focused workflow

**Use get_feature with includeTasks when**:
- Need full feature context
- Want sections too
- Few tasks (< 10)

## Parameter Reference

### Required Parameters
- **featureId** (UUID): Feature identifier

### Optional Parameters

**Filtering**:
- **status** (enum): Filter by status (pending | in-progress | completed | cancelled | deferred)
- **priority** (enum): Filter by priority (high | medium | low)
- **complexityMin** (integer, 1-10): Minimum complexity threshold
- **complexityMax** (integer, 1-10): Maximum complexity threshold

**Sorting & Pagination**:
- **sortBy** (string, default: modifiedAt): Sort field (createdAt | modifiedAt | title | status | priority | complexity)
- **sortDirection** (string, default: desc): Sort direction (asc | desc)
- **limit** (integer, default: 20): Results per page (1-100)
- **offset** (integer, default: 0): Skip N results

## Usage Patterns

### Pattern 1: All Tasks for Feature
Get all tasks in a feature with default sorting.

```json
{
  "featureId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response**: All tasks, sorted by modifiedAt descending (most recently modified first)
**When to use**: Feature overview, task list display

### Pattern 2: Pending Tasks Only
Find tasks that haven't been started yet.

```json
{
  "featureId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "pending",
  "sortBy": "priority",
  "sortDirection": "desc"
}
```

**Response**: Pending tasks, high priority first
**When to use**: Task planning, work queue

### Pattern 3: In-Progress Tasks
See what's currently being worked on.

```json
{
  "featureId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "in-progress",
  "sortBy": "modifiedAt",
  "sortDirection": "desc"
}
```

**Response**: Active tasks, most recently updated first
**When to use**: Daily standup, progress tracking

### Pattern 4: High Complexity Tasks
Find challenging tasks requiring special attention.

```json
{
  "featureId": "550e8400-e29b-41d4-a716-446655440000",
  "complexityMin": 7,
  "sortBy": "complexity",
  "sortDirection": "desc"
}
```

**Response**: Tasks with complexity 7-10, most complex first
**When to use**: Resource planning, senior developer assignment

### Pattern 5: Quick Wins
Find low complexity pending tasks.

```json
{
  "featureId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "pending",
  "complexityMax": 3,
  "sortBy": "complexity",
  "sortDirection": "asc"
}
```

**Response**: Easy pending tasks, simplest first
**When to use**: New team members, quick progress

### Pattern 6: Complexity Range
Tasks within specific complexity band.

```json
{
  "featureId": "550e8400-e29b-41d4-a716-446655440000",
  "complexityMin": 4,
  "complexityMax": 6,
  "status": "pending"
}
```

**Response**: Medium complexity pending tasks (4-6)
**When to use**: Developer skill matching

### Pattern 7: Priority Queue
High priority tasks across all statuses.

```json
{
  "featureId": "550e8400-e29b-41d4-a716-446655440000",
  "priority": "high",
  "sortBy": "status",
  "sortDirection": "asc"
}
```

**Response**: High priority tasks grouped by status
**When to use**: Urgent work identification

### Pattern 8: Pagination
Handle features with many tasks.

```json
{
  "featureId": "550e8400-e29b-41d4-a716-446655440000",
  "limit": 10,
  "offset": 0
}
```

**Next page**:
```json
{
  "featureId": "550e8400-e29b-41d4-a716-446655440000",
  "limit": 10,
  "offset": 10
}
```

**When to use**: Large features (15+ tasks)

## Response Structure

### Standard Response
```json
{
  "success": true,
  "message": "Found 8 tasks for feature: User Authentication System",
  "data": {
    "featureId": "550e8400-e29b-41d4-a716-446655440000",
    "featureName": "User Authentication System",
    "items": [
      {
        "id": "task-uuid",
        "title": "Implement OAuth Google provider",
        "summary": "Add Google OAuth 2.0 authentication flow with token management",
        "status": "completed",
        "priority": "high",
        "complexity": 6,
        "createdAt": "2025-10-01T14:30:00Z",
        "modifiedAt": "2025-10-15T10:20:00Z",
        "tags": "oauth, google, authentication"
      }
    ],
    "total": 8,
    "limit": 20,
    "offset": 0,
    "hasMore": false
  }
}
```

### Empty Results
```json
{
  "success": true,
  "message": "No tasks found for feature: User Authentication System",
  "data": {
    "featureId": "550e8400-e29b-41d4-a716-446655440000",
    "featureName": "User Authentication System",
    "items": [],
    "total": 0,
    "limit": 20,
    "offset": 0,
    "hasMore": false
  }
}
```

### Truncated Summary
For tasks with summaries > 100 characters:
```json
{
  "summary": "This is a very long summary that exceeds the 100 character limit and will be truncated for ef..."
}
```

## Common Workflows

### Workflow 1: Task Progress Dashboard
Display feature progress with task breakdown.

```javascript
// Get all tasks
const tasks = await get_feature_tasks({
  featureId: featureId
});

// Calculate progress
const total = tasks.data.total;
const completed = tasks.data.items.filter(t => t.status === 'completed').length;
const inProgress = tasks.data.items.filter(t => t.status === 'in-progress').length;

console.log(`Feature: ${tasks.data.featureName}`);
console.log(`Progress: ${completed}/${total} completed (${(completed/total*100).toFixed(1)}%)`);
console.log(`Active: ${inProgress} tasks in progress`);
```

### Workflow 2: Next Task Selection
Find the next task to work on.

```javascript
// Get pending high priority tasks
const tasks = await get_feature_tasks({
  featureId: featureId,
  status: "pending",
  priority: "high",
  sortBy: "complexity",
  sortDirection: "asc"
});

if (tasks.data.items.length > 0) {
  const nextTask = tasks.data.items[0];
  console.log(`Next task: ${nextTask.title} (complexity: ${nextTask.complexity})`);
} else {
  console.log("No high priority pending tasks");
}
```

### Workflow 3: Feature Completion Check
Verify all tasks are completed before completing feature.

```javascript
// Get incomplete tasks
const incompleteTasks = await get_feature_tasks({
  featureId: featureId,
  status: "pending"  // Or check in-progress too
});

if (incompleteTasks.data.total === 0) {
  console.log("All tasks completed! Feature ready to complete.");

  await set_status({
    id: featureId,
    status: "completed"
  });
} else {
  console.log(`${incompleteTasks.data.total} tasks still pending`);
  incompleteTasks.data.items.forEach(t => {
    console.log(`- ${t.title}`);
  });
}
```

### Workflow 4: Team Task Assignment
Distribute tasks by complexity.

```javascript
// Get pending tasks by complexity
const easyTasks = await get_feature_tasks({
  featureId: featureId,
  status: "pending",
  complexityMax: 3
});

const mediumTasks = await get_feature_tasks({
  featureId: featureId,
  status: "pending",
  complexityMin: 4,
  complexityMax: 6
});

const hardTasks = await get_feature_tasks({
  featureId: featureId,
  status: "pending",
  complexityMin: 7
});

console.log(`Easy: ${easyTasks.data.total} tasks (junior devs)`);
console.log(`Medium: ${mediumTasks.data.total} tasks (mid-level devs)`);
console.log(`Hard: ${hardTasks.data.total} tasks (senior devs)`);
```

### Workflow 5: Stale Task Detection
Find tasks not modified recently.

```javascript
// Get all tasks sorted by modification date
const tasks = await get_feature_tasks({
  featureId: featureId,
  status: "in-progress",
  sortBy: "modifiedAt",
  sortDirection: "asc"  // Oldest first
});

const weekAgo = new Date();
weekAgo.setDate(weekAgo.getDate() - 7);

const staleTasks = tasks.data.items.filter(t =>
  new Date(t.modifiedAt) < weekAgo
);

if (staleTasks.length > 0) {
  console.log(`${staleTasks.length} tasks not updated in 7+ days:`);
  staleTasks.forEach(t => {
    console.log(`- ${t.title} (last update: ${t.modifiedAt})`);
  });
}
```

### Workflow 6: Export Task List
Generate task list for reporting.

```javascript
// Get all tasks
const tasks = await get_feature_tasks({
  featureId: featureId,
  sortBy: "status",
  limit: 100
});

console.log(`# ${tasks.data.featureName} - Task List\n`);

tasks.data.items.forEach(task => {
  console.log(`- [${task.status}] ${task.title} (${task.priority} priority, complexity ${task.complexity})`);
});
```

## Sorting Strategies

### By Modified Date (Default)
```json
{"sortBy": "modifiedAt", "sortDirection": "desc"}
```
**Use**: See most recently updated tasks first

### By Priority
```json
{"sortBy": "priority", "sortDirection": "desc"}
```
**Use**: High priority tasks first

### By Complexity
```json
{"sortBy": "complexity", "sortDirection": "asc"}
```
**Use**: Easy tasks first (or desc for hard tasks first)

### By Status
```json
{"sortBy": "status", "sortDirection": "asc"}
```
**Use**: Group tasks by status (cancelled, completed, deferred, in-progress, pending)

### By Title
```json
{"sortBy": "title", "sortDirection": "asc"}
```
**Use**: Alphabetical listing

### By Creation Date
```json
{"sortBy": "createdAt", "sortDirection": "desc"}
```
**Use**: Newest tasks first

## Performance Considerations

### Token Usage
- **Per task**: ~150-200 tokens (with truncated summary)
- **20 tasks**: ~3000-4000 tokens
- **100 tasks**: ~15000-20000 tokens

**Optimization**: Use pagination and filtering to reduce result set

### Summary Truncation
- **Automatic**: Summaries > 100 chars truncated to 97 chars + "..."
- **Token savings**: 50-70% for long summaries
- **Trade-off**: Lose full summary context

**When full summary needed**: Use `get_task` on specific tasks

## Common Mistakes to Avoid

### ❌ Mistake 1: Wrong Parameter Name
```json
{
  "id": "..."  // Wrong! Should be featureId
}
```

### ✅ Solution: Use featureId
```json
{
  "featureId": "..."
}
```

### ❌ Mistake 2: Invalid Complexity Range
```json
{
  "complexityMin": 7,
  "complexityMax": 3  // Min > Max!
}
```

### ✅ Solution: Min <= Max
```json
{
  "complexityMin": 3,
  "complexityMax": 7
}
```

### ❌ Mistake 3: Requesting Too Many Results
```json
{
  "limit": 500  // Exceeds maximum
}
```

### ✅ Solution: Use Pagination
```json
{
  "limit": 100  // Maximum
}
```

### ❌ Mistake 4: Wrong Sort Field
```json
{
  "sortBy": "name"  // Tasks use "title", not "name"
}
```

### ✅ Solution: Use Valid Field
```json
{
  "sortBy": "title"
}
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

### Invalid Complexity Range
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "complexityMin cannot be greater than complexityMax"
  }
}
```

### Invalid Status
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid status: in_progress. Must be one of: pending, in-progress, completed, cancelled, deferred"
  }
}
```

## Best Practices

1. **Filter Before Sorting**: Apply status/priority filters first to reduce dataset
2. **Use Appropriate Limits**: 20 for display, 50-100 for processing
3. **Leverage Sorting**: Sort by priority or complexity for task selection
4. **Check Total Count**: Use for progress calculations
5. **Handle Empty Results**: Gracefully handle features with no tasks
6. **Pagination for Large Features**: Use offset/limit for features with 15+ tasks
7. **Complexity Filtering**: Use for task assignment by skill level
8. **Combine Filters**: Status + priority + complexity for precise targeting

## Filter Combinations

### Active High Priority Work
```json
{
  "featureId": "...",
  "status": "in-progress",
  "priority": "high"
}
```

### Easy Pending Tasks
```json
{
  "featureId": "...",
  "status": "pending",
  "complexityMax": 3,
  "sortBy": "priority",
  "sortDirection": "desc"
}
```

### Complex Unstarted Work
```json
{
  "featureId": "...",
  "status": "pending",
  "complexityMin": 7,
  "priority": "high"
}
```

## Related Tools

- **get_feature**: Get feature details with basic task list
- **get_task**: Get full details for specific tasks
- **search_tasks**: Search tasks across all features
- **create_task**: Add tasks to feature
- **update_task**: Modify tasks in feature
- **set_status**: Update task status

## See Also

- Feature Management: `task-orchestrator://guidelines/feature-management`
- Task Management: `task-orchestrator://guidelines/task-management`
- Pagination Patterns: `task-orchestrator://guidelines/pagination`
