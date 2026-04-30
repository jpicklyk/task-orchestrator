package io.github.jpicklyk.mcptask.current.infrastructure.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.Ed25519Signer
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
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
import org.junit.jupiter.api.Nested
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

        // EC P-256 key — uses standard JCA.
        val ecKey = ECKeyGenerator(Curve.P_256).keyID("ec-test-key").generate()

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
        expiry: Instant = Instant.now().plusSeconds(300),
        notBefore: Instant? = null
    ): JWTClaimsSet {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .expirationTime(Date.from(expiry))
        if (notBefore != null) {
            builder.notBeforeTime(Date.from(notBefore))
        }
        return builder.build()
    }

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

    private fun signEc(claims: JWTClaimsSet = buildClaims()): String {
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.ES256).keyID("ec-test-key").build(),
                claims
            )
        jwt.sign(ECDSASigner(ecKey))
        return jwt.serialize()
    }

    /** Actor whose id matches the JWT sub claim. */
    private fun actor(
        id: String = "agent-1",
        proof: String? = null
    ) = ActorClaim(id = id, kind = ActorKind.SUBAGENT, proof = proof)

    /** Fresh (non-stale) cache state. */
    private val freshCacheState = CacheState(fromStaleCache = false, ageSeconds = null)

    /** Mock provider that returns the RSA public key. */
    private fun rsaMockProvider(
        resolvedIssuer: String? = null,
        cacheState: CacheState = freshCacheState
    ): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns JWKSet(listOf(rsaKey.toPublicJWK()))
        every { provider.getResolvedIssuer() } returns resolvedIssuer
        every { provider.getCacheState() } returns cacheState
        every { provider.close() } just Runs
        return provider
    }

    /** Mock provider that returns the EdDSA public key. */
    private fun edMockProvider(
        resolvedIssuer: String? = null,
        cacheState: CacheState = freshCacheState
    ): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns JWKSet(listOf(edKey.toPublicJWK()))
        every { provider.getResolvedIssuer() } returns resolvedIssuer
        every { provider.getCacheState() } returns cacheState
        every { provider.close() } just Runs
        return provider
    }

    /** Mock provider that returns the EC public key. */
    private fun ecMockProvider(
        resolvedIssuer: String? = null,
        cacheState: CacheState = freshCacheState
    ): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns JWKSet(listOf(ecKey.toPublicJWK()))
        every { provider.getResolvedIssuer() } returns resolvedIssuer
        every { provider.getCacheState() } returns cacheState
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

    // 1. null proof → ABSENT
    @Test
    fun `null proof returns ABSENT`() =
        runTest {
            val result = verifier().verify(actor(proof = null))
            assertEquals(VerificationStatus.ABSENT, result.status)
            assertEquals("jwks", result.verifier)
        }

    // 2. blank proof → ABSENT
    @Test
    fun `blank proof returns ABSENT`() =
        runTest {
            val result = verifier().verify(actor(proof = ""))
            assertEquals(VerificationStatus.ABSENT, result.status)
            assertEquals("jwks", result.verifier)
        }

    // 3. invalid JWT string → REJECTED with failureKind=crypto
    @Test
    fun `invalid JWT string returns REJECTED`() =
        runTest {
            val result = verifier().verify(actor(proof = "not-a-jwt"))
            assertEquals(VerificationStatus.REJECTED, result.status)
            assertNotNull(result.reason)
            assertEquals("crypto", result.metadata["failureKind"])
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

    // 6. expired JWT → REJECTED with failureKind=claims
    @Test
    fun `expired JWT returns REJECTED`() =
        runTest {
            val expiredClaims = buildClaims(expiry = Instant.now().minusSeconds(600))
            val result = verifier().verify(actor(proof = signRsa(expiredClaims)))
            assertEquals(VerificationStatus.REJECTED, result.status)
            assertTrue(result.reason?.contains("expired") == true, "reason should mention 'expired': ${result.reason}")
            assertEquals("claims", result.metadata["failureKind"])
        }

    // 7. wrong issuer → REJECTED with failureKind=claims
    @Test
    fun `wrong issuer returns REJECTED`() =
        runTest {
            val claims = buildClaims(issuer = "https://other-issuer.example")
            val result = verifier().verify(actor(proof = signRsa(claims)))
            assertEquals(VerificationStatus.REJECTED, result.status)
            assertTrue(result.reason?.contains("issuer") == true, "reason should mention 'issuer': ${result.reason}")
            assertEquals("claims", result.metadata["failureKind"])
        }

    // 8. wrong audience → REJECTED with failureKind=claims
    @Test
    fun `wrong audience returns REJECTED`() =
        runTest {
            val claims = buildClaims(audience = "wrong-audience")
            val result = verifier().verify(actor(proof = signRsa(claims)))
            assertEquals(VerificationStatus.REJECTED, result.status)
            assertTrue(result.reason?.contains("audience") == true, "reason should mention 'audience': ${result.reason}")
            assertEquals("claims", result.metadata["failureKind"])
        }

    // 9. algorithm not in allowlist → REJECTED with failureKind=policy
    @Test
    fun `algorithm not in allowlist returns REJECTED`() =
        runTest {
            val config = baseConfig(algorithms = listOf("EdDSA"))
            val result = verifier(config = config, provider = rsaMockProvider()).verify(actor(proof = signRsa()))
            assertEquals(VerificationStatus.REJECTED, result.status)
            assertTrue(result.reason?.contains("algorithm not allowed") == true, "reason: ${result.reason}")
            assertEquals("policy", result.metadata["failureKind"])
        }

    // 10. sub mismatch + requireSubMatch=true → REJECTED with failureKind=claims
    @Test
    fun `sub mismatch with strict match returns REJECTED`() =
        runTest {
            val claims = buildClaims(subject = "other-agent")
            // actor.id = "agent-1" but JWT sub = "other-agent"
            val result =
                verifier(config = baseConfig(requireSubMatch = true)).verify(
                    actor(id = "agent-1", proof = signRsa(claims))
                )
            assertEquals(VerificationStatus.REJECTED, result.status)
            assertTrue(result.reason?.contains("sub mismatch") == true, "reason: ${result.reason}")
            assertEquals("claims", result.metadata["failureKind"])
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

    // 12. provider throws → UNAVAILABLE with failureKind=network
    @Test
    fun `provider exception returns UNAVAILABLE`() =
        runTest {
            val brokenProvider = mockk<JwksKeySetProvider>()
            coEvery { brokenProvider.getKeySet() } throws RuntimeException("network unreachable")
            every { brokenProvider.getResolvedIssuer() } returns null
            every { brokenProvider.getCacheState() } returns freshCacheState
            every { brokenProvider.close() } just Runs

            val result = verifier(provider = brokenProvider).verify(actor(proof = signRsa()))
            assertEquals(VerificationStatus.UNAVAILABLE, result.status)
            assertNotNull(result.reason)
            assertEquals("network", result.metadata["failureKind"])
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
            assertEquals(VerificationStatus.REJECTED, result.status)
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

    // 16. no matching kid → REJECTED with failureKind=crypto
    @Test
    fun `no matching kid returns REJECTED`() =
        runTest {
            // Provider only has the EdDSA key; JWT is signed with RSA (kid="rsa-test-key")
            val result = verifier(provider = edMockProvider()).verify(actor(proof = signRsa()))
            assertEquals(VerificationStatus.REJECTED, result.status)
            assertTrue(result.reason?.contains("no matching key") == true, "reason: ${result.reason}")
            assertEquals("crypto", result.metadata["failureKind"])
        }

    // =========================================================================
    // EC key type verification
    // =========================================================================

    // 17. valid EC (P-256 / ES256) JWT → VERIFIED
    @Test
    fun `valid EC P-256 JWT returns VERIFIED`() =
        runTest {
            val result = verifier(provider = ecMockProvider()).verify(actor(proof = signEc()))
            assertEquals(VerificationStatus.VERIFIED, result.status)
            assertEquals("jwks", result.verifier)
        }

    // 18. EC JWT with algorithm allowlist including ES256 → VERIFIED
    @Test
    fun `EC JWT passes algorithm allowlist containing ES256`() =
        runTest {
            val config = baseConfig(algorithms = listOf("ES256", "RS256"))
            val result = verifier(config = config, provider = ecMockProvider()).verify(actor(proof = signEc()))
            assertEquals(VerificationStatus.VERIFIED, result.status)
        }

    // 19. EC JWT rejected when algorithm allowlist excludes ES256
    @Test
    fun `EC JWT rejected when algorithm allowlist excludes ES256`() =
        runTest {
            val config = baseConfig(algorithms = listOf("RS256"))
            val result = verifier(config = config, provider = ecMockProvider()).verify(actor(proof = signEc()))
            assertEquals(VerificationStatus.REJECTED, result.status)
            assertTrue(result.reason?.contains("algorithm not allowed") == true, "reason: ${result.reason}")
            assertEquals("policy", result.metadata["failureKind"])
        }

    // 20. EC JWT with wrong issuer → REJECTED (claim validation works with EC keys)
    @Test
    fun `EC JWT with wrong issuer returns REJECTED`() =
        runTest {
            val claims = buildClaims(issuer = "https://wrong-issuer.example")
            val result = verifier(provider = ecMockProvider()).verify(actor(proof = signEc(claims)))
            assertEquals(VerificationStatus.REJECTED, result.status)
            assertTrue(result.reason?.contains("issuer") == true, "reason: ${result.reason}")
            assertEquals("claims", result.metadata["failureKind"])
        }

    // =========================================================================
    // nbf (not-before) claim validation
    // =========================================================================

    // 21. JWT with nbf far in the future → REJECTED with failureKind=claims
    @Test
    fun `JWT with future nbf returns REJECTED`() =
        runTest {
            val claims = buildClaims(notBefore = Instant.now().plusSeconds(600))
            val result = verifier().verify(actor(proof = signRsa(claims)))
            assertEquals(VerificationStatus.REJECTED, result.status)
            assertTrue(result.reason?.contains("not yet valid") == true, "reason: ${result.reason}")
            assertEquals("claims", result.metadata["failureKind"])
        }

    // 22. JWT with nbf in the past → VERIFIED
    @Test
    fun `JWT with past nbf returns VERIFIED`() =
        runTest {
            val claims = buildClaims(notBefore = Instant.now().minusSeconds(60))
            val result = verifier().verify(actor(proof = signRsa(claims)))
            assertEquals(VerificationStatus.VERIFIED, result.status)
        }

    // 23. JWT without nbf → VERIFIED (nbf is optional)
    @Test
    fun `JWT without nbf returns VERIFIED`() =
        runTest {
            // Default buildClaims() has no notBefore
            val result = verifier().verify(actor(proof = signRsa()))
            assertEquals(VerificationStatus.VERIFIED, result.status)
        }

    // =========================================================================
    // Stale cache metadata
    // =========================================================================

    // 24. Successful verification using stale cache → VERIFIED with verifiedFromCache metadata
    @Test
    fun `verified with stale cache includes verifiedFromCache metadata`() =
        runTest {
            val staleState = CacheState(fromStaleCache = true, ageSeconds = 450L)
            val provider = rsaMockProvider(cacheState = staleState)
            val result = verifier(provider = provider).verify(actor(proof = signRsa()))
            assertEquals(VerificationStatus.VERIFIED, result.status)
            assertEquals("true", result.metadata["verifiedFromCache"])
            assertEquals("450", result.metadata["cacheAgeSeconds"])
        }

    // 25. Successful verification with fresh cache → no cache metadata
    @Test
    fun `verified with fresh cache has no stale metadata`() =
        runTest {
            val provider = rsaMockProvider(cacheState = freshCacheState)
            val result = verifier(provider = provider).verify(actor(proof = signRsa()))
            assertEquals(VerificationStatus.VERIFIED, result.status)
            assertTrue(result.metadata.isEmpty(), "Expected empty metadata for fresh cache: ${result.metadata}")
        }

    // 26. Unexpected exception in success path → REJECTED with failureKind=internal
    @Test
    fun `verify returns REJECTED with failureKind=internal on unexpected exception`() =
        runTest {
            // getCacheState() is called after all inner try/catch blocks in the success path.
            // Throwing here bypasses every inner catch and reaches the outer catch in verify(),
            // which maps any unexpected exception to REJECTED + failureKind="internal".
            val throwingProvider = mockk<JwksKeySetProvider>()
            coEvery { throwingProvider.getKeySet() } returns JWKSet(listOf(rsaKey.toPublicJWK()))
            every { throwingProvider.getResolvedIssuer() } returns null
            every { throwingProvider.getCacheState() } throws RuntimeException("simulated internal failure")
            every { throwingProvider.close() } just Runs

            val result = verifier(provider = throwingProvider).verify(actor(proof = signRsa()))
            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("internal", result.metadata["failureKind"])
            assertNotNull(result.reason)
        }

    // =========================================================================
    // DID-trust path tests
    // =========================================================================

    @Nested
    inner class DidTrustPath {

        private val didIssuer = "did:web:test.example.com"

        // Build a separate Ed25519 keypair for DID-trust tests so they don't share
        // the companion-object's "ed-test-key" kid.
        private val didEdKey: OctetKeyPair = run {
            val gen = Ed25519KeyPairGenerator()
            gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val pair = gen.generateKeyPair()
            val priv = pair.private as Ed25519PrivateKeyParameters
            val pub = pair.public as Ed25519PublicKeyParameters
            OctetKeyPair
                .Builder(Curve.Ed25519, Base64URL.encode(pub.encoded))
                .d(Base64URL.encode(priv.encoded))
                .keyID("did-ed-key")
                .build()
        }

        private fun signDidEd(
            claims: JWTClaimsSet,
            kid: String = "did-ed-key"
        ): String {
            val jwt = SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.Ed25519).keyID(kid).build(),
                claims
            )
            jwt.sign(Ed25519Signer(didEdKey))
            return jwt.serialize()
        }

        private fun didConfig(
            didAllowlist: List<String> = listOf(didIssuer),
            didPattern: String? = null,
            didLooseKidMatch: Boolean = true,
            issuer: String? = null,
            audience: String? = null,
            requireSubMatch: Boolean = false
        ) = VerifierConfig.Jwks(
            didAllowlist = didAllowlist,
            didPattern = didPattern,
            didLooseKidMatch = didLooseKidMatch,
            issuer = issuer,
            audience = audience,
            requireSubMatch = requireSubMatch
        )

        /** Mock provider for DID trust mode — getKeySetForIssuer returns the supplied JWKSet. */
        private fun didMockProvider(
            issuerToJwks: Map<String, JWKSet>,
            cacheState: CacheState = CacheState(fromStaleCache = false, ageSeconds = null)
        ): JwksKeySetProvider {
            val provider = mockk<JwksKeySetProvider>()
            issuerToJwks.forEach { (iss, jwks) ->
                coEvery { provider.getKeySetForIssuer(iss) } returns jwks
            }
            every { provider.getResolvedIssuer() } returns null
            every { provider.getCacheState() } returns cacheState
            every { provider.close() } just Runs
            return provider
        }

        // DID-V1: DID-trust happy path — kid matches, VERIFIED
        @Test
        fun `DID-trust happy path with matching kid returns VERIFIED`() =
            runTest {
                val claims = JWTClaimsSet.Builder()
                    .subject("agent-1")
                    .issuer(didIssuer)
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build()
                val jwt = signDidEd(claims)
                val jwks = JWKSet(listOf(didEdKey.toPublicJWK()))
                val provider = didMockProvider(mapOf(didIssuer to jwks))

                val result = JwksActorVerifier(
                    config = didConfig(requireSubMatch = false),
                    keySetProvider = provider
                ).verify(actor(id = "agent-1", proof = jwt))

                assertEquals(VerificationStatus.VERIFIED, result.status)
                assertEquals("jwks", result.verifier)
            }

        // DID-V2: missing iss claim under DID trust → REJECTED failureKind=claims
        @Test
        fun `missing iss claim under DID trust returns REJECTED with failureKind=claims`() =
            runTest {
                val claims = JWTClaimsSet.Builder()
                    .subject("agent-1")
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    // no issuer
                    .build()
                val jwt = signDidEd(claims)
                val provider = mockk<JwksKeySetProvider>()
                every { provider.close() } just Runs

                val result = JwksActorVerifier(
                    config = didConfig(),
                    keySetProvider = provider
                ).verify(actor(id = "agent-1", proof = jwt))

                assertEquals(VerificationStatus.REJECTED, result.status)
                assertEquals("claims", result.metadata["failureKind"])
                assertTrue(result.reason?.contains("iss") == true, "reason: ${result.reason}")
            }

        // DID-V3: IssuerNotTrustedException from provider → REJECTED failureKind=policy
        @Test
        fun `IssuerNotTrustedException returns REJECTED with failureKind=policy`() =
            runTest {
                val untrustedIss = "did:web:untrusted.example.com"
                val claims = JWTClaimsSet.Builder()
                    .subject("agent-1")
                    .issuer(untrustedIss)
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build()
                val jwt = signDidEd(claims)

                val provider = mockk<JwksKeySetProvider>()
                coEvery { provider.getKeySetForIssuer(untrustedIss) } throws IssuerNotTrustedException(untrustedIss)
                every { provider.getResolvedIssuer() } returns null
                every { provider.getCacheState() } returns CacheState(fromStaleCache = false, ageSeconds = null)
                every { provider.close() } just Runs

                val result = JwksActorVerifier(
                    config = didConfig(),
                    keySetProvider = provider
                ).verify(actor(id = "agent-1", proof = jwt))

                assertEquals(VerificationStatus.REJECTED, result.status)
                assertEquals("policy", result.metadata["failureKind"])
            }

        // DID-V4: loose-kid match — single key, kid mismatch, didLooseKidMatch=true → VERIFIED
        @Test
        fun `loose-kid match with single key and mismatched kid returns VERIFIED`() =
            runTest {
                // JWT kid is "different-kid" but the JWKSet has "did-ed-key"
                val claims = JWTClaimsSet.Builder()
                    .subject("agent-1")
                    .issuer(didIssuer)
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build()
                val jwt = signDidEd(claims, kid = "different-kid")
                // JWKSet has only one key with kid="did-ed-key" — loose match will use it
                val jwks = JWKSet(listOf(didEdKey.toPublicJWK()))
                val provider = didMockProvider(mapOf(didIssuer to jwks))

                val result = JwksActorVerifier(
                    config = didConfig(didLooseKidMatch = true, requireSubMatch = false),
                    keySetProvider = provider
                ).verify(actor(id = "agent-1", proof = jwt))

                assertEquals(VerificationStatus.VERIFIED, result.status)
            }

        // DID-V5: loose-kid disabled — single key, kid mismatch, didLooseKidMatch=false → REJECTED
        @Test
        fun `loose-kid disabled with single key and mismatched kid returns REJECTED`() =
            runTest {
                val claims = JWTClaimsSet.Builder()
                    .subject("agent-1")
                    .issuer(didIssuer)
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build()
                val jwt = signDidEd(claims, kid = "different-kid")
                val jwks = JWKSet(listOf(didEdKey.toPublicJWK()))
                val provider = didMockProvider(mapOf(didIssuer to jwks))

                val result = JwksActorVerifier(
                    config = didConfig(didLooseKidMatch = false, requireSubMatch = false),
                    keySetProvider = provider
                ).verify(actor(id = "agent-1", proof = jwt))

                assertEquals(VerificationStatus.REJECTED, result.status)
                assertEquals("crypto", result.metadata["failureKind"])
                assertTrue(result.reason?.contains("no matching key") == true, "reason: ${result.reason}")
            }

        // DID-V6: multi-key kid mismatch — even with didLooseKidMatch=true → REJECTED (single-key guard)
        @Test
        fun `loose-kid does not apply to multi-key DID JWKS`() =
            runTest {
                val claims = JWTClaimsSet.Builder()
                    .subject("agent-1")
                    .issuer(didIssuer)
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build()
                // JWT kid is "different-kid", neither key has that id
                val jwt = signDidEd(claims, kid = "different-kid")

                // Build a second Ed key
                val gen = Ed25519KeyPairGenerator()
                gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
                val pair2 = gen.generateKeyPair()
                val pub2 = pair2.public as Ed25519PublicKeyParameters
                val secondKey = OctetKeyPair
                    .Builder(Curve.Ed25519, Base64URL.encode(pub2.encoded))
                    .keyID("second-did-key")
                    .build()

                // Two keys — single-key guard prevents loose match
                val jwks = JWKSet(listOf(didEdKey.toPublicJWK(), secondKey.toPublicJWK()))
                val provider = didMockProvider(mapOf(didIssuer to jwks))

                val result = JwksActorVerifier(
                    config = didConfig(didLooseKidMatch = true, requireSubMatch = false),
                    keySetProvider = provider
                ).verify(actor(id = "agent-1", proof = jwt))

                assertEquals(VerificationStatus.REJECTED, result.status)
                assertEquals("crypto", result.metadata["failureKind"])
            }

        // DID-V7: static-JWKS path (isDidTrust=false) preserves strict kid matching
        @Test
        fun `static-JWKS path keeps strict kid matching even when single key in set`() =
            runTest {
                // isDidTrust=false: no didAllowlist, no didPattern
                // JWT kid does not match the Ed key in the JWKSet
                val claims = buildClaims(issuer = "https://static-issuer.example")
                val jwt = SignedJWT(
                    JWSHeader.Builder(JWSAlgorithm.Ed25519).keyID("wrong-kid").build(),
                    claims
                )
                jwt.sign(Ed25519Signer(edKey))
                val jwtStr = jwt.serialize()

                val provider = edMockProvider() // returns single key with kid="ed-test-key"
                val config = VerifierConfig.Jwks(
                    jwksPath = "/unused",
                    issuer = "https://static-issuer.example",
                    audience = "task-orchestrator",
                    requireSubMatch = false,
                    // DID fields all at default (empty/null) → isDidTrust=false
                    didLooseKidMatch = true // even if set true, should not apply in static path
                )

                val result = JwksActorVerifier(config = config, keySetProvider = provider)
                    .verify(actor(id = "agent-1", proof = jwtStr))

                // Must be REJECTED — loose-kid only applies under isDidTrust=true
                assertEquals(VerificationStatus.REJECTED, result.status)
                assertEquals("crypto", result.metadata["failureKind"])
                assertTrue(result.reason?.contains("no matching key") == true, "reason: ${result.reason}")
            }
    }
}
