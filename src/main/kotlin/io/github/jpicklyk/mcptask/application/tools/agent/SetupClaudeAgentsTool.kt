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

    override val description: String = """Initializes the Claude Code agent configuration system for this project.

        ## Purpose
        Sets up file-based Claude Code agent definitions in the .claude/agents/ directory.
        Enables specialized Claude Code subagents to be recommended and used based on task metadata.

        ## What This Tool Does

        1. **Creates Directory Structure**:
           - Creates `.claude/` directory in project root
           - Creates `.claude/agents/` subdirectory

        2. **Installs Claude Code Agent Templates**:
           - backend-engineer.md - Backend/API/service development (model: sonnet)
           - bug-triage-specialist.md - Bug intake and triage (model: sonnet)
           - database-engineer.md - Database/migration work (model: sonnet)
           - feature-architect.md - Feature authoring and structuring (model: opus)
           - feature-manager.md - Feature lifecycle coordination (model: sonnet)
           - frontend-developer.md - Frontend/UI development (model: sonnet)
           - planning-specialist.md - Task breakdown and planning (model: opus)
           - task-manager.md - Task lifecycle coordination (model: sonnet)
           - technical-writer.md - Documentation (model: sonnet)
           - test-engineer.md - Testing and QA (model: sonnet)

        ## Claude Code Compatibility
        Agent files are formatted for Claude Code with:
        - YAML frontmatter with name, description, tools, model
        - Model field: "sonnet" or "opus" (Claude Code format)
        - Markdown content with agent instructions

        ## Idempotent Operation
        This tool is safe to run multiple times:
        - Skips files that already exist
        - Won't overwrite user customizations
        - Returns list of newly created vs existing files

        ## When to Use
        - First time using Claude Code agent features
        - Setting up a new project
        - After cloning a repository (if agents not in version control)
        - To restore default agent configurations

        ## Usage Example
        ```json
        {
          // No parameters required
        }
        ```

        ## After Setup
        Use the following tools to work with agents:
        - `recommend_agent(taskId)` - Get agent recommendation for a task
        - `get_agent_definition(agentName)` - Read agent definition file

        ## File Locations
        - Agent definitions: `.claude/agents/*.md` (Claude Code specific)
        - Agent mapping config: `.taskorchestrator/agent-mapping.yaml` (used by recommend_agent tool)

        ## Customization
        After running this tool, you can:
        - Edit agent definition files in .claude/agents/ to customize behavior
        - Add custom agent definitions following the same format
        - Commit agent files to version control for team sharing
        - Modify .taskorchestrator/agent-mapping.yaml to change which agents are recommended

        ## Error Handling
        - FILESYSTEM_ERROR: Cannot create directories or copy files
        - INTERNAL_ERROR: Unexpected error during setup
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
                    append("Created agent-mapping.yaml.")
                } else {
                    append("Agent-mapping.yaml already exists.")
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
