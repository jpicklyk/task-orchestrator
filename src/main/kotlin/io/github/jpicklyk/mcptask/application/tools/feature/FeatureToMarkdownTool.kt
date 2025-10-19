package io.github.jpicklyk.mcptask.application.tools.feature

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
 * MCP tool for transforming a feature into markdown format with YAML frontmatter.
 *
 * This tool retrieves a feature and its sections, then renders them as a markdown document
 * with YAML frontmatter metadata. The markdown output is suitable for file export,
 * documentation generation, and systems that can render markdown directly.
 *
 * Use this tool when you need a markdown-formatted view of a feature rather than JSON data.
 * For inspecting feature details in structured format, use get_feature instead.
 *
 * Related tools:
 * - get_feature: To retrieve feature details in JSON format for inspection
 * - task_to_markdown: To transform a task into markdown
 * - project_to_markdown: To transform a project into markdown
 */
class FeatureToMarkdownTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.FEATURE_MANAGEMENT

    override val name: String = "feature_to_markdown"

    override val title: String = "Transform Feature to Markdown"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("The feature rendered as markdown"),
                        "properties" to JsonObject(
                            mapOf(
                                "markdown" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Markdown-formatted feature with YAML frontmatter")
                                    )
                                ),
                                "featureId" to JsonObject(
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

    override val description: String = """⚠️ DEPRECATED: Use manage_feature with operation="export" instead.

Transforms a feature into markdown format with YAML frontmatter.

        This tool retrieves a feature and all its sections, then renders them as a markdown document
        with YAML frontmatter containing feature metadata. The output is suitable for:
        - File export and documentation generation
        - Systems that can render markdown directly
        - Version control and diff-friendly storage
        - Human-readable feature archives

        The markdown output includes:
        - YAML frontmatter with feature metadata (id, name, status, priority, tags, dates)
        - Feature summary as the first content paragraph
        - All sections rendered according to their content format (markdown, code, JSON, plain text)

        For inspecting feature details in structured JSON format, use get_feature instead.

        For detailed examples and patterns: task-orchestrator://docs/tools/feature-to-markdown
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "id" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The unique ID (UUID) of the feature to transform to markdown (e.g., '550e8400-e29b-41d4-a716-446655440000')")
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
        logger.info("Executing feature_to_markdown tool")

        try {
            // Extract parameters
            val idStr = requireString(params, "id")
            val featureId = UUID.fromString(idStr)

            // Get the feature
            val featureResult = context.featureRepository().getById(featureId)

            return when (featureResult) {
                is Result.Success -> {
                    val feature = featureResult.data

                    // Get sections for the feature
                    val sectionsResult = context.sectionRepository().getSectionsForEntity(EntityType.FEATURE, featureId)
                    val sections = if (sectionsResult is Result.Success) sectionsResult.data else emptyList()

                    // Render to markdown
                    val renderer = MarkdownRenderer()
                    val markdown = renderer.renderFeature(feature, sections)

                    val data = buildJsonObject {
                        put("markdown", markdown)
                        put("featureId", featureId.toString())
                    }

                    successResponse(data, "Feature transformed to markdown successfully")
                }

                is Result.Error -> {
                    if (featureResult.error is RepositoryError.NotFound) {
                        errorResponse(
                            message = "Feature not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = "No feature exists with ID $featureId"
                        )
                    } else {
                        errorResponse(
                            message = "Failed to retrieve feature",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = featureResult.error.toString()
                        )
                    }
                }
            }
        } catch (e: ToolValidationException) {
            logger.warn("Validation error in feature_to_markdown: ${e.message}")
            return errorResponse(
                message = e.message ?: "Validation error",
                code = ErrorCodes.VALIDATION_ERROR
            )
        } catch (e: Exception) {
            logger.error("Error transforming feature to markdown", e)
            return errorResponse(
                message = "Failed to transform feature to markdown",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}
