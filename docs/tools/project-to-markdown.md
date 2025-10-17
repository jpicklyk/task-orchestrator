# project_to_markdown Tool - Detailed Documentation

## Overview

Transforms a project into markdown format with YAML frontmatter. Renders project metadata and sections as a human-readable markdown document suitable for export, documentation, and version control.

**Resource**: `task-orchestrator://docs/tools/project-to-markdown`

## Key Concepts

### Markdown Rendering
Converts structured project data into markdown:
- **YAML frontmatter**: Project metadata (id, name, status, tags, dates)
- **Summary paragraph**: Project summary as first content
- **Section rendering**: Sections rendered according to content format

### Content Format Handling
Sections are rendered based on their contentFormat:
- **MARKDOWN**: Rendered as-is
- **CODE**: Wrapped in code fences with language detection
- **JSON**: Formatted with syntax highlighting
- **PLAIN_TEXT**: Rendered as plain text

### Use Cases
- **Documentation**: Generate project documentation
- **Export**: Save projects to file system
- **Version control**: Commit project snapshots to git
- **Archival**: Human-readable project archives
- **Sharing**: Share project details as markdown

## Parameter Reference

### Required Parameters
- **id** (UUID): Project identifier

## Output Structure

### YAML Frontmatter
```yaml
---
id: b160fbdb-07e4-42d7-8c61-8deac7d2fc17
name: Mobile App Redesign
status: in-development
created: 2025-10-15T14:30:00Z
modified: 2025-10-17T16:20:00Z
tags:
  - mobile
  - redesign
  - 2025-q2
---
```

### Content Structure
```markdown
Complete mobile app overhaul with new UI/UX and offline support

## Project Overview
[Content from "Project Overview" section]

## Architecture
[Content from "Architecture" section]

## Q2 Objectives
[Content from "Q2 Objectives" section]
```

## Common Usage Patterns

### Pattern 1: Export Single Project
Export project to markdown for documentation.

```json
{
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17"
}
```

**When to use**:
- Generating project documentation
- Creating README files
- Exporting for sharing
- Version control commits

**Response includes**:
- Complete markdown document
- Project ID for reference

**Token usage**: ~1000-3000 tokens (varies by content)

### Pattern 2: Backup Before Deletion
Export project before deleting for archive.

```javascript
// Step 1: Export to markdown
const markdown = await project_to_markdown({
  id: projectId
});

// Step 2: Save to file (conceptual)
// saveToFile(`project-backup-${projectId}.md`, markdown.data.markdown);

// Step 3: Now safe to delete
await delete_project({
  id: projectId,
  cascade: true
});
```

**When to use**:
- Creating backups before deletion
- Archiving completed projects
- Preserving project history

### Pattern 3: Generate Documentation Set
Export all projects for documentation site.

```javascript
// Get all projects
const projects = await search_projects({
  limit: 100
});

// Export each to markdown
for (const project of projects.data.items) {
  const markdown = await project_to_markdown({
    id: project.id
  });

  // Save to docs directory
  const filename = `${project.name.toLowerCase().replace(/\s+/g, '-')}.md`;
  console.log(`Exported: ${filename}`);
  // saveToFile(`docs/projects/${filename}`, markdown.data.markdown);
}
```

**When to use**:
- Building documentation sites
- Creating project wikis
- Generating static documentation

### Pattern 4: Version Control Snapshot
Commit project state to git.

```javascript
// Export project
const markdown = await project_to_markdown({
  id: projectId
});

// Write to file and commit (conceptual)
// fs.writeFileSync('project-snapshot.md', markdown.data.markdown);
// exec('git add project-snapshot.md');
// exec('git commit -m "Project snapshot: 2025-10-17"');
```

**When to use**:
- Tracking project evolution
- Creating historical snapshots
- Documentation versioning

### Pattern 5: Quarterly Review Documents
Generate markdown for quarterly reviews.

```javascript
// Find Q2 projects
const q2Projects = await search_projects({
  tag: "2025-q2",
  status: "completed",
  limit: 50
});

// Create review document
let reviewDoc = "# Q2 2025 Completed Projects\n\n";

for (const project of q2Projects.data.items) {
  const markdown = await project_to_markdown({
    id: project.id
  });

  reviewDoc += `\n---\n\n${markdown.data.markdown}\n`;
}

console.log("Quarterly review document generated");
// saveToFile('q2-2025-review.md', reviewDoc);
```

**When to use**:
- Quarterly reviews
- Annual reports
- Team retrospectives

## Response Structure

### Success Response
```json
{
  "success": true,
  "message": "Project transformed to markdown successfully",
  "data": {
    "markdown": "---\nid: b160fbdb-07e4-42d7-8c61-8deac7d2fc17\nname: Mobile App Redesign\nstatus: in-development\ncreated: 2025-10-15T14:30:00Z\nmodified: 2025-10-17T16:20:00Z\ntags:\n  - mobile\n  - redesign\n  - 2025-q2\n---\n\nComplete mobile app overhaul with new UI/UX\n\n## Project Overview\n...",
    "projectId": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17"
  }
}
```

**Key fields**:
- `markdown`: Complete markdown document with YAML frontmatter
- `projectId`: Project UUID for reference

## Example Markdown Output

### Simple Project
```markdown
---
id: b160fbdb-07e4-42d7-8c61-8deac7d2fc17
name: Mobile App Redesign
status: in-development
created: 2025-10-15T14:30:00Z
modified: 2025-10-17T16:20:00Z
tags:
  - mobile
  - redesign
  - 2025-q2
---

Complete mobile app overhaul with new UI/UX and offline support
```

### Project with Sections
```markdown
---
id: b160fbdb-07e4-42d7-8c61-8deac7d2fc17
name: Customer Portal v2.0
status: planning
created: 2025-10-10T09:00:00Z
modified: 2025-10-17T14:00:00Z
tags:
  - customer-portal
  - frontend
  - backend
---

Next-generation customer portal with real-time data and enhanced UX

## Project Overview

### Goals
- Reduce customer support tickets by 40%
- Improve page load time to < 2 seconds
- Support 10,000 concurrent users

### Success Criteria
- User satisfaction score > 4.5/5
- Zero critical bugs in first month
- 95% uptime SLA

## Technical Architecture

### Frontend
- React 18 with TypeScript
- TailwindCSS for styling
- React Query for data fetching

### Backend
- Node.js with Express
- PostgreSQL database
- Redis for caching

### Infrastructure
- AWS ECS for hosting
- CloudFront CDN
- Auto-scaling enabled

## Q2 2025 Roadmap

### Phase 1 (April-May)
- Real-time dashboard development
- User profile management
- Role-based access control

### Phase 2 (June)
- Analytics integration
- Performance optimization
- Production deployment
```

### Project with Code Sections
```markdown
---
id: a7854b2c-3d1e-4f6a-9c8d-1e2f3a4b5c6d
name: API Gateway Implementation
status: in-development
created: 2025-10-01T10:00:00Z
modified: 2025-10-17T15:30:00Z
tags:
  - backend
  - api
  - infrastructure
---

Implement centralized API gateway for microservices architecture

## Configuration

```json
{
  "gateway": {
    "port": 8080,
    "timeout": 30000,
    "rateLimit": {
      "max": 100,
      "window": "1m"
    }
  }
}
```

## Implementation Example

```kotlin
class ApiGateway(
    private val services: List<ServiceRoute>
) {
    fun route(request: Request): Response {
        val service = services.find { it.matches(request.path) }
        return service?.handle(request) ?: Response.notFound()
    }
}
```
```

## Common Workflows

### Workflow 1: Export All Projects to Files

```javascript
// Get all projects
const allProjects = await search_projects({
  limit: 100
});

console.log(`Exporting ${allProjects.data.total} projects...`);

// Export each to markdown
for (const project of allProjects.data.items) {
  const result = await project_to_markdown({
    id: project.id
  });

  // Create filename from project name
  const filename = project.name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');

  console.log(`- ${filename}.md`);

  // In real usage, save to file:
  // fs.writeFileSync(`exports/${filename}.md`, result.data.markdown);
}
```

### Workflow 2: Create Project Wiki

```javascript
// Create index page
let wikiIndex = "# Project Wiki\n\n";
wikiIndex += "## All Projects\n\n";

// Get all projects grouped by status
const statuses = ['planning', 'in-development', 'completed', 'archived'];

for (const status of statuses) {
  const projects = await search_projects({
    status: status,
    limit: 100
  });

  wikiIndex += `\n### ${status.replace('-', ' ').toUpperCase()}\n\n`;

  for (const project of projects.data.items) {
    const filename = project.name.toLowerCase().replace(/\s+/g, '-');
    wikiIndex += `- [${project.name}](${filename}.md)\n`;

    // Export project
    const markdown = await project_to_markdown({
      id: project.id
    });

    // Save project page
    // fs.writeFileSync(`wiki/${filename}.md`, markdown.data.markdown);
  }
}

// Save index
// fs.writeFileSync('wiki/index.md', wikiIndex);
console.log("Wiki created successfully");
```

### Workflow 3: Compare Project Versions

```javascript
// Export current version
const current = await project_to_markdown({
  id: projectId
});

// Save current version
const timestamp = new Date().toISOString().split('T')[0];
// fs.writeFileSync(`snapshots/project-${timestamp}.md`, current.data.markdown);

// Compare with previous version using git diff
// exec(`git diff snapshots/project-*.md`);
```

### Workflow 4: Generate Release Notes

```javascript
// Find completed projects this quarter
const completed = await search_projects({
  status: "completed",
  tag: "2025-q2",
  limit: 50
});

let releaseNotes = "# Q2 2025 Release Notes\n\n";
releaseNotes += `${completed.data.total} projects completed this quarter.\n\n`;

for (const project of completed.data.items) {
  const markdown = await project_to_markdown({
    id: project.id
  });

  // Extract just the summary
  const lines = markdown.data.markdown.split('\n');
  const summaryStart = lines.findIndex(l => l === '---') + 1;
  const summaryEnd = lines.findIndex((l, i) => i > summaryStart && l === '---');
  const summary = lines.slice(summaryEnd + 1).find(l => l.trim() !== '');

  releaseNotes += `## ${project.name}\n${summary}\n\n`;
}

// Save release notes
// fs.writeFileSync('release-notes-q2-2025.md', releaseNotes);
```

## YAML Frontmatter Fields

All projects include these frontmatter fields:

| Field | Type | Description |
|-------|------|-------------|
| id | UUID | Project unique identifier |
| name | string | Project name |
| status | string | Current status (planning, in-development, completed, archived) |
| created | ISO-8601 | Project creation timestamp |
| modified | ISO-8601 | Last modification timestamp |
| tags | array | List of project tags |

**Example parsing**:
```javascript
const markdown = result.data.markdown;
const yamlMatch = markdown.match(/^---\n([\s\S]*?)\n---/);
if (yamlMatch) {
  const yamlContent = yamlMatch[1];
  // Parse YAML frontmatter
  // const metadata = yaml.parse(yamlContent);
}
```

## Error Handling

### Project Not Found
```json
{
  "success": false,
  "message": "Project not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No project exists with ID b160fbdb-07e4-42d7-8c61-8deac7d2fc17"
  }
}
```

**Solution**: Verify project ID using `search_projects`

### Invalid UUID
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid id format. Must be a valid UUID"
  }
}
```

**Solution**: Ensure ID is valid UUID format

## Markdown Rendering Details

### Section Title Levels
- Project name: H1 (in frontmatter, not rendered as heading)
- Section titles: H2 (##)
- Subsections within sections: H3 (###) and below

### Code Block Rendering
Sections with `contentFormat: CODE` are wrapped in code fences:
````markdown
```kotlin
// Code content here
```
````

### JSON Rendering
Sections with `contentFormat: JSON` are formatted with syntax highlighting:
````markdown
```json
{
  "formatted": "json"
}
```
````

### Plain Text Rendering
Sections with `contentFormat: PLAIN_TEXT` are rendered as plain paragraphs.

## Best Practices

1. **Export Before Deletion**: Always export projects before deleting
2. **Version Control**: Commit markdown snapshots to track changes
3. **Organized Storage**: Use consistent naming conventions for files
4. **Batch Export**: Export multiple projects in one session
5. **Documentation Generation**: Automate documentation updates
6. **Archive Completed**: Export completed projects for historical record
7. **Wiki Integration**: Use for team wikis and knowledge bases

## Integration with Other Tools

### With get_project
```javascript
// Get structured data
const project = await get_project({
  id: projectId,
  includeSections: true,
  includeFeatures: true
});

// Get markdown representation
const markdown = await project_to_markdown({
  id: projectId
});

// Use structured data for processing
// Use markdown for documentation
```

### With search_projects
```javascript
// Find projects to export
const projects = await search_projects({
  status: "completed",
  tag: "2025-q2"
});

// Export each
for (const project of projects.data.items) {
  await project_to_markdown({ id: project.id });
}
```

### With update_project
```javascript
// Make changes
await update_project({
  id: projectId,
  status: "completed"
});

// Export updated version
const markdown = await project_to_markdown({
  id: projectId
});
```

## Related Tools

- **get_project**: Get structured project data (JSON)
- **task_to_markdown**: Export tasks as markdown
- **feature_to_markdown**: Export features as markdown
- **search_projects**: Find projects to export
- **create_project**: Create projects to export later

## Comparison: JSON vs Markdown

### Use get_project (JSON) when:
- ✅ Processing data programmatically
- ✅ Filtering/transforming project data
- ✅ Building UIs or dashboards
- ✅ Need structured, machine-readable format

### Use project_to_markdown when:
- ✅ Generating human-readable documentation
- ✅ Exporting for version control
- ✅ Creating README files
- ✅ Archiving historical records
- ✅ Sharing with non-technical stakeholders

## See Also

- Markdown Rendering: `task-orchestrator://guidelines/markdown-rendering`
- Documentation Patterns: `task-orchestrator://guidelines/documentation`
- Export Strategies: `task-orchestrator://guidelines/export`
