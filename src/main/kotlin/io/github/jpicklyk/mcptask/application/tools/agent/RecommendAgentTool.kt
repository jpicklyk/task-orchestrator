package io.github.jpicklyk.mcptask.application.tools.agent

import io.github.jpicklyk.mcptask.application.service.agent.AgentRecommendationService
import io.github.jpicklyk.mcptask.application.service.agent.AgentRecommendationServiceImpl
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.infrastructure.filesystem.AgentDirectoryManager
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for getting AI agent recommendations for tasks.
 *
 * Analyzes task metadata (tags, status, complexity) to recommend the most
 * appropriate specialized AI agent for working on the task.
 */
class RecommendAgentTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.SYSTEM

    override val name: String = "recommend_agent"

    override val title: String = "Recommend AI Agent for Task"

    override val description: String = """Recommends an AI agent for a specific task based on task metadata.

        ## Purpose
        Analyzes task tags, status, and complexity to recommend the most appropriate
        specialized AI agent. Uses the agent-mapping.yaml configuration to match
        task characteristics with agent capabilities.

        ## How It Works
        1. Retrieves task metadata (tags, status, complexity)
        2. Matches task tags against agent mappings in agent-mapping.yaml
        3. Returns recommended agent with rationale and relevant section tags

        ## Parameters
        - **taskId** (required): UUID of the task to get recommendation for

        ## Usage Examples

        **Get Recommendation for Task:**
        ```json
        {
          "taskId": "550e8400-e29b-41d4-a716-446655440000"
        }
        ```

        ## Response Format

        **When Recommendation Found:**
        ```json
        {
          "success": true,
          "message": "Agent recommendation found",
          "data": {
            "recommended": true,
            "agent": "backend-engineer",
            "reason": "Task involves backend development work",
            "matchedTags": ["backend", "api"],
            "sectionTags": ["technical-approach", "implementation"],
            "definitionPath": ".taskorchestrator/agents/backend-engineer.md",
            "taskId": "550e8400-e29b-41d4-a716-446655440000",
            "taskTitle": "Implement REST API"
          }
        }
        ```

        **When No Recommendation:**
        ```json
        {
          "success": true,
          "message": "No agent recommendation available",
          "data": {
            "recommended": false,
            "reason": "No agent recommendation available for this task's tags",
            "taskId": "550e8400-e29b-41d4-a716-446655440000",
            "taskTags": ["documentation", "general"]
          }
        }
        ```

        ## Agent Selection Logic
        - Matches task tags against agent tag mappings
        - Considers workflow phase (implementation, testing, planning)
        - Returns section tags to help agent find relevant information efficiently

        ## Integration
        - Use recommended agent for task implementation
        - Use section tags with get_sections tool for efficient information retrieval
        - Agent definition files contain specialized instructions and workflows

        ## Error Handling
        - VALIDATION_ERROR: When taskId is missing or invalid UUID format
        - RESOURCE_NOT_FOUND: When task doesn't exist
        - INTERNAL_ERROR: When agent configuration cannot be read
        """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "taskId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("UUID of the task to get agent recommendation for"),
                        "format" to JsonPrimitive("uuid")
                    )
                )
            )
        ),
        required = listOf("taskId")
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
                        "description" to JsonPrimitive("Agent recommendation data"),
                        "properties" to JsonObject(
                            mapOf(
                                "recommended" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("boolean"),
                                        "description" to JsonPrimitive("Whether an agent recommendation was found")
                                    )
                                ),
                                "agent" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Name of recommended agent (if found)")
                                    )
                                ),
                                "reason" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Explanation of recommendation or why no recommendation")
                                    )
                                ),
                                "matchedTags" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("Task tags that matched agent mapping"),
                                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                    )
                                ),
                                "sectionTags" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("Section tags for efficient information retrieval"),
                                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                    )
                                ),
                                "definitionPath" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Path to agent definition file")
                                    )
                                ),
                                "taskId" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("UUID of the task")
                                    )
                                ),
                                "taskTitle" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Title of the task")
                                    )
                                ),
                                "taskTags" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("All tags on the task (when no recommendation)"),
                                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))
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
        val taskIdString = requireString(params, "taskId")
        try {
            UUID.fromString(taskIdString)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid UUID format for taskId: $taskIdString")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val taskIdString = params.jsonObject["taskId"]!!.jsonPrimitive.content
        val taskId = UUID.fromString(taskIdString)

        logger.info("Executing recommend_agent tool for task: $taskId")

        return try {
            val taskRepository = context.taskRepository()
            val agentDirectoryManager = AgentDirectoryManager()
            val agentRecommendationService: AgentRecommendationService =
                AgentRecommendationServiceImpl(agentDirectoryManager)

            // Get task from repository
            val taskResult = taskRepository.getById(taskId)

            if (!taskResult.isSuccess()) {
                return errorResponse(
                    message = "Task not found with ID: $taskId",
                    code = ErrorCodes.RESOURCE_NOT_FOUND,
                    details = "The specified task does not exist in the database"
                )
            }

            val task = taskResult.getOrNull()!!

            // Get agent recommendation
            val recommendation = agentRecommendationService.recommendAgent(task)

            if (recommendation != null) {
                successResponse(
                    data = buildJsonObject {
                        put("recommended", true)
                        put("agent", recommendation.agentName)
                        put("reason", recommendation.reason)
                        putJsonArray("matchedTags") {
                            recommendation.matchedTags.forEach { add(it) }
                        }
                        putJsonArray("sectionTags") {
                            recommendation.sectionTags.forEach { add(it) }
                        }
                        put("definitionPath", ".taskorchestrator/agents/${recommendation.agentName}.md")
                        put("taskId", taskId.toString())
                        put("taskTitle", task.title)
                    },
                    message = "Agent recommendation found"
                )
            } else {
                successResponse(
                    data = buildJsonObject {
                        put("recommended", false)
                        put("reason", "No agent recommendation available for this task's tags")
                        put("taskId", taskId.toString())
                        put("taskTitle", task.title)
                        putJsonArray("taskTags") {
                            task.tags.forEach { add(it) }
                        }
                    },
                    message = "No agent recommendation available"
                )
            }
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid task ID format", e)
            errorResponse(
                message = "Invalid task ID format",
                code = ErrorCodes.VALIDATION_ERROR,
                details = e.message ?: "Task ID must be a valid UUID"
            )
        } catch (e: Exception) {
            logger.error("Error getting agent recommendation", e)
            errorResponse(
                message = "Failed to get agent recommendation",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error occurred while getting recommendation"
            )
        }
    }
}
