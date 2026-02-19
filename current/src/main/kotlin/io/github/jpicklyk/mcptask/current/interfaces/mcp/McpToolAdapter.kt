package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Adapter bridging v3 [ToolDefinition] instances to the MCP SDK [Server].
 *
 * Follows the Adapter pattern from Clean Architecture: the application layer defines
 * tool contracts ([ToolDefinition]), and this class adapts them to the MCP protocol
 * without leaking protocol details into business logic.
 *
 * Compared to the v2 adapter, this version:
 * - Uses simplified boolean preprocessing (no verbose debug logging per parameter)
 * - Returns clean single-line error messages (no multi-line recommendation blocks)
 * - Leverages v3 [ResponseUtil] for response envelope inspection
 */
class McpToolAdapter {
    private val logger = LoggerFactory.getLogger(McpToolAdapter::class.java)

    /**
     * Registers a single tool with the MCP server.
     *
     * The registration wires up parameter preprocessing, validation, execution,
     * user summary generation, and structured content extraction into the MCP
     * tool handler lambda.
     *
     * @param server The MCP SDK server instance to register with
     * @param toolDefinition The v3 tool definition providing schema and execution logic
     * @param context The execution context giving tools access to repositories and services
     */
    fun registerToolWithServer(
        server: Server,
        toolDefinition: ToolDefinition,
        context: ToolExecutionContext
    ) {
        server.addTool(
            name = toolDefinition.name,
            description = toolDefinition.description,
            inputSchema = toolDefinition.parameterSchema
        ) { request ->
            try {
                val preprocessedParams = preprocessParameters(request.arguments ?: JsonObject(emptyMap()))

                try {
                    toolDefinition.validateParams(preprocessedParams)
                } catch (e: ToolValidationException) {
                    val message = "Validation error in '${toolDefinition.name}': ${e.message}"
                    logger.warn(message)
                    return@addTool CallToolResult(
                        content = listOf(TextContent(text = message)),
                        isError = true
                    )
                }

                // Execute the tool
                val result = toolDefinition.execute(preprocessedParams, context)
                val resultObj = result as? JsonObject

                // Determine error state from response envelope
                val isError = resultObj?.let { ResponseUtil.isErrorResponse(it) } ?: false

                // Generate user-facing summary
                val summary = toolDefinition.userSummary(preprocessedParams, result, isError)

                // Extract raw data for structuredContent (strip envelope)
                val structuredData = resultObj?.let { ResponseUtil.extractDataPayload(it) } as? JsonObject

                CallToolResult(
                    content = listOf(TextContent(text = summary)),
                    isError = isError,
                    structuredContent = structuredData
                )
            } catch (e: Exception) {
                val message = "Internal error in '${toolDefinition.name}': ${e.message}"
                logger.error(message, e)
                CallToolResult(
                    content = listOf(TextContent(text = message)),
                    isError = true
                )
            }
        }

        logger.debug("Registered tool '{}' with MCP server", toolDefinition.name)
    }

    /**
     * Registers multiple tools with the MCP server in batch.
     *
     * @param server The MCP SDK server instance to register with
     * @param tools Collection of v3 tool definitions to register
     * @param context The execution context giving tools access to repositories and services
     */
    fun registerToolsWithServer(
        server: Server,
        tools: Collection<ToolDefinition>,
        context: ToolExecutionContext
    ) {
        logger.info("Registering {} tools with MCP server", tools.size)
        tools.forEach { tool -> registerToolWithServer(server, tool, context) }
        logger.info("All {} tools registered with MCP server", tools.size)
    }

    /**
     * Normalizes string boolean values to actual JSON boolean primitives.
     *
     * Some MCP clients send boolean parameters as strings ("true"/"false").
     * This preprocessing step converts them to proper JSON booleans so that
     * tool validation and execution logic can rely on consistent types.
     */
    private fun preprocessParameters(params: JsonElement): JsonElement {
        val paramsObj = params as? JsonObject ?: return params
        val processed = mutableMapOf<String, JsonElement>()
        paramsObj.forEach { (key, value) ->
            processed[key] = if (value is JsonPrimitive && value.isString) {
                when (value.content.lowercase()) {
                    "true" -> JsonPrimitive(true)
                    "false" -> JsonPrimitive(false)
                    else -> value
                }
            } else {
                value
            }
        }
        return JsonObject(processed)
    }
}
