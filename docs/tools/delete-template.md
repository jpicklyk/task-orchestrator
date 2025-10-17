# delete_template Tool - Detailed Documentation

## Overview

Deletes a user-created template permanently. Built-in templates cannot be deleted. Protected templates require force=true parameter. This is a permanent operation - prefer `disable_template` for reversibility.

**Resource**: `task-orchestrator://docs/tools/delete-template`

## Key Concepts

### Permanent Deletion
- **Irreversible operation** - template cannot be recovered
- Template and all its section definitions are removed
- Does NOT affect tasks/features that previously used the template

### Deletion Restrictions
- **Built-in templates**: Cannot be deleted (use `disable_template`)
- **Protected templates**: Require `force=true` parameter
- **User-created templates**: Can be deleted freely (if not protected)

### Safe Deletion Philosophy
**PREFER**: `disable_template` for reversibility
**USE delete_template**: Only when template is truly obsolete

## Parameter Reference

### Required Parameters
- **id** (UUID): Template ID to delete

### Optional Parameters
- **force** (boolean, default: false): Override protection
  - `false`: Protected/built-in templates cannot be deleted
  - `true`: Allows deletion of protected templates (use with caution)
  - Built-in templates still cannot be deleted even with force=true

## Usage Patterns

### Pattern 1: Delete User Template
Delete a simple user-created template.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Template deleted successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Custom API Template"
  }
}
```

**When to use**: Removing experimental or obsolete user templates.

### Pattern 2: Force Delete Protected Template
Delete a protected user template.

```json
{
  "id": "protected-template-uuid",
  "force": true
}
```

**WARNING**: This permanently deletes a protected template. Use only when absolutely necessary.

**When to use**: Cleanup of protected templates that are no longer needed.

### Pattern 3: Attempt Built-in Deletion (Will Fail)
```json
{
  "id": "builtin-template-uuid"
}
```

**Response**:
```json
{
  "success": false,
  "message": "Built-in templates cannot be deleted. Use 'disable_template' instead to make the template unavailable for use.",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Template '...' (id: ...) is a built-in template and cannot be deleted."
  }
}
```

**Solution**: Use `disable_template` instead.

## Common Workflows

### Workflow 1: Safe Deletion with Validation
```javascript
// Step 1: Get template details
const template = await get_template({ id: templateId });

// Step 2: Check if safe to delete
if (template.data.isBuiltIn) {
  console.log("Cannot delete built-in template");
  console.log("Use disable_template instead");
  return;
}

if (template.data.isProtected) {
  console.log("Template is protected");
  const confirm = await getUserConfirmation(
    `Delete protected template "${template.data.name}"?`
  );

  if (confirm) {
    await delete_template({ id: templateId, force: true });
  }
} else {
  // Safe to delete
  await delete_template({ id: templateId });
}
```

### Workflow 2: Cleanup Obsolete Templates
Remove deprecated or experimental templates.

```javascript
// Find obsolete templates
const templates = await list_templates({
  targetEntityType: "TASK",
  tags: "deprecated"
});

for (const template of templates.data.templates) {
  if (!template.isBuiltIn && !template.isProtected) {
    console.log(`Deleting: ${template.name}`);
    await delete_template({ id: template.id });
  } else {
    console.log(`Disabling: ${template.name}`);
    await disable_template({ id: template.id });
  }
}
```

### Workflow 3: Template Replacement
Replace an old template with a new version.

```javascript
// Step 1: Create new template with improved structure
const newTemplate = await create_template({
  name: "API Implementation v2",
  description: "Updated API implementation template",
  targetEntityType: "TASK",
  tags: "api,implementation,v2"
});

// Step 2: Add sections to new template
// ... (add sections) ...

// Step 3: Test new template
await apply_template({
  templateIds: [newTemplate.data.id],
  entityType: "TASK",
  entityId: testTaskId
});

// Step 4: If satisfied, delete old template
const oldTemplate = await list_templates({
  targetEntityType: "TASK",
  tags: "api,implementation,v1"
});

if (oldTemplate.data.templates.length > 0) {
  await delete_template({
    id: oldTemplate.data.templates[0].id
  });
}
```

### Workflow 4: Disable vs Delete Decision
```javascript
const template = await get_template({ id: templateId });

// Decision logic
if (template.data.isBuiltIn) {
  // Built-in: Can only disable
  await disable_template({ id: templateId });
  console.log("Built-in template disabled");

} else if (template.data.tags.includes("experimental")) {
  // Experimental: Safe to delete
  await delete_template({ id: templateId });
  console.log("Experimental template deleted");

} else if (template.data.tags.includes("deprecated")) {
  // Deprecated: Disable first, delete later
  await disable_template({ id: templateId });
  console.log("Deprecated template disabled (consider deleting later)");

} else {
  // In-use: Disable for reversibility
  await disable_template({ id: templateId });
  console.log("Template disabled (not deleted for safety)");
}
```

## Decision Guide: Disable vs Delete

### Use `disable_template` when:
- ✅ Template is built-in
- ✅ Template might be useful later
- ✅ Template is used in active projects
- ✅ You want reversibility
- ✅ Template is being deprecated gradually
- ✅ Unsure if template will be needed

### Use `delete_template` when:
- ✅ Template is clearly obsolete
- ✅ Template was experimental/temporary
- ✅ Template has been replaced by better version
- ✅ Template was created by mistake
- ✅ You're certain it won't be needed
- ✅ Template is user-created and unused

### Never Use Force Unless:
- ⚠️ Absolutely certain template should be deleted
- ⚠️ Template is protected but obsolete
- ⚠️ You understand consequences
- ⚠️ You've verified no dependencies

## Impact on Existing Entities

### What Happens to Tasks/Features?
**Sections are PRESERVED**: Tasks and features that already used the template keep their sections.

```javascript
// Before deletion
const task = await get_task({
  id: taskId,
  includeSections: true
});
// task has sections from template

await delete_template({ id: templateId });

// After deletion
const taskAfter = await get_task({
  id: taskId,
  includeSections: true
});
// task STILL has the same sections
// Only the template blueprint is gone
```

**Cannot Re-apply**: Cannot apply the deleted template to new entities.

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

**Common causes**:
- Template already deleted
- Wrong UUID
- Template from different installation

### Built-in Template
```json
{
  "success": false,
  "message": "Built-in templates cannot be deleted. Use 'disable_template' instead to make the template unavailable for use.",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Template '...' (id: ...) is a built-in template and cannot be deleted."
  }
}
```

**Solution**: Use `disable_template` instead.

### Protected Template Without Force
```json
{
  "success": false,
  "message": "Protected templates cannot be deleted without the force parameter.",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Template '...' is protected. Use force=true to override protection or use 'disable_template' instead."
  }
}
```

**Solution**: Add `force: true` or use `disable_template`.

## Common Mistakes to Avoid

### ❌ Mistake 1: Deleting When Disabling Would Suffice
```javascript
// Permanently deletes template
await delete_template({ id: templateId });

// Later: "Oh, I needed that template..."
```

### ✅ Solution: Prefer Disabling for Reversibility
```javascript
// Reversible
await disable_template({ id: templateId });

// Later: Can re-enable
await enable_template({ id: templateId });
```

### ❌ Mistake 2: Trying to Delete Built-in Templates
```javascript
await delete_template({ id: builtinTemplateId });
// Error: Built-in templates cannot be deleted
```

### ✅ Solution: Disable Built-in Templates
```javascript
await disable_template({ id: builtinTemplateId });
```

### ❌ Mistake 3: Not Checking Template Status First
```javascript
// Blindly attempting deletion
await delete_template({ id: templateId });
// May fail if protected or built-in
```

### ✅ Solution: Validate Before Deleting
```javascript
const template = await get_template({ id: templateId });

if (template.data.isBuiltIn) {
  await disable_template({ id: templateId });
} else if (template.data.isProtected) {
  // Decide if force deletion is justified
  const shouldForce = await getUserConfirmation();
  if (shouldForce) {
    await delete_template({ id: templateId, force: true });
  }
} else {
  await delete_template({ id: templateId });
}
```

### ❌ Mistake 4: Using Force Casually
```javascript
// Dangerous: Bypassing protection without consideration
await delete_template({ id: templateId, force: true });
```

### ✅ Solution: Use Force Only When Necessary
```javascript
// Get template details
const template = await get_template({ id: templateId });

// Careful decision
if (template.data.isProtected) {
  console.warn(`Template "${template.data.name}" is protected`);
  console.warn("Reason: Critical template for team workflow");
  console.warn("Consider disabling instead of deleting");

  // Only force if absolutely necessary
  const certainToDelete = await getExplicitConfirmation();
  if (certainToDelete) {
    await delete_template({ id: templateId, force: true });
  }
}
```

## Best Practices

1. **Prefer Disabling**: Use `disable_template` for reversibility
2. **Validate First**: Check template status before deletion
3. **Never Delete Built-in**: Use `disable_template` instead
4. **Use Force Sparingly**: Only for protected templates that must be deleted
5. **Consider Impact**: Verify template isn't actively used
6. **Document Reason**: Log why template was deleted
7. **Replace, Don't Just Delete**: Create replacement before deleting
8. **Test Replacement**: Verify new template works before deleting old

## Deletion Checklist

Before deleting a template, ask:

- [ ] Is this template built-in? → If yes, use disable instead
- [ ] Is this template protected? → If yes, do I need force=true?
- [ ] Could I need this template later? → If maybe, disable instead
- [ ] Is there a better replacement? → If yes, test replacement first
- [ ] Are there active tasks using this? → Check impact
- [ ] Have I verified the template ID? → Double-check UUID
- [ ] Is this truly obsolete? → If unsure, disable instead

## Related Tools

- **disable_template**: Reversible alternative to deletion
- **enable_template**: Re-enable disabled templates
- **update_template_metadata**: Update template instead of deleting
- **create_template**: Create replacement templates
- **list_templates**: Find templates to delete
- **get_template**: Inspect template before deletion

## See Also

- Template Lifecycle: `task-orchestrator://guidelines/template-strategy`
- Disabling Templates: `task-orchestrator://docs/tools/disable-template`
- Template Management: `task-orchestrator://docs/tools/create-template`
