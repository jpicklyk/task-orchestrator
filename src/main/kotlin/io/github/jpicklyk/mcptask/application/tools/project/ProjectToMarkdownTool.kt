package io.github.jpicklyk.mcptask.application.tools.project

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
 * MCP tool for transforming a project into markdown format with YAML frontmatter.
 *
 * This tool retrieves a project and its sections, then renders them as a markdown document
 * with YAML frontmatter metadata. The markdown output is suitable for file export,
 * documentation generation, and systems that can render markdown directly.
 *
 * Use this tool when you need a markdown-formatted view of a project rather than JSON data.
 * For inspecting project details in structured format, use get_project instead.
 *
 * Related tools:
 * - get_project: To retrieve project details in JSON format for inspection
 * - task_to_markdown: To transform a task into markdown
 * - feature_to_markdown: To transform a feature into markdown
 */
class ProjectToMarkdownTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.PROJECT_MANAGEMENT

    override val name: String = "project_to_markdown"

    override val title: String = "Transform Project to Markdown"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("The project rendered as markdown"),
                        "properties" to JsonObject(
                            mapOf(
                                "markdown" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Markdown-formatted project with YAML frontmatter")
                                    )
                                ),
                                "projectId" to JsonObject(
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

    override val description: String = """Transforms a project into markdown format with YAML frontmatter.

        This tool retrieves a project and all its sections, then renders them as a markdown document
        with YAML frontmatter containing project metadata. The output is suitable for:
        - File export and documentation generation
        - Systems that can render markdown directly
        - Version control and diff-friendly storage
        - Human-readable project archives

        The markdown output includes:
        - YAML frontmatter with project metadata (id, name, status, tags, dates)
        - Project summary as the first content paragraph
        - All sections rendered according to their content format (markdown, code, JSON, plain text)

        For inspecting project details in structured JSON format, use get_project instead.
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID (UUID) of the project to transform to markdown (e.g., '550e8400-e29b-41d4-a716-446655440000')")
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
        logger.info("Executing project_to_markdown tool")

        try {
            // Extract parameters
            val idStr = requireString(params, "id")
            val projectId = UUID.fromString(idStr)

            // Get the project
            val projectResult = context.projectRepository().getById(projectId)

            return when (projectResult) {
                is Result.Success -> {
                    val project = projectResult.data

                    // Get sections for the project
                    val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.PROJECT, projectId)
                    val sections = if (sectionsResult is Result.Success) sectionsResult.data else emptyList()

                    // Render to markdown
                    val renderer = MarkdownRenderer()
                    val markdown = renderer.renderProject(project, sections)

                    val data = buildJsonObject {
                        put("markdown", markdown)
                        put("projectId", projectId.toString())
                    }

                    successResponse(data, "Project transformed to markdown successfully")
                }

                is Result.Error -> {
                    if (projectResult.error is RepositoryError.NotFound) {
                        errorResponse(
                            message = "Project not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No project exists with ID $projectId"
                        )
                    } else {
                        errorResponse(
                            message = "Failed to retrieve project",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = projectResult.error.toString()
                        )
                    }
                }
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in project_to_markdown: ${e.message}")
            return errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error transforming project to markdown", e)
            return errorResponse(
                message = "Failed to transform project to markdown",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}
