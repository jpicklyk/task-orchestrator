# manage_sections Tool - Detailed Documentation

## Overview

The `manage_sections` tool provides unified write operations for sections across all entity types (projects, features, tasks, templates). It consolidates multiple former individual tools into a single, efficient interface with nine operations: `add`, `update`, `updateText`, `updateMetadata`, `delete`, `reorder`, `bulkCreate`, `bulkUpdate`, and `bulkDelete`.

**Key Feature (v2.0+):** The `manage_sections` tool handles all section write operations with consistent validation, content format support (MARKDOWN, PLAIN_TEXT, JSON, CODE), and bulk operations for batch processing.

**Resource**: `task-orchestrator://docs/tools/manage-sections`

## Key Concepts

### Unified Section Interface

All four entity types support sections through a single write interface:

- **Projects** - High-level strategic sections (architecture, timeline, milestones)
- **Features** - Design and planning sections (requirements, architecture, implementation)
- **Tasks** - Implementation and tracking sections (approach, progress, results)
- **Templates** - Reusable section templates for consistent structure

### Nine Core Operations

1. **add** - Create new section with metadata
2. **update** - Full section update (all fields)
3. **updateText** - Targeted text replacement in content
4. **updateMetadata** - Update title, tags, ordinal without touching content
5. **delete** - Remove section by ID
6. **reorder** - Change section ordering within entity
7. **bulkCreate** - Create multiple sections at once
8. **bulkUpdate** - Update multiple sections in single operation
9. **bulkDelete** - Delete multiple sections at once

### Parameter Consistency Philosophy

v2.0 `manage_sections` emphasizes **consistent operation across all entity types**:

- Same operations work for projects, features, tasks, templates
- Content format options: MARKDOWN, PLAIN_TEXT, JSON, CODE (all optional, default: MARKDOWN)
- Section ordering is entity-specific (0-based ordinal within parent)
- Tags provide flexible categorization across any section type
- Bulk operations handle mixed entity types for efficiency

## Parameter Reference

### Common Parameters (All Operations)

| Parameter       | Type | Required | Description                                      |
|-----------------|------|----------|--------------------------------------------------|
| `operation`     | enum | **Yes**  | Operation: add, update, updateText, updateMetadata, delete, reorder, bulkCreate, bulkUpdate, bulkDelete |

### Operation-Specific Parameters

| Parameter      | Type    | Operations             | Description                                       |
|----------------|---------|------------------------|---------------------------------------------------|
| `id`           | UUID    | update, updateText, updateMetadata, delete | Section ID to modify |
| `ids`          | array   | bulkDelete             | Array of section IDs to delete |
| `entityType`   | enum    | add, reorder, bulkCreate | Entity type: PROJECT, FEATURE, TASK, TEMPLATE |
| `entityId`     | UUID    | add, reorder, bulkCreate | Parent entity ID |
| `title`        | string  | add, update, updateMetadata, bulkCreate, bulkUpdate | Section title |
| `usageDescription` | string | add, update, updateMetadata, bulkCreate, bulkUpdate | Brief usage description |
| `content`      | string  | add, update, bulkCreate, bulkUpdate | Section content (text, markdown, json, code) |
| `contentFormat` | enum   | add, update, updateMetadata, bulkCreate, bulkUpdate | MARKDOWN, PLAIN_TEXT, JSON, CODE (default: MARKDOWN) |
| `ordinal`      | integer | add, update, updateMetadata, bulkCreate, bulkUpdate | Display order (0-based, default: 0) |
| `tags`         | string  | add, update, updateMetadata, bulkCreate, bulkUpdate | Comma-separated tags for filtering |
| `oldText`      | string  | updateText             | Text to find and replace |
| `newText`      | string  | updateText             | Replacement text |
| `sectionOrder` | string  | reorder                | Comma-separated section IDs in desired order |
| `sections`     | array   | bulkCreate, bulkUpdate | Array of section objects |

### Entity Types Supported

- **PROJECT** - Project-level sections
- **FEATURE** - Feature-level sections
- **TASK** - Task-level sections
- **TEMPLATE** - Template-level sections

### Content Format Options

- **MARKDOWN** - Markdown formatted content (default)
- **PLAIN_TEXT** - Plain text without formatting
- **JSON** - JSON structured data
- **CODE** - Source code (with language inference)

---

## Quick Start

### Basic Add Pattern

```json
{
  "operation": "add",
  "entityType": "task",
  "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "title": "Implementation Approach",
  "usageDescription": "Detailed technical approach for implementation",
  "content": "1. Set up development environment\n2. Create OAuth handler\n3. Implement token management",
  "contentFormat": "MARKDOWN",
  "ordinal": 5,
  "tags": "implementation,technical"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Section added successfully",
  "data": {
    "id": "section-uuid-1",
    "entityType": "task",
    "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
    "title": "Implementation Approach",
    "ordinal": 5,
    "createdAt": "2025-10-24T10:00:00Z"
  }
}
```

### Basic Update Pattern

```json
{
  "operation": "update",
  "id": "section-uuid-1",
  "title": "Updated Title",
  "content": "Updated content here"
}
```

### Basic Delete Pattern

```json
{
  "operation": "delete",
  "id": "section-uuid-1"
}
```

### Common Patterns

**Update only metadata (title and tags)**:

```json
{
  "operation": "updateMetadata",
  "id": "section-uuid-1",
  "title": "New Title",
  "tags": "updated,important"
}
```

**Replace text in section**:

```json
{
  "operation": "updateText",
  "id": "section-uuid-1",
  "oldText": "TODO: implement",
  "newText": "DONE: implemented on 2025-10-24"
}
```

**Reorder sections within entity**:

```json
{
  "operation": "reorder",
  "entityType": "task",
  "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "sectionOrder": "section-uuid-2, section-uuid-1, section-uuid-3"
}
```

**Bulk create multiple sections**:

```json
{
  "operation": "bulkCreate",
  "sections": [
    {
      "entityType": "task",
      "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
      "title": "Requirements",
      "usageDescription": "Feature requirements",
      "content": "- Must support OAuth\n- Must handle token refresh",
      "ordinal": 0
    },
    {
      "entityType": "task",
      "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
      "title": "Implementation Plan",
      "usageDescription": "Step-by-step implementation",
      "content": "1. Setup\n2. Create handlers\n3. Test",
      "ordinal": 5
    }
  ]
}
```

---

## Operation 1: add

**Purpose**: Create a new section with metadata

### Required Parameters

- `operation`: "add"
- `entityType`: PROJECT, FEATURE, TASK, or TEMPLATE
- `entityId`: Parent entity UUID
- `title`: Section title
- `usageDescription`: Brief description of section purpose
- `content`: Section content
- `ordinal`: Display order (0-based)

### Optional Parameters

- `contentFormat`: MARKDOWN, PLAIN_TEXT, JSON, CODE (default: MARKDOWN)
- `tags`: Comma-separated tags

### Example - Add Section to Task

```json
{
  "operation": "add",
  "entityType": "task",
  "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "title": "Files Changed",
  "usageDescription": "List of files modified during implementation",
  "content": "- src/main/kotlin/AuthService.kt (new)\n- src/main/kotlin/OAuthHandler.kt (new)\n- src/test/kotlin/AuthServiceTest.kt (new)",
  "contentFormat": "MARKDOWN",
  "ordinal": 999,
  "tags": "files-changed,completion"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Section added successfully",
  "data": {
    "id": "section-uuid-1",
    "entityType": "task",
    "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
    "title": "Files Changed",
    "ordinal": 999,
    "createdAt": "2025-10-24T10:00:00Z"
  }
}
```

**Token Cost**: ~300-400 tokens

### Example - Add JSON Section

```json
{
  "operation": "add",
  "entityType": "feature",
  "entityId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "title": "API Response Schema",
  "usageDescription": "Expected API response format",
  "content": "{\"status\": \"success\",\"data\": {\"userId\": \"uuid\",\"email\": \"string\",\"createdAt\": \"ISO8601\"}}",
  "contentFormat": "JSON",
  "ordinal": 10,
  "tags": "api,schema"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Section added successfully",
  "data": {
    "id": "section-uuid-2",
    "entityType": "feature",
    "entityId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
    "title": "API Response Schema",
    "ordinal": 10,
    "createdAt": "2025-10-24T10:15:00Z"
  }
}
```

### Add Best Practices

1. **Always specify ordinal** - Determines display order within entity
2. **Use meaningful titles** - Describes section purpose clearly
3. **Include usage description** - Explains when/why section is used
4. **Apply relevant tags** - Enables filtering and categorization
5. **Choose correct contentFormat** - MARKDOWN for docs, JSON for data, CODE for code
6. **Verify entity exists** - Tool validates entityId before creation

### When to Use Add

✅ **Use add when:**

- Creating new section for task/feature/project/template
- Adding documentation during implementation
- Building task structure with standard sections
- Adding "Files Changed" section at end of task
- Creating template sections for reuse

---

## Operation 2: update

**Purpose**: Modify existing section (all fields can be updated)

### Required Parameters

- `operation`: "update"
- `id`: Section UUID to update

### Optional Parameters

- `title`: New title
- `usageDescription`: New description
- `content`: New content
- `contentFormat`: New content format
- `ordinal`: New display order
- `tags`: New tags

**Note**: Only specified fields are updated; others remain unchanged.

### Example - Update Section Content

```json
{
  "operation": "update",
  "id": "section-uuid-1",
  "content": "Updated content with new information. Previously: TODO\nNow: COMPLETED on 2025-10-24"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Section updated successfully",
  "data": {
    "id": "section-uuid-1",
    "modifiedAt": "2025-10-24T11:00:00Z"
  }
}
```

**Token Cost**: ~200-300 tokens

### Example - Update Multiple Fields

```json
{
  "operation": "update",
  "id": "section-uuid-1",
  "title": "Implementation Progress",
  "content": "OAuth provider integrated. Token management in progress. 70% complete.",
  "tags": "progress,implementation,75-percent"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Section updated successfully",
  "data": {
    "id": "section-uuid-1",
    "modifiedAt": "2025-10-24T11:15:00Z"
  }
}
```

### Update Best Practices

1. **Only update necessary fields** - Reduces token usage
2. **Keep content format consistent** - Don't change unless needed
3. **Preserve meaningful tags** - Add new tags, don't remove existing ones
4. **Update ordinal carefully** - May affect visual layout
5. **Use updateMetadata for structure changes** - Separate from content updates

### When to Use Update

✅ **Use update when:**

- Modifying section content during work
- Adding new information to existing section
- Changing section title or purpose
- Updating tags for better categorization
- Reorganizing section ordering

❌ **Avoid update when:**

- Only changing text (use updateText instead)
- Only changing metadata (use updateMetadata instead)
- Deleting entire section (use delete)

---

## Operation 3: updateText

**Purpose**: Replace specific text within section content

### Required Parameters

- `operation`: "updateText"
- `id`: Section UUID
- `oldText`: Text to find and replace
- `newText`: Replacement text

### Example - Update Progress Text

```json
{
  "operation": "updateText",
  "id": "section-uuid-1",
  "oldText": "Status: In Progress",
  "newText": "Status: Completed"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Section text updated successfully",
  "data": {
    "id": "section-uuid-1",
    "replacedTextLength": 17,
    "newTextLength": 17,
    "modifiedAt": "2025-10-24T11:30:00Z"
  }
}
```

**Token Cost**: ~200 tokens

### Example - Update Multiple Occurrences

```json
{
  "operation": "updateText",
  "id": "section-uuid-1",
  "oldText": "TODO:",
  "newText": "DONE:"
}
```

This replaces the first occurrence. For bulk replacements across sections, use multiple updateText calls or bulkUpdate.

### updateText Best Practices

1. **Use exact string matching** - oldText must match exactly (case-sensitive)
2. **Escape special characters** - Include quotes, newlines, etc. as they appear
3. **Replace one pattern at a time** - Keep changes atomic
4. **Verify text exists first** - Check section content before updateText
5. **Use for minor updates** - Full replacements better with update operation

### When to Use updateText

✅ **Use updateText when:**

- Updating status markers (TODO → DONE)
- Replacing dates or version numbers
- Minor content corrections
- Marking progress in content
- Updating test results or metrics

❌ **Avoid updateText when:**

- Replacing large content blocks (use update)
- Changing multiple unrelated parts (use update)
- Modifying structure/format (use update)

---

## Operation 4: updateMetadata

**Purpose**: Update section title, tags, ordinal, and format WITHOUT modifying content

### Required Parameters

- `operation`: "updateMetadata"
- `id`: Section UUID

### Optional Parameters

- `title`: New title
- `usageDescription`: New description
- `contentFormat`: New content format
- `ordinal`: New display order
- `tags`: New tags

### Example - Update Title and Tags

```json
{
  "operation": "updateMetadata",
  "id": "section-uuid-1",
  "title": "Implementation Progress - OAuth2",
  "tags": "backend,security,oauth,progress"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Section metadata updated successfully",
  "data": {
    "id": "section-uuid-1",
    "modifiedAt": "2025-10-24T11:45:00Z"
  }
}
```

**Token Cost**: ~150-200 tokens

### Example - Update Ordinal for Reordering

```json
{
  "operation": "updateMetadata",
  "id": "section-uuid-2",
  "ordinal": 5
}
```

### Example - Change Content Format

```json
{
  "operation": "updateMetadata",
  "id": "section-uuid-3",
  "contentFormat": "JSON"
}
```

### updateMetadata Best Practices

1. **Preserve content integrity** - Content is never modified
2. **Update tags incrementally** - Add important tags as you work
3. **Use meaningful ordinals** - Standard convention: 0-10 for main, 999 for "Files Changed"
4. **Keep descriptions current** - Update when section purpose evolves
5. **Change format only if necessary** - Usually done once during creation

### When to Use updateMetadata

✅ **Use updateMetadata when:**

- Reorganizing section order
- Updating section title/description
- Adding or modifying tags
- Changing content format
- Maintaining section structure while preserving content

---

## Operation 5: delete

**Purpose**: Remove a section by ID

### Required Parameters

- `operation`: "delete"
- `id`: Section UUID

### Example - Delete Section

```json
{
  "operation": "delete",
  "id": "section-uuid-1"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Section deleted successfully",
  "data": {
    "id": "section-uuid-1",
    "deleted": true,
    "entityType": "task",
    "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
    "title": "Old Section"
  }
}
```

**Token Cost**: ~150 tokens

### Delete Best Practices

1. **Query section first** - Verify section exists and content before deletion
2. **Consider archiving instead** - Use status/tags to hide instead of delete
3. **Check dependencies** - Ensure nothing depends on section
4. **Delete unused sections** - Remove template/placeholder sections
5. **Irreversible operation** - No undo available

### When to Use Delete

✅ **Use delete when:**

- Removing placeholder sections
- Cleaning up template sections
- Deleting sections created in error
- Removing obsolete documentation

❌ **Avoid delete when:**

- Content still needed (archive with tags instead)
- Unsure about dependencies (query first)
- Others might reference section (mark archived)

---

## Operation 6: reorder

**Purpose**: Change section ordering within an entity

### Required Parameters

- `operation`: "reorder"
- `entityType`: PROJECT, FEATURE, TASK, or TEMPLATE
- `entityId`: Parent entity UUID
- `sectionOrder`: Comma-separated section IDs in desired order

### Example - Reorder Task Sections

```json
{
  "operation": "reorder",
  "entityType": "task",
  "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "sectionOrder": "section-uuid-5, section-uuid-2, section-uuid-1, section-uuid-3"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Sections reordered successfully",
  "data": {
    "entityType": "task",
    "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
    "sectionCount": 4
  }
}
```

**Token Cost**: ~200-300 tokens

### Reorder Validation Rules

- **All sections required** - sectionOrder must include ALL sections of the entity
- **No duplicates allowed** - Each section ID appears exactly once
- **Valid UUIDs only** - All section IDs must be valid UUIDs
- **IDs must belong to entity** - All section IDs must belong to specified entity

### Reorder Best Practices

1. **Query sections first** - Get complete list before reordering
2. **Include all sections** - sectionOrder must contain every section
3. **Use ordinal values** - Consider using updateMetadata instead for individual ordinals
4. **Standard conventions** - Main sections 0-10, "Files Changed" at 999
5. **Verify before operation** - Validate new order makes sense

### When to Use Reorder

✅ **Use reorder when:**

- Organizing section display order
- Moving "Files Changed" section to end (ordinal 999)
- Arranging sections by importance
- Creating consistent structure
- Rearranging after section additions/deletions

---

## Operation 7: bulkCreate

**Purpose**: Create multiple sections at once for efficiency

### Required Parameters

- `operation`: "bulkCreate"
- `sections`: Array of section objects

### Section Object Structure

Each section in the array must include:
- `entityType`: PROJECT, FEATURE, TASK, or TEMPLATE
- `entityId`: Parent entity UUID
- `title`: Section title
- `usageDescription`: Brief description
- `content`: Section content
- `ordinal`: Display order

Optional:
- `contentFormat`: MARKDOWN, PLAIN_TEXT, JSON, CODE (default: MARKDOWN)
- `tags`: Comma-separated tags

### Example - Bulk Create Task Sections

```json
{
  "operation": "bulkCreate",
  "sections": [
    {
      "entityType": "task",
      "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
      "title": "Requirements",
      "usageDescription": "Feature requirements and acceptance criteria",
      "content": "1. Support OAuth2 authentication\n2. JWT token generation\n3. Token refresh mechanism",
      "contentFormat": "MARKDOWN",
      "ordinal": 0,
      "tags": "requirements"
    },
    {
      "entityType": "task",
      "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
      "title": "Implementation Approach",
      "usageDescription": "Technical implementation details",
      "content": "1. Create OAuth service\n2. Implement token manager\n3. Add security middleware",
      "ordinal": 5,
      "tags": "implementation,backend"
    },
    {
      "entityType": "task",
      "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
      "title": "Files Changed",
      "usageDescription": "List of modified files",
      "content": "- OAuthService.kt (new)\n- TokenManager.kt (new)\n- SecurityMiddleware.kt (updated)",
      "ordinal": 999,
      "tags": "files-changed,completion"
    }
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "3 sections created successfully",
  "data": {
    "items": [
      {
        "id": "section-uuid-1",
        "entityType": "task",
        "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "title": "Requirements",
        "ordinal": 0,
        "createdAt": "2025-10-24T10:00:00Z"
      },
      {
        "id": "section-uuid-2",
        "entityType": "task",
        "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "title": "Implementation Approach",
        "ordinal": 5,
        "createdAt": "2025-10-24T10:01:00Z"
      },
      {
        "id": "section-uuid-3",
        "entityType": "task",
        "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "title": "Files Changed",
        "ordinal": 999,
        "createdAt": "2025-10-24T10:02:00Z"
      }
    ],
    "count": 3,
    "failed": 0
  }
}
```

**Token Cost**: ~400-600 tokens (for 3 sections)

### Example - Bulk Create with Partial Failures

```json
{
  "operation": "bulkCreate",
  "sections": [
    {
      "entityType": "task",
      "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
      "title": "Valid Section",
      "usageDescription": "This will succeed",
      "content": "Content here",
      "ordinal": 0
    },
    {
      "entityType": "task",
      "entityId": "invalid-uuid",
      "title": "Invalid Entity",
      "usageDescription": "This will fail",
      "content": "Content here",
      "ordinal": 5
    }
  ]
}
```

**Response** (partial success):

```json
{
  "success": true,
  "message": "1 sections created successfully, 1 failed",
  "data": {
    "items": [
      {
        "id": "section-uuid-1",
        "entityType": "task",
        "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "title": "Valid Section",
        "createdAt": "2025-10-24T10:00:00Z"
      }
    ],
    "count": 1,
    "failed": 1,
    "failures": [
      {
        "index": 1,
        "error": {
          "code": "RESOURCE_NOT_FOUND",
          "details": "Entity not found: TASK with ID invalid-uuid"
        }
      }
    ]
  }
}
```

### BulkCreate Validation Rules

- **At least one section** - Cannot create empty batch
- **All required fields** - Each section needs entityType, entityId, title, usageDescription, content, ordinal
- **Valid entity types** - Only PROJECT, FEATURE, TASK, TEMPLATE
- **Valid entity IDs** - All entityIds must be valid UUIDs and exist
- **Valid ordinals** - Must be non-negative integers
- **Optional fields validated** - contentFormat must be valid if specified

### BulkCreate Best Practices

1. **Use for template application** - Bulk create standard section structure
2. **Group by entity** - Create sections for same entity in one operation
3. **Include Files Changed** - Always add with ordinal 999 for completion
4. **Validate entities exist** - Tool validates, but check first for speed
5. **Keep batches reasonable** - 3-10 sections per operation
6. **Include meaningful tags** - Help with discovery and filtering

### When to Use BulkCreate

✅ **Use bulkCreate when:**

- Setting up standard sections for new task/feature
- Applying template structure to multiple entities
- Batch creating documentation sections
- Initializing entity with full section structure
- Creating 3+ sections at once

❌ **Avoid bulkCreate when:**

- Creating single section (use add)
- Sections for different entities (group by entity)
- Updating existing sections (use bulkUpdate)

---

## Operation 8: bulkUpdate

**Purpose**: Update multiple sections in a single operation

### Required Parameters

- `operation`: "bulkUpdate"
- `sections`: Array of section update objects

### Section Update Object Structure

Each section must include:
- `id`: Section UUID (required)
- At least one updatable field: title, usageDescription, content, contentFormat, ordinal, tags

### Example - Bulk Update Multiple Sections

```json
{
  "operation": "bulkUpdate",
  "sections": [
    {
      "id": "section-uuid-1",
      "title": "Updated Requirements",
      "content": "Updated requirement content"
    },
    {
      "id": "section-uuid-2",
      "tags": "implementation,completed,backend"
    },
    {
      "id": "section-uuid-3",
      "usageDescription": "New description",
      "ordinal": 10
    }
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "3 sections updated successfully",
  "data": {
    "items": [
      {
        "id": "section-uuid-1",
        "entityType": "task",
        "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "title": "Updated Requirements",
        "ordinal": 0,
        "modifiedAt": "2025-10-24T11:00:00Z"
      },
      {
        "id": "section-uuid-2",
        "entityType": "task",
        "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "title": "Implementation Approach",
        "ordinal": 5,
        "modifiedAt": "2025-10-24T11:01:00Z"
      },
      {
        "id": "section-uuid-3",
        "entityType": "task",
        "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "title": "Files Changed",
        "ordinal": 10,
        "modifiedAt": "2025-10-24T11:02:00Z"
      }
    ],
    "count": 3,
    "failed": 0
  }
}
```

**Token Cost**: ~300-500 tokens (for 3 sections)

### Example - BulkUpdate with Partial Failures

```json
{
  "operation": "bulkUpdate",
  "sections": [
    {
      "id": "section-uuid-1",
      "title": "Valid Update"
    },
    {
      "id": "non-existent-uuid",
      "title": "Invalid ID"
    },
    {
      "id": "section-uuid-3",
      "title": "Another Valid"
    }
  ]
}
```

**Response** (partial success):

```json
{
  "success": true,
  "message": "2 sections updated successfully, 1 failed",
  "data": {
    "items": [
      {
        "id": "section-uuid-1",
        "title": "Valid Update",
        "modifiedAt": "2025-10-24T11:00:00Z"
      },
      {
        "id": "section-uuid-3",
        "title": "Another Valid",
        "modifiedAt": "2025-10-24T11:01:00Z"
      }
    ],
    "count": 2,
    "failed": 1,
    "failures": [
      {
        "index": 1,
        "id": "non-existent-uuid",
        "error": {
          "code": "RESOURCE_NOT_FOUND",
          "details": "Section not found"
        }
      }
    ]
  }
}
```

### BulkUpdate Validation Rules

- **At least one section** - Cannot update empty batch
- **Each section requires id** - UUID is mandatory
- **At least one update field** - Each section needs: title, usageDescription, content, contentFormat, ordinal, or tags
- **Valid section IDs** - UUIDs must be valid format and exist
- **Field validation** - contentFormat must be valid, ordinal must be non-negative
- **Partial success allowed** - Valid sections updated even if others fail

### BulkUpdate Best Practices

1. **Update same type together** - Group similar updates
2. **Validate IDs first** - Query sections to verify before update
3. **Keep updates focused** - Each section updates 1-2 fields
4. **Handle failures gracefully** - Check failures array in response
5. **Use for status updates** - Tag updates across sections
6. **Atomic operations** - Each section updated independently

### When to Use BulkUpdate

✅ **Use bulkUpdate when:**

- Updating tags across multiple sections
- Batch updating section titles
- Reordering multiple sections
- Marking sections complete
- Adding metadata to multiple sections
- Updating 3+ sections

❌ **Avoid bulkUpdate when:**

- Updating single section (use update)
- Content modifications only (use updateText)
- Metadata only (use updateMetadata)
- Deleting sections (use bulkDelete)

---

## Operation 9: bulkDelete

**Purpose**: Delete multiple sections in a single operation

### Required Parameters

- `operation`: "bulkDelete"
- `ids`: Array of section IDs to delete

### Example - Bulk Delete Sections

```json
{
  "operation": "bulkDelete",
  "ids": [
    "section-uuid-1",
    "section-uuid-2",
    "section-uuid-3"
  ]
}
```

**Response**:

```json
{
  "success": true,
  "message": "3 sections deleted successfully",
  "data": {
    "ids": [
      "section-uuid-1",
      "section-uuid-2",
      "section-uuid-3"
    ],
    "count": 3,
    "failed": 0
  }
}
```

**Token Cost**: ~200-300 tokens (for 3 sections)

### Example - BulkDelete with Partial Failures

```json
{
  "operation": "bulkDelete",
  "ids": [
    "section-uuid-1",
    "non-existent-uuid",
    "section-uuid-3"
  ]
}
```

**Response** (partial success):

```json
{
  "success": true,
  "message": "2 sections deleted successfully, 1 failed",
  "data": {
    "ids": [
      "section-uuid-1",
      "section-uuid-3"
    ],
    "count": 2,
    "failed": 1,
    "failures": [
      {
        "id": "non-existent-uuid",
        "index": 1,
        "error": {
          "code": "RESOURCE_NOT_FOUND",
          "details": "Section not found"
        }
      }
    ]
  }
}
```

### BulkDelete Validation Rules

- **At least one ID** - Cannot delete empty batch
- **Valid UUIDs** - All section IDs must be valid UUID format
- **IDs must exist** - Tool validates existence before deletion
- **Partial success allowed** - Valid sections deleted even if others fail
- **Irreversible** - Deleted sections cannot be recovered

### BulkDelete Best Practices

1. **Verify sections first** - Query before bulk delete
2. **Check dependencies** - Ensure nothing depends on sections
3. **Be cautious** - Deletion is irreversible
4. **Include all IDs** - Don't batch separate delete operations
5. **Handle failures** - Check failures array for details
6. **Use for cleanup** - Remove old/placeholder sections

### When to Use BulkDelete

✅ **Use bulkDelete when:**

- Removing placeholder sections
- Cleaning up old documentation
- Deleting 3+ sections
- Removing template sections
- Purging obsolete content

❌ **Avoid bulkDelete when:**

- Deleting single section (use delete)
- Unsure about impact (query first)
- Content still needed (archive instead)

---

## Advanced Usage

### Token Efficiency Notes

| Operation      | Typical Tokens | Best For              |
|----------------|----------------|-----------------------|
| add            | 300-400        | Single new section    |
| update         | 200-300        | Field modifications   |
| updateText     | 200            | Text replacements     |
| updateMetadata | 150-200        | Structure changes     |
| delete         | 150            | Single removal        |
| reorder        | 200-300        | Rearranging           |
| bulkCreate     | 400-600        | Multiple new sections |
| bulkUpdate     | 300-500        | Batch modifications   |
| bulkDelete     | 200-300        | Batch removal         |

**Recommendation**: Use bulk operations for 3+ items (more efficient than individual calls).

### With Task Implementation Workflow

**Specialist task completion workflow** using manage_sections:

```
1. Read task with existing sections (via query_sections)
2. Add "Files Changed" section (ordinal 999)
   ↓
3. Update task summary with results
   ↓
4. Mark task complete (via manage_container)
   ↓
5. Return minimal output to orchestrator
```

### Integration with manage_container

Task lifecycle uses both tools:

```json
// Step 1: Create task with templates
{
  "operation": "create",
  "containerType": "task",
  "title": "Implement feature",
  "templateIds": ["template-uuid"]
}

// Step 2: Customize sections (manage_sections)
{
  "operation": "update",
  "id": "section-uuid",
  "content": "Updated requirements"
}

// Step 3: Add completion section
{
  "operation": "add",
  "entityType": "task",
  "entityId": "task-uuid",
  "title": "Files Changed",
  "content": "Modified files list",
  "ordinal": 999
}

// Step 4: Mark task complete
{
  "operation": "setStatus",
  "containerType": "task",
  "id": "task-uuid",
  "status": "completed"
}
```

### Bulk Section Creation During Feature Setup

Template-driven bulk creation:

```json
{
  "operation": "bulkCreate",
  "sections": [
    {
      "entityType": "feature",
      "entityId": "feature-uuid",
      "title": "Requirements",
      "usageDescription": "Feature requirements",
      "content": "From template",
      "ordinal": 0,
      "tags": "template,requirements"
    },
    {
      "entityType": "feature",
      "entityId": "feature-uuid",
      "title": "Architecture",
      "usageDescription": "System design",
      "content": "From template",
      "ordinal": 5,
      "tags": "template,architecture"
    },
    {
      "entityType": "feature",
      "entityId": "feature-uuid",
      "title": "Implementation",
      "usageDescription": "Step-by-step plan",
      "content": "From template",
      "ordinal": 10,
      "tags": "template,implementation"
    }
  ]
}
```

---

## Error Handling

| Error Code | Condition | Solution |
|-----------|-----------|----------|
| VALIDATION_ERROR | Invalid operation | Check operation spelling: add, update, updateText, updateMetadata, delete, reorder, bulkCreate, bulkUpdate, bulkDelete |
| VALIDATION_ERROR | Required parameter missing | Verify: operation, id (if required), entityType, entityId, title, usageDescription, content (as needed) |
| VALIDATION_ERROR | Invalid UUID format | Use valid UUIDs, format: `550e8400-e29b-41d4-a716-446655440000` |
| VALIDATION_ERROR | Invalid entityType | Use: PROJECT, FEATURE, TASK, TEMPLATE |
| VALIDATION_ERROR | Invalid contentFormat | Use: MARKDOWN, PLAIN_TEXT, JSON, CODE |
| VALIDATION_ERROR | Ordinal is negative | Ordinal must be 0 or positive integer |
| VALIDATION_ERROR | BulkCreate/BulkUpdate missing required fields | Check each section has all required fields |
| VALIDATION_ERROR | BulkDelete empty array | Provide at least one section ID |
| VALIDATION_ERROR | updateText oldText not found | Verify exact text to replace exists in content |
| VALIDATION_ERROR | Reorder missing/invalid section IDs | sectionOrder must include ALL sections of entity |
| RESOURCE_NOT_FOUND | Entity (project/feature/task) not found | Verify entityId exists and is correct |
| RESOURCE_NOT_FOUND | Section not found | Verify section ID is correct |
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

**Scenario 1: Missing Required Field**

```json
{
  "operation": "add",
  "entityType": "task",
  "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "title": "Section"
}
```

Response:

```json
{
  "success": false,
  "message": "Missing required parameter: content",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "The 'content' parameter is required for add operation"
  }
}
```

**Scenario 2: Text Not Found**

```json
{
  "operation": "updateText",
  "id": "section-uuid-1",
  "oldText": "Nonexistent text",
  "newText": "New text"
}
```

Response:

```json
{
  "success": false,
  "message": "Text not found",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "The specified text to replace was not found in the section content"
  }
}
```

**Scenario 3: Invalid Reorder**

```json
{
  "operation": "reorder",
  "entityType": "task",
  "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "sectionOrder": "section-uuid-1, section-uuid-2"
}
```

Response (assuming 3 sections exist):

```json
{
  "success": false,
  "message": "Missing section IDs",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "The following section IDs are missing from the order: section-uuid-3"
  }
}
```

---

## Integration Patterns

### Pattern 1: Task Implementation → Add Files Changed Section

```
1. Specialist completes implementation work
2. Create "Files Changed" section with modified files
3. Use add operation with ordinal: 999 (standard convention)
4. Include tags: "files-changed,completion"
5. Update task summary and mark complete
```

**Example**:

```json
{
  "operation": "add",
  "entityType": "task",
  "entityId": "task-uuid",
  "title": "Files Changed",
  "usageDescription": "Summary of modified files",
  "content": "- src/OAuthService.kt (new)\n- src/TokenManager.kt (new)\n- tests/OAuthServiceTest.kt (new)",
  "contentFormat": "MARKDOWN",
  "ordinal": 999,
  "tags": "files-changed,completion"
}
```

### Pattern 2: Query → Update → Verify

```
1. Query sections for entity (query_sections)
2. Use bulkUpdate to modify sections
3. Query again to verify changes
```

**Example**:

```json
// Step 1: Query sections
{
  "operation": "overview",
  "containerType": "task",
  "id": "task-uuid"
}

// Step 2: Update sections
{
  "operation": "bulkUpdate",
  "sections": [
    {"id": "section-uuid-1", "tags": "updated"},
    {"id": "section-uuid-2", "tags": "updated"}
  ]
}

// Step 3: Verify with query
{
  "operation": "overview",
  "containerType": "task",
  "id": "task-uuid"
}
```

### Pattern 3: Bulk Create Template Sections

```
1. Template defines section structure
2. Use bulkCreate to apply all sections at once
3. Specialists customize as needed
```

**Example**:

```json
{
  "operation": "bulkCreate",
  "sections": [
    {
      "entityType": "task",
      "entityId": "new-task-uuid",
      "title": "Requirements",
      "usageDescription": "Feature requirements",
      "content": "From standard template",
      "ordinal": 0,
      "tags": "template"
    },
    {
      "entityType": "task",
      "entityId": "new-task-uuid",
      "title": "Implementation",
      "usageDescription": "Implementation plan",
      "content": "From standard template",
      "ordinal": 5,
      "tags": "template"
    }
  ]
}
```

### Pattern 4: Content Update Without Metadata Change

```
1. Use updateText for minor content corrections
2. Use update for major content replacements
3. Use updateMetadata to reorganize structure
```

---

## Use Cases

### Use Case 1: Specialist Task Completion Workflow

**Scenario**: Specialist completes task implementation and needs to document changes.

**Steps**:

1. Query task sections to understand current structure

```json
{
  "operation": "get",
  "containerType": "task",
  "id": "task-uuid",
  "includeSections": true
}
```

2. Update implementation section with results

```json
{
  "operation": "update",
  "id": "implementation-section-uuid",
  "content": "Implemented OAuth2 with Google provider. All 15 tests passing."
}
```

3. Add Files Changed section

```json
{
  "operation": "add",
  "entityType": "task",
  "entityId": "task-uuid",
  "title": "Files Changed",
  "usageDescription": "Modified files during implementation",
  "content": "- src/OAuthService.kt (new, 150 lines)\n- src/GoogleOAuthProvider.kt (new, 200 lines)\n- src/TokenManager.kt (modified, +50 lines)",
  "ordinal": 999,
  "tags": "files-changed,completion"
}
```

4. Mark task complete via manage_container

```json
{
  "operation": "setStatus",
  "containerType": "task",
  "id": "task-uuid",
  "status": "completed"
}
```

### Use Case 2: Bulk Section Creation During Feature Setup

**Scenario**: Feature architect creates feature with standard section structure from template.

**Steps**:

1. Create feature

```json
{
  "operation": "create",
  "containerType": "feature",
  "name": "User Authentication System",
  "projectId": "project-uuid",
  "priority": "high"
}
```

2. Bulk create standard sections from template

```json
{
  "operation": "bulkCreate",
  "sections": [
    {
      "entityType": "feature",
      "entityId": "feature-uuid",
      "title": "Requirements",
      "usageDescription": "Feature requirements and acceptance criteria",
      "content": "- OAuth2 support\n- JWT tokens\n- Session management",
      "ordinal": 0,
      "tags": "requirements"
    },
    {
      "entityType": "feature",
      "entityId": "feature-uuid",
      "title": "Architecture",
      "usageDescription": "System design and components",
      "content": "Authentication service with OAuth providers",
      "ordinal": 5,
      "tags": "architecture"
    },
    {
      "entityType": "feature",
      "entityId": "feature-uuid",
      "title": "Implementation Tasks",
      "usageDescription": "Subtasks for implementation",
      "content": "1. Create authentication service\n2. Add OAuth providers\n3. Implement token management",
      "ordinal": 10,
      "tags": "implementation,tasks"
    }
  ]
}
```

3. Plan tasks broken down from subtasks

### Use Case 3: Updating Section Content Progressively

**Scenario**: Developer working on task progressively updates status in documentation section.

**Steps**:

1. Initial status

```json
{
  "operation": "updateText",
  "id": "progress-section-uuid",
  "oldText": "Status: Not started",
  "newText": "Status: In Progress (25% complete)"
}
```

2. Midway update

```json
{
  "operation": "updateText",
  "id": "progress-section-uuid",
  "oldText": "Status: In Progress (25% complete)",
  "newText": "Status: In Progress (75% complete)"
}
```

3. Completion

```json
{
  "operation": "updateText",
  "id": "progress-section-uuid",
  "oldText": "Status: In Progress (75% complete)",
  "newText": "Status: Completed - All tests passing"
}
```

---

## Best Practices

### DO

✅ **Always include ordinal when creating** - Determines section order

✅ **Use appropriate content format** - MARKDOWN for docs, JSON for structured data, CODE for code

✅ **Include meaningful tags** - Enables filtering: `files-changed`, `completion`, `implementation`, etc.

✅ **Use Files Changed section** - Standard ordinal 999 at end of task

✅ **Bulk operations for 3+ items** - More efficient than individual operations

✅ **Update metadata separately** - Keep structure changes separate from content

✅ **Query before reorder** - Verify all sections included in reorder

✅ **Specify all sections in reorder** - sectionOrder must include EVERY section

✅ **Use updateText for minor changes** - More efficient than full update

✅ **Archive instead of delete** - Use tags/status rather than deletion

### DON'T

❌ **Don't delete without verification** - Check dependencies first

❌ **Don't leave placeholder text** - Remove or update template sections

❌ **Don't exceed reasonable ordinals** - Standard: 0-10 for main, 999 for "Files Changed"

❌ **Don't mix entity types in reorder** - One entity type per reorder operation

❌ **Don't modify content with updateMetadata** - Content stays unchanged

❌ **Don't use bulkUpdate for single section** - Use update operation instead

❌ **Don't trust oldText without verification** - Ensure text exists before updateText

❌ **Don't modify multiple things at once** - Keep changes focused and atomic

❌ **Don't create sections without entity verification** - Tool validates, but pre-check for safety

❌ **Don't ignore bulk operation failures** - Check failures array in response

---

## Related Tools

- **query_sections** - Read operations (retrieve sections with selective loading)
- **manage_container** - Write operations for projects, features, tasks (container lifecycle)
- **query_container** - Read operations for projects, features, tasks
- **get_next_status** - Status progression recommendations

---

## Integration with Workflow Systems

### Workflow Prompts Integration

The manage_sections tool works with workflow prompts for:

- **Implementation Workflow** - Create and update task sections during work
- **Feature Setup Workflow** - Bulk create standard sections from templates
- **Task Completion Workflow** - Add Files Changed and mark complete

### Skills Integration

**Specialists** use manage_sections for:

1. Adding implementation notes during work
2. Creating Files Changed section on completion
3. Updating progress documentation
4. Bulk creating structure from templates

---

## References

### Source Code

- **Tool Implementation**: `src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/section/ManageSectionsTool.kt`
- **Domain Model**: `src/main/kotlin/io/github/jpicklyk/mcptask/domain/model/Section.kt`
- **Section Repository**: `src/main/kotlin/io/github/jpicklyk/mcptask/domain/repository/SectionRepository.kt`

### Related Documentation

- **[query_sections Tool](query-sections.md)** - Read operations for sections
- **[manage_container Tool](manage-container.md)** - Container lifecycle management
- **[query_container Tool](query-container.md)** - Container read operations
- **[API Reference](../api-reference.md)** - Complete tool documentation index
- **[Quick Start Guide](../quick-start.md)** - Getting started with Task Orchestrator

### Example Dataset

All examples use consistent IDs:

- **Project**: `b160fbdb-07e4-42d7-8c61-8deac7d2fc17` (MCP Task Orchestrator)
- **Feature**: `f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c` (Container Management API)
- **Task**: `a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d` (Implement manage_sections tool)

---

## Version History

- **v2.0.0** (2025-10-24): Initial comprehensive documentation for unified manage_sections tool
- **v2.0.0-beta** (2025-10-19): manage_sections tool release as part of v2.0 consolidation

---

## See Also

- [API Reference](../api-reference.md) - Complete tool documentation
- [query_sections Tool](query-sections.md) - Read operations (complementary tool)
- [Quick Start Guide](../quick-start.md) - Common workflows
- [v2.0 Migration Guide](../migration/v2.0-migration-guide.md) - Upgrading from v1.x
