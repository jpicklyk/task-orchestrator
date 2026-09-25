package io.github.jpicklyk.mcptask.current.interfaces.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.github.jpicklyk.mcptask.current.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MCP-path MDC correlation coverage for AR-78 / item b5081c9b: [McpToolAdapter] wraps each tool
 * call's handler in `withContext(MDCContext(...))` so every log line emitted while the call is in
 * flight — including from inside the tool's own `execute()`, even after a dispatcher hop — carries
 * `transport`, `tool`, `sessionId`, `requestId`, and (when present) `actorId`.
 *
 * **Test pitfall (see the task-scope note on b5081c9b):** [ILoggingEvent.getMdcPropertyMap] is
 * read lazily by the standard `LoggingEvent` implementation. A plain `ListAppender` only stores
 * the event object; reading `.mdcPropertyMap` later, on the test thread, after the tool call's
 * coroutine has already unwound its MDC scope, returns the WRONG (test-thread) map. The custom
 * [MdcSnapshotAppender] below snapshots `mdcPropertyMap` inside `append()`, which logback invokes
 * synchronously on the logging call's own thread while its MDC context is still live.
 */
class McpToolAdapterMdcTest {
    private lateinit var server: Server
    private lateinit var client: Client
    private lateinit var adapter: McpToolAdapter

    private val uuidPattern =
        Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            server =
                Server(
                    serverInfo = Implementation(name = "test-server", version = "1.0.0"),
                    options =
                        ServerOptions(
                            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))
                        )
                )
            adapter = McpToolAdapter()
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            client =
                Client(
                    clientInfo = Implementation(name = "test-client", version = "1.0.0"),
                    options = ClientOptions(capabilities = ClientCapabilities())
                )
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
                DefaultRepositoryProvider(
                    DatabaseManager(
                        Database.connect(
                            "jdbc:h2:mem:mcpadapter_mdc_${System.nanoTime()};DB_CLOSE_DELAY=-1",
                            driver = "org.h2.Driver"
                        )
                    ).also { DirectDatabaseSchemaManager().updateSchema() }
                )
        )

    /** A tool whose `execute` logs one INFO line, optionally hopping to [Dispatchers.IO] first. */
    private fun mdcProbeTool(hopDispatcher: Boolean): ToolDefinition =
        object : ToolDefinition {
            override val name = "mdc_probe"
            override val description = "Logs one INFO line from execute(), optionally after a dispatcher hop"
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.SYSTEM
            private val toolLogger = LoggerFactory.getLogger("mdc.probe.tool")

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement {
                if (hopDispatcher) {
                    withContext(Dispatchers.IO) { toolLogger.info("probe from execute (IO dispatcher)") }
                } else {
                    toolLogger.info("probe from execute")
                }
                return JsonObject(emptyMap())
            }
        }

    /** Snapshots each event's MDC map at append time — see the class KDoc for why this matters. */
    private class MdcSnapshotAppender : AppenderBase<ILoggingEvent>() {
        val snapshots = mutableListOf<Map<String, String>>()

        override fun append(eventObject: ILoggingEvent) {
            snapshots.add(eventObject.getMDCPropertyMap() ?: emptyMap())
        }
    }

    private fun <T> captureMdcSnapshots(
        loggerName: String,
        block: () -> T
    ): Pair<T, List<Map<String, String>>> {
        val logbackLogger = LoggerFactory.getLogger(loggerName) as Logger
        val appender =
            MdcSnapshotAppender().also {
                it.start()
                logbackLogger.addAppender(it)
            }
        val savedLevel = logbackLogger.level
        logbackLogger.level = Level.ALL
        try {
            val result = block()
            return result to appender.snapshots.toList()
        } finally {
            logbackLogger.detachAppender(appender)
            logbackLogger.level = savedLevel
        }
    }

    @Test
    fun `S2 - a tool call carries transport, tool, sessionId and a UUID requestId in MDC`() {
        adapter.registerToolWithServer(server, mdcProbeTool(hopDispatcher = false), dummyContext)

        val (_, adapterSnapshots) =
            captureMdcSnapshots(McpToolAdapter::class.java.name) {
                runBlocking { client.callTool(name = "mdc_probe", arguments = emptyMap()) }
            }

        // The adapter's own "tool call" telemetry INFO line is logged inside the same MDC scope.
        val telemetrySnapshot = adapterSnapshots.lastOrNull { it["tool"] == "mdc_probe" }
        assertTrue(telemetrySnapshot != null, "Expected an MDC-tagged log line from the adapter")
        assertEquals("mcp", telemetrySnapshot!!["transport"])
        assertEquals("mdc_probe", telemetrySnapshot["tool"])
        assertFalse(telemetrySnapshot["sessionId"].isNullOrBlank(), "sessionId must be non-blank")
        val requestId = telemetrySnapshot["requestId"]
        assertTrue(requestId != null && uuidPattern.matcher(requestId).matches(), "requestId must be a UUID: $requestId")
    }

    @Test
    fun `S3 - MDC fields survive a Dispatchers IO hop inside execute`() {
        adapter.registerToolWithServer(server, mdcProbeTool(hopDispatcher = true), dummyContext)

        val (_, probeSnapshots) =
            captureMdcSnapshots("mdc.probe.tool") {
                runBlocking { client.callTool(name = "mdc_probe", arguments = emptyMap()) }
            }

        assertEquals(1, probeSnapshots.size, "Expected exactly one log line from the probe tool")
        val snapshot = probeSnapshots.single()
        assertEquals("mcp", snapshot["transport"])
        assertEquals("mdc_probe", snapshot["tool"])
        assertTrue(
            snapshot["requestId"] != null && uuidPattern.matcher(snapshot["requestId"]!!).matches(),
            "requestId must survive the Dispatchers.IO hop"
        )
    }

    @Test
    fun `S11 - no actor supplied yields no actorId key`() {
        adapter.registerToolWithServer(server, mdcProbeTool(hopDispatcher = false), dummyContext)

        val (_, noActorSnapshots) =
            captureMdcSnapshots(McpToolAdapter::class.java.name) {
                runBlocking { client.callTool(name = "mdc_probe", arguments = emptyMap()) }
            }
        val noActorSnapshot = noActorSnapshots.lastOrNull { it["tool"] == "mdc_probe" }
        assertTrue(noActorSnapshot != null)
        assertFalse(noActorSnapshot!!.containsKey("actorId"), "No actor supplied: actorId key must be absent")
    }

    /**
     * [McpToolAdapter.actorIdFrom]'s JSON-shape handling directly — no absent/non-string
     * `actor.id` variant is exercisable through the real MCP-client round trip above (the SDK's
     * `Map<String, Any?>` argument encoding does not offer a clean way to send a non-string `id`
     * or a raw JSON object literal without going through generic reflection-based encoding), so
     * this covers the branch logic (absent actor, absent id, non-string id, string id) unit-style.
     */
    @Test
    fun `actorIdFrom extracts a string actor id and omits it for absent or non-string values`() {
        val actorId = "user-${UUID.randomUUID()}"
        assertEquals(
            actorId,
            adapter.actorIdFrom(JsonObject(mapOf("actor" to JsonObject(mapOf("id" to JsonPrimitive(actorId))))))
        )
        assertNull(adapter.actorIdFrom(JsonObject(emptyMap())), "No actor key")
        assertNull(adapter.actorIdFrom(JsonObject(mapOf("actor" to JsonObject(emptyMap())))), "No id key")
        assertNull(
            adapter.actorIdFrom(JsonObject(mapOf("actor" to JsonObject(mapOf("id" to JsonPrimitive(42)))))),
            "Non-string id"
        )
        assertNull(adapter.actorIdFrom(null), "Null arguments")
    }

    @Test
    fun `S12 - MDC does not leak onto the calling thread after a tool call returns`() {
        adapter.registerToolWithServer(server, mdcProbeTool(hopDispatcher = false), dummyContext)

        runBlocking {
            client.callTool(name = "mdc_probe", arguments = emptyMap())
        }

        assertNull(MDC.get("tool"), "MDC 'tool' key must not leak onto the calling thread")
        assertNull(MDC.get("requestId"), "MDC 'requestId' key must not leak onto the calling thread")
    }
}
