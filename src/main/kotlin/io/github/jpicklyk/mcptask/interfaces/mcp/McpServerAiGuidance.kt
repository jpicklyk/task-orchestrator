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
        
        // Add template management guidance
        TemplateMgtGuidance.configureTemplateManagementGuidance(this)
    }
    
    /**
     * Adds a user-invokable prompt for getting comprehensive server overview and capabilities.
     */
    private fun addServerOverviewPrompt(server: Server) {
        server.addPrompt(
            name = "task_orchestrator_overview",
            description = "Get comprehensive overview of Task Orchestrator capabilities and workflow guidance"
        ) { _ ->
            GetPromptResult(
                description = "Task Orchestrator comprehensive overview and workflow guidance",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # MCP Task Orchestrator - Complete Capability Overview
                            
                            Welcome to the MCP Task Orchestrator, a comprehensive project management and workflow automation system designed for AI-assisted development.
                            
                            ## Core Architecture
                            
                            The system uses a hierarchical organization model:
                            **Projects** → **Features** → **Tasks** → **Sections**
                            
                            - **Projects**: Top-level organizational containers for large initiatives
                            - **Features**: Mid-level groupings for related functionality
                            - **Tasks**: Primary work items with status, priority, and complexity tracking
                            - **Sections**: Detailed content blocks for documentation and requirements
                            
                            ## Essential Workflow Pattern
                            
                            1. **Start with Overview**: Always begin with `get_overview` to understand current state
                            2. **Template-Driven Creation**: Use `list_templates` + `create_task`/`create_feature` with templates
                            3. **Progressive Enhancement**: Add sections, update status, create dependencies as work progresses
                            4. **Efficient Operations**: Use bulk operations when possible (bulk_create_sections)
                            
                            ## Key Capabilities
                            
                            ### Template System
                            - **AI Workflow Instructions**: Git branching, PR workflows, implementation guidance
                            - **Documentation Properties**: Technical approach, requirements, context
                            - **Process & Quality**: Testing strategy, definition of done
                            
                            ### Search & Discovery
                            - Flexible filtering by status, priority, tags, features
                            - Text-based search across titles and summaries
                            - Hierarchical overview for project understanding
                            
                            ### Workflow Integration
                            - Status-driven lifecycle management (pending → in-progress → completed)
                            - Priority-based work planning (high/medium/low)
                            - Complexity tracking for estimation (1-10 scale)
                            - Tag-based categorization (task-type-feature, task-type-bug, etc.)
                            
                            ### Advanced Features
                            - Dependency tracking and relationship management
                            - Locking system for concurrent operation safety
                            - Context-efficient bulk operations
                            - Git workflow prompts with step-by-step guidance
                            
                            ## Best Practices
                            
                            ### For Task Management
                            - Use descriptive titles and comprehensive summaries
                            - Apply appropriate templates during creation
                            - Set realistic complexity ratings (helps with future estimation)
                            - Use consistent tagging conventions
                            
                            ### For Feature Organization
                            - Group related tasks under features for better organization
                            - Use feature-level templates for planning and requirements
                            - Track feature status independently from individual tasks
                            
                            ### For Efficient Operations
                            - Start work sessions with `get_overview`
                            - Use `search_tasks` for finding specific work
                            - Apply templates during creation rather than separately
                            - Use bulk operations for multiple sections
                            
                            ## Tool Categories Available
                            
                            - **Task Management**: create_task, update_task, get_task, delete_task, search_tasks
                            - **Feature Management**: create_feature, update_feature, get_feature, search_features
                            - **Project Management**: create_project, update_project, get_project, search_projects
                            - **Template Management**: list_templates, apply_template, create_template
                            - **Section Management**: add_section, bulk_create_sections, get_sections, update_section
                            - **Dependency Management**: create_dependency, get_task_dependencies, delete_dependency
                            - **Overview & Search**: get_overview (essential starting point)
                            
                            ## Getting Started Recommendations
                            
                            1. Run `get_overview` to see current project state
                            2. Run `list_templates` to understand available templates
                            3. Create your first task or feature with appropriate templates
                            4. Use `get_task` or `get_feature` with includeSections=true to see the structure
                            5. Begin your workflow with status updates and progressive enhancement
                            
                            The Task Orchestrator is designed to support AI-assisted development workflows with comprehensive tracking, documentation, and automation capabilities.
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }

}