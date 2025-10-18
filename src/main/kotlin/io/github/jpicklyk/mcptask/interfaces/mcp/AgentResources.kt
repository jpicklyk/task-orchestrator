package io.github.jpicklyk.mcptask.interfaces.mcp

import io.github.jpicklyk.mcptask.application.service.agent.AgentRecommendationService
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.github.jpicklyk.mcptask.infrastructure.filesystem.AgentDirectoryManager
import org.slf4j.LoggerFactory
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP Resources for AI Agent Discovery and Management.
 *
 * These resources enable AI agents to discover available specialized agents,
 * get agent recommendations for tasks, and retrieve agent definition files.
 */
object AgentResources {

    private val logger = LoggerFactory.getLogger(AgentResources::class.java)
    private val json = Json { prettyPrint = true; encodeDefaults = false }

    /**
     * Configures all agent-related MCP resources with the server.
     */
    fun configure(
        server: Server,
        agentDirectoryManager: AgentDirectoryManager,
        agentRecommendationService: AgentRecommendationService,
        taskRepository: TaskRepository
    ) {
        addListAgentsResource(server, agentRecommendationService)
        addRecommendAgentResource(server, agentRecommendationService, taskRepository)
        addAgentDefinitionResource(server, agentDirectoryManager)
    }

    /**
     * Adds resource for listing all available agents.
     * URI: task-orchestrator://agents/list
     */
    private fun addListAgentsResource(
        server: Server,
        agentRecommendationService: AgentRecommendationService
    ) {
        server.addResource(
            uri = "task-orchestrator://agents/list",
            name = "Available AI Agents",
            description = "Lists all configured AI agent definitions for this project",
            mimeType = "text/plain"
        ) { _ ->
            try {
                val agents = agentRecommendationService.listAvailableAgents()

                val responseData = buildJsonObject {
                    putJsonArray("agents") {
                        agents.forEach { agentName ->
                            addJsonObject {
                                put("name", agentName)
                                put("definitionPath", ".claude/agents/$agentName.md")
                            }
                        }
                    }
                    put("count", agents.size)
                }

                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            uri = "task-orchestrator://agents/list",
                            mimeType = "text/plain",
                            text = json.encodeToString(JsonElement.serializer(), responseData)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("Failed to list agents", e)
                val errorResponse = buildJsonObject {
                    put("error", "Failed to list agents: ${e.message}")
                    putJsonArray("agents") { }
                    put("count", 0)
                }
                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            uri = "task-orchestrator://agents/list",
                            mimeType = "text/plain",
                            text = json.encodeToString(JsonElement.serializer(), errorResponse)
                        )
                    )
                )
            }
        }
    }

    /**
     * Adds resource for getting agent recommendation for a specific task.
     * URI: task-orchestrator://agents/recommend?taskId={taskId}
     */
    private fun addRecommendAgentResource(
        server: Server,
        agentRecommendationService: AgentRecommendationService,
        taskRepository: TaskRepository
    ) {
        server.addResource(
            uri = "task-orchestrator://agents/recommend",
            name = "Agent Recommendation for Task",
            description = "Returns recommended AI agent configuration based on task metadata, tags, and workflow phase. Query parameter: taskId (UUID)",
            mimeType = "text/plain"
        ) { request ->
            try {
                // Extract taskId from URI query parameter
                val uri = request.uri ?: "task-orchestrator://agents/recommend"
                val taskId = extractTaskIdFromUri(uri)

                if (taskId == null) {
                    val errorResponse = buildJsonObject {
                        put("error", "Missing required query parameter: taskId")
                        put("usage", "task-orchestrator://agents/recommend?taskId=<uuid>")
                    }
                    return@addResource ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                uri = uri,
                                mimeType = "text/plain",
                                text = json.encodeToString(JsonElement.serializer(), errorResponse)
                            )
                        )
                    )
                }

                // Get task from repository
                val taskResult = taskRepository.getById(taskId)

                if (!taskResult.isSuccess()) {
                    val errorResponse = buildJsonObject {
                        put("error", "Task not found with ID: $taskId")
                    }
                    return@addResource ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                uri = uri,
                                mimeType = "text/plain",
                                text = json.encodeToString(JsonElement.serializer(), errorResponse)
                            )
                        )
                    )
                }

                val task = taskResult.getOrNull()!!

                // Get agent recommendation
                val recommendation = agentRecommendationService.recommendAgent(task)

                val responseData = if (recommendation != null) {
                    buildJsonObject {
                        put("recommended", true)
                        put("agent", recommendation.agentName)
                        put("reason", recommendation.reason)
                        putJsonArray("matchedTags") {
                            recommendation.matchedTags.forEach { add(it) }
                        }
                        putJsonArray("sectionTags") {
                            recommendation.sectionTags.forEach { add(it) }
                        }
                        put("definitionPath", ".claude/agents/${recommendation.agentName}.md")
                        put("taskId", taskId.toString())
                    }
                } else {
                    buildJsonObject {
                        put("recommended", false)
                        put("reason", "No agent recommendation available for this task's tags")
                        put("taskId", taskId.toString())
                        putJsonArray("taskTags") {
                            task.tags.forEach { add(it) }
                        }
                    }
                }

                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            uri = uri,
                            mimeType = "application/json",
                            text = json.encodeToString(JsonElement.serializer(), responseData)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("Failed to recommend agent", e)
                val errorResponse = buildJsonObject {
                    put("error", "Failed to recommend agent: ${e.message}")
                }
                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            uri = request.uri ?: "task-orchestrator://agents/recommend",
                            mimeType = "application/json",
                            text = json.encodeToString(JsonElement.serializer(), errorResponse)
                        )
                    )
                )
            }
        }
    }

    /**
     * Adds resource for getting agent definition file by name.
     * URI: task-orchestrator://agents/definition/{agentName}
     */
    private fun addAgentDefinitionResource(
        server: Server,
        agentDirectoryManager: AgentDirectoryManager
    ) {
        server.addResource(
            uri = "task-orchestrator://agents/definition",
            name = "Agent Definition File",
            description = "Returns the full agent definition file content (Markdown with YAML frontmatter) for a specific agent. Path parameter: {agentName}",
            mimeType = "text/markdown"
        ) { request ->
            try {
                // Extract agent name from URI path
                val uri = request.uri ?: "task-orchestrator://agents/definition"
                val agentName = extractAgentNameFromUri(uri)

                if (agentName == null) {
                    return@addResource ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                uri = uri,
                                mimeType = "text/plain",
                                text = "Error: Missing agent name in URI path. Usage: task-orchestrator://agents/definition/{agentName}"
                            )
                        )
                    )
                }

                // Read agent definition file
                val fileName = if (agentName.endsWith(".md")) agentName else "$agentName.md"
                val content = agentDirectoryManager.readAgentFile(fileName)

                if (content == null) {
                    return@addResource ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                uri = uri,
                                mimeType = "text/plain",
                                text = "Error: Agent definition file not found: $fileName\n\nRun setup_agents tool to initialize agent configurations."
                            )
                        )
                    )
                }

                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            uri = uri,
                            mimeType = "text/markdown",
                            text = content
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("Failed to get agent definition", e)
                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            uri = request.uri ?: "task-orchestrator://agents/definition",
                            mimeType = "text/plain",
                            text = "Error: Failed to get agent definition: ${e.message}"
                        )
                    )
                )
            }
        }
    }

    /**
     * Extract taskId UUID from URI query parameter.
     * Example: task-orchestrator://agents/recommend?taskId=550e8400-e29b-41d4-a716-446655440000
     */
    private fun extractTaskIdFromUri(uri: String): UUID? {
        return try {
            val queryString = uri.substringAfter("?", "")
            val params = queryString.split("&")
                .mapNotNull { param ->
                    val (key, value) = param.split("=", limit = 2)
                    key to value
                }
                .toMap()

            val taskIdString = params["taskId"] ?: return null
            UUID.fromString(taskIdString)
        } catch (e: Exception) {
            logger.warn("Failed to extract taskId from URI: $uri", e)
            null
        }
    }

    /**
     * Extract agent name from URI path.
     * Example: task-orchestrator://agents/definition/backend-engineer
     */
    private fun extractAgentNameFromUri(uri: String): String? {
        return try {
            // Remove query parameters if any
            val pathPart = uri.substringBefore("?")

            // Extract the last path segment after "definition/"
            val parts = pathPart.split("/")
            val definitionIndex = parts.indexOf("definition")

            if (definitionIndex >= 0 && definitionIndex < parts.size - 1) {
                parts[definitionIndex + 1]
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract agent name from URI: $uri", e)
            null
        }
    }
}
