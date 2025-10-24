# query_templates Tool - Detailed Documentation

## Overview

The `query_templates` tool provides unified read-only operations for template discovery and retrieval. It consolidates template querying functionality into a single, efficient interface with two operations: `get` and `list`.

**Key Features:**
- **Template discovery** - Find templates matching your criteria
- **Template inspection** - Retrieve complete template definitions with optional sections
- **Flexible filtering** - Filter by entity type, built-in status, enabled status, and tags
- **Section loading** - Optionally include template sections for full template structure

**Resource**: `task-orchestrator://docs/tools/query-templates`

## Key Concepts

### Template Types

Templates are reusable documentation structures for tasks and features:

- **Task Templates** - Standardized sections for task work items
- **Feature Templates** - Standardized sections for features
- **Built-in Templates** - Pre-configured by system
- **Custom Templates** - Created by users via `manage_template`

### When to Use Templates

**Template discovery** happens before:
- Creating a task or feature (apply templates during creation)
- Applying templates to existing entities
- Reviewing available documentation structures
- Planning standardized workflows

**Typical workflow**:
```
1. List templates (find what's available)
   → query_templates(operation="list", targetEntityType="TASK")

2. Review template (inspect structure)
   → query_templates(operation="get", id="template-id", includeSections=true)

3. Create entity with template
   → manage_container(operation="create", containerType="task", templateIds=[...])

4. Or apply to existing entity
   → apply_template(templateIds=[...], entityType="TASK", entityId="...")
```

## Parameter Reference

### Common Parameters (All Operations)

| Parameter   | Type | Required | Description |
|-------------|------|----------|-------------|
| `operation` | enum | **Yes** | Operation: `get` or `list` |

### Operation-Specific Parameters

| Parameter | Type | Operations | Description |
|-----------|------|------------|-------------|
| `id` | UUID | get | Template ID |
| `includeSections` | boolean | get | Include sections (default: false) |
| `targetEntityType` | enum | list | Filter by TASK or FEATURE |
| `isBuiltIn` | boolean | list | Filter for built-in templates |
| `isEnabled` | boolean | list | Filter for enabled templates |
| `tags` | string | list | Filter by tags (comma-separated) |

---

## Operation 1: get

**Purpose**: Retrieve a specific template by ID with optional sections

### Required Parameters

- `operation`: "get"
- `id`: Template UUID

### Optional Parameters

- `includeSections`: Include section definitions (default: false)

### Example - Get Template (without sections)

```json
{
  "operation": "get",
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Template retrieved successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Task Implementation Guidance",
    "description": "Standard sections for implementing a development task with clear structure and acceptance criteria",
    "targetEntityType": "TASK",
    "isBuiltIn": true,
    "isProtected": true,
    "isEnabled": true,
    "createdBy": null,
    "tags": [
      "implementation",
      "task",
      "development"
    ],
    "createdAt": "2025-01-01T00:00:00Z",
    "modifiedAt": "2025-10-19T10:00:00Z"
  }
}
```

**Token Cost**: ~200-400 tokens (without sections)

### Example - Get Template (with sections)

```json
{
  "operation": "get",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "includeSections": true
}
```

**Response**:

```json
{
  "success": true,
  "message": "Template retrieved successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Task Implementation Guidance",
    "description": "Standard sections for implementing a development task with clear structure and acceptance criteria",
    "targetEntityType": "TASK",
    "isBuiltIn": true,
    "isProtected": true,
    "isEnabled": true,
    "createdBy": null,
    "tags": [
      "implementation",
      "task",
      "development"
    ],
    "createdAt": "2025-01-01T00:00:00Z",
    "modifiedAt": "2025-10-19T10:00:00Z",
    "sections": [
      {
        "id": "section-1",
        "title": "Technical Approach",
        "usageDescription": "Describe the technical approach for implementing this task",
        "contentSample": "## Technical Approach\n\n### Strategy\n[Outline the technical strategy and approach]\n\n### Implementation Steps\n1. [Step 1]\n2. [Step 2]\n3. [Step 3]",
        "contentFormat": "markdown",
        "ordinal": 0,
        "isRequired": true,
        "tags": ["implementation", "technical"]
      },
      {
        "id": "section-2",
        "title": "Implementation Plan",
        "usageDescription": "Step-by-step implementation plan with milestones",
        "contentSample": "## Implementation Plan\n\n### Phase 1: Setup\n- [Task 1]\n- [Task 2]\n\n### Phase 2: Core Implementation\n- [Task 1]\n- [Task 2]",
        "contentFormat": "markdown",
        "ordinal": 10,
        "isRequired": true,
        "tags": ["implementation", "planning"]
      },
      {
        "id": "section-3",
        "title": "Definition of Done",
        "usageDescription": "Acceptance criteria and completion checklist",
        "contentSample": "## Definition of Done\n\n### Requirements\n- [ ] All unit tests passing\n- [ ] Code review completed\n- [ ] Documentation updated\n- [ ] No breaking changes",
        "contentFormat": "markdown",
        "ordinal": 99,
        "isRequired": true,
        "tags": ["quality", "acceptance-criteria"]
      }
    ]
  }
}
```

**Token Cost**: ~2,000-4,000 tokens (with sections)

### When to Use Get

✅ **Use get when:**
- Inspecting a specific template structure
- Reviewing sections before applying template
- Understanding template content samples
- Validating template configuration

❌ **Avoid get when:**
- Just discovering available templates (use list instead)
- Checking if template exists (use list with filters)

---

## Operation 2: list

**Purpose**: Find templates matching filter criteria with metadata

### Required Parameters

- `operation`: "list"

### Optional Parameters

- `targetEntityType`: Filter by "TASK" or "FEATURE"
- `isBuiltIn`: Filter for built-in templates (true/false)
- `isEnabled`: Filter for enabled templates (true/false)
- `tags`: Comma-separated tags filter (matches ANY tag)

### Example - List All Task Templates

```json
{
  "operation": "list",
  "targetEntityType": "TASK"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Retrieved 12 templates",
  "data": {
    "templates": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "name": "Task Implementation Guidance",
        "description": "Standard sections for implementing a development task with clear structure and acceptance criteria",
        "targetEntityType": "TASK",
        "isBuiltIn": true,
        "isProtected": true,
        "isEnabled": true,
        "createdBy": null,
        "tags": [
          "implementation",
          "task",
          "development"
        ],
        "createdAt": "2025-01-01T00:00:00Z",
        "modifiedAt": "2025-10-19T10:00:00Z"
      },
      {
        "id": "660f9511-f40c-52e5-c938-668899001111",
        "name": "Bug Investigation Template",
        "description": "Structured approach for investigating and documenting bugs",
        "targetEntityType": "TASK",
        "isBuiltIn": true,
        "isProtected": true,
        "isEnabled": true,
        "createdBy": null,
        "tags": [
          "bug",
          "investigation",
          "testing"
        ],
        "createdAt": "2025-01-01T00:00:00Z",
        "modifiedAt": "2025-10-19T10:00:00Z"
      }
    ],
    "count": 12,
    "filters": {
      "targetEntityType": "TASK",
      "isBuiltIn": "Any",
      "isEnabled": "Any",
      "tags": "Any"
    }
  }
}
```

**Token Cost**: ~500-2,000 tokens (depends on count)

### Example - List Enabled Feature Templates

```json
{
  "operation": "list",
  "targetEntityType": "FEATURE",
  "isEnabled": true
}
```

**Response**:

```json
{
  "success": true,
  "message": "Retrieved 4 templates",
  "data": {
    "templates": [
      {
        "id": "770g0622-g51d-63f6-d049-779900002222",
        "name": "Feature Planning Template",
        "description": "Complete feature planning with requirements, architecture, and acceptance criteria",
        "targetEntityType": "FEATURE",
        "isBuiltIn": true,
        "isProtected": true,
        "isEnabled": true,
        "createdBy": null,
        "tags": [
          "planning",
          "feature",
          "requirements"
        ],
        "createdAt": "2025-01-01T00:00:00Z",
        "modifiedAt": "2025-10-19T10:00:00Z"
      },
      {
        "id": "880h1733-h62e-74g7-e150-880011113333",
        "name": "Architecture Overview",
        "description": "Detailed architecture documentation for feature design",
        "targetEntityType": "FEATURE",
        "isBuiltIn": true,
        "isProtected": true,
        "isEnabled": true,
        "createdBy": null,
        "tags": [
          "architecture",
          "design",
          "feature"
        ],
        "createdAt": "2025-01-01T00:00:00Z",
        "modifiedAt": "2025-10-19T10:00:00Z"
      }
    ],
    "count": 4,
    "filters": {
      "targetEntityType": "FEATURE",
      "isBuiltIn": "Any",
      "isEnabled": true,
      "tags": "Any"
    }
  }
}
```

### Example - List Templates by Tag

```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "tags": "implementation,development"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Retrieved 5 templates",
  "data": {
    "templates": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "name": "Task Implementation Guidance",
        "description": "Standard sections for implementing a development task with clear structure and acceptance criteria",
        "targetEntityType": "TASK",
        "isBuiltIn": true,
        "isProtected": true,
        "isEnabled": true,
        "createdBy": null,
        "tags": [
          "implementation",
          "task",
          "development"
        ],
        "createdAt": "2025-01-01T00:00:00Z",
        "modifiedAt": "2025-10-19T10:00:00Z"
      }
    ],
    "count": 5,
    "filters": {
      "targetEntityType": "TASK",
      "isBuiltIn": "Any",
      "isEnabled": "Any",
      "tags": "implementation,development"
    }
  }
}
```

### Example - List Built-in Templates

```json
{
  "operation": "list",
  "isBuiltIn": true
}
```

**Response**:

```json
{
  "success": true,
  "message": "Retrieved 18 templates",
  "data": {
    "templates": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "name": "Task Implementation Guidance",
        "description": "Standard sections for implementing a development task",
        "targetEntityType": "TASK",
        "isBuiltIn": true,
        "isProtected": true,
        "isEnabled": true,
        "createdBy": null,
        "tags": ["implementation", "task", "development"],
        "createdAt": "2025-01-01T00:00:00Z",
        "modifiedAt": "2025-10-19T10:00:00Z"
      }
    ],
    "count": 18,
    "filters": {
      "targetEntityType": "Any",
      "isBuiltIn": true,
      "isEnabled": "Any",
      "tags": "Any"
    }
  }
}
```

### When to Use List

✅ **Use list when:**
- Discovering available templates
- Finding templates matching criteria
- Building template selection UI
- Checking what templates are enabled
- Filtering by entity type or tags
- Finding templates by user

---

## Common Workflows

### Workflow 1: Template Discovery for Task

**User**: "What templates are available for tasks?"

**Step 1: List available task templates**

```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "isEnabled": true
}
```

**Step 2: Review template options in response**

Choose template(s) based on your task type:
- "Task Implementation Guidance" for development work
- "Bug Investigation Template" for bug fixes
- "Technical Approach Template" for architecture decisions

**Step 3: Create task with template**

```json
{
  "operation": "create",
  "containerType": "task",
  "title": "Implement OAuth Google provider",
  "templateIds": ["550e8400-e29b-41d4-a716-446655440000"]
}
```

---

### Workflow 2: Template Inspection Before Applying

**Step 1: List templates by type**

```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "tags": "implementation"
}
```

**Step 2: Get detailed template with sections**

```json
{
  "operation": "get",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "includeSections": true
}
```

**Step 3: Review sections and content samples**

Verify template structure matches your needs before applying.

**Step 4: Apply template to existing task**

```json
{
  "templateIds": ["550e8400-e29b-41d4-a716-446655440000"],
  "entityType": "TASK",
  "entityId": "existing-task-uuid"
}
```

---

### Workflow 3: Find Templates by Domain

**For Backend Development Tasks**:

```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "tags": "backend,development"
}
```

**For Testing Tasks**:

```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "tags": "testing"
}
```

**For Feature Planning**:

```json
{
  "operation": "list",
  "targetEntityType": "FEATURE",
  "isEnabled": true
}
```

---

### Workflow 4: Custom Templates Discovery

**List all custom (non-built-in) templates**:

```json
{
  "operation": "list",
  "isBuiltIn": false,
  "isEnabled": true
}
```

**Inspect custom template structure**:

```json
{
  "operation": "get",
  "id": "custom-template-uuid",
  "includeSections": true
}
```

---

## Best Practices

### 1. Always Discover Templates Before Creation

❌ **Hardcoding template IDs**:

```json
{
  "operation": "create",
  "containerType": "task",
  "title": "My task",
  "templateIds": ["550e8400-e29b-41d4-a716-446655440000"]  // Hardcoded!
}
```

✅ **Dynamic discovery**:

```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "isEnabled": true
}
```

Then select from discovered templates.

### 2. Filter by Entity Type First

Always specify `targetEntityType` to get relevant templates:

```json
{
  "operation": "list",
  "targetEntityType": "TASK"  // Must match entity type
}
```

### 3. Inspect Before Applying to Existing Entities

When applying templates to already-created entities:

```json
{
  "operation": "get",
  "id": "template-uuid",
  "includeSections": true  // Review what sections will be added
}
```

### 4. Only Load Sections When Needed

Without sections (fast):
```json
{
  "operation": "get",
  "id": "template-uuid"
}
```

With sections (slower, more detail):
```json
{
  "operation": "get",
  "id": "template-uuid",
  "includeSections": true
}
```

### 5. Use Tags for Domain-Specific Selection

```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "tags": "backend,api"  // Matches templates with EITHER tag
}
```

### 6. Filter by Enabled Status

Avoid disabled templates:

```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "isEnabled": true  // Only active templates
}
```

### 7. Create Task with Templates During Creation

Most efficient approach:

```json
{
  "operation": "create",
  "containerType": "task",
  "title": "Implementation task",
  "templateIds": ["discovered-template-uuid"]  // Applied during creation
}
```

Avoid applying after creation (requires extra call).

---

## Advanced Usage: Template Discovery Patterns

### Pattern 1: Find Templates for Specific Workflow

**Development Implementation Tasks**:

```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "tags": "implementation,development",
  "isEnabled": true
}
```

**Bug Fix Tasks**:

```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "tags": "bug,investigation",
  "isEnabled": true
}
```

**Documentation Tasks**:

```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "tags": "documentation",
  "isEnabled": true
}
```

### Pattern 2: Multi-Template Combination Discovery

**Find implementation templates**:

```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "tags": "implementation"
}
```

**Find testing templates**:

```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "tags": "testing"
}
```

**Combine them during task creation**:

```json
{
  "operation": "create",
  "containerType": "task",
  "title": "Complex implementation",
  "templateIds": ["implementation-template-id", "testing-template-id"]
}
```

### Pattern 3: Check Template Availability

**Before relying on a template, verify it exists and is enabled**:

```json
{
  "operation": "list",
  "targetEntityType": "FEATURE",
  "isEnabled": true
}
```

Check if your required template is in the list.

---

## Filter Combinations

### Most Common

**Task templates that are enabled**:
```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "isEnabled": true
}
```

**Feature planning templates**:
```json
{
  "operation": "list",
  "targetEntityType": "FEATURE",
  "tags": "planning"
}
```

**Built-in task templates for development**:
```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "isBuiltIn": true,
  "tags": "development"
}
```

### Advanced

**Custom task templates that are enabled**:
```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "isBuiltIn": false,
  "isEnabled": true
}
```

**All feature templates (built-in and custom)**:
```json
{
  "operation": "list",
  "targetEntityType": "FEATURE"
}
```

---

## Error Handling

### Template Not Found

**Request**:

```json
{
  "operation": "get",
  "id": "non-existent-uuid"
}
```

**Response**:

```json
{
  "success": false,
  "message": "Template not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No Template exists with ID non-existent-uuid"
  }
}
```

**Solution**: Verify template ID with `list` operation before using.

### Invalid UUID Format

**Request**:

```json
{
  "operation": "get",
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
    "details": "Invalid template ID format. Must be a valid UUID."
  }
}
```

### Invalid Entity Type

**Request**:

```json
{
  "operation": "list",
  "targetEntityType": "INVALID"
}
```

**Response**:

```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid target entity type: INVALID. Must be 'TASK' or 'FEATURE'"
  }
}
```

**Solution**: Use only "TASK" or "FEATURE" for targetEntityType.

### No Templates Found

**Request**:

```json
{
  "operation": "list",
  "targetEntityType": "TASK",
  "tags": "non-existent-tag"
}
```

**Response**:

```json
{
  "success": true,
  "message": "No templates found matching criteria",
  "data": {
    "templates": [],
    "count": 0,
    "filters": {
      "targetEntityType": "TASK",
      "isBuiltIn": "Any",
      "isEnabled": "Any",
      "tags": "non-existent-tag"
    }
  }
}
```

**Solution**: Try broader filters or check available templates with `list` without filters.

---

## Performance Considerations

### Token Usage

| Operation | Configuration | Typical Tokens | Use Case |
|-----------|---------------|----------------|----------|
| `list` | 10 results | ~500-1,000 | Template discovery |
| `list` | 20 results | ~1,000-2,000 | Full template list |
| `get` | No sections | ~200-400 | Template metadata |
| `get` | With sections | ~2,000-4,000 | Full structure inspection |

### Optimization Strategies

1. **Use list for discovery** - Minimal response, fast
2. **Only load sections when needed** - Don't include sections in get unless inspecting
3. **Filter early** - Use targetEntityType, isEnabled to narrow results
4. **Cache results** - Store template list during work session
5. **Use tags for domain filtering** - More efficient than post-processing

### Response Time Expectations

- **list** (no filters): < 100ms
- **list** (with filters): < 50ms (indexed queries)
- **get** (no sections): < 50ms (single query)
- **get** (with sections): < 150ms (joins sections)

---

## Related Tools

- **manage_template** - Create, update, delete, and manage templates
- **apply_template** - Apply templates to existing tasks/features
- **manage_container** - Create tasks/features with templates (preferred during creation)
- **query_sections** - Query sections on created entities

---

## Common Use Cases

### Use Case 1: Planning a Development Task

```
1. List task templates for development
   → query_templates(operation="list", targetEntityType="TASK", tags="development")

2. Select "Task Implementation Guidance" template

3. Create task with template
   → manage_container(operation="create", containerType="task",
     title="Implement feature", templateIds=[...])

4. Sections automatically created from template
   → Sections: Technical Approach, Implementation Plan, Definition of Done
```

### Use Case 2: Setting Up Bug Investigation

```
1. Find bug investigation templates
   → query_templates(operation="list", targetEntityType="TASK", tags="bug")

2. Inspect template structure
   → query_templates(operation="get", id="bug-template-uuid", includeSections=true)

3. Create bug task with template
   → manage_container(operation="create", containerType="task",
     title="[Bug] Fix login page", templateIds=[...])
```

### Use Case 3: Planning Feature with Standard Structure

```
1. List feature templates
   → query_templates(operation="list", targetEntityType="FEATURE", isEnabled=true)

2. Review available feature templates

3. Create feature with template
   → manage_container(operation="create", containerType="feature",
     name="User Authentication", templateIds=[...])

4. Feature includes standard sections: Requirements, Architecture, Design, etc.
```

---

## Integration with Task Orchestrator Workflow

### Recommended Workflow

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Discover Templates                                      │
│    query_templates(operation="list", targetEntityType=...) │
│    └─ Shows available templates for your entity type       │
└────────────────┬────────────────────────────────────────────┘
                 │
┌────────────────▼────────────────────────────────────────────┐
│ 2. Inspect Template (optional)                             │
│    query_templates(operation="get", id=...,                │
│                    includeSections=true)                   │
│    └─ Review sections before applying                      │
└────────────────┬────────────────────────────────────────────┘
                 │
┌────────────────▼────────────────────────────────────────────┐
│ 3a. Create with Template (PREFERRED)                       │
│    manage_container(operation="create",                    │
│                     templateIds=[...])                     │
│    └─ Sections created automatically                       │
└────────────────┬────────────────────────────────────────────┘
                 │
      OR ────────▼────────────
         │                    │
    ┌────▼──────────────┐  ┌──▼──────────────┐
    │ 3b. Create Entity │  │ 3c. Apply Later │
    │ (no template)     │  │ apply_template()│
    └───────────────────┘  └─────────────────┘
```

---

## Best Practices Summary

| Practice | DO ✅ | DON'T ❌ |
|----------|------|---------|
| **Template Discovery** | Use `list` with filters | Hardcode template UUIDs |
| **Entity Creation** | Apply templates during creation | Apply after creation |
| **Section Inspection** | Use `get` with `includeSections` | Skip checking sections |
| **Entity Type Matching** | Filter by `targetEntityType` first | Apply wrong template type |
| **Template Validation** | Check `isEnabled=true` | Use disabled templates |
| **Multi-Template Application** | Apply all together | Apply templates one-by-one |
| **Filter Usage** | Start broad, then refine | Use all filters at once |

---

## See Also

- [apply_template Tool](apply-template.md) - Apply templates to entities
- [manage_template Tool](manage-template.md) - Create and manage templates
- [manage_container Tool](manage-container.md) - Create entities with templates
- [API Reference](../api-reference.md) - Complete tool documentation
- [Templates Guide](../templates.md) - Understanding template system
- [Quick Start Guide](../quick-start.md) - Common workflows
