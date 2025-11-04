# manage_container Tool - Detailed Documentation

## Overview

The `manage_container` tool provides unified write operations for all container types (projects, features, tasks). It consolidates multiple former individual tools into a single, efficient interface with five operations: `create`, `update`, `delete`, `setStatus`, and `bulkUpdate`.

**Key Feature (v2.0+):** The `manage_container` tool handles all write operations across three container types (project, feature, task) with consistent validation, template application, and status management through a single interface.

**Resource**: `task-orchestrator://docs/tools/manage-container`

## Key Concepts

### Unified Container Interface

All three container types share a single, consistent write interface:

- **Projects** - Top-level organization containers
- **Features** - Groups of related tasks within projects
- **Tasks** - Individual work items within features

### Five Core Operations

1. **create** - Create new containers with optional template application
2. **update** - Modify existing container properties
3. **delete** - Remove containers with dependency handling
4. **setStatus** - Change container status with workflow validation
5. **bulkUpdate** - Update up to 100 containers in a single operation

### Parameter Consistency Philosophy

v2.0 `manage_container` emphasizes **consistent parameter naming** across all container types:

- Use `name` for project and feature names, `title` for task titles (or use `name` as alias)
- Container-specific parameters (complexity, priority) are automatically ignored for irrelevant types
- Status values are validated per-container-type
- All IDs use standard UUID format

## Parameter Reference

### Common Parameters (All Operations)

| Parameter       | Type | Required | Description                                      |
|-----------------|------|----------|--------------------------------------------------|
| `operation`     | enum | **Yes**  | Operation: `create`, `update`, `delete`, `setStatus`, `bulkUpdate` |
| `containerType` | enum | **Yes**  | Container type: `project`, `feature`, `task`     |

### Operation-Specific Parameters

| Parameter      | Type    | Operations             | Description                                       |
|----------------|---------|------------------------|---------------------------------------------------|
| `id`           | UUID    | update, delete, setStatus | Container ID (required for these operations) |
| `name`         | string  | create, update         | Container name (project/feature only)             |
| `title`        | string  | create, update         | Task title (alias for `name`)                     |
| `description`  | string  | create, update         | Detailed description                              |
| `summary`      | string  | create, update         | Brief summary (max 500 chars)                     |
| `status`       | string  | create, update, setStatus | Container status                               |
| `priority`     | enum    | create, update         | Priority level (feature/task only): high, medium, low |
| `complexity`   | integer | create, update         | Complexity 1-10 (task only)                       |
| `projectId`    | UUID    | create, update         | Parent project ID (feature/task)                  |
| `featureId`    | UUID    | create, update         | Parent feature ID (task only)                     |
| `tags`         | string  | create, update         | Comma-separated tags                              |
| `templateIds`  | array   | create                 | Template IDs to apply (feature/task only)         |
| `deleteSections` | boolean | delete                | Delete sections (default: true)                   |
| `force`        | boolean | delete                | Force delete with dependencies (default: false)   |
| `containers`   | array   | bulkUpdate             | Array of container objects (max 100 items)        |

### Status Values by Container Type

| Container Type | Valid Status Values                                  |
|----------------|------------------------------------------------------|
| **project**    | planning, active, in-development, in-review, completed, cancelled |
| **feature**    | planning, in-development, in-review, completed, cancelled |
| **task**       | pending, in-progress, in-review, completed, cancelled, blocked |

---

## Quick Start

### Basic Create Pattern

```json
{
  "operation": "create",
  "containerType": "task",
  "title": "Implement OAuth2 authentication",
  "featureId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "priority": "high",
  "complexity": 7,
  "tags": "backend,security,authentication"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Task created successfully",
  "data": {
    "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
    "title": "Implement OAuth2 authentication",
    "status": "pending",
    "createdAt": "2025-10-24T19:30:00Z"
  }
}
```

### Basic Update Pattern

```json
{
  "operation": "update",
  "containerType": "task",
  "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "status": "in-progress",
  "summary": "Implementing Google OAuth 2.0 with JWT token management"
}
```

### Basic Delete Pattern

```json
{
  "operation": "delete",
  "containerType": "task",
  "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
}
```

### Common Patterns

**Create with Templates** (feature/task only):

```json
{
  "operation": "create",
  "containerType": "feature",
  "name": "User Authentication System",
  "projectId": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "priority": "high",
  "templateIds": [
    "550e8400-e29b-41d4-a716-446655440001",
    "550e8400-e29b-41d4-a716-446655440002"
  ]
}
```

**Bulk Update Multiple Tasks**:

```json
{
  "operation": "bulkUpdate",
  "containerType": "task",
  "containers": [
    {
      "id": "task-uuid-1",
      "status": "completed",
      "summary": "Task 1 complete"
    },
    {
      "id": "task-uuid-2",
      "status": "completed",
      "summary": "Task 2 complete"
    }
  ]
}
```

---

## Operation 1: create

**Purpose**: Create a new container (project, feature, or task) with optional template application

### Required Parameters

- `operation`: "create"
- `containerType`: "project", "feature", or "task"
- `name` (project/feature) or `title` (task): Container name/title

### Optional Parameters

- `description`: Detailed description
- `summary`: Brief summary (max 500 chars)
- `status`: Initial status (defaults per type)
- `priority`: high, medium, low (feature/task only)
- `complexity`: 1-10 (task only, default: 5)
- `projectId`: Parent project ID (feature/task)
- `featureId`: Parent feature ID (task only)
- `tags`: Comma-separated tags
- `templateIds`: Array of template UUIDs (feature/task only)

### Default Status Values

- **project**: "planning"
- **feature**: "planning"
- **task**: "pending"

### Example - Create Project

```json
{
  "operation": "create",
  "containerType": "project",
  "name": "E-Commerce Platform",
  "description": "Complete e-commerce platform with shopping cart, payment processing, and admin dashboard",
  "summary": "Full-featured e-commerce platform with payment integration",
  "tags": "e-commerce,platform,v2.0"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Project created successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "E-Commerce Platform",
    "status": "planning",
    "createdAt": "2025-10-24T10:00:00Z"
  }
}
```

**Token Cost**: ~200-300 tokens

### Example - Create Feature with Templates

```json
{
  "operation": "create",
  "containerType": "feature",
  "name": "User Authentication System",
  "description": "Comprehensive authentication with OAuth, JWT, and session management",
  "summary": "OAuth2 and JWT-based authentication with multiple provider support",
  "projectId": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "priority": "high",
  "status": "planning",
  "tags": "backend,security,authentication",
  "templateIds": [
    "550e8400-e29b-41d4-a716-446655440001"
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "Feature created successfully with 1 template(s) applied, creating 5 section(s)",
  "data": {
    "id": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
    "name": "User Authentication System",
    "status": "planning",
    "createdAt": "2025-10-24T10:15:00Z",
    "appliedTemplates": [
      {
        "templateId": "550e8400-e29b-41d4-a716-446655440001",
        "sectionsCreated": 5
      }
    ]
  }
}
```

**Token Cost**: ~250-350 tokens (plus template section creation)

### Example - Create Task with All Parameters

```json
{
  "operation": "create",
  "containerType": "task",
  "title": "Implement Google OAuth provider",
  "description": "Add Google OAuth 2.0 authentication provider with token management and user profile sync",
  "summary": "Add Google OAuth 2.0 authentication with JWT token integration",
  "featureId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "projectId": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "priority": "high",
  "complexity": 7,
  "status": "pending",
  "tags": "backend,oauth,google,authentication",
  "templateIds": [
    "550e8400-e29b-41d4-a716-446655440002"
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "Task created successfully with 1 template(s) applied, creating 3 section(s)",
  "data": {
    "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
    "title": "Implement Google OAuth provider",
    "status": "pending",
    "createdAt": "2025-10-24T10:30:00Z",
    "appliedTemplates": [
      {
        "templateId": "550e8400-e29b-41d4-a716-446655440002",
        "sectionsCreated": 3
      }
    ]
  }
}
```

**Token Cost**: ~250-350 tokens

### When to Use Create

✅ **Use create when:**

- Building new projects or features
- Creating individual tasks
- Need to apply templates for standard structure
- Starting new work streams

### Create Best Practices

1. **Always specify tags** - Helps with filtering and categorization
2. **Include summary** - Concise overview for dashboard views
3. **Set appropriate priority** - High/Medium/Low for task scheduling
4. **Apply templates** - Leverage existing templates for features/tasks (get list via query_templates)
5. **Validate parent exists** - Tool will validate projectId/featureId exist before creation
6. **Use complexity wisely** - 1-3: trivial, 4-6: moderate, 7-9: complex, 10: very complex

---

## Operation 2: update

**Purpose**: Modify existing container properties

### Required Parameters

- `operation`: "update"
- `containerType`: "project", "feature", or "task"
- `id`: Container UUID to update

### Optional Parameters

- `name` (project/feature): Update name
- `title` (task): Update task title (alias for `name`)
- `description`: Update description
- `summary`: Update summary
- `status`: Update status (with validation)
- `priority`: Update priority (feature/task only)
- `complexity`: Update complexity (task only)
- `projectId`: Update parent project (feature/task)
- `featureId`: Update parent feature (task only)
- `tags`: Update tags

**Note**: Only specified fields are updated; others remain unchanged.

### Example - Update Task Status and Summary

```json
{
  "operation": "update",
  "containerType": "task",
  "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "status": "in-progress",
  "summary": "Implementing Google OAuth 2.0 with JWT token management. Started frontend integration."
}
```

**Response**:

```json
{
  "success": true,
  "message": "Task updated successfully",
  "data": {
    "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
    "status": "in-progress",
    "modifiedAt": "2025-10-24T14:00:00Z"
  }
}
```

**Token Cost**: ~150-200 tokens

### Example - Update Feature Priority and Project

```json
{
  "operation": "update",
  "containerType": "feature",
  "id": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "priority": "high",
  "projectId": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "status": "in-development"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Feature updated successfully",
  "data": {
    "id": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
    "status": "in-development",
    "modifiedAt": "2025-10-24T14:15:00Z"
  }
}
```

**Token Cost**: ~150-200 tokens

### Example - Update Project Name and Status

```json
{
  "operation": "update",
  "containerType": "project",
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "name": "MCP Task Orchestrator v2.0",
  "status": "active"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Project updated successfully",
  "data": {
    "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
    "status": "active",
    "modifiedAt": "2025-10-24T14:30:00Z"
  }
}
```

**Token Cost**: ~150-200 tokens

### Update Validation Rules

- **Status transitions** are validated per-container-type
- **Priority values**: must be `high`, `medium`, or `low` (feature/task only)
- **Complexity**: must be 1-10 (task only)
- **Parent updates**: projectId/featureId must exist in database
- **Summary**: max 500 characters

### When to Use Update

✅ **Use update when:**

- Changing status (use setStatus operation if status-only change)
- Updating task complexity
- Reassigning to different project/feature
- Adding/modifying description or summary
- Changing priority

---

## Operation 3: delete

**Purpose**: Remove containers with optional dependency handling

### Required Parameters

- `operation`: "delete"
- `containerType`: "project", "feature", or "task"
- `id`: Container UUID to delete

### Optional Parameters

- `deleteSections`: Delete associated sections (default: true)
- `force`: Force delete with dependencies (default: false)

### Deletion Behavior

**Without force flag**:
- Cannot delete project with features
- Cannot delete feature with tasks
- Can delete task (child) without issues

**With force=true**:
- Deletes project AND all features AND all tasks
- Deletes feature AND all tasks
- Deletes task regardless of dependencies

### Example - Delete Task (Safe)

```json
{
  "operation": "delete",
  "containerType": "task",
  "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Task deleted successfully",
  "data": {
    "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
    "sections_deleted": 8
  }
}
```

**Token Cost**: ~150 tokens

### Example - Delete Feature (Requires Force)

```json
{
  "operation": "delete",
  "containerType": "feature",
  "id": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "force": true,
  "deleteSections": true
}
```

**Response**:

```json
{
  "success": true,
  "message": "Feature deleted successfully with all 20 child tasks",
  "data": {
    "id": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
    "tasks_deleted": 20,
    "sections_deleted": 45,
    "dependencies_cleaned": 8
  }
}
```

**Token Cost**: ~150-200 tokens

### Example - Delete Attempt Without Force (Error)

```json
{
  "operation": "delete",
  "containerType": "feature",
  "id": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c"
}
```

**Response**:

```json
{
  "success": false,
  "message": "Cannot delete feature with 20 child tasks",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Use force=true to delete feature and all child tasks"
  }
}
```

### Delete Best Practices

1. **Always check children first** - Query feature/project before deleting
2. **Use force carefully** - Cascade deletion is permanent
3. **Keep deleteSections=true** - Prevents orphaned sections
4. **Consider archiving instead** - Use status=cancelled instead of deleting
5. **Clean up dependencies** - Tool auto-cleans dependency references

### When to Use Delete

✅ **Use delete when:**

- Removing completed, obsolete work
- Cleaning up test data
- Removing duplicate containers

❌ **Avoid delete when:**

- Work is ongoing (use status=cancelled instead)
- Others depend on this work (archive/mark cancelled)
- You're unsure (prefer marking as cancelled)

---

## Operation 4: setStatus

**Purpose**: Change container status with workflow validation

### Required Parameters

- `operation`: "setStatus"
- `containerType`: "project", "feature", or "task"
- `id`: Container UUID
- `status`: New status value

### Optional Parameters

- None (only status change operation)

### Status Validation

Status changes are validated against container-type-specific workflows. Invalid transitions are rejected.

### Valid Status Transitions

**Project**: planning → active → in-development → in-review → completed/cancelled

**Feature**: planning → in-development → in-review → completed/cancelled

**Task**: pending → in-progress → in-review → completed/cancelled/blocked

### Example - Task Status Progression

```json
{
  "operation": "setStatus",
  "containerType": "task",
  "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "status": "in-progress"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Task status updated to in-progress",
  "data": {
    "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
    "status": "in-progress",
    "modifiedAt": "2025-10-24T15:00:00Z"
  }
}
```

**Token Cost**: ~150 tokens

### Example - Feature Status to Completed

```json
{
  "operation": "setStatus",
  "containerType": "feature",
  "id": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "status": "completed"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Feature status updated to completed",
  "data": {
    "id": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
    "status": "completed",
    "modifiedAt": "2025-10-24T15:15:00Z"
  }
}
```

**Token Cost**: ~150 tokens

### Status Workflows

**Project Workflow**:
```
planning → active → in-development → in-review → completed
              ↓
            cancelled (anytime)
```

**Feature Workflow**:
```
planning → in-development → in-review → completed
  ↓
cancelled (anytime)
```

**Task Workflow**:
```
pending → in-progress → in-review → completed
  ↓          ↓
blocked    cancelled (anytime)
```

### Integration with get_next_status

For intelligent status recommendations based on workflow configuration, use `get_next_status`:

```json
{
  "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "containerType": "task"
}
```

This returns whether container is Ready, Blocked, or Terminal for progression, helping you determine the right status to set.

### When to Use setStatus

✅ **Use setStatus when:**

- Changing status without other modifications
- Status-only updates
- Integrated with get_next_status for recommendations

❌ **Avoid setStatus when:**

- Also updating other fields (use update instead)
- Unsure if status transition is valid (use get_next_status first)

---

## Operation 5: bulkUpdate

**Purpose**: Update up to 100 containers in a single operation for batch processing

### Required Parameters

- `operation`: "bulkUpdate"
- `containerType`: "project", "feature", or "task"
- `containers`: Array of container update objects (1-100 items)

### Container Object Structure

Each container object must include:
- `id`: UUID (required)
- At least one field to update: name, title, description, summary, status, priority, complexity, projectId, featureId, tags

### Example - Bulk Complete Tasks

```json
{
  "operation": "bulkUpdate",
  "containerType": "task",
  "containers": [
    {
      "id": "task-uuid-1",
      "status": "completed",
      "summary": "Completed OAuth implementation"
    },
    {
      "id": "task-uuid-2",
      "status": "completed",
      "summary": "Completed JWT token management"
    },
    {
      "id": "task-uuid-3",
      "status": "completed",
      "summary": "Completed password reset flow"
    }
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "3 task(s) updated successfully",
  "data": {
    "updated": 3,
    "failed": 0,
    "results": [
      {
        "id": "task-uuid-1",
        "status": "success",
        "modifiedAt": "2025-10-24T16:00:00Z"
      },
      {
        "id": "task-uuid-2",
        "status": "success",
        "modifiedAt": "2025-10-24T16:00:01Z"
      },
      {
        "id": "task-uuid-3",
        "status": "success",
        "modifiedAt": "2025-10-24T16:00:02Z"
      }
    ]
  }
}
```

**Token Cost**: ~200-400 tokens (depending on count)

### Example - Bulk Priority Update

```json
{
  "operation": "bulkUpdate",
  "containerType": "feature",
  "containers": [
    {
      "id": "feature-uuid-1",
      "priority": "high"
    },
    {
      "id": "feature-uuid-2",
      "priority": "high"
    },
    {
      "id": "feature-uuid-3",
      "priority": "medium"
    }
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "3 feature(s) updated successfully",
  "data": {
    "updated": 3,
    "failed": 0,
    "results": [
      {
        "id": "feature-uuid-1",
        "status": "success",
        "modifiedAt": "2025-10-24T16:15:00Z"
      },
      {
        "id": "feature-uuid-2",
        "status": "success",
        "modifiedAt": "2025-10-24T16:15:01Z"
      },
      {
        "id": "feature-uuid-3",
        "status": "success",
        "modifiedAt": "2025-10-24T16:15:02Z"
      }
    ]
  }
}
```

**Token Cost**: ~200-300 tokens

### Example - Mixed Field Bulk Update

```json
{
  "operation": "bulkUpdate",
  "containerType": "task",
  "containers": [
    {
      "id": "task-uuid-1",
      "status": "in-progress",
      "priority": "high"
    },
    {
      "id": "task-uuid-2",
      "status": "completed",
      "summary": "Finalized implementation"
    },
    {
      "id": "task-uuid-3",
      "complexity": 8,
      "priority": "high"
    }
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "3 task(s) updated successfully",
  "data": {
    "updated": 3,
    "failed": 0,
    "results": [...]
  }
}
```

### BulkUpdate Validation Rules

- Maximum 100 containers per request
- Each container requires `id`
- Each container requires at least one updatable field
- Field validation is per-container-type (complexity ignored for projects)
- Status values must be valid for container type
- Priority must be high/medium/low (if specified)
- Complexity must be 1-10 (if specified)
- UUIDs must be valid format

### Example - BulkUpdate with Errors

```json
{
  "operation": "bulkUpdate",
  "containerType": "task",
  "containers": [
    {
      "id": "task-uuid-1",
      "status": "completed"
    },
    {
      "id": "invalid-uuid",
      "status": "in-progress"
    },
    {
      "id": "task-uuid-3",
      "status": "invalid-status"
    }
  ]
}
```

**Response**:

```json
{
  "success": false,
  "message": "2 of 3 container(s) updated, 1 failed",
  "data": {
    "updated": 2,
    "failed": 1,
    "results": [
      {
        "id": "task-uuid-1",
        "status": "success",
        "modifiedAt": "2025-10-24T16:30:00Z"
      },
      {
        "id": "invalid-uuid",
        "status": "error",
        "error": "Invalid UUID format"
      },
      {
        "id": "task-uuid-3",
        "status": "error",
        "error": "Invalid status: invalid-status"
      }
    ]
  }
}
```

### Bulk Update Best Practices

1. **Validate before bulk update** - Query to verify IDs exist first
2. **Keep batches reasonable** - Use 10-50 items per request, not max 100
3. **Handle partial failures** - Check results array for per-item status
4. **Use for status changes** - Ideal for marking batches complete
5. **Consistent fields** - Update same type of field per batch (all status, or all priority)

### When to Use BulkUpdate

✅ **Use bulkUpdate when:**

- Marking multiple items status complete
- Batch priority updates
- Reassigning multiple items
- Periodic batch maintenance
- Workflow automation scripts

❌ **Avoid bulkUpdate when:**

- Single item update (use update operation)
- Complex conditional updates
- Updates requiring validation between items

---

## Advanced Usage

### Token Efficiency Notes

| Operation      | Typical Tokens | Best For              |
|----------------|----------------|-----------------------|
| create         | 200-350        | Single new item       |
| update         | 150-200        | Field modifications   |
| delete         | 150-200        | Removal               |
| setStatus      | 150            | Status-only changes   |
| bulkUpdate     | 200-400        | Batch operations      |

**Recommendation**: Use bulkUpdate for 3+ similar updates (more efficient than individual update calls).

### With Status Progression Skill

The Status Progression Skill integrates with manage_container:

```
1. Call get_next_status to validate readiness
   ↓
2. If Ready → use setStatus to apply recommended status
   ↓
3. If Blocked → show blocking requirements
   ↓
4. If Terminal → no further progression possible
```

**Example workflow**:

```json
// Step 1: Check if task can progress
{
  "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "containerType": "task"
}

// Step 2: If Ready, apply status
{
  "operation": "setStatus",
  "containerType": "task",
  "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "status": "in-progress"
}
```

### Template Application During Create

Templates provide automatic section structure:

```json
{
  "operation": "create",
  "containerType": "task",
  "title": "Implement payment processing",
  "featureId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "templateIds": [
    "550e8400-e29b-41d4-a716-446655440001",
    "550e8400-e29b-41d4-a716-446655440002"
  ]
}
```

**Response includes**:

```json
{
  "appliedTemplates": [
    {
      "templateId": "550e8400-e29b-41d4-a716-446655440001",
      "sectionsCreated": 5
    },
    {
      "templateId": "550e8400-e29b-41d4-a716-446655440002",
      "sectionsCreated": 3
    }
  ]
}
```

### Dependency Handling

Delete operation automatically cleans up dependencies:

```json
{
  "operation": "delete",
  "containerType": "task",
  "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "force": true
}
```

**Cleanup includes**:
- Remove task from dependency relationships
- Clean up BLOCKS relationships
- Clean up IS_BLOCKED_BY relationships
- Preserve other task integrity

---

## Error Handling

| Error Code | Condition | Solution |
|-----------|-----------|----------|
| VALIDATION_ERROR | Invalid operation, containerType, or parameters | Check parameter format and values |
| VALIDATION_ERROR | Required parameter missing | Verify `operation`, `containerType`, `id` (where required) |
| VALIDATION_ERROR | Invalid UUID format | Use valid UUIDs, format: `550e8400-e29b-41d4-a716-446655440000` |
| VALIDATION_ERROR | Name/title required for create | Provide `name` (project/feature) or `title` (task) |
| VALIDATION_ERROR | Invalid status for container type | Check status values against container type workflows |
| VALIDATION_ERROR | Invalid priority | Use: `high`, `medium`, or `low` |
| VALIDATION_ERROR | Complexity out of range | Complexity must be 1-10 |
| VALIDATION_ERROR | BulkUpdate has 0 or >100 items | Provide 1-100 containers |
| VALIDATION_ERROR | Container in bulkUpdate missing id | Every container needs `id` field |
| VALIDATION_ERROR | BulkUpdate container has no updatable fields | Include at least one field: name, title, description, summary, status, priority, complexity, projectId, featureId, tags |
| RESOURCE_NOT_FOUND | Container ID doesn't exist | Verify ID with query_container search/get |
| RESOURCE_NOT_FOUND | Parent project/feature not found | Verify projectId/featureId exists |
| VALIDATION_ERROR | Cannot delete project/feature with children | Use `force=true` to cascade delete |
| DATABASE_ERROR | Unexpected database issue | Retry operation, contact support if persists |

### Error Response Format

```json
{
  "success": false,
  "message": "Human-readable error message",
  "error": {
    "code": "ERROR_CODE",
    "details": "Additional error details if available"
  }
}
```

### Common Error Scenarios

**Scenario 1: Invalid Status Transition**

```json
{
  "operation": "setStatus",
  "containerType": "feature",
  "id": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "status": "pending"
}
```

Response:

```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid status for feature: pending. Valid values: planning, in-development, in-review, completed, cancelled"
  }
}
```

**Scenario 2: Missing Required Field for Create**

```json
{
  "operation": "create",
  "containerType": "task",
  "featureId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c"
}
```

Response:

```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Title is required for create operation"
  }
}
```

**Scenario 3: Parent Not Found**

```json
{
  "operation": "create",
  "containerType": "task",
  "title": "New task",
  "featureId": "non-existent-uuid"
}
```

Response:

```json
{
  "success": false,
  "message": "Feature not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No feature exists with ID non-existent-uuid"
  }
}
```

---

## Integration Patterns

### Pattern 1: Create → Configure → Mark Ready

```
1. Create task with templateIds
2. Use manage_sections to customize sections
3. Use setStatus to mark "in-progress"
```

**Example**:

```json
// Step 1: Create with template
{
  "operation": "create",
  "containerType": "task",
  "title": "Implement payment integration",
  "featureId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "templateIds": ["550e8400-e29b-41d4-a716-446655440001"],
  "priority": "high"
}
// Response: task created with sections

// Step 2: Customize (via manage_sections)
// - Update requirements section
// - Add implementation notes

// Step 3: Mark ready
{
  "operation": "setStatus",
  "containerType": "task",
  "id": "new-task-id",
  "status": "in-progress"
}
```

### Pattern 2: Bulk Status Update with Query

```
1. Search for pending tasks
2. BulkUpdate to "in-progress"
3. Verify with scoped overview
```

**Example**:

```json
// Step 1: Find pending high-priority tasks
{
  "operation": "search",
  "containerType": "task",
  "status": "pending",
  "priority": "high",
  "limit": 20
}

// Step 2: BulkUpdate to in-progress
{
  "operation": "bulkUpdate",
  "containerType": "task",
  "containers": [
    {"id": "task-1", "status": "in-progress"},
    {"id": "task-2", "status": "in-progress"},
    ...
  ]
}

// Step 3: Verify with scoped overview
{
  "operation": "overview",
  "containerType": "feature",
  "id": "feature-id"
}
```

### Pattern 3: Feature Lifecycle with Status Progression

```
1. Create feature with planning status
2. Use get_next_status to check readiness
3. Transition through workflow (planning → in-development → completed)
4. At completion, verify all tasks are done
```

**Example**:

```json
// Step 1: Create feature
{
  "operation": "create",
  "containerType": "feature",
  "name": "Payment Integration",
  "projectId": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "priority": "high"
}

// Step 2: When ready, check progression
{
  "containerId": "new-feature-id",
  "containerType": "feature"
}

// Step 3: If Ready, transition
{
  "operation": "setStatus",
  "containerType": "feature",
  "id": "new-feature-id",
  "status": "in-development"
}

// Step 4: Later, verify completion
{
  "operation": "overview",
  "containerType": "feature",
  "id": "new-feature-id"
  // Check taskCounts.byStatus to ensure all tasks completed
}
```

---

## Use Cases

### Use Case 1: Developer Workflow - Task Lifecycle

**Scenario**: Developer starts work on a task, makes progress, and completes it.

**Steps**:

1. Search for pending high-priority tasks

```json
{
  "operation": "search",
  "containerType": "task",
  "status": "pending",
  "priority": "high",
  "featureId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c"
}
```

2. Create task with standard template

```json
{
  "operation": "create",
  "containerType": "task",
  "title": "Implement password reset endpoint",
  "featureId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "priority": "high",
  "complexity": 6,
  "templateIds": ["550e8400-e29b-41d4-a716-446655440001"],
  "tags": "backend,api,authentication"
}
```

3. Mark as in-progress when starting

```json
{
  "operation": "setStatus",
  "containerType": "task",
  "id": "task-id",
  "status": "in-progress"
}
```

4. Update summary with progress

```json
{
  "operation": "update",
  "containerType": "task",
  "id": "task-id",
  "summary": "Implemented password reset with email token verification and 24hr expiration"
}
```

5. Mark complete with final status

```json
{
  "operation": "setStatus",
  "containerType": "task",
  "id": "task-id",
  "status": "completed"
}
```

### Use Case 2: Project Manager - Bulk Status Update

**Scenario**: PM completes a sprint and marks all sprint tasks complete.

**Steps**:

1. Search for sprint tasks in progress

```json
{
  "operation": "search",
  "containerType": "task",
  "status": "in-progress",
  "featureId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "limit": 100
}
```

2. Bulk mark all as completed

```json
{
  "operation": "bulkUpdate",
  "containerType": "task",
  "containers": [
    {"id": "task-1", "status": "completed"},
    {"id": "task-2", "status": "completed"},
    {"id": "task-3", "status": "completed"},
    {"id": "task-4", "status": "completed"},
    {"id": "task-5", "status": "completed"}
  ]
}
```

3. Check feature completion status

```json
{
  "operation": "overview",
  "containerType": "feature",
  "id": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c"
  // taskCounts shows all completed
}
```

### Use Case 3: Architecture - New Feature Planning

**Scenario**: Architect creates new feature with standard structure, planning tasks.

**Steps**:

1. Create feature with architecture template

```json
{
  "operation": "create",
  "containerType": "feature",
  "name": "Real-time Notifications System",
  "projectId": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "priority": "high",
  "description": "WebSocket-based real-time notifications with persistence",
  "summary": "Real-time notification system using WebSocket and persistence",
  "templateIds": [
    "550e8400-e29b-41d4-a716-446655440001",
    "550e8400-e29b-41d4-a716-446655440003"
  ],
  "tags": "backend,realtime,notifications,websocket"
}
```

2. Create subtasks (or break them down from templates)

```json
{
  "operation": "create",
  "containerType": "task",
  "title": "Design WebSocket architecture",
  "featureId": "new-feature-id",
  "priority": "high",
  "complexity": 8,
  "templateIds": ["550e8400-e29b-41d4-a716-446655440002"]
}
```

3. Update feature summary with status

```json
{
  "operation": "update",
  "containerType": "feature",
  "id": "new-feature-id",
  "status": "in-development",
  "summary": "Real-time notifications with WebSocket architecture and database persistence. 8 tasks in development."
}
```

---

## Best Practices

### DO

✅ **Use bulkUpdate for batch operations** (3+ items) rather than individual update calls

✅ **Set priority on creation** - High/Medium/Low helps with scheduling

✅ **Apply templates on creation** - Leverage existing templates for standard sections

✅ **Use meaningful tags** - Enables powerful filtering and searching

✅ **Write concise summaries** - 1-2 sentences describing the work and current state

✅ **Validate parent exists** - Tool validates, but query first for safety

✅ **Use Status Progression Skill** - Let get_next_status guide status changes

✅ **Archive instead of delete** - Use status=cancelled to preserve history

✅ **Keep complexity reasonable** - 1-3 trivial, 4-6 moderate, 7-9 complex, 10 very complex

### DON'T

❌ **Don't delete indiscriminately** - Force delete cascades to all children

❌ **Don't ignore validation errors** - Check error messages carefully for guidance

❌ **Don't mix update and setStatus** - Use setStatus for status-only, update for other fields

❌ **Don't create without parent validation** - Verify projectId/featureId exist first

❌ **Don't exceed 100 in bulkUpdate** - Split into multiple requests if needed

❌ **Don't assume status transitions** - Use get_next_status to validate progression

❌ **Don't store complex data in summary** - Max 500 chars, use sections for details

❌ **Don't create duplicate containers** - Search first to check for existing work

❌ **Don't apply too many templates** - 2-3 templates typically sufficient

---

## Related Tools

- **query_container** - Read operations (get, search, export, overview)
- **manage_sections** - Modify section content and metadata
- **query_sections** - Retrieve sections with selective loading
- **get_next_status** - Intelligent status progression recommendations
- **query_dependencies** - Query task dependencies with filtering
- **manage_dependency** - Create and remove task dependencies

---

## Integration with Workflow Systems

### Workflow Prompts Integration

The manage_container tool works with workflow prompts for:

- **Create Feature Workflow** - Guided feature creation with templates
- **Task Breakdown Workflow** - Decompose features into tasks
- **Implementation Workflow** - Create and manage implementation tasks

### Skills Integration

**Status Progression Skill** uses manage_container for:

1. Checking readiness with get_next_status
2. Applying recommended status via setStatus
3. Validating completion prerequisites

**Task Management Skill** uses manage_container for:

1. Creating tasks from natural language
2. Updating task status and priority
3. Completing tasks with validation

---

## References

### Source Code

- **Tool Implementation**: `src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/ManageContainerTool.kt`
- **Domain Models**: `src/main/kotlin/io/github/jpicklyk/mcptask/domain/model/`
  - `Project.kt`
  - `Feature.kt`
  - `Task.kt`
- **Status Validator**: `src/main/kotlin/io/github/jpicklyk/mcptask/application/service/StatusValidator.kt`

### Related Documentation

- **[query_container Tool](query-container.md)** - Read operations for comparison
- **[manage_sections Tool](manage-sections.md)** - Section content management
- **[get_next_status Tool](get-next-status.md)** - Status progression recommendations
- **[API Reference](../api-reference.md)** - Complete tool documentation index
- **[Status Progression Guide](../status-progression.md)** - Workflow and status management examples
- **[Quick Start Guide](../quick-start.md)** - Getting started with Task Orchestrator
- **[Templates Guide](../templates.md)** - Using templates for standard structures

### Related Skills

- **Status Progression Skill** - Intelligent status management with validation
- **Task Management Skill** - Natural language task creation and completion

### Example Dataset

All examples use consistent IDs:

- **Project**: `b160fbdb-07e4-42d7-8c61-8deac7d2fc17` (MCP Task Orchestrator)
- **Feature**: `f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c` (Container Management API)
- **Task**: `a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d` (Implement manage_container tool)

---

## Version History

- **v2.0.0** (2025-10-24): Initial comprehensive documentation for unified manage_container tool
- **v2.0.0-beta** (2025-10-19): manage_container tool release as part of v2.0 consolidation

---

## See Also

- [API Reference](../api-reference.md) - Complete tool documentation
- [query_container Tool](query-container.md) - Read operations (complementary tool)
- [Quick Start Guide](../quick-start.md) - Common workflows
- [v2.0 Migration Guide](../migration/v2.0-migration-guide.md) - Upgrading from v1.x
