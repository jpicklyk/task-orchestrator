---
layout: default
title: API Reference
---

# MCP Tools API Reference

The MCP Task Orchestrator provides **40 MCP tools** for AI-driven project management. This reference focuses on **when and why** AI uses each tool, not exhaustive parameter documentation (AI agents have access to complete MCP schemas).

## Table of Contents

- [How AI Uses Tools](#how-ai-uses-tools)
- [Workflow-Based Tool Patterns](#workflow-based-tool-patterns)
- [Tool Categories](#tool-categories)
- [Core Workflow Tools](#core-workflow-tools)
- [Context Efficiency Features](#context-efficiency-features)
- [Concurrent Access Protection](#concurrent-access-protection)

---

## How AI Uses Tools

### Autonomous Tool Discovery

AI agents don't have hardcoded knowledge of tools - they **discover tools dynamically** through MCP:

1. **MCP Connection**: When Claude connects to Task Orchestrator, it receives the full tool catalog
2. **Schema Access**: Each tool includes complete parameter schemas and descriptions
3. **Pattern Recognition**: AI recognizes user intent and selects appropriate tools
4. **Sequential Execution**: AI chains tools together for complex workflows

### Tool Selection Intelligence

AI chooses tools based on:

- **User Intent**: Natural language understanding determines which tools are needed
- **Workflow Context**: Current project state influences tool selection
- **Template Availability**: Dynamic template discovery guides template application
- **Git Detection**: File system analysis triggers git workflow tools

> **Learn More**: See [AI Guidelines - How AI Uses Tools](ai-guidelines#overview) for complete autonomous pattern documentation.

### Natural Language to Tool Mapping

**User Says** → **AI Uses**

```
"Show me my tasks" → get_overview or search_tasks
"Create a task for implementing login" → list_templates + create_task
"Update this task to in-progress" → update_task
"What's blocking task X?" → get_task_dependencies
"Apply testing template" → apply_template
"Create a feature with templates" → list_templates + create_feature
```

---

## Workflow-Based Tool Patterns

### PRD-Driven Development Pattern ⭐ Most Effective

**When**: You have a Product Requirements Document (PRD) or comprehensive requirements

**Tool Sequence**:
1. AI reads entire PRD content
2. `get_overview` - Understand current state
3. `list_templates` - Discover all available templates
4. `create_project` - Top-level container
5. `create_feature` (multiple) - Major functional areas with appropriate templates
6. `create_task` (multiple) - Specific implementation tasks with templates
7. `create_dependency` (multiple) - Establish technical sequencing
8. AI presents complete breakdown with recommended implementation order

**AI Trigger**: "Analyze this PRD...", "Break down this product requirements document..."

**Why Most Effective**:
- Complete context enables intelligent breakdown
- Optimal template selection based on PRD content
- Proper technical dependency sequencing
- Systematic coverage of all requirements
- Best results from AI analysis

> **Detailed Guide**: See [PRD Workflow Guide](quick-start#prd-driven-development-workflow) for complete instructions and examples.

---

### Project Initialization Pattern

**When**: Starting new projects without a comprehensive PRD

**Tool Sequence**:
1. `create_project` - Top-level container
2. `bulk_create_sections` - Project documentation
3. `create_feature` (multiple) - Major functional areas
4. `create_task` (multiple) - Foundation tasks
5. `create_dependency` - Sequencing relationships

**AI Trigger**: "Create a new project for...", "Set up a project with..."

---

### Implementation Planning Pattern

**When**: Creating tasks for implementation work

**Tool Sequence**:
1. `get_overview` - Understand current state
2. `list_templates` - Discover appropriate templates
3. `create_task` - With templates applied
4. `create_dependency` (if needed) - Link related tasks

**AI Trigger**: "Create a task to implement...", "Add a task for..."

**Auto-Applied**: Git workflow templates if .git detected

---

### Progress Tracking Pattern

**When**: Monitoring work status

**Tool Sequence**:
1. `get_overview` - Hierarchical project view
2. `search_tasks` - Filtered task lists (by status, priority, tags)
3. `get_task` or `get_feature` - Detailed individual items

**AI Trigger**: "What should I work on?", "Show me pending tasks", "Project status?"

---

### Task Completion Pattern

**When**: Finishing work and marking complete

**Tool Sequence**:
1. `get_sections` - Review all task sections for completion validation
2. `update_task` - Set status to completed
3. `get_task_dependencies` (optional) - Check what's unblocked

**AI Trigger**: "Mark task as complete", "I finished the login implementation"

**Important**: AI validates template guidance completion before marking done

---

### Bug Triage Pattern

**When**: Investigating and documenting bugs

**Tool Sequence**:
1. `search_tasks` (tag="task-type-bug") - Review existing bugs
2. `create_task` - With Bug Investigation Workflow template
3. `update_task` - Update priority based on severity
4. `create_dependency` (if needed) - Link to related tasks

**AI Trigger**: "I found a bug where...", "X isn't working"

---

### Feature Breakdown Pattern

**When**: Decomposing complex features into tasks

**Tool Sequence**:
1. `get_feature` (includeSections=true) - Analyze feature requirements
2. `create_task` (multiple) - Create focused subtasks
3. `create_dependency` - Establish implementation order
4. `update_feature` - Update status to in-development

**AI Trigger**: "Break down this feature", "Create tasks for this feature"

---

## Tool Categories

### Task Management (7 tools)

**Core Workflow**:
- `create_task` - Create tasks with templates
- `update_task` - Status, priority, complexity updates
- `get_task` - Fetch task details with progressive loading
- `search_tasks` - Filter and find tasks
- `get_overview` - Hierarchical project view
- `delete_task` - Remove tasks with cleanup
- `task_to_markdown` - Transform task to markdown format

**When AI Uses**: Most frequently - tasks are primary work units

**Key Feature**: Progressive loading (`includeSections`, `includeDependencies`, `includeFeature`)

---

### Feature Management (6 tools)

**Core Workflow**:
- `create_feature` - Group related tasks
- `update_feature` - Feature status and metadata
- `get_feature` - Feature details with task statistics
- `search_features` - Find features by criteria
- `delete_feature` - Remove with cascade or orphan options
- `feature_to_markdown` - Transform feature to markdown format

**When AI Uses**: Organizing 3+ related tasks, major functional areas

**Key Feature**: Task aggregation and organizational hierarchy

---

### Project Management (6 tools)

**Core Workflow**:
- `create_project` - Top-level organizational containers
- `update_project` - Project metadata and status
- `get_project` - Project details with features and tasks
- `search_projects` - Find projects by criteria
- `delete_project` - Remove with cascade options
- `project_to_markdown` - Transform project to markdown format

**When AI Uses**: Large initiatives, multi-feature work, organizational hierarchy

**Key Feature**: Highest-level organization and comprehensive scope management

---

### Dependency Management (3 tools)

**Core Workflow**:
- `create_dependency` - BLOCKS, IS_BLOCKED_BY, RELATES_TO relationships
- `get_task_dependencies` - Analyze dependency chains
- `delete_dependency` - Remove relationships

**When AI Uses**: Implementation sequencing, blocking issue identification

**Key Feature**: Workflow ordering and relationship modeling

**Common Patterns**:
- Database schema BLOCKS API implementation
- Authentication setup BLOCKS feature development
- Research task BLOCKS implementation decisions

---

### Section Management (9 tools)

**Core Workflow**:
- `add_section` - Add detailed content blocks
- `bulk_create_sections` - Efficient multi-section creation
- `get_sections` - Retrieve sections with selective loading (supports `includeContent` and `sectionIds`)
- `update_section` - Modify section content
- `update_section_text` - Targeted text replacement
- `update_section_metadata` - Title, format, ordinal changes
- `bulk_update_sections` - Efficient multi-section updates
- `reorder_sections` - Change section sequence
- `delete_section` - Remove content blocks

**When AI Uses**: Detailed documentation, template application results

**Key Features**:
- Structured content organization and context efficiency
- **Selective loading** - Browse metadata without content (85-99% token savings)
- **Two-step workflow** - Browse structure first, then fetch specific sections

**Efficiency Patterns**:
- Prefer `bulk_create_sections` over multiple `add_section` calls
- Use `includeContent=false` to browse section structure before loading content
- Use `sectionIds` to fetch only needed sections

---

### Template Management (9 tools)

**Core Workflow**:
- `list_templates` - **Most Important**: Dynamic template discovery
- `apply_template` - Add templates to existing entities
- `get_template` - Template details with section structure
- `create_template` - Custom template creation
- `add_template_section` - Add sections to templates
- `update_template_metadata` - Template properties
- `enable_template` / `disable_template` - Availability control
- `delete_template` - Remove custom templates

**When AI Uses**: Before every task/feature creation for template discovery

**Key Feature**: Dynamic, database-driven template system

**Critical Pattern**: AI ALWAYS uses `list_templates` before creating tasks/features

**Performance Optimization**: Template operations use in-memory caching for fast repeated access. No configuration needed - enabled by default.

> **See**: [Templates Guide](templates) for complete template system documentation

---

### Markdown Transformation (3 tools)

**Core Workflow**:
- `task_to_markdown` - Transform task to markdown with YAML frontmatter
- `feature_to_markdown` - Transform feature to markdown with YAML frontmatter
- `project_to_markdown` - Transform project to markdown with YAML frontmatter

**When AI Uses**:
- File export and documentation generation
- Systems that can render markdown directly
- Version control and diff-friendly storage
- Human-readable archives

**Key Feature**: Separate transformation tools for clear use case distinction

**Important Pattern**:
- Use `get_*` tools (get_task, get_feature, get_project) for JSON inspection
- Use `*_to_markdown` tools for markdown export/rendering
- Avoids content duplication in responses

**Use Cases**:
- "Export this task to markdown" → `task_to_markdown`
- "Create a markdown document for this feature" → `feature_to_markdown`
- "Generate project documentation" → `project_to_markdown`
- For terminal inspection, use `get_*` tools instead

---

## Core Workflow Tools

### Most Frequently Used Tools

**Daily Operations** (AI uses constantly):
1. `get_overview` - Start of every work session
2. `list_templates` - Before every task/feature creation
3. `create_task` - Primary work item creation
4. `update_task` - Status tracking throughout work
5. `search_tasks` - Finding specific work items

**Feature Organization** (AI uses for structure):
6. `create_feature` - Grouping related tasks
7. `get_feature` - Understanding feature scope
8. `create_dependency` - Establishing relationships

**Template Application** (AI uses for documentation):
9. `apply_template` - Adding structured documentation
10. `get_sections` - Validating completion before marking done

### Tool Chaining Examples

**Example 1: Complete Feature Creation**
```
User: "Create a user authentication feature"

AI Executes:
1. get_overview (understand current state)
2. list_templates --targetEntityType FEATURE (discover templates)
3. create_feature (with Context, Requirements, Technical Approach templates)
4. list_templates --targetEntityType TASK (find task templates)
5. create_task (multiple tasks with Implementation + Git templates)
6. create_dependency (establish implementation order)
```

**Example 2: Task Status Update with Validation**
```
User: "I finished implementing the login endpoint"

AI Executes:
1. search_tasks --query "login endpoint" (find the task)
2. get_sections --entityType TASK --entityId [id] --includeContent false (browse section structure)
3. get_sections --entityType TASK --entityId [id] --sectionIds [needed-ids] (fetch specific sections)
4. update_task --id [id] --status completed
5. get_task_dependencies --taskId [id] (check what's now unblocked)
```

---

## Context Efficiency Features

### Progressive Loading

Many tools support progressive detail loading to optimize context:

**get_task**:
- Basic: Just task metadata
- +`includeSections`: Add detailed content
- +`includeDependencies`: Add relationship info
- +`includeFeature`: Add parent feature context
- `summaryView`: Truncated for efficiency

**get_feature**:
- Basic: Feature metadata only
- +`includeTasks`: Add associated tasks
- +`includeSections`: Add documentation
- +`includeTaskCounts`: Add statistics
- +`includeTaskDependencies`: Full dependency analysis

**get_sections** (NEW - Token Optimization):
- Basic: All sections with full content (default)
- +`includeContent=false`: Section metadata only (85-99% token savings)
- +`sectionIds=[list]`: Specific sections only (selective loading)
- **Two-step pattern**: Browse with includeContent=false, then fetch specific sections

**Strategy**: AI starts basic, progressively loads as needed

### Selective Section Loading ⭐ Token Optimization

**Problem**: Loading all section content consumes 5,000-15,000 tokens when only metadata needed

**Solution**: Two-step workflow with `get_sections`

**Step 1: Browse Structure** (Low token cost)
```json
{
  "entityType": "TASK",
  "entityId": "task-uuid",
  "includeContent": false
}
```
Returns: id, title, usageDescription, contentFormat, ordinal, tags (no content field)

**Step 2: Fetch Specific Content** (Only what's needed)
```json
{
  "entityType": "TASK",
  "entityId": "task-uuid",
  "sectionIds": ["section-1-uuid", "section-3-uuid"]
}
```
Returns: Only the specified sections with full content

**Token Savings**: 85-99% reduction when browsing section structure

**When AI Uses**:
- Validating task completion (browse structure to see what exists)
- Finding specific section (browse titles, then fetch content)
- Understanding documentation organization (metadata reveals structure)

---

### Bulk Operations

**When to Use Bulk Tools**:

✅ `bulk_create_sections` - Creating 2+ sections (more efficient than multiple `add_section`)
✅ `bulk_update_sections` - Updating multiple sections simultaneously
✅ `bulk_delete_sections` - Removing multiple sections at once

**Performance Benefit**: Single database transaction, reduced network overhead

---

### Summary Views

Tools with `summaryView` parameter:
- `get_task`
- `get_feature`
- `get_project`

**Use When**: Need overview without full content (token optimization)

---

## Concurrent Access Protection

### Built-In Collision Prevention

The system automatically prevents conflicts when multiple AI agents work in parallel.

**Protected Operations**:
- `update_task` and `delete_task`
- `update_feature` and `delete_feature`
- `update_project` and `delete_project`

**How It Works**:
- Automatic locking on update/delete operations
- 2-minute timeout prevents deadlocks
- Clear error messages when conflicts detected
- No configuration needed

**Best Practice**: Distribute work across different entities for parallel workflows

---

## Tool Usage Best Practices

### For AI Agents

1. **Always start with `get_overview`** - Understand current state before creating work
2. **Always use `list_templates`** - Discover templates before creating tasks/features
3. **Use selective section loading** - Browse with `includeContent=false` before loading full content
4. **Use `get_sections` before completion** - Validate template guidance followed
5. **Prefer bulk operations** - More efficient for multiple sections
6. **Progressive loading** - Start basic, load details as needed

### For Development Teams

1. **Trust autonomous patterns** - AI knows when to use tools
2. **Natural language works** - No need to specify tool names
3. **Templates are dynamic** - Create custom templates, AI will discover them
4. **Let AI chain tools** - Complex operations use multiple tools automatically
5. **Review patterns** - Understand how AI sequences tools for your workflows

---

## Integration with Other Systems

### MCP Tool Integration

Task Orchestrator tools work seamlessly with:
- **GitHub MCP**: PR creation, code review integration
- **File System Tools**: Git detection, project analysis
- **Web Search Tools**: Research task support

### Workflow Prompt Integration

Tools are automatically used by workflow prompts:
- `create_feature_workflow` - Uses create_feature, create_task, create_dependency
- `task_breakdown_workflow` - Uses create_task, create_dependency, update_task
- `implementation_workflow` - Uses get_overview, search_tasks, apply_template for tasks, features, and bugs

> **See**: [Workflow Prompts](workflow-prompts) for complete workflow automation details

---

## Additional Resources

- **[AI Guidelines](ai-guidelines)** - Complete AI usage patterns and autonomous workflows
- **[Templates Guide](templates)** - Dynamic template discovery and application
- **[Workflow Prompts](workflow-prompts)** - Workflow automation integration
- **[Quick Start](quick-start)** - Getting started with Task Orchestrator

---

**Questions About Tools?** Ask Claude directly - Claude understands the complete MCP schema and can explain any tool's parameters, usage, and integration patterns.
