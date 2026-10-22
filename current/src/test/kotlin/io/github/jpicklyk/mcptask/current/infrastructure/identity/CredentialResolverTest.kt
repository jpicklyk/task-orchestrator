package io.github.jpicklyk.mcptask.current.infrastructure.identity

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksResult
import io.github.jpicklyk.mcptask.current.test.resolveOrNull
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.security.Security
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item b9527ef9) for [CredentialResolver], the one bearer pipeline, with the token-file
 * loader feeding it and a real [JwksApiVerifier] behind it: scenarios S1 to S7 and S14 of the frozen test-plan.
 *
 * Oracles (never read from a run): the R10 plan paragraph and task-scope items 4 and 5 (kind default SERVICE for both
 * credential sources; AGENT for a DID subject when no claim is given; proof ISSUED for a token-file entry, VERIFIED or
 * VERIFIED_STALE for a JWT; `human` accepted only from a credential), decision D2 (`kind` and `to_kind` are trim and
 * case-insensitive; an unknown value fails startup for the file and rejects the JWT for the claim), D3 (DID default is
 * `sub == iss && sub.startsWith("did:")`), D4 (the credential `profile` rides on [Resolved], not on the Principal; no JWT
 * profile claim), `api-rest.md` section 1 (the token is looked up by SHA-256 of its exact bytes; `to_capabilities` absent or
 * empty is `[read]`, an unknown capability is dropped; `sub` is the principal id; `exp` is required) and the W3C DID Core
 * grammar (the scheme prefix is the lowercase `did:`). Expected values are literals.
 *
 * Negative-assertion conditions: each `null` result is paired with a control that differs by exactly one input (a valid kind
 * claim, the right token, a fresh clock) and does resolve, so a resolver that always returned null fails the pair.
 *
 * Domain invariants satisfied by construction: token-file fixtures are built by one generator that emits a 64-hex lowercase
 * SHA-256 of the raw token, a `scope` mapping with `root_ids: null` and `tags_include: []`, and unique ids; JWT fixtures carry
 * the configured issuer and audience, a whole-second `exp` inside the 24h lifetime cap, and are signed with the key the mocked
 * key provider publishes.
 */
class CredentialResolverTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private val rsaKey = RSAKeyGenerator(2048).keyID("resolver-test-key").generate()
        private const val ISSUER = "https://idp.test.example"
        private const val DID_ISSUER = "did:web:idp.test.example"
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
        vararg extra: String,
        capabilities: String? = "[read]",
        expiresAt: String? = null,
    ): String {
        val lines =
            mutableListOf(
                "  - id: $id",
                "    token_sha256: \"${sha256Hex(raw)}\"",
                "    scope:",
                "      root_ids: null",
                "      tags_include: []",
            )
        if (capabilities != null) lines += "    capabilities: $capabilities"
        if (expiresAt != null) lines += "    expires_at: \"$expiresAt\""
        extra.forEach { lines += "    $it" }
        return lines.joinToString("\n")
    }

    private fun load(vararg entries: String): Map<HashBytes, BearerTokenStore.TokenEntry> {
        val file = dir.resolve("tokens-${fileCounter++}.yaml").toFile()
        file.writeText("version: 1\ntokens:\n" + entries.joinToString("\n") + "\n")
        return BearerTokenStore(file.absolutePath).loadWithEntries()
    }

    private fun resolverFor(
        entries: Map<HashBytes, BearerTokenStore.TokenEntry>,
        verifier: JwksApiVerifier? = null,
        now: Instant = Instant.now(),
    ) = CredentialResolver(entries, verifier) { now }

    private fun resolve(
        resolver: CredentialResolver,
        raw: String,
    ): Resolved? = runBlocking { resolver.resolveOrNull(raw) }

    // ------------------------------------------------------------------ JWT fixtures

    private fun jwksConfig(issuer: String) =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = issuer,
            audience = AUDIENCE,
            algorithms = listOf("RS256"),
            cacheTtlSeconds = 300,
        )

    private fun provider(stale: Boolean): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        val cache = CacheState(fromStaleCache = stale, ageSeconds = if (stale) 100L else null)
        val result = JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), cache)
        coEvery { provider.getKeySet() } returns result
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun verifier(
        issuer: String = ISSUER,
        stale: Boolean = false,
    ) = JwksApiVerifier(jwksConfig(issuer), provider(stale))

    private fun wholeSecondsFromNow(seconds: Long): Instant = Instant.ofEpochSecond(Instant.now().epochSecond + seconds)

    private fun jwt(
        subject: String = "jwt-svc",
        issuer: String = ISSUER,
        expiry: Instant = wholeSecondsFromNow(300),
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
        val signed = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("resolver-test-key").build(), builder.build())
        signed.sign(RSASSASigner(rsaKey))
        return signed.serialize()
    }

    private fun jwtResolver(
        issuer: String = ISSUER,
        stale: Boolean = false,
    ) = CredentialResolver(emptyMap(), verifier(issuer, stale))

    // ------------------------------------------------------------------ S1 entry defaults

    @Test
    fun `S1 an entry without kind resolves to SERVICE, ISSUED, the entry id, and no host`() {
        val entries = load(entry("svc-a", "raw-a"))
        val resolved = assertNotNull(resolve(resolverFor(entries), "raw-a"))
        val p = resolved.principal
        assertEquals("svc-a", p.id)
        assertEquals(PrincipalKind.SERVICE, p.kind)
        assertEquals(ProofStatus.ISSUED, p.proofStatus)
        assertEquals(setOf(ApiCapability.READ), p.capabilities)
        assertTrue(p.scope.isUnrestricted)
        assertNull(p.host)
        assertNull(resolved.expiresAt)
        assertNull(resolved.profile)
    }

    @Test
    fun `S1 the entry expiry is returned on Resolved and enforced against the injected clock`() {
        val expiry = Instant.parse("2026-10-13T12:00:00Z")
        val entries = load(entry("svc-exp", "raw-exp", expiresAt = "2026-10-13T12:00:00Z"))

        val before = assertNotNull(resolve(resolverFor(entries, now = Instant.parse("2026-10-13T11:59:59Z")), "raw-exp"))
        assertEquals(expiry, before.expiresAt)

        assertNull(
            resolve(resolverFor(entries, now = Instant.parse("2026-10-13T12:00:01Z")), "raw-exp"),
            "one second after expires_at the entry no longer resolves",
        )
    }

    @Test
    fun `S1 two entries resolve each to their own principal regardless of file order`() {
        val entries = load(entry("first", "raw-first"), entry("second", "raw-second"))
        val resolver = resolverFor(entries)
        assertEquals("second", assertNotNull(resolve(resolver, "raw-second")).principal.id)
        assertEquals("first", assertNotNull(resolve(resolver, "raw-first")).principal.id)
    }

    // ------------------------------------------------------------------ S2 / S3 entry kind

    @Test
    fun `S2 an entry kind of agent, human or service is carried and the proof stays ISSUED`() {
        val entries =
            load(
                entry("k-agent", "raw-agent", "kind: agent"),
                entry("k-human", "raw-human", "kind: human"),
                entry("k-service", "raw-service", "kind: service"),
            )
        val resolver = resolverFor(entries)
        val expected = mapOf("raw-agent" to PrincipalKind.AGENT, "raw-human" to PrincipalKind.HUMAN, "raw-service" to PrincipalKind.SERVICE)
        for ((raw, kind) in expected) {
            val p = assertNotNull(resolve(resolver, raw)).principal
            assertEquals(kind, p.kind, "kind of $raw")
            assertEquals(ProofStatus.ISSUED, p.proofStatus, "a declared kind never raises the proof of $raw")
        }
    }

    @Test
    fun `S3 an entry kind is trimmed and case-insensitive`() {
        val entries =
            load(
                entry("padded", "raw-padded", "kind: \" Human \""),
                entry("upper", "raw-upper", "kind: \"AGENT\""),
            )
        val resolver = resolverFor(entries)
        assertEquals(PrincipalKind.HUMAN, assertNotNull(resolve(resolver, "raw-padded")).principal.kind)
        assertEquals(PrincipalKind.AGENT, assertNotNull(resolve(resolver, "raw-upper")).principal.kind)
    }

    @Test
    fun `S3 an unknown entry kind fails startup naming the token id`() {
        val ex = assertThrows<IllegalArgumentException> { load(entry("bad-kind-1", "raw-x", "kind: robot")) }
        assertTrue("bad-kind-1" in ex.message.orEmpty(), "startup failure must name the token id: ${ex.message}")
        // control: the identical entry with a valid kind loads
        assertEquals(1, load(entry("bad-kind-1", "raw-x", "kind: agent")).size)
    }

    @Test
    fun `S3 probe an empty kind string fails startup like any unknown kind`() {
        val ex = assertThrows<IllegalArgumentException> { load(entry("empty-kind", "raw-e", "kind: \"\"")) }
        assertTrue("empty-kind" in ex.message.orEmpty(), "startup failure must name the token id: ${ex.message}")
    }

    @Test
    fun `S3 probe a misspelled kind key still fails startup as an unrecognized entry key`() {
        assertThrows<IllegalArgumentException> { load(entry("typo", "raw-t", "kinds: human")) }
        // control: the correct key is accepted
        assertEquals(1, load(entry("typo", "raw-t", "kind: human")).size)
    }

    // ------------------------------------------------------------------ entry profile

    @Test
    fun `an entry profile of director rides on Resolved and does not change the Principal`() {
        val entries =
            load(
                entry("dir", "raw-dir", "profile: director"),
                entry("plain", "raw-plain"),
            )
        val resolver = resolverFor(entries)
        val directorCredential = assertNotNull(resolve(resolver, "raw-dir"))
        assertEquals(DIRECTOR_CREDENTIAL_PROFILE, directorCredential.profile)
        assertEquals("director", directorCredential.profile)
        assertEquals(PrincipalKind.SERVICE, directorCredential.principal.kind)
        assertEquals(setOf(ApiCapability.READ), directorCredential.principal.capabilities)
        assertNull(assertNotNull(resolve(resolver, "raw-plain")).profile)
    }

    @Test
    fun `a profile other than director fails startup naming the token id`() {
        val ex = assertThrows<IllegalArgumentException> { load(entry("odd-profile", "raw-o", "profile: other")) }
        assertTrue("odd-profile" in ex.message.orEmpty(), "startup failure must name the token id: ${ex.message}")
        assertThrows<IllegalArgumentException> { load(entry("empty-profile", "raw-e", "profile: \"\"")) }
        // control: director loads
        assertEquals(1, load(entry("odd-profile", "raw-o", "profile: director")).size)
    }

    @Test
    fun `admin with a director profile loads and keeps ADMIN, the restriction is the route guard's job`() {
        val entries = load(entry("dir-admin", "raw-da", "profile: director", capabilities = "[admin]"))
        val resolved = assertNotNull(resolve(resolverFor(entries), "raw-da"))
        assertEquals("director", resolved.profile)
        assertTrue(ApiCapability.ADMIN in resolved.principal.capabilities)
    }

    // ------------------------------------------------------------------ S7 failures

    @Test
    fun `S7 an expired entry, an unknown token and an empty string all resolve to null`() {
        val entries = load(entry("svc-x", "raw-x", expiresAt = "2020-01-01T00:00:00Z"), entry("svc-live", "raw-live"))
        val resolver = resolverFor(entries, now = Instant.parse("2026-10-13T12:00:00Z"))
        assertNull(resolve(resolver, "raw-x"), "expired")
        assertNull(resolve(resolver, "not-a-token"), "unknown")
        assertNull(resolve(resolver, ""), "empty")
        assertEquals("svc-live", assertNotNull(resolve(resolver, "raw-live")).principal.id, "control: a live entry resolves")
    }

    @Test
    fun `S7 probe lookup is by the SHA-256 of the exact bytes and nothing else`() {
        val entries = load(entry("svc-p", "raw-p"))
        val resolver = resolverFor(entries)
        assertEquals("svc-p", assertNotNull(resolve(resolver, "raw-p")).principal.id)

        assertNull(resolve(resolver, "svc-p"), "the entry id is not a credential")
        assertNull(resolve(resolver, sha256Hex("raw-p")), "presenting the stored digest must not authenticate")
        assertNull(resolve(resolver, "RAW-P"), "case differs")
        assertNull(resolve(resolver, " raw-p"), "leading space")
        assertNull(resolve(resolver, "raw-p "), "trailing space")
        assertNull(resolve(resolver, "raw-p\n"), "trailing newline")
        assertNull(resolve(resolver, "raw-"), "prefix")
        assertNull(resolve(resolver, "raw-pp"), "suffix")
        assertNull(resolve(resolver, "Bearer raw-p"), "the scheme is stripped before the resolver")
        assertNull(resolve(resolver, " "), "blank")
    }

    @Test
    fun `S7 a JWT-shaped string with no verifier configured resolves to null`() {
        val entries = load(entry("svc-j", "raw-j"))
        assertNull(resolve(resolverFor(entries, verifier = null), jwt()))
    }

    // ------------------------------------------------------------------ S14 capabilities

    @Test
    fun `S14 an entry with an empty capability list gets the empty set, not ADMIN`() {
        val entries = load(entry("no-caps", "raw-nc", capabilities = "[]"))
        val p = assertNotNull(resolve(resolverFor(entries), "raw-nc")).principal
        assertEquals(emptySet(), p.capabilities)
        assertFalse(ApiCapability.ADMIN in p.capabilities)
    }

    @Test
    fun `S14 an entry with no capability key gets the empty set, not ADMIN`() {
        val entries = load(entry("absent-caps", "raw-ac", capabilities = null))
        val p = assertNotNull(resolve(resolverFor(entries), "raw-ac")).principal
        assertEquals(emptySet(), p.capabilities)
        assertFalse(ApiCapability.ADMIN in p.capabilities)
    }

    @Test
    fun `S14 the new capability spellings load from the token file`() {
        val entries = load(entry("new-caps", "raw-new", capabilities = "[write-docs, runs, decide]"))
        val p = assertNotNull(resolve(resolverFor(entries), "raw-new")).principal
        assertEquals(setOf(ApiCapability.WRITE_DOCS, ApiCapability.RUNS, ApiCapability.DECIDE), p.capabilities)
    }

    @Test
    fun `S14 the local principal is local, AGENT, NONE, unrestricted, ADMIN`() {
        val p = LOCAL_PRINCIPAL
        assertEquals("local", p.id)
        assertEquals(PrincipalKind.AGENT, p.kind)
        assertEquals(ProofStatus.NONE, p.proofStatus)
        assertTrue(p.scope.isUnrestricted)
        assertEquals(setOf(ApiCapability.ADMIN), p.capabilities)
        assertNull(p.host)
    }

    // ------------------------------------------------------------------ S4 JWT kind and proof

    @Test
    fun `S4 a JWT without to_kind resolves to SERVICE, VERIFIED, sub as id, read only, with its exp`() {
        val exp = wholeSecondsFromNow(300)
        val resolved = assertNotNull(resolve(jwtResolver(), jwt(subject = "jwt-svc-1", expiry = exp)))
        val p = resolved.principal
        assertEquals("jwt-svc-1", p.id)
        assertEquals(PrincipalKind.SERVICE, p.kind)
        assertEquals(ProofStatus.VERIFIED, p.proofStatus)
        assertEquals(setOf(ApiCapability.READ), p.capabilities)
        assertEquals(exp, resolved.expiresAt)
        assertNull(resolved.profile, "no JWT profile claim in R10")
    }

    @Test
    fun `S4 to_kind human, agent and service are carried from the claim`() {
        val resolver = jwtResolver()
        assertEquals(PrincipalKind.HUMAN, assertNotNull(resolve(resolver, jwt(claims = mapOf("to_kind" to "human")))).principal.kind)
        assertEquals(PrincipalKind.AGENT, assertNotNull(resolve(resolver, jwt(claims = mapOf("to_kind" to "agent")))).principal.kind)
        assertEquals(PrincipalKind.SERVICE, assertNotNull(resolve(resolver, jwt(claims = mapOf("to_kind" to "service")))).principal.kind)
    }

    @Test
    fun `S4 to_kind is trimmed and case-insensitive`() {
        val resolver = jwtResolver()
        assertEquals(PrincipalKind.HUMAN, assertNotNull(resolve(resolver, jwt(claims = mapOf("to_kind" to "HUMAN")))).principal.kind)
        assertEquals(PrincipalKind.AGENT, assertNotNull(resolve(resolver, jwt(claims = mapOf("to_kind" to " Agent ")))).principal.kind)
    }

    @Test
    fun `S4 a JWT from a stale key set is VERIFIED_STALE and from a fresh one VERIFIED`() {
        val stale = assertNotNull(resolve(jwtResolver(stale = true), jwt())).principal
        assertEquals(ProofStatus.VERIFIED_STALE, stale.proofStatus)
        val fresh = assertNotNull(resolve(jwtResolver(stale = false), jwt())).principal
        assertEquals(ProofStatus.VERIFIED, fresh.proofStatus)
    }

    @Test
    fun `S4 a JWT may carry the new capability spellings and unknown ones are dropped`() {
        val resolver = jwtResolver()
        val docs = assertNotNull(resolve(resolver, jwt(claims = mapOf("to_capabilities" to listOf("write-docs"))))).principal
        assertEquals(setOf(ApiCapability.WRITE_DOCS), docs.capabilities)
        val mixed =
            assertNotNull(resolve(resolver, jwt(claims = mapOf("to_capabilities" to listOf("runs", "decide", "superpower"))))).principal
        assertEquals(setOf(ApiCapability.RUNS, ApiCapability.DECIDE), mixed.capabilities)
    }

    @Test
    fun `S14 a JWT with no to_capabilities or an empty list defaults to read and never to ADMIN`() {
        val resolver = jwtResolver()
        val absent = assertNotNull(resolve(resolver, jwt())).principal
        assertEquals(setOf(ApiCapability.READ), absent.capabilities)
        val empty = assertNotNull(resolve(resolver, jwt(claims = mapOf("to_capabilities" to emptyList<String>())))).principal
        assertEquals(setOf(ApiCapability.READ), empty.capabilities)
        assertFalse(ApiCapability.ADMIN in absent.capabilities || ApiCapability.ADMIN in empty.capabilities)
    }

    // ------------------------------------------------------------------ S5 DID default

    @Test
    fun `S5 a DID subject equal to the issuer with no to_kind defaults to AGENT`() {
        val token = jwt(subject = DID_ISSUER, issuer = DID_ISSUER)
        val p = assertNotNull(resolve(jwtResolver(issuer = DID_ISSUER), token)).principal
        assertEquals(PrincipalKind.AGENT, p.kind)
        assertEquals(DID_ISSUER, p.id)
        assertEquals(ProofStatus.VERIFIED, p.proofStatus)
    }

    @Test
    fun `S5 an explicit to_kind wins over the DID default`() {
        val resolver = jwtResolver(issuer = DID_ISSUER)
        val service =
            assertNotNull(resolve(resolver, jwt(subject = DID_ISSUER, issuer = DID_ISSUER, claims = mapOf("to_kind" to "service"))))
        assertEquals(PrincipalKind.SERVICE, service.principal.kind)
        val human = assertNotNull(resolve(resolver, jwt(subject = DID_ISSUER, issuer = DID_ISSUER, claims = mapOf("to_kind" to "human"))))
        assertEquals(PrincipalKind.HUMAN, human.principal.kind)
    }

    @Test
    fun `S5 probe a did subject that differs from the issuer stays SERVICE`() {
        val p = assertNotNull(resolve(jwtResolver(issuer = ISSUER), jwt(subject = "did:web:somebody.else", issuer = ISSUER))).principal
        assertEquals(PrincipalKind.SERVICE, p.kind)
        // control: the DID default does apply when subject equals issuer
        val agent = assertNotNull(resolve(jwtResolver(issuer = DID_ISSUER), jwt(subject = DID_ISSUER, issuer = DID_ISSUER))).principal
        assertEquals(PrincipalKind.AGENT, agent.kind)
    }

    @Test
    fun `S5 probe a subject equal to the issuer that is not a did stays SERVICE`() {
        val p = assertNotNull(resolve(jwtResolver(issuer = ISSUER), jwt(subject = ISSUER, issuer = ISSUER))).principal
        assertEquals(PrincipalKind.SERVICE, p.kind)
    }

    @Test
    fun `S5 probe an uppercase DID scheme is not the did prefix`() {
        val upper = "DID:web:idp.test.example"
        val p = assertNotNull(resolve(jwtResolver(issuer = upper), jwt(subject = upper, issuer = upper))).principal
        assertEquals(PrincipalKind.SERVICE, p.kind, "the DID scheme name is the lowercase did: (W3C DID Core, section 3.1)")
    }

    // ------------------------------------------------------------------ S6 rejected to_kind

    @Test
    fun `S6 an unknown to_kind rejects the whole JWT`() {
        val resolver = jwtResolver()
        assertNull(resolve(resolver, jwt(claims = mapOf("to_kind" to "robot"))))
        assertNull(resolve(resolver, jwt(claims = mapOf("to_kind" to "humans"))))
        assertNull(resolve(resolver, jwt(claims = mapOf("to_kind" to ""))), "empty is an unknown kind, not an absent one")
        assertNull(resolve(resolver, jwt(claims = mapOf("to_kind" to "  "))), "blank is an unknown kind, not an absent one")
        // control: a valid to_kind on the otherwise identical JWT resolves
        assertNotNull(resolve(resolver, jwt(claims = mapOf("to_kind" to "agent"))))
    }

    @Test
    fun `S6 a non-string to_kind rejects the whole JWT`() {
        val resolver = jwtResolver()
        assertNull(resolve(resolver, jwt(claims = mapOf("to_kind" to 5))), "number")
        assertNull(resolve(resolver, jwt(claims = mapOf("to_kind" to true))), "boolean")
        assertNull(resolve(resolver, jwt(claims = mapOf("to_kind" to listOf("human")))), "list")
        assertNull(resolve(resolver, jwt(claims = mapOf("to_kind" to mapOf("kind" to "human")))), "object")
        assertNotNull(resolve(resolver, jwt(claims = mapOf("to_kind" to "human"))), "control: the string form resolves")
    }

    @Test
    fun `S6 an expired JWT and a wrong-issuer JWT resolve to null while the control resolves`() {
        val resolver = jwtResolver()
        assertNull(resolve(resolver, jwt(expiry = Instant.ofEpochSecond(Instant.now().epochSecond - 3600))), "expired")
        assertNull(resolve(resolver, jwt(issuer = "https://evil.example")), "wrong issuer")
        assertNotNull(resolve(resolver, jwt()), "control")
    }

    // ------------------------------------------------------------------ order

    @Test
    fun `an entry token resolves through the entry path even when a verifier is configured, and a JWT through the verifier`() {
        val entries = load(entry("svc-both", "raw-both", "kind: agent"))
        val resolver = CredentialResolver(entries, verifier())
        val fromEntry = assertNotNull(resolve(resolver, "raw-both"))
        assertEquals("svc-both", fromEntry.principal.id)
        assertEquals(ProofStatus.ISSUED, fromEntry.principal.proofStatus)
        val fromJwt = assertNotNull(resolve(resolver, jwt(subject = "jwt-both")))
        assertEquals("jwt-both", fromJwt.principal.id)
        assertEquals(ProofStatus.VERIFIED, fromJwt.principal.proofStatus)
        assertNull(resolve(resolver, "neither-an-entry-nor-a-jwt"))
    }
}
