# search_tasks Tool - Detailed Documentation

## Overview

Search and filter tasks using flexible query parameters. Supports text search, status filtering, priority filtering, tag filtering, and sorting.

**Resource**: `task-orchestrator://docs/tools/search-tasks`

## Key Concepts

### Search Strategy
- **Text Search**: Searches title, summary, and description fields
- **Tag Filtering**: Filter by single or multiple tags
- **Status Filtering**: Filter by task lifecycle status
- **Priority Filtering**: Filter by urgency level
- **Feature Context**: Filter tasks by parent feature
- **Sorting**: Order results by various criteria

### Empty Query Behavior
Calling `search_tasks` with no parameters returns ALL tasks (use with caution on large datasets).

## Parameter Reference

### Optional Parameters
- **query** (string): Text to search in title, summary, description
- **status** (enum): pending | in-progress | completed | cancelled | deferred
- **priority** (enum): high | medium | low
- **tag** (string): Single tag to filter by
- **tags** (string): Comma-separated tags (matches ANY tag)
- **featureId** (UUID): Filter tasks belonging to specific feature
- **projectId** (UUID): Filter tasks belonging to specific project
- **sortBy** (enum): Field to sort by (title, priority, complexity, createdAt, modifiedAt)
- **sortDirection** (enum): asc | desc (default: desc)
- **limit** (integer): Maximum results to return
- **offset** (integer): Skip N results (for pagination)

## Common Search Patterns

### Pattern 1: Find High-Priority Pending Work
Get unfinished high-priority tasks to work on next.

```json
{
  "status": "pending",
  "priority": "high",
  "sortBy": "complexity",
  "sortDirection": "asc"
}
```

**Use case**: "What should I work on next?"
**Result**: High-priority pending tasks, easiest first

### Pattern 2: Search by Text
Find tasks matching specific keywords.

```json
{
  "query": "authentication",
  "status": "pending"
}
```

**Use case**: "Find auth-related tasks"
**Result**: Tasks with "authentication" in title/summary/description

### Pattern 3: Filter by Tags
Find tasks with specific technology or component tags.

```json
{
  "tag": "backend",
  "status": "pending"
}
```

**Use case**: "Show me pending backend tasks"
**Result**: All pending tasks tagged "backend"

### Pattern 4: Multiple Tag Search (OR logic)
Find tasks matching ANY of the specified tags.

```json
{
  "tags": "backend,database,api",
  "status": "pending"
}
```

**Use case**: "Show backend, database, OR api tasks"
**Result**: Tasks with ANY of those tags

### Pattern 5: Feature-Specific Tasks
Get all tasks for a specific feature.

```json
{
  "featureId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Use case**: "Show all tasks for OAuth feature"
**Result**: All tasks (any status) in that feature

### Pattern 6: Completed Work Review
Find recently completed tasks.

```json
{
  "status": "completed",
  "sortBy": "modifiedAt",
  "sortDirection": "desc",
  "limit": 10
}
```

**Use case**: "What was completed recently?"
**Result**: Last 10 completed tasks

### Pattern 7: Bug Triage
Find all open bugs, most severe first.

```json
{
  "tag": "task-type-bug",
  "status": "pending",
  "priority": "high",
  "sortBy": "createdAt",
  "sortDirection": "asc"
}
```

**Use case**: "Show oldest high-priority bugs"
**Result**: High-priority bugs, oldest first

### Pattern 8: Complex Work Identification
Find high-complexity tasks for planning.

```json
{
  "status": "pending",
  "sortBy": "complexity",
  "sortDirection": "desc",
  "limit": 5
}
```

**Use case**: "What are the hardest tasks?"
**Result**: Top 5 most complex pending tasks

### Pattern 9: Technology-Specific Work
Find all tasks for a specific technology.

```json
{
  "query": "kotlin",
  "tags": "backend,api"
}
```

**Use case**: "Show Kotlin backend work"
**Result**: Tasks mentioning Kotlin with backend/api tags

### Pattern 10: Project Overview
Get all tasks in a project by status.

```json
{
  "projectId": "project-uuid",
  "sortBy": "status"
}
```

**Use case**: "Show project progress"
**Result**: All project tasks grouped by status

## Advanced Filtering Combinations

### Priority + Complexity Filter
Find high-value, achievable work.

```json
{
  "status": "pending",
  "priority": "high",
  "sortBy": "complexity",
  "sortDirection": "asc",
  "limit": 3
}
```

**Logic**: High-priority tasks, simplest first (quick wins)

### Tag + Text + Status Filter
Precise filtering for specific work.

```json
{
  "query": "OAuth",
  "tag": "authentication",
  "status": "in-progress"
}
```

**Logic**: In-progress authentication tasks mentioning OAuth

### Feature + Priority + Status
Feature-specific high-priority work.

```json
{
  "featureId": "feature-uuid",
  "priority": "high",
  "status": "pending"
}
```

**Logic**: Critical feature tasks not yet started

## Pagination Pattern

For large result sets, use pagination.

```javascript
// Get first page (20 results)
const page1 = await search_tasks({
  status: "pending",
  limit: 20,
  offset: 0
});

// Get second page (next 20 results)
const page2 = await search_tasks({
  status: "pending",
  limit: 20,
  offset: 20
});

// Get third page
const page3 = await search_tasks({
  status: "pending",
  limit: 20,
  offset: 40
});
```

## Sorting Strategies

### By Priority + Complexity (Work Planning)
```json
{
  "status": "pending",
  "sortBy": "priority",
  "sortDirection": "desc"
}
```

**Then manually sort by complexity** for fine-grained prioritization.

### By Creation Date (FIFO)
```json
{
  "status": "pending",
  "sortBy": "createdAt",
  "sortDirection": "asc"
}
```

**Use case**: Work on oldest tasks first.

### By Modified Date (Recent Activity)
```json
{
  "sortBy": "modifiedAt",
  "sortDirection": "desc"
}
```

**Use case**: See recently updated tasks.

### By Title (Alphabetical)
```json
{
  "sortBy": "title",
  "sortDirection": "asc"
}
```

**Use case**: Organized browsing.

## Response Structure

```json
{
  "success": true,
  "message": "Found N tasks",
  "data": {
    "tasks": [
      {
        "id": "uuid",
        "title": "Task title",
        "summary": "Brief summary",
        "status": "pending",
        "priority": "high",
        "complexity": 6,
        "featureId": "uuid-or-null",
        "projectId": "uuid-or-null",
        "tags": ["tag1", "tag2"],
        "createdAt": "ISO-8601",
        "modifiedAt": "ISO-8601"
      }
    ],
    "count": 25,
    "query": {
      "status": "pending",
      "priority": "high"
    }
  }
}
```

## Common Workflows

### Workflow 1: Daily Work Planning
```javascript
// Find today's high-priority work
const urgentTasks = await search_tasks({
  status: "pending",
  priority: "high",
  sortBy: "complexity",
  sortDirection: "asc"
});

// Find in-progress work to continue
const ongoingTasks = await search_tasks({
  status: "in-progress",
  sortBy: "modifiedAt",
  sortDirection: "desc"
});

// Plan: Finish ongoing work, then tackle urgent tasks
```

### Workflow 2: Bug Triage
```javascript
// Find all open bugs
const bugs = await search_tasks({
  tag: "task-type-bug",
  status: "pending"
});

// Group by severity (using tags like "severity-critical")
const criticalBugs = await search_tasks({
  tags: "task-type-bug,severity-critical",
  status: "pending"
});

// Prioritize critical bugs
```

### Workflow 3: Feature Progress Check
```javascript
// Get all feature tasks
const allTasks = await search_tasks({
  featureId: "feature-uuid"
});

// Calculate progress
const completed = allTasks.data.tasks.filter(t => t.status === 'completed').length;
const total = allTasks.data.count;
const progress = (completed / total) * 100;

console.log(`Feature ${progress}% complete`);
```

### Workflow 4: Technology-Specific Onboarding
```javascript
// Find all React tasks for new frontend developer
const reactTasks = await search_tasks({
  query: "react",
  tags: "frontend,ui",
  status: "pending",
  sortBy: "complexity",
  sortDirection: "asc"
});

// Start with simplest React tasks
```

## Performance Considerations

### Efficient Queries
- **Specific filters**: Reduce result set size
- **Limit results**: Use `limit` parameter for large datasets
- **Status filtering**: Most effective filter (indexed)
- **Tag filtering**: Very efficient (indexed)

### Avoid Full Scans
```javascript
// ❌ Inefficient: Get everything
const all = await search_tasks({});

// ✅ Efficient: Filter by status
const pending = await search_tasks({ status: "pending" });
```

## Common Mistakes to Avoid

### ❌ Mistake 1: No Filters on Large Datasets
```json
{}
```
**Problem**: Returns ALL tasks (could be thousands)

### ✅ Solution: Always Filter
```json
{
  "status": "pending",
  "limit": 100
}
```

### ❌ Mistake 2: Confusing tag vs tags
```json
{
  "tag": "backend,api"
}
```
**Problem**: Looks for single tag "backend,api" (literal comma)

### ✅ Solution: Use tags for Multiple
```json
{
  "tags": "backend,api"
}
```

### ❌ Mistake 3: Not Using Pagination
Getting 500+ results in one call.

### ✅ Solution: Paginate Large Results
```json
{
  "limit": 50,
  "offset": 0
}
```

## Best Practices

1. **Filter First**: Start with status or feature filters
2. **Use Tags Effectively**: Consistent tagging enables powerful filtering
3. **Sort Strategically**: Choose sort order based on use case
4. **Paginate Large Results**: Limit results for performance
5. **Combine Filters**: Use multiple parameters for precise results
6. **Cache Frequent Searches**: Store common query results
7. **Use Specific Text Searches**: More specific queries = faster results

## Related Tools

- **get_task**: Retrieve full details after finding task
- **get_overview**: Quick view of all tasks without search
- **list_tags**: Discover available tags for filtering
- **get_blocked_tasks**: Find tasks blocked by dependencies
- **get_next_task**: Get AI-recommended next task

## See Also

- Task Management Patterns: `task-orchestrator://guidelines/task-management`
- Tag Conventions: `task-orchestrator://docs/tools/create-task#tag-conventions`
