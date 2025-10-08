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

### Feature Development Workflows
- **`create_feature_workflow`**: Create features with templates and tasks
- **`implement_feature_workflow`**: Smart implementation with git detection
- **`task_breakdown_workflow`**: Decompose complex work

### Issue Management Workflows
- **`bug_triage_workflow`**: Systematic bug investigation and resolution

### Project Organization Workflows
- **`project_setup_workflow`**: Initialize new projects with structure

### How Workflow Prompts Work
Workflow prompts are **natural language guidance** that AI agents can invoke and follow:
1. User or AI invokes prompt: `/task-orchestrator:create_feature_workflow`
2. AI receives step-by-step workflow instructions
3. AI executes workflow using MCP tools
4. User gets guided through process with best practices built-in

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
- Use `create_feature_workflow` or follow pattern directly
- Apply authentication and implementation templates

**User Says**: "Fix this small typo in the README"
**Agent Recognizes**:
- Simple one-off task (don't use Task Orchestrator)
- Direct file edit appropriate

**User Says**: "Investigate why the API is timing out"
**Agent Recognizes**:
- Bug investigation scenario
- Use `bug_triage_workflow` for systematic approach
- Create task with Bug Investigation template

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

## Getting Started

**For AI Agents**:
1. Read and internalize these guidelines into your memory system
2. Use `get_overview` to understand current project state
3. Check `list_templates` to see available documentation patterns
4. Follow workflow prompts for complex scenarios
5. Apply patterns naturally based on user requests

**For Users**:
1. Invoke workflow prompts for guided experiences: `/task-orchestrator:create_feature_workflow`
2. Trust AI agents to use Task Orchestrator when appropriate
3. Provide clear requirements and let workflows handle the details

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
}
