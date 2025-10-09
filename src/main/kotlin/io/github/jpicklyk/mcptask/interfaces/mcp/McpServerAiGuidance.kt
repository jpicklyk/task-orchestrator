package io.github.jpicklyk.mcptask.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.Role
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject

/**
 * Extension functions to provide AI guidance for the MCP server.
 */
object McpServerAiGuidance {

    /**
     * Configures AI guidance for the MCP server.
     * 
     * Note: This previously contained instruction "prompts" that were actually documentation.
     * Those have been removed as they don't align with MCP prompt concepts.
     * MCP prompts should be user-invokable templates for specific interactions.
     * 
     * Usage guidance is now provided through:
     * - Enhanced tool descriptions and parameter documentation
     * - Server-level metadata and descriptions
     * - User-invokable prompts for specific workflows
     */
    fun Server.configureAiGuidance() {
        // Add server overview prompt for users
        addServerOverviewPrompt(this)

        // Configure MCP Resources for AI guidelines
        TaskOrchestratorResources.configure(this)

        // Template management guidance now integrated into tool descriptions

        // Add workflow prompts for common task orchestrator workflows
        WorkflowPromptsGuidance.configureWorkflowPrompts(this)
    }
    
    /**
     * Adds a user-invokable prompt for getting comprehensive server overview and capabilities.
     */
    private fun addServerOverviewPrompt(server: Server) {
        server.addPrompt(
            name = "getting_started",
            description = "Essential workflow patterns and getting started guide for Task Orchestrator"
        ) { _ ->
            GetPromptResult(
                description = "Essential workflow patterns for Task Orchestrator",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Task Orchestrator - Essential Workflow Patterns
                            
                            ## Core Organization
                            **Projects** → **Features** → **Tasks** → **Sections**
                            
                            ## Essential Workflow
                            
                            1. **Start with Overview**: `get_overview` to see current state
                            2. **Template-Driven Creation**: `list_templates` → `create_task`/`create_feature` with templateIds
                            3. **Status Management**: pending → in-progress → completed
                            4. **Progressive Enhancement**: Add sections, dependencies as needed
                            
                            ## Quick Start
                            
                            ```
                            # See current work
                            get_overview
                            
                            # Find templates
                            list_templates --targetEntityType TASK
                            
                            # Create with template
                            create_task --title "..." --summary "..." --templateIds ["uuid"]
                            
                            # Update status when starting work
                            update_task --id "uuid" --status "in_progress"
                            ```
                            
                            ## Key Patterns
                            
                            - **Always start sessions** with `get_overview`
                            - **Use templates** for consistent documentation
                            - **Tag consistently**: task-type-feature, task-type-bug, etc.
                            - **Set complexity** (1-10) for estimation
                            - **Use bulk operations** for multiple sections
                            - **Check template guidance** with `get_sections` before marking complete
                            
                            ## Template Categories
                            - **Workflow**: Git branching, PR workflows, implementation
                            - **Documentation**: Technical approach, requirements
                            - **Quality**: Testing strategy, definition of done
                            
                            ## Workflow Automation
                            
                            **Use workflow prompts** for automated guidance on complex scenarios:
                            - `create_feature_workflow` - Complete feature creation with templates and tasks
                            - `task_breakdown_workflow` - Break complex tasks into manageable pieces
                            - `bug_triage_workflow` - Systematic bug investigation and resolution
                            - `project_setup_workflow` - Initialize new projects with proper structure
                            - `implement_feature_workflow` - Smart implementation with git detection
                            
                            **Usage**: `task-orchestrator:workflow_name` then provide details or let AI guide you
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }

}