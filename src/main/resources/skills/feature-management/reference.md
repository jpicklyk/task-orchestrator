# Feature Management Skill - MCP Tool Reference

This document provides detailed reference for all MCP tools available to the Feature Management Skill.

## Tools Available

The Feature Management Skill has access to these Task Orchestrator MCP tools:

1. `mcp__task-orchestrator__get_feature`
2. `mcp__task-orchestrator__get_next_task`
3. `mcp__task-orchestrator__update_feature`
4. `mcp__task-orchestrator__add_section`
5. `mcp__task-orchestrator__get_blocked_tasks`
6. `mcp__task-orchestrator__search_tasks`

---

## 1. get_feature

**Purpose**: Retrieve detailed information about a feature.

### Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| id | UUID | Yes | - | Feature identifier |
| includeSections | boolean | No | false | Include detailed content sections |
| includeTasks | boolean | No | false | Include associated tasks |
| maxTaskCount | integer | No | 10 | Maximum tasks to include (1-100) |
| includeTaskCounts | boolean | No | false | Include task statistics by status |
| includeTaskDependencies | boolean | No | false | Include dependency info for tasks |
| summaryView | boolean | No | false | Truncate text fields for efficiency |

### Common Usage Patterns

**Basic feature info**:
```javascript
get_feature(id='f1234567-89ab-cdef-0123-456789abcdef')
```

**Feature with task overview** (for progress checking):
```javascript
get_feature(
  id='f1234567-89ab-cdef-0123-456789abcdef',
  includeTasks=true,
  includeTaskCounts=true
)
```

**Feature with task dependencies** (for next task recommendation):
```javascript
get_feature(
  id='f1234567-89ab-cdef-0123-456789abcdef',
  includeTasks=true,
  includeTaskDependencies=true,
  includeTaskCounts=true
)
```

**Feature with full content** (for feature completion):
```javascript
get_feature(
  id='f1234567-89ab-cdef-0123-456789abcdef',
  includeTasks=true,
  includeSections=true,
  includeTaskCounts=true
)
```

### Response Structure

```json
{
  "success": true,
  "data": {
    "id": "f1234567-89ab-cdef-0123-456789abcdef",
    "name": "User Authentication System",
    "summary": "Feature summary text",
    "description": "Feature description text",
    "status": "in_development",
    "priority": "high",
    "createdAt": "2025-10-18T10:00:00Z",
    "modifiedAt": "2025-10-18T14:30:00Z",
    "projectId": "p0000000-0000-0000-0000-000000000000",
    "tags": ["auth", "security", "backend"],
    "taskCounts": {
      "byStatus": {
        "pending": 2,
        "in_progress": 1,
        "completed": 4,
        "cancelled": 0
      }
    },
    "tasks": [
      {
        "id": "t1111111-89ab-cdef-0123-456789abcdef",
        "title": "Database schema design",
        "status": "completed",
        "priority": "high",
        "complexity": 5,
        "summary": "Created users, sessions, and tokens tables",
        "dependencies": {
          "incoming": [],
          "outgoing": ["t2222222-89ab-cdef-0123-456789abcdef"]
        }
      }
    ],
    "sections": [
      {
        "id": "s1111111-89ab-cdef-0123-456789abcdef",
        "title": "Requirements",
        "content": "...",
        "contentFormat": "markdown",
        "ordinal": 0
      }
    ]
  }
}
```

---

## 2. get_next_task

**Purpose**: Get recommended next unblocked task(s) to work on in a feature.

### Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| featureId | UUID | Yes | - | Feature to get tasks from |
| limit | integer | No | 1 | Maximum tasks to return (1-10) |
| includeDetails | boolean | No | false | Include task details and dependencies |

### Key Behavior

- **Automatically filters blocked tasks**: Only returns tasks with all dependencies completed
- **Automatically filters in-progress tasks**: Returns only pending tasks
- **Automatically filters completed tasks**: Returns only actionable work
- **Sorts by priority and complexity**: High priority, lower complexity first
- **Returns empty array if no tasks available**: All tasks completed, in-progress, or blocked

### Common Usage Patterns

**Get single next task**:
```javascript
get_next_task(
  featureId='f1234567-89ab-cdef-0123-456789abcdef',
  limit=1,
  includeDetails=true
)
```

**Get batch of tasks for parallel work**:
```javascript
get_next_task(
  featureId='f1234567-89ab-cdef-0123-456789abcdef',
  limit=5,
  includeDetails=true
)
```

### Response Structure

```json
{
  "success": true,
  "data": {
    "tasks": [
      {
        "id": "t2222222-89ab-cdef-0123-456789abcdef",
        "title": "Login API endpoint",
        "summary": "Create POST /api/auth/login",
        "status": "pending",
        "priority": "high",
        "complexity": 7,
        "tags": ["backend", "api", "auth"],
        "dependencies": {
          "incoming": [
            {
              "id": "t1111111-89ab-cdef-0123-456789abcdef",
              "title": "Database schema design",
              "status": "completed"
            }
          ],
          "outgoing": []
        },
        "reason": "All dependencies completed, highest priority unblocked task"
      }
    ],
    "totalUnblocked": 1,
    "totalRemaining": 3
  }
}
```

---

## 3. update_feature

**Purpose**: Update feature properties (status, summary, priority, etc.).

### Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| id | UUID | Yes | - | Feature identifier |
| name | string | No | - | Feature name |
| summary | string | No | - | Brief summary (max 500 chars) |
| description | string | No | - | Detailed description |
| status | enum | No | - | planning, in_development, testing, completed, cancelled, on_hold |
| priority | enum | No | - | low, medium, high, critical |
| tags | string | No | - | Comma-separated tags |

### Common Usage Patterns

**Update status to in_development** (when starting first task):
```javascript
update_feature(
  id='f1234567-89ab-cdef-0123-456789abcdef',
  status='in_development'
)
```

**Update summary field** (during feature completion):
```javascript
update_feature(
  id='f1234567-89ab-cdef-0123-456789abcdef',
  summary='Implemented complete user authentication system with JWT tokens, password hashing, and React UI. All 127 tests passing.'
)
```

**Mark feature complete**:
```javascript
update_feature(
  id='f1234567-89ab-cdef-0123-456789abcdef',
  status='completed'
)
```

### Important Notes

- **summary field**: Brief outcome (max 500 chars), what was delivered
- **description field**: Forward-looking requirements, usually set by Feature Architect (don't modify during completion)
- **Status transitions**: planning → in_development → testing → completed
- **Only provide parameters you want to change**: Omit others to keep existing values

---

## 4. add_section

**Purpose**: Add a section to a feature (used for Summary section during completion).

### Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| entityType | enum | Yes | - | FEATURE, TASK, or PROJECT |
| entityId | UUID | Yes | - | Entity identifier |
| title | string | Yes | - | Section title |
| usageDescription | string | Yes | - | How this section should be used |
| content | string | Yes | - | Section content |
| contentFormat | enum | No | MARKDOWN | MARKDOWN, PLAIN_TEXT, JSON, CODE |
| ordinal | integer | Yes | - | Display order (0-based) |
| tags | string | No | - | Comma-separated tags |

### Common Usage Patterns

**Create feature Summary section** (during feature completion):
```javascript
add_section(
  entityType='FEATURE',
  entityId='f1234567-89ab-cdef-0123-456789abcdef',
  title='Summary',
  usageDescription='Summary of completed feature work including tasks, files, and technical decisions',
  content='### What Was Built\n\n[content]...',
  contentFormat='MARKDOWN',
  ordinal=999,
  tags='summary,completion'
)
```

### Summary Section Content Template

```markdown
### What Was Built
[2-3 sentences describing the feature outcome and value delivered]

### Tasks Completed
1. **[Task 1 title]** - [1 sentence: what it did]
2. **[Task 2 title]** - [1 sentence: what it did]

### Files Changed
- `path/to/file1.kt` - [what changed]
- `path/to/file2.kt` - [what changed]

### Technical Decisions
- [Key decision 1 and rationale]
- [Key decision 2 and rationale]

### Testing
[Brief overview of test coverage]

### Integration Points
[What systems/components this feature integrates with]

### Next Steps
[Any follow-up work or technical debt noted]
```

### Important Notes

- **ordinal=999**: High ordinal ensures Summary appears last
- **Don't duplicate title in content**: Section title becomes H2 heading automatically
- **Use tags**: 'summary,completion' helps identify completion sections
- **Markdown format**: Preferred for human readability

---

## 5. get_blocked_tasks

**Purpose**: Get all tasks in a feature that are blocked by incomplete dependencies.

### Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| featureId | UUID | Yes | - | Feature identifier |

### Common Usage Patterns

**List all blocked tasks**:
```javascript
get_blocked_tasks(featureId='f1234567-89ab-cdef-0123-456789abcdef')
```

### Response Structure

```json
{
  "success": true,
  "data": {
    "blockedTasks": [
      {
        "task": {
          "id": "t5555555-89ab-cdef-0123-456789abcdef",
          "title": "Integration Tests",
          "status": "pending",
          "priority": "high"
        },
        "blockedBy": [
          {
            "id": "t2222222-89ab-cdef-0123-456789abcdef",
            "title": "Login API endpoint",
            "status": "in_progress"
          },
          {
            "id": "t3333333-89ab-cdef-0123-456789abcdef",
            "title": "Login UI component",
            "status": "in_progress"
          }
        ],
        "blockedCount": 2
      }
    ],
    "totalBlocked": 1,
    "featureId": "f1234567-89ab-cdef-0123-456789abcdef"
  }
}
```

### Usage Tips

- Use this when `get_next_task` returns no results but pending tasks exist
- Helps identify which dependencies to prioritize
- Shows impact of each blocker (how many tasks it blocks)

---

## 6. search_tasks

**Purpose**: Search for tasks with filtering and sorting options.

### Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| featureId | UUID | No | - | Filter by feature |
| status | enum | No | - | pending, in_progress, completed, cancelled |
| priority | enum | No | - | low, medium, high, critical |
| tags | string | No | - | Comma-separated tags (ANY match) |
| textQuery | string | No | - | Search in title and summary |
| sortBy | enum | No | priority | priority, complexity, createdAt, modifiedAt |
| sortOrder | enum | No | desc | asc, desc |
| limit | integer | No | 50 | Maximum results (1-100) |

### Common Usage Patterns

**Find all in-progress tasks in feature**:
```javascript
search_tasks(
  featureId='f1234567-89ab-cdef-0123-456789abcdef',
  status='in_progress'
)
```

**Find all backend tasks**:
```javascript
search_tasks(
  featureId='f1234567-89ab-cdef-0123-456789abcdef',
  tags='backend'
)
```

**Find high priority pending tasks**:
```javascript
search_tasks(
  featureId='f1234567-89ab-cdef-0123-456789abcdef',
  status='pending',
  priority='high',
  sortBy='complexity'
)
```

### Response Structure

```json
{
  "success": true,
  "data": {
    "tasks": [
      {
        "id": "t2222222-89ab-cdef-0123-456789abcdef",
        "title": "Login API endpoint",
        "summary": "Create POST /api/auth/login",
        "status": "in_progress",
        "priority": "high",
        "complexity": 7,
        "tags": ["backend", "api"],
        "createdAt": "2025-10-18T10:00:00Z",
        "modifiedAt": "2025-10-18T14:30:00Z"
      }
    ],
    "totalResults": 1,
    "limit": 50
  }
}
```

---

## Tool Selection Guide

**Use get_feature when**:
- Getting overall feature status
- Checking task counts by status
- Need task details with dependencies
- Completing feature (need all sections)

**Use get_next_task when**:
- Recommending what to work on next
- Want only unblocked, actionable tasks
- Need automatic dependency filtering
- Starting or continuing feature work

**Use update_feature when**:
- Changing feature status (planning → in_development → completed)
- Updating summary field (brief outcome)
- Marking feature complete

**Use add_section when**:
- Creating Summary section during completion
- Adding detailed content to feature
- Documenting feature outcomes

**Use get_blocked_tasks when**:
- Understanding why work is stuck
- get_next_task returns no results but tasks remain
- Prioritizing which blockers to resolve
- Analyzing dependency chains

**Use search_tasks when**:
- Finding specific tasks by criteria
- Filtering by tags or priority
- Custom queries not covered by get_next_task
- Need flexible search capabilities

---

## Error Handling

All tools return standardized responses:

**Success**:
```json
{
  "success": true,
  "data": { ... },
  "message": "Operation completed successfully"
}
```

**Error**:
```json
{
  "success": false,
  "error": "Error message describing what went wrong",
  "data": null
}
```

### Common Errors

- `Feature not found`: Invalid feature ID
- `Invalid status value`: Status not in allowed enum
- `No tasks found`: Feature has no tasks matching criteria
- `Permission denied`: Tool not in allowed-tools list (shouldn't happen with Skill)

---

## Performance Considerations

**Token Usage**:
- `includeSections=true`: Adds significant tokens (full section content)
- `includeTasks=true`: Moderate tokens (task list)
- `includeTaskCounts=true`: Minimal tokens (just counts)
- `summaryView=true`: Reduces tokens (truncates text fields)

**Recommendations**:
- Use `includeTaskCounts=true` for progress checks (lightweight)
- Use `includeSections=true` only when needed (feature completion)
- Use `get_next_task` instead of searching task list manually (automatic filtering)
- Limit task count with `maxTaskCount` if feature has many tasks

---

## Related Skills

- **Task Management Skill**: For working with individual tasks
- **Dependency Analysis Skill**: For deep dependency investigation
- **Planning Specialist** (subagent): For complex feature planning and task breakdown
