package io.github.jpicklyk.mcptask.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.Role
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject

/**
 * User-invokable MCP prompts for common Task Orchestrator workflows.
 *
 * These prompts provide users with specific workflow templates and guidance
 * for common task orchestration scenarios, following proper MCP prompt patterns.
 */
object WorkflowPromptsGuidance {

    /**
     * Configures workflow prompts for the MCP server.
     *
     * v2.0 Architecture: Workflows now delegate to Skills for detailed guidance (Claude Code only).
     * For non-Claude Code users, direct tool calls are recommended.
     */
    fun configureWorkflowPrompts(server: Server) {
        addInitializeTaskOrchestratorPrompt(server)
        addProjectSetupPrompt(server)
        addUpdateProjectConfigPrompt(server)
        addCoordinateFeatureDevelopmentPrompt(server)
    }

    /**
     * Adds an AI-agnostic initialization prompt that guides AI agents through setting up
     * Task Orchestrator guidelines in their own memory systems.
     */
    private fun addInitializeTaskOrchestratorPrompt(server: Server) {
        server.addPrompt(
            name = "initialize_task_orchestrator",
            description = "Guide AI agents through self-initialization with Task Orchestrator guidelines in their own memory systems"
        ) { _ ->
            val workflowContent = try {
                this::class.java.classLoader
                    .getResourceAsStream("workflows/initialize-task-orchestrator.md")
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: "# Task Orchestrator Initialization Workflow\n\nWorkflow file not found. Please ensure initialize-task-orchestrator.md exists in src/main/resources/workflows/"
            } catch (e: Exception) {
                "# Task Orchestrator Initialization Workflow\n\nError loading workflow: ${e.message}"
            }

            GetPromptResult(
                description = "AI-agnostic initialization workflow for setting up Task Orchestrator usage guidelines",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(text = workflowContent)
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }

    /**
     * Adds a prompt for setting up Task Orchestrator with existing or new projects.
     */
    private fun addProjectSetupPrompt(server: Server) {
        server.addPrompt(
            name = "project_setup_workflow",
            description = "AI-agnostic guide for setting up Task Orchestrator with existing or new projects"
        ) { _ ->
            val workflowContent = try {
                this::class.java.classLoader
                    .getResourceAsStream("workflows/project-setup-workflow.md")
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: "# Project Setup Workflow\n\nWorkflow file not found. Please ensure project-setup-workflow.md exists in src/main/resources/workflows/"
            } catch (e: Exception) {
                "# Project Setup Workflow\n\nError loading workflow: ${e.message}"
            }

            GetPromptResult(
                description = "Universal workflow for Task Orchestrator project setup across any AI agent",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(text = workflowContent)
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }

    /**
     * Adds a prompt for upgrading Task Orchestrator configuration files to latest versions.
     */
    private fun addUpdateProjectConfigPrompt(server: Server) {
        server.addPrompt(
            name = "update_project_config",
            description = "Upgrade Task Orchestrator configuration files (.taskorchestrator/) to latest versions with backup and customization preservation"
        ) { _ ->
            val workflowContent = try {
                this::class.java.classLoader
                    .getResourceAsStream("workflows/update-project-config.md")
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: "# Update Project Configuration Workflow\n\nWorkflow file not found. Please ensure update-project-config.md exists in src/main/resources/workflows/"
            } catch (e: Exception) {
                "# Update Project Configuration Workflow\n\nError loading workflow: ${e.message}"
            }

            GetPromptResult(
                description = "Guided workflow for upgrading configuration files to latest versions",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(text = workflowContent)
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }

    /**
     * Adds a prompt for coordinating feature development through four phases.
     */
    private fun addCoordinateFeatureDevelopmentPrompt(server: Server) {
        server.addPrompt(
            name = "coordinate_feature_development",
            description = "Coordinate feature development through four phases (Feature Creation, Task Breakdown, Task Execution, Feature Completion) using Skills for detailed guidance"
        ) { _ ->
            val workflowContent = try {
                this::class.java.classLoader
                    .getResourceAsStream("workflows/coordinate-feature-development.md")
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: "# Coordinate Feature Development\n\nWorkflow file not found. Please ensure coordinate-feature-development.md exists in src/main/resources/workflows/"
            } catch (e: Exception) {
                "# Coordinate Feature Development\n\nError loading workflow: ${e.message}"
            }

            GetPromptResult(
                description = "Lightweight phase coordinator for feature development - Skills provide detailed templates, examples, and error handling",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(text = workflowContent)
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }
}
