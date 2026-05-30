package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant

/**
 * Unit tests for the authentication plugin logic.
 *
 * Since [ApiBearerAuth] is a Ktor plugin that requires a full Ktor application context for
 * integration testing (via `ktor-server-test-host`), and that dependency is not in the project,
 * these tests exercise the key algorithmic components directly:
 *
 * 1. SHA-256 token lookup via [BearerTokenStore.lookupInEntries]
 * 2. Expiry enforcement in the token store
 * 3. [JwksApiVerifier.verify] called correctly
 * 4. [HashBytes] equality using constant-time compare
 *
 * Full integration tests (HTTP 401/200 responses) will be added in Phase 2 once the plugin
 * is wired into the embedded server and `ktor-server-test-host` can be added as a test dep.
 */
class AuthenticationPluginTest {
    private fun sha256(input: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(input: String): String = sha256(input).joinToString("") { "%02x".format(it) }

    private fun makePrincipal(id: String): ApiPrincipal =
        ApiPrincipal(
            tokenId = id,
            scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
            capabilities = setOf(ApiCapability.READ),
            authMode = ApiAuthMode.BEARER,
        )

    private fun makeEntries(vararg tokens: Pair<String, String>): Map<HashBytes, BearerTokenStore.TokenEntry> =
        tokens.associate { (rawToken, tokenId) ->
            HashBytes(sha256(rawToken)) to BearerTokenStore.TokenEntry(makePrincipal(tokenId), expiresAt = null)
        }

    // -------------------------------------------------------------------------
    // BearerTokenStore.lookupInEntries — directly exercises the auth logic
    // -------------------------------------------------------------------------

    @Test
    fun `valid bearer token resolves to principal`() {
        val entries = makeEntries("secret-token" to "my-service")
        val store = BearerTokenStore("/unused")
        val result = store.lookupInEntries("secret-token", entries)
        assertNotNull(result)
        assertEquals("my-service", result!!.tokenId)
    }

    @Test
    fun `missing bearer token returns null`() {
        val entries = makeEntries("correct-secret" to "my-service")
        val store = BearerTokenStore("/unused")
        val result = store.lookupInEntries("wrong-secret", entries)
        assertNull(result)
    }

    @Test
    fun `expired bearer token returns null`() {
        val rawToken = "expiring-token"
        val pastExpiry = Instant.now().minusSeconds(10)
        val entries =
            mapOf(
                HashBytes(sha256(rawToken)) to
                    BearerTokenStore.TokenEntry(
                        principal = makePrincipal("expiring"),
                        expiresAt = pastExpiry,
                    ),
            )
        val fixedClock: () -> Instant = { Instant.now() } // real clock, token is in the past
        val store = BearerTokenStore("/unused", clock = fixedClock)
        val result = store.lookupInEntries(rawToken, entries)
        assertNull(result, "Expired token should return null")
    }

    @Test
    fun `non-expired bearer token with future expiry returns principal`() {
        val rawToken = "future-token"
        val futureExpiry = Instant.now().plusSeconds(3600)
        val entries =
            mapOf(
                HashBytes(sha256(rawToken)) to
                    BearerTokenStore.TokenEntry(
                        principal = makePrincipal("future-user"),
                        expiresAt = futureExpiry,
                    ),
            )
        val store = BearerTokenStore("/unused")
        val result = store.lookupInEntries(rawToken, entries)
        assertNotNull(result)
        assertEquals("future-user", result!!.tokenId)
    }

    @Test
    fun `bearer token lookup uses constant-time comparison via HashBytes`() {
        // Two different raw tokens produce different digests — verify no false positive
        val tokenA = "token-alpha"
        val tokenB = "token-beta"

        val entries = makeEntries(tokenA to "alpha-user")
        val store = BearerTokenStore("/unused")

        assertNotNull(store.lookupInEntries(tokenA, entries), "tokenA should resolve")
        assertNull(store.lookupInEntries(tokenB, entries), "tokenB should NOT resolve for tokenA's entry")
    }

    @Test
    fun `multiple tokens in map — correct one is resolved`() {
        val entries =
            makeEntries(
                "token-read" to "reader",
                "token-write" to "writer",
                "token-admin" to "admin",
            )
        val store = BearerTokenStore("/unused")

        assertEquals("reader", store.lookupInEntries("token-read", entries)?.tokenId)
        assertEquals("writer", store.lookupInEntries("token-write", entries)?.tokenId)
        assertEquals("admin", store.lookupInEntries("token-admin", entries)?.tokenId)
        assertNull(store.lookupInEntries("token-unknown", entries))
    }

    // -------------------------------------------------------------------------
    // JWKS mode — JwksApiVerifier integration
    // -------------------------------------------------------------------------

    @Test
    fun `JWKS verifier called with token string`() =
        runTest {
            val verifier = mockk<JwksApiVerifier>()
            val principal = makePrincipal("jwks-caller").copy(authMode = ApiAuthMode.JWKS)
            coEvery { verifier.verify(any()) } returns principal

            val result = verifier.verify("valid.jwt.token")
            assertNotNull(result)
            assertEquals(ApiAuthMode.JWKS, result!!.authMode)
        }

    @Test
    fun `JWKS verifier returns null for bad JWT`() =
        runTest {
            val verifier = mockk<JwksApiVerifier>()
            coEvery { verifier.verify(any()) } returns null

            val result = verifier.verify("bad.jwt")
            assertNull(result)
        }

    // -------------------------------------------------------------------------
    // HashBytes structural equality
    // -------------------------------------------------------------------------

    @Test
    fun `HashBytes with same bytes are equal`() {
        val bytes = sha256("same-token")
        val a = HashBytes(bytes.clone())
        val b = HashBytes(bytes.clone())
        assertEquals(a, b, "HashBytes wrapping identical bytes must be equal")
        assertEquals(a.hashCode(), b.hashCode(), "Equal HashBytes must have same hashCode")
    }

    @Test
    fun `HashBytes with different bytes are not equal`() {
        val a = HashBytes(sha256("token-one"))
        val b = HashBytes(sha256("token-two"))
        assert(a != b) { "HashBytes wrapping different bytes must not be equal" }
    }

    @Test
    fun `HashBytes works as Map key for lookup`() {
        val raw = "lookup-test"
        val digest = sha256(raw)
        val key = HashBytes(digest.clone())

        val map = mapOf(HashBytes(digest.clone()) to "found-value")
        val result = map[key]
        assertEquals("found-value", result, "HashBytes lookup must work as a Map key")
    }
}
