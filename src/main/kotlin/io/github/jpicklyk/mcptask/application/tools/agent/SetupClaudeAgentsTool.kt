package io.github.jpicklyk.mcptask.application.tools.agent

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.infrastructure.filesystem.ClaudeAgentDirectoryManager
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*

/**
 * MCP tool for initializing Claude Code agent configuration.
 *
 * Creates .claude/agents/ directory structure, copies Claude-specific agent
 * template files from embedded resources, and sets up agent configuration.
 *
 * This tool is idempotent - safe to run multiple times, will skip existing files.
 */
class SetupClaudeAgentsTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.SYSTEM

    override val name: String = "setup_claude_agents"

    override val title: String = "Setup Claude Code Agent Configuration"

    override val description: String = """Initializes Claude Code agent configuration system by creating .claude/agents/ directory and installing 10 specialized agent definitions.

        What This Creates:
        - `.claude/agents/` directory structure
        - 10 agent definition files: backend-engineer, bug-triage-specialist, database-engineer, feature-architect, feature-manager, frontend-developer, planning-specialist, task-manager, technical-writer, test-engineer
        - Agent mapping configuration in `.taskorchestrator/agent-mapping.yaml`

        Agent Details:
        - Backend Engineer, Database Engineer, Frontend Developer, Test Engineer, Technical Writer (sonnet model)
        - Feature Architect, Planning Specialist (opus model)
        - Feature Manager, Task Manager, Bug Triage Specialist (sonnet model)

        Parameters: None required

        Usage notes:
        - Idempotent: Safe to run multiple times (skips existing files)
        - Won't overwrite user customizations
        - Returns list of created vs skipped files
        - Agent files use YAML frontmatter with Claude Code format

        When to use:
        - First time using Claude Code agent features
        - Setting up new project or after cloning repository
        - Restoring default agent configurations

        After setup:
        - Use recommend_agent(taskId) to get agent recommendations
        - Use get_agent_definition(agentName) to read agent files
        - Edit .claude/agents/*.md to customize behavior
        - Commit agent files to version control for team sharing

        Related tools: recommend_agent, get_agent_definition

        For detailed examples and patterns: task-orchestrator://docs/tools/setup-claude-agents
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
                                        "description" to JsonPrimitive("Whether directory was newly created")
                                    )
                                ),
                                "agentFilesCreated" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("List of agent files that were newly created"),
                                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                    )
                                ),
                                "agentFilesSkipped" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("List of agent files that already existed"),
                                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                    )
                                ),
                                "directory" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Path to the .claude directory")
                                    )
                                ),
                                "totalAgents" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive("Total number of agent files (created + skipped)")
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
        logger.info("Executing setup_claude_agents tool")

        return try {
            val agentDirectoryManager = ClaudeAgentDirectoryManager()

            // Step 1: Create directory structure
            logger.info("Creating .claude/agents/ directory structure...")
            val directoryCreated = agentDirectoryManager.createDirectoryStructure()

            // Step 2: Copy Claude-specific agent template files
            logger.info("Copying Claude Code agent template files...")
            val copiedAgentFiles = agentDirectoryManager.copyDefaultAgentTemplates()

            // Step 3: Create .taskorchestrator directory
            logger.info("Creating .taskorchestrator/ directory structure...")
            val taskOrchestratorDirCreated = agentDirectoryManager.createTaskOrchestratorDirectory()

            // Step 4: Copy agent-mapping.yaml file
            logger.info("Copying agent-mapping.yaml configuration file...")
            val agentMappingCopied = agentDirectoryManager.copyAgentMappingFile()

            // Step 5: Inject decision gates into CLAUDE.md
            logger.info("Injecting decision gates into CLAUDE.md...")
            val decisionGatesInjected = agentDirectoryManager.injectDecisionGatesIntoClaude()

            // Calculate skipped files
            val allAgentFiles = ClaudeAgentDirectoryManager.DEFAULT_AGENT_FILES
            val skippedAgentFiles = allAgentFiles.filter { it !in copiedAgentFiles }

            // Build response message
            val message = buildString {
                append("Claude Code agent system setup ")
                if (directoryCreated || taskOrchestratorDirCreated) {
                    append("completed successfully. ")
                } else {
                    append("verified. ")
                }

                if (copiedAgentFiles.isNotEmpty()) {
                    append("Created ${copiedAgentFiles.size} agent file(s). ")
                }
                if (skippedAgentFiles.isNotEmpty()) {
                    append("Skipped ${skippedAgentFiles.size} existing agent file(s). ")
                }
                if (agentMappingCopied) {
                    append("Created agent-mapping.yaml. ")
                } else {
                    append("Agent-mapping.yaml already exists. ")
                }
                if (decisionGatesInjected) {
                    append("Injected decision gates into CLAUDE.md.")
                } else {
                    append("Decision gates already present in CLAUDE.md.")
                }
            }

            successResponse(
                data = buildJsonObject {
                    put("directoryCreated", directoryCreated)
                    put("agentFilesCreated", JsonArray(copiedAgentFiles.map { JsonPrimitive(it) }))
                    put("agentFilesSkipped", JsonArray(skippedAgentFiles.map { JsonPrimitive(it) }))
                    put("directory", agentDirectoryManager.getClaudeDir().toString())
                    put("totalAgents", allAgentFiles.size)
                    put("taskOrchestratorDirCreated", taskOrchestratorDirCreated)
                    put("agentMappingCreated", agentMappingCopied)
                    put("agentMappingPath", agentDirectoryManager.getTaskOrchestratorDir().resolve(
                        ClaudeAgentDirectoryManager.AGENT_MAPPING_FILE
                    ).toString())
                    put("decisionGatesInjected", decisionGatesInjected)
                },
                message = message
            )
        } catch (e: Exception) {
            logger.error("Error setting up Claude agents", e)
            errorResponse(
                message = "Failed to setup Claude Code agent system",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error occurred during agent setup"
            )
        }
    }
}
