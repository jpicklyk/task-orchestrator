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
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.security.Security
import java.time.Clock
import java.time.Instant
import java.util.Date

class JwksActorVerifierTest {
    companion object {
        init {
            // Register BouncyCastle before any crypto operations.
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        // RSA key — uses standard JCA (no Tink needed).
        val rsaKey = RSAKeyGenerator(2048).keyID("rsa-test-key").generate()

        // Ed25519 key — constructed via Bouncy Castle raw API to avoid Tink dependency.
        val edKey: OctetKeyPair =
            run {
                val gen = Ed25519KeyPairGenerator()
                gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
                val pair = gen.generateKeyPair()
                val priv = pair.private as Ed25519PrivateKeyParameters
                val pub = pair.public as Ed25519PublicKeyParameters
                OctetKeyPair
                    .Builder(Curve.Ed25519, Base64URL.encode(pub.encoded))
                    .d(Base64URL.encode(priv.encoded))
                    .keyID("ed-test-key")
                    .build()
            }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildClaims(
        subject: String = "agent-1",
        issuer: String = "https://test-issuer.example",
        audience: String = "task-orchestrator",
        expiry: Instant = Instant.now().plusSeconds(300)
    ): JWTClaimsSet =
        JWTClaimsSet
            .Builder()
            .subject(subject)
            .issuer(issuer)
            .audience(audience)
            .expirationTime(Date.from(expiry))
            .build()

    private fun signRsa(claims: JWTClaimsSet = buildClaims()): String {
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rsa-test-key").build(),
                claims
            )
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    private fun signEd(claims: JWTClaimsSet = buildClaims()): String {
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.Ed25519).keyID("ed-test-key").build(),
                claims
            )
        jwt.sign(Ed25519Signer(edKey))
        return jwt.serialize()
    }

    /** Actor whose id matches the JWT sub claim. */
    private fun actor(
        id: String = "agent-1",
        proof: String? = null
    ) = ActorClaim(id = id, kind = ActorKind.SUBAGENT, proof = proof)

    /** Mock provider that returns the RSA public key. */
    private fun rsaMockProvider(resolvedIssuer: String? = null): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns JWKSet(listOf(rsaKey.toPublicJWK()))
        every { provider.getResolvedIssuer() } returns resolvedIssuer
        every { provider.close() } just Runs
        return provider
    }

    /** Mock provider that returns the EdDSA public key. */
    private fun edMockProvider(resolvedIssuer: String? = null): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns JWKSet(listOf(edKey.toPublicJWK()))
        every { provider.getResolvedIssuer() } returns resolvedIssuer
        every { provider.close() } just Runs
        return provider
    }

    private fun baseConfig(
        issuer: String? = "https://test-issuer.example",
        audience: String? = "task-orchestrator",
        algorithms: List<String> = emptyList(),
        requireSubMatch: Boolean = true
    ) = VerifierConfig.Jwks(
        jwksPath = "/unused-in-unit-tests",
        issuer = issuer,
        audience = audience,
        algorithms = algorithms,
        requireSubMatch = requireSubMatch
    )

    private fun verifier(
        config: VerifierConfig.Jwks = baseConfig(),
        provider: JwksKeySetProvider = rsaMockProvider(),
        clock: Clock = Clock.systemUTC()
    ) = JwksActorVerifier(config = config, keySetProvider = provider, clock = clock)

    // =========================================================================
    // Tests
    // =========================================================================

    // 1. null proof → UNVERIFIED
    @Test
    fun `null proof returns UNVERIFIED`() =
        runTest {
            val result = verifier().verify(actor(proof = null))
            assertEquals(VerificationStatus.UNVERIFIED, result.status)
            assertEquals("jwks", result.verifier)
        }

    // 2. blank proof → UNVERIFIED
    @Test
    fun `blank proof returns UNVERIFIED`() =
        runTest {
            val result = verifier().verify(actor(proof = ""))
            assertEquals(VerificationStatus.UNVERIFIED, result.status)
            assertEquals("jwks", result.verifier)
        }

    // 3. invalid JWT string → FAILED
    @Test
    fun `invalid JWT string returns FAILED`() =
        runTest {
            val result = verifier().verify(actor(proof = "not-a-jwt"))
            assertEquals(VerificationStatus.FAILED, result.status)
            assertNotNull(result.reason)
        }

    // 4. valid RSA JWT → VERIFIED
    @Test
    fun `valid RSA JWT returns VERIFIED`() =
        runTest {
            val result = verifier(provider = rsaMockProvider()).verify(actor(proof = signRsa()))
            assertEquals(VerificationStatus.VERIFIED, result.status)
            assertEquals("jwks", result.verifier)
        }

    // 5. valid EdDSA JWT → VERIFIED
    @Test
    fun `valid EdDSA JWT returns VERIFIED`() =
        runTest {
            val config = baseConfig(algorithms = emptyList())
            val result = verifier(config = config, provider = edMockProvider()).verify(actor(proof = signEd()))
            assertEquals(VerificationStatus.VERIFIED, result.status)
            assertEquals("jwks", result.verifier)
        }

    // 6. expired JWT → FAILED
    @Test
    fun `expired JWT returns FAILED`() =
        runTest {
            val expiredClaims = buildClaims(expiry = Instant.now().minusSeconds(600))
            val result = verifier().verify(actor(proof = signRsa(expiredClaims)))
            assertEquals(VerificationStatus.FAILED, result.status)
            assertTrue(result.reason?.contains("expired") == true, "reason should mention 'expired': ${result.reason}")
        }

    // 7. wrong issuer → FAILED
    @Test
    fun `wrong issuer returns FAILED`() =
        runTest {
            val claims = buildClaims(issuer = "https://other-issuer.example")
            val result = verifier().verify(actor(proof = signRsa(claims)))
            assertEquals(VerificationStatus.FAILED, result.status)
            assertTrue(result.reason?.contains("issuer") == true, "reason should mention 'issuer': ${result.reason}")
        }

    // 8. wrong audience → FAILED
    @Test
    fun `wrong audience returns FAILED`() =
        runTest {
            val claims = buildClaims(audience = "wrong-audience")
            val result = verifier().verify(actor(proof = signRsa(claims)))
            assertEquals(VerificationStatus.FAILED, result.status)
            assertTrue(result.reason?.contains("audience") == true, "reason should mention 'audience': ${result.reason}")
        }

    // 9. algorithm not in allowlist → FAILED
    @Test
    fun `algorithm not in allowlist returns FAILED`() =
        runTest {
            val config = baseConfig(algorithms = listOf("EdDSA"))
            val result = verifier(config = config, provider = rsaMockProvider()).verify(actor(proof = signRsa()))
            assertEquals(VerificationStatus.FAILED, result.status)
            assertTrue(result.reason?.contains("algorithm not allowed") == true, "reason: ${result.reason}")
        }

    // 10. sub mismatch + requireSubMatch=true → FAILED
    @Test
    fun `sub mismatch with strict match returns FAILED`() =
        runTest {
            val claims = buildClaims(subject = "other-agent")
            // actor.id = "agent-1" but JWT sub = "other-agent"
            val result =
                verifier(config = baseConfig(requireSubMatch = true)).verify(
                    actor(id = "agent-1", proof = signRsa(claims))
                )
            assertEquals(VerificationStatus.FAILED, result.status)
            assertTrue(result.reason?.contains("sub mismatch") == true, "reason: ${result.reason}")
        }

    // 11. sub mismatch + requireSubMatch=false → VERIFIED
    @Test
    fun `sub mismatch with relaxed match returns VERIFIED`() =
        runTest {
            val claims = buildClaims(subject = "other-agent")
            val config = baseConfig(requireSubMatch = false)
            val result = verifier(config = config).verify(actor(id = "agent-1", proof = signRsa(claims)))
            assertEquals(VerificationStatus.VERIFIED, result.status)
        }

    // 12. provider throws → FAILED (graceful)
    @Test
    fun `provider exception returns FAILED`() =
        runTest {
            val brokenProvider = mockk<JwksKeySetProvider>()
            coEvery { brokenProvider.getKeySet() } throws RuntimeException("network unreachable")
            every { brokenProvider.getResolvedIssuer() } returns null
            every { brokenProvider.close() } just Runs

            val result = verifier(provider = brokenProvider).verify(actor(proof = signRsa()))
            assertEquals(VerificationStatus.FAILED, result.status)
            assertNotNull(result.reason)
        }

    // 13. OIDC-discovered issuer used when config.issuer is null
    @Test
    fun `OIDC-discovered issuer is used when config issuer is null`() =
        runTest {
            // Config has no explicit issuer, but provider discovered one via OIDC
            val config = baseConfig(issuer = null)
            val provider = rsaMockProvider(resolvedIssuer = "https://test-issuer.example")
            // JWT issuer matches discovered issuer → VERIFIED
            val result = verifier(config = config, provider = provider).verify(actor(proof = signRsa()))
            assertEquals(VerificationStatus.VERIFIED, result.status)
        }

    // 14. OIDC-discovered issuer rejects mismatched JWT
    @Test
    fun `OIDC-discovered issuer rejects mismatched JWT issuer`() =
        runTest {
            val config = baseConfig(issuer = null)
            val provider = rsaMockProvider(resolvedIssuer = "https://expected-issuer.example")
            // JWT issuer is "https://test-issuer.example" but discovered issuer is different
            val result = verifier(config = config, provider = provider).verify(actor(proof = signRsa()))
            assertEquals(VerificationStatus.FAILED, result.status)
            assertTrue(result.reason?.contains("issuer mismatch") == true, "reason: ${result.reason}")
        }

    // 15. explicit config.issuer overrides OIDC-discovered issuer
    @Test
    fun `explicit config issuer overrides OIDC-discovered issuer`() =
        runTest {
            // Config has explicit issuer matching JWT; discovered issuer is different
            val config = baseConfig(issuer = "https://test-issuer.example")
            val provider = rsaMockProvider(resolvedIssuer = "https://other-issuer.example")
            val result = verifier(config = config, provider = provider).verify(actor(proof = signRsa()))
            assertEquals(VerificationStatus.VERIFIED, result.status)
        }

    // 16. no matching kid → FAILED
    @Test
    fun `no matching kid returns FAILED`() =
        runTest {
            // Provider only has the EdDSA key; JWT is signed with RSA (kid="rsa-test-key")
            val result = verifier(provider = edMockProvider()).verify(actor(proof = signRsa()))
            assertEquals(VerificationStatus.FAILED, result.status)
            assertTrue(result.reason?.contains("no matching key") == true, "reason: ${result.reason}")
        }
}
