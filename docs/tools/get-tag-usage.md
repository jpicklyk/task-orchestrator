# get_tag_usage Tool - Detailed Documentation

## Overview

Finds all entities (tasks, features, projects, templates) that use a specific tag. Essential for impact analysis before renaming or removing tags, and for understanding tag adoption across your workspace.

**Resource**: `task-orchestrator://docs/tools/get-tag-usage`

## Key Concepts

### Tag Usage Analysis
Tags provide cross-entity organization in Task Orchestrator:
- **Tasks**: Categorize work items by type, domain, or technology
- **Features**: Group features by theme or architectural layer
- **Projects**: Classify projects by department or initiative
- **Templates**: Categorize templates by purpose or workflow type

**Why track usage?**
- **Impact Analysis**: See what would be affected before renaming/removing a tag
- **Tag Cleanup**: Identify obsolete tags no longer in use
- **Discovery**: Find all work related to a specific topic
- **Consistency**: Understand tag adoption patterns

### Case-Insensitive Matching
Tag searches are case-insensitive for flexibility:
- Search for "API" finds: "api", "Api", "API"
- Helps find inconsistent tag capitalization
- Original tag casing is preserved in results

## Parameter Reference

### Required Parameters
- **tag** (string): The tag to search for
  - Case-insensitive matching
  - Searches across all entity types by default
  - Example: "authentication", "backend", "api"

### Optional Parameters
- **entityTypes** (string): Comma-separated list of entity types to search
  - Valid values: TASK, FEATURE, PROJECT, TEMPLATE
  - Default: "TASK,FEATURE,PROJECT,TEMPLATE" (all types)
  - Example: "TASK,FEATURE" to search only tasks and features

## Usage Patterns

### Pattern 1: Full Impact Analysis
Search all entity types to understand complete tag usage.

```json
{
  "tag": "authentication"
}
```

**Response size**: Varies by usage (typically 500-2000 tokens)

**When to use**:
- Before renaming a tag (see what would be affected)
- Understanding scope of a tag across all work
- Tag cleanup analysis
- Cross-entity discovery

**Example response**:
```json
{
  "success": true,
  "message": "Found 12 entities with tag 'authentication'",
  "data": {
    "tag": "authentication",
    "totalCount": 12,
    "entities": {
      "TASK": [
        {
          "id": "uuid1",
          "title": "Implement JWT token generation",
          "status": "completed",
          "priority": "high",
          "complexity": 6
        },
        {
          "id": "uuid2",
          "title": "Add OAuth provider integration",
          "status": "in-progress",
          "priority": "high",
          "complexity": 7
        }
      ],
      "FEATURE": [
        {
          "id": "uuid3",
          "name": "User Authentication System",
          "status": "in-development",
          "priority": "high"
        }
      ],
      "PROJECT": [
        {
          "id": "uuid4",
          "name": "Security Infrastructure",
          "status": "in-development"
        }
      ],
      "TEMPLATE": [
        {
          "id": "uuid5",
          "name": "Security Implementation Workflow",
          "targetEntityType": "TASK",
          "isEnabled": true
        }
      ]
    }
  }
}
```

### Pattern 2: Task-Specific Search
Focus on tasks only for implementation planning.

```json
{
  "tag": "backend",
  "entityTypes": "TASK"
}
```

**When to use**:
- Finding all backend tasks to assign or prioritize
- Scoping work for a specific developer/team
- Estimating effort for a specific category
- Building task lists by specialty

### Pattern 3: Template Discovery
Find templates using a specific tag.

```json
{
  "tag": "workflow",
  "entityTypes": "TEMPLATE"
}
```

**When to use**:
- Discovering workflow templates for task creation
- Understanding available template categories
- Template inventory management
- Finding templates by purpose

### Pattern 4: Feature & Project Grouping
Search features and projects to understand high-level organization.

```json
{
  "tag": "api",
  "entityTypes": "FEATURE,PROJECT"
}
```

**When to use**:
- Understanding feature organization
- Project portfolio analysis
- High-level planning
- Resource allocation by initiative

## Common Workflows

### Workflow 1: Pre-Rename Impact Analysis
Standard workflow before renaming a tag.

```javascript
// Step 1: Check what would be affected
const usage = await get_tag_usage({
  tag: "authentcation"  // Typo found
});

console.log(`Found ${usage.data.totalCount} entities to update:`);
console.log(`- Tasks: ${usage.data.entities.TASK?.length || 0}`);
console.log(`- Features: ${usage.data.entities.FEATURE?.length || 0}`);
console.log(`- Projects: ${usage.data.entities.PROJECT?.length || 0}`);
console.log(`- Templates: ${usage.data.entities.TEMPLATE?.length || 0}`);

// Step 2: Preview the rename
const preview = await rename_tag({
  oldTag: "authentcation",
  newTag: "authentication",
  dryRun: true
});

// Step 3: Execute the rename
const result = await rename_tag({
  oldTag: "authentcation",
  newTag: "authentication"
});
```

### Workflow 2: Tag Cleanup
Identify and remove obsolete tags.

```javascript
// Check if old tag is still in use
const usage = await get_tag_usage({
  tag: "legacy-v1"
});

if (usage.data.totalCount === 0) {
  console.log("Tag 'legacy-v1' is no longer in use and can be removed from documentation");
} else {
  console.log(`Tag still in use by ${usage.data.totalCount} entities`);
  // Review entities and update if needed
}
```

### Workflow 3: Discover Related Work
Find all work on a specific topic.

```javascript
// Find all security-related work
const securityWork = await get_tag_usage({
  tag: "security"
});

// Group by status
const inProgress = securityWork.data.entities.TASK?.filter(
  t => t.status === "in-progress"
);

console.log(`Security work in progress: ${inProgress.length} tasks`);
```

### Workflow 4: Tag Consistency Audit
Find inconsistent tag capitalization.

```javascript
// Search for common variations
const variations = ["API", "api", "Api"];
const results = await Promise.all(
  variations.map(v => get_tag_usage({ tag: v }))
);

// get_tag_usage is case-insensitive, so all will return same results
// showing that these variations should be standardized
const total = results[0].data.totalCount;
console.log(`Found ${total} entities with inconsistent API tag capitalization`);

// Standardize to lowercase
await rename_tag({
  oldTag: "API",
  newTag: "api"
});
```

## Response Structure

### Success Response
```json
{
  "success": true,
  "message": "Found N entities with tag 'tagname'",
  "data": {
    "tag": "searched-tag",
    "totalCount": 0,
    "entities": {
      "TASK": [
        {
          "id": "uuid",
          "title": "Task title",
          "status": "pending|in-progress|completed|cancelled|deferred",
          "priority": "high|medium|low",
          "complexity": 1-10
        }
      ],
      "FEATURE": [
        {
          "id": "uuid",
          "name": "Feature name",
          "status": "planning|in-development|completed|archived",
          "priority": "high|medium|low"
        }
      ],
      "PROJECT": [
        {
          "id": "uuid",
          "name": "Project name",
          "status": "planning|in-development|completed|archived"
        }
      ],
      "TEMPLATE": [
        {
          "id": "uuid",
          "name": "Template name",
          "targetEntityType": "TASK|FEATURE|PROJECT",
          "isEnabled": true|false
        }
      ]
    }
  }
}
```

### Empty Result Response
```json
{
  "success": true,
  "message": "No entities found with tag 'unused-tag'",
  "data": {
    "tag": "unused-tag",
    "totalCount": 0,
    "entities": {}
  }
}
```

## Error Handling

### Validation Error - Missing Tag
```json
{
  "success": false,
  "message": "Missing required parameter: tag",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Cause**: Tag parameter not provided

**Solution**: Provide tag parameter

### Validation Error - Empty Tag
```json
{
  "success": false,
  "message": "Tag cannot be empty",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Cause**: Tag parameter is empty string or whitespace only

**Solution**: Provide a non-empty tag value

### Validation Error - Invalid Entity Type
```json
{
  "success": false,
  "message": "Invalid entity types: INVALID, WRONG. Valid types are: TASK, FEATURE, PROJECT, TEMPLATE",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Cause**: Invalid entity type in entityTypes parameter

**Solution**: Use only: TASK, FEATURE, PROJECT, TEMPLATE

## Performance Considerations

### Query Scope
The tool queries all entity repositories in parallel for efficiency:
- Tasks searched: Up to 10,000
- Features searched: Up to 10,000
- Projects searched: Up to 10,000
- Templates searched: All (typically < 100)

**Note**: If your workspace has more than 10,000 tasks/features/projects, some results may be truncated.

### Response Size
Response size varies by tag usage:
- Unused tag: ~150 tokens
- Light usage (1-5 entities): ~300-500 tokens
- Medium usage (5-20 entities): ~800-1500 tokens
- Heavy usage (20+ entities): ~1500-3000 tokens

### Optimization Strategies
1. **Narrow entity types**: If you only need tasks, specify `entityTypes: "TASK"`
2. **Use for analysis**: Great for one-time analysis, not repeated queries
3. **Cache results**: Store results if planning multiple related operations

## Use Cases

### 1. Tag Standardization
**Scenario**: Found inconsistent tag usage across the workspace

**Solution**:
```javascript
// Find all entities using old tag
const usage = await get_tag_usage({ tag: "rest-api" });
console.log(`Found ${usage.data.totalCount} uses of 'rest-api'`);

// Standardize to new convention
await rename_tag({
  oldTag: "rest-api",
  newTag: "api"
});
```

### 2. Impact Analysis for Tag Removal
**Scenario**: Want to deprecate a tag but need to understand impact

**Solution**:
```javascript
const usage = await get_tag_usage({ tag: "deprecated-v1" });

if (usage.data.totalCount > 0) {
  console.log("Cannot remove tag - still in use:");
  console.log(`Tasks: ${usage.data.entities.TASK?.length || 0}`);
  console.log(`Features: ${usage.data.entities.FEATURE?.length || 0}`);
  // Review and migrate entities first
} else {
  console.log("Tag can be safely removed from taxonomy");
}
```

### 3. Workload Distribution
**Scenario**: Understand backend vs frontend workload

**Solution**:
```javascript
const backendTasks = await get_tag_usage({
  tag: "backend",
  entityTypes: "TASK"
});

const frontendTasks = await get_tag_usage({
  tag: "frontend",
  entityTypes: "TASK"
});

console.log(`Backend tasks: ${backendTasks.data.totalCount}`);
console.log(`Frontend tasks: ${frontendTasks.data.totalCount}`);
```

### 4. Template Organization
**Scenario**: Audit available templates by category

**Solution**:
```javascript
const workflowTemplates = await get_tag_usage({
  tag: "workflow",
  entityTypes: "TEMPLATE"
});

console.log("Available workflow templates:");
workflowTemplates.data.entities.TEMPLATE?.forEach(t => {
  console.log(`- ${t.name} (${t.targetEntityType})`);
});
```

## Best Practices

1. **Use Before Renaming**: Always run get_tag_usage before rename_tag to understand impact
2. **Regular Tag Audits**: Periodically check for unused tags to keep taxonomy clean
3. **Standardize Early**: Use to find and fix tag inconsistencies before they spread
4. **Narrow When Possible**: Specify entityTypes when you only need specific entity types
5. **Case Doesn't Matter**: Search is case-insensitive, so use whatever is convenient
6. **Document Patterns**: When you find tag usage patterns, document them for consistency

## Common Mistakes to Avoid

### ❌ Mistake 1: Renaming Without Checking Impact
```javascript
// BAD: Rename without understanding impact
await rename_tag({
  oldTag: "api",
  newTag: "rest-api"
});
```

**Problem**: Might affect dozens of entities unexpectedly

### ✅ Solution: Always Check First
```javascript
// GOOD: Check impact before renaming
const usage = await get_tag_usage({ tag: "api" });
console.log(`Will affect ${usage.data.totalCount} entities`);

await rename_tag({
  oldTag: "api",
  newTag: "rest-api"
});
```

### ❌ Mistake 2: Assuming No Results Means No Usage
```javascript
// BAD: Only checking tasks
const usage = await get_tag_usage({
  tag: "legacy",
  entityTypes: "TASK"
});

if (usage.data.totalCount === 0) {
  // Might miss features/projects/templates using this tag!
}
```

### ✅ Solution: Check All Entity Types for Complete Picture
```javascript
// GOOD: Check all entity types
const usage = await get_tag_usage({ tag: "legacy" });
// Searches TASK, FEATURE, PROJECT, TEMPLATE
```

### ❌ Mistake 3: Case-Sensitive Tag Assumptions
```javascript
// BAD: Searching multiple times for case variations
const upper = await get_tag_usage({ tag: "API" });
const lower = await get_tag_usage({ tag: "api" });
// Both return identical results - wasteful!
```

### ✅ Solution: Remember Search is Case-Insensitive
```javascript
// GOOD: Single search finds all case variations
const usage = await get_tag_usage({ tag: "api" });
// Finds "api", "Api", "API", etc.
```

## Related Tools

- **rename_tag**: Rename tags across all entities (use get_tag_usage first for impact analysis)
- **search_tasks**: Find tasks by various criteria including tags
- **search_features**: Find features by tags
- **search_projects**: Find projects by tags
- **list_templates**: Discover templates (which can be filtered by tags)

## Integration Examples

### With rename_tag
```javascript
// Complete tag rename workflow
const usage = await get_tag_usage({ tag: "authentcation" });
console.log(`Impact: ${usage.data.totalCount} entities`);

const preview = await rename_tag({
  oldTag: "authentcation",
  newTag: "authentication",
  dryRun: true
});

const result = await rename_tag({
  oldTag: "authentcation",
  newTag: "authentication"
});
```

### With search_tasks
```javascript
// Find tasks by tag, then get detailed info
const usage = await get_tag_usage({
  tag: "urgent",
  entityTypes: "TASK"
});

// Get full details for urgent tasks
const taskIds = usage.data.entities.TASK.map(t => t.id);
const detailedTasks = await Promise.all(
  taskIds.map(id => get_task({ id, includeSections: true }))
);
```

## See Also

- Tag Management Guide: `task-orchestrator://guidelines/tag-management`
- Tag Conventions: `task-orchestrator://guidelines/tagging-conventions`
- Workflow Integration: `task-orchestrator://guidelines/workflow-integration`
