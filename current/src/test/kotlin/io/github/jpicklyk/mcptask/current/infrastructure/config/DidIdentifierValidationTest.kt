package io.github.jpicklyk.mcptask.current.infrastructure.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.Ed25519Signer
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.DidDocument
import io.github.jpicklyk.mcptask.current.domain.model.VerificationMethod
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.SecureRandom
import java.security.Security
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

/**
 * S4-5, S10-15 from item a890542c's `test-plan` — DID identifier validation (decision D3, and
 * D4's host-wildcard character class) in [DefaultJwksKeySetProvider.getKeySetForIssuer] and
 * [DidWebResolver.resolve]: malformed `did:web` identifiers must be rejected before any
 * allowlist/pattern check or network fetch, and a "*" in a trust pattern must never match a
 * percent-encoded octet.
 *
 * All scenarios here are EXISTING-SURFACE: [DidSecurityViolationException] and
 * [IssuerNotTrustedException] both predate this fix; the fix only changes when/whether they are
 * thrown for a given input, which a plain revert restores to the pre-fix (vulnerable) behavior.
 */
class DidIdentifierValidationTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildDidDocument(
        did: String,
        kid: String = "key-1"
    ): DidDocument {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = gen.generateKeyPair()
        val pub = pair.public as Ed25519PublicKeyParameters
        val edKey = OctetKeyPair.Builder(Curve.Ed25519, Base64URL.encode(pub.encoded)).keyID(kid).build()
        val publicKeyJwk =
            JsonObject(
                mapOf(
                    "kty" to JsonPrimitive("OKP"),
                    "crv" to JsonPrimitive("Ed25519"),
                    "x" to JsonPrimitive(edKey.x.toString()),
                    "kid" to JsonPrimitive(kid)
                )
            )
        val vm =
            VerificationMethod(
                id = "$did#$kid",
                type = "JsonWebKey2020",
                controller = did,
                publicKeyJwk = publicKeyJwk
            )
        return DidDocument(id = did, verificationMethods = listOf(vm), assertionMethod = listOf("$did#$kid"))
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)

    // -------------------------------------------------------------------------
    // Happy path — glob pattern & percent-encoded allowlist entries trust
    // -------------------------------------------------------------------------

    // S4a: host-segment wildcard spans dot-separated labels. D3/D4 (guard)
    @Test
    fun `S4a pattern with host wildcard trusts a multi-label subdomain`() =
        runTest {
            val did = "did:web:a.b.example.com"
            val doc = buildDidDocument(did)
            val registry = mockk<DidResolverRegistry>()
            coEvery { registry.resolve(did) } returns doc

            val config = VerifierConfig.Jwks(didPattern = "did:web:*.example.com", cacheTtlSeconds = 300)
            val provider = DefaultJwksKeySetProvider(config, clock = fixedClock(), didResolverRegistry = registry)

            val result = provider.getKeySetForIssuer(did)
            assertNotNull(result.keys)
            assertEquals(1, result.keys.keys.size)
        }

    // S4b: host-segment wildcard trusts a percent-encoded-port DID. D3/D4 (guard)
    @Test
    fun `S4b pattern with host wildcard trusts a percent-encoded port DID`() =
        runTest {
            val did = "did:web:x.example.com%3A8443"
            val doc = buildDidDocument(did)
            val registry = mockk<DidResolverRegistry>()
            coEvery { registry.resolve(did) } returns doc

            val config =
                VerifierConfig.Jwks(didPattern = "did:web:*.example.com%3A8443", cacheTtlSeconds = 300)
            val provider = DefaultJwksKeySetProvider(config, clock = fixedClock(), didResolverRegistry = registry)

            val result = provider.getKeySetForIssuer(did)
            assertNotNull(result.keys)
            assertEquals(1, result.keys.keys.size)
        }

    // S5: allowlist entry with a percent-encoded space in a path segment is trusted.
    // D3, DidWebResolverTest:578 (guard)
    @Test
    fun `S5 allowlist entry with percent-encoded space path segment is trusted`() =
        runTest {
            val did = "did:web:example.com:agents:abc%20def"
            val doc = buildDidDocument(did)
            val registry = mockk<DidResolverRegistry>()
            coEvery { registry.resolve(did) } returns doc

            val config = VerifierConfig.Jwks(didAllowlist = listOf(did), cacheTtlSeconds = 300)
            val provider = DefaultJwksKeySetProvider(config, clock = fixedClock(), didResolverRegistry = registry)

            val result = provider.getKeySetForIssuer(did)
            assertNotNull(result.keys)
            assertEquals(1, result.keys.keys.size)
        }

    // -------------------------------------------------------------------------
    // Failure — malformed identifiers rejected pre-fetch
    // -------------------------------------------------------------------------

    // S10: malformed did:web host segments are rejected before any allowlist/pattern check or
    // registry call, even when they would match the configured pattern once malformed.
    // D3, DID Core §3.1 idchar rules.
    @Test
    fun `S10 malformed host segment rejects before pattern match and never calls registry`() =
        runTest {
            val delimiters = listOf("%2F", "%2f", "%3F", "%23", "%40", "/", "?", "#", "@")
            for (d in delimiters) {
                val malformedDid = "did:web:evil.com$d.example.com"
                val registry = mockk<DidResolverRegistry>()
                val config = VerifierConfig.Jwks(didPattern = "did:web:*.example.com", cacheTtlSeconds = 300)
                val provider =
                    DefaultJwksKeySetProvider(config, clock = fixedClock(), didResolverRegistry = registry)

                val ex =
                    assertThrows<DidSecurityViolationException> {
                        provider.getKeySetForIssuer(malformedDid)
                    }
                assertTrue(
                    ex.message!!.startsWith("malformed DID"),
                    "delimiter '$d': expected message to start with 'malformed DID', got: ${ex.message}"
                )
                coVerify(exactly = 0) { registry.resolve(any()) }
            }
        }

    // S11: same malformed-host DID rejected end-to-end through JwksActorVerifier +
    // DefaultJwksKeySetProvider + DidWebResolver(MockEngine) — REJECTED/policy, zero HTTP
    // requests. D3
    @Test
    fun `S11 malformed issuer rejects end-to-end with zero HTTP requests`() =
        runTest {
            val malformedIssuer = "did:web:evil.com%2F.example.com"

            var requestCount = 0
            val engine =
                MockEngine { _ ->
                    requestCount++
                    respond(
                        content = "{}",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }

            val gen = Ed25519KeyPairGenerator()
            gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val pair = gen.generateKeyPair()
            val priv = pair.private as Ed25519PrivateKeyParameters
            val pub = pair.public as Ed25519PublicKeyParameters
            val key =
                OctetKeyPair
                    .Builder(Curve.Ed25519, Base64URL.encode(pub.encoded))
                    .d(Base64URL.encode(priv.encoded))
                    .keyID("s11-key")
                    .build()

            val claimsSet =
                JWTClaimsSet
                    .Builder()
                    .subject(malformedIssuer)
                    .issuer(malformedIssuer)
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build()
            val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.Ed25519).keyID("s11-key").build(), claimsSet)
            jwt.sign(Ed25519Signer(key))
            val jwtStr = jwt.serialize()

            val config = VerifierConfig.Jwks(didPattern = "did:web:*.example.com", cacheTtlSeconds = 300)
            val registry = DidResolverRegistry(listOf(DidWebResolver(engine)))
            val provider = DefaultJwksKeySetProvider(config, didResolverRegistry = registry)
            val verifier = JwksActorVerifier(config = config, keySetProvider = provider)

            val result =
                verifier.verify(ActorClaim(id = malformedIssuer, kind = ActorKind.SUBAGENT, proof = jwtStr))

            assertEquals(VerificationStatus.REJECTED, result.status)
            assertEquals("policy", result.metadata["failureKind"])
            assertTrue(result.reason?.startsWith("malformed DID") == true, "reason: ${result.reason}")
            assertEquals(0, requestCount, "DidWebResolver must not issue any HTTP request for a malformed DID")
        }

    // S12: DidWebResolver.resolve validates the identifier before any HTTP request. D3
    @Test
    fun `S12 DidWebResolver resolve rejects malformed identifier before any HTTP request`() =
        runTest {
            var requestCount = 0
            val engine =
                MockEngine { _ ->
                    requestCount++
                    respond(
                        content = "{}",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
            val resolver = DidWebResolver(engine)

            val ex =
                assertThrows<DidSecurityViolationException> {
                    resolver.resolve("did:web:evil.com%2F.example.com")
                }
            assertTrue(ex.message!!.startsWith("malformed DID"), "message: ${ex.message}")
            assertEquals(0, requestCount)
        }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    // S13: identifiers with disallowed host percent-encoding or an invalid hex escape are
    // rejected as malformed even under the maximally permissive pattern "did:web:*". D3
    @Test
    fun `S13 disallowed host percent-encoding rejects as malformed under a permissive pattern`() =
        runTest {
            val malformed = listOf("did:web:a%2Eb.com", "did:web:a%zz.com")
            for (did in malformed) {
                val registry = mockk<DidResolverRegistry>()
                val config = VerifierConfig.Jwks(didPattern = "did:web:*", cacheTtlSeconds = 300)
                val provider =
                    DefaultJwksKeySetProvider(config, clock = fixedClock(), didResolverRegistry = registry)

                val ex =
                    assertThrows<DidSecurityViolationException> {
                        provider.getKeySetForIssuer(did)
                    }
                assertTrue(ex.message!!.startsWith("malformed DID"), "did='$did': message=${ex.message}")
                coVerify(exactly = 0) { registry.resolve(any()) }
            }
        }

    // S14: dot-segments and double-encoded delimiters in a path segment are rejected as
    // malformed. D3, RFC 3986 §5.2.4
    @Test
    fun `S14 dot-segment and double-encoded path segments reject as malformed`() =
        runTest {
            val malformed =
                listOf(
                    "did:web:example.com:agents:..",
                    "did:web:example.com:agents:%2E%2E",
                    "did:web:example.com:agents:a%252Fb"
                )
            for (did in malformed) {
                val registry = mockk<DidResolverRegistry>()
                val config =
                    VerifierConfig.Jwks(didPattern = "did:web:example.com:agents:*", cacheTtlSeconds = 300)
                val provider =
                    DefaultJwksKeySetProvider(config, clock = fixedClock(), didResolverRegistry = registry)

                val ex =
                    assertThrows<DidSecurityViolationException> {
                        provider.getKeySetForIssuer(did)
                    }
                assertTrue(ex.message!!.startsWith("malformed DID"), "did='$did': message=${ex.message}")
                coVerify(exactly = 0) { registry.resolve(any()) }
            }
        }

    // S15: a validly-formed DID that merely fails to match a wildcard pattern (because "*" never
    // matches a percent-encoded port) throws IssuerNotTrustedException, not a malformed-DID
    // validation error. D4
    @Test
    fun `S15 percent-encoded port defeats a bare wildcard pattern but is not malformed`() =
        runTest {
            val did = "did:web:agents.x%3A1"
            val registry = mockk<DidResolverRegistry>()
            val config = VerifierConfig.Jwks(didPattern = "did:web:agents.*", cacheTtlSeconds = 300)
            val provider = DefaultJwksKeySetProvider(config, clock = fixedClock(), didResolverRegistry = registry)

            assertThrows<IssuerNotTrustedException> {
                provider.getKeySetForIssuer(did)
            }
            coVerify(exactly = 0) { registry.resolve(any()) }
        }
}
