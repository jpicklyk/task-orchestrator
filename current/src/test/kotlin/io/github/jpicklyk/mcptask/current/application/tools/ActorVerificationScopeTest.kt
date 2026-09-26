package io.github.jpicklyk.mcptask.current.application.tools

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.service.ActorVerificationScope
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.config.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksActorVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksResult
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapter
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
 * [io.github.jpicklyk.mcptask.current.application.tools.ActorAware.parseActorClaim] when the SAME
 * proof is presented more than once inside a single MCP call.
 *
 * Oracle (task-scope Part 2, frozen at queue phase): "Per-call memo so one MCP call counts once:
 * NEW application/service/ActorVerificationScope.kt - a CoroutineContext.Element holding
 * ConcurrentHashMap<String /*proof sha256*/, VerificationResult>. ActorAware.parseActorClaim
 * reuses a memoized result when the element is present. Installed once per call at
 * interfaces/mcp/McpToolAdapter.kt via withContext(ActorVerificationScope()). No element (direct
 * in-process calls) = no memo."
 *
 * S18's harness follows the declarations' explicit instruction: build a [ToolExecutionContext]
 * whose `actorVerifier` is a real [JwksActorVerifier] with `jtiReplayProtection = true` and
 * `degradedModePolicy = REJECT`, register the real [AdvanceItemTool] via [McpToolAdapter], and
 * issue an `advance_item` call with two transitions sharing one `actor.proof` value. Because
 * `jtiReplayProtection = true`, this scenario is SELF-PROVING: if the per-call memo were absent,
 * the second transition's direct re-verification of the same proof would hit the jti replay
 * cache (already recorded by the first transition's verify) and be rejected — no invocation
 * counting or spying is needed. The narrowest revert for this NEW-SURFACE scenario is exactly
 * that: keep [ActorVerificationScope] and its installation in [McpToolAdapter], but skip the
 * memo lookup in `parseActorClaim` — which would flip transition 2 from applied=true to a jti
 * replay rejection.
 */
class ActorVerificationScopeTest {
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
    private lateinit var adapter: McpToolAdapter

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            server =
                Server(
                    serverInfo = Implementation(name = "actor-verification-scope-test-server", version = "1.0.0"),
                    options =
                        ServerOptions(
                            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))
                        )
                )
            adapter = McpToolAdapter()
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            client =
                Client(
                    clientInfo = Implementation(name = "actor-verification-scope-test-client", version = "1.0.0"),
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

    /** A mocked [ToolExecutionContext] for [AdvanceItemTool] over the given items, with a real jti-replay-protecting verifier. */
    private fun contextFor(items: Map<UUID, WorkItem>): ToolExecutionContext {
        val workItemRepo = mockk<WorkItemRepository>()
        val depRepo = mockk<DependencyRepository>()
        val roleTransitionRepo = mockk<RoleTransitionRepository>()
        val noteRepo = mockk<NoteRepository>()
        coEvery { noteRepo.findByItemId(any()) } returns Result.Success(emptyList())
        coEvery { noteRepo.findByItemId(any(), any()) } returns Result.Success(emptyList())

        items.forEach { (id, item) ->
            coEvery { workItemRepo.getById(id) } returns Result.Success(item)
            every { depRepo.findByToItemId(id) } returns emptyList()
            every { depRepo.findByFromItemId(id) } returns emptyList()
        }
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        coEvery { workItemRepo.dbNow() } returns Instant.now()
        coEvery { workItemRepo.inTransaction(any()) } coAnswers {
            firstArg<suspend () -> Unit>().invoke()
        }

        val repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.dependencyRepository() } returns depRepo
        every { repoProvider.noteRepository() } returns noteRepo
        every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
        every { repoProvider.resourceLeaseRepository() } returns mockk(relaxed = true)

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
            repositoryProvider = repoProvider,
            actorVerifier = verifier,
            degradedModePolicy = DegradedModePolicy.REJECT
        )
    }

    private fun makeItem(id: UUID): WorkItem = WorkItem(id = id, title = "Test Item", role = Role.QUEUE)

    private fun actorJson(proof: String): JsonObject =
        buildJsonObject {
            put("id", "agent-shared")
            put("kind", "subagent")
            put("proof", proof)
        }

    /**
     * [AdvanceItemTool]'s success envelope ({success, data: {results, summary}}) is unwrapped by
     * [McpToolAdapter] before it reaches the wire: on success, `structuredContent` IS the "data"
     * object directly (no "success"/"data" nesting) — the same convention already exercised by
     * McpToolAdapterValidationEnvelopeTest's `createResult.structuredContent!!["items"]`.
     */
    private fun resultJson(callResult: CallToolResult): JsonObject =
        assertNotNull(callResult.structuredContent, "expected structuredContent on the response: ${callResult.content}")

    @Test
    fun `S18 two transitions sharing one proof succeed via the memo, a later call replays jti and is rejected`() =
        runBlocking {
            val id1 = UUID.randomUUID()
            val id2 = UUID.randomUUID()
            val id3 = UUID.randomUUID()
            val context = contextFor(mapOf(id1 to makeItem(id1), id2 to makeItem(id2), id3 to makeItem(id3)))
            adapter.registerToolWithServer(server, AdvanceItemTool(), context)

            val sharedProof = sign(claimsFor(subject = "agent-shared", jti = "shared-jti-s18"))

            val call1Params =
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", id1.toString())
                                    put("trigger", "start")
                                    put("actor", actorJson(sharedProof))
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("itemId", id2.toString())
                                    put("trigger", "start")
                                    put("actor", actorJson(sharedProof))
                                }
                            )
                        }
                    )
                }
            val call1Request =
                buildCallToolRequest {
                    name = "advance_item"
                    arguments(call1Params)
                }
            val call1Result = client.callTool(call1Request)
            val call1Body = resultJson(call1Result)

            assertTrue(call1Result.isError != true, "call 1 must succeed overall, got: ${call1Result.content}")
            val call1Results = call1Body["results"]!!.jsonArray
            assertEquals(2, call1Results.size)
            call1Results.forEach { r ->
                assertTrue(
                    r.jsonObject["applied"]!!.jsonPrimitive.boolean,
                    "both transitions sharing one proof must succeed via the per-call memo: $r"
                )
            }

            // A SEPARATE MCP call installs a fresh ActorVerificationScope, so the memo from call 1
            // does not apply here — but the JtiReplayCache is owned by the verifier instance
            // (shared across both calls via the same context), so the jti from call 1 is still on
            // record and this presentation of the identical proof must be rejected as a replay.
            val call2Params =
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", id3.toString())
                                    put("trigger", "start")
                                    put("actor", actorJson(sharedProof))
                                }
                            )
                        }
                    )
                }
            val call2Request =
                buildCallToolRequest {
                    name = "advance_item"
                    arguments(call2Params)
                }
            val call2Result = client.callTool(call2Request)
            val call2Body = resultJson(call2Result)

            val call2Results = call2Body["results"]!!.jsonArray
            assertEquals(1, call2Results.size)
            val r3 = call2Results[0].jsonObject
            assertFalse(
                r3["applied"]!!.jsonPrimitive.boolean,
                "a fresh MCP call replaying the same jti (already recorded by call 1) must be rejected: $r3"
            )
        }
}
