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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.security.Security

/**
 * Independent test-author coverage for item 3dcfcbab, Part 4: a JWT whose claims set is
 * malformed (e.g. a non-numeric `exp`) must classify as REJECTED/failureKind=claims, never as
 * UNAVAILABLE/network (DID-trust path) or REJECTED/internal (static-JWKS path) - both of which
 * were the pre-fix behavior per the diagnosis (task-scope Part 4).
 *
 * Oracle: task-scope Part 4 - "Parse the claims set once in its own try: ParseException ->
 * REJECTED failureKind=claims reason prefix 'malformed JWT claims'. Under DID trust this happens
 * before the fetch (iss needed); in static mode keep it after signature verification so a
 * malformed+bad-signature token still reports crypto (ordering contract of
 * JwksActorVerifierExpRequiredTest S13)."
 *
 * Deliberately independent fixtures (own RSA/Ed25519 keys) from every other JwksActorVerifier
 * test file.
 */
class JwksActorVerifierMalformedClaimsTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private val rsaKey = RSAKeyGenerator(2048).keyID("malformed-claims-test-key").generate()

        private val edKey: OctetKeyPair =
            run {
                val gen = Ed25519KeyPairGenerator()
                gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
                val pair = gen.generateKeyPair()
                val priv = pair.private as Ed25519PrivateKeyParameters
                val pub = pair.public as Ed25519PublicKeyParameters
                OctetKeyPair
                    .Builder(Curve.Ed25519, Base64URL.encode(pub.encoded))
                    .d(Base64URL.encode(priv.encoded))
                    .keyID("malformed-claims-did-key")
                    .build()
            }
    }

    private fun signRsa(claimsSet: JWTClaimsSet): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("malformed-claims-test-key").build(), claimsSet)
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    private fun signDid(claimsSet: JWTClaimsSet): String {
        val jwt =
            SignedJWT(JWSHeader.Builder(JWSAlgorithm.Ed25519).keyID("malformed-claims-did-key").build(), claimsSet)
        jwt.sign(Ed25519Signer(edKey))
        return jwt.serialize()
    }

    /**
     * Flips the first character of the JWS signature segment - same technique as
     * JwksActorVerifierExpRequiredTest.corruptSignature - keeping header/payload byte-for-byte
     * identical while invalidating the signature.
     */
    private fun corruptSignature(jwt: String): String {
        val parts = jwt.split(".")
        require(parts.size == 3) { "expected a compact JWS with 3 segments, got ${parts.size}" }
        val sig = parts[2]
        val corrupted =
            if (sig.isNotEmpty()) {
                val flipped = if (sig[0] == 'A') 'B' else 'A'
                flipped + sig.substring(1)
            } else {
                sig
            }
        return "${parts[0]}.${parts[1]}.$corrupted"
    }

    private fun staticMockProvider(): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns
            JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun staticConfig(): VerifierConfig.Jwks =
        VerifierConfig.Jwks(
            jwksPath = "/unused-in-unit-tests",
            issuer = "https://test-issuer.example",
            audience = "task-orchestrator",
            requireSubMatch = true
        )

    private fun actor(proof: String): ActorClaim = ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT, proof = proof)

    // -------------------------------------------------------------------------
    // S11 - static-JWKS: exp="not-a-number" -> REJECTED claims (not internal)
    // -------------------------------------------------------------------------

    @Test
    fun `S11 static-JWKS a non-numeric exp claim is REJECTED with failureKind claims, not internal`() =
        runTest {
            val malformed =
                JWTClaimsSet
                    .Builder()
                    .subject("agent-1")
                    .issuer("https://test-issuer.example")
                    .audience("task-orchestrator")
                    .claim("exp", "not-a-number")
                    .build()
            val proof = signRsa(malformed)

            val verifier = JwksActorVerifier(config = staticConfig(), keySetProvider = staticMockProvider())
            val result = verifier.verify(actor(proof))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("claims", result.metadata["failureKind"])
            assertNotEquals("internal", result.metadata["failureKind"])
        }

    // -------------------------------------------------------------------------
    // S12 - DID trust: malformed exp -> REJECTED claims, not UNAVAILABLE/network
    // -------------------------------------------------------------------------

    @Test
    fun `S12 DID-trust a non-numeric exp claim is REJECTED with failureKind claims, not unavailable`() =
        runTest {
            val didIssuer = "did:web:malformed-claims-test.example.com"
            val malformed =
                JWTClaimsSet
                    .Builder()
                    .subject(didIssuer)
                    .issuer(didIssuer)
                    .claim("exp", "not-a-number")
                    .build()
            val proof = signDid(malformed)

            val provider = mockk<JwksKeySetProvider>()
            coEvery { provider.getKeySetForIssuer(didIssuer) } returns
                JwksResult(JWKSet(listOf(edKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
            every { provider.getResolvedIssuer() } returns null
            every { provider.close() } just Runs

            val didConfig =
                VerifierConfig.Jwks(
                    didAllowlist = listOf(didIssuer),
                    requireSubMatch = false
                )
            val verifier = JwksActorVerifier(config = didConfig, keySetProvider = provider)
            val result = verifier.verify(actor(proof))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("claims", result.metadata["failureKind"])
            assertNotEquals(VerificationStatus.UNAVAILABLE, result.status)
        }

    // -------------------------------------------------------------------------
    // S13 - DID trust, sub==iss, exp-less -> REJECTED claims "missing exp claim" (regression lock:
    // pre-existing exp-required behavior, previously untested on the DID-trust path)
    // -------------------------------------------------------------------------

    @Test
    fun `S13 DID-trust an exp-less proof with sub equal to iss is REJECTED for missing exp claim`() =
        runTest {
            val didIssuer = "did:web:exp-less-did-test.example.com"
            val expLess =
                JWTClaimsSet
                    .Builder()
                    .subject(didIssuer)
                    .issuer(didIssuer)
                    // no exp
                    .build()
            val proof = signDid(expLess)

            val provider = mockk<JwksKeySetProvider>()
            coEvery { provider.getKeySetForIssuer(didIssuer) } returns
                JwksResult(JWKSet(listOf(edKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
            every { provider.getResolvedIssuer() } returns null
            every { provider.close() } just Runs

            val didConfig =
                VerifierConfig.Jwks(
                    didAllowlist = listOf(didIssuer),
                    requireSubMatch = false
                )
            val verifier = JwksActorVerifier(config = didConfig, keySetProvider = provider)
            val result = verifier.verify(actor(proof))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("claims", result.metadata["failureKind"])
            assertEquals("missing exp claim", result.reason)
        }

    // -------------------------------------------------------------------------
    // S14 - stale-cache provider, exp-less -> REJECTED "missing exp claim", no verifiedFromCache
    // metadata (regression lock: a rejection must not carry stale-cache success metadata)
    // -------------------------------------------------------------------------

    @Test
    fun `S14 an exp-less proof verified against a stale cache is REJECTED with no verifiedFromCache metadata`() =
        runTest {
            val expLess =
                JWTClaimsSet
                    .Builder()
                    .subject("agent-1")
                    .issuer("https://test-issuer.example")
                    .audience("task-orchestrator")
                    // no exp
                    .build()
            val proof = signRsa(expLess)

            val staleProvider = mockk<JwksKeySetProvider>()
            coEvery { staleProvider.getKeySet() } returns
                JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = true, ageSeconds = 450L))
            every { staleProvider.getResolvedIssuer() } returns null
            every { staleProvider.close() } just Runs

            val verifier = JwksActorVerifier(config = staticConfig(), keySetProvider = staleProvider)
            val result = verifier.verify(actor(proof))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("missing exp claim", result.reason)
            assertEquals(null, result.metadata["verifiedFromCache"])
            assertEquals(null, result.metadata["cacheAgeSeconds"])
        }

    // -------------------------------------------------------------------------
    // S20 - static-JWKS: malformed claims AND a corrupted signature -> crypto, not claims
    // (extends the S13-in-JwksActorVerifierExpRequiredTest ordering contract from a MISSING claim
    // to a MALFORMED one: signature verification must still be checked first)
    // -------------------------------------------------------------------------

    @Test
    fun `S20 static-JWKS malformed exp with a corrupted signature is REJECTED for crypto, not claims`() =
        runTest {
            val malformed =
                JWTClaimsSet
                    .Builder()
                    .subject("agent-1")
                    .issuer("https://test-issuer.example")
                    .audience("task-orchestrator")
                    .claim("exp", "not-a-number")
                    .build()
            val exploitProof = corruptSignature(signRsa(malformed))

            val verifier = JwksActorVerifier(config = staticConfig(), keySetProvider = staticMockProvider())
            val result = verifier.verify(actor(exploitProof))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("crypto", result.metadata["failureKind"])
            assertNotEquals("claims", result.metadata["failureKind"])
            assertNotNull(result.reason)
        }
}
