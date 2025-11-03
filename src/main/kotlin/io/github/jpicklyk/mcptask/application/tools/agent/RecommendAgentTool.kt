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

    override val description: String = """Recommends appropriate execution method (Skill vs Subagent) for a task based on tags, complexity, and work type.

        CRITICAL Workflow:
        - When Skill recommended: Use Skill directly (lightweight coordination, 2-5 tool calls, ~500 tokens)
        - When Subagent recommended: Launch subagent using Task tool (deep implementation work, 2000+ tokens)
        - When NO recommendation: Work on task yourself using general capabilities

        Decision Logic (Skills vs Subagents):
        - **Skills** (Coordination): Recommended for task routing, dependency analysis, feature management, creating summaries
        - **Subagents** (Implementation): Recommended for code implementation, complex documentation, architecture, testing

        How It Works:
        1. Retrieves task metadata (tags, status, complexity)
        2. Analyzes work type: coordination vs implementation
        3. Matches task tags against agent/skill mappings in .taskorchestrator/agent-mapping.yaml
        4. Returns recommendation with execution instructions in nextAction field

        Parameters:
        - taskId (required): Task UUID

        Response Fields:
        - recommended (boolean): Whether skill/agent was recommended
        - recommendationType (string): "SKILL" or "SUBAGENT" (indicates routing decision)
        - agent (string): Recommended skill/agent name (e.g., "Task Management Skill" or "Database Engineer")
        - reason (string): Why this skill/agent or why no recommendation
        - matchedTags (array): Task tags that matched mapping
        - sectionTags (array): Section tags for efficient information retrieval
        - nextAction (object): Instructions and parameters for launching skill/subagent

        Routing Examples:
        - Task with tags [task-routing, coordination] → Recommends "Task Management Skill"
        - Task with tags [backend, api-implementation] → Recommends "Backend Engineer" subagent
        - Task with tags [dependency-check] → Recommends "Dependency Analysis Skill"
        - Task with tags [database, schema] → Recommends "Database Engineer" subagent

        Agent/Skill Selection:
        - Matches task tags against mappings in .taskorchestrator/agent-mapping.yaml
        - Names in Proper Case format
        - Returns section tags to help skill/agent find relevant information
        - No recommendation when task tags don't match any mappings

        Usage notes:
        - Run setup_project first to initialize core configuration
        - Response includes complete nextAction with Tool parameters
        - Section tags help agents query only relevant sections (token efficiency)

        Related tools: get_agent_definition, get_task, setup_project

        For detailed examples and patterns: task-orchestrator://docs/tools/recommend-agent
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
