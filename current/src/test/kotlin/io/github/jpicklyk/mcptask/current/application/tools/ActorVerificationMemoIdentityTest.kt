package io.github.jpicklyk.mcptask.current.application.tools

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.service.ActorVerificationScope
import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksActorVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksResult
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActorVerificationMemoIdentityTest {
    private val rsaKey = RSAKeyGenerator(2048).keyID("memo-identity-test-key").generate()
    private val now: Instant = Instant.now()
    private val testIssuer = "https://test-issuer.example"
    private val testAudience = "task-orchestrator"

    private fun sign(claimsSet: JWTClaimsSet): String {
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).keyID("memo-identity-test-key").build(),
                claimsSet
            )
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    private fun claimsFor(subject: String): JWTClaimsSet =
        JWTClaimsSet
            .Builder()
            .subject(subject)
            .issuer(testIssuer)
            .audience(testAudience)
            .expirationTime(Date.from(now.plusSeconds(300)))
            .build()

    private fun mockKeySetProvider(): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns
            JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun baseConfig(): VerifierConfig.Jwks =
        VerifierConfig.Jwks(
            jwksPath = "/unused-in-unit-tests",
            issuer = testIssuer,
            audience = testAudience,
            requireSubMatch = true
        )

    private class CountingActorVerifier(
        private val delegate: ActorVerifier
    ) : ActorVerifier {
        var invocationCount: Int = 0
            private set

        override suspend fun verify(actor: ActorClaim): VerificationResult {
            invocationCount++
            return delegate.verify(actor)
        }
    }

    private fun contextWith(verifier: ActorVerifier): ToolExecutionContext =
        ToolExecutionContext(
            repositoryProvider = mockk<RepositoryProvider>(relaxed = true),
            actorVerifier = verifier,
            degradedModePolicy = DegradedModePolicy.REJECT
        )

    private fun actorJson(
        id: String,
        proof: String,
        kind: String = "subagent",
        parent: String? = null
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("kind", kind)
            put("proof", proof)
            if (parent != null) put("parent", parent)
        }

    @Test
    fun `a proof verified as sub attacker is not served VERIFIED for a differing actor id`() =
        runBlocking {
            val verifier = JwksActorVerifier(config = baseConfig(), keySetProvider = mockKeySetProvider())
            val counting = CountingActorVerifier(verifier)
            val context = contextWith(counting)
            val tool = AdvanceItemTool()
            val proof = sign(claimsFor(subject = "attacker"))

            withContext(ActorVerificationScope()) {
                val attackerResult =
                    assertIs<ActorParseResult.Success>(
                        tool.parseActorClaim(actorJson(id = "attacker", proof = proof), context)
                    )
                assertEquals(
                    VerificationStatus.VERIFIED,
                    attackerResult.verification.status,
                    "per RFC 7519 s4.1.2, sub=attacker identifies the principal and matches actor.id=attacker"
                )

                val victimResult =
                    assertIs<ActorParseResult.Success>(
                        tool.parseActorClaim(actorJson(id = "victim", proof = proof), context)
                    )
                assertEquals(
                    VerificationStatus.REJECTED,
                    victimResult.verification.status,
                    "the SAME proof (sub=attacker) presented for actor.id=victim must be independently " +
                        "re-checked, not served the memoized VERIFIED result: with require_sub_match on, " +
                        "sub != actor.id must REJECT. Observed: ${victimResult.verification}"
                )

                val resolution =
                    ActorAware.resolveTrustedActorId(
                        victimResult.claim,
                        victimResult.verification,
                        DegradedModePolicy.REJECT
                    )
                assertIs<PolicyResolution.Rejected>(
                    resolution,
                    "a REJECTED verification under DegradedModePolicy.REJECT must never resolve to a trusted id"
                )

                assertEquals(
                    2,
                    counting.invocationCount,
                    "a memo entry must never yield a verification result for a different (id, kind, parent) " +
                        "than the one verified -- two distinct actor identities sharing one proof must each " +
                        "hit the real verifier once"
                )
            }
        }

    @Test
    fun `same proof and same actor id but a differing kind is verified independently, not merged in the memo`() =
        runBlocking {
            val verifier = JwksActorVerifier(config = baseConfig(), keySetProvider = mockKeySetProvider())
            val counting = CountingActorVerifier(verifier)
            val context = contextWith(counting)
            val tool = AdvanceItemTool()
            val proof = sign(claimsFor(subject = "attacker"))

            withContext(ActorVerificationScope()) {
                val subagentResult =
                    assertIs<ActorParseResult.Success>(
                        tool.parseActorClaim(actorJson(id = "attacker", proof = proof, kind = "subagent"), context)
                    )
                val orchestratorResult =
                    assertIs<ActorParseResult.Success>(
                        tool.parseActorClaim(
                            actorJson(id = "attacker", proof = proof, kind = "orchestrator"),
                            context
                        )
                    )

                assertEquals(VerificationStatus.VERIFIED, subagentResult.verification.status)
                assertEquals(VerificationStatus.VERIFIED, orchestratorResult.verification.status)
                assertEquals(
                    2,
                    counting.invocationCount,
                    "a memo entry keyed without actor kind would incorrectly serve the differing-kind " +
                        "lookup from the first -- the real verifier must be hit once per distinct " +
                        "(id, kind, parent)"
                )
            }
        }

    @Test
    fun `same proof, same actor id and kind but a differing parent is verified independently, not merged in the memo`() =
        runBlocking {
            val verifier = JwksActorVerifier(config = baseConfig(), keySetProvider = mockKeySetProvider())
            val counting = CountingActorVerifier(verifier)
            val context = contextWith(counting)
            val tool = AdvanceItemTool()
            val proof = sign(claimsFor(subject = "attacker"))

            withContext(ActorVerificationScope()) {
                val teamAResult =
                    assertIs<ActorParseResult.Success>(
                        tool.parseActorClaim(actorJson(id = "attacker", proof = proof, parent = "team-a"), context)
                    )
                val teamBResult =
                    assertIs<ActorParseResult.Success>(
                        tool.parseActorClaim(actorJson(id = "attacker", proof = proof, parent = "team-b"), context)
                    )

                assertEquals(VerificationStatus.VERIFIED, teamAResult.verification.status)
                assertEquals(VerificationStatus.VERIFIED, teamBResult.verification.status)
                assertEquals(
                    2,
                    counting.invocationCount,
                    "a memo entry keyed without actor parent would incorrectly serve the differing-parent " +
                        "lookup from the first -- the real verifier must be hit once per distinct " +
                        "(id, kind, parent)"
                )
            }
        }

    @Test
    fun `positive control - identical proof and identical identity is memo-served within one call`() =
        runBlocking {
            val verifier = JwksActorVerifier(config = baseConfig(), keySetProvider = mockKeySetProvider())
            val counting = CountingActorVerifier(verifier)
            val context = contextWith(counting)
            val tool = AdvanceItemTool()
            val proof = sign(claimsFor(subject = "attacker"))
            val actorObj = actorJson(id = "attacker", proof = proof)

            withContext(ActorVerificationScope()) {
                val first = assertIs<ActorParseResult.Success>(tool.parseActorClaim(actorObj, context))
                val second = assertIs<ActorParseResult.Success>(tool.parseActorClaim(actorObj, context))

                assertEquals(VerificationStatus.VERIFIED, first.verification.status)
                assertEquals(
                    first.verification,
                    second.verification,
                    "an identical repeat of the same identity and proof within one call must return the " +
                        "same memoized VerificationResult"
                )
                assertEquals(
                    1,
                    counting.invocationCount,
                    "identical (proof, id, kind, parent) presented twice within one call must be " +
                        "memo-served the second time, not re-verified -- this is the positive control " +
                        "proving the memo is still effective for the identity it was recorded for"
                )
            }
        }
}
