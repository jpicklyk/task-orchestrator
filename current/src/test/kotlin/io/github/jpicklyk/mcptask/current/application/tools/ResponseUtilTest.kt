package io.github.jpicklyk.mcptask.current.application.tools

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponseUtilTest {

    // ────────────────────────────────────────────────────────
    // createSuccessResponse
    // ────────────────────────────────────────────────────────

    @Test
    fun `createSuccessResponse with data and message includes both`() {
        val data = buildJsonObject { put("id", JsonPrimitive("abc-123")) }
        val response = ResponseUtil.createSuccessResponse(data = data, message = "Item created")

        assertTrue(response["success"]!!.jsonPrimitive.boolean)
        assertEquals("Item created", response["message"]!!.jsonPrimitive.content)
        assertEquals("abc-123", response["data"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertNotNull(response["metadata"])
    }

    @Test
    fun `createSuccessResponse with data only omits message`() {
        val data = buildJsonObject { put("count", JsonPrimitive(10)) }
        val response = ResponseUtil.createSuccessResponse(data = data)

        assertTrue(response["success"]!!.jsonPrimitive.boolean)
        assertNull(response["message"])
        assertNotNull(response["data"])
        assertNotNull(response["metadata"])
    }

    @Test
    fun `createSuccessResponse with message only omits data`() {
        val response = ResponseUtil.createSuccessResponse(message = "All good")

        assertTrue(response["success"]!!.jsonPrimitive.boolean)
        assertEquals("All good", response["message"]!!.jsonPrimitive.content)
        assertNull(response["data"])
        assertNotNull(response["metadata"])
    }

    @Test
    fun `createSuccessResponse with neither data nor message is valid envelope`() {
        val response = ResponseUtil.createSuccessResponse()

        assertTrue(response["success"]!!.jsonPrimitive.boolean)
        assertNull(response["message"])
        assertNull(response["data"])
        assertNotNull(response["metadata"])
    }

    // ────────────────────────────────────────────────────────
    // createErrorResponse
    // ────────────────────────────────────────────────────────

    @Test
    fun `createErrorResponse with all fields`() {
        val extraData = buildJsonObject { put("field", JsonPrimitive("title")) }
        val response = ResponseUtil.createErrorResponse(
            message = "Validation failed",
            code = ErrorCodes.VALIDATION_ERROR,
            details = "Title is required",
            additionalData = extraData
        )

        assertFalse(response["success"]!!.jsonPrimitive.boolean)
        val error = response["error"]!!.jsonObject
        assertEquals("Validation failed", error["message"]!!.jsonPrimitive.content)
        assertEquals(ErrorCodes.VALIDATION_ERROR, error["code"]!!.jsonPrimitive.content)
        assertEquals("Title is required", error["details"]!!.jsonPrimitive.content)
        assertEquals("title", response["data"]!!.jsonObject["field"]!!.jsonPrimitive.content)
        assertNotNull(response["metadata"])
    }

    @Test
    fun `createErrorResponse with message and code only omits details and data`() {
        val response = ResponseUtil.createErrorResponse(
            message = "Not found",
            code = ErrorCodes.RESOURCE_NOT_FOUND
        )

        assertFalse(response["success"]!!.jsonPrimitive.boolean)
        val error = response["error"]!!.jsonObject
        assertEquals("Not found", error["message"]!!.jsonPrimitive.content)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error["code"]!!.jsonPrimitive.content)
        assertNull(error["details"])
        assertNull(response["data"])
    }

    @Test
    fun `createErrorResponse defaults code to VALIDATION_ERROR`() {
        val response = ResponseUtil.createErrorResponse(message = "Bad input")

        val error = response["error"]!!.jsonObject
        assertEquals(ErrorCodes.VALIDATION_ERROR, error["code"]!!.jsonPrimitive.content)
    }

    // ────────────────────────────────────────────────────────
    // isErrorResponse
    // ────────────────────────────────────────────────────────

    @Test
    fun `isErrorResponse returns true for error response`() {
        val response = ResponseUtil.createErrorResponse("fail")
        assertTrue(ResponseUtil.isErrorResponse(response))
    }

    @Test
    fun `isErrorResponse returns false for success response`() {
        val response = ResponseUtil.createSuccessResponse(message = "ok")
        assertFalse(ResponseUtil.isErrorResponse(response))
    }

    @Test
    fun `isErrorResponse returns false for non-object input`() {
        assertFalse(ResponseUtil.isErrorResponse(JsonPrimitive("text")))
    }

    @Test
    fun `isErrorResponse returns false for object without success field`() {
        val obj = buildJsonObject { put("foo", JsonPrimitive("bar")) }
        assertFalse(ResponseUtil.isErrorResponse(obj))
    }

    // ────────────────────────────────────────────────────────
    // extractDataPayload
    // ────────────────────────────────────────────────────────

    @Test
    fun `extractDataPayload returns data from success response`() {
        val data = buildJsonObject { put("id", JsonPrimitive("123")) }
        val response = ResponseUtil.createSuccessResponse(data = data)

        val extracted = ResponseUtil.extractDataPayload(response)
        assertNotNull(extracted)
        assertEquals("123", extracted.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `extractDataPayload returns null when data is absent`() {
        val response = ResponseUtil.createSuccessResponse(message = "no data")
        assertNull(ResponseUtil.extractDataPayload(response))
    }

    @Test
    fun `extractDataPayload returns null for non-object input`() {
        assertNull(ResponseUtil.extractDataPayload(JsonPrimitive("text")))
    }

    @Test
    fun `extractDataPayload returns null for JsonArray input`() {
        assertNull(ResponseUtil.extractDataPayload(JsonArray(listOf(JsonPrimitive(1)))))
    }

    // ────────────────────────────────────────────────────────
    // createMetadata
    // ────────────────────────────────────────────────────────

    @Test
    fun `createMetadata includes timestamp and version`() {
        val metadata = ResponseUtil.createMetadata()

        assertNotNull(metadata["timestamp"])
        val timestamp = metadata["timestamp"]!!.jsonPrimitive.content
        // Verify it looks like an ISO 8601 timestamp (starts with a year)
        assertTrue(timestamp.matches(Regex("^\\d{4}-\\d{2}-\\d{2}T.*")))

        assertNotNull(metadata["version"])
        assertEquals("0.1.0", metadata["version"]!!.jsonPrimitive.content)
    }
}
