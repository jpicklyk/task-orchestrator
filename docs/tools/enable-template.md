# enable_template Tool - Detailed Documentation

## Overview

Enables a previously disabled template, making it available for discovery and application to tasks/features. Works with both built-in and user-created templates.

**Resource**: `task-orchestrator://docs/tools/enable-template`

## Key Concepts

### Template Enabled State
- **Enabled (true)**: Template appears in `list_templates` and can be applied
- **Disabled (false)**: Template hidden from discovery, cannot be applied

### When Templates Get Disabled
- **Manual disabling**: Via `disable_template` or `update_template_metadata`
- **Deprecation**: Marking templates as no longer recommended
- **Testing**: Disabling during template development/modification
- **Seasonal/temporary**: Hiding templates not currently relevant

### Reversibility
Enabling is fully reversible - templates can be disabled again at any time with `disable_template`.

## Parameter Reference

### Required Parameters
- **id** (UUID): Template ID to enable

### No Optional Parameters
Simple operation - just enable the template.

## Usage Patterns

### Pattern 1: Re-enable Disabled Template
Enable a template that was previously disabled.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Template enabled successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "API Implementation Template",
    "description": "Standard REST API implementation template",
    "targetEntityType": "TASK",
    "isBuiltIn": false,
    "isProtected": false,
    "isEnabled": true,
    "createdBy": "system",
    "tags": ["api", "implementation", "backend"],
    "createdAt": "2025-10-17T10:00:00Z",
    "modifiedAt": "2025-10-17T19:00:00Z"
  }
}
```

**When to use**: Restoring previously disabled templates.

### Pattern 2: Enable Built-in Template
Re-enable a disabled built-in template.

```json
{
  "id": "builtin-template-uuid"
}
```

**When to use**: Built-in template was disabled but is now needed again.

### Pattern 3: Batch Enable Templates
Enable multiple templates in a workflow.

```javascript
const disabledTemplates = await list_templates({
  targetEntityType: "TASK",
  isEnabled: false  // Note: list_templates may not support this filter
});

for (const template of templatesToEnable) {
  await enable_template({ id: template.id });
  console.log(`Enabled: ${template.name}`);
}
```

**When to use**: Re-enabling a set of related templates.

## Common Workflows

### Workflow 1: Enable After Testing
Enable template after testing and validation.

```javascript
// Step 1: Create template (initially enabled by default)
const template = await create_template({
  name: "New Workflow Template",
  description: "Experimental workflow template",
  targetEntityType: "TASK",
  isEnabled: false,  // Start disabled
  tags: "workflow,experimental"
});

// Step 2: Add sections
// ... add template sections ...

// Step 3: Test template
await apply_template({
  templateIds: [template.data.id],
  entityType: "TASK",
  entityId: testTaskId
});

// Step 4: Review results
const testTask = await get_task({
  id: testTaskId,
  includeSections: true
});

// Step 5: If satisfied, enable for general use
await enable_template({ id: template.data.id });
console.log("Template enabled for general use");
```

### Workflow 2: Seasonal Template Activation
Enable templates for specific time periods.

```javascript
// Start of new quarter
const quarterlyTemplates = [
  "quarterly-planning-template-id",
  "okr-template-id",
  "quarterly-review-template-id"
];

for (const templateId of quarterlyTemplates) {
  await enable_template({ id: templateId });
  console.log(`Enabled quarterly template: ${templateId}`);
}
```

### Workflow 3: Feature Toggle Pattern
Enable templates based on feature flags or configuration.

```javascript
// Configuration-driven template enablement
const config = {
  enableAdvancedTemplates: true,
  enableExperimentalTemplates: false
};

const templates = await list_templates({ targetEntityType: "TASK" });

for (const template of templates.data.templates) {
  const shouldEnable =
    (template.tags.includes("advanced") && config.enableAdvancedTemplates) ||
    (template.tags.includes("experimental") && config.enableExperimentalTemplates);

  if (shouldEnable && !template.isEnabled) {
    await enable_template({ id: template.id });
  }
}
```

### Workflow 4: Template Discovery and Enable
Discover disabled templates and selectively enable.

```javascript
// Get all templates
const allTemplates = await list_templates({ targetEntityType: "TASK" });

// Filter disabled templates
const disabledTemplates = allTemplates.data.templates.filter(
  t => !t.isEnabled
);

console.log("Disabled templates:");
disabledTemplates.forEach((t, i) => {
  console.log(`${i + 1}. ${t.name} [${t.tags.join(", ")}]`);
});

// Enable specific template by index
const templateToEnable = disabledTemplates[0];
await enable_template({ id: templateToEnable.id });
console.log(`Enabled: ${templateToEnable.name}`);
```

## Enable vs Update Metadata

### Use `enable_template` when:
- ✅ Only want to enable a template
- ✅ Clear, explicit intent
- ✅ Simple, single-purpose operation
- ✅ Working in workflows focused on enabling

### Use `update_template_metadata` when:
- ✅ Enabling along with other changes (name, tags, etc.)
- ✅ Batch updating multiple properties
- ✅ More complex metadata updates

### Example Comparison:

**Using enable_template (preferred for just enabling)**:
```javascript
await enable_template({ id: templateId });
```

**Using update_template_metadata (if changing multiple fields)**:
```javascript
await update_template_metadata({
  id: templateId,
  isEnabled: true,
  name: "Updated Name",
  tags: "new,tags"
});
```

## Common Workflows with Disable/Enable Cycle

### Pattern: Deprecation and Replacement
```javascript
// Step 1: Create replacement template
const newTemplate = await create_template({
  name: "API Implementation v2",
  description: "Improved API implementation template",
  targetEntityType: "TASK",
  tags: "api,implementation,v2"
});

// Step 2: Add sections to new template
// ... add sections ...

// Step 3: Disable old template
await disable_template({ id: oldTemplateId });

// Step 4: Test new template
// ... testing ...

// Step 5: If issues found, rollback
await enable_template({ id: oldTemplateId });
await disable_template({ id: newTemplate.data.id });

// OR Step 5: If successful, keep new
console.log("New template is live, old template disabled");
```

### Pattern: Maintenance Mode
```javascript
// Enter maintenance mode
const maintenanceTemplates = await list_templates({
  targetEntityType: "TASK",
  tags: "maintenance"
});

for (const template of maintenanceTemplates.data.templates) {
  if (!template.isEnabled) {
    await enable_template({ id: template.id });
  }
}

console.log("Maintenance templates enabled");

// Exit maintenance mode (later)
for (const template of maintenanceTemplates.data.templates) {
  await disable_template({ id: template.id });
}

console.log("Maintenance templates disabled");
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

**Common causes**:
- Template was deleted
- Wrong UUID
- Template from different installation

**Solution**: Use `list_templates` to verify template exists.

### Database Error
```json
{
  "success": false,
  "message": "Failed to enable template",
  "error": {
    "code": "DATABASE_ERROR",
    "details": "..."
  }
}
```

**Common causes**:
- Database connectivity issues
- Concurrent modification

**Solution**: Retry operation.

## Common Mistakes to Avoid

### ❌ Mistake 1: Enabling Without Verification
```javascript
// Blindly enabling template
await enable_template({ id: templateId });
```

**Problem**: Template might be incomplete or broken.

### ✅ Solution: Verify Template First
```javascript
// Check template structure
const template = await get_template({
  id: templateId,
  includeSections: true
});

if (template.data.sections.length === 0) {
  console.log("Template has no sections, not enabling yet");
} else {
  await enable_template({ id: templateId });
}
```

### ❌ Mistake 2: Enabling Incomplete Templates
```javascript
const template = await create_template({ ... });
await enable_template({ id: template.data.id });
// Template has no sections yet!
```

### ✅ Solution: Complete Template Before Enabling
```javascript
// Create template (start disabled)
const template = await create_template({
  ...,
  isEnabled: false
});

// Add sections
await add_template_section({ ... });
await add_template_section({ ... });

// Test template
// ... testing ...

// Enable after completion
await enable_template({ id: template.data.id });
```

### ❌ Mistake 3: Not Communicating Template Availability
```javascript
// Enable template silently
await enable_template({ id: templateId });
```

### ✅ Solution: Log or Notify About Changes
```javascript
const template = await get_template({ id: templateId });
await enable_template({ id: templateId });
console.log(`Template "${template.data.name}" is now available for use`);
// Or send notification to team
```

## Best Practices

1. **Verify Template First**: Check template structure before enabling
2. **Test Before Enabling**: Apply to test task/feature to verify functionality
3. **Complete Sections**: Ensure template has all sections before enabling
4. **Document Changes**: Log or notify when templates are enabled
5. **Pair with Disable**: Use disable/enable for reversible template management
6. **Gradual Rollout**: Enable for testing before general availability
7. **Version Management**: Use tags (v1, v2) to manage template versions

## Template Lifecycle Management

### Complete Template Lifecycle:
```javascript
// 1. Create (disabled during development)
const template = await create_template({
  name: "New Template",
  description: "...",
  targetEntityType: "TASK",
  isEnabled: false,
  tags: "experimental"
});

// 2. Develop (add sections)
await add_template_section({ ... });
await add_template_section({ ... });

// 3. Test
await apply_template({
  templateIds: [template.data.id],
  entityType: "TASK",
  entityId: testTaskId
});

// 4. Enable for general use
await enable_template({ id: template.data.id });

// 5. If issues found, disable
await disable_template({ id: template.data.id });

// 6. Fix and re-enable
await enable_template({ id: template.data.id });

// 7. Eventually deprecate
await update_template_metadata({
  id: template.data.id,
  name: "[DEPRECATED] New Template",
  tags: "deprecated,experimental"
});
await disable_template({ id: template.data.id });

// 8. Finally delete (optional)
await delete_template({ id: template.data.id });
```

## Related Tools

- **disable_template**: Disable templates (reverse of enable)
- **update_template_metadata**: Update template properties including isEnabled
- **create_template**: Create templates (can set initial enabled state)
- **list_templates**: Discover templates (filter by enabled status)
- **get_template**: View template details
- **apply_template**: Apply templates to tasks/features

## See Also

- Template Lifecycle: `task-orchestrator://guidelines/template-strategy`
- Disabling Templates: `task-orchestrator://docs/tools/disable-template`
- Template Management: `task-orchestrator://docs/tools/create-template`
