# Tool Description Guidelines

**Purpose:** Standardized approach for writing concise, efficient MCP tool descriptions
**Target:** 400-600 tokens per tool (vs 1,400 average currently)
**Reduction:** 70% token savings while maintaining clarity

---

## Core Principle

**Tool descriptions are reference documentation, not tutorials.**

Move comprehensive guides, examples, and best practices to MCP Resources for on-demand loading.

---

## Target Structure (400-600 tokens)

### 1. Purpose (50-100 tokens)
**Single paragraph** explaining what the tool does and when to use it.

**Formula:**
```
[Action verb] [what] with [key capability]. [Primary use case]. [Important constraint or note].
```

### 2. Key Features (50-100 tokens)
**Bullet list** of 3-5 core capabilities. No explanations, just features.

**Format:**
```
- Feature 1
- Feature 2
- Feature 3
```

### 3. Parameters (100-200 tokens)
**Simple table** of required and important optional parameters only.

**Format:**
```
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| id | UUID | Yes | Entity identifier |
| name | string | No | Display name |
```

### 4. Usage Notes (100-150 tokens)
**2-4 bullet points** covering critical information only:
- Efficiency tips
- Common pitfalls
- Important relationships

### 5. Related Tools (50 tokens)
**Simple list** of tool names only.

**Format:**
```
Related: get_task, update_task, delete_task
```

---

## What to REMOVE

### ❌ Remove: Multi-Section Structure
**Before:**
```kotlin
override val description = """
## Purpose
Long explanation...

## When to Use
Multiple paragraphs...

## Common Patterns
More paragraphs...

## Best Practices
Even more content...
"""
```

**After:**
```kotlin
override val description = """
Creates multiple sections in a single operation. More efficient than multiple add_section calls
for batch creation. Use for initial section sets (3+ sections) or template-based content.

Key features:
- Atomic transaction (all succeed or fail)
- Single network round-trip
- Maintains section ordering

Parameters:
| Field | Type | Required | Description |
| sections | array | Yes | Section objects to create |

Each section requires: entityType, entityId, title, content, ordinal.

Usage: Prefer for bulk operations. Use add_section only when adding single sections with
substantial content requiring individual validation.

Related: add_section, get_sections, bulk_update_sections
"""
```

### ❌ Remove: JSON Examples
**Don't include:**
- Full JSON request/response examples
- Multiple use case examples
- Efficient vs inefficient comparisons

**Why:** These belong in MCP Resources, not tool metadata.

### ❌ Remove: Token Savings Calculations
**Don't include:**
- "This saves X tokens compared to Y"
- Efficiency comparisons with numbers
- Performance benchmarks

**Why:** Implementation details, not tool description.

### ❌ Remove: Workflow Instructions
**Don't include:**
- Step-by-step processes
- Decision trees
- Multi-paragraph usage patterns

**Why:** These are user guides, not tool descriptions.

### ❌ Remove: Best Practices Sections
**Don't include:**
- "Best Practices" headers
- "Common Patterns" sections
- "Integration with Other Tools" essays

**Why:** Reference material, not tool metadata.

---

## What to KEEP

### ✅ Keep: Core Functionality
One-sentence description of what the tool does.

### ✅ Keep: Critical Parameters
Table or list of required and key optional parameters.

### ✅ Keep: Essential Warnings
Important constraints or common mistakes (1-2 sentences max).

### ✅ Keep: Tool Relationships
Simple list of related tools for navigation.

---

## Before/After Examples

### Example 1: BulkCreateSectionsTool

**Before (3,053 tokens):**
```kotlin
override val description = """Creates multiple sections in a single operation.

## Purpose

This tool efficiently creates multiple sections for tasks, features, or projects in a single operation.
PREFERRED over multiple `add_section` calls for efficiency and performance.

## When to Use bulk_create_sections

**ALWAYS PREFER** for:
- Creating initial section sets for new tasks/features (3+ sections)
- Adding template-like section structures
- Sections with shorter content (< 500 characters each)
- Any scenario requiring multiple sections for the same entity

**Performance Benefits**:
- Single database transaction (atomic operation)
- Reduced network overhead (one API call vs multiple)
- Faster execution for multiple sections
- Better error handling (all succeed or fail together)

## Common Section Creation Patterns

**Standard Task Documentation Set**:
[50+ lines of JSON examples]

## Section Organization Best Practices

**Ordinal Sequencing**:
- Start with ordinal 0 for the first section
[Multiple paragraphs of guidance]

[... continues for 12,000+ characters]
"""
```

**After (500 tokens):**
```kotlin
override val description = """Creates multiple sections in a single operation. More efficient
than multiple add_section calls due to atomic transaction and single network round-trip.

Key features:
- Atomic operation (all sections succeed or all fail)
- Single database transaction
- Maintains section ordering via ordinal field
- Supports all content formats (MARKDOWN, PLAIN_TEXT, JSON, CODE)

Parameters:
| Field | Type | Required | Description |
| sections | array | Yes | Array of section objects to create |

Each section object requires:
- entityType: TASK, FEATURE, or PROJECT
- entityId: UUID of parent entity
- title: Section heading
- usageDescription: Purpose for AI/users
- content: Section content
- ordinal: Display order (0-based)
- contentFormat: Format type (default: MARKDOWN)

Usage notes:
- Use for initial section sets (3+ sections) or template-based creation
- For single sections, use add_section
- All sections must belong to the same entity
- Operation fails if any section validation fails

Related: add_section, get_sections, bulk_update_sections, bulk_delete_sections
"""
```

**Reduction:** 3,053 → 500 tokens (84% reduction)

---

### Example 2: CreateTaskTool

**Before (929 tokens):**
```kotlin
override val description = """Creates a new task with required and optional fields.

## Purpose
Tasks are the primary work items in the system and can be organized into Features.
Each task has basic metadata (title, status, etc.) and can have Sections for detailed content.

## Template Integration
RECOMMENDED: Apply templates at creation time using templateIds parameter for consistent
documentation structure. Use `list_templates` first to find appropriate templates.

Template application creates standardized sections automatically:
- Use single-item array for one template: ["template-uuid"]
- Use multiple templates for comprehensive coverage: ["uuid1", "uuid2"]
- Templates are applied in order, with later templates' sections appearing after earlier ones

## Best Practices
- Use descriptive titles that clearly indicate the work to be done
- Write comprehensive summaries with acceptance criteria when helpful
[... multiple paragraphs continue]
"""
```

**After (400 tokens):**
```kotlin
override val description = """Creates a new task with metadata and optional template application.

Parameters:
| Field | Type | Required | Default | Description |
| title | string | Yes | - | Task title |
| summary | string | No | - | Brief summary (max 500 chars) |
| status | enum | No | pending | Task status |
| priority | enum | No | medium | Priority level |
| complexity | integer | No | 5 | Complexity rating (1-10) |
| featureId | UUID | No | - | Parent feature |
| templateIds | array | No | - | Templates to apply |
| tags | string | No | - | Comma-separated tags |

Status values: pending, in-progress, completed, cancelled, deferred
Priority values: high, medium, low

Usage notes:
- Apply templates during creation for structured documentation (use list_templates to discover)
- Summary limited to 500 characters
- Tasks can be independent or associated with features

Related: update_task, get_task, delete_task, search_tasks, list_templates
"""
```

**Reduction:** 929 → 400 tokens (57% reduction)

---

### Example 3: GetSectionsTool

**Before (1,671 tokens):**
```kotlin
override val description = """Retrieves sections for a task, feature, or project.

## Purpose

Sections contain detailed content for tasks, features, and projects in a structured format.
Each section has a specific purpose, content format, and ordering position. This tool
allows retrieving all sections for a specified entity.

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
[... extensive parameter table with descriptions]

## Response Format

### Success Response

```json
{
  "success": true,
  "message": "Sections retrieved successfully",
  [... 50+ lines of example JSON]
}
```

[... continues with error responses, usage examples, etc.]
"""
```

**After (450 tokens):**
```kotlin
override val description = """Retrieves sections for a task, feature, or project with optional filtering.

Parameters:
| Field | Type | Required | Default | Description |
| entityType | enum | Yes | - | TASK, FEATURE, or PROJECT |
| entityId | UUID | Yes | - | Entity identifier |
| includeContent | boolean | No | true | Include section content (false for metadata only, 85-99% token savings) |
| sectionIds | array | No | all | Specific section IDs to retrieve |
| tags | string | No | - | Filter by tags (comma-separated, returns sections with ANY tag) |

Returns sections ordered by ordinal field (0-based). Each section includes: id, title,
usageDescription, contentFormat, ordinal, tags, timestamps.

Usage notes:
- Set includeContent=false to browse structure without content (saves 85-99% tokens)
- Use sectionIds for selective loading after browsing metadata
- Tag filtering enables agent-specific content queries

Related: add_section, update_section, delete_section, bulk_create_sections
"""
```

**Reduction:** 1,671 → 450 tokens (73% reduction)

---

## MCP Resource Strategy

For removed content, create MCP Resources at:

```
task-orchestrator://docs/tools/{tool-name}
```

**Example resources:**
- `task-orchestrator://docs/tools/bulk-create-sections` - Full guide with examples
- `task-orchestrator://docs/tools/create-task` - Template selection guide
- `task-orchestrator://examples/section-patterns` - JSON examples

**Reference in descriptions:**
```kotlin
override val description = """
[Concise description]

For detailed examples and patterns, see: task-orchestrator://docs/tools/bulk-create-sections
"""
```

---

## Validation Checklist

Before submitting tool description changes:

### Length Check
- [ ] Description is 400-600 tokens (use online counter or `wc -w`)
- [ ] No section exceeds the recommended token allocation
- [ ] Entire description fits in single screen view

### Content Check
- [ ] Has single-paragraph purpose statement (50-100 tokens)
- [ ] Has bullet list of 3-5 key features (50-100 tokens)
- [ ] Has parameter table with essentials only (100-200 tokens)
- [ ] Has 2-4 usage notes (100-150 tokens)
- [ ] Has related tools list (50 tokens)

### Removal Check
- [ ] No JSON examples
- [ ] No token savings calculations
- [ ] No best practices sections
- [ ] No workflow instructions
- [ ] No multi-paragraph explanations
- [ ] No "## Headers" (use simple formatting)

### Quality Check
- [ ] Purpose is clear and specific
- [ ] Parameters cover required and important optional fields
- [ ] Usage notes focus on critical information only
- [ ] Related tools are accurate
- [ ] No redundant or repetitive content

### MCP Resource Check
- [ ] Detailed examples moved to MCP Resources (if applicable)
- [ ] Resource URI documented (if created)
- [ ] Description references resource (if applicable)

---

## Special Cases

### Simple CRUD Operations
For basic CRUD tools (create, read, update, delete), aim for **300-400 tokens**:
- One sentence purpose
- Parameter table
- 1-2 usage notes
- Related tools

**Example:**
```kotlin
override val description = """Deletes a task by ID with optional cascade.

Parameters:
| Field | Type | Required | Default |
| id | UUID | Yes | - |
| cascade | boolean | No | false |
| force | boolean | No | false |

Cascade deletes associated sections. Force removes even with dependencies.

Related: create_task, update_task, get_task
"""
```

### Complex Operations
For complex tools (bulk operations, specialized queries), aim for **500-600 tokens**:
- Purpose with key benefit
- Feature list
- Parameter table
- 3-4 usage notes
- Related tools

### Specialized Tools
For unique tools (agent recommendations, tag operations), provide context but stay concise:
- What makes it special (1-2 sentences)
- Key capabilities
- When to use (1 sentence)
- Parameters
- Related tools

---

## Implementation Guide

### Step 1: Identify Current Pattern
Read existing description and categorize verbosity:
- Multi-section structure? → Collapse to single flow
- JSON examples? → Remove or move to MCP Resource
- Best practices? → Condense to 1-2 critical notes
- Workflow instructions? → Remove

### Step 2: Extract Core Information
Answer these questions:
1. What does the tool do? (1 sentence)
2. What are the 3-5 key features?
3. What parameters are essential?
4. What 2-3 things must users know?
5. What tools are related?

### Step 3: Write Concise Version
Follow the target structure:
1. Purpose paragraph
2. Feature bullets
3. Parameter table
4. Usage notes
5. Related tools

### Step 4: Validate
Run through checklist above.

### Step 5: Create MCP Resource (if needed)
If removed substantial guidance:
1. Create resource file in `docs/tools/`
2. Add comprehensive examples
3. Reference in description

---

## Token Estimation

**Quick formula:** Characters ÷ 4 ≈ Tokens

**Target ranges:**
- Simple CRUD: 1,200-1,600 characters (300-400 tokens)
- Standard tools: 1,600-2,400 characters (400-600 tokens)
- Complex tools: 2,000-2,400 characters (500-600 tokens)

**Never exceed:** 2,800 characters (700 tokens)

---

## Examples by Category

### CRUD Operations (300-400 tokens)
- create_task, update_task, delete_task
- create_feature, update_feature, delete_feature
- create_project, update_project, delete_project

### Bulk Operations (500-600 tokens)
- bulk_create_sections
- bulk_update_sections
- bulk_update_tasks

### Query Operations (400-500 tokens)
- search_tasks
- search_features
- get_sections

### Specialized Operations (500-600 tokens)
- recommend_agent
- apply_template
- rename_tag

---

## Common Mistakes

### ❌ Too Much Context
```kotlin
// BAD: 200 tokens just for purpose
override val description = """
## Purpose

Tasks are the fundamental work unit in the orchestration system. They represent
discrete pieces of work that can be tracked, prioritized, and managed independently
or as part of larger features. The task creation tool provides a flexible interface
for creating tasks with comprehensive metadata including status, priority, complexity
ratings, and tag-based categorization. Tasks can be associated with features for
hierarchical organization or remain independent for ad-hoc work items.
[... continues]
"""
```

```kotlin
// GOOD: 30 tokens for purpose
override val description = """
Creates a task with metadata and optional template application. Tasks represent
discrete work items that can be independent or organized under features.
[... continues concisely]
"""
```

### ❌ JSON Examples in Description
```kotlin
// BAD: 300 tokens of JSON
Example:
```json
{
  "title": "Implement authentication",
  "summary": "Add OAuth 2.0 support...",
  [... 50 lines of example JSON]
}
```
```

```kotlin
// GOOD: Reference to examples
For detailed examples, see: task-orchestrator://docs/tools/create-task
```

### ❌ Efficiency Comparisons
```kotlin
// BAD: 100 tokens explaining savings
Using this tool instead of multiple calls saves significant tokens:
- Individual calls: 2,500 tokens
- Bulk operation: 350 tokens
- Savings: 86% (2,150 tokens)
```

```kotlin
// GOOD: Simple statement
More efficient than multiple individual calls due to atomic transaction.
```

---

## Review Process

### Self-Review
1. Read description as if you've never seen the tool
2. Ask: "Can I use this tool with just this description?"
3. Check token count
4. Verify checklist items

### Peer Review
When reviewing PRs with description changes:
1. Verify 400-600 token target
2. Check for removed verbosity patterns
3. Confirm critical information retained
4. Validate MCP Resource creation (if needed)

---

## Maintenance

**When to update descriptions:**
- New parameters added → Update parameter table
- Functionality changed → Update purpose statement
- Related tools changed → Update related tools list
- Breaking changes → Add warning note

**What NOT to do:**
- Don't add back removed examples
- Don't expand usage notes
- Don't add workflow instructions
- Don't exceed 600 tokens

---

**Summary:** Tool descriptions should be **concise reference documentation**, not comprehensive guides. Focus on enabling tool use, not teaching best practices. Move extensive content to MCP Resources for on-demand access.
