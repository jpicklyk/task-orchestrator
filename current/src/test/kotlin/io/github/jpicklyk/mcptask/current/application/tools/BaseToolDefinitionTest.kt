package io.github.jpicklyk.mcptask.current.application.tools

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class BaseToolDefinitionTest {

    /**
     * Concrete test subclass that exposes protected BaseToolDefinition methods for testing.
     */
    private val tool = object : BaseToolDefinition() {
        override val name = "test_tool"
        override val description = "Test tool for unit testing BaseToolDefinition helpers"
        override val parameterSchema = ToolSchema(
            properties = buildJsonObject {},
            required = emptyList()
        )
        override val category = ToolCategory.SYSTEM
        override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement = JsonNull

        // Expose protected methods for testing
        fun testRequireString(params: JsonElement, name: String) = requireString(params, name)
        fun testOptionalString(params: JsonElement, name: String) = optionalString(params, name)
        fun testOptionalStringDefault(params: JsonElement, name: String, default: String) = optionalString(params, name, default)
        fun testOptionalBoolean(params: JsonElement, name: String, default: Boolean = false) = optionalBoolean(params, name, default)
        fun testRequireInt(params: JsonElement, name: String) = requireInt(params, name)
        fun testOptionalInt(params: JsonElement, name: String, default: Int? = null) = optionalInt(params, name, default)
        fun testOptionalJsonArray(params: JsonElement, name: String) = optionalJsonArray(params, name)
        fun testRequireJsonArray(params: JsonElement, name: String) = requireJsonArray(params, name)
        fun testExtractUUID(params: JsonElement, name: String, required: Boolean = true) = extractUUID(params, name, required)
        fun testParseInstant(params: JsonElement, name: String) = parseInstant(params, name)
        fun testShortId(uuid: String) = shortId(uuid)
        fun testSuccessResponseData(data: JsonElement, msg: String? = null) = successResponse(data, msg)
        fun testSuccessResponseMsg(msg: String) = successResponse(msg)
        fun testErrorResponse(msg: String, code: String = ErrorCodes.VALIDATION_ERROR) = errorResponse(msg, code)
    }

    /** Helper to build a JsonObject from pairs. */
    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    // ────────────────────────────────────────────────────────
    // String extraction — requireString
    // ────────────────────────────────────────────────────────

    @Test
    fun `requireString returns value when present`() {
        val p = params("name" to JsonPrimitive("hello"))
        assertEquals("hello", tool.testRequireString(p, "name"))
    }

    @Test
    fun `requireString throws on missing parameter`() {
        val p = params()
        val ex = assertFailsWith<ToolValidationException> {
            tool.testRequireString(p, "name")
        }
        assertTrue(ex.message!!.contains("name"))
    }

    @Test
    fun `requireString throws on blank value`() {
        val p = params("name" to JsonPrimitive("   "))
        assertFailsWith<ToolValidationException> {
            tool.testRequireString(p, "name")
        }
    }

    @Test
    fun `requireString throws on non-string type`() {
        val p = params("name" to JsonPrimitive(42))
        assertFailsWith<ToolValidationException> {
            tool.testRequireString(p, "name")
        }
    }

    @Test
    fun `requireString throws when params is not a JsonObject`() {
        assertFailsWith<ToolValidationException> {
            tool.testRequireString(JsonPrimitive("not an object"), "name")
        }
    }

    // ────────────────────────────────────────────────────────
    // String extraction — optionalString (nullable)
    // ────────────────────────────────────────────────────────

    @Test
    fun `optionalString returns value when present`() {
        val p = params("name" to JsonPrimitive("hello"))
        assertEquals("hello", tool.testOptionalString(p, "name"))
    }

    @Test
    fun `optionalString returns null on missing parameter`() {
        val p = params()
        assertNull(tool.testOptionalString(p, "name"))
    }

    @Test
    fun `optionalString returns null on blank value`() {
        val p = params("name" to JsonPrimitive("   "))
        assertNull(tool.testOptionalString(p, "name"))
    }

    @Test
    fun `optionalString throws on non-string type`() {
        val p = params("name" to JsonPrimitive(42))
        assertFailsWith<ToolValidationException> {
            tool.testOptionalString(p, "name")
        }
    }

    // ────────────────────────────────────────────────────────
    // String extraction — optionalString with default
    // ────────────────────────────────────────────────────────

    @Test
    fun `optionalString with default returns value when present`() {
        val p = params("name" to JsonPrimitive("actual"))
        assertEquals("actual", tool.testOptionalStringDefault(p, "name", "fallback"))
    }

    @Test
    fun `optionalString with default returns default on missing parameter`() {
        val p = params()
        assertEquals("fallback", tool.testOptionalStringDefault(p, "name", "fallback"))
    }

    @Test
    fun `optionalString with default returns blank content when present and blank`() {
        // Note: optionalString with default does NOT filter blanks — it returns content as-is
        val p = params("name" to JsonPrimitive("  "))
        assertEquals("  ", tool.testOptionalStringDefault(p, "name", "fallback"))
    }

    // ────────────────────────────────────────────────────────
    // Boolean extraction — optionalBoolean
    // ────────────────────────────────────────────────────────

    @Test
    fun `optionalBoolean parses JSON boolean true`() {
        val p = params("flag" to JsonPrimitive(true))
        assertTrue(tool.testOptionalBoolean(p, "flag"))
    }

    @Test
    fun `optionalBoolean parses JSON boolean false`() {
        val p = params("flag" to JsonPrimitive(false))
        assertFalse(tool.testOptionalBoolean(p, "flag"))
    }

    @Test
    fun `optionalBoolean parses string true case-insensitive`() {
        val p = params("flag" to JsonPrimitive("true"))
        assertTrue(tool.testOptionalBoolean(p, "flag"))
    }

    @Test
    fun `optionalBoolean parses string TRUE case-insensitive`() {
        val p = params("flag" to JsonPrimitive("TRUE"))
        assertTrue(tool.testOptionalBoolean(p, "flag"))
    }

    @Test
    fun `optionalBoolean parses string false case-insensitive`() {
        val p = params("flag" to JsonPrimitive("false"))
        assertFalse(tool.testOptionalBoolean(p, "flag"))
    }

    @Test
    fun `optionalBoolean parses string FALSE case-insensitive`() {
        val p = params("flag" to JsonPrimitive("FALSE"))
        assertFalse(tool.testOptionalBoolean(p, "flag"))
    }

    @Test
    fun `optionalBoolean parses numeric string 1 as true`() {
        val p = params("flag" to JsonPrimitive("1"))
        assertTrue(tool.testOptionalBoolean(p, "flag"))
    }

    @Test
    fun `optionalBoolean parses numeric string 0 as false`() {
        val p = params("flag" to JsonPrimitive("0"))
        assertFalse(tool.testOptionalBoolean(p, "flag"))
    }

    @Test
    fun `optionalBoolean returns default on missing parameter`() {
        val p = params()
        assertFalse(tool.testOptionalBoolean(p, "flag", false))
        assertTrue(tool.testOptionalBoolean(p, "flag", true))
    }

    @Test
    fun `optionalBoolean throws on unparseable string`() {
        val p = params("flag" to JsonPrimitive("maybe"))
        assertFailsWith<ToolValidationException> {
            tool.testOptionalBoolean(p, "flag")
        }
    }

    // ────────────────────────────────────────────────────────
    // Integer extraction — requireInt
    // ────────────────────────────────────────────────────────

    @Test
    fun `requireInt returns value from numeric primitive`() {
        val p = params("count" to JsonPrimitive(42))
        assertEquals(42, tool.testRequireInt(p, "count"))
    }

    @Test
    fun `requireInt parses string integer`() {
        val p = params("count" to JsonPrimitive("42"))
        assertEquals(42, tool.testRequireInt(p, "count"))
    }

    @Test
    fun `requireInt throws on missing parameter`() {
        val p = params()
        assertFailsWith<ToolValidationException> {
            tool.testRequireInt(p, "count")
        }
    }

    @Test
    fun `requireInt throws on non-numeric string`() {
        val p = params("count" to JsonPrimitive("abc"))
        assertFailsWith<ToolValidationException> {
            tool.testRequireInt(p, "count")
        }
    }

    // ────────────────────────────────────────────────────────
    // Integer extraction — optionalInt
    // ────────────────────────────────────────────────────────

    @Test
    fun `optionalInt returns value when present`() {
        val p = params("count" to JsonPrimitive(7))
        assertEquals(7, tool.testOptionalInt(p, "count"))
    }

    @Test
    fun `optionalInt returns null on missing parameter with null default`() {
        val p = params()
        assertNull(tool.testOptionalInt(p, "count"))
    }

    @Test
    fun `optionalInt returns default value on missing parameter`() {
        val p = params()
        assertEquals(10, tool.testOptionalInt(p, "count", 10))
    }

    @Test
    fun `optionalInt throws on non-numeric string`() {
        val p = params("count" to JsonPrimitive("xyz"))
        assertFailsWith<ToolValidationException> {
            tool.testOptionalInt(p, "count")
        }
    }

    // ────────────────────────────────────────────────────────
    // JSON array extraction
    // ────────────────────────────────────────────────────────

    @Test
    fun `requireJsonArray returns array when present`() {
        val array = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        val p = params("items" to array)
        val result = tool.testRequireJsonArray(p, "items")
        assertEquals(2, result.size)
        assertEquals("a", result[0].jsonPrimitive.content)
    }

    @Test
    fun `requireJsonArray throws on missing parameter`() {
        val p = params()
        assertFailsWith<ToolValidationException> {
            tool.testRequireJsonArray(p, "items")
        }
    }

    @Test
    fun `requireJsonArray throws on non-array type`() {
        val p = params("items" to JsonPrimitive("not an array"))
        assertFailsWith<ToolValidationException> {
            tool.testRequireJsonArray(p, "items")
        }
    }

    @Test
    fun `optionalJsonArray returns array when present`() {
        val array = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))
        val p = params("items" to array)
        val result = tool.testOptionalJsonArray(p, "items")
        assertNotNull(result)
        assertEquals(2, result.size)
    }

    @Test
    fun `optionalJsonArray returns null on missing parameter`() {
        val p = params()
        assertNull(tool.testOptionalJsonArray(p, "items"))
    }

    @Test
    fun `optionalJsonArray throws on non-array type`() {
        val p = params("items" to JsonPrimitive("not an array"))
        assertFailsWith<ToolValidationException> {
            tool.testOptionalJsonArray(p, "items")
        }
    }

    // ────────────────────────────────────────────────────────
    // UUID extraction
    // ────────────────────────────────────────────────────────

    @Test
    fun `extractUUID parses valid UUID string`() {
        val uuid = UUID.randomUUID()
        val p = params("id" to JsonPrimitive(uuid.toString()))
        assertEquals(uuid, tool.testExtractUUID(p, "id"))
    }

    @Test
    fun `extractUUID throws on invalid UUID format`() {
        val p = params("id" to JsonPrimitive("not-a-uuid"))
        assertFailsWith<ToolValidationException> {
            tool.testExtractUUID(p, "id")
        }
    }

    @Test
    fun `extractUUID returns null when not required and missing`() {
        val p = params()
        assertNull(tool.testExtractUUID(p, "id", required = false))
    }

    @Test
    fun `extractUUID throws when required and missing`() {
        val p = params()
        assertFailsWith<ToolValidationException> {
            tool.testExtractUUID(p, "id", required = true)
        }
    }

    @Test
    fun `extractUUID returns null when not required and blank`() {
        val p = params("id" to JsonPrimitive(""))
        assertNull(tool.testExtractUUID(p, "id", required = false))
    }

    @Test
    fun `extractUUID throws when required and blank`() {
        val p = params("id" to JsonPrimitive(""))
        assertFailsWith<ToolValidationException> {
            tool.testExtractUUID(p, "id", required = true)
        }
    }

    @Test
    fun `extractUUID throws on non-string type`() {
        val p = params("id" to JsonPrimitive(12345))
        assertFailsWith<ToolValidationException> {
            tool.testExtractUUID(p, "id")
        }
    }

    // ────────────────────────────────────────────────────────
    // Instant parsing
    // ────────────────────────────────────────────────────────

    @Test
    fun `parseInstant parses valid ISO 8601 timestamp`() {
        val p = params("ts" to JsonPrimitive("2024-01-15T10:30:00Z"))
        val result = tool.testParseInstant(p, "ts")
        assertNotNull(result)
        assertEquals(Instant.parse("2024-01-15T10:30:00Z"), result)
    }

    @Test
    fun `parseInstant returns null on missing parameter`() {
        val p = params()
        assertNull(tool.testParseInstant(p, "ts"))
    }

    @Test
    fun `parseInstant returns null on blank value`() {
        val p = params("ts" to JsonPrimitive(""))
        assertNull(tool.testParseInstant(p, "ts"))
    }

    @Test
    fun `parseInstant throws on invalid timestamp format`() {
        val p = params("ts" to JsonPrimitive("not-a-date"))
        assertFailsWith<ToolValidationException> {
            tool.testParseInstant(p, "ts")
        }
    }

    @Test
    fun `parseInstant throws on non-string type`() {
        val p = params("ts" to JsonPrimitive(1234567890))
        assertFailsWith<ToolValidationException> {
            tool.testParseInstant(p, "ts")
        }
    }

    // ────────────────────────────────────────────────────────
    // Utility — shortId
    // ────────────────────────────────────────────────────────

    @Test
    fun `shortId truncates UUID to first 8 characters`() {
        val full = "12345678-abcd-efgh-ijkl-mnopqrstuvwx"
        assertEquals("12345678", tool.testShortId(full))
    }

    @Test
    fun `shortId handles string shorter than 8 characters`() {
        assertEquals("abc", tool.testShortId("abc"))
    }

    // ────────────────────────────────────────────────────────
    // Response helpers — successResponse
    // ────────────────────────────────────────────────────────

    @Test
    fun `successResponse with data returns success envelope`() {
        val data = buildJsonObject { put("count", JsonPrimitive(5)) }
        val response = tool.testSuccessResponseData(data)

        assertTrue(response["success"]!!.jsonPrimitive.boolean)
        assertEquals(5, response["data"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertNotNull(response["metadata"])
    }

    @Test
    fun `successResponse with data and message includes both`() {
        val data = buildJsonObject { put("id", JsonPrimitive("abc")) }
        val response = tool.testSuccessResponseData(data, "Created successfully")

        assertTrue(response["success"]!!.jsonPrimitive.boolean)
        assertEquals("Created successfully", response["message"]!!.jsonPrimitive.content)
        assertNotNull(response["data"])
    }

    @Test
    fun `successResponse with message only returns envelope without data`() {
        val response = tool.testSuccessResponseMsg("Operation completed")

        assertTrue(response["success"]!!.jsonPrimitive.boolean)
        assertEquals("Operation completed", response["message"]!!.jsonPrimitive.content)
        assertNotNull(response["metadata"])
    }

    // ────────────────────────────────────────────────────────
    // Response helpers — errorResponse
    // ────────────────────────────────────────────────────────

    @Test
    fun `errorResponse includes code and message in error envelope`() {
        val response = tool.testErrorResponse("Something went wrong", ErrorCodes.DATABASE_ERROR)

        assertFalse(response["success"]!!.jsonPrimitive.boolean)
        val error = response["error"]!!.jsonObject
        assertEquals("Something went wrong", error["message"]!!.jsonPrimitive.content)
        assertEquals(ErrorCodes.DATABASE_ERROR, error["code"]!!.jsonPrimitive.content)
        assertNotNull(response["metadata"])
    }

    @Test
    fun `errorResponse defaults to VALIDATION_ERROR code`() {
        val response = tool.testErrorResponse("Bad input")

        val error = response["error"]!!.jsonObject
        assertEquals(ErrorCodes.VALIDATION_ERROR, error["code"]!!.jsonPrimitive.content)
    }
}
