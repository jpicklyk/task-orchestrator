# create_template Tool - Detailed Documentation

## Overview

Creates a new template with structured section definitions that can be applied to tasks and features. Templates provide reusable documentation patterns and ensure consistency across projects.

**Resource**: `task-orchestrator://docs/tools/create-template`

## Key Concepts

### Template Purpose
Templates define a set of sections that will be created when applied to a task or feature. They provide:
- **Standardized documentation structure** - Consistent patterns across tasks/features
- **Guided AI workflow** - Section definitions guide AI agents on what to document
- **Reusable patterns** - Apply proven structures repeatedly
- **Team alignment** - Everyone follows the same documentation approach

### Template Types
- **Task Templates**: Applied to tasks (implementation guides, bug investigation, testing strategies)
- **Feature Templates**: Applied to features (requirements, architecture, planning)

### Built-in vs User-Created Templates
- **Built-in**: System templates (9 provided), protected by default, comprehensive coverage
- **User-Created**: Custom templates for project-specific needs

## Parameter Reference

### Required Parameters
- **name** (string): Template name (e.g., "User Authentication", "Data Export")
  - Should be descriptive and specific
  - Must be unique across all templates

- **description** (string): Template purpose and scope
  - Explain what the template is for
  - Describe when to use it

- **targetEntityType** (enum): TASK or FEATURE
  - TASK: For task-level templates
  - FEATURE: For feature-level templates

### Optional Parameters
- **isBuiltIn** (boolean, default: false): Whether this is a built-in system template
  - Set to true only for system templates
  - Built-in templates have special deletion restrictions

- **isProtected** (boolean, default: false): Prevents modification or deletion
  - Protected templates cannot be updated or deleted without force=true
  - Use for critical templates

- **isEnabled** (boolean, default: true): Whether template is available for use
  - Disabled templates don't appear in list_templates (unless requested)
  - Disabled templates cannot be applied

- **createdBy** (string): Creator identifier
  - Optional metadata for tracking
  - Can be username, email, or system identifier

- **tags** (string): Comma-separated tags for categorization
  - Examples: "workflow,implementation", "documentation,frontend"
  - Used for filtering and discovery

## Common Usage Patterns

### Pattern 1: Basic Task Template
Create a simple template for implementation tasks.

```json
{
  "name": "API Implementation",
  "description": "Standard template for implementing REST API endpoints",
  "targetEntityType": "TASK",
  "tags": "implementation,backend,api"
}
```

**When to use**: Standard implementation work requiring consistent documentation.

### Pattern 2: Feature Planning Template
Create a comprehensive template for feature planning.

```json
{
  "name": "Feature Specification",
  "description": "Complete specification template for new features including requirements, architecture, and testing strategy",
  "targetEntityType": "FEATURE",
  "tags": "planning,requirements,architecture",
  "createdBy": "planning-team"
}
```

**When to use**: Large features requiring detailed upfront planning.

### Pattern 3: Protected Template
Create a protected template that cannot be accidentally modified.

```json
{
  "name": "Security Review Checklist",
  "description": "Required security review steps for production deployments",
  "targetEntityType": "TASK",
  "isProtected": true,
  "tags": "security,compliance,review"
}
```

**When to use**: Critical templates that must remain consistent (compliance, security).

### Pattern 4: Temporarily Disabled Template
Create a template that starts disabled.

```json
{
  "name": "Experimental Workflow",
  "description": "Experimental task workflow for testing new approaches",
  "targetEntityType": "TASK",
  "isEnabled": false,
  "tags": "experimental,workflow"
}
```

**When to use**: Templates under development or experimentation.

## Complete Workflow: Creating a Template

### Step 1: Create the Template
```json
{
  "name": "Database Migration",
  "description": "Template for database schema changes with rollback procedures",
  "targetEntityType": "TASK",
  "tags": "database,migration,infrastructure"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Template created successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Database Migration",
    "description": "Template for database schema changes with rollback procedures",
    "targetEntityType": "TASK",
    "isBuiltIn": false,
    "isProtected": false,
    "isEnabled": true,
    "createdBy": null,
    "tags": ["database", "migration", "infrastructure"],
    "createdAt": "2025-10-17T19:00:00Z",
    "modifiedAt": "2025-10-17T19:00:00Z"
  }
}
```

### Step 2: Add Section Definitions
After creating the template, add sections using `add_template_section`:

```json
{
  "templateId": "550e8400-e29b-41d4-a716-446655440000",
  "title": "Migration Script",
  "usageDescription": "SQL script for forward migration",
  "contentSample": "```sql\n-- Add your migration SQL here\nALTER TABLE users ADD COLUMN email VARCHAR(255);\n```",
  "ordinal": 0,
  "contentFormat": "CODE",
  "tags": "migration,sql"
}
```

### Step 3: Apply to Tasks
Use the template when creating tasks:

```json
{
  "title": "Add email column to users table",
  "summary": "Add email field to support authentication",
  "templateIds": ["550e8400-e29b-41d4-a716-446655440000"],
  "tags": "database,migration"
}
```

## Template Design Best Practices

### Naming Conventions
- **Be Specific**: "User Authentication API" vs "Auth"
- **Use Category Prefixes**: "Workflow: Task Implementation", "Guide: Bug Investigation"
- **Avoid Abbreviations**: "Implementation" vs "Impl"

### Section Organization Strategy
When planning sections for your template (added via add_template_section):

**Standard Section Order**:
1. **Context/Background** (ordinal 0) - Why this work exists
2. **Requirements** (ordinal 10) - What needs to be done
3. **Technical Approach** (ordinal 20) - How to implement
4. **Implementation Steps** (ordinal 30) - Detailed steps
5. **Testing Strategy** (ordinal 40) - How to verify
6. **Definition of Done** (ordinal 50) - Completion criteria

**Use ordinal gaps (0, 10, 20)** to allow inserting sections later without renumbering.

### Tag Strategy
- **Domain**: database, frontend, backend, api, infrastructure
- **Purpose**: implementation, testing, documentation, workflow
- **Audience**: developer, architect, qa, ops

### Content Format Selection
- **MARKDOWN**: Documentation, requirements, notes (most common)
- **CODE**: Implementation scripts, examples
- **JSON**: Structured data, configurations
- **PLAIN_TEXT**: Simple checklists, unformatted text

## Built-in Templates Reference

The system includes 9 built-in templates (use list_templates to see all):

### Task Templates
1. **Task Implementation** - Standard implementation workflow
2. **Bug Investigation** - Systematic bug resolution
3. **Git Branching Workflow** - Git workflow guidance
4. **Technical Approach** - Technical decision documentation
5. **Testing Strategy** - Test planning and coverage
6. **Definition of Done** - Task completion criteria

### Feature Templates
1. **Requirements Specification** - Detailed requirements
2. **Context & Background** - Feature context and rationale
3. **Architecture Overview** - High-level design

**Best Practice**: Study built-in templates before creating custom ones.

## Response Structure

### Success Response
```json
{
  "success": true,
  "message": "Template created successfully",
  "data": {
    "id": "uuid",
    "name": "Template name",
    "description": "Template description",
    "targetEntityType": "TASK",
    "isBuiltIn": false,
    "isProtected": false,
    "isEnabled": true,
    "createdBy": "creator-id",
    "tags": ["tag1", "tag2"],
    "createdAt": "ISO-8601 timestamp",
    "modifiedAt": "ISO-8601 timestamp"
  }
}
```

## Error Handling

### Duplicate Name Error
```json
{
  "success": false,
  "message": "Template name already exists",
  "error": {
    "code": "CONFLICT_ERROR",
    "details": "A template with name 'API Implementation' already exists"
  }
}
```

**Solution**: Choose a more specific name or check existing templates with `list_templates`.

### Validation Error
```json
{
  "success": false,
  "message": "Invalid target entity type: INVALID. Must be 'TASK' or 'FEATURE'",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Common causes**:
- Invalid targetEntityType value
- Empty name or description
- Invalid enum values

## Common Mistakes to Avoid

### ❌ Mistake 1: Generic Template Names
```json
{
  "name": "Task Template"
}
```
**Problem**: Too generic, doesn't convey purpose.

### ✅ Solution: Specific, Descriptive Names
```json
{
  "name": "REST API Implementation with Authentication"
}
```

### ❌ Mistake 2: Creating Duplicate Templates
Creating a new template without checking if similar ones exist.

### ✅ Solution: Always Discover First
```javascript
// Step 1: Check existing templates
const templates = await list_templates({
  targetEntityType: "TASK",
  tags: "implementation"
});

// Step 2: Only create if no suitable match
if (!templates.data.templates.some(t => t.name.includes("API"))) {
  await create_template({ ... });
}
```

### ❌ Mistake 3: Forgetting to Add Sections
Creating a template and then forgetting to add section definitions.

### ✅ Solution: Complete Workflow
```javascript
// Create template
const template = await create_template({ ... });

// Immediately add sections
await add_template_section({
  templateId: template.data.id,
  title: "Requirements",
  ...
});
```

### ❌ Mistake 4: Making Everything Protected
```json
{
  "isProtected": true,
  "isBuiltIn": true
}
```
**Problem**: Cannot update or improve the template later.

### ✅ Solution: Only Protect Critical Templates
Reserve protection for compliance, security, or team-standard templates.

## Integration with Other Tools

### Template Discovery
Before creating, always discover:
```javascript
const templates = await list_templates({
  targetEntityType: "TASK",
  isEnabled: true
});
```

### Template Application
After creating and adding sections:
```javascript
// During task creation
const task = await create_task({
  title: "Implement login endpoint",
  templateIds: [templateId]
});

// Or apply to existing task
const result = await apply_template({
  templateIds: [templateId],
  entityType: "TASK",
  entityId: taskId
});
```

### Template Management
```javascript
// Enable/disable
await disable_template({ id: templateId });
await enable_template({ id: templateId });

// Update metadata
await update_template_metadata({
  id: templateId,
  tags: "updated,tags"
});

// Delete (user-created only)
await delete_template({ id: templateId });
```

## Best Practices Summary

1. **Always Discover First**: Use `list_templates` before creating new templates
2. **Use Descriptive Names**: Make template purpose immediately clear
3. **Add Sections Immediately**: Don't create empty templates
4. **Use Ordinal Gaps**: Leave room for future section insertion (0, 10, 20)
5. **Tag Consistently**: Follow project tagging conventions
6. **Protect Sparingly**: Only protect truly critical templates
7. **Document Usage**: Clear description field explaining when to use
8. **Test Before Protecting**: Apply and verify before marking as protected

## Related Tools

- **add_template_section**: Add section definitions to template (required after creation)
- **list_templates**: Discover existing templates before creating
- **get_template**: View template details and sections
- **apply_template**: Apply template to tasks/features
- **update_template_metadata**: Update template properties
- **delete_template**: Remove user-created templates
- **enable_template**: Enable disabled templates
- **disable_template**: Temporarily disable templates

## See Also

- Template Management Guide: `task-orchestrator://guidelines/template-strategy`
- Section Definition Guide: `task-orchestrator://docs/tools/add-template-section`
- Template Application: `task-orchestrator://docs/tools/apply-template`
