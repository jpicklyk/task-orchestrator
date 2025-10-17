# update_section_text Tool - Detailed Documentation

## Overview

Updates specific text within a section without requiring the entire content. This is the most efficient tool for targeted content changes like fixing typos, updating values, or modifying specific paragraphs.

**Resource**: `task-orchestrator://docs/tools/update-section-text`

## Key Concepts

### Context Efficiency Strategy

**update_section_text** is **PREFERRED** for targeted updates because:

- ✅ Only send the text segment to replace (not entire content)
- ✅ Exact text matching ensures precision
- ✅ Significantly reduces token usage vs full content replacement
- ✅ Ideal for incremental improvements

### When to Use This Tool

- **Fixing typos** in template-generated content
- **Updating specific values** or references
- **Modifying specific paragraphs** within larger sections
- **Incremental improvements** without affecting surrounding text
- **Correcting placeholder text** like `[TBD]` → actual content

### When to Use Alternatives

- **Metadata changes** (title, format, ordinal, tags) → Use `update_section_metadata`
- **Complete content replacement** → Use `update_section`
- **Multiple unrelated text changes** → Use `update_section` or multiple `update_section_text` calls

## Parameter Reference

### Required Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| **id** | UUID | Section identifier |
| **oldText** | string | Text to replace (must match exactly) |
| **newText** | string | Replacement text |

**CRITICAL**: `oldText` must match exactly (case-sensitive, whitespace-sensitive)

## Common Usage Patterns

### Pattern 1: Fix Typos

Correct spelling errors in existing content.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "oldText": "straegy",
  "newText": "strategy"
}
```

**When to use**: Correcting spelling mistakes, grammar errors

### Pattern 2: Update Version References

Change version numbers or references.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "oldText": "version 1.0",
  "newText": "version 2.0"
}
```

**When to use**: Updating versions, dates, numerical values

### Pattern 3: Replace Placeholder Text

Fill in template placeholders with actual content.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "oldText": "[Insert implementation details here]",
  "newText": "Use the AuthService class to handle OAuth 2.0 authentication with JWT tokens."
}
```

**When to use**:
- Template-generated sections with `[TBD]` or `[Insert details]`
- Filling in specification templates
- Progressive documentation completion

### Pattern 4: Update API Endpoints

Change specific API references.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "oldText": "POST /api/v1/auth/login",
  "newText": "POST /api/v2/auth/login"
}
```

**When to use**: API documentation updates, endpoint changes

### Pattern 5: Modify Status Indicators

Update task progress indicators within content.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "oldText": "- [ ] Implement OAuth provider",
  "newText": "- [x] Implement OAuth provider"
}
```

**When to use**: Updating checkboxes, progress markers, status flags

### Pattern 6: Update Multi-Line Text

Replace entire paragraphs or multi-line sections.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "oldText": "### Current Approach\n\nThe current implementation uses basic password authentication.",
  "newText": "### Current Approach\n\nThe current implementation uses OAuth 2.0 with JWT tokens for secure authentication."
}
```

**When to use**: Replacing entire subsections within a section

## Response Structure

### Success Response

```json
{
  "success": true,
  "message": "Section text updated successfully",
  "data": {
    "success": true,
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "replacedTextLength": 8,
    "newTextLength": 8,
    "contentPreview": "The new straegy for authentication includes..."
  }
}
```

**Note**: Response includes preview (first 100 chars) rather than full content for efficiency

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
Text not found in section:
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

**Common causes**:
- Case mismatch (`"Strategy"` vs `"strategy"`)
- Whitespace differences
- Text has already been changed
- Text spans line breaks differently than expected

## Best Practices

### 1. Use for Small Targeted Changes

```javascript
// ✅ Efficient: Targeted text replacement
await update_section_text({
  id: sectionId,
  oldText: "[TBD]",
  newText: "OAuth 2.0 with JWT tokens"
});

// ❌ Inefficient: Using update_section for small change
await update_section({
  id: sectionId,
  content: entireContentWithSmallChange  // Wastes tokens
});
```

**Token savings**: 90-95% compared to full content update

### 2. Ensure Exact Text Match

```javascript
// ❌ Won't match: Case or whitespace mismatch
await update_section_text({
  oldText: "OAuth authentication",  // Actual: "OAuth  authentication" (2 spaces)
  newText: "OAuth 2.0 authentication"
});

// ✅ Matches exactly
await update_section_text({
  oldText: "OAuth  authentication",  // Match exact spacing
  newText: "OAuth 2.0 authentication"
});
```

### 3. Use for Progressive Template Completion

```javascript
// Template creates section with placeholders
const section = await add_section({
  content: "### Approach\n\n[Insert technical approach]\n\n### Implementation\n\n[Insert implementation details]"
});

// Fill in progressively
await update_section_text({
  id: section.id,
  oldText: "[Insert technical approach]",
  newText: "Use microservices architecture with event-driven communication"
});

await update_section_text({
  id: section.id,
  oldText: "[Insert implementation details]",
  newText: "Implement using Kafka for event streaming and Redis for caching"
});
```

### 4. Verify Text Exists Before Replacing

```javascript
// Get section content first
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionIds: [sectionId]
});

const content = sections.data.sections[0].content;

// Verify text exists
if (content.includes("[TBD]")) {
  await update_section_text({
    id: sectionId,
    oldText: "[TBD]",
    newText: "Completed"
  });
}
```

### 5. Handle Multi-Line Replacements Carefully

```javascript
// Ensure exact match including line breaks
await update_section_text({
  id: sectionId,
  oldText: "Line 1\nLine 2\nLine 3",
  newText: "Updated Line 1\nUpdated Line 2\nUpdated Line 3"
});
```

## Common Workflows

### Workflow 1: Template Placeholder Completion

```javascript
// Step 1: Create task with template
const task = await create_task({
  title: "Implement authentication",
  templateIds: ["technical-approach-uuid"]
  // Template creates sections with [TBD] placeholders
});

// Step 2: Get section IDs
const sections = await get_sections({
  entityType: "TASK",
  entityId: task.data.id,
  includeContent: false
});

const techSection = sections.data.sections.find(s => s.title === "Technical Approach");

// Step 3: Fill in placeholders progressively
await update_section_text({
  id: techSection.id,
  oldText: "[TBD: Describe technical approach]",
  newText: "Use OAuth 2.0 with Google and GitHub providers"
});
```

### Workflow 2: Incremental Documentation Updates

```javascript
// Update specific parts as work progresses
await update_section_text({
  id: sectionId,
  oldText: "Status: Not started",
  newText: "Status: In progress"
});

// Later, update again
await update_section_text({
  id: sectionId,
  oldText: "Status: In progress",
  newText: "Status: Completed"
});
```

### Workflow 3: Correct Generated Content

```javascript
// Fix typos in template-generated content
await update_section_text({
  id: sectionId,
  oldText: "authetication",
  newText: "authentication"
});

await update_section_text({
  id: sectionId,
  oldText: "requirments",
  newText: "requirements"
});
```

## Comparison with Alternative Tools

### vs update_section

| Aspect | update_section_text | update_section |
|--------|---------------------|----------------|
| **Token usage** | Minimal (only changed text) | High (entire content) |
| **Use case** | Small targeted changes | Complete content replacement |
| **Precision** | Exact text matching | Replace entire content |
| **Efficiency** | ⭐⭐⭐⭐⭐ | ⭐⭐ |

### vs update_section_metadata

| Aspect | update_section_text | update_section_metadata |
|--------|---------------------|-------------------------|
| **Target** | Content only | Metadata only (title, format, ordinal, tags) |
| **Content required** | No | No |
| **Use case** | Text changes | Structural changes |

## Edge Cases and Limitations

### Multiple Occurrences

If `oldText` appears multiple times, **all occurrences** are replaced:

```javascript
// Content: "OAuth OAuth OAuth"
await update_section_text({
  oldText: "OAuth",
  newText: "OAuth 2.0"
});
// Result: "OAuth 2.0 OAuth 2.0 OAuth 2.0"
```

**Solution**: Use more specific context in oldText to match only desired occurrence:

```javascript
await update_section_text({
  oldText: "Use OAuth for authentication",  // More specific
  newText: "Use OAuth 2.0 for authentication"
});
```

### Special Characters

Ensure special characters are properly escaped in JSON:

```javascript
await update_section_text({
  oldText: "Function: `authenticate()`",  // Backticks need escaping in JSON
  newText: "Function: `authenticateUser()`"
});
```

## Integration with Other Tools

### With get_sections (Verification)

```javascript
// Get section to verify text exists
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionIds: [sectionId]
});

const content = sections.data.sections[0].content;

if (content.includes("old text")) {
  await update_section_text({
    id: sectionId,
    oldText: "old text",
    newText: "new text"
  });
}
```

### With add_section (Template Pattern)

```javascript
// Create section with template
await add_section({
  entityType: "TASK",
  entityId: taskId,
  title: "Requirements",
  content: "### Functional\n\n[TBD]\n\n### Non-Functional\n\n[TBD]",
  ordinal: 0
});

// Fill in later
await update_section_text({
  id: sectionId,
  oldText: "[TBD]",
  newText: "Must support OAuth 2.0"
});
```

## Related Tools

- **update_section**: For complete content replacement
- **update_section_metadata**: For metadata-only changes
- **get_sections**: Retrieve section content
- **add_section**: Create sections with initial content

## See Also

- Complete Updates: `task-orchestrator://docs/tools/update-section`
- Metadata Updates: `task-orchestrator://docs/tools/update-section-metadata`
- Section Creation: `task-orchestrator://docs/tools/add-section`
