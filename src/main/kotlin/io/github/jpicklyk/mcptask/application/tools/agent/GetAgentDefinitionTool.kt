package io.github.jpicklyk.mcptask.application.tools.agent

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.infrastructure.filesystem.AgentDirectoryManager
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*

/**
 * MCP tool for retrieving an AI agent definition file.
 *
 * Returns the full agent definition file content (Markdown with YAML frontmatter)
 * for a specific agent by name.
 */
class GetAgentDefinitionTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.SYSTEM

    override val name: String = "get_agent_definition"

    override val title: String = "Get AI Agent Definition"

    override val description: String = """Retrieves an AI agent definition file by name.

        ## Purpose
        Returns the full agent definition file content (Markdown with YAML frontmatter)
        for a specific AI agent. Agent definitions contain instructions, capabilities,
        and guidelines for specialized AI agents.

        ## Parameters
        - **agentName** (required): Name of the agent to retrieve (e.g., 'backend-engineer', 'frontend-developer')
          - Can include or omit the .md extension
          - Case-sensitive file name matching

        ## Usage Examples

        **Get Backend Engineer Agent:**
        ```json
        {
          "agentName": "backend-engineer"
        }
        ```

        **Get Frontend Developer Agent:**
        ```json
        {
          "agentName": "frontend-developer.md"
        }
        ```

        ## Response Format
        Returns the full markdown content of the agent definition file, including:
        - YAML frontmatter with agent metadata
        - Agent description and capabilities
        - Recommended workflows and patterns
        - Tools and resources the agent should use

        ## Available Agents
        Use the `task-orchestrator://agents/list` resource or list_templates to see
        all available agents in the system.

        ## Error Handling
        - VALIDATION_ERROR: When agentName is missing or empty
        - RESOURCE_NOT_FOUND: When agent definition file doesn't exist
        - INTERNAL_ERROR: When file cannot be read
        """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "agentName" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Name of the agent to retrieve (e.g., 'backend-engineer')")
                    )
                )
            )
        ),
        required = listOf("agentName")
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
                        "description" to JsonPrimitive("Agent definition data"),
                        "properties" to JsonObject(
                            mapOf(
                                "agentName" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Name of the agent")
                                    )
                                ),
                                "fileName" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Full file name with extension")
                                    )
                                ),
                                "content" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Full agent definition content (Markdown with YAML frontmatter)")
                                    )
                                ),
                                "filePath" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Relative path to the agent file")
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
        val agentName = requireString(params, "agentName")
        if (agentName.isBlank()) {
            throw IllegalArgumentException("Agent name cannot be empty")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val agentName = params.jsonObject["agentName"]!!.jsonPrimitive.content
        logger.info("Executing get_agent_definition tool for agent: $agentName")

        return try {
            val agentDirectoryManager = AgentDirectoryManager()

            // Normalize file name (add .md if not present)
            val fileName = if (agentName.endsWith(".md")) agentName else "$agentName.md"

            // Read agent definition file
            val content = agentDirectoryManager.readAgentFile(fileName)

            if (content == null) {
                return errorResponse(
                    message = "Agent definition file not found: $fileName",
                    code = ErrorCodes.RESOURCE_NOT_FOUND,
                    details = "Run setup_agents tool to initialize agent configurations, or check that the agent name is correct."
                )
            }

            successResponse(
                data = buildJsonObject {
                    put("agentName", agentName)
                    put("fileName", fileName)
                    put("content", content)
                    put("filePath", ".taskorchestrator/agents/$fileName")
                },
                message = "Agent definition retrieved successfully"
            )
        } catch (e: Exception) {
            logger.error("Error retrieving agent definition", e)
            errorResponse(
                message = "Failed to retrieve agent definition",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error occurred while reading agent file"
            )
        }
    }
}
