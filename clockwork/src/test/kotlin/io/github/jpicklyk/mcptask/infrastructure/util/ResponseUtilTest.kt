package io.github.jpicklyk.mcptask.infrastructure.util

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ResponseUtilTest {

    @Test
    fun `extractDataPayload returns data object from success envelope`() {
        val envelope = buildJsonObject {
            put("success", true)
            put("message", "OK")
            put("data", buildJsonObject {
                put("id", "abc-123")
                put("name", "Test")
            })
            put("error", JsonNull)
        }
        val result = ResponseUtil.extractDataPayload(envelope)
        assertEquals("abc-123", result["id"]?.jsonPrimitive?.content)
        assertEquals("Test", result["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `extractDataPayload returns empty object when data is null and no error info`() {
        val envelope = buildJsonObject {
            put("success", true)
            put("data", JsonNull)
        }
        val result = ResponseUtil.extractDataPayload(envelope)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extractDataPayload returns empty object when data key is missing and no error info`() {
        val envelope = buildJsonObject {
            put("success", true)
        }
        val result = ResponseUtil.extractDataPayload(envelope)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extractDataPayload includes error details when data is null on error response`() {
        val envelope = buildJsonObject {
            put("success", false)
            put("message", "Task not found with ID 12345")
            put("data", JsonNull)
            putJsonObject("error") {
                put("code", "NOT_FOUND")
                put("details", "Task not found with ID 12345")
            }
        }
        val result = ResponseUtil.extractDataPayload(envelope)
        assertEquals("Task not found with ID 12345", result["message"]?.jsonPrimitive?.content)
        val error = result["error"]?.jsonObject
        assertNotNull(error)
        assertEquals("NOT_FOUND", error?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `extractDataPayload includes message even without error object`() {
        val envelope = buildJsonObject {
            put("success", false)
            put("message", "Validation failed: missing required field")
            put("data", JsonNull)
        }
        val result = ResponseUtil.extractDataPayload(envelope)
        assertEquals("Validation failed: missing required field", result["message"]?.jsonPrimitive?.content)
        assertNull(result["error"])
    }

    @Test
    fun `extractDataPayload wraps non-object data in value key`() {
        val envelope = buildJsonObject {
            put("success", true)
            put("data", JsonPrimitive("raw-string"))
        }
        val result = ResponseUtil.extractDataPayload(envelope)
        assertEquals("raw-string", result["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `isErrorResponse returns true for error envelope`() {
        val envelope = buildJsonObject {
            put("success", false)
            put("message", "Not found")
        }
        assertTrue(ResponseUtil.isErrorResponse(envelope))
    }

    @Test
    fun `isErrorResponse returns false for success envelope`() {
        val envelope = buildJsonObject {
            put("success", true)
            put("message", "OK")
        }
        assertFalse(ResponseUtil.isErrorResponse(envelope))
    }

    @Test
    fun `isErrorResponse returns false when success key missing`() {
        val envelope = buildJsonObject {
            put("message", "OK")
        }
        assertFalse(ResponseUtil.isErrorResponse(envelope))
    }

    @Test
    fun `createSuccessResponse includes all standard fields`() {
        val data = buildJsonObject { put("id", "test") }
        val response = ResponseUtil.createSuccessResponse("Test message", data)
        assertTrue(response["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("Test message", response["message"]?.jsonPrimitive?.content)
        assertNotNull(response["data"])
        assertNotNull(response["metadata"])
    }

    @Test
    fun `createErrorResponse includes error details`() {
        val response = ResponseUtil.createErrorResponse("Bad input", ErrorCodes.VALIDATION_ERROR, "details here")
        assertTrue(response["success"]?.jsonPrimitive?.boolean == false)
        assertEquals("Bad input", response["message"]?.jsonPrimitive?.content)
        val error = response["error"]?.jsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.VALIDATION_ERROR, error?.get("code")?.jsonPrimitive?.content)
    }
}
