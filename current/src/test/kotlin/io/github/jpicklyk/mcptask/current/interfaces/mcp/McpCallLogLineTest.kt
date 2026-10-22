package io.github.jpicklyk.mcptask.current.interfaces.mcp

import ch.qos.logback.classic.Level
import io.github.jpicklyk.mcptask.current.application.port.CallLogSink
import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.LogCapture
import io.github.jpicklyk.mcptask.current.test.LogSnapshot
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item df10fafb) for the MCP per-call log line: scenarios S12 to S15 of the frozen test-plan.
 *
 * Oracle (frozen task-scope item 3): [McpOperationAdapter] emits exactly one INFO line per non-cancelled call, inside the call's
 * MDC (reqId, sessionId, tool, transport), whatever the sink does, with the template
 * `tool call: name={}, operation={}, outcome={}, errorCode={}, latencyMs={}, responseBytes={}` (outcome ok or error; errorCode `-`
 * on ok) and never an argument value or body text. Expected response bytes and the error code are recomputed here from the wire
 * result the client received (UTF-8 bytes of the text content plus the compact structuredContent), the same measure the stored
 * row's `response_bytes` uses (McpCallLogTest S2); the reqId is the one on the result's `_meta`.
 *
 * Absence assertions are paired with a control call on the same connection that must log.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class McpCallLogLineTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private lateinit var rig: EventLogRig
    private val fixtures = FixtureTools()
    private val pairs = mutableListOf<Pair<Client, Server>>()
    private val capture = LogCapture()

    @BeforeEach
    fun setUp() {
        rig = EventLogRig.build(db.db, dir)
        capture.attach(McpOperationAdapter::class.java.name)
    }

    @AfterEach
    fun tearDown(): Unit =
        runBlocking {
            capture.detach()
            pairs.forEach { closeInMemoryPair(it.first, it.second) }
        }

    private suspend fun connect(sink: CallLogSink = CallLogSink.NONE): Client {
        val server =
            Server(serverInfo = Implementation(name = "df10-line-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        val adapter = McpOperationAdapter(buildToolDispatcher(rig.ctx), callLog = sink)
        adapter.register(server, ToolRegistry(buildToolSpecs()), ToolProfile.ORCHESTRATION)
        adapter.register(server, fixtures.registry, ToolProfile.ORCHESTRATION)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client =
            Client(
                clientInfo = Implementation(name = "df10-line-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities())
            )
        server.createSession(serverTransport)
        client.connect(clientTransport)
        pairs += client to server
        return client
    }

    private suspend fun Client.run(
        tool: String,
        vararg args: Pair<String, JsonElement>
    ): CallToolResult =
        callTool(
            buildCallToolRequest {
                name = tool
                arguments(JsonObject(mapOf(*args)))
            }
        )

    private fun fixtureGet(id: String): Array<Pair<String, JsonElement>> =
        arrayOf("select" to JsonPrimitive("get"), "id" to JsonPrimitive(id))

    private fun reqIdOf(result: CallToolResult): String? =
        result.meta
            ?.get("reqId")
            ?.jsonPrimitive
            ?.content

    /** UTF-8 bytes of the text content plus the compact structuredContent JSON, excluding _meta and framing. */
    private fun responseBytes(result: CallToolResult): Long =
        result.content.filterIsInstance<TextContent>().sumOf {
            it.text
                .toByteArray(Charsets.UTF_8)
                .size
                .toLong()
        } +
            (
                result.structuredContent?.let {
                    it
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                        .size
                        .toLong()
                } ?: 0L
            )

    private fun errorCodeOf(result: CallToolResult): String =
        result.structuredContent
            ?.get("error")
            ?.jsonObject
            ?.get("code")
            ?.jsonPrimitive
            ?.content ?: "-"

    /** The INFO events of the adapter's logger (the per-call lines). */
    private fun lines(): List<LogSnapshot> =
        capture.events.filter { it.level == Level.INFO && it.logger == McpOperationAdapter::class.java.name }

    /** The lines once at least [count] are there (a line may be written just after the result is sent), waiting at most 3 seconds. */
    private fun awaitLines(count: Int): List<LogSnapshot> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (lines().size < count && System.nanoTime() < deadline) Thread.sleep(25)
        return lines()
    }

    // ------------------------------------------------------------------ S12

    @Test
    fun `S12 an ok call logs one INFO line with its reqId in MDC and the documented fields`() {
        val client = runBlocking { connect() }

        val result = runBlocking { client.run("fx_read", *fixtureGet(FixtureTools.GET_OK)) }

        assertTrue(result.isError != true, "${result.content}")
        val line = awaitLines(1).single()
        val reqId = assertNotNull(reqIdOf(result))
        assertTrue(ReqId.isValid(reqId))
        assertEquals(reqId, line.mdc["reqId"], "the line is logged inside the call's MDC")
        assertEquals("fx_read", line.mdc["tool"])
        assertEquals("mcp", line.mdc["transport"])
        assertFalse(line.mdc["sessionId"].isNullOrBlank(), "sessionId in MDC: ${line.mdc}")
        val prefix = "tool call: name=fx_read, operation=get, outcome=ok, errorCode=-, latencyMs="
        assertTrue(line.message.startsWith(prefix), "message: ${line.message}")
        val rest = line.message.removePrefix(prefix)
        assertTrue(Regex("""\d+, responseBytes=\d+""").matches(rest), "latency and bytes are numbers: $rest")
        assertEquals(responseBytes(result), rest.substringAfter("responseBytes=").toLong())
    }

    @Test
    fun `S12 a production tool call logs the same line shape with its select as the operation`() {
        val client = runBlocking { connect() }
        val item = runBlocking { rig.seed("S12 production") }

        val result =
            runBlocking {
                client.run("items_read", "select" to JsonPrimitive("get"), "itemId" to JsonPrimitive(item.id.toString()))
            }

        assertTrue(result.isError != true, "${result.content}")
        val line = awaitLines(1).single()
        assertEquals(reqIdOf(result), line.mdc["reqId"])
        assertTrue(
            line.message.startsWith("tool call: name=items_read, operation=get, outcome=ok, errorCode=-, latencyMs="),
            "message: ${line.message}"
        )
    }

    @Test
    fun `S12 a failing call logs outcome error with its error code`() {
        val client = runBlocking { connect() }

        val result = runBlocking { client.run("fx_read", *fixtureGet(FixtureTools.GET_MISSING)) }

        assertEquals(true, result.isError, "fixture: the call fails")
        val code = errorCodeOf(result)
        assertNotEquals("-", code, "fixture: the failure carries a code")
        val line = awaitLines(1).single()
        assertEquals(reqIdOf(result), line.mdc["reqId"])
        assertTrue(
            line.message.startsWith("tool call: name=fx_read, operation=get, outcome=error, errorCode=$code, latencyMs="),
            "message: ${line.message}"
        )
    }

    @Test
    fun `S12 a call that throws logs outcome error with the internal code and never the exception text`() {
        val client = runBlocking { connect() }

        val result = runBlocking { client.run("fx_read", *fixtureGet(FixtureTools.GET_SECRET)) }

        assertEquals(true, result.isError)
        val line = awaitLines(1).single()
        assertEquals(reqIdOf(result), line.mdc["reqId"])
        assertTrue(
            line.message.startsWith("tool call: name=fx_read, operation=get, outcome=error, errorCode=internal, latencyMs="),
            line.message
        )
        assertFalse(FixtureTools.SECRET in line.message, "the thrown text must not reach the line: ${line.message}")
    }

    @Test
    fun `S12 every call gets its own line carrying its own reqId`() {
        val client = runBlocking { connect() }

        val results =
            runBlocking {
                listOf(
                    client.run("fx_read", *fixtureGet(FixtureTools.GET_OK)),
                    client.run("fx_read", *fixtureGet(FixtureTools.GET_MISSING)),
                    client.run("fx_read", *fixtureGet(FixtureTools.GET_OK))
                )
            }

        val reqIds = results.map { reqIdOf(it) }
        assertEquals(3, reqIds.toSet().size, "fixture: three distinct reqIds")
        assertEquals(reqIds, awaitLines(3).map { it.mdc["reqId"] }, "one line per call, in call order")
    }

    // ------------------------------------------------------------------ S13

    @Test
    fun `S13 a cancelled call logs no line while a normal call on the same connection does`() {
        val client = runBlocking { connect() }

        runBlocking {
            runCatching { withTimeoutOrNull(2_000) { client.run("fx_read", *fixtureGet(FixtureTools.GET_CANCEL)) } }
        }
        // A cancelled call may take its connection down with it, so the control runs on a fresh connection to the same adapter setup.
        val control = runBlocking { connect().run("fx_read", *fixtureGet(FixtureTools.GET_OK)) }

        assertTrue(control.isError != true, "control: ${control.content}")
        val logged = awaitLines(1)
        assertEquals(1, logged.size, "only the control call logs; the cancelled call must not: ${logged.map { it.message }}")
        assertEquals(reqIdOf(control), logged.single().mdc["reqId"])
        assertTrue("outcome=ok" in logged.single().message)
    }

    // ------------------------------------------------------------------ S14

    @Test
    fun `S14 a sink that throws still gets the line logged and leaves the result unchanged`() {
        val withNone = runBlocking { connect(CallLogSink.NONE) }
        val withThrowing = runBlocking { connect(CallLogSink { throw IllegalStateException("sink down") }) }
        val request = fixtureGet(FixtureTools.GET_OK)

        val expected = runBlocking { withNone.run("fx_read", *request) }
        val before = awaitLines(1).size
        val actual = runBlocking { withThrowing.run("fx_read", *request) }

        assertEquals(1, before, "control: the call over the quiet sink logged its line")
        assertNotEquals(true, actual.isError, "${actual.content}")
        assertEquals(expected.content, actual.content, "identical content with and without a failing sink")
        val afterLines = awaitLines(2)
        assertEquals(2, afterLines.size, "the throwing-sink call logged its own line: ${afterLines.map { it.message }}")
        val line = afterLines.last()
        assertEquals(reqIdOf(actual), line.mdc["reqId"])
        assertTrue(line.message.startsWith("tool call: name=fx_read, operation=get, outcome=ok, errorCode=-, latencyMs="), line.message)
    }

    // ------------------------------------------------------------------ S15

    @Test
    fun `S15 argument values and body text never appear in the line`() {
        val client = runBlocking { connect() }
        val tagNeedle = "UNIQUE-TAG-7f3a9c"
        val queryNeedle = "uniqneedleq9z"
        val idNeedle = FixtureTools.GET_OK

        val listed = runBlocking { client.run("fx_read", "select" to JsonPrimitive("list"), "tag" to JsonPrimitive(tagNeedle)) }
        val searched = runBlocking { client.run("items_read", "select" to JsonPrimitive("search"), "query" to JsonPrimitive(queryNeedle)) }
        val got = runBlocking { client.run("fx_read", *fixtureGet(idNeedle)) }

        assertTrue(listed.isError != true, "${listed.content}")
        assertTrue(searched.isError != true, "${searched.content}")
        assertTrue(got.isError != true, "${got.content}")
        assertTrue(
            got.content.filterIsInstance<TextContent>().any { idNeedle in it.text } ||
                idNeedle in (got.structuredContent?.toString() ?: ""),
            "fixture: the id value is echoed in the response body, so a body-copying line would show it"
        )
        val logged = awaitLines(3)
        assertEquals(3, logged.size, "one line per call: ${logged.map { it.message }}")
        for (line in logged) {
            for (needle in listOf(tagNeedle, queryNeedle, idNeedle)) {
                assertFalse(needle in line.message, "'$needle' leaked into: ${line.message}")
            }
        }
    }
}
