package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.BatchRunner
import io.github.jpicklyk.mcptask.current.application.api.RequestDecoder
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.bootstrap.installMcpStreamableHttp
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.CollectingSink
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
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
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.streams.toList
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Independent tests (test-author seat, item ab14346c, Q7 host capture) for the MCP side: scenarios S1, S2, S6, S7, S13,
 * S14, S15 of the frozen test-plan, driven through the real [McpOperationAdapter] over the in-memory SDK client/server pair
 * (the production registry with the real call log writer, and the fixture registry with a collecting sink), plus the
 * streamable-HTTP transport for the SDK-registry risk. Q32 port: the scenarios that ran the removed 3.x adapter now run the
 * production registry (`items_read` `context` for the removed get_context, `advance` for the removed advance_item).
 *
 * Oracles (task-scope, frozen at queue phase): MCP host = normalize(clientInfo.name) with no fallback literal; host is
 * recorded per call on `call_log.host` and on every `events.host` row the call writes; two sessions on one Server keep
 * separate hosts; host is data only (responses are identical across hosts apart from reqId); no `CallTelemetry(`
 * constructor call remains in production source outside CallTelemetryFactory.kt; an event written outside any call has
 * a null host.
 *
 * Harnesses reused unchanged: McpCallLogTest.connect shape (Server + McpOperationAdapter + ChannelTransport.createLinkedPair),
 * McpOperationAdapterTest.connect shape, S16 of RestCallLogTest for the HTTP wire shapes. Rows are read with
 * [CallLogRows.read] and with raw JDBC for `events`.
 *
 * Narrowest reverts for the NEW-SURFACE scenarios: S2 the adapter passes a null client name; S6 EventRecorder omits
 * host; S14 a stray `CallTelemetry(` call site returns.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class McpHostCaptureTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private lateinit var rig: EventLogRig
    private lateinit var writer: CallLogWriter
    private val servers = mutableListOf<Server>()
    private val clients = mutableListOf<Client>()

    private val fx = FixtureTools()
    private val sink = CollectingSink()
    private val env: ToolExecutionContext by lazy {
        ToolExecutionContext(repositoryProvider = db.repositoryProvider(), unitOfWork = db.unitOfWork())
    }

    @BeforeEach
    fun setUp() {
        rig = EventLogRig.build(db.db, dir, EventLogRig.GATED_CONFIG)
        writer = CallLogWriter(rig.composition.unitOfWork, SqliteCallLogStore(db.databaseManager), flushInterval = 1.hours)
    }

    @AfterEach
    fun tearDown(): Unit =
        runBlocking {
            clients.forEach { runCatching { withTimeoutOrNull(5_000) { it.close() } } }
            servers.forEach { runCatching { withTimeoutOrNull(5_000) { it.close() } } }
        }

    // ------------------------------------------------------------------ helpers

    private fun bareServer(): Server =
        Server(serverInfo = Implementation(name = "q7-server", version = "1.0.0"), options = inMemoryTestServerOptions()).also {
            servers +=
                it
        }

    /** One Server carrying the production registry through the real McpOperationAdapter with the real call log writer. */
    private fun serverProd(): Server =
        bareServer().also {
            McpOperationAdapter(
                buildToolDispatcher(rig.ctx),
                callLog = writer
            ).register(it, ToolRegistry(buildToolSpecs()), ToolProfile.ORCHESTRATION)
        }

    /** The health view of `items_read` `context` (the removed get_context with no item). */
    private suspend fun Client.context(vararg args: Pair<String, JsonElement>): CallToolResult =
        call(
            "items_read",
            "select" to jstr("context"),
            *args
        )

    private fun operationAdapter(): McpOperationAdapter =
        McpOperationAdapter(
            ToolDispatcher(RequestDecoder(), BatchRunner(env.unitOfWork, env.idempotency), env),
            callLog = sink
        )

    /** One Server carrying the fixture registry through the real 4.0 McpOperationAdapter and a collecting sink. */
    private fun server40(): Server = bareServer().also { operationAdapter().register(it, fx.registry, ToolProfile.ORCHESTRATION) }

    /** Connects one more client with [clientName] to [server]; several clients may share one Server. */
    private suspend fun attach(
        server: Server,
        clientName: String
    ): Client {
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client =
            Client(
                clientInfo = Implementation(name = clientName, version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities())
            )
        server.createSession(serverTransport)
        client.connect(clientTransport)
        clients += client
        return client
    }

    private fun jstr(value: String): JsonElement = JsonPrimitive(value)

    private suspend fun Client.call(
        tool: String,
        vararg args: Pair<String, JsonElement>
    ): CallToolResult =
        callTool(
            buildCallToolRequest {
                name = tool
                arguments(JsonObject(mapOf(*args)))
            }
        )

    private suspend fun Client.callFx(
        tool: String,
        text: String
    ): CallToolResult =
        callTool(
            buildCallToolRequest {
                name = tool
                arguments(Json.parseToJsonElement(text).jsonObject)
            }
        )

    private fun reqIdOf(result: CallToolResult): String =
        assertNotNull(
            result.meta
                ?.get("reqId")
                ?.jsonPrimitive
                ?.contentOrNull,
            "_meta.reqId must be present: ${result.meta}"
        )

    private suspend fun flush(): List<Map<String, Any?>> {
        writer.flushNow()
        return CallLogRows.read(db.jdbcUrl)
    }

    /** (req_id, host) of every events row, ordered by seq, straight from the table. */
    private fun eventHosts(): List<Pair<String?, String?>> =
        DriverManager.getConnection(db.jdbcUrl).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT req_id, host FROM events ORDER BY seq").use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString(1) to rs.getString(2))
                    }
                }
            }
        }

    /** The comparable part of a result: payload, text, error flag and meta with the call's own reqId masked. */
    private fun scrubbed(result: CallToolResult): String {
        val reqId = reqIdOf(result)
        val text = result.content.filterIsInstance<TextContent>().joinToString("|") { it.text }
        return "${result.structuredContent}|$text|${result.isError}|${result.meta}".replace(reqId, "<REQ>")
    }

    // ------------------------------------------------------------------ S2 (production registry)

    @Test
    fun `S2 a production tool call records the normalized clientInfo name as call_log host`(): Unit =
        runBlocking {
            val client = attach(serverProd(), "Claude Code")
            val result = client.context()
            val reqId = reqIdOf(result)

            val row = flush().single()
            assertEquals(reqId, row["req_id"])
            assertEquals("mcp", row["surface"])
            assertEquals("claude code", row["host"], "N3 + N4 applied to clientInfo.name")
            assertTrue(!(row["session_id"] as String?).isNullOrBlank(), "fixture: this is a real MCP session row")
        }

    @Test
    fun `S2 a blank or empty clientInfo name records a null host with no fallback literal`(): Unit =
        runBlocking {
            val server = serverProd()
            val blank = attach(server, "   ")
            val empty = attach(server, "")
            val control = attach(server, "Named")
            val rb = blank.context()
            val re = empty.context()
            val rc = control.context()
            val rows = flush().associateBy { it["req_id"] }
            assertNull(rows.getValue(reqIdOf(rb))["host"], "blank name")
            assertNull(rows.getValue(reqIdOf(re))["host"], "empty name")
            assertEquals("named", rows.getValue(reqIdOf(rc))["host"], "control: host capture works on the same server")
        }

    @Test
    fun `S2 caller text in the clientInfo name is bounded by the normalization`(): Unit =
        runBlocking {
            val server = serverProd()
            val dirty = attach(server, "  Pi\u0000-Agent\t ")
            val long = attach(server, "X".repeat(70))
            val rd = dirty.context()
            val rl = long.context()
            val rows = flush().associateBy { it["req_id"] }
            assertEquals("pi-agent", rows.getValue(reqIdOf(rd))["host"], "N2 removes NUL and tab, N3 trims, N4 lowercases")
            assertEquals("x".repeat(64), rows.getValue(reqIdOf(rl))["host"], "N5 keeps the first 64")
        }

    // ------------------------------------------------------------------ S6

    @Test
    fun `S6 a rejected transition event carries the host too`(): Unit =
        runBlocking {
            val client = attach(serverProd(), "Gate Agent")
            val item = rig.seed("S6 gated", tags = EventLogRig.GATED_TAG)
            val transitions =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("op", "transition")
                            put("itemId", item.id.toString())
                            put("trigger", "start")
                        }
                    )
                }
            val result = client.call("advance", "ops" to transitions)
            val reqId = reqIdOf(result)
            val rows = flush().associateBy { it["req_id"] }
            assertEquals("gate agent", rows.getValue(reqId)["host"])
            val mine = eventHosts().filter { it.first == reqId }
            assertTrue(
                mine.isNotEmpty(),
                "fixture: the gated advance wrote a rejection event stamped with the call's reqId: ${eventHosts()}"
            )
            assertTrue(mine.all { it.second == "gate agent" }, "$mine")
        }

    // ------------------------------------------------------------------ S7

    @Test
    fun `S7 two clients on one production Server keep separate hosts under interleaved and concurrent calls`(): Unit =
        runBlocking {
            val server = serverProd()
            val alpha = attach(server, "alpha")
            val beta = attach(server, "Beta")
            val expected = mutableMapOf<String, String>()

            suspend fun go(
                client: Client,
                host: String
            ) {
                expected[reqIdOf(client.context())] = host
            }
            go(alpha, "alpha")
            go(beta, "beta")
            go(beta, "beta")
            go(alpha, "alpha")
            val concurrent =
                (1..24)
                    .map { i ->
                        async {
                            val even = i % 2 == 0
                            reqIdOf((if (even) alpha else beta).context()) to
                                (if (even) "alpha" else "beta")
                        }
                    }.awaitAll()
            concurrent.forEach { (reqId, host) -> expected[reqId] = host }

            val rows = flush()
            assertEquals(expected.size, rows.size, "one row per call")
            for (row in rows) {
                assertEquals(expected.getValue(row["req_id"] as String), row["host"], "row ${row["req_id"]}")
            }
            val sessionsByHost =
                rows
                    .groupBy(
                        { row -> row["host"] as String? },
                        { row -> row["session_id"] as String? }
                    ).mapValues { it.value.toSet() }
            assertEquals(setOf<String?>("alpha", "beta"), sessionsByHost.keys, "both hosts appear")
            assertEquals(1, sessionsByHost.getValue("alpha").size, "alpha is one session")
            assertEquals(1, sessionsByHost.getValue("beta").size, "beta is one session")
            assertNotEquals(sessionsByHost.getValue("alpha"), sessionsByHost.getValue("beta"), "fixture: the two sessions really differ")
        }

    @Test
    fun `S7 two clients with the same name still record the same host and different sessions`(): Unit =
        runBlocking {
            val server = serverProd()
            val one = attach(server, "Same Name")
            val two = attach(server, "same name")
            val r1 = one.context()
            val r2 = two.context()
            val rows = flush().associateBy { it["req_id"] }
            assertEquals("same name", rows.getValue(reqIdOf(r1))["host"])
            assertEquals("same name", rows.getValue(reqIdOf(r2))["host"])
            assertNotEquals(rows.getValue(reqIdOf(r1))["session_id"], rows.getValue(reqIdOf(r2))["session_id"])
        }

    @Test
    fun `S7 two clients on one 40 Server keep separate hosts including error results`(): Unit =
        runBlocking {
            val server = server40()
            val alpha = attach(server, "alpha")
            val beta = attach(server, "Beta")
            val expected = mutableMapOf<String, String>()

            suspend fun go(
                client: Client,
                host: String,
                id: String
            ) {
                expected[reqIdOf(client.callFx("fx_read", """{"select":"get","id":"$id"}"""))] = host
            }
            go(alpha, "alpha", FixtureTools.GET_OK)
            go(beta, "beta", FixtureTools.GET_OK)
            go(beta, "beta", FixtureTools.GET_MISSING)
            go(alpha, "alpha", FixtureTools.GET_MISSING)
            val concurrent =
                (1..20)
                    .map { i ->
                        async {
                            val even = i % 2 == 0
                            reqIdOf((if (even) alpha else beta).callFx("fx_read", """{"select":"get","id":"${FixtureTools.GET_OK}"}""")) to
                                (if (even) "alpha" else "beta")
                        }
                    }.awaitAll()
            concurrent.forEach { (reqId, host) -> expected[reqId] = host }

            assertEquals(expected.size, sink.records.size, "one record per call")
            for (record in sink.records) {
                assertEquals(expected.getValue(record.reqId), record.host, "record ${record.reqId} (${record.outcome})")
            }
            assertTrue(sink.records.any { it.outcome == "error" }, "fixture: error results are among the records")
        }

    // ------------------------------------------------------------------ S1 (4.0)

    @Test
    fun `S1 a 40 tool call records the normalized clientInfo name`(): Unit =
        runBlocking {
            val client = attach(server40(), "Claude Code")
            val result = client.callFx("fx_read", """{"select":"get","id":"${FixtureTools.GET_OK}"}""")
            assertTrue(result.isError != true, "fixture: the read succeeds")
            val record = sink.records.single()
            assertEquals(reqIdOf(result), record.reqId)
            assertEquals("claude code", record.host)
        }

    @Test
    fun `S1 a 40 error call and a 40 validation error both record the host`(): Unit =
        runBlocking {
            val client = attach(server40(), "Claude Code")
            val missing = client.callFx("fx_read", """{"select":"get","id":"${FixtureTools.GET_MISSING}"}""")
            val invalid = client.callFx("fx_read", """{"select":"nope"}""")
            assertEquals(true, missing.isError, "fixture")
            assertEquals(true, invalid.isError, "fixture")
            assertEquals(2, sink.records.size)
            assertTrue(sink.records.all { it.outcome == "error" })
            assertTrue(sink.records.all { it.host == "claude code" }, "${sink.records.map { it.host }}")
        }

    @Test
    fun `S1 a 40 call from a blank client name records a null host`(): Unit =
        runBlocking {
            val server = server40()
            val blank = attach(server, "  ")
            val control = attach(server, "Ctl")
            val rb = blank.callFx("fx_read", """{"select":"get","id":"${FixtureTools.GET_OK}"}""")
            val rc = control.callFx("fx_read", """{"select":"get","id":"${FixtureTools.GET_OK}"}""")
            val byReq = sink.records.associateBy { it.reqId }
            assertNull(byReq.getValue(reqIdOf(rb)).host)
            assertEquals("ctl", byReq.getValue(reqIdOf(rc)).host, "control: capture works on the same server")
        }

    // ------------------------------------------------------------------ S13

    @Test
    fun `S13 the same production read from two sessions with different hosts returns identical results apart from reqId`(): Unit =
        runBlocking {
            val server = serverProd()
            val item = rig.seed("S13 read")
            val x = attach(server, "x")
            val y = attach(server, "y")
            val args = arrayOf("itemId" to jstr(item.id.toString()))
            val rx = x.context(*args)
            val ry = y.context(*args)
            assertNotEquals(reqIdOf(rx), reqIdOf(ry), "fixture: each call has its own reqId")
            assertTrue(
                rx.structuredContent.toString().contains("S13 read"),
                "fixture: the payload is the real item: ${rx.structuredContent}"
            )
            assertEquals(scrubbed(rx), scrubbed(ry))
            val rows = flush().associateBy { it["req_id"] }
            assertEquals("x", rows.getValue(reqIdOf(rx))["host"])
            assertEquals("y", rows.getValue(reqIdOf(ry))["host"])
        }

    @Test
    fun `S13 the same 40 read from two sessions with different hosts returns identical envelopes apart from reqId`(): Unit =
        runBlocking {
            val server = server40()
            val x = attach(server, "x")
            val y = attach(server, "y")
            val text = """{"select":"get","id":"${FixtureTools.GET_OK}"}"""
            val rx = x.callFx("fx_read", text)
            val ry = y.callFx("fx_read", text)
            assertNotEquals(reqIdOf(rx), reqIdOf(ry), "fixture: each call has its own reqId")
            assertTrue(rx.structuredContent.toString().contains("got:"), "fixture: a real envelope: ${rx.structuredContent}")
            assertEquals(scrubbed(rx), scrubbed(ry))
            val byReq = sink.records.associateBy { it.reqId }
            assertEquals("x", byReq.getValue(reqIdOf(rx)).host)
            assertEquals("y", byReq.getValue(reqIdOf(ry)).host)
        }

    // ------------------------------------------------------------------ probe: idempotent replay

    // ------------------------------------------------------------------ S15

    // ------------------------------------------------------------------ streamable HTTP

    private val accept = "application/json, text/event-stream"

    private suspend fun HttpClient.rpc(
        sessionId: String?,
        body: String
    ): HttpResponse =
        post("/mcp") {
            header(HttpHeaders.Accept, accept)
            if (sessionId != null) header("mcp-session-id", sessionId)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.initSession(clientName: String): String {
        val init =
            rpc(
                null,
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"$clientName","version":"1.0"}}}"""
            )
        val sessionId = assertNotNull(init.headers["mcp-session-id"], "initialize returns a session id")
        rpc(sessionId, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        return sessionId
    }

    private suspend fun HttpClient.overview(
        sessionId: String,
        id: Int
    ): String {
        val response =
            rpc(
                sessionId,
                """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"items_read","arguments":{"select":"context"}}}"""
            )
        val text = response.bodyAsText()
        val payload =
            text
                .lineSequence()
                .firstOrNull { it.startsWith("data:") }
                ?.removePrefix("data:")
                ?.trim()
                ?: text.trim()
        val result = Json.parseToJsonElement(payload).jsonObject["result"]!!.jsonObject
        return assertNotNull(
            result["_meta"]
                ?.jsonObject
                ?.get("reqId")
                ?.jsonPrimitive
                ?.content,
            "the over-HTTP result carries _meta.reqId: $result"
        )
    }

    @Test
    fun `S7 over streamable HTTP two sessions on one Server keep separate hosts`() =
        testApplication {
            val server =
                Server(
                    serverInfo = Implementation(name = "q7-http", version = "1.0.0"),
                    options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)))
                )
            servers += server
            McpOperationAdapter(buildToolDispatcher(rig.ctx), callLog = writer)
                .register(server, ToolRegistry(buildToolSpecs()), ToolProfile.ORCHESTRATION)
            application { installMcpStreamableHttp(server, appConfig = AppConfig.fromEnv()) }

            val alpha = client.initSession("HttpAlpha")
            val beta = client.initSession("HttpBeta")
            assertNotEquals(alpha, beta, "fixture: two distinct sessions")
            val r1 = client.overview(alpha, 2)
            val r2 = client.overview(beta, 2)
            val r3 = client.overview(alpha, 3)
            val r4 = client.overview(beta, 3)

            val rows = flush().associateBy { it["req_id"] }
            assertEquals("httpalpha", rows.getValue(r1)["host"])
            assertEquals("httpbeta", rows.getValue(r2)["host"])
            assertEquals("httpalpha", rows.getValue(r3)["host"])
            assertEquals("httpbeta", rows.getValue(r4)["host"])
            assertEquals(
                rows.getValue(r1)["session_id"],
                rows.getValue(r3)["session_id"],
                "fixture: one HTTP session is one MCP session id"
            )
            assertNotEquals(rows.getValue(r1)["session_id"], rows.getValue(r2)["session_id"], "fixture: the two HTTP sessions differ")
        }

    // ------------------------------------------------------------------ S14

    private fun mainSourceRoot(): Path =
        listOf(Paths.get("src/main/kotlin"), Paths.get("current/src/main/kotlin"))
            .firstOrNull { Files.isDirectory(it) }
            ?: error("production source root not found from ${Paths.get("").toAbsolutePath()}")

    private val constructorCall = Regex("""(?<![A-Za-z0-9_])CallTelemetry\s*\(""")
    private val constructorReference = Regex("""::\s*CallTelemetry(?![A-Za-z0-9_])""")
    private val classDeclaration = Regex("""\bclass\s+CallTelemetry\s*\(""")
    private val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

    private fun stripComments(text: String): String =
        blockComment
            .replace(text) { m -> "\n".repeat(m.value.count { it == '\n' }) }
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `S14 the scan patterns match what they must and ignore what they must`() {
        assertTrue(constructorCall.containsMatchIn("val t = CallTelemetry(reqId = r, surface = s, sessionId = null)"))
        assertTrue(constructorCall.containsMatchIn("x = io.github.jpicklyk.mcptask.current.application.telemetry.CallTelemetry (a, b, c)"))
        assertTrue(constructorReference.containsMatchIn("val make = ::CallTelemetry"))
        assertTrue(!constructorCall.containsMatchIn("CallTelemetryFactory.mcp(reqId, sessionId, name)"), "the factory is not a constructor")
        assertTrue(!constructorCall.containsMatchIn("val x = MyCallTelemetry(1)"), "a different class")
        assertTrue(classDeclaration.containsMatchIn("class CallTelemetry(val reqId: String)"))
        assertEquals("a\n\nb", stripComments("a\n/* x\n*/b"), "block comments keep their line count")
        assertEquals("val t = 1 ", stripComments("val t = 1 // CallTelemetry("))
    }

    @Test
    fun `S14 no production source outside CallTelemetryFactory constructs a CallTelemetry`() {
        val root = mainSourceRoot()
        val files = Files.walk(root).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }
        assertTrue(files.size > 100, "vacuity control: the scan really covers the production tree (${files.size} files)")
        assertTrue(files.any { it.fileName.toString() == "CurrentMain.kt" }, "vacuity control: the known entry point is scanned")

        var declarationFound = false
        val hits = mutableListOf<String>()
        for (file in files) {
            val clean = stripComments(Files.readAllLines(file, Charsets.UTF_8).joinToString("\n"))
            clean.lines().forEachIndexed { index, line ->
                if (classDeclaration.containsMatchIn(line)) declarationFound = true
                val isConstruction =
                    (constructorCall.containsMatchIn(line) && !classDeclaration.containsMatchIn(line)) ||
                        constructorReference.containsMatchIn(line)
                if (isConstruction && file.fileName.toString() != "CallTelemetryFactory.kt") {
                    hits += "${root.relativize(file)}:${index + 1}"
                }
            }
        }
        assertTrue(declarationFound, "vacuity control: the declaring file was scanned and its declaration line was excluded, not missed")
        assertEquals(emptyList(), hits, "CallTelemetry must be built only by CallTelemetryFactory")
    }
}
