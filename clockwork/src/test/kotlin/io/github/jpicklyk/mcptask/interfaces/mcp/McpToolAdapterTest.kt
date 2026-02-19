package io.github.jpicklyk.mcptask.interfaces.mcp

import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class McpToolAdapterTest {

    @Test
    @DisplayName("Test basic boolean parameter preprocessing")
    fun `test preprocessParameters handles boolean values correctly`() {
        val adapter = McpToolAdapter()

        // We need to access the private method using Java reflection
        val method = adapter.javaClass.getDeclaredMethod("preprocessParameters", JsonElement::class.java)
        method.isAccessible = true

        val params = buildJsonObject {
            put("stringTrue", JsonPrimitive("true"))
            put("stringFalse", JsonPrimitive("false"))
            put("numeric1", JsonPrimitive("1"))
            put("numeric0", JsonPrimitive("0"))
            put("normalString", JsonPrimitive("hello"))
            put("nestedObject", buildJsonObject {
                put("innerBool", JsonPrimitive("true"))
            })
        }

        val result = method.invoke(adapter, params) as JsonObject

        // Verify string booleans were converted
        assertEquals("true", result["stringTrue"]?.jsonPrimitive?.content)
        assertEquals("false", result["stringFalse"]?.jsonPrimitive?.content)
        assertEquals("true", result["numeric1"]?.jsonPrimitive?.content)
        assertEquals("false", result["numeric0"]?.jsonPrimitive?.content)

        // Verify other types weren't affected
        assertEquals("hello", result["normalString"]?.jsonPrimitive?.content)

        // Verify nested objects are preserved
        val nestedObj = result["nestedObject"] as JsonObject
        assertEquals("true", nestedObj["innerBool"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("Test parameter preprocessing with mixed case boolean values")
    fun `test preprocessParameters handles mixed case boolean values`() {
        val adapter = McpToolAdapter()
        val method = adapter.javaClass.getDeclaredMethod("preprocessParameters", JsonElement::class.java)
        method.isAccessible = true

        val params = buildJsonObject {
            put("upperTrue", JsonPrimitive("TRUE"))
            put("mixedFalse", JsonPrimitive("FaLsE"))
            put("titleTrue", JsonPrimitive("True"))
        }

        val result = method.invoke(adapter, params) as JsonObject

        // Verify mixed-case booleans were converted correctly
        assertEquals("true", result["upperTrue"]?.jsonPrimitive?.content)
        assertEquals("false", result["mixedFalse"]?.jsonPrimitive?.content)
        assertEquals("true", result["titleTrue"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("Test parameter preprocessing with empty and null values")
    fun `test preprocessParameters handles empty and null values`() {
        val adapter = McpToolAdapter()
        val method = adapter.javaClass.getDeclaredMethod("preprocessParameters", JsonElement::class.java)
        method.isAccessible = true

        val params = buildJsonObject {
            put("emptyString", JsonPrimitive(""))
            put("nullValue", JsonNull)
        }

        val result = method.invoke(adapter, params) as JsonObject

        // Verify empty string and null values are preserved
        assertEquals("", result["emptyString"]?.jsonPrimitive?.content)
        assertTrue(result.containsKey("nullValue"))
        assertTrue(result["nullValue"] is JsonNull)
    }

    @Test
    @DisplayName("Test parameter preprocessing with non-object input")
    fun `test preprocessParameters handles non-object input`() {
        val adapter = McpToolAdapter()
        val method = adapter.javaClass.getDeclaredMethod("preprocessParameters", JsonElement::class.java)
        method.isAccessible = true

        // Test with a non-object input (JsonArray)
        val array = buildJsonArray {
            add(JsonPrimitive("value1"))
            add(JsonPrimitive("true"))
        }

        val result = method.invoke(adapter, array)

        // Verify non-object inputs are returned unchanged
        assertEquals(array, result)
    }

    @Test
    @DisplayName("Test detailed error message generation")
    fun `test detailed error message generation`() {
        val adapter = McpToolAdapter()
        val method = adapter.javaClass.getDeclaredMethod(
            "buildDetailedErrorMessage",
            String::class.java,
            ToolValidationException::class.java
        )
        method.isAccessible = true

        // Test boolean error message
        val booleanError = ToolValidationException("Parameter isActive must be a boolean")
        val booleanMessage = method.invoke(adapter, "testTool", booleanError) as String

        assertTrue(booleanMessage.contains("Parameter validation error in tool 'testTool'"))
        assertTrue(booleanMessage.contains("Boolean parameters accept values: true, false, 1, 0"))
        assertTrue(booleanMessage.contains("String representations are also supported"))

        // Test integer error message
        val integerError = ToolValidationException("Parameter count must be an integer")
        val integerMessage = method.invoke(adapter, "testTool", integerError) as String

        assertTrue(integerMessage.contains("Parameter validation error in tool 'testTool'"))
        assertTrue(integerMessage.contains("Integer parameters must be valid numbers"))

        // Test missing parameter error message
        val missingParamError = ToolValidationException("Missing required parameter: name")
        val missingParamMessage = method.invoke(adapter, "testTool", missingParamError) as String

        assertTrue(missingParamMessage.contains("Parameter validation error in tool 'testTool'"))
        assertTrue(missingParamMessage.contains("All required parameters must be provided"))
    }
}