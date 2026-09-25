package io.github.jpicklyk.mcptask.current.interfaces.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.github.jpicklyk.mcptask.current.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
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
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
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

    /**
     * Builds a `tools/call` request with a top-level `actor.id` string argument, using the SDK's
     * [JsonObject]-based DSL directly (rather than the reflection-based `Map<String, Any?>`
     * overload) so the nested `actor` object is encoded exactly as intended, with no ambiguity
     * about how a nested map value gets converted to JSON.
     */
    private fun callToolWithActor(
        toolName: String,
        actorId: String
    ) = buildCallToolRequest {
        name = toolName
        arguments(JsonObject(mapOf("actor" to JsonObject(mapOf("id" to JsonPrimitive(actorId))))))
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

    /** One captured log event: its level plus its MDC map, snapshotted at append time. */
    private data class MdcSnapshot(
        val level: Level,
        val mdc: Map<String, String>
    )

    /**
     * Snapshots each event's level and MDC map at append time — see the class KDoc for why
     * reading the MDC map at append() (not later) matters. Extended for O2: the level is needed
     * to distinguish the WARN/WARN/ERROR paths sharing the same [McpToolAdapter] logger name.
     */
    private class MdcSnapshotAppender : AppenderBase<ILoggingEvent>() {
        val snapshots = mutableListOf<MdcSnapshot>()

        override fun append(eventObject: ILoggingEvent) {
            snapshots.add(MdcSnapshot(eventObject.level, eventObject.getMDCPropertyMap() ?: emptyMap()))
        }
    }

    private fun <T> captureMdcSnapshots(
        loggerName: String,
        block: () -> T
    ): Pair<T, List<MdcSnapshot>> {
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
        val telemetrySnapshot = adapterSnapshots.lastOrNull { it.mdc["tool"] == "mdc_probe" }
        assertTrue(telemetrySnapshot != null, "Expected an MDC-tagged log line from the adapter")
        assertEquals("mcp", telemetrySnapshot!!.mdc["transport"])
        assertEquals("mdc_probe", telemetrySnapshot.mdc["tool"])
        assertFalse(telemetrySnapshot.mdc["sessionId"].isNullOrBlank(), "sessionId must be non-blank")
        val requestId = telemetrySnapshot.mdc["requestId"]
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
        val snapshot = probeSnapshots.single().mdc
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
        val noActorSnapshot = noActorSnapshots.lastOrNull { it.mdc["tool"] == "mdc_probe" }
        assertTrue(noActorSnapshot != null)
        assertFalse(noActorSnapshot!!.mdc.containsKey("actorId"), "No actor supplied: actorId key must be absent")
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

    /**
     * A tool whose `execute` logs one INFO line after hopping to a caller-supplied
     * [CoroutineDispatcher]. Used by the O1 test with a fixed single-thread dispatcher so every
     * call's probe log line is emitted on the SAME OS thread, exercising real thread reuse — a
     * bare `MDC.put` with no cleanup would leak onto that reused thread and this test would catch
     * it (see the class KDoc's red-proof note).
     */
    private fun mdcProbeToolOnDispatcher(dispatcher: CoroutineDispatcher): ToolDefinition =
        object : ToolDefinition {
            override val name = "mdc_probe"
            override val description = "Logs one INFO line from execute() on a fixed single-thread dispatcher"
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.SYSTEM
            private val toolLogger = LoggerFactory.getLogger("mdc.probe.tool")

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement {
                withContext(dispatcher) { toolLogger.info("probe from execute (fixed single-thread dispatcher)") }
                return JsonObject(emptyMap())
            }
        }

    /**
     * O1: MDC-leak test that actually exercises a reused OS thread (task-scope note on
     * b5081c9b). The prior `S12` test asserted `MDC.get` on the JUnit thread, which the handler
     * never runs on — that assertion would pass even with a bare `MDC.put` and no cleanup. This
     * test instead pins every probe call to the SAME single-thread executor:
     *
     * 1. Call 1 (with an actor) proves MDC propagates onto the pooled thread (not vacuous).
     * 2. Call 2 (no actor) proves each call gets its OWN scope, not a leftover from call 1.
     * 3. A plain (non-coroutine) [Callable] submitted afterward to that SAME thread proves the
     *    thread's MDC was restored when each call's scope exited.
     *
     * Red-proof (recorded in implementation-notes): with the adapter's `MDCContext(...)` swapped
     * for a no-op `ThreadContextElement.restoreThreadContext`, step 3 fails because the thread
     * still carries call 2's fields.
     */
    @Test
    fun `O1 - MDC is scoped per call and restored on a reused thread`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = executor.asCoroutineDispatcher()
            adapter.registerToolWithServer(server, mdcProbeToolOnDispatcher(dispatcher), dummyContext)

            val (_, call1Snapshots) =
                captureMdcSnapshots("mdc.probe.tool") {
                    runBlocking { client.callTool(callToolWithActor("mdc_probe", "leaky-actor")) }
                }
            assertEquals(1, call1Snapshots.size)
            val call1 = call1Snapshots.single().mdc
            assertEquals("leaky-actor", call1["actorId"], "L1: call 1's event must carry the reported actorId")
            val call1RequestId = call1["requestId"]
            assertTrue(
                call1RequestId != null && uuidPattern.matcher(call1RequestId).matches(),
                "L1: call 1's requestId must be a UUID: $call1RequestId"
            )

            val (_, call2Snapshots) =
                captureMdcSnapshots("mdc.probe.tool") {
                    runBlocking { client.callTool(name = "mdc_probe", arguments = emptyMap()) }
                }
            assertEquals(1, call2Snapshots.size)
            val call2 = call2Snapshots.single().mdc
            assertFalse(call2.containsKey("actorId"), "L2: call 2 supplied no actor, actorId key must be absent")
            val call2RequestId = call2["requestId"]
            assertTrue(
                call2RequestId != null && call2RequestId != call1RequestId,
                "L2: each call must get its own requestId"
            )

            // L3: a plain Callable on the SAME thread — no MDCContext applies to it, so any MDC
            // value visible here would be genuine leakage surviving from the tool calls above.
            val residualMdc = executor.submit(Callable { MDC.getCopyOfContextMap() }).get()
            assertTrue(
                residualMdc.isNullOrEmpty(),
                "L3: MDC must be restored on the reused thread after each call's scope exits: $residualMdc"
            )
        } finally {
            executor.shutdown()
        }
    }

    /** A tool whose `validateParams` always throws [ToolValidationException]. */
    private fun validationErrorTool(toolName: String): ToolDefinition =
        object : ToolDefinition {
            override val name = toolName
            override val description = "Always throws ToolValidationException from validateParams"
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.SYSTEM

            override fun validateParams(params: JsonElement): Unit =
                throw ToolValidationException("forced validation failure for MDC coverage test")

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement = JsonObject(emptyMap())
        }

    /** A tool whose `execute` always throws [PerRootConfigUnavailableException]. */
    private fun configUnavailableTool(toolName: String): ToolDefinition =
        object : ToolDefinition {
            override val name = toolName
            override val description = "Always throws PerRootConfigUnavailableException from execute"
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.SYSTEM

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement = throw PerRootConfigUnavailableException(UUID.randomUUID(), "config unavailable")
        }

    /** A tool whose `execute` always throws a generic [IllegalStateException]. */
    private fun internalErrorTool(toolName: String): ToolDefinition =
        object : ToolDefinition {
            override val name = toolName
            override val description = "Always throws IllegalStateException from execute"
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.SYSTEM

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement = throw IllegalStateException("forced internal error for MDC coverage test")
        }

    /**
     * L4 (O2): the `ToolValidationException` WARN path (`McpToolAdapter.kt`'s `validateParams`
     * catch block) still carries full MDC correlation — the adapter KDoc says every line in
     * flight while a call is in progress carries these fields, including its own error-logging.
     */
    @Test
    fun `O2 - the ToolValidationException WARN path carries MDC correlation fields`() {
        val toolName = "mdc_validation_error_probe"
        adapter.registerToolWithServer(server, validationErrorTool(toolName), dummyContext)

        val (_, snapshots) =
            captureMdcSnapshots(McpToolAdapter::class.java.name) {
                runBlocking { client.callTool(name = toolName, arguments = emptyMap()) }
            }

        val warnSnapshot = snapshots.lastOrNull { it.level == Level.WARN && it.mdc["tool"] == toolName }
        assertTrue(warnSnapshot != null, "Expected a WARN-level MDC-tagged log line from the validation-error path")
        assertEquals("mcp", warnSnapshot!!.mdc["transport"])
        assertFalse(warnSnapshot.mdc["sessionId"].isNullOrBlank(), "sessionId must be non-blank")
        val requestId = warnSnapshot.mdc["requestId"]
        assertTrue(requestId != null && uuidPattern.matcher(requestId).matches(), "requestId must be a UUID: $requestId")
    }

    /**
     * L5 (O2): the `PerRootConfigUnavailableException` WARN path (`McpToolAdapter.kt`'s dedicated
     * catch block) still carries full MDC correlation.
     */
    @Test
    fun `O2 - the PerRootConfigUnavailableException WARN path carries MDC correlation fields`() {
        val toolName = "mdc_config_unavailable_probe"
        adapter.registerToolWithServer(server, configUnavailableTool(toolName), dummyContext)

        val (_, snapshots) =
            captureMdcSnapshots(McpToolAdapter::class.java.name) {
                runBlocking { client.callTool(name = toolName, arguments = emptyMap()) }
            }

        val warnSnapshot = snapshots.lastOrNull { it.level == Level.WARN && it.mdc["tool"] == toolName }
        assertTrue(warnSnapshot != null, "Expected a WARN-level MDC-tagged log line from the config-unavailable path")
        assertEquals("mcp", warnSnapshot!!.mdc["transport"])
        assertFalse(warnSnapshot.mdc["sessionId"].isNullOrBlank(), "sessionId must be non-blank")
        val requestId = warnSnapshot.mdc["requestId"]
        assertTrue(requestId != null && uuidPattern.matcher(requestId).matches(), "requestId must be a UUID: $requestId")
    }

    /**
     * L6 (O2): the generic-`Exception` ERROR path (`McpToolAdapter.kt`'s catch-all) still carries
     * full MDC correlation, including `actorId` when the call supplied one.
     */
    @Test
    fun `O2 - the generic Exception ERROR path carries MDC correlation fields including actorId`() {
        val toolName = "mdc_internal_error_probe"
        adapter.registerToolWithServer(server, internalErrorTool(toolName), dummyContext)

        val (_, snapshots) =
            captureMdcSnapshots(McpToolAdapter::class.java.name) {
                runBlocking { client.callTool(callToolWithActor(toolName, "error-path-actor")) }
            }

        val errorSnapshot = snapshots.lastOrNull { it.level == Level.ERROR && it.mdc["tool"] == toolName }
        assertTrue(errorSnapshot != null, "Expected an ERROR-level MDC-tagged log line from the internal-error path")
        assertEquals("mcp", errorSnapshot!!.mdc["transport"])
        assertFalse(errorSnapshot.mdc["sessionId"].isNullOrBlank(), "sessionId must be non-blank")
        val requestId = errorSnapshot.mdc["requestId"]
        assertTrue(requestId != null && uuidPattern.matcher(requestId).matches(), "requestId must be a UUID: $requestId")
        assertEquals("error-path-actor", errorSnapshot.mdc["actorId"], "ERROR path must also carry actorId")
    }
}
