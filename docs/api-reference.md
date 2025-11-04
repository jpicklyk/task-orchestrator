---
layout: default
title: API Reference
---

# MCP Tools API Reference (v2.0)

The MCP Task Orchestrator v2.0 provides **19 consolidated MCP tools** for AI-driven project management, achieving **70% token reduction** through container-based consolidation.

> **Migration from v1.x**: See [v2.0 Migration Guide](migration/v2.0-migration-guide.md) for complete migration instructions.

## Table of Contents

- [What's New in v2.0](#whats-new-in-v20)
- [Tool Overview](#tool-overview)
- [Container Tools](#container-tools) - Projects, Features, Tasks
  - [query_container](#query_container) 🔍
  - [manage_container](#manage_container) ✏️
- [Section Tools](#section-tools)
  - [query_sections](#query_sections) 🔍
  - [manage_sections](#manage_sections) ✏️
- [Template Tools](#template-tools)
  - [query_templates](#query_templates) 🔍
  - [manage_template](#manage_template) ✏️
  - [apply_template](#apply_template) ✏️
- [Dependency Tools](#dependency-tools)
  - [query_dependencies](#query_dependencies) 🔍
  - [manage_dependency](#manage_dependency) ✏️
- [Tag Tools](#tag-tools)
  - [list_tags](#list_tags) 🔍
  - [get_tag_usage](#get_tag_usage) 🔍
  - [rename_tag](#rename_tag) ✏️
- [System Tools](#system-tools)
  - [setup_project](#setup_project) ✏️
- [Agent Tools](#agent-tools) (Claude Code Only - Plugin Installation)
  - [get_agent_definition](#get_agent_definition) 🔍
  - [recommend_agent](#recommend_agent) 🔍
- [Workflow Tools](#workflow-tools)
  - [get_next_status](#get_next_status) 🔍
  - [query_workflow_state](#query_workflow_state) 🔍
- [Permission Model](#permission-model)
- [Best Practices](#best-practices)

**Legend**: 🔍 READ-ONLY | ✏️ WRITE

---

## What's New in v2.0

### Massive Consolidation

**v1.x: 56 tools** → **v2.0: 18 tools** (68% reduction)

| Category | v1.x Tools | v2.0 Tools | Reduction |
|----------|------------|------------|-----------|
| Container (Project/Feature/Task) | 40+ tools | 2 tools | 95% |
| Sections | 11 tools | 2 tools | 82% |
| Templates | 9 tools | 3 tools | 67% |
| Dependencies | 3 tools | 2 tools | 33% |
| Tags, Agents (plugin-only), Workflow | 6 tools | 6 tools | 0% |

### Key Improvements

✅ **70% token reduction** - Massive context window savings
✅ **Permission separation** - `query_*` (read) vs `manage_*` (write)
✅ **Operation-based** - `operation` parameter routes to functionality
✅ **Consistent patterns** - Same interface across all container types
✅ **Future-proof** - Add operations without new tools

### Breaking Changes

⚠️ **All v1.x container, section, template, and dependency tools removed**
⚠️ **No backward compatibility** - immediate migration required
⚠️ **New parameter model** - `operation` + `containerType`/`entityType`
⚠️ **Database schema unchanged** - safe to rollback to v1.1

---

## Tool Overview

### Complete v2.0 Tool List

| Tool | Permission | Operations | Purpose |
|------|-----------|------------|---------|
| **Container Tools** |
| `query_container` | 🔍 READ | get, search, export, overview | Read operations for projects/features/tasks |
| `manage_container` | ✏️ WRITE | create, update, delete, setStatus, bulkUpdate | Write operations for projects/features/tasks |
| **Section Tools** |
| `query_sections` | 🔍 READ | (single operation with filtering) | Read sections with selective loading |
| `manage_sections` | ✏️ WRITE | add, update, updateText, updateMetadata, delete, reorder, bulkCreate, bulkUpdate, bulkDelete | All section write operations |
| **Template Tools** |
| `query_templates` | 🔍 READ | get, list | Read template definitions |
| `manage_template` | ✏️ WRITE | create, update, delete, enable, disable, addSection | Template management |
| `apply_template` | ✏️ WRITE | (dedicated tool) | Apply templates to entities |
| **Dependency Tools** |
| `query_dependencies` | 🔍 READ | (single operation with filtering) | Read task dependencies |
| `manage_dependency` | ✏️ WRITE | create, delete | Dependency management |
| **Tag Tools** |
| `list_tags` | 🔍 READ | (single operation) | List all unique tags |
| `get_tag_usage` | 🔍 READ | (single operation) | Find entities using tag |
| `rename_tag` | ✏️ WRITE | (single operation) | Bulk rename tags |
| **System Tools** |
| `setup_project` | ✏️ WRITE | (single operation) | Initialize Task Orchestrator project config |
| **Agent Tools** (Claude Code - via Plugin) |
| `get_agent_definition` | 🔍 READ | (single operation) | Get agent metadata |
| `recommend_agent` | 🔍 READ | (single operation) | Route task to skill/agent |
| **Workflow Tools** |
| `get_next_status` | 🔍 READ | (single operation) | Status progression recommendations |
| `query_workflow_state` | 🔍 READ | (single operation) | Query complete workflow state |

---

## Container Tools

Container tools provide unified operations for **Projects**, **Features**, and **Tasks** through a single consistent interface.

### Container Type Hierarchy

```
PROJECT (top-level)
└── FEATURE (groups related tasks)
    └── TASK (individual work items)
```

---

### query_container

**Permission**: 🔍 READ-ONLY

**Purpose**: Unified read operations for all container types (projects, features, tasks)

**Operations**: `get`, `search`, `export`, `overview`

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `operation` | enum | **Yes** | Operation: `get`, `search`, `export`, `overview` |
| `containerType` | enum | **Yes** | Container type: `project`, `feature`, `task` |
| `id` | UUID | Varies | Container ID (required for: `get`, `export`; optional for: `overview`) |
| `query` | string | No | Search text query (`search` only) |
| `status` | string | No | Filter by status |
| `priority` | enum | No | Filter by priority (`feature`/`task` only) |
| `tags` | string | No | Comma-separated tags filter |
| `projectId` | UUID | No | Filter by project (`feature`/`task`) |
| `featureId` | UUID | No | Filter by feature (`task` only) |
| `limit` | integer | No | Max results (default: 20) |
| `includeSections` | boolean | No | Include sections (`get` only, default: false) |
| `summaryLength` | integer | No | Summary truncation (`overview` only, 0-200) |

#### Operation: get

**Purpose**: Retrieve a single container by ID

**Required Parameters**: `operation`, `containerType`, `id`

**Example - Get Task**:
```json
{
  "operation": "get",
  "containerType": "task",
  "id": "640522b7-810e-49a2-865c-3725f5d39608",
  "includeSections": true
}
```

**Example - Get Feature**:
```json
{
  "operation": "get",
  "containerType": "feature",
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Task retrieved successfully",
  "data": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "title": "Implement user authentication",
    "summary": "Add JWT-based authentication to the API",
    "status": "in-progress",
    "priority": "high",
    "complexity": 7,
    "featureId": "550e8400-e29b-41d4-a716-446655440000",
    "tags": ["backend", "security", "api"],
    "createdAt": "2025-10-19T10:00:00Z",
    "modifiedAt": "2025-10-19T12:00:00Z",
    "sections": [...]
  }
}
```

---

#### Operation: search

**Purpose**: Find containers matching filters

**Required Parameters**: `operation`, `containerType`

**Example - Search Tasks by Status**:
```json
{
  "operation": "search",
  "containerType": "task",
  "status": "pending",
  "priority": "high",
  "limit": 20
}
```

**Example - Search Features by Project**:
```json
{
  "operation": "search",
  "containerType": "feature",
  "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039",
  "status": "in-development"
}
```

**Example - Search Tasks by Tags**:
```json
{
  "operation": "search",
  "containerType": "task",
  "tags": "backend,api",
  "featureId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Found 5 tasks",
  "data": {
    "containers": [
      {
        "id": "...",
        "title": "Implement OAuth login",
        "status": "pending",
        "priority": "high",
        "complexity": 7,
        "tags": ["backend", "security"]
      },
      ...
    ],
    "totalCount": 5
  }
}
```

---

#### Operation: export

**Purpose**: Export container to markdown format

**Required Parameters**: `operation`, `containerType`, `id`

**Example - Export Task to Markdown**:
```json
{
  "operation": "export",
  "containerType": "task",
  "id": "640522b7-810e-49a2-865c-3725f5d39608",
  "includeSections": true
}
```

**Response**:
```json
{
  "success": true,
  "message": "Task exported successfully",
  "data": {
    "markdown": "---\ntitle: Implement user authentication\nstatus: in-progress\npriority: high\n---\n\n# Implement user authentication\n\n..."
  }
}
```

---

#### Operation: overview

**Purpose**: Get overview data for containers. Supports two modes: **global overview** (all entities) and **scoped overview** (specific entity with hierarchy).

**Required Parameters**: `operation`, `containerType`

**Optional Parameters**: `id` (enables scoped overview mode), `summaryLength`

---

##### Global Overview Mode (without `id` parameter)

Returns an array of all entities of the specified type with minimal fields. No child relationships included.

**Example - Global Feature Overview**:
```json
{
  "operation": "overview",
  "containerType": "feature",
  "summaryLength": 100
}
```

**Response**:
```json
{
  "success": true,
  "message": "Overview retrieved successfully",
  "data": {
    "items": [
      {
        "id": "feature-uuid-1",
        "name": "User Authentication",
        "status": "completed",
        "priority": "high",
        "summary": "Comprehensive authentication with OAuth 2.0...",
        "projectId": "project-uuid",
        "tags": "backend,security",
        "createdAt": "2025-10-15T10:00:00Z",
        "modifiedAt": "2025-10-18T14:30:00Z"
      },
      {
        "id": "feature-uuid-2",
        "name": "Payment Integration",
        "status": "in-development",
        "priority": "high",
        "summary": "Stripe and PayPal payment processing...",
        "projectId": "project-uuid",
        "tags": "backend,payments",
        "createdAt": "2025-10-16T09:00:00Z",
        "modifiedAt": "2025-10-19T11:00:00Z"
      }
    ],
    "count": 2
  }
}
```

**Use Cases**:
- "List all features"
- "Show me all projects"
- "What tasks exist?"

---

##### Scoped Overview Mode (with `id` parameter)

Returns a specific entity with hierarchical child data and task counts. Section content is **excluded** for token efficiency (85-90% reduction vs `get` with `includeSections=true`).

**Example - Scoped Feature Overview**:
```json
{
  "operation": "overview",
  "containerType": "feature",
  "id": "b7b68139-2175-4b4f-8cf7-40d06affe168",
  "summaryLength": 100
}
```

**Response**:
```json
{
  "success": true,
  "message": "Feature overview retrieved successfully",
  "data": {
    "id": "b7b68139-2175-4b4f-8cf7-40d06affe168",
    "name": "Scoped Overview for Query Container Tool",
    "status": "in-development",
    "priority": "high",
    "summary": "Add scoped overview operation to query_container...",
    "tags": "enhancement,query-optimization,v1.1.0",
    "projectId": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
    "createdAt": "2025-10-19T23:12:43.123Z",
    "modifiedAt": "2025-10-19T23:12:47.456Z",
    "taskCounts": {
      "total": 8,
      "byStatus": {
        "completed": 3,
        "in-progress": 2,
        "pending": 3
      }
    },
    "tasks": [
      {
        "id": "task-uuid-1",
        "title": "Design scoped overview data structure",
        "status": "completed",
        "priority": "high",
        "complexity": 5,
        "tags": "design,backend",
        "createdAt": "2025-10-19T23:12:44.001Z",
        "modifiedAt": "2025-10-19T23:15:30.123Z"
      },
      {
        "id": "task-uuid-2",
        "title": "Implement scoped overview in QueryContainerTool",
        "status": "completed",
        "priority": "high",
        "complexity": 6,
        "tags": "backend,implementation",
        "createdAt": "2025-10-19T23:12:44.234Z",
        "modifiedAt": "2025-10-19T23:25:45.678Z"
      },
      {
        "id": "task-uuid-3",
        "title": "Add unit tests for scoped overview",
        "status": "in-progress",
        "priority": "medium",
        "complexity": 4,
        "tags": "testing,backend",
        "createdAt": "2025-10-19T23:12:44.567Z",
        "modifiedAt": "2025-10-19T23:30:12.345Z"
      }
    ]
  }
}
```

**Scoped Overview by Container Type**:

| Container Type | Returns | Child Data Included |
|----------------|---------|---------------------|
| **Project** | Project metadata | Array of features + task counts for each feature |
| **Feature** | Feature metadata | Array of tasks + task counts |
| **Task** | Task metadata | Dependencies (blocking and blockedBy arrays) |

**Example - Scoped Project Overview**:
```json
{
  "operation": "overview",
  "containerType": "project",
  "id": "project-uuid-here"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Project overview retrieved successfully",
  "data": {
    "id": "project-uuid-here",
    "name": "E-Commerce Platform",
    "status": "in-development",
    "summary": "Online shopping platform with payment integration",
    "tags": "web,ecommerce,fullstack",
    "createdAt": "2025-10-01T10:00:00Z",
    "modifiedAt": "2025-10-19T15:30:00Z",
    "taskCounts": {
      "total": 45,
      "byStatus": {
        "completed": 28,
        "in-progress": 10,
        "pending": 7
      }
    },
    "features": [
      {
        "id": "feature-uuid-1",
        "name": "User Authentication",
        "status": "completed",
        "priority": "high",
        "summary": "OAuth and JWT authentication...",
        "tags": "backend,security",
        "taskCounts": {
          "total": 12,
          "byStatus": {
            "completed": 12
          }
        }
      },
      {
        "id": "feature-uuid-2",
        "name": "Product Catalog",
        "status": "in-development",
        "priority": "high",
        "summary": "Product browsing and search...",
        "tags": "backend,frontend",
        "taskCounts": {
          "total": 18,
          "byStatus": {
            "completed": 10,
            "in-progress": 5,
            "pending": 3
          }
        }
      }
    ]
  }
}
```

**Example - Scoped Task Overview**:
```json
{
  "operation": "overview",
  "containerType": "task",
  "id": "task-uuid-here"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Task overview retrieved successfully",
  "data": {
    "id": "task-uuid-here",
    "title": "Implement payment processing",
    "summary": "Integrate Stripe and PayPal",
    "description": "Full implementation details...",
    "status": "in-progress",
    "priority": "high",
    "complexity": 8,
    "featureId": "feature-uuid",
    "tags": "backend,payments,stripe,paypal",
    "createdAt": "2025-10-18T09:00:00Z",
    "modifiedAt": "2025-10-19T14:20:00Z",
    "blocking": [
      {
        "id": "dependency-uuid-1",
        "taskId": "blocked-task-uuid",
        "taskTitle": "Deploy payment service",
        "taskStatus": "pending",
        "dependencyType": "BLOCKS",
        "createdAt": "2025-10-18T10:00:00Z"
      }
    ],
    "blockedBy": [
      {
        "id": "dependency-uuid-2",
        "taskId": "blocker-task-uuid",
        "taskTitle": "Set up Stripe account",
        "taskStatus": "completed",
        "dependencyType": "IS_BLOCKED_BY",
        "createdAt": "2025-10-18T09:30:00Z"
      }
    ]
  }
}
```

**Use Cases**:
- "Show me details on Feature X"
- "What's in Project Y?"
- "Give me an overview of Task Z with dependencies"
- "What tasks are in this feature?"

---

##### Token Efficiency

Scoped overview is optimized for "show me details" queries without the overhead of section content:

**Comparison (Feature with 10 sections + 20 tasks)**:

| Method | Includes Sections | Includes Tasks | Token Cost | Use Case |
|--------|-------------------|----------------|------------|----------|
| `get` with `includeSections=true` | ✅ Yes (full content) | ❌ No | ~18,500 tokens | Full documentation needed |
| `get` with `includeSections=false` | ❌ No | ❌ No | ~200 tokens | Metadata only |
| Scoped `overview` | ❌ No | ✅ Yes (minimal fields) | ~1,200 tokens | Hierarchical overview |
| Global `overview` | ❌ No | ❌ No | ~500 tokens | List all entities |

**Token Reduction**: Scoped overview vs `get` with sections = **93% reduction** (18,500 → 1,200 tokens)

**Real-World Measurements** (from Scoped Overview feature):
- Feature with 8 tasks, 10 sections:
  - `get` with `includeSections=true`: ~18,500 tokens
  - Scoped `overview`: ~1,200 tokens
  - **Savings: 93%**

---

##### When to Use Each Mode

| Query Need | Best Method | Why |
|------------|-------------|-----|
| List all features/projects/tasks | Global `overview` | Minimal data for browsing |
| Show feature with all tasks | Scoped `overview` (feature) | Hierarchical data without section bloat |
| Show project with all features | Scoped `overview` (project) | Complete project structure |
| Show task with dependencies | Scoped `overview` (task) | Dependency analysis |
| Full documentation | `get` with `includeSections=true` | Need section content |
| Quick metadata lookup | `get` with `includeSections=false` | Fastest, minimal data |
| Find entities by filters | `search` | Filter-based queries |
| Export to markdown | `export` | Documentation export |

**Recommendation**: Use scoped overview for "show details" queries to get hierarchical data without section content overhead

---

### manage_container

**Permission**: ✏️ WRITE

**Purpose**: Unified write operations for all container types (projects, features, tasks)

**Operations**: `create`, `update`, `delete`, `setStatus`, `bulkUpdate`

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `operation` | enum | **Yes** | Operation: `create`, `update`, `delete`, `setStatus`, `bulkUpdate` |
| `containerType` | enum | **Yes** | Container type: `project`, `feature`, `task` |
| `id` | UUID | Varies | Container ID (required for: `update`, `delete`, `setStatus`) |
| `name` / `title` | string | Varies | Name (project/feature) or title (task) - required for `create` |
| `summary` | string | No | Brief summary (max 500 chars) |
| `description` | string | No | Detailed description |
| `status` | enum | No | Container status |
| `priority` | enum | No | Priority: `low`, `medium`, `high` (`feature`/`task` only) |
| `complexity` | integer | No | Complexity 1-10 (`task` only) |
| `projectId` | UUID | No | Parent project ID (`feature`/`task`) |
| `featureId` | UUID | No | Parent feature ID (`task` only) |
| `templateIds` | array | No | Template UUIDs to apply (`create` only) |
| `tags` | string | No | Comma-separated tags |
| `deleteSections` | boolean | No | Delete sections with container (default: true) |
| `force` | boolean | No | Force delete with dependencies (default: false) |
| `containers` | array | No | Container objects for bulk update (`bulkUpdate` only) |

#### Status Values by Container Type

v2.0 introduces **31 comprehensive statuses** across all container types for orchestration workflows, deployment tracking, and quality assurance.

##### TaskStatus (14 statuses)

**Original v1.0 Statuses (5)**:

| Status | Description | When to Use |
|--------|-------------|-------------|
| `pending` | Task ready to start | Initial state for new tasks |
| `in-progress` | Task actively being worked on | Developer is implementing |
| `completed` | Task finished successfully | All work done, tests passing, summary written (300-500 chars required) |
| `cancelled` | Task explicitly cancelled | No longer needed or explicitly abandoned |
| `deferred` | Task postponed indefinitely | Will be addressed later, not cancelled |

**New v2.0 Orchestration Statuses (9)**:

| Status | Description | When to Use |
|--------|-------------|-------------|
| `backlog` | Task in backlog, not ready | Awaiting prioritization or dependencies |
| `in-review` | Implementation complete, awaiting review | Code written, waiting for peer review |
| `changes-requested` | Review completed, changes needed | Reviewer requested modifications |
| `on-hold` | Task temporarily paused | Waiting on external factors, temporarily stopped |
| `testing` | Implementation complete, running tests | Code written, test suite executing |
| `ready-for-qa` | Testing complete, ready for QA review | Tests pass, ready for quality assurance |
| `investigating` | Actively investigating issues or approach | Research phase, technical exploration |
| `blocked` | Blocked by incomplete dependencies | Cannot proceed until blockers resolve |
| `deployed` | Successfully deployed to environment | Live in production/staging/canary (see environment tags below) |

**Status Transitions**:
- Forward: `pending → backlog → in-progress → testing → ready-for-qa → in-review → completed → deployed`
- Backward (rework): `testing → in-progress`, `in-review → changes-requested → in-progress`
- Emergency: Any status → `blocked`, `on-hold`, `cancelled`, `deferred`
- Terminal: `completed`, `cancelled`, `deployed` cannot transition further

**Prerequisites**:
- `completed`: Requires summary field (300-500 characters) - StatusValidator enforces this
- `in-progress`: Checks for blocking dependencies (warns if blockers incomplete)

---

##### FeatureStatus (11 statuses)

**Original v1.0 Statuses (4)**:

| Status | Description | When to Use |
|--------|-------------|-------------|
| `planning` | Feature design and planning phase | Initial state, defining requirements |
| `in-development` | Feature actively being developed | At least 1 task exists and work is ongoing |
| `completed` | Feature finished successfully | All tasks completed |
| `archived` | Feature archived for reference | No longer active, kept for history |

**New v2.0 Orchestration Statuses (7)**:

| Status | Description | When to Use |
|--------|-------------|-------------|
| `draft` | Initial draft state, not yet planned | Idea stage, not yet in planning |
| `on-hold` | Feature temporarily paused | Waiting on external factors |
| `testing` | Feature in testing phase | All tasks completed, running test suite |
| `validating` | Tests passed, final validation | Testing complete, final checks before completion |
| `pending-review` | Awaiting human review approval | Ready for stakeholder review |
| `blocked` | Blocked by external dependencies | Cannot proceed until blockers resolve |
| `deployed` | Successfully deployed to environment | Live in production/staging/canary |

**Status Transitions**:
- Forward: `draft → planning → in-development → testing → validating → pending-review → completed → deployed`
- Backward (rework): `testing → in-development`, `validating → testing`
- Emergency: Any status → `blocked`, `on-hold`, `archived`
- Terminal: `completed`, `archived`, `deployed` cannot transition further

**Prerequisites**:
- `in-development`: Requires at least 1 task
- `testing`: Requires all tasks completed
- `completed`: Requires all tasks completed

---

##### ProjectStatus (6 statuses)

**Original v1.0 Statuses (4)**:

| Status | Description | When to Use |
|--------|-------------|-------------|
| `planning` | Project design and planning phase | Initial state, defining scope |
| `in-development` | Project actively being developed | Features and tasks in progress |
| `completed` | Project finished successfully | All features completed |
| `archived` | Project archived for reference | No longer active, kept for history |

**New v2.0 Orchestration Statuses (2)**:

| Status | Description | When to Use |
|--------|-------------|-------------|
| `on-hold` | Project temporarily paused | Waiting on external factors, resources |
| `cancelled` | Project cancelled/abandoned | No longer pursuing this project |

**Status Transitions**:
- Forward: `planning → in-development → completed`
- Emergency: Any status → `on-hold`, `cancelled`, `archived`
- Terminal: `completed`, `archived`, `cancelled` cannot transition further

**Prerequisites**:
- `completed`: Requires all features completed

---

#### DEPLOYED Status with Environment Tags

The `deployed` status supports **environment tags** to track deployment targets:

**Recommended Environment Tags**:
- `env:production` - Live production environment
- `env:staging` - Staging/pre-production environment
- `env:canary` - Canary deployment (gradual rollout)
- `env:dev` - Development environment
- `env:qa` - QA/testing environment

**Example - Deploy Task to Production**:
```json
{
  "operation": "update",
  "containerType": "task",
  "id": "task-uuid",
  "status": "deployed",
  "tags": "backend,api,env:production"
}
```

**Example - Deploy Feature to Staging**:
```json
{
  "operation": "setStatus",
  "containerType": "feature",
  "id": "feature-uuid",
  "status": "deployed",
  "tags": "authentication,env:staging"
}
```

**StatusValidator Advisory**:
When setting status to `deployed` without environment tags, StatusValidator returns `ValidWithAdvisory` suggesting you add environment tags. This is **advisory only**, not an error.

---

#### StatusValidator Usage

The StatusValidator service enforces status validation rules:

**Status Validation**:
```kotlin
// Validate status value
statusValidator.validateStatus(
    status = "testing",
    containerType = "task",
    tags = listOf("backend", "env:staging")
)
// Returns: ValidationResult.Valid
```

**Transition Validation**:
```kotlin
// Validate status transition
statusValidator.validateTransition(
    currentStatus = "in-progress",
    newStatus = "testing",
    containerType = "task",
    containerId = taskUuid,
    context = prerequisiteContext,
    tags = listOf("backend")
)
// Returns: ValidationResult.Valid or ValidationResult.Invalid with suggestions
```

**Prerequisite Validation**:
```kotlin
// Check prerequisites for status change
statusValidator.validatePrerequisites(
    containerId = taskUuid,
    newStatus = "completed",
    containerType = "task",
    context = prerequisiteContext
)
// For "completed": checks summary field (300-500 chars)
// For "in-progress": checks blocking dependencies
```

**Example - Invalid Transition**:
```json
// Attempt to skip statuses
{
  "current": "pending",
  "new": "completed"
}
// Result: ValidationResult.Invalid(
//   reason: "Cannot transition directly from pending to completed",
//   suggestions: ["Set status to in-progress first", "Complete implementation"]
// )
```

---

#### Deployment Workflow Examples

**Task Deployment Flow**:
```javascript
// 1. Complete task
manage_container(operation="update", containerType="task", id="...",
                 status="completed", summary="Implemented OAuth with JWT tokens...")

// 2. Deploy to staging
manage_container(operation="update", containerType="task", id="...",
                 status="deployed", tags="backend,oauth,env:staging")

// 3. Verify staging, then deploy to production
manage_container(operation="update", containerType="task", id="...",
                 tags="backend,oauth,env:production")
```

**Feature Deployment Flow**:
```javascript
// 1. Complete all tasks
// ... (tasks reach completed status)

// 2. Move feature to testing
manage_container(operation="setStatus", containerType="feature", id="...",
                 status="testing")

// 3. Validate tests passed
manage_container(operation="setStatus", containerType="feature", id="...",
                 status="validating")

// 4. Get review approval
manage_container(operation="setStatus", containerType="feature", id="...",
                 status="pending-review")

// 5. Mark complete
manage_container(operation="setStatus", containerType="feature", id="...",
                 status="completed")

// 6. Deploy to production
manage_container(operation="update", containerType="feature", id="...",
                 status="deployed", tags="user-auth,env:production")
```

**Canary Deployment**:
```javascript
// Deploy to canary first (gradual rollout)
manage_container(operation="update", containerType="feature", id="...",
                 status="deployed", tags="payments,env:canary")

// Monitor canary, then promote to production
manage_container(operation="update", containerType="feature", id="...",
                 tags="payments,env:production")
```

---

#### Operation: create

**Purpose**: Create new container

**Required Parameters**: `operation`, `containerType`, `name`/`title`

**Example - Create Task with Templates**:
```json
{
  "operation": "create",
  "containerType": "task",
  "title": "Implement user authentication",
  "summary": "Add JWT-based authentication to the API",
  "status": "pending",
  "priority": "high",
  "complexity": 7,
  "featureId": "550e8400-e29b-41d4-a716-446655440000",
  "tags": "backend,security,api",
  "templateIds": ["661e8511-e29b-41d4-a716-446655440001"]
}
```

**Example - Create Feature**:
```json
{
  "operation": "create",
  "containerType": "feature",
  "name": "User Authentication System",
  "summary": "Comprehensive authentication with OAuth",
  "status": "planning",
  "priority": "high",
  "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039",
  "tags": "security,backend"
}
```

**Example - Create Project**:
```json
{
  "operation": "create",
  "containerType": "project",
  "name": "E-Commerce Platform",
  "summary": "Online shopping platform with payment integration",
  "status": "planning",
  "tags": "web,ecommerce,fullstack"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Task created successfully",
  "data": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "title": "Implement user authentication",
    "status": "pending",
    "priority": "high",
    "complexity": 7,
    "createdAt": "2025-10-19T10:00:00Z",
    "templatesApplied": 1,
    "sectionsCreated": 3
  }
}
```

---

#### Operation: update

**Purpose**: Update container fields (partial updates supported)

**Required Parameters**: `operation`, `containerType`, `id`

**Example - Update Task Status and Priority**:
```json
{
  "operation": "update",
  "containerType": "task",
  "id": "640522b7-810e-49a2-865c-3725f5d39608",
  "status": "in-progress",
  "priority": "critical"
}
```

**Example - Update Feature Summary**:
```json
{
  "operation": "update",
  "containerType": "feature",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "summary": "Updated comprehensive authentication with OAuth 2.0 and JWT"
}
```

**Example - Update Tags Only**:
```json
{
  "operation": "update",
  "containerType": "task",
  "id": "640522b7-810e-49a2-865c-3725f5d39608",
  "tags": "backend,security,api,authentication"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Task updated successfully",
  "data": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "modifiedAt": "2025-10-19T12:30:00Z",
    "fieldsUpdated": ["status", "priority"]
  }
}
```

---

#### Operation: delete

**Purpose**: Remove container

**Required Parameters**: `operation`, `containerType`, `id`

**Example - Delete Task (with sections)**:
```json
{
  "operation": "delete",
  "containerType": "task",
  "id": "640522b7-810e-49a2-865c-3725f5d39608",
  "deleteSections": true
}
```

**Example - Delete Feature (force with dependencies)**:
```json
{
  "operation": "delete",
  "containerType": "feature",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "force": true
}
```

**Response**:
```json
{
  "success": true,
  "message": "Task deleted successfully",
  "data": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "sectionsDeleted": 3,
    "dependenciesRemoved": 2
  }
}
```

---

#### Operation: setStatus

**Purpose**: Quick status-only update (optimized)

**Required Parameters**: `operation`, `containerType`, `id`, `status`

**Example - Set Task to Completed**:
```json
{
  "operation": "setStatus",
  "containerType": "task",
  "id": "640522b7-810e-49a2-865c-3725f5d39608",
  "status": "completed"
}
```

**Example - Set Feature to In-Development**:
```json
{
  "operation": "setStatus",
  "containerType": "feature",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "in-development"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Task status updated successfully",
  "data": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "status": "completed",
    "modifiedAt": "2025-10-19T14:00:00Z"
  }
}
```

---

#### Operation: bulkUpdate

**Purpose**: Update multiple containers in single transaction

**Required Parameters**: `operation`, `containerType`, `containers`

**Example - Bulk Status Update**:
```json
{
  "operation": "bulkUpdate",
  "containerType": "task",
  "containers": [
    {"id": "task-1-uuid", "status": "completed"},
    {"id": "task-2-uuid", "status": "completed"},
    {"id": "task-3-uuid", "status": "deferred", "priority": "low"}
  ]
}
```

**Response**:
```json
{
  "success": true,
  "message": "Updated 3 tasks successfully",
  "data": {
    "totalUpdated": 3,
    "failed": 0,
    "updates": [
      {"id": "task-1-uuid", "success": true},
      {"id": "task-2-uuid", "success": true},
      {"id": "task-3-uuid", "success": true}
    ]
  }
}
```

---

## Section Tools

Sections provide structured content within containers (projects, features, tasks).

### query_sections

**Permission**: 🔍 READ-ONLY

**Purpose**: Read sections with selective loading for token efficiency

**No operation parameter** - single query operation with flexible filtering

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `entityType` | enum | **Yes** | `PROJECT`, `FEATURE`, or `TASK` (uppercase) |
| `entityId` | UUID | **Yes** | Parent entity identifier |
| `includeContent` | boolean | No | Include content (default: true) |
| `sectionIds` | array | No | Filter to specific section IDs |
| `tags` | string | No | Comma-separated tags (returns sections with ANY tag) |

#### Usage Patterns

**Pattern 1: Get All Sections with Content** (default):
```json
{
  "entityType": "TASK",
  "entityId": "640522b7-810e-49a2-865c-3725f5d39608"
}
```

**Pattern 2: Browse Structure Only** (85-99% token savings):
```json
{
  "entityType": "TASK",
  "entityId": "640522b7-810e-49a2-865c-3725f5d39608",
  "includeContent": false
}
```

**Pattern 3: Selective Loading** (fetch specific sections):
```json
{
  "entityType": "TASK",
  "entityId": "640522b7-810e-49a2-865c-3725f5d39608",
  "sectionIds": ["section-1-uuid", "section-3-uuid"]
}
```

**Pattern 4: Filter by Tags**:
```json
{
  "entityType": "TASK",
  "entityId": "640522b7-810e-49a2-865c-3725f5d39608",
  "tags": "requirements,implementation",
  "includeContent": true
}
```

#### Response (with includeContent=false)

```json
{
  "success": true,
  "message": "Retrieved 3 sections",
  "data": {
    "sections": [
      {
        "id": "section-1-uuid",
        "title": "Implementation Notes",
        "usageDescription": "Technical implementation details",
        "contentFormat": "MARKDOWN",
        "ordinal": 0,
        "tags": ["implementation", "backend"],
        "createdAt": "2025-10-19T10:00:00Z",
        "modifiedAt": "2025-10-19T12:00:00Z"
      },
      ...
    ],
    "totalCount": 3
  }
}
```

#### Two-Step Workflow (Token Optimization)

```
Step 1: Browse structure (low tokens)
→ query_sections(entityType="TASK", entityId="...", includeContent=false)
→ Returns: metadata only (id, title, usageDescription, tags)
→ Token cost: ~500 chars

Step 2: Fetch specific content (only what's needed)
→ query_sections(entityType="TASK", entityId="...", sectionIds=["needed-id"])
→ Returns: only requested sections with full content
→ Token cost: ~2,000 chars

Savings: 85-99% vs loading all content upfront
```

---

### manage_sections

**Permission**: ✏️ WRITE

**Purpose**: All section write operations

**Operations**: `add`, `update`, `updateText`, `updateMetadata`, `delete`, `reorder`, `bulkCreate`, `bulkUpdate`, `bulkDelete`

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `operation` | enum | **Yes** | Operation to perform |
| `id` | UUID | Varies | Section ID (required for: `update`, `updateText`, `updateMetadata`, `delete`) |
| `ids` | array | No | Section IDs (required for: `bulkDelete`) |
| `entityType` | enum | Varies | `PROJECT`, `FEATURE`, `TASK` (required for: `add`, `reorder`, bulk operations) |
| `entityId` | UUID | Varies | Parent entity ID (required for: `add`, `reorder`, bulk operations) |
| `title` | string | No | Section title |
| `usageDescription` | string | No | Usage description |
| `content` | string | No | Section content |
| `contentFormat` | enum | No | `PLAIN_TEXT`, `MARKDOWN`, `JSON`, `CODE` |
| `ordinal` | integer | No | Display order (0-based) |
| `tags` | string | No | Comma-separated tags |
| `oldText` | string | No | Text to replace (`updateText` only) |
| `newText` | string | No | Replacement text (`updateText` only) |
| `sectionOrder` | string | No | Comma-separated section IDs (`reorder` only) |
| `sections` | array | No | Section objects (`bulkCreate`, `bulkUpdate`) |

---

#### Operation: add

**Purpose**: Add single section

**Required Parameters**: `operation`, `entityType`, `entityId`, `title`

**Example**:
```json
{
  "operation": "add",
  "entityType": "TASK",
  "entityId": "640522b7-810e-49a2-865c-3725f5d39608",
  "title": "Implementation Notes",
  "usageDescription": "Technical implementation details",
  "content": "Use JWT tokens for stateless authentication",
  "contentFormat": "MARKDOWN",
  "ordinal": 0,
  "tags": "implementation,backend"
}
```

---

#### Operation: update

**Purpose**: Update section (full update)

**Required Parameters**: `operation`, `id`

**Example**:
```json
{
  "operation": "update",
  "id": "section-uuid",
  "title": "Updated Implementation Notes",
  "content": "Use OAuth 2.0 with JWT tokens for stateless authentication",
  "tags": "implementation,backend,security"
}
```

---

#### Operation: updateText

**Purpose**: Replace specific text in section

**Required Parameters**: `operation`, `id`, `oldText`, `newText`

**Example**:
```json
{
  "operation": "updateText",
  "id": "section-uuid",
  "oldText": "Use JWT tokens",
  "newText": "Use OAuth 2.0 with JWT tokens"
}
```

---

#### Operation: updateMetadata

**Purpose**: Update metadata only (no content changes)

**Required Parameters**: `operation`, `id`

**Example**:
```json
{
  "operation": "updateMetadata",
  "id": "section-uuid",
  "title": "Updated Title",
  "usageDescription": "Updated description",
  "tags": "new,tags"
}
```

---

#### Operation: delete

**Purpose**: Remove section

**Required Parameters**: `operation`, `id`

**Example**:
```json
{
  "operation": "delete",
  "id": "section-uuid"
}
```

---

#### Operation: reorder

**Purpose**: Change section display order

**Required Parameters**: `operation`, `entityType`, `entityId`, `sectionOrder`

**Example**:
```json
{
  "operation": "reorder",
  "entityType": "TASK",
  "entityId": "640522b7-810e-49a2-865c-3725f5d39608",
  "sectionOrder": "section-3-uuid,section-1-uuid,section-2-uuid"
}
```

---

#### Operation: bulkCreate

**Purpose**: Create multiple sections efficiently

**Required Parameters**: `operation`, `entityType`, `entityId`, `sections`

**Example**:
```json
{
  "operation": "bulkCreate",
  "entityType": "TASK",
  "entityId": "640522b7-810e-49a2-865c-3725f5d39608",
  "sections": [
    {
      "title": "Requirements",
      "content": "User authentication requirements",
      "ordinal": 0,
      "tags": "requirements"
    },
    {
      "title": "Implementation",
      "content": "OAuth 2.0 implementation steps",
      "ordinal": 1,
      "tags": "implementation"
    }
  ]
}
```

---

#### Operation: bulkUpdate

**Purpose**: Update multiple sections efficiently

**Required Parameters**: `operation`, `sections` (each with `id` field)

**Example**:
```json
{
  "operation": "bulkUpdate",
  "sections": [
    {"id": "section-1-uuid", "title": "Updated Title 1"},
    {"id": "section-2-uuid", "content": "Updated content 2"}
  ]
}
```

---

#### Operation: bulkDelete

**Purpose**: Delete multiple sections

**Required Parameters**: `operation`, `ids`

**Example**:
```json
{
  "operation": "bulkDelete",
  "ids": ["section-1-uuid", "section-2-uuid", "section-3-uuid"]
}
```

---

## Template Tools

Templates provide structured guidance and documentation for containers.

### query_templates

**Permission**: 🔍 READ-ONLY

**Purpose**: Read template definitions

**Operations**: `get`, `list`

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `operation` | enum | **Yes** | `get` or `list` |
| `id` | UUID | Varies | Template ID (required for `get`) |
| `includeSections` | boolean | No | Include template sections (default: false) |
| `targetEntityType` | enum | No | Filter: `PROJECT`, `FEATURE`, `TASK` |
| `isBuiltIn` | boolean | No | Filter built-in templates |
| `isEnabled` | boolean | No | Filter enabled templates |
| `tags` | string | No | Comma-separated tags filter |

---

#### Operation: get

**Purpose**: Retrieve single template by ID

**Required Parameters**: `operation`, `id`

**Example**:
```json
{
  "operation": "get",
  "id": "template-uuid",
  "includeSections": true
}
```

**Response**:
```json
{
  "success": true,
  "message": "Template retrieved successfully",
  "data": {
    "id": "template-uuid",
    "name": "Backend Development Template",
    "description": "Template for backend implementation tasks",
    "targetEntityType": "TASK",
    "isBuiltIn": true,
    "isEnabled": true,
    "tags": ["backend", "api"],
    "sections": [...]
  }
}
```

---

#### Operation: list

**Purpose**: List templates with filtering

**Required Parameters**: `operation`

**Example - List All Enabled Task Templates**:
```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "isEnabled": true
}
```

**Example - List All Templates**:
```json
{
  "operation": "list"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Found 9 templates",
  "data": {
    "templates": [
      {
        "id": "template-1-uuid",
        "name": "Backend Development Template",
        "description": "Template for backend implementation tasks",
        "targetEntityType": "TASK",
        "isBuiltIn": true,
        "isEnabled": true,
        "tags": ["backend", "api"]
      },
      ...
    ],
    "totalCount": 9
  }
}
```

---

### manage_template

**Permission**: ✏️ WRITE

**Purpose**: Template management operations

**Operations**: `create`, `update`, `delete`, `enable`, `disable`, `addSection`

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `operation` | enum | **Yes** | Operation to perform |
| `id` | UUID | Varies | Template ID (required for: `update`, `delete`, `enable`, `disable`, `addSection`) |
| `name` | string | Varies | Template name (required for `create`) |
| `description` | string | Varies | Template description (required for `create`) |
| `targetEntityType` | enum | Varies | `PROJECT`, `FEATURE`, `TASK` (required for `create`) |
| `isBuiltIn` | boolean | No | Mark as built-in (default: false) |
| `isProtected` | boolean | No | Protect from deletion (default: false) |
| `isEnabled` | boolean | No | Enable template (default: true) |
| `createdBy` | string | No | Creator identifier |
| `tags` | string | No | Comma-separated tags |
| `title` | string | Varies | Section title (required for `addSection`) |
| `usageDescription` | string | Varies | Section usage (required for `addSection`) |
| `contentSample` | string | No | Sample content for section |
| `contentFormat` | enum | No | `PLAIN_TEXT`, `MARKDOWN`, `JSON`, `CODE` |
| `ordinal` | integer | No | Display order |
| `isRequired` | boolean | No | Required section (default: false) |
| `force` | boolean | No | Force delete protected template |

---

#### Operation: create

**Purpose**: Create custom template

**Required Parameters**: `operation`, `name`, `description`, `targetEntityType`

**Example**:
```json
{
  "operation": "create",
  "name": "API Development Template",
  "description": "Template for API feature development",
  "targetEntityType": "TASK",
  "tags": "backend,api",
  "isEnabled": true
}
```

---

#### Operation: update

**Purpose**: Update template metadata

**Required Parameters**: `operation`, `id`

**Example**:
```json
{
  "operation": "update",
  "id": "template-uuid",
  "name": "Updated API Development Template",
  "description": "Updated description",
  "tags": "backend,api,rest"
}
```

---

#### Operation: delete

**Purpose**: Remove template

**Required Parameters**: `operation`, `id`

**Example**:
```json
{
  "operation": "delete",
  "id": "template-uuid",
  "force": false
}
```

---

#### Operation: enable / disable

**Purpose**: Toggle template availability

**Required Parameters**: `operation`, `id`

**Example - Enable**:
```json
{
  "operation": "enable",
  "id": "template-uuid"
}
```

**Example - Disable**:
```json
{
  "operation": "disable",
  "id": "template-uuid"
}
```

---

#### Operation: addSection

**Purpose**: Add section to template

**Required Parameters**: `operation`, `id`, `title`, `usageDescription`, `ordinal`

**Example**:
```json
{
  "operation": "addSection",
  "id": "template-uuid",
  "title": "API Endpoints",
  "usageDescription": "Document all API endpoints and their specifications",
  "contentSample": "## Endpoints\n\n### GET /api/users\nDescription: ...",
  "contentFormat": "MARKDOWN",
  "ordinal": 0,
  "tags": "api,documentation",
  "isRequired": true
}
```

---

### apply_template

**Permission**: ✏️ WRITE

**Purpose**: Apply template to existing entity

**Unchanged from v1.x** - Kept as dedicated tool due to complexity

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `entityType` | enum | **Yes** | `PROJECT`, `FEATURE`, or `TASK` |
| `entityId` | UUID | **Yes** | Target entity ID |
| `templateId` | UUID | **Yes** | Template to apply |
| `overwrite` | boolean | No | Overwrite existing sections (default: false) |

#### Example

```json
{
  "entityType": "TASK",
  "entityId": "640522b7-810e-49a2-865c-3725f5d39608",
  "templateId": "template-uuid",
  "overwrite": false
}
```

**Response**:
```json
{
  "success": true,
  "message": "Template applied successfully",
  "data": {
    "entityId": "640522b7-810e-49a2-865c-3725f5d39608",
    "templateId": "template-uuid",
    "sectionsCreated": 3,
    "sectionsSkipped": 0
  }
}
```

---

## Dependency Tools

Dependencies model relationships between tasks (BLOCKS, IS_BLOCKED_BY, RELATES_TO).

### query_dependencies

**Permission**: 🔍 READ-ONLY

**Purpose**: Query task dependencies with filtering

**No operation parameter** - single query operation with flexible filtering

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `taskId` | UUID | **Yes** | Task to query dependencies for |
| `direction` | enum | No | `incoming`, `outgoing`, `all` (default: `all`) |
| `type` | enum | No | `BLOCKS`, `IS_BLOCKED_BY`, `RELATES_TO`, `all` |
| `includeTaskInfo` | boolean | No | Include task details (default: false) |

#### Dependency Types

- `BLOCKS`: This task blocks another task
- `IS_BLOCKED_BY`: This task is blocked by another task
- `RELATES_TO`: General relationship (no blocking semantics)

#### Example - Get All Dependencies

```json
{
  "taskId": "640522b7-810e-49a2-865c-3725f5d39608",
  "direction": "all",
  "includeTaskInfo": true
}
```

#### Example - Get Only Incoming Blockers

```json
{
  "taskId": "640522b7-810e-49a2-865c-3725f5d39608",
  "direction": "incoming",
  "type": "BLOCKS"
}
```

#### Response

```json
{
  "success": true,
  "message": "Found 3 dependencies",
  "data": {
    "dependencies": [
      {
        "id": "dependency-uuid",
        "fromTaskId": "blocker-uuid",
        "toTaskId": "640522b7-810e-49a2-865c-3725f5d39608",
        "type": "BLOCKS",
        "createdAt": "2025-10-19T10:00:00Z",
        "fromTask": {
          "id": "blocker-uuid",
          "title": "Design authentication flow",
          "status": "completed",
          "priority": "high"
        },
        "toTask": {
          "id": "640522b7-810e-49a2-865c-3725f5d39608",
          "title": "Implement authentication",
          "status": "pending",
          "priority": "high"
        }
      }
    ],
    "totalCount": 3,
    "incoming": 2,
    "outgoing": 1
  }
}
```

---

### manage_dependency

**Permission**: ✏️ WRITE

**Purpose**: Create and delete task dependencies

**Operations**: `create`, `delete`

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `operation` | enum | **Yes** | `create` or `delete` |
| `id` | UUID | Varies | Dependency ID (for `delete`) |
| `fromTaskId` | UUID | Varies | Source task ID (for `create` and alternative `delete`) |
| `toTaskId` | UUID | Varies | Target task ID (for `create` and alternative `delete`) |
| `type` | enum | No | `BLOCKS`, `IS_BLOCKED_BY`, `RELATES_TO` (default: `BLOCKS`) |
| `deleteAll` | boolean | No | Delete all matching dependencies (default: false) |

---

#### Operation: create

**Purpose**: Create dependency relationship

**Required Parameters**: `operation`, `fromTaskId`, `toTaskId`

**Example - Create BLOCKS Dependency**:
```json
{
  "operation": "create",
  "fromTaskId": "task-1-uuid",
  "toTaskId": "task-2-uuid",
  "type": "BLOCKS"
}
```

**Example - Create RELATES_TO**:
```json
{
  "operation": "create",
  "fromTaskId": "task-1-uuid",
  "toTaskId": "task-3-uuid",
  "type": "RELATES_TO"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Dependency created successfully",
  "data": {
    "id": "dependency-uuid",
    "fromTaskId": "task-1-uuid",
    "toTaskId": "task-2-uuid",
    "type": "BLOCKS",
    "createdAt": "2025-10-19T10:00:00Z"
  }
}
```

---

#### Operation: delete

**Purpose**: Remove dependency

**Required Parameters**: `operation`, (`id` OR `fromTaskId` + `toTaskId`)

**Example - Delete by ID**:
```json
{
  "operation": "delete",
  "id": "dependency-uuid"
}
```

**Example - Delete by Task IDs**:
```json
{
  "operation": "delete",
  "fromTaskId": "task-1-uuid",
  "toTaskId": "task-2-uuid"
}
```

**Example - Delete All Dependencies Between Tasks**:
```json
{
  "operation": "delete",
  "fromTaskId": "task-1-uuid",
  "toTaskId": "task-2-uuid",
  "deleteAll": true
}
```

---

## Tag Tools

Tags provide categorization and organization across all entities.

### list_tags

**Permission**: 🔍 READ-ONLY

**Purpose**: List all unique tags with usage counts

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `entityTypes` | array | No | Filter: `PROJECT`, `FEATURE`, `TASK`, `TEMPLATE` |
| `sortBy` | enum | No | `count` or `name` (default: `count`) |
| `sortDirection` | enum | No | `asc` or `desc` (default: `desc`) |

#### Example - All Tags by Usage

```json
{
  "sortBy": "count",
  "sortDirection": "desc"
}
```

#### Example - Task Tags Alphabetically

```json
{
  "entityTypes": ["TASK"],
  "sortBy": "name",
  "sortDirection": "asc"
}
```

#### Response

```json
{
  "success": true,
  "message": "Found 25 unique tags",
  "data": {
    "tags": [
      {
        "tag": "backend",
        "totalCount": 45,
        "byEntityType": {
          "TASK": 32,
          "FEATURE": 10,
          "TEMPLATE": 3
        }
      },
      {
        "tag": "api",
        "totalCount": 38,
        "byEntityType": {
          "TASK": 28,
          "FEATURE": 8,
          "TEMPLATE": 2
        }
      }
    ],
    "totalTags": 25
  }
}
```

---

### get_tag_usage

**Permission**: 🔍 READ-ONLY

**Purpose**: Find all entities using a specific tag

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `tag` | string | **Yes** | Tag to search for (case-insensitive) |
| `entityTypes` | string | No | Comma-separated types to filter |

#### Example - Find All Entities with Tag

```json
{
  "tag": "authentication"
}
```

#### Example - Find Only Tasks

```json
{
  "tag": "api",
  "entityTypes": "TASK"
}
```

#### Response

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
          "id": "task-uuid",
          "title": "Implement OAuth login",
          "status": "in-progress",
          "priority": "high",
          "complexity": 7
        }
      ],
      "FEATURE": [
        {
          "id": "feature-uuid",
          "name": "User Authentication",
          "status": "in-development",
          "priority": "high"
        }
      ]
    }
  }
}
```

---

### rename_tag

**Permission**: ✏️ WRITE

**Purpose**: Bulk rename tag across all entities

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `oldTag` | string | **Yes** | Current tag name (case-insensitive) |
| `newTag` | string | **Yes** | New tag name |
| `entityTypes` | string | No | Comma-separated types to update |
| `dryRun` | boolean | No | Preview changes without applying (default: false) |

#### Example - Rename Tag

```json
{
  "oldTag": "authentcation",
  "newTag": "authentication"
}
```

#### Example - Rename in Tasks Only

```json
{
  "oldTag": "api",
  "newTag": "rest-api",
  "entityTypes": "TASK"
}
```

#### Example - Preview Changes

```json
{
  "oldTag": "frontend",
  "newTag": "ui",
  "dryRun": true
}
```

#### Response

```json
{
  "success": true,
  "message": "Successfully renamed tag in 12 entities",
  "data": {
    "oldTag": "authentcation",
    "newTag": "authentication",
    "totalUpdated": 12,
    "byEntityType": {
      "TASK": 8,
      "FEATURE": 3,
      "PROJECT": 1
    },
    "failedUpdates": 0,
    "dryRun": false
  }
}
```

---

## System Tools

System tools manage Task Orchestrator project initialization and configuration.

### setup_project

**Permission**: ✏️ WRITE

**Purpose**: Initialize Task Orchestrator project configuration files

Creates `.taskorchestrator/` directory with:
- `config.yaml` - Core orchestrator configuration
- `status-workflow-config.yaml` - Workflow definitions
- `agent-mapping.yaml` - Agent routing configuration
- `orchestration/` - Workflow automation files

**Parameters**: None required

**Key Features**:
- Idempotent (safe to run multiple times)
- Won't overwrite customizations
- Detects outdated configurations

**Detailed Documentation**: [setup_project](tools/setup-project.md)

---

## Agent Tools

Agent tools support Claude Code agent orchestration (Skills and Subagents).

> **Note**: Agent tools are automatically installed when you use the plugin marketplace. Other MCP clients don't support agent orchestration. For Claude Code, install via: `/plugin install task-orchestrator`

### get_agent_definition

**Permission**: 🔍 READ-ONLY

**Purpose**: Retrieve agent metadata

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `agentName` | string | **Yes** | Agent filename (without .md extension) |

#### Example

```json
{
  "agentName": "backend-engineer"
}
```

#### Response

```json
{
  "success": true,
  "message": "Agent definition retrieved successfully",
  "data": {
    "name": "Backend Engineer",
    "file": "backend-engineer.md",
    "model": "sonnet",
    "tools": ["Write", "Edit", "Read", "Bash"],
    "description": "Specialist for backend implementation tasks",
    "capabilities": [
      "API development",
      "Database integration",
      "Authentication systems",
      "Business logic implementation"
    ]
  }
}
```

---

### recommend_agent

**Permission**: 🔍 READ-ONLY

**Purpose**: Get routing recommendation (Skill vs Subagent)

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `taskId` | UUID | **Yes** | Task to analyze for routing |

#### Example

```json
{
  "taskId": "640522b7-810e-49a2-865c-3725f5d39608"
}
```

#### Response - Skill Recommendation

```json
{
  "success": true,
  "message": "Routing recommendation generated",
  "data": {
    "recommended": true,
    "recommendationType": "SKILL",
    "agent": "Task Management Skill",
    "reason": "Task requires lightweight coordination for routing",
    "matchedTags": ["task-routing", "coordination"],
    "sectionTags": ["requirements", "context"],
    "taskId": "640522b7-810e-49a2-865c-3725f5d39608",
    "taskTitle": "Route task to specialist",
    "nextAction": {
      "instruction": "Use the Task Management Skill directly",
      "tool": "Skill",
      "parameters": {
        "skill": "Task Management Skill",
        "prompt": "Route this task to appropriate specialist"
      }
    }
  }
}
```

#### Response - Subagent Recommendation

```json
{
  "success": true,
  "message": "Routing recommendation generated",
  "data": {
    "recommended": true,
    "recommendationType": "SUBAGENT",
    "agent": "Backend Engineer",
    "reason": "Task requires complex backend implementation work",
    "matchedTags": ["backend", "api-implementation"],
    "sectionTags": ["implementation", "technical-approach"],
    "taskId": "640522b7-810e-49a2-865c-3725f5d39608",
    "taskTitle": "Implement REST API endpoints",
    "nextAction": {
      "instruction": "Launch Backend Engineer subagent",
      "tool": "Task",
      "parameters": {
        "agent": "backend-engineer",
        "prompt": "Implement the REST API endpoints described in task"
      }
    }
  }
}
```

---

## Workflow Tools

Workflow tools provide intelligent recommendations for task management and status progression.

### get_next_status

**Permission**: 🔍 READ-ONLY

**Purpose**: Get intelligent status progression recommendations based on workflow configuration

**Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `containerId` | UUID | **Yes** | UUID of task/feature/project to analyze |
| `containerType` | enum | **Yes** | Type: `task`, `feature`, or `project` |
| `currentStatus` | string | No | Override current status (for what-if analysis) |
| `tags` | array[string] | No | Override entity tags (for flow determination) |

#### How It Works

The tool analyzes the entity and workflow configuration to recommend the next status:

1. **Fetches entity** - Gets current status and tags from repository
2. **Determines workflow** - Uses tags to match flow (e.g., bug_fix_flow, documentation_flow, default_flow)
3. **Checks prerequisites** - Validates completion requirements via StatusValidator
4. **Returns recommendation** - Ready/Blocked/Terminal with detailed context

#### Recommendation Types

**Ready**: Entity can progress to next status
- Includes: `recommendedStatus`, `activeFlow`, `flowSequence`, `currentPosition`, `matchedTags`, `reason`

**Blocked**: Prerequisites not met
- Includes: `currentStatus`, `blockers` (array), `activeFlow`, `flowSequence`, `currentPosition`, `reason`

**Terminal**: At final status (completed, cancelled, archived)
- Includes: `currentStatus`, `activeFlow`, `reason`

#### Example - Task Ready for Next Status

**Request**:
```json
{
  "containerId": "640522b7-810e-49a2-865c-3725f5d39608",
  "containerType": "task"
}
```

**Response (Ready)**:
```json
{
  "success": true,
  "message": "Ready to progress to 'testing' in default_flow",
  "data": {
    "recommendation": "Ready",
    "recommendedStatus": "testing",
    "currentStatus": "in-progress",
    "activeFlow": "default_flow",
    "flowSequence": ["backlog", "pending", "in-progress", "testing", "completed"],
    "currentPosition": 2,
    "matchedTags": ["backend", "api"],
    "reason": "Task is ready to progress from in-progress to testing. All prerequisites met."
  }
}
```

#### Example - Task Blocked by Prerequisites

**Request**:
```json
{
  "containerId": "640522b7-810e-49a2-865c-3725f5d39608",
  "containerType": "task"
}
```

**Response (Blocked)**:
```json
{
  "success": true,
  "message": "Blocked by 1 issue(s)",
  "data": {
    "recommendation": "Blocked",
    "currentStatus": "testing",
    "blockers": [
      "Task summary must be 300-500 characters (current: 50)"
    ],
    "activeFlow": "default_flow",
    "flowSequence": ["backlog", "pending", "in-progress", "testing", "completed"],
    "currentPosition": 3,
    "reason": "Cannot progress: Task summary must be 300-500 characters (current: 50)"
  }
}
```

#### Example - Terminal Status

**Request**:
```json
{
  "containerId": "640522b7-810e-49a2-865c-3725f5d39608",
  "containerType": "task"
}
```

**Response (Terminal)**:
```json
{
  "success": true,
  "message": "At terminal status 'completed'",
  "data": {
    "recommendation": "Terminal",
    "currentStatus": "completed",
    "activeFlow": "default_flow",
    "reason": "Task has reached terminal status 'completed'. No further progression possible."
  }
}
```

#### Example - What-If Analysis

Override current status or tags to test different scenarios:

**Request**:
```json
{
  "containerId": "640522b7-810e-49a2-865c-3725f5d39608",
  "containerType": "task",
  "currentStatus": "pending",
  "tags": ["bug", "urgent"]
}
```

**Response**:
```json
{
  "success": true,
  "message": "Ready to progress to 'in-progress' in bug_fix_flow",
  "data": {
    "recommendation": "Ready",
    "recommendedStatus": "in-progress",
    "currentStatus": "pending",
    "activeFlow": "bug_fix_flow",
    "flowSequence": ["pending", "in-progress", "testing", "completed"],
    "currentPosition": 0,
    "matchedTags": ["bug"],
    "reason": "Using bug_fix_flow (matched tags: bug). Ready to start work."
  }
}
```

#### Integration with Status Progression Skill

The **Status Progression Skill** (Claude Code only) uses `get_next_status` to provide human-friendly guidance:

**User**: "Can I complete this task?"

**Skill workflow**:
1. Calls `get_next_status(containerId="...", containerType="task")`
2. Interprets recommendation
3. If Blocked → explains blockers and how to fix them
4. If Ready → confirms and provides setStatus command
5. If Terminal → explains status is final

**Example Skill Response (Blocked)**:
```
Not ready for completion. Your config requires task summary (300-500 chars).

Current: 50 characters
Required: 300-500 characters

Fix:
manage_container(operation="update", containerType="task",
  id="...", summary="[300-500 char description]")

Then complete:
manage_container(operation="setStatus", containerType="task",
  id="...", status="completed")
```

#### Usage Notes

1. **Read-Only**: This tool ONLY recommends status. Use `manage_container(operation="setStatus", ...)` to apply changes.

2. **Flow Determination**: Entity tags are matched against workflow configuration (config.yaml) to determine active flow:
   - Tags `["bug"]` → `bug_fix_flow`
   - Tags `["docs", "documentation"]` → `documentation_flow`
   - Default → `default_flow`

3. **Prerequisite Validation**: Automatically checks:
   - **Tasks**: Summary length (300-500 chars for completed), blocking dependencies
   - **Features**: Task completion, minimum task count
   - **Projects**: Feature completion

4. **Terminal Statuses**: Cannot progress from:
   - Tasks: `completed`, `cancelled`, `deferred`
   - Features: `completed`, `archived`
   - Projects: `completed`, `archived`, `cancelled`

5. **What-If Analysis**: Use optional `currentStatus` and `tags` parameters to test scenarios without modifying entities

#### Related Tools

- `manage_container` - Apply status changes with `setStatus` operation
- `query_container` - Get entity details
- **Status Progression Skill** (Claude Code) - Natural language interface to get_next_status

#### Additional Resources

- **[Status Progression Guide](status-progression.md)** - Comprehensive workflow examples
- **[config.yaml Reference](../src/main/resources/orchestration/default-config.yaml)** - Status flow configuration
- **[Status Progression Skill](.claude/skills/status-progression/SKILL.md)** - Claude Code skill documentation

---

### query_workflow_state

**Permission**: 🔍 READ-ONLY

**Purpose**: Query comprehensive workflow state for any container

**Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `containerType` | enum | **Yes** | Type: `task`, `feature`, or `project` |
| `id` | UUID | **Yes** | Container ID to query workflow state for |

#### What It Returns

Complete workflow state including:
- **Current status** and **active workflow flow** (based on entity tags)
- **Allowed transitions** from current status (based on config)
- **Detected cascade events** that may trigger related entity updates
- **Prerequisites for each transition** (what must be met before transitioning)

#### Use Cases

1. **Pre-flight check** - Verify prerequisites before status changes
2. **Cascade preview** - See what will happen when you complete a task
3. **Debug workflow issues** - Understand why status change failed
4. **Discover allowed transitions** - See all valid next statuses

#### Example Request

```json
{
  "containerType": "task",
  "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
}
```

#### Example Response

```json
{
  "success": true,
  "message": "Workflow state retrieved successfully",
  "data": {
    "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
    "containerType": "task",
    "currentStatus": "in-progress",
    "activeFlow": "default_flow",
    "allowedTransitions": ["testing", "in-review", "blocked", "on-hold"],
    "detectedEvents": [],
    "prerequisites": {
      "testing": {
        "met": false,
        "requirements": [
          "Summary must be 300-500 characters",
          "No incomplete blocking dependencies"
        ],
        "blockingReasons": [
          "Summary too short: 50 characters (need 300-500)"
        ]
      },
      "blocked": {
        "met": true,
        "requirements": ["Emergency transition - no prerequisites"]
      }
    }
  }
}
```

#### Related Tools

- `get_next_status` - Get intelligent recommendations (uses query_workflow_state internally)
- `manage_container` (setStatus) - Apply status changes after validation

#### Detailed Documentation

**[query_workflow_state](tools/query-workflow-state.md)** - Complete reference with examples

---

## Permission Model

### Read vs Write Separation

v2.0 introduces **clear permission separation** between read and write operations:

| Tool Pattern | Permission | Locking | Use Case |
|--------------|-----------|---------|----------|
| `query_*` | 🔍 READ-ONLY | No | Safe, concurrent reads |
| `manage_*` | ✏️ WRITE | Yes | Mutation with collision protection |

### Read-Only Tools (query_*)

- `query_container` - Read projects/features/tasks
- `query_sections` - Read sections with filtering
- `query_templates` - Read template definitions
- `query_dependencies` - Read dependency relationships
- `list_tags` - List all tags
- `get_tag_usage` - Find tag usage
- `get_agent_definition` - Read agent metadata
- `recommend_agent` - Get routing recommendation
- `get_next_status` - Status progression recommendations

**Characteristics**:
- ✅ No locking required
- ✅ Safe concurrent access
- ✅ Cannot modify data
- ✅ Fast execution

### Write Tools (manage_*)

- `manage_container` - Create/update/delete containers
- `manage_sections` - Add/update/delete sections
- `manage_template` - Create/update/delete templates
- `manage_dependency` - Create/delete dependencies
- `rename_tag` - Bulk rename tags
- `apply_template` - Apply templates to entities
- `setup_project` - Initialize project configuration

**Characteristics**:
- ✅ Automatic locking
- ✅ Collision prevention
- ✅ Transaction support
- ✅ Audit trail

### Benefits

1. **Security**: Clear read/write boundaries for access control
2. **Concurrency**: Read tools don't block each other
3. **Intent**: Tool name immediately signals permission requirement
4. **Future-proof**: Easy to add permission layers

---

## Best Practices

### AI Agent Patterns

#### 1. Always Discover Templates First

**Before creating any task or feature**:
```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "isEnabled": true
}
```

**Why**: Dynamic template discovery ensures AI applies appropriate guidance

---

#### 2. Use Two-Step Section Loading

**Step 1: Browse structure** (low token cost):
```json
{
  "entityType": "TASK",
  "entityId": "...",
  "includeContent": false
}
```

**Step 2: Fetch specific content**:
```json
{
  "entityType": "TASK",
  "entityId": "...",
  "sectionIds": ["needed-section-uuid"]
}
```

**Savings**: 85-99% token reduction vs loading all content

---

#### 3. Prefer Bulk Operations

**Instead of**:
```
3 × manage_sections(operation="delete", id="...")
= ~3,600 characters
```

**Use**:
```json
{
  "operation": "bulkDelete",
  "ids": ["id1", "id2", "id3"]
}
= ~400 characters
```

**Savings**: 89% token reduction

---

#### 4. Use setStatus for Status-Only Changes

**Instead of**:
```json
{
  "operation": "update",
  "containerType": "task",
  "id": "...",
  "status": "completed"
}
```

**Use**:
```json
{
  "operation": "setStatus",
  "containerType": "task",
  "id": "...",
  "status": "completed"
}
```

**Benefits**: Simpler, faster, clear intent

---

#### 5. Progressive Loading

**Start minimal, load details as needed**:

```
1. query_container(operation="search", containerType="task", status="pending")
   → Get task list

2. query_container(operation="get", containerType="task", id="...", includeSections=false)
   → Get task details (no sections)

3. query_sections(entityType="TASK", entityId="...", includeContent=false)
   → Get section structure

4. query_sections(entityType="TASK", entityId="...", sectionIds=["needed"])
   → Get specific section content
```

---

### Development Team Patterns

#### 1. Natural Language Works

**Don't teach users tool syntax** - AI understands intent:

```
User: "Show me pending backend tasks"
→ AI uses: query_container(operation="search", containerType="task",
                           status="pending", tags="backend")

User: "Mark task X as done"
→ AI uses: manage_container(operation="setStatus", containerType="task",
                            id="X", status="completed")
```

---

#### 2. Trust Autonomous Workflows

**AI chains tools automatically**:

```
User: "Create a feature for user authentication"

AI Workflow:
1. query_templates(operation="list", targetEntityType="FEATURE")
2. manage_container(operation="create", containerType="feature",
                   name="User Authentication", templateIds=[...])
3. query_templates(operation="list", targetEntityType="TASK")
4. manage_container(operation="create", containerType="task",
                   title="Implement OAuth", templateIds=[...])
5. manage_dependency(operation="create", fromTaskId="...", toTaskId="...")
```

---

#### 3. Create Custom Templates

**AI discovers them automatically**:

1. Create custom template:
```json
{
  "operation": "create",
  "name": "Company-Specific API Template",
  "description": "Our API development standards",
  "targetEntityType": "TASK",
  "tags": "api,company-standard"
}
```

2. Add sections via `manage_template(operation="addSection", ...)`

3. AI automatically discovers via `query_templates` and applies

---

### entityType vs containerType

**Key Difference**:

| Parameter | Case | Used In | Values |
|-----------|------|---------|--------|
| `containerType` | lowercase | Container tools | `project`, `feature`, `task` |
| `entityType` | UPPERCASE | Section/template tools | `PROJECT`, `FEATURE`, `TASK` |

**Why Different**:
- **Containers** = primary entities (projects, features, tasks)
- **Entity** = parent owner of sections/templates

**Remember**:
- Container tools → lowercase `containerType`
- Section tools → UPPERCASE `entityType`
- Template tools → UPPERCASE `targetEntityType` (for filtering)

---

### Token Optimization Summary

| Pattern | Token Savings | When to Use |
|---------|---------------|-------------|
| Two-step section loading | 85-99% | Browse structure before loading content |
| Bulk operations | 70-95% | Updating 3+ items simultaneously |
| `setStatus` vs `update` | 40-60% | Status-only changes |
| Template discovery caching | Auto | Repeated template access |
| Progressive container loading | 50-80% | Load details only when needed |

---

## Migration from v1.x

**Complete migration guide**: [v2.0 Migration Guide](migration/v2.0-migration-guide.md)

**Quick reference**:

| v1.x Tool | v2.0 Tool | v2.0 Operation |
|-----------|-----------|----------------|
| `create_task` | `manage_container` | `create` (containerType=task) |
| `get_task` | `query_container` | `get` (containerType=task) |
| `search_tasks` | `query_container` | `search` (containerType=task) |
| `update_task` | `manage_container` | `update` (containerType=task) |
| `delete_task` | `manage_container` | `delete` (containerType=task) |
| `get_sections` | `query_sections` | (no operation parameter) |
| `add_section` | `manage_sections` | `add` |
| `list_templates` | `query_templates` | `list` |
| `create_dependency` | `manage_dependency` | `create` |

---

## Additional Resources

- **[v2.0 Migration Guide](migration/v2.0-migration-guide.md)** - Complete migration instructions
- **[AI Guidelines](ai-guidelines.md)** - AI usage patterns and workflows
- **[Templates Guide](templates.md)** - Template system documentation
- **[Workflow Prompts](workflow-prompts.md)** - Workflow automation
- **[Quick Start](quick-start.md)** - Getting started guide

---

**Questions?** AI agents discover tool schemas automatically through MCP. Ask Claude directly for parameter details, usage examples, or integration patterns.

**Last Updated**: 2025-11-03
**Version**: 2.0.0
**Tool Count**: 18 tools (68% reduction from v1.x)
