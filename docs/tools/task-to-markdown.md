# task_to_markdown Tool - Detailed Documentation

## Overview

Transforms a task into a markdown document with YAML frontmatter. Retrieves task metadata and all sections, then renders them in human-readable markdown format suitable for export, documentation, and version control.

**Resource**: `task-orchestrator://docs/tools/task-to-markdown`

## Key Concepts

### YAML Frontmatter
Task metadata (id, title, status, priority, complexity, tags, dates) is rendered as YAML frontmatter at the top of the document.

### Markdown Body
Task summary appears as the first paragraph, followed by all sections rendered according to their content format (markdown, code, JSON, plain text).

### Section Rendering
Different section content formats are rendered appropriately:
- **MARKDOWN**: Rendered directly with proper heading levels
- **CODE**: Wrapped in code fences with syntax highlighting
- **JSON**: Formatted and wrapped in JSON code blocks
- **PLAIN_TEXT**: Rendered as markdown paragraphs

### Export Use Cases
Perfect for:
- File export and archival
- Version control (git diff-friendly)
- Documentation generation
- Human-readable task reports
- Integration with markdown-based systems

## Parameter Reference

### Required Parameters
- **id** (UUID): Task identifier to transform

### No Optional Parameters
The tool always renders the complete task with all sections.

## Output Structure

### YAML Frontmatter
```yaml
---
id: 550e8400-e29b-41d4-a716-446655440000
title: Implement OAuth Authentication API
status: completed
priority: high
complexity: 8
tags:
  - authentication
  - backend
  - oauth
  - api
createdAt: 2025-05-10T14:30:00Z
modifiedAt: 2025-05-10T16:45:00Z
featureId: 661e8511-f30c-41d4-a716-557788990000
---
```

### Summary Paragraph
```markdown
Create secure authentication flow with OAuth 2.0 protocol supporting Google, GitHub, and Facebook providers. Implement JWT token generation, validation, and refresh mechanisms.
```

### Section Content
```markdown
## Requirements

- **Must** support OAuth 2.0 with multiple providers
- **Must** implement JWT token generation (RS256)
- **Should** handle token refresh automatically
- **Must** validate tokens on every API request

## Implementation Notes

### OAuth Flow
1. User initiates login with provider selection
2. Redirect to provider authorization URL
3. Handle callback with authorization code
4. Exchange code for access token
5. Generate JWT for session management

### Code Example
\`\`\`kotlin
fun generateJWT(user: User): String {
    return JWT.create()
        .withSubject(user.id)
        .withExpiresAt(Date(System.currentTimeMillis() + 3600000))
        .sign(Algorithm.RSA256(publicKey, privateKey))
}
\`\`\`
```

## Common Usage Patterns

### Pattern 1: Export Task for Documentation
Export task as markdown file for project documentation.

```javascript
const result = await task_to_markdown({
  id: "550e8400-e29b-41d4-a716-446655440000"
});

if (result.success) {
  // Write to file
  const filename = `task-${result.data.taskId}.md`;
  fs.writeFileSync(filename, result.data.markdown);
  console.log(`Exported to ${filename}`);
}
```

**When to use**: Creating documentation from completed work, archiving tasks

### Pattern 2: Archive Completed Tasks
Export all completed tasks for archival before deletion.

```javascript
async function archiveCompletedTasks() {
  const completed = await search_tasks({ status: "completed" });

  for (const task of completed.data.tasks) {
    const markdown = await task_to_markdown({ id: task.id });

    if (markdown.success) {
      const filename = `archive/${task.id}.md`;
      fs.writeFileSync(filename, markdown.data.markdown);
      console.log(`Archived: ${task.title}`);
    }
  }
}
```

**When to use**: Cleaning up completed work, creating historical archive

### Pattern 3: Generate Feature Documentation
Export all tasks within a feature for comprehensive documentation.

```javascript
async function documentFeature(featureId) {
  const feature = await get_feature({
    id: featureId,
    includeTasks: true
  });

  let documentation = `# Feature: ${feature.data.name}\n\n`;
  documentation += `${feature.data.summary}\n\n`;

  for (const task of feature.data.tasks) {
    const taskMd = await task_to_markdown({ id: task.id });
    if (taskMd.success) {
      documentation += `\n---\n\n${taskMd.data.markdown}\n`;
    }
  }

  fs.writeFileSync(`${featureId}-documentation.md`, documentation);
}
```

**When to use**: Feature completion, stakeholder reports

### Pattern 4: Version Control Commit
Export task before making significant changes for version control.

```javascript
async function snapshotTask(taskId) {
  const markdown = await task_to_markdown({ id: taskId });

  if (markdown.success) {
    const timestamp = new Date().toISOString().replace(/:/g, '-');
    const filename = `snapshots/task-${taskId}-${timestamp}.md`;
    fs.writeFileSync(filename, markdown.data.markdown);

    // Commit to git
    execSync(`git add ${filename}`);
    execSync(`git commit -m "Snapshot task ${taskId}"`);
  }
}
```

**When to use**: Before major task updates, creating audit trail

### Pattern 5: Email/Slack Report
Generate markdown report for sharing with team.

```javascript
async function generateWeeklyReport() {
  const completed = await search_tasks({
    status: "completed",
    // Filter for last week
  });

  let report = `# Weekly Accomplishments\n\n`;

  for (const task of completed.data.tasks) {
    const md = await task_to_markdown({ id: task.id });
    if (md.success) {
      report += md.data.markdown + "\n\n---\n\n";
    }
  }

  // Send via email or Slack
  sendReport(report);
}
```

**When to use**: Status reports, team updates, stakeholder communication

### Pattern 6: Migration to External System
Export tasks for migration to another system.

```javascript
async function exportForMigration() {
  const allTasks = await search_tasks({ limit: 1000 });
  const exports = [];

  for (const task of allTasks.data.tasks) {
    const markdown = await task_to_markdown({ id: task.id });

    if (markdown.success) {
      exports.push({
        id: task.id,
        markdown: markdown.data.markdown,
        metadata: {
          title: task.title,
          status: task.status,
          priority: task.priority
        }
      });
    }
  }

  fs.writeFileSync('migration-export.json', JSON.stringify(exports, null, 2));
}
```

**When to use**: System migrations, platform changes

## Response Structure

### Success Response
```json
{
  "success": true,
  "message": "Task transformed to markdown successfully",
  "data": {
    "markdown": "---\nid: 550e8400-e29b-41d4-a716-446655440000\ntitle: Implement OAuth Authentication\n...\n",
    "taskId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

The `markdown` field contains the complete markdown document as a string.

### Task Not Found
```json
{
  "success": false,
  "message": "Task not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No task exists with ID ..."
  }
}
```

### Invalid UUID
```json
{
  "success": false,
  "message": "Invalid id format. Must be a valid UUID",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

## Markdown Format Details

### Frontmatter Fields
- **id**: Task UUID
- **title**: Task title
- **status**: Current status (pending, in-progress, completed, cancelled, deferred)
- **priority**: Priority level (high, medium, low)
- **complexity**: Complexity rating (1-10)
- **tags**: Array of tag strings
- **createdAt**: ISO-8601 timestamp
- **modifiedAt**: ISO-8601 timestamp
- **featureId**: Parent feature UUID (if associated)
- **projectId**: Parent project UUID (if associated)

### Section Rendering Rules

**Markdown Sections**:
```markdown
## Section Title
Section content rendered directly with proper formatting.
```

**Code Sections**:
````markdown
## Implementation Code
```kotlin
fun example() {
    // Code content
}
```
````

**JSON Sections**:
````markdown
## API Schema
```json
{
  "endpoint": "/api/auth",
  "method": "POST"
}
```
````

**Plain Text Sections**:
```markdown
## Notes
Plain text content rendered as paragraphs.
```

## Comparison with get_task

### task_to_markdown
**Purpose**: Human-readable export and documentation
**Format**: Markdown with YAML frontmatter
**Output**: Single markdown string
**Use cases**: File export, version control, reports

### get_task
**Purpose**: Programmatic access to task data
**Format**: Structured JSON
**Output**: Nested JSON object with sections array
**Use cases**: Task inspection, updates, processing

### When to Use Which

Use `task_to_markdown`:
- Creating documentation files
- Exporting for archival
- Generating reports
- Version control snapshots
- Integration with markdown systems

Use `get_task`:
- Inspecting task details
- Making programmatic updates
- Processing task data
- Dependency analysis
- Building task-based workflows

## Common Mistakes to Avoid

### ❌ Mistake 1: Using for Task Inspection
```javascript
// ❌ Using markdown for inspection
const markdown = await task_to_markdown({ id: taskId });
const statusMatch = markdown.data.markdown.match(/status: (\w+)/);
const status = statusMatch[1];
```
**Problem**: Parsing markdown is inefficient and error-prone

### ✅ Solution: Use get_task for Inspection
```javascript
// ✅ Use JSON API for inspection
const task = await get_task({ id: taskId });
const status = task.data.status;
```

### ❌ Mistake 2: Repeated Exports in Loop
```javascript
// ❌ Inefficient repeated exports
for (const taskId of taskIds) {
  const md = await task_to_markdown({ id: taskId });
  // Process markdown
}
```
**Problem**: Each call includes all sections (potentially large)

### ✅ Solution: Batch Export or Use get_task
```javascript
// ✅ Use get_task for iteration, markdown once at end
for (const taskId of taskIds) {
  const task = await get_task({ id: taskId });
  // Process task data
}

// Export final result
const final = await task_to_markdown({ id: finalTaskId });
```

### ❌ Mistake 3: Not Handling Large Output
```javascript
// ❌ Assuming markdown is small
const md = await task_to_markdown({ id: taskId });
sendEmail(md.data.markdown);  // May be too large!
```

### ✅ Solution: Check Size and Truncate if Needed
```javascript
const md = await task_to_markdown({ id: taskId });
const MAX_SIZE = 50000;  // 50KB limit for email

if (md.data.markdown.length > MAX_SIZE) {
  // Truncate or split into multiple emails
  const truncated = md.data.markdown.substring(0, MAX_SIZE) + "\n\n[Truncated...]";
  sendEmail(truncated);
} else {
  sendEmail(md.data.markdown);
}
```

### ❌ Mistake 4: Forgetting File Encoding
```javascript
// ❌ May corrupt special characters
fs.writeFileSync('task.md', markdown.data.markdown);
```

### ✅ Solution: Specify UTF-8 Encoding
```javascript
fs.writeFileSync('task.md', markdown.data.markdown, 'utf8');
```

## Best Practices

1. **Use for Export, Not Inspection**: Use `get_task` for data inspection
2. **Specify UTF-8 Encoding**: When writing to files
3. **Handle Large Output**: Check markdown size before processing
4. **Archive Before Deletion**: Export tasks before removing them
5. **Include in Version Control**: Track task snapshots in git
6. **Generate Documentation**: Create human-readable docs from tasks
7. **Batch Carefully**: Export many tasks can be token-intensive
8. **Filename Conventions**: Use consistent naming (task-{id}.md, task-{title-slug}.md)

## File Naming Strategies

### Strategy 1: UUID-Based
```javascript
const filename = `task-${taskId}.md`;
// Result: task-550e8400-e29b-41d4-a716-446655440000.md
```
**Pros**: Unique, consistent
**Cons**: Not human-readable

### Strategy 2: Title-Based
```javascript
const slug = task.title.toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9-]/g, '');
const filename = `${slug}.md`;
// Result: implement-oauth-authentication-api.md
```
**Pros**: Human-readable, descriptive
**Cons**: Not unique, can have collisions

### Strategy 3: Hybrid
```javascript
const slug = task.title.toLowerCase().replace(/\s+/g, '-').substring(0, 50);
const filename = `${slug}-${taskId.substring(0, 8)}.md`;
// Result: implement-oauth-authentication-api-550e8400.md
```
**Pros**: Readable + unique
**Cons**: Longer filenames

### Strategy 4: Date-Based Archive
```javascript
const date = new Date().toISOString().split('T')[0];
const filename = `archive/${date}/task-${taskId}.md`;
// Result: archive/2025-05-10/task-550e8400-e29b-41d4-a716-446655440000.md
```
**Pros**: Organized by date, easy to find recent exports
**Cons**: Requires directory structure

## Integration Patterns

### Pattern: Git Documentation Workflow
```javascript
async function commitTaskDocumentation(taskId) {
  const task = await get_task({ id: taskId });
  const markdown = await task_to_markdown({ id: taskId });

  if (markdown.success) {
    const filename = `docs/tasks/${task.data.title.replace(/\s+/g, '-')}.md`;
    fs.writeFileSync(filename, markdown.data.markdown, 'utf8');

    // Git commit
    execSync(`git add ${filename}`);
    execSync(`git commit -m "docs: add task ${task.data.title}"`);
    execSync(`git push`);

    console.log(`Documented: ${task.data.title}`);
  }
}
```

### Pattern: Automated Weekly Archive
```javascript
async function weeklyArchive() {
  const lastWeek = new Date();
  lastWeek.setDate(lastWeek.getDate() - 7);

  const recentlyCompleted = await search_tasks({
    status: "completed",
    // Filter by modifiedAt > lastWeek
  });

  const archiveDir = `archive/${new Date().toISOString().split('T')[0]}`;
  fs.mkdirSync(archiveDir, { recursive: true });

  for (const task of recentlyCompleted.data.tasks) {
    const md = await task_to_markdown({ id: task.id });
    if (md.success) {
      fs.writeFileSync(
        `${archiveDir}/task-${task.id}.md`,
        md.data.markdown,
        'utf8'
      );
    }
  }

  console.log(`Archived ${recentlyCompleted.data.tasks.length} tasks to ${archiveDir}`);
}
```

### Pattern: Confluence/Wiki Upload
```javascript
async function uploadToConfluence(taskId, confluencePageId) {
  const markdown = await task_to_markdown({ id: taskId });

  if (markdown.success) {
    // Convert markdown to Confluence storage format
    const confluenceContent = convertMarkdownToConfluence(markdown.data.markdown);

    // Upload to Confluence
    await confluenceAPI.updatePage(confluencePageId, {
      title: task.data.title,
      body: confluenceContent
    });

    console.log(`Uploaded task ${taskId} to Confluence`);
  }
}
```

## Related Tools

- **get_task**: Retrieve task in JSON format for inspection
- **feature_to_markdown**: Transform feature to markdown
- **project_to_markdown**: Transform project to markdown
- **search_tasks**: Find tasks to export
- **delete_task**: Remove tasks (export first for archival)

## See Also

- Documentation Generation: `task-orchestrator://guidelines/documentation`
- Export Strategies: `task-orchestrator://guidelines/export-patterns`
- Markdown Rendering: `task-orchestrator://guidelines/markdown-format`
