package io.github.jpicklyk.mcptask.current.infrastructure.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.Ed25519Signer
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.service.ActorTrustPolicy
import io.github.jpicklyk.mcptask.current.application.service.PolicyResolution
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.IssuerNotTrustedException
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksActorVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksResult
import io.github.jpicklyk.mcptask.current.test.actorJson
import io.github.jpicklyk.mcptask.current.test.advanceTool
import io.github.jpicklyk.mcptask.current.test.claimOp
import io.github.jpicklyk.mcptask.current.test.errorCode
import io.github.jpicklyk.mcptask.current.test.onlyResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.security.SecureRandom
import java.security.Security
import java.time.Instant
import java.util.Date

/**
 * S1-3, S6-9, S16-17 from item a890542c's `test-plan` — sub/iss binding under DID-rooted trust
 * (decisions D1/D2 in the `diagnosis` note).
 *
 * A = did:web:a.example.com, V = did:web:v.example.com, one Ed25519 key each, per the frozen
 * test-plan. All scenarios here are labelled EXISTING-SURFACE except where they touch
 * [io.github.jpicklyk.mcptask.current.domain.model.VerificationResult.verifiedSubject], which is
 * a new field with a `null` default — a plain revert of the fix still compiles against it, so
 * red-first is behavioral, not compile-red.
 *
 * Q32 port: S3 and S9 drive the production `advance` tool's `claim` op on a real SQLite database (formerly the removed
 * 3.x claim_item over a mocked repository): the holder recorded on the row is the oracle for the trusted id.
 */
class DidTrustSubjectBindingTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private const val A_DID = "did:web:a.example.com"
        private const val V_DID = "did:web:v.example.com"

        private fun genEdKey(kid: String): OctetKeyPair {
            val gen = Ed25519KeyPairGenerator()
            gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val pair = gen.generateKeyPair()
            val priv = pair.private as Ed25519PrivateKeyParameters
            val pub = pair.public as Ed25519PublicKeyParameters
            return OctetKeyPair
                .Builder(Curve.Ed25519, Base64URL.encode(pub.encoded))
                .d(Base64URL.encode(priv.encoded))
                .keyID(kid)
                .build()
        }

        private val aKey = genEdKey("a-key")
        private val vKey = genEdKey("v-key")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun claims(
        subject: String?,
        issuer: String,
        expiry: Instant = Instant.now().plusSeconds(300)
    ): JWTClaimsSet {
        val builder =
            JWTClaimsSet
                .Builder()
                .issuer(issuer)
                .expirationTime(Date.from(expiry))
        if (subject != null) builder.subject(subject)
        return builder.build()
    }

    private fun sign(
        claimsSet: JWTClaimsSet,
        key: OctetKeyPair,
        kid: String = key.keyID
    ): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.Ed25519).keyID(kid).build(), claimsSet)
        jwt.sign(Ed25519Signer(key))
        return jwt.serialize()
    }

    private fun actor(
        id: String,
        proof: String?
    ) = ActorClaim(id = id, kind = ActorKind.SUBAGENT, proof = proof)

    private fun didConfig(
        didAllowlist: List<String> = listOf(A_DID, V_DID),
        didLooseKidMatch: Boolean = true,
        requireSubMatch: Boolean = true
    ) = VerifierConfig.Jwks(
        didAllowlist = didAllowlist,
        didLooseKidMatch = didLooseKidMatch,
        requireSubMatch = requireSubMatch
    )

    private fun mockProvider(vararg issuerToJwks: Pair<String, JWKSet>): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        issuerToJwks.forEach { (iss, jwks) ->
            coEvery { provider.getKeySetForIssuer(iss) } returns
                JwksResult(jwks, CacheState(fromStaleCache = false, ageSeconds = null))
        }
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun aJwks() = JWKSet(listOf(aKey.toPublicJWK()))

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    // S1: allowlist [A,V], JWT {iss A, sub A}, actor.id A, requireSubMatch=true -> VERIFIED. D1 (guard)
    @Test
    fun `S1 sub equals iss under DID trust with requireSubMatch true returns VERIFIED`() =
        runTest {
            val jwt = sign(claims(subject = A_DID, issuer = A_DID), aKey)
            val provider = mockProvider(A_DID to aJwks())

            val result =
                JwksActorVerifier(
                    config = didConfig(requireSubMatch = true),
                    keySetProvider = provider
                ).verify(actor(id = A_DID, proof = jwt))

            assertEquals(VerificationStatus.VERIFIED, result.status)
            assertEquals(A_DID, result.verifiedSubject)
        }

    // S2: JWT {A,A}, requireSubMatch=false, actor.id "planner" -> VERIFIED; resolveTrustedActorId
    // (REJECT, ACCEPT_CACHED) -> Trusted(A). D2, fleet-deployment.md:659-662
    @Test
    fun `S2 verified DID subject is trusted over an unrelated self-reported actor id`() =
        runTest {
            val jwt = sign(claims(subject = A_DID, issuer = A_DID), aKey)
            val provider = mockProvider(A_DID to aJwks())

            val verification =
                JwksActorVerifier(
                    config = didConfig(requireSubMatch = false),
                    keySetProvider = provider
                ).verify(actor(id = "planner", proof = jwt))

            assertEquals(VerificationStatus.VERIFIED, verification.status)
            assertEquals(A_DID, verification.verifiedSubject)

            val claim = actor(id = "planner", proof = jwt)

            val rejectResolution = ActorTrustPolicy.resolveTrustedActorId(claim, verification, DegradedModePolicy.REJECT)
            assertTrue(rejectResolution is PolicyResolution.Trusted)
            assertEquals(A_DID, (rejectResolution as PolicyResolution.Trusted).trustedId)

            val cachedResolution =
                ActorTrustPolicy.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_CACHED)
            assertTrue(cachedResolution is PolicyResolution.Trusted)
            assertEquals(A_DID, (cachedResolution as PolicyResolution.Trusted).trustedId)
        }

    // S3: advance claim, REJECT, real JwksActorVerifier as S2 -> the item is claimed by A. D2
    @Test
    fun `S3 an advance claim trusts the verified DID subject over the self-reported actor id under REJECT policy`(): Unit =
        runBlocking {
            val jwt = sign(claims(subject = A_DID, issuer = A_DID), aKey)
            val provider = mockProvider(A_DID to aJwks())
            val verifier =
                JwksActorVerifier(
                    config = didConfig(requireSubMatch = false),
                    keySetProvider = provider
                )
            val item = db.repositoryProvider().workItemRepository().create(WorkItem(title = "S3 item", role = Role.QUEUE))
            val context =
                ToolExecutionContext(
                    repositoryProvider = db.repositoryProvider(),
                    actorVerifier = verifier,
                    degradedModePolicy = DegradedModePolicy.REJECT,
                    unitOfWork = db.unitOfWork()
                )

            val result = context.advanceTool(claimOp(item.id), actor = actorJson("planner", proof = jwt)).onlyResult()

            assertEquals(true, result["ok"]?.jsonPrimitive?.content?.toBoolean(), "$result")
            assertEquals(
                A_DID,
                db
                    .repositoryProvider()
                    .workItemRepository()
                    .getById(item.id)!!
                    .claimedBy,
                "the claim holder is the DID subject"
            )
        }

    // -------------------------------------------------------------------------
    // Failure
    // -------------------------------------------------------------------------

    // S6: JWT {iss A, sub V}, actor.id V, requireSubMatch=true -> REJECTED, failureKind=claims,
    // reason starts "sub/iss mismatch under DID trust"; REJECT -> Rejected. D1
    @Test
    fun `S6 sub not equal iss under DID trust returns REJECTED with claims failureKind`() =
        runTest {
            val jwt = sign(claims(subject = V_DID, issuer = A_DID), aKey)
            val provider = mockProvider(A_DID to aJwks())

            val verification =
                JwksActorVerifier(
                    config = didConfig(requireSubMatch = true),
                    keySetProvider = provider
                ).verify(actor(id = V_DID, proof = jwt))

            assertEquals(VerificationStatus.REJECTED, verification.status)
            assertEquals("claims", verification.metadata["failureKind"])
            assertTrue(
                verification.reason?.startsWith("sub/iss mismatch under DID trust") == true,
                "reason: ${verification.reason}"
            )
            assertNull(verification.verifiedSubject)

            val resolution =
                ActorTrustPolicy.resolveTrustedActorId(
                    actor(id = V_DID, proof = jwt),
                    verification,
                    DegradedModePolicy.REJECT
                )
            assertTrue(resolution is PolicyResolution.Rejected)
        }

    // S7: S6 with requireSubMatch=false -> same. D1
    @Test
    fun `S7 sub not equal iss under DID trust rejects even with requireSubMatch false`() =
        runTest {
            val jwt = sign(claims(subject = V_DID, issuer = A_DID), aKey)
            val provider = mockProvider(A_DID to aJwks())

            val result =
                JwksActorVerifier(
                    config = didConfig(requireSubMatch = false),
                    keySetProvider = provider
                ).verify(actor(id = V_DID, proof = jwt))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("claims", result.metadata["failureKind"])
            assertTrue(
                result.reason?.startsWith("sub/iss mismatch under DID trust") == true,
                "reason: ${result.reason}"
            )
        }

    // S8a: requireSubMatch=false, sub absent -> same rejection. D1
    @Test
    fun `S8a missing sub under DID trust rejects as mismatch`() =
        runTest {
            val jwt = sign(claims(subject = null, issuer = A_DID), aKey)
            val provider = mockProvider(A_DID to aJwks())

            val result =
                JwksActorVerifier(
                    config = didConfig(requireSubMatch = false),
                    keySetProvider = provider
                ).verify(actor(id = A_DID, proof = jwt))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("claims", result.metadata["failureKind"])
            assertTrue(
                result.reason?.startsWith("sub/iss mismatch under DID trust") == true,
                "reason: ${result.reason}"
            )
        }

    // S8b: requireSubMatch=false, sub "" -> same rejection. D1
    @Test
    fun `S8b blank sub under DID trust rejects as mismatch`() =
        runTest {
            val jwt = sign(claims(subject = "", issuer = A_DID), aKey)
            val provider = mockProvider(A_DID to aJwks())

            val result =
                JwksActorVerifier(
                    config = didConfig(requireSubMatch = false),
                    keySetProvider = provider
                ).verify(actor(id = A_DID, proof = jwt))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("claims", result.metadata["failureKind"])
            assertTrue(
                result.reason?.startsWith("sub/iss mismatch under DID trust") == true,
                "reason: ${result.reason}"
            )
        }

    // S9: advance claim REJECT, S6 proof -> the element fails unauthenticated and the item is never claimed. D1, fleet-deployment.md:345
    @Test
    fun `S9 an advance claim never claims when a sub-iss mismatch is rejected under REJECT policy`(): Unit =
        runBlocking {
            val jwt = sign(claims(subject = V_DID, issuer = A_DID), aKey)
            val provider = mockProvider(A_DID to aJwks())
            val verifier =
                JwksActorVerifier(
                    config = didConfig(requireSubMatch = true),
                    keySetProvider = provider
                )
            val item = db.repositoryProvider().workItemRepository().create(WorkItem(title = "S9 item", role = Role.QUEUE))
            val context =
                ToolExecutionContext(
                    repositoryProvider = db.repositoryProvider(),
                    actorVerifier = verifier,
                    degradedModePolicy = DegradedModePolicy.REJECT,
                    unitOfWork = db.unitOfWork()
                )

            val result = context.advanceTool(claimOp(item.id), actor = actorJson(V_DID, proof = jwt)).onlyResult()

            assertEquals(false, result["ok"]?.jsonPrimitive?.content?.toBoolean(), "$result")
            assertEquals("unauthenticated", result.errorCode(), "$result")
            assertNull(
                db
                    .repositoryProvider()
                    .workItemRepository()
                    .getById(item.id)!!
                    .claimedBy,
                "the claim never happened"
            )
        }

    // -------------------------------------------------------------------------
    // Edge / ordering guards
    // -------------------------------------------------------------------------

    // S16a: untrusted issuer takes precedence over the sub/iss claims check -> failureKind=policy. D1 (guard)
    @Test
    fun `S16a untrusted issuer rejects as policy even when sub does not equal iss`() =
        runTest {
            val untrustedIssuer = "did:web:untrusted.example.com"
            val jwt = sign(claims(subject = V_DID, issuer = untrustedIssuer), aKey)

            val provider = mockk<JwksKeySetProvider>()
            coEvery { provider.getKeySetForIssuer(untrustedIssuer) } throws IssuerNotTrustedException(untrustedIssuer)
            every { provider.getResolvedIssuer() } returns null
            every { provider.close() } just Runs

            val result =
                JwksActorVerifier(
                    config = didConfig(requireSubMatch = true),
                    keySetProvider = provider
                ).verify(actor(id = V_DID, proof = jwt))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("policy", result.metadata["failureKind"])
        }

    // S16b: kid mismatch (crypto) takes precedence over the sub/iss claims check.
    // D1 (guard, integration case 7 / DID-V5 unchanged)
    @Test
    fun `S16b kid mismatch rejects as crypto even when sub does not equal iss`() =
        runTest {
            val jwt = sign(claims(subject = V_DID, issuer = A_DID), aKey, kid = "wrong-kid")
            val provider = mockProvider(A_DID to aJwks())

            val result =
                JwksActorVerifier(
                    config = didConfig(didLooseKidMatch = false, requireSubMatch = true),
                    keySetProvider = provider
                ).verify(actor(id = V_DID, proof = jwt))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("crypto", result.metadata["failureKind"])
            assertTrue(result.reason?.contains("no matching key") == true, "reason: ${result.reason}")
        }

    // S17: static-JWKS verification leaves verifiedSubject null; resolveTrustedActorId falls back
    // to claim.id under REJECT. D2 (guard — the DID-only verifiedSubject-preferring behavior must
    // not leak into static-JWKS mode).
    @Test
    fun `S17 static JWKS verification does not set verifiedSubject and REJECT trusts claim id`() =
        runTest {
            val rsaKey = RSAKeyGenerator(2048).keyID("s17-rsa-key").generate()
            val claimsSet =
                JWTClaimsSet
                    .Builder()
                    .subject("svc")
                    .issuer("https://static-issuer.example")
                    .audience("task-orchestrator")
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build()
            val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("s17-rsa-key").build(), claimsSet)
            jwt.sign(RSASSASigner(rsaKey))
            val jwtStr = jwt.serialize()

            val provider = mockk<JwksKeySetProvider>()
            coEvery { provider.getKeySet() } returns
                JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
            every { provider.getResolvedIssuer() } returns null
            every { provider.close() } just Runs

            val config =
                VerifierConfig.Jwks(
                    jwksPath = "/unused-in-unit-tests",
                    issuer = "https://static-issuer.example",
                    audience = "task-orchestrator",
                    requireSubMatch = false
                    // No didAllowlist/didPattern -> isDidTrust=false
                )
            val verification =
                JwksActorVerifier(config = config, keySetProvider = provider)
                    .verify(actor(id = "agent-1", proof = jwtStr))

            assertEquals(VerificationStatus.VERIFIED, verification.status)
            assertNull(verification.verifiedSubject)

            val resolution =
                ActorTrustPolicy.resolveTrustedActorId(
                    actor(id = "agent-1", proof = jwtStr),
                    verification,
                    DegradedModePolicy.REJECT
                )
            assertTrue(resolution is PolicyResolution.Trusted)
            assertEquals("agent-1", (resolution as PolicyResolution.Trusted).trustedId)
        }
}
