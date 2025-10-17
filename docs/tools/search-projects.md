# search_projects Tool - Detailed Documentation

## Overview

Searches projects with flexible filtering, sorting, and pagination. Supports text search, status filtering, tag filtering, date range filtering, and configurable result ordering.

**Resource**: `task-orchestrator://docs/tools/search-projects`

## Key Concepts

### Search Capabilities
- **Text search**: Searches project names and summaries
- **Status filtering**: Filter by project status
- **Tag filtering**: Find projects with specific tags
- **Date range**: Filter by creation date
- **Multi-criteria**: Combine multiple filters

### Pagination
- Default: 20 results per page
- Maximum: 100 results per page
- Offset-based pagination for large result sets

### Performance Optimization
- Returns minimal fields for efficiency
- Use `get_project` for detailed information on specific results
- Summary field excluded from search results

## Parameter Reference

### Optional Parameters (all optional - no params returns all projects)

#### Search Filters
- **query** (string): Text to search in names and summaries
- **status** (enum): Filter by project status
  - `planning`
  - `in-development`
  - `completed`
  - `archived`
- **tag** (string): Filter by tag (case-insensitive, single tag)
- **createdAfter** (ISO-8601 date): Projects created after this date
- **createdBefore** (ISO-8601 date): Projects created before this date

#### Sorting
- **sortBy** (string, default: "modifiedAt"): Sort field
  - `createdAt` - When project was created
  - `modifiedAt` - When project was last updated (default)
  - `name` - Project name alphabetically
  - `status` - Project status
- **sortDirection** (string, default: "desc"): Sort order
  - `asc` - Ascending (oldest/A-Z first)
  - `desc` - Descending (newest/Z-A first)

#### Pagination
- **limit** (integer, default: 20, range: 1-100): Results per page
- **offset** (integer, default: 0, min: 0): Results to skip

## Common Usage Patterns

### Pattern 1: List All Projects
Get all projects ordered by most recently modified.

```json
{}
```

**When to use**:
- Project overview
- Quick project list
- Finding recently active projects

**Response**: Up to 20 most recently modified projects

**Token usage**: ~300-800 tokens (depends on result count)

### Pattern 2: Find Active Projects
Get all projects currently in development.

```json
{
  "status": "in-development",
  "sortBy": "modifiedAt",
  "sortDirection": "desc"
}
```

**When to use**:
- Daily standup prep
- Sprint planning
- Active work review

**Response**: In-development projects, most recently updated first

**Use case**: "What projects are we actively working on?"

### Pattern 3: Search by Name
Find projects matching text query.

```json
{
  "query": "mobile app",
  "limit": 10
}
```

**When to use**:
- Finding specific project
- Searching by keyword
- Locating related projects

**Searches**: Project names and summaries

**Example matches**:
- "Mobile App Redesign"
- "iOS Mobile App v2.0"
- "Backend API for Mobile App"

### Pattern 4: Filter by Tag
Find all projects with a specific tag.

```json
{
  "tag": "2025-q2",
  "sortBy": "name",
  "sortDirection": "asc"
}
```

**When to use**:
- Quarterly planning
- Technology-specific projects
- Client-specific projects
- Department work

**Common tag searches**:
- Time-based: `2025-q1`, `sprint-12`
- Technology: `backend`, `frontend`, `mobile`
- Client: `client-acme`, `internal`
- Priority: `high-priority`, `critical`

### Pattern 5: Recent Projects
Get projects created in the last 30 days.

```json
{
  "createdAfter": "2024-12-17T00:00:00Z",
  "sortBy": "createdAt",
  "sortDirection": "desc",
  "limit": 50
}
```

**When to use**:
- New project review
- Recent work analysis
- Onboarding overview

**Calculation**:
```javascript
const thirtyDaysAgo = new Date();
thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);
const createdAfter = thirtyDaysAgo.toISOString();
```

### Pattern 6: Completed Projects This Quarter
Find projects completed in a specific time range.

```json
{
  "status": "completed",
  "createdAfter": "2025-10-01T00:00:00Z",
  "createdBefore": "2025-12-31T23:59:59Z",
  "sortBy": "modifiedAt",
  "sortDirection": "desc"
}
```

**When to use**:
- Quarterly reviews
- Success metrics
- Retrospectives
- Team performance analysis

### Pattern 7: Paginated Results
Handle large result sets with pagination.

```json
{
  "status": "planning",
  "limit": 20,
  "offset": 0
}
```

**When to use**:
- Large project databases
- Browsing all results
- Building paginated UI

**Pagination workflow**:
```javascript
// Page 1
const page1 = await search_projects({ limit: 20, offset: 0 });

// Page 2
const page2 = await search_projects({ limit: 20, offset: 20 });

// Page 3
const page3 = await search_projects({ limit: 20, offset: 40 });

// Check if more pages exist
if (page1.data.hasMore) {
  console.log("More results available");
}
```

### Pattern 8: Combined Filters
Combine multiple criteria for precise targeting.

```json
{
  "query": "api",
  "status": "in-development",
  "tag": "backend",
  "createdAfter": "2025-01-01T00:00:00Z",
  "sortBy": "modifiedAt",
  "limit": 30
}
```

**When to use**:
- Specific project searches
- Team-focused queries
- Precise filtering needs

**Example**: "Find all in-development backend projects created this year with 'api' in the name"

## Response Structure

### Success Response
```json
{
  "success": true,
  "message": "Found 3 projects",
  "data": {
    "items": [
      {
        "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
        "name": "Mobile App Redesign",
        "status": "in-development",
        "createdAt": "2025-10-15T14:30:00Z",
        "modifiedAt": "2025-10-17T16:20:00Z",
        "tags": "mobile,redesign,2025-q2"
      },
      {
        "id": "a7854b2c-3d1e-4f6a-9c8d-1e2f3a4b5c6d",
        "name": "Backend API v3.0",
        "status": "planning",
        "createdAt": "2025-10-10T09:00:00Z",
        "modifiedAt": "2025-10-16T11:30:00Z",
        "tags": "backend,api,2025-q2"
      }
    ],
    "total": 3,
    "limit": 20,
    "offset": 0,
    "hasMore": false
  }
}
```

**Key fields**:
- `items`: Array of matching projects (minimal fields for performance)
- `total`: Total number of matching projects
- `limit`: Results per page
- `offset`: Results skipped
- `hasMore`: Whether more results available

**Note**: Summary excluded from results for performance. Use `get_project` for full details.

### Empty Results Response
```json
{
  "success": true,
  "message": "Found 0 projects",
  "data": {
    "items": [],
    "total": 0,
    "limit": 20,
    "offset": 0,
    "hasMore": false
  }
}
```

## Common Workflows

### Workflow 1: Daily Active Projects Review

```javascript
// Get all in-development projects
const activeProjects = await search_projects({
  status: "in-development",
  sortBy: "modifiedAt",
  sortDirection: "desc"
});

console.log(`Active Projects: ${activeProjects.data.total}`);

// Review each active project
for (const project of activeProjects.data.items) {
  console.log(`- ${project.name} (${project.status})`);
  console.log(`  Last updated: ${project.modifiedAt}`);
  console.log(`  Tags: ${project.tags}`);
}
```

### Workflow 2: Quarterly Planning Review

```javascript
// Find all projects for current quarter
const q2Projects = await search_projects({
  tag: "2025-q2",
  sortBy: "status",
  sortDirection: "asc",
  limit: 100
});

// Group by status
const byStatus = q2Projects.data.items.reduce((acc, p) => {
  acc[p.status] = (acc[p.status] || 0) + 1;
  return acc;
}, {});

console.log("Q2 2025 Projects:");
console.log(`- Planning: ${byStatus.planning || 0}`);
console.log(`- In Development: ${byStatus['in-development'] || 0}`);
console.log(`- Completed: ${byStatus.completed || 0}`);
```

### Workflow 3: Find and Open Specific Project

```javascript
// Search for project
const results = await search_projects({
  query: "customer portal",
  limit: 10
});

if (results.data.items.length === 0) {
  console.log("No projects found matching 'customer portal'");
} else if (results.data.items.length === 1) {
  // Single match, get full details
  const project = await get_project({
    id: results.data.items[0].id,
    includeSections: true,
    includeFeatures: true
  });
  console.log("Found project:", project.data.name);
} else {
  // Multiple matches, show list
  console.log(`Found ${results.data.total} matching projects:`);
  results.data.items.forEach((p, i) => {
    console.log(`${i + 1}. ${p.name} (${p.status})`);
  });
}
```

### Workflow 4: Archive Old Completed Projects

```javascript
// Find completed projects older than 90 days
const ninetyDaysAgo = new Date();
ninetyDaysAgo.setDate(ninetyDaysAgo.getDate() - 90);

const oldCompleted = await search_projects({
  status: "completed",
  createdBefore: ninetyDaysAgo.toISOString(),
  limit: 100
});

console.log(`Found ${oldCompleted.data.total} old completed projects`);

// Archive them
for (const project of oldCompleted.data.items) {
  await update_project({
    id: project.id,
    status: "archived"
  });
  console.log(`Archived: ${project.name}`);
}
```

### Workflow 5: Technology Stack Analysis

```javascript
// Find all backend projects
const backendProjects = await search_projects({
  tag: "backend",
  limit: 100
});

// Find all frontend projects
const frontendProjects = await search_projects({
  tag: "frontend",
  limit: 100
});

// Find all mobile projects
const mobileProjects = await search_projects({
  tag: "mobile",
  limit: 100
});

console.log("Technology Distribution:");
console.log(`- Backend: ${backendProjects.data.total} projects`);
console.log(`- Frontend: ${frontendProjects.data.total} projects`);
console.log(`- Mobile: ${mobileProjects.data.total} projects`);
```

## Sorting Strategies

### Sort by Modified Date (Default)
```json
{ "sortBy": "modifiedAt", "sortDirection": "desc" }
```
**Best for**: Finding recently active projects

### Sort by Created Date
```json
{ "sortBy": "createdAt", "sortDirection": "desc" }
```
**Best for**: Finding newest projects, chronological ordering

### Sort by Name
```json
{ "sortBy": "name", "sortDirection": "asc" }
```
**Best for**: Alphabetical browsing, organized lists

### Sort by Status
```json
{ "sortBy": "status", "sortDirection": "asc" }
```
**Best for**: Grouping by lifecycle stage

**Status sort order** (ascending):
1. archived
2. completed
3. in-development
4. planning

## Error Handling

### Invalid Status
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid status: active. Must be one of: planning, in-development, completed, archived"
  }
}
```

**Solution**: Use correct status values

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

**Solution**: Use ISO-8601 date format: `YYYY-MM-DDTHH:MM:SSZ`

### Invalid Sort Field
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid sortBy value: priority. Must be one of: createdAt, modifiedAt, name, status"
  }
}
```

**Solution**: Use valid sort fields (projects don't have priority field)

### Limit Out of Range
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "limit cannot exceed 100"
  }
}
```

**Solution**: Use limit between 1 and 100

## Best Practices

1. **Start Broad**: Begin with simple queries, add filters as needed
2. **Use Appropriate Limits**: Balance between completeness and performance
3. **Check hasMore**: Implement pagination for large result sets
4. **Combine Filters**: Use multiple criteria for precise results
5. **Sort Strategically**: Choose sort order based on use case
6. **Use get_project**: For detailed info on specific results
7. **Cache Results**: Store search results in working memory during session

## Performance Considerations

### Token Usage by Result Count

| Results | Approximate Tokens | Notes |
|---------|-------------------|-------|
| 0 | ~100 | Empty results |
| 1-5 | ~200-400 | Small result set |
| 10-20 | ~400-800 | Default page size |
| 50-100 | ~1000-2000 | Large page size |

### Optimization Strategies

1. **Limit Results**: Use smaller limits when possible
2. **Specific Filters**: Narrow results with multiple criteria
3. **Paginate**: Don't fetch all results at once
4. **Exclude Summary**: Summary not included in results (already optimized)

## Related Tools

- **get_project**: Retrieve full project details from search results
- **get_overview**: Alternative for system-wide project view
- **create_project**: Create new projects
- **update_project**: Modify project metadata
- **delete_project**: Remove projects

## See Also

- Search Patterns: `task-orchestrator://guidelines/search-patterns`
- Project Organization: `task-orchestrator://guidelines/project-organization`
- Tag Conventions: `task-orchestrator://guidelines/tagging`
