# get_sections Tool - Detailed Documentation

## Overview

Retrieves sections for tasks, features, or projects with powerful filtering and content control options. Sections contain detailed content in structured blocks, allowing efficient context management through selective loading.

**Resource**: `task-orchestrator://docs/tools/get-sections`

## Key Concepts

### Context Efficiency Strategy

**get_sections** offers two-step workflow for optimal token usage:

1. **Browse Mode** (`includeContent=false`): Retrieve metadata only (85-99% token savings)
   - Section IDs, titles, formats, ordinals, tags
   - Identify which sections you need

2. **Load Mode** (`sectionIds=[...]`): Fetch specific sections with content
   - Load only sections relevant to current work
   - Pass section IDs from browse step

### Why This Matters

- **Tasks with many sections**: Browse first, load selectively
- **Agent-specific content**: Filter by tags for role-specific sections
- **Large codebases**: Avoid loading entire task context when you only need specific sections

## Parameter Reference

### Required Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| **entityType** | enum | TASK, FEATURE, or PROJECT |
| **entityId** | UUID | Entity identifier |

### Optional Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| **includeContent** | boolean | true | Include section content. Set false for metadata only (saves 85-99% tokens) |
| **sectionIds** | array of UUIDs | all | Specific section IDs to retrieve. Enables selective loading after browsing |
| **tags** | string | - | Comma-separated tags to filter sections (returns sections with ANY tag) |

## Common Usage Patterns

### Pattern 1: Get All Sections (Full Content)

Default behavior - retrieve all sections with complete content.

```json
{
  "entityType": "TASK",
  "entityId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**When to use**: Task has few sections (1-3), need complete context

**Response includes**: Full content for all sections

### Pattern 2: Browse Section Structure (Metadata Only)

Retrieve only section metadata to understand structure without loading content.

```json
{
  "entityType": "TASK",
  "entityId": "550e8400-e29b-41d4-a716-446655440000",
  "includeContent": false
}
```

**When to use**:
- Task has many sections (4+)
- Want to see what's available before loading
- Building a section navigation UI
- Optimizing token usage

**Response includes**: id, title, usageDescription, contentFormat, ordinal, tags, timestamps

**Token savings**: 85-99% compared to full content load

### Pattern 3: Two-Step Workflow (Browse → Load)

Most efficient pattern for large tasks with many sections.

**Step 1 - Browse metadata:**
```json
{
  "entityType": "TASK",
  "entityId": "550e8400-e29b-41d4-a716-446655440000",
  "includeContent": false
}
```

**Response analysis:**
```json
{
  "sections": [
    { "id": "uuid-1", "title": "Requirements", "tags": ["requirements"] },
    { "id": "uuid-2", "title": "Technical Approach", "tags": ["implementation"] },
    { "id": "uuid-3", "title": "Testing Strategy", "tags": ["testing"] },
    { "id": "uuid-4", "title": "API Documentation", "tags": ["documentation"] }
  ]
}
```

**Step 2 - Load specific sections:**
```json
{
  "entityType": "TASK",
  "entityId": "550e8400-e29b-41d4-a716-446655440000",
  "sectionIds": ["uuid-1", "uuid-2"]
}
```

**When to use**:
- Task has 4+ sections
- Only need specific sections for current work
- Want to minimize token usage
- Building progressive loading UI

### Pattern 4: Filter by Tags (Agent-Specific Content)

Retrieve only sections relevant to specific role or purpose.

```json
{
  "entityType": "TASK",
  "entityId": "550e8400-e29b-41d4-a716-446655440000",
  "tags": "requirements,technical-approach"
}
```

**Returns**: Sections tagged with "requirements" OR "technical-approach" (OR logic)

**When to use**:
- Agent needs role-specific content (e.g., backend engineer only needs implementation sections)
- Filter documentation vs code sections
- Separate testing from implementation content
- Progressive disclosure based on task phase

**Example tag combinations:**

**For Backend Engineer:**
```json
{ "tags": "implementation,technical-approach,architecture" }
```

**For QA Engineer:**
```json
{ "tags": "testing,qa,acceptance-criteria" }
```

**For Documentation:**
```json
{ "tags": "documentation,references,examples" }
```

### Pattern 5: Selective Loading with Tag Filter

Combine tag filtering with metadata browsing.

```json
{
  "entityType": "TASK",
  "entityId": "550e8400-e29b-41d4-a716-446655440000",
  "tags": "implementation,testing",
  "includeContent": false
}
```

**When to use**:
- Explore what implementation/testing sections exist
- Load those sections in subsequent call if needed

## Response Structure

### Success Response

```json
{
  "success": true,
  "message": "Retrieved 3 sections",
  "data": {
    "sections": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "title": "Requirements",
        "usageDescription": "Key requirements for implementation",
        "content": "- Must support OAuth 2.0...",
        "contentFormat": "MARKDOWN",
        "ordinal": 0,
        "tags": ["requirements", "specifications"],
        "createdAt": "2025-05-10T14:30:00Z",
        "modifiedAt": "2025-05-10T14:30:00Z"
      }
    ],
    "entityType": "TASK",
    "entityId": "550e8400-e29b-41d4-a716-446655440000",
    "count": 3
  }
}
```

### Metadata-Only Response (includeContent=false)

```json
{
  "success": true,
  "message": "Retrieved 3 sections",
  "data": {
    "sections": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "title": "Requirements",
        "usageDescription": "Key requirements for implementation",
        "contentFormat": "MARKDOWN",
        "ordinal": 0,
        "tags": ["requirements", "specifications"],
        "createdAt": "2025-05-10T14:30:00Z",
        "modifiedAt": "2025-05-10T14:30:00Z"
      }
    ],
    "entityType": "TASK",
    "entityId": "550e8400-e29b-41d4-a716-446655440000",
    "count": 3
  }
}
```

**Note**: `content` field is omitted

### Empty Result Response

```json
{
  "success": true,
  "message": "No sections found for task",
  "data": {
    "sections": [],
    "entityType": "TASK",
    "entityId": "550e8400-e29b-41d4-a716-446655440000",
    "count": 0
  }
}
```

## Error Responses

### RESOURCE_NOT_FOUND (404)
Entity doesn't exist:
```json
{
  "success": false,
  "message": "The specified task was not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND"
  }
}
```

### VALIDATION_ERROR (400)
Invalid parameters:
```json
{
  "success": false,
  "message": "Invalid entity type: INVALID. Must be one of: TASK, FEATURE, PROJECT",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

## Content Format Types

Sections support multiple content formats:

| Format | Description | Use Case |
|--------|-------------|----------|
| **MARKDOWN** | Rich formatted text | Documentation, requirements, notes |
| **PLAIN_TEXT** | Unformatted text | Simple notes, plain descriptions |
| **JSON** | Structured data | API specs, configuration, structured info |
| **CODE** | Source code | Implementation examples, snippets |

## Common Section Types

While sections can have any title, common patterns include:

| Section Title | Typical Tags | Content Format | Purpose |
|---------------|--------------|----------------|---------|
| Requirements | requirements, specifications | MARKDOWN | What needs to be built |
| Technical Approach | implementation, technical | MARKDOWN | How to implement |
| Implementation Notes | implementation, guidance | MARKDOWN | Detailed technical guidance |
| Testing Strategy | testing, qa | MARKDOWN | Testing approach and coverage |
| Code Examples | code-example, implementation | CODE | Sample implementations |
| API Documentation | api, documentation | JSON/MARKDOWN | API specifications |
| References | references, documentation | MARKDOWN | External links and resources |
| Architecture | architecture, design | MARKDOWN | System design decisions |

## Best Practices

### 1. Use Two-Step Workflow for Large Tasks

```javascript
// ❌ Inefficient: Load all content when task has many sections
const allSections = await get_sections({
  entityType: "TASK",
  entityId: taskId
});
// Wastes tokens loading irrelevant sections

// ✅ Efficient: Browse first, load selectively
const metadata = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

// Analyze metadata, identify needed sections
const needed = metadata.data.sections
  .filter(s => s.tags.includes("implementation"))
  .map(s => s.id);

// Load only what you need
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  sectionIds: needed
});
```

### 2. Filter by Tags for Role-Specific Work

```javascript
// Backend engineer only needs implementation sections
const backendSections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  tags: "implementation,technical-approach,architecture"
});

// QA engineer only needs testing sections
const testingSections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  tags: "testing,qa,acceptance-criteria"
});
```

### 3. Progressive Loading Based on Task Phase

```javascript
// Phase 1: Planning - load requirements only
const requirements = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  tags: "requirements"
});

// Phase 2: Implementation - load technical sections
const implementation = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  tags: "implementation,technical-approach"
});

// Phase 3: Testing - load testing sections
const testing = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  tags: "testing,qa"
});
```

### 4. Always Check Entity Type Support

get_sections works with:
- ✅ TASK
- ✅ FEATURE
- ✅ PROJECT

Different entity types may have different section patterns.

## Integration with Other Tools

### With get_task

```javascript
// Option 1: Get task with sections included
const task = await get_task({
  id: taskId,
  includeSections: true
});

// Option 2: Get task metadata, then load sections selectively
const task = await get_task({ id: taskId });
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  tags: "implementation"
});
```

### With add_section

```javascript
// Add new section
const newSection = await add_section({
  entityType: "TASK",
  entityId: taskId,
  title: "Implementation Notes",
  content: "...",
  ordinal: 1
});

// Retrieve all sections to see new structure
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false  // Just check structure
});
```

### With update_section

```javascript
// Browse sections to find what needs updating
const metadata = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  includeContent: false
});

// Find specific section
const reqSection = metadata.data.sections
  .find(s => s.title === "Requirements");

// Update it
await update_section({
  id: reqSection.id,
  content: "Updated requirements..."
});
```

## Related Tools

- **add_section**: Create new sections
- **update_section**: Modify entire section
- **update_section_text**: Update specific text within section
- **update_section_metadata**: Update section metadata without content
- **delete_section**: Remove sections
- **bulk_create_sections**: Create multiple sections at once
- **get_task**: Get task with optional section inclusion

## See Also

- Section Management: `task-orchestrator://docs/tools/add-section`
- Task Retrieval: `task-orchestrator://docs/tools/get-task`
- Template Strategy: `task-orchestrator://guidelines/template-strategy`
