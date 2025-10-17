# get_project Tool - Detailed Documentation

## Overview

Retrieves complete project information with options to include related entities like features, tasks, and sections. Supports progressive loading for context efficiency.

**Resource**: `task-orchestrator://docs/tools/get-project`

## Key Concepts

### Project Content Architecture
Projects store their detailed content in **separate Section entities** for efficiency:
- Project entity: Lightweight metadata (name, status, tags, etc.)
- Section entities: Detailed content blocks (overview, objectives, architecture, etc.)

**CRITICAL**: To get the complete project with all content, set `includeSections=true`.

### Context Efficiency Strategy
Use parameter flags to control what data is loaded:
- Minimal fetch: Just project metadata (~200 tokens)
- With features: Project + feature list (~500-1000 tokens)
- Full fetch: Project + sections + features + tasks (~2000-5000 tokens)

## Parameter Reference

### Required Parameters
- **id** (UUID): Project ID to retrieve

### Optional Parameters
- **includeSections** (boolean, default: false): Include section content blocks
- **includeFeatures** (boolean, default: false): Include associated features
- **includeTasks** (boolean, default: false): Include directly associated tasks
- **maxFeatureCount** (integer, default: 10, range: 1-100): Maximum features to include
- **maxTaskCount** (integer, default: 10, range: 1-100): Maximum tasks to include
- **summaryView** (boolean, default: false): Return truncated text for context efficiency

## Usage Patterns

### Pattern 1: Quick Metadata Check
Get project status and basic info without loading sections.

```json
{
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17"
}
```

**Response size**: ~200 tokens
**When to use**:
- Check project status
- Get project name/tags
- Quick verification
- When you already know the content

**Response includes**:
- Project metadata (id, name, summary, status, tags)
- Created/modified timestamps
- No sections, features, or tasks

### Pattern 2: Project with Features Overview
Get project and see what features it contains.

```json
{
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "includeFeatures": true,
  "maxFeatureCount": 20
}
```

**Response size**: ~500-1000 tokens
**When to use**:
- Planning feature work
- Understanding project scope
- Reviewing project structure
- Feature prioritization

**Response includes**:
- Project metadata
- Feature list (id, name, status, priority, summary)
- Feature counts (total, included, hasMore flag)

### Pattern 3: Full Context Retrieval
Get everything needed to understand the project completely.

```json
{
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "includeSections": true,
  "includeFeatures": true,
  "includeTasks": true,
  "maxFeatureCount": 50,
  "maxTaskCount": 50
}
```

**Response size**: ~2000-5000 tokens (varies by content)
**When to use**:
- Starting work on a project
- Complete project review
- Documentation generation
- Understanding all project details

**Response includes**:
- Project metadata
- All sections with full content
- Feature list with summaries
- Direct task list
- Complete project context

### Pattern 4: Summary View for Context
Get truncated view for context without full content.

```json
{
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "includeSections": true,
  "includeFeatures": true,
  "summaryView": true
}
```

**Response size**: ~500-800 tokens
**When to use**:
- Building context across multiple projects
- Overview of project content
- When full content isn't needed
- Multi-project comparisons

**Truncation behavior**:
- Section content: Limited to 100 chars
- Feature summaries: Limited to 100 chars
- Task summaries: Limited to 100 chars
- Project summary: Limited to 500 chars

### Pattern 5: Features Only (No Tasks)
Get project with features but without direct tasks.

```json
{
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "includeFeatures": true,
  "includeTasks": false,
  "maxFeatureCount": 100
}
```

**Response size**: ~800-1500 tokens
**When to use**:
- Feature-centric projects (most projects)
- When direct tasks are rare
- Feature planning sessions
- Project structure reviews

## Two-Step Efficient Pattern

For large projects, use a two-step approach to minimize token usage:

### Step 1: Get Section Metadata Only
```javascript
// First, see what sections exist
const sections = await get_sections({
  entityType: "PROJECT",
  entityId: "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  includeContent: false
});

// Review section titles and tags
// sections.data.sections = [
//   { id: "uuid1", title: "Project Overview", tags: ["overview"] },
//   { id: "uuid2", title: "Architecture", tags: ["architecture", "technical"] },
//   { id: "uuid3", title: "Q2 Objectives", tags: ["objectives", "roadmap"] }
// ]
```

**Token savings**: 85-99% compared to loading all content

### Step 2: Load Specific Sections
```javascript
// Then, load only the sections you need
const overview = await get_sections({
  entityType: "PROJECT",
  entityId: "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  sectionIds: ["uuid1"],  // Only Overview section
  includeContent: true
});
```

**Total token usage**: 10-20% of loading everything upfront

## Response Structure

### Minimal Response (no optional parameters)
```json
{
  "success": true,
  "message": "Project retrieved successfully",
  "data": {
    "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
    "name": "Mobile App Redesign",
    "summary": "Complete mobile app overhaul with new UI/UX",
    "status": "in-development",
    "createdAt": "2025-10-17T14:30:00Z",
    "modifiedAt": "2025-10-17T15:45:00Z",
    "tags": ["mobile", "redesign", "2025-q2"]
  }
}
```

### Full Response (all optional parameters enabled)
```json
{
  "success": true,
  "message": "Project retrieved successfully",
  "data": {
    "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
    "name": "Mobile App Redesign",
    "summary": "Complete mobile app overhaul with new UI/UX",
    "status": "in-development",
    "createdAt": "2025-10-17T14:30:00Z",
    "modifiedAt": "2025-10-17T15:45:00Z",
    "tags": ["mobile", "redesign", "2025-q2"],
    "sections": [
      {
        "id": "section-uuid",
        "title": "Project Overview",
        "content": "## Goals\n- Improve user experience\n- Modernize design...",
        "contentFormat": "markdown",
        "ordinal": 0,
        "tags": ["overview"]
      }
    ],
    "features": {
      "items": [
        {
          "id": "feature-uuid",
          "name": "User Authentication Redesign",
          "status": "in-development",
          "priority": "high",
          "summary": "Modernize authentication flow with biometric support"
        }
      ],
      "total": 8,
      "included": 8,
      "hasMore": false
    },
    "tasks": {
      "items": [
        {
          "id": "task-uuid",
          "title": "Set up project documentation",
          "status": "completed",
          "priority": "high",
          "complexity": 3,
          "summary": "Created project documentation structure"
        }
      ],
      "total": 2,
      "included": 2,
      "hasMore": false
    }
  }
}
```

## Common Workflows

### Workflow 1: Review Project Status
```javascript
// Quick status check
const project = await get_project({
  id: projectId
});

console.log(`Project: ${project.data.name}`);
console.log(`Status: ${project.data.status}`);
console.log(`Tags: ${project.data.tags.join(', ')}`);
console.log(`Last Modified: ${project.data.modifiedAt}`);
```

### Workflow 2: Plan Feature Work
```javascript
// Get project with all features
const project = await get_project({
  id: projectId,
  includeFeatures: true,
  maxFeatureCount: 50
});

// Analyze feature status distribution
const statusCounts = project.data.features.items.reduce((acc, f) => {
  acc[f.status] = (acc[f.status] || 0) + 1;
  return acc;
}, {});

console.log('Feature Status:', statusCounts);
// { planning: 2, in-development: 5, completed: 1 }

// Find next features to work on
const nextFeatures = project.data.features.items
  .filter(f => f.status === 'planning')
  .sort((a, b) => {
    const priorityOrder = { high: 0, medium: 1, low: 2 };
    return priorityOrder[a.priority] - priorityOrder[b.priority];
  });
```

### Workflow 3: Generate Project Report
```javascript
// Get complete project for reporting
const project = await get_project({
  id: projectId,
  includeSections: true,
  includeFeatures: true,
  includeTasks: true,
  maxFeatureCount: 100,
  maxTaskCount: 100
});

// Generate markdown report
const report = `
# ${project.data.name}

**Status**: ${project.data.status}
**Created**: ${project.data.createdAt}

## Summary
${project.data.summary}

## Features (${project.data.features.total})
${project.data.features.items.map(f =>
  `- [${f.status}] ${f.name} (${f.priority} priority)`
).join('\n')}

## Sections
${project.data.sections.map(s =>
  `### ${s.title}\n${s.content}`
).join('\n\n')}
`;

// Export or display report
console.log(report);
```

### Workflow 4: Update Project After Review
```javascript
// Get current state
const project = await get_project({
  id: projectId,
  includeSections: true
});

// Review and update
const updatedProject = await update_project({
  id: projectId,
  status: "in-development",
  summary: "Updated summary based on kickoff meeting"
});

// Update sections
const overviewSection = project.data.sections.find(
  s => s.title === "Project Overview"
);

await update_section_text({
  id: overviewSection.id,
  oldText: "## Goals\nTBD",
  newText: "## Goals\n- Goal 1: Launch by Q2\n- Goal 2: Support 10k users"
});
```

## Performance Considerations

### Token Usage by Configuration

| Configuration | Approximate Tokens | Use Case |
|---------------|-------------------|----------|
| Basic (no flags) | ~200 | Status check |
| With features | ~500-1000 | Feature planning |
| With sections | ~1000-3000 | Documentation review |
| With features + tasks | ~1000-2000 | Project overview |
| Full (all flags) | ~3000-6000 | Complete context |
| Summary view | ~500-800 | Multi-project context |

### Optimization Strategies

1. **Start Minimal**: Get basic info first, expand as needed
2. **Use Two-Step Pattern**: Browse sections metadata, then load specific sections
3. **Summary View**: Use for multi-project context building
4. **Limit Counts**: Set maxFeatureCount and maxTaskCount appropriately
5. **Selective Loading**: Only enable flags you actually need

## Error Handling

### Project Not Found
```json
{
  "success": false,
  "message": "Project not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No project exists with ID b160fbdb-07e4-42d7-8c61-8deac7d2fc17"
  }
}
```

**Common causes**:
- Project was deleted
- Wrong UUID provided
- Project doesn't exist yet

**Solution**: Verify project ID using `search_projects` or `get_overview`

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

### Invalid Count Parameters
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "maxFeatureCount must be at least 1"
  }
}
```

**Solution**: Ensure count parameters are within valid range (1-100)

## Best Practices

1. **Start with Metadata**: Get basic info before loading full content
2. **Use includeSections=true**: When working on project details
3. **Check Feature Lists**: Before diving into individual features
4. **Enable Summary View**: When building context across multiple projects
5. **Cache Project Data**: Store in working memory during review session
6. **Selective Section Loading**: Use get_sections with sectionIds for efficiency
7. **Monitor Token Usage**: Use appropriate flags for your use case

## Related Tools

- **get_sections**: More efficient way to load specific sections
- **search_projects**: Find projects before retrieving
- **update_project**: Modify project metadata
- **delete_project**: Remove project (with cascade options)
- **create_project**: Create new projects
- **get_overview**: See all projects at once
- **project_to_markdown**: Export project as markdown

## See Also

- Project Management Patterns: `task-orchestrator://guidelines/project-management`
- Context Efficiency: `task-orchestrator://guidelines/usage-overview`
- Section Management: `task-orchestrator://docs/tools/get-sections`
