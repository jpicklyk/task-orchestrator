package io.github.jpicklyk.mcptask.current.application.tools.workflow

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.config.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksActorVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksResult
import io.github.jpicklyk.mcptask.current.test.MockRepositoryProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Independent test-author coverage for item 3dcfcbab, test-plan S15 / task-scope Part 5's test
 * gap: "idempotent-replay cache hit (REJECT policy: a cached VERIFIED (actor,requestId) response
 * must not be served to a later call whose proof is REJECTED)".
 *
 * Labelled EXISTING-SURFACE per the test-plan: the sequencing this locks down — `parseActorClaim`
 * runs BEFORE `idempotencyCache.getOrCompute` on every call, so a retry always re-verifies its own
 * proof before the idempotency cache is ever consulted — is pre-existing behavior, unchanged by
 * this item's fix, and not itself under test here. What is new is the specific scenario: the SAME
 * `requestId` is reused across two calls, the first with a valid proof (which succeeds and is
 * cached), the second with an exp-less proof for the same actor id. If idempotency were checked
 * before actor verification, the second call would incorrectly replay the first call's cached
 * success. This is a regression lock — expected green both before and after the 3dcfcbab fix,
 * since the fix does not touch this ordering.
 *
 * Deliberately independent fixtures from [ClaimItemExpLessProofPolicyTest] — own RSA key, own
 * claims/signing helpers — even though the two files share the same underlying "exp-less proof
 * under REJECT policy" building block.
 */
class ClaimItemIdempotentReplayProofTest {
    private lateinit var tool: ClaimItemTool
    private lateinit var mockRepo: MockRepositoryProvider
    private lateinit var workItemRepo: WorkItemRepository

    private val agentId = "agent-idempotent-replay"
    private val itemId = UUID.randomUUID()
    private val rsaKey = RSAKeyGenerator(2048).keyID("claim-idempotent-replay-test-key").generate()

    @BeforeEach
    fun setUp() {
        tool = ClaimItemTool()
        mockRepo = MockRepositoryProvider()
        workItemRepo = mockRepo.workItemRepo
    }

    private fun validClaims(): JWTClaimsSet =
        JWTClaimsSet
            .Builder()
            .subject(agentId)
            .issuer("https://test-issuer.example")
            .audience("task-orchestrator")
            .expirationTime(java.util.Date.from(Instant.now().plusSeconds(300)))
            .build()

    private fun expLessClaims(): JWTClaimsSet =
        JWTClaimsSet
            .Builder()
            .subject(agentId)
            .issuer("https://test-issuer.example")
            .audience("task-orchestrator")
            .build()

    private fun sign(claimsSet: JWTClaimsSet): String {
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).keyID("claim-idempotent-replay-test-key").build(),
                claimsSet
            )
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    private fun mockKeySetProvider(): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns
            JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    /** A single shared context — its IdempotencyCache instance must persist across both calls. */
    private fun sharedRejectPolicyContext(): ToolExecutionContext {
        val verifier =
            JwksActorVerifier(
                config =
                    VerifierConfig.Jwks(
                        jwksPath = "/unused-in-unit-tests",
                        issuer = "https://test-issuer.example",
                        audience = "task-orchestrator",
                        requireSubMatch = true
                    ),
                keySetProvider = mockKeySetProvider()
            )
        return ToolExecutionContext(
            repositoryProvider = mockRepo.provider,
            actorVerifier = verifier,
            degradedModePolicy = DegradedModePolicy.REJECT
        )
    }

    private fun claimParams(
        proof: String,
        requestId: String
    ): JsonObject =
        buildJsonObject {
            put(
                "claims",
                buildJsonArray {
                    add(buildJsonObject { put("itemId", itemId.toString()) })
                }
            )
            put(
                "actor",
                buildJsonObject {
                    put("id", agentId)
                    put("kind", "subagent")
                    put("proof", proof)
                }
            )
            put("requestId", requestId)
        }

    @Test
    fun `S15 a retry with the same requestId but a rejected proof is rejected_by_policy, not served the cached success`(): Unit =
        runBlocking {
            val requestId = UUID.randomUUID().toString()
            val sharedContext = sharedRejectPolicyContext()

            // Derive claimedAt/claimExpiresAt/originalClaimedAt from a SINGLE `now` capture -
            // three independent Instant.now() calls can race such that originalClaimedAt lands
            // after claimedAt, which WorkItem.validate() rejects outright (fixture-invariant
            // pitfall: derive dependent fields from each other, never independently).
            val claimNow = Instant.now()
            coEvery { workItemRepo.claim(itemId, agentId, 900) } returns
                ClaimResult.Success(
                    io.github.jpicklyk.mcptask.current.domain.model.WorkItem(
                        id = itemId,
                        title = "Test Item",
                        claimedBy = agentId,
                        claimedAt = claimNow,
                        claimExpiresAt = claimNow.plusSeconds(900),
                        originalClaimedAt = claimNow
                    )
                )

            // Call 1: valid proof, same requestId — succeeds and is cached under (actor, requestId).
            val validProof = sign(validClaims())
            val result1 = tool.execute(claimParams(validProof, requestId), sharedContext)
            val body1 = result1 as JsonObject
            assertEquals(true, body1["success"]?.jsonPrimitive?.booleanOrNull, "call 1 with a valid proof must succeed")

            // Call 2: SAME requestId, but an exp-less (REJECTED) proof for the same actor id.
            // If the idempotency cache were checked before actor verification, this would
            // incorrectly return call 1's cached success instead of a fresh policy rejection.
            val expLessProof = sign(expLessClaims())
            val result2 = tool.execute(claimParams(expLessProof, requestId), sharedContext)
            val body2 = result2 as JsonObject

            assertEquals(
                false,
                body2["success"]?.jsonPrimitive?.booleanOrNull,
                "call 2 must NOT replay call 1's cached success"
            )
            val error2 = body2["error"] as? JsonObject
            assertNotNull(error2, "call 2 must report a fresh error object, not a cached success")
            assertEquals(
                "rejected_by_policy",
                error2["code"]?.jsonPrimitive?.content,
                "call 2's rejection must be rejected_by_policy, exactly as a first-time exp-less proof would be"
            )

            // The repository must have been called exactly once (call 1) — call 2 never reached
            // claim() at all, whether via a fresh policy check or (wrongly) via a cache replay of
            // a call that itself never re-invoked claim().
            coVerify(exactly = 1) { workItemRepo.claim(itemId, agentId, 900) }
        }
}
