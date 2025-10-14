---
layout: default
title: API Reference
---

# MCP Tools API Reference

The MCP Task Orchestrator provides comprehensive MCP tools for AI-driven project management. This reference focuses on **when and why** AI uses each tool, not exhaustive parameter documentation (AI agents have access to complete MCP schemas).

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
"Mark this task as completed" → set_status
"Update this task to in-progress" → set_status
"Change feature status to planning" → set_status
"What tags are we using?" → list_tags
"Show me bug-related tasks" → list_tags + search_tasks
"What's blocked?" → get_blocked_tasks
"Why is nothing moving forward?" → get_blocked_tasks
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
2. `set_status` - Set status to completed (auto-detects entity type, warns if blocking others)
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

### Task Management

**Core Workflow**:
- `create_task` - Create tasks with templates
- `set_status` - Simple, unified status updates for tasks, features, and projects ⭐ **Preferred for status-only changes**
- `update_task` - Status, priority, complexity updates
- `bulk_update_tasks` - Update multiple tasks efficiently (70-95% token savings)
- `get_task` - Fetch task details with progressive loading
- `search_tasks` - Filter and find tasks
- `list_tags` - Discover all tags with usage counts ⭐ **Use before searching by tag**
- `get_blocked_tasks` - Identify blocked tasks for workflow optimization ⭐ **Use for bottleneck analysis**
- `get_overview` - Hierarchical project view
- `delete_task` - Remove tasks with cleanup
- `task_to_markdown` - Transform task to markdown format

**When AI Uses**: Most frequently - tasks are primary work units

**Key Features**:
- Progressive loading (`includeSections`, `includeDependencies`, `includeFeature`)
- `set_status` for simple status updates - auto-detects entity type, provides dependency warnings
- `list_tags` for tag discovery - shows usage across all entity types

---

### Feature Management

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

### Project Management

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

### Dependency Management

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

### Section Management

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

### Template Management

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

### Markdown Transformation

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
4. set_status --id [id] --status completed (simple status-only update)
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

✅ `bulk_update_tasks` - Updating 3+ tasks simultaneously (70-95% token savings vs individual calls)
✅ `bulk_create_sections` - Creating 2+ sections (more efficient than multiple `add_section`)
✅ `bulk_update_sections` - Updating multiple sections simultaneously
✅ `bulk_delete_sections` - Removing multiple sections at once

**Performance Benefit**: Single database transaction, reduced network overhead, massive token savings

**Example Scenarios**:
- Marking 10 tasks as completed after feature implementation
- Updating priority on multiple related tasks
- Batch status changes across feature tasks
- Updating complexity ratings after task analysis

**Token Savings Example**:
```
Individual calls: 10 × update_task = ~12,500 characters
Bulk operation: 1 × bulk_update_tasks = ~650 characters
Savings: 95% (11,850 characters saved!)
```

---

### Summary Views

Tools with `summaryView` parameter:
- `get_task`
- `get_feature`
- `get_project`

**Use When**: Need overview without full content (token optimization)

---

### Simple Status Updates with `set_status`

**Purpose**: Unified, simple status updates across all entity types (tasks, features, projects)

**Why Use `set_status` Instead of `update_task`/`update_feature`/`update_project`**:
- ✅ **Simpler**: Only 2 parameters (id + status) vs many optional fields
- ✅ **More efficient**: Saves tokens - no need to specify entity type
- ✅ **Auto-detection**: Automatically identifies if ID is a task, feature, or project
- ✅ **Smart warnings**: For tasks, warns when completing tasks that block others
- ✅ **Universal**: Works for all entity types with one tool

**When AI Uses**:
- Any status-only update ("mark as completed", "set to in-progress")
- Workflow transitions where only status changes
- Quick status changes without metadata updates

**Supported Status Values by Entity Type**:

**Tasks**: `pending`, `in-progress`, `completed`, `cancelled`, `deferred`
**Features**: `planning`, `in-development`, `completed`, `archived`
**Projects**: `planning`, `in-development`, `completed`, `archived`

**Format Flexibility**: Accepts `in-progress`, `in_progress`, or `inprogress` (auto-normalized)

**Examples**:

**Update Task Status**:
```json
{
  "id": "640522b7-810e-49a2-865c-3725f5d39608",
  "status": "completed"
}
```
Response includes entity type and blocking task warning if applicable:
```json
{
  "success": true,
  "data": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "entityType": "TASK",
    "status": "completed",
    "modifiedAt": "2025-10-14T18:30:00Z",
    "blockingTasksCount": 2,
    "warning": "This task blocks 2 other task(s)"
  }
}
```

**Update Feature Status**:
```json
{
  "id": "6b787bca-2ca2-461c-90f4-25adf53e0aa0",
  "status": "in-development"
}
```

**Update Project Status**:
```json
{
  "id": "a4fae8cb-7640-4527-bd89-11effbb1d039",
  "status": "completed"
}
```

**Use `update_task`/`update_feature`/`update_project` When**:
- Updating multiple fields simultaneously (status + priority + complexity)
- Changing tags or associations
- Updating title or summary
- For efficiency, updating 3+ entities use `bulk_update_tasks`

**AI Pattern**:
```
User: "Mark task X as done"
AI: Uses set_status (simple, 2 params)

User: "Update task X to high priority and mark as in-progress"
AI: Uses update_task (multiple fields changing)
```

---

### Tag Discovery with `list_tags`

**Purpose**: Discover all unique tags across all entities with usage counts and entity type breakdown

**Why Use `list_tags`**:
- ✅ **Tag Discovery**: Find all available tags before searching
- ✅ **Usage Analytics**: Understand tag popularity and patterns
- ✅ **Tag Cleanup**: Identify rarely used or duplicate tags
- ✅ **Standardization**: Detect tag variations (e.g., "bug" vs "bugs")
- ✅ **Entity Breakdown**: See which entity types use each tag

**When AI Uses**:
- Before searching by tag (discover available tags)
- User asks "what tags are we using?"
- Tag cleanup and standardization tasks
- Understanding project categorization patterns

**Features**:
- Lists tags from all entity types (tasks, features, projects, templates)
- Shows usage count per entity type
- Supports filtering by specific entity types
- Flexible sorting (by usage count or alphabetically)

**Examples**:

**Discover All Tags** (default - sorted by usage, most popular first):
```json
{}
```

**Find Task-Specific Tags**:
```json
{
  "entityTypes": ["TASK"],
  "sortBy": "count",
  "sortDirection": "desc"
}
```

**Alphabetical Tag List**:
```json
{
  "sortBy": "name",
  "sortDirection": "asc"
}
```

**Response Format**:
```json
{
  "success": true,
  "data": {
    "tags": [
      {
        "tag": "bug",
        "totalCount": 15,
        "byEntityType": {
          "TASK": 12,
          "FEATURE": 3
        }
      },
      {
        "tag": "feature",
        "totalCount": 28,
        "byEntityType": {
          "TASK": 18,
          "FEATURE": 8,
          "TEMPLATE": 2
        }
      }
    ],
    "totalTags": 2
  }
}
```

**AI Usage Pattern**:
```
User: "Show me all tasks related to bugs"

AI Workflow:
1. list_tags (discover available tags)
2. Identify relevant tags: "bug", "bugfix", "debugging"
3. search_tasks --tag "bug" (or most appropriate tag)
4. Present results to user
```

**Sorting Options**:
- `sortBy: "count"` (default) - Most used tags first
- `sortBy: "name"` - Alphabetical order
- `sortDirection: "desc"` (default) - Descending
- `sortDirection: "asc"` - Ascending

**Entity Type Filtering**:
- No filter (default): All entity types (PROJECT, FEATURE, TASK, TEMPLATE)
- `entityTypes: ["TASK"]` - Only task tags
- `entityTypes: ["TASK", "FEATURE"]` - Task and feature tags

---

### Workflow Management with `get_blocked_tasks`

**Purpose**: Identify tasks currently blocked by incomplete dependencies for workflow optimization and bottleneck analysis

**Why Use `get_blocked_tasks`**:
- ✅ **Bottleneck Identification**: Find what's blocking progress
- ✅ **Sprint Planning**: Identify tasks that can't start yet
- ✅ **Priority Setting**: Focus on unblocking critical paths
- ✅ **Team Coordination**: Know which tasks need other teams' work
- ✅ **Daily Standup**: Quick view of blocked work

**What Makes a Task "Blocked"**:
1. Task status is `pending` or `in-progress` (active work)
2. Task has incoming dependencies (other tasks block it)
3. At least one blocking task is NOT `completed` or `cancelled`

**When AI Uses**:
- User asks "what's blocked?" or "why is nothing moving?"
- Sprint planning and work prioritization
- Identifying workflow bottlenecks
- Daily standup preparation

**Features**:
- Automatic blocking detection across project
- Shows blocker task details (ID, title, status, priority)
- Filter by project/feature for focused analysis
- Optional full task metadata via `includeTaskDetails`
- Efficient queries (only checks active tasks)

**Examples**:

**Find All Blocked Tasks**:
```json
{}
```

**Blocked Tasks in Specific Project**:
```json
{
  "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039"
}
```

**Blocked Tasks with Full Details**:
```json
{
  "includeTaskDetails": true
}
```

**Response Format**:
```json
{
  "success": true,
  "data": {
    "blockedTasks": [
      {
        "taskId": "task-uuid-1",
        "title": "Implement user dashboard",
        "status": "pending",
        "priority": "high",
        "complexity": 7,
        "blockedBy": [
          {
            "taskId": "blocker-uuid-1",
            "title": "Design dashboard mockups",
            "status": "in-progress",
            "priority": "high"
          },
          {
            "taskId": "blocker-uuid-2",
            "title": "Create API endpoints",
            "status": "pending",
            "priority": "medium"
          }
        ],
        "blockerCount": 2
      }
    ],
    "totalBlocked": 1
  }
}
```

**AI Usage Pattern - Bottleneck Analysis**:
```
User: "Why is nothing moving forward?"

AI Workflow:
1. get_blocked_tasks (identify all blocked work)
2. Analyze which blocker tasks appear most often
3. Suggest prioritizing those blocker tasks
4. Recommend specific actions to unblock work
```

**AI Usage Pattern - Sprint Planning**:
```
User: "What can we start next sprint?"

AI Workflow:
1. get_blocked_tasks (see what's blocked)
2. search_tasks --status pending (see what's ready)
3. Filter for tasks with no blockers
4. Recommend unblocked, high-priority tasks
```

**Filtering Options**:
- `projectId`: Only show blocked tasks in specific project
- `featureId`: Only show blocked tasks in specific feature
- `includeTaskDetails`: Include full task metadata (default: false for efficiency)

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
