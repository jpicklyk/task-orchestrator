package io.github.jpicklyk.mcptask.current.application.service

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.service.ActorVerificationScope
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksActorVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.mcp.closeInMemoryPair
import io.github.jpicklyk.mcptask.current.interfaces.mcp.inMemoryTestServerOptions
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item 3dcfcbab, Part 2's per-call verification memo:
 * [ActorVerificationScope] (application/service, entirely new in 8534e8da) and its effect through
 * [io.github.jpicklyk.mcptask.current.application.service.ActorAware.parseActorClaim] when the SAME
 * proof is presented more than once inside a single MCP call.
 *
 * Oracle (task-scope Part 2, frozen at queue phase): "Per-call memo so one MCP call counts once:
 * NEW application/service/ActorVerificationScope.kt - a CoroutineContext.Element holding
 * ConcurrentHashMap<String /*proof sha256*/, VerificationResult>. ActorAware.parseActorClaim
 * reuses a memoized result when the element is present. Installed once per call at
 * the (removed) 3.x MCP tool adapter via withContext(ActorVerificationScope()). No element (direct
 * in-process calls) = no memo."
 *
 * Q32 port: S18 now runs over the 4.0 path. The `advance` tool's dispatcher lifts the call's ONE top-level actor and
 * verifies its proof once per call, so two `transition` elements sharing that actor both apply; a second MCP call
 * presenting the same proof again re-verifies it and the verifier's jti replay cache rejects it (the element fails
 * `unauthenticated` under the REJECT policy). The harness registers the production tool registry through
 * [McpOperationAdapter] over a real SQLite database (the removed 3.x [ActorVerificationScope] memo consumer was the
 * advance_item driver).
 */
class ActorVerificationScopeTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    // =========================================================================
    // ActorVerificationScope — class-level behavior
    // =========================================================================

    @Test
    fun `a fresh ActorVerificationScope has an empty memo map`() {
        val scope = ActorVerificationScope()
        assertTrue(scope.memo.isEmpty(), "a newly constructed scope must start with no memoized results")
    }

    @Test
    fun `the memo map stores and retrieves a VerificationResult by key`() {
        val scope = ActorVerificationScope()
        val result = VerificationResult(status = VerificationStatus.VERIFIED, verifier = "jwks")

        scope.memo["proof-hash-1"] = result

        assertEquals(result, scope.memo["proof-hash-1"])
        assertNull(scope.memo["proof-hash-2"], "an unrelated key must not be present")
    }

    @Test
    fun `installing ActorVerificationScope makes it retrievable from the coroutine context`() =
        runTest {
            val scope = ActorVerificationScope()
            withContext(scope) {
                val fromContext = currentCoroutineContext()[ActorVerificationScope]
                assertTrue(fromContext === scope, "the exact installed instance must be retrievable by its key")
            }
        }

    @Test
    fun `with no scope installed the coroutine context has no ActorVerificationScope element`() =
        runTest {
            assertNull(
                currentCoroutineContext()[ActorVerificationScope],
                "outside any withContext(ActorVerificationScope()) installation, the element must be absent"
            )
        }

    // =========================================================================
    // S18 — per-call memo through a real MCP round trip (advance_item, two transitions, one proof)
    // =========================================================================

    private val rsaKey = RSAKeyGenerator(2048).keyID("actor-verification-scope-test-key").generate()
    private val now: Instant = Instant.now()

    private lateinit var server: Server
    private lateinit var client: Client

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            server =
                Server(
                    serverInfo = Implementation(name = "actor-verification-scope-test-server", version = "1.0.0"),
                    options = inMemoryTestServerOptions()
                )
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            client =
                Client(
                    clientInfo = Implementation(name = "actor-verification-scope-test-client", version = "1.0.0"),
                    options = ClientOptions(capabilities = ClientCapabilities())
                )
            pendingTransports = clientTransport to serverTransport
        }

    private var pendingTransports: Pair<ChannelTransport, ChannelTransport>? = null

    /** Registers the production tools over [context] and connects the client (tools must be added before a session). */
    private suspend fun connect(context: ToolExecutionContext) {
        McpOperationAdapter(buildToolDispatcher(context)).register(server, ToolRegistry(buildToolSpecs()), ToolProfile.ORCHESTRATION)
        val (clientTransport, serverTransport) = checkNotNull(pendingTransports)
        server.createSession(serverTransport)
        client.connect(clientTransport)
    }

    @AfterEach
    fun tearDown(): Unit =
        runBlocking {
            closeInMemoryPair(client, server)
        }

    private fun sign(claimsSet: JWTClaimsSet): String {
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).keyID("actor-verification-scope-test-key").build(),
                claimsSet
            )
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    private fun claimsFor(
        subject: String,
        jti: String
    ): JWTClaimsSet =
        JWTClaimsSet
            .Builder()
            .subject(subject)
            .issuer("https://test-issuer.example")
            .audience("task-orchestrator")
            .expirationTime(java.util.Date.from(now.plusSeconds(300)))
            .jwtID(jti)
            .build()

    private fun mockKeySetProvider(): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns
            JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    /** A [ToolExecutionContext] over the real database with a real jti-replay-protecting verifier and the REJECT policy. */
    private fun context(): ToolExecutionContext {
        val verifier =
            JwksActorVerifier(
                config =
                    VerifierConfig.Jwks(
                        jwksPath = "/unused-in-unit-tests",
                        issuer = "https://test-issuer.example",
                        audience = "task-orchestrator",
                        requireSubMatch = true,
                        jtiReplayProtection = true
                    ),
                keySetProvider = mockKeySetProvider()
            )
        return ToolExecutionContext(
            repositoryProvider = db.repositoryProvider(),
            actorVerifier = verifier,
            degradedModePolicy = DegradedModePolicy.REJECT,
            unitOfWork = db.unitOfWork()
        )
    }

    private suspend fun makeItem(): UUID =
        db
            .repositoryProvider()
            .workItemRepository()
            .create(WorkItem(title = "Test Item", role = Role.QUEUE))
            .id

    private fun transition(id: UUID) =
        buildJsonObject {
            put("op", "transition")
            put("itemId", id.toString())
            put("trigger", "start")
        }

    private fun actorJson(proof: String): JsonObject =
        buildJsonObject {
            put("id", "agent-shared")
            put("kind", "subagent")
            put("proof", proof)
        }

    /** The `advance` envelope body: [McpOperationAdapter] serves it as `structuredContent` unchanged. */
    private fun resultJson(callResult: CallToolResult): JsonObject =
        assertNotNull(callResult.structuredContent, "expected structuredContent on the response: ${callResult.content}")

    @Test
    fun `S18 two transitions under one actor proof both apply, a later call replays the jti and is rejected`() =
        runBlocking {
            val id1 = makeItem()
            val id2 = makeItem()
            val id3 = makeItem()
            connect(context())

            val sharedProof = sign(claimsFor(subject = "agent-shared", jti = "shared-jti-s18"))

            val call1Result =
                client.callTool(
                    buildCallToolRequest {
                        name = "advance"
                        arguments(
                            buildJsonObject {
                                put("actor", actorJson(sharedProof))
                                put("ops", kotlinx.serialization.json.JsonArray(listOf(transition(id1), transition(id2))))
                            }
                        )
                    }
                )
            val call1Body = resultJson(call1Result)

            assertTrue(call1Result.isError != true, "call 1 must succeed overall, got: ${call1Result.content}")
            val call1Results = call1Body["data"]!!.jsonObject["results"]!!.jsonArray
            assertEquals(2, call1Results.size)
            call1Results.forEach { r ->
                assertTrue(
                    r.jsonObject["ok"]!!.jsonPrimitive.boolean,
                    "both transitions under the call's one verified actor must apply: $r"
                )
            }

            // A SEPARATE MCP call verifies its actor again. The JtiReplayCache is owned by the verifier instance (shared
            // across both calls via the same context), so the jti from call 1 is still on record and this presentation of
            // the identical proof is rejected as a replay; under the REJECT policy the element fails.
            val call2Result =
                client.callTool(
                    buildCallToolRequest {
                        name = "advance"
                        arguments(
                            buildJsonObject {
                                put("actor", actorJson(sharedProof))
                                put("ops", kotlinx.serialization.json.JsonArray(listOf(transition(id3))))
                            }
                        )
                    }
                )
            val call2Body = resultJson(call2Result)

            val call2Results = call2Body["data"]!!.jsonObject["results"]!!.jsonArray
            assertEquals(1, call2Results.size)
            val r3 = call2Results[0].jsonObject
            assertFalse(
                r3["ok"]!!.jsonPrimitive.boolean,
                "a fresh MCP call replaying the same jti (already recorded by call 1) must be rejected: $r3"
            )
            assertEquals("unauthenticated", r3["error"]!!.jsonObject["code"]!!.jsonPrimitive.content, "$r3")
        }
}
