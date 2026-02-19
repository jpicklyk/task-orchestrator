package io.github.jpicklyk.mcptask.current.application.tools

import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Interface defining the common structure for all MCP tools in Current (v3).
 *
 * Each tool implementation provides metadata, parameter schema, and execution logic.
 * Tools are registered with the MCP server and invoked by AI agents via the protocol.
 */
interface ToolDefinition {
    /**
     * The name of the tool, used to identify it in the MCP protocol.
     * Must be unique across all registered tools (e.g., "manage_items", "query_items").
     */
    val name: String

    /**
     * A human-readable markdown description of what the tool does.
     * Displayed to AI agents for tool selection and usage guidance.
     */
    val description: String

    /**
     * The JSON Schema defining the expected input parameters for this tool.
     */
    val parameterSchema: ToolSchema

    /**
     * Optional MCP annotations providing behavioral hints about this tool.
     * Helps clients understand tool characteristics:
     * - readOnlyHint: tool does not modify state
     * - destructiveHint: tool may permanently delete data
     * - idempotentHint: repeated calls with same params produce same result
     * - openWorldHint: tool interacts with external systems
     */
    val toolAnnotations: ToolAnnotations? get() = null

    /**
     * Categorization for organizing tools in documentation and UI.
     */
    val category: ToolCategory

    /**
     * Executes the tool with the provided parameters.
     *
     * @param params The input parameters as a JSON element
     * @param context The execution context providing access to repositories and services
     * @return The result of the tool execution as a JsonElement (response envelope)
     * @throws ToolValidationException If the parameters are invalid
     * @throws ToolExecutionException If an error occurs during execution
     */
    suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement

    /**
     * Validates the input parameters before execution.
     * The default implementation performs no validation.
     * Subclasses should override to add parameter-specific validation logic.
     *
     * @param params The input parameters as a JSON element
     * @throws ToolValidationException If the parameters are invalid
     */
    fun validateParams(params: JsonElement) {
        // Default implementation doesn't validate.
        // Subclasses can override to add validation logic.
    }

    /**
     * Generates a short, user-facing summary line from the tool's execution result.
     * Shown in the client terminal while the model receives full data via structuredContent.
     *
     * Default implementation extracts the "message" field from the response envelope.
     * Tools should override this for operation-specific summaries.
     *
     * @param params The input parameters (allows summaries to reference operation type)
     * @param result The JSON result from execute()
     * @param isError Whether the execution resulted in an error
     * @return A short summary string for display in client UIs
     */
    fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        val obj = result as? JsonObject ?: return if (isError) "Tool execution failed" else "Operation completed"
        val message = obj["message"]?.let {
            if (it is JsonPrimitive && it.isString) it.content else null
        }
        return message ?: if (isError) "Tool execution failed" else "Operation completed successfully"
    }
}

/**
 * Categories for organizing tools by functionality.
 * Current (v3) uses a simplified category model reflecting the unified WorkItem entity.
 */
enum class ToolCategory(val value: String) {
    /** WorkItem CRUD and hierarchy operations */
    ITEM_MANAGEMENT("item_management"),

    /** Note content management operations */
    NOTE_MANAGEMENT("note_management"),

    /** Dependency graph operations */
    DEPENDENCY_MANAGEMENT("dependency_management"),

    /** Status progression, role transitions, and task recommendations */
    WORKFLOW("workflow"),

    /** System-level operations (health, diagnostics, configuration) */
    SYSTEM("system")
}

/**
 * Exception thrown when tool parameter validation fails.
 * Contains a descriptive message indicating which parameter(s) are invalid and why.
 */
class ToolValidationException(message: String) : RuntimeException(message)

/**
 * Exception thrown when tool execution fails due to a runtime error.
 * May wrap an underlying cause for debugging.
 */
class ToolExecutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
