package io.github.jpicklyk.mcptask.application.tools.agent

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.infrastructure.filesystem.TaskOrchestratorConfigManager
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*

/**
 * MCP tool for initializing core Task Orchestrator project configuration.
 *
 * Creates .taskorchestrator/ directory structure including:
 * - config.yaml - Core orchestrator configuration (status progression, validation)
 * - status-workflow-config.yaml - Workflow definitions and event handlers
 * - agent-mapping.yaml - Agent routing configuration (used if Claude Code integration enabled)
 *
 * This tool sets up core orchestrator functionality and is AI-agnostic.
 * For Claude Code integration, manually configure .claude/skills and .claude/agents (not provided by this tool).
 */
class SetupProjectTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.SYSTEM

    override val name: String = "setup_project"

    override val title: String = "Setup Task Orchestrator Project"

    override val description: String = """Initializes core Task Orchestrator project configuration.

        What This Creates:
        - `.taskorchestrator/` - Core configuration directory
        - `.taskorchestrator/config.yaml` - Orchestrator configuration (status progression, validation rules)
        - `.taskorchestrator/agent-mapping.yaml` - Agent routing configuration

        Configuration Files:
        - config.yaml: Status workflows, validation rules, quality gates, parallelism settings
        - agent-mapping.yaml: Maps task tags to appropriate agents (used with Claude Code)

        Parameters: None required

        Usage notes:
        - Idempotent: Safe to run multiple times (skips existing files)
        - Won't overwrite user customizations
        - Returns list of created vs skipped files
        - AI-agnostic: Works with any MCP client

        When to use:
        - First time using Task Orchestrator in a project
        - After cloning a repository without .taskorchestrator/
        - Restoring default configurations

        After setup:
        - Edit .taskorchestrator/config.yaml to customize status workflows
        - Commit .taskorchestrator/ directory for team sharing

        For detailed examples and patterns: task-orchestrator://docs/tools/setup-project
        """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(emptyMap()),
        required = emptyList()
    )

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether the operation succeeded")
                    )
                ),
                "message" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Human-readable message describing the result")
                    )
                ),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("Setup operation results"),
                        "properties" to JsonObject(
                            mapOf(
                                "directoryCreated" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("boolean"),
                                        "description" to JsonPrimitive("Whether .taskorchestrator directory was newly created")
                                    )
                                ),
                                "configCreated" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("boolean"),
                                        "description" to JsonPrimitive("Whether config.yaml was newly created")
                                    )
                                ),
                                "agentMappingCreated" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("boolean"),
                                        "description" to JsonPrimitive("Whether agent-mapping.yaml was newly created")
                                    )
                                ),
                                "directory" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Path to the .taskorchestrator directory")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        ),
        required = listOf("success", "message")
    )

    override fun validateParams(params: JsonElement) {
        // No parameters required
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing setup_project tool")

        return try {
            val configManager = TaskOrchestratorConfigManager()

            // Step 1: Create .taskorchestrator directory
            logger.info("Creating .taskorchestrator/ directory structure...")
            val directoryCreated = configManager.createTaskOrchestratorDirectory()

            // Step 2: Copy config.yaml file (core orchestrator configuration)
            logger.info("Copying config.yaml configuration file...")
            val configCopied = configManager.copyConfigFile()

            // Step 3: Copy agent-mapping.yaml file (agent routing configuration)
            logger.info("Copying agent-mapping.yaml configuration file...")
            val agentMappingCopied = configManager.copyAgentMappingFile()

            // Step 4: Check for version updates (if files already existed)
            val versionStatus = configManager.getConfigVersionStatus()
            val outdatedConfigs = versionStatus.filter { it.value.first }

            // Build response message
            val message = buildString {
                append("Task Orchestrator project setup ")
                if (directoryCreated) {
                    append("completed successfully. ")
                } else {
                    append("verified. ")
                }

                if (configCopied) {
                    append("Created config.yaml. ")
                } else {
                    append("Config.yaml already exists. ")
                }

                if (agentMappingCopied) {
                    append("Created agent-mapping.yaml. ")
                } else {
                    append("Agent-mapping.yaml already exists. ")
                }

                if (directoryCreated || configCopied || agentMappingCopied) {
                    append("Core configuration is ready.")
                } else {
                    append("All configuration files already present.")
                }

                // Report version status if any configs are outdated
                if (outdatedConfigs.isNotEmpty()) {
                    append("\n\n⚠️  Configuration updates available:\n")
                    outdatedConfigs.forEach { (filename, versionInfo) ->
                        val (_, currentVersion, latestVersion) = versionInfo
                        append("  - $filename: ")
                        if (currentVersion != null) {
                            append("v$currentVersion → v$latestVersion")
                        } else {
                            append("No version → v$latestVersion (pre-v2.0 format)")
                        }
                        append("\n")
                    }
                    append("\nTo upgrade configurations while preserving customizations, run: update_project_config workflow")
                }
            }

            successResponse(
                data = buildJsonObject {
                    put("directoryCreated", directoryCreated)
                    put("configCreated", configCopied)
                    put("agentMappingCreated", agentMappingCopied)
                    put("directory", configManager.getTaskOrchestratorDir().toString())
                    put("configPath", configManager.getTaskOrchestratorDir().resolve(
                        TaskOrchestratorConfigManager.CONFIG_FILE
                    ).toString())
                    put("agentMappingPath", configManager.getTaskOrchestratorDir().resolve(
                        TaskOrchestratorConfigManager.AGENT_MAPPING_FILE
                    ).toString())
                    put("hasOutdatedConfigs", outdatedConfigs.isNotEmpty())
                    put("currentVersion", TaskOrchestratorConfigManager.CURRENT_CONFIG_VERSION)
                    if (outdatedConfigs.isNotEmpty()) {
                        put("outdatedConfigs", buildJsonArray {
                            outdatedConfigs.forEach { (filename, versionInfo) ->
                                add(buildJsonObject {
                                    put("filename", filename)
                                    put("currentVersion", versionInfo.second ?: "unknown")
                                    put("latestVersion", versionInfo.third)
                                })
                            }
                        })
                    }
                },
                message = message
            )
        } catch (e: Exception) {
            logger.error("Error setting up Task Orchestrator project", e)
            errorResponse(
                message = "Failed to setup Task Orchestrator project",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error occurred during project setup"
            )
        }
    }
}
