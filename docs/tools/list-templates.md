# list_templates Tool - Detailed Documentation

## Overview

Discover available templates for tasks and features. Templates provide structured documentation patterns and workflow guidance.

**Resource**: `task-orchestrator://docs/tools/list-templates`

**CRITICAL**: Always discover templates dynamically. Never hardcode template IDs or assume templates exist.

## Why Template Discovery Matters

Templates are:
- **Database-driven**: Stored in database, not code
- **Runtime configurable**: Can be enabled/disabled
- **Installation-specific**: UUIDs vary between installations
- **User-extensible**: Custom templates can be created

**Never assume templates exist. Always use `list_templates` first.**

## Parameter Reference

### Optional Parameters
- **targetEntityType** (enum): TASK | FEATURE (filter by entity type)
- **isEnabled** (boolean): Filter enabled/disabled templates
- **tags** (string): Comma-separated tags to filter

## Usage Patterns

### Pattern 1: Discover All Task Templates
Basic template discovery for task creation.

```json
{
  "targetEntityType": "TASK",
  "isEnabled": true
}
```

**Response**: All enabled templates applicable to tasks
**When to use**: Before creating any task with templates

### Pattern 2: Discover Feature Templates
Template discovery for feature creation.

```json
{
  "targetEntityType": "FEATURE",
  "isEnabled": true
}
```

**Response**: All enabled templates applicable to features
**When to use**: Before creating features with documentation

### Pattern 3: Filter by Tag
Find specific template types by tag.

```json
{
  "targetEntityType": "TASK",
  "isEnabled": true,
  "tags": "workflow"
}
```

**Response**: Task templates tagged with "workflow"
**When to use**: Finding workflow-specific templates

### Pattern 4: Discover All Templates
See everything available (including disabled).

```json
{}
```

**Response**: All templates (enabled and disabled, all types)
**When to use**: Administration, configuration

## Template Categories

Templates fall into three main categories:

### 1. Workflow Instructions (AI Guidance)
**Purpose**: Step-by-step process guidance

**Characteristics**:
- Executable instructions for AI agents
- Integrate with MCP tool calls
- Focus on HOW to execute tasks

**Common Examples**:
- Local Git Branching Workflow
- GitHub PR Workflow
- Task Implementation Workflow
- Bug Investigation Workflow

**Tags**: `workflow`, `git`, `process`

### 2. Documentation Structure
**Purpose**: Organized information capture

**Characteristics**:
- Define content organization
- Provide documentation templates
- Focus on WHAT to document

**Common Examples**:
- Technical Approach
- Requirements Specification
- Context & Background
- Architecture Overview

**Tags**: `documentation`, `requirements`, `architecture`

### 3. Quality Standards
**Purpose**: Completion criteria and quality gates

**Characteristics**:
- Define "done" criteria
- Establish quality standards
- Provide validation checklists

**Common Examples**:
- Testing Strategy
- Definition of Done

**Tags**: `quality`, `testing`, `validation`

## Response Structure

```json
{
  "success": true,
  "message": "Found N templates",
  "data": {
    "templates": [
      {
        "id": "uuid",
        "name": "Task Implementation Workflow",
        "description": "Step-by-step guidance for implementing tasks...",
        "targetEntityType": "TASK",
        "isEnabled": true,
        "isBuiltIn": true,
        "tags": ["workflow", "implementation"],
        "sections": [
          {
            "id": "section-uuid",
            "title": "Implementation Analysis",
            "usageDescription": "Analyze task requirements before implementation",
            "contentFormat": "MARKDOWN",
            "ordinal": 0
          }
        ],
        "createdAt": "ISO-8601",
        "modifiedAt": "ISO-8601"
      }
    ],
    "count": 5
  }
}
```

## Template Selection Decision Trees

### For Task Creation

**Q1: Git repository detected?**
- YES → Include git workflow template
- NO → Skip git templates

**Q2: Task complexity?**
- Low (1-3) → 0-1 templates (maybe just Implementation)
- Medium (4-6) → 1-2 templates (Implementation + workflow)
- High (7-10) → 2-4 templates (Implementation + Technical Approach + workflow + quality)

**Q3: Task type?**
- Feature → Implementation + Git workflows
- Bug → Bug Investigation + Git workflows
- Research → Technical Approach only
- Documentation → Minimal templates

**Q4: Quality requirements?**
- Critical → Include Testing Strategy / Definition of Done
- Standard → Optional quality templates

### For Feature Creation

**Q1: Feature phase?**
- Planning → Context & Background + Requirements
- Development → Requirements + Technical Approach
- Complete docs → All documentation templates

**Q2: User-facing?**
- YES → Include Context & Background
- NO → Focus on technical templates

**Q3: Architecture decisions needed?**
- YES → Include Technical Approach
- NO → Skip if simple feature

## Selection Examples

### Example 1: Simple Bug Fix
```javascript
const templates = await list_templates({
  targetEntityType: "TASK",
  isEnabled: true,
  tags: "workflow"
});

// Select minimal templates for simple bug
const gitWorkflow = templates.data.templates.find(t =>
  t.name.includes("Git") && t.name.includes("Branching")
);

const task = await create_task({
  title: "Fix login button alignment",
  complexity: 2,
  templateIds: gitWorkflow ? [gitWorkflow.id] : []
});
```

### Example 2: Complex Feature Task
```javascript
const templates = await list_templates({
  targetEntityType: "TASK",
  isEnabled: true
});

// Select comprehensive templates for complex work
const selectedTemplates = templates.data.templates
  .filter(t =>
    t.tags.includes("implementation") ||
    t.tags.includes("architecture") ||
    t.tags.includes("workflow") ||
    t.tags.includes("testing")
  )
  .map(t => t.id);

const task = await create_task({
  title: "Design distributed caching architecture",
  complexity: 9,
  templateIds: selectedTemplates
});
```

### Example 3: Feature with Documentation
```javascript
const templates = await list_templates({
  targetEntityType: "FEATURE",
  isEnabled: true
});

// Select documentation templates
const docTemplates = templates.data.templates
  .filter(t => t.tags.includes("documentation"))
  .map(t => t.id);

const feature = await create_feature({
  name: "User Authentication System",
  templateIds: docTemplates
});
```

## Common Workflows

### Workflow 1: Task Creation with Template Discovery
```javascript
// Step 1: Discover templates
const templates = await list_templates({
  targetEntityType: "TASK",
  isEnabled: true
});

// Step 2: Review and select
console.log("Available templates:");
templates.data.templates.forEach(t => {
  console.log(`- ${t.name}: ${t.description}`);
  console.log(`  Tags: ${t.tags.join(", ")}`);
});

// Step 3: Select based on task needs
const implementation = templates.data.templates.find(t =>
  t.tags.includes("implementation")
);

// Step 4: Create task with selected templates
const task = await create_task({
  title: "Implement API endpoint",
  templateIds: [implementation.id]
});
```

### Workflow 2: Feature Planning
```javascript
// Discover feature templates
const templates = await list_templates({
  targetEntityType: "FEATURE",
  isEnabled: true
});

// Select planning-phase templates
const planning = templates.data.templates.filter(t =>
  t.tags.includes("requirements") || t.tags.includes("context")
);

// Create feature with planning templates
const feature = await create_feature({
  name: "Payment Processing",
  templateIds: planning.map(t => t.id)
});
```

## Best Practices

1. **Always Discover First**: Never skip template discovery
2. **Filter by Entity Type**: Match templates to entity (TASK vs FEATURE)
3. **Check Enabled Status**: Use `isEnabled: true` to exclude disabled templates
4. **Read Descriptions**: Understand template purpose before applying
5. **Use Tags for Categories**: Filter by workflow, documentation, quality
6. **Match to Complexity**: More complex work = more templates
7. **Cache for Session**: Discover once per session, reuse for multiple creates
8. **Review Section Preview**: Check template sections to understand structure

## Common Mistakes to Avoid

### ❌ Mistake 1: Hardcoding Template IDs
```javascript
// NEVER do this
const task = await create_task({
  templateIds: ["550e8400-e29b-41d4-a716-446655440000"]
});
```

### ✅ Solution: Always Discover
```javascript
const templates = await list_templates({
  targetEntityType: "TASK",
  isEnabled: true
});
const selected = templates.data.templates.find(t => /* selection logic */);
const task = await create_task({
  templateIds: [selected.id]
});
```

### ❌ Mistake 2: Assuming Template Names
```javascript
const bugTemplate = templates.data.templates.find(t =>
  t.name === "Bug Investigation"  // Might not exist
);
```

### ✅ Solution: Use Descriptions and Tags
```javascript
const bugTemplate = templates.data.templates.find(t =>
  t.tags.includes("bug") && t.description.includes("investigation")
);
```

### ❌ Mistake 3: Ignoring Disabled Templates
```javascript
const templates = await list_templates({
  targetEntityType: "TASK"
  // No isEnabled filter - includes disabled templates
});
```

### ✅ Solution: Filter Enabled Only
```javascript
const templates = await list_templates({
  targetEntityType: "TASK",
  isEnabled: true  // Only get usable templates
});
```

### ❌ Mistake 4: Over-templating
```javascript
// Applying 6 templates to a simple task
const task = await create_task({
  title: "Fix typo",
  complexity: 1,
  templateIds: [/* all 6 templates */]
});
```

### ✅ Solution: Match Template Count to Complexity
```javascript
// Simple task = 0-1 templates
const task = await create_task({
  title: "Fix typo",
  complexity: 1,
  templateIds: []  // No templates needed for trivial work
});
```

## Related Tools

- **create_task**: Create tasks with discovered templates
- **create_feature**: Create features with discovered templates
- **get_template**: Get detailed information about specific template
- **add_section**: Add custom sections beyond templates

## See Also

- Template Strategy Guide: `task-orchestrator://guidelines/template-strategy`
- Task Creation Examples: `task-orchestrator://docs/tools/create-task#template-combination-strategies`
