package io.github.jpicklyk.mcptask.current.infrastructure.identity

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksResult
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.KeySetUnavailableException
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.security.Security
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item a0a47509, R18) for the one bearer pipeline's verdict, [TrustDecision], returned by
 * [CredentialResolver.resolve]: scenarios S1 to S6 of the frozen test-plan (all NEW-SURFACE: the verdict type is introduced by
 * this item; red-proof is the narrowest revert, keep [TrustDecision] and revert one arm).
 *
 * Oracles (written from the task-scope and the plan, never from a run): task-scope C, the mapping table of the pipeline:
 * a token-file hit is Trusted with proof ISSUED; an entry past its `expires_at` is Unauthenticated with no JWT fall-through;
 * an empty raw credential, or a JWT-shaped one with no verifier configured, is Unauthenticated; a rejected JWT (bad signature,
 * wrong issuer, expired) is Unauthenticated; a key set that was never fetched or is older than `max_stale_seconds` (the
 * provider throws [KeySetUnavailableException]) is Unavailable with `retryAfterMs` equal to [KEY_SET_RETRY_AFTER_MS] (15 000,
 * the provider's base backoff); a non-root scope anchor, or a fault while looking the anchors up, is Unauthenticated; a JWT
 * verified from the stale cache is Trusted with VERIFIED_STALE, from a fresh one VERIFIED. RFC 6750 section 3 supplies the
 * rest: a token that cannot be checked is not called invalid. Expected values are literals.
 *
 * Negative-assertion conditions: every Unauthenticated or Unavailable result is paired with a control that differs by exactly
 * one input (the right signature, a fresh key set, a live entry, an empty anchor-lookup answer) and resolves Trusted, so a
 * resolver that always refused fails the pair, and a resolver that maps rejected to unavailable (or the reverse) fails the arm
 * it swapped.
 *
 * Domain invariants satisfied by construction: token-file fixtures come from one generator that emits a 64-hex lowercase SHA-256
 * of the raw token, a `scope` mapping with `root_ids: null` and `tags_include: []`, and unique ids, loaded through the real
 * [BearerTokenStore]; JWT fixtures carry the configured issuer and audience, a whole-second `exp` inside the 24 h lifetime cap
 * and are signed with the key the mocked key provider publishes (the rejected-signature fixture reuses the key id with another
 * private key).
 */
class TrustDecisionTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private const val KEY_ID = "trust-decision-key"
        private val rsaKey: RSAKey = RSAKeyGenerator(2048).keyID(KEY_ID).generate()
        private val otherKey: RSAKey = RSAKeyGenerator(2048).keyID(KEY_ID).generate()
        private const val ISSUER = "https://idp.test.example"
        private const val AUDIENCE = "task-orchestrator-api"
    }

    @TempDir
    lateinit var dir: Path

    private var fileCounter = 0

    // ------------------------------------------------------------------ token-file fixtures

    private fun sha256Hex(input: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun entry(
        id: String,
        raw: String,
        expiresAt: String? = null,
    ): String {
        val lines =
            mutableListOf(
                "  - id: $id",
                "    token_sha256: \"${sha256Hex(raw)}\"",
                "    scope:",
                "      root_ids: null",
                "      tags_include: []",
                "    capabilities: [read]",
            )
        if (expiresAt != null) lines += "    expires_at: \"$expiresAt\""
        return lines.joinToString("\n")
    }

    private fun load(vararg entries: String): Map<HashBytes, BearerTokenStore.TokenEntry> {
        val file = dir.resolve("tokens-${fileCounter++}.yaml").toFile()
        file.writeText("version: 1\ntokens:\n" + entries.joinToString("\n") + "\n")
        return BearerTokenStore(file.absolutePath).loadWithEntries()
    }

    // ------------------------------------------------------------------ JWT fixtures

    private fun jwksConfig() =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = ISSUER,
            audience = AUDIENCE,
            algorithms = listOf("RS256"),
            cacheTtlSeconds = 300,
        )

    /** A key provider publishing [rsaKey]; [stale] marks the result as served from the stale cache; [unavailable] makes every fetch throw. */
    private fun provider(
        stale: Boolean = false,
        unavailable: Boolean = false,
    ): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        val cache = CacheState(fromStaleCache = stale, ageSeconds = if (stale) 100L else null)
        val result = JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), cache)
        if (unavailable) {
            coEvery { provider.getKeySet() } throws KeySetUnavailableException("key set never fetched")
        } else {
            coEvery { provider.getKeySet() } returns result
        }
        coEvery { provider.refreshForUnknownKid(any()) } returns null
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun verifier(
        stale: Boolean = false,
        unavailable: Boolean = false,
    ) = JwksApiVerifier(jwksConfig(), provider(stale, unavailable))

    private fun wholeSecondsFromNow(seconds: Long): Instant = Instant.ofEpochSecond(Instant.now().epochSecond + seconds)

    private fun jwt(
        subject: String = "jwt-svc",
        issuer: String = ISSUER,
        expiry: Instant = wholeSecondsFromNow(300),
        signWith: RSAKey = rsaKey,
        claims: Map<String, Any> = emptyMap(),
    ): String {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(AUDIENCE)
                .expirationTime(Date.from(expiry))
        claims.forEach { (k, v) -> builder.claim(k, v) }
        val signed = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), builder.build())
        signed.sign(RSASSASigner(signWith))
        return signed.serialize()
    }

    private fun resolve(
        resolver: CredentialResolver,
        raw: String,
    ): TrustDecision = runBlocking { resolver.resolve(raw) }

    private fun jwtResolver(
        verifier: JwksApiVerifier? = verifier(),
        nonRootAnchors: suspend (Set<UUID>) -> Set<UUID> = { emptySet() },
    ) = CredentialResolver(emptyMap(), verifier, nonRootAnchors)

    // ------------------------------------------------------------------ S1 token-file hit

    @Test
    fun `S1 an unexpired token-file entry is Trusted with proof ISSUED and the entry id`() {
        val entries = load(entry("svc-a", "raw-a"))
        val decision = resolve(CredentialResolver(entries, null), "raw-a")
        val trusted = assertIs<TrustDecision.Trusted>(decision)
        assertEquals("svc-a", trusted.resolved.principal.id)
        assertEquals(ProofStatus.ISSUED, trusted.resolved.principal.proofStatus)
    }

    @Test
    fun `S1 an entry with a future expires_at is Trusted and carries that expiry`() {
        val entries = load(entry("svc-exp", "raw-exp", expiresAt = "2026-10-13T12:00:00Z"))
        val now = Instant.parse("2026-10-13T11:59:59Z")
        val trusted = assertIs<TrustDecision.Trusted>(resolve(CredentialResolver(entries, null) { now }, "raw-exp"))
        assertEquals(Instant.parse("2026-10-13T12:00:00Z"), trusted.resolved.expiresAt)
    }

    @Test
    fun `S1 an unknown token with no verifier is Unauthenticated while the known token beside it is Trusted`() {
        val entries = load(entry("svc-a", "raw-a"))
        val resolver = CredentialResolver(entries, null)
        assertEquals(TrustDecision.Unauthenticated, resolve(resolver, "raw-b"))
        assertIs<TrustDecision.Trusted>(resolve(resolver, "raw-a"), "control: the entry's own token resolves")
    }

    // ------------------------------------------------------------------ S2 expired entry, no fall-through

    @Test
    fun `S2 an entry past expires_at is Unauthenticated`() {
        val entries = load(entry("svc-old", "raw-old", expiresAt = "2026-10-13T12:00:00Z"))
        val after = Instant.parse("2026-10-13T12:00:01Z")
        val before = Instant.parse("2026-10-13T11:59:59Z")
        assertEquals(TrustDecision.Unauthenticated, resolve(CredentialResolver(entries, null) { after }, "raw-old"))
        assertIs<TrustDecision.Trusted>(
            resolve(CredentialResolver(entries, null) { before }, "raw-old"),
            "control: one second before expires_at the same entry resolves",
        )
    }

    @Test
    fun `S2 an expired entry does not fall through to JWT verification of the same string`() {
        val token = jwt(subject = "would-be-verified")
        val entries = load(entry("svc-expired-jwt", token, expiresAt = "2020-01-01T00:00:00Z"))
        val withEntry = CredentialResolver(entries, verifier())
        assertEquals(
            TrustDecision.Unauthenticated,
            resolve(withEntry, token),
            "the string is also a valid JWT; the expired entry that matches it must still refuse it",
        )
        // control: without the expired entry the very same string is a valid JWT and is trusted
        val trusted = assertIs<TrustDecision.Trusted>(resolve(jwtResolver(), token))
        assertEquals("would-be-verified", trusted.resolved.principal.id)
    }

    // ------------------------------------------------------------------ S3 rejected JWT

    @Test
    fun `S3 a JWT signed with the wrong private key is Unauthenticated`() {
        val resolver = jwtResolver()
        assertEquals(TrustDecision.Unauthenticated, resolve(resolver, jwt(signWith = otherKey)))
        assertIs<TrustDecision.Trusted>(resolve(resolver, jwt()), "control: the key the provider publishes verifies")
    }

    @Test
    fun `S3 a wrong-issuer JWT and an expired JWT are Unauthenticated`() {
        val resolver = jwtResolver()
        assertEquals(TrustDecision.Unauthenticated, resolve(resolver, jwt(issuer = "https://evil.example")), "wrong issuer")
        assertEquals(
            TrustDecision.Unauthenticated,
            resolve(resolver, jwt(expiry = Instant.ofEpochSecond(Instant.now().epochSecond - 3600))),
            "expired",
        )
        assertIs<TrustDecision.Trusted>(resolve(resolver, jwt()), "control")
    }

    @Test
    fun `S3 a rejected JWT is never Unavailable even when the key set is stale`() {
        val staleResolver = jwtResolver(verifier(stale = true))
        val decision = resolve(staleResolver, jwt(signWith = otherKey))
        assertEquals(TrustDecision.Unauthenticated, decision, "a bad signature is a rejection whatever the cache state")
        assertIs<TrustDecision.Trusted>(resolve(staleResolver, jwt()), "control: a good signature on the stale set is trusted")
    }

    // ------------------------------------------------------------------ S4 key set unavailable

    @Test
    fun `S4 a provider that throws KeySetUnavailableException yields Unavailable with a positive retryAfterMs`() {
        val token = jwt()
        val decision = resolve(jwtResolver(verifier(unavailable = true)), token)
        val unavailable = assertIs<TrustDecision.Unavailable>(decision)
        assertEquals(KEY_SET_RETRY_AFTER_MS, unavailable.retryAfterMs)
        assertEquals(15_000L, unavailable.retryAfterMs, "task-scope C fixes the hint at the provider's 15 s base backoff")
        assertTrue(unavailable.retryAfterMs > 0)
        assertTrue(unavailable.reason.isNotBlank(), "the reason names the outage for the one WARN")
        assertFalse(token in unavailable.reason, "the reason never carries the credential")
        // control: the same token with a working provider is trusted
        assertIs<TrustDecision.Trusted>(resolve(jwtResolver(), token))
    }

    @Test
    fun `S4 an unavailable key set does not shadow a token-file hit`() {
        val entries = load(entry("svc-file", "raw-file"))
        val resolver = CredentialResolver(entries, verifier(unavailable = true))
        val trusted = assertIs<TrustDecision.Trusted>(resolve(resolver, "raw-file"))
        assertEquals(ProofStatus.ISSUED, trusted.resolved.principal.proofStatus)
    }

    // ------------------------------------------------------------------ S5 fresh vs stale proof

    @Test
    fun `S5 a JWT from a fresh key set is VERIFIED and from the stale cache VERIFIED_STALE`() {
        val exp = wholeSecondsFromNow(300)
        val fresh = assertIs<TrustDecision.Trusted>(resolve(jwtResolver(verifier(stale = false)), jwt(expiry = exp)))
        assertEquals(ProofStatus.VERIFIED, fresh.resolved.principal.proofStatus)
        assertEquals(exp, fresh.resolved.expiresAt)

        val stale = assertIs<TrustDecision.Trusted>(resolve(jwtResolver(verifier(stale = true)), jwt(expiry = exp)))
        assertEquals(ProofStatus.VERIFIED_STALE, stale.resolved.principal.proofStatus)
        assertEquals(exp, stale.resolved.expiresAt)
    }

    // ------------------------------------------------------------------ S6 empty raw, no verifier

    @Test
    fun `S6 an empty or blank raw credential is Unauthenticated whatever is configured`() {
        val entries = load(entry("svc-a", "raw-a"))
        val resolver = CredentialResolver(entries, verifier())
        assertEquals(TrustDecision.Unauthenticated, resolve(resolver, ""))
        assertEquals(TrustDecision.Unauthenticated, resolve(resolver, " "))
        assertIs<TrustDecision.Trusted>(resolve(resolver, "raw-a"), "control: a non-empty token resolves")
    }

    @Test
    fun `S6 a JWT with no verifier configured is Unauthenticated and with one is Trusted`() {
        val token = jwt()
        assertEquals(TrustDecision.Unauthenticated, resolve(jwtResolver(verifier = null), token))
        assertIs<TrustDecision.Trusted>(resolve(jwtResolver(), token), "control")
    }

    // ------------------------------------------------------------------ probes

    @Test
    fun `probe a trailing CRLF on the token is a different credential`() {
        val entries = load(entry("svc-crlf", "raw-crlf"))
        val resolver = CredentialResolver(entries, null)
        assertEquals(TrustDecision.Unauthenticated, resolve(resolver, "raw-crlf\r\n"))
        assertEquals(TrustDecision.Unauthenticated, resolve(resolver, "raw-crlf\n"))
        assertIs<TrustDecision.Trusted>(resolve(resolver, "raw-crlf"), "control: the exact bytes resolve")
    }

    @Test
    fun `probe a scope anchor that is not a root makes the JWT Unauthenticated`() {
        val anchor = UUID.randomUUID()
        val token = jwt(claims = mapOf("to_scope" to mapOf("root_ids" to listOf(anchor.toString()))))
        assertEquals(
            TrustDecision.Unauthenticated,
            resolve(jwtResolver(nonRootAnchors = { ids -> ids }), token),
            "every claimed anchor reported as non-root",
        )
        assertIs<TrustDecision.Trusted>(
            resolve(jwtResolver(nonRootAnchors = { emptySet() }), token),
            "control: with no non-root anchor the same token is trusted",
        )
    }

    @Test
    fun `probe a fault while looking the scope anchors up makes the JWT Unauthenticated`() {
        val anchor = UUID.randomUUID()
        val token = jwt(claims = mapOf("to_scope" to mapOf("root_ids" to listOf(anchor.toString()))))
        val faulty: suspend (Set<UUID>) -> Set<UUID> = { throw IllegalStateException("anchor store down") }
        assertEquals(TrustDecision.Unauthenticated, resolve(jwtResolver(nonRootAnchors = faulty), token))
        assertIs<TrustDecision.Trusted>(resolve(jwtResolver(nonRootAnchors = { emptySet() }), token), "control")
    }

    @Test
    fun `probe recovery - the same token is Unavailable during the outage and Trusted after it`() {
        val token = jwt()
        var down = true
        val provider = mockk<JwksKeySetProvider>()
        val result = JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
        coEvery { provider.getKeySet() } answers { if (down) throw KeySetUnavailableException("outage") else result }
        coEvery { provider.refreshForUnknownKid(any()) } returns null
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        val resolver = CredentialResolver(emptyMap(), JwksApiVerifier(jwksConfig(), provider))

        assertIs<TrustDecision.Unavailable>(resolve(resolver, token))
        down = false
        assertIs<TrustDecision.Trusted>(resolve(resolver, token), "no verdict is cached across the recovery")
    }
}
