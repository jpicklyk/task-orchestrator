package io.github.jpicklyk.mcptask.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.io.File

/**
 * MCP Resources for detailed tool documentation.
 *
 * This provides on-demand documentation for Task Orchestrator tools with comprehensive
 * examples, use cases, and integration patterns. AI can fetch these resources when needed
 * without loading all documentation upfront, saving context tokens.
 *
 * Documentation is loaded from markdown files in docs/tools/ directory.
 *
 * Resource URL Pattern: task-orchestrator://docs/tools/{tool-name}
 */
object ToolDocumentationResources {

    /**
     * Configures all tool documentation resources with the MCP server.
     * Each tool gets a dedicated resource with detailed examples and patterns.
     */
    fun configure(server: Server) {
        // Task Management (10 tools)
        addCreateTaskDocumentation(server)
        addGetTaskDocumentation(server)
        addSearchTasksDocumentation(server)
        addUpdateTaskDocumentation(server)
        addDeleteTaskDocumentation(server)
        addBulkUpdateTasksDocumentation(server)
        addTaskToMarkdownDocumentation(server)
        addGetOverviewDocumentation(server)

        // Feature Management (7 tools)
        addCreateFeatureDocumentation(server)
        addUpdateFeatureDocumentation(server)
        addGetFeatureDocumentation(server)
        addDeleteFeatureDocumentation(server)
        addSearchFeaturesDocumentation(server)
        addFeatureToMarkdownDocumentation(server)
        addGetFeatureTasksDocumentation(server)

        // Project Management (6 tools)
        addCreateProjectDocumentation(server)
        addUpdateProjectDocumentation(server)
        addGetProjectDocumentation(server)
        addDeleteProjectDocumentation(server)
        addSearchProjectsDocumentation(server)
        addProjectToMarkdownDocumentation(server)

        // Section Management (10 tools)
        addAddSectionDocumentation(server)
        addGetSectionsDocumentation(server)
        addUpdateSectionDocumentation(server)
        addUpdateSectionTextDocumentation(server)
        addUpdateSectionMetadataDocumentation(server)
        addDeleteSectionDocumentation(server)
        addBulkCreateSectionsDocumentation(server)
        addBulkUpdateSectionsDocumentation(server)
        addBulkDeleteSectionsDocumentation(server)
        addReorderSectionsDocumentation(server)

        // Template Management (9 tools)
        addListTemplatesDocumentation(server)
        addCreateTemplateDocumentation(server)
        addGetTemplateDocumentation(server)
        addApplyTemplateDocumentation(server)
        addAddTemplateSectionDocumentation(server)
        addUpdateTemplateMetadataDocumentation(server)
        addDeleteTemplateDocumentation(server)
        addEnableTemplateDocumentation(server)
        addDisableTemplateDocumentation(server)

        // Dependency Management (3 tools)
        addCreateDependencyDocumentation(server)
        addGetTaskDependenciesDocumentation(server)
        addDeleteDependencyDocumentation(server)

        // Agent Orchestration (3 tools)
        addSetupClaudeAgentsDocumentation(server)
        addGetAgentDefinitionDocumentation(server)
        addRecommendAgentDocumentation(server)

        // Tag Management (3 tools)
        addListTagsDocumentation(server)
        addGetTagUsageDocumentation(server)
        addRenameTagDocumentation(server)

        // Workflow Optimization (3 tools)
        addSetStatusDocumentation(server)
        addGetBlockedTasksDocumentation(server)
        addGetNextTaskDocumentation(server)
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
                "# Documentation Not Found\n\nThe documentation file `$fileName` could not be found at path: ${file.absolutePath}"
            }
        } catch (e: Exception) {
            "# Error Loading Documentation\n\nFailed to load documentation file `$fileName`: ${e.message}"
        }
    }

    /**
     * Documentation for create_task tool with comprehensive examples.
     */
    private fun addCreateTaskDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/create-task",
            name = "create_task Tool Documentation",
            description = "Detailed documentation for creating tasks with templates, examples, and patterns",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/create-task",
                        mimeType = "text/markdown",
                        text = loadDocumentation("create-task.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for get_task tool with comprehensive examples.
     */
    private fun addGetTaskDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/get-task",
            name = "get_task Tool Documentation",
            description = "Detailed documentation for retrieving tasks with sections, dependencies, and related entities",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/get-task",
                        mimeType = "text/markdown",
                        text = loadDocumentation("get-task.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for search_tasks tool with comprehensive examples.
     */
    private fun addSearchTasksDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/search-tasks",
            name = "search_tasks Tool Documentation",
            description = "Detailed documentation for searching and filtering tasks with advanced query patterns",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/search-tasks",
                        mimeType = "text/markdown",
                        text = loadDocumentation("search-tasks.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for list_templates tool.
     */
    private fun addListTemplatesDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/list-templates",
            name = "list_templates Tool Documentation",
            description = "Detailed documentation for discovering and selecting templates",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/list-templates",
                        mimeType = "text/markdown",
                        text = loadDocumentation("list-templates.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for add_section tool.
     */
    private fun addAddSectionDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/add-section",
            name = "add_section Tool Documentation",
            description = "Detailed documentation for adding structured content sections to tasks and features",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/add-section",
                        mimeType = "text/markdown",
                        text = loadDocumentation("add-section.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for create_dependency tool.
     */
    private fun addCreateDependencyDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/create-dependency",
            name = "create_dependency Tool Documentation",
            description = "Detailed documentation for creating task dependencies and managing work ordering",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/create-dependency",
                        mimeType = "text/markdown",
                        text = loadDocumentation("create-dependency.md")
                    )
                )
            )
        }
    }

    // ========== Task Management ==========

    /**
     * Documentation for update_task tool.
     */
    private fun addUpdateTaskDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/update-task",
            name = "update_task Tool Documentation",
            description = "Detailed documentation for updating task properties and metadata",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/update-task",
                        mimeType = "text/markdown",
                        text = loadDocumentation("update-task.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for delete_task tool.
     */
    private fun addDeleteTaskDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/delete-task",
            name = "delete_task Tool Documentation",
            description = "Detailed documentation for deleting tasks and handling dependencies",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/delete-task",
                        mimeType = "text/markdown",
                        text = loadDocumentation("delete-task.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for bulk_update_tasks tool.
     */
    private fun addBulkUpdateTasksDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/bulk-update-tasks",
            name = "bulk_update_tasks Tool Documentation",
            description = "Detailed documentation for updating multiple tasks efficiently in a single operation",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/bulk-update-tasks",
                        mimeType = "text/markdown",
                        text = loadDocumentation("bulk-update-tasks.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for task_to_markdown tool.
     */
    private fun addTaskToMarkdownDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/task-to-markdown",
            name = "task_to_markdown Tool Documentation",
            description = "Detailed documentation for exporting tasks to markdown format with sections",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/task-to-markdown",
                        mimeType = "text/markdown",
                        text = loadDocumentation("task-to-markdown.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for get_overview tool.
     */
    private fun addGetOverviewDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/get-overview",
            name = "get_overview Tool Documentation",
            description = "Detailed documentation for getting system overview with projects, features, and tasks",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/get-overview",
                        mimeType = "text/markdown",
                        text = loadDocumentation("get-overview.md")
                    )
                )
            )
        }
    }

    // ========== Feature Management ==========

    /**
     * Documentation for create_feature tool.
     */
    private fun addCreateFeatureDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/create-feature",
            name = "create_feature Tool Documentation",
            description = "Detailed documentation for creating features with templates and project assignment",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/create-feature",
                        mimeType = "text/markdown",
                        text = loadDocumentation("create-feature.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for update_feature tool.
     */
    private fun addUpdateFeatureDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/update-feature",
            name = "update_feature Tool Documentation",
            description = "Detailed documentation for updating feature properties and metadata",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/update-feature",
                        mimeType = "text/markdown",
                        text = loadDocumentation("update-feature.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for get_feature tool.
     */
    private fun addGetFeatureDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/get-feature",
            name = "get_feature Tool Documentation",
            description = "Detailed documentation for retrieving features with sections, tasks, and projects",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/get-feature",
                        mimeType = "text/markdown",
                        text = loadDocumentation("get-feature.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for delete_feature tool.
     */
    private fun addDeleteFeatureDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/delete-feature",
            name = "delete_feature Tool Documentation",
            description = "Detailed documentation for deleting features and handling associated tasks",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/delete-feature",
                        mimeType = "text/markdown",
                        text = loadDocumentation("delete-feature.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for search_features tool.
     */
    private fun addSearchFeaturesDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/search-features",
            name = "search_features Tool Documentation",
            description = "Detailed documentation for searching and filtering features with advanced queries",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/search-features",
                        mimeType = "text/markdown",
                        text = loadDocumentation("search-features.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for feature_to_markdown tool.
     */
    private fun addFeatureToMarkdownDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/feature-to-markdown",
            name = "feature_to_markdown Tool Documentation",
            description = "Detailed documentation for exporting features to markdown format with tasks",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/feature-to-markdown",
                        mimeType = "text/markdown",
                        text = loadDocumentation("feature-to-markdown.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for get_feature_tasks tool.
     */
    private fun addGetFeatureTasksDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/get-feature-tasks",
            name = "get_feature_tasks Tool Documentation",
            description = "Detailed documentation for retrieving all tasks belonging to a specific feature",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/get-feature-tasks",
                        mimeType = "text/markdown",
                        text = loadDocumentation("get-feature-tasks.md")
                    )
                )
            )
        }
    }

    // ========== Project Management ==========

    /**
     * Documentation for create_project tool.
     */
    private fun addCreateProjectDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/create-project",
            name = "create_project Tool Documentation",
            description = "Detailed documentation for creating projects with templates and organization",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/create-project",
                        mimeType = "text/markdown",
                        text = loadDocumentation("create-project.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for update_project tool.
     */
    private fun addUpdateProjectDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/update-project",
            name = "update_project Tool Documentation",
            description = "Detailed documentation for updating project properties and metadata",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/update-project",
                        mimeType = "text/markdown",
                        text = loadDocumentation("update-project.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for get_project tool.
     */
    private fun addGetProjectDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/get-project",
            name = "get_project Tool Documentation",
            description = "Detailed documentation for retrieving projects with features, tasks, and sections",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/get-project",
                        mimeType = "text/markdown",
                        text = loadDocumentation("get-project.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for delete_project tool.
     */
    private fun addDeleteProjectDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/delete-project",
            name = "delete_project Tool Documentation",
            description = "Detailed documentation for deleting projects and handling cascading effects",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/delete-project",
                        mimeType = "text/markdown",
                        text = loadDocumentation("delete-project.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for search_projects tool.
     */
    private fun addSearchProjectsDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/search-projects",
            name = "search_projects Tool Documentation",
            description = "Detailed documentation for searching and filtering projects with queries",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/search-projects",
                        mimeType = "text/markdown",
                        text = loadDocumentation("search-projects.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for project_to_markdown tool.
     */
    private fun addProjectToMarkdownDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/project-to-markdown",
            name = "project_to_markdown Tool Documentation",
            description = "Detailed documentation for exporting projects to markdown with features and tasks",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/project-to-markdown",
                        mimeType = "text/markdown",
                        text = loadDocumentation("project-to-markdown.md")
                    )
                )
            )
        }
    }

    // ========== Section Management ==========

    /**
     * Documentation for get_sections tool.
     */
    private fun addGetSectionsDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/get-sections",
            name = "get_sections Tool Documentation",
            description = "Detailed documentation for retrieving sections with content filtering options",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/get-sections",
                        mimeType = "text/markdown",
                        text = loadDocumentation("get-sections.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for update_section tool.
     */
    private fun addUpdateSectionDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/update-section",
            name = "update_section Tool Documentation",
            description = "Detailed documentation for updating complete section content and metadata",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/update-section",
                        mimeType = "text/markdown",
                        text = loadDocumentation("update-section.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for update_section_text tool.
     */
    private fun addUpdateSectionTextDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/update-section-text",
            name = "update_section_text Tool Documentation",
            description = "Detailed documentation for efficient partial text updates within sections",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/update-section-text",
                        mimeType = "text/markdown",
                        text = loadDocumentation("update-section-text.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for update_section_metadata tool.
     */
    private fun addUpdateSectionMetadataDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/update-section-metadata",
            name = "update_section_metadata Tool Documentation",
            description = "Detailed documentation for updating section title, format, ordinal, and tags",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/update-section-metadata",
                        mimeType = "text/markdown",
                        text = loadDocumentation("update-section-metadata.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for delete_section tool.
     */
    private fun addDeleteSectionDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/delete-section",
            name = "delete_section Tool Documentation",
            description = "Detailed documentation for deleting sections from tasks and features",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/delete-section",
                        mimeType = "text/markdown",
                        text = loadDocumentation("delete-section.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for bulk_create_sections tool.
     */
    private fun addBulkCreateSectionsDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/bulk-create-sections",
            name = "bulk_create_sections Tool Documentation",
            description = "Detailed documentation for efficiently creating multiple sections in one operation",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/bulk-create-sections",
                        mimeType = "text/markdown",
                        text = loadDocumentation("bulk-create-sections.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for bulk_update_sections tool.
     */
    private fun addBulkUpdateSectionsDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/bulk-update-sections",
            name = "bulk_update_sections Tool Documentation",
            description = "Detailed documentation for efficiently updating multiple sections in one operation",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/bulk-update-sections",
                        mimeType = "text/markdown",
                        text = loadDocumentation("bulk-update-sections.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for bulk_delete_sections tool.
     */
    private fun addBulkDeleteSectionsDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/bulk-delete-sections",
            name = "bulk_delete_sections Tool Documentation",
            description = "Detailed documentation for efficiently deleting multiple sections in one operation",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/bulk-delete-sections",
                        mimeType = "text/markdown",
                        text = loadDocumentation("bulk-delete-sections.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for reorder_sections tool.
     */
    private fun addReorderSectionsDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/reorder-sections",
            name = "reorder_sections Tool Documentation",
            description = "Detailed documentation for changing section order within tasks and features",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/reorder-sections",
                        mimeType = "text/markdown",
                        text = loadDocumentation("reorder-sections.md")
                    )
                )
            )
        }
    }

    // ========== Template Management ==========

    /**
     * Documentation for create_template tool.
     */
    private fun addCreateTemplateDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/create-template",
            name = "create_template Tool Documentation",
            description = "Detailed documentation for creating custom templates with sections",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/create-template",
                        mimeType = "text/markdown",
                        text = loadDocumentation("create-template.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for get_template tool.
     */
    private fun addGetTemplateDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/get-template",
            name = "get_template Tool Documentation",
            description = "Detailed documentation for retrieving templates with sections and metadata",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/get-template",
                        mimeType = "text/markdown",
                        text = loadDocumentation("get-template.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for apply_template tool.
     */
    private fun addApplyTemplateDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/apply-template",
            name = "apply_template Tool Documentation",
            description = "Detailed documentation for applying templates to existing tasks and features",
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

    /**
     * Documentation for add_template_section tool.
     */
    private fun addAddTemplateSectionDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/add-template-section",
            name = "add_template_section Tool Documentation",
            description = "Detailed documentation for adding sections to custom templates",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/add-template-section",
                        mimeType = "text/markdown",
                        text = loadDocumentation("add-template-section.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for update_template_metadata tool.
     */
    private fun addUpdateTemplateMetadataDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/update-template-metadata",
            name = "update_template_metadata Tool Documentation",
            description = "Detailed documentation for updating template name, description, and settings",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/update-template-metadata",
                        mimeType = "text/markdown",
                        text = loadDocumentation("update-template-metadata.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for delete_template tool.
     */
    private fun addDeleteTemplateDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/delete-template",
            name = "delete_template Tool Documentation",
            description = "Detailed documentation for deleting custom templates from the system",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/delete-template",
                        mimeType = "text/markdown",
                        text = loadDocumentation("delete-template.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for enable_template tool.
     */
    private fun addEnableTemplateDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/enable-template",
            name = "enable_template Tool Documentation",
            description = "Detailed documentation for enabling templates for use in the system",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/enable-template",
                        mimeType = "text/markdown",
                        text = loadDocumentation("enable-template.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for disable_template tool.
     */
    private fun addDisableTemplateDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/disable-template",
            name = "disable_template Tool Documentation",
            description = "Detailed documentation for disabling templates to hide them from lists",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/disable-template",
                        mimeType = "text/markdown",
                        text = loadDocumentation("disable-template.md")
                    )
                )
            )
        }
    }

    // ========== Dependency Management ==========

    /**
     * Documentation for get_task_dependencies tool.
     */
    private fun addGetTaskDependenciesDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/get-task-dependencies",
            name = "get_task_dependencies Tool Documentation",
            description = "Detailed documentation for retrieving task dependencies and blocking relationships",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/get-task-dependencies",
                        mimeType = "text/markdown",
                        text = loadDocumentation("get-task-dependencies.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for delete_dependency tool.
     */
    private fun addDeleteDependencyDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/delete-dependency",
            name = "delete_dependency Tool Documentation",
            description = "Detailed documentation for removing task dependencies",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/delete-dependency",
                        mimeType = "text/markdown",
                        text = loadDocumentation("delete-dependency.md")
                    )
                )
            )
        }
    }

    // ========== Agent Orchestration ==========

    /**
     * Documentation for setup_claude_agents tool.
     */
    private fun addSetupClaudeAgentsDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/setup-claude-agents",
            name = "setup_claude_agents Tool Documentation",
            description = "Detailed documentation for setting up Claude Code agent orchestration system",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/setup-claude-agents",
                        mimeType = "text/markdown",
                        text = loadDocumentation("setup-claude-agents.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for get_agent_definition tool.
     */
    private fun addGetAgentDefinitionDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/get-agent-definition",
            name = "get_agent_definition Tool Documentation",
            description = "Detailed documentation for retrieving agent definition markdown content",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/get-agent-definition",
                        mimeType = "text/markdown",
                        text = loadDocumentation("get-agent-definition.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for recommend_agent tool.
     */
    private fun addRecommendAgentDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/recommend-agent",
            name = "recommend_agent Tool Documentation",
            description = "Detailed documentation for getting agent routing recommendations for tasks",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/recommend-agent",
                        mimeType = "text/markdown",
                        text = loadDocumentation("recommend-agent.md")
                    )
                )
            )
        }
    }

    // ========== Tag Management ==========

    /**
     * Documentation for list_tags tool.
     */
    private fun addListTagsDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/list-tags",
            name = "list_tags Tool Documentation",
            description = "Detailed documentation for listing all tags used across the system",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/list-tags",
                        mimeType = "text/markdown",
                        text = loadDocumentation("list-tags.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for get_tag_usage tool.
     */
    private fun addGetTagUsageDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/get-tag-usage",
            name = "get_tag_usage Tool Documentation",
            description = "Detailed documentation for analyzing tag usage statistics across entities",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/get-tag-usage",
                        mimeType = "text/markdown",
                        text = loadDocumentation("get-tag-usage.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for rename_tag tool.
     */
    private fun addRenameTagDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/rename-tag",
            name = "rename_tag Tool Documentation",
            description = "Detailed documentation for renaming tags across all entities in the system",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/rename-tag",
                        mimeType = "text/markdown",
                        text = loadDocumentation("rename-tag.md")
                    )
                )
            )
        }
    }

    // ========== Workflow Optimization ==========

    /**
     * Documentation for set_status tool.
     */
    private fun addSetStatusDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/set-status",
            name = "set_status Tool Documentation",
            description = "Detailed documentation for unified status updates across tasks, features, and projects",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://docs/tools/set-status",
                        mimeType = "text/markdown",
                        text = loadDocumentation("set-status.md")
                    )
                )
            )
        }
    }

    /**
     * Documentation for get_blocked_tasks tool.
     */
    private fun addGetBlockedTasksDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/get-blocked-tasks",
            name = "get_blocked_tasks Tool Documentation",
            description = "Detailed documentation for finding tasks blocked by incomplete dependencies",
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

    /**
     * Documentation for get_next_task tool.
     */
    private fun addGetNextTaskDocumentation(server: Server) {
        server.addResource(
            uri = "task-orchestrator://docs/tools/get-next-task",
            name = "get_next_task Tool Documentation",
            description = "Detailed documentation for intelligently finding the next task to work on",
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
}
