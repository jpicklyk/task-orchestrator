# list_tags Tool - Detailed Documentation

## Overview

Discovers all unique tags across your entire workspace with usage statistics and entity type breakdowns. Essential for tag discovery, taxonomy management, and understanding tag usage patterns before searching or filtering entities.

**Resource**: `task-orchestrator://docs/tools/list-tags`

## Key Concepts

### Tag Discovery Before Search
list_tags is the foundation for effective tag-based filtering:
- **Discover available tags**: See what tags exist before using them in searches
- **Understand usage**: Know which tags are common vs rare
- **Find inconsistencies**: Detect similar tags that should be standardized
- **Plan taxonomy**: Analyze tag usage patterns for cleanup

**Workflow**: Always run list_tags before search_tasks/search_features to discover available filter values.

### Usage Statistics
Each tag includes:
- **Total count**: Number of entities using the tag
- **Entity type breakdown**: Count per entity type (PROJECT, FEATURE, TASK, TEMPLATE)
- **Sorting options**: Sort by usage count or alphabetically

### Why Tag Statistics Matter
- **High usage tags**: Core organizational categories (e.g., "backend" with 50+ uses)
- **Low usage tags**: Potential typos or deprecated tags (e.g., "temp-test" with 1 use)
- **Entity distribution**: Understanding where tags are used (tasks vs features vs projects)

## Parameter Reference

### Optional Parameters
All parameters are optional. Default behavior returns all tags sorted by usage count (most used first).

- **entityTypes** (array of strings): Filter tags by entity types
  - Valid values: PROJECT, FEATURE, TASK, TEMPLATE
  - Default: All entity types
  - Format: Array of strings (not comma-separated)
  - Example: `["TASK", "FEATURE"]` to see only task and feature tags

- **sortBy** (string): Sort results by field
  - Valid values: `count` | `name`
  - Default: `count` (usage count, most used first)
  - `count`: Sort by total usage (popular tags first)
  - `name`: Alphabetical sort (A-Z or Z-A based on sortDirection)

- **sortDirection** (string): Sort order
  - Valid values: `asc` | `desc`
  - Default: `desc` (descending)
  - With `sortBy: count`: desc = most used first, asc = least used first
  - With `sortBy: name`: desc = Z-A, asc = A-Z

## Usage Patterns

### Pattern 1: Full Tag Discovery (Default)
Get all tags across all entity types, sorted by popularity.

```json
{}
```

**Response size**: 300-1500 tokens (depends on tag count)

**When to use**:
- Starting a new work session (what tags exist?)
- Before searching tasks/features (what can I filter by?)
- Understanding workspace organization
- Tag taxonomy overview

**Example response**:
```json
{
  "success": true,
  "message": "Found 42 unique tag(s)",
  "data": {
    "tags": [
      {
        "tag": "backend",
        "totalCount": 67,
        "byEntityType": {
          "TASK": 52,
          "FEATURE": 12,
          "PROJECT": 3
        }
      },
      {
        "tag": "api",
        "totalCount": 45,
        "byEntityType": {
          "TASK": 38,
          "FEATURE": 5,
          "TEMPLATE": 2
        }
      },
      {
        "tag": "authentication",
        "totalCount": 23,
        "byEntityType": {
          "TASK": 18,
          "FEATURE": 3,
          "PROJECT": 2
        }
      }
    ],
    "totalTags": 42
  }
}
```

### Pattern 2: Task Tag Discovery
Show only tags used in tasks (most common use case).

```json
{
  "entityTypes": ["TASK"],
  "sortBy": "count",
  "sortDirection": "desc"
}
```

**When to use**:
- Finding tags for task filtering
- Understanding task categorization
- Planning task-specific work
- Developer-focused tag discovery

**Why filter by entity type**:
- Reduces noise from project/feature/template tags
- Shows only task-relevant categories
- Faster response (fewer entities to query)

### Pattern 3: Alphabetical Tag Listing
Get organized, alphabetical view of all tags.

```json
{
  "sortBy": "name",
  "sortDirection": "asc"
}
```

**When to use**:
- Creating tag documentation
- Finding specific tag by name
- Alphabetical browsing
- Detecting similar tags (e.g., "api", "Api", "API")

**Example response**:
```json
{
  "data": {
    "tags": [
      { "tag": "api", "totalCount": 45, "byEntityType": {...} },
      { "tag": "authentication", "totalCount": 23, "byEntityType": {...} },
      { "tag": "backend", "totalCount": 67, "byEntityType": {...} },
      { "tag": "database", "totalCount": 31, "byEntityType": {...} }
    ]
  }
}
```

### Pattern 4: Template Tag Discovery
Find tags used in templates (discover available workflow templates).

```json
{
  "entityTypes": ["TEMPLATE"],
  "sortBy": "name",
  "sortDirection": "asc"
}
```

**When to use**:
- Before creating tasks/features (what templates are available?)
- Understanding template categories
- Template management and organization
- Finding workflow templates by tag

### Pattern 5: Find Rarely Used Tags
Identify tags that might be typos or deprecated.

```json
{
  "sortBy": "count",
  "sortDirection": "asc"
}
```

**When to use**:
- Tag cleanup initiatives
- Finding typos or one-off tags
- Identifying deprecated tags
- Tag taxonomy maintenance

**Example result**: Tags with count 1-3 at the top (potential cleanup candidates)

### Pattern 6: Feature & Project Tag Discovery
High-level organizational tags.

```json
{
  "entityTypes": ["FEATURE", "PROJECT"],
  "sortBy": "count",
  "sortDirection": "desc"
}
```

**When to use**:
- Understanding high-level organization
- Portfolio management
- Strategic planning
- Cross-project initiatives

### Pattern 7: Multi-Entity Context
Compare tag usage across multiple entity types.

```json
{
  "entityTypes": ["TASK", "FEATURE"],
  "sortBy": "count",
  "sortDirection": "desc"
}
```

**When to use**:
- Understanding tag consistency between tasks and features
- Ensuring alignment between task and feature categories
- Cross-entity tag analysis

## Common Workflows

### Workflow 1: Tag Discovery for Task Search
Standard workflow before searching tasks by tag.

```javascript
// Step 1: Discover available task tags
const allTags = await list_tags({
  entityTypes: ["TASK"],
  sortBy: "count",
  sortDirection: "desc"
});

console.log("Most popular task tags:");
allTags.data.tags.slice(0, 10).forEach(t => {
  console.log(`- ${t.tag} (${t.totalCount} tasks)`);
});

// Step 2: Search tasks using discovered tag
const backendTasks = await search_tasks({
  tag: "backend",
  status: "pending"
});
```

### Workflow 2: Tag Cleanup Analysis
Find and clean up inconsistent or deprecated tags.

```javascript
// Step 1: Get all tags, least used first
const tags = await list_tags({
  sortBy: "count",
  sortDirection: "asc"
});

// Step 2: Review rarely used tags
const rarelyUsed = tags.data.tags.filter(t => t.totalCount <= 2);

console.log(`Found ${rarelyUsed.length} tags with 2 or fewer uses:`);
rarelyUsed.forEach(t => {
  console.log(`- ${t.tag} (${t.totalCount} uses)`);
});

// Step 3: Check if typos or variations exist
const apiVariations = tags.data.tags.filter(t =>
  t.tag.toLowerCase().includes("api")
);

console.log("API-related tags:");
apiVariations.forEach(t => {
  console.log(`- ${t.tag} (${t.totalCount})`);
});

// Step 4: Standardize if needed
if (apiVariations.some(t => t.tag === "API")) {
  await rename_tag({
    oldTag: "API",
    newTag: "api"
  });
}
```

### Workflow 3: Tag Standardization Audit
Find inconsistent capitalization and naming.

```javascript
// Get all tags alphabetically
const tags = await list_tags({
  sortBy: "name",
  sortDirection: "asc"
});

// Group similar tags (case-insensitive)
const tagGroups = {};
tags.data.tags.forEach(t => {
  const lower = t.tag.toLowerCase();
  if (!tagGroups[lower]) {
    tagGroups[lower] = [];
  }
  tagGroups[lower].push(t);
});

// Find tags with multiple case variations
Object.entries(tagGroups).forEach(([key, variations]) => {
  if (variations.length > 1) {
    console.log(`Inconsistent capitalization for "${key}":`);
    variations.forEach(v => {
      console.log(`  - ${v.tag} (${v.totalCount} uses)`);
    });

    // Standardize to lowercase
    const mostUsed = variations.sort((a, b) =>
      b.totalCount - a.totalCount
    )[0];

    console.log(`Recommend standardizing all to: "${key}"`);
  }
});
```

### Workflow 4: Template Discovery Before Task Creation
Find relevant templates before creating tasks.

```javascript
// Step 1: List template tags
const templateTags = await list_tags({
  entityTypes: ["TEMPLATE"],
  sortBy: "name",
  sortDirection: "asc"
});

console.log("Available template categories:");
templateTags.data.tags.forEach(t => {
  console.log(`- ${t.tag} (${t.totalCount} templates)`);
});

// Step 2: Find templates with specific tag
const workflowTemplates = await list_templates({
  targetEntityType: "TASK",
  isEnabled: true
});

// Filter by tag discovered above
const relevantTemplates = workflowTemplates.data.templates.filter(t =>
  t.tags.includes("workflow")
);

// Step 3: Create task with discovered template
const task = await create_task({
  title: "Implement feature",
  templateIds: [relevantTemplates[0].id]
});
```

## Response Structure

### Success Response
```json
{
  "success": true,
  "message": "Found 42 unique tag(s)",
  "data": {
    "tags": [
      {
        "tag": "backend",
        "totalCount": 67,
        "byEntityType": {
          "TASK": 52,
          "FEATURE": 12,
          "PROJECT": 3
        }
      },
      {
        "tag": "api",
        "totalCount": 45,
        "byEntityType": {
          "TASK": 38,
          "FEATURE": 5,
          "TEMPLATE": 2
        }
      }
    ],
    "totalTags": 42
  }
}
```

**Response fields**:
- `tags`: Array of tag objects sorted by specified criteria
- `tag`: The tag name (as used in entities)
- `totalCount`: Total entities using this tag
- `byEntityType`: Count breakdown by entity type (only includes types with non-zero counts)
- `totalTags`: Total number of unique tags found

### Empty Result Response
```json
{
  "success": true,
  "message": "Found 0 unique tag(s)",
  "data": {
    "tags": [],
    "totalTags": 0
  }
}
```

**When this occurs**:
- Brand new workspace with no entities
- All entities have no tags assigned
- Filtered by entity type with no tags (e.g., no TEMPLATE tags exist)

## Error Handling

### Validation Error - Invalid Entity Type
```json
{
  "success": false,
  "message": "Invalid entity type: INVALID. Must be PROJECT, FEATURE, TASK, or TEMPLATE",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Cause**: Invalid entity type in entityTypes array

**Solution**: Use only valid entity types: PROJECT, FEATURE, TASK, TEMPLATE

### Validation Error - Invalid Sort By
```json
{
  "success": false,
  "message": "Invalid sortBy: invalid. Must be 'count' or 'name'",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Cause**: Invalid value for sortBy parameter

**Solution**: Use only `count` or `name`

### Validation Error - Invalid Sort Direction
```json
{
  "success": false,
  "message": "Invalid sortDirection: invalid. Must be 'asc' or 'desc'",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Cause**: Invalid value for sortDirection parameter

**Solution**: Use only `asc` or `desc`

### Database Error
```json
{
  "success": false,
  "message": "Failed to list tags",
  "error": {
    "code": "DATABASE_ERROR",
    "details": "Connection timeout"
  }
}
```

**Cause**: Database connection or query issues

**Solution**: Retry the operation, check database connectivity

## Performance Considerations

### Query Performance
list_tags queries all entity repositories:
- **Tasks**: Queries task_tags table
- **Features**: Queries feature_tags table
- **Projects**: Queries project_tags table
- **Templates**: Queries templates table and extracts tags

**Optimization**: Queries run in parallel, not sequential.

### Response Size
Response size varies by tag count and entity type filtering:
- **No tags**: ~150 tokens
- **10 tags**: ~400-600 tokens
- **50 tags**: ~1000-1500 tokens
- **100+ tags**: ~2000-3000 tokens

**Optimization strategies**:
1. Filter by entityTypes to reduce response size
2. Use sortBy: "count" and limit mental review to top N tags
3. Cache results for a work session (tags don't change frequently)

### Best Practices for Performance
1. **Filter when possible**: Use entityTypes to narrow results
2. **Cache results**: Tags typically don't change mid-session
3. **Use before searches**: Run once, then use discovered tags in multiple searches
4. **Limit mental review**: Focus on top 10-20 most used tags for typical work

## Use Cases

### 1. New User Onboarding
**Scenario**: New team member needs to understand workspace organization

**Solution**:
```javascript
// Show most popular tags
const tags = await list_tags({
  sortBy: "count",
  sortDirection: "desc"
});

console.log("Workspace organization (top 10 tags):");
tags.data.tags.slice(0, 10).forEach((t, i) => {
  console.log(`${i+1}. ${t.tag} (${t.totalCount} uses)`);
  console.log(`   Tasks: ${t.byEntityType.TASK || 0}`);
  console.log(`   Features: ${t.byEntityType.FEATURE || 0}`);
});
```

### 2. Tag Taxonomy Documentation
**Scenario**: Need to document tag conventions for the team

**Solution**:
```javascript
// Get all tags alphabetically
const tags = await list_tags({
  sortBy: "name",
  sortDirection: "asc"
});

// Generate markdown documentation
let markdown = "# Tag Taxonomy\n\n";
tags.data.tags.forEach(t => {
  markdown += `## ${t.tag}\n`;
  markdown += `- Total uses: ${t.totalCount}\n`;
  markdown += `- Used in: `;
  markdown += Object.keys(t.byEntityType).join(", ");
  markdown += `\n\n`;
});

console.log(markdown);
```

### 3. Tag Cleanup Initiative
**Scenario**: Workspace has grown and tags need standardization

**Solution**:
```javascript
// Find rarely used tags
const tags = await list_tags({
  sortBy: "count",
  sortDirection: "asc"
});

// Identify cleanup candidates
const singleUse = tags.data.tags.filter(t => t.totalCount === 1);
console.log(`Found ${singleUse.length} tags used only once`);

// Find potential typos (similar names)
const allTags = tags.data.tags.map(t => t.tag);
const potential = [];
allTags.forEach((tag1, i) => {
  allTags.slice(i + 1).forEach(tag2 => {
    // Simple similarity check
    if (tag1.toLowerCase() === tag2.toLowerCase() && tag1 !== tag2) {
      potential.push([tag1, tag2]);
    }
  });
});

console.log("Potential case inconsistencies:", potential);
```

### 4. Workload Analysis
**Scenario**: Understand distribution of work across categories

**Solution**:
```javascript
// Get task tags by usage
const tags = await list_tags({
  entityTypes: ["TASK"],
  sortBy: "count",
  sortDirection: "desc"
});

// Calculate percentages
const total = tags.data.tags.reduce((sum, t) => sum + t.totalCount, 0);

console.log("Task distribution by category:");
tags.data.tags.slice(0, 10).forEach(t => {
  const percent = ((t.totalCount / total) * 100).toFixed(1);
  console.log(`${t.tag}: ${percent}% (${t.totalCount} tasks)`);
});
```

### 5. Template Discovery Flow
**Scenario**: User wants to create a task but doesn't know what templates exist

**Solution**:
```javascript
// Step 1: Show template categories
const templateTags = await list_tags({
  entityTypes: ["TEMPLATE"],
  sortBy: "name",
  sortDirection: "asc"
});

console.log("Available template categories:");
templateTags.data.tags.forEach(t => {
  console.log(`- ${t.tag} (${t.totalCount} templates)`);
});

// Step 2: User selects category → list templates
const templates = await list_templates({
  targetEntityType: "TASK",
  isEnabled: true
});

// Filter by selected tag
console.log("\nWorkflow templates:");
templates.data.templates
  .filter(t => t.tags.includes("workflow"))
  .forEach(t => console.log(`- ${t.name}`));
```

## Best Practices

1. **Run Before Searching**: Always discover tags before filtering by tags
2. **Cache Results**: Tag lists don't change frequently, cache for work session
3. **Sort Appropriately**: Use "count" for discovery, "name" for documentation
4. **Filter by Entity Type**: Narrow results to relevant entity types
5. **Regular Cleanup**: Periodically review rarely used tags
6. **Standardize Capitalization**: Pick a convention (lowercase recommended)
7. **Use with get_tag_usage**: Discover with list_tags, analyze with get_tag_usage
8. **Document Conventions**: Use alphabetical listing to generate tag documentation

## Common Mistakes to Avoid

### ❌ Mistake 1: Searching Without Discovering Tags First
```javascript
// BAD: Guessing tag names
const tasks = await search_tasks({
  tag: "back-end"  // Does this tag exist? Is it "backend"?
});
```

**Problem**: Tag might not exist or use different naming convention

### ✅ Solution: Discover Tags First
```javascript
// GOOD: Discover available tags
const tags = await list_tags({ entityTypes: ["TASK"] });
const backendTag = tags.data.tags.find(t =>
  t.tag.toLowerCase().includes("backend")
);

const tasks = await search_tasks({
  tag: backendTag.tag  // Use exact tag name from system
});
```

### ❌ Mistake 2: Not Filtering by Entity Type
```javascript
// BAD: Getting all tags when only need task tags
const tags = await list_tags({});  // Includes project, feature, template tags
```

**Problem**: Cluttered results with irrelevant tags, slower response

### ✅ Solution: Filter by Relevant Entity Types
```javascript
// GOOD: Only get task tags
const tags = await list_tags({
  entityTypes: ["TASK"]
});
```

### ❌ Mistake 3: Ignoring Rarely Used Tags
```javascript
// BAD: Never checking for cleanup candidates
const tags = await list_tags({
  sortBy: "count",
  sortDirection: "desc"
});
// Only look at top tags, ignore the bottom
```

**Problem**: Accumulation of typos and deprecated tags

### ✅ Solution: Periodic Cleanup Reviews
```javascript
// GOOD: Regular cleanup reviews
const tags = await list_tags({
  sortBy: "count",
  sortDirection: "asc"  // Least used first
});

const cleanup = tags.data.tags.filter(t => t.totalCount <= 2);
console.log("Review these tags for cleanup:", cleanup);
```

### ❌ Mistake 4: Not Using entityTypes Array Format
```javascript
// BAD: Wrong format
const tags = await list_tags({
  entityTypes: "TASK,FEATURE"  // String, not array
});
```

**Problem**: Parameter validation error

### ✅ Solution: Use Array Format
```javascript
// GOOD: Correct array format
const tags = await list_tags({
  entityTypes: ["TASK", "FEATURE"]
});
```

### ❌ Mistake 5: Not Caching Results
```javascript
// BAD: Calling multiple times in same session
const tags1 = await list_tags({});
// ... do something ...
const tags2 = await list_tags({});  // Identical call
```

**Problem**: Unnecessary API calls, slower performance

### ✅ Solution: Cache and Reuse
```javascript
// GOOD: Cache for session
const tagsCache = await list_tags({});

// Use cached results multiple times
function findTag(name) {
  return tagsCache.data.tags.find(t =>
    t.tag.toLowerCase() === name.toLowerCase()
  );
}
```

## Related Tools

- **get_tag_usage**: Detailed analysis of entities using a specific tag
- **rename_tag**: Rename tags workspace-wide (use list_tags to find candidates)
- **search_tasks**: Find tasks by tag (use list_tags to discover available tags)
- **search_features**: Find features by tag
- **search_projects**: Find projects by tag
- **list_templates**: Discover templates (can filter by tags from list_tags)

## Integration Examples

### With search_tasks
```javascript
// Discover tags, then search
const tags = await list_tags({ entityTypes: ["TASK"] });
console.log("Available task tags:", tags.data.tags.map(t => t.tag));

// User selects "backend" from list
const tasks = await search_tasks({
  tag: "backend",
  status: "pending"
});
```

### With get_tag_usage
```javascript
// List all tags
const tags = await list_tags({ sortBy: "count", sortDirection: "desc" });

// Get detailed usage for top tag
const topTag = tags.data.tags[0];
const usage = await get_tag_usage({ tag: topTag.tag });

console.log(`Details for most popular tag "${topTag.tag}":`);
console.log(`Total: ${usage.data.totalCount}`);
console.log(`Tasks: ${usage.data.entities.TASK?.length || 0}`);
```

### With rename_tag
```javascript
// Find tags to standardize
const tags = await list_tags({ sortBy: "name", sortDirection: "asc" });

// Find case variations
const apiTags = tags.data.tags.filter(t =>
  t.tag.toLowerCase() === "api"
);

if (apiTags.length > 1) {
  console.log("Found case variations:", apiTags.map(t => t.tag));

  // Standardize to lowercase
  await rename_tag({
    oldTag: "API",
    newTag: "api"
  });
}
```

### Multi-Tool Workflow: Complete Tag Audit
```javascript
// Step 1: List all tags
const allTags = await list_tags({ sortBy: "count", sortDirection: "asc" });

// Step 2: Find rarely used tags
const rare = allTags.data.tags.filter(t => t.totalCount <= 2);

// Step 3: Analyze each rare tag
for (const tag of rare) {
  const usage = await get_tag_usage({ tag: tag.tag });

  console.log(`\nTag: ${tag.tag} (${tag.totalCount} uses)`);

  // Show what would be affected if renamed or removed
  Object.entries(usage.data.entities).forEach(([type, entities]) => {
    console.log(`  ${type}: ${entities.length} entities`);
    entities.forEach(e => {
      console.log(`    - ${e.title || e.name}`);
    });
  });
}

// Step 4: Rename or consolidate as needed
```

## See Also

- Tag Management Guide: `task-orchestrator://guidelines/tag-management`
- Tag Conventions: `task-orchestrator://guidelines/tagging-conventions`
- Search Best Practices: `task-orchestrator://guidelines/search-best-practices`
- Workflow Integration: `task-orchestrator://guidelines/workflow-integration`
