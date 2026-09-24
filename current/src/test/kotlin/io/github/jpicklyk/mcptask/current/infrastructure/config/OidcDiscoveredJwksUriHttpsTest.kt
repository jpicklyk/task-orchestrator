package io.github.jpicklyk.mcptask.current.infrastructure.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Date

/**
 * TEST-AUTHOR INDEPENDENT SUITE for item `59989657` (test-plan S6, S13, S14).
 *
 * Targets the NEW `validateDiscoveredJwksUri` check inside `DefaultJwksKeySetProvider`'s OIDC
 * discovery path (D-c, change #6): a `jwks_uri` returned by a discovery document is subject to
 * the SAME https-or-loopback rule as a directly-configured `jwks_uri`, keyed on
 * `VerifierConfig.Jwks.allowInsecureUrl`. This is distinct from `ActorAuthKeySourceHttpsTest`,
 * which covers a directly-configured `jwks_uri`/`oidc_discovery` URL via the YAML service --
 * here the rejected value is DISCOVERED at runtime from a mocked discovery document, never
 * written to YAML at all.
 *
 * Oracles (supplied verbatim in the dispatch prompt's declarations block):
 * `DefaultJwksKeySetProvider`'s `runOidcDiscovery`/`validateDiscoveredJwksUri`/`fetchKeySet` KDoc
 * oracles (D-c) and `JwksActorVerifier.verify`'s KDoc oracle (any fetch exception, including the
 * one `validateDiscoveredJwksUri` raises, is caught by the generic `catch (e: Exception)` and
 * reported as `UNAVAILABLE`/`failureKind=network`); `current/docs/fleet-deployment.md` :522.
 *
 * MockEngine pattern reused from the sibling `JwksKeySetProviderTest.kt` (same `httpClientEngineForTest`
 * constructor seam, same `validJwksJson()`/`respond(...)` shape); the JWT-signing helpers mirror
 * `JwksActorVerifierTest.kt`'s `buildClaims`/`signRsa`.
 */
class OidcDiscoveredJwksUriHttpsTest {
    companion object {
        private val rsaKey = RSAKeyGenerator(2048).keyID("oidc-https-test-key").generate()
    }

    private fun validJwksJson(): String {
        val key = RSAKeyGenerator(2048).keyID("oidc-https-served-key").generate()
        return JWKSet(listOf(key.toPublicJWK())).toString()
    }

    private fun buildClaims(
        subject: String = "oidc-https-agent",
        issuer: String = "https://idp.test.example",
        expiry: Instant = Instant.now().plusSeconds(300),
    ): JWTClaimsSet =
        JWTClaimsSet
            .Builder()
            .subject(subject)
            .issuer(issuer)
            .expirationTime(Date.from(expiry))
            .build()

    private fun signRsa(claims: JWTClaimsSet = buildClaims()): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("oidc-https-test-key").build(), claims)
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    /**
     * A [MockEngine] that answers the OIDC discovery GET (returning a document naming
     * [discoveredJwksUri] as `jwks_uri`) and, separately, the discovered `jwks_uri` GET itself
     * (returning a fresh valid JWKS). Returns the engine plus two hit-count accessors so a test
     * can assert exactly which URLs were actually fetched.
     */
    private fun discoveryMockEngine(
        discoveryUrl: String,
        discoveredJwksUri: String,
        issuer: String = "https://idp.test.example",
    ): Triple<MockEngine, () -> Int, () -> Int> {
        var discoveryHits = 0
        var jwksHits = 0
        val engine =
            MockEngine { request ->
                when (request.url.toString()) {
                    discoveryUrl -> {
                        discoveryHits++
                        respond(
                            content = """{"issuer":"$issuer","jwks_uri":"$discoveredJwksUri"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "application/json"),
                        )
                    }
                    discoveredJwksUri -> {
                        jwksHits++
                        respond(
                            content = validJwksJson(),
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "application/jwk-set+json"),
                        )
                    }
                    else ->
                        respond(
                            content = "not found",
                            status = HttpStatusCode.NotFound,
                            headers = headersOf("Content-Type", "text/plain"),
                        )
                }
            }
        return Triple(engine, { discoveryHits }, { jwksHits })
    }

    // -------------------------------------------------------------------------------------------
    // S6 -- https discovery doc naming an https jwks_uri: positive control, unaffected by D-c.
    // [D-c] EXISTING-SURFACE
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S6 - https discovery doc pointing to an https jwks_uri returns keys`() =
        runTest {
            val discoveryUrl = "https://idp.test.example/.well-known/openid-configuration"
            val discoveredUri = "https://idp.test.example/jwks.json"
            val (engine, discoveryHits, jwksHits) = discoveryMockEngine(discoveryUrl, discoveredUri)
            val config = VerifierConfig.Jwks(oidcDiscovery = discoveryUrl)
            val provider = DefaultJwksKeySetProvider(config, httpClientEngineForTest = engine)
            try {
                val result = provider.getKeySet()
                assertEquals(1, result.keys.keys.size, "S6: the served JWKS key must come through")
                assertEquals(1, discoveryHits(), "S6: the discovery document must be fetched exactly once")
                assertEquals(1, jwksHits(), "S6: the discovered https jwks_uri must be fetched")
                assertEquals("https://idp.test.example", provider.getResolvedIssuer())
            } finally {
                provider.close()
            }
        }

    // -------------------------------------------------------------------------------------------
    // S13 -- discovery doc naming a rejected http jwks_uri (non-loopback, or loopback with no
    // opt-in): getKeySet throws, the rejected URI is never fetched, no issuer is resolved, and
    // JwksActorVerifier.verify surfaces UNAVAILABLE. [D-c; fleet:522] EXISTING-SURFACE
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S13 - discovered non-loopback http jwks_uri is rejected, getKeySet throws and it is never fetched`() =
        runTest {
            val discoveryUrl = "https://idp.test.example/.well-known/openid-configuration"
            val discoveredUri = "http://idp.example/j"
            val (engine, discoveryHits, jwksHits) = discoveryMockEngine(discoveryUrl, discoveredUri)
            val config = VerifierConfig.Jwks(oidcDiscovery = discoveryUrl)
            val provider = DefaultJwksKeySetProvider(config, httpClientEngineForTest = engine)
            try {
                val ex = assertThrows<IllegalStateException> { provider.getKeySet() }
                assertTrue(
                    ex.message?.contains("No JWKS source produced a key set") == true,
                    "S13: expected the documented no-source message. Got: ${ex.message}",
                )
                assertEquals(1, discoveryHits(), "S13: the discovery document itself is still fetched once")
                assertEquals(0, jwksHits(), "S13: the rejected discovered jwks_uri must never be fetched")
                assertNull(provider.getResolvedIssuer(), "S13: a rejected discovery must leave no resolved issuer")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `S13 - discovered loopback http jwks_uri without opt-in is still rejected`() =
        runTest {
            val discoveryUrl = "https://idp.test.example/.well-known/openid-configuration"
            val discoveredUri = "http://127.0.0.1:9/j"
            val (engine, discoveryHits, jwksHits) = discoveryMockEngine(discoveryUrl, discoveredUri)
            // allowInsecureUrl defaults to false -- loopback alone is not sufficient.
            val config = VerifierConfig.Jwks(oidcDiscovery = discoveryUrl)
            val provider = DefaultJwksKeySetProvider(config, httpClientEngineForTest = engine)
            try {
                assertThrows<IllegalStateException> { provider.getKeySet() }
                assertEquals(1, discoveryHits())
                assertEquals(0, jwksHits(), "S13: loopback host alone must not bypass the opt-in requirement")
                assertNull(provider.getResolvedIssuer())
            } finally {
                provider.close()
            }
        }

    @Test
    fun `S13 - JwksActorVerifier reports UNAVAILABLE when the discovered jwks_uri is rejected`() =
        runTest {
            val discoveryUrl = "https://idp.test.example/.well-known/openid-configuration"
            val discoveredUri = "http://idp.example/j"
            val (engine, _, _) = discoveryMockEngine(discoveryUrl, discoveredUri)
            val config = VerifierConfig.Jwks(oidcDiscovery = discoveryUrl, algorithms = listOf("RS256"))
            val provider = DefaultJwksKeySetProvider(config, httpClientEngineForTest = engine)
            val verifier = JwksActorVerifier(config = config, keySetProvider = provider)
            try {
                val result =
                    verifier.verify(ActorClaim(id = "s13-agent", kind = ActorKind.SUBAGENT, proof = signRsa()))
                assertEquals(
                    VerificationStatus.UNAVAILABLE,
                    result.status,
                    "S13: the rejected discovery must surface as UNAVAILABLE via the generic catch-all, " +
                        "same as any other JWKS-fetch failure",
                )
                assertEquals("network", result.metadata["failureKind"])
            } finally {
                provider.close()
                verifier.close()
            }
        }

    // -------------------------------------------------------------------------------------------
    // S14 -- same loopback discovered jwks_uri as S13, but with allowInsecureUrl=true: fetched.
    // [D-c] NEW-SURFACE
    //
    // Narrowest-revert recipe (for the orchestrator's red-proof; not run by the test author):
    // keep the `allowInsecureUrl` field on VerifierConfig.Jwks, but revert ONLY
    // validateDiscoveredJwksUri's use of it so the discovered-URI check always treats it as
    // false. Under that narrow revert, this loopback URL flips from "fetched" back to "rejected"
    // (IllegalStateException on getKeySet(), zero jwksHits) -- both assertions below turn red.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S14 - discovered loopback http jwks_uri IS fetched when allowInsecureUrl is true`() =
        runTest {
            val discoveryUrl = "https://idp.test.example/.well-known/openid-configuration"
            val discoveredUri = "http://127.0.0.1:9/j"
            val (engine, discoveryHits, jwksHits) = discoveryMockEngine(discoveryUrl, discoveredUri)
            val config = VerifierConfig.Jwks(oidcDiscovery = discoveryUrl, allowInsecureUrl = true)
            val provider = DefaultJwksKeySetProvider(config, httpClientEngineForTest = engine)
            try {
                val result = provider.getKeySet()
                assertEquals(1, result.keys.keys.size, "S14: keys must be fetched once the loopback opt-in is honored")
                assertEquals(1, discoveryHits())
                assertEquals(1, jwksHits(), "S14: the discovered loopback jwks_uri must actually be fetched")
                assertEquals("https://idp.test.example", provider.getResolvedIssuer())
            } finally {
                provider.close()
            }
        }
}
