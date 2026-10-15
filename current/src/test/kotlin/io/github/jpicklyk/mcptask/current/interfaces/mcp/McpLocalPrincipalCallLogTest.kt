package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.infrastructure.identity.LOCAL_PRINCIPAL
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.toCallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.AUDIT_ID_PREFIX
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.LOCAL_VERIFIER
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.auditClaim
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.auditId
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.auditVerification
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.toCallPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Independent tests (test-author seat, item 8ae4c8e7, R14) for the local principal and the audit mapping every
 * transport-established principal goes through: scenarios S6, S8, S12 (cross-host half) and S13 (stdio and Local-mode half) of
 * the frozen test-plan, plus the audit mapping of task-scope item 6.
 *
 * Oracles (written from the plan, never from a run): V10 (a) and decision D1 (one `local` principal for stdio, HTTP Local mode and
 * REST `API_AUTH_MODE=none`: kind AGENT, proof NONE, unrestricted scope, capability ADMIN, audited as the bare id `local`; an
 * idempotency key sent through one host replays through another because both are the same principal), decision D2 (the audit
 * kind of every transport principal is `external`; the audit id of an issued or verified credential is `api:<id>`, of a NONE
 * principal the bare id; verification is UNCHECKED for ISSUED and NONE with verifier `local` for NONE, VERIFIED for VERIFIED
 * and VERIFIED_STALE), plan "the stdio principal recorded on `call_log`" (`call_log.principal_id = local`), the ADMIN
 * capability rule (note attribution is visible to ADMIN), and R10 provenance (principal id, kind, proof, host = clientInfo.name,
 * served profile). Table cells are literals.
 *
 * Negative-assertion conditions (what makes each fail without the feature): the `local` row is paired with an `api:<id>` row
 * written through the same adapter and tables by a credential principal, so a mapping that audits everything the same way
 * fails; the cross-host replay is read from `work_items` (one row after two calls) and from the `replayed` flag, and is paired
 * with the same call under a different principal id, which does NOT replay.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled.
 *
 * Domain invariants satisfied by construction: items are created through the real `items_write` create with a declared type, or
 * seeded through the undecorated repository (a root has no parent); principals are plain values.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class McpLocalPrincipalCallLogTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val pairs = mutableListOf<Pair<Client, Server>>()

    @AfterEach
    fun tearDown(): Unit = runBlocking { pairs.forEach { closeInMemoryPair(it.first, it.second) } }

    // ------------------------------------------------------------------ the value and the audit mapping

    @Test
    fun `S6 the local principal is local, AGENT, NONE, unrestricted and ADMIN`() {
        assertEquals("local", LOCAL_PRINCIPAL.id)
        assertEquals(PrincipalKind.AGENT, LOCAL_PRINCIPAL.kind)
        assertEquals(ProofStatus.NONE, LOCAL_PRINCIPAL.proofStatus)
        assertTrue(LOCAL_PRINCIPAL.scope.isUnrestricted)
        assertEquals(setOf(ApiCapability.ADMIN), LOCAL_PRINCIPAL.capabilities)
    }

    @Test
    fun `S6 the default local access carries the local principal`() {
        assertEquals(
            "local",
            CallAccess.LOCAL.principal
                ?.claim
                ?.id
        )
        assertEquals(
            "local",
            CallAccess.LOCAL.principal
                ?.principal
                ?.id
        )
        assertTrue(CallAccess.LOCAL.capabilities.contains(ApiCapability.ADMIN))
        assertTrue(CallAccess.LOCAL.scope.isUnrestricted)
    }

    @Test
    fun `the audit mapping is api prefix for a credential, the bare id for NONE, kind external, local verifier for NONE`() {
        fun p(proof: ProofStatus) = Principal("svc", PrincipalKind.SERVICE, proof, AccessScope.unrestricted(), setOf(ApiCapability.READ))
        assertEquals("api:", AUDIT_ID_PREFIX)
        assertEquals("local", LOCAL_VERIFIER)

        for (proof in listOf(ProofStatus.ISSUED, ProofStatus.VERIFIED, ProofStatus.VERIFIED_STALE)) {
            assertEquals("api:svc", p(proof).auditId(), "$proof")
            assertEquals("api:svc", p(proof).auditClaim().id, "$proof")
        }
        assertEquals("svc", p(ProofStatus.NONE).auditId(), "NONE audits as the bare id")
        assertEquals("local", LOCAL_PRINCIPAL.auditId())

        for (proof in ProofStatus.entries) {
            assertEquals(ActorKind.EXTERNAL, p(proof).auditClaim().kind, "every transport principal is kind external: $proof")
        }
        assertEquals(VerificationStatus.UNCHECKED, p(ProofStatus.ISSUED).auditVerification().status)
        assertEquals(VerificationStatus.UNCHECKED, p(ProofStatus.NONE).auditVerification().status)
        assertEquals(VerificationStatus.VERIFIED, p(ProofStatus.VERIFIED).auditVerification().status)
        assertEquals(VerificationStatus.VERIFIED, p(ProofStatus.VERIFIED_STALE).auditVerification().status)
        assertEquals(LOCAL_VERIFIER, LOCAL_PRINCIPAL.auditVerification().verifier, "the NONE principal's verifier is local")
        assertEquals("local", LOCAL_PRINCIPAL.toCallPrincipal().claim.id)
    }

    // ------------------------------------------------------------------ stdio-style wiring over the in-memory pair

    private fun stdioAccess(): (String?) -> CallAccess = { _ -> LOCAL_PRINCIPAL.toCallAccess(redactNoteAttribution = true) }

    private suspend fun connect(
        rig: EventLogRig,
        writer: CallLogWriter,
        access: (String?) -> CallAccess,
        clientName: String = "stdio-client",
        fx: FixtureTools? = null
    ): Client {
        val server = Server(serverInfo = Implementation(name = "local-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        val adapter = McpOperationAdapter(buildToolDispatcher(rig.ctx), access = access, callLog = writer)
        adapter.register(server, ToolRegistry(buildToolSpecs()), ToolProfile.ORCHESTRATION)
        if (fx != null) adapter.register(server, fx.registry, ToolProfile.ORCHESTRATION)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client =
            Client(
                clientInfo = Implementation(name = clientName, version = "1.0.0"),
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

    private fun newWriter(rig: EventLogRig) =
        CallLogWriter(rig.composition.unitOfWork, SqliteCallLogStore(db.databaseManager), flushInterval = 1.hours)

    private fun noteActorIds(): List<String?> =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT actor_id FROM notes ORDER BY key").use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString(1))
                    }
                }
            }
        }

    @Test
    fun `S6 a stdio call is recorded on call_log as local agent and a note upsert stores actor id local`() {
        val rig = EventLogRig.build(db.db, dir)
        val writer = newWriter(rig)
        runBlocking {
            val client = connect(rig, writer, stdioAccess())
            val item = rig.seed("Stdio target")

            val create = client.call("items_write", McpAuthHttpFixture.CREATE_ARGS)
            assertTrue(create.isError != true, "the stdio create succeeds: ${create.structuredContent}")
            val note =
                client.call(
                    "notes_write",
                    """{"ops":[{"op":"upsert","itemId":"${item.id}","key":"spec","role":"queue","body":"b"}]}"""
                )
            assertTrue(note.isError != true, "the stdio note upsert succeeds: ${note.structuredContent}")

            writer.flushNow()
            val rows = CallLogRows.read(db.jdbcUrl)
            assertEquals(2, rows.size, "one row per call: $rows")
            assertEquals(setOf("local"), rows.map { it["principal_id"] }.toSet(), "stdio calls are recorded as the bare local id")
            assertEquals(setOf("agent"), rows.map { it["principal_kind"] }.toSet())
            assertEquals(listOf("local"), noteActorIds(), "the note's actor id is local")
        }
    }

    @Test
    fun `S6 control - the same adapter under a credential principal records api colon id, so the mapping is not constant`() {
        val rig = EventLogRig.build(db.db, dir)
        val writer = newWriter(rig)
        runBlocking {
            val issued = Principal("alice", PrincipalKind.AGENT, ProofStatus.ISSUED, AccessScope.unrestricted(), setOf(ApiCapability.ADMIN))
            val client = connect(rig, writer, { _ -> issued.toCallAccess(redactNoteAttribution = true) })
            val item = rig.seed("Credential target")
            client.call("notes_write", """{"ops":[{"op":"upsert","itemId":"${item.id}","key":"spec","role":"queue","body":"b"}]}""")
            writer.flushNow()
            assertEquals(listOf("api:alice"), CallLogRows.read(db.jdbcUrl).map { it["principal_id"] })
            assertEquals(listOf("api:alice"), noteActorIds())
        }
    }

    @Test
    fun `S13 the provenance of a stdio call is local, AGENT, NONE, the client name and the served profile`() {
        val rig = EventLogRig.build(db.db, dir)
        val writer = newWriter(rig)
        val fx = FixtureTools()
        runBlocking {
            val client = connect(rig, writer, stdioAccess(), clientName = "stdio-client", fx = fx)
            val result = client.call("fx_read", """{"select":"get","id":"${FixtureTools.GET_OK}"}""")
            assertTrue(result.isError != true, "${result.structuredContent}")
            val provenance = assertNotNull(fx.seen.single().provenance())
            assertEquals("local", provenance.principalId)
            assertEquals(PrincipalKind.AGENT, provenance.kind)
            assertEquals(ProofStatus.NONE, provenance.proofStatus)
            assertEquals("stdio-client", provenance.host)
            assertEquals(ToolProfile.ORCHESTRATION.wire, provenance.profile)
        }
    }

    // ------------------------------------------------------------------ HTTP Local mode and cross-host keys (S8, S12)

    private val keyedCreate =
        """{"ops":[{"op":"create","title":"Keyed","type":"test-item","idempotencyKey":"cross-host-1"}]}"""

    private fun workItemCount(): Int = McpAuthHttpFixture.workItemCount(db.jdbcUrl)

    @Test
    fun `S8 in Local mode mcp serves without any Authorization header, even a garbage one, as the local principal`() {
        val rig = EventLogRig.build(db.db, dir)
        val f = McpAuthHttpFixture(rig, db.databaseManager)
        testApplication {
            application { with(f) { mount(McpAuthMode.Local, ApiAuthConfig.Unauthenticated) } }
            with(f) {
                for (authorization in listOf(null, "Bearer not-a-token", "Basic abc")) {
                    val init = client.rpc("/mcp", initializeBody, authorization)
                    assertEquals(HttpStatusCode.OK, init.status, "Authorization=$authorization: ${init.body}")
                    val session = assertNotNull(init.sessionId)
                    client.rpc("/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""", authorization, session)
                    val call =
                        client.rpc(
                            "/mcp",
                            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"items_write","arguments":${McpAuthHttpFixture.CREATE_ARGS}}}""",
                            authorization,
                            session
                        )
                    assertTrue(call.toolCallSucceeded(), "Authorization=$authorization: ${call.body}")
                }
                assertEquals(3, workItemCount(), "all three local sessions could write")
                runBlocking { f.writer.flushNow() }
                val rows = CallLogRows.forTool(db.jdbcUrl, "items_write")
                assertEquals(setOf("local"), rows.map { it["principal_id"] }.toSet(), "every Local-mode call is the local principal")
                assertEquals(3, rows.size)
            }
        }
    }

    @Test
    fun `S8 a key sent through REST in unauthenticated mode replays through mcp in Local mode`() {
        val rig = EventLogRig.build(db.db, dir)
        val f = McpAuthHttpFixture(rig, db.databaseManager)
        testApplication {
            application { with(f) { mount(McpAuthMode.Local, ApiAuthConfig.Unauthenticated) } }
            with(f) {
                val rest =
                    client.post("/api/v1/ops/items_write") {
                        contentType(ContentType.Application.Json)
                        setBody(keyedCreate)
                    }
                assertEquals(HttpStatusCode.OK, rest.status, rest.bodyAsText())
                assertEquals(1, workItemCount())

                val init = client.rpc("/mcp", initializeBody)
                val session = assertNotNull(init.sessionId)
                client.rpc("/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""", null, session)
                val viaMcp =
                    client.rpc(
                        "/mcp",
                        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"items_write","arguments":$keyedCreate}}""",
                        null,
                        session
                    )
                assertTrue(viaMcp.toolCallSucceeded(), viaMcp.body)
                assertTrue("\"replayed\":true" in viaMcp.structured.toString(), "the MCP call replays the REST call's key: ${viaMcp.body}")
                assertEquals(1, workItemCount(), "executed once across both hosts")

                runBlocking { f.writer.flushNow() }
                val row = CallLogRows.forTool(db.jdbcUrl, "items_write").single()
                assertEquals(1L, row["replayed"], "call_log marks the MCP call as a replay")
                assertEquals("local", row["principal_id"])
            }
        }
    }

    @Test
    fun `S8 a key sent through mcp in Local mode replays through REST in unauthenticated mode`() {
        val rig = EventLogRig.build(db.db, dir)
        val f = McpAuthHttpFixture(rig, db.databaseManager)
        testApplication {
            application { with(f) { mount(McpAuthMode.Local, ApiAuthConfig.Unauthenticated) } }
            with(f) {
                val init = client.rpc("/mcp", initializeBody)
                val session = assertNotNull(init.sessionId)
                client.rpc("/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""", null, session)
                val viaMcp =
                    client.rpc(
                        "/mcp",
                        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"items_write","arguments":$keyedCreate}}""",
                        null,
                        session
                    )
                assertTrue(viaMcp.toolCallSucceeded(), viaMcp.body)
                assertEquals(1, workItemCount())
                assertFalse("\"replayed\":true" in viaMcp.structured.toString(), "control: the first send is not a replay")

                val rest =
                    client.post("/api/v1/ops/items_write") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(keyedCreate)
                    }
                val body = rest.bodyAsText()
                assertEquals(HttpStatusCode.OK, rest.status, body)
                assertTrue("\"replayed\":true" in body, "the REST call replays the MCP call's key: $body")
                assertEquals(1, workItemCount(), "executed once across both hosts")
            }
        }
    }

    @Test
    fun `S12 control - the same key and payload under another principal id does not replay`() {
        val rig = EventLogRig.build(db.db, dir)
        val writer = newWriter(rig)
        runBlocking {
            val local = connect(rig, writer, stdioAccess())
            assertTrue(local.call("items_write", keyedCreate).isError != true)
            val other =
                Principal("someone-else", PrincipalKind.AGENT, ProofStatus.NONE, AccessScope.unrestricted(), setOf(ApiCapability.ADMIN))
            val stranger = connect(rig, writer, { _ -> other.toCallAccess(redactNoteAttribution = true) })
            val result = stranger.call("items_write", keyedCreate)
            assertTrue(result.isError != true, "${result.structuredContent}")
            assertFalse("\"replayed\":true" in result.structuredContent.toString(), "keys are scoped per principal id")
            assertEquals(2, workItemCount(), "each principal's first send executes")
        }
    }
}
