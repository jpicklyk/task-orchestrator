package io.github.jpicklyk.mcptask.current.interfaces.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MCP-path MDC correlation (AR-78 / item b5081c9b) at the 4.0 call boundary (Q32 port of the removed 3.x adapter's
 * McpToolAdapterMdcTest): [McpOperationAdapter] runs each call inside `MDCContext` carrying `transport`, `tool`,
 * `sessionId`, `reqId` and (when an actor is supplied) `actorId`, so every log line in flight carries them -- the
 * handler's own lines, after a dispatcher hop, and the dispatcher's error line. The 3.x adapter's UUID `requestId` and
 * its per-call INFO line have no 4.0 counterpart (the reqId is the call id; McpOperationAdapter logs no per-call line).
 *
 * Test pitfall: `ILoggingEvent.getMDCPropertyMap` is read lazily, so [MdcSnapshotAppender] snapshots it inside `append()`,
 * on the logging call's own thread while the call's MDC scope is still live.
 */
class McpOperationAdapterMdcTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val pairs = mutableListOf<Pair<Client, Server>>()
    private val probeLogger = LoggerFactory.getLogger("mdc.probe.tool")

    @AfterEach
    fun tearDown(): Unit =
        runBlocking {
            pairs.forEach { (client, server) -> closeInMemoryPair(client, server) }
        }

    @Serializable
    data class ProbeReq(
        @Doc("Hop to Dispatchers.IO before logging.") val hop: Boolean = false,
        @Doc("Throw instead of logging.") val fail: Boolean = false
    )

    /** The `mdc_probe` tool: select `log` logs one INFO line (optionally after a hop to [hopTo]) or throws. */
    private fun probeTool(hopTo: CoroutineDispatcher): SelectToolSpec =
        SelectToolSpec(
            "mdc_probe",
            "MDC probe.",
            listOf(
                SelectSpec.Single<ProbeReq, String>(
                    name = "log",
                    doc = "Log one line.",
                    profile = ToolProfile.KNOWLEDGE,
                    request = serializer<ProbeReq>(),
                    handler = { _, _, req ->
                        if (req.fail) throw IllegalStateException("forced internal error for MDC coverage")
                        if (req.hop) withContext(hopTo) { probeLogger.info("probe after a hop") } else probeLogger.info("probe")
                        Outcome.Ok("logged")
                    },
                    data = { JsonPrimitive(it) }
                )
            )
        )

    private suspend fun connect(hopTo: CoroutineDispatcher = Dispatchers.IO): Client {
        val env = ToolExecutionContext(repositoryProvider = db.repositoryProvider(), unitOfWork = db.unitOfWork())
        val server = Server(serverInfo = Implementation(name = "mdc-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        McpOperationAdapter(buildToolDispatcher(env)).register(server, ToolRegistry(listOf(probeTool(hopTo))), ToolProfile.ORCHESTRATION)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client =
            Client(
                clientInfo = Implementation(name = "mdc-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities())
            )
        server.createSession(serverTransport)
        client.connect(clientTransport)
        pairs += client to server
        return client
    }

    private suspend fun Client.probe(
        hop: Boolean = false,
        fail: Boolean = false,
        actorId: String? = null
    ) = callTool(
        buildCallToolRequest {
            name = "mdc_probe"
            arguments(
                JsonObject(
                    buildMap {
                        put("select", JsonPrimitive("log"))
                        put("hop", JsonPrimitive(hop))
                        put("fail", JsonPrimitive(fail))
                        if (actorId !=
                            null
                        ) {
                            put("actor", JsonObject(mapOf("id" to JsonPrimitive(actorId), "kind" to JsonPrimitive("subagent"))))
                        }
                    }
                )
            )
        }
    )

    private data class MdcSnapshot(
        val level: Level,
        val mdc: Map<String, String>
    )

    private class MdcSnapshotAppender : AppenderBase<ILoggingEvent>() {
        val snapshots = mutableListOf<MdcSnapshot>()

        override fun append(eventObject: ILoggingEvent) {
            snapshots.add(MdcSnapshot(eventObject.level, eventObject.getMDCPropertyMap() ?: emptyMap()))
        }
    }

    private fun <T> capture(
        loggerName: String,
        block: () -> T
    ): Pair<T, List<MdcSnapshot>> {
        val logbackLogger = LoggerFactory.getLogger(loggerName) as Logger
        val appender = MdcSnapshotAppender().also { it.start() }
        logbackLogger.addAppender(appender)
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
    fun `S2 a call's handler log line carries transport, tool, sessionId and the call reqId in MDC`() {
        val (_, lines) = capture("mdc.probe.tool") { runBlocking { connect().probe() } }

        val mdc = lines.single().mdc
        assertEquals("mcp", mdc["transport"])
        assertEquals("mdc_probe", mdc["tool"])
        assertFalse(mdc["sessionId"].isNullOrBlank(), "sessionId must be non-blank: $mdc")
        assertTrue(ReqId.isValid(mdc["reqId"].orEmpty()), "reqId must be the call's 8-character id: $mdc")
    }

    @Test
    fun `S3 MDC fields survive a Dispatchers IO hop inside the handler`() {
        val (_, lines) = capture("mdc.probe.tool") { runBlocking { connect().probe(hop = true) } }

        val mdc = lines.single().mdc
        assertEquals("mcp", mdc["transport"])
        assertEquals("mdc_probe", mdc["tool"])
        assertTrue(ReqId.isValid(mdc["reqId"].orEmpty()), "reqId must survive the Dispatchers.IO hop: $mdc")
    }

    @Test
    fun `S11 no actor supplied yields no actorId key and a supplied actor yields its id`() {
        val (_, lines) =
            capture("mdc.probe.tool") {
                runBlocking {
                    val client = connect()
                    client.probe()
                    client.probe(actorId = "agent-mdc")
                }
            }

        assertEquals(2, lines.size, "$lines")
        assertFalse(lines[0].mdc.containsKey("actorId"), "no actor supplied: actorId must be absent: ${lines[0].mdc}")
        assertEquals("agent-mdc", lines[1].mdc["actorId"], "${lines[1].mdc}")
    }

    @Test
    fun `O1 MDC is scoped per call and restored on a reused thread`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = executor.asCoroutineDispatcher()
            val (_, lines) =
                capture("mdc.probe.tool") {
                    runBlocking {
                        val client = connect(hopTo = dispatcher)
                        client.probe(hop = true, actorId = "leaky-actor")
                        client.probe(hop = true)
                    }
                }
            assertEquals(2, lines.size, "$lines")
            assertEquals("leaky-actor", lines[0].mdc["actorId"], "L1: call 1's line carries its actorId on the pooled thread")
            assertFalse(lines[1].mdc.containsKey("actorId"), "L2: call 2 supplied no actor, actorId must be absent: ${lines[1].mdc}")
            assertTrue(lines[0].mdc["reqId"] != lines[1].mdc["reqId"], "L2: each call has its own reqId")

            // L3: a plain Callable on the SAME thread: any MDC value here would be leakage from the calls above.
            val residual = executor.submit(Callable { MDC.getCopyOfContextMap() }).get()
            assertTrue(residual.isNullOrEmpty(), "L3: MDC must be restored on the reused thread after each call: $residual")
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `O2 the dispatcher's internal-error line carries the MDC correlation fields including actorId`() {
        val (result, lines) =
            capture(
                ToolDispatcher::class.java.name
            ) { runBlocking { connect().probe(fail = true, actorId = "agent-err") } }

        assertEquals(true, result.isError, "fixture: the handler failed: $result")
        val errorLine = lines.last { it.level == Level.ERROR }
        assertEquals("mcp", errorLine.mdc["transport"])
        assertEquals("mdc_probe", errorLine.mdc["tool"])
        assertEquals("agent-err", errorLine.mdc["actorId"])
        assertTrue(ReqId.isValid(errorLine.mdc["reqId"].orEmpty()), "${errorLine.mdc}")
    }
}
