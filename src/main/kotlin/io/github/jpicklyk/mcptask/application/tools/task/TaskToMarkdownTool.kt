package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.rendering.MarkdownRenderer
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for transforming a task into markdown format with YAML frontmatter.
 *
 * This tool retrieves a task and its sections, then renders them as a markdown document
 * with YAML frontmatter metadata. The markdown output is suitable for file export,
 * documentation generation, and systems that can render markdown directly.
 *
 * Use this tool when you need a markdown-formatted view of a task rather than JSON data.
 * For inspecting task details in structured format, use get_task instead.
 *
 * Related tools:
 * - get_task: To retrieve task details in JSON format for inspection
 * - feature_to_markdown: To transform a feature into markdown
 * - project_to_markdown: To transform a project into markdown
 */
class TaskToMarkdownTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT

    override val name: String = "task_to_markdown"

    override val title: String = "Transform Task to Markdown"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("The task rendered as markdown"),
                        "properties" to JsonObject(
                            mapOf(
                                "markdown" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Markdown-formatted task with YAML frontmatter")
                                    )
                                ),
                                "taskId" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "format" to JsonPrimitive("uuid")
                                    )
                                )
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

    override val description: String = """Transforms a task into markdown format with YAML frontmatter.

        This tool retrieves a task and all its sections, then renders them as a markdown document
        with YAML frontmatter containing task metadata. The output is suitable for:
        - File export and documentation generation
        - Systems that can render markdown directly
        - Version control and diff-friendly storage
        - Human-readable task archives

        The markdown output includes:
        - YAML frontmatter with task metadata (id, title, status, priority, complexity, tags, dates)
        - Task summary as the first content paragraph
        - All sections rendered according to their content format (markdown, code, JSON, plain text)

        For inspecting task details in structured JSON format, use get_task instead.
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID (UUID) of the task to transform to markdown (e.g., '550e8400-e29b-41d4-a716-446655440000')")
                    )
                )
            )
        ),
        required = listOf("id")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required parameters
        val idStr = requireString(params, "id")
        try {
            UUID.fromString(idStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid id format. Must be a valid UUID")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing task_to_markdown tool")

        try {
            // Extract parameters
            val idStr = requireString(params, "id")
            val taskId = UUID.fromString(idStr)

            // Get the task
            val taskResult = context.taskRepository().getById(taskId)

            return when (taskResult) {
                is Result.Success -> {
                    val task = taskResult.data

                    // Get sections for the task
                    val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.TASK, taskId)
                    val sections = if (sectionsResult is Result.Success) sectionsResult.data else emptyList()

                    // Render to markdown
                    val renderer = MarkdownRenderer()
                    val markdown = renderer.renderTask(task, sections)

                    val data = buildJsonObject {
                        put("markdown", markdown)
                        put("taskId", taskId.toString())
                    }

                    successResponse(data, "Task transformed to markdown successfully")
                }

                is Result.Error -> {
                    if (taskResult.error is RepositoryError.NotFound) {
                        errorResponse(
                            message = "Task not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No task exists with ID $taskId"
                        )
                    } else {
                        errorResponse(
                            message = "Failed to retrieve task",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = taskResult.error.toString()
                        )
                    }
                }
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in task_to_markdown: ${e.message}")
            return errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error transforming task to markdown", e)
            return errorResponse(
                message = "Failed to transform task to markdown",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}
