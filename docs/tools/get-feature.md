# get_feature Tool - Detailed Documentation

## Overview

Retrieves complete feature information with options to include related entities like tasks, sections, and task statistics.

**Resource**: `task-orchestrator://docs/tools/get-feature`

## Key Concepts

### Feature Content Architecture
Features store their detailed content in **separate Section entities** for efficiency:
- Feature entity: Lightweight metadata (name, status, priority, etc.)
- Section entities: Detailed content blocks (requirements, architecture, etc.)

**CRITICAL**: To get the complete feature with all content, set `includeSections=true`.

### Context Efficiency Strategy
Use parameter flags to control what data is loaded:
- Minimal fetch: Just feature metadata (~200 tokens)
- With tasks: Feature + task list (~500-1000 tokens)
- Full fetch: Feature + sections + tasks + dependencies (~2000-5000 tokens)

## Parameter Reference

### Required Parameters
- **id** (UUID): Feature ID to retrieve

### Optional Parameters
- **includeSections** (boolean, default: false): Include section content blocks
- **includeTasks** (boolean, default: false): Include associated tasks
- **maxTaskCount** (integer, default: 10): Maximum tasks to include (1-100)
- **includeTaskCounts** (boolean, default: false): Include task statistics by status
- **includeTaskDependencies** (boolean, default: false): Include dependency info for tasks
- **summaryView** (boolean, default: false): Return truncated text for context efficiency
- **maxsummaryLength** (integer, default: 500): Maximum summary length when using summaryView

## Usage Patterns

### Pattern 1: Quick Metadata Check
Get feature status, priority, and basic info without loading sections or tasks.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response size**: ~200 tokens
**When to use**:
- Check feature status
- Get feature name/summary
- Quick verification
- When you already know the content

### Pattern 2: Feature with Task Overview
Get feature with task counts to understand progress.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "includeTaskCounts": true
}
```

**Response size**: ~300-400 tokens
**When to use**:
- Quick progress check
- See how many tasks are completed vs pending
- Dashboard/overview displays

### Pattern 3: Full Feature Context
Get everything needed to work on the feature.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "includeSections": true,
  "includeTasks": true,
  "maxTaskCount": 20
}
```

**Response size**: ~2000-5000 tokens (varies by content)
**When to use**:
- Starting work on a feature
- Need complete context
- Understanding feature architecture
- Planning task breakdown

### Pattern 4: Feature with Task Dependencies
Get feature with tasks and their dependency information.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "includeTasks": true,
  "includeTaskDependencies": true,
  "maxTaskCount": 20
}
```

**Response size**: ~800-1500 tokens
**When to use**:
- Planning work order
- Understanding task relationships
- Identifying blockers

### Pattern 5: Summary View for Context
Get truncated view for context without full content.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "includeSections": true,
  "summaryView": true,
  "maxsummaryLength": 200
}
```

**Response size**: ~500-800 tokens
**When to use**:
- Building context across multiple features
- Overview of feature content
- When full content isn't needed

## Two-Step Efficient Pattern

For large features, use a two-step approach to minimize token usage:

### Step 1: Get Section Metadata Only
```javascript
// First, see what sections exist
const sections = await get_sections({
  entityType: "FEATURE",
  entityId: "550e8400-e29b-41d4-a716-446655440000",
  includeContent: false
});

// Review section titles and tags
// sections.data.sections = [
//   { id: "uuid1", title: "Requirements", tags: ["requirements"] },
//   { id: "uuid2", title: "Architecture", tags: ["architecture"] },
//   { id: "uuid3", title: "Technical Approach", tags: ["technical"] }
// ]
```

**Token savings**: 85-99% compared to loading all content

### Step 2: Load Specific Sections
```javascript
// Then, load only the sections you need
const requirements = await get_sections({
  entityType: "FEATURE",
  entityId: "550e8400-e29b-41d4-a716-446655440000",
  sectionIds: ["uuid1"],  // Only Requirements section
  includeContent: true
});
```

**Total token usage**: 10-20% of loading everything upfront

## Response Structure

### Minimal Response (no optional parameters)
```json
{
  "success": true,
  "message": "Feature retrieved successfully",
  "data": {
    "id": "uuid",
    "name": "Feature name",
    "summary": "Feature summary",
    "status": "in-development",
    "priority": "high",
    "createdAt": "ISO-8601",
    "modifiedAt": "ISO-8601",
    "projectId": "uuid-or-null",
    "tags": ["tag1", "tag2"]
  }
}
```

### With Task Counts
```json
{
  "success": true,
  "message": "Feature retrieved successfully",
  "data": {
    "id": "uuid",
    "name": "User Authentication System",
    "summary": "Multi-provider OAuth with password login",
    "status": "in-development",
    "priority": "high",
    "tags": ["authentication", "oauth", "security"],
    "taskCounts": {
      "total": 8,
      "byStatus": {
        "completed": 3,
        "in-progress": 2,
        "pending": 3
      }
    }
  }
}
```

### Full Response (all optional parameters enabled)
```json
{
  "success": true,
  "message": "Feature retrieved successfully",
  "data": {
    "id": "uuid",
    "name": "User Authentication System",
    "summary": "Multi-provider OAuth with password login and 2FA",
    "status": "in-development",
    "priority": "high",
    "createdAt": "ISO-8601",
    "modifiedAt": "ISO-8601",
    "projectId": "project-uuid",
    "tags": ["authentication", "oauth", "security"],
    "sections": [
      {
        "id": "section-uuid",
        "title": "Requirements",
        "content": "Detailed requirements...",
        "contentFormat": "markdown",
        "ordinal": 0
      }
    ],
    "tasks": {
      "items": [
        {
          "id": "task-uuid",
          "title": "Implement OAuth Google provider",
          "status": "completed",
          "priority": "high",
          "complexity": 6,
          "dependencies": {
            "counts": {
              "total": 2,
              "incoming": 1,
              "outgoing": 1
            }
          }
        }
      ],
      "total": 8,
      "included": 8,
      "hasMore": false,
      "dependencyStatistics": {
        "totalDependencies": 12,
        "totalIncomingDependencies": 6,
        "totalOutgoingDependencies": 6,
        "tasksWithDependencies": 5
      }
    }
  }
}
```

## Common Workflows

### Workflow 1: Check Feature Progress
```javascript
// Get quick status overview
const feature = await get_feature({
  id: featureId,
  includeTaskCounts: true
});

console.log(`Feature: ${feature.data.name}`);
console.log(`Status: ${feature.data.status}`);
console.log(`Tasks: ${feature.data.taskCounts.byStatus.completed}/${feature.data.taskCounts.total} completed`);
```

### Workflow 2: Start Working on Feature
```javascript
// Get full context
const feature = await get_feature({
  id: featureId,
  includeSections: true,
  includeTasks: true,
  includeTaskDependencies: true
});

// Read requirements
const requirements = feature.data.sections.find(s => s.title === "Requirements");

// Find next task to work on (no incomplete incoming dependencies)
const nextTask = feature.data.tasks.items.find(t =>
  t.status === "pending" &&
  t.dependencies.counts.incoming === 0
);
```

### Workflow 3: Update Feature Status Based on Tasks
```javascript
// Check if all tasks are completed
const feature = await get_feature({
  id: featureId,
  includeTaskCounts: true
});

if (feature.data.taskCounts.byStatus.completed === feature.data.taskCounts.total) {
  await set_status({
    id: featureId,
    status: "completed"
  });
}
```

### Workflow 4: Export Feature Documentation
```javascript
// Get feature with all sections
const feature = await get_feature({
  id: featureId,
  includeSections: true
});

// Or use feature_to_markdown for formatted output
const markdown = await feature_to_markdown({
  id: featureId
});
```

## Performance Considerations

### Token Usage by Configuration

| Configuration | Approximate Tokens | Use Case |
|---------------|-------------------|----------|
| Basic (no flags) | ~200 | Status check |
| With task counts | ~300-400 | Progress overview |
| With tasks | ~500-1000 | Task planning |
| With sections | ~1500-3000 | Implementation |
| Full (all flags) | ~2000-5000 | Complete context |
| Summary view | ~500-800 | Overview |

### Optimization Strategies

1. **Start Minimal**: Get basic info first, expand as needed
2. **Use Task Counts**: More efficient than loading full task list
3. **Two-Step Pattern**: Browse sections metadata, then load specific sections
4. **Summary View**: Use for multi-feature context building
5. **Limit Tasks**: Set maxTaskCount to only what you need
6. **Cache Results**: Store feature data locally during work session

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

**Common causes**:
- Feature was deleted
- Wrong UUID provided
- Feature doesn't exist yet

**Solution**: Verify feature ID using `search_features` or `get_overview`

### Invalid UUID Format
```json
{
  "success": false,
  "message": "Invalid input",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid id format. Must be a valid UUID"
  }
}
```

**Solution**: Ensure ID is a valid UUID format

### Invalid Parameter Values
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "maxTaskCount must be at least 1"
  }
}
```

**Solution**: Check parameter constraints (maxTaskCount: 1-100)

## Best Practices

1. **Start with Metadata**: Get basic info before loading full content
2. **Use includeSections=true**: When working on feature implementation
3. **Check Task Counts First**: More efficient than loading all tasks
4. **Enable Summary View**: When building context across multiple features
5. **Cache Feature Data**: Store in working memory during implementation
6. **Selective Task Loading**: Use maxTaskCount to limit response size
7. **Use get_sections**: More efficient for loading specific sections
8. **Include Dependencies**: When planning task execution order

## Related Tools

- **get_sections**: More efficient way to load specific sections
- **search_features**: Find features before retrieving
- **update_feature**: Modify feature metadata
- **set_status**: Update feature status efficiently
- **get_feature_tasks**: Get detailed task list with filtering
- **feature_to_markdown**: Export feature as markdown document
- **create_feature**: Create new features
- **delete_feature**: Remove features

## See Also

- Feature Management Patterns: `task-orchestrator://guidelines/feature-management`
- Context Efficiency: `task-orchestrator://guidelines/usage-overview`
- Task Dependencies: `task-orchestrator://guidelines/dependency-management`
