# apply_template Tool - Detailed Documentation

## Overview

Applies one or more templates to a task or feature, creating sections based on template definitions. Supports single or bulk template application for standardized documentation patterns.

**Resource**: `task-orchestrator://docs/tools/apply-template`

## Key Concepts

### Template Application Process
When you apply a template:
1. **Template sections are copied** to the target entity (task/feature)
2. **Content samples become actual content** in the created sections
3. **Section ordinals preserve order** from template definitions
4. **Multiple templates merge** their sections in array order

### Single vs Multiple Application
- **Single template**: Apply one template to an entity
- **Multiple templates**: Apply several templates at once, sections merge seamlessly

### When to Apply Templates

**PREFERRED**: During entity creation
```javascript
const task = await create_task({
  title: "Implement login API",
  templateIds: [templateId1, templateId2]  // Applied during creation
});
```

**ALTERNATIVE**: After entity creation
```javascript
await apply_template({
  templateIds: [templateId],
  entityType: "TASK",
  entityId: existingTaskId
});
```

**Why prefer creation-time application?**
- More efficient (one operation instead of two)
- Atomic transaction (template application included in creation)
- Cleaner workflow

## Parameter Reference

### Required Parameters
- **templateIds** (array of UUIDs): Template IDs to apply
  - Use single-item array for one template: `["uuid"]`
  - Use multi-item array for multiple: `["uuid1", "uuid2", "uuid3"]`

- **entityType** (enum): TASK or FEATURE
  - Must match template's targetEntityType

- **entityId** (UUID): Target task or feature ID
  - Entity must exist before applying template

## Usage Patterns

### Pattern 1: Single Template Application
Apply one template to a task.

```json
{
  "templateIds": ["550e8400-e29b-41d4-a716-446655440000"],
  "entityType": "TASK",
  "entityId": "661e8511-f30c-52e5-b827-557788990000"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Template applied successfully, created 3 sections",
  "data": {
    "templateId": "550e8400-e29b-41d4-a716-446655440000",
    "entityType": "TASK",
    "entityId": "661e8511-f30c-52e5-b827-557788990000",
    "sectionsCreated": 3,
    "sections": [
      { "id": "section-1", "title": "Requirements", "ordinal": 0 },
      { "id": "section-2", "title": "Implementation", "ordinal": 10 },
      { "id": "section-3", "title": "Testing", "ordinal": 20 }
    ]
  }
}
```

**When to use**: Adding standardized structure to an existing entity.

### Pattern 2: Multiple Template Application
Apply several templates to combine their sections.

```json
{
  "templateIds": [
    "template-technical-approach",
    "template-task-implementation",
    "template-testing-strategy"
  ],
  "entityType": "TASK",
  "entityId": "task-uuid"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Applied 3 templates successfully, created 9 sections",
  "data": {
    "entityType": "TASK",
    "entityId": "task-uuid",
    "totalSectionsCreated": 9,
    "appliedTemplates": [
      {
        "templateId": "template-technical-approach",
        "sectionsCreated": 3,
        "sections": [...]
      },
      {
        "templateId": "template-task-implementation",
        "sectionsCreated": 4,
        "sections": [...]
      },
      {
        "templateId": "template-testing-strategy",
        "sectionsCreated": 2,
        "sections": [...]
      }
    ]
  }
}
```

**When to use**: Complex tasks requiring multiple documentation aspects.

### Pattern 3: Development Task Template Combination
Recommended templates for standard development tasks.

```javascript
// Discover templates
const templates = await list_templates({
  targetEntityType: "TASK",
  isEnabled: true
});

// Select combination
const technicalApproach = templates.data.templates.find(t =>
  t.name.includes("Technical Approach")
);
const taskImplementation = templates.data.templates.find(t =>
  t.name.includes("Task Implementation")
);
const testingStrategy = templates.data.templates.find(t =>
  t.name.includes("Testing Strategy")
);

// Apply combination
await apply_template({
  templateIds: [
    technicalApproach.id,
    taskImplementation.id,
    testingStrategy.id
  ],
  entityType: "TASK",
  entityId: taskId
});
```

**When to use**: Standard implementation work requiring comprehensive documentation.

### Pattern 4: Bug Fix Template Combination
Recommended templates for bug resolution.

```javascript
const templates = await list_templates({
  targetEntityType: "TASK",
  tags: "bug,investigation"
});

const bugInvestigation = templates.data.templates.find(t =>
  t.name.includes("Bug Investigation")
);
const definitionOfDone = templates.data.templates.find(t =>
  t.name.includes("Definition of Done")
);

await apply_template({
  templateIds: [bugInvestigation.id, definitionOfDone.id],
  entityType: "TASK",
  entityId: bugTaskId
});
```

**When to use**: Systematic bug investigation and resolution.

### Pattern 5: Feature Planning Template Combination
Recommended templates for feature planning.

```javascript
const templates = await list_templates({
  targetEntityType: "FEATURE",
  isEnabled: true
});

await apply_template({
  templateIds: [
    templates.find(t => t.name.includes("Requirements")).id,
    templates.find(t => t.name.includes("Context & Background")).id,
    templates.find(t => t.name.includes("Architecture")).id
  ],
  entityType: "FEATURE",
  entityId: featureId
});
```

**When to use**: Large features requiring detailed planning documentation.

## Recommended Template Combinations

### Standard Development Task
```
Templates: Technical Approach + Task Implementation + Testing Strategy
Section Count: ~7-9 sections
Complexity: Medium (4-6)
Use: Regular feature implementation
```

### Bug Fix Task
```
Templates: Bug Investigation + Technical Approach + Definition of Done
Section Count: ~6-8 sections
Complexity: Varies (3-7)
Use: Systematic bug resolution
```

### Architecture Task
```
Templates: Architecture Overview + Technical Approach + Testing Strategy
Section Count: ~8-10 sections
Complexity: High (7-10)
Use: Major architectural changes
```

### Quick Fix Task
```
Templates: Task Implementation (or none)
Section Count: ~3-4 sections
Complexity: Low (1-3)
Use: Simple fixes, minor updates
```

### Feature Planning
```
Templates: Requirements Specification + Context & Background + Architecture Overview
Section Count: ~9-12 sections
Complexity: High (7-10)
Use: Feature planning and design
```

### Research/Spike Task
```
Templates: Technical Approach + Definition of Done
Section Count: ~5-6 sections
Complexity: Medium (4-6)
Use: Research and exploration
```

## Common Workflows

### Workflow 1: Discover and Apply
```javascript
// Step 1: Discover available templates
const templates = await list_templates({
  targetEntityType: "TASK",
  isEnabled: true
});

// Step 2: Review templates
console.log("Available templates:");
templates.data.templates.forEach(t => {
  console.log(`- ${t.name} [${t.tags.join(", ")}]`);
});

// Step 3: Select and apply
const selectedIds = templates.data.templates
  .filter(t => t.tags.includes("implementation"))
  .map(t => t.id);

await apply_template({
  templateIds: selectedIds,
  entityType: "TASK",
  entityId: taskId
});
```

### Workflow 2: Validate Before Apply
```javascript
// Validate entity exists
const task = await get_task({ id: taskId });
if (!task.success) {
  console.error("Task not found!");
  return;
}

// Validate templates
const templates = [];
for (const templateId of templateIds) {
  const template = await get_template({ id: templateId });

  if (!template.success) {
    console.error(`Template ${templateId} not found!`);
    return;
  }

  if (template.data.targetEntityType !== "TASK") {
    console.error(`Template ${template.data.name} is for ${template.data.targetEntityType}, not TASK!`);
    return;
  }

  if (!template.data.isEnabled) {
    console.error(`Template ${template.data.name} is disabled!`);
    return;
  }

  templates.push(template.data);
}

// All validated, proceed
await apply_template({
  templateIds: templateIds,
  entityType: "TASK",
  entityId: taskId
});
```

### Workflow 3: Progressive Template Application
Apply templates in stages as task evolves.

```javascript
// Initially: Just planning
await apply_template({
  templateIds: [requirementsTemplateId],
  entityType: "TASK",
  entityId: taskId
});

// Later: Add implementation guidance
await apply_template({
  templateIds: [implementationTemplateId],
  entityType: "TASK",
  entityId: taskId
});

// Finally: Add testing strategy
await apply_template({
  templateIds: [testingTemplateId],
  entityType: "TASK",
  entityId: taskId
});
```

## Section Ordinal Handling

When multiple templates are applied, sections are ordered by:
1. **Template order in array** (earlier templates' sections come first)
2. **Ordinal within each template** (preserves template's internal ordering)

### Example:
```javascript
// Template A sections: ordinal 0, 10, 20
// Template B sections: ordinal 0, 10
// Result order: A(0), A(10), A(20), B(0), B(10)

await apply_template({
  templateIds: [templateA_id, templateB_id],
  entityType: "TASK",
  entityId: taskId
});
```

## Error Handling

### Entity Not Found
```json
{
  "success": false,
  "message": "Entity not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No task exists with ID ..."
  }
}
```

**Solution**: Verify entity exists with `get_task` or `get_feature`.

### Template Not Found
```json
{
  "success": false,
  "message": "One or more templates or the entity not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No template exists with ID ..."
  }
}
```

**Solution**: Use `list_templates` to discover valid template IDs.

### Type Mismatch
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Template targetEntityType FEATURE does not match TASK"
  }
}
```

**Solution**: Ensure template targetEntityType matches entityType parameter.

## Common Mistakes to Avoid

### ❌ Mistake 1: Type Mismatch
```json
{
  "templateIds": ["feature-template-uuid"],
  "entityType": "TASK",
  "entityId": "task-uuid"
}
```
**Problem**: Applying FEATURE template to TASK.

### ✅ Solution: Match Template Type to Entity Type
Always verify template.targetEntityType === entityType.

### ❌ Mistake 2: Applying After Creation Instead of During
```javascript
// Inefficient
const task = await create_task({ title: "..." });
await apply_template({
  templateIds: [templateId],
  entityType: "TASK",
  entityId: task.data.id
});
```

### ✅ Solution: Apply During Creation
```javascript
// Efficient
const task = await create_task({
  title: "...",
  templateIds: [templateId]
});
```

### ❌ Mistake 3: Hardcoding Template IDs
```javascript
await apply_template({
  templateIds: ["550e8400-e29b-41d4-a716-446655440000"],  // Hardcoded!
  ...
});
```

### ✅ Solution: Dynamic Discovery
```javascript
const templates = await list_templates({
  targetEntityType: "TASK",
  tags: "implementation"
});

await apply_template({
  templateIds: templates.data.templates.map(t => t.id),
  ...
});
```

### ❌ Mistake 4: Empty Template Array
```json
{
  "templateIds": [],
  "entityType": "TASK",
  "entityId": "task-uuid"
}
```

**Problem**: Must provide at least one template ID.

### ✅ Solution: Always Include Template IDs
Validate array is non-empty before calling tool.

## Best Practices

1. **Prefer Creation-Time Application**: Use templateIds during create_task/create_feature
2. **Discover Templates Dynamically**: Never hardcode template UUIDs
3. **Validate Template Types**: Ensure targetEntityType matches
4. **Use Recommended Combinations**: Follow proven template combinations
5. **Apply Multiple Templates Together**: More efficient than separate calls
6. **Check Template Enabled Status**: Verify templates are enabled before applying
7. **Handle Errors Gracefully**: Templates may be deleted or disabled

## Performance Considerations

### Single vs Multiple Application
- **Single**: One database transaction, minimal overhead
- **Multiple**: Still one transaction, all sections created atomically
- **Prefer multiple templates in one call** over separate calls

### Token Usage
- Tool call: ~200-300 tokens
- Response: ~100-500 tokens (varies by section count)
- Total: ~300-800 tokens per application

## Related Tools

- **create_task**: Apply templates during task creation (preferred)
- **create_feature**: Apply templates during feature creation (preferred)
- **list_templates**: Discover available templates
- **get_template**: Inspect template structure
- **add_template_section**: Define template sections
- **get_sections**: View applied sections on entity

## See Also

- Template Discovery: `task-orchestrator://guidelines/template-strategy`
- Task Creation: `task-orchestrator://docs/tools/create-task`
- Template Management: `task-orchestrator://docs/tools/create-template`
