package io.github.jpicklyk.mcptask.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.io.File

/**
 * MCP Resources for detailed tool documentation (v2.0).
 *
 * This provides on-demand documentation for Task Orchestrator consolidated tools with comprehensive
 * examples, use cases, and integration patterns. AI can fetch these resources when needed
 * without loading all documentation upfront, saving context tokens.
 *
 * Documentation is loaded from markdown files in docs/tools/ directory.
 *
 * Resource URL Pattern: task-orchestrator://docs/tools/{tool-name}
 */
object ToolDocumentationResources {

    /**
     * Configures all v2.0 consolidated tool documentation resources with the MCP server.
     * Each tool gets a dedicated resource with detailed examples and patterns.
     */
    fun configure(server: Server) {
        // v2.0 Consolidated Container Tools
        addQueryContainerDocumentation(server)
        addManageContainerDocumentation(server)

        // v2.0 Consolidated Section Tools
        addQuerySectionsDocumentation(server)
        addManageSectionsDocumentation(server)

        // v2.0 Consolidated Template Tools
        addQueryTemplatesDocumentation(server)
        addManageTemplateDocumentation(server)
        addApplyTemplateDocumentation(server)  // Unchanged in v2.0

        // v2.0 Consolidated Dependency Tools
        addQueryDependenciesDocumentation(server)
        addManageDependencyDocumentation(server)

        // Workflow Optimization (2 tools)
        addGetNextTaskDocumentation(server)
        addGetBlockedTasksDocumentation(server)

        // Status Progression (2 tools)
        addGetNextStatusDocumentation(server)
        addRequestTransitionDocumentation(server)
    }

    /**
     * Loads markdown documentation from file system.
     * Returns the file content or an error message if the file cannot be read.
     */
    private fun loadDocumentation(fileName: String): String {
        return try {
            val file = File("docs/tools/$fileName")
            if (file.exists()) {
                file.readText()
            } else {
                "# Documentation Not Found\n\nThe documentation file `$fileName` could not be found at path: ${file.absolutePath}\n\n" +
                        "Note: v2.0 documentation for consolidated tools is in progress. Please refer to the migration guide: docs/migration/v2.0-migration-guide.md"
            }
        } catch (e: Exception) {
            "# Error Loading Documentation\n\nFailed to load documentation file `$fileName`: ${e.message}"
        }
    }

    // ========== v2.0 Container Tools ==========

    private fun addQueryContainerDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/query-container",
            name = "query_container Tool Documentation",
            description = "Detailed documentation for query_container - unified read operations for projects, features, and tasks",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/query-container",
                        mimeType = "text/markdown",
                        text = loadDocumentation("query-container.md")
                    )
                )
            )
        }
    }

    private fun addManageContainerDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/manage-container",
            name = "manage_container Tool Documentation",
            description = "Detailed documentation for manage_container - unified write operations for projects, features, and tasks",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/manage-container",
                        mimeType = "text/markdown",
                        text = loadDocumentation("manage-container.md")
                    )
                )
            )
        }
    }

    // ========== v2.0 Section Tools ==========

    private fun addQuerySectionsDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/query-sections",
            name = "query_sections Tool Documentation",
            description = "Detailed documentation for query_sections - unified read operations for sections with filtering",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/query-sections",
                        mimeType = "text/markdown",
                        text = loadDocumentation("query-sections.md")
                    )
                )
            )
        }
    }

    private fun addManageSectionsDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/manage-sections",
            name = "manage_sections Tool Documentation",
            description = "Detailed documentation for manage_sections - unified write operations for sections",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/manage-sections",
                        mimeType = "text/markdown",
                        text = loadDocumentation("manage-sections.md")
                    )
                )
            )
        }
    }

    // ========== v2.0 Template Tools ==========

    private fun addQueryTemplatesDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/query-templates",
            name = "query_templates Tool Documentation",
            description = "Detailed documentation for query_templates - unified read operations for templates",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/query-templates",
                        mimeType = "text/markdown",
                        text = loadDocumentation("query-templates.md")
                    )
                )
            )
        }
    }

    private fun addManageTemplateDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/manage-template",
            name = "manage_template Tool Documentation",
            description = "Detailed documentation for manage_template - unified write operations for templates",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/manage-template",
                        mimeType = "text/markdown",
                        text = loadDocumentation("manage-template.md")
                    )
                )
            )
        }
    }

    private fun addApplyTemplateDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/apply-template",
            name = "apply_template Tool Documentation",
            description = "Detailed documentation for apply_template - apply templates to tasks and features",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/apply-template",
                        mimeType = "text/markdown",
                        text = loadDocumentation("apply-template.md")
                    )
                )
            )
        }
    }

    // ========== v2.0 Dependency Tools ==========

    private fun addQueryDependenciesDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/query-dependencies",
            name = "query_dependencies Tool Documentation",
            description = "Detailed documentation for query_dependencies - unified read operations for task dependencies",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/query-dependencies",
                        mimeType = "text/markdown",
                        text = loadDocumentation("query-dependencies.md")
                    )
                )
            )
        }
    }

    private fun addManageDependencyDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/manage-dependency",
            name = "manage_dependency Tool Documentation",
            description = "Detailed documentation for manage_dependency - unified write operations for task dependencies",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/manage-dependency",
                        mimeType = "text/markdown",
                        text = loadDocumentation("manage-dependency.md")
                    )
                )
            )
        }
    }

    // ========== Workflow Optimization ==========

    private fun addGetNextTaskDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/get-next-task",
            name = "get_next_task Tool Documentation",
            description = "Detailed documentation for get_next_task - intelligent task recommendations",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/get-next-task",
                        mimeType = "text/markdown",
                        text = loadDocumentation("get-next-task.md")
                    )
                )
            )
        }
    }

    private fun addGetBlockedTasksDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/get-blocked-tasks",
            name = "get_blocked_tasks Tool Documentation",
            description = "Detailed documentation for get_blocked_tasks - find tasks blocked by dependencies",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/get-blocked-tasks",
                        mimeType = "text/markdown",
                        text = loadDocumentation("get-blocked-tasks.md")
                    )
                )
            )
        }
    }

    // ========== Status Progression ==========

    private fun addGetNextStatusDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/get-next-status",
            name = "get_next_status Tool Documentation",
            description = "Detailed documentation for get_next_status - read-only status progression recommendations with role annotations",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/get-next-status",
                        mimeType = "text/markdown",
                        text = loadDocumentation("get-next-status.md")
                    )
                )
            )
        }
    }

    private fun addRequestTransitionDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/request-transition",
            name = "request_transition Tool Documentation",
            description = "Detailed documentation for request_transition - trigger-based status transitions with validation",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/request-transition",
                        mimeType = "text/markdown",
                        text = loadDocumentation("request-transition.md")
                    )
                )
            )
        }
    }

}
