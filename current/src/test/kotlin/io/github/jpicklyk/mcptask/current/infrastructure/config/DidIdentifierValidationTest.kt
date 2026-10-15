package io.github.jpicklyk.mcptask.current.infrastructure.config

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.util.Base64URL
import io.github.jpicklyk.mcptask.current.infrastructure.identity.did.DidDocument
import io.github.jpicklyk.mcptask.current.infrastructure.identity.did.DidResolverRegistry
import io.github.jpicklyk.mcptask.current.infrastructure.identity.did.DidSecurityViolationException
import io.github.jpicklyk.mcptask.current.infrastructure.identity.did.DidWebResolver
import io.github.jpicklyk.mcptask.current.infrastructure.identity.did.VerificationMethod
import io.github.jpicklyk.mcptask.current.infrastructure.identity.did.validateDidWebIdentifier
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.DefaultJwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.IssuerNotTrustedException
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.KeySourceConfig
import io.github.jpicklyk.mcptask.current.test.publicEgressGuard
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
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
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

            val config = KeySourceConfig(didPattern = "did:web:*.example.com", cacheTtlSeconds = 300)
            val provider =
                DefaultJwksKeySetProvider(
                    config,
                    java.nio.file.Path
                        .of("."),
                    clock = fixedClock(),
                    didResolverRegistry = registry,
                    egressGuard = publicEgressGuard(config.allowInsecureUrl)
                )

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
                KeySourceConfig(didPattern = "did:web:*.example.com%3A8443", cacheTtlSeconds = 300)
            val provider =
                DefaultJwksKeySetProvider(
                    config,
                    java.nio.file.Path
                        .of("."),
                    clock = fixedClock(),
                    didResolverRegistry = registry,
                    egressGuard = publicEgressGuard(config.allowInsecureUrl)
                )

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

            val config = KeySourceConfig(didAllowlist = listOf(did), cacheTtlSeconds = 300)
            val provider =
                DefaultJwksKeySetProvider(
                    config,
                    java.nio.file.Path
                        .of("."),
                    clock = fixedClock(),
                    didResolverRegistry = registry,
                    egressGuard = publicEgressGuard(config.allowInsecureUrl)
                )

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
                val config = KeySourceConfig(didPattern = "did:web:*.example.com", cacheTtlSeconds = 300)
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        java.nio.file.Path
                            .of("."),
                        clock = fixedClock(),
                        didResolverRegistry = registry,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )

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
            val resolver = DidWebResolver(engine, publicEgressGuard())

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
                val config = KeySourceConfig(didPattern = "did:web:*", cacheTtlSeconds = 300)
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        java.nio.file.Path
                            .of("."),
                        clock = fixedClock(),
                        didResolverRegistry = registry,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )

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
                    KeySourceConfig(didPattern = "did:web:example.com:agents:*", cacheTtlSeconds = 300)
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        java.nio.file.Path
                            .of("."),
                        clock = fixedClock(),
                        didResolverRegistry = registry,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )

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
            val config = KeySourceConfig(didPattern = "did:web:agents.*", cacheTtlSeconds = 300)
            val provider =
                DefaultJwksKeySetProvider(
                    config,
                    java.nio.file.Path
                        .of("."),
                    clock = fixedClock(),
                    didResolverRegistry = registry,
                    egressGuard = publicEgressGuard(config.allowInsecureUrl)
                )

            assertThrows<IssuerNotTrustedException> {
                provider.getKeySetForIssuer(did)
            }
            coVerify(exactly = 0) { registry.resolve(any()) }
        }

    // -------------------------------------------------------------------------
    // D8 (post-review amendment) — wildcard char class, control-char rejection, port range
    // -------------------------------------------------------------------------

    // S18: "*" spans "_" within a path segment (D8a) and an issuer matching via that wildcard
    // passes the trust check — neither malformed nor untrusted. D8a
    @Test
    fun `S18 wildcard matches an underscore path segment and the issuer passes the trust check`() =
        runTest {
            val did = "did:web:example.com:agents:alice_bob"
            val pattern = "did:web:example.com:agents:*"
            val doc = buildDidDocument(did)
            val registry = mockk<DidResolverRegistry>()
            coEvery { registry.resolve(did) } returns doc

            val config = KeySourceConfig(didPattern = pattern, cacheTtlSeconds = 300)
            val provider =
                DefaultJwksKeySetProvider(
                    config,
                    java.nio.file.Path
                        .of("."),
                    clock = fixedClock(),
                    didResolverRegistry = registry,
                    egressGuard = publicEgressGuard(config.allowInsecureUrl)
                )

            assertTrue(provider.matchesGlob(did, pattern))

            val result = provider.getKeySetForIssuer(did)
            assertNotNull(result.keys)
            assertEquals(1, result.keys.keys.size)
        }

    // S19: "*" never matches a percent-encoded octet, even though the identifier itself stays
    // legal. The pattern miss surfaces as IssuerNotTrustedException, not a malformed-DID
    // validation error, because the identifier is well-formed on its own. D8a, D8b (guard)
    @Test
    fun `S19 wildcard does not match a percent-encoded space though the identifier stays legal`() =
        runTest {
            val pattern = "did:web:example.com:agents:*"
            val spaceDid = "did:web:example.com:agents:abc%20def"
            val registry = mockk<DidResolverRegistry>()
            val config = KeySourceConfig(didPattern = pattern, cacheTtlSeconds = 300)
            val provider =
                DefaultJwksKeySetProvider(
                    config,
                    java.nio.file.Path
                        .of("."),
                    clock = fixedClock(),
                    didResolverRegistry = registry,
                    egressGuard = publicEgressGuard(config.allowInsecureUrl)
                )

            assertTrue(!provider.matchesGlob(spaceDid, pattern))
            assertDoesNotThrow { validateDidWebIdentifier("example.com:agents:abc%20def") }

            assertThrows<IssuerNotTrustedException> {
                provider.getKeySetForIssuer(spaceDid)
            }
            coVerify(exactly = 0) { registry.resolve(any()) }
        }

    // S20: a decoded path segment containing a control character or a literal backslash rejects
    // as malformed before any allowlist/pattern check or network fetch, both through
    // getKeySetForIssuer and directly through DidWebResolver.resolve. D8b
    @Test
    fun `S20 control characters and backslash in a path segment reject as malformed with zero HTTP requests`() =
        runTest {
            val encodings = listOf("%5C", "%5c", "%0A", "%00", "%7F", "%1F")
            for (enc in encodings) {
                val malformedDid = "did:web:h.example.com:a${enc}b"

                var providerRequestCount = 0
                val providerEngine =
                    MockEngine { _ ->
                        providerRequestCount++
                        respond(
                            content = "{}",
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "application/json")
                        )
                    }
                val registry = DidResolverRegistry(listOf(DidWebResolver(providerEngine, publicEgressGuard())))
                val config = KeySourceConfig(didPattern = "did:web:h.example.com:*", cacheTtlSeconds = 300)
                val provider =
                    DefaultJwksKeySetProvider(
                        config,
                        java.nio.file.Path
                            .of("."),
                        clock = fixedClock(),
                        didResolverRegistry = registry,
                        egressGuard = publicEgressGuard(config.allowInsecureUrl)
                    )

                val providerEx =
                    assertThrows<DidSecurityViolationException> {
                        provider.getKeySetForIssuer(malformedDid)
                    }
                assertTrue(
                    providerEx.message!!.startsWith("malformed DID"),
                    "enc='$enc': expected message to start with 'malformed DID', got: ${providerEx.message}"
                )
                assertEquals(0, providerRequestCount, "enc='$enc': provider must not fetch for a malformed DID")

                var resolverRequestCount = 0
                val resolverEngine =
                    MockEngine { _ ->
                        resolverRequestCount++
                        respond(
                            content = "{}",
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "application/json")
                        )
                    }
                val resolver = DidWebResolver(resolverEngine, publicEgressGuard())

                val resolverEx =
                    assertThrows<DidSecurityViolationException> {
                        resolver.resolve(malformedDid)
                    }
                assertTrue(
                    resolverEx.message!!.startsWith("malformed DID"),
                    "enc='$enc': expected message to start with 'malformed DID', got: ${resolverEx.message}"
                )
                assertEquals(0, resolverRequestCount, "enc='$enc': resolver must not fetch for a malformed DID")
            }
        }

    // S21: port must be in 1-65535; 0 and 65536 are malformed, 1 and 65535 are legal. D8c
    @Test
    fun `S21 out-of-range percent-encoded ports reject as malformed while 1 and 65535 stay legal`() {
        val malformed = listOf("a.example.com%3A65536", "a.example.com%3A0")
        for (identifier in malformed) {
            val ex =
                assertThrows<DidSecurityViolationException> {
                    validateDidWebIdentifier(identifier)
                }
            assertTrue(
                ex.message!!.startsWith("malformed DID"),
                "identifier='$identifier': expected message to start with 'malformed DID', got: ${ex.message}"
            )
        }

        val legal = listOf("a.example.com%3A65535", "a.example.com%3A1")
        for (identifier in legal) {
            assertDoesNotThrow { validateDidWebIdentifier(identifier) }
        }
    }
}
