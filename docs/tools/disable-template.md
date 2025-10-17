# disable_template Tool - Detailed Documentation

## Overview

Disables a template, preventing it from being discovered or applied to new tasks/features. Works with both built-in and user-created templates. Fully reversible with `enable_template`.

**Resource**: `task-orchestrator://docs/tools/disable-template`

## Key Concepts

### Template Disabled State
- **Enabled (true)**: Template appears in `list_templates` and can be applied
- **Disabled (false)**: Template hidden from discovery, cannot be applied to new entities

### Reversible Deactivation
- **NOT permanent**: Template can be re-enabled with `enable_template`
- **Safer than deletion**: Preserves template structure for potential future use
- **Preferred approach**: Use instead of `delete_template` for deprecation

### Impact on Existing Entities
- **No effect**: Tasks/features that already used the template keep their sections
- **Only affects future**: Cannot apply disabled template to new entities

## Parameter Reference

### Required Parameters
- **id** (UUID): Template ID to disable

### No Optional Parameters
Simple operation - just disable the template.

## Usage Patterns

### Pattern 1: Disable Outdated Template
Disable a template that's been superseded.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Template disabled successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "API Implementation Template (v1)",
    "description": "Original API implementation template",
    "targetEntityType": "TASK",
    "isBuiltIn": false,
    "isProtected": false,
    "isEnabled": false,
    "createdBy": "system",
    "tags": ["api", "implementation", "v1", "legacy"],
    "createdAt": "2025-01-01T10:00:00Z",
    "modifiedAt": "2025-10-17T19:00:00Z"
  }
}
```

**When to use**: Deprecating old template versions without deletion.

### Pattern 2: Disable Built-in Template
Disable a built-in template not relevant to your project.

```json
{
  "id": "builtin-git-workflow-template-id"
}
```

**When to use**: Project doesn't use Git, disable Git-related templates.

### Pattern 3: Temporary Disable During Maintenance
Disable template while making updates.

```javascript
// Disable before updates
await disable_template({ id: templateId });

// Update template sections
await add_template_section({ ... });
await update_template_metadata({ ... });

// Re-enable after updates
await enable_template({ id: templateId });
```

**When to use**: Preventing template use during modifications.

## Common Workflows

### Workflow 1: Template Deprecation
Properly deprecate a template in favor of a newer version.

```javascript
// Step 1: Get old template
const oldTemplate = await get_template({ id: oldTemplateId });

// Step 2: Mark as deprecated in metadata
await update_template_metadata({
  id: oldTemplateId,
  name: `[DEPRECATED] ${oldTemplate.data.name}`,
  description: `${oldTemplate.data.description}\n\nDEPRECATED: Use "${newTemplateName}" instead.`,
  tags: `deprecated,${oldTemplate.data.tags.join(",")}`
});

// Step 3: Disable the template
await disable_template({ id: oldTemplateId });

console.log(`Template "${oldTemplate.data.name}" deprecated and disabled`);
console.log(`Users should now use "${newTemplateName}"`);
```

### Workflow 2: Gradual Template Rollout
Disable old version only after new version is verified.

```javascript
// Step 1: Create new template version
const newTemplate = await create_template({
  name: "API Implementation v2",
  description: "Improved API implementation template",
  targetEntityType: "TASK",
  tags: "api,implementation,v2"
});

// Step 2: Add sections to new template
// ... add sections ...

// Step 3: Test new template extensively
await apply_template({
  templateIds: [newTemplate.data.id],
  entityType: "TASK",
  entityId: testTaskId
});

// Step 4: Verify test results
// ... manual verification ...

// Step 5: Only after verification, disable old
const verified = true; // From testing
if (verified) {
  await disable_template({ id: oldTemplateId });
  console.log("Old template disabled, new template is live");
} else {
  console.log("Issues found, keeping old template active");
}
```

### Workflow 3: Seasonal Template Management
Enable/disable templates based on time periods.

```javascript
// End of quarter - disable quarterly templates
const quarterlyTemplates = await list_templates({
  targetEntityType: "FEATURE",
  tags: "quarterly"
});

for (const template of quarterlyTemplates.data.templates) {
  if (template.isEnabled) {
    await disable_template({ id: template.id });
    console.log(`Disabled quarterly template: ${template.name}`);
  }
}

// Next quarter start - re-enable
for (const template of quarterlyTemplates.data.templates) {
  await enable_template({ id: template.id });
  console.log(`Enabled quarterly template: ${template.name}`);
}
```

### Workflow 4: Cleanup Experimental Templates
Disable experimental templates that didn't work out.

```javascript
const templates = await list_templates({
  targetEntityType: "TASK",
  tags: "experimental"
});

for (const template of templates.data.templates) {
  // Check if template has been used
  const templateDetails = await get_template({
    id: template.id,
    includeSections: true
  });

  // If experimental template hasn't been successful, disable it
  if (template.tags.includes("experimental") && template.tags.includes("failed")) {
    await disable_template({ id: template.id });
    console.log(`Disabled failed experimental template: ${template.name}`);
  }
}
```

## Disable vs Delete Decision Guide

### Use `disable_template` when:
- ✅ Template might be useful later
- ✅ Template is being deprecated gradually
- ✅ Template is built-in (cannot delete anyway)
- ✅ Want reversibility
- ✅ Unsure if template will be needed
- ✅ Template has historical value
- ✅ Testing new template version

### Use `delete_template` when:
- ✅ Template is clearly obsolete
- ✅ Template was experimental and failed
- ✅ Template created by mistake
- ✅ Certain it won't be needed
- ✅ Template has been disabled for extended period with no re-enable
- ❌ Never for built-in templates (only user-created)

### Recommended Approach:
```javascript
// Safe two-phase deprecation:
// Phase 1: Disable (reversible)
await disable_template({ id: templateId });

// Phase 2: After 30-90 days, if not re-enabled, consider deletion
// (Only for user-created templates)
if (!template.isBuiltIn && daysDisabled > 90) {
  await delete_template({ id: templateId });
}
```

## Common Workflows with Enable/Disable Cycle

### Pattern: A/B Testing Templates
```javascript
// Test two template versions
const templateA = "template-a-uuid";
const templateB = "template-b-uuid";

// Week 1: Test Template A
await enable_template({ id: templateA });
await disable_template({ id: templateB });
// Collect feedback...

// Week 2: Test Template B
await disable_template({ id: templateA });
await enable_template({ id: templateB });
// Collect feedback...

// Week 3: Choose winner
const templateAScore = 8.5;
const templateBScore = 9.2;

if (templateBScore > templateAScore) {
  await enable_template({ id: templateB });
  await disable_template({ id: templateA });
  console.log("Template B wins, now the standard");
}
```

### Pattern: Feature Flag Management
```javascript
// Configuration-driven template availability
const config = {
  enableLegacyTemplates: false,
  enableBetaTemplates: true,
  enableAdvancedTemplates: true
};

async function updateTemplateAvailability(config) {
  const templates = await list_templates({ targetEntityType: "TASK" });

  for (const template of templates.data.templates) {
    let shouldEnable = true;

    if (template.tags.includes("legacy") && !config.enableLegacyTemplates) {
      shouldEnable = false;
    }
    if (template.tags.includes("beta") && !config.enableBetaTemplates) {
      shouldEnable = false;
    }
    if (template.tags.includes("advanced") && !config.enableAdvancedTemplates) {
      shouldEnable = false;
    }

    if (shouldEnable && !template.isEnabled) {
      await enable_template({ id: template.id });
    } else if (!shouldEnable && template.isEnabled) {
      await disable_template({ id: template.id });
    }
  }
}
```

## Impact on Existing Entities

### What Happens to Tasks/Features?

**Sections are PRESERVED**:
```javascript
// Task created with template before disabling
const task = await create_task({
  title: "Implement API",
  templateIds: [templateId]
});

// Task has sections from template
const taskBefore = await get_task({
  id: task.data.id,
  includeSections: true
});
// taskBefore.data.sections = [section1, section2, section3]

// Disable template
await disable_template({ id: templateId });

// Task STILL has all sections
const taskAfter = await get_task({
  id: task.data.id,
  includeSections: true
});
// taskAfter.data.sections = [section1, section2, section3] (unchanged)
```

**Cannot Apply to New Entities**:
```javascript
// After disabling template
await disable_template({ id: templateId });

// This will fail
await apply_template({
  templateIds: [templateId],
  entityType: "TASK",
  entityId: newTaskId
});
// Error: Template not found or disabled
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

### Database Error
```json
{
  "success": false,
  "message": "Failed to disable template",
  "error": {
    "code": "DATABASE_ERROR",
    "details": "..."
  }
}
```

**Solution**: Retry operation or check database connectivity.

## Common Mistakes to Avoid

### ❌ Mistake 1: Disabling Without Communication
```javascript
// Silently disable template
await disable_template({ id: templateId });
```

**Problem**: Team members don't know template is unavailable.

### ✅ Solution: Communicate Changes
```javascript
const template = await get_template({ id: templateId });

await disable_template({ id: templateId });

console.log(`NOTICE: Template "${template.data.name}" has been disabled`);
console.log(`Reason: Superseded by new template version`);
console.log(`Alternative: Use "API Implementation v2" template`);

// Or send notification to team
```

### ❌ Mistake 2: Disabling Without Replacement
```javascript
// Disable only template for a task type
await disable_template({ id: onlyApiTemplateId });
// Now no API templates available!
```

### ✅ Solution: Ensure Replacement Exists
```javascript
// Check if replacement exists
const apiTemplates = await list_templates({
  targetEntityType: "TASK",
  tags: "api"
});

const enabledApiTemplates = apiTemplates.data.templates.filter(t =>
  t.id !== oldTemplateId && t.isEnabled
);

if (enabledApiTemplates.length > 0) {
  await disable_template({ id: oldTemplateId });
  console.log("Old template disabled, alternatives available");
} else {
  console.log("Cannot disable - no alternative API templates available");
}
```

### ❌ Mistake 3: Forgetting to Update Metadata
```javascript
// Disable without marking as deprecated
await disable_template({ id: templateId });
```

### ✅ Solution: Update Metadata When Disabling
```javascript
// Mark as deprecated in metadata
const template = await get_template({ id: templateId });

await update_template_metadata({
  id: templateId,
  name: `[DEPRECATED] ${template.data.name}`,
  description: `${template.data.description}\n\nDEPRECATED: Use "${newTemplateName}" instead.`,
  tags: `deprecated,${template.data.tags.join(",")}`
});

// Then disable
await disable_template({ id: templateId });
```

## Best Practices

1. **Communicate Changes**: Notify team when disabling templates
2. **Update Metadata**: Mark templates as deprecated before disabling
3. **Provide Alternatives**: Ensure replacement templates exist
4. **Gradual Deprecation**: Disable old after verifying new
5. **Document Reasons**: Log why template was disabled
6. **Two-Phase Approach**: Disable first, delete later (if at all)
7. **Prefer Over Deletion**: Use for reversible template management
8. **Test Replacements**: Verify new template works before disabling old

## Disable vs Update Metadata

### Use `disable_template` when:
- ✅ Only want to disable (single-purpose operation)
- ✅ Clear, explicit intent
- ✅ Working in enable/disable workflows
- ✅ Quick toggle operation

### Use `update_template_metadata` when:
- ✅ Disabling along with other changes
- ✅ Batch updating multiple properties
- ✅ Changing name, tags, description simultaneously

### Example Comparison:

**Using disable_template (preferred for just disabling)**:
```javascript
await disable_template({ id: templateId });
```

**Using update_template_metadata (if changing multiple fields)**:
```javascript
await update_template_metadata({
  id: templateId,
  isEnabled: false,
  name: "[DEPRECATED] Old Template",
  tags: "deprecated,legacy"
});
```

## Template Lifecycle Best Practices

### Complete Deprecation Process:
```javascript
// 1. Create replacement
const newTemplate = await create_template({ ... });
await add_template_section({ ... }); // Add sections

// 2. Test replacement
await apply_template({
  templateIds: [newTemplate.data.id],
  entityType: "TASK",
  entityId: testTaskId
});

// 3. Verify replacement works
// ... testing and validation ...

// 4. Update old template metadata
await update_template_metadata({
  id: oldTemplateId,
  name: `[DEPRECATED] ${oldTemplate.name}`,
  tags: `deprecated,${oldTemplate.tags.join(",")}`
});

// 5. Disable old template
await disable_template({ id: oldTemplateId });

// 6. Monitor (30-90 days)
// If no issues, keep disabled
// If problems, re-enable with enable_template

// 7. Eventually delete (optional, for user-created only)
await delete_template({ id: oldTemplateId });
```

## Related Tools

- **enable_template**: Re-enable disabled templates
- **delete_template**: Permanently remove templates
- **update_template_metadata**: Update template properties
- **create_template**: Create replacement templates
- **list_templates**: Discover templates
- **get_template**: View template details

## See Also

- Template Lifecycle: `task-orchestrator://guidelines/template-strategy`
- Enabling Templates: `task-orchestrator://docs/tools/enable-template`
- Deleting Templates: `task-orchestrator://docs/tools/delete-template`
- Template Management: `task-orchestrator://docs/tools/create-template`
