package io.github.jpicklyk.mcptask.test.mock

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * A mock tool implementation for testing. This tool returns predefined responses.
 */
class MockToolDefinition(
    override val name: String = "mock_tool",
    override val description: String = "Mock tool for testing",
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT,
    override val parameterSchema: Tool.Input = Tool.Input(properties = JsonObject(emptyMap()), required = emptyList()),
    private val mockResponse: JsonElement = buildJsonObject {
        put("success", true)
        put("message", "Mock response")
        put("data", JsonNull)
        put("error", JsonNull)
        put("metadata", buildJsonObject {
            put("timestamp", "2025-05-11T14:00:00Z")
        })
    },
    private val executeHandler: (suspend (JsonElement, ToolExecutionContext) -> JsonElement)? = null
) : ToolDefinition {

    private val logger = LoggerFactory.getLogger(MockToolDefinition::class.java)

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing mock tool: $name")
        return executeHandler?.invoke(params, context) ?: mockResponse
    }

    override fun validateParams(params: JsonElement) {
        // Do nothing for mock implementation
    }
}
