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

    override val description: String = """Returns AI agent configuration files for the AI agent to write to the project directory.

        ## Purpose
        Provides file-based AI agent definitions that enable specialized AI agents
        (Claude Code subagents, GitHub Copilot agents, etc.) to be recommended and
        used based on task metadata.

        ## What This Tool Returns

        Returns the contents of all agent configuration files that should be created in
        the project directory:

        1. **Agent Definition Files** (6 files):
           - backend-engineer.md - Backend/API/service development
           - frontend-developer.md - Frontend/UI development
           - database-engineer.md - Database/migration work
           - test-engineer.md - Testing and QA
           - planning-specialist.md - Requirements and planning
           - technical-writer.md - Documentation

        2. **Agent Mapping Configuration**:
           - agent-mapping.yaml with tag-based agent recommendations
           - Maps task tags to appropriate agents
           - Configures section tags for efficient information access

        ## Expected AI Agent Behavior

        **CRITICAL**: The AI agent MUST write these files to the project directory:

        1. Create `.taskorchestrator/agents/` directory if it doesn't exist
        2. For each file in the response's `files` array:
           - Use the `Write` tool to create the file at the specified `path`
           - Write the provided `content` exactly as returned
        3. Inform the user which files were created

        ## Idempotent Operation
        Files are always returned with current embedded templates.
        AI agent should check if files exist and ask user before overwriting.

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

        ## File Locations (where AI agent should write)
        - Agent definitions: `.taskorchestrator/agents/*.md`
        - Configuration: `.taskorchestrator/agent-mapping.yaml`

        ## Customization
        After files are created, users can:
        - Edit agent definition files to customize behavior
        - Modify agent-mapping.yaml to change tag mappings
        - Add custom agent definitions
        - Commit files to version control for team sharing

        ## Error Handling
        - RESOURCE_NOT_FOUND: Embedded resource files not found
        - INTERNAL_ERROR: Unexpected error reading resources
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
                        "description" to JsonPrimitive("Agent configuration files to be written by AI agent"),
                        "properties" to JsonObject(
                            mapOf(
                                "files" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("Array of files that should be created by the AI agent"),
                                        "items" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("object"),
                                                "properties" to JsonObject(
                                                    mapOf(
                                                        "path" to JsonObject(
                                                            mapOf(
                                                                "type" to JsonPrimitive("string"),
                                                                "description" to JsonPrimitive("Relative path where file should be created (e.g., '.taskorchestrator/agents/backend-engineer.md')")
                                                            )
                                                        ),
                                                        "content" to JsonObject(
                                                            mapOf(
                                                                "type" to JsonPrimitive("string"),
                                                                "description" to JsonPrimitive("File content to write")
                                                            )
                                                        ),
                                                        "description" to JsonObject(
                                                            mapOf(
                                                                "type" to JsonPrimitive("string"),
                                                                "description" to JsonPrimitive("Description of what this file contains")
                                                            )
                                                        )
                                                    )
                                                )
                                            )
                                        )
                                    )
                                ),
                                "totalFiles" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive("Total number of files to be created")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        ),
        required = listOf("success", "message", "data")
    )

    override fun validateParams(params: JsonElement) {
        // No parameters required
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing setup_agents tool - preparing file contents")

        return try {
            val files = mutableListOf<JsonObject>()

            // Read all agent definition files from embedded resources
            val allAgentFiles = AgentDirectoryManager.DEFAULT_AGENT_FILES
            for (fileName in allAgentFiles) {
                val resourcePath = "${AgentDirectoryManager.RESOURCE_PATH_PREFIX}/$fileName"
                val content = readResourceAsString(resourcePath)
                    ?: throw IllegalStateException("Could not find embedded resource: $resourcePath")

                files.add(
                    buildJsonObject {
                        put("path", ".taskorchestrator/agents/$fileName")
                        put("content", content)
                        put("description", getAgentFileDescription(fileName))
                    }
                )
            }

            // Read agent-mapping.yaml from embedded resources
            val mappingResourcePath = "${AgentDirectoryManager.RESOURCE_PATH_PREFIX}/${AgentDirectoryManager.AGENT_MAPPING_FILE}"
            val mappingContent = readResourceAsString(mappingResourcePath)
                ?: throw IllegalStateException("Could not find embedded resource: $mappingResourcePath")

            files.add(
                buildJsonObject {
                    put("path", ".taskorchestrator/${AgentDirectoryManager.AGENT_MAPPING_FILE}")
                    put("content", mappingContent)
                    put("description", "Agent mapping configuration for tag-based agent recommendations")
                }
            )

            successResponse(
                data = buildJsonObject {
                    put("files", JsonArray(files))
                    put("totalFiles", files.size)
                },
                message = "Retrieved ${files.size} agent configuration files. AI agent should write these files to the project directory."
            )
        } catch (e: Exception) {
            logger.error("Error reading agent configuration resources", e)
            errorResponse(
                message = "Failed to read agent configuration files",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error occurred while reading agent resources"
            )
        }
    }

    /**
     * Read an embedded resource as a string.
     */
    private fun readResourceAsString(resourcePath: String): String? {
        return javaClass.getResourceAsStream(resourcePath)?.use { inputStream ->
            inputStream.bufferedReader().use { it.readText() }
        }
    }

    /**
     * Get human-readable description for an agent file.
     */
    private fun getAgentFileDescription(fileName: String): String {
        return when (fileName) {
            "backend-engineer.md" -> "Backend engineer agent for API/service development"
            "frontend-developer.md" -> "Frontend developer agent for UI development"
            "database-engineer.md" -> "Database engineer agent for schema/migration work"
            "test-engineer.md" -> "Test engineer agent for testing and QA"
            "planning-specialist.md" -> "Planning specialist agent for requirements and design"
            "technical-writer.md" -> "Technical writer agent for documentation"
            else -> "Agent definition file"
        }
    }
}
