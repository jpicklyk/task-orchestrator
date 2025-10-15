package io.github.jpicklyk.mcptask.application.tools.agent

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.infrastructure.filesystem.AgentDirectoryManager
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*

/**
 * MCP tool for initializing the AI agent configuration system.
 *
 * Creates .taskorchestrator/agents/ directory structure, copies default agent
 * template files from embedded resources, and creates agent-mapping.yaml configuration.
 *
 * This tool is idempotent - safe to run multiple times, will skip existing files.
 */
class SetupAgentsTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.SYSTEM

    override val name: String = "setup_agents"

    override val title: String = "Setup AI Agent Configuration"

    override val description: String = """Initializes the AI agent configuration system for this project.

        ## Purpose
        Sets up file-based AI agent definitions in the .taskorchestrator/agents/ directory.
        Enables specialized AI agents (Claude Code subagents, GitHub Copilot agents, etc.)
        to be recommended and used based on task metadata.

        ## What This Tool Does

        1. **Creates Directory Structure**:
           - Creates `.taskorchestrator/` directory in project root
           - Creates `.taskorchestrator/agents/` subdirectory

        2. **Installs Default Agent Templates**:
           - backend-engineer.md - Backend/API/service development
           - frontend-developer.md - Frontend/UI development
           - database-engineer.md - Database/migration work
           - test-engineer.md - Testing and QA
           - planning-specialist.md - Requirements and planning
           - technical-writer.md - Documentation

        3. **Creates Agent Mapping Configuration**:
           - agent-mapping.yaml with tag-based agent recommendations
           - Maps task tags to appropriate agents
           - Configures section tags for efficient information access

        ## Idempotent Operation
        This tool is safe to run multiple times:
        - Skips files that already exist
        - Won't overwrite user customizations
        - Returns list of newly created vs existing files

        ## When to Use
        - First time using AI agent features
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
        Use the following resources to work with agents:
        - `task-orchestrator://agents/list` - List available agents
        - `task-orchestrator://agents/recommend?taskId=<uuid>` - Get agent recommendation for a task
        - `task-orchestrator://agents/definition/<agent-name>` - Read agent definition file

        ## File Locations
        - Agent definitions: `.taskorchestrator/agents/*.md`
        - Configuration: `.taskorchestrator/agent-mapping.yaml`

        ## Customization
        After running this tool, you can:
        - Edit agent definition files to customize behavior
        - Modify agent-mapping.yaml to change tag mappings
        - Add custom agent definitions
        - Commit files to version control for team sharing

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
                                "mappingFileCreated" to JsonObject(
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
        logger.info("Executing setup_agents tool")

        return try {
            val agentDirectoryManager = AgentDirectoryManager()

            // Step 1: Create directory structure
            logger.info("Creating .taskorchestrator/agents/ directory structure...")
            val directoryCreated = agentDirectoryManager.createDirectoryStructure()

            // Step 2: Copy default agent template files
            logger.info("Copying default agent template files...")
            val copiedAgentFiles = agentDirectoryManager.copyDefaultAgentTemplates()

            // Calculate skipped files
            val allAgentFiles = AgentDirectoryManager.DEFAULT_AGENT_FILES
            val skippedAgentFiles = allAgentFiles.filter { it !in copiedAgentFiles }

            // Step 3: Copy agent-mapping.yaml
            logger.info("Copying agent mapping configuration...")
            val mappingFileCreated = agentDirectoryManager.copyDefaultAgentMapping()

            // Build response message
            val message = buildString {
                append("Agent system setup ")
                if (directoryCreated) {
                    append("completed successfully. ")
                } else {
                    append("verified. ")
                }

                if (copiedAgentFiles.isNotEmpty()) {
                    append("Created ${copiedAgentFiles.size} agent file(s). ")
                }
                if (skippedAgentFiles.isNotEmpty()) {
                    append("Skipped ${skippedAgentFiles.size} existing file(s). ")
                }

                if (mappingFileCreated) {
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
                    put("mappingFileCreated", mappingFileCreated)
                    put("directory", agentDirectoryManager.getTaskOrchestratorDir().toString())
                    put("totalAgents", allAgentFiles.size)
                },
                message = message
            )
        } catch (e: Exception) {
            logger.error("Error setting up agents", e)
            errorResponse(
                message = "Failed to setup agent system",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error occurred during agent setup"
            )
        }
    }
}
