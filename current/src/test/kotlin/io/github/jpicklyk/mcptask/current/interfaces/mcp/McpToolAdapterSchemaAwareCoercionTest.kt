package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.notes.QueryNotesTool
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent tests for [McpToolAdapter.preprocessParameters] (item 462931bb): string-to-boolean
 * coercion must be scoped to parameters whose declared `parameterSchema` type is `"boolean"`.
 * String-typed and untyped/unknown parameters — e.g. a `query` of `"false"`, or a `title` of
 * `"true"` — must pass through unchanged; nested values are never touched; real boolean params
 * are still coerced from their string form.
 *
 * Oracle sources (frozen at queue phase, see the `test-plan` note on 462931bb):
 *  O1 JSON Schema 2020-12 §6.1.1 `type`: a value for a prop declared `"type":"string"` is a
 *     string; a transport shim must not retype it.
 *  O2 [McpToolAdapter.preprocessParameters] KDoc: "Some MCP clients send boolean parameters as
 *     strings" — coercion is scoped to boolean params.
 *  O3 Real tool contracts: `manage_items`'s `recursive` is declared `boolean`; `query_notes`'s
 *     `query` is declared `string` ("pass plain terms").
 *  O4 `BaseToolDefinition.requireString` rejects a non-string `JsonPrimitive`.
 */
class McpToolAdapterSchemaAwareCoercionTest {
    private val adapter = McpToolAdapter()

    private fun stringSchema(propName: String): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put(propName, buildJsonObject { put("type", JsonPrimitive("string")) })
                }
        )

    private fun booleanSchema(propName: String): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put(propName, buildJsonObject { put("type", JsonPrimitive("boolean")) })
                }
        )

    private fun preprocess(
        params: JsonObject,
        schema: ToolSchema?
    ): JsonObject = adapter.preprocessParameters(params, schema) as JsonObject

    // ──────────────────────────────────────────────
    // S1 / S2 — string-typed param, literal "false"/"true" values stay strings
    // ──────────────────────────────────────────────

    @Test
    fun `S1 - string-typed param with string value false is left as a string`() {
        val result = preprocess(buildJsonObject { put("query", JsonPrimitive("false")) }, stringSchema("query"))

        val query = result["query"]
        assertTrue(query is JsonPrimitive && query.isString, "query must remain a JSON string, got $query")
        assertEquals("false", (query as JsonPrimitive).content)
    }

    @Test
    fun `S2 - string-typed param with string value true is left as a string`() {
        val result = preprocess(buildJsonObject { put("query", JsonPrimitive("true")) }, stringSchema("query"))

        val query = result["query"]
        assertTrue(query is JsonPrimitive && query.isString, "query must remain a JSON string, got $query")
        assertEquals("true", (query as JsonPrimitive).content)
    }

    // ──────────────────────────────────────────────
    // S3 / S4 — boolean-typed param: string forms coerce, real booleans pass through
    // ──────────────────────────────────────────────

    @Test
    fun `S3 - boolean-typed param coerces string true and false to JSON booleans`() {
        val trueResult = preprocess(buildJsonObject { put("recursive", JsonPrimitive("true")) }, booleanSchema("recursive"))
        val falseResult = preprocess(buildJsonObject { put("recursive", JsonPrimitive("false")) }, booleanSchema("recursive"))

        val trueVal = trueResult["recursive"] as JsonPrimitive
        val falseVal = falseResult["recursive"] as JsonPrimitive
        assertFalse(trueVal.isString, "expected a JSON boolean primitive, got a string: $trueVal")
        assertFalse(falseVal.isString, "expected a JSON boolean primitive, got a string: $falseVal")
        assertEquals(true, trueVal.boolean)
        assertEquals(false, falseVal.boolean)
    }

    @Test
    fun `S4 - boolean-typed param with a real JSON boolean value is unchanged`() {
        val result = preprocess(buildJsonObject { put("recursive", JsonPrimitive(true)) }, booleanSchema("recursive"))

        val recursive = result["recursive"] as JsonPrimitive
        assertFalse(recursive.isString)
        assertEquals(true, recursive.boolean)
    }

    // ──────────────────────────────────────────────
    // S5 — string-typed param given a real boolean is not reverse-coerced
    // ──────────────────────────────────────────────

    @Test
    fun `S5 - string-typed param given a real JSON boolean is not reverse-coerced to a string`() {
        // Per O4, BaseToolDefinition#requireString rejects a non-string JsonPrimitive, so a
        // boolean value arriving on a string-typed param must reach validation unchanged rather
        // than being silently stringified into "true"/"false" to dodge that check.
        val result = preprocess(buildJsonObject { put("query", JsonPrimitive(true)) }, stringSchema("query"))

        val query = result["query"] as JsonPrimitive
        assertFalse(query.isString, "a real boolean on a string-typed param must not be turned into a string")
        assertEquals(true, query.boolean)
    }

    // ──────────────────────────────────────────────
    // S7 — mixed case: coerced for boolean params, preserved verbatim for string params
    // ──────────────────────────────────────────────

    @Test
    fun `S7 - mixed case is coerced for boolean params, preserved verbatim for string params`() {
        val boolTrue = preprocess(buildJsonObject { put("recursive", JsonPrimitive("True")) }, booleanSchema("recursive"))
        val boolFalse = preprocess(buildJsonObject { put("recursive", JsonPrimitive("FALSE")) }, booleanSchema("recursive"))

        val recursiveTrue = boolTrue["recursive"] as JsonPrimitive
        val recursiveFalse = boolFalse["recursive"] as JsonPrimitive
        assertFalse(recursiveTrue.isString)
        assertEquals(true, recursiveTrue.boolean)
        assertFalse(recursiveFalse.isString)
        assertEquals(false, recursiveFalse.boolean)

        val stringResult = preprocess(buildJsonObject { put("query", JsonPrimitive("True")) }, stringSchema("query"))
        val query = stringResult["query"] as JsonPrimitive
        assertTrue(query.isString)
        assertEquals("True", query.content, "case must be preserved verbatim for a string-typed param")
    }

    // ──────────────────────────────────────────────
    // S8 — a param with no type evidence in the schema is left untouched
    // ──────────────────────────────────────────────

    @Test
    fun `S8 - a param absent from parameterSchema properties is left untouched`() {
        // "unknownFlag" is not declared anywhere in properties — only "recursive" is.
        val params =
            buildJsonObject {
                put("recursive", JsonPrimitive("true"))
                put("unknownFlag", JsonPrimitive("true"))
            }

        val result = preprocess(params, booleanSchema("recursive"))

        val unknownFlag = result["unknownFlag"] as JsonPrimitive
        assertTrue(unknownFlag.isString, "a param with no type evidence in the schema must stay a string")
        assertEquals("true", unknownFlag.content)
    }

    // ──────────────────────────────────────────────
    // S9 — a ToolSchema with null properties coerces nothing
    // ──────────────────────────────────────────────

    @Test
    fun `S9 - a ToolSchema with null properties leaves every string value unchanged`() {
        val schema = ToolSchema()
        val params =
            buildJsonObject {
                put("anything", JsonPrimitive("true"))
                put("other", JsonPrimitive("false"))
            }

        val result = preprocess(params, schema)

        val anything = result["anything"] as JsonPrimitive
        val other = result["other"] as JsonPrimitive
        assertTrue(anything.isString)
        assertEquals("true", anything.content)
        assertTrue(other.isString)
        assertEquals("false", other.content)
    }

    // ──────────────────────────────────────────────
    // S10 — nested values are never touched, regardless of the parent's declared type
    // ──────────────────────────────────────────────

    @Test
    fun `S10 - a nested value under a top-level param is left untouched regardless of its declared type`() {
        val schema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put("config", buildJsonObject { put("type", JsonPrimitive("object")) })
                    }
            )
        val params =
            buildJsonObject {
                put("config", buildJsonObject { put("nestedFlag", JsonPrimitive("true")) })
            }

        val result = preprocess(params, schema)

        val config = result["config"] as JsonObject
        val nestedFlag = config["nestedFlag"] as JsonPrimitive
        assertTrue(nestedFlag.isString, "nested values must never be coerced, only top-level ones")
        assertEquals("true", nestedFlag.content)
    }

    // ──────────────────────────────────────────────
    // S11 — empty vs. absent vs. JsonNull on a boolean param keep their own identity
    // ──────────────────────────────────────────────

    @Test
    fun `S11 - an empty string on a boolean param stays a string`() {
        val result = preprocess(buildJsonObject { put("flag", JsonPrimitive("")) }, booleanSchema("flag"))

        val flag = result["flag"] as JsonPrimitive
        assertTrue(flag.isString, "an empty string is not \"true\"/\"false\" and must stay a string")
        assertEquals("", flag.content)
    }

    @Test
    fun `S11 - an absent boolean param stays absent`() {
        val result = preprocess(buildJsonObject { put("other", JsonPrimitive("x")) }, booleanSchema("flag"))

        assertFalse("flag" in result, "an absent key must stay absent, not be materialized")
    }

    @Test
    fun `S11 - a JsonNull boolean param stays JsonNull`() {
        val result = preprocess(buildJsonObject { put("flag", JsonNull) }, booleanSchema("flag"))

        assertEquals(JsonNull, result["flag"], "JsonNull must stay JsonNull, never become a false boolean")
    }

    // ──────────────────────────────────────────────
    // Adversarial probes
    // ──────────────────────────────────────────────

    @Test
    fun `adversarial - boundary strings that are not exact true or false stay strings on a boolean param`() {
        val schema = booleanSchema("flag")
        for (candidate in listOf("truex", "tru", " true ", "TrUe ")) {
            val result = preprocess(buildJsonObject { put("flag", JsonPrimitive(candidate)) }, schema)
            val flag = result["flag"] as JsonPrimitive
            assertTrue(flag.isString, "\"$candidate\" is not an exact true/false match and must stay a string")
            assertEquals(candidate, flag.content)
        }
    }

    @Test
    fun `adversarial - preprocessing an already-preprocessed object is a fixed point`() {
        val schema = booleanSchema("recursive")
        val params = buildJsonObject { put("recursive", JsonPrimitive("true")) }

        val once = preprocess(params, schema)
        val twice = preprocess(once, schema)

        assertEquals(once, twice)
    }

    // ──────────────────────────────────────────────
    // S6 — full MCP stack regression (red-first): a legitimate string query value of "false"
    // must never surface a validation error through the real protocol path.
    // ──────────────────────────────────────────────

    @Nested
    inner class RegressionThroughFullMcpStack {
        private lateinit var server: Server
        private lateinit var client: Client

        @BeforeEach
        fun setUp(): Unit =
            runBlocking {
                server =
                    Server(
                        serverInfo = Implementation(name = "coercion-test-server", version = "1.0.0"),
                        options =
                            ServerOptions(
                                capabilities =
                                    ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))
                            )
                    )
                client =
                    Client(
                        clientInfo = Implementation(name = "coercion-test-client", version = "1.0.0"),
                        options = ClientOptions(capabilities = ClientCapabilities())
                    )
                val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
                server.createSession(serverTransport)
                client.connect(clientTransport)
            }

        @AfterEach
        fun tearDown(): Unit =
            runBlocking {
                client.close()
                server.close()
            }

        private val dummyContext =
            ToolExecutionContext(
                repositoryProvider =
                    io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider(
                        io.github.jpicklyk.mcptask.current.infrastructure.database
                            .DatabaseManager(
                                org.jetbrains.exposed.v1.jdbc.Database.connect(
                                    "jdbc:h2:mem:coercion_${System.nanoTime()};DB_CLOSE_DELAY=-1",
                                    driver = "org.h2.Driver"
                                )
                            ).also {
                                io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management
                                    .DirectDatabaseSchemaManager()
                                    .updateSchema()
                            }
                    )
            )

        @Test
        fun `S6 - query_notes search with query false does not surface a validation error`(): Unit =
            runBlocking {
                val adapter = McpToolAdapter()
                adapter.registerToolWithServer(server, QueryNotesTool(), dummyContext)

                val result =
                    client.callTool(
                        name = "query_notes",
                        arguments = mapOf("operation" to "search", "query" to "false")
                    )

                assertTrue(
                    result.isError != true,
                    "query_notes(search, query=\"false\") must not fail validation: ${result.content}"
                )
                val textContent = result.content.filterIsInstance<TextContent>()
                assertFalse(
                    textContent.any { it.text.contains("Validation error") },
                    "no Validation error should surface for a legitimate string query value: " +
                        textContent.map { it.text }
                )
            }
    }
}
