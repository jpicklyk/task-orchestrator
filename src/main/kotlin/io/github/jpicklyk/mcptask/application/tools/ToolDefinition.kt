package io.github.jpicklyk.mcptask.application.tools

import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.JsonElement

/**
 * Interface defining the common structure for all MCP tools.
 * Each tool implementation should provide metadata, parameter schema, and execution logic.
 */
interface ToolDefinition {
    /**
     * The name of the tool, which is used to identify it in the MCP protocol.
     */
    val name: String

    /**
     * A human-readable description of what the tool does.
     */
    val description: String

    /**
     * Optional human-readable title for the tool (used for better organization and discoverability).
     * If not specified, defaults to null.
     */
    val title: String? get() = null

    /**
     * The parameter schema defining the expected input for this tool.
     */
    val parameterSchema: Tool.Input

    /**
     * Optional output schema defining the structure of the tool's response.
     * Enables better integration with agent workflow systems (n8n, Zapier, etc.).
     * If not specified, defaults to null.
     */
    val outputSchema: Tool.Output? get() = null

    /**
     * Categorization for organizing tools in documentation and UI.
     */
    val category: ToolCategory

    /**
     * Executes the tool with the provided parameters.
     *
     * @param params The input parameters as a JSON element
     * @param context The execution context providing access to shared resources
     * @return The result of the tool execution as a JsonElement
     * @throws ToolValidationException If the parameters are invalid
     * @throws ToolExecutionException If an error occurs during execution
     */
    suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement

    /**
     * Validates the input parameters against the parameter schema.
     * The default implementation doesn't perform any validation.
     *
     * @param params The input parameters as a JSON element
     * @throws ToolValidationException If the parameters are invalid
     */
    fun validateParams(params: JsonElement) {
        // Default implementation doesn't validate
        // Subclasses can override to add validation logic
    }
}

/**
 * Categories for organizing tools by functionality.
 */
enum class ToolCategory(val value: String) {
    TASK_MANAGEMENT("task_management"),
    FEATURE_MANAGEMENT("feature_management"),
    TEMPLATE_MANAGEMENT("template_management"),
    PROJECT_MANAGEMENT("project_management"),
    SECTION_MANAGEMENT("section_management"),
    COMPLEXITY_ANALYSIS("complexity_analysis"),
    TASK_EXPANSION("task_expansion"),
    DEPENDENCY_MANAGEMENT("dependency_management"),
    QUERY_AND_FILTER("query_and_filter"),
    REPORTING("reporting"),
    SYSTEM("system")
}

/**
 * Exception thrown when tool parameter validation fails.
 */
class ToolValidationException(message: String) : Exception(message)

/**
 * Exception thrown when tool execution fails.
 */
class ToolExecutionException(message: String, cause: Throwable? = null) : Exception(message, cause)
