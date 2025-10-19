# manage_feature

**Category:** Feature Management
**Type:** Consolidated CRUD Tool
**Replaces:** create_feature, get_feature, update_feature, delete_feature, feature_to_markdown

## Purpose

Unified tool for all single-entity feature operations. Consolidates 5 separate tools into one operation-based interface, reducing token overhead by ~5.8k characters while preserving all functionality.

## Operations

The `manage_feature` tool supports 5 operations via the `operation` parameter:

| Operation | Purpose | Required Params | Use Case |
|-----------|---------|----------------|----------|
| `create` | Create new feature | `name` | Initial feature creation with templates |
| `get` | Retrieve feature details | `id` | Fetch feature information, sections, project |
| `update` | Update feature fields | `id` | Status changes, priority updates, partial updates |
| `delete` | Remove feature | `id` | Clean up completed or abandoned features |
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
| `name` | string | **Yes** | - | Feature name (brief, descriptive) |
| `summary` | string | No | "" | Brief summary (max 500 chars) |
| `description` | string | No | null | Detailed description (user-provided context) |
| `status` | enum | No | "planning" | `planning`, `in-development`, `completed`, `archived` |
| `priority` | enum | No | "medium" | `high`, `medium`, `low` |
| `projectId` | UUID | No | null | Parent project ID |
| `tags` | string | No | null | Comma-separated tags |
| `templateIds` | array | No | [] | Template UUIDs to apply |

#### Get Operation

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `id` | UUID | **Yes** | - | Feature ID to retrieve |
| `includeSections` | boolean | No | false | Include detailed content sections |
| `includeProject` | boolean | No | false | Include parent project info |
| `summaryView` | boolean | No | false | Truncate text fields for efficiency |

#### Update Operation

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `id` | UUID | **Yes** | - | Feature ID to update |
| `name` | string | No | unchanged | New feature name |
| `summary` | string | No | unchanged | New summary |
| `description` | string | No | unchanged | New description |
| `status` | enum | No | unchanged | New status |
| `priority` | enum | No | unchanged | New priority |
| `projectId` | UUID | No | unchanged | New parent project |
| `tags` | string | No | unchanged | New comma-separated tags |

**Note:** Update supports **partial updates**. Only send fields you want to change. Unchanged fields retain their current values.

#### Delete Operation

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `id` | UUID | **Yes** | - | Feature ID to delete |
| `deleteSections` | boolean | No | true | Delete associated sections |
| `force` | boolean | No | false | Delete with tasks |

#### Export Operation

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `id` | UUID | **Yes** | - | Feature ID to export |
| `format` | enum | No | "markdown" | Export format: `markdown`, `json` |
| `includeSections` | boolean | No | false | Include sections in export |

## Response Format

### Create Response

```json
{
  "success": true,
  "message": "Feature created successfully with 2 template(s) applied, creating 5 section(s)",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "User Authentication System",
    "description": null,
    "summary": "Implement comprehensive user authentication with OAuth support",
    "status": "planning",
    "priority": "high",
    "createdAt": "2025-10-18T12:00:00Z",
    "modifiedAt": "2025-10-18T12:00:00Z",
    "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "tags": ["backend", "security", "authentication"],
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
  "message": "Feature retrieved successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "User Authentication System",
    "summary": "Implement comprehensive user authentication with OAuth support",
    "status": "in-development",
    "priority": "high",
    "createdAt": "2025-10-18T12:00:00Z",
    "modifiedAt": "2025-10-18T14:30:00Z",
    "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "tags": ["backend", "security", "authentication"],
    "project": {
      "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
      "name": "MCP Task Orchestrator",
      "status": "in-development",
      "summary": "Model Context Protocol server for task management"
    },
    "sections": [
      {
        "id": "section-uuid",
        "title": "Requirements",
        "content": "- JWT token generation\n- OAuth 2.0 integration\n- Session management",
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
  "message": "Feature updated successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "completed",
    "modifiedAt": "2025-10-18T16:00:00Z"
  }
}
```

**Note:** Update returns minimal response for efficiency. Full feature data available via get operation.

### Delete Response

```json
{
  "success": true,
  "message": "Feature deleted successfully with 3 tasks and 5 sections",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "deleted": true,
    "sectionsDeleted": 5,
    "tasksDeleted": 3,
    "warningsOrphanedTasks": true
  }
}
```

### Export Response (Markdown)

```json
{
  "success": true,
  "message": "Feature transformed to markdown successfully",
  "data": {
    "markdown": "---\nname: User Authentication System\nstatus: completed\n...",
    "featureId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

### Export Response (JSON)

Export with `format: "json"` returns same structure as get operation.

## Examples

### Example 1: Create Feature with Templates

**Scenario:** Create a new authentication feature with templates for structured planning.

**Request:**
```json
{
  "operation": "create",
  "name": "User Authentication System",
  "summary": "Implement comprehensive user authentication with OAuth support",
  "status": "planning",
  "priority": "high",
  "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039",
  "tags": "backend,security,authentication",
  "templateIds": [
    "feature-planning-template-uuid",
    "technical-architecture-template-uuid"
  ]
}
```

**Response:**
```json
{
  "success": true,
  "message": "Feature created successfully with 2 template(s) applied, creating 6 section(s)",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "User Authentication System",
    "summary": "Implement comprehensive user authentication with OAuth support",
    "status": "planning",
    "priority": "high",
    "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "tags": ["backend", "security", "authentication"],
    "appliedTemplates": [
      {"templateId": "feature-planning-template-uuid", "sectionsCreated": 4},
      {"templateId": "technical-architecture-template-uuid", "sectionsCreated": 2}
    ]
  }
}
```

---

### Example 2: Get Feature with Full Context

**Scenario:** Retrieve feature with sections and project information for planning review.

**Request:**
```json
{
  "operation": "get",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "includeSections": true,
  "includeProject": true
}
```

**Response:**
```json
{
  "success": true,
  "message": "Feature retrieved successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "User Authentication System",
    "status": "in-development",
    "priority": "high",
    "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "project": {
      "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
      "name": "MCP Task Orchestrator",
      "status": "in-development",
      "summary": "Model Context Protocol server for task management"
    },
    "sections": [
      {
        "id": "section-1-uuid",
        "title": "Requirements",
        "content": "- JWT token generation\n- OAuth 2.0 integration\n- Session management\n- Password reset flow",
        "contentFormat": "markdown",
        "ordinal": 0
      },
      {
        "id": "section-2-uuid",
        "title": "Technical Approach",
        "content": "Use Passport.js for OAuth integration...",
        "contentFormat": "markdown",
        "ordinal": 1
      }
    ]
  }
}
```

---

### Example 3: Partial Update (Status Only)

**Scenario:** Mark feature as completed without changing other fields.

**Request:**
```json
{
  "operation": "update",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "completed"
}
```

**Response:**
```json
{
  "success": true,
  "message": "Feature updated successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "completed",
    "modifiedAt": "2025-10-18T16:00:00Z"
  }
}
```

**Token Efficiency:** This partial update uses ~30 characters vs ~500+ if we sent all fields.

---

### Example 4: Delete Feature with Tasks

**Scenario:** Delete feature that has tasks, requires force flag.

**Request (without force - fails):**
```json
{
  "operation": "delete",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "deleteSections": true
}
```

**Error Response:**
```json
{
  "success": false,
  "message": "Cannot delete feature with existing tasks",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Feature has 5 tasks. Use 'force=true' to delete anyway and orphan tasks.",
    "data": {
      "totalTasks": 5,
      "taskIds": ["task-1-uuid", "task-2-uuid", "task-3-uuid", "task-4-uuid", "task-5-uuid"]
    }
  }
}
```

**Request (with force - succeeds):**
```json
{
  "operation": "delete",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "deleteSections": true,
  "force": true
}
```

**Success Response:**
```json
{
  "success": true,
  "message": "Feature deleted successfully with 5 tasks and 6 sections",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "deleted": true,
    "sectionsDeleted": 6,
    "tasksDeleted": 5,
    "warningsOrphanedTasks": true
  }
}
```

---

### Example 5: Export to Markdown for Documentation

**Scenario:** Export completed feature to markdown for project documentation.

**Request:**
```json
{
  "operation": "export",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "format": "markdown",
  "includeSections": true
}
```

**Response:**
```json
{
  "success": true,
  "message": "Feature transformed to markdown successfully",
  "data": {
    "markdown": "---\nname: User Authentication System\nstatus: completed\npriority: high\ntags:\n  - backend\n  - security\n  - authentication\ncreatedAt: 2025-10-18T12:00:00Z\nmodifiedAt: 2025-10-18T16:00:00Z\n---\n\n# User Authentication System\n\n**Status:** completed  \n**Priority:** high\n\n## Summary\n\nImplement comprehensive user authentication with OAuth support\n\n## Requirements\n\n- JWT token generation\n- OAuth 2.0 integration\n- Session management\n- Password reset flow\n\n## Technical Approach\n\nUse Passport.js for OAuth integration...\n",
    "featureId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

## Best Practices

### 1. Use Partial Updates

**❌ Inefficient (sends 500+ unnecessary characters):**
```json
{
  "operation": "update",
  "id": "feature-uuid",
  "name": "Existing Name",
  "summary": "Long existing summary that hasn't changed...",
  "status": "completed",  // Only this changed
  "priority": "medium",
  "tags": "tag1,tag2,tag3"
}
```

**✅ Efficient (sends only what changed):**
```json
{
  "operation": "update",
  "id": "feature-uuid",
  "status": "completed"
}
```

### 2. Progressive Loading

**Start with basic get:**
```json
{
  "operation": "get",
  "id": "feature-uuid"
}
```

**Load details only when needed:**
```json
{
  "operation": "get",
  "id": "feature-uuid",
  "includeSections": true,
  "includeProject": true
}
```

### 3. Template-Driven Creation

**Always discover templates first:**
```bash
# Step 1: List available templates
list_templates --targetEntityType FEATURE

# Step 2: Create feature with appropriate templates
manage_feature --operation create --name "..." --templateIds ["uuid1", "uuid2"]
```

### 4. Validation Before Deletion

**Check tasks before deleting:**
```bash
# Step 1: Check feature for tasks
search_tasks --featureId "feature-uuid"

# Step 2: Review task impact

# Step 3: Delete with appropriate force flag
manage_feature --operation delete --id "feature-uuid" --force true
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
  "message": "Feature not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No feature exists with ID 550e8400-e29b-41d4-a716-446655440000"
  }
}
```

### Task Conflicts

```json
{
  "success": false,
  "message": "Cannot delete feature with existing tasks",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Feature has 5 tasks. Use 'force=true' to delete anyway.",
    "data": {
      "totalTasks": 5,
      "taskIds": ["task-1-uuid", "task-2-uuid", "..."]
    }
  }
}
```

## Related Tools

- **search_features** - Find features by filters
- **manage_task** - Task management (create tasks within features)
- **set_status** - Simple status-only updates (alternative to update operation)
- **get_sections** - Retrieve/manage feature sections
- **list_templates** - Discover available templates

## Migration from Deprecated Tools

See [Migration Guide](../migration/task-tool-consolidation.md) for complete migration instructions from:
- `create_feature` → `manage_feature` (operation: create)
- `get_feature` → `manage_feature` (operation: get)
- `update_feature` → `manage_feature` (operation: update)
- `delete_feature` → `manage_feature` (operation: delete)
- `feature_to_markdown` → `manage_feature` (operation: export)

## Performance Notes

- **Create:** Template application adds ~50-100ms per template
- **Get:** Section loading adds ~10-20ms per section, project loading ~5-10ms
- **Update:** Partial updates ~2-5ms, full validation ~5-10ms
- **Delete:** Section/task cleanup adds ~5-10ms per item
- **Export:** Markdown rendering adds ~20-50ms

**Optimization:** Use progressive loading and partial updates to minimize overhead.
