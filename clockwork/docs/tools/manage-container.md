# manage_container Tool - Detailed Documentation

## Overview

The `manage_container` tool provides unified write operations for all container types (projects, features, tasks). It consolidates multiple former individual tools into a single, efficient interface with three operations: `create`, `update`, `delete`. All operations use array parameters (`containers` for create/update, `ids` for delete).

**Key Feature (v2.0+):** The `manage_container` tool handles all write operations across three container types (project, feature, task) with consistent validation, template application, and batch support through a single interface. For status changes, use `request_transition` with named triggers (`start`, `complete`, `cancel`, `block`, `hold`).

**Resource**: `task-orchestrator://docs/tools/manage-container`

## Key Concepts

### Unified Container Interface

All three container types share a single, consistent write interface:

- **Projects** - Top-level organization containers
- **Features** - Groups of related tasks within projects
- **Tasks** - Individual work items within features

### Three Core Operations

1. **create** - Create new containers with optional template application (uses `containers` array, top-level `projectId`/`featureId`/`templateIds`/`tags` as shared defaults)
2. **update** - Modify existing container properties (uses `containers` array, each item has `id`, max 100 items)
3. **delete** - Remove containers with dependency handling (uses `ids` array)

**Note:** For status changes, use `request_transition(trigger=start|complete|cancel|block|hold)` instead of `manage_container`. Batch status transitions use the `transitions` array parameter on `request_transition`.

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
| `operation`     | enum | **Yes**  | Operation: `create`, `update`, `delete`                  |
| `containerType` | enum | **Yes**  | Container type: `project`, `feature`, `task`     |

### Operation-Specific Parameters

| Parameter      | Type    | Operations             | Description                                       |
|----------------|---------|------------------------|---------------------------------------------------|
| `containers`   | array   | create, update         | Array of container objects (max 100 items). Each create item: `name`/`title` (required), `description`, `summary`, `status`, `priority`, `complexity`, `projectId`, `featureId`, `templateIds`, `tags`. Each update item: `id` (required), plus any fields to change. |
| `ids`          | array   | delete                 | Array of container UUIDs to delete (max 100)      |
| `projectId`    | UUID    | create                 | Default parent project ID inherited by all items  |
| `featureId`    | UUID    | create                 | Default parent feature ID inherited by all items (task only) |
| `templateIds`  | array   | create                 | Default template IDs inherited by all items       |
| `tags`         | string  | create                 | Default comma-separated tags inherited by all items |
| `deleteSections` | boolean | delete                | Delete sections (default: true)                   |
| `force`        | boolean | delete                | Force delete with dependencies (default: false)   |

### Status Values by Container Type

| Container Type | Valid Status Values                                  |
|----------------|------------------------------------------------------|
| **project**    | planning, in-development, completed, archived, on-hold, deployed, cancelled |
| **feature**    | planning, in-development, completed, archived, draft, on-hold, testing, validating, pending-review, blocked, deployed |
| **task**       | pending, in-progress, completed, cancelled, deferred, backlog, in-review, changes-requested, on-hold, testing, ready-for-qa, investigating, blocked, deployed |

---

## Quick Start

### Basic Create Pattern

```json
{
  "operation": "create",
  "containerType": "task",
  "featureId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "containers": [
    {
      "title": "Implement OAuth2 authentication",
      "priority": "high",
      "complexity": 7,
      "tags": "backend,security,authentication"
    }
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "1 task(s) created successfully",
  "data": {
    "items": [
      {
        "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "title": "Implement OAuth2 authentication",
        "status": "pending",
        "appliedTemplates": []
      }
    ],
    "created": 1,
    "failed": 0
  }
}
```

### Basic Update Pattern

```json
{
  "operation": "update",
  "containerType": "task",
  "containers": [
    {
      "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
      "summary": "Implementing Google OAuth 2.0 with JWT token management"
    }
  ]
}
```

### Basic Delete Pattern

```json
{
  "operation": "delete",
  "containerType": "task",
  "ids": ["a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"]
}
```

### Common Patterns

**Create with Templates** (feature/task only):

```json
{
  "operation": "create",
  "containerType": "feature",
  "projectId": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "templateIds": [
    "550e8400-e29b-41d4-a716-446655440001",
    "550e8400-e29b-41d4-a716-446655440002"
  ],
  "containers": [
    {
      "name": "User Authentication System",
      "priority": "high"
    }
  ]
}
```

**Batch Update Multiple Tasks**:

```json
{
  "operation": "update",
  "containerType": "task",
  "containers": [
    {
      "id": "task-uuid-1",
      "summary": "Task 1 complete"
    },
    {
      "id": "task-uuid-2",
      "summary": "Task 2 complete"
    }
  ]
}
```

---

## Operation 1: create

**Purpose**: Create new containers (projects, features, or tasks) with optional template application. Uses `containers` array even for single items.

### Required Parameters

- `operation`: "create"
- `containerType`: "project", "feature", or "task"
- `containers`: Array of container objects (1-100 items). Each item requires `name` (project/feature) or `title` (task).

### Optional Top-Level Parameters (shared defaults)

- `projectId`: Default parent project ID inherited by all items (feature/task)
- `featureId`: Default parent feature ID inherited by all items (task only)
- `templateIds`: Default template IDs inherited by all items (feature/task only)
- `tags`: Default comma-separated tags inherited by all items

### Optional Per-Item Parameters (override shared defaults)

- `description`: Detailed description
- `summary`: Brief summary (max 500 chars)
- `status`: Initial status (defaults per type)
- `priority`: high, medium, low (feature/task only)
- `complexity`: 1-10 (task only, default: 5)
- `projectId`: Parent project ID (overrides top-level)
- `featureId`: Parent feature ID (overrides top-level)
- `templateIds`: Template IDs to apply (overrides top-level)
- `tags`: Comma-separated tags (overrides top-level)

### Default Status Values

- **project**: "planning"
- **feature**: "planning"
- **task**: "pending"

### Example - Create Project

```json
{
  "operation": "create",
  "containerType": "project",
  "containers": [
    {
      "name": "E-Commerce Platform",
      "description": "Complete e-commerce platform with shopping cart, payment processing, and admin dashboard",
      "summary": "Full-featured e-commerce platform with payment integration",
      "tags": "e-commerce,platform,v2.0"
    }
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "1 project(s) created successfully",
  "data": {
    "items": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "name": "E-Commerce Platform",
        "status": "planning",
        "appliedTemplates": []
      }
    ],
    "created": 1,
    "failed": 0
  }
}
```

**Token Cost**: ~200-300 tokens

### Example - Create Feature with Templates

```json
{
  "operation": "create",
  "containerType": "feature",
  "projectId": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "templateIds": [
    "550e8400-e29b-41d4-a716-446655440001"
  ],
  "containers": [
    {
      "name": "User Authentication System",
      "description": "Comprehensive authentication with OAuth, JWT, and session management",
      "summary": "OAuth2 and JWT-based authentication with multiple provider support",
      "priority": "high",
      "status": "planning",
      "tags": "backend,security,authentication"
    }
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "1 feature(s) created successfully",
  "data": {
    "items": [
      {
        "id": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
        "name": "User Authentication System",
        "status": "planning",
        "appliedTemplates": [
          {
            "templateId": "550e8400-e29b-41d4-a716-446655440001",
            "sectionsCreated": 5
          }
        ]
      }
    ],
    "created": 1,
    "failed": 0
  }
}
```

**Token Cost**: ~250-350 tokens (plus template section creation)

### Example - Create Task with All Parameters

```json
{
  "operation": "create",
  "containerType": "task",
  "projectId": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "featureId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "templateIds": [
    "550e8400-e29b-41d4-a716-446655440002"
  ],
  "containers": [
    {
      "title": "Implement Google OAuth provider",
      "description": "Add Google OAuth 2.0 authentication provider with token management and user profile sync",
      "summary": "Add Google OAuth 2.0 authentication with JWT token integration",
      "priority": "high",
      "complexity": 7,
      "status": "pending",
      "tags": "backend,oauth,google,authentication"
    }
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "1 task(s) created successfully",
  "data": {
    "items": [
      {
        "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "title": "Implement Google OAuth provider",
        "status": "pending",
        "appliedTemplates": [
          {
            "templateId": "550e8400-e29b-41d4-a716-446655440002",
            "sectionsCreated": 3
          }
        ]
      }
    ],
    "created": 1,
    "failed": 0
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

**Purpose**: Modify existing container properties. Uses `containers` array (max 100 items) for both single and batch updates.

### Required Parameters

- `operation`: "update"
- `containerType`: "project", "feature", or "task"
- `containers`: Array of container objects (1-100 items). Each item requires `id` and at least one field to update.

### Per-Item Parameters

- `id` (required): Container UUID to update
- `name` (project/feature): Update name
- `title` (task): Update task title (alias for `name`)
- `description`: Update description
- `summary`: Update summary
- `priority`: Update priority (feature/task only)
- `complexity`: Update complexity (task only)
- `projectId`: Update parent project (feature/task)
- `featureId`: Update parent feature (task only)
- `tags`: Update tags

**Note**: Only specified fields are updated; others remain unchanged. For status changes, use `request_transition` instead.

### Example - Update Task Summary

```json
{
  "operation": "update",
  "containerType": "task",
  "containers": [
    {
      "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
      "summary": "Implementing Google OAuth 2.0 with JWT token management. Started frontend integration."
    }
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "1 task(s) updated successfully",
  "data": {
    "items": [
      {
        "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "modifiedAt": "2025-10-24T14:00:00Z"
      }
    ],
    "updated": 1,
    "failed": 0,
    "cascadeEvents": [],
    "unblockedTasks": []
  }
}
```

**Token Cost**: ~150-200 tokens

### Example - Update Feature Priority and Project

```json
{
  "operation": "update",
  "containerType": "feature",
  "containers": [
    {
      "id": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
      "priority": "high",
      "projectId": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17"
    }
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "1 feature(s) updated successfully",
  "data": {
    "items": [
      {
        "id": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
        "modifiedAt": "2025-10-24T14:15:00Z"
      }
    ],
    "updated": 1,
    "failed": 0,
    "cascadeEvents": [],
    "unblockedTasks": []
  }
}
```

**Token Cost**: ~150-200 tokens

### Example - Update Project Name

```json
{
  "operation": "update",
  "containerType": "project",
  "containers": [
    {
      "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
      "name": "MCP Task Orchestrator v2.0"
    }
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "1 project(s) updated successfully",
  "data": {
    "items": [
      {
        "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
        "modifiedAt": "2025-10-24T14:30:00Z"
      }
    ],
    "updated": 1,
    "failed": 0,
    "cascadeEvents": [],
    "unblockedTasks": []
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

- Updating task complexity, priority, or summary
- Reassigning to different project/feature
- Adding/modifying description or summary
- Batch updating multiple containers at once

**Note:** For status changes, use `request_transition(trigger=start|complete|cancel|block|hold)` instead.

---

## Operation 3: delete

**Purpose**: Remove containers with optional dependency handling. Uses `ids` array.

### Required Parameters

- `operation`: "delete"
- `containerType`: "project", "feature", or "task"
- `ids`: Array of container UUIDs to delete (max 100)

### Optional Parameters

- `deleteSections`: Delete associated sections (default: true)
- `force`: Force delete with dependencies (default: false)

### Deletion Behavior

**Without force flag**:
- Cannot delete project with features or tasks
- Cannot delete feature with tasks
- Cannot delete task with dependencies (use force=true)

**With force=true**:
- Deletes project AND all features AND all tasks
- Deletes feature AND all tasks
- Deletes task and breaks dependency chains

### Example - Delete Task (Safe)

```json
{
  "operation": "delete",
  "containerType": "task",
  "ids": ["a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"]
}
```

**Response**:

```json
{
  "success": true,
  "message": "1 task(s) deleted successfully",
  "data": {
    "ids": ["a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"],
    "deleted": 1,
    "failed": 0
  }
}
```

**Token Cost**: ~150 tokens

### Example - Delete Feature (Requires Force)

```json
{
  "operation": "delete",
  "containerType": "feature",
  "ids": ["f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c"],
  "force": true,
  "deleteSections": true
}
```

**Response**:

```json
{
  "success": true,
  "message": "1 feature(s) deleted successfully",
  "data": {
    "ids": ["f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c"],
    "deleted": 1,
    "failed": 0
  }
}
```

**Token Cost**: ~150-200 tokens

### Example - Delete Attempt Without Force (Error)

```json
{
  "operation": "delete",
  "containerType": "feature",
  "ids": ["f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c"]
}
```

**Response**:

```json
{
  "success": true,
  "message": "0 feature(s) deleted, 1 failed",
  "data": {
    "ids": [],
    "deleted": 0,
    "failed": 1,
    "failures": [
      {
        "id": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
        "error": {
          "code": "VALIDATION_ERROR",
          "details": "Feature has 20 tasks. Use 'force=true' to delete anyway."
        }
      }
    ]
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

## Status Changes: Use request_transition

**Important:** Status changes are no longer handled by `manage_container`. Use `request_transition` with named triggers instead.

### Example - Task Status Progression

```json
{
  "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "containerType": "task",
  "trigger": "start"
}
```

### Example - Batch Status Changes

```json
{
  "transitions": [
    {
      "containerId": "task-uuid-1",
      "containerType": "task",
      "trigger": "complete"
    },
    {
      "containerId": "task-uuid-2",
      "containerType": "task",
      "trigger": "complete"
    }
  ]
}
```

### Available Triggers

- `start` - Progress to next status in workflow
- `complete` - Move to completed (validates prerequisites)
- `cancel` - Move to cancelled (emergency transition)
- `block` - Move to blocked (emergency transition)
- `hold` - Move to on-hold (emergency transition)

### Integration with get_next_status

For intelligent status recommendations before transitioning, use `get_next_status`:

```json
{
  "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "containerType": "task"
}
```

This returns whether a container is Ready, Blocked, or Terminal for progression, along with flow context.

### Batch Update (update operation)

The `update` operation supports up to 100 containers per call, replacing the former `bulkUpdate` operation:

```json
{
  "operation": "update",
  "containerType": "task",
  "containers": [
    {
      "id": "task-uuid-1",
      "priority": "high"
    },
    {
      "id": "task-uuid-2",
      "priority": "high"
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
    "items": [
      { "id": "task-uuid-1", "modifiedAt": "2025-10-24T16:00:00Z" },
      { "id": "task-uuid-2", "modifiedAt": "2025-10-24T16:00:01Z" },
      { "id": "task-uuid-3", "modifiedAt": "2025-10-24T16:00:02Z" }
    ],
    "updated": 3,
    "failed": 0,
    "cascadeEvents": [],
    "unblockedTasks": []
  }
}
```

### Update Validation Rules

- Maximum 100 containers per request
- Each container requires `id`
- Each container requires at least one updatable field
- Field validation is per-container-type (complexity ignored for projects)
- Priority must be high/medium/low (if specified)
- Complexity must be 1-10 (if specified)
- UUIDs must be valid format

### Example - Update with Errors

```json
{
  "operation": "update",
  "containerType": "task",
  "containers": [
    {
      "id": "task-uuid-1",
      "summary": "Updated summary"
    },
    {
      "id": "invalid-uuid",
      "priority": "high"
    }
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "1 task(s) updated, 1 failed",
  "data": {
    "items": [
      { "id": "task-uuid-1", "modifiedAt": "2025-10-24T16:30:00Z" }
    ],
    "updated": 1,
    "failed": 1,
    "failures": [
      {
        "index": 1,
        "id": "invalid-uuid",
        "error": { "code": "INTERNAL_ERROR", "details": "Invalid UUID format" }
      }
    ],
    "cascadeEvents": [],
    "unblockedTasks": []
  }
}
```

---

## Advanced Usage

### Token Efficiency Notes

| Operation              | Typical Tokens | Best For              |
|------------------------|----------------|-----------------------|
| create                 | 200-350        | New items (single or batch) |
| update                 | 150-400        | Field modifications (single or batch) |
| delete                 | 150-200        | Removal (single or batch) |
| request_transition     | 150            | Status changes (use instead of manage_container) |

**Recommendation**: Use `containers` array with multiple items for 3+ similar updates (more efficient than individual calls). For status changes, always use `request_transition`.

### With Status Progression Skill

The Status Progression Skill integrates with `request_transition`:

```
1. Call get_next_status to validate readiness
   ↓
2. If Ready → use request_transition to apply recommended status
   ↓
3. If Blocked → show blocking requirements
   ↓
4. If Terminal → no further progression possible
```

**Example workflow**:

```json
// Step 1: Check if task can progress (get_next_status)
{
  "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "containerType": "task"
}

// Step 2: If Ready, apply transition (request_transition)
{
  "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "containerType": "task",
  "trigger": "start"
}
```

### Template Application During Create

Templates provide automatic section structure:

```json
{
  "operation": "create",
  "containerType": "task",
  "featureId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "templateIds": [
    "550e8400-e29b-41d4-a716-446655440001",
    "550e8400-e29b-41d4-a716-446655440002"
  ],
  "containers": [
    {
      "title": "Implement payment processing"
    }
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
  "ids": ["a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"],
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
| VALIDATION_ERROR | Update has 0 or >100 items | Provide 1-100 containers |
| VALIDATION_ERROR | Container in update missing id | Every update container needs `id` field |
| VALIDATION_ERROR | Update container has no updatable fields | Include at least one field: name, title, description, summary, priority, complexity, projectId, featureId, tags |
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

**Scenario 1: Missing Required Field for Create**

```json
{
  "operation": "create",
  "containerType": "task",
  "featureId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "containers": [{}]
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

**Scenario 2: Parent Not Found**

```json
{
  "operation": "create",
  "containerType": "task",
  "featureId": "non-existent-uuid",
  "containers": [
    {
      "title": "New task"
    }
  ]
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
3. Use request_transition to start work
```

**Example**:

```json
// Step 1: Create with template (manage_container)
{
  "operation": "create",
  "containerType": "task",
  "featureId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "templateIds": ["550e8400-e29b-41d4-a716-446655440001"],
  "containers": [
    {
      "title": "Implement payment integration",
      "priority": "high"
    }
  ]
}
// Response: task created with sections

// Step 2: Customize (via manage_sections)
// - Update requirements section
// - Add implementation notes

// Step 3: Start work (request_transition)
{
  "containerId": "new-task-id",
  "containerType": "task",
  "trigger": "start"
}
```

### Pattern 2: Batch Status Transition with Query

```
1. Search for pending tasks
2. Use request_transition batch to start them
3. Verify with scoped overview
```

**Example**:

```json
// Step 1: Find pending high-priority tasks (query_container)
{
  "operation": "search",
  "containerType": "task",
  "status": "pending",
  "priority": "high",
  "limit": 20
}

// Step 2: Batch start tasks (request_transition)
{
  "transitions": [
    {"containerId": "task-1", "containerType": "task", "trigger": "start"},
    {"containerId": "task-2", "containerType": "task", "trigger": "start"}
  ]
}

// Step 3: Verify with scoped overview (query_container)
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
// Step 1: Create feature (manage_container)
{
  "operation": "create",
  "containerType": "feature",
  "projectId": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "containers": [
    {
      "name": "Payment Integration",
      "priority": "high"
    }
  ]
}

// Step 2: When ready, check progression (get_next_status)
{
  "containerId": "new-feature-id",
  "containerType": "feature"
}

// Step 3: If Ready, transition (request_transition)
{
  "containerId": "new-feature-id",
  "containerType": "feature",
  "trigger": "start"
}

// Step 4: Later, verify completion (query_container)
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

2. Create task with standard template (manage_container)

```json
{
  "operation": "create",
  "containerType": "task",
  "featureId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "templateIds": ["550e8400-e29b-41d4-a716-446655440001"],
  "containers": [
    {
      "title": "Implement password reset endpoint",
      "priority": "high",
      "complexity": 6,
      "tags": "backend,api,authentication"
    }
  ]
}
```

3. Start work (request_transition)

```json
{
  "containerId": "task-id",
  "containerType": "task",
  "trigger": "start"
}
```

4. Update summary with progress (manage_container)

```json
{
  "operation": "update",
  "containerType": "task",
  "containers": [
    {
      "id": "task-id",
      "summary": "Implemented password reset with email token verification and 24hr expiration"
    }
  ]
}
```

5. Mark complete (request_transition)

```json
{
  "containerId": "task-id",
  "containerType": "task",
  "trigger": "complete"
}
```

### Use Case 2: Project Manager - Batch Status Transition

**Scenario**: PM completes a sprint and marks all sprint tasks complete.

**Steps**:

1. Search for sprint tasks in progress (query_container)

```json
{
  "operation": "search",
  "containerType": "task",
  "status": "in-progress",
  "featureId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "limit": 100
}
```

2. Batch complete all tasks (request_transition)

```json
{
  "transitions": [
    {"containerId": "task-1", "containerType": "task", "trigger": "complete"},
    {"containerId": "task-2", "containerType": "task", "trigger": "complete"},
    {"containerId": "task-3", "containerType": "task", "trigger": "complete"},
    {"containerId": "task-4", "containerType": "task", "trigger": "complete"},
    {"containerId": "task-5", "containerType": "task", "trigger": "complete"}
  ]
}
```

3. Check feature completion status (query_container)

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

1. Create feature with architecture template (manage_container)

```json
{
  "operation": "create",
  "containerType": "feature",
  "projectId": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "templateIds": [
    "550e8400-e29b-41d4-a716-446655440001",
    "550e8400-e29b-41d4-a716-446655440003"
  ],
  "containers": [
    {
      "name": "Real-time Notifications System",
      "priority": "high",
      "description": "WebSocket-based real-time notifications with persistence",
      "summary": "Real-time notification system using WebSocket and persistence",
      "tags": "backend,realtime,notifications,websocket"
    }
  ]
}
```

2. Create subtasks (manage_container)

```json
{
  "operation": "create",
  "containerType": "task",
  "featureId": "new-feature-id",
  "templateIds": ["550e8400-e29b-41d4-a716-446655440002"],
  "containers": [
    {
      "title": "Design WebSocket architecture",
      "priority": "high",
      "complexity": 8
    }
  ]
}
```

3. Start feature development (request_transition)

```json
{
  "containerId": "new-feature-id",
  "containerType": "feature",
  "trigger": "start"
}
```

4. Update feature summary with progress (manage_container)

```json
{
  "operation": "update",
  "containerType": "feature",
  "containers": [
    {
      "id": "new-feature-id",
      "summary": "Real-time notifications with WebSocket architecture and database persistence. 8 tasks in development."
    }
  ]
}
```

---

## Best Practices

### DO

✅ **Use `containers` array for batch operations** (3+ items) rather than individual update calls

✅ **Set priority on creation** - High/Medium/Low helps with scheduling

✅ **Apply templates on creation** - Leverage existing templates for standard sections

✅ **Use meaningful tags** - Enables powerful filtering and searching

✅ **Write concise summaries** - 1-2 sentences describing the work and current state

✅ **Validate parent exists** - Tool validates, but query first for safety

✅ **Use `request_transition` for status changes** - Use named triggers, not manage_container

✅ **Archive instead of delete** - Use status=cancelled to preserve history

✅ **Keep complexity reasonable** - 1-3 trivial, 4-6 moderate, 7-9 complex, 10 very complex

### DON'T

❌ **Don't delete indiscriminately** - Force delete cascades to all children

❌ **Don't ignore validation errors** - Check error messages carefully for guidance

❌ **Don't use manage_container for status changes** - Use `request_transition` with named triggers instead

❌ **Don't create without parent validation** - Verify projectId/featureId exist first

❌ **Don't exceed 100 in `containers`/`ids` array** - Split into multiple requests if needed

❌ **Don't assume status transitions** - Use `get_next_status` to preview, then `request_transition` to apply

❌ **Don't store complex data in summary** - Max 500 chars, use sections for details

❌ **Don't create duplicate containers** - Search first to check for existing work

❌ **Don't apply too many templates** - 2-3 templates typically sufficient

---

## Related Tools

- **query_container** - Read operations (get, search, export, overview)
- **request_transition** - Status changes with named triggers (start, complete, cancel, block, hold). Supports batch transitions via `transitions` array.
- **get_next_status** - Intelligent status progression recommendations (read-only preview)
- **manage_sections** - Modify section content and metadata
- **query_sections** - Retrieve sections with selective loading
- **query_dependencies** - Query task dependencies with filtering
- **manage_dependencies** - Create and remove task dependencies with batch support

---

## Integration with Workflow Systems

### Workflow Prompts Integration

The manage_container tool works with workflow prompts for:

- **Create Feature Workflow** - Guided feature creation with templates
- **Task Breakdown Workflow** - Decompose features into tasks
- **Implementation Workflow** - Create and manage implementation tasks

### Skills Integration

**Status Progression Skill** uses `request_transition` for:

1. Checking readiness with `get_next_status`
2. Applying recommended status via `request_transition(trigger=start|complete|...)`
3. Validating completion prerequisites

**Task Management Skill** uses manage_container + request_transition for:

1. Creating tasks from natural language (manage_container create)
2. Updating task properties (manage_container update)
3. Completing tasks with validation (request_transition complete)

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
