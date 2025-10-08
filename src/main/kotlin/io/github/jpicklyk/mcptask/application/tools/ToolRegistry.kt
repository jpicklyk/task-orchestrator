package io.github.jpicklyk.mcptask.application.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for MCP tools that manages registration, discovery, and execution.
 * Acts as a central hub for all tool-related operations in the MCP server.
 *
 * @property executionContext The context to provide to tools during execution
 */
class ToolRegistry(
    private val executionContext: ToolExecutionContext
) {
    private val logger = LoggerFactory.getLogger(ToolRegistry::class.java)
    private val tools = ConcurrentHashMap<String, ToolDefinition>()

    /**
     * Register a tool with the registry.
     *
     * @param tool The tool definition to register
     * @throws IllegalArgumentException If a tool with the same name is already registered
     */
    fun registerTool(tool: ToolDefinition) {
        if (tools.containsKey(tool.name)) {
            throw IllegalArgumentException("Tool with name '${tool.name}' is already registered")
        }

        tools[tool.name] = tool
        logger.info("Registered tool: ${tool.name}")
    }

    /**
     * Register multiple tools with the registry.
     *
     * @param tools A collection of tool definitions to register
     */
    fun registerTools(tools: Collection<ToolDefinition>) {
        tools.forEach { registerTool(it) }
    }

    /**
     * Unregister a tool from the registry.
     *
     * @param toolName The name of the tool to unregister
     * @return true if the tool was unregistered, false if it wasn't found
     */
    fun unregisterTool(toolName: String): Boolean {
        val removed = tools.remove(toolName)
        if (removed != null) {
            logger.info("Unregistered tool: $toolName")
            return true
        }
        return false
    }

    /**
     * Get a tool definition by name.
     *
     * @param toolName The name of the tool to retrieve
     * @return The tool definition if found, null otherwise
     */
    fun getTool(toolName: String): ToolDefinition? {
        return tools[toolName]
    }

    /**
     * Get all registered tools.
     *
     * @return A map of tool names to tool definitions
     */
    fun getAllTools(): Map<String, ToolDefinition> {
        return tools.toMap()
    }

    /**
     * Get all tools in a specific category.
     *
     * @param category The category to filter by
     * @return A list of tool definitions in the specified category
     */
    fun getToolsByCategory(category: ToolCategory): List<ToolDefinition> {
        return tools.values.filter { it.category == category }
    }

    /**
     * Register all tools with an MCP server.
     *
     * @param server The MCP server to register tools with
     */
    fun registerWithServer(server: Server) {
        logger.info("Registering ${tools.size} tools with MCP server")

        tools.forEach { (name, definition) ->
            server.addTool(
                name = name,
                description = definition.description,
                inputSchema = definition.parameterSchema,
                title = definition.title,
                outputSchema = definition.outputSchema
            ) { request ->
                try {
                    // Validate parameters
                    definition.validateParams(request.arguments)

                    // Execute the tool using the shared execution context
                    CallToolResult(
                        listOf(
                            TextContent(
                                definition.execute(request.arguments, executionContext).toString()
                            )
                        )
                    )

                } catch (e: ToolValidationException) {
                    val message = "Tool validation error for ${definition.name}: ${e.message}"
                    logger.warn(message)
                    return@addTool CallToolResult(listOf(TextContent(message)), isError = true)
                } catch (e: ToolExecutionException) {
                    val message = "Tool execution error for ${definition.name}: ${e.message}"
                    logger.warn(message)
                    return@addTool CallToolResult(listOf(TextContent(message)), isError = true)
                } catch (e: Exception) {
                    val message = "Unexpected error in tool ${definition.name}: ${e.message}"
                    logger.error(message, e)
                    return@addTool CallToolResult(listOf(TextContent(message)), isError = true)
                }
            }

            logger.debug("Registered tool ${definition.name} with MCP server")
        }

        logger.info("All tools registered with MCP server")
    }
}