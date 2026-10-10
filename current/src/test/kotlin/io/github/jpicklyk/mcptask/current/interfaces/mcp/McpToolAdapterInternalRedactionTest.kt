package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f1b003a6) for the carry-in redaction of an escaping tool exception, scenario S19
 * (plan section 4.3 flag (a), AR-23): the client-visible text, the `structuredContent.error.message` and any logging
 * notification are the fixed `Internal error in '<tool>'` with no exception text, no cause text and no session id; the
 * `{code, message, kind}` key set, the code `INTERNAL_ERROR` and the kind `transient` are unchanged; the storage-fault branch
 * keeps its SQL text (3.x contract, api-reference "Storage faults").
 *
 * EXISTING-SURFACE: the adapter, the wire codes and the message prefix all predate the item, so a plain revert of the
 * adapter hunk turns S19 red on behaviour. Harness: a real [Server]/[Client] pair over [ChannelTransport.createLinkedPair]
 * driving [McpToolAdapter] through [Client.callTool], as in `McpToolAdapterErrorKindTest`; the adapter is built directly with
 * its defaults. The server declares the logging capability so any logging notification reaches the client; the client keeps
 * every notification it receives so the redaction is checked on that channel too.
 */
class McpToolAdapterInternalRedactionTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val secret = "SECRET-7f3a"
    private val notifications = CopyOnWriteArrayList<String>()

    private lateinit var server: Server
    private lateinit var client: Client
    private lateinit var adapter: McpToolAdapter

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            server =
                Server(
                    serverInfo = Implementation(name = "redaction-server", version = "1.0.0"),
                    options =
                        ServerOptions(
                            capabilities =
                                ServerCapabilities(
                                    tools = ServerCapabilities.Tools(listChanged = false),
                                    logging = buildJsonObject { }
                                )
                        )
                )
            adapter = McpToolAdapter()
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            client =
                Client(
                    clientInfo = Implementation(name = "redaction-client", version = "1.0.0"),
                    options = ClientOptions(capabilities = ClientCapabilities())
                )
            client.fallbackNotificationHandler = { notification -> notifications.add(notification.toString()) }
            server.createSession(serverTransport)
            client.connect(clientTransport)
        }

    @AfterEach
    fun tearDown(): Unit =
        runBlocking {
            closeInMemoryPair(client, server)
        }

    private val context by lazy { ToolExecutionContext(repositoryProvider = db.repositoryProvider(), unitOfWork = db.unitOfWork()) }

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

    private fun sessionIds(): List<String> {
        val ids = server.sessions.keys.toList() + server.sessions.values.map { it.sessionId }
        assertTrue(
            ids.isNotEmpty() &&
                ids.all {
                    it.isNotBlank()
                },
            "the harness must expose the live session id (control for the session-id assertions): $ids"
        )
        return ids.distinct()
    }

    private fun assertNothingLeaks(
        result: CallToolResult,
        label: String
    ) {
        val everything = result.content.toString() + result.structuredContent.toString()
        assertFalse(everything.contains(secret), "$label: exception text leaked into $everything")
        assertFalse(everything.contains("cause-"), "$label: cause text leaked into $everything")
        for (id in sessionIds()) assertFalse(everything.contains(id), "$label: session id $id leaked into $everything")
    }

    @Test
    fun `S19 an escaping RuntimeException reaches the client as the fixed Internal error text only`(): Unit =
        runBlocking {
            val result = call("s19_runtime") { RuntimeException(secret) }

            assertEquals(true, result.isError)
            val text = result.text()
            assertEquals("Internal error in 's19_runtime'", text, "the whole text is the fixed message")
            val error = result.errorObject()
            assertEquals(ErrorCodes.INTERNAL_ERROR, error["code"]?.jsonPrimitive?.content)
            assertEquals("transient", error["kind"]?.jsonPrimitive?.content)
            assertEquals(text, error["message"]?.jsonPrimitive?.content, "structuredContent.error.message equals the text")
            assertEquals(setOf("code", "message", "kind"), error.keys, "the key set is unchanged")
            assertEquals(setOf("error"), result.structuredContent!!.keys)
            assertEquals(1, result.content.size, "one text block, nothing else carries the exception")
            assertNothingLeaks(result, "runtime")
        }

    @Test
    fun `S19 the exception text is hidden for other exception types and for a secret inside the cause chain`(): Unit =
        runBlocking {
            val cases: List<Pair<String, () -> Throwable>> =
                listOf(
                    "s19_state" to { IllegalStateException("state $secret") },
                    "s19_cause" to { RuntimeException("outer", RuntimeException("cause-$secret")) },
                    "s19_unsupported" to { UnsupportedOperationException(secret) },
                    "s19_nested" to { RuntimeException("a", IllegalStateException("b", RuntimeException("cause-$secret"))) }
                )
            for ((tool, thrower) in cases) {
                val result = call(tool, thrower)

                assertEquals(true, result.isError, tool)
                assertEquals("Internal error in '$tool'", result.text(), tool)
                assertEquals(ErrorCodes.INTERNAL_ERROR, result.errorObject()["code"]?.jsonPrimitive?.content, tool)
                assertEquals("Internal error in '$tool'", result.errorObject()["message"]?.jsonPrimitive?.content, tool)
                assertNothingLeaks(result, tool)
            }
        }

    @Test
    fun `S19 probe an exception with a null message gives the same fixed text`(): Unit =
        runBlocking {
            val result = call("s19_null_message") { RuntimeException() }

            assertEquals("Internal error in 's19_null_message'", result.text())
            assertEquals(setOf("code", "message", "kind"), result.errorObject().keys)
            assertNothingLeaks(result, "null message")
        }

    @Test
    fun `S19 probe an exception whose message is the tool name or the fixed prefix still yields exactly the fixed text`(): Unit =
        runBlocking {
            val result = call("s19_echo") { RuntimeException("Internal error in 's19_echo': $secret") }

            assertEquals("Internal error in 's19_echo'", result.text())
            assertNothingLeaks(result, "echo")
        }

    @Test
    fun `S19 control - a storage fault keeps its SQL text and the DATABASE_ERROR code`(): Unit =
        runBlocking {
            val result = call("s19_db") { SQLiteException("marker-busy", SQLiteErrorCode.SQLITE_BUSY) }

            assertEquals(true, result.isError)
            val text = result.text()
            assertTrue(text.startsWith("Database error in 's19_db': "), "got: $text")
            assertTrue(text.contains("marker-busy"), "the storage branch keeps the SQL text: $text")
            assertEquals(ErrorCodes.DATABASE_ERROR, result.errorObject()["code"]?.jsonPrimitive?.content)
        }

    @Test
    fun `S19 no logging notification sent to the client carries the exception text, the cause text or the session id`(): Unit =
        runBlocking {
            val result = call("s19_notify") { RuntimeException("$secret cause-$secret") }
            assertEquals("Internal error in 's19_notify'", result.text())

            val ids = sessionIds()
            // give any asynchronous notification a chance to arrive before looking
            repeat(20) { if (notifications.isEmpty()) kotlinx.coroutines.delay(50) }
            val logged = notifications.toList()
            assertTrue(logged.isNotEmpty(), "the server must have emitted a logging notification for the failed call (control)")
            assertTrue(logged.any { it.contains("Internal error in 's19_notify'") }, "the notification carries the fixed message: $logged")
            for (notification in logged) {
                assertFalse(notification.contains(secret), "exception text leaked into a notification: $notification")
                assertFalse(notification.contains("cause-"), "cause text leaked into a notification: $notification")
                for (id in ids) assertFalse(notification.contains(id), "session id leaked into a notification: $notification")
            }
        }
}
