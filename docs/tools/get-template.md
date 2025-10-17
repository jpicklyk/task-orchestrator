# get_template Tool - Detailed Documentation

## Overview

Retrieves complete template information by ID with optional section definitions. Supports progressive loading for context efficiency.

**Resource**: `task-orchestrator://docs/tools/get-template`

## Key Concepts

### Progressive Loading Strategy
Templates can be loaded in two modes:
- **Metadata Only**: Just template properties (~200 tokens)
- **Full Template**: Template + all section definitions (~1000-3000 tokens)

### Content Architecture
Templates store their structure in two parts:
- **Template entity**: Core metadata (name, description, targetEntityType, flags)
- **Section definitions**: Structured section blueprints (title, contentSample, ordinal)

**CRITICAL**: To see section definitions, set `includeSections=true`.

## Parameter Reference

### Required Parameters
- **id** (UUID): Template ID to retrieve

### Optional Parameters
- **includeSections** (boolean, default: false): Include section definitions in response
  - `false`: Lightweight metadata query
  - `true`: Full template with section definitions

## Usage Patterns

### Pattern 1: Quick Metadata Check
Get template properties without loading sections.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response size**: ~200 tokens

**When to use**:
- Verify template exists
- Check if template is enabled
- Get template name/description
- Quick validation

### Pattern 2: Full Template Inspection
Get complete template with all section definitions.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "includeSections": true
}
```

**Response size**: ~1000-3000 tokens (varies by section count)

**When to use**:
- Understanding template structure
- Planning template application
- Reviewing section definitions
- Analyzing template content

### Pattern 3: Template Validation Before Application
Check template compatibility before applying.

```javascript
// Step 1: Get template with sections
const template = await get_template({
  id: templateId,
  includeSections: true
});

// Step 2: Validate compatibility
if (template.data.targetEntityType === "TASK") {
  // Step 3: Apply to task
  await apply_template({
    templateIds: [templateId],
    entityType: "TASK",
    entityId: taskId
  });
}
```

**When to use**: Before applying template to ensure it matches entity type.

### Pattern 4: Template Discovery and Inspection
Discover templates, then inspect specific ones.

```javascript
// Step 1: List available templates
const templates = await list_templates({
  targetEntityType: "TASK",
  isEnabled: true
});

// Step 2: Inspect interesting templates
for (const template of templates.data.templates.slice(0, 3)) {
  const fullTemplate = await get_template({
    id: template.id,
    includeSections: true
  });

  console.log(`Template: ${fullTemplate.data.name}`);
  console.log(`Sections: ${fullTemplate.data.sections.length}`);
}
```

**When to use**: Exploring available templates to understand options.

## Response Structure

### Minimal Response (includeSections=false)
```json
{
  "success": true,
  "message": "Template retrieved successfully",
  "data": {
    "id": "uuid",
    "name": "Template name",
    "description": "Template description",
    "targetEntityType": "TASK",
    "isBuiltIn": false,
    "isProtected": false,
    "isEnabled": true,
    "tags": ["tag1", "tag2"],
    "createdAt": "ISO-8601 timestamp",
    "modifiedAt": "ISO-8601 timestamp"
  }
}
```

### Full Response (includeSections=true)
```json
{
  "success": true,
  "message": "Template retrieved successfully",
  "data": {
    "id": "uuid",
    "name": "API Implementation",
    "description": "Standard REST API implementation template",
    "targetEntityType": "TASK",
    "isBuiltIn": false,
    "isProtected": false,
    "isEnabled": true,
    "createdBy": "system",
    "tags": ["implementation", "api", "backend"],
    "createdAt": "ISO-8601",
    "modifiedAt": "ISO-8601",
    "sections": [
      {
        "id": "section-uuid",
        "title": "Requirements",
        "usageDescription": "API endpoint requirements and specifications",
        "contentSample": "### Endpoint Specifications\n- Method: POST\n- Path: /api/users\n...",
        "contentFormat": "markdown",
        "ordinal": 0,
        "isRequired": true,
        "tags": ["requirements", "api"]
      },
      {
        "id": "section-uuid-2",
        "title": "Implementation Steps",
        "usageDescription": "Step-by-step implementation guide",
        "contentSample": "1. Define request/response models\n2. Implement handler...",
        "contentFormat": "markdown",
        "ordinal": 10,
        "isRequired": false,
        "tags": ["implementation"]
      }
    ]
  }
}
```

## Common Workflows

### Workflow 1: Inspect Template Before Use
```javascript
// Get template with sections
const template = await get_template({
  id: templateId,
  includeSections: true
});

// Review sections
console.log(`Template: ${template.data.name}`);
console.log(`Target: ${template.data.targetEntityType}`);
console.log(`Sections: ${template.data.sections.length}`);

template.data.sections.forEach(section => {
  console.log(`  - ${section.title} (ordinal: ${section.ordinal})`);
});
```

### Workflow 2: Validate Template Compatibility
```javascript
// Get template metadata
const template = await get_template({ id: templateId });

// Check if compatible with entity
if (template.data.targetEntityType !== entityType) {
  console.error("Template type mismatch!");
  return;
}

if (!template.data.isEnabled) {
  console.error("Template is disabled!");
  return;
}

// Proceed with application
await apply_template({ templateIds: [templateId], entityType, entityId });
```

### Workflow 3: Clone Template Structure
```javascript
// Get existing template
const sourceTemplate = await get_template({
  id: sourceTemplateId,
  includeSections: true
});

// Create new template with similar structure
const newTemplate = await create_template({
  name: `${sourceTemplate.data.name} (Modified)`,
  description: sourceTemplate.data.description,
  targetEntityType: sourceTemplate.data.targetEntityType,
  tags: sourceTemplate.data.tags.join(",")
});

// Clone sections
for (const section of sourceTemplate.data.sections) {
  await add_template_section({
    templateId: newTemplate.data.id,
    title: section.title,
    usageDescription: section.usageDescription,
    contentSample: section.contentSample,
    contentFormat: section.contentFormat,
    ordinal: section.ordinal,
    isRequired: section.isRequired,
    tags: section.tags.join(",")
  });
}
```

### Workflow 4: Template Comparison
```javascript
// Compare two templates
const template1 = await get_template({
  id: templateId1,
  includeSections: true
});

const template2 = await get_template({
  id: templateId2,
  includeSections: true
});

console.log("Template Comparison:");
console.log(`${template1.data.name}: ${template1.data.sections.length} sections`);
console.log(`${template2.data.name}: ${template2.data.sections.length} sections`);

// Compare section titles
const titles1 = new Set(template1.data.sections.map(s => s.title));
const titles2 = new Set(template2.data.sections.map(s => s.title));

console.log("Unique to template 1:",
  template1.data.sections
    .filter(s => !titles2.has(s.title))
    .map(s => s.title)
);
```

## Performance Considerations

### Token Usage by Configuration

| Configuration | Approximate Tokens | Use Case |
|---------------|-------------------|----------|
| Metadata only (default) | ~200 | Quick checks |
| With sections (3 sections) | ~1000 | Small templates |
| With sections (6 sections) | ~2000 | Standard templates |
| With sections (10+ sections) | ~3000+ | Complex templates |

### Optimization Strategies

1. **Start Minimal**: Get metadata first, expand only if needed
2. **Batch Inspection**: If reviewing multiple templates, consider metadata-only first pass
3. **Cache Results**: Store template data locally during workflow
4. **Selective Loading**: Only load sections when actually needed

## Error Handling

### Template Not Found
```json
{
  "success": false,
  "message": "Template not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No template exists with ID 550e8400-..."
  }
}
```

**Common causes**:
- Template was deleted
- Wrong UUID provided
- Template ID from different installation

**Solution**: Use `list_templates` to discover valid template IDs.

### Invalid UUID Format
```json
{
  "success": false,
  "message": "Invalid input",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid template ID format. Must be a valid UUID."
  }
}
```

**Solution**: Ensure ID is a valid UUID format.

### Sections Retrieval Failed
```json
{
  "success": true,
  "message": "Template retrieved successfully (sections retrieval failed)",
  "data": {
    "id": "uuid",
    "name": "Template name",
    ...
  }
}
```

**Note**: Tool still returns template metadata even if sections fail to load.

## Common Mistakes to Avoid

### ❌ Mistake 1: Not Loading Sections When Needed
```javascript
const template = await get_template({ id: templateId });
// template.data.sections is undefined!
```

**Problem**: Sections not included by default.

### ✅ Solution: Request Sections Explicitly
```javascript
const template = await get_template({
  id: templateId,
  includeSections: true
});
```

### ❌ Mistake 2: Loading Sections Unnecessarily
```javascript
// Just checking if template is enabled
const template = await get_template({
  id: templateId,
  includeSections: true  // Wasteful!
});
```

**Problem**: Loading sections wastes tokens for simple checks.

### ✅ Solution: Minimal Fetch for Simple Checks
```javascript
const template = await get_template({ id: templateId });
if (template.data.isEnabled) { ... }
```

### ❌ Mistake 3: Hardcoding Template IDs
```javascript
const template = await get_template({
  id: "550e8400-e29b-41d4-a716-446655440000"  // Hardcoded!
});
```

**Problem**: Template UUIDs vary between installations.

### ✅ Solution: Dynamic Template Discovery
```javascript
const templates = await list_templates({
  targetEntityType: "TASK",
  tags: "implementation"
});

const template = await get_template({
  id: templates.data.templates[0].id
});
```

## Best Practices

1. **Start with Metadata**: Load sections only when actually needed
2. **Validate Before Use**: Check targetEntityType and isEnabled
3. **Cache Locally**: Store template data during workflow session
4. **Handle Errors Gracefully**: Template may be deleted between discovery and retrieval
5. **Use with list_templates**: Discover first, then inspect specific templates
6. **Check Section Count**: Large templates may require context management

## Related Tools

- **list_templates**: Discover available templates before retrieving
- **create_template**: Create new templates
- **apply_template**: Apply template to tasks/features
- **add_template_section**: Add sections to template
- **update_template_metadata**: Update template properties
- **enable_template**: Enable disabled templates
- **disable_template**: Disable templates

## See Also

- Template Discovery Guide: `task-orchestrator://guidelines/template-strategy`
- Template Management: `task-orchestrator://docs/tools/create-template`
- Section Management: `task-orchestrator://docs/tools/add-template-section`
