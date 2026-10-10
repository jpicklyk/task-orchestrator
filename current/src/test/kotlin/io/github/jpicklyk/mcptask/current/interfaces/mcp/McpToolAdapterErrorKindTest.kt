package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored for item `0c07190d` (error catalog adoption), scenarios S1 and S2 of the frozen `test-plan`.
 * Every scenario here is EXISTING-SURFACE: tools, the adapter and the wire codes all predate the item; only the
 * `kind` / `structuredContent` additions are new, so a plain revert of the adapter turns these red on behaviour
 * (missing `structuredContent`, wrong `kind`), not on compilation.
 *
 * Harness: a real [Server]/[Client] pair over [ChannelTransport.createLinkedPair] driving [McpToolAdapter] through
 * [Client.callTool], exactly as `McpToolAdapterValidationEnvelopeTest` does, so the adapter's catch handling is what
 * is exercised and `execute()` is never called directly.
 *
 * Oracles: [P] v4-phase1-core 3.1/6 and task-scope Build step 4 (a generic exception keeps its text and gains
 * `structuredContent.error = {code INTERNAL_ERROR, message <same text>, kind transient}`; a store fault keeps the wire
 * code `DATABASE_ERROR` and takes its kind from the fault); [E] the catalog table in `ErrorCatalogTest` and
 * api-reference `ErrorKind Values` (kinds are the lowercase strings `transient` / `permanent` / `shedding`; lock
 * contention is `shedding`, a uniqueness or foreign-key violation is `permanent`, any other storage error is
 * `transient`); [M] api-reference Error Envelope "Storage faults": the adapter text for a storage fault is
 * `Database error in '<tool>': <sql text>`.
 */
class McpToolAdapterErrorKindTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var server: Server
    private lateinit var client: Client
    private lateinit var adapter: McpToolAdapter

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            server =
                Server(
                    serverInfo = Implementation(name = "error-kind-server", version = "1.0.0"),
                    options = inMemoryTestServerOptions()
                )
            adapter = McpToolAdapter()
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            client =
                Client(
                    clientInfo = Implementation(name = "error-kind-client", version = "1.0.0"),
                    options = ClientOptions(capabilities = ClientCapabilities())
                )
            server.createSession(serverTransport)
            client.connect(clientTransport)
        }

    @AfterEach
    fun tearDown(): Unit =
        runBlocking {
            closeInMemoryPair(client, server)
        }

    private val context by lazy { ToolExecutionContext(repositoryProvider = db.repositoryProvider(), unitOfWork = db.unitOfWork()) }

    /** A tool whose `execute` throws whatever [thrower] builds, so each scenario picks its own fault. */
    private fun throwingTool(
        toolName: String,
        thrower: () -> Throwable
    ): ToolDefinition =
        object : ToolDefinition {
            override val name = toolName
            override val description = "Always throws from execute"
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.SYSTEM

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement = throw thrower()
        }

    private suspend fun call(
        toolName: String,
        thrower: () -> Throwable
    ): CallToolResult {
        adapter.registerToolWithServer(server, throwingTool(toolName, thrower), context)
        return client.callTool(name = toolName, arguments = emptyMap())
    }

    private fun CallToolResult.text(): String {
        val texts = content.filterIsInstance<TextContent>()
        assertTrue(texts.isNotEmpty(), "Expected at least one TextContent")
        return texts[0].text
    }

    private fun CallToolResult.errorObject(): JsonObject {
        val structured = assertNotNull(structuredContent, "an error result must carry structuredContent")
        return assertNotNull(structured["error"]?.jsonObject, "structuredContent must contain the error object: $structured")
    }

    // ----------------------------------------------
    // S1 - a generic exception
    // ----------------------------------------------

    @Test
    fun `S1 a RuntimeException from execute keeps its text and gains a structured INTERNAL_ERROR transient envelope`(): Unit =
        runBlocking {
            val result = call("s1_runtime") { RuntimeException("boom") }

            assertEquals(true, result.isError)
            val text = result.text()
            assertTrue(text.startsWith("Internal error in 's1_runtime'"), "the text summary keeps its Internal error prefix, got: $text")

            val error = result.errorObject()
            assertEquals(ErrorCodes.INTERNAL_ERROR, error["code"]?.jsonPrimitive?.content)
            assertEquals("transient", error["kind"]?.jsonPrimitive?.content)
            assertEquals(text, error["message"]?.jsonPrimitive?.content, "structuredContent.error.message must equal the text summary")
            assertEquals(setOf("code", "message", "kind"), error.keys, "no retryAfterMs, contendedItemId or details on an internal error")
            assertEquals(setOf("error"), result.structuredContent!!.keys, "structuredContent carries only the error object")
        }

    @Test
    fun `S1 probe an exception with a null message does not break the envelope`(): Unit =
        runBlocking {
            val result = call("s1_null_message") { RuntimeException() }

            assertEquals(true, result.isError)
            val text = result.text()
            assertTrue(text.startsWith("Internal error in 's1_null_message'"), "got: $text")
            val error = result.errorObject()
            assertEquals(ErrorCodes.INTERNAL_ERROR, error["code"]?.jsonPrimitive?.content)
            assertEquals("transient", error["kind"]?.jsonPrimitive?.content)
            assertEquals(text, error["message"]?.jsonPrimitive?.content)
        }

    @Test
    fun `S1 probe a succeeding tool result carries no error object and no kind`(): Unit =
        runBlocking {
            val okTool =
                object : ToolDefinition {
                    override val name = "s1_ok"
                    override val description = "Always succeeds"
                    override val parameterSchema = ToolSchema()
                    override val category = ToolCategory.SYSTEM

                    override suspend fun execute(
                        params: JsonElement,
                        context: ToolExecutionContext
                    ): JsonElement =
                        buildJsonObject {
                            put("success", JsonPrimitive(true))
                            put("data", buildJsonObject { put("ok", JsonPrimitive(true)) })
                        }
                }
            adapter.registerToolWithServer(server, okTool, context)

            val result = client.callTool(name = "s1_ok", arguments = emptyMap())

            assertTrue(result.isError != true, "a succeeding tool is not an error: ${result.content}")
            assertNull(result.structuredContent?.get("error"), "a success result must not carry an error object")
            assertNull(result.structuredContent?.get("kind"), "a success result must not carry a kind")
        }

    // ----------------------------------------------
    // S2 - a storage fault escaping a tool
    // ----------------------------------------------

    private fun assertStorageFault(
        toolName: String,
        marker: String,
        result: CallToolResult,
        expectedKind: String
    ) {
        assertEquals(true, result.isError)
        val text = result.text()
        assertTrue(text.startsWith("Database error in '$toolName': "), "storage fault text keeps its 3.x prefix, got: $text")
        assertTrue(text.contains(marker), "the text carries the innermost SQL error text, got: $text")
        val error = result.errorObject()
        assertEquals(ErrorCodes.DATABASE_ERROR, error["code"]?.jsonPrimitive?.content, "the wire code stays DATABASE_ERROR")
        assertEquals(expectedKind, error["kind"]?.jsonPrimitive?.content, "kind follows the fault class")
        assertEquals(text, error["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `S2 SQLITE_BUSY is DATABASE_ERROR with kind shedding`(): Unit =
        runBlocking {
            val result = call("s2_busy") { SQLiteException("marker-busy", SQLiteErrorCode.SQLITE_BUSY) }
            assertStorageFault("s2_busy", "marker-busy", result, "shedding")
        }

    @Test
    fun `S2 probe SQLITE_BUSY_SNAPSHOT is shedding`(): Unit =
        runBlocking {
            val result = call("s2_busy_snapshot") { SQLiteException("marker-snapshot", SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT) }
            assertStorageFault("s2_busy_snapshot", "marker-snapshot", result, "shedding")
        }

    @Test
    fun `S2 probe SQLITE_LOCKED_SHAREDCACHE is shedding`(): Unit =
        runBlocking {
            val result = call("s2_locked") { SQLiteException("marker-locked", SQLiteErrorCode.SQLITE_LOCKED_SHAREDCACHE) }
            assertStorageFault("s2_locked", "marker-locked", result, "shedding")
        }

    @Test
    fun `S2 SQLITE_CONSTRAINT_UNIQUE is DATABASE_ERROR with kind permanent`(): Unit =
        runBlocking {
            val result = call("s2_unique") { SQLiteException("marker-unique", SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE) }
            assertStorageFault("s2_unique", "marker-unique", result, "permanent")
        }

    @Test
    fun `S2 SQLITE_CONSTRAINT_FOREIGNKEY is DATABASE_ERROR with kind permanent`(): Unit =
        runBlocking {
            val result = call("s2_fk") { SQLiteException("marker-fk", SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY) }
            assertStorageFault("s2_fk", "marker-fk", result, "permanent")
        }

    @Test
    fun `S2 a generic SQLITE_ERROR is DATABASE_ERROR with kind transient`(): Unit =
        runBlocking {
            val result = call("s2_generic") { SQLiteException("marker-generic", SQLiteErrorCode.SQLITE_ERROR) }
            assertStorageFault("s2_generic", "marker-generic", result, "transient")
        }

    @Test
    fun `S2 probe an SQLException-rooted chain wrapped three causes deep is a persistence fault classified by the innermost fault`(): Unit =
        runBlocking {
            // Persistence-fault contract (P5a, task-scope step 4): only an outermost java.sql.SQLException chain is a fault.
            val result =
                call("s2_wrapped") {
                    SQLException(
                        "outer",
                        SQLException(
                            "middle",
                            SQLiteException("marker-wrapped", SQLiteErrorCode.SQLITE_BUSY)
                        )
                    )
                }
            assertStorageFault("s2_wrapped", "marker-wrapped", result, "shedding")
        }

    @Test
    fun `S2 probe a non-SQL exception wrapping a storage fault is not a persistence fault and stays INTERNAL_ERROR transient`(): Unit =
        runBlocking {
            val result =
                call("s2_wrapped_plain") { RuntimeException("outer", SQLiteException("marker-plain", SQLiteErrorCode.SQLITE_BUSY)) }

            assertEquals(true, result.isError)
            val text = result.text()
            assertTrue(text.startsWith("Internal error in 's2_wrapped_plain'"), "generic internal error text, got: $text")
            val error = result.errorObject()
            assertEquals(ErrorCodes.INTERNAL_ERROR, error["code"]?.jsonPrimitive?.content)
            assertEquals("transient", error["kind"]?.jsonPrimitive?.content)
            assertEquals(text, error["message"]?.jsonPrimitive?.content)
        }

    @Test
    fun `S2 probe the kind values are exactly the lowercase catalog strings`(): Unit =
        runBlocking {
            val kinds =
                listOf(
                    call("s2_kind_a") { SQLiteException("a", SQLiteErrorCode.SQLITE_BUSY) },
                    call("s2_kind_b") { SQLiteException("b", SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE) },
                    call("s2_kind_c") { SQLiteException("c", SQLiteErrorCode.SQLITE_ERROR) },
                    call("s2_kind_d") { RuntimeException("d") }
                ).map { it.errorObject()["kind"]!!.jsonPrimitive.content }

            assertEquals(listOf("shedding", "permanent", "transient", "transient"), kinds)
        }
}
