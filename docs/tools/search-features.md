# search_features Tool - Detailed Documentation

## Overview

Searches features with flexible filtering, sorting, and pagination. Supports multiple filter combinations for precise feature discovery.

**Resource**: `task-orchestrator://docs/tools/search-features`

## Key Concepts

### Flexible Search
- **Text search**: Find features by name or description content
- **Filter combinations**: Combine multiple filters (status + priority + tag + project)
- **Date range filtering**: Find features created within specific timeframe
- **Sorting options**: Order by creation, modification, name, status, or priority
- **Pagination**: Handle large result sets efficiently

### Search Strategies

**Strategy 1: Text Search**
- Searches in feature names and descriptions
- Use when you know part of the name

**Strategy 2: Filter-Based Search**
- Precise filtering by status, priority, tags, project
- Use when you know specific criteria

**Strategy 3: Combined Search**
- Mix text search with filters
- Most powerful for precise discovery

## Parameter Reference

### Optional Parameters (all optional - no required parameters)

**Search Filters**:
- **query** (string): Text to search in names and descriptions
- **status** (enum): Filter by status (planning | in-development | completed | archived)
- **priority** (enum): Filter by priority (high | medium | low)
- **projectId** (UUID): Filter by parent project
- **tag** (string): Filter by tag (case-insensitive, single tag)
- **createdAfter** (ISO-8601): Filter by creation date after this timestamp
- **createdBefore** (ISO-8601): Filter by creation date before this timestamp

**Sorting & Pagination**:
- **sortBy** (string, default: modifiedAt): Sort field (createdAt | modifiedAt | name | status | priority)
- **sortDirection** (string, default: desc): Sort direction (asc | desc)
- **limit** (integer, default: 20): Results per page (1-100)
- **offset** (integer, default: 0): Skip N results

## Usage Patterns

### Pattern 1: Find All Active Features
Get features currently in development.

```json
{
  "status": "in-development",
  "sortBy": "priority",
  "sortDirection": "desc"
}
```

**Response**: Features in-development, sorted high to low priority
**When to use**: Daily standup, sprint planning, progress tracking

### Pattern 2: High Priority Features
Find urgent work across all statuses.

```json
{
  "priority": "high",
  "sortBy": "modifiedAt",
  "sortDirection": "desc"
}
```

**Response**: All high priority features, most recently modified first
**When to use**: Focus on urgent work, executive overview

### Pattern 3: Text Search
Find features by name or description content.

```json
{
  "query": "authentication",
  "limit": 10
}
```

**Response**: Features with "authentication" in name or description
**When to use**: You remember part of the feature name

### Pattern 4: Project Features
Get all features for a specific project.

```json
{
  "projectId": "550e8400-e29b-41d4-a716-446655440000",
  "sortBy": "status"
}
```

**Response**: All features in project, sorted by status
**When to use**: Project overview, feature breakdown

### Pattern 5: Features by Tag
Find features with specific tag.

```json
{
  "tag": "authentication",
  "status": "completed"
}
```

**Response**: Completed features tagged "authentication"
**When to use**: Technology audits, component review

### Pattern 6: Recent Features
Find features created recently.

```json
{
  "createdAfter": "2025-10-01T00:00:00Z",
  "sortBy": "createdAt",
  "sortDirection": "desc"
}
```

**Response**: Features created since October 1st, newest first
**When to use**: Recent work review, sprint retrospective

### Pattern 7: Combined Filters
Precise multi-filter search.

```json
{
  "status": "in-development",
  "priority": "high",
  "tag": "backend",
  "sortBy": "modifiedAt",
  "sortDirection": "desc",
  "limit": 5
}
```

**Response**: Top 5 high-priority backend features in development
**When to use**: Focused work lists, team assignment

### Pattern 8: Pagination
Handle large result sets.

```json
{
  "status": "completed",
  "limit": 20,
  "offset": 0
}
```

**Then for next page**:
```json
{
  "status": "completed",
  "limit": 20,
  "offset": 20
}
```

**When to use**: Browsing large feature lists

## Response Structure

### Standard Response
```json
{
  "success": true,
  "message": "Found 12 features",
  "data": {
    "items": [
      {
        "id": "uuid",
        "name": "User Authentication System",
        "status": "in-development",
        "priority": "high",
        "createdAt": "2025-10-01T14:30:00Z",
        "modifiedAt": "2025-10-17T10:15:00Z",
        "projectId": "project-uuid",
        "tags": "authentication, oauth, security"
      }
    ],
    "total": 12,
    "limit": 20,
    "offset": 0,
    "hasMore": false
  }
}
```

**Note**: Summary is excluded from search results for performance. Use `get_feature` to retrieve full details.

### Empty Results
```json
{
  "success": true,
  "message": "Found 0 features",
  "data": {
    "items": [],
    "total": 0,
    "limit": 20,
    "offset": 0,
    "hasMore": false
  }
}
```

### Paginated Results
```json
{
  "success": true,
  "message": "Found 45 features",
  "data": {
    "items": [ /* 20 features */ ],
    "total": 45,
    "limit": 20,
    "offset": 0,
    "hasMore": true  // More results available
  }
}
```

## Common Workflows

### Workflow 1: Daily Active Features
Get features currently being worked on.

```javascript
const activeFeatures = await search_features({
  status: "in-development",
  sortBy: "priority",
  sortDirection: "desc",
  limit: 10
});

console.log(`${activeFeatures.data.total} features in development`);
activeFeatures.data.items.forEach(f => {
  console.log(`- [${f.priority}] ${f.name}`);
});
```

### Workflow 2: Sprint Planning
Find high priority pending features.

```javascript
const plannedFeatures = await search_features({
  status: "planning",
  priority: "high",
  sortBy: "createdAt",
  sortDirection: "desc"
});

console.log(`${plannedFeatures.data.total} high-priority features ready for sprint`);
```

### Workflow 3: Technology Audit
Find all features using specific technology.

```javascript
const reactFeatures = await search_features({
  tag: "react",
  limit: 100
});

console.log(`Found ${reactFeatures.data.total} React features`);

const statusBreakdown = {};
reactFeatures.data.items.forEach(f => {
  statusBreakdown[f.status] = (statusBreakdown[f.status] || 0) + 1;
});

console.log("Status breakdown:", statusBreakdown);
```

### Workflow 4: Completion Report
Features completed this month.

```javascript
const firstOfMonth = new Date();
firstOfMonth.setDate(1);
firstOfMonth.setHours(0, 0, 0, 0);

const completedFeatures = await search_features({
  status: "completed",
  createdAfter: firstOfMonth.toISOString(),
  sortBy: "modifiedAt",
  sortDirection: "desc"
});

console.log(`${completedFeatures.data.total} features completed this month`);
```

### Workflow 5: Pagination Loop
Process all features matching criteria.

```javascript
let offset = 0;
const limit = 20;
let hasMore = true;

while (hasMore) {
  const results = await search_features({
    status: "completed",
    limit: limit,
    offset: offset
  });

  // Process this batch
  results.data.items.forEach(feature => {
    console.log(`Processing: ${feature.name}`);
  });

  // Check if more results
  hasMore = results.data.hasMore;
  offset += limit;
}
```

### Workflow 6: Find and Update
Search, then update matching features.

```javascript
// Find features to update
const features = await search_features({
  tag: "legacy",
  status: "planning"
});

// Archive old legacy features
for (const feature of features.data.items) {
  await update_feature({
    id: feature.id,
    status: "archived"
  });
}
```

## Sorting Strategies

### Sort by Modified Date (Default)
```json
{
  "sortBy": "modifiedAt",
  "sortDirection": "desc"
}
```
**Use**: Most recently updated features first (default behavior)

### Sort by Priority
```json
{
  "sortBy": "priority",
  "sortDirection": "desc"
}
```
**Use**: High priority features first

### Sort by Name
```json
{
  "sortBy": "name",
  "sortDirection": "asc"
}
```
**Use**: Alphabetical listing

### Sort by Status
```json
{
  "sortBy": "status",
  "sortDirection": "asc"
}
```
**Use**: Group by status (archived, completed, in-development, planning)

### Sort by Creation Date
```json
{
  "sortBy": "createdAt",
  "sortDirection": "desc"
}
```
**Use**: Newest features first

## Performance Considerations

### Result Limits
- **Default**: 20 results per request
- **Minimum**: 1 result
- **Maximum**: 100 results per request

**Recommendation**: Use default (20) for interactive browsing, higher limits (50-100) for batch processing.

### Token Usage
- **Per feature**: ~100-150 tokens (no summary)
- **20 features**: ~2000-3000 tokens
- **100 features**: ~10000-15000 tokens

**Optimization**: Use pagination to limit results, then call `get_feature` for details on specific features.

### Search Performance
- **Text search**: Searches name and description fields
- **Filter search**: Direct database filtering (faster)
- **Combined**: Applies text search first, then filters

## Common Mistakes to Avoid

### ❌ Mistake 1: Requesting Too Many Results
```json
{
  "limit": 1000  // Exceeds maximum!
}
```
**Problem**: Limit capped at 100.

### ✅ Solution: Use Pagination
```json
{
  "limit": 100
}
```

### ❌ Mistake 2: Wrong Date Format
```json
{
  "createdAfter": "2025-10-01"  // Missing time component
}
```
**Problem**: Must be full ISO-8601 format.

### ✅ Solution: Use Full ISO-8601
```json
{
  "createdAfter": "2025-10-01T00:00:00Z"
}
```

### ❌ Mistake 3: Invalid Sort Field
```json
{
  "sortBy": "complexity"  // Not a valid field for features
}
```
**Problem**: Features don't have complexity field.

### ✅ Solution: Use Valid Sort Fields
```json
{
  "sortBy": "priority"
}
```
Valid: createdAt, modifiedAt, name, status, priority

### ❌ Mistake 4: Case-Sensitive Tag Search
**Problem**: Tag search is case-insensitive, but exact match.

### ✅ Solution: Use Exact Tag Name
```javascript
// If tag is "Authentication", use that exact casing
const features = await search_features({
  tag: "Authentication"  // or "authentication" - case-insensitive
});
```

## Filter Combinations

### Effective Combinations

**Active High Priority Work**:
```json
{
  "status": "in-development",
  "priority": "high"
}
```

**Recent Backend Features**:
```json
{
  "tag": "backend",
  "createdAfter": "2025-10-01T00:00:00Z"
}
```

**Project Planning**:
```json
{
  "projectId": "project-uuid",
  "status": "planning"
}
```

**Completed This Sprint**:
```json
{
  "status": "completed",
  "createdAfter": "2025-10-01T00:00:00Z",
  "createdBefore": "2025-10-17T23:59:59Z"
}
```

## Error Handling

### Invalid UUID Format
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid projectId format. Must be a valid UUID"
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

### Invalid Date Format
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid createdAfter format. Must be in ISO-8601 format (e.g., 2025-05-10T14:30:00Z)"
  }
}
```

## Best Practices

1. **Start Broad, Refine**: Begin with fewer filters, add more to narrow results
2. **Use Appropriate Limits**: 20 for browsing, 50-100 for processing
3. **Leverage Sorting**: Sort by priority or modifiedAt for most relevant results
4. **Combine Filters**: Use multiple filters for precise targeting
5. **Handle Pagination**: Always check hasMore for large result sets
6. **Get Details Separately**: Use get_feature for full information on specific features
7. **Date Ranges**: Use ISO-8601 format with timezone
8. **Tag Filtering**: Remember case-insensitive but exact match

## Related Tools

- **get_feature**: Get full details for specific features from search results
- **get_overview**: Get hierarchical view of project/feature/task structure
- **create_feature**: Create new features
- **update_feature**: Modify features found via search
- **delete_feature**: Remove features found via search
- **search_tasks**: Search for tasks within features

## See Also

- Search Strategies: `task-orchestrator://guidelines/search-strategies`
- Feature Management: `task-orchestrator://guidelines/feature-management`
- Pagination Patterns: `task-orchestrator://guidelines/pagination`
