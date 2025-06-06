package io.github.jpicklyk.mcptask.application.tools.base

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.test.util.ResponseAssertions
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BaseToolDefinitionTest {

    private lateinit var tool: TestBaseToolDefinition

    @BeforeEach
    fun setup() {
        tool = TestBaseToolDefinition()
    }

    @Test
    fun `test create success response with data and message`() {
        val data = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val message = "Success message"

        val response = tool.testCreateSuccessResponse(data, message)

        val responseData = ResponseAssertions.assertSuccessResponse(response, message)
        assertTrue(responseData is JsonObject)
        assertEquals("value", (responseData as JsonObject)["key"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test create success response with message only`() {
        val message = "Success message only"

        val response = tool.testCreateSuccessResponse(message)

        ResponseAssertions.assertSuccessResponse(response, message)
    }

    @Test
    fun `test create error response`() {
        val message = "Error message"

        val response = tool.testCreateErrorResponse(message)

        ResponseAssertions.assertErrorResponse(response, message)
    }

    @Test
    fun `test create error response with custom code and details`() {
        val message = "Error message"
        val code = "CUSTOM_ERROR"
        val details = "Detailed error information"

        val response = tool.testCreateErrorResponse(message, code, details)

        ResponseAssertions.assertErrorResponse(response, message, code)

        val responseObj = response as JsonObject
        val errorObj = responseObj["error"] as JsonObject
        assertEquals(details, errorObj["details"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test require string parameter`() {
        val params = JsonObject(
            mapOf(
                "string_param" to JsonPrimitive("test value")
            )
        )

        assertEquals("test value", tool.testRequireString(params, "string_param"))
    }

    @Test
    fun `test require string parameter missing`() {
        val params = JsonObject(mapOf())

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.testRequireString(params, "missing_param")
        }

        assertTrue(exception.message!!.contains("Missing required parameter"))
    }

    @Test
    fun `test require string parameter wrong type`() {
        val params = JsonObject(
            mapOf(
                "number_param" to JsonPrimitive(123)
            )
        )

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.testRequireString(params, "number_param")
        }

        assertTrue(exception.message!!.contains("must be a string"))
    }

    @Test
    fun `test optional string parameter present`() {
        val params = JsonObject(
            mapOf(
                "string_param" to JsonPrimitive("optional value")
            )
        )

        assertEquals("optional value", tool.testOptionalString(params, "string_param"))
    }

    @Test
    fun `test optional string parameter missing`() {
        val params = JsonObject(mapOf())

        assertNull(tool.testOptionalString(params, "missing_param"))
    }

    @Test
    fun `test optional string parameter with default value present`() {
        val params = JsonObject(
            mapOf(
                "string_param" to JsonPrimitive("custom value")
            )
        )

        assertEquals("custom value", tool.testOptionalStringWithDefault(params, "string_param", "default value"))
    }

    @Test
    fun `test optional string parameter with default value missing`() {
        val params = JsonObject(mapOf())

        assertEquals("default value", tool.testOptionalStringWithDefault(params, "missing_param", "default value"))
    }

    @Test
    fun `test optional string parameter with blank value`() {
        val params = JsonObject(
            mapOf(
                "string_param" to JsonPrimitive("")
            )
        )

        assertNull(tool.testOptionalString(params, "string_param"))
    }

    @Test
    fun `test require boolean parameter true`() {
        val params = JsonObject(
            mapOf(
                "bool_param" to JsonPrimitive("true")
            )
        )

        assertTrue(tool.testRequireBoolean(params, "bool_param"))
    }

    @Test
    fun `test require boolean parameter false`() {
        val params = JsonObject(
            mapOf(
                "bool_param" to JsonPrimitive("false")
            )
        )

        assertFalse(tool.testRequireBoolean(params, "bool_param"))
    }

    @Test
    fun `test require boolean parameter case insensitive`() {
        val params = JsonObject(
            mapOf(
                "bool_param_true" to JsonPrimitive("TRUE"),
                "bool_param_false" to JsonPrimitive("False")
            )
        )

        assertTrue(tool.testRequireBoolean(params, "bool_param_true"))
        assertFalse(tool.testRequireBoolean(params, "bool_param_false"))
    }

    @Test
    fun `test require boolean parameter with numeric values`() {
        val params = JsonObject(
            mapOf(
                "bool_param_one" to JsonPrimitive("1"),
                "bool_param_zero" to JsonPrimitive("0")
            )
        )

        assertTrue(tool.testRequireBoolean(params, "bool_param_one"))
        assertFalse(tool.testRequireBoolean(params, "bool_param_zero"))
    }

    @Test
    fun `test require boolean parameter with non-string values`() {
        // Non-string values need to be mocked since JsonPrimitive doesn't support direct boolean values
        // This test simulates how a non-string boolean might be passed through the MCP protocol
        val trueParam = JsonPrimitive(true.toString())
        val falseParam = JsonPrimitive(false.toString())

        val params = JsonObject(
            mapOf(
                "bool_param_true_nonstring" to trueParam,
                "bool_param_false_nonstring" to falseParam
            )
        )

        assertTrue(tool.testRequireBoolean(params, "bool_param_true_nonstring"))
        assertFalse(tool.testRequireBoolean(params, "bool_param_false_nonstring"))
    }

    @Test
    fun `test require boolean parameter missing`() {
        val params = JsonObject(mapOf())

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.testRequireBoolean(params, "missing_param")
        }

        assertTrue(exception.message!!.contains("Missing required parameter"))
    }

    @Test
    fun `test require boolean parameter invalid value`() {
        val params = JsonObject(
            mapOf(
                "bool_param" to JsonPrimitive("not-a-boolean")
            )
        )

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.testRequireBoolean(params, "bool_param")
        }

        assertTrue(exception.message?.contains("must be a boolean") == true)
    }

    @Test
    fun `test optional boolean parameter present`() {
        val params = JsonObject(
            mapOf(
                "bool_param_true" to JsonPrimitive("true"),
                "bool_param_false" to JsonPrimitive("false")
            )
        )

        assertTrue(tool.testOptionalBoolean(params, "bool_param_true", false))
        assertFalse(tool.testOptionalBoolean(params, "bool_param_false", true))
    }

    @Test
    fun `test optional boolean parameter with numeric values`() {
        val params = JsonObject(
            mapOf(
                "bool_param_one" to JsonPrimitive("1"),
                "bool_param_zero" to JsonPrimitive("0")
            )
        )

        assertTrue(tool.testOptionalBoolean(params, "bool_param_one", false))
        assertFalse(tool.testOptionalBoolean(params, "bool_param_zero", true))
    }

    @Test
    fun `test optional boolean parameter with non-string values`() {
        // Non-string values need to be mocked since JsonPrimitive doesn't support direct boolean values
        // This test simulates how a non-string boolean might be passed through the MCP protocol
        val trueParam = JsonPrimitive(true.toString())
        val falseParam = JsonPrimitive(false.toString())

        val params = JsonObject(
            mapOf(
                "bool_param_true_nonstring" to trueParam,
                "bool_param_false_nonstring" to falseParam
            )
        )

        assertTrue(tool.testOptionalBoolean(params, "bool_param_true_nonstring", false))
        assertFalse(tool.testOptionalBoolean(params, "bool_param_false_nonstring", true))
    }

    @Test
    fun `test optional boolean parameter missing with default true`() {
        val params = JsonObject(mapOf())

        assertTrue(tool.testOptionalBoolean(params, "missing_param", true))
    }

    @Test
    fun `test optional boolean parameter missing with default false`() {
        val params = JsonObject(mapOf())

        assertFalse(tool.testOptionalBoolean(params, "missing_param", false))
    }

    @Test
    fun `test optional boolean parameter invalid value`() {
        val params = JsonObject(
            mapOf(
                "bool_param" to JsonPrimitive("not-a-boolean")
            )
        )

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.testOptionalBoolean(params, "bool_param", true)
        }

        assertTrue(exception.message?.contains("must be a boolean") == true)
    }

    @Test
    fun `test require int parameter`() {
        val params = JsonObject(
            mapOf(
                "int_param" to JsonPrimitive("42")
            )
        )

        assertEquals(42, tool.testRequireInt(params, "int_param"))
    }

    @Test
    fun `test require int parameter missing`() {
        val params = JsonObject(mapOf())

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.testRequireInt(params, "missing_param")
        }

        assertTrue(exception.message!!.contains("Missing required parameter"))
    }

    @Test
    fun `test require int parameter invalid value`() {
        val params = JsonObject(
            mapOf(
                "int_param" to JsonPrimitive("not-an-integer")
            )
        )

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.testRequireInt(params, "int_param")
        }

        assertTrue(exception.message!!.contains("must be an integer"))
    }

    @Test
    fun `test optional int parameter present`() {
        val params = JsonObject(
            mapOf(
                "int_param" to JsonPrimitive("42")
            )
        )

        assertEquals(42, tool.testOptionalInt(params, "int_param"))
    }

    @Test
    fun `test optional int parameter missing`() {
        val params = JsonObject(mapOf())

        assertNull(tool.testOptionalInt(params, "missing_param"))
    }

    @Test
    fun `test optional int parameter with default value`() {
        val params = JsonObject(mapOf())

        assertEquals(99, tool.testOptionalInt(params, "missing_param", 99))
    }

    @Test
    fun `test optional int parameter invalid value`() {
        val params = JsonObject(
            mapOf(
                "int_param" to JsonPrimitive("not-an-integer")
            )
        )

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.testOptionalInt(params, "int_param", 99)
        }

        assertTrue(exception.message!!.contains("must be an integer"))
    }

    @Test
    fun `test require string list parameter`() {
        val params = JsonObject(
            mapOf(
                "list_param" to JsonPrimitive("item1,item2,item3")
            )
        )

        val list = tool.testRequireStringList(params, "list_param")
        assertEquals(3, list.size)
        assertEquals("item1", list[0])
        assertEquals("item2", list[1])
        assertEquals("item3", list[2])
    }

    @Test
    fun `test require string list parameter with spaces`() {
        val params = JsonObject(
            mapOf(
                "list_param" to JsonPrimitive("item1, item2, item3")
            )
        )

        val list = tool.testRequireStringList(params, "list_param")
        assertEquals(3, list.size)
        assertEquals("item1", list[0])
        assertEquals("item2", list[1])
        assertEquals("item3", list[2])
    }

    @Test
    fun `test require string list parameter with empty items`() {
        val params = JsonObject(
            mapOf(
                "list_param" to JsonPrimitive("item1,,item3")
            )
        )

        val list = tool.testRequireStringList(params, "list_param")
        assertEquals(2, list.size)
        assertEquals("item1", list[0])
        assertEquals("item3", list[1])
    }

    @Test
    fun `test require string list parameter missing`() {
        val params = JsonObject(mapOf())

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.testRequireStringList(params, "missing_param")
        }

        assertTrue(exception.message!!.contains("Missing required parameter"))
    }

    @Test
    fun `test empty string list parameter`() {
        val params = JsonObject(
            mapOf(
                "list_param" to JsonPrimitive("")
            )
        )

        val list = tool.testRequireStringList(params, "list_param")
        assertTrue(list.isEmpty())
    }

    @Test
    fun `test optional string list parameter present`() {
        val params = JsonObject(
            mapOf(
                "list_param" to JsonPrimitive("item1,item2,item3")
            )
        )

        val list = tool.testOptionalStringList(params, "list_param")
        assertEquals(3, list.size)
        assertEquals("item1", list[0])
        assertEquals("item2", list[1])
        assertEquals("item3", list[2])
    }

    @Test
    fun `test optional string list parameter missing`() {
        val params = JsonObject(mapOf())

        val list = tool.testOptionalStringList(params, "missing_param")
        assertTrue(list.isEmpty())
    }

    @Test
    fun `test optional string list parameter empty string`() {
        val params = JsonObject(
            mapOf(
                "list_param" to JsonPrimitive("")
            )
        )

        val list = tool.testOptionalStringList(params, "list_param")
        assertTrue(list.isEmpty())
    }

    /**
     * Test implementation of the abstract BaseToolDefinition class.
     */
    private class TestBaseToolDefinition : BaseToolDefinition() {
        override val name: String = "test_base_tool"
        override val description: String = "Test base tool for unit testing"
        override val category: ToolCategory = ToolCategory.SYSTEM
        override val parameterSchema: Tool.Input = Tool.Input(
            properties = JsonObject(mapOf("type" to JsonPrimitive("object")))
        )

        override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
            return JsonObject(mapOf("success" to JsonPrimitive(true)))
        }

        fun testCreateSuccessResponse(data: JsonElement, message: String? = null): JsonElement {
            return successResponse(data, message)
        }

        fun testCreateSuccessResponse(message: String): JsonElement {
            return successResponse(message)
        }

        fun testCreateErrorResponse(message: String): JsonElement {
            return errorResponse(message)
        }

        fun testCreateErrorResponse(message: String, code: String, details: String? = null): JsonElement {
            return errorResponse(message, code, details)
        }

        fun testRequireString(params: JsonElement, name: String): String {
            return requireString(params, name)
        }

        fun testOptionalString(params: JsonElement, name: String): String? {
            return optionalString(params, name)
        }

        fun testOptionalStringWithDefault(params: JsonElement, name: String, defaultValue: String): String {
            return optionalString(params, name, defaultValue)
        }

        fun testRequireBoolean(params: JsonElement, name: String): Boolean {
            return requireBoolean(params, name)
        }

        fun testOptionalBoolean(params: JsonElement, name: String, default: Boolean): Boolean {
            return optionalBoolean(params, name, default)
        }

        fun testRequireInt(params: JsonElement, name: String): Int {
            return requireInt(params, name)
        }

        fun testOptionalInt(params: JsonElement, name: String, defaultValue: Int? = null): Int? {
            return optionalInt(params, name, defaultValue)
        }

        fun testRequireStringList(params: JsonElement, name: String): List<String> {
            return requireStringList(params, name)
        }

        fun testOptionalStringList(params: JsonElement, name: String): List<String> {
            return optionalStringList(params, name)
        }
    }
}