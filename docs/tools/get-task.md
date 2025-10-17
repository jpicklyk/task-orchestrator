# get_task Tool - Detailed Documentation

## Overview

Retrieves complete task information with options to include related entities like sections, subtasks, dependencies, and feature information.

**Resource**: `task-orchestrator://docs/tools/get-task`

## Key Concepts

### Task Content Architecture
Tasks store their detailed content in **separate Section entities** for efficiency:
- Task entity: Lightweight metadata (title, status, priority, etc.)
- Section entities: Detailed content blocks (requirements, implementation notes, etc.)

**CRITICAL**: To get the complete task with all content, set `includeSections=true`.

### Context Efficiency Strategy
Use parameter flags to control what data is loaded:
- Minimal fetch: Just task metadata (~200 tokens)
- Full fetch: Task + sections + dependencies + feature (~2000+ tokens)

## Parameter Reference

### Required Parameters
- **id** (UUID): Task ID to retrieve

### Optional Parameters
- **includeSections** (boolean, default: false): Include section content blocks
- **includeSubtasks** (boolean, default: false): Include subtasks (experimental)
- **includeDependencies** (boolean, default: false): Include dependency information
- **includeFeature** (boolean, default: false): Include parent feature details
- **summaryView** (boolean, default: false): Return truncated text for context efficiency

## Usage Patterns

### Pattern 1: Quick Metadata Check
Get task status, priority, and basic info without loading sections.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response size**: ~200 tokens
**When to use**:
- Check task status
- Get task title/summary
- Quick verification
- When you already know the content

### Pattern 2: Full Context Retrieval
Get everything needed to work on the task.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "includeSections": true,
  "includeDependencies": true,
  "includeFeature": true
}
```

**Response size**: ~2000-5000 tokens (varies by content)
**When to use**:
- Starting work on a task
- Need complete context
- Implementing the task
- Understanding requirements

### Pattern 3: Summary View for Context
Get truncated view for context without full content.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "includeSections": true,
  "summaryView": true
}
```

**Response size**: ~500 tokens
**When to use**:
- Building context across multiple tasks
- Overview of task content
- When full content isn't needed

### Pattern 4: Dependency Analysis
Get task with dependency information to understand blockers.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "includeDependencies": true
}
```

**Response size**: ~300-400 tokens
**When to use**:
- Planning work order
- Understanding blockers
- Checking if task is ready to start

### Pattern 5: Feature Context
Get task with parent feature details.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "includeFeature": true
}
```

**Response size**: ~400-600 tokens
**When to use**:
- Understanding task in feature context
- Checking feature status
- Ensuring alignment with feature goals

## Two-Step Efficient Pattern

For large tasks, use a two-step approach to minimize token usage:

### Step 1: Get Section Metadata Only
```javascript
// First, see what sections exist
const sections = await get_sections({
  entityType: "TASK",
  entityId: "550e8400-e29b-41d4-a716-446655440000",
  includeContent: false
});

// Review section titles and tags
// sections.data.sections = [
//   { id: "uuid1", title: "Requirements", tags: ["requirements"] },
//   { id: "uuid2", title: "Implementation Notes", tags: ["implementation"] },
//   { id: "uuid3", title: "Testing Strategy", tags: ["testing"] }
// ]
```

**Token savings**: 85-99% compared to loading all content

### Step 2: Load Specific Sections
```javascript
// Then, load only the sections you need
const requirements = await get_sections({
  entityType: "TASK",
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
  "message": "Task retrieved successfully",
  "data": {
    "id": "uuid",
    "title": "Task title",
    "summary": "Task summary",
    "status": "in-progress",
    "priority": "high",
    "complexity": 7,
    "createdAt": "ISO-8601",
    "modifiedAt": "ISO-8601",
    "featureId": "uuid-or-null",
    "projectId": "uuid-or-null",
    "tags": ["tag1", "tag2"]
  }
}
```

### Full Response (all optional parameters enabled)
```json
{
  "success": true,
  "message": "Task retrieved successfully",
  "data": {
    "id": "uuid",
    "title": "Implement OAuth authentication",
    "summary": "Add JWT-based authentication with OAuth providers",
    "status": "in-progress",
    "priority": "high",
    "complexity": 7,
    "createdAt": "ISO-8601",
    "modifiedAt": "ISO-8601",
    "featureId": "feature-uuid",
    "tags": ["authentication", "backend", "oauth"],
    "feature": {
      "id": "feature-uuid",
      "name": "User Authentication System",
      "status": "in-development"
    },
    "sections": [
      {
        "id": "section-uuid",
        "title": "Requirements",
        "content": "Detailed requirements...",
        "contentFormat": "MARKDOWN",
        "ordinal": 0,
        "tags": ["requirements"]
      }
    ],
    "dependencies": {
      "incoming": [
        {
          "id": "dep-uuid",
          "fromTask": {
            "id": "blocker-task-uuid",
            "title": "Create database schema",
            "status": "completed"
          },
          "type": "BLOCKS"
        }
      ],
      "outgoing": [],
      "counts": {
        "total": 1,
        "incoming": 1,
        "outgoing": 0
      }
    }
  }
}
```

## Common Workflows

### Workflow 1: Start Working on Task
```javascript
// Step 1: Get full task context
const task = await get_task({
  id: taskId,
  includeSections: true,
  includeDependencies: true,
  includeFeature: true
});

// Step 2: Check if task is blocked
if (task.data.dependencies?.incoming.some(d => d.fromTask.status !== 'completed')) {
  console.log("Task is blocked by incomplete dependencies");
  return;
}

// Step 3: Update status to in-progress
await set_status({
  id: taskId,
  status: "in-progress"
});

// Step 4: Read requirements section
const requirements = task.data.sections.find(s => s.title === "Requirements");
```

### Workflow 2: Check Task Status
```javascript
// Quick status check without loading sections
const task = await get_task({ id: taskId });

console.log(`Task: ${task.data.title}`);
console.log(`Status: ${task.data.status}`);
console.log(`Priority: ${task.data.priority}`);
```

### Workflow 3: Update Task Content
```javascript
// Get task with sections to update content
const task = await get_task({
  id: taskId,
  includeSections: true
});

// Find section to update
const implSection = task.data.sections.find(s => s.title === "Implementation Notes");

// Update section content
await update_section_text({
  id: implSection.id,
  oldText: "TODO: Add implementation details",
  newText: "Implemented using JWT library with RS256 signing"
});
```

## Performance Considerations

### Token Usage by Configuration

| Configuration | Approximate Tokens | Use Case |
|---------------|-------------------|----------|
| Basic (no flags) | ~200 | Status check |
| With dependencies | ~400 | Planning |
| With feature | ~500 | Context |
| With sections | ~2000-5000 | Implementation |
| Full (all flags) | ~3000-6000 | Complete context |
| Summary view | ~500 | Overview |

### Optimization Strategies

1. **Start Minimal**: Get basic info first, expand as needed
2. **Use Two-Step Pattern**: Browse sections metadata, then load specific sections
3. **Summary View**: Use for multi-task context building
4. **Cache Results**: Store task data locally during work session
5. **Selective Loading**: Only enable flags you actually need

## Error Handling

### Task Not Found
```json
{
  "success": false,
  "message": "Task not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No task exists with ID ..."
  }
}
```

**Common causes**:
- Task was deleted
- Wrong UUID provided
- Task doesn't exist yet

**Solution**: Verify task ID using `search_tasks` or `get_overview`

### Invalid UUID Format
```json
{
  "success": false,
  "message": "Invalid input",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid task ID format. Must be a valid UUID."
  }
}
```

**Solution**: Ensure ID is a valid UUID format

## Best Practices

1. **Start with Metadata**: Get basic info before loading full content
2. **Use includeSections=true**: When working on task implementation
3. **Check Dependencies**: Before starting work, verify no blockers
4. **Enable Summary View**: When building context across multiple tasks
5. **Cache Task Data**: Store in working memory during implementation
6. **Selective Section Loading**: Use get_sections with sectionIds for efficiency
7. **Include Feature Context**: When task is part of larger feature work

## Related Tools

- **get_sections**: More efficient way to load specific sections
- **search_tasks**: Find tasks before retrieving
- **update_task**: Modify task metadata
- **set_status**: Update task status efficiently
- **get_task_dependencies**: Get detailed dependency information
- **get_feature**: Get parent feature details

## See Also

- Task Management Patterns: `task-orchestrator://guidelines/task-management`
- Context Efficiency: `task-orchestrator://guidelines/usage-overview`
