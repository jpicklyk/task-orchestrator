# bulk_update_sections Tool - Detailed Documentation

## Overview

Updates multiple sections in a single operation. More efficient than multiple `update_section` calls when you need to update 2+ sections at once.

**Resource**: `task-orchestrator://docs/tools/bulk-update-sections`

## Key Concepts

### Why Use Bulk Update

**bulk_update_sections** provides:

- ✅ **Single operation** - Update multiple sections at once
- ✅ **Partial updates** - Only send changed fields per section
- ✅ **Better performance** - Reduced network overhead
- ✅ **Consistent** timestamps** - All sections updated at same time

**Efficiency gain**: 50-70% faster than individual `update_section` calls for 2+ sections

### When to Use This Tool

- **Metadata standardization** - Update titles/tags across multiple sections
- **Batch content updates** - Update content in several sections
- **Reorganization** - Change ordinals for multiple sections
- **Tag management** - Add/update tags for section sets

### When to Use Alternatives

- **Single section** - Use `update_section` (simpler)
- **Metadata only** - Use `update_section_metadata` (more efficient)
- **Text replacements** - Use `update_section_text` (more targeted)

## Parameter Reference

### Required Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| **sections** | array | Array of section update objects |

### Section Update Object Fields

Each object in sections array:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| **id** | UUID | Yes | Section identifier |
| **title** | string | No | New section title |
| **usageDescription** | string | No | New usage description |
| **content** | string | No | New section content |
| **contentFormat** | enum | No | MARKDOWN, PLAIN_TEXT, JSON, or CODE |
| **ordinal** | integer | No | New display order |
| **tags** | string | No | Comma-separated tags |

**CRITICAL**: Each object must have `id` and at least one field to update

## Common Usage Patterns

### Pattern 1: Update Tags for Multiple Sections

Add tags to multiple sections at once.

```json
{
  "sections": [
    {
      "id": "uuid-1",
      "tags": "requirements,reviewed,approved"
    },
    {
      "id": "uuid-2",
      "tags": "implementation,reviewed,approved"
    },
    {
      "id": "uuid-3",
      "tags": "testing,reviewed,approved"
    }
  ]
}
```

**When to use**: Marking sections as reviewed, approved, or adding status tags

### Pattern 2: Reorder Multiple Sections

Change ordinals for several sections.

```json
{
  "sections": [
    {
      "id": "uuid-testing",
      "ordinal": 0
    },
    {
      "id": "uuid-requirements",
      "ordinal": 1
    },
    {
      "id": "uuid-implementation",
      "ordinal": 2
    }
  ]
}
```

**When to use**: Reorganizing section display order

**Alternative**: Use `reorder_sections` for complete reordering (more efficient)

### Pattern 3: Standardize Section Titles

Update titles to consistent format.

```json
{
  "sections": [
    {
      "id": "uuid-1",
      "title": "Functional Requirements"
    },
    {
      "id": "uuid-2",
      "title": "Technical Approach"
    },
    {
      "id": "uuid-3",
      "title": "Testing Strategy"
    }
  ]
}
```

**When to use**: Standardizing naming across tasks

### Pattern 4: Update Content for Multiple Sections

Replace content in several sections.

```json
{
  "sections": [
    {
      "id": "uuid-1",
      "content": "### Updated Requirements\n\n- OAuth 2.0 support\n- Token refresh"
    },
    {
      "id": "uuid-2",
      "content": "### Updated Approach\n\nUse passport.js library"
    }
  ]
}
```

**When to use**: Batch content updates after review

### Pattern 5: Change Format for Multiple Sections

Convert several sections to different format.

```json
{
  "sections": [
    {
      "id": "uuid-1",
      "contentFormat": "CODE"
    },
    {
      "id": "uuid-2",
      "contentFormat": "CODE"
    }
  ]
}
```

**When to use**: Converting documentation sections to code examples

### Pattern 6: Mixed Updates

Update different fields for different sections.

```json
{
  "sections": [
    {
      "id": "uuid-1",
      "title": "Updated Requirements",
      "tags": "requirements,priority-high"
    },
    {
      "id": "uuid-2",
      "content": "New implementation approach...",
      "ordinal": 5
    },
    {
      "id": "uuid-3",
      "tags": "testing,completed"
    }
  ]
}
```

**When to use**: Updating different aspects of multiple sections

## Response Structure

### Success Response (All Updated)

```json
{
  "success": true,
  "message": "3 sections updated successfully",
  "data": {
    "items": [
      {
        "id": "uuid-1",
        "entityType": "task",
        "entityId": "550e8400-e29b-41d4-a716-446655440000",
        "title": "Updated Requirements",
        "usageDescription": "Core requirements",
        "contentFormat": "markdown",
        "ordinal": 0,
        "modifiedAt": "2025-05-10T15:45:00Z"
      },
      /* ... more sections ... */
    ],
    "count": 3,
    "failed": 0
  }
}
```

### Partial Success Response

```json
{
  "success": true,
  "message": "2 sections updated successfully, 1 failed",
  "data": {
    "items": [ /* successfully updated sections */ ],
    "count": 2,
    "failed": 1,
    "failures": [
      {
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

## Error Responses

### VALIDATION_ERROR (400)
Invalid section data:
```json
{
  "success": false,
  "message": "Section at index 0 has no fields to update",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

### OPERATION_FAILED
All sections failed:
```json
{
  "success": false,
  "message": "Failed to update any sections",
  "error": {
    "code": "OPERATION_FAILED",
    "details": "All 3 sections failed to update"
  }
}
```

## Best Practices

### 1. Use for Multiple Sections

```javascript
// ✅ Efficient: Bulk update for 2+ sections
await bulk_update_sections({
  sections: [
    { id: id1, tags: "reviewed" },
    { id: id2, tags: "reviewed" },
    { id: id3, tags: "reviewed" }
  ]
});

// ❌ Inefficient: Multiple individual calls
await update_section({ id: id1, tags: "reviewed" });
await update_section({ id: id2, tags: "reviewed" });
await update_section({ id: id3, tags: "reviewed" });
```

### 2. Only Send Changed Fields

```javascript
// ✅ Efficient: Only send what changed
await bulk_update_sections({
  sections: [
    { id: id1, tags: "reviewed" },  // Only tags
    { id: id2, ordinal: 5 }  // Only ordinal
  ]
});

// ❌ Inefficient: Sending unnecessary fields
await bulk_update_sections({
  sections: [
    { id: id1, tags: "reviewed", title: existingTitle, content: existingContent }
  ]
});
```

### 3. Verify Sections Exist

```javascript
// Get sections first
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

// Prepare updates
const updates = sections.data.sections.map(s => ({
  id: s.id,
  tags: s.tags + ",reviewed"
}));

// Bulk update
await bulk_update_sections({ sections: updates });
```

### 4. Handle Partial Failures

```javascript
const result = await bulk_update_sections({ sections: updates });

if (result.data.failed > 0) {
  console.log(`${result.data.count} succeeded, ${result.data.failed} failed`);
  console.log("Failures:", result.data.failures);
}
```

## Common Workflows

### Workflow 1: Mark Sections as Reviewed

```javascript
// Get all sections
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

// Add "reviewed" tag to all
const updates = sections.data.sections.map(s => ({
  id: s.id,
  tags: [...s.tags, "reviewed"].join(",")
}));

await bulk_update_sections({ sections: updates });
```

### Workflow 2: Reorganize Section Order

```javascript
// Get current sections
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

// Reverse order
const updates = sections.data.sections
  .sort((a, b) => b.ordinal - a.ordinal)
  .map((s, index) => ({
    id: s.id,
    ordinal: index
  }));

await bulk_update_sections({ sections: updates });
```

### Workflow 3: Standardize Section Naming

```javascript
// Standardize names across multiple tasks
const sectionNameMap = {
  "Req": "Requirements",
  "Tech": "Technical Approach",
  "Test": "Testing Strategy"
};

const updates = sections.data.sections
  .filter(s => s.title in sectionNameMap)
  .map(s => ({
    id: s.id,
    title: sectionNameMap[s.title]
  }));

await bulk_update_sections({ sections: updates });
```

### Workflow 4: Update Section Formats

```javascript
// Convert all code example sections to CODE format
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  tags: "code-example"
});

const updates = sections.data.sections.map(s => ({
  id: s.id,
  contentFormat: "CODE"
}));

await bulk_update_sections({ sections: updates });
```

## Comparison with Alternatives

### vs update_section (individual)

| Aspect | bulk_update_sections | update_section |
|--------|----------------------|----------------|
| **Number of sections** | 2+ | 1 |
| **Efficiency** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| **API calls** | 1 | 1 per section |
| **Use case** | Batch updates | Single update |

### vs update_section_metadata

| Aspect | bulk_update_sections | update_section_metadata |
|--------|----------------------|-------------------------|
| **Can update content** | Yes | No |
| **Token efficiency** | Good | Better (no content) |
| **Use case** | Content + metadata | Metadata only |

### vs reorder_sections

| Aspect | bulk_update_sections | reorder_sections |
|--------|----------------------|------------------|
| **Purpose** | General updates | Reordering only |
| **Ordinal handling** | Manual | Automatic |
| **Use case** | Mixed updates | Pure reordering |

## Related Tools

- **update_section**: Update single section
- **update_section_metadata**: Metadata-only updates
- **bulk_create_sections**: Create multiple sections
- **bulk_delete_sections**: Delete multiple sections
- **reorder_sections**: Dedicated reordering tool

## See Also

- Single Updates: `task-orchestrator://docs/tools/update-section`
- Metadata Updates: `task-orchestrator://docs/tools/update-section-metadata`
- Reordering: `task-orchestrator://docs/tools/reorder-sections`
