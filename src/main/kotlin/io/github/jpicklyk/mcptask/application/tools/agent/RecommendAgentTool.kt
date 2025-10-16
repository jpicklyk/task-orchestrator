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

        ## CRITICAL WORKFLOW INSTRUCTION
        When a recommendation is returned:
        1. **ALWAYS use the Task tool** to launch the recommended agent (provided in nextAction.parameters)
        2. **NEVER work on the task yourself** if an agent is recommended
        3. **Follow the exact parameters** provided in nextAction.parameters.prompt

        When NO recommendation is returned:
        1. **Work on the task yourself** using your general capabilities
        2. Use available tools directly without launching a subagent

        ## How It Works
        1. Retrieves task metadata (tags, status, complexity)
        2. Matches task tags against agent mappings in agent-mapping.yaml
        3. Returns recommended agent with execution instructions in nextAction field

        ## Parameters
        - **taskId** (required): UUID of the task to get recommendation for

        ## Response Format

        **When Recommendation Found:**
        ```json
        {
          "success": true,
          "message": "Agent recommendation found. Use the Task tool to launch the Database Engineer agent.",
          "data": {
            "recommended": true,
            "agent": "Database Engineer",
            "reason": "Task involves database development work",
            "matchedTags": ["database", "schema"],
            "sectionTags": ["technical-approach", "data-model"],
            "definitionPath": ".taskorchestrator/agents/Database Engineer.md",
            "taskId": "550e8400-e29b-41d4-a716-446655440000",
            "taskTitle": "Design database schema",
            "nextAction": {
              "instruction": "Launch the Database Engineer agent using the Task tool",
              "tool": "Task",
              "parameters": {
                "subagent_type": "Database Engineer",
                "description": "Design database schema",
                "prompt": "Work on task 550e8400...: Design database schema. Start by reading..."
              }
            }
          }
        }
        ```

        **When No Recommendation:**
        ```json
        {
          "success": true,
          "message": "No agent recommendation available. Execute this task yourself.",
          "data": {
            "recommended": false,
            "reason": "No agent recommendation available for this task's tags",
            "taskId": "550e8400-e29b-41d4-a716-446655440000",
            "taskTags": ["documentation", "general"],
            "nextAction": {
              "instruction": "No specialized agent recommended. You should work on this task directly.",
              "approach": "Execute the task yourself using available tools and your general capabilities"
            }
          }
        }
        ```

        ## Agent Selection Logic
        - Matches task tags against agent tag mappings
        - Agent names are in Proper Case format (e.g., "Database Engineer", "Backend Engineer")
        - Returns section tags to help agent find relevant information efficiently

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

            // Get feature and project context for better agent prompts
            var featureName: String? = null
            var projectName: String? = null

            if (task.featureId != null) {
                val featureRepository = context.featureRepository()
                val featureResult = featureRepository.getById(task.featureId!!)
                if (featureResult.isSuccess()) {
                    val feature = featureResult.getOrNull()!!
                    featureName = feature.name

                    if (feature.projectId != null) {
                        val projectRepository = context.projectRepository()
                        val projectResult = projectRepository.getById(feature.projectId!!)
                        if (projectResult.isSuccess()) {
                            projectName = projectResult.getOrNull()!!.name
                        }
                    }
                }
            }

            // Get agent recommendation
            val recommendation = agentRecommendationService.recommendAgent(task)

            if (recommendation != null) {
                // Build execution prompt for the agent with essential context
                val agentPrompt = buildString {
                    append("Work on task ${taskId}: ${task.title}\n\n")
                    append("SUMMARY: ${task.summary}\n\n")

                    // Add project/feature context if available
                    if (projectName != null) {
                        append("PROJECT: $projectName")
                        if (featureName != null) {
                            append(" / FEATURE: $featureName")
                        }
                        append("\n\n")
                    }

                    append("Read full details: get_task(id='${taskId}', includeSections=true). ")
                    if (recommendation.sectionTags.isNotEmpty()) {
                        append("Focus on sections tagged: ${recommendation.sectionTags.joinToString(", ")}. ")
                    }
                    append("Follow your standard workflow.")
                }

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
                        // Agent definitions are in .claude/agents/ for Claude Code compatibility
                        val agentFileName = recommendation.agentName.lowercase().replace(" ", "-")
                        put("definitionPath", ".claude/agents/${agentFileName}.md")
                        put("taskId", taskId.toString())
                        put("taskTitle", task.title)

                        // Add execution instructions
                        putJsonObject("nextAction") {
                            put("instruction", "Launch the ${recommendation.agentName} agent using the Task tool")
                            put("tool", "Task")
                            putJsonObject("parameters") {
                                put("subagent_type", recommendation.agentName)
                                put("description", task.title)
                                put("prompt", agentPrompt)
                            }
                        }
                    },
                    message = "Agent recommendation found. Use the Task tool to launch the ${recommendation.agentName} agent."
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

                        // Add execution instructions for no recommendation
                        putJsonObject("nextAction") {
                            put("instruction", "No specialized agent recommended. You should work on this task directly.")
                            put("approach", "Execute the task yourself using available tools and your general capabilities")
                        }
                    },
                    message = "No agent recommendation available. Execute this task yourself."
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
