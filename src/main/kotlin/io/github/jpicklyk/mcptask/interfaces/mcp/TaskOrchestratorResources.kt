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
}
