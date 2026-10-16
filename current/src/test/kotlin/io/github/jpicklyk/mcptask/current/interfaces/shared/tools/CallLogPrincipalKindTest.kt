package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.bootstrap.CompositionResult
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.bootstrap.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN_ID
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.makeTestAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpAuthHttpFixture
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpAuthReply
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.closeInMemoryPair
import io.github.jpicklyk.mcptask.current.interfaces.mcp.inMemoryTestServerOptions
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Independent tests (test-author seat, item 66d8b0d3) for `call_log.principal_kind`: the row records the authenticated principal's
 * [PrincipalKind] wire value (`human` / `agent` / `service`), not the audit claim's actor kind (always `external`). Scenarios S1 to
 * S9 of the frozen test-plan, driven end to end through the transports (the `/mcp` streamable HTTP mount, the in-memory MCP pair,
 * the REST routes) into the real `call_log` table, plus the `report usage groupBy principalKind` consequence.
 *
 * Oracles (written from the plan, never from a run): O1 the item summary (`call_log.principal_kind` is the authenticated principal's
 * PrincipalKind wire value), O2 the PrincipalKind KDoc (wire `human` / `agent` / `service`), O3 the LOCAL_PRINCIPAL KDoc (kind AGENT,
 * audited as the bare id `local`), O4 the fleet-deployment call-log section (a call with no principal holds none: SQL NULL), O5 the
 * auditClaim KDoc and api-reference (the audited actor kind of a note and of an event stays `external` whatever Principal.kind is),
 * and api-reference `report` `usage` (`groupBy` principalKind, items `{key: {<groupBy>: value}, calls, ...}`). Every expected
 * kind is a literal chosen from the credential the test itself built; no expected value is read from a run.
 *
 * Negative-assertion conditions (what makes each fail without the fix): the kind assertions fail when the column carries the audit
 * claim's `external`; S2 and S3 mix three kinds under one transport so a constant column fails; the S6/S7 NULL guards fail if a
 * fix invents a value for a call with no principal (and read SQL NULL, so an empty string fails too); S8 fails if the kind is moved
 * into the audit claim (note actor kind or events.principal_kind would then read `human`); S5 fails if the report groups the rows
 * under `external`.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions, no disjunctive assertions.
 *
 * Domain invariants satisfied by construction: principals are plain values built with an id, a kind, a proof status, an unrestricted
 * scope and capabilities; items are created through the real `items_write` create with a declared type or seeded through the
 * undecorated repository (a root has no parent); token-file entries carry the scope, capabilities and sha256 the loader requires.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class CallLogPrincipalKindTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val pairs = mutableListOf<Pair<Client, Server>>()

    @AfterEach
    fun tearDown(): Unit = runBlocking { pairs.forEach { closeInMemoryPair(it.first, it.second) } }

    private val humanToken = "kind-human-token"
    private val agentToken = "kind-agent-token"
    private val serviceToken = "kind-service-token"

    private val readWrite = setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS, ApiCapability.WRITE_NOTES)

    private fun entryOf(
        raw: String,
        id: String,
        kind: PrincipalKind,
        capabilities: Set<ApiCapability> = readWrite
    ): Pair<HashBytes, BearerTokenStore.TokenEntry> =
        HashBytes(McpAuthHttpFixture.sha256(raw)) to
            BearerTokenStore.TokenEntry(
                Principal(id, kind, ProofStatus.ISSUED, AccessScope.unrestricted(), capabilities),
                expiresAt = null
            )

    private fun threeKinds() =
        mapOf(
            entryOf(humanToken, "hana", PrincipalKind.HUMAN),
            entryOf(agentToken, "aria", PrincipalKind.AGENT),
            entryOf(serviceToken, "sven", PrincipalKind.SERVICE)
        )

    private fun fixture() = McpAuthHttpFixture(EventLogRig.build(db.db, dir), db.databaseManager)

    /** `initialize` then one `tools/call` of [tool] on [path] under [token]. */
    private suspend fun McpAuthHttpFixture.callOnce(
        client: HttpClient,
        token: String,
        tool: String,
        arguments: String,
        path: String = "/mcp"
    ): McpAuthReply {
        val session = client.handshake(path, token)
        return client.callTool(path, token, session, tool, arguments)
    }

    private fun strings(sql: String): List<String?> =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString(1))
                    }
                }
            }
        }

    // ------------------------------------------------------------------ S1 / S2 MCP over HTTP bearer

    @Test
    fun `S1 an MCP bearer call under a HUMAN credential records principal_kind human`() {
        val f = fixture()
        val entries = mapOf(entryOf(humanToken, "hana", PrincipalKind.HUMAN))
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), ApiAuthConfig.Bearer(tokens = entries), entries) } }
            with(f) {
                val reply = callOnce(client, humanToken, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(reply.toolCallSucceeded(), "the authenticated create must succeed: ${reply.body}")

                runBlocking { f.writer.flushNow() }
                val row = CallLogRows.forTool(db.jdbcUrl, "items_write").single()
                assertEquals("api:hana", row["principal_id"], "the audit id is unchanged")
                assertEquals("human", row["principal_kind"], "the row carries the credential's PrincipalKind wire value")
            }
        }
    }

    @Test
    fun `S2 one call per credential kind records three distinct kinds, human agent and service`() {
        val f = fixture()
        val entries = threeKinds()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), ApiAuthConfig.Bearer(tokens = entries), entries) } }
            with(f) {
                for (token in listOf(humanToken, agentToken, serviceToken)) {
                    val reply = callOnce(client, token, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                    assertTrue(reply.toolCallSucceeded(), "$token: ${reply.body}")
                }
                runBlocking { f.writer.flushNow() }
                val kindById =
                    CallLogRows.forTool(db.jdbcUrl, "items_write").associate {
                        (it["principal_id"] as String) to
                            (it["principal_kind"] as String?)
                    }
                assertEquals(
                    mapOf("api:hana" to "human", "api:aria" to "agent", "api:sven" to "service"),
                    kindById,
                    "each credential's kind, not one constant"
                )
            }
        }
    }

    @Test
    fun `S2 a refused call by a HUMAN credential still records the credential kind, and so does its allowed control`() {
        val f = fixture()
        val all =
            mapOf(
                entryOf(humanToken, "hana", PrincipalKind.HUMAN, setOf(ApiCapability.READ)),
                entryOf(agentToken, "aria", PrincipalKind.AGENT)
            )
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(all), ApiAuthConfig.Bearer(tokens = all), all) } }
            with(f) {
                val refused = callOnce(client, humanToken, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertFalse(refused.toolCallSucceeded(), "control: a READ-only credential cannot write: ${refused.body}")
                val allowed = callOnce(client, agentToken, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(allowed.toolCallSucceeded(), "control: the write credential can: ${allowed.body}")

                runBlocking { f.writer.flushNow() }
                val byId = CallLogRows.forTool(db.jdbcUrl, "items_write").associateBy { it["principal_id"] }
                assertEquals("error", byId.getValue("api:hana")["outcome"], "the refused row is an error row")
                assertEquals("human", byId.getValue("api:hana")["principal_kind"], "an error row keeps the credential kind")
                assertEquals("ok", byId.getValue("api:aria")["outcome"])
                assertEquals("agent", byId.getValue("api:aria")["principal_kind"])
            }
        }
    }

    // ------------------------------------------------------------------ S3 REST

    private fun Application.configureRest(
        composition: CompositionResult,
        writer: CallLogWriter,
        auth: ApiAuthConfig.Bearer
    ) {
        install(ContentNegotiation) { json(McpJson) }
        installRestApiRoutes(
            apiConfig = auth,
            eventBus = null,
            effectiveProvider = composition.toolContext.repositoryProvider,
            apiTokenEntries = auth.tokens,
            allowQueryToken = false,
            serverName = "call-log-principal-kind",
            serverVersion = "test",
            serverConfig = composition.serverConfig,
            toolContext = composition.toolContext,
            callLog = writer,
            appConfig = AppConfig.fromEnv()
        )
    }

    private fun newWriter(composition: CompositionResult) =
        CallLogWriter(composition.unitOfWork, SqliteCallLogStore(db.databaseManager), flushInterval = 1.hours)

    @Test
    fun `S3 a REST read records the credential kind for HUMAN, AGENT and SERVICE bearers`(): Unit =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            val auth = ApiAuthConfig.Bearer(tokens = threeKinds())
            application { configureRest(rig.composition, writer, auth) }
            val item = rig.seed("S3 item")

            for (token in listOf(humanToken, agentToken, serviceToken)) {
                val response = client.get("/api/v1/items/${item.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
                assertEquals(HttpStatusCode.OK, response.status, token)
            }
            writer.flushNow()
            val kindById = CallLogRows.read(db.jdbcUrl).associate { (it["principal_id"] as String) to (it["principal_kind"] as String?) }
            assertEquals(
                mapOf("api:hana" to "human", "api:aria" to "agent", "api:sven" to "service"),
                kindById,
                "one REST row per credential, each with its own kind"
            )
        }

    @Test
    fun `S3 the shared REST test token, a SERVICE principal, records service`(): Unit =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureRest(rig.composition, writer, makeTestAuthConfig()) }
            val item = rig.seed("S3 shared token item")

            val response = client.get("/api/v1/items/${item.id}") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, response.status)
            writer.flushNow()
            val row = CallLogRows.read(db.jdbcUrl).single()
            assertEquals("api:$TEST_TOKEN_ID", row["principal_id"])
            assertEquals("service", row["principal_kind"])
        }

    // ------------------------------------------------------------------ S4 local principal

    private suspend fun connect(
        rig: EventLogRig,
        writer: CallLogWriter,
        access: CallAccess
    ): Client {
        val server = Server(serverInfo = Implementation(name = "kind-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        val adapter = McpOperationAdapter(buildToolDispatcher(rig.ctx), access = { _ -> access }, callLog = writer)
        adapter.register(server, ToolRegistry(buildToolSpecs()), ToolProfile.ORCHESTRATION)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client =
            Client(
                clientInfo = Implementation(name = "kind-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities())
            )
        server.createSession(serverTransport)
        client.connect(clientTransport)
        pairs += client to server
        return client
    }

    private suspend fun Client.call(
        tool: String,
        arguments: String
    ): CallToolResult =
        callTool(
            buildCallToolRequest {
                name = tool
                arguments(Json.parseToJsonElement(arguments).jsonObject)
            }
        )

    @Test
    fun `S4 the local principal over an in-memory MCP call records agent and keeps the bare id local`() {
        val rig = EventLogRig.build(db.db, dir)
        val writer = newWriter(rig.composition)
        runBlocking {
            val client = connect(rig, writer, CallAccess.LOCAL)
            val result = client.call("items_write", McpAuthHttpFixture.CREATE_ARGS)
            assertTrue(result.isError != true, "the local create succeeds: ${result.structuredContent}")
            writer.flushNow()
            val row = CallLogRows.forTool(db.jdbcUrl, "items_write").single()
            assertEquals("local", row["principal_id"], "the audit id of the local principal is the bare id")
            assertEquals("agent", row["principal_kind"], "LOCAL_PRINCIPAL is kind AGENT")
        }
    }

    @Test
    fun `S4 an HTTP mount in Local mode, with no Authorization header, records agent for the local principal`() {
        val f = fixture()
        testApplication {
            application { with(f) { mount(McpAuthMode.Local, ApiAuthConfig.Unauthenticated) } }
            with(f) {
                val session = assertNotNull(client.rpc("/mcp", initializeBody).sessionId)
                client.rpc("/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""", null, session)
                val call =
                    client.rpc(
                        "/mcp",
                        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"items_write","arguments":${McpAuthHttpFixture.CREATE_ARGS}}}""",
                        null,
                        session
                    )
                assertTrue(call.toolCallSucceeded(), call.body)
                runBlocking { f.writer.flushNow() }
                val row = CallLogRows.forTool(db.jdbcUrl, "items_write").single()
                assertEquals("local", row["principal_id"])
                assertEquals("agent", row["principal_kind"])
            }
        }
    }

    // ------------------------------------------------------------------ S6 / S7 no principal

    @Test
    @Suppress("ktlint:standard:max-line-length")
    fun `S6 a REST request with no token or an unknown token writes principal_kind NULL, and a valid token beside it writes a kind`(): Unit =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureRest(rig.composition, writer, ApiAuthConfig.Bearer(tokens = threeKinds())) }
            val item = rig.seed("S6 item")

            val noToken = client.get("/api/v1/items/${item.id}")
            val badToken = client.get("/api/v1/items/${item.id}") { header(HttpHeaders.Authorization, "Bearer not-a-known-token") }
            val control = client.get("/api/v1/items/${item.id}") { header(HttpHeaders.Authorization, "Bearer $humanToken") }
            assertEquals(HttpStatusCode.Unauthorized, noToken.status)
            assertEquals(HttpStatusCode.Unauthorized, badToken.status)
            assertEquals(HttpStatusCode.OK, control.status, "control: the same request with a valid credential succeeds")

            writer.flushNow()
            val byReq = CallLogRows.read(db.jdbcUrl).associateBy { it["req_id"] }
            for ((label, response) in listOf("no token" to noToken, "unknown token" to badToken)) {
                val row = byReq.getValue(response.headers["X-Req-Id"])
                assertNull(row["principal_kind"], "$label: SQL NULL, not external and not an empty string")
                assertNull(row["principal_id"], "$label: no principal at all")
            }
            assertEquals("human", byReq.getValue(control.headers["X-Req-Id"])["principal_kind"], "control row carries the kind")
        }

    @Test
    fun `S7 an MCP call under an unauthenticated access writes principal_kind NULL`() {
        val rig = EventLogRig.build(db.db, dir)
        val writer = newWriter(rig.composition)
        runBlocking {
            val client = connect(rig, writer, CallAccess.unauthenticated())
            val result = client.call("items_write", McpAuthHttpFixture.CREATE_ARGS)
            assertTrue(result.isError == true, "an unauthenticated write is refused: ${result.structuredContent}")
            writer.flushNow()
            val row = CallLogRows.forTool(db.jdbcUrl, "items_write").single()
            assertNull(row["principal_kind"], "no principal, no kind: SQL NULL")
            assertNull(row["principal_id"])
        }
    }

    // ------------------------------------------------------------------ S8 audit claim unchanged

    @Test
    fun `S8 a HUMAN credential writing a note leaves the note actor kind and the events principal_kind at external`() {
        val f = fixture()
        val entries = mapOf(entryOf(humanToken, "hana", PrincipalKind.HUMAN))
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), ApiAuthConfig.Bearer(tokens = entries), entries) } }
            val item = f.rig.seed("S8 target")
            with(f) {
                val reply =
                    callOnce(
                        client,
                        humanToken,
                        "notes_write",
                        """{"ops":[{"op":"upsert","itemId":"${item.id}","key":"spec","role":"queue","body":"b"}]}"""
                    )
                assertTrue(reply.toolCallSucceeded(), "the HUMAN credential writes the note: ${reply.body}")

                runBlocking { f.writer.flushNow() }
                assertEquals(
                    "human",
                    CallLogRows.forTool(db.jdbcUrl, "notes_write").single()["principal_kind"],
                    "control: the call log kind moved to the credential kind"
                )
                assertEquals(
                    listOf("external"),
                    strings("SELECT actor_kind FROM notes").map { it?.lowercase() },
                    "the note's actor kind is the audit claim's kind, unchanged"
                )
                val eventKinds = strings("SELECT principal_kind FROM events").map { it?.lowercase() }
                assertTrue(eventKinds.isNotEmpty(), "the note write recorded at least one event row")
                assertEquals(setOf<String?>("external"), eventKinds.toSet(), "every event row keeps the audit actor kind: $eventKinds")
            }
        }
    }

    // ------------------------------------------------------------------ S9 token-file kinds, replay

    @Test
    fun `S9 a token file kind written in upper case is stored lower case, and an absent kind is service`() {
        val file = dir.resolve("kind-tokens.yaml").toFile()

        fun block(
            id: String,
            raw: String,
            kindLine: String
        ) = "  - id: $id\n" +
            "    token_sha256: \"${McpAuthHttpFixture.sha256(raw).joinToString("") { "%02x".format(it) }}\"\n" +
            "    scope:\n      root_ids: null\n      tags_include: []\n" +
            "    capabilities: [read]\n" + kindLine
        file.writeText(
            "version: 1\ntokens:\n" +
                block("upper", humanToken, "    kind: HUMAN\n") +
                block("bare", serviceToken, "")
        )
        val entries = BearerTokenStore(file.absolutePath).loadWithEntries()
        val f = fixture()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), ApiAuthConfig.Bearer(tokens = entries), entries) } }
            with(f) {
                for (token in listOf(humanToken, serviceToken)) {
                    val reply = callOnce(client, token, "items_read", """{"select":"list"}""")
                    assertTrue(reply.toolCallSucceeded(), "$token: ${reply.body}")
                }
                runBlocking { f.writer.flushNow() }
                val kindById =
                    CallLogRows.forTool(db.jdbcUrl, "items_read").associate {
                        (it["principal_id"] as String) to
                            (it["principal_kind"] as String?)
                    }
                assertEquals(mapOf("api:upper" to "human", "api:bare" to "service"), kindById)
            }
        }
    }

    @Test
    fun `probe a replayed keyed write records the credential kind on the original row and on the replay row`() {
        val f = fixture()
        val entries = mapOf(entryOf(humanToken, "hana", PrincipalKind.HUMAN))
        val keyed = """{"ops":[{"op":"create","title":"Keyed","type":"test-item","idempotencyKey":"kind-replay-1"}]}"""
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), ApiAuthConfig.Bearer(tokens = entries), entries) } }
            with(f) {
                val session = client.handshake("/mcp", humanToken)
                val first = client.callTool("/mcp", humanToken, session, "items_write", keyed)
                val second = client.callTool("/mcp", humanToken, session, "items_write", keyed)
                assertTrue(first.toolCallSucceeded(), first.body)
                assertTrue("\"replayed\":true" in second.structured.toString(), "control: the second send replays: ${second.body}")
                assertEquals(1, McpAuthHttpFixture.workItemCount(db.jdbcUrl), "executed once")

                runBlocking { f.writer.flushNow() }
                val rows = CallLogRows.forTool(db.jdbcUrl, "items_write")
                assertEquals(listOf(0L, 1L), rows.map { it["replayed"] as Long }.sorted(), "one original, one replay")
                assertEquals(listOf("human", "human"), rows.map { it["principal_kind"] })
            }
        }
    }

    // ------------------------------------------------------------------ S5 report usage groupBy principalKind

    private fun usageGroups(
        element: JsonElement,
        out: MutableMap<String, Long> = mutableMapOf()
    ): Map<String, Long> {
        when (element) {
            is JsonObject -> {
                val key = element["key"] as? JsonObject
                val calls = element["calls"]?.jsonPrimitive?.longOrNull
                if (key != null && calls != null) {
                    out[key["principalKind"]?.jsonPrimitive?.contentOrNull ?: "<missing>"] = calls
                } else {
                    element.values.forEach { usageGroups(it, out) }
                }
            }
            is JsonArray -> element.forEach { usageGroups(it, out) }
            else -> Unit
        }
        return out
    }

    @Test
    fun `S5 report usage grouped by principalKind separates human from service and has no external group`() {
        val f = fixture()
        val entries = threeKinds()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), ApiAuthConfig.Bearer(tokens = entries), entries) } }
            with(f) {
                repeat(2) { assertTrue(callOnce(client, humanToken, "items_write", McpAuthHttpFixture.CREATE_ARGS).toolCallSucceeded()) }
                assertTrue(callOnce(client, serviceToken, "items_write", McpAuthHttpFixture.CREATE_ARGS).toolCallSucceeded())
                val directorSession = client.handshake("/mcp/director", humanToken)

                runBlocking { f.writer.flushNow() }
                val written = CallLogRows.forTool(db.jdbcUrl, "items_write")
                assertEquals(listOf("human", "human", "service"), written.map { it["principal_kind"] as String }.sorted())
                val expected =
                    CallLogRows
                        .read(db.jdbcUrl)
                        .filter { it["principal_kind"] != null }
                        .groupingBy { it["principal_kind"] as String }
                        .eachCount()
                        .mapValues { it.value.toLong() }
                assertEquals(setOf("human", "service"), expected.keys, "the table holds only the two credential kinds used")

                val report =
                    client.callTool(
                        "/mcp/director",
                        humanToken,
                        directorSession,
                        "report",
                        """{"select":"usage","groupBy":"principalKind"}"""
                    )
                assertTrue(report.toolCallSucceeded(), "the usage report is answered: ${report.body}")
                val groups = usageGroups(assertNotNull(report.structured, report.body)).filterKeys { it != "<missing>" }
                assertEquals(expected, groups, "one group per recorded kind, with the table's counts: ${report.body}")
                assertFalse("external" in groups, "no external group from calls recorded after the fix: $groups")
            }
        }
    }
}
