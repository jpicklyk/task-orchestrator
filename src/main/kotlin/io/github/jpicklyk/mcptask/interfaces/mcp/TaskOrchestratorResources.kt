package io.github.jpicklyk.mcptask.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * MCP Resources for Task Orchestrator AI Guidelines.
 *
 * These resources provide discoverable guidelines that AI agents can reference
 * to understand when and how to use Task Orchestrator tools effectively.
 * Resources focus on WHEN/WHY principles rather than detailed HOW instructions.
 *
 * ## Two-Layer Setup Instructions Architecture
 *
 * Non-plugin agents (those using only `.mcp.json`, not the Claude Code plugin) need
 * workflow guidance injected into their project's CLAUDE.md (or equivalent instructions file).
 * This is delivered through a two-layer system:
 *
 * **Layer 1 — `server.instructions` (every session, ~80 tokens):**
 * [McpServer.configureServer] passes [SETUP_INSTRUCTIONS_VERSION] into the MCP `Server`
 * constructor's `instructions` parameter. The MCP protocol delivers this string to agents
 * once during `initialize`. It tells the agent to look for a version marker comment
 * (`<!-- mcp-task-orchestrator-setup: vN -->`) in their project config and, if missing or
 * outdated, read the on-demand resource.
 *
 * **Layer 2 — MCP Resource (on demand, ~600 tokens):**
 * [addSetupInstructionsResource] registers `task-orchestrator://guidelines/setup-instructions`,
 * which contains the full CLAUDE.md template with all workflow rules, status flow tables,
 * cleanup warnings, and resource links. Agents read this only when they need to install or
 * update the instructions block.
 *
 * **Version lifecycle:** When the CLAUDE.md template changes, bump [SETUP_INSTRUCTIONS_VERSION].
 * Agents with an older marker will detect the mismatch via Layer 1 and re-read Layer 2
 * to get the updated template.
 *
 * @see McpServer.configureServer where `server.instructions` references this version constant
 */
object TaskOrchestratorResources {

    /**
     * Version marker embedded in the CLAUDE.md template output.
     *
     * This version string appears in three places and must stay in sync:
     * 1. **`server.instructions`** in [McpServer.configureServer] — tells agents which version to look for
     * 2. **MCP Resource content** in [addSetupInstructionsResource] — the template agents copy into CLAUDE.md
     * 3. **Plugin skill** at `claude-plugins/task-orchestrator/skills/setup-instructions/SKILL.md` —
     *    the Claude Code plugin's `/setup-instructions` skill template output
     *
     * When the CLAUDE.md template changes materially, bump this to `"v2"`, `"v3"`, etc.
     * Agents whose CLAUDE.md contains an older marker will be prompted by `server.instructions`
     * to re-read the setup resource and update their instructions block.
     */
    const val SETUP_INSTRUCTIONS_VERSION = "v1"

    /**
     * Configures all Task Orchestrator guideline resources with the MCP server.
     */
    fun configure(server: Server) {
        addUsageOverviewResource(server)
        addTemplateStrategyResource(server)
        addTaskManagementPatternsResource(server)
        addWorkflowIntegrationResource(server)
        addSetupInstructionsResource(server)
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

## Available Tools

Task Orchestrator provides consolidated tools for all scenarios:

### Core Operations
- **`manage_container`**: Create, update, delete for projects/features/tasks (all use array parameters)
- **`query_container`**: Get, search, export, overview for projects/features/tasks
- **`request_transition`**: Trigger-based status changes with validation (preferred for status updates)
- **`get_next_status`**: Read-only status progression recommendations
- **Template discovery**: Always discover and apply templates during creation

### How Workflows Work
1. **Direct tool invocation**: Use consolidated tools for all operations
2. **Template discovery**: Always discover and apply templates during creation
3. Best practices are embedded in tool descriptions

## Template Discovery and Application

### Template Philosophy
Templates provide **structured documentation patterns** and **workflow guidance**:

- **Workflow Templates**: Step-by-step implementation instructions (Git workflow, PR process, testing)
- **Documentation Templates**: Content structure for requirements, technical approach, context
- **Quality Templates**: Completion criteria, testing strategy, definition of done

### Dynamic Template Discovery
**Always check templates before creating tasks or features**:
```
1. Use `query_templates --targetEntityType TASK` or `FEATURE`
2. Review available templates and their descriptions
3. Select appropriate templates based on work type
4. Apply templates via `templateIds` parameter during creation
```

### Template Combination Strategies
Combine templates for comprehensive coverage:
- **Development Task**: Technical Approach + Task Implementation + Git Branching
- **Bug Fix**: Bug Investigation + Git Branching + Definition of Done
- **Feature Planning**: Context & Background + Requirements + Testing Strategy

## How Guidance Is Delivered

Task Orchestrator provides guidance through multiple channels:

1. **MCP Resources** (these documents): Discoverable reference documentation for principles and patterns
2. **Tool Descriptions**: Detailed parameter and usage documentation embedded in each tool
3. **Dynamic Templates**: Database-driven documentation structure applied during entity creation

AI agents should internalize these guidelines and apply patterns naturally based on user requests.

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
**How**: Use `query_templates` filtered by entity type

### Principle 2: Progressive Detail
**Why**: Avoid overwhelming upfront documentation requirements
**When**: Start with essential information, add detail as work progresses
**How**: Create entity with templates, add sections as needed

### Principle 3: Status-Driven Lifecycle
**Why**: Clear status tracking enables progress visibility
**When**: Update status as work transitions between phases
**How**: pending → in-progress → completed (use `request_transition`)

### Principle 4: Context Efficiency
**Why**: Reduce token usage for better AI agent performance
**When**: Working with large projects or many tasks
**How**: Use summary views, bulk operations, targeted queries

### Principle 5: Workflow Integration
**Why**: Leverage existing development processes (git, PR workflows)
**When**: Project uses version control or code review processes
**How**: Apply workflow templates that provide step-by-step git guidance

## Best Practices

1. **Start every session** with `query_container(operation="overview")` to understand current state
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
2. Use `query_container(operation="overview")` to understand current project state
3. Check `query_templates` to see available documentation patterns
4. Follow tool descriptions and template guidance for complex scenarios
5. Apply patterns naturally based on user requests

**For Users**:
1. Use natural language requests for autonomous AI assistance
2. Trust AI agents to use Task Orchestrator when appropriate
3. Provide clear requirements and let Skills and tools handle the details

This overview focuses on WHEN and WHY to use Task Orchestrator. For specific HOW-TO guidance, refer to individual tool descriptions and template documentation.
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

**Always discover templates dynamically** using `query_templates` before applying them.

## Why Template Discovery Matters

### The Problem with Assumptions
```
❌ WRONG: Assuming templates exist
manage_container(operation="create", containerType="task", containers=[{...}], templateIds=["task-implementation-workflow-uuid"])
// Fails if template disabled, deleted, or has different UUID

✅ CORRECT: Discovering templates first
query_templates(targetEntityType: "TASK", isEnabled: true)
// Review available templates
// Select appropriate template IDs from results
manage_container(operation="create", containerType="task", containers=[{...}], templateIds=[discovered-uuid])
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
query_templates --targetEntityType TASK --isEnabled true
// or
query_templates --targetEntityType FEATURE --isEnabled true
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
manage_container(operation="create", containerType="task",
    containers=[{title: "...", summary: "..."}],
    templateIds=[selected-uuids-from-discovery]
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

**Common Examples** (discover via query_templates):
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

**Common Examples** (discover via query_templates):
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

**Common Examples** (discover via query_templates):
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
templates = query_templates(targetEntityType: "TASK", tags: "implementation")
selected = find template matching "Task Implementation"

manage_container(operation="create", containerType="task",
    containers=[{...}], templateIds=[selected.id]
)
```

### Pattern 2: Development Task with Git
```
// For standard development work (complexity 4-6)
templates = query_templates(targetEntityType: "TASK", isEnabled: true)
implementation = find "Task Implementation Workflow"
git = find "Local Git Branching Workflow"

manage_container(operation="create", containerType="task",
    containers=[{...}], templateIds=[implementation.id, git.id]
)
```

### Pattern 3: Complex Feature Task
```
// For complex tasks requiring architecture (complexity 7-10)
templates = query_templates(targetEntityType: "TASK", isEnabled: true)
implementation = find "Task Implementation"
technical = find "Technical Approach"
git = find "Local Git Branching"
testing = find "Testing Strategy"

manage_container(operation="create", containerType="task",
    containers=[{...}], templateIds=[implementation.id, technical.id, git.id, testing.id]
)
```

### Pattern 4: Feature with Full Documentation
```
// For well-documented features
templates = query_templates(targetEntityType: "FEATURE", isEnabled: true)
context = find "Context & Background"
requirements = find "Requirements Specification"
technical = find "Technical Approach"

manage_container(operation="create", containerType="feature",
    containers=[{...}], templateIds=[context.id, requirements.id, technical.id]
)
```

### Pattern 5: Bug Investigation
```
// For systematic bug resolution
templates = query_templates(targetEntityType: "TASK", tags: "bug")
bug_workflow = find "Bug Investigation Workflow"
git = find "Local Git Branching"

manage_container(operation="create", containerType="task",
    containers=[{...}], templateIds=[bug_workflow.id, git.id]
)
```

## Common Mistakes to Avoid

### ❌ Mistake 1: Hardcoding Template IDs
```
// NEVER do this
manage_container(operation="create", containerType="task", containers=[{...}], templateIds=["550e8400-e29b-41d4-a716-446655440000"])
```

### ✅ Solution: Always Discover
```
templates = query_templates(targetEntityType: "TASK")
selected = templates.find(name: "Task Implementation Workflow")
manage_container(operation="create", containerType="task", containers=[{...}], templateIds=[selected.id])
```

### ❌ Mistake 2: Assuming Template Names
```
// Templates might be renamed
templates = query_templates()
task_template = templates.find(name: "Task Template")  // Might not exist
```

### ✅ Solution: Use Descriptions and Tags
```
templates = query_templates(targetEntityType: "TASK", tags: "implementation")
// Review descriptions to find right template
selected = templates.find_by_purpose(...)
```

### ❌ Mistake 3: Ignoring Disabled Templates
```
// Disabled templates won't work
templates = query_templates(targetEntityType: "TASK")  // Includes disabled
```

### ✅ Solution: Filter for Enabled
```
templates = query_templates(targetEntityType: "TASK", isEnabled: true)
```

### ❌ Mistake 4: Over-templating
```
// Too many templates creates noise
manage_container(operation="create", containerType="task", containers=[{...}], templateIds=[all_discovered_templates])
```

### ✅ Solution: Select Relevant Templates
```
// Choose 2-4 most relevant templates based on task needs
```

## Best Practices

1. **Always Start with Discovery**
   - Never skip `query_templates`
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
- Always use `query_templates` before applying
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
query_container(operation="overview")

Step 2: Discover available templates
templates = query_templates(targetEntityType: "FEATURE", isEnabled: true)

Step 3: Select relevant templates
context_template = find template with "Context" or "Background"
requirements_template = find template with "Requirements"

Step 4: Create feature with templates
manage_container(operation="create", containerType="feature",
    containers=[{
        name: [extract feature name from user request],
        summary: [comprehensive description based on user input],
        status: "planning",
        priority: [assess from context: high/medium/low],
        tags: [derive from feature type and domain]
    }],
    templateIds=[selected template IDs]
)

Step 5: Confirm creation and suggest next steps
"Feature created. Ready to break down into tasks?"
```

**Example**:
```
User: "Create a feature for OAuth authentication with Google and GitHub"

AI applies pattern:
1. query_container(operation="overview") - check existing work
2. query_templates(targetEntityType: "FEATURE") - discover templates
3. manage_container(operation="create", containerType="feature",
     containers=[{
       name: "OAuth Authentication Integration",
       summary: "Implement OAuth 2.0 authentication with Google and GitHub providers...",
       priority: "high",
       tags: "authentication,oauth,security,user-management"
     }],
     templateIds=[context-id, requirements-id]
   )
4. "Feature created with 2 documentation templates. Ready to create implementation tasks?"
```

### Pattern 2: Task Creation with Template Discovery

**Trigger**: User requests implementation of specific functionality

**Execution**:
```
Step 1: Understand context
query_container(operation="overview") - see current work
Detect git usage: check for .git directory

Step 2: Discover templates
templates = query_templates(targetEntityType: "TASK", isEnabled: true)

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
manage_container(operation="create", containerType="task",
    containers=[{
        title: [specific, actionable title],
        summary: [detailed description with acceptance criteria],
        complexity: [assessed complexity],
        priority: [high/medium/low based on urgency],
        tags: [task-type, component, technology]
    }],
    templateIds=[selected template IDs]
)

Step 6: Update status when starting
request_transition(transitions=[{containerId: "task-id", containerType: "task", trigger: "start"}])
```

**Example**:
```
User: "Implement API endpoints for user profile management"

AI applies pattern:
1. query_container(operation="overview") - check current state
2. Check for .git → detected
3. query_templates(targetEntityType: "TASK")
4. Assess complexity: Medium (4-6) - API + validation + tests
5. manage_container(operation="create", containerType="task",
     containers=[{
       title: "Implement user profile management API endpoints",
       summary: "Create REST API endpoints for user profile CRUD operations. Include: GET /profile, PUT /profile, validation, error handling, tests",
       complexity: 5,
       priority: "medium",
       tags: "task-type-feature,api,backend,user-management"
     }],
     templateIds=[impl-workflow-id, git-workflow-id, technical-approach-id]
   )
6. "Task created with git branching workflow. Ready to start implementation?"
```

### Pattern 3: Bug Triage and Investigation

**Trigger**: User reports a bug or issue

**Execution**:
```
Step 1: Check existing bugs
query_container(operation="search", containerType="task",tag: "task-type-bug", status: "pending")

Step 2: Discover bug investigation templates
templates = query_templates(targetEntityType: "TASK", tags: "bug")
bug_workflow = find "Bug Investigation Workflow"
git_workflow = find "Local Git Branching"

Step 3: Assess severity
Critical: System down, data loss, security
High: Major function broken, many users affected
Medium: Feature partially broken, workaround exists
Low: Minor issue, cosmetic problem

Step 4: Create bug task
manage_container(operation="create", containerType="task",
    containers=[{
        title: [clear bug description with impact],
        summary: [symptoms, reproduction steps, initial impact],
        priority: [based on severity assessment],
        complexity: [initial estimate: 3-5 for investigation],
        tags: "task-type-bug,severity-[level],component-[area]"
    }],
    templateIds=[bug-workflow-id, git-workflow-id]
)

Step 5: Begin systematic investigation
Follow bug investigation template sections
```

**Example**:
```
User: "The login page is showing a blank screen after submitting credentials"

AI applies pattern:
1. query_container(operation="search", containerType="task",tag: "task-type-bug") - check existing bugs
2. query_templates(tags: "bug")
3. Assess: High severity (login is critical, affects all users)
4. manage_container(operation="create", containerType="task",
     containers=[{
       title: "Login page blank screen after credential submission",
       summary: "Symptoms: Blank white screen appears after entering credentials and clicking login. User session not established. Reproduction: Navigate to /login, enter valid credentials, click submit. Expected: Dashboard loads. Actual: Blank screen.",
       priority: "high",
       complexity: 4,
       tags: "task-type-bug,severity-high,component-frontend,authentication"
     }],
     templateIds=[bug-investigation-id, git-branching-id]
   )
5. "Bug task created. Following investigation workflow to determine root cause..."
```

### Pattern 4: Priority Assessment and Work Selection

**Trigger**: User asks what to work on next

**Execution**:
```
Step 1: Get comprehensive overview
overview = query_container(operation="overview")

Step 2: Search for high-priority pending work
high_priority = query_container(operation="search", containerType="task",
    status: "pending",
    priority: "high",
    sortBy: "complexity",
    sortDirection: "asc"
)

Step 3: Check for blockers
For each high-priority task:
    dependencies = query_dependencies(taskId: task-id, direction: "incoming")
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
1. query_container(operation="overview") - get project state
2. query_container(operation="search", containerType="task",status: "pending", priority: "high")
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
manage_dependencies(operation="create",
    dependencies=[{fromTaskId: [source task], toTaskId: [target task], type: [relationship type]}]
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
1. query_container(operation="search", containerType="task",query: "API implementation") → Task A
2. query_container(operation="search", containerType="task",query: "database schema") → Task B
3. Relationship: Task B BLOCKS Task A
4. manage_dependencies(operation="create",
     dependencies=[{fromTaskId: task-B-id, toTaskId: task-A-id, type: "BLOCKS"}]
   )
5. "Dependency created: Database schema blocks API implementation. Work on database schema first."
```

### Pattern 6: Feature Decomposition

**Trigger**: User has a large feature that needs breakdown

**Execution**:
```
Step 1: Understand feature scope
query_container(operation="get", containerType="feature",id: feature-id, includeSections: true)

Step 2: Identify natural boundaries
Analyze by:
- Component (frontend, backend, database)
- Phase (research, implementation, testing)
- Skill set (different expertise required)

Step 3: Discover task templates
templates = query_templates(targetEntityType: "TASK", isEnabled: true)

Step 4: Create tasks for each component
For each component:
    manage_container(operation="create", containerType="task",
        containers=[{
            title: [specific component implementation],
            summary: [clear scope and criteria],
            complexity: [3-6 for manageable tasks],
            tags: [component-specific tags]
        }],
        featureId=feature-id,
        templateIds=[relevant templates]
    )

Step 5: Establish dependencies
manage_dependencies(operation="create") between tasks with ordering requirements

Step 6: Verify breakdown
query_container(operation="get", containerType="feature",id: feature-id, includeTasks: true)
Ensure total complexity manageable
```

**Example**:
```
User: "Break down the OAuth feature into implementation tasks"

AI applies pattern:
1. query_container(operation="get", containerType="feature",id: oauth-feature-id, includeSections: true)
2. Identify components:
   - Database: User OAuth tokens table
   - Backend: OAuth providers integration
   - Backend: Token management endpoints
   - Frontend: OAuth login buttons
   - Testing: Integration test suite
3. query_templates(targetEntityType: "TASK")
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
2. Discover: query_templates(targetEntityType: "FEATURE")
3. Execute: manage_container(operation="create", containerType="feature", containers=[{...}], templateIds=[...])
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

## Autonomous vs Guided Pattern Application

### When to Apply Patterns Automatically

**Apply patterns automatically when**:
- Clear intent recognized from user request
- Sufficient context available to act
- Standard pattern applies without ambiguity
- Low risk of misunderstanding

**Example**:
```
User: "Create a feature for file upload with drag and drop"
AI: Automatically applies feature creation pattern
```

### When to Ask for Clarification

**Ask clarifying questions when**:
- Intent is ambiguous (bug fix vs enhancement)
- Multiple valid patterns could apply
- Requirements are underspecified
- Scope is unclear

**Example**:
```
User: "Fix the authentication system"
AI: "This sounds like either:
     1. A bug fix (if auth is broken - create task with Bug Investigation template)
     2. An enhancement task (if improving auth)
     Which applies?"
```

### Hybrid Approach
1. Apply patterns automatically for clear, standard cases
2. Ask clarifying questions when intent is ambiguous
3. Offer alternatives when multiple patterns could apply

## Best Practices

1. **Always start with query_container(operation="overview")**
   - Understand current project state
   - Check for existing related work
   - Avoid duplicate creation

2. **Template discovery is mandatory**
   - Never assume templates
   - Always use query_templates
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
   - Use query_container(operation="get", containerType="task") to verify before completing
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
     * Adds the workflow integration resource explaining template-driven workflows.
     */
    private fun addWorkflowIntegrationResource(server: Server) {
        server.addResource(
            uri = "task-orchestrator://guidelines/workflow-integration",
            name = "Workflow Integration Guide",
            description = "Explains how to use Task Orchestrator tools with templates for effective workflow management",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://guidelines/workflow-integration",
                        mimeType = "text/markdown",
                        text = """
# Workflow Integration Guide

## Guidance Architecture

Task Orchestrator provides guidance through multiple channels:

### MCP Resources (Guidelines)
**Purpose**: Discoverable reference documentation for AI agents

- **What**: Reference docs via `task-orchestrator://guidelines/*` URIs
- **Available Resources**:
  1. `usage-overview` - When to use Task Orchestrator vs other systems
  2. `template-strategy` - Template discovery and selection patterns
  3. `task-management` - Intent recognition and executable workflow patterns
  4. `workflow-integration` - This document

### Dynamic Templates (Database-Driven)
**Purpose**: Consistent documentation structure for entities

- **What**: Reusable section definitions stored in database
- **Access**: Discovered via `query_templates`, applied via `templateIds` during creation
- **Usage**: Provide standardized documentation structure

### Status Progression Tools
**Purpose**: Config-driven workflow advancement with validation

- **`get_next_status`**: Read-only recommendations based on workflow config and entity state
- **`request_transition`**: Preferred tool for status changes — uses named triggers (start, complete, cancel, block, hold) with prerequisite validation

## Core Workflow Pattern

```
1. query_container(operation="overview") - Understand current state
2. query_templates(targetEntityType="TASK") - Discover templates
3. manage_container(operation="create", ..., containers=[{...}], templateIds=[...]) - Create with templates
4. get_next_status(containerId, containerType) - Check progression
5. request_transition(transitions=[{containerId, containerType, trigger: "start"}]) - Apply status change (preferred)
```

## Status Workflow Management

Status progression is config-driven via `.taskorchestrator/config.yaml`:
- **`get_next_status`**: Recommends next status based on tags and flow
- **`request_transition`**: Applies status transitions with trigger-based validation (preferred)
- Flows are determined by entity tags (e.g., `bug` → `bug_fix_flow`)
- Emergency transitions (blocked, cancelled) available from any state

## Update Efficiency

**NEVER fetch an entity just to update it. ALWAYS use partial updates — only send fields you're changing.**

All update tools support partial updates. Only `id` is required:
- `manage_container(operation="update")` - All other fields optional
- `manage_sections(operation="update")` - All other fields optional
- `manage_sections(operation="updateText")` - For content changes (send only text snippets)
- `manage_sections(operation="updateMetadata")` - For metadata only (excludes content)

**Example — changing status only:**
```json
{"id": "uuid", "status": "in-progress"}
```
Not: fetching the entity, modifying one field, and sending everything back (wastes 90%+ tokens).

## Verification Gates

Entities with `requiresVerification=true` cannot be completed until a Verification section passes all criteria.

**How It Works:**
1. Create a Verification section with JSON acceptance criteria: `[{"criteria": "description", "pass": false}, ...]`
2. As you verify each condition, update the criterion's `pass` to `true`
3. The server blocks `request_transition(transitions=[{..., trigger: "complete"}])` until ALL criteria pass

**When to Use:**
- **Use** for implementation tasks, bug fixes, features with formal requirements
- **Skip** for planning, documentation, research, or configuration tasks
- Templates with a Verification section auto-enable the gate via `apply_template`

**Gate Checks (5 sequential):**
1. Section titled "Verification" must exist
2. Content must not be blank
3. Content must be valid JSON array of criteria objects
4. At least one criterion must be defined
5. All criteria must have `pass: true`

If completion is blocked, the response includes which specific criteria failed.

## Best Practices

1. **Always discover templates** before creating entities
2. **Use `query_container(overview)`** for status checks (token-efficient)
3. **Check `get_next_status`** before changing statuses
4. **Apply meaningful tags** for flow determination
5. **Set appropriate complexity** (1-10) for estimation
6. **Use partial updates** — only send fields you're changing
                            """.trimIndent()
                        )
                    )
                )
        }
    }

    /**
     * Adds the setup instructions resource — **Layer 2** of the two-layer architecture.
     *
     * Registers `task-orchestrator://guidelines/setup-instructions` as an on-demand MCP
     * resource. Agents are directed here by Layer 1 (`server.instructions` in
     * [McpServer.configureServer]) when their project's CLAUDE.md is missing the
     * `<!-- mcp-task-orchestrator-setup: vN -->` marker or has an older version.
     *
     * The resource contains:
     * - Installation steps (create project, copy block, verify marker)
     * - Complete CLAUDE.md template with `{PROJECT_NAME}` / `{PROJECT_UUID}` placeholders
     * - 6 workflow rules, tag-driven status flow tables, completion cleanup warning
     * - Dependency batch creation guidance, session start pattern, deep-reference links
     *
     * The template embeds [SETUP_INSTRUCTIONS_VERSION] so the installed block can be
     * version-checked by Layer 1 in future sessions.
     *
     * @see SETUP_INSTRUCTIONS_VERSION for version lifecycle documentation
     * @see McpServer.configureServer where Layer 1 references this resource URI
     */
    private fun addSetupInstructionsResource(server: Server) {
        server.addResource(
            uri = "task-orchestrator://guidelines/setup-instructions",
            name = "Setup Instructions — CLAUDE.md Template",
            description = "Complete CLAUDE.md instruction block template for configuring any AI agent to use MCP Task Orchestrator effectively. Read this resource when the server.instructions marker is missing from your project configuration.",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://guidelines/setup-instructions",
                        mimeType = "text/markdown",
                        text = """
# MCP Task Orchestrator — Setup Instructions

## What This Is

This resource contains a ready-to-use instruction block for your project's CLAUDE.md (or equivalent agent instructions file). Adding this block teaches your AI agent the essential workflow rules for MCP Task Orchestrator.

## How to Install

1. **Create a project** (if you haven't already):
   ```
   manage_container(operation="create", containerType="project", containers=[{name: "Your Project Name", summary: "Description"}])
   ```
   Note the returned project UUID.

2. **Copy the block below** into your CLAUDE.md, replacing `{PROJECT_NAME}` with your project name and `{PROJECT_UUID}` with the UUID from step 1.

3. **Verify** by checking that the marker comment `<!-- mcp-task-orchestrator-setup: $SETUP_INSTRUCTIONS_VERSION -->` is present at the top of the block.

---

## CLAUDE.md Block (copy below this line)

```markdown
<!-- mcp-task-orchestrator-setup: $SETUP_INSTRUCTIONS_VERSION -->
## MCP Task Orchestrator — Project: {PROJECT_NAME} (`{PROJECT_UUID}`)

All features and tasks belong to this project. Always pass `projectId` when creating features or standalone tasks so they remain queryable.

### Workflow Rules

1. **Status changes** — Use `request_transition(transitions=[{containerId, containerType, trigger: "start"|"complete"|"cancel"|"block"|"hold"}])`. Always wrap in a `transitions` array, even for single transitions. The `setStatus` operation was removed in v2 — use `request_transition` exclusively for all status changes.

2. **Template discovery** — Before creating any task or feature, run `query_templates(operation="list", targetEntityType="TASK"|"FEATURE", isEnabled=true)` and include `templateIds` in the create call.

3. **Post-transition handling** — After every `request_transition`, check the response for:
   - `cascadeEvents` — parent entities that should advance (act on them)
   - `unblockedTasks` — downstream tasks now available to start
   - Flow context (`activeFlow`, `flowSequence`, `flowPosition`)

4. **Token-efficient queries** — Default to `query_container(operation="overview")` for status checks and dashboards. Use `get` only when you need section content. Use `export` for full markdown snapshots before completion or archival.

5. **Work selection** — Use `get_next_task(projectId="{PROJECT_UUID}")` for dependency-aware, priority-sorted recommendations instead of manual searching. Use `get_blocked_tasks` to identify bottlenecks.

6. **Completion requirements** — Tasks: summary populated, dependencies resolved, required sections filled. Features: all child tasks in terminal status (completed or cancelled). Projects: all features completed.

### Status Flows (Tag-Driven)

Tags applied at creation time select which status flow an entity follows:

| Entity | Tags | Flow |
|--------|------|------|
| Task | _(default)_ | backlog → pending → in-progress → testing → completed |
| Task | `bug`, `bugfix`, `fix` | pending → in-progress → testing → completed |
| Task | `documentation`, `docs` | pending → in-progress → in-review → completed |
| Task | `hotfix`, `emergency` | in-progress → testing → completed |
| Feature | _(default)_ | draft → planning → in-development → testing → validating → completed |
| Feature | `prototype`, `poc`, `spike` | draft → in-development → completed |
| Feature | `experiment`, `research` | draft → in-development → archived |

### Completion Cleanup

When a feature reaches terminal status (completed/archived), its child tasks are **automatically deleted** — including their sections and dependencies. Tasks tagged `bug`, `bugfix`, `fix`, `hotfix`, or `critical` are retained. Use `query_container(operation="export")` on a feature BEFORE completing it to preserve a full markdown snapshot of all task content.

### Dependency Batch Creation

Use `manage_dependencies` with a `dependencies` array or pattern shortcuts (`linear`, `fan-out`, `fan-in`) for creating multiple dependencies at once. Avoid creating them one at a time.

### Session Start

Begin each session by checking project state:
```
query_container(operation="overview", containerType="project", id="{PROJECT_UUID}")
```

### MCP Resources (Deep Reference)

For detailed guidance beyond these rules, read these MCP resources:
- `task-orchestrator://guidelines/usage-overview` — decision framework for when to use Task Orchestrator
- `task-orchestrator://guidelines/template-strategy` — template discovery patterns and selection trees
- `task-orchestrator://guidelines/task-management` — intent recognition and 6 executable workflow patterns
- `task-orchestrator://guidelines/workflow-integration` — status flows, verification gates, update efficiency
- `task-orchestrator://docs/tools/{tool-name}` — per-tool documentation (13 tools)
```

---

## Version History

- **$SETUP_INSTRUCTIONS_VERSION**: Initial release — 6 workflow rules, tag-driven status flows, completion cleanup, dependency patterns, session start, resource links
                        """.trimIndent()
                    )
                )
            )
        }
    }
}
