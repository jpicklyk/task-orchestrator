package io.github.jpicklyk.mcptask.interfaces.mcp

import io.github.jpicklyk.mcptask.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import org.slf4j.LoggerFactory

/**
 * Adapter for MCP tool integration.
 * This class bridges the application layer tool definitions with the MCP interface layer,
 * following the adapter pattern from Clean Architecture.
 */
class McpToolAdapter {
    private val logger = LoggerFactory.getLogger(McpToolAdapter::class.java)

    /**
     * Builds a detailed error message for parameter validation failures.
     *
     * @param toolName The name of the tool where the validation failed
     * @param error The validation exception
     * @return A detailed error message
     */
    private fun buildDetailedErrorMessage(toolName: String, error: ToolValidationException): String {
        val message = StringBuilder()
        message.append("Parameter validation error in tool '").append(toolName).append("':\n")
        message.append("  - ").append(error.message).append("\n")
        message.append("\nPlease ensure that:\n")

        // Add specific recommendations based on the error message
        if (error.message?.contains("must be a boolean", ignoreCase = true) == true) {
            message.append("  - Boolean parameters accept values: true, false, 1, 0\n")
            message.append("  - String representations are also supported: \"true\", \"false\", \"1\", \"0\"\n")
        } else if (error.message?.contains("must be an integer", ignoreCase = true) == true) {
            message.append("  - Integer parameters must be valid numbers without decimal points\n")
            message.append("  - String representations of numbers are also supported\n")
        } else if (error.message?.contains("must be a string", ignoreCase = true) == true) {
            message.append("  - String parameters must be enclosed in quotes\n")
        } else if (error.message?.contains("Missing required parameter", ignoreCase = true) == true) {
            message.append("  - All required parameters must be provided\n")
            message.append("  - Check the tool documentation for the list of required parameters\n")
        }

        message.append("\nRefer to the tool documentation for the expected parameter formats.")

        return message.toString()
    }
    private fun preprocessParameters(params: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        logger.debug("Preprocessing parameters: {}", params)

        // If it's not a JSON object, return as-is
        val paramsObj = params as? kotlinx.serialization.json.JsonObject
        if (paramsObj == null) {
            logger.debug("Parameters are not a JSON object, returning as-is")
            return params
        }

        // Create a new map for the processed parameters
        val processedParams = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()

        // Process each parameter
        paramsObj.forEach { (key, value) ->
            logger.debug("Processing parameter '{}' with value: {}", key, value)

            val processedValue = when {
                // Convert string boolean values to actual booleans where possible
                value is kotlinx.serialization.json.JsonPrimitive && value.isString -> {
                    when (value.content.lowercase()) {
                        "true" -> {
                            logger.debug("Converting parameter '$key' from string 'true' to boolean")
                            kotlinx.serialization.json.JsonPrimitive(true.toString())
                        }

                        "false" -> {
                            logger.debug("Converting parameter '$key' from string 'false' to boolean")
                            kotlinx.serialization.json.JsonPrimitive(false.toString())
                        }

                        "1" -> {
                            logger.debug("Converting parameter '$key' from string '1' to boolean true")
                            kotlinx.serialization.json.JsonPrimitive(true.toString())
                        }

                        "0" -> {
                            logger.debug("Converting parameter '$key' from string '0' to boolean false")
                            kotlinx.serialization.json.JsonPrimitive(false.toString())
                        }

                        else -> {
                            logger.debug("Parameter '$key' remains unchanged: ${value.content}")
                            value
                        }
                    }
                }

                else -> {
                    logger.debug("Parameter '$key' remains unchanged (not a string primitive)")
                    value
                }
            }

            processedParams[key] = processedValue
        }

        val result = kotlinx.serialization.json.JsonObject(processedParams)
        logger.debug("Preprocessed parameters: {}", result)
        return result
    }

    /**
     * Registers an application layer tool with the MCP server.
     *
     * @param server The MCP server to register with
     * @param toolDefinition The application layer tool definition
     * @param context The tool execution context to use
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
                // Preprocess and normalize parameters to handle various formats
                val preprocessedParams = preprocessParameters(request.arguments)

                // Log the processed parameters for debugging
                logger.debug("Processed parameters for tool '{}': {}", toolDefinition.name, preprocessedParams)

                try {
                    // Validate parameters
                    toolDefinition.validateParams(preprocessedParams)
                } catch (e: ToolValidationException) {
                    // Enhanced error message with more context
                    val message = "Parameter validation error in tool '${toolDefinition.name}': ${e.message}"
                    logger.warn(message)
                    return@addTool CallToolResult(
                        listOf(TextContent(buildDetailedErrorMessage(toolDefinition.name, e))),
                        isError = true
                    )
                }

                // Execute the tool with the validated parameters
                val result = toolDefinition.execute(preprocessedParams, context)

                CallToolResult(listOf(TextContent(result.toString())))
            } catch (e: Exception) {
                val errorType = when (e) {
                    is ToolValidationException -> "Validation"
                    else -> "Execution"
                }
                val message = "$errorType error in tool '${toolDefinition.name}': ${e.message}"
                logger.error(message, e)
                CallToolResult(listOf(TextContent(message)), isError = true)
            }
        }

        logger.debug("Registered tool ${toolDefinition.name} with MCP server")
    }

    /**
     * Registers multiple application layer tools with the MCP server.
     *
     * @param server The MCP server to register with
     * @param tools Collection of tool definitions
     * @param context The tool execution context to use
     */
    fun registerToolsWithServer(
        server: Server,
        tools: Collection<ToolDefinition>,
        context: ToolExecutionContext
    ) {
        logger.info("Registering ${tools.size} tools with MCP server")
        tools.forEach { tool -> registerToolWithServer(server, tool, context) }
        logger.info("All tools registered with MCP server")
    }
}
