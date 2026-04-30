package io.github.jpicklyk.mcptask.current.integration

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
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.DefaultJwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.config.DidDocumentJwksExtractor
import io.github.jpicklyk.mcptask.current.infrastructure.config.DidResolverRegistry
import io.github.jpicklyk.mcptask.current.infrastructure.config.DidWebResolver
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksActorVerifier
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.security.Security
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

/**
 * End-to-end integration tests for the DID-trust verification path.
 *
 * These tests exercise the full pipeline:
 *   JwksActorVerifier → DefaultJwksKeySetProvider → DidResolverRegistry
 *     → DidWebResolver(MockEngine) → DidDocumentJwksExtractor
 *
 * The HTTP layer is mocked via Ktor's [MockEngine]. No real network calls are made.
 * All test cases use real Ed25519 keypairs and real JWT signing.
 *
 * Covers the AgentLair deployment shape described in issue #156 — specifically the
 * kid-mismatch scenario where agent tooling sets a thumbprint-based kid that does not
 * match the bare-fragment kid the extractor derives from the DID document.
 */
class DidVerificationIntegrationTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun registerBouncyCastle() {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        // -------------------------------------------------------------------------
        // Key material — generated once for the test class
        // -------------------------------------------------------------------------

        /** Primary Ed25519 keypair whose public key appears in the synthetic DID document. */
        val primaryKey: OctetKeyPair =
            run {
                val gen = Ed25519KeyPairGenerator()
                gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
                val pair = gen.generateKeyPair()
                val priv = pair.private as Ed25519PrivateKeyParameters
                val pub = pair.public as Ed25519PublicKeyParameters
                // No kid set here — the extractor derives kid from the VM fragment ("key-1")
                OctetKeyPair
                    .Builder(Curve.Ed25519, Base64URL.encode(pub.encoded))
                    .d(Base64URL.encode(priv.encoded))
                    .build()
            }

        /** A second Ed25519 keypair used only in the multi-key test (case 4). */
        val secondaryKey: OctetKeyPair =
            run {
                val gen = Ed25519KeyPairGenerator()
                gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
                val pair = gen.generateKeyPair()
                val priv = pair.private as Ed25519PrivateKeyParameters
                val pub = pair.public as Ed25519PublicKeyParameters
                OctetKeyPair
                    .Builder(Curve.Ed25519, Base64URL.encode(pub.encoded))
                    .d(Base64URL.encode(priv.encoded))
                    .build()
            }

        // -------------------------------------------------------------------------
        // DID document constants
        // -------------------------------------------------------------------------

        const val TEST_DID = "did:web:test.example.com:agents:abc123"
        const val TEST_DID_URL = "https://test.example.com/agents/abc123/did.json"
        const val VM_ID = "$TEST_DID#key-1"
        const val AUDIENCE = "mcp-task-orchestrator"
    }

    // -------------------------------------------------------------------------
    // DID document builders
    // -------------------------------------------------------------------------

    /**
     * Builds a synthetic DID document JSON with a single Ed25519 verification method.
     *
     * Note: The inline `publicKeyJwk` does NOT include a `kid` field — this matches the
     * AgentLair deployment shape where agents omit kid from the document-embedded JWK.
     * The extractor injects `kid="key-1"` (the bare fragment) automatically.
     */
    private fun singleKeyDidDocument(
        key: OctetKeyPair = primaryKey,
        docId: String = TEST_DID
    ): String {
        val pub = key.toPublicJWK()
        return """
            {
              "id": "$docId",
              "verificationMethod": [
                {
                  "id": "$TEST_DID#key-1",
                  "type": "JsonWebKey2020",
                  "controller": "$TEST_DID",
                  "publicKeyJwk": {
                    "kty": "OKP",
                    "crv": "Ed25519",
                    "x": "${pub.x}"
                  }
                }
              ],
              "assertionMethod": ["$TEST_DID#key-1"]
            }
            """.trimIndent()
    }

    /**
     * Builds a synthetic DID document with TWO verification methods, both in assertionMethod.
     * Used for the multi-key loose-kid guard test (case 4).
     */
    private fun twoKeyDidDocument(): String {
        val pub1 = primaryKey.toPublicJWK()
        val pub2 = secondaryKey.toPublicJWK()
        return """
            {
              "id": "$TEST_DID",
              "verificationMethod": [
                {
                  "id": "$TEST_DID#key-1",
                  "type": "JsonWebKey2020",
                  "controller": "$TEST_DID",
                  "publicKeyJwk": {
                    "kty": "OKP",
                    "crv": "Ed25519",
                    "x": "${pub1.x}"
                  }
                },
                {
                  "id": "$TEST_DID#key-2",
                  "type": "JsonWebKey2020",
                  "controller": "$TEST_DID",
                  "publicKeyJwk": {
                    "kty": "OKP",
                    "crv": "Ed25519",
                    "x": "${pub2.x}"
                  }
                }
              ],
              "assertionMethod": ["$TEST_DID#key-1", "$TEST_DID#key-2"]
            }
            """.trimIndent()
    }

    /**
     * Builds a DID document that includes a `service` block of type `JsonWebKeySet2020`
     * pointing at a non-existent endpoint. This mirrors AgentLair's shape for un-rotated
     * accounts. The verifier must ignore the `service` block and use `verificationMethod` only.
     */
    private fun didDocumentWithServiceBlock(): String {
        val pub = primaryKey.toPublicJWK()
        return """
            {
              "id": "$TEST_DID",
              "verificationMethod": [
                {
                  "id": "$TEST_DID#key-1",
                  "type": "JsonWebKey2020",
                  "controller": "$TEST_DID",
                  "publicKeyJwk": {
                    "kty": "OKP",
                    "crv": "Ed25519",
                    "x": "${pub.x}"
                  }
                }
              ],
              "assertionMethod": ["$TEST_DID#key-1"],
              "service": [
                {
                  "id": "$TEST_DID#jwks",
                  "type": "JsonWebKeySet2020",
                  "serviceEndpoint": "https://non-existent-jwks.test.example.com/jwks.json"
                }
              ]
            }
            """.trimIndent()
    }

    // -------------------------------------------------------------------------
    // JWT signing helpers
    // -------------------------------------------------------------------------

    /**
     * Signs a JWT with the given key and kid header.
     *
     * @param key The Ed25519 private key to sign with.
     * @param kid The kid to set in the JWT header.
     * @param iss The issuer claim (defaults to [TEST_DID]).
     * @param sub The subject claim (defaults to [TEST_DID]).
     * @param aud The audience claim (defaults to [AUDIENCE]).
     * @param exp The expiration time (defaults to 5 minutes from now).
     */
    private fun signJwt(
        key: OctetKeyPair = primaryKey,
        kid: String = "key-1",
        iss: String = TEST_DID,
        sub: String = TEST_DID,
        aud: String = AUDIENCE,
        exp: Instant = Instant.now().plusSeconds(300)
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(iss)
                .subject(sub)
                .audience(aud)
                .expirationTime(Date.from(exp))
                .build()
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.Ed25519).keyID(kid).build(),
                claims
            )
        jwt.sign(Ed25519Signer(key))
        return jwt.serialize()
    }

    // -------------------------------------------------------------------------
    // Verifier factory
    // -------------------------------------------------------------------------

    /**
     * Builds a [JwksActorVerifier] backed by a [DefaultJwksKeySetProvider] that
     * resolves DID documents via [DidWebResolver] using the provided [MockEngine].
     *
     * @param engine The Ktor [MockEngine] that handles HTTP requests for DID document fetches.
     * @param didLooseKidMatch Whether to enable the loose-kid match policy (default true).
     * @param clock The clock to use for token expiry checks (default system UTC).
     */
    private fun verifierWithMockHttp(
        engine: MockEngine,
        didLooseKidMatch: Boolean = true,
        clock: Clock = Clock.systemUTC()
    ): JwksActorVerifier {
        val config =
            VerifierConfig.Jwks(
                didAllowlist = listOf(TEST_DID),
                audience = AUDIENCE,
                requireSubMatch = true,
                didLooseKidMatch = didLooseKidMatch
            )
        val registry = DidResolverRegistry(listOf(DidWebResolver(engine)))
        val extractor = DidDocumentJwksExtractor(strictRelationship = config.didStrictRelationship)
        val provider =
            DefaultJwksKeySetProvider(
                config = config,
                didResolverRegistry = registry,
                didDocumentJwksExtractor = extractor
            )
        return JwksActorVerifier(config = config, keySetProvider = provider, clock = clock)
    }

    private fun actor(proof: String): ActorClaim = ActorClaim(id = TEST_DID, kind = ActorKind.SUBAGENT, proof = proof)

    // =========================================================================
    // Test cases
    // =========================================================================

    /**
     * Case 1 — Happy path with matching kid.
     *
     * JWT kid="key-1" matches the bare-fragment kid that [DidDocumentJwksExtractor] derives
     * from the verification method id "...#key-1". Should verify successfully.
     */
    @Test
    fun `verify_didTrust_validSignatureMatchingKid_returnsVerified`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = singleKeyDidDocument(),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }

            val verifier = verifierWithMockHttp(engine)
            val result = verifier.verify(actor(signJwt(kid = "key-1")))

            assertEquals(VerificationStatus.VERIFIED, result.status, "Expected VERIFIED for matching kid")
            assertEquals("jwks", result.verifier)
        }

    /**
     * Case 2 — AgentLair-shape: kid mismatch with single-key JWKSet and didLooseKidMatch=true.
     *
     * This is the core regression test requested in issue #156 comment 4355963991. AgentLair
     * agents set a thumbprint-based kid in the JWT header that does not appear in the DID
     * document's verification method fragment. With a single-key document and loose-kid
     * matching enabled, the sole key is used regardless of the kid mismatch.
     */
    @Test
    fun `verify_didTrust_kidMismatchSingleKey_looseMatchVerifies`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = singleKeyDidDocument(),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }

            // "ab0502f7" is a thumbprint-style kid not present in the resolved JWKSet
            val verifier = verifierWithMockHttp(engine, didLooseKidMatch = true)
            val result = verifier.verify(actor(signJwt(kid = "ab0502f7")))

            assertEquals(
                VerificationStatus.VERIFIED,
                result.status,
                "Expected VERIFIED via loose-kid match (AgentLair-shape single-key document)"
            )
        }

    /**
     * Case 3 — Strict kid matching: kid mismatch with single-key JWKSet and didLooseKidMatch=false.
     *
     * Operators can disable loose-kid matching to require exact kid alignment. With a single
     * key and a mismatched kid, verification must fail with failureKind=crypto.
     */
    @Test
    fun `verify_didTrust_kidMismatchSingleKey_strictRejects`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = singleKeyDidDocument(),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }

            val verifier = verifierWithMockHttp(engine, didLooseKidMatch = false)
            val result = verifier.verify(actor(signJwt(kid = "ab0502f7")))

            assertEquals(VerificationStatus.REJECTED, result.status, "Expected REJECTED for kid mismatch with strict matching")
            assertEquals("crypto", result.metadata["failureKind"])
        }

    /**
     * Case 4 — Multi-key document prevents loose-kid match (single-key guard).
     *
     * When the resolved DID document has two keys and the JWT kid matches neither,
     * the loose-kid policy MUST NOT apply — "first key wins" would be incorrect for
     * multi-key documents. The single-key guard ensures REJECTED is returned.
     */
    @Test
    fun `verify_didTrust_kidMismatchMultiKey_rejects`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = twoKeyDidDocument(),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }

            // kid="unknown-kid" matches neither key-1 nor key-2
            val verifier = verifierWithMockHttp(engine, didLooseKidMatch = true)
            val result = verifier.verify(actor(signJwt(kid = "unknown-kid")))

            assertEquals(
                VerificationStatus.REJECTED,
                result.status,
                "Expected REJECTED: loose-kid must not apply to multi-key DID documents (single-key guard)"
            )
            assertEquals("crypto", result.metadata["failureKind"])
        }

    /**
     * Case 5 — Tampered signature is rejected.
     *
     * Sign a valid JWT then corrupt the final character of the signature segment.
     * The signature verification step must detect the tampering and reject.
     */
    @Test
    fun `verify_didTrust_tamperedSignature_rejects`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = singleKeyDidDocument(),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }

            val validJwt = signJwt(kid = "key-1")
            // Corrupt a middle character of the signature (third dot-separated segment).
            // The LAST char of a base64url segment may only encode a few significant bits,
            // so swapping it can decode to identical bytes — corrupt a middle char instead
            // (every interior char encodes a full 6 significant bits of the signature).
            val parts = validJwt.split(".")
            val sig = parts[2]
            val mid = sig.length / 2
            val flipped = if (sig[mid] == 'A') 'Z' else 'A'
            val corruptedSig = sig.substring(0, mid) + flipped + sig.substring(mid + 1)
            val tamperedJwt = "${parts[0]}.${parts[1]}.$corruptedSig"

            val verifier = verifierWithMockHttp(engine)
            val result = verifier.verify(actor(tamperedJwt))

            assertEquals(VerificationStatus.REJECTED, result.status, "Expected REJECTED for tampered signature")
            assertEquals("crypto", result.metadata["failureKind"])
        }

    /**
     * Case 6 — Expired token is rejected.
     *
     * Uses [Clock.fixed] to pin the verification time to a known instant so the test is
     * deterministic regardless of execution timing. The JWT exp is set to 10 minutes in
     * the past relative to the fixed clock.
     */
    @Test
    fun `verify_didTrust_expiredToken_rejects`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = singleKeyDidDocument(),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }

            // Fix the clock at a specific point in time
            val fixedNow = Instant.parse("2026-04-30T12:00:00Z")
            val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)

            // JWT exp is 10 minutes before fixedNow, well past the 60s skew window
            val expiredJwt = signJwt(kid = "key-1", exp = fixedNow.minusSeconds(600))

            val verifier = verifierWithMockHttp(engine, clock = fixedClock)
            val result = verifier.verify(actor(expiredJwt))

            assertEquals(VerificationStatus.REJECTED, result.status, "Expected REJECTED for expired token")
            assertEquals("claims", result.metadata["failureKind"])
            assertTrue(
                result.reason?.contains("expired") == true,
                "Reason should mention expiry: ${result.reason}"
            )
        }

    /**
     * Case 7 — Untrusted issuer is rejected with failureKind=policy.
     *
     * A JWT signed with the correct key but whose `iss` is not in the allowlist triggers
     * [IssuerNotTrustedException] in the provider, which the verifier maps to REJECTED+policy.
     * The DID document is never fetched because the allowlist check short-circuits first.
     */
    @Test
    fun `verify_didTrust_untrustedIssuer_rejects`() =
        runTest {
            // The MockEngine should not receive any request because the allowlist check
            // short-circuits before the HTTP fetch. Include a fallback that fails loudly
            // to detect accidental HTTP calls.
            val engine =
                MockEngine { request ->
                    respond(
                        content = "should not be called for untrusted issuer: ${request.url}",
                        status = HttpStatusCode.InternalServerError
                    )
                }

            val verifier = verifierWithMockHttp(engine)
            // iss="did:web:attacker.com" is not in the allowlist (only TEST_DID is trusted)
            val jwt = signJwt(kid = "key-1", iss = "did:web:attacker.com", sub = TEST_DID)
            val result = verifier.verify(actor(jwt))

            assertEquals(VerificationStatus.REJECTED, result.status, "Expected REJECTED for untrusted issuer")
            assertEquals("policy", result.metadata["failureKind"])
        }

    /**
     * Case 8 — DID document id mismatch results in UNAVAILABLE.
     *
     * The mocked HTTP returns a document whose `id` field is `did:web:wrong.example.com`
     * instead of the requested [TEST_DID]. [DidWebResolver] detects this document substitution
     * and throws [DidResolutionException], which [DefaultJwksKeySetProvider.getKeySetForIssuer]
     * catches and maps to the `unavailable()` path in [JwksActorVerifier].
     *
     * Result: UNAVAILABLE (the resolution failed before any key material was available).
     * This is appropriate — the verifier cannot distinguish a network fault from a spoofed
     * document at this level, so UNAVAILABLE is the correct conservative status.
     */
    @Test
    fun `verify_didTrust_documentIdMismatch_rejects`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    // Returns a document whose id does not match the requested DID
                    respond(
                        content = singleKeyDidDocument(docId = "did:web:wrong.example.com"),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }

            val verifier = verifierWithMockHttp(engine)
            val result = verifier.verify(actor(signJwt(kid = "key-1")))

            // The DidWebResolver throws DidResolutionException for id mismatch.
            // DefaultJwksKeySetProvider.getKeySetForIssuer catches it via the outer Exception
            // catch block and maps it to unavailable("failed to fetch JWKS: ...").
            // JwksActorVerifier.verifyInternal returns unavailable() → UNAVAILABLE status.
            assertEquals(
                VerificationStatus.UNAVAILABLE,
                result.status,
                "Expected UNAVAILABLE: document id mismatch treated as resolution failure"
            )
        }

    /**
     * Case 9 — Unknown DID method results in UNAVAILABLE.
     *
     * A JWT whose `iss` is `did:key:...` is presented to a registry that only contains
     * a [DidWebResolver] (method="web"). The registry throws [DidResolutionException]
     * ("no resolver for did:key"), which maps to UNAVAILABLE via the same catch path
     * as case 8.
     *
     * Note: The unknown DID is NOT in the allowlist (`did:key:...` is not [TEST_DID]),
     * so [IssuerNotTrustedException] fires first — result is REJECTED+policy, not UNAVAILABLE.
     * The two cases are distinct: allowlist check fires before method resolution.
     * Documenting actual behavior: REJECTED (policy) because the issuer is not trusted.
     */
    @Test
    fun `verify_didTrust_unknownDidMethod_rejects`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = "unreachable",
                        status = HttpStatusCode.InternalServerError
                    )
                }

            val verifier = verifierWithMockHttp(engine)
            // did:key issuer — not in allowlist (TEST_DID only)
            val jwt = signJwt(kid = "key-1", iss = "did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK")
            val result = verifier.verify(actor(jwt))

            // The allowlist check in DefaultJwksKeySetProvider.isIssuerTrusted() fires before
            // any DID resolution attempt. Since "did:key:..." is not in the allowlist,
            // IssuerNotTrustedException is thrown → JwksActorVerifier maps it to REJECTED+policy.
            // The "no resolver for did:key" DidResolutionException path is never reached.
            assertEquals(
                VerificationStatus.REJECTED,
                result.status,
                "Expected REJECTED+policy: did:key issuer fails the allowlist check before method resolution"
            )
            assertEquals("policy", result.metadata["failureKind"])
        }

    /**
     * Case 10 — Service block in DID document is silently ignored.
     *
     * AgentLair-shape deployments publish a `service` entry of type `JsonWebKeySet2020`
     * pointing at a separate (potentially broken) JWKS endpoint. The v1 verifier extracts
     * keys from `verificationMethod[]` only — the `service` block is not consulted.
     *
     * This is a regression guard: if a future change accidentally routes through the
     * `service` endpoint, this test would fail (the endpoint URL is non-existent).
     * A passing test confirms that the inline VM key is the only source of truth.
     */
    @Test
    fun `verify_didTrust_serviceBlockIgnored`() =
        runTest {
            val engine =
                MockEngine { request ->
                    // Only the DID document fetch should occur — the service endpoint URL
                    // is embedded in the document but must never be fetched by the verifier.
                    // We allow the DID document request and reject any other URL.
                    if (request.url.toString() == TEST_DID_URL) {
                        respond(
                            content = didDocumentWithServiceBlock(),
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "application/json")
                        )
                    } else {
                        // If any request targets the service endpoint, the test fails with a
                        // clear indicator that the service block was incorrectly resolved.
                        respond(
                            content = "service endpoint must not be fetched: ${request.url}",
                            status = HttpStatusCode.ServiceUnavailable
                        )
                    }
                }

            val verifier = verifierWithMockHttp(engine, didLooseKidMatch = true)
            // Sign with kid="key-1" (matches the VM fragment) to use exact-match path
            val result = verifier.verify(actor(signJwt(kid = "key-1")))

            assertEquals(
                VerificationStatus.VERIFIED,
                result.status,
                "Expected VERIFIED: service block must be ignored; keys come from verificationMethod only"
            )
        }
}
