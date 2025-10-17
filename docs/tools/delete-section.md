# delete_section Tool - Detailed Documentation

## Overview

Deletes a section by its ID. This is a permanent operation that removes the section and its content from the entity.

**Resource**: `task-orchestrator://docs/tools/delete-section`

## Key Concepts

### Permanent Deletion

**delete_section** permanently removes:
- Section content
- Section metadata
- All section data

**Cannot be undone** - ensure section should be deleted before executing.

### When to Use This Tool

- **Remove obsolete sections** - Content no longer relevant
- **Clean up template sections** - Remove unused template-generated sections
- **Restructure documentation** - Remove sections being merged/replaced
- **Delete duplicate sections** - Remove accidentally created duplicates

### Alternatives

- **For multiple sections**: Use `bulk_delete_sections` (more efficient)
- **For temporary removal**: Consider updating tags instead (e.g., add "archived" tag)

## Parameter Reference

### Required Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| **id** | UUID | Section identifier to delete |

## Common Usage Patterns

### Pattern 1: Delete Single Section

Remove one specific section.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**When to use**: Removing single obsolete or duplicate section

### Pattern 2: Clean Up Template Sections

Remove unused sections created by templates.

```javascript
// Get sections
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

// Find unused template section
const unusedSection = sections.data.sections.find(s =>
  s.title === "Optional Section" && s.content === "[TBD]"
);

// Delete it
if (unusedSection) {
  await delete_section({
    id: unusedSection.id
  });
}
```

**When to use**: Task created with template, some sections not needed

### Pattern 3: Remove Duplicate Sections

Delete accidentally created duplicates.

```javascript
// Find duplicates
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

const duplicates = sections.data.sections.filter(s =>
  s.title === "Requirements" && s.ordinal > 0
);

// Delete duplicates (keep ordinal=0)
for (const duplicate of duplicates) {
  await delete_section({
    id: duplicate.id
  });
}
```

**When to use**: Multiple sections with same title created accidentally

## Response Structure

### Success Response

```json
{
  "success": true,
  "message": "Section deleted successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "deleted": true,
    "entityType": "task",
    "entityId": "661f9511-f30c-52e5-b827-557766551111",
    "title": "Obsolete Section"
  }
}
```

**Response includes**: Confirmation of deletion and information about what was deleted

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

**Possible causes**:
- Section already deleted
- Invalid section ID
- Section belongs to different entity

### DATABASE_ERROR (500)
Deletion failed:
```json
{
  "success": false,
  "message": "Failed to delete section",
  "error": {
    "code": "DATABASE_ERROR",
    "details": "..."
  }
}
```

## Best Practices

### 1. Verify Before Deleting

```javascript
// ✅ Good: Verify section exists and is correct one
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

const section = sections.data.sections.find(s =>
  s.title === "Obsolete Section"
);

if (section) {
  console.log(`Deleting section: ${section.title}`);
  await delete_section({ id: section.id });
}

// ❌ Risky: Delete without verification
await delete_section({ id: someId });  // What section is this?
```

### 2. Use Bulk Delete for Multiple Sections

```javascript
// ❌ Inefficient: Multiple individual deletes
await delete_section({ id: id1 });
await delete_section({ id: id2 });
await delete_section({ id: id3 });

// ✅ Efficient: Bulk delete
await bulk_delete_sections({
  ids: [id1, id2, id3]
});
```

### 3. Consider Archiving Instead of Deleting

```javascript
// Instead of deleting, mark as archived
await update_section_metadata({
  id: sectionId,
  tags: "archived,deprecated"
});

// Later, filter archived sections
const activeSections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

const active = activeSections.data.sections.filter(s =>
  !s.tags.includes("archived")
);
```

### 4. Document Deletion Reason

```javascript
// For audit trail, log why section was deleted
console.log(`Deleting section ${sectionId}: Template section not needed for this task`);
await delete_section({ id: sectionId });
```

## Common Workflows

### Workflow 1: Clean Up After Template Application

```javascript
// Apply template
const task = await create_task({
  title: "Implement feature",
  templateIds: ["comprehensive-template-uuid"]
});

// Get created sections
const sections = await get_sections({
  entityType: "TASK",
  entityId: task.data.id,
  includeContent: false
});

// Remove sections not needed for this task
const optionalSections = sections.data.sections.filter(s =>
  s.title.includes("Optional")
);

for (const section of optionalSections) {
  await delete_section({ id: section.id });
}
```

### Workflow 2: Merge Sections (Delete Source)

```javascript
// Get both sections
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId
});

const section1 = sections.data.sections.find(s => s.title === "Requirements Part 1");
const section2 = sections.data.sections.find(s => s.title === "Requirements Part 2");

// Merge content
await update_section({
  id: section1.id,
  title: "Requirements",
  content: section1.content + "\n\n" + section2.content
});

// Delete second section
await delete_section({ id: section2.id });
```

### Workflow 3: Remove Empty Placeholder Sections

```javascript
// Find and remove empty template placeholders
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId
});

const emptyPlaceholders = sections.data.sections.filter(s =>
  s.content.trim() === "[TBD]" || s.content.trim() === ""
);

for (const section of emptyPlaceholders) {
  await delete_section({ id: section.id });
}
```

## Comparison with Alternatives

### vs bulk_delete_sections

| Aspect | delete_section | bulk_delete_sections |
|--------|----------------|----------------------|
| **Number of sections** | 1 | Multiple (2+) |
| **Efficiency** | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **Use case** | Single deletion | Multiple deletions |
| **API calls** | 1 per section | 1 for all sections |

### vs Archiving (update_section_metadata)

| Aspect | delete_section | Archive with tags |
|--------|----------------|-------------------|
| **Reversible** | No | Yes |
| **Data preserved** | No | Yes |
| **Clutter** | Removes completely | Remains in database |
| **Use case** | Truly obsolete content | Temporarily unused content |

## Edge Cases and Limitations

### Cannot Delete Non-Existent Section

```javascript
// Section already deleted or invalid ID
await delete_section({ id: "invalid-uuid" });
// Returns RESOURCE_NOT_FOUND error
```

### No Undo Operation

```javascript
// ❌ No way to undo deletion
await delete_section({ id: sectionId });
// Section is permanently gone

// ✅ Consider archiving instead for reversibility
await update_section_metadata({
  id: sectionId,
  tags: "archived"
});
```

### Deletion Doesn't Reorder Remaining Sections

```javascript
// Before: Sections with ordinals 0, 1, 2, 3
await delete_section({ id: ordinal1SectionId });
// After: Sections with ordinals 0, 2, 3 (gap at 1)

// If needed, use reorder_sections to fix gaps
```

## Integration with Other Tools

### With get_sections

```javascript
// Browse sections to identify what to delete
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

const toDelete = sections.data.sections.filter(s =>
  s.tags.includes("obsolete")
);

for (const section of toDelete) {
  await delete_section({ id: section.id });
}
```

### With bulk_delete_sections (Alternative)

```javascript
// More efficient for multiple deletions
const idsToDelete = toDelete.map(s => s.id);

await bulk_delete_sections({
  ids: idsToDelete
});
```

## Related Tools

- **bulk_delete_sections**: Delete multiple sections efficiently
- **get_sections**: Browse sections before deleting
- **update_section_metadata**: Archive instead of delete (reversible)
- **add_section**: Create sections (opposite operation)

## See Also

- Bulk Deletion: `task-orchestrator://docs/tools/bulk-delete-sections`
- Section Browsing: `task-orchestrator://docs/tools/get-sections`
- Archiving Pattern: `task-orchestrator://docs/tools/update-section-metadata`
