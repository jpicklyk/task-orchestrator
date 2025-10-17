# update_section Tool - Detailed Documentation

## Overview

Updates an existing section with support for partial updates. Only send the fields you want to change, making this tool efficient for targeted modifications.

**Resource**: `task-orchestrator://docs/tools/update-section`

## Key Concepts

### Partial Update Strategy

**update_section** supports partial updates - you only send fields being changed:

- ✅ **Send only changed fields** - Efficient, recommended
- ❌ **Send all fields** - Wastes tokens, not necessary

### When to Use This Tool

- **Changing multiple fields** (title + content + format)
- **Complete content replacement**
- **Metadata and content changes together**

### When to Use Alternative Tools

- **Content-only changes**: Use `update_section_text` (more efficient for targeted text replacement)
- **Metadata-only changes**: Use `update_section_metadata` (doesn't require sending content)

## Parameter Reference

### Required Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| **id** | UUID | Section identifier to update |

### Optional Parameters (All Fields)

| Parameter | Type | Description |
|-----------|------|-------------|
| **title** | string | New section title (becomes ## H2 heading) |
| **usageDescription** | string | New usage description for AI/users |
| **content** | string | New section content (complete replacement) |
| **contentFormat** | enum | MARKDOWN, PLAIN_TEXT, JSON, or CODE |
| **ordinal** | integer | Display order position (0-based) |
| **tags** | string | Comma-separated tags |

**CRITICAL**: Only send fields you want to change. Unchanged fields retain their current values.

## Common Usage Patterns

### Pattern 1: Update Content Only

Replace entire content of a section.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "content": "## New Requirements\n\n- Must support OAuth 2.0\n- Must handle token refresh\n- Should support rate limiting"
}
```

**When to use**: Complete content rewrite

**Alternative**: For small text changes, use `update_section_text` instead (more efficient)

### Pattern 2: Update Title and Usage Description

Change section metadata without touching content.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "Functional Requirements",
  "usageDescription": "Core functional requirements that must be satisfied"
}
```

**When to use**: Renaming or clarifying section purpose

**Alternative**: Use `update_section_metadata` (doesn't require loading content first)

### Pattern 3: Change Content Format

Convert section from one format to another.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "contentFormat": "CODE",
  "content": "function authenticate(user) {\n  // Implementation\n}"
}
```

**When to use**: Converting markdown documentation to code example, or vice versa

### Pattern 4: Reorder Section

Change section's display position.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "ordinal": 5
}
```

**When to use**: Moving section to different position in sequence

**Alternative**: For reordering multiple sections, use `reorder_sections` (more efficient)

### Pattern 5: Update Tags

Change section categorization.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "tags": "requirements,specifications,priority-high"
}
```

**When to use**: Recategorizing section for better filtering

### Pattern 6: Complete Section Overhaul

Update everything at once.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "Updated Requirements",
  "usageDescription": "Revised requirements after stakeholder feedback",
  "content": "### Phase 1 Requirements\n\n- OAuth 2.0 support\n\n### Phase 2 Requirements\n\n- SSO integration",
  "contentFormat": "MARKDOWN",
  "ordinal": 0,
  "tags": "requirements,specifications,revised"
}
```

**When to use**: Major section revision affecting multiple aspects

## Response Structure

### Success Response

```json
{
  "success": true,
  "message": "Section updated successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "modifiedAt": "2025-05-10T15:45:00Z"
  }
}
```

**Note**: Response is minimal for efficiency. Use `get_sections` to retrieve updated content if needed.

## Error Responses

### RESOURCE_NOT_FOUND (404)
Section doesn't exist:
```json
{
  "success": false,
  "message": "Section not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No section exists with ID ..."
  }
}
```

### VALIDATION_ERROR (400)
Invalid parameters:
```json
{
  "success": false,
  "message": "Invalid content format. Must be one of: MARKDOWN, PLAIN_TEXT, JSON, CODE",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

### DATABASE_ERROR (500)
Update failed:
```json
{
  "success": false,
  "message": "Failed to update section",
  "error": {
    "code": "DATABASE_ERROR",
    "details": "..."
  }
}
```

## Best Practices

### 1. Only Send Changed Fields

```javascript
// ❌ Inefficient: Sending all fields when only title changed
await update_section({
  id: sectionId,
  title: "New Title",
  usageDescription: existingSection.usageDescription,  // Unnecessary
  content: existingSection.content,  // Wastes tokens
  contentFormat: existingSection.contentFormat,
  ordinal: existingSection.ordinal,
  tags: existingSection.tags
});

// ✅ Efficient: Only send what changed
await update_section({
  id: sectionId,
  title: "New Title"
});
```

### 2. Use Appropriate Tool for the Job

```javascript
// ❌ Inefficient: Using update_section for small text fix
await update_section({
  id: sectionId,
  content: fullContentWithTypoFixed  // Sends entire content
});

// ✅ Efficient: Use update_section_text for targeted changes
await update_section_text({
  id: sectionId,
  oldText: "straegy",
  newText: "strategy"
});
```

### 3. Metadata-Only Changes

```javascript
// ❌ Inefficient: Using update_section for metadata-only
await update_section({
  id: sectionId,
  title: "New Title",
  ordinal: 5
  // Still needs to load section content internally
});

// ✅ Efficient: Use update_section_metadata
await update_section_metadata({
  id: sectionId,
  title: "New Title",
  ordinal: 5
  // No content involved, saves tokens
});
```

### 4. Verify Section Exists Before Update

```javascript
// Get section metadata first
const metadata = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

const section = metadata.data.sections.find(s => s.title === "Requirements");

if (section) {
  await update_section({
    id: section.id,
    content: "Updated requirements..."
  });
}
```

### 5. Preserve Content Format

When updating content, ensure it matches the contentFormat:

```javascript
// If section is CODE format
await update_section({
  id: sectionId,
  contentFormat: "CODE",
  content: "function example() {\n  return 'valid code';\n}"
});

// If section is JSON format
await update_section({
  id: sectionId,
  contentFormat: "JSON",
  content: JSON.stringify({ endpoints: [...] }, null, 2)
});
```

## Common Workflows

### Workflow 1: Content Revision

```javascript
// 1. Get current section content
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionIds: [sectionId]
});

const current = sections.data.sections[0];

// 2. Revise content
const revisedContent = current.content + "\n\n### Additional Notes\n...";

// 3. Update section
await update_section({
  id: sectionId,
  content: revisedContent
});
```

### Workflow 2: Template Application

```javascript
// Apply template structure to existing section
await update_section({
  id: sectionId,
  title: "Requirements Specification",
  usageDescription: "Functional and non-functional requirements",
  content: "### Functional Requirements\n\n[TBD]\n\n### Non-Functional Requirements\n\n[TBD]",
  tags: "requirements,specifications,template"
});
```

### Workflow 3: Section Reorganization

```javascript
// Promote section to higher priority
await update_section({
  id: sectionId,
  ordinal: 0,  // Move to top
  tags: "priority-high,urgent"
});
```

## Integration with Other Tools

### With get_sections

```javascript
// Retrieve section
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionIds: [sectionId]
});

// Modify and update
const section = sections.data.sections[0];
await update_section({
  id: section.id,
  content: modifyContent(section.content)
});
```

### With update_section_text (Alternative)

```javascript
// For small text changes, use update_section_text instead
await update_section_text({
  id: sectionId,
  oldText: "[TBD]",
  newText: "Implementation complete"
});
```

### With update_section_metadata (Alternative)

```javascript
// For metadata-only changes, use update_section_metadata
await update_section_metadata({
  id: sectionId,
  title: "Revised Requirements",
  ordinal: 1
});
```

## Related Tools

- **update_section_text**: More efficient for targeted content changes
- **update_section_metadata**: More efficient for metadata-only changes
- **get_sections**: Retrieve section before updating
- **add_section**: Create new sections
- **delete_section**: Remove sections
- **bulk_update_sections**: Update multiple sections at once

## See Also

- Text Updates: `task-orchestrator://docs/tools/update-section-text`
- Metadata Updates: `task-orchestrator://docs/tools/update-section-metadata`
- Section Retrieval: `task-orchestrator://docs/tools/get-sections`
