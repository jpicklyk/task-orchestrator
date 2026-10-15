package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.bootstrap.buildProfileServer
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.bootstrap.installMcpStreamableHttp
import io.github.jpicklyk.mcptask.current.bootstrap.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** A reply of the streamable HTTP mount: status, headers (lower-cased names) and the JSON body (plain or the first SSE data line). */
internal class McpAuthReply(
    val status: HttpStatusCode,
    val body: String,
    val headers: Map<String, String>
) {
    val sessionId: String? get() = headers["mcp-session-id"]

    val wwwAuthenticate: String? get() = headers["www-authenticate"]

    val json: JsonObject? =
        runCatching {
            val payload =
                body
                    .lineSequence()
                    .firstOrNull { it.startsWith("data:") }
                    ?.removePrefix("data:")
                    ?.trim()
                    ?: body.trim()
            Json.parseToJsonElement(payload).jsonObject
        }.getOrNull()

    private val result: JsonObject? get() = json?.get("result") as? JsonObject

    /** The `structuredContent` of a tools/call result (the 4.0 envelope), or null. */
    val structured: JsonObject? get() = result?.get("structuredContent") as? JsonObject

    /** The error object of a refusal envelope (HTTP-level) or of a tool-call result's envelope, or null. */
    val errorObject: JsonObject?
        get() = (json?.get("error") as? JsonObject) ?: (structured?.get("error") as? JsonObject)

    val errorCode: String? get() = errorObject?.get("code")?.jsonPrimitive?.contentOrNull

    val errorDetail: JsonObject? get() = errorObject?.get("detail") as? JsonObject

    /** True only for HTTP 200 carrying a JSON-RPC result that is not a tool error. */
    fun toolCallSucceeded(): Boolean {
        val r = result ?: return false
        return status == HttpStatusCode.OK && r["isError"]?.jsonPrimitive?.booleanOrNull != true
    }
}

/**
 * The production HTTP topology for the `/mcp` auth tests: the real tool registry behind one [McpOperationAdapter] whose access
 * seam is the [McpSessionPrincipals] store (the production HTTP wiring), the three profile servers mounted by
 * [installMcpStreamableHttp] and, when a REST auth config is given, the REST routes beside them ([installRestApiRoutes]).
 */
internal class McpAuthHttpFixture(
    val rig: EventLogRig,
    databaseManager: DatabaseManager,
    val appConfig: AppConfig = AppConfig.fromEnv { null },
    clock: Clock = Clock.SYSTEM,
    idleTtl: Duration = McpSessionPrincipals.DEFAULT_IDLE_TTL,
    maxSessions: Int = McpSessionPrincipals.DEFAULT_MAX_SESSIONS
) {
    val registry = ToolRegistry(buildToolSpecs())
    val writer = CallLogWriter(rig.composition.unitOfWork, SqliteCallLogStore(databaseManager), flushInterval = 1.hours)
    val sdkSessions = ServerSdkSessions()
    val sessions = McpSessionPrincipals(appConfig.apiRedactNoteAttribution, sdkSessions, clock, idleTtl, maxSessions)

    /** Every session id the adapter asked the access seam about, in call order (a spy over [McpSessionPrincipals.access]). */
    val accessQueries = CopyOnWriteArrayList<String?>()

    val adapter =
        McpOperationAdapter(
            buildToolDispatcher(rig.ctx),
            access = { sessionId ->
                accessQueries += sessionId
                sessions.access(sessionId)
            },
            callLog = writer
        )

    private fun server(profile: ToolProfile): Server =
        buildProfileServer(
            profile = profile,
            serverName = "mcp-auth-test",
            version = "1.0.0",
            registry = registry,
            operationAdapter = adapter
        )

    val orchestration: Server = server(ToolProfile.ORCHESTRATION)
    val knowledge: Server = server(ToolProfile.KNOWLEDGE)
    val director: Server = server(ToolProfile.DIRECTOR)

    init {
        listOf(orchestration, knowledge, director).forEach(sdkSessions::serve)
    }

    /** Registers the fixture tools on the orchestration and knowledge servers (call before the application starts). */
    fun withFixtureTools(fx: FixtureTools) {
        adapter.register(orchestration, fx.registry, ToolProfile.ORCHESTRATION)
        adapter.register(knowledge, fx.registry, ToolProfile.KNOWLEDGE)
    }

    /** Mounts `/mcp*` under [mode] and, if [restAuth] is non-null, the REST routes under it. */
    fun Application.mount(
        mode: McpAuthMode,
        restAuth: ApiAuthConfig? = null,
        restEntries: Map<HashBytes, BearerTokenStore.TokenEntry> = emptyMap()
    ) {
        installMcpStreamableHttp(
            orchestration,
            appConfig,
            knowledgeServer = knowledge,
            directorServer = director,
            mcpAuth = mode,
            sessionPrincipals = sessions
        )
        if (restAuth != null) {
            installRestApiRoutes(
                apiConfig = restAuth,
                eventBus = null,
                effectiveProvider = rig.ctx.repositoryProvider,
                apiTokenEntries = restEntries,
                allowQueryToken = false,
                serverName = "mcp-auth-test",
                serverVersion = "1.0.0",
                actorAuthEnabled = rig.composition.actorAuthEnabled,
                serverConfig = rig.composition.serverConfig,
                toolContext = rig.ctx,
                appConfig = appConfig
            )
        }
    }

    private val acceptBoth = "application/json, text/event-stream"

    val initializeBody =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
            """"clientInfo":{"name":"mcp-auth-client","version":"1.0"}}}"""

    private suspend fun HttpResponse.toReply(): McpAuthReply =
        McpAuthReply(status, bodyAsText(), headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") })

    /** One JSON-RPC POST on [path]; [authorization] is the full header value (null sends none). */
    suspend fun HttpClient.rpc(
        path: String,
        body: String,
        authorization: String? = null,
        session: String? = null,
        host: String? = null,
        sessionHeader: String = "Mcp-Session-Id"
    ): McpAuthReply =
        post(path) {
            header(HttpHeaders.Accept, acceptBoth)
            if (authorization != null) header(HttpHeaders.Authorization, authorization)
            if (session != null) header(sessionHeader, session)
            if (host != null) header(HttpHeaders.Host, host)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.toReply()

    /** `initialize` then `notifications/initialized` on [path] with [token]; returns the session id. */
    suspend fun HttpClient.handshake(
        path: String,
        token: String
    ): String {
        val init = rpc(path, initializeBody, "Bearer $token")
        assertEquals(HttpStatusCode.OK, init.status, "initialize on $path: ${init.body}")
        val session = assertNotNull(init.sessionId, "initialize on $path must assign a session id")
        rpc(path, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", "Bearer $token", session)
        return session
    }

    suspend fun HttpClient.callTool(
        path: String,
        token: String,
        session: String,
        tool: String,
        arguments: String,
        sessionHeader: String = "Mcp-Session-Id"
    ): McpAuthReply =
        rpc(
            path,
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""",
            "Bearer $token",
            session,
            sessionHeader = sessionHeader
        )

    suspend fun HttpClient.getStream(
        path: String,
        authorization: String? = null,
        session: String? = null
    ): McpAuthReply =
        get(path) {
            header(HttpHeaders.Accept, "text/event-stream")
            if (authorization != null) header(HttpHeaders.Authorization, authorization)
            if (session != null) header("Mcp-Session-Id", session)
        }.toReply()

    suspend fun HttpClient.endSession(
        path: String,
        authorization: String? = null,
        session: String? = null
    ): McpAuthReply =
        delete(path) {
            if (authorization != null) header(HttpHeaders.Authorization, authorization)
            if (session != null) header("Mcp-Session-Id", session)
        }.toReply()

    companion object {
        const val CREATE_ARGS = """{"ops":[{"op":"create","title":"From MCP","type":"test-item"}]}"""

        fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

        /** A token-file style entry: [raw] resolves to a principal [id] holding [capabilities]. */
        fun entry(
            raw: String,
            id: String,
            capabilities: Set<ApiCapability> = setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS),
            expiresAt: Instant? = null
        ): Pair<HashBytes, BearerTokenStore.TokenEntry> =
            HashBytes(sha256(raw)) to
                BearerTokenStore.TokenEntry(
                    Principal(id, PrincipalKind.AGENT, ProofStatus.ISSUED, AccessScope.unrestricted(), capabilities),
                    expiresAt = expiresAt
                )

        fun credentialMode(
            entries: Map<HashBytes, BearerTokenStore.TokenEntry>,
            clock: () -> Instant = { Instant.now() }
        ): McpAuthMode {
            val resolution =
                McpAuthMode.resolve(
                    "http",
                    ApiAuthConfig.Bearer(tokens = entries),
                    entries,
                    null,
                    { emptySet() },
                    clock,
                    false
                )
            return assertIs<McpAuthResolution.Resolved>(resolution).mode
        }

        fun workItemCount(jdbcUrl: String): Int =
            DriverManager.getConnection(jdbcUrl).use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT count(*) FROM work_items").use {
                        it.next()
                        it.getInt(1)
                    }
                }
            }
    }
}

/**
 * Independent tests (test-author seat, item 8ae4c8e7, R14) for `/mcp` bearer authentication ([McpBearerAuth]) over real HTTP
 * through Ktor's test engine, wired like production: [installMcpStreamableHttp] with a [McpAuthMode.Credential] and the
 * [McpSessionPrincipals] seam, [installRestApiRoutes] beside it. Scenarios S1, S2, S9, S10, S13, S14 and the header /
 * expiry / fail-closed probes of the frozen test-plan.
 *
 * Oracles (written from the plan, never from a run): task-scope item 4 (credential mode: a missing or empty credential is 401
 * `unauthenticated` with `WWW-Authenticate: Bearer error="invalid_request"`, an unresolved or expired one is 401 with
 * `error="invalid_token"`; a `profile: director` credential is admitted on `/mcp/director` only and is `forbidden{scope:
 * capability, required: director}` elsewhere; the refusal body is the 4.0 error envelope, never JSON-RPC, and no session is
 * created), the plan paragraph (the plugin runs after the Host guard and on every request), AC1, AC6, test-plan S10 (`/mcp/other`
 * is 404 not 401), the `extractBearerToken` KDoc (case-insensitive `Bearer` plus one or more SP, a tab or a bare/mismatched
 * scheme does not match, one prefix stripped) and RFC 6750 section 3.1 (the challenge codes); the REST twin
 * (`BearerSchemeParsingTest`) for the header-shape table; D2 (call_log kind `external`, id `api:<token id>`) and R10 provenance
 * (principal id, kind, proof, host = clientInfo.name, session id, served profile).
 *
 * Negative-assertion conditions (what makes each fail without the feature): every refusal is paired with a control that differs
 * only by the credential (the same call with a valid token succeeds and creates its row); "no row written" is read from
 * `work_items` / `call_log`, not from the response; the expiry test advances only the injected clock.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled. The duplicate-header probe compares the MCP status with the
 * REST status for the same header shape (a differential oracle: both go through one resolver), because RFC 7230 leaves the
 * outcome of repeated Authorization headers undefined.
 *
 * Domain invariants satisfied by construction: principals are plain values (no validation beyond types), items are created only
 * through the real `items_write` create with a declared type; token entries are built with a SHA-256 of the raw token.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class McpBearerAuthTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val aliceToken = "alice-mcp-token"
    private val bobToken = "bob-mcp-token"
    private val readerToken = "reader-mcp-token"

    private val paths = listOf("/mcp", "/mcp/knowledge", "/mcp/director")

    private fun fixture() = McpAuthHttpFixture(EventLogRig.build(db.db, dir), db.databaseManager)

    private fun baseEntries() =
        mapOf(
            McpAuthHttpFixture.entry(aliceToken, "alice"),
            McpAuthHttpFixture.entry(bobToken, "bob"),
            McpAuthHttpFixture.entry(readerToken, "reader", setOf(ApiCapability.READ))
        )

    private fun directorEntries(
        raw: String,
        id: String
    ): Map<HashBytes, BearerTokenStore.TokenEntry> {
        val file = dir.resolve("director-tokens-$id.yaml").toFile()
        file.writeText(
            "version: 1\ntokens:\n" +
                "  - id: $id\n" +
                "    token_sha256: \"${McpAuthHttpFixture.sha256(raw).joinToString("") { "%02x".format(it) }}\"\n" +
                "    scope:\n" +
                "      root_ids: null\n" +
                "      tags_include: []\n" +
                "    capabilities: [read]\n" +
                "    profile: director\n"
        )
        return BearerTokenStore(file.absolutePath).loadWithEntries()
    }

    private fun bearerRest(entries: Map<HashBytes, BearerTokenStore.TokenEntry>) = ApiAuthConfig.Bearer(tokens = entries)

    // ------------------------------------------------------------------ S1

    @Test
    fun `S1 a valid bearer initializes and calls a tool and the call is recorded under the api principal`() {
        val f = fixture()
        val entries = baseEntries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), bearerRest(entries), entries) } }
            with(f) {
                val session = client.handshake("/mcp", aliceToken)
                val reply = client.callTool("/mcp", aliceToken, session, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(reply.toolCallSucceeded(), "the authenticated create must succeed: ${reply.body}")
                assertEquals(1, McpAuthHttpFixture.workItemCount(db.jdbcUrl), "the create wrote its row")

                runBlocking { f.writer.flushNow() }
                val row = CallLogRows.forTool(db.jdbcUrl, "items_write").single()
                assertEquals("api:alice", row["principal_id"], "call_log carries the api principal (D2)")
                assertEquals("external", row["principal_kind"], "every transport principal is audited as kind external (D2)")
                assertEquals(session, row["session_id"], "call_log carries the Mcp-Session-Id")
                assertEquals("mcp-auth-client", row["host"], "host is clientInfo.name")
            }
        }
    }

    @Test
    fun `S1 a READ bearer is forbidden a write by capability and nothing is written while the control token writes`() {
        val f = fixture()
        val entries = baseEntries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), bearerRest(entries), entries) } }
            with(f) {
                val readerSession = client.handshake("/mcp", readerToken)
                val refused = client.callTool("/mcp", readerToken, readerSession, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                val compact = refused.structured?.toString().orEmpty()
                assertTrue("\"code\":\"forbidden\"" in compact, "the capability refusal is a forbidden envelope: ${refused.body}")
                assertTrue("\"scope\":\"capability\"" in compact, "scope capability: ${refused.body}")
                assertFalse(refused.toolCallSucceeded(), "the refused call is a tool error")
                assertEquals(0, McpAuthHttpFixture.workItemCount(db.jdbcUrl), "a refused write wrote nothing")

                val aliceSession = client.handshake("/mcp", aliceToken)
                val control = client.callTool("/mcp", aliceToken, aliceSession, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(control.toolCallSucceeded(), "control: the same call under a token holding write-items succeeds")
                assertEquals(1, McpAuthHttpFixture.workItemCount(db.jdbcUrl))
            }
        }
    }

    // ------------------------------------------------------------------ S2

    @Test
    fun `S2 no Authorization is 401 unauthenticated on POST GET and DELETE of every mcp path with no session and no JSON-RPC`() {
        val f = fixture()
        val entries = baseEntries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), bearerRest(entries), entries) } }
            with(f) {
                for (path in paths) {
                    val replies =
                        listOf(
                            "POST $path" to client.rpc(path, initializeBody),
                            "GET $path" to client.getStream(path),
                            "DELETE $path" to client.endSession(path, session = "some-session")
                        )
                    for ((label, reply) in replies) {
                        assertEquals(HttpStatusCode.Unauthorized, reply.status, "$label: ${reply.body}")
                        assertEquals("unauthenticated", reply.errorCode, "$label: the 4.0 envelope code: ${reply.body}")
                        assertTrue(
                            reply.wwwAuthenticate.orEmpty().contains("error=\"invalid_request\""),
                            "$label: a missing credential challenges invalid_request: ${reply.wwwAuthenticate}"
                        )
                        assertNull(reply.json?.get("jsonrpc"), "$label: the refusal is an envelope, never JSON-RPC: ${reply.body}")
                        assertNull(reply.sessionId, "$label: no session is created")
                    }
                    val control = client.rpc(path, initializeBody, "Bearer $aliceToken")
                    assertEquals(HttpStatusCode.OK, control.status, "control: $path with a valid token initializes: ${control.body}")
                    assertNotNull(control.sessionId)
                }
            }
        }
    }

    @Test
    fun `S2 an unknown token is 401 invalid_token on every mcp path`() {
        val f = fixture()
        val entries = baseEntries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), bearerRest(entries), entries) } }
            with(f) {
                for (path in paths) {
                    val reply = client.rpc(path, initializeBody, "Bearer not-a-known-token")
                    assertEquals(HttpStatusCode.Unauthorized, reply.status, "$path: ${reply.body}")
                    assertEquals("unauthenticated", reply.errorCode, path)
                    assertTrue(
                        reply.wwwAuthenticate.orEmpty().contains("error=\"invalid_token\""),
                        "$path: an unresolved credential challenges invalid_token: ${reply.wwwAuthenticate}"
                    )
                    assertNull(reply.sessionId, "$path: no session")
                }
            }
        }
    }

    @Test
    fun `S2 the Host guard answers before authentication`() {
        val f = fixture()
        val entries = baseEntries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), bearerRest(entries), entries) } }
            with(f) {
                val rebinding = client.rpc("/mcp", initializeBody, host = "attacker.example:3001")
                assertEquals(HttpStatusCode.Forbidden, rebinding.status, "a rebinding Host is refused as Host: ${rebinding.body}")
                assertTrue("MCP_ALLOWED_HOSTS" in rebinding.body, rebinding.body)
                val authenticated = client.rpc("/mcp", initializeBody, "Bearer $aliceToken", host = "attacker.example:3001")
                assertEquals(HttpStatusCode.Forbidden, authenticated.status, "a valid token does not bypass the Host guard")
                val control = client.rpc("/mcp", initializeBody)
                assertEquals(HttpStatusCode.Unauthorized, control.status, "control: the normal Host without a token is 401")
            }
        }
    }

    // ------------------------------------------------------------------ S14 header shapes

    private data class Shape(
        val header: String,
        val admitted: Boolean,
        val challenge: String?
    )

    @Test
    fun `S14 the Authorization header shapes are admitted or challenged like the REST plugin`() {
        val f = fixture()
        val entries = baseEntries()
        val shapes =
            listOf(
                Shape("Bearer $aliceToken", true, null),
                Shape("bearer $aliceToken", true, null),
                Shape("BEARER $aliceToken", true, null),
                Shape("Bearer\t$aliceToken", false, "invalid_request"),
                Shape("Bearer$aliceToken", false, "invalid_request"),
                Shape("Bearer", false, "invalid_request"),
                Shape("Basic $aliceToken", false, "invalid_request"),
                Shape(aliceToken, false, "invalid_request"),
                Shape("Bearer bearer $aliceToken", false, "invalid_token"),
                Shape("Bearer ${aliceToken}x", false, "invalid_token")
            )
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), bearerRest(entries), entries) } }
            with(f) {
                for (shape in shapes) {
                    val mcp = client.rpc("/mcp", initializeBody, shape.header)
                    val rest = client.get("/api/v1/resources/leases") { header(HttpHeaders.Authorization, shape.header) }
                    if (shape.admitted) {
                        assertEquals(HttpStatusCode.OK, mcp.status, "${shape.header}: ${mcp.body}")
                        assertNotNull(mcp.sessionId, "${shape.header}: a session")
                    } else {
                        assertEquals(HttpStatusCode.Unauthorized, mcp.status, "${shape.header}: ${mcp.body}")
                        assertTrue(
                            mcp.wwwAuthenticate.orEmpty().contains("error=\"${shape.challenge}\""),
                            "${shape.header}: challenge ${shape.challenge} but was ${mcp.wwwAuthenticate}"
                        )
                        assertNull(mcp.sessionId, "${shape.header}: no session")
                    }
                    assertEquals(rest.status, mcp.status, "${shape.header}: /mcp and /api/v1 agree (one resolver)")
                }
            }
        }
    }

    @Test
    fun `S14 probe a repeated Authorization header is decided exactly as the REST plugin decides it`() {
        val f = fixture()
        val entries = baseEntries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), bearerRest(entries), entries) } }
            for (pair in listOf("Bearer wrong-token" to "Bearer $aliceToken", "Bearer $aliceToken" to "Bearer wrong-token")) {
                val mcp =
                    client.post("/mcp") {
                        header(HttpHeaders.Accept, "application/json, text/event-stream")
                        header(HttpHeaders.Authorization, pair.first)
                        header(HttpHeaders.Authorization, pair.second)
                        contentType(ContentType.Application.Json)
                        setBody(f.initializeBody)
                    }
                val rest =
                    client.get("/api/v1/resources/leases") {
                        header(HttpHeaders.Authorization, pair.first)
                        header(HttpHeaders.Authorization, pair.second)
                    }
                assertEquals(rest.status, mcp.status, "$pair: one rule for repeated headers on both surfaces")
            }
        }
    }

    // ------------------------------------------------------------------ S9

    @Test
    fun `S9 a director credential is admitted on mcp director only and forbidden director elsewhere`() {
        val f = fixture()
        val entries = baseEntries() + directorEntries("director-mcp-token", "dir-1")
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), bearerRest(entries), entries) } }
            with(f) {
                val ok = client.rpc("/mcp/director", initializeBody, "Bearer director-mcp-token")
                assertEquals(HttpStatusCode.OK, ok.status, "the director credential initializes on /mcp/director: ${ok.body}")
                assertNotNull(ok.sessionId)

                for (path in listOf("/mcp", "/mcp/knowledge")) {
                    val replies =
                        listOf(
                            "POST $path" to client.rpc(path, initializeBody, "Bearer director-mcp-token"),
                            "GET $path" to client.getStream(path, "Bearer director-mcp-token"),
                            "DELETE $path" to client.endSession(path, "Bearer director-mcp-token", session = "any")
                        )
                    for ((label, reply) in replies) {
                        assertEquals(HttpStatusCode.Forbidden, reply.status, "$label: ${reply.body}")
                        assertEquals("forbidden", reply.errorCode, label)
                        assertEquals(
                            "capability",
                            reply.errorDetail
                                ?.get("scope")
                                ?.jsonPrimitive
                                ?.content,
                            "$label: ${reply.body}"
                        )
                        assertEquals(
                            "director",
                            reply.errorDetail
                                ?.get("required")
                                ?.jsonPrimitive
                                ?.content,
                            "$label: ${reply.body}"
                        )
                        assertNull(reply.sessionId, "$label: no session")
                    }
                    val control = client.rpc(path, initializeBody, "Bearer $aliceToken")
                    assertEquals(HttpStatusCode.OK, control.status, "control: an ordinary credential initializes on $path")
                }
                val ordinary = client.rpc("/mcp/director", initializeBody, "Bearer $aliceToken")
                assertEquals(HttpStatusCode.OK, ordinary.status, "control: an ordinary credential is admitted on /mcp/director too")
            }
        }
    }

    // ------------------------------------------------------------------ S10

    @Test
    fun `S10 an unknown mcp sub-path is 404 not 401, with or without a token`() {
        val f = fixture()
        val entries = baseEntries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), bearerRest(entries), entries) } }
            with(f) {
                assertEquals(HttpStatusCode.NotFound, client.rpc("/mcp/other", initializeBody).status, "no token")
                assertEquals(HttpStatusCode.NotFound, client.rpc("/mcp/other", initializeBody, "Bearer $aliceToken").status, "valid token")
                assertEquals(HttpStatusCode.Unauthorized, client.rpc("/mcp", initializeBody).status, "control: /mcp without a token is 401")
            }
        }
    }

    // ------------------------------------------------------------------ probes

    @Test
    fun `probe a token that expires mid-session is refused on the next request and runs no tool`() {
        val f = fixture()
        val clock = SettableClock(Instant.parse("2026-10-13T10:00:00Z"))
        val expiring = McpAuthHttpFixture.entry("expiring-token", "expiring", expiresAt = Instant.parse("2026-10-13T10:10:00Z"))
        val entries = baseEntries() + expiring
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries) { clock.now() }, bearerRest(entries), entries) } }
            with(f) {
                val session = client.handshake("/mcp", "expiring-token")
                val before = client.callTool("/mcp", "expiring-token", session, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(before.toolCallSucceeded(), "control: before the expiry the session works")
                assertEquals(1, McpAuthHttpFixture.workItemCount(db.jdbcUrl))

                clock.advance(Duration.ofMinutes(11))
                val refused = client.callTool("/mcp", "expiring-token", session, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertEquals(HttpStatusCode.Unauthorized, refused.status, refused.body)
                assertEquals("unauthenticated", refused.errorCode)
                assertTrue(refused.wwwAuthenticate.orEmpty().contains("error=\"invalid_token\""), refused.wwwAuthenticate)
                assertEquals(1, McpAuthHttpFixture.workItemCount(db.jdbcUrl), "the refused call wrote nothing")
            }
        }
    }

    @Test
    fun `probe a Jwks API config with no verifier fails closed on every mcp path`() {
        val f = fixture()
        val jwks =
            ApiAuthConfig.Jwks(
                url = "https://idp.test.example/.well-known/jwks.json",
                issuer = "https://idp.test.example",
                audience = "task-orchestrator-api",
                algorithms = listOf("RS256"),
                cacheTtlSeconds = 300
            )
        val resolution = McpAuthMode.resolve("http", jwks, emptyMap(), null, { emptySet() }, { Instant.now() }, false)
        val mode = assertIs<McpAuthResolution.Resolved>(resolution).mode
        testApplication {
            application { with(f) { mount(mode) } }
            with(f) {
                for (path in paths) {
                    val noHeader = client.rpc(path, initializeBody)
                    assertEquals(HttpStatusCode.Unauthorized, noHeader.status, "$path without a credential: ${noHeader.body}")
                    val someToken = client.rpc(path, initializeBody, "Bearer anything.at.all")
                    assertEquals(HttpStatusCode.Unauthorized, someToken.status, "$path with a credential nothing can verify")
                    assertNull(someToken.sessionId)
                }
            }
        }
    }

    // ------------------------------------------------------------------ S13

    @Test
    fun `S13 the provenance of an MCP call is the api principal, the client name, the session and the served profile`() {
        val f = fixture()
        val fx = FixtureTools()
        f.withFixtureTools(fx)
        val entries = baseEntries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries), bearerRest(entries), entries) } }
            with(f) {
                val orchestrationSession = client.handshake("/mcp", aliceToken)
                val knowledgeSession = client.handshake("/mcp/knowledge", aliceToken)
                val get = """{"select":"get","id":"${FixtureTools.GET_OK}"}"""
                assertTrue(client.callTool("/mcp", aliceToken, orchestrationSession, "fx_read", get).toolCallSucceeded())
                assertTrue(client.callTool("/mcp/knowledge", aliceToken, knowledgeSession, "fx_read", get).toolCallSucceeded())

                assertEquals(2, fx.seen.size, "both calls reached the handler")
                val orchestrationProvenance = runBlocking { assertNotNull(fx.seen[0].provenance()) }
                val knowledgeProvenance = runBlocking { assertNotNull(fx.seen[1].provenance()) }
                assertEquals("api:alice", orchestrationProvenance.principalId)
                assertEquals(PrincipalKind.AGENT, orchestrationProvenance.kind)
                assertEquals(ProofStatus.ISSUED, orchestrationProvenance.proofStatus)
                assertEquals("mcp-auth-client", orchestrationProvenance.host)
                assertEquals(orchestrationSession, orchestrationProvenance.sessionId)
                assertEquals(ToolProfile.ORCHESTRATION.wire, orchestrationProvenance.profile)
                assertEquals(knowledgeSession, knowledgeProvenance.sessionId)
                assertNotEquals(orchestrationSession, knowledgeSession, "two initializes are two sessions")
                assertEquals(ToolProfile.KNOWLEDGE.wire, knowledgeProvenance.profile)
            }
        }
    }
}
