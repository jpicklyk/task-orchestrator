# update_template_metadata Tool - Detailed Documentation

## Overview

Updates a template's metadata fields (name, description, targetEntityType, isEnabled, tags) without affecting its section definitions. Provides efficient context usage by targeting only changed fields.

**Resource**: `task-orchestrator://docs/tools/update-template-metadata`

## Key Concepts

### Metadata vs Sections
- **Metadata**: Template properties (name, description, enabled status, tags)
- **Sections**: Template structure (section definitions with content samples)

This tool updates only metadata. Sections remain unchanged.

### Protected Templates
- **Protected templates** cannot be updated without force=true
- **Built-in templates** are typically protected
- Use `disable_template` for reversible changes to protected templates

### Partial Updates
- **Only send fields you want to change**
- Unspecified fields retain their current values
- More efficient than sending complete template

## Parameter Reference

### Required Parameters
- **id** (UUID): Template ID to update

### Optional Parameters
All optional - only include fields you want to change:

- **name** (string): New template name
  - Must be unique across all templates
  - Cannot be empty

- **description** (string): New description
  - Cannot be empty

- **targetEntityType** (enum): TASK or FEATURE
  - Changes what entity type the template can be applied to
  - Careful: May make template incompatible with existing uses

- **isEnabled** (boolean): Enable/disable template
  - true: Template available for use
  - false: Template hidden from discovery
  - Alternative: Use `enable_template` / `disable_template`

- **tags** (string): Comma-separated tags
  - Replaces all existing tags
  - Empty string removes all tags

## Usage Patterns

### Pattern 1: Update Name Only
Change just the template name.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Enhanced API Implementation Template"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Template metadata updated successfully",
  "data": {
    "template": {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "Enhanced API Implementation Template",
      "description": "[unchanged]",
      "targetEntityType": "TASK",
      "isEnabled": true,
      "tags": ["api", "implementation"],
      "modifiedAt": "2025-10-17T19:00:00Z"
    }
  }
}
```

**When to use**: Simple name corrections or clarifications.

### Pattern 2: Update Tags Only
Replace all tags with new set.

```json
{
  "id": "template-uuid",
  "tags": "backend,api,rest,implementation,v2"
}
```

**When to use**: Improving template discoverability or categorization.

### Pattern 3: Update Multiple Fields
Change several properties at once.

```json
{
  "id": "template-uuid",
  "name": "REST API Implementation (v2)",
  "description": "Updated template for REST API implementation with modern patterns",
  "tags": "api,rest,backend,implementation,modern"
}
```

**When to use**: Comprehensive template metadata refresh.

### Pattern 4: Disable Template
Temporarily disable a template.

```json
{
  "id": "template-uuid",
  "isEnabled": false
}
```

**Note**: Consider using `disable_template` tool instead for clarity.

**When to use**: Deprecating templates temporarily.

### Pattern 5: Change Target Entity Type
Change what entity type the template applies to.

```json
{
  "id": "template-uuid",
  "targetEntityType": "FEATURE"
}
```

**WARNING**: This may make the template incompatible with existing applications. Use carefully.

**When to use**: Repurposing a template (rare).

## Common Workflows

### Workflow 1: Incremental Template Improvement
```javascript
// Step 1: Get current template
const template = await get_template({ id: templateId });

// Step 2: Review current metadata
console.log(`Current name: ${template.data.name}`);
console.log(`Current tags: ${template.data.tags.join(", ")}`);

// Step 3: Update with improvements
await update_template_metadata({
  id: templateId,
  name: template.data.name + " (Enhanced)",
  tags: [...template.data.tags, "enhanced", "v2"].join(",")
});
```

### Workflow 2: Template Standardization
Standardize template names and tags across project.

```javascript
const templates = await list_templates({ targetEntityType: "TASK" });

for (const template of templates.data.templates) {
  if (!template.isBuiltIn && !template.name.startsWith("Task:")) {
    await update_template_metadata({
      id: template.id,
      name: `Task: ${template.name}`,
      tags: `task,${template.tags.join(",")}`
    });
  }
}
```

### Workflow 3: Tag Cleanup
Clean up inconsistent tags.

```javascript
const template = await get_template({ id: templateId });

// Normalize tags (lowercase, deduplicate, sort)
const cleanTags = [...new Set(
  template.data.tags.map(t => t.toLowerCase().trim())
)].sort().join(",");

await update_template_metadata({
  id: templateId,
  tags: cleanTags
});
```

### Workflow 4: Template Deprecation
Mark template as deprecated without deleting.

```javascript
const template = await get_template({ id: templateId });

await update_template_metadata({
  id: templateId,
  name: `[DEPRECATED] ${template.data.name}`,
  isEnabled: false,
  tags: `deprecated,${template.data.tags.join(",")}`
});
```

## Tag Management Strategies

### Tag Categories
Organize tags into categories:

**Domain Tags**:
- frontend, backend, database, devops, infrastructure

**Purpose Tags**:
- implementation, testing, documentation, planning, workflow

**Technology Tags**:
- kotlin, javascript, postgresql, docker, kubernetes

**Version Tags**:
- v1, v2, legacy, modern, experimental

### Tag Conventions
```javascript
// Good tag sets
"backend,api,rest,implementation"           // Clear, specific
"frontend,ui,react,component"               // Technology-specific
"database,migration,postgresql,workflow"    // Multi-category

// Poor tag sets
"stuff,things,misc"                         // Too vague
"backend"                                   // Too minimal
"backend,back-end,backend-dev,be"          // Redundant variations
```

### Tag Update Pattern
```javascript
// Get current tags
const template = await get_template({ id: templateId });
const currentTags = new Set(template.data.tags);

// Add new tags
currentTags.add("enhanced");
currentTags.add("v2");

// Remove deprecated tags
currentTags.delete("old");
currentTags.delete("legacy");

// Update
await update_template_metadata({
  id: templateId,
  tags: Array.from(currentTags).sort().join(",")
});
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

**Solution**: Verify template ID with `list_templates`.

### Protected Template
```json
{
  "success": false,
  "message": "Cannot update protected template",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Template with ID ... is protected and cannot be updated"
  }
}
```

**Solution**: Protected templates cannot be updated. Use `disable_template` for reversible changes, or create a new template.

### Name Conflict
```json
{
  "success": false,
  "message": "Template name already exists",
  "error": {
    "code": "CONFLICT_ERROR",
    "details": "A template with the name '...' already exists"
  }
}
```

**Solution**: Choose a unique name.

### Empty Field
```json
{
  "success": false,
  "message": "Template name cannot be empty",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Solution**: Provide non-empty values for name and description.

### Invalid Entity Type
```json
{
  "success": false,
  "message": "Invalid target entity type: INVALID. Must be one of: TASK, FEATURE",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Solution**: Use "TASK" or "FEATURE".

## Common Mistakes to Avoid

### ❌ Mistake 1: Updating Protected Templates
```javascript
await update_template_metadata({
  id: protectedTemplateId,  // Built-in template
  name: "Modified"
});
// Error: Cannot update protected template
```

### ✅ Solution: Check Protection Status First
```javascript
const template = await get_template({ id: templateId });
if (template.data.isProtected) {
  console.log("Template is protected, cannot update");
} else {
  await update_template_metadata({ ... });
}
```

### ❌ Mistake 2: Accidentally Clearing Fields
```javascript
await update_template_metadata({
  id: templateId,
  tags: ""  // Removes all tags!
});
```

### ✅ Solution: Only Update Intended Fields
```javascript
// Only update name, leave tags unchanged
await update_template_metadata({
  id: templateId,
  name: "New Name"
  // Don't include tags parameter
});
```

### ❌ Mistake 3: Not Checking Name Uniqueness
```javascript
await update_template_metadata({
  id: templateId,
  name: "API Implementation"  // May conflict with existing
});
```

### ✅ Solution: Check for Conflicts First
```javascript
const templates = await list_templates({ targetEntityType: "TASK" });
const nameExists = templates.data.templates.some(t =>
  t.name === "API Implementation" && t.id !== templateId
);

if (!nameExists) {
  await update_template_metadata({ ... });
}
```

### ❌ Mistake 4: Changing Entity Type Carelessly
```javascript
await update_template_metadata({
  id: taskTemplateId,
  targetEntityType: "FEATURE"  // Now incompatible with tasks!
});
```

### ✅ Solution: Avoid Changing Entity Type
Create a new template instead of changing entity type on existing template.

## Best Practices

1. **Check Protection Status**: Verify template is not protected before updating
2. **Partial Updates**: Only send fields you want to change
3. **Validate Name Uniqueness**: Check for conflicts before renaming
4. **Preserve Tags**: Don't accidentally clear tags by sending empty string
5. **Avoid Entity Type Changes**: Create new template instead
6. **Use disable_template for Disabling**: More explicit than isEnabled=false
7. **Document Changes**: Update description when making significant changes
8. **Batch Related Updates**: Update multiple fields in one call

## When to Use Other Tools

### Use `disable_template` instead when:
- You just want to disable a template
- More explicit intent
- Works on protected templates

### Use `delete_template` instead when:
- Template is no longer needed
- Clean up obsolete templates
- Only works on non-protected user templates

### Use `enable_template` instead when:
- You just want to enable a template
- More explicit intent

### Use `create_template` instead when:
- Making major structural changes
- Changing entity type
- Creating variations

## Related Tools

- **create_template**: Create new templates
- **get_template**: View template details
- **delete_template**: Remove templates
- **enable_template**: Enable disabled templates
- **disable_template**: Disable templates
- **list_templates**: Discover templates
- **add_template_section**: Add sections to template

## See Also

- Template Management: `task-orchestrator://docs/tools/create-template`
- Template Lifecycle: `task-orchestrator://guidelines/template-strategy`
- Disabling Templates: `task-orchestrator://docs/tools/disable-template`
