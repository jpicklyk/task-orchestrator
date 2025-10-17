# update_section_metadata Tool - Detailed Documentation

## Overview

Updates section metadata (title, usageDescription, contentFormat, ordinal, tags) without affecting content. More efficient than `update_section` for metadata-only changes because it doesn't require loading or sending section content.

**Resource**: `task-orchestrator://docs/tools/update-section-metadata`

## Key Concepts

### Why Use This Tool

**update_section_metadata** is **PREFERRED** for metadata changes because:

- ✅ **No content required** - Saves tokens by not loading/sending content
- ✅ **Faster execution** - Less data to process
- ✅ **Clearer intent** - Explicitly metadata-focused
- ✅ **Prevents accidental content loss** - Content can't be overwritten

### Metadata vs Content

**Metadata** (this tool updates):
- title (section heading)
- usageDescription (AI guidance)
- contentFormat (MARKDOWN, PLAIN_TEXT, JSON, CODE)
- ordinal (display order)
- tags (categorization)

**Content** (this tool does NOT update):
- The actual section content text

## Parameter Reference

### Required Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| **id** | UUID | Section identifier |

### Optional Parameters (At Least One Required)

| Parameter | Type | Description |
|-----------|------|-------------|
| **title** | string | New section title (cannot be empty if provided) |
| **usageDescription** | string | New usage description (cannot be empty if provided) |
| **contentFormat** | enum | MARKDOWN, PLAIN_TEXT, JSON, or CODE |
| **ordinal** | integer | Display order position (0-based, must be non-negative) |
| **tags** | string | Comma-separated tags |

**Note**: Must provide at least one optional parameter. All optional parameters preserve existing values if not provided.

## Common Usage Patterns

### Pattern 1: Rename Section

Change section title without affecting content.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "Functional Requirements"
}
```

**When to use**:
- Standardizing section names
- Clarifying section purpose
- Reorganizing documentation structure

### Pattern 2: Update Usage Description

Improve AI guidance for section usage.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "usageDescription": "Core functional requirements that must be satisfied before implementation"
}
```

**When to use**:
- Clarifying section purpose for AI agents
- Adding context for future reference
- Improving template section descriptions

### Pattern 3: Change Content Format

Convert section to different format type.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "contentFormat": "CODE"
}
```

**When to use**:
- Section content type changed (markdown → code example)
- Correcting incorrect format assignment
- Improving syntax highlighting

**Note**: Changing format doesn't modify content - ensure content matches new format

### Pattern 4: Reorder Section

Change section's display position.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "ordinal": 0
}
```

**When to use**:
- Moving section to top/bottom
- Reorganizing section flow
- Prioritizing important sections

**Alternative**: For reordering multiple sections, use `reorder_sections`

### Pattern 5: Update Tags

Recategorize section for better filtering.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "tags": "requirements,specifications,priority-high,reviewed"
}
```

**When to use**:
- Adding agent-specific tags
- Marking section status (reviewed, approved, deprecated)
- Improving section discoverability

### Pattern 6: Multiple Metadata Updates

Update several metadata fields together.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "Security Requirements",
  "usageDescription": "Security and compliance requirements",
  "ordinal": 0,
  "tags": "requirements,security,compliance,priority-critical"
}
```

**When to use**:
- Comprehensive section reorganization
- Template standardization
- Section promotion/demotion

## Response Structure

### Success Response

```json
{
  "success": true,
  "message": "Section metadata updated successfully",
  "data": {
    "section": {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "entityType": "task",
      "entityId": "661f9511-f30c-52e5-b827-557766551111",
      "title": "Security Requirements",
      "usageDescription": "Security and compliance requirements",
      "contentFormat": "markdown",
      "ordinal": 0,
      "tags": ["requirements", "security", "compliance"],
      "createdAt": "2025-05-10T14:30:00Z",
      "modifiedAt": "2025-05-10T16:20:00Z"
    }
  }
}
```

**Note**: Response excludes `content` field for efficiency

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
  "message": "Section title cannot be empty",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Common validation errors**:
- Title or usageDescription is empty string
- Ordinal is negative
- Invalid contentFormat enum value

## Best Practices

### 1. Use for Metadata-Only Changes

```javascript
// ✅ Efficient: Metadata-only update
await update_section_metadata({
  id: sectionId,
  title: "Updated Requirements",
  ordinal: 1
});

// ❌ Inefficient: Using update_section for metadata-only
await update_section({
  id: sectionId,
  title: "Updated Requirements",
  ordinal: 1
  // Still loads content internally, wastes tokens
});
```

**Token savings**: 80-95% compared to `update_section` for metadata-only changes

### 2. Verify Non-Empty Values

```javascript
// ❌ Will fail: Empty title
await update_section_metadata({
  id: sectionId,
  title: ""  // Validation error
});

// ✅ Valid: Non-empty title
await update_section_metadata({
  id: sectionId,
  title: "Requirements"
});
```

### 3. Match Format to Content

```javascript
// Ensure content matches format when changing
await update_section_metadata({
  id: sectionId,
  contentFormat: "CODE"
  // Content should be actual code, not markdown
});
```

### 4. Use Descriptive Tags

```javascript
// ✅ Good: Descriptive, consistent tags
await update_section_metadata({
  id: sectionId,
  tags: "requirements,functional,priority-high,reviewed"
});

// ❌ Less useful: Vague or inconsistent tags
await update_section_metadata({
  id: sectionId,
  tags: "stuff,important,todo"
});
```

### 5. Standardize Section Titles

```javascript
// Standardize titles across tasks
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

for (const section of sections.data.sections) {
  if (section.title.toLowerCase().includes("requirement")) {
    await update_section_metadata({
      id: section.id,
      title: "Requirements",  // Standardized
      tags: "requirements,specifications"
    });
  }
}
```

## Common Workflows

### Workflow 1: Section Reorganization

```javascript
// Get current sections
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

// Promote "Testing" section to higher priority
const testingSection = sections.data.sections.find(s => s.title === "Testing Strategy");

await update_section_metadata({
  id: testingSection.id,
  ordinal: 1,  // Move up
  tags: "testing,priority-high"
});
```

### Workflow 2: Template Standardization

```javascript
// Standardize section metadata across tasks
const techSection = sections.data.sections.find(s =>
  s.title.includes("Technical")
);

await update_section_metadata({
  id: techSection.id,
  title: "Technical Approach",  // Standardized title
  usageDescription: "Technical implementation approach and architecture decisions",
  tags: "implementation,technical,architecture"
});
```

### Workflow 3: Tag Management

```javascript
// Add "reviewed" tag to all requirement sections
const reqSections = sections.data.sections.filter(s =>
  s.tags.includes("requirements")
);

for (const section of reqSections) {
  const updatedTags = [...section.tags, "reviewed"].join(",");

  await update_section_metadata({
    id: section.id,
    tags: updatedTags
  });
}
```

### Workflow 4: Format Correction

```javascript
// Correct sections with wrong format
const codeSections = sections.data.sections.filter(s =>
  s.title.includes("Example") && s.contentFormat !== "CODE"
);

for (const section of codeSections) {
  await update_section_metadata({
    id: section.id,
    contentFormat: "CODE"
  });
}
```

## Comparison with Alternative Tools

### vs update_section

| Aspect | update_section_metadata | update_section |
|--------|-------------------------|----------------|
| **Token usage** | Minimal (no content) | High (includes content) |
| **Can update content** | No | Yes |
| **Can update metadata** | Yes | Yes |
| **Efficiency for metadata** | ⭐⭐⭐⭐⭐ | ⭐⭐ |
| **Use case** | Metadata-only changes | Content + metadata changes |

### vs update_section_text

| Aspect | update_section_metadata | update_section_text |
|--------|-------------------------|---------------------|
| **Target** | Metadata (title, tags, format, ordinal) | Content text |
| **Can change title** | Yes | No |
| **Can change content** | No | Yes |
| **Use case** | Structural changes | Text corrections |

## Edge Cases and Limitations

### Changing Format Doesn't Transform Content

```javascript
// ❌ Misunderstanding: Format change doesn't convert content
await update_section_metadata({
  id: sectionId,
  contentFormat: "JSON"
  // Markdown content stays markdown, just labeled as JSON
});

// ✅ Correct: Change format AND update content separately
await update_section_metadata({
  id: sectionId,
  contentFormat: "JSON"
});

await update_section({
  id: sectionId,
  content: JSON.stringify(data, null, 2)
});
```

### Empty String Validation

```javascript
// ❌ Fails: Empty strings not allowed
await update_section_metadata({
  id: sectionId,
  title: "",  // Validation error
  usageDescription: ""  // Validation error
});

// ✅ Correct: Omit fields you don't want to change
await update_section_metadata({
  id: sectionId,
  ordinal: 5
  // title and usageDescription unchanged
});
```

## Integration with Other Tools

### With get_sections

```javascript
// Browse sections, update metadata
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false  // Don't need content
});

const section = sections.data.sections[0];

await update_section_metadata({
  id: section.id,
  title: "Updated Title"
});
```

### With reorder_sections (Alternative)

```javascript
// For reordering multiple sections
// Instead of multiple update_section_metadata calls:
await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: "uuid1,uuid2,uuid3,uuid4"
});
```

## Related Tools

- **update_section**: Update content and/or metadata
- **update_section_text**: Update specific text within content
- **get_sections**: Retrieve section metadata
- **reorder_sections**: Reorder multiple sections efficiently

## See Also

- Content Updates: `task-orchestrator://docs/tools/update-section`
- Text Updates: `task-orchestrator://docs/tools/update-section-text`
- Section Retrieval: `task-orchestrator://docs/tools/get-sections`
