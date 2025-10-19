# manage_project

**Category:** Project Management
**Type:** Consolidated CRUD Tool
**Replaces:** create_project, get_project, update_project, delete_project, project_to_markdown

## Purpose

Unified tool for all single-entity project operations. Consolidates 5 separate tools into one operation-based interface, reducing token overhead by ~5.8k characters while preserving all functionality.

## Operations

The `manage_project` tool supports 5 operations via the `operation` parameter:

| Operation | Purpose | Required Params | Use Case |
|-----------|---------|----------------|----------|
| `create` | Create new project | `name` | Initial project creation |
| `get` | Retrieve project details | `id` | Fetch project information, features, tasks |
| `update` | Update project fields | `id` | Status changes, partial updates |
| `delete` | Remove project | `id` | Clean up completed or abandoned projects |
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
| `name` | string | **Yes** | - | Project name (brief, descriptive) |
| `summary` | string | No | "" | Brief summary (max 500 chars) |
| `description` | string | No | null | Detailed description (user-provided context) |
| `status` | enum | No | "planning" | `planning`, `in-development`, `completed`, `archived` |
| `tags` | string | No | null | Comma-separated tags |

#### Get Operation

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `id` | UUID | **Yes** | - | Project ID to retrieve |
| `includeSections` | boolean | No | false | Include detailed content sections |
| `includeFeatures` | boolean | No | false | Include child features |
| `includeTasks` | boolean | No | false | Include child tasks |
| `summaryView` | boolean | No | false | Truncate text fields for efficiency |

#### Update Operation

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `id` | UUID | **Yes** | - | Project ID to update |
| `name` | string | No | unchanged | New project name |
| `summary` | string | No | unchanged | New summary |
| `description` | string | No | unchanged | New description |
| `status` | enum | No | unchanged | New status |
| `tags` | string | No | unchanged | New comma-separated tags |

**Note:** Update supports **partial updates**. Only send fields you want to change. Unchanged fields retain their current values.

#### Delete Operation

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `id` | UUID | **Yes** | - | Project ID to delete |
| `deleteSections` | boolean | No | true | Delete associated sections |
| `force` | boolean | No | false | Delete with features/tasks |

#### Export Operation

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `id` | UUID | **Yes** | - | Project ID to export |
| `format` | enum | No | "markdown" | Export format: `markdown`, `json` |
| `includeSections` | boolean | No | false | Include sections in export |

## Response Format

### Create Response

```json
{
  "success": true,
  "message": "Project created successfully",
  "data": {
    "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "name": "MCP Task Orchestrator",
    "description": null,
    "summary": "Model Context Protocol server for task management",
    "status": "planning",
    "createdAt": "2025-10-18T12:00:00Z",
    "modifiedAt": "2025-10-18T12:00:00Z",
    "tags": ["mcp", "task-management", "kotlin"]
  }
}
```

### Get Response

```json
{
  "success": true,
  "message": "Project retrieved successfully",
  "data": {
    "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "name": "MCP Task Orchestrator",
    "summary": "Model Context Protocol server for task management",
    "description": "Comprehensive task orchestration system...",
    "status": "in-development",
    "createdAt": "2025-10-18T12:00:00Z",
    "modifiedAt": "2025-10-18T14:30:00Z",
    "tags": ["mcp", "task-management", "kotlin"],
    "features": [
      {
        "id": "feature-1-uuid",
        "name": "User Authentication",
        "status": "completed",
        "summary": "JWT-based authentication system"
      },
      {
        "id": "feature-2-uuid",
        "name": "Task Dependencies",
        "status": "in-development",
        "summary": "Dependency graph tracking"
      }
    ],
    "featureCount": 2,
    "tasks": [
      {
        "id": "task-1-uuid",
        "title": "Setup CI/CD pipeline",
        "status": "completed",
        "summary": "Configure GitHub Actions"
      }
    ],
    "taskCount": 1,
    "sections": [
      {
        "id": "section-uuid",
        "title": "Project Goals",
        "content": "Provide AI-driven task management...",
        "contentFormat": "markdown",
        "ordinal": 0
      }
    ]
  }
}
```

### Update Response

```json
{
  "success": true,
  "message": "Project updated successfully",
  "data": {
    "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "status": "completed",
    "modifiedAt": "2025-10-18T16:00:00Z"
  }
}
```

**Note:** Update returns minimal response for efficiency. Full project data available via get operation.

### Delete Response

```json
{
  "success": true,
  "message": "Project deleted successfully with 3 features, 12 tasks, and 5 sections",
  "data": {
    "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "deleted": true,
    "sectionsDeleted": 5,
    "featuresDeleted": 3,
    "tasksDeleted": 12
  }
}
```

### Export Response (Markdown)

```json
{
  "success": true,
  "message": "Project transformed to markdown successfully",
  "data": {
    "markdown": "---\nname: MCP Task Orchestrator\nstatus: completed\n...",
    "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039"
  }
}
```

### Export Response (JSON)

Export with `format: "json"` returns same structure as get operation.

## Examples

### Example 1: Create Project

**Scenario:** Create a new project for tracking development work.

**Request:**
```json
{
  "operation": "create",
  "name": "MCP Task Orchestrator",
  "summary": "Model Context Protocol server for task management",
  "description": "Comprehensive task orchestration system with AI-driven workflows, dependency tracking, and template support",
  "status": "planning",
  "tags": "mcp,task-management,kotlin"
}
```

**Response:**
```json
{
  "success": true,
  "message": "Project created successfully",
  "data": {
    "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "name": "MCP Task Orchestrator",
    "summary": "Model Context Protocol server for task management",
    "description": "Comprehensive task orchestration system with AI-driven workflows, dependency tracking, and template support",
    "status": "planning",
    "tags": ["mcp", "task-management", "kotlin"],
    "createdAt": "2025-10-18T12:00:00Z",
    "modifiedAt": "2025-10-18T12:00:00Z"
  }
}
```

---

### Example 2: Get Project with Features and Tasks

**Scenario:** Retrieve project with all child entities for comprehensive overview.

**Request:**
```json
{
  "operation": "get",
  "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
  "includeFeatures": true,
  "includeTasks": true,
  "includeSections": true
}
```

**Response:**
```json
{
  "success": true,
  "message": "Project retrieved successfully",
  "data": {
    "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "name": "MCP Task Orchestrator",
    "summary": "Model Context Protocol server for task management",
    "status": "in-development",
    "tags": ["mcp", "task-management", "kotlin"],
    "features": [
      {
        "id": "feature-1-uuid",
        "name": "User Authentication",
        "status": "completed",
        "summary": "JWT-based authentication system"
      },
      {
        "id": "feature-2-uuid",
        "name": "Task Dependencies",
        "status": "in-development",
        "summary": "Dependency graph tracking"
      },
      {
        "id": "feature-3-uuid",
        "name": "Template System",
        "status": "planning",
        "summary": "Reusable task templates"
      }
    ],
    "featureCount": 3,
    "tasks": [
      {
        "id": "task-1-uuid",
        "title": "Setup CI/CD pipeline",
        "status": "completed",
        "summary": "Configure GitHub Actions"
      },
      {
        "id": "task-2-uuid",
        "title": "Write project documentation",
        "status": "in-progress",
        "summary": "Create comprehensive README"
      }
    ],
    "taskCount": 2,
    "sections": [
      {
        "id": "section-uuid",
        "title": "Project Goals",
        "content": "Provide AI-driven task management through MCP protocol integration...",
        "contentFormat": "markdown",
        "ordinal": 0
      }
    ]
  }
}
```

---

### Example 3: Partial Update (Status Only)

**Scenario:** Mark project as completed without changing other fields.

**Request:**
```json
{
  "operation": "update",
  "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
  "status": "completed"
}
```

**Response:**
```json
{
  "success": true,
  "message": "Project updated successfully",
  "data": {
    "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "status": "completed",
    "modifiedAt": "2025-10-18T16:00:00Z"
  }
}
```

**Token Efficiency:** This partial update uses ~30 characters vs ~500+ if we sent all fields.

---

### Example 4: Delete Project with Features and Tasks

**Scenario:** Delete project with all child entities, requires force flag.

**Request (without force - fails):**
```json
{
  "operation": "delete",
  "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
  "deleteSections": true
}
```

**Error Response:**
```json
{
  "success": false,
  "message": "Cannot delete project with existing features or tasks",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Project has 3 features and 12 tasks. Use 'force=true' to delete anyway.",
    "data": {
      "featureCount": 3,
      "taskCount": 12
    }
  }
}
```

**Request (with force - succeeds):**
```json
{
  "operation": "delete",
  "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
  "deleteSections": true,
  "force": true
}
```

**Success Response:**
```json
{
  "success": true,
  "message": "Project deleted successfully with 3 features, 12 tasks, and 5 sections",
  "data": {
    "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "deleted": true,
    "sectionsDeleted": 5,
    "featuresDeleted": 3,
    "tasksDeleted": 12
  }
}
```

---

### Example 5: Export to Markdown for Documentation

**Scenario:** Export completed project to markdown for archival.

**Request:**
```json
{
  "operation": "export",
  "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
  "format": "markdown",
  "includeSections": true
}
```

**Response:**
```json
{
  "success": true,
  "message": "Project transformed to markdown successfully",
  "data": {
    "markdown": "---\nname: MCP Task Orchestrator\nstatus: completed\ntags:\n  - mcp\n  - task-management\n  - kotlin\ncreatedAt: 2025-10-18T12:00:00Z\nmodifiedAt: 2025-10-18T16:00:00Z\n---\n\n# MCP Task Orchestrator\n\n**Status:** completed\n\n## Summary\n\nModel Context Protocol server for task management\n\n## Description\n\nComprehensive task orchestration system with AI-driven workflows, dependency tracking, and template support\n\n## Project Goals\n\nProvide AI-driven task management through MCP protocol integration...\n",
    "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039"
  }
}
```

## Best Practices

### 1. Use Partial Updates

**❌ Inefficient (sends 500+ unnecessary characters):**
```json
{
  "operation": "update",
  "id": "project-uuid",
  "name": "Existing Name",
  "summary": "Long existing summary that hasn't changed...",
  "status": "completed",  // Only this changed
  "tags": "tag1,tag2,tag3"
}
```

**✅ Efficient (sends only what changed):**
```json
{
  "operation": "update",
  "id": "project-uuid",
  "status": "completed"
}
```

### 2. Progressive Loading

**Start with basic get:**
```json
{
  "operation": "get",
  "id": "project-uuid"
}
```

**Load details only when needed:**
```json
{
  "operation": "get",
  "id": "project-uuid",
  "includeFeatures": true,
  "includeTasks": true,
  "includeSections": true
}
```

### 3. Hierarchical Organization

**Create project first, then features:**
```bash
# Step 1: Create project
manage_project --operation create --name "My Project"

# Step 2: Create features within project
manage_feature --operation create --name "Feature 1" --projectId "project-uuid"

# Step 3: Create tasks within features
manage_task --operation create --title "Task 1" --featureId "feature-uuid"
```

### 4. Validation Before Deletion

**Check children before deleting:**
```bash
# Step 1: Get project with features and tasks
manage_project --operation get --id "project-uuid" --includeFeatures true --includeTasks true

# Step 2: Review impact (features, tasks to be deleted)

# Step 3: Delete with appropriate force flag
manage_project --operation delete --id "project-uuid" --force true
```

## Error Handling

### Validation Errors

```json
{
  "success": false,
  "message": "Invalid status: in_dev",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid status: in_dev. Must be one of: planning, in-development, completed, archived"
  }
}
```

### Not Found Errors

```json
{
  "success": false,
  "message": "Project not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No project exists with ID a4fae8cb-7640-4527-bd89-11effbb1d039"
  }
}
```

### Child Entity Conflicts

```json
{
  "success": false,
  "message": "Cannot delete project with existing features or tasks",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Project has 3 features and 12 tasks. Use 'force=true' to delete anyway.",
    "data": {
      "featureCount": 3,
      "taskCount": 12
    }
  }
}
```

## Related Tools

- **search_projects** - Find projects by filters
- **manage_feature** - Feature management (create features within projects)
- **manage_task** - Task management (create tasks within projects)
- **set_status** - Simple status-only updates (alternative to update operation)
- **get_sections** - Retrieve/manage project sections

## Migration from Deprecated Tools

See [Migration Guide](../migration/task-tool-consolidation.md) for complete migration instructions from:
- `create_project` → `manage_project` (operation: create)
- `get_project` → `manage_project` (operation: get)
- `update_project` → `manage_project` (operation: update)
- `delete_project` → `manage_project` (operation: delete)
- `project_to_markdown` → `manage_project` (operation: export)

## Performance Notes

- **Create:** Basic creation ~5-10ms
- **Get:** Feature loading adds ~10-20ms per feature, task loading ~5-10ms per task, section loading ~10-20ms per section
- **Update:** Partial updates ~2-5ms, full validation ~5-10ms
- **Delete:** Cascading deletion of features/tasks adds ~5-10ms per entity
- **Export:** Markdown rendering adds ~20-50ms

**Optimization:** Use progressive loading and partial updates to minimize overhead.
