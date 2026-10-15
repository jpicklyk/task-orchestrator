package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfigLoader
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.toKeySource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Test-author coverage for item 385444c2 (R8), configuration half (scenario S18): the new
 * `API_JWKS_MAX_STALE_SECONDS` variable and the `toKeySource()` mapping that carries it to the key
 * provider.
 *
 * Oracle (task-scope s3, decision D4, frozen at queue phase): the env var fails fast like `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS` (non-numeric or <= 0 names the
 * variable) and obeys the same `>=` rule against `API_JWKS_CACHE_TTL_SECONDS`.
 *
 * The exact message text of the wrong-type branch is not declared, so those cases assert the
 * exception type only. The default for the env var when `API_JWKS_CACHE_TTL_SECONDS` exceeds 3600
 * and the variable is absent is not declared; it is not asserted.
 */
class MaxStaleConfigTest {
    // -------------------------------------------------------------------------
    // Defaults on the domain/config classes
    // -------------------------------------------------------------------------

    @Test
    fun `the API config class defaults to one hour of staleness and no insecure URLs`() {
        val api =
            ApiAuthConfig.Jwks(
                url = "https://idp.example.com/jwks.json",
                issuer = "https://idp.example.com",
                audience = "aud",
                algorithms = listOf("RS256"),
                cacheTtlSeconds = 300,
            )
        assertEquals(3600L, api.maxStaleSeconds)
        assertFalse(api.allowInsecureUrl)
    }

    // -------------------------------------------------------------------------
    // S18 - env: API_JWKS_MAX_STALE_SECONDS
    // -------------------------------------------------------------------------

    private fun loader(vararg extra: Pair<String, String>): ApiAuthConfigLoader {
        val env =
            mapOf(
                "API_ENABLED" to "true",
                "API_AUTH_MODE" to "jwks",
                "API_JWKS_URL" to "https://idp.example.com/.well-known/jwks.json",
                "API_JWKS_ISSUER" to "https://idp.example.com",
                "API_JWKS_AUDIENCE" to "task-orchestrator-api",
                "API_JWKS_ALGORITHMS" to "RS256",
            ) + extra.toMap()
        return ApiAuthConfigLoader(envResolver = { key -> env[key] })
    }

    private fun load(vararg extra: Pair<String, String>): ApiAuthConfig.Jwks {
        val config = loader(*extra).load()
        assertInstanceOf(ApiAuthConfig.Jwks::class.java, config)
        return config as ApiAuthConfig.Jwks
    }

    private fun assertEnvRejected(vararg extra: Pair<String, String>) {
        val ex = assertThrows(IllegalArgumentException::class.java) { loader(*extra).load() }
        assertTrue(
            ex.message?.contains("API_JWKS_MAX_STALE_SECONDS") == true,
            "Expected the error to name the variable, got: ${ex.message}"
        )
    }

    @Test
    fun `S18 API_JWKS_MAX_STALE_SECONDS unset defaults to 3600`() {
        assertEquals(3600L, load().maxStaleSeconds)
    }

    @Test
    fun `S18 API_JWKS_MAX_STALE_SECONDS is parsed when valid`() {
        assertEquals(7200L, load("API_JWKS_MAX_STALE_SECONDS" to "7200").maxStaleSeconds)
        assertEquals(300L, load("API_JWKS_CACHE_TTL_SECONDS" to "300", "API_JWKS_MAX_STALE_SECONDS" to "300").maxStaleSeconds)
    }

    @Test
    fun `S18 API_JWKS_MAX_STALE_SECONDS non-numeric zero or negative fails fast naming the variable`() {
        listOf("abc", "0", "-1", "1.5").forEach { bad ->
            assertEnvRejected("API_JWKS_MAX_STALE_SECONDS" to bad)
        }
    }

    @Test
    fun `S18 API_JWKS_MAX_STALE_SECONDS below API_JWKS_CACHE_TTL_SECONDS fails fast naming the variable`() {
        assertEnvRejected("API_JWKS_CACHE_TTL_SECONDS" to "300", "API_JWKS_MAX_STALE_SECONDS" to "299")
        assertEnvRejected("API_JWKS_CACHE_TTL_SECONDS" to "300", "API_JWKS_MAX_STALE_SECONDS" to "100")
    }

    @Test
    fun `S18 API_JWKS_ALLOW_INSECURE_URL is carried onto the API config`() {
        assertFalse(load().allowInsecureUrl)
        assertTrue(load("API_JWKS_ALLOW_INSECURE_URL" to "true").allowInsecureUrl)
    }

    // -------------------------------------------------------------------------
    // toKeySource: the provider sees exactly what was configured
    // -------------------------------------------------------------------------

    @Test
    fun `the API config maps its url cache ttl max stale and insecure flag onto the key source`() {
        val ks =
            ApiAuthConfig
                .Jwks(
                    url = "https://idp.example.com/jwks.json",
                    issuer = "https://idp.example.com",
                    audience = "aud",
                    algorithms = listOf("RS256"),
                    cacheTtlSeconds = 150,
                    maxStaleSeconds = 1800,
                    allowInsecureUrl = true,
                ).toKeySource()

        assertEquals("https://idp.example.com/jwks.json", ks.jwksUri)
        assertEquals(150L, ks.cacheTtlSeconds)
        assertEquals(1800L, ks.maxStaleSeconds)
        assertTrue(ks.allowInsecureUrl)
        assertTrue(ks.didAllowlist.isEmpty(), "the REST path has no DID trust")
        assertEquals(null, ks.didPattern)
    }
}
