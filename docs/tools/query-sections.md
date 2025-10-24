# query_sections Tool - Detailed Documentation

## Overview

The `query_sections` tool provides unified read-only section queries with comprehensive filtering capabilities. It retrieves sections for any entity type (projects, features, tasks) with optional content inclusion, selective loading by ID, and tag-based filtering.

**Key Feature (v2.0+):** The `includeContent` parameter provides significant token savings (85-99% reduction) by returning metadata-only when full section content is not needed.

**Resource**: `task-orchestrator://docs/tools/query-sections`

## Key Concepts

### Unified Section Query Interface

Query sections across all entity types with consistent parameters:

- **Projects** - Top-level organization containers
- **Features** - Groups of related tasks within projects
- **Tasks** - Individual work items within features

### Token Efficiency Philosophy

v2.0 `query_sections` emphasizes **token efficiency** through selective content loading:

- **Metadata-only queries**: 85-99% token reduction (100 tokens vs 5,000-10,000 with content)
- **Selective loading**: Query metadata first, then fetch specific sections by ID
- **Tag filtering**: Retrieve only sections relevant to specific agents or workflows

### Four Filtering Strategies

1. **Content Control** - `includeContent` parameter (true/false)
2. **Selective IDs** - `sectionIds` parameter (fetch specific sections only)
3. **Tag Filtering** - `tags` parameter (returns sections with ANY matching tag)
4. **Entity Filtering** - `entityType` and `entityId` (scope to specific entity)

## Parameter Reference

### Required Parameters

| Parameter    | Type   | Description                                  |
|--------------|--------|----------------------------------------------|
| `entityType` | enum   | Entity type: PROJECT, FEATURE, or TASK       |
| `entityId`   | UUID   | Entity identifier                            |

### Optional Parameters

| Parameter       | Type           | Default | Description                                                |
|-----------------|----------------|---------|-------------------------------------------------------------|
| `includeContent` | boolean       | true    | Include section content (false saves 85-99% tokens)        |
| `sectionIds`    | array (UUID)   | null    | Filter to specific section IDs only                        |
| `tags`          | string         | null    | Comma-separated tags (returns sections with ANY tag match) |

### Response Structure

Each section in response includes:

- `id` - Section UUID
- `title` - Section title
- `usageDescription` - Purpose of section
- `contentFormat` - Format: markdown, plain_text, json, or code
- `ordinal` - Display order (0-based)
- `tags` - Array of tags for categorization
- `createdAt` - ISO timestamp when created
- `modifiedAt` - ISO timestamp when last modified
- `content` - Section content (only if `includeContent=true`)

---

## Quick Start

### Basic Query - Metadata Only (Token Efficient)

```json
{
  "entityType": "TASK",
  "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "includeContent": false
}
```

**Response**:

```json
{
  "success": true,
  "message": "Retrieved 5 sections",
  "data": {
    "sections": [
      {
        "id": "section-uuid-1",
        "title": "Requirements",
        "usageDescription": "Functional and non-functional requirements",
        "contentFormat": "markdown",
        "ordinal": 0,
        "tags": ["backend", "api"],
        "createdAt": "2025-10-24T10:00:00Z",
        "modifiedAt": "2025-10-24T14:30:00Z"
      },
      {
        "id": "section-uuid-2",
        "title": "Implementation Plan",
        "usageDescription": "Step-by-step implementation approach",
        "contentFormat": "markdown",
        "ordinal": 10,
        "tags": ["implementation"],
        "createdAt": "2025-10-24T10:15:00Z",
        "modifiedAt": "2025-10-24T14:30:00Z"
      }
    ],
    "entityType": "TASK",
    "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
    "count": 5
  }
}
```

**Token Cost**: ~100-150 tokens

### Full Content Query

```json
{
  "entityType": "TASK",
  "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "includeContent": true
}
```

**Response** includes `content` field in each section (5,000-10,000 tokens depending on content size)

### Selective Section Loading

```json
{
  "entityType": "TASK",
  "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "sectionIds": [
    "section-uuid-1",
    "section-uuid-3"
  ],
  "includeContent": true
}
```

**Response**: Only sections 1 and 3 with content (~1,500-2,000 tokens)

### Tag-Filtered Query

```json
{
  "entityType": "TASK",
  "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "tags": "backend,api",
  "includeContent": false
}
```

**Response**: Only sections tagged with "backend" OR "api" (metadata only)

---

## Parameter Details

### entityType (Required)

Must be one of:

- `PROJECT` - Retrieve sections for a project
- `FEATURE` - Retrieve sections for a feature
- `TASK` - Retrieve sections for a task

### entityId (Required)

Must be a valid UUID of the entity.

**Examples**:
- Project: `b160fbdb-07e4-42d7-8c61-8deac7d2fc17`
- Feature: `f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c`
- Task: `a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d`

### includeContent (Optional, Default: true)

**Token Efficiency Impact**:

- `true` - Include full `content` field (~5-10KB per section)
- `false` - Omit content field, only metadata (~200-300 bytes per section)

**Token Savings Example**:

```
Feature with 10 sections, 3KB each:
- includeContent=true:  ~5,000-8,000 tokens
- includeContent=false: ~100-150 tokens
- Savings: 97-98% reduction!
```

### sectionIds (Optional)

Array of section UUIDs to retrieve. If provided, only these sections are returned (other sections ignored).

**Usage Patterns**:

1. **Selective loading**: Query metadata first (includeContent=false), then fetch specific sections by ID (includeContent=true)
2. **Partial content retrieval**: Avoid loading all sections when you only need a few

**Example - Two-Step Pattern**:

```json
// Step 1: Get all section metadata
{
  "entityType": "TASK",
  "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "includeContent": false
}
// Response: 100 tokens, shows all section titles and IDs

// Step 2: Load only required sections with content
{
  "entityType": "TASK",
  "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "sectionIds": [
    "section-uuid-1",
    "section-uuid-5"
  ],
  "includeContent": true
}
// Response: 800 tokens, only sections 1 and 5 with content
```

### tags (Optional)

Comma-separated tags to filter sections. Returns sections matching ANY tag (OR logic).

**Filtering Logic**:

- `tags="backend,api"` - Returns sections with "backend" OR "api" OR both tags
- Case-insensitive matching
- Whitespace trimmed automatically
- Empty or whitespace-only tags ignored

**Use Cases**:

1. **Agent-specific content**: Retrieve only sections relevant to a specific specialist
   - Backend engineer: `tags="backend,api,server"`
   - Frontend engineer: `tags="frontend,ui,component"`

2. **Workflow-specific content**: Get sections for specific workflow phases
   - Planning: `tags="requirements,planning"`
   - Implementation: `tags="implementation,coding"`

3. **Review-focused content**: Get only sections needing review
   - `tags="needs-review,in-progress"`

**Example - Backend Engineer Sections**:

```json
{
  "entityType": "FEATURE",
  "entityId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "tags": "backend,api,database,performance",
  "includeContent": false
}
```

---

## Advanced Usage

### Token Optimization Strategies

**Strategy 1: Metadata-First Approach**

```json
// Query 1: Get all section metadata (minimal tokens)
{
  "entityType": "TASK",
  "entityId": "task-id",
  "includeContent": false
}
// Cost: ~100 tokens

// Query 2: Fetch only needed sections with content
{
  "entityType": "TASK",
  "entityId": "task-id",
  "sectionIds": ["section-1", "section-3"],
  "includeContent": true
}
// Cost: ~500 tokens

// Total: ~600 tokens vs 8,000 for single "includeContent=true"
```

**Strategy 2: Tag-Based Filtering**

```json
{
  "entityType": "FEATURE",
  "entityId": "feature-id",
  "tags": "requirements,acceptance-criteria",
  "includeContent": true
}
// Load only documentation sections, skip implementation details
// Typical cost: ~1,500 tokens instead of 8,000
```

**Strategy 3: Combined Filtering**

```json
{
  "entityType": "TASK",
  "entityId": "task-id",
  "sectionIds": ["section-1", "section-5"],
  "tags": "backend",
  "includeContent": true
}
// Retrieve only section 1 or 5 that also have "backend" tag with content
// Most precise filtering, lowest token cost
```

### Usage Patterns

#### Pattern 1: Browse Then Load

```
1. Use includeContent=false to see what sections exist
   → Shows all section titles, descriptions, tags, formats
   → Very cheap: ~100-150 tokens

2. Decide which sections you need based on titles/tags

3. Use sectionIds with includeContent=true to load specific content
   → Load only what you need
   → Saves 85-99% tokens vs loading everything
```

#### Pattern 2: Workflow-Specific Content

```
For Planning Specialist:
- Query: tags="requirements,planning,acceptance-criteria"
- Gets only sections relevant to planning phase

For Backend Engineer:
- Query: tags="backend,api,database,performance"
- Gets only backend-specific sections

For QA Engineer:
- Query: tags="testing,acceptance-criteria,edge-cases"
- Gets only testing-focused sections
```

#### Pattern 3: Selective Section Management

```
1. Get all sections (metadata only)
   → Identify which sections to modify

2. Query specific sections (with content)
   → Read content before making changes

3. Use manage_sections to update only identified sections
   → More targeted, efficient modifications
```

### Integration with manage_sections

**Read-Before-Write Pattern**:

```json
// Step 1: Query existing sections
{
  "entityType": "TASK",
  "entityId": "task-id",
  "includeContent": false
}
// Identify which sections exist

// Step 2: Load specific section content
{
  "entityType": "TASK",
  "entityId": "task-id",
  "sectionIds": ["section-1"],
  "includeContent": true
}
// Read current content

// Step 3: Update with manage_sections
{
  "operation": "updateText",
  "id": "section-1",
  "oldText": "old content",
  "newText": "new content"
}
// Make targeted modifications
```

---

## Error Handling

| Error Code | Condition | Solution |
|-----------|-----------|----------|
| VALIDATION_ERROR | Missing entityType | Provide entityType: PROJECT, FEATURE, or TASK |
| VALIDATION_ERROR | Missing entityId | Provide entityId as valid UUID |
| VALIDATION_ERROR | Invalid entityType value | Use: PROJECT, FEATURE, or TASK (case-sensitive) |
| VALIDATION_ERROR | Invalid UUID format | Ensure entityId and sectionIds are valid UUIDs |
| VALIDATION_ERROR | Invalid section ID in array | All sectionIds must be valid UUIDs |
| RESOURCE_NOT_FOUND | Entity doesn't exist | Verify entityId exists and is correct |
| DATABASE_ERROR | Database connection issue | Retry operation, contact support if persists |
| INTERNAL_ERROR | Unexpected server error | Check logs, retry operation |

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

**Scenario 1: Entity Not Found**

```json
{
  "entityType": "TASK",
  "entityId": "00000000-0000-0000-0000-000000000000"
}
```

Response:

```json
{
  "success": false,
  "message": "Task not found with ID: 00000000-0000-0000-0000-000000000000",
  "error": {
    "code": "RESOURCE_NOT_FOUND"
  }
}
```

**Scenario 2: Invalid Entity Type**

```json
{
  "entityType": "SECTION",
  "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
}
```

Response:

```json
{
  "success": false,
  "message": "Invalid entityType: SECTION. Must be one of: PROJECT, FEATURE, TASK",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Scenario 3: No Sections Found (Success)**

```json
{
  "success": true,
  "message": "No sections found for task",
  "data": {
    "sections": [],
    "count": 0,
    "entityType": "TASK",
    "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
  }
}
```

---

## Integration Patterns

### Pattern 1: With query_container for Hierarchical View

```
// Get entity overview (metadata about entity + task/feature counts)
query_container(operation="overview", containerType="task", id="task-id")
↓
// Shows task metadata + child task counts

// Then query sections if detailed documentation needed
query_sections(entityType="TASK", entityId="task-id", includeContent=false)
↓
// Shows section structure without loading all content
```

**Token Efficiency**:
- Step 1: ~1,200 tokens (entity overview)
- Step 2: ~100 tokens (section metadata)
- Total: ~1,300 tokens for complete understanding
- vs. ~25,000 tokens for get with all sections

### Pattern 2: With manage_sections for Content Updates

```
// Step 1: Query sections to identify targets
query_sections(entityType="TASK", entityId="task-id", includeContent=false)
↓
// Identifies which sections exist

// Step 2: Load specific sections
query_sections(
  entityType="TASK",
  entityId="task-id",
  sectionIds=["section-1"],
  includeContent=true
)
↓
// Gets content for targeted section

// Step 3: Update using manage_sections
manage_sections(
  operation="updateText",
  id="section-1",
  oldText="...",
  newText="..."
)
↓
// Modifies only needed sections efficiently
```

### Pattern 3: Specialist Agent Queries

**Backend Engineer Specialist**:
```json
{
  "entityType": "FEATURE",
  "entityId": "feature-id",
  "tags": "backend,api,database,performance,security",
  "includeContent": true
}
// Load only backend-relevant sections
```

**Frontend Developer Specialist**:
```json
{
  "entityType": "FEATURE",
  "entityId": "feature-id",
  "tags": "frontend,ui,component,styling,usability",
  "includeContent": true
}
// Load only frontend-relevant sections
```

**QA/Test Engineer Specialist**:
```json
{
  "entityType": "TASK",
  "entityId": "task-id",
  "tags": "testing,acceptance-criteria,edge-cases,validation",
  "includeContent": true
}
// Load only testing-focused sections
```

---

## Use Cases

### Use Case 1: Efficient Documentation Review

**Scenario**: Developer wants to understand what a task requires without loading entire document.

**Steps**:

1. Query section metadata (find out what exists):

```json
{
  "entityType": "TASK",
  "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "includeContent": false
}
```

Response shows sections: Requirements, Implementation Plan, Testing Strategy, API Spec

2. Load only Requirements section:

```json
{
  "entityType": "TASK",
  "entityId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "sectionIds": [
    "550e8400-e29b-41d4-a716-446655440001"
  ],
  "includeContent": true
}
```

3. If more detail needed, load Implementation Plan next.

**Token Impact**: ~600 tokens total vs ~8,000 if loading all sections at once

### Use Case 2: Specialist-Specific Content Loading

**Scenario**: Backend Engineer needs only backend-relevant documentation.

```json
{
  "entityType": "FEATURE",
  "entityId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "tags": "backend,api,database,performance",
  "includeContent": true
}
```

Response includes sections on:
- API Specification
- Database Schema
- Performance Requirements
- Authentication Architecture

Excludes sections on:
- Frontend Components
- UI/UX Design
- Styling Guidelines

**Benefit**: 50% token savings by filtering out irrelevant sections

### Use Case 3: Content Modification with Context

**Scenario**: Need to update a section while reviewing related sections for context.

1. Browse all sections (metadata):

```json
{
  "entityType": "TASK",
  "entityId": "task-id",
  "includeContent": false
}
```

2. Load related sections for context:

```json
{
  "entityType": "TASK",
  "entityId": "task-id",
  "sectionIds": [
    "section-requirements-id",
    "section-implementation-id",
    "section-testing-id"
  ],
  "includeContent": true
}
```

3. Use manage_sections to update section-implementation-id with knowledge of requirements and testing constraints.

---

## Best Practices

### DO

✅ **Use `includeContent=false` first** - Understand section structure before loading content

✅ **Combine filters for efficiency** - Use sectionIds AND tags for precise results

✅ **Leverage tag filtering** - Use tags="backend" instead of loading all sections then filtering

✅ **Cache metadata queries** - Section titles/tags rarely change, expensive to reload

✅ **Query progressively** - Load metadata first, then content as needed (metadata-first pattern)

✅ **Use sectionIds for selective updates** - Query only sections you plan to modify

✅ **Batch related sections** - If loading 3+ sections, load all at once vs. individual requests

✅ **Document your tags** - Clear tag conventions (backend, frontend, testing, etc.) enable filtering

### DON'T

❌ **Don't always load content** - Default to metadata (includeContent=false) unless content required

❌ **Don't load all sections for filtering** - Use `tags` parameter instead of post-filtering results

❌ **Don't forget entity validation** - Verify entityId exists before complex queries

❌ **Don't mix unrelated filtering** - Tags are OR logic; combine with sectionIds for AND logic

❌ **Don't assume section structure** - Always query metadata first to understand what exists

❌ **Don't load same sections repeatedly** - Cache results for sections that don't change frequently

❌ **Don't query without entity verification** - Querying non-existent entity wastes tokens

---

## Response Format Details

### Success Response Structure

```json
{
  "success": true,
  "message": "Retrieved X sections",
  "data": {
    "sections": [
      {
        "id": "uuid",
        "title": "string",
        "usageDescription": "string",
        "contentFormat": "markdown|plain_text|json|code",
        "ordinal": 0,
        "tags": ["string"],
        "createdAt": "ISO-8601 timestamp",
        "modifiedAt": "ISO-8601 timestamp",
        "content": "string (only if includeContent=true)"
      }
    ],
    "entityType": "PROJECT|FEATURE|TASK",
    "entityId": "uuid",
    "count": number
  }
}
```

### Ordering

Sections are returned ordered by `ordinal` (display order, 0-based).

### Content Formats

- `markdown` - Markdown formatted content
- `plain_text` - Plain text content
- `json` - JSON structured content
- `code` - Code content (programming language-specific)

---

## Common Workflows

### Workflow 1: Task Analysis (Token Efficient)

```
Developer: "Show me what this task requires"

1. query_sections(
     entityType="TASK",
     entityId="task-id",
     includeContent=false
   )
   → See all sections, pick which to read (~100 tokens)

2. query_sections(
     entityType="TASK",
     entityId="task-id",
     sectionIds=["requirements-id"],
     includeContent=true
   )
   → Read requirements (~500 tokens)

3. If more detail needed, query additional sections
   → Each query is small, incremental (~500 tokens each)

Total: ~600-1,200 tokens
vs. ~8,000 for loading everything at once
```

### Workflow 2: Backend Engineer Setup

```
Engineer joins project, needs backend documentation

query_sections(
  entityType="FEATURE",
  entityId="feature-id",
  tags="backend,api,database,performance,security",
  includeContent=true
)
→ Get all backend-relevant sections in one call (~2,000 tokens)
→ Everything needed to start developing
→ Skips frontend, UI, UX sections (saves ~50% tokens)
```

### Workflow 3: Documentation Update

```
Writer: "Update this task's requirements section"

1. query_sections(
     entityType="TASK",
     entityId="task-id",
     includeContent=false
   )
   → Find requirements section ID (~100 tokens)

2. query_sections(
     entityType="TASK",
     entityId="task-id",
     sectionIds=["requirements-id"],
     includeContent=true
   )
   → Load current content for editing (~500 tokens)

3. manage_sections(
     operation="updateText",
     id="requirements-id",
     oldText="...",
     newText="..."
   )
   → Update requirements

Total: ~600 tokens, very focused
```

---

## Token Efficiency Examples

### Example 1: Large Feature (12 Sections, 2KB each)

**Scenario A: Load all with content**
```json
{
  "entityType": "FEATURE",
  "entityId": "feature-id",
  "includeContent": true
}
```
- Cost: ~8,000 tokens
- You get: All 12 sections with full content

**Scenario B: Metadata-first approach**
```json
// Query 1: Metadata only
{
  "entityType": "FEATURE",
  "entityId": "feature-id",
  "includeContent": false
}
```
- Cost: ~150 tokens
- You get: Section titles, descriptions, IDs

```json
// Query 2: Load 3 specific sections
{
  "entityType": "FEATURE",
  "entityId": "feature-id",
  "sectionIds": ["sec-1", "sec-3", "sec-7"],
  "includeContent": true
}
```
- Cost: ~1,500 tokens
- You get: Only sections 1, 3, 7 with content

**Total: ~1,650 tokens vs 8,000 = 79% savings**

### Example 2: Task with Mixed Content

**Scenario A: Tag-based filtering**
```json
{
  "entityType": "TASK",
  "entityId": "task-id",
  "tags": "backend,api",
  "includeContent": true
}
```
- Cost: ~2,000 tokens (if 4 sections match)
- vs. ~6,000 tokens loading all sections
- Savings: 67%

**Scenario B: Combined filtering**
```json
{
  "entityType": "TASK",
  "entityId": "task-id",
  "sectionIds": ["sec-1", "sec-3"],
  "tags": "backend",
  "includeContent": true
}
```
- Cost: ~800 tokens (if 1-2 sections match both filters)
- Most precise, lowest cost
- Savings: 87% vs loading all

---

## Related Tools

- **[manage_sections](manage-sections.md)** - Create, update, delete sections
- **[query_container](query-container.md)** - Query entities with optional sections
- **[get_next_status](get-next-status.md)** - Status progression recommendations
- **[query_container Scoped Overview](query-container.md#scoped-overview)** - Hierarchical view without sections

---

## Integration with Workflow Systems

### Skills Integration

**Documentation Implementation Skill** uses query_sections for:

1. Browsing section structure (metadata-only queries)
2. Loading specific sections for editing (selective loading)
3. Finding related sections by tag (tag filtering)
4. Verifying section completeness before publishing

### Subagent Integration

**Specialists use query_sections for**:

1. **Backend Engineer**: Query with `tags="backend,api,database,performance"`
2. **Frontend Developer**: Query with `tags="frontend,ui,component,styling"`
3. **QA Engineer**: Query with `tags="testing,acceptance-criteria,validation"`
4. **Technical Writer**: Query with `tags="documentation,user-guide,api-reference"`

Each specialist loads only relevant sections, reducing token overhead significantly.

---

## References

### Source Code

- **Tool Implementation**: `src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/section/QuerySectionsTool.kt`
- **Tests**: `src/test/kotlin/io/github/jpicklyk/mcptask/application/tools/section/QuerySectionsToolTest.kt`
- **Domain Model**: `src/main/kotlin/io/github/jpicklyk/mcptask/domain/model/Section.kt`
- **Repository**: `src/main/kotlin/io/github/jpicklyk/mcptask/domain/repository/SectionRepository.kt`

### Related Documentation

- **[manage_sections Tool](manage-sections.md)** - Write operations for sections
- **[query_container Tool](query-container.md)** - Read operations for containers (complementary tool)
- **[API Reference](../api-reference.md)** - Complete tool documentation index
- **[Quick Start Guide](../quick-start.md)** - Getting started with Task Orchestrator
- **[Workflow Automation](../workflow-prompts.md)** - Workflow integration examples

### Example Dataset

All examples use consistent IDs:

- **Project**: `b160fbdb-07e4-42d7-8c61-8deac7d2fc17` (MCP Task Orchestrator)
- **Feature**: `f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c` (Section Management API)
- **Task**: `a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d` (Document query-sections tool)

---

## Version History

- **v2.0.0** (2025-10-24): Comprehensive documentation for unified query_sections tool
- **v2.0.0-beta** (2025-10-19): query_sections tool release as part of v2.0 consolidation

---

## See Also

- [API Reference](../api-reference.md) - Complete tool documentation
- [manage_sections Tool](manage-sections.md) - Write operations (complementary tool)
- [query_container Tool](query-container.md) - Container queries (entity overview)
- [Quick Start Guide](../quick-start.md) - Common workflows
- [Workflow Prompts](../workflow-prompts.md) - Workflow automation integration
