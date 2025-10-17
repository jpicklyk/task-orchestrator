# add_template_section Tool - Detailed Documentation

## Overview

Adds a section definition to a template. Section definitions are blueprints that get instantiated as actual sections when the template is applied to tasks or features.

**Resource**: `task-orchestrator://docs/tools/add-template-section`

## Key Concepts

### Section Definitions vs Sections
- **Section Definition** (TemplateSection): Blueprint stored in template
  - Contains `contentSample` (example content)
  - Defines `title`, `usageDescription`, `ordinal`
  - Lives in template, reusable

- **Section** (Entity Section): Actual content in task/feature
  - Contains `content` (actual documentation)
  - Created when template is applied
  - Lives in task/feature, specific instance

### Section Structure
Every section definition includes:
- **title**: Section heading (becomes markdown H2 in output)
- **usageDescription**: Guidance for AI/users on section purpose
- **contentSample**: Example content (becomes initial content when applied)
- **ordinal**: Display order (0-based, lower numbers first)
- **contentFormat**: MARKDOWN, PLAIN_TEXT, JSON, or CODE
- **isRequired**: Whether section must be completed
- **tags**: Categorization for filtering

## Parameter Reference

### Required Parameters
- **templateId** (UUID): Template to add section to
- **title** (string): Section title
  - Examples: "Requirements", "Implementation Notes", "Testing Strategy"
  - Should be clear and descriptive
  - Becomes H2 heading in markdown output

- **usageDescription** (string): How section should be used
  - Guides AI agents on section purpose
  - Helps users understand what to document
  - Examples: "List all functional requirements", "Describe technical implementation approach"

- **contentSample** (string): Sample content for section
  - Becomes actual content when template is applied
  - Can include placeholders or instructions
  - Should demonstrate expected format

- **ordinal** (integer): Display order position (0-based)
  - Lower numbers appear first
  - Use gaps (0, 10, 20) for easier insertion later
  - Must be non-negative

### Optional Parameters
- **contentFormat** (enum, default: MARKDOWN): Content format
  - **MARKDOWN**: Formatted text (most common)
  - **PLAIN_TEXT**: Unformatted text
  - **JSON**: Structured data
  - **CODE**: Source code examples

- **isRequired** (boolean, default: false): Whether section is required
  - Required sections should be completed before task completion
  - Visual indicator for importance

- **tags** (string): Comma-separated tags
  - Examples: "requirements,functional", "implementation,backend"
  - Used for filtering and organization

## Usage Patterns

### Pattern 1: Basic Documentation Section
Add a simple markdown documentation section.

```json
{
  "templateId": "550e8400-e29b-41d4-a716-446655440000",
  "title": "Requirements",
  "usageDescription": "List all functional and non-functional requirements for this task",
  "contentSample": "### Functional Requirements\n- Requirement 1\n- Requirement 2\n\n### Non-Functional Requirements\n- Performance: Response time < 200ms\n- Security: All inputs validated",
  "ordinal": 0,
  "contentFormat": "MARKDOWN",
  "isRequired": true,
  "tags": "requirements,documentation"
}
```

**When to use**: Standard documentation sections with markdown formatting.

### Pattern 2: Code Example Section
Add a section with code examples.

```json
{
  "templateId": "template-uuid",
  "title": "Implementation Example",
  "usageDescription": "Example code showing how to implement the solution",
  "contentSample": "```kotlin\nfun authenticateUser(credentials: Credentials): Result<User> {\n    // Implementation here\n    return Result.Success(user)\n}\n```",
  "ordinal": 20,
  "contentFormat": "CODE",
  "isRequired": false,
  "tags": "implementation,code-sample"
}
```

**When to use**: Providing code examples or implementation templates.

### Pattern 3: Structured Data Section
Add a section with JSON configuration.

```json
{
  "templateId": "template-uuid",
  "title": "API Specification",
  "usageDescription": "OpenAPI specification for the endpoint",
  "contentSample": "{\n  \"endpoint\": \"/api/users\",\n  \"method\": \"POST\",\n  \"requestBody\": {\n    \"username\": \"string\",\n    \"email\": \"string\"\n  },\n  \"responses\": {\n    \"200\": \"User created successfully\"\n  }\n}",
  "ordinal": 10,
  "contentFormat": "JSON",
  "tags": "api,specification"
}
```

**When to use**: Structured specifications or configurations.

### Pattern 4: Checklist Section
Add a plain text checklist section.

```json
{
  "templateId": "template-uuid",
  "title": "Definition of Done",
  "usageDescription": "Checklist of completion criteria",
  "contentSample": "[ ] Code implemented and reviewed\n[ ] Unit tests written and passing\n[ ] Integration tests passing\n[ ] Documentation updated\n[ ] Deployed to staging",
  "ordinal": 50,
  "contentFormat": "PLAIN_TEXT",
  "isRequired": true,
  "tags": "checklist,completion"
}
```

**When to use**: Checklists or simple lists without formatting needs.

## Complete Workflow: Building a Template

### Step 1: Create Template
```javascript
const template = await create_template({
  name: "Database Migration Template",
  description: "Standard template for database schema changes",
  targetEntityType: "TASK",
  tags: "database,migration"
});

const templateId = template.data.id;
```

### Step 2: Add Sections in Order

#### Section 1: Overview (ordinal 0)
```javascript
await add_template_section({
  templateId: templateId,
  title: "Migration Overview",
  usageDescription: "High-level description of schema changes",
  contentSample: "### Changes\nDescribe what schema changes are being made and why.\n\n### Impact\nDescribe impact on existing data and applications.",
  ordinal: 0,
  contentFormat: "MARKDOWN",
  isRequired: true,
  tags: "overview,documentation"
});
```

#### Section 2: Forward Migration (ordinal 10)
```javascript
await add_template_section({
  templateId: templateId,
  title: "Forward Migration Script",
  usageDescription: "SQL script to apply schema changes",
  contentSample: "```sql\n-- Add your migration SQL here\nALTER TABLE users ADD COLUMN email VARCHAR(255) NOT NULL;\nCREATE INDEX idx_users_email ON users(email);\n```",
  ordinal: 10,
  contentFormat: "CODE",
  isRequired: true,
  tags: "migration,sql,forward"
});
```

#### Section 3: Rollback Script (ordinal 20)
```javascript
await add_template_section({
  templateId: templateId,
  title: "Rollback Script",
  usageDescription: "SQL script to revert schema changes",
  contentSample: "```sql\n-- Rollback SQL\nDROP INDEX idx_users_email;\nALTER TABLE users DROP COLUMN email;\n```",
  ordinal: 20,
  contentFormat: "CODE",
  isRequired: true,
  tags: "migration,sql,rollback"
});
```

#### Section 4: Testing Strategy (ordinal 30)
```javascript
await add_template_section({
  templateId: templateId,
  title: "Testing Strategy",
  usageDescription: "How to test the migration",
  contentSample: "### Pre-Migration Tests\n- Backup database\n- Verify current schema\n\n### Post-Migration Tests\n- Verify new columns exist\n- Check data integrity\n- Run application tests",
  ordinal: 30,
  contentFormat: "MARKDOWN",
  tags: "testing,validation"
});
```

### Step 3: Verify Template Structure
```javascript
const fullTemplate = await get_template({
  id: templateId,
  includeSections: true
});

console.log(`Template: ${fullTemplate.data.name}`);
console.log(`Sections: ${fullTemplate.data.sections.length}`);
fullTemplate.data.sections.forEach(section => {
  console.log(`  ${section.ordinal}: ${section.title}`);
});
```

## Ordinal Best Practices

### Ordinal Sequencing Strategies

#### Strategy 1: Sequential (0, 1, 2, 3...)
```javascript
// Simple sequential numbering
ordinal: 0  // First section
ordinal: 1  // Second section
ordinal: 2  // Third section
```

**Pros**: Simple, straightforward
**Cons**: Hard to insert sections later without renumbering

#### Strategy 2: Gaps (0, 10, 20, 30...)
```javascript
// Leave gaps for future insertion
ordinal: 0   // First section
ordinal: 10  // Second section (can insert 1-9 before this)
ordinal: 20  // Third section (can insert 11-19 before this)
```

**Pros**: Easy to insert sections without renumbering
**Cons**: Slightly less intuitive

**RECOMMENDED**: Use gap strategy for flexibility.

#### Strategy 3: Category-Based (0-99, 100-199, 200-299...)
```javascript
// Group sections by category
ordinal: 0    // Overview sections (0-99)
ordinal: 10
ordinal: 100  // Implementation sections (100-199)
ordinal: 110
ordinal: 200  // Testing sections (200-299)
ordinal: 210
```

**Pros**: Clear categorization, lots of insertion room
**Cons**: Large ordinal numbers

### Common Section Ordinal Patterns

**Standard Documentation Flow**:
```
0:  Context/Overview
10: Requirements
20: Technical Approach
30: Implementation Steps
40: Testing Strategy
50: Definition of Done
```

**Bug Investigation Flow**:
```
0:  Problem Description
10: Steps to Reproduce
20: Expected vs Actual Behavior
30: Investigation Notes
40: Root Cause Analysis
50: Solution Approach
60: Verification Steps
```

**Architecture Flow**:
```
0:  Architecture Overview
10: Design Principles
20: Component Diagram
30: Data Flow
40: Integration Points
50: Security Considerations
60: Performance Considerations
```

## Writing Content Samples

### Markdown Content Samples

**CRITICAL - Section Title Handling**:
- The `title` field becomes the section heading (## H2)
- **DO NOT** duplicate the title as a heading in `contentSample`
- Content should start directly with information

**Example - WRONG (❌)**:
```json
{
  "title": "Requirements",
  "contentSample": "## Requirements\n\n- Requirement 1"
}
```
*Problem: Creates duplicate heading*

**Example - CORRECT (✅)**:
```json
{
  "title": "Requirements",
  "contentSample": "- Requirement 1\n- Requirement 2"
}
```
*Correct: Title provides heading, content starts directly*

**With Subsections (✅)**:
```json
{
  "title": "Requirements",
  "contentSample": "### Functional Requirements\n- Requirement 1\n\n### Non-Functional Requirements\n- Performance requirements"
}
```
*Correct: Uses H3 for subsections, not H2*

### Markdown Formatting Tips
```markdown
- Use subsections: ### Subsection (H3 or lower)
- Use lists: - Item or 1. Numbered
- Use emphasis: **bold** or *italic*
- Use code: `inline` or ```kotlin block```
- Use links: [text](url)
```

### Placeholder Patterns
```markdown
### Good Placeholders
- [Insert specific requirements here]
- TODO: Add implementation details
- Describe the [component name] architecture

### Bad Placeholders
- ???
- Fill this in
- TBD
```

## Error Handling

### Template Not Found
```json
{
  "success": false,
  "message": "Template not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No template exists with ID ..."
  }
}
```

**Solution**: Verify template ID with `get_template`.

### Protected Template
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Cannot add section to protected template"
  }
}
```

**Solution**: Protected templates cannot have sections added. Create a new template or unprotect the existing one.

### Invalid Ordinal
```json
{
  "success": false,
  "message": "Ordinal must be a non-negative integer",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Solution**: Use ordinal >= 0.

## Common Mistakes to Avoid

### ❌ Mistake 1: Duplicating Title in Content
```json
{
  "title": "Implementation Steps",
  "contentSample": "## Implementation Steps\n\n1. Step one"
}
```

### ✅ Solution: Content Starts Directly
```json
{
  "title": "Implementation Steps",
  "contentSample": "1. Step one\n2. Step two"
}
```

### ❌ Mistake 2: No Ordinal Gaps
```json
// Sections at ordinals: 0, 1, 2, 3, 4
// Later: Need to insert section between 1 and 2
// Must renumber all sections 2+ to 3+
```

### ✅ Solution: Use Ordinal Gaps
```json
// Sections at ordinals: 0, 10, 20, 30, 40
// Later: Insert section at ordinal 15 (no renumbering needed)
```

### ❌ Mistake 3: Empty Content Sample
```json
{
  "contentSample": ""
}
```

### ✅ Solution: Provide Meaningful Sample
```json
{
  "contentSample": "### Requirements\n- Add functional requirements here\n- Include acceptance criteria"
}
```

### ❌ Mistake 4: Wrong Content Format
```json
{
  "contentSample": "```kotlin\nfun example() { }\n```",
  "contentFormat": "MARKDOWN"  // Should be CODE
}
```

### ✅ Solution: Match Format to Content
```json
{
  "contentSample": "fun example() { }",
  "contentFormat": "CODE"
}
```

## Best Practices

1. **Use Ordinal Gaps**: Leave room for future insertion (0, 10, 20...)
2. **Provide Clear Usage Descriptions**: Help AI/users understand section purpose
3. **Write Meaningful Content Samples**: Show expected format and structure
4. **Match Format to Content**: Use appropriate contentFormat enum
5. **Mark Critical Sections Required**: Use isRequired for essential sections
6. **Tag Consistently**: Follow project tagging conventions
7. **No Duplicate Headings**: Title becomes heading, content starts directly
8. **Plan Section Order**: Think through complete documentation flow

## Related Tools

- **create_template**: Create template before adding sections
- **get_template**: View template with sections
- **apply_template**: Apply template to tasks/features
- **update_template_metadata**: Update template properties
- **delete_template**: Remove template and its sections

## See Also

- Template Creation: `task-orchestrator://docs/tools/create-template`
- Template Application: `task-orchestrator://docs/tools/apply-template`
- Section Management: `task-orchestrator://guidelines/section-patterns`
