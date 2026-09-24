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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression test for item d426fbfa / decision D4, at the `claim_item` policy boundary (S10):
 * an actor proof that is validly signed and otherwise well-formed but carries no `exp` claim
 * must be REJECTED by [JwksActorVerifier] (see [io.github.jpicklyk.mcptask.current.infrastructure.config.JwksActorVerifierExpRequiredTest]
 * S8), and under `degradedModePolicy = REJECT` that non-VERIFIED status must block the claim —
 * `rejected_by_policy`, item left unclaimed — exactly as an unverified/absent actor already does
 * (see `ClaimItemToolTest`'s existing REJECT-policy tests).
 */
class ClaimItemExpLessProofPolicyTest {
    private lateinit var tool: ClaimItemTool
    private lateinit var mockRepo: MockRepositoryProvider
    private lateinit var workItemRepo: WorkItemRepository

    private val agentId = "agent-exp-less"
    private val itemId = UUID.randomUUID()

    private val rsaKey = RSAKeyGenerator(2048).keyID("claim-exp-less-test-key").generate()

    @BeforeEach
    fun setUp() {
        tool = ClaimItemTool()
        mockRepo = MockRepositoryProvider()
        workItemRepo = mockRepo.workItemRepo
    }

    private fun claimsWithoutExp(): JWTClaimsSet =
        JWTClaimsSet
            .Builder()
            .subject(agentId)
            .issuer("https://test-issuer.example")
            .audience("task-orchestrator")
            .build()

    private fun signExpLess(): String {
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).keyID("claim-exp-less-test-key").build(),
                claimsWithoutExp()
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

    private fun rejectPolicyContextWithJwksVerifier(): ToolExecutionContext {
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

    private fun claimParams(proof: String): JsonObject =
        buildJsonObject {
            put(
                "claims",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemId", itemId.toString())
                        }
                    )
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
            put("requestId", UUID.randomUUID().toString())
        }

    // -------------------------------------------------------------------------
    // S10 — exp-less proof under REJECT policy: rejected_by_policy, item stays unclaimed
    // -------------------------------------------------------------------------

    @Test
    fun `S10 exp-less proof under REJECT policy yields rejected_by_policy and leaves item unclaimed`(): Unit =
        runBlocking {
            val proof = signExpLess()
            val context = rejectPolicyContextWithJwksVerifier()

            val result = tool.execute(claimParams(proof), context)

            val resultObj = result as JsonObject
            assertEquals(false, resultObj["success"]?.jsonPrimitive?.booleanOrNull, "success must be false")

            val errorObj = resultObj["error"] as? JsonObject
            assertNotNull(errorObj, "error object must be present in the response")
            assertEquals(
                "rejected_by_policy",
                errorObj["code"]?.jsonPrimitive?.content,
                "error.code must be exactly \"rejected_by_policy\""
            )
            assertEquals(
                "permanent",
                errorObj["kind"]?.jsonPrimitive?.content,
                "error.kind must be \"permanent\" for rejected_by_policy"
            )

            // Repository must NOT have been called — policy rejection is a pre-DB guard, and the
            // item must remain unclaimed.
            coVerify(exactly = 0) { workItemRepo.claim(any(), any(), any()) }
        }
}
