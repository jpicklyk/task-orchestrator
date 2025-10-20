# query_container Tool - Detailed Documentation

## Overview

The `query_container` tool provides unified read-only operations for all container types (projects, features, tasks). It
consolidates multiple former individual tools into a single, efficient interface with four operations: `get`, `search`,
`export`, and `overview`.

**Key Feature (v2.0+):** The `overview` operation supports both **global** and **scoped** modes, enabling 85-90% token
reduction for "show details" queries.

**Resource**: `task-orchestrator://docs/tools/query-container`

## Key Concepts

### Unified Container Interface

All three container types share a single, consistent query interface:

- **Projects** - Top-level organization containers
- **Features** - Groups of related tasks within projects
- **Tasks** - Individual work items within features

### Four Core Operations

1. **get** - Retrieve a specific container by ID (with optional sections)
2. **search** - Find containers matching filter criteria
3. **export** - Export container to markdown format
4. **overview** - Get hierarchical view (global or scoped)

### Token Efficiency Philosophy

v2.0 query_container emphasizes **token efficiency**:

- **Minimal search results**: 89% token reduction (30 tokens vs 280 per task)
- **Scoped overview**: 85-90% reduction vs get with sections
- **Task counts**: 99% reduction (100 tokens vs 14,400 for 50 tasks)

## Parameter Reference

### Common Parameters (All Operations)

| Parameter       | Type | Required | Description                                      |
|-----------------|------|----------|--------------------------------------------------|
| `operation`     | enum | **Yes**  | Operation: `get`, `search`, `export`, `overview` |
| `containerType` | enum | **Yes**  | Container type: `project`, `feature`, `task`     |

### Operation-Specific Parameters

| Parameter         | Type    | Operations             | Description                                       |
|-------------------|---------|------------------------|---------------------------------------------------|
| `id`              | UUID    | get, export, overview* | Container ID (*optional for overview)             |
| `query`           | string  | search                 | Text search in title/name/description             |
| `status`          | string  | search                 | Filter by status (supports multi-value, negation) |
| `priority`        | string  | search                 | Filter by priority (feature/task only)            |
| `tags`            | string  | search                 | Comma-separated tags filter                       |
| `projectId`       | UUID    | search                 | Filter by project (feature/task)                  |
| `featureId`       | UUID    | search                 | Filter by feature (task only)                     |
| `limit`           | integer | search                 | Max results (default: 20, max: 100)               |
| `includeSections` | boolean | get                    | Include section content (default: false)          |
| `summaryLength`   | integer | overview               | Summary truncation (0-200, default: 100)          |

### Filter Syntax (Search Operation)

**Single value**: `status="pending"`
**Multi-value (OR)**: `status="pending,in-progress"` (matches pending OR in-progress)
**Negation**: `status="!completed"` (matches anything EXCEPT completed)
**Multi-negation**: `status="!completed,!cancelled"` (matches anything EXCEPT completed or cancelled)
**Priority filters**: `priority="high,medium"` or `priority="!low"`

---

## Operation 1: get

**Purpose**: Retrieve a specific container by ID with complete metadata

### Required Parameters

- `operation`: "get"
- `containerType`: "project", "feature", or "task"
- `id`: Container UUID

### Optional Parameters

- `includeSections`: Include section content (default: false)

### Example - Get Feature (without sections)

```json
{
  "operation": "get",
  "containerType": "feature",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "includeSections": false
}
```

**Response**:

```json
{
  "success": true,
  "message": "Feature retrieved successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "User Authentication System",
    "status": "in-development",
    "priority": "high",
    "summary": "Comprehensive authentication with OAuth support",
    "description": "Full authentication system including JWT tokens, OAuth providers, password reset, and session management",
    "tags": [
      "backend",
      "security",
      "authentication"
    ],
    "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "createdAt": "2025-01-15T10:30:00Z",
    "modifiedAt": "2025-01-20T14:45:00Z",
    "taskCounts": {
      "total": 20,
      "byStatus": {
        "completed": 15,
        "in-progress": 3,
        "pending": 2
      }
    }
  }
}
```

**Token Cost**: ~200-400 tokens (without sections)

### Example - Get Task (with sections)

```json
{
  "operation": "get",
  "containerType": "task",
  "id": "640522b7-810e-49a2-865c-3725f5d39608",
  "includeSections": true
}
```

**Response**:

```json
{
  "success": true,
  "message": "Task retrieved successfully",
  "data": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "title": "Implement OAuth Google provider",
    "summary": "Add Google OAuth 2.0 authentication",
    "status": "in-progress",
    "priority": "high",
    "complexity": 7,
    "featureId": "550e8400-e29b-41d4-a716-446655440000",
    "tags": [
      "backend",
      "oauth",
      "authentication"
    ],
    "createdAt": "2025-01-18T09:00:00Z",
    "modifiedAt": "2025-01-20T11:30:00Z",
    "sections": [
      {
        "id": "section-uuid-1",
        "title": "Requirements",
        "content": "Full requirements documentation...",
        "contentFormat": "MARKDOWN",
        "ordinal": 0
      },
      {
        "id": "section-uuid-2",
        "title": "Implementation Plan",
        "content": "Detailed implementation steps...",
        "contentFormat": "MARKDOWN",
        "ordinal": 10
      }
    ]
  }
}
```

**Token Cost**: ~18,000-25,000 tokens (with sections - very expensive!)

### When to Use Get

✅ **Use get when:**

- Editing entity properties
- Need complete metadata for a specific entity
- User explicitly asks for "full documentation"
- Preparing to update entity

❌ **Avoid get with sections when:**

- Just viewing details (use scoped overview instead)
- Checking status (use search or scoped overview)
- Need hierarchical view (use scoped overview)

---

## Operation 2: search

**Purpose**: Find containers matching filter criteria with minimal response format

### Required Parameters

- `operation`: "search"
- `containerType`: "project", "feature", or "task"

### Optional Parameters

- `query`: Text search
- `status`: Status filter (supports multi-value, negation)
- `priority`: Priority filter (feature/task only)
- `tags`: Comma-separated tags
- `projectId`: Filter by project
- `featureId`: Filter by feature (task only)
- `limit`: Max results (default: 20)

### Example - Search High-Priority Pending Tasks

```json
{
  "operation": "search",
  "containerType": "task",
  "status": "pending",
  "priority": "high",
  "limit": 10
}
```

**Response (Minimal Format - 89% token reduction)**:

```json
{
  "success": true,
  "message": "Found 10 task(s)",
  "data": {
    "containers": [
      {
        "id": "task-uuid-1",
        "title": "Implement password reset flow",
        "status": "pending",
        "priority": "high",
        "complexity": 5,
        "featureId": "feature-uuid"
      },
      {
        "id": "task-uuid-2",
        "title": "Add rate limiting to auth endpoints",
        "status": "pending",
        "priority": "high",
        "complexity": 4,
        "featureId": "feature-uuid"
      }
    ],
    "totalCount": 10
  }
}
```

**Token Cost**: ~30 tokens per task (minimal format)

### Example - Search Features by Project

```json
{
  "operation": "search",
  "containerType": "feature",
  "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039",
  "status": "in-development,planning"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Found 3 feature(s)",
  "data": {
    "containers": [
      {
        "id": "feature-uuid-1",
        "name": "User Authentication System",
        "status": "in-development",
        "priority": "high",
        "projectId": "project-uuid"
      },
      {
        "id": "feature-uuid-2",
        "name": "Payment Integration",
        "status": "planning",
        "priority": "medium",
        "projectId": "project-uuid"
      }
    ],
    "totalCount": 3
  }
}
```

### Example - Search with Negation Filters

```json
{
  "operation": "search",
  "containerType": "task",
  "status": "!completed,!cancelled",
  "priority": "!low",
  "featureId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Meaning**: Find tasks that are NOT completed or cancelled, with priority NOT low

### When to Use Search

✅ **Use search when:**

- Finding entities by criteria
- Building lists or dashboards
- Filtering by status, priority, tags
- Need multiple matching entities
- Building reports or summaries

---

## Operation 3: export

**Purpose**: Export container to markdown format for external use

### Required Parameters

- `operation`: "export"
- `containerType`: "project", "feature", or "task"
- `id`: Container UUID

### Example - Export Feature to Markdown

```json
{
  "operation": "export",
  "containerType": "feature",
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Feature exported to markdown successfully",
  "data": {
    "markdown": "---\ntitle: User Authentication System\nstatus: in-development\npriority: high\ntags: backend, security, authentication\n---\n\n# User Authentication System\n\n**Status:** in-development\n**Priority:** high\n**Project:** E-Commerce Platform\n\n## Summary\n\nComprehensive authentication with OAuth support\n\n## Description\n\nFull authentication system including JWT tokens, OAuth providers, password reset, and session management\n\n## Tasks\n\n- [x] Implement JWT token generation (completed)\n- [x] Add token refresh endpoint (completed)\n- [ ] Implement OAuth Google provider (in-progress)\n- [ ] Add password reset flow (pending)\n\n## Sections\n\n### Requirements\n\n[Full section content...]\n\n### Architecture\n\n[Full section content...]\n",
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "type": "feature"
  }
}
```

**Token Cost**: ~15,000-20,000 tokens (includes full section content)

### When to Use Export

✅ **Use export when:**

- Generating reports for external tools
- Creating documentation snapshots
- Exporting for version control
- Sharing with external systems
- User explicitly requests markdown format

❌ **Avoid export when:**

- Just viewing details (use scoped overview)
- Need structured data (use get or overview)
- Token efficiency matters (export is expensive)

---

## Operation 4: overview

**Purpose**: Get hierarchical view with two modes - global or scoped

### Overview Has Two Modes

1. **Global Overview** (without `id`): Returns list of all containers
2. **Scoped Overview** (with `id`): Returns specific container + hierarchy ⭐ **NEW in v2.0**

---

## Global Overview (without id)

**Purpose**: Get overview of all containers of specified type

### Required Parameters

- `operation`: "overview"
- `containerType`: "project", "feature", or "task"

### Optional Parameters

- `summaryLength`: Summary truncation (0-200, default: 100)

### Example - Global Feature Overview

```json
{
  "operation": "overview",
  "containerType": "feature"
}
```

**Response**:

```json
{
  "success": true,
  "message": "7 feature(s) retrieved",
  "data": {
    "items": [
      {
        "id": "feature-uuid-1",
        "name": "User Authentication System",
        "status": "in-development",
        "priority": "high",
        "summary": "Comprehensive authentication with OAuth support. Includes JWT tokens, OAuth providers, password...",
        "tags": "backend, security, authentication"
      },
      {
        "id": "feature-uuid-2",
        "name": "Payment Integration",
        "status": "planning",
        "priority": "medium",
        "summary": "Integrate Stripe and PayPal payment processors with webhook handling...",
        "tags": "backend, payments, integration"
      }
    ],
    "count": 7
  }
}
```

**Token Cost**: ~500-2,000 tokens (depending on count)

### When to Use Global Overview

✅ **Use global overview when:**

- "List all features"
- "Show me all projects"
- "What tasks exist?"
- High-level dashboard view
- Getting overall system status

---

## Scoped Overview (with id) ⭐ NEW IN V2.0

**Purpose**: Return specific container with hierarchical data (children + counts) WITHOUT section content

### Required Parameters

- `operation`: "overview"
- `containerType`: "project", "feature", or "task"
- `id`: Container UUID (enables scoped mode)

### Optional Parameters

- `summaryLength`: Summary truncation (0-200, default: 100)

### Scoped Overview by Container Type

| Container Type | Returns                                           |
|----------------|---------------------------------------------------|
| **Project**    | Project metadata + features list + task counts    |
| **Feature**    | Feature metadata + tasks list + task counts       |
| **Task**       | Task metadata + dependencies (blocking/blockedBy) |

---

### Example - Scoped Feature Overview

```json
{
  "operation": "overview",
  "containerType": "feature",
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Feature overview retrieved",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "v2.0 Container-Based Tool Consolidation",
    "status": "completed",
    "priority": "high",
    "summary": "Transform architecture into streamlined design with 71% token reduction...",
    "tags": "v2.0, consolidation, token-optimization",
    "projectId": "project-uuid",
    "createdAt": "2025-10-15T10:00:00Z",
    "modifiedAt": "2025-10-19T15:00:00Z",
    "taskCounts": {
      "total": 26,
      "byStatus": {
        "completed": 26
      }
    },
    "tasks": [
      {
        "id": "task-uuid-1",
        "title": "Implement ManageContainerTool",
        "status": "completed",
        "priority": "high",
        "complexity": 9
      },
      {
        "id": "task-uuid-2",
        "title": "Implement QueryContainerTool",
        "status": "completed",
        "priority": "high",
        "complexity": 8
      }
    ]
  }
}
```

**Token Cost**: ~800-2,000 tokens (hierarchical data, NO sections)

**Token Efficiency**: 85-90% reduction vs `get` with `includeSections=true`

---

### Example - Scoped Project Overview

```json
{
  "operation": "overview",
  "containerType": "project",
  "id": "a4fae8cb-7640-4527-bd89-11effbb1d039"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Project overview retrieved",
  "data": {
    "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "name": "MCP Task Orchestrator",
    "status": "in-development",
    "summary": "Model Context Protocol server for AI-driven task management...",
    "tags": "mcp, kotlin, task-management",
    "createdAt": "2024-12-01T10:00:00Z",
    "modifiedAt": "2025-10-19T18:00:00Z",
    "taskCounts": {
      "total": 150,
      "byStatus": {
        "completed": 120,
        "in-progress": 20,
        "pending": 10
      }
    },
    "features": [
      {
        "id": "feature-uuid-1",
        "name": "v2.0 Container-Based Tool Consolidation",
        "status": "completed",
        "priority": "high",
        "taskCount": 26
      },
      {
        "id": "feature-uuid-2",
        "name": "Scoped Overview for Query Container",
        "status": "planning",
        "priority": "high",
        "taskCount": 10
      }
    ]
  }
}
```

**Token Cost**: ~1,500-2,500 tokens (project + features + counts)

---

### Example - Scoped Task Overview

```json
{
  "operation": "overview",
  "containerType": "task",
  "id": "640522b7-810e-49a2-865c-3725f5d39608"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Task overview retrieved",
  "data": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "title": "Implement OAuth Google provider",
    "summary": "Add Google OAuth 2.0 authentication with token management...",
    "status": "in-progress",
    "priority": "high",
    "complexity": 7,
    "featureId": "550e8400-e29b-41d4-a716-446655440000",
    "tags": "backend, oauth, authentication",
    "createdAt": "2025-01-18T09:00:00Z",
    "modifiedAt": "2025-01-20T11:30:00Z",
    "dependencies": {
      "blocking": [
        {
          "id": "task-uuid-1",
          "title": "Implement JWT token generation",
          "status": "completed"
        }
      ],
      "blockedBy": [
        {
          "id": "task-uuid-2",
          "title": "Set up OAuth configuration",
          "status": "in-progress"
        }
      ]
    }
  }
}
```

**Token Cost**: ~400-800 tokens (task + dependencies)

---

### When to Use Scoped Overview ⭐ BEST FOR DETAILS

✅ **Use scoped overview when:**

- "Show me Feature X details"
- "What's the status of Project Y?"
- "What tasks are in Feature Z?"
- "Give me details on Task W"
- Need hierarchical view without section content
- Want to see children (features for project, tasks for feature)
- Token efficiency is important

**This is THE recommended approach for "show details" queries**

---

## Operation Comparison Table

| Operation             | Mode       | Token Cost   | Use Case                       |
|-----------------------|------------|--------------|--------------------------------|
| `search`              | -          | ~30 per item | Find entities by filters       |
| `get` (no sections)   | -          | ~200-400     | Get metadata only              |
| `overview`            | Global     | ~500-2k      | "List all features"            |
| `overview`            | **Scoped** | **~800-2k**  | **"Show Feature X details"** ⭐ |
| `get` (with sections) | -          | ~18k-25k     | "Show full documentation"      |
| `export`              | -          | ~15k-20k     | Generate markdown report       |

**Key Insight**: Scoped overview provides 85-90% token savings vs get with sections while still showing hierarchical
relationships.

---

## Decision Tree: Which Operation to Use?

```
User asks: "Show me details on Feature X"
├─ Do they need section content (documentation)?
│  ├─ YES → Use get with includeSections=true (~18k tokens)
│  └─ NO → Use scoped overview (~800 tokens) ⭐ RECOMMENDED
│
User asks: "List all features in project"
├─ Global list → Use global overview (~1k tokens)
│
User asks: "Find all high-priority pending tasks"
├─ Search by criteria → Use search (~300 tokens)
│
User asks: "Export Feature X to markdown"
├─ Need markdown format → Use export (~15k tokens)
│
User asks: "What are the complete requirements for Task X?"
└─ Need full documentation → Use get with includeSections=true (~18k tokens)
```

---

## Common Workflows

### Workflow 1: Feature Status Check

**User**: "What's the status of the User Authentication feature?"

**Efficient approach**:

```json
{
  "operation": "overview",
  "containerType": "feature",
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Result**: ~800 tokens, shows feature status + task list + task counts

**Inefficient approach** (don't do this):

```json
{
  "operation": "get",
  "containerType": "feature",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "includeSections": true
}
```

**Result**: ~18,500 tokens (23x more expensive!)

---

### Workflow 2: Project Progress Review

**User**: "Show me progress on the MCP Task Orchestrator project"

```json
{
  "operation": "overview",
  "containerType": "project",
  "id": "a4fae8cb-7640-4527-bd89-11effbb1d039"
}
```

**Shows**: Project metadata + all features + task counts by status

---

### Workflow 3: Finding Work to Do

**Step 1**: Search for available tasks

```json
{
  "operation": "search",
  "containerType": "task",
  "status": "pending",
  "priority": "high",
  "limit": 5
}
```

**Step 2**: Get details on selected task (without heavy sections)

```json
{
  "operation": "overview",
  "containerType": "task",
  "id": "selected-task-id"
}
```

**Shows**: Task details + dependencies (what's blocking/blocked by this task)

---

### Workflow 4: Feature Deep Dive

**Step 1**: Get feature overview

```json
{
  "operation": "overview",
  "containerType": "feature",
  "id": "feature-id"
}
```

**Step 2**: If user needs documentation for specific task, then get with sections

```json
{
  "operation": "get",
  "containerType": "task",
  "id": "specific-task-id",
  "includeSections": true
}
```

**Efficiency**: Only load expensive section content when truly needed

---

## Best Practices

### 1. Default to Scoped Overview for "Show Details" Queries

❌ **Inefficient**:

```json
{
  "operation": "get",
  "containerType": "feature",
  "id": "...",
  "includeSections": true
}
```

**Cost**: 18,500 tokens

✅ **Efficient**:

```json
{
  "operation": "overview",
  "containerType": "feature",
  "id": "..."
}
```

**Cost**: 1,200 tokens (91% reduction)

### 2. Only Load Sections When Explicitly Needed

**Load sections when**:

- User asks for "documentation"
- User asks for "requirements"
- User needs to read/edit section content
- Preparing to work on implementation

**Don't load sections when**:

- Just checking status
- Reviewing task list
- Checking dependencies
- Building dashboards

### 3. Use Search for Discovery, Overview for Details

**Discovery** (find entities):

```json
{
  "operation": "search",
  "containerType": "feature",
  "status": "in-development"
}
```

**Details** (examine specific entity):

```json
{
  "operation": "overview",
  "containerType": "feature",
  "id": "selected-feature-id"
}
```

### 4. Leverage Filter Syntax for Precise Searches

**Multi-value OR**:

```json
{
  "operation": "search",
  "containerType": "task",
  "status": "pending,in-progress"
}
```

**Negation** (exclude):

```json
{
  "operation": "search",
  "containerType": "task",
  "status": "!completed,!cancelled"
}
```

### 5. Use Task Counts Instead of Fetching All Tasks

Features and projects returned by `get` or `overview` include `taskCounts`:

```json
{
  "taskCounts": {
    "total": 26,
    "byStatus": {
      "completed": 26
    }
  }
}
```

**This is 99% more efficient** than fetching all tasks just to count them.

---

## Error Handling

### Resource Not Found

**Request**:

```json
{
  "operation": "get",
  "containerType": "feature",
  "id": "non-existent-uuid"
}
```

**Response**:

```json
{
  "success": false,
  "message": "Feature not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No Feature exists with ID non-existent-uuid"
  }
}
```

### Validation Error (Invalid UUID)

**Request**:

```json
{
  "operation": "get",
  "containerType": "task",
  "id": "not-a-valid-uuid"
}
```

**Response**:

```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid UUID format for id parameter"
  }
}
```

### Missing Required Parameter

**Request**:

```json
{
  "operation": "get",
  "containerType": "feature"
}
```

**Response**:

```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "id parameter is required for get operation"
  }
}
```

### Database Error

**Response**:

```json
{
  "success": false,
  "message": "Database operation failed",
  "error": {
    "code": "DATABASE_ERROR",
    "details": "Connection timeout"
  }
}
```

---

## Performance Considerations

### Token Usage by Operation

| Operation | Configuration     | Typical Tokens | Use Case            |
|-----------|-------------------|----------------|---------------------|
| search    | 10 results        | ~300           | Find tasks          |
| get       | No sections       | ~200-400       | Metadata only       |
| get       | With sections     | ~18k-25k       | Full documentation  |
| overview  | Global (20 items) | ~1k-2k         | List all features   |
| overview  | Scoped feature    | ~800-1.2k      | Feature + tasks     |
| overview  | Scoped project    | ~1.5k-2.5k     | Project + features  |
| overview  | Scoped task       | ~400-800       | Task + dependencies |
| export    | Full markdown     | ~15k-20k       | External report     |

### Optimization Strategies

1. **Prefer scoped overview over get with sections** (85-90% savings)
2. **Use search for lists** (minimal response format)
3. **Only include sections when truly needed** (documentation work)
4. **Leverage task counts** instead of fetching all tasks
5. **Use filters to narrow results** (smaller result sets)
6. **Cache scoped overview results** during work session

### Response Time Expectations

- **search**: < 100ms (indexed queries)
- **get** (no sections): < 50ms (single query)
- **get** (with sections): < 200ms (joins sections)
- **overview** (global): < 150ms (aggregation)
- **overview** (scoped): < 100ms (hierarchical query)
- **export**: < 250ms (markdown generation)

---

## Related Tools

- **manage_container** - Write operations (create, update, delete, setStatus)
- **query_sections** - Query section content with selective loading
- **manage_sections** - Modify section content
- **get_next_task** - Intelligent task recommendation with dependency filtering
- **get_blocked_tasks** - Identify tasks blocked by dependencies

---

## Migration from v1.x Tools

### Former Tools Consolidated

query_container **replaces 9 v1.x tools**:

- `get_task` → `query_container(operation="get", containerType="task")`
- `get_feature` → `query_container(operation="get", containerType="feature")`
- `get_project` → `query_container(operation="get", containerType="project")`
- `search_tasks` → `query_container(operation="search", containerType="task")`
- `search_features` → `query_container(operation="search", containerType="feature")`
- `search_projects` → `query_container(operation="search", containerType="project")`
- `task_to_markdown` → `query_container(operation="export", containerType="task")`
- `feature_to_markdown` → `query_container(operation="export", containerType="feature")`
- `project_to_markdown` → `query_container(operation="export", containerType="project")`

### Migration Examples

**v1.x**:

```json
{
  "tool": "get_feature",
  "id": "..."
}
```

**v2.0**:

```json
{
  "operation": "get",
  "containerType": "feature",
  "id": "..."
}
```

---

**v1.x**:

```json
{
  "tool": "search_tasks",
  "status": "pending",
  "priority": "high"
}
```

**v2.0**:

```json
{
  "operation": "search",
  "containerType": "task",
  "status": "pending",
  "priority": "high"
}
```

---

## Version History

- **v2.0.0** (2025-01-20): Added scoped overview mode with 85-90% token reduction
- **v2.0.0-beta-01** (2025-10-19): Initial v2.0 release with container consolidation, minimal search results, task
  counts

---

## See Also

- [API Reference](../api-reference.md) - Complete tool documentation
- [manage_container Tool](manage-container.md) - Write operations (when created)
- [Quick Start Guide](../quick-start.md) - Common workflows
- [v2.0 Migration Guide](../migration/v2.0-migration-guide.md) - Upgrading from v1.x
