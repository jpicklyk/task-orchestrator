# reorder_sections Tool - Detailed Documentation

## Overview

Changes the display order of sections without modifying content. Provides a dedicated, efficient mechanism for reordering all sections within an entity.

**Resource**: `task-orchestrator://docs/tools/reorder-sections`

## Key Concepts

### Why Use This Tool

**reorder_sections** is designed specifically for reordering:

- ✅ **Complete reordering** - Specify new order for all sections
- ✅ **Automatic ordinal assignment** - No manual ordinal calculations
- ✅ **Validation** - Ensures all sections included, no duplicates
- ✅ **More efficient** - Single operation vs multiple updates

**vs bulk_update_sections**: This tool is specialized for pure reordering, while bulk_update is more general but requires manual ordinal management.

### When to Use This Tool

- **Complete reorganization** - Changing order of all sections
- **Logical flow improvements** - Reorganizing for better readability
- **Priority changes** - Moving important sections to top
- **Template adjustments** - Reordering template-generated sections

### When to Use Alternatives

- **Moving single section** - Use `update_section_metadata` with new ordinal
- **Updating other fields too** - Use `bulk_update_sections`

## Parameter Reference

### Required Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| **entityType** | enum | TEMPLATE, TASK, or FEATURE |
| **entityId** | UUID | Entity identifier |
| **sectionOrder** | string | Comma-separated list of section IDs in desired order |

### Validation Rules

- **All sections must be included** - Cannot omit any existing sections
- **No duplicates** - Each section ID can appear only once
- **All IDs must belong to entity** - Invalid IDs rejected
- **All IDs must exist** - Non-existent section IDs rejected

## Common Usage Patterns

### Pattern 1: Promote Section to Top

Move specific section to first position.

```json
{
  "entityType": "TASK",
  "entityId": "550e8400-e29b-41d4-a716-446655440000",
  "sectionOrder": "uuid-testing,uuid-requirements,uuid-implementation,uuid-references"
}
```

**When to use**: Making specific section highest priority

### Pattern 2: Reverse Section Order

Flip section order completely.

```javascript
// Get current sections
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

// Reverse order
const reversed = sections.data.sections
  .sort((a, b) => b.ordinal - a.ordinal)
  .map(s => s.id)
  .join(",");

await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: reversed
});
```

**When to use**: Testing different flow, bottom-up documentation

### Pattern 3: Logical Grouping

Reorganize sections by logical category.

```javascript
// Get sections
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

// Group by tags
const context = sections.data.sections.filter(s => s.tags.includes("context"));
const requirements = sections.data.sections.filter(s => s.tags.includes("requirements"));
const implementation = sections.data.sections.filter(s => s.tags.includes("implementation"));
const testing = sections.data.sections.filter(s => s.tags.includes("testing"));

// New order: context → requirements → implementation → testing
const newOrder = [
  ...context,
  ...requirements,
  ...implementation,
  ...testing
].map(s => s.id).join(",");

await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: newOrder
});
```

**When to use**: Organizing by workflow phase or category

### Pattern 4: Alphabetical Ordering

Sort sections alphabetically by title.

```javascript
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

const alphabetical = sections.data.sections
  .sort((a, b) => a.title.localeCompare(b.title))
  .map(s => s.id)
  .join(",");

await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: alphabetical
});
```

**When to use**: Standardizing section order across tasks

### Pattern 5: Priority-Based Ordering

Order by tags indicating priority.

```javascript
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

// Priority order: critical → high → medium → low
const priorityOrder = ["priority-critical", "priority-high", "priority-medium", "priority-low"];

const sorted = sections.data.sections.sort((a, b) => {
  const aPriority = priorityOrder.findIndex(p => a.tags.includes(p));
  const bPriority = priorityOrder.findIndex(p => b.tags.includes(p));
  return (aPriority === -1 ? 999 : aPriority) - (bPriority === -1 ? 999 : bPriority);
});

await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: sorted.map(s => s.id).join(",")
});
```

**When to use**: Surfacing high-priority content first

## Response Structure

### Success Response

```json
{
  "success": true,
  "message": "Sections reordered successfully",
  "data": {
    "entityType": "TASK",
    "entityId": "550e8400-e29b-41d4-a716-446655440000",
    "sectionCount": 4,
    "sectionOrder": [
      "uuid-1",
      "uuid-2",
      "uuid-3",
      "uuid-4"
    ]
  }
}
```

## Error Responses

### RESOURCE_NOT_FOUND (404)
Entity doesn't exist:
```json
{
  "success": false,
  "message": "Entity not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No TASK exists with ID ..."
  }
}
```

### VALIDATION_ERROR (400)
Invalid section order:
```json
{
  "success": false,
  "message": "Invalid section IDs",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "The following section IDs do not belong to the entity: uuid-x, uuid-y"
  }
}
```

**Common validation errors**:
- Section IDs don't belong to entity
- Missing section IDs (not all sections included)
- Duplicate section IDs
- Invalid UUID format

## Best Practices

### 1. Include All Sections

```javascript
// ✅ Correct: All 4 sections included
await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: "uuid-1,uuid-2,uuid-3,uuid-4"
});

// ❌ Error: Missing uuid-4
await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: "uuid-1,uuid-2,uuid-3"
});
// Returns VALIDATION_ERROR: Missing section IDs
```

### 2. Get Current Sections First

```javascript
// ✅ Good: Get current state before reordering
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

// Work with current sections
const newOrder = reorganize(sections.data.sections);

await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: newOrder
});

// ❌ Risky: Reorder without knowing current state
await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: "uuid-1,uuid-2,..."  // Might be missing sections
});
```

### 3. No Duplicates

```javascript
// ❌ Error: uuid-1 appears twice
await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: "uuid-1,uuid-2,uuid-1,uuid-3"
});
// Returns VALIDATION_ERROR: Duplicate section IDs

// ✅ Correct: Each section appears once
await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: "uuid-1,uuid-2,uuid-3,uuid-4"
});
```

### 4. Validate UUIDs

```javascript
// Ensure all IDs are valid UUIDs
const validOrder = sectionIds
  .filter(id => isValidUUID(id))
  .join(",");

await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: validOrder
});
```

## Common Workflows

### Workflow 1: Standard Documentation Order

```javascript
// Apply standard order: Context → Req → Tech → Impl → Test → Ref
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

const titleOrder = [
  "Context",
  "Requirements",
  "Technical Approach",
  "Implementation Notes",
  "Testing Strategy",
  "References"
];

const ordered = titleOrder
  .map(title => sections.data.sections.find(s => s.title === title))
  .filter(s => s !== undefined)
  .map(s => s.id);

// Add any sections not in standard order at end
const remaining = sections.data.sections
  .filter(s => !ordered.includes(s.id))
  .map(s => s.id);

const finalOrder = [...ordered, ...remaining].join(",");

await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: finalOrder
});
```

### Workflow 2: Move Section to Top

```javascript
// Move specific section to first position
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

const urgentSection = sections.data.sections.find(s =>
  s.tags.includes("urgent")
);

const others = sections.data.sections
  .filter(s => s.id !== urgentSection.id);

const newOrder = [urgentSection, ...others]
  .map(s => s.id)
  .join(",");

await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: newOrder
});
```

### Workflow 3: Group by Content Format

```javascript
// Order: MARKDOWN → CODE → JSON → PLAIN_TEXT
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

const formatOrder = ["MARKDOWN", "CODE", "JSON", "PLAIN_TEXT"];

const sorted = sections.data.sections.sort((a, b) => {
  const aIndex = formatOrder.indexOf(a.contentFormat);
  const bIndex = formatOrder.indexOf(b.contentFormat);
  return aIndex - bIndex;
});

await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: sorted.map(s => s.id).join(",")
});
```

## Comparison with Alternatives

### vs bulk_update_sections

| Aspect | reorder_sections | bulk_update_sections |
|--------|------------------|----------------------|
| **Purpose** | Reordering only | General updates |
| **Ordinal handling** | Automatic (sequential) | Manual (specify each) |
| **Validation** | All sections required | Only updated sections |
| **Efficiency for reordering** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| **Can update other fields** | No | Yes |

### vs update_section_metadata (single)

| Aspect | reorder_sections | update_section_metadata |
|--------|------------------|-------------------------|
| **Number of sections** | All | 1 |
| **Use case** | Complete reordering | Move single section |
| **Efficiency for 1 section** | Overkill | ⭐⭐⭐⭐⭐ |
| **Efficiency for all sections** | ⭐⭐⭐⭐⭐ | ⭐⭐ |

## Edge Cases and Limitations

### Must Include All Sections

```javascript
// Current sections: uuid-1, uuid-2, uuid-3, uuid-4

// ❌ Error: Missing uuid-4
await reorder_sections({
  sectionOrder: "uuid-1,uuid-2,uuid-3"
});
// Returns: Missing section IDs: uuid-4

// ✅ Correct: All sections included
await reorder_sections({
  sectionOrder: "uuid-1,uuid-2,uuid-3,uuid-4"
});
```

### Order String Format

```javascript
// ✅ Correct: Comma-separated, no spaces
sectionOrder: "uuid-1,uuid-2,uuid-3"

// ❌ Spaces cause UUID parsing errors
sectionOrder: "uuid-1, uuid-2, uuid-3"

// Use trim() if needed
const cleanOrder = dirtyOrder.split(",").map(id => id.trim()).join(",");
```

## Integration with Other Tools

### With get_sections

```javascript
// Always get current sections first
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

// Transform order
const newOrder = transformOrder(sections.data.sections);

// Apply new order
await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: newOrder
});
```

### With update_section_metadata (Alternative)

```javascript
// For moving single section, update_section_metadata is simpler
await update_section_metadata({
  id: sectionId,
  ordinal: 0  // Move to top
});

// For reordering all sections, use reorder_sections
await reorder_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionOrder: "uuid-3,uuid-1,uuid-4,uuid-2"
});
```

## Related Tools

- **bulk_update_sections**: Update multiple sections (including order)
- **update_section_metadata**: Update single section metadata
- **get_sections**: Retrieve current section order
- **add_section**: Add sections with ordinal

## See Also

- Bulk Updates: `task-orchestrator://docs/tools/bulk-update-sections`
- Section Metadata: `task-orchestrator://docs/tools/update-section-metadata`
- Section Retrieval: `task-orchestrator://docs/tools/get-sections`
