# add_section Tool - Detailed Documentation

## Overview

Add structured content sections to tasks, features, or projects. Sections organize detailed information into logical blocks with specific formats and purposes.

**Resource**: `task-orchestrator://docs/tools/add-section`

## Key Concepts

### Why Sections?
Instead of storing all content directly in entities, sections provide:
- **Organized Structure**: Logical content blocks (Requirements, Implementation, Testing)
- **Format Flexibility**: Different formats per section (Markdown, JSON, Code, Plain Text)
- **Context Efficiency**: Load only sections needed, not everything
- **Ordinal Ordering**: Explicit ordering for logical flow

### Section vs Entity Content
- **Entity**: Lightweight metadata (title, summary, status)
- **Sections**: Detailed content blocks (requirements, technical details, implementation notes)

### Content Format Types
- **MARKDOWN**: Rich formatting with headers, lists, code blocks (default)
- **PLAIN_TEXT**: Unformatted plain text
- **JSON**: Structured data
- **CODE**: Source code and implementation snippets

## Parameter Reference

### Required Parameters
- **entityType** (enum): PROJECT | TASK | FEATURE
- **entityId** (UUID): ID of entity to attach section to
- **title** (string): Section title (becomes heading)
- **usageDescription** (string): How AI/users should use this section
- **content** (string): The actual content in specified format
- **ordinal** (integer): Order position (0-based, lower = earlier)

### Optional Parameters
- **contentFormat** (enum): MARKDOWN | PLAIN_TEXT | JSON | CODE (default: MARKDOWN)
- **tags** (string): Comma-separated tags for categorization

## Critical: Section Title Handling

**IMPORTANT**: The `title` field becomes the section heading. Do NOT duplicate it in content.

### ❌ WRONG (Duplicate Heading)
```json
{
  "title": "Requirements",
  "content": "## Requirements\n\n- Must support OAuth..."
}
```
**Problem**: Creates duplicate "Requirements" heading

### ✅ CORRECT (No Duplicate)
```json
{
  "title": "Requirements",
  "content": "- **Must** support OAuth 2.0\n- **Should** handle token refresh..."
}
```
**Result**: Title provides heading, content starts directly

### ✅ CORRECT (With Subsections)
```json
{
  "title": "Implementation Strategy",
  "content": "### Phase 1: Setup\n...\n\n### Phase 2: Development\n..."
}
```
**Result**: Use H3 (###) for subsections within content

## Common Usage Patterns

### Pattern 1: Requirements Section
Document what needs to be accomplished.

```json
{
  "entityType": "TASK",
  "entityId": "uuid",
  "title": "Requirements",
  "usageDescription": "Key requirements that implementation must satisfy",
  "content": "- **Must** support OAuth 2.0 with Google and GitHub\n- **Must** implement token refresh mechanism\n- **Should** handle expired tokens gracefully\n- **Should** support rate limiting\n\n### Acceptance Criteria\n- User can log in with Google/GitHub\n- Tokens refresh automatically\n- Expired tokens return clear error",
  "contentFormat": "MARKDOWN",
  "ordinal": 0,
  "tags": "requirements,specifications"
}
```

**When to use**: Defining what needs to be built

### Pattern 2: Implementation Notes
Technical details and guidance.

```json
{
  "entityType": "TASK",
  "entityId": "uuid",
  "title": "Implementation Notes",
  "usageDescription": "Technical approach and implementation guidance",
  "content": "### Libraries\nUse `passport.js` for OAuth integration.\n\n### Approach\n1. Configure OAuth providers\n2. Implement callback handlers\n3. Store tokens in Redis\n4. Implement refresh logic\n\n### Security Considerations\n- Store client secrets in environment variables\n- Use HTTPS for all OAuth redirects\n- Implement CSRF protection",
  "contentFormat": "MARKDOWN",
  "ordinal": 1,
  "tags": "implementation,technical"
}
```

**When to use**: Providing technical guidance for implementation

### Pattern 3: Code Examples
Show implementation examples.

```json
{
  "entityType": "TASK",
  "entityId": "uuid",
  "title": "OAuth Configuration Example",
  "usageDescription": "Code example for configuring OAuth providers",
  "content": "const passport = require('passport');\nconst GoogleStrategy = require('passport-google-oauth20');\n\npassport.use(new GoogleStrategy({\n  clientID: process.env.GOOGLE_CLIENT_ID,\n  clientSecret: process.env.GOOGLE_CLIENT_SECRET,\n  callbackURL: '/auth/google/callback'\n}, (accessToken, refreshToken, profile, done) => {\n  // Handle authentication\n}));",
  "contentFormat": "CODE",
  "ordinal": 2,
  "tags": "code-example,oauth"
}
```

**When to use**: Providing code samples

### Pattern 4: Testing Strategy
Define testing approach.

```json
{
  "entityType": "TASK",
  "entityId": "uuid",
  "title": "Testing Strategy",
  "usageDescription": "Testing approach and coverage requirements",
  "content": "### Unit Tests\n- Test OAuth provider configuration\n- Test token generation\n- Test token refresh logic\n- Test error handling\n\n### Integration Tests\n- Test complete OAuth flow\n- Test callback handling\n- Test token storage and retrieval\n\n### Manual Tests\n- [ ] Log in with Google\n- [ ] Log in with GitHub\n- [ ] Token refreshes automatically\n- [ ] Expired token handled correctly",
  "contentFormat": "MARKDOWN",
  "ordinal": 3,
  "tags": "testing,qa"
}
```

**When to use**: Defining testing requirements

### Pattern 5: Structured Data (JSON)
Store configuration or structured information.

```json
{
  "entityType": "TASK",
  "entityId": "uuid",
  "title": "API Endpoints",
  "usageDescription": "API endpoint specifications",
  "content": "{\"endpoints\": [{\"path\": \"/auth/google\", \"method\": \"GET\", \"description\": \"Initiate Google OAuth\"}, {\"path\": \"/auth/google/callback\", \"method\": \"GET\", \"description\": \"OAuth callback\"}]}",
  "contentFormat": "JSON",
  "ordinal": 4,
  "tags": "api,specifications"
}
```

**When to use**: Structured data that might be parsed programmatically

## Ordinal Sequencing Strategy

Ordinals determine section order. Best practices:

### Sequential Numbering (Standard)
```
0 - Requirements
1 - Technical Approach
2 - Implementation Notes
3 - Testing Strategy
4 - References
```

**Use when**: Straightforward sequential flow

### Gapped Numbering (Flexible)
```
0 - Requirements
10 - Technical Approach
20 - Implementation Notes
30 - Testing Strategy
40 - References
```

**Use when**: Anticipating insertions between sections

### Common Section Order
```
0 - Context/Background (why this matters)
1 - Requirements (what needs to be done)
2 - Technical Approach (how to do it)
3 - Implementation Notes (detailed guidance)
4 - Testing Strategy (validation approach)
5 - References (links, docs)
```

## Markdown Formatting Guide

### Use Subsection Headings (H3+)
```markdown
### Subsection Title
Content here

### Another Subsection
More content
```

### Use Lists
```markdown
- Unordered item
- Another item

1. Numbered item
2. Another item
```

### Use Emphasis
```markdown
**Bold text** for emphasis
*Italic text* for emphasis
`inline code` for technical terms
```

### Use Code Blocks
```markdown
\`\`\`javascript
function example() {
  return "code here";
}
\`\`\`
```

### Use Links
```markdown
[Link text](https://example.com)
See [OAuth spec](https://oauth.net/2/)
```

## Common Workflows

### Workflow 1: Add Section After Task Creation
```javascript
// Create task
const task = await create_task({
  title: "Implement OAuth authentication",
  summary: "Add OAuth login with Google and GitHub"
});

// Add detailed requirements section
await add_section({
  entityType: "TASK",
  entityId: task.data.id,
  title: "Requirements",
  usageDescription: "Functional and non-functional requirements",
  content: "- Must support Google OAuth\n- Must support GitHub OAuth...",
  contentFormat: "MARKDOWN",
  ordinal: 0,
  tags: "requirements"
});

// Add implementation notes
await add_section({
  entityType: "TASK",
  entityId: task.data.id,
  title: "Implementation Notes",
  usageDescription: "Technical guidance for implementation",
  content: "Use passport.js library...",
  contentFormat: "MARKDOWN",
  ordinal: 1,
  tags: "implementation"
});
```

### Workflow 2: Supplement Template Sections
```javascript
// Create task with templates
const task = await create_task({
  title: "Implement payment processing",
  templateIds: ["impl-workflow-uuid"]  // Creates base sections
});

// Add project-specific section
await add_section({
  entityType: "TASK",
  entityId: task.data.id,
  title: "Payment Provider Configuration",
  usageDescription: "Stripe-specific configuration details",
  content: "### Stripe Setup\n...",
  contentFormat: "MARKDOWN",
  ordinal: 10,  // After template sections
  tags: "configuration,stripe"
});
```

## Efficiency Note: Bulk Creation

**For multiple sections**: Use `bulk_create_sections` instead of multiple `add_section` calls.

```javascript
// ❌ Inefficient: Multiple calls
await add_section({...});
await add_section({...});
await add_section({...});

// ✅ Efficient: Single bulk call
await bulk_create_sections({
  entityType: "TASK",
  entityId: taskId,
  sections: [
    { title: "Requirements", content: "...", ordinal: 0 },
    { title: "Implementation", content: "...", ordinal: 1 },
    { title: "Testing", content: "...", ordinal: 2 }
  ]
});
```

## Best Practices

1. **Don't Duplicate Title in Content**: Title field provides the heading
2. **Use Ordinals Strategically**: Leave gaps for future insertions
3. **Match Format to Content**: Markdown for docs, JSON for data, CODE for examples
4. **Write Clear Usage Descriptions**: Help AI understand section purpose
5. **Tag Consistently**: Use standard tags (requirements, implementation, testing)
6. **Start with High-Level, Add Detail**: Progressive elaboration
7. **Use Subsections (H3)**: Organize within sections
8. **Prefer bulk_create_sections**: When adding multiple sections

## Related Tools

- **bulk_create_sections**: More efficient for multiple sections
- **update_section**: Modify existing section content
- **update_section_text**: Partial content updates
- **get_sections**: Retrieve sections
- **delete_section**: Remove sections

## See Also

- Task Creation: `task-orchestrator://docs/tools/create-task`
- Template Strategy: `task-orchestrator://guidelines/template-strategy`
