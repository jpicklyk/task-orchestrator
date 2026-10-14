package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testAccess
import io.ktor.http.HttpStatusCode
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
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 8ae4c8e7, R14) for the per-session principal binding: [McpSessionPrincipals] at its
 * unit seam, and the HTTP mount refusing a request that carries another principal's `Mcp-Session-Id` (scenario S3 and its
 * probes), plus the dispatcher refusal for a tool call in a session that has no binding (S4 at the adapter seam).
 *
 * Oracles (written from the plan, never from a run): plan paragraph "session binding" and task-scope item 5 (a session bound to
 * principal A refuses a request resolving to another `principal.id` with 401 `unauthenticated`, the message reveals neither
 * id, no tool runs and no `call_log` row is written, A's later requests still work; a binding is forgotten by `DELETE /mcp`;
 * the invariant is that a tool call in a session with no binding gets an access with no principal and the dispatcher refuses
 * it), AC2, item 6 (the audit id of an issued credential is `api:<id>`; a READ bearer is redacted like REST, an ADMIN or an
 * unredacted deployment sees note attribution) and the test-plan probe list (two tokens for one id are accepted; DELETE with the
 * wrong principal; replay after DELETE; header-name case).
 *
 * Negative-assertion conditions (what makes each fail without the feature): every refusal is checked against `work_items` and
 * `call_log` (a mismatch check that is dropped makes bob's create succeed and the count rise); each refusal is paired with the
 * owner's call in the same session that succeeds, and with bob's call in his own session.
 *
 * Forbidden-construct declaration: none. After DELETE the HTTP status of a replay is asserted only as "the tool did not run"
 * (not succeeded, no row) because the unknown-session status belongs to the SDK, not to this item.
 *
 * Domain invariants satisfied by construction: principals are plain values; the scoped principal's root set holds a freshly
 * generated UUID and tags are empty, so [AccessScope.principal] is restricted by construction.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class SessionBindMismatchTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun principal(
        id: String,
        capabilities: Set<ApiCapability> = setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS),
        scope: AccessScope = AccessScope.unrestricted()
    ) = Principal(id, PrincipalKind.AGENT, ProofStatus.ISSUED, scope, capabilities)

    // ------------------------------------------------------------------ unit seam

    @Test
    fun `an unbound session and a missing session id have no principal`() {
        val sessions = McpSessionPrincipals(redactNoteAttribution = true)
        assertNull(sessions.principalOf("never-bound"))
        assertNull(sessions.access("never-bound").principal, "an unbound session gets an access with no principal")
        assertNull(sessions.access(null).principal, "no session id at all gets an access with no principal")
    }

    @Test
    fun `a bound session answers the principal's audit id, capabilities, scope and its own session id`() {
        val sessions = McpSessionPrincipals(redactNoteAttribution = true)
        val scoped =
            principal(
                "scoped",
                setOf(ApiCapability.READ),
                AccessScope.principal(rootIds = setOf(UUID.randomUUID()), tagsInclude = emptySet())
            )
        sessions.bind("s1", scoped)

        assertEquals("scoped", sessions.principalOf("s1")?.id)
        val access = sessions.access("s1")
        assertEquals("api:scoped", access.principal?.claim?.id, "an issued credential is audited as api:<id>")
        assertEquals("scoped", access.principal?.principal?.id)
        assertEquals(setOf(ApiCapability.READ), access.capabilities)
        assertFalse(access.scope.isUnrestricted, "the principal's restricted scope rides on the access")
        assertEquals("s1", access.sessionId)
    }

    @Test
    fun `two sessions keep two principals`() {
        val sessions = McpSessionPrincipals(redactNoteAttribution = true)
        sessions.bind("s1", principal("alice"))
        sessions.bind("s2", principal("bob"))
        assertEquals(
            "api:alice",
            sessions
                .access("s1")
                .principal
                ?.claim
                ?.id
        )
        assertEquals(
            "api:bob",
            sessions
                .access("s2")
                .principal
                ?.claim
                ?.id
        )
    }

    @Test
    fun `the first binding of a session wins and a rebind by another principal does not replace it`() {
        val sessions = McpSessionPrincipals(redactNoteAttribution = true)
        sessions.bind("s1", principal("alice"))
        sessions.bind("s1", principal("bob"))
        assertEquals("alice", sessions.principalOf("s1")?.id)
        assertEquals(
            "api:alice",
            sessions
                .access("s1")
                .principal
                ?.claim
                ?.id
        )
    }

    @Test
    fun `a second credential of the same principal id leaves the binding on that principal`() {
        val sessions = McpSessionPrincipals(redactNoteAttribution = true)
        sessions.bind("s1", principal("alice"))
        sessions.bind("s1", principal("alice", setOf(ApiCapability.READ)))
        assertEquals("alice", sessions.principalOf("s1")?.id)
        assertNotNull(sessions.access("s1").principal)
    }

    @Test
    fun `forget removes the binding and forgetting an unknown session is harmless`() {
        val sessions = McpSessionPrincipals(redactNoteAttribution = true)
        sessions.bind("s1", principal("alice"))
        sessions.bind("s2", principal("bob"))
        sessions.forget("s1")
        sessions.forget("never-bound")
        assertNull(sessions.principalOf("s1"))
        assertNull(sessions.access("s1").principal, "a forgotten session is unauthenticated again")
        assertEquals("bob", sessions.principalOf("s2")?.id, "control: another session keeps its binding")
    }

    @Test
    fun `note attribution is redacted for a READ bearer, visible for ADMIN and for an unredacted deployment`() {
        val redacting = McpSessionPrincipals(redactNoteAttribution = true)
        redacting.bind("reader", principal("reader", setOf(ApiCapability.READ)))
        redacting.bind("admin", principal("admin", setOf(ApiCapability.ADMIN)))
        assertFalse(redacting.access("reader").attributionVisible, "a READ bearer is redacted like REST")
        assertTrue(redacting.access("admin").attributionVisible, "ADMIN sees note attribution")

        val open = McpSessionPrincipals(redactNoteAttribution = false)
        open.bind("reader", principal("reader", setOf(ApiCapability.READ)))
        assertTrue(open.access("reader").attributionVisible, "with redaction off the attribution is visible")
    }

    @Test
    fun `an unauthenticated access has no principal and keeps the session id it was given`() {
        assertNull(CallAccess.unauthenticated().principal)
        val withSession = CallAccess.unauthenticated("s-9")
        assertNull(withSession.principal)
        assertEquals("s-9", withSession.sessionId)
    }

    // ------------------------------------------------------------------ S3 over HTTP

    private fun fixture() = McpAuthHttpFixture(EventLogRig.build(db.db, dir), db.databaseManager)

    private val aliceToken = "alice-bind-token"
    private val alice2Token = "alice-second-token"
    private val bobToken = "bob-bind-token"

    private fun entries() =
        mapOf(
            McpAuthHttpFixture.entry(aliceToken, "alice"),
            McpAuthHttpFixture.entry(alice2Token, "alice"),
            McpAuthHttpFixture.entry(bobToken, "bob")
        )

    @Test
    fun `S3 another principal's token on a bound session is 401 unauthenticated, runs no tool and leaks neither id`() {
        val f = fixture()
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val session = client.handshake("/mcp", aliceToken)
                val hijack = client.callTool("/mcp", bobToken, session, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertEquals(HttpStatusCode.Unauthorized, hijack.status, hijack.body)
                assertEquals("unauthenticated", hijack.errorCode, hijack.body)
                assertFalse("alice" in hijack.body.lowercase(), "the refusal must not reveal the bound id: ${hijack.body}")
                assertFalse("bob" in hijack.body.lowercase(), "the refusal must not reveal the presenting id: ${hijack.body}")
                assertEquals(0, McpAuthHttpFixture.workItemCount(db.jdbcUrl), "the hijack created nothing")

                val owner = client.callTool("/mcp", aliceToken, session, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(owner.toolCallSucceeded(), "control: the owner's call in the same session runs: ${owner.body}")
                assertEquals(1, McpAuthHttpFixture.workItemCount(db.jdbcUrl))

                val again = client.callTool("/mcp", aliceToken, session, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(again.toolCallSucceeded(), "A's later requests in the session still work: ${again.body}")
                assertEquals(2, McpAuthHttpFixture.workItemCount(db.jdbcUrl))

                val bobSession = client.handshake("/mcp", bobToken)
                val own = client.callTool("/mcp", bobToken, bobSession, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(own.toolCallSucceeded(), "control: bob's own session works for bob: ${own.body}")
                assertEquals(3, McpAuthHttpFixture.workItemCount(db.jdbcUrl))

                runBlocking { f.writer.flushNow() }
                val ids = CallLogRows.forTool(db.jdbcUrl, "items_write").map { it["principal_id"] }
                assertEquals(listOf("api:alice", "api:alice", "api:bob"), ids.sortedBy { it as String }, "the hijack left no call_log row")
            }
        }
    }

    @Test
    fun `S3 the session id the adapter asks the access seam about is the Mcp-Session-Id the plugin bound`() {
        val f = fixture()
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val session = client.handshake("/mcp", aliceToken)
                assertEquals(
                    "alice",
                    f.sessions.principalOf(session)?.id,
                    "the plugin bound the session named by the Mcp-Session-Id header"
                )
                client.callTool("/mcp", aliceToken, session, "items_read", """{"select":"list"}""")
                assertEquals(
                    listOf<String?>(session),
                    f.accessQueries.toList(),
                    "the one tool call resolved its access by the same id the header carried, or the binding is never found"
                )
            }
        }
    }

    @Test
    fun `S3 the session header name is case-insensitive for the mismatch check`() {
        val f = fixture()
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val session = client.handshake("/mcp", aliceToken)
                for (header in listOf("mcp-session-id", "MCP-SESSION-ID")) {
                    val hijack = client.callTool("/mcp", bobToken, session, "items_write", McpAuthHttpFixture.CREATE_ARGS, header)
                    assertEquals(HttpStatusCode.Unauthorized, hijack.status, "$header: ${hijack.body}")
                    assertEquals("unauthenticated", hijack.errorCode, header)
                }
                assertEquals(0, McpAuthHttpFixture.workItemCount(db.jdbcUrl), "no refused call created anything")
                val owner = client.callTool("/mcp", aliceToken, session, "items_write", McpAuthHttpFixture.CREATE_ARGS, "mcp-session-id")
                assertTrue(owner.toolCallSucceeded(), "control: the owner with the lower-case header name works: ${owner.body}")
            }
        }
    }

    @Test
    fun `probe two tokens of one principal id share the session`() {
        val f = fixture()
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val session = client.handshake("/mcp", aliceToken)
                val viaSecond = client.callTool("/mcp", alice2Token, session, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(viaSecond.toolCallSucceeded(), "the binding is the principal id, not the token: ${viaSecond.body}")
                assertEquals(1, McpAuthHttpFixture.workItemCount(db.jdbcUrl))
            }
        }
    }

    @Test
    fun `probe DELETE by another principal is refused, the owner's DELETE ends the session, a replay after it runs nothing`() {
        val f = fixture()
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val session = client.handshake("/mcp", aliceToken)

                val foreign = client.endSession("/mcp", "Bearer $bobToken", session)
                assertEquals(HttpStatusCode.Unauthorized, foreign.status, foreign.body)
                assertEquals("unauthenticated", foreign.errorCode)
                val survives = client.callTool("/mcp", aliceToken, session, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(survives.toolCallSucceeded(), "a refused DELETE leaves the session alive: ${survives.body}")
                assertEquals(1, McpAuthHttpFixture.workItemCount(db.jdbcUrl))

                val own = client.endSession("/mcp", "Bearer $aliceToken", session)
                assertTrue(own.status.value in 200..299, "the owner's DELETE ends the session: ${own.status} ${own.body}")

                val ownerReplay = client.callTool("/mcp", aliceToken, session, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertFalse(ownerReplay.toolCallSucceeded(), "a replay by the owner after DELETE runs nothing: ${ownerReplay.body}")
                val foreignReplay = client.callTool("/mcp", bobToken, session, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertFalse(
                    foreignReplay.toolCallSucceeded(),
                    "a replay by another principal after DELETE runs nothing: ${foreignReplay.body}"
                )
                assertEquals(1, McpAuthHttpFixture.workItemCount(db.jdbcUrl), "neither replay created anything")
            }
        }
    }

    @Test
    fun `probe a session id that was never initialized runs no tool for a valid token`() {
        val f = fixture()
        val entries = entries()
        testApplication {
            application { with(f) { mount(McpAuthHttpFixture.credentialMode(entries)) } }
            with(f) {
                val forged = client.callTool("/mcp", bobToken, "forged-session-id", "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertFalse(forged.toolCallSucceeded(), "a forged session id is not a session: ${forged.body}")
                assertEquals(0, McpAuthHttpFixture.workItemCount(db.jdbcUrl))
                val session = client.handshake("/mcp", bobToken)
                val control = client.callTool("/mcp", bobToken, session, "items_write", McpAuthHttpFixture.CREATE_ARGS)
                assertTrue(control.toolCallSucceeded(), "control: a real session for the same token works: ${control.body}")
            }
        }
    }

    // ------------------------------------------------------------------ S4 at the adapter seam

    private suspend fun callOverPair(
        access: (String?) -> CallAccess,
        rig: EventLogRig
    ): CallToolResult {
        val server = Server(serverInfo = Implementation(name = "bind-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        McpOperationAdapter(buildToolDispatcher(rig.ctx), access = access)
            .register(server, ToolRegistry(buildToolSpecs()), ToolProfile.ORCHESTRATION)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client =
            Client(
                clientInfo = Implementation(name = "bind-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities())
            )
        server.createSession(serverTransport)
        client.connect(clientTransport)
        try {
            return client.callTool(
                buildCallToolRequest {
                    name = "items_read"
                    arguments(Json.parseToJsonElement("""{"select":"list"}""").jsonObject)
                }
            )
        } finally {
            closeInMemoryPair(client, server)
        }
    }

    @Test
    fun `S4 a tool call in a session with no binding is refused as unauthenticated by the dispatcher`() {
        val rig = EventLogRig.build(db.db, dir)
        val sessions = McpSessionPrincipals(redactNoteAttribution = true)
        val refused = runBlocking { callOverPair(sessions::access, rig) }
        assertEquals(true, refused.isError, "a session nobody bound cannot call a tool")
        val code =
            refused.structuredContent
                ?.get("error")
                ?.jsonObject
                ?.get("code")
                ?.jsonPrimitive
                ?.content
        assertEquals("unauthenticated", code, "structured: ${refused.structuredContent}")

        val control = runBlocking { callOverPair({ _ -> testAccess("someone") }, rig) }
        assertNotNull(control.structuredContent)
        assertTrue(control.isError != true, "control: the same call with a principal on the access succeeds: ${control.structuredContent}")
    }
}
