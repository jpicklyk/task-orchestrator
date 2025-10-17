# feature_to_markdown Tool - Detailed Documentation

## Overview

Transforms a feature into markdown format with YAML frontmatter. Creates human-readable, version-control-friendly documentation suitable for export, archival, and external rendering.

**Resource**: `task-orchestrator://docs/tools/feature-to-markdown`

## Key Concepts

### Markdown Export Purpose
- **Human-readable**: Easy to read and understand
- **Version control friendly**: Diff-able format for git
- **Portable**: Standard markdown works everywhere
- **Documentation**: Export for wikis, docs sites
- **Archival**: Long-term storage format
- **Backup**: Preserves feature content outside database

### Output Format
- **YAML frontmatter**: Metadata header (id, name, status, priority, tags, dates)
- **Summary paragraph**: Feature summary as first content
- **Sections**: All sections rendered according to their content format
- **Proper formatting**: Markdown headings, code blocks, etc.

## Parameter Reference

### Required Parameters
- **id** (UUID): Feature ID to transform

## Usage Patterns

### Pattern 1: Feature Documentation Export
Export feature for documentation purposes.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response includes**:
```markdown
---
id: 550e8400-e29b-41d4-a716-446655440000
name: User Authentication System
status: in-development
priority: high
tags:
  - authentication
  - oauth
  - security
created: 2025-10-01T14:30:00Z
modified: 2025-10-17T10:15:00Z
---

Multi-provider OAuth authentication with Google, GitHub, and Facebook, plus email/password login and 2FA support.

## Requirements

- Must support OAuth 2.0 with Google, GitHub, Facebook
- Must support email/password authentication
- Must implement 2FA using TOTP
- Session management with Redis
- JWT token generation and validation

## Architecture

[Architecture details from section...]
```

**When to use**:
- Creating documentation
- Exporting to wiki
- Sharing with stakeholders

### Pattern 2: Backup Before Deletion
Create markdown backup before deleting feature.

```javascript
// Export to markdown first
const result = await feature_to_markdown({
  id: featureId
});

// Save to file (pseudo-code)
saveToFile(`feature-${featureId}.md`, result.data.markdown);

// Now safe to delete
await delete_feature({
  id: featureId,
  cascade: true
});
```

**When to use**:
- Before cascade deletion
- Compliance/audit requirements
- Historical preservation

### Pattern 3: Version Control Integration
Export features for git commit.

```javascript
// Export all features in project
const features = await search_features({
  projectId: projectId,
  limit: 100
});

for (const feature of features.data.items) {
  const markdown = await feature_to_markdown({
    id: feature.id
  });

  // Save to docs/ directory
  writeFile(
    `docs/features/${feature.name.toLowerCase().replace(/\s+/g, '-')}.md`,
    markdown.data.markdown
  );
}

// Commit to git
// git add docs/features/
// git commit -m "Update feature documentation"
```

**When to use**:
- Documentation as code
- Track feature evolution
- Team collaboration

### Pattern 4: Static Site Generation
Generate markdown for documentation sites.

```javascript
const result = await feature_to_markdown({
  id: featureId
});

// Process for documentation generator (e.g., MkDocs, Docusaurus)
const processedMarkdown = addNavigationLinks(result.data.markdown);
writeFile('docs/features/authentication.md', processedMarkdown);
```

**When to use**:
- Building documentation sites
- Knowledge bases
- Internal wikis

## Response Structure

### Successful Response
```json
{
  "success": true,
  "message": "Feature transformed to markdown successfully",
  "data": {
    "markdown": "---\nid: 550e8400-e29b-41d4-a716-446655440000\nname: User Authentication System\n...",
    "featureId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

### Markdown Structure

#### YAML Frontmatter
```yaml
---
id: 550e8400-e29b-41d4-a716-446655440000
name: User Authentication System
status: in-development
priority: high
projectId: project-uuid-or-null
tags:
  - authentication
  - oauth
  - security
created: 2025-10-01T14:30:00Z
modified: 2025-10-17T10:15:00Z
---
```

#### Content Body
```markdown
Multi-provider OAuth authentication with Google, GitHub, Facebook, plus email/password login and 2FA.

## Section Title 1

Section content here...

## Section Title 2

More section content...

### Code Example

```kotlin
fun authenticate(credentials: Credentials): Result<User> {
    // Implementation
}
```
```

## Section Content Rendering

### Markdown Sections
Rendered as-is with proper heading levels.

**Input** (Section):
```
title: "Requirements"
contentFormat: MARKDOWN
content: "- OAuth 2.0 support\n- Email/password login"
```

**Output** (Markdown):
```markdown
## Requirements

- OAuth 2.0 support
- Email/password login
```

### Code Sections
Rendered as code blocks with syntax highlighting.

**Input** (Section):
```
title: "Authentication Handler"
contentFormat: CODE
content: "fun authenticate() { ... }"
```

**Output** (Markdown):
````markdown
## Authentication Handler

```
fun authenticate() { ... }
```
````

### JSON Sections
Rendered as JSON code blocks.

**Input** (Section):
```
title: "API Schema"
contentFormat: JSON
content: '{"endpoint": "/auth", "method": "POST"}'
```

**Output** (Markdown):
````markdown
## API Schema

```json
{"endpoint": "/auth", "method": "POST"}
```
````

### Plain Text Sections
Rendered as plain paragraphs.

**Input** (Section):
```
title: "Notes"
contentFormat: PLAIN_TEXT
content: "Important considerations for implementation"
```

**Output** (Markdown):
```markdown
## Notes

Important considerations for implementation
```

## Common Workflows

### Workflow 1: Feature Documentation Export
```javascript
// Get feature markdown
const result = await feature_to_markdown({
  id: featureId
});

// Write to file
const fs = require('fs');
fs.writeFileSync(
  `docs/features/${featureName}.md`,
  result.data.markdown
);

console.log('Feature documentation exported');
```

### Workflow 2: Batch Export All Features
```javascript
// Get all features
const features = await search_features({
  limit: 100
});

// Export each to markdown
for (const feature of features.data.items) {
  const markdown = await feature_to_markdown({
    id: feature.id
  });

  const filename = feature.name
    .toLowerCase()
    .replace(/\s+/g, '-')
    .replace(/[^a-z0-9-]/g, '');

  fs.writeFileSync(
    `docs/features/${filename}.md`,
    markdown.data.markdown
  );
}

console.log(`Exported ${features.data.total} features`);
```

### Workflow 3: Email Feature Summary
```javascript
// Export to markdown
const result = await feature_to_markdown({
  id: featureId
});

// Convert markdown to HTML (using markdown library)
const html = markdownToHtml(result.data.markdown);

// Send email
sendEmail({
  to: 'stakeholder@company.com',
  subject: 'Feature: User Authentication System',
  html: html
});
```

### Workflow 4: Backup Before Major Changes
```javascript
// Backup current state
const before = await feature_to_markdown({
  id: featureId
});

fs.writeFileSync(
  `backups/feature-${featureId}-before.md`,
  before.data.markdown
);

// Make changes
await update_feature({
  id: featureId,
  summary: "Updated summary..."
});

// Backup after changes
const after = await feature_to_markdown({
  id: featureId
});

fs.writeFileSync(
  `backups/feature-${featureId}-after.md`,
  after.data.markdown
);
```

## Use Cases

### 1. Documentation Generation
Export features to markdown for documentation websites (MkDocs, Docusaurus, etc.)

**Benefits**:
- Version controlled documentation
- Easy to update and maintain
- Searchable content
- Team collaboration

### 2. Feature Archival
Preserve feature content for long-term storage.

**Benefits**:
- Compliance/audit trails
- Historical reference
- Portable format
- Independent of database

### 3. Stakeholder Reports
Generate human-readable feature summaries.

**Benefits**:
- Non-technical format
- Email-friendly
- Presentation-ready
- Easy to share

### 4. Knowledge Base
Build internal wiki or knowledge base.

**Benefits**:
- Centralized documentation
- Easy navigation
- Full-text search
- Cross-linking

### 5. Code Review Context
Include feature docs in pull requests.

**Benefits**:
- Context for reviewers
- Requirements reference
- Architecture decisions
- Implementation notes

## Comparison with get_feature

| Feature | feature_to_markdown | get_feature |
|---------|-------------------|-------------|
| **Output Format** | Markdown text | JSON data |
| **Human Readable** | ✅ Yes | ❌ No (JSON) |
| **Machine Readable** | Limited | ✅ Yes |
| **Version Control** | ✅ Excellent | ❌ Poor |
| **Export/Share** | ✅ Easy | Requires processing |
| **Inspection** | Limited | ✅ Full access |
| **Use Case** | Documentation, export | Data processing, display |

**When to use feature_to_markdown**:
- Exporting documentation
- Creating backups
- Sharing with non-technical users
- Version control integration

**When to use get_feature**:
- Inspecting feature data
- Processing feature information
- Building UIs
- Data analysis

## Error Handling

### Feature Not Found
```json
{
  "success": false,
  "message": "Feature not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No feature exists with ID ..."
  }
}
```

### Invalid UUID Format
```json
{
  "success": false,
  "message": "Invalid input",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid id format. Must be a valid UUID"
  }
}
```

## Best Practices

1. **Export Before Deletion**: Create markdown backup before deleting features
2. **Batch Export**: Use scripts to export multiple features at once
3. **Version Control**: Commit markdown files to git for history
4. **Consistent Naming**: Use feature name for filename (slugified)
5. **Directory Structure**: Organize by project or category
6. **Automation**: Schedule regular exports for backup
7. **Metadata Preservation**: YAML frontmatter keeps all metadata
8. **Documentation Sites**: Integrate with static site generators

## File Naming Conventions

### Recommended Patterns

**By Feature Name**:
```
user-authentication-system.md
payment-gateway-integration.md
real-time-notifications.md
```

**By Feature ID** (when names conflict):
```
550e8400-e29b-41d4-a716-446655440000.md
```

**Organized by Project**:
```
project-a/user-authentication.md
project-a/payment-gateway.md
project-b/analytics-dashboard.md
```

**With Status Prefix**:
```
completed-user-authentication.md
in-development-payment-gateway.md
planning-mobile-app.md
```

## Integration Examples

### MkDocs Integration
```yaml
# mkdocs.yml
nav:
  - Features:
    - Authentication: features/user-authentication.md
    - Payments: features/payment-gateway.md
```

### Docusaurus Integration
```javascript
// sidebars.js
module.exports = {
  features: [
    'features/user-authentication',
    'features/payment-gateway'
  ]
};
```

### GitHub Wiki
```bash
# Export to wiki repo
feature_to_markdown | save to wiki/Features.md
git add wiki/Features.md
git commit -m "Update features documentation"
```

## Related Tools

- **get_feature**: Retrieve feature in JSON format for processing
- **task_to_markdown**: Export individual tasks to markdown
- **project_to_markdown**: Export entire project to markdown
- **search_features**: Find features to export
- **get_sections**: Access individual sections

## See Also

- Markdown Rendering: `task-orchestrator://guidelines/markdown-rendering`
- Documentation Export: `task-orchestrator://guidelines/export-strategies`
- Backup Strategies: `task-orchestrator://guidelines/backup-strategies`
