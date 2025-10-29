package io.github.jpicklyk.mcptask.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * MCP Resources for Task Orchestrator AI Guidelines.
 *
 * These resources provide discoverable guidelines that AI agents can reference
 * to understand when and how to use Task Orchestrator tools effectively.
 * Resources focus on WHEN/WHY principles rather than detailed HOW instructions.
 */
object TaskOrchestratorResources {

    /**
     * Configures all Task Orchestrator guideline resources with the MCP server.
     */
    fun configure(server: Server) {
        addUsageOverviewResource(server)
        addTemplateStrategyResource(server)
        addTaskManagementPatternsResource(server)
        addWorkflowIntegrationResource(server)
    }

    /**
     * Adds the usage overview resource providing high-level principles for
     * when to use Task Orchestrator tools vs internal systems.
     */
    private fun addUsageOverviewResource(server: Server) {
        server.addResource(
            uri = "task-orchestrator://guidelines/usage-overview",
            name = "Task Orchestrator Usage Overview",
            description = "High-level principles for when and why to use Task Orchestrator tools effectively",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://guidelines/usage-overview",
                        mimeType = "text/markdown",
                        text = """
# Task Orchestrator Usage Overview

## When to Use Task Orchestrator Tools

### Primary Use Cases

**Structured Work Management** (✅ Recommended):
- Multi-step implementation tasks requiring tracking and documentation
- Feature development with multiple related tasks
- Bug investigations requiring systematic approach
- Projects with team collaboration and progress visibility needs

**Not Recommended For**:
- Simple one-off commands or queries (use direct tool invocation)
- Temporary or exploratory work that won't need tracking
- Work already managed in external systems (GitHub Issues, Jira, etc.)

### Decision Framework: MCP Tools vs Internal Systems

**Use Task Orchestrator MCP tools when**:
- Work complexity > 3 (on 1-10 scale)
- Implementation spans multiple sessions or days
- Documentation and context preservation is valuable
- Multiple related work items need coordination
- Templates provide value for consistency

**Use Internal Systems (TodoWrite, file-based notes) when**:
- Simple task tracking within a larger task
- Temporary checklists or reminders
- Work is transient and won't be referenced later
- External project management tools are already in use

## Available Workflows

Task Orchestrator provides **user-invokable workflow prompts** for common scenarios:

### Feature Development Workflows (v2.0)
- **`coordinate_feature_development`**: End-to-end feature orchestration with Claude Code Skills
- **Direct tool calls**: Using consolidated v2.0 tools (manage_container, query_container)
- **Template discovery**: Automatic template application during entity creation

### Project Organization Workflows
- **`project_setup_workflow`**: Initialize new projects with structure

### How Modern Workflows Work
In v2.0, workflows use **two complementary approaches**:
1. **Direct tool invocation**: Use consolidated tools for straightforward operations
2. **Skill-based coordination**: Invoke Skills for complex workflows requiring reasoning
3. **Template discovery**: Always discover and apply templates during creation
4. Best practices are embedded in tool descriptions and Skills

## Template Discovery and Application

### Template Philosophy
Templates provide **structured documentation patterns** and **workflow guidance**:

- **Workflow Templates**: Step-by-step implementation instructions (Git workflow, PR process, testing)
- **Documentation Templates**: Content structure for requirements, technical approach, context
- **Quality Templates**: Completion criteria, testing strategy, definition of done

### Dynamic Template Discovery
**Always check templates before creating tasks or features**:
```
1. Use `list_templates --targetEntityType TASK` or `FEATURE`
2. Review available templates and their descriptions
3. Select appropriate templates based on work type
4. Apply templates via `templateIds` parameter during creation
```

### Template Combination Strategies
Combine templates for comprehensive coverage:
- **Development Task**: Technical Approach + Task Implementation + Git Branching
- **Bug Fix**: Bug Investigation + Git Branching + Definition of Done
- **Feature Planning**: Context & Background + Requirements + Testing Strategy

## Integration with Workflow Prompts

Task Orchestrator provides **two complementary systems**:

1. **MCP Resources** (this document): General principles AI agents should internalize
2. **Workflow Prompts**: Executable step-by-step guidance for specific scenarios

### Relationship Between Systems

**MCP Resources** provide:
- High-level WHEN/WHY principles
- Decision frameworks for tool selection
- Overview of capabilities and patterns

**Workflow Prompts** provide:
- Specific HOW-TO instructions
- Step-by-step execution guidance
- Tool invocation examples with parameters

### Recommended AI Agent Workflow

1. **Initialization**: Internalize MCP Resource guidelines into memory (CLAUDE.md, .cursorrules, etc.)
2. **Pattern Recognition**: Use internalized principles to recognize when Task Orchestrator fits
3. **Execution**: Invoke workflow prompts for specific scenarios requiring step-by-step guidance
4. **Natural Interaction**: Enable natural language like "create a feature from this PRD"

## Natural Language Pattern Recognition

With guidelines internalized, AI agents can recognize patterns:

**User Says**: "Create a feature for user authentication with OAuth"
**Agent Recognizes**:
- Multi-step feature development (use Task Orchestrator)
- Use consolidated tools (manage_container, query_container) directly
- Apply authentication and implementation templates via template discovery

**User Says**: "Fix this small typo in the README"
**Agent Recognizes**:
- Simple one-off task (don't use Task Orchestrator)
- Direct file edit appropriate

**User Says**: "Investigate why the API is timing out"
**Agent Recognizes**:
- Bug investigation scenario
- Create task with Bug Investigation template
- Use Task Management Skill or direct tool calls to guide bug fix implementation

## Key Principles

### Principle 1: Template-First Approach
**Why**: Templates ensure consistent documentation and provide workflow guidance
**When**: Always check available templates before creating tasks or features
**How**: Use `list_templates` filtered by entity type

### Principle 2: Progressive Detail
**Why**: Avoid overwhelming upfront documentation requirements
**When**: Start with essential information, add detail as work progresses
**How**: Create entity with templates, add sections as needed

### Principle 3: Status-Driven Lifecycle
**Why**: Clear status tracking enables progress visibility
**When**: Update status as work transitions between phases
**How**: pending → in-progress → completed (use `update_task`)

### Principle 4: Context Efficiency
**Why**: Reduce token usage for better AI agent performance
**When**: Working with large projects or many tasks
**How**: Use summary views, bulk operations, targeted queries

### Principle 5: Workflow Integration
**Why**: Leverage existing development processes (git, PR workflows)
**When**: Project uses version control or code review processes
**How**: Apply workflow templates that provide step-by-step git guidance

## Best Practices

1. **Start every session** with `get_overview` to understand current state
2. **Use templates consistently** for documentation patterns
3. **Apply meaningful tags** for filtering and categorization
4. **Set appropriate complexity** (1-10) for estimation and prioritization
5. **Create dependencies** when tasks have ordering requirements
6. **Leverage bulk operations** when working with multiple sections
7. **Verify template compliance** before marking work complete
8. **Write markdown-formatted content** when using contentFormat=MARKDOWN
   - Use headings (##, ###) for structure
   - Use lists (-, *) for items and requirements
   - Use bold (**) for emphasis
   - Use code blocks (\`\`\`) with language tags
   - Include links [text](url) for references

## Getting Started

**For AI Agents**:
1. Read and internalize these guidelines into your memory system
2. Use `get_overview` to understand current project state
3. Check `list_templates` to see available documentation patterns
4. Follow workflow prompts for complex scenarios
5. Apply patterns naturally based on user requests

**For Users**:
1. Use natural language requests for autonomous AI assistance
2. Trust AI agents to use Task Orchestrator when appropriate
3. Provide clear requirements and let Skills and tools handle the details

This overview focuses on WHEN and WHY to use Task Orchestrator. For specific HOW-TO guidance, use workflow prompts or refer to individual tool descriptions.
                        """.trimIndent()
                    )
                )
            )
        }
    }

    /**
     * Adds the template strategy resource teaching AI agents how to discover
     * and apply templates dynamically.
     */
    private fun addTemplateStrategyResource(server: Server) {
        server.addResource(
            uri = "task-orchestrator://guidelines/template-strategy",
            name = "Template Discovery and Application Strategy",
            description = "Dynamic template discovery patterns and selection guidelines for AI agents",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://guidelines/template-strategy",
                        mimeType = "text/markdown",
                        text = """
# Template Discovery and Application Strategy

## Critical Principle: Templates Are Dynamic

**NEVER assume which templates exist.** Templates are:
- Stored in a database (not code)
- Can be enabled or disabled at runtime
- Can be customized per installation
- May vary between environments
- User-created templates can exist alongside built-in ones

**Always discover templates dynamically** using `list_templates` before applying them.

## Why Template Discovery Matters

### The Problem with Assumptions
```
❌ WRONG: Assuming templates exist
create_task(..., templateIds: ["task-implementation-workflow-uuid"])
// Fails if template disabled, deleted, or has different UUID

✅ CORRECT: Discovering templates first
list_templates(targetEntityType: "TASK", isEnabled: true)
// Review available templates
// Select appropriate template IDs from results
create_task(..., templateIds: [discovered-uuid])
```

### Dynamic Template Ecosystem
Templates can change for valid reasons:
- Administrators disable templates not relevant to their workflow
- Custom templates replace built-in ones
- Template UUIDs differ between installations
- New templates added over time
- Template availability changes based on project needs

## Template Discovery Pattern

### Standard Discovery Workflow

**Step 1: Filter by Entity Type**
```
list_templates --targetEntityType TASK --isEnabled true
// or
list_templates --targetEntityType FEATURE --isEnabled true
```

**Step 2: Review Template Descriptions**
Each template has:
- `name`: Human-readable template name
- `description`: Purpose and use case
- `tags`: Categorization markers
- `targetEntityType`: TASK or FEATURE
- `isBuiltIn`: Whether it's a system template
- `isEnabled`: Current availability status

**Step 3: Select by Purpose**
Match templates to your needs:
- Read `description` to understand purpose
- Check `tags` for categories (workflow, documentation, quality)
- Note `id` (UUID) for application

**Step 4: Apply During Creation**
```
create_task(
    title: "...",
    summary: "...",
    templateIds: [selected-uuids-from-discovery]
)
```

## Template Categories

Templates fall into three main categories:

### 1. AI Workflow Instructions
**Purpose**: Step-by-step process guidance for AI agents

**Characteristics**:
- Provide executable instructions
- Integrate with MCP tools
- Guide AI through complex workflows
- Focus on HOW to execute tasks

**Common Examples** (discover via list_templates):
- Local Git Branching Workflow
- GitHub PR Workflow
- Task Implementation Workflow
- Bug Investigation Workflow

**When to Use**:
- Tasks requiring structured process
- Development work with version control
- Systematic investigations
- Multi-step implementations

### 2. Documentation Properties
**Purpose**: Structured information capture

**Characteristics**:
- Define content organization
- Provide documentation templates
- Focus on WHAT to document
- Create consistent structure

**Common Examples** (discover via list_templates):
- Technical Approach
- Requirements Specification
- Context & Background

**When to Use**:
- Features requiring detailed documentation
- Tasks with complex requirements
- Architecture decision documentation
- Business context capture

### 3. Process & Quality Standards
**Purpose**: Completion criteria and quality gates

**Characteristics**:
- Define done-ness criteria
- Establish quality standards
- Provide validation checklists
- Focus on quality assurance

**Common Examples** (discover via list_templates):
- Testing Strategy
- Definition of Done

**When to Use**:
- Tasks requiring quality validation
- Features with testing requirements
- Work needing clear completion criteria
- Quality-critical implementations

## Template Selection Decision Trees

### For Task Creation

**Q1: Does this involve git version control?**
- YES → Discover and include git workflow template(s)
- NO → Skip git templates

**Q2: What's the task complexity?**
- Low (1-3) → Minimal templates (maybe just Task Implementation)
- Medium (4-6) → Task Implementation + relevant workflow
- High (7-10) → Task Implementation + Technical Approach + workflow

**Q3: What's the task type?**
- Feature implementation → Task Implementation + Git workflows
- Bug fix → Bug Investigation + Git workflows
- Research → Technical Approach only
- Documentation → Minimal templates

**Q4: Does it need quality gates?**
- YES → Include Testing Strategy or Definition of Done
- NO → Skip quality templates

### For Feature Creation

**Q1: What phase is the feature in?**
- Planning → Context & Background + Requirements Specification
- Development → Requirements + Technical Approach
- Complete documentation → All three documentation templates

**Q2: Is this user-facing?**
- YES → Include Context & Background for user value
- NO → May skip context, focus on technical

**Q3: Does it require architecture decisions?**
- YES → Include Technical Approach
- NO → May skip if simple feature

## Template Application Patterns

### Pattern 1: Minimal Task
```
// For simple, straightforward tasks (complexity 1-3)
templates = list_templates(targetEntityType: "TASK", tags: "implementation")
selected = find template matching "Task Implementation"

create_task(
    templateIds: [selected.id]
)
```

### Pattern 2: Development Task with Git
```
// For standard development work (complexity 4-6)
templates = list_templates(targetEntityType: "TASK", isEnabled: true)
implementation = find "Task Implementation Workflow"
git = find "Local Git Branching Workflow"

create_task(
    templateIds: [implementation.id, git.id]
)
```

### Pattern 3: Complex Feature Task
```
// For complex tasks requiring architecture (complexity 7-10)
templates = list_templates(targetEntityType: "TASK", isEnabled: true)
implementation = find "Task Implementation"
technical = find "Technical Approach"
git = find "Local Git Branching"
testing = find "Testing Strategy"

create_task(
    templateIds: [implementation.id, technical.id, git.id, testing.id]
)
```

### Pattern 4: Feature with Full Documentation
```
// For well-documented features
templates = list_templates(targetEntityType: "FEATURE", isEnabled: true)
context = find "Context & Background"
requirements = find "Requirements Specification"
technical = find "Technical Approach"

create_feature(
    templateIds: [context.id, requirements.id, technical.id]
)
```

### Pattern 5: Bug Investigation
```
// For systematic bug resolution
templates = list_templates(targetEntityType: "TASK", tags: "bug")
bug_workflow = find "Bug Investigation Workflow"
git = find "Local Git Branching"

create_task(
    templateIds: [bug_workflow.id, git.id]
)
```

## Integration with Workflow Prompts

### Complementary Usage

**Workflow Prompts** provide step-by-step guidance:
- Invoke for specific scenarios
- Follow structured processes
- Get tool usage examples
- Receive automated guidance

**Template Strategy** enables autonomous decisions:
- AI agents internalize patterns
- Recognize scenarios without explicit prompts
- Apply templates based on context
- Make intelligent selections

### When to Use Each

**Use Workflow Prompts when**:
- User explicitly invokes workflow
- Complex multi-step process needed
- Step-by-step guidance valuable
- Learning new patterns

**Use Template Discovery when**:
- AI agent working autonomously
- Natural language requests
- Pattern-based recognition
- Streamlined operations

## Common Mistakes to Avoid

### ❌ Mistake 1: Hardcoding Template IDs
```
// NEVER do this
create_task(templateIds: ["550e8400-e29b-41d4-a716-446655440000"])
```

### ✅ Solution: Always Discover
```
templates = list_templates(targetEntityType: "TASK")
selected = templates.find(name: "Task Implementation Workflow")
create_task(templateIds: [selected.id])
```

### ❌ Mistake 2: Assuming Template Names
```
// Templates might be renamed
templates = list_templates()
task_template = templates.find(name: "Task Template")  // Might not exist
```

### ✅ Solution: Use Descriptions and Tags
```
templates = list_templates(targetEntityType: "TASK", tags: "implementation")
// Review descriptions to find right template
selected = templates.find_by_purpose(...)
```

### ❌ Mistake 3: Ignoring Disabled Templates
```
// Disabled templates won't work
templates = list_templates(targetEntityType: "TASK")  // Includes disabled
```

### ✅ Solution: Filter for Enabled
```
templates = list_templates(targetEntityType: "TASK", isEnabled: true)
```

### ❌ Mistake 4: Over-templating
```
// Too many templates creates noise
create_task(templateIds: [all_discovered_templates])
```

### ✅ Solution: Select Relevant Templates
```
// Choose 2-4 most relevant templates based on task needs
```

## Best Practices

1. **Always Start with Discovery**
   - Never skip `list_templates`
   - Check availability before applying
   - Review current template ecosystem

2. **Filter Appropriately**
   - Use `targetEntityType` to match entity
   - Use `isEnabled: true` to exclude disabled
   - Use `tags` for category-specific discovery

3. **Read Descriptions**
   - Don't assume from names alone
   - Understand template purpose
   - Match to actual needs

4. **Apply Judiciously**
   - 1-2 templates for simple tasks
   - 2-3 templates for standard tasks
   - 3-4 templates for complex work
   - Avoid template overload

5. **Combine Thoughtfully**
   - Workflow + Documentation + Quality
   - Match categories to task needs
   - Consider template interactions

6. **Cache for Session**
   - Discover once per work session
   - Reuse discoveries for similar tasks
   - Re-discover if context changes significantly

## Summary

Template discovery is **mandatory, not optional**:
- Templates are database-driven and dynamic
- Always use `list_templates` before applying
- Never hardcode template IDs or names
- Select based on description, tags, and purpose
- Apply 2-4 relevant templates per entity
- Combine categories for comprehensive coverage

This strategy ensures robust template usage across any Task Orchestrator installation, regardless of customization or configuration.
                        """.trimIndent()
                    )
                )
            )
        }
    }

    /**
     * Adds the task management patterns resource containing executable workflow
     * patterns for natural language usage.
     */
    private fun addTaskManagementPatternsResource(server: Server) {
        server.addResource(
            uri = "task-orchestrator://guidelines/task-management",
            name = "Task Management Patterns",
            description = "Executable workflow patterns for recognizing user intent and applying appropriate task management patterns",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://guidelines/task-management",
                        mimeType = "text/markdown",
                        text = """
# Task Management Patterns

## Purpose

This resource teaches AI agents to recognize natural language user requests and automatically apply appropriate task management patterns without requiring explicit workflow invocation.

**Goal**: Enable interactions like:
- User: "Create a feature for user authentication"
- AI: Automatically applies feature creation pattern with templates
- User: "Fix the login bug"
- AI: Automatically applies bug triage pattern

## Pattern Recognition

### Intent Categories

**Feature Creation Intents**:
- "Create a feature for X"
- "Build X functionality"
- "Implement X feature"
- "Add X capability"
- "New feature: X"

**Task Implementation Intents**:
- "Implement X"
- "Build X component"
- "Create task for X"
- "Work on X"
- "Fix X" (non-bug)

**Bug Triage Intents**:
- "Fix bug: X"
- "X is broken"
- "Debug X issue"
- "Investigate X problem"
- "X doesn't work"

**Documentation Intents**:
- "Document X"
- "Write docs for X"
- "Add documentation"
- "Create README"

**Priority Assessment Intents**:
- "What should I work on?"
- "What's next?"
- "Show priorities"
- "What's urgent?"

## Executable Workflow Patterns

### Pattern 1: Feature Creation from Natural Language

**Trigger**: User describes a feature to build

**Execution**:
```
Step 1: Check current state
get_overview()

Step 2: Discover available templates
templates = list_templates(targetEntityType: "FEATURE", isEnabled: true)

Step 3: Select relevant templates
context_template = find template with "Context" or "Background"
requirements_template = find template with "Requirements"

Step 4: Create feature with templates
create_feature(
    name: [extract feature name from user request],
    summary: [comprehensive description based on user input],
    status: "planning",
    priority: [assess from context: high/medium/low],
    templateIds: [selected template IDs],
    tags: [derive from feature type and domain]
)

Step 5: Confirm creation and suggest next steps
"Feature created. Ready to break down into tasks?"
```

**Example**:
```
User: "Create a feature for OAuth authentication with Google and GitHub"

AI applies pattern:
1. get_overview() - check existing work
2. list_templates(targetEntityType: "FEATURE") - discover templates
3. create_feature(
     name: "OAuth Authentication Integration",
     summary: "Implement OAuth 2.0 authentication with Google and GitHub providers...",
     priority: "high",
     templateIds: [context-id, requirements-id],
     tags: "authentication,oauth,security,user-management"
   )
4. "Feature created with 2 documentation templates. Ready to create implementation tasks?"
```

### Pattern 2: Task Creation with Template Discovery

**Trigger**: User requests implementation of specific functionality

**Execution**:
```
Step 1: Understand context
get_overview() - see current work
Detect git usage: check for .git directory

Step 2: Discover templates
templates = list_templates(targetEntityType: "TASK", isEnabled: true)

Step 3: Select templates based on task type
implementation = find "Task Implementation Workflow"
git = find "Local Git Branching" (if git detected)
technical = find "Technical Approach" (if complexity > 6)
testing = find "Testing Strategy" (if quality critical)

Step 4: Assess complexity
Analyze requirements:
- Simple (1-3): Single component, straightforward
- Medium (4-6): Multiple components, moderate integration
- High (7-10): Complex architecture, multiple integrations

Step 5: Create task
create_task(
    title: [specific, actionable title],
    summary: [detailed description with acceptance criteria],
    complexity: [assessed complexity],
    priority: [high/medium/low based on urgency],
    templateIds: [selected template IDs],
    tags: [task-type, component, technology]
)

Step 6: Update status when starting
update_task(id: task-id, status: "in_progress")
```

**Example**:
```
User: "Implement API endpoints for user profile management"

AI applies pattern:
1. get_overview() - check current state
2. Check for .git → detected
3. list_templates(targetEntityType: "TASK")
4. Assess complexity: Medium (4-6) - API + validation + tests
5. create_task(
     title: "Implement user profile management API endpoints",
     summary: "Create REST API endpoints for user profile CRUD operations. Include: GET /profile, PUT /profile, validation, error handling, tests",
     complexity: 5,
     priority: "medium",
     templateIds: [impl-workflow-id, git-workflow-id, technical-approach-id],
     tags: "task-type-feature,api,backend,user-management"
   )
6. "Task created with git branching workflow. Ready to start implementation?"
```

### Pattern 3: Bug Triage and Investigation

**Trigger**: User reports a bug or issue

**Execution**:
```
Step 1: Check existing bugs
search_tasks(tag: "task-type-bug", status: "pending")

Step 2: Discover bug investigation templates
templates = list_templates(targetEntityType: "TASK", tags: "bug")
bug_workflow = find "Bug Investigation Workflow"
git_workflow = find "Local Git Branching"

Step 3: Assess severity
Critical: System down, data loss, security
High: Major function broken, many users affected
Medium: Feature partially broken, workaround exists
Low: Minor issue, cosmetic problem

Step 4: Create bug task
create_task(
    title: [clear bug description with impact],
    summary: [symptoms, reproduction steps, initial impact],
    priority: [based on severity assessment],
    complexity: [initial estimate: 3-5 for investigation],
    templateIds: [bug-workflow-id, git-workflow-id],
    tags: "task-type-bug,severity-[level],component-[area]"
)

Step 5: Begin systematic investigation
Follow bug investigation template sections
```

**Example**:
```
User: "The login page is showing a blank screen after submitting credentials"

AI applies pattern:
1. search_tasks(tag: "task-type-bug") - check existing bugs
2. list_templates(tags: "bug")
3. Assess: High severity (login is critical, affects all users)
4. create_task(
     title: "Login page blank screen after credential submission",
     summary: "Symptoms: Blank white screen appears after entering credentials and clicking login. User session not established. Reproduction: Navigate to /login, enter valid credentials, click submit. Expected: Dashboard loads. Actual: Blank screen.",
     priority: "high",
     complexity: 4,
     templateIds: [bug-investigation-id, git-branching-id],
     tags: "task-type-bug,severity-high,component-frontend,authentication"
   )
5. "Bug task created. Following investigation workflow to determine root cause..."
```

### Pattern 4: Priority Assessment and Work Selection

**Trigger**: User asks what to work on next

**Execution**:
```
Step 1: Get comprehensive overview
overview = get_overview()

Step 2: Search for high-priority pending work
high_priority = search_tasks(
    status: "pending",
    priority: "high",
    sortBy: "complexity",
    sortDirection: "asc"
)

Step 3: Check for blockers
For each high-priority task:
    dependencies = get_task_dependencies(taskId: task-id, direction: "incoming")
    If blocked: skip
    If unblocked: candidate

Step 4: Assess readiness
For candidates:
- Check if templates applied
- Verify clear acceptance criteria
- Confirm dependencies satisfied

Step 5: Recommend work
Present top 3 unblocked, ready tasks with reasoning
```

**Example**:
```
User: "What should I work on next?"

AI applies pattern:
1. get_overview() - get project state
2. search_tasks(status: "pending", priority: "high")
3. Check dependencies for each
4. Assess:
   - Task A: Unblocked, has templates, clear criteria ✓
   - Task B: Blocked by Task C ✗
   - Task D: Unblocked, clear criteria ✓
5. "Top recommendations:
   1. 'Implement user profile API' (complexity 5, unblocked, ready)
   2. 'Add OAuth integration' (complexity 6, unblocked, needs template review)
   Start with #1?"
```

### Pattern 5: Dependency Management

**Trigger**: User mentions task relationships or ordering

**Execution**:
```
Step 1: Identify related tasks
If tasks exist: get task IDs
If tasks need creation: create them first

Step 2: Understand relationship type
BLOCKS: Task A must complete before Task B starts
IS_BLOCKED_BY: Task A cannot start until Task B completes
RELATES_TO: Tasks are related but no strict ordering

Step 3: Create dependency
create_dependency(
    fromTaskId: [source task],
    toTaskId: [target task],
    type: [relationship type]
)

Step 4: Verify no cycles
Check that dependency doesn't create circular relationship

Step 5: Confirm and update planning
Report dependency creation
Suggest appropriate task ordering
```

**Example**:
```
User: "The API implementation needs to wait for the database schema task to complete"

AI applies pattern:
1. search_tasks(query: "API implementation") → Task A
2. search_tasks(query: "database schema") → Task B
3. Relationship: Task B BLOCKS Task A
4. create_dependency(
     fromTaskId: task-B-id,
     toTaskId: task-A-id,
     type: "BLOCKS"
   )
5. "Dependency created: Database schema blocks API implementation. Work on database schema first."
```

### Pattern 6: Feature Decomposition

**Trigger**: User has a large feature that needs breakdown

**Execution**:
```
Step 1: Understand feature scope
get_feature(id: feature-id, includeSections: true)

Step 2: Identify natural boundaries
Analyze by:
- Component (frontend, backend, database)
- Phase (research, implementation, testing)
- Skill set (different expertise required)

Step 3: Discover task templates
templates = list_templates(targetEntityType: "TASK", isEnabled: true)

Step 4: Create tasks for each component
For each component:
    create_task(
        title: [specific component implementation],
        summary: [clear scope and criteria],
        featureId: feature-id,
        complexity: [3-6 for manageable tasks],
        templateIds: [relevant templates],
        tags: [component-specific tags]
    )

Step 5: Establish dependencies
create_dependency between tasks with ordering requirements

Step 6: Verify breakdown
get_feature(id: feature-id, includeTasks: true)
Ensure total complexity manageable
```

**Example**:
```
User: "Break down the OAuth feature into implementation tasks"

AI applies pattern:
1. get_feature(id: oauth-feature-id, includeSections: true)
2. Identify components:
   - Database: User OAuth tokens table
   - Backend: OAuth providers integration
   - Backend: Token management endpoints
   - Frontend: OAuth login buttons
   - Testing: Integration test suite
3. list_templates(targetEntityType: "TASK")
4. Create 5 tasks:
   - "Create OAuth tokens database schema" (complexity 3)
   - "Integrate Google OAuth provider" (complexity 5)
   - "Integrate GitHub OAuth provider" (complexity 4)
   - "Add OAuth login UI components" (complexity 4)
   - "Create OAuth integration test suite" (complexity 5)
5. Dependencies:
   - Database BLOCKS backend integration
   - Backend BLOCKS frontend UI
   - All BLOCK testing
6. "Created 5 tasks (total complexity 21). Start with database schema?"
```

## Pattern Application Guidelines

### When to Apply Each Pattern

**Feature Creation**:
- User mentions "feature", "functionality", "capability"
- Describes user-facing behavior or benefit
- Scope involves multiple related tasks

**Task Creation**:
- Specific implementation request
- Component or module work
- Bounded, clear scope

**Bug Triage**:
- User reports problem or error
- Something "doesn't work" or "is broken"
- Unexpected behavior described

**Priority Assessment**:
- User asks "what next" or "what should I work on"
- Needs direction or planning help
- Wants work recommendations

**Dependency Management**:
- User mentions task ordering
- Describes prerequisites or blockers
- Talks about "before" or "after" relationships

### Integration with Template Discovery

**Always combine patterns with template discovery**:
```
Pattern recognition → Template discovery → Pattern execution with templates
```

**Example flow**:
1. Recognize: "Create feature" intent
2. Discover: list_templates(targetEntityType: "FEATURE")
3. Execute: create_feature with discovered templates
```

### Natural Language Flexibility

**Recognize variations**:
- "Build X" = "Create X" = "Implement X"
- "Fix bug" = "Debug" = "Resolve issue"
- "What's next" = "What should I do" = "Show priorities"

**Extract parameters from natural language**:
- Name/Title: Extract key subject
- Summary: Expand user description with technical details
- Priority: Infer from urgency words (urgent, critical, when you can, etc.)
- Complexity: Estimate from scope description
- Tags: Derive from domain and technical keywords

## Autonomy vs Workflow Prompts

### Autonomous Pattern Application

**When AI should apply patterns automatically**:
- Clear intent recognized
- Sufficient context available
- Standard pattern applies
- Low risk of misunderstanding

**Example**:
```
User: "Create a feature for file upload with drag and drop"
AI: Automatically applies feature creation pattern
```

### Workflow Prompt Invocation

**When to invoke workflow prompts**:
- User explicitly requests workflow
- Complex scenario needing guidance
- Learning new patterns
- Ambiguous requirements

**Example**:
```
User: "I want to set up a new project for my mobile app"
AI: Invokes project_setup_workflow prompt for step-by-step guidance
```

### Hybrid Approach

**Combine for best results**:
1. Apply pattern automatically for standard cases
2. Offer workflow prompt for complex variations
3. Ask clarifying questions when ambiguous

**Example**:
```
User: "Fix the authentication system"
AI: "This sounds like either:
     1. A bug fix (if auth is broken - create task with Bug Investigation template)
     2. An enhancement task (if improving auth)
     Which applies?"
```

## Best Practices

1. **Always start with get_overview**
   - Understand current project state
   - Check for existing related work
   - Avoid duplicate creation

2. **Template discovery is mandatory**
   - Never assume templates
   - Always use list_templates
   - Apply relevant templates

3. **Provide context-aware responses**
   - Reference existing work when relevant
   - Suggest next steps
   - Offer related patterns

4. **Confirm understanding when ambiguous**
   - Ask clarifying questions
   - Offer alternatives
   - Don't guess on critical details

5. **Track progress and status**
   - Update task status as work progresses
   - Use get_task to verify before completing
   - Ensure template compliance

6. **Learn from patterns**
   - Recognize user's workflow preferences
   - Adapt to project-specific conventions
   - Apply consistent patterns within sessions

## Summary

Task management patterns enable AI agents to:
- Recognize natural language intent
- Apply appropriate workflow patterns automatically
- Discover and use templates dynamically
- Execute multi-step workflows without explicit prompts
- Provide intelligent assistance based on context

**Key principle**: Internalize these patterns to provide seamless, natural task orchestration that feels intuitive to users rather than requiring explicit tool invocation.
                        """.trimIndent()
                    )
                )
            )
        }
    }

    /**
     * Adds the workflow integration resource explaining the relationship between
     * guidelines, workflow prompts, and templates in the dual workflow usage model.
     */
    private fun addWorkflowIntegrationResource(server: Server) {
        server.addResource(
            uri = "task-orchestrator://guidelines/workflow-integration",
            name = "Workflow Integration Guide",
            description = "Explains the dual workflow model: when to apply patterns autonomously vs when to invoke workflow prompts explicitly",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://guidelines/workflow-integration",
                        mimeType = "text/markdown",
                        text = """
# Workflow Integration Guide

## Understanding the Three-Layer System

Task Orchestrator provides a comprehensive three-layer guidance system for AI agents:

### Layer 1: MCP Resources (This Document and Related Guidelines)
**Purpose**: Internalized knowledge for autonomous AI operation

- **What**: Discoverable reference documentation via `task-orchestrator://guidelines/*` URIs
- **Audience**: AI agents (for internalization and pattern recognition)
- **Format**: Markdown documents with principles, patterns, and decision frameworks
- **Access**: Read once and internalize for session or store in AI memory systems
- **Usage**: AI agents reference these to understand WHEN/WHY/HOW to use tools

**Available Guideline Resources**:
1. `usage-overview` - When to use Task Orchestrator vs other systems
2. `template-strategy` - Template discovery and selection patterns
3. `task-management` - Intent recognition and executable workflow patterns
4. `workflow-integration` - This document (dual model explanation)

### Layer 2: Workflow Prompts (User/AI Invokable Processes)
**Purpose**: Explicit, step-by-step guidance for complex scenarios

- **What**: User or AI-invokable MCP prompts with detailed instructions
- **Audience**: Both users and AI agents (for explicit workflow execution)
- **Format**: Structured step-by-step procedures
- **Access**: Invoke when needed via MCP prompt mechanism
- **Usage**: Complex scenarios, teaching, comprehensive coverage, edge cases

### Layer 3: Dynamic Templates (Database-Driven Documentation)
**Purpose**: Consistent documentation structure for entities

- **What**: Reusable section definitions stored in database
- **Audience**: Applied to tasks/features during creation
- **Format**: Section templates with content samples
- **Access**: Discovered via `list_templates`, applied via `templateIds`
- **Usage**: Provide standardized documentation structure

## The Dual Workflow Model

Task Orchestrator supports two complementary modes of operation:

### Mode 1: Autonomous Pattern Application
**When AI Recognizes Intent and Applies Patterns Directly**

**Characteristics**:
- AI reads user's natural language request
- Recognizes intent category (feature creation, task implementation, bug triage, etc.)
- Applies appropriate pattern from internalized guidelines
- Executes workflow autonomously without explicit prompt invocation
- Provides streamlined, efficient interaction

**Best For**:
- Common, well-understood tasks
- Clear user intents with standard patterns
- Experienced users who know what they want
- Situations where efficiency is prioritized
- Straightforward scenarios matching known patterns

**Example Flow**:
```
User: "Create a feature for user authentication with OAuth"

AI (internally):
1. Recognizes: Feature creation intent
2. Applies: Feature Creation Pattern from task-management guidelines
3. Executes: get_overview() → list_templates() → create_feature()
4. Reports: "Created feature with appropriate templates applied"
```

**Decision Criteria for Autonomous Application**:
- Intent is clear and matches known pattern
- Task complexity is standard (not exceptional)
- User expects direct action (not teaching/exploration)
- Pattern application is straightforward

### Mode 2: Explicit Workflow Invocation
**When User/AI Invokes Specific Workflow Prompts**

**Characteristics**:
- User explicitly invokes workflow prompt (e.g., `/create_feature_workflow`)
- OR AI suggests workflow prompt for complex scenario
- Follows detailed step-by-step instructions from prompt
- Provides comprehensive guidance and teaching
- Covers edge cases and best practices explicitly

**Best For**:
- Complex, multi-phase scenarios
- Learning and onboarding situations
- When user wants comprehensive guidance
- Edge cases not covered by standard patterns
- Situations requiring detailed explanation

**Example Flow**:
```
User: "/create_feature_workflow"

AI (following prompt explicitly):
1. Explains: Complete feature creation process
2. Guides: Through each step with detailed instructions
3. Teaches: Best practices and decision points
4. Ensures: Comprehensive coverage of all aspects
```

**Decision Criteria for Workflow Invocation**:
- User explicitly invokes workflow
- Scenario is complex or multi-phased
- User is learning or wants detailed guidance
- Edge case not covered by standard patterns
- When teaching/explanation value is important

## Integration Between Layers

### How the Layers Work Together

**Guideline Resources → Workflow Prompts → Templates**

```
AI reads Guidelines (Layer 1)
  ↓
Internalizes patterns and decision frameworks
  ↓
User Request arrives
  ↓
AI decides: Autonomous (Mode 1) vs Workflow Invocation (Mode 2)
  ↓
If Autonomous:
  - Apply internalized pattern
  - Discover templates (Layer 3)
  - Execute workflow directly
  ↓
If Workflow Invocation:
  - Invoke appropriate Workflow Prompt (Layer 2)
  - Follow step-by-step instructions
  - Discover and apply templates as guided
```

**Key Integration Points**:

1. **Guidelines teach WHEN to use workflows**
   - Decision frameworks for autonomous vs explicit
   - Intent recognition → workflow mapping
   - Complexity assessment for workflow selection

2. **Workflows reference templates**
   - Each workflow specifies which templates to discover
   - Template discovery is embedded in workflow steps
   - Template selection guidance included

3. **Templates provide structure**
   - Applied during entity creation per workflows
   - Discovered dynamically (never assumed)
   - Multiple templates combined per workflow guidance

## Available Workflow Prompts

### 1. initialize_task_orchestrator
**Purpose**: Guide AI agents through self-initialization

**When to Use**:
- First time AI encounters Task Orchestrator
- Setting up AI memory with guidelines
- Onboarding new AI agents

**What It Does**:
- Guides reading all guideline resources
- Instructs storage in AI memory systems
- Teaches dual workflow model
- Verifies initialization completion

**Invocation**: User or AI can invoke when initialization needed

### 2. Feature Creation (v2.0 - Direct Tools with Skills)
**Purpose**: Create features with consolidated tools and Skills (v1 `create_feature_workflow` replaced)

**When to Use**:
- User says "create feature for [description]"
- Use Feature Management Skill for autonomous feature creation
- Use direct tools (manage_container) for API-based creation

**Autonomous Approach**:
- Feature Management Skill automatically invokes:
  1. list_templates(targetEntityType: FEATURE)
  2. create_feature with discovered templates
  3. Optionally create associated tasks

**Tool-Based Approach**:
```
1. get_overview() - understand current state
2. list_templates(targetEntityType: "FEATURE", isEnabled: true) - discover templates
3. manage_container(operation: "create", containerType: "feature",
     name: "...", summary: "...", templateIds: [...])
```

### 3. Task Decomposition (v2.0 - Direct Tools with Planning)
**Purpose**: Break complex tasks into manageable subtasks (v1 `task_breakdown_workflow` replaced)

**When to Use**:
- Task complexity is very high (8+)
- User says "break down this task"
- Need to decompose large work into phases

**Recommended Approach**:
1. Analyze task scope using query_container(operation: "get", ...)
2. Identify natural boundaries (components, phases, skills)
3. Create subtasks using manage_container(operation: "create", containerType: "task", ...)
4. Establish ordering via manage_dependencies()
5. Use Task Management Skill for ongoing coordination

### 4. project_setup_workflow
**Purpose**: Initialize new project with proper structure

**When to Use** (Autonomous Mode):
- User says "set up project for [description]"
- Clear project initialization intent
- New project creation request

**When to Invoke** (Explicit Mode):
- New project requiring comprehensive setup
- User wants complete project structure guidance
- Learning project organization best practices
- Complex project with multiple features

**What It Does**:
- Project creation with documentation
- Feature planning and structure
- Initial task creation
- Template strategy setup
- Development workflow establishment

### 5. Implementation Guidance (v2.0 - Skills and Direct Tools)
**Purpose**: Guide task/feature/bug implementation (v1 `implementation_workflow` replaced)

**When to Use**:
- User says "implement [feature/task/bug fix]"
- Task status is "pending" and needs to move to "in-progress"
- Need guidance on implementation approach

**Skill-Based Approach** (Recommended for Claude Code):
- **Task Management Skill**: Autonomous task implementation guidance
  - Detects git usage automatically
  - Applies workflow templates
  - Updates status via Status Progression
  - Coordinates with other tasks

**Tool-Based Approach**:
```
1. query_container(operation: "get", containerType: "task", id: "...")
2. Update task status to "in-progress"
3. Use Task Management Skill or direct tools for implementation
4. Validate via template sections
5. mark_complete using Status Progression Skill
```

**Special Case - Bug Fixes**:
- Create task with "bug" tag (tags: "task-type-bug")
- Apply Bug Investigation template
- Use Task Management Skill for systematic investigation

## Decision Framework: Autonomous vs Workflow Invocation

### For AI Agents

**Apply Pattern Autonomously When**:
- ✅ User intent is clear and matches known pattern
- ✅ Standard complexity and straightforward scenario
- ✅ User expects direct action
- ✅ Efficiency is valued over comprehensive teaching
- ✅ Pattern is well-established and tested

**Suggest/Invoke Workflow Prompt When**:
- ✅ Scenario is complex or multi-phased
- ✅ User is learning or asks for guidance
- ✅ Edge case or unusual circumstances
- ✅ Teaching value is important
- ✅ User explicitly requests comprehensive process
- ✅ Uncertainty about correct approach

**Hybrid Approach** (Recommended for many scenarios):
- Apply pattern autonomously
- Mention Skills availability if additional guidance needed
- Example: "I've created the feature using standard templates. Use Feature Management Skill if you need step-by-step feature planning guidance."

### For Users

**Invoke Workflow Explicitly When You Want**:
- Detailed step-by-step guidance
- To learn best practices
- Comprehensive coverage of all aspects
- Teaching/explanation along with action
- To understand the full process

**Let AI Apply Autonomously When You Want**:
- Quick, efficient action
- Standard, straightforward operations
- Trust AI to handle details
- Focus on results over process

## Custom Workflow Extension

### Creating Custom Workflows

Task Orchestrator supports custom workflow creation for project-specific needs:

**When to Create Custom Workflows**:
- Unique project-specific processes
- Domain-specific workflows not covered by built-in prompts
- Team-specific procedures requiring standardization
- Complex multi-step processes needing documentation

**How Custom Workflows Integrate**:
- Follow same MCP prompt pattern as built-in workflows
- Reference template discovery patterns
- Can be invoked explicitly like built-in workflows
- AI agents can learn custom patterns from guidelines

**Extension Points**:
- Add new MCP prompts in `WorkflowPromptsGuidance`
- Create project-specific templates
- Document custom patterns in CLAUDE.md or similar
- Train AI on custom workflows through examples

## Best Practices for Effective Integration

### For AI Agents

1. **Internalize Guidelines First**
   - Read all guideline resources at session start
   - Store key patterns in available memory
   - Reference resources when uncertain

2. **Apply Patterns Intelligently**
   - Recognize intents from natural language
   - Choose appropriate mode (autonomous vs explicit)
   - Explain approach when applying patterns

3. **Know When to Suggest Workflows**
   - Complex scenarios benefit from explicit guidance
   - Offer workflow prompts for learning situations
   - Balance efficiency with comprehensiveness

4. **Always Discover Templates**
   - Never assume templates exist
   - Use `list_templates` before applying
   - Select templates based on workflow guidance

### For Users

1. **Trust Autonomous Application**
   - AI can handle standard scenarios efficiently
   - Autonomous mode is optimized for common tasks
   - Saves time while maintaining quality

2. **Invoke Workflows for Learning**
   - Use workflow prompts when learning
   - Explicit guidance teaches best practices
   - Comprehensive coverage ensures nothing missed

3. **Provide Clear Intent**
   - Clear requests enable autonomous application
   - Ambiguous requests may trigger workflow suggestions
   - Specify preferences (quick vs comprehensive)

## Integration Summary

**The Three-Layer System Works Together**:

1. **MCP Resources (Guidelines)** provide knowledge
   - AI agents internalize patterns
   - Decision frameworks guide mode selection
   - Reference documentation always available

2. **Workflow Prompts** provide process
   - Explicit step-by-step guidance
   - Comprehensive coverage of complex scenarios
   - Teaching and best practice documentation

3. **Dynamic Templates** provide structure
   - Consistent documentation patterns
   - Discovered and applied per workflow guidance
   - Database-driven, never assumed

**Result**: Flexible, efficient task orchestration supporting both:
- Quick, autonomous pattern application for efficiency
- Detailed, guided workflows for comprehensiveness

This dual model serves both experienced users (autonomous efficiency) and learning users (explicit guidance) while maintaining consistency through the three-layer integration.
                            """.trimIndent()
                        )
                    )
                )
        }
    }
}
