# manage_task

**Category:** Task Management
**Type:** Consolidated CRUD Tool
**Replaces:** create_task, get_task, update_task, delete_task, task_to_markdown

## Purpose

Unified tool for all single-entity task operations. Consolidates 5 separate tools into one operation-based interface, reducing token overhead by ~5.8k characters while preserving all functionality.

## Operations

The `manage_task` tool supports 5 operations via the `operation` parameter:

| Operation | Purpose | Required Params | Use Case |
|-----------|---------|----------------|----------|
| `create` | Create new task | `title` | Initial task creation with templates |
| `get` | Retrieve task details | `id` | Fetch task information, sections, dependencies |
| `update` | Update task fields | `id` | Status changes, priority updates, partial updates |
| `delete` | Remove task | `id` | Clean up completed or abandoned tasks |
| `export` | Export to markdown/JSON | `id` | Documentation generation, archival |

## Parameters

### Common Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `operation` | enum | **Yes** | Operation type: `create`, `get`, `update`, `delete`, `export` |

### Operation-Specific Parameters

#### Create Operation

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `title` | string | **Yes** | - | Task title (brief, descriptive) |
| `summary` | string | No | "" | Brief summary (max 500 chars) |
| `description` | string | No | null | Detailed description (user-provided context) |
| `status` | enum | No | "pending" | `pending`, `in-progress`, `completed`, `cancelled`, `deferred` |
| `priority` | enum | No | "medium" | `high`, `medium`, `low` |
| `complexity` | integer | No | 5 | Complexity rating (1-10) |
| `featureId` | UUID | No | null | Parent feature ID |
| `projectId` | UUID | No | null | Parent project ID |
| `tags` | string | No | null | Comma-separated tags |
| `templateIds` | array | No | [] | Template UUIDs to apply |

#### Get Operation

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `id` | UUID | **Yes** | - | Task ID to retrieve |
| `includeSections` | boolean | No | false | Include detailed content sections |
| `includeFeature` | boolean | No | false | Include parent feature info |
| `includeDependencies` | boolean | No | false | Include dependency information |
| `summaryView` | boolean | No | false | Truncate text fields for efficiency |

#### Update Operation

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `id` | UUID | **Yes** | - | Task ID to update |
| `title` | string | No | unchanged | New task title |
| `summary` | string | No | unchanged | New summary |
| `description` | string | No | unchanged | New description |
| `status` | enum | No | unchanged | New status |
| `priority` | enum | No | unchanged | New priority |
| `complexity` | integer | No | unchanged | New complexity (1-10) |
| `featureId` | UUID | No | unchanged | New parent feature |
| `tags` | string | No | unchanged | New comma-separated tags |

**Note:** Update supports **partial updates**. Only send fields you want to change. Unchanged fields retain their current values.

#### Delete Operation

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `id` | UUID | **Yes** | - | Task ID to delete |
| `deleteSections` | boolean | No | true | Delete associated sections |
| `cascade` | boolean | No | false | Delete subtasks (experimental) |
| `force` | boolean | No | false | Delete despite dependencies |

#### Export Operation

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `id` | UUID | **Yes** | - | Task ID to export |
| `format` | enum | No | "markdown" | Export format: `markdown`, `json` |
| `includeSections` | boolean | No | false | Include sections in export |

## Response Format

### Create Response

```json
{
  "success": true,
  "message": "Task created successfully with 2 template(s) applied, creating 5 section(s)",
  "data": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "title": "Implement OAuth login",
    "description": null,
    "summary": "Add OAuth 2.0 authentication to the API",
    "status": "pending",
    "priority": "high",
    "complexity": 8,
    "createdAt": "2025-10-18T12:00:00Z",
    "modifiedAt": "2025-10-18T12:00:00Z",
    "projectId": null,
    "featureId": "550e8400-e29b-41d4-a716-446655440000",
    "tags": ["backend", "security"],
    "appliedTemplates": [
      {
        "templateId": "template-1-uuid",
        "sectionsCreated": 3
      },
      {
        "templateId": "template-2-uuid",
        "sectionsCreated": 2
      }
    ]
  }
}
```

### Get Response

```json
{
  "success": true,
  "message": "Task retrieved successfully",
  "data": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "title": "Implement OAuth login",
    "summary": "Add OAuth 2.0 authentication to the API",
    "status": "in-progress",
    "priority": "high",
    "complexity": 8,
    "createdAt": "2025-10-18T12:00:00Z",
    "modifiedAt": "2025-10-18T14:30:00Z",
    "featureId": "550e8400-e29b-41d4-a716-446655440000",
    "tags": ["backend", "security"],
    "sections": [
      {
        "id": "section-uuid",
        "title": "Implementation Plan",
        "content": "...",
        "contentFormat": "markdown",
        "ordinal": 0
      }
    ],
    "dependencies": {
      "incoming": [],
      "outgoing": [
        {
          "id": "dep-uuid",
          "fromTaskId": "640522b7-810e-49a2-865c-3725f5d39608",
          "toTaskId": "other-task-uuid",
          "type": "BLOCKS",
          "createdAt": "2025-10-18T12:00:00Z"
        }
      ],
      "counts": {
        "total": 1,
        "incoming": 0,
        "outgoing": 1
      }
    }
  }
}
```

### Update Response

```json
{
  "success": true,
  "message": "Task updated successfully",
  "data": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "status": "completed",
    "modifiedAt": "2025-10-18T16:00:00Z"
  }
}
```

**Note:** Update returns minimal response for efficiency. Full task data available via get operation.

### Delete Response

```json
{
  "success": true,
  "message": "Task deleted successfully with 2 dependencies and 5 sections",
  "data": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "deleted": true,
    "sectionsDeleted": 5,
    "dependenciesDeleted": 2,
    "warningsBrokenDependencies": true,
    "brokenDependencyChains": {
      "incomingDependencies": 1,
      "outgoingDependencies": 1,
      "affectedTasks": 2
    }
  }
}
```

### Export Response (Markdown)

```json
{
  "success": true,
  "message": "Task transformed to markdown successfully",
  "data": {
    "markdown": "---\ntitle: Implement OAuth login\nstatus: completed\n...",
    "taskId": "640522b7-810e-49a2-865c-3725f5d39608"
  }
}
```

### Export Response (JSON)

Export with `format: "json"` returns same structure as get operation.

## Examples

### Example 1: Create Task with Templates

**Scenario:** Create a backend implementation task with templates applied.

**Request:**
```json
{
  "operation": "create",
  "title": "Implement user registration endpoint",
  "summary": "Create POST /api/auth/register endpoint with email validation",
  "status": "pending",
  "priority": "high",
  "complexity": 6,
  "featureId": "550e8400-e29b-41d4-a716-446655440000",
  "tags": "backend,api,authentication",
  "templateIds": [
    "implementation-template-uuid",
    "git-workflow-template-uuid"
  ]
}
```

**Response:**
```json
{
  "success": true,
  "message": "Task created successfully with 2 template(s) applied, creating 6 section(s)",
  "data": {
    "id": "new-task-uuid",
    "title": "Implement user registration endpoint",
    "summary": "Create POST /api/auth/register endpoint with email validation",
    "status": "pending",
    "priority": "high",
    "complexity": 6,
    "featureId": "550e8400-e29b-41d4-a716-446655440000",
    "tags": ["backend", "api", "authentication"],
    "appliedTemplates": [
      {"templateId": "implementation-template-uuid", "sectionsCreated": 4},
      {"templateId": "git-workflow-template-uuid", "sectionsCreated": 2}
    ]
  }
}
```

---

### Example 2: Get Task with Full Context

**Scenario:** Retrieve task with sections and dependencies for completion validation.

**Request:**
```json
{
  "operation": "get",
  "id": "640522b7-810e-49a2-865c-3725f5d39608",
  "includeSections": true,
  "includeDependencies": true,
  "includeFeature": true
}
```

**Response:**
```json
{
  "success": true,
  "message": "Task retrieved successfully",
  "data": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "title": "Implement user registration endpoint",
    "status": "in-progress",
    "priority": "high",
    "complexity": 6,
    "featureId": "550e8400-e29b-41d4-a716-446655440000",
    "feature": {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "User Authentication System",
      "status": "in-development"
    },
    "sections": [
      {
        "id": "section-1-uuid",
        "title": "Requirements",
        "content": "- Email validation\n- Password hashing\n- Duplicate email check",
        "contentFormat": "markdown",
        "ordinal": 0
      },
      {
        "id": "section-2-uuid",
        "title": "Implementation Notes",
        "content": "Use bcrypt for password hashing...",
        "contentFormat": "markdown",
        "ordinal": 1
      }
    ],
    "dependencies": {
      "incoming": [
        {
          "id": "dep-uuid",
          "fromTaskId": "database-schema-task-uuid",
          "toTaskId": "640522b7-810e-49a2-865c-3725f5d39608",
          "type": "BLOCKS"
        }
      ],
      "outgoing": [],
      "counts": {"total": 1, "incoming": 1, "outgoing": 0}
    }
  }
}
```

---

### Example 3: Partial Update (Status Only)

**Scenario:** Mark task as completed without changing other fields.

**Request:**
```json
{
  "operation": "update",
  "id": "640522b7-810e-49a2-865c-3725f5d39608",
  "status": "completed"
}
```

**Response:**
```json
{
  "success": true,
  "message": "Task updated successfully",
  "data": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "status": "completed",
    "modifiedAt": "2025-10-18T16:00:00Z"
  }
}
```

**Token Efficiency:** This partial update uses ~30 characters vs ~500+ if we sent all fields.

---

### Example 4: Delete Task with Dependencies

**Scenario:** Delete task that has dependencies, requires force flag.

**Request (without force - fails):**
```json
{
  "operation": "delete",
  "id": "640522b7-810e-49a2-865c-3725f5d39608",
  "deleteSections": true
}
```

**Error Response:**
```json
{
  "success": false,
  "message": "Cannot delete task with existing dependencies",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Task has 3 dependencies. Use 'force=true' to delete anyway and break dependency chains.",
    "data": {
      "totalDependencies": 3,
      "incomingDependencies": 1,
      "outgoingDependencies": 2,
      "affectedTasks": 3
    }
  }
}
```

**Request (with force - succeeds):**
```json
{
  "operation": "delete",
  "id": "640522b7-810e-49a2-865c-3725f5d39608",
  "deleteSections": true,
  "force": true
}
```

**Success Response:**
```json
{
  "success": true,
  "message": "Task deleted successfully with 3 dependencies and 6 sections",
  "data": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "deleted": true,
    "sectionsDeleted": 6,
    "dependenciesDeleted": 3,
    "warningsBrokenDependencies": true,
    "brokenDependencyChains": {
      "incomingDependencies": 1,
      "outgoingDependencies": 2,
      "affectedTasks": 3
    }
  }
}
```

---

### Example 5: Export to Markdown for Documentation

**Scenario:** Export completed task to markdown for project documentation.

**Request:**
```json
{
  "operation": "export",
  "id": "640522b7-810e-49a2-865c-3725f5d39608",
  "format": "markdown",
  "includeSections": true
}
```

**Response:**
```json
{
  "success": true,
  "message": "Task transformed to markdown successfully",
  "data": {
    "markdown": "---\ntitle: Implement user registration endpoint\nstatus: completed\npriority: high\ncomplexity: 6\ntags:\n  - backend\n  - api\n  - authentication\ncreatedAt: 2025-10-18T12:00:00Z\nmodifiedAt: 2025-10-18T16:00:00Z\n---\n\n# Implement user registration endpoint\n\n**Status:** completed  \n**Priority:** high  \n**Complexity:** 6/10\n\n## Summary\n\nCreate POST /api/auth/register endpoint with email validation\n\n## Requirements\n\n- Email validation\n- Password hashing\n- Duplicate email check\n\n## Implementation Notes\n\nUse bcrypt for password hashing...\n",
    "taskId": "640522b7-810e-49a2-865c-3725f5d39608"
  }
}
```

## Best Practices

### 1. Use Partial Updates

**❌ Inefficient (sends 500+ unnecessary characters):**
```json
{
  "operation": "update",
  "id": "task-uuid",
  "title": "Existing Title",
  "summary": "Long existing summary that hasn't changed...",
  "status": "completed",  // Only this changed
  "priority": "medium",
  "complexity": 5,
  "tags": "tag1,tag2,tag3"
}
```

**✅ Efficient (sends only what changed):**
```json
{
  "operation": "update",
  "id": "task-uuid",
  "status": "completed"
}
```

### 2. Progressive Loading

**Start with basic get:**
```json
{
  "operation": "get",
  "id": "task-uuid"
}
```

**Load details only when needed:**
```json
{
  "operation": "get",
  "id": "task-uuid",
  "includeSections": true,
  "includeDependencies": true
}
```

### 3. Template-Driven Creation

**Always discover templates first:**
```bash
# Step 1: List available templates
list_templates --targetEntityType TASK

# Step 2: Create task with appropriate templates
manage_task --operation create --title "..." --templateIds ["uuid1", "uuid2"]
```

### 4. Validation Before Deletion

**Check dependencies before deleting:**
```bash
# Step 1: Get task with dependencies
manage_task --operation get --id "task-uuid" --includeDependencies true

# Step 2: Review dependency impact

# Step 3: Delete with appropriate force flag
manage_task --operation delete --id "task-uuid" --force true
```

## Error Handling

### Validation Errors

```json
{
  "success": false,
  "message": "Invalid status: in_progres",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid status: in_progres. Must be one of: pending, in-progress, completed, cancelled, deferred"
  }
}
```

### Not Found Errors

```json
{
  "success": false,
  "message": "Task not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No task exists with ID 640522b7-810e-49a2-865c-3725f5d39608"
  }
}
```

### Dependency Conflicts

```json
{
  "success": false,
  "message": "Cannot delete task with existing dependencies",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Task has 3 dependencies. Use 'force=true' to delete anyway.",
    "data": {
      "totalDependencies": 3,
      "incomingDependencies": 1,
      "outgoingDependencies": 2
    }
  }
}
```

## Related Tools

- **query_tasks** - Multi-entity queries (search, blocked, next, bulkUpdate)
- **create_dependency** - Link tasks with dependencies
- **set_status** - Simple status-only updates (alternative to update operation)
- **get_sections** - Retrieve/manage task sections
- **list_templates** - Discover available templates

## Migration from Deprecated Tools

See [Migration Guide](../migration/task-tool-consolidation.md) for complete migration instructions from:
- `create_task` → `manage_task` (operation: create)
- `get_task` → `manage_task` (operation: get)
- `update_task` → `manage_task` (operation: update)
- `delete_task` → `manage_task` (operation: delete)
- `task_to_markdown` → `manage_task` (operation: export)

## Performance Notes

- **Create:** Template application adds ~50-100ms per template
- **Get:** Section loading adds ~10-20ms per section
- **Update:** Partial updates ~2-5ms, full validation ~5-10ms
- **Delete:** Section/dependency cleanup adds ~5-10ms per item
- **Export:** Markdown rendering adds ~20-50ms

**Optimization:** Use progressive loading and partial updates to minimize overhead.
