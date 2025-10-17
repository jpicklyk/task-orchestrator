# bulk_delete_sections Tool - Detailed Documentation

## Overview

Deletes multiple sections in a single operation. More efficient than multiple `delete_section` calls when removing 2+ sections at once.

**Resource**: `task-orchestrator://docs/tools/bulk-delete-sections`

## Key Concepts

### Why Use Bulk Deletion

**bulk_delete_sections** provides:

- ✅ **Single operation** - Delete multiple sections at once
- ✅ **Better performance** - Reduced network overhead
- ✅ **Atomic operation** - All succeed or all fail
- ✅ **Optional soft delete** - Support for reversible deletion

**Efficiency gain**: 60-75% faster than individual `delete_section` calls for 2+ sections

### When to Use This Tool

- **Clean up template sections** - Remove multiple unused sections
- **Remove duplicate sections** - Delete several duplicates at once
- **Archival cleanup** - Remove old/obsolete sections
- **Restructuring** - Delete sections being consolidated

### When to Use Alternatives

- **Single section** - Use `delete_section` (simpler)
- **Prefer archiving** - Use `update_section_metadata` with "archived" tag (reversible)

## Parameter Reference

### Required Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| **ids** | array of UUIDs | Section IDs to delete |

### Optional Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| **hardDelete** | boolean | false | Permanently delete (true) or soft delete (false) |

**Note**: `hardDelete=false` currently has same effect as `true` (permanent deletion). Parameter exists for future soft-delete support.

## Common Usage Patterns

### Pattern 1: Delete Multiple Sections

Remove several sections at once.

```json
{
  "ids": [
    "550e8400-e29b-41d4-a716-446655440000",
    "661f9511-f30c-52e5-b827-557766551111",
    "772f9622-g41d-52e5-b827-668899101111"
  ]
}
```

**When to use**: Removing multiple obsolete or unwanted sections

### Pattern 2: Clean Up Template Placeholders

Remove unused template-generated sections.

```javascript
// Get sections
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

// Find unused placeholders
const placeholders = sections.data.sections
  .filter(s => s.tags.includes("template") && s.content === "[TBD]")
  .map(s => s.id);

// Delete them
if (placeholders.length > 0) {
  await bulk_delete_sections({
    ids: placeholders
  });
}
```

**When to use**: Task created with template, some sections not needed

### Pattern 3: Remove Sections by Tag

Delete all sections with specific tag.

```javascript
// Get sections with "obsolete" tag
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  tags: "obsolete"
});

// Delete all obsolete sections
const idsToDelete = sections.data.sections.map(s => s.id);

await bulk_delete_sections({
  ids: idsToDelete
});
```

**When to use**: Removing categorized sections (obsolete, deprecated, etc.)

### Pattern 4: Delete Duplicates

Remove duplicate sections keeping only one.

```javascript
// Find sections with same title
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

const titleGroups = {};
sections.data.sections.forEach(s => {
  if (!titleGroups[s.title]) titleGroups[s.title] = [];
  titleGroups[s.title].push(s);
});

// For each duplicate set, keep first (lowest ordinal), delete rest
const duplicatesToDelete = [];
Object.values(titleGroups).forEach(group => {
  if (group.length > 1) {
    const sorted = group.sort((a, b) => a.ordinal - b.ordinal);
    duplicatesToDelete.push(...sorted.slice(1).map(s => s.id));
  }
});

if (duplicatesToDelete.length > 0) {
  await bulk_delete_sections({
    ids: duplicatesToDelete
  });
}
```

**When to use**: Cleaning up accidentally created duplicates

### Pattern 5: Remove Empty Sections

Delete sections with no content.

```javascript
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId
});

const emptyIds = sections.data.sections
  .filter(s => !s.content || s.content.trim() === "" || s.content === "[TBD]")
  .map(s => s.id);

await bulk_delete_sections({
  ids: emptyIds
});
```

**When to use**: Cleanup after partial template completion

## Response Structure

### Success Response (All Deleted)

```json
{
  "success": true,
  "message": "3 sections deleted successfully",
  "data": {
    "ids": [
      "550e8400-e29b-41d4-a716-446655440000",
      "661f9511-f30c-52e5-b827-557766551111",
      "772f9622-g41d-52e5-b827-668899101111"
    ],
    "count": 3,
    "failed": 0,
    "hardDelete": false
  }
}
```

### Partial Success Response

```json
{
  "success": true,
  "message": "2 sections deleted successfully, 1 failed",
  "data": {
    "ids": [
      "550e8400-e29b-41d4-a716-446655440000",
      "661f9511-f30c-52e5-b827-557766551111"
    ],
    "count": 2,
    "failed": 1,
    "hardDelete": false,
    "failures": [
      {
        "id": "772f9622-g41d-52e5-b827-668899101111",
        "index": 2,
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
Invalid parameters:
```json
{
  "success": false,
  "message": "At least one section ID must be provided",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

### OPERATION_FAILED
All deletions failed:
```json
{
  "success": false,
  "message": "Failed to delete any sections",
  "error": {
    "code": "OPERATION_FAILED",
    "details": "All 3 sections failed to delete"
  }
}
```

## Best Practices

### 1. Use for Multiple Deletions

```javascript
// ✅ Efficient: Bulk delete for 2+ sections
await bulk_delete_sections({
  ids: [id1, id2, id3, id4]
});

// ❌ Inefficient: Multiple individual calls
await delete_section({ id: id1 });
await delete_section({ id: id2 });
await delete_section({ id: id3 });
await delete_section({ id: id4 });
```

**Efficiency**: ~70% faster for 4 sections

### 2. Verify Sections Before Deleting

```javascript
// ✅ Good: Verify before deleting
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

const obsolete = sections.data.sections
  .filter(s => s.tags.includes("obsolete"));

console.log(`Deleting ${obsolete.length} obsolete sections`);

await bulk_delete_sections({
  ids: obsolete.map(s => s.id)
});

// ❌ Risky: Delete without verification
await bulk_delete_sections({ ids: someIds });
```

### 3. Consider Archiving Instead

```javascript
// Instead of deleting, archive for reversibility
await bulk_update_sections({
  sections: idsToArchive.map(id => ({
    id: id,
    tags: "archived,hidden"
  }))
});

// Filter out archived in queries
const active = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

const visible = active.data.sections.filter(s =>
  !s.tags.includes("archived")
);
```

### 4. Handle Partial Failures

```javascript
const result = await bulk_delete_sections({ ids: idsToDelete });

if (result.data.failed > 0) {
  console.log(`Deleted ${result.data.count}, failed ${result.data.failed}`);

  // Log failed deletions
  result.data.failures.forEach(failure => {
    console.log(`Failed to delete ${failure.id}: ${failure.error.details}`);
  });
}
```

### 5. Validate IDs

```javascript
// Ensure all IDs are valid UUIDs
const validIds = idsArray.filter(id => {
  try {
    UUID.parse(id);  // Or your UUID validation
    return true;
  } catch {
    return false;
  }
});

await bulk_delete_sections({ ids: validIds });
```

## Common Workflows

### Workflow 1: Clean Up After Template

```javascript
// Create task with template
const task = await create_task({
  title: "Implement feature",
  templateIds: ["full-template-uuid"]
});

// Get sections
const sections = await get_sections({
  entityType: "TASK",
  entityId: task.data.id,
  includeContent: false
});

// Remove optional sections not needed
const optional = sections.data.sections
  .filter(s => s.tags.includes("optional"))
  .map(s => s.id);

await bulk_delete_sections({ ids: optional });
```

### Workflow 2: Remove Old Sections

```javascript
// Find sections older than 90 days with "draft" tag
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  tags: "draft"
});

const ninetyDaysAgo = new Date();
ninetyDaysAgo.setDate(ninetyDaysAgo.getDate() - 90);

const oldDrafts = sections.data.sections
  .filter(s => new Date(s.createdAt) < ninetyDaysAgo)
  .map(s => s.id);

if (oldDrafts.length > 0) {
  await bulk_delete_sections({ ids: oldDrafts });
}
```

### Workflow 3: Consolidate Sections

```javascript
// Merge multiple sections into one, delete originals
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId
});

const reqSections = sections.data.sections.filter(s =>
  s.title.includes("Requirements")
);

// Create consolidated section
const consolidated = reqSections.map(s => s.content).join("\n\n");

await add_section({
  entityType: "TASK",
  entityId: taskId,
  title: "Requirements",
  content: consolidated,
  ordinal: 0
});

// Delete original sections
await bulk_delete_sections({
  ids: reqSections.map(s => s.id)
});
```

## Comparison with Alternatives

### vs delete_section (individual)

| Aspect | bulk_delete_sections | delete_section |
|--------|----------------------|----------------|
| **Number of sections** | 2+ | 1 |
| **Efficiency** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| **API calls** | 1 | 1 per section |
| **Use case** | Batch deletion | Single deletion |

### vs Archiving (update_section_metadata)

| Aspect | bulk_delete_sections | Archive with tags |
|--------|----------------------|-------------------|
| **Reversible** | No (permanent) | Yes |
| **Data preserved** | No | Yes |
| **Clutter** | Removes completely | Remains in database |
| **Use case** | Truly obsolete | Temporarily unused |

## Edge Cases and Limitations

### Non-Existent Section IDs

Deleting non-existent sections results in partial failure:

```javascript
await bulk_delete_sections({
  ids: [validId1, "invalid-uuid", validId2]
});
// Result: 2 deleted, 1 failed (invalid ID)
```

### No Undo

Deletion is permanent - no recovery mechanism:

```javascript
// ❌ Cannot undo
await bulk_delete_sections({ ids: importantIds });
// Sections permanently gone

// ✅ Consider archiving for reversibility
await bulk_update_sections({
  sections: importantIds.map(id => ({
    id, tags: "archived"
  }))
});
```

## Integration with Other Tools

### With get_sections

```javascript
// Browse sections, identify deletions
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

const toDelete = sections.data.sections
  .filter(s => shouldDelete(s))
  .map(s => s.id);

await bulk_delete_sections({ ids: toDelete });
```

### With bulk_update_sections (Archive Alternative)

```javascript
// Instead of deleting, archive
await bulk_update_sections({
  sections: idsToArchive.map(id => ({
    id,
    tags: "archived"
  }))
});
```

## Related Tools

- **delete_section**: Delete single section
- **bulk_update_sections**: Update instead of delete (archiving pattern)
- **get_sections**: Browse sections before deleting
- **bulk_create_sections**: Create sections (opposite operation)

## See Also

- Single Deletion: `task-orchestrator://docs/tools/delete-section`
- Archiving Pattern: `task-orchestrator://docs/tools/update-section-metadata`
- Section Browsing: `task-orchestrator://docs/tools/get-sections`
