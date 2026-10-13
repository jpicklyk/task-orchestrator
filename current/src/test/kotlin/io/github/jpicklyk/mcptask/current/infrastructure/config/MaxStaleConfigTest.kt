package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfigLoader
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.toKeySource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Test-author coverage for item 385444c2 (R8), configuration half (scenario S18): the new
 * `max_stale_seconds` key (`actor_authentication.verifier.max_stale_seconds`) and the new
 * `API_JWKS_MAX_STALE_SECONDS` variable, and the `toKeySource()` mappings that carry them to the key
 * provider.
 *
 * Oracle (task-scope s3, decision D4, frozen at queue phase): the key is a number, `> 0` and
 * `>= cache_ttl_seconds`, otherwise IllegalArgumentException naming
 * `actor_authentication.verifier.max_stale_seconds`; absent means `max(3600, cacheTtlSeconds)`. The
 * env var fails fast like `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS` (non-numeric or <= 0 names the
 * variable) and obeys the same `>=` rule against `API_JWKS_CACHE_TTL_SECONDS`.
 *
 * The exact message text of the wrong-type branch is not declared, so those cases assert the
 * exception type only. The default for the env var when `API_JWKS_CACHE_TTL_SECONDS` exceeds 3600
 * and the variable is absent is not declared; it is not asserted.
 */
class MaxStaleConfigTest {
    @TempDir
    lateinit var tempDir: Path

    private fun configFile(
        cacheTtl: String? = null,
        maxStale: String? = null,
    ): Path {
        val dir = File(tempDir.toFile(), ".taskorchestrator")
        dir.mkdirs()
        val file = File(dir, "config.yaml")
        val extra =
            buildString {
                cacheTtl?.let { append("    cache_ttl_seconds: $it\n") }
                maxStale?.let { append("    max_stale_seconds: $it\n") }
            }
        file.writeText(
            "actor_authentication:\n" +
                "  verifier:\n" +
                "    type: jwks\n" +
                "    audience: \"test-audience\"\n" +
                "    issuer: \"https://issuer.example.com\"\n" +
                "    jwks_path: \"/etc/keys/jwks.json\"\n" +
                "    algorithms:\n" +
                "      - EdDSA\n" +
                extra
        )
        return file.toPath()
    }

    private fun parse(
        cacheTtl: String? = null,
        maxStale: String? = null,
    ): VerifierConfig.Jwks {
        val service = YamlActorAuthenticationConfigService(configFile(cacheTtl, maxStale))
        val verifier = service.getConfig().verifier
        assertInstanceOf(VerifierConfig.Jwks::class.java, verifier)
        return verifier as VerifierConfig.Jwks
    }

    private fun assertRejected(
        cacheTtl: String? = null,
        maxStale: String?,
    ): IllegalArgumentException =
        assertThrows(IllegalArgumentException::class.java) {
            YamlActorAuthenticationConfigService(configFile(cacheTtl, maxStale)).getConfig()
        }

    // -------------------------------------------------------------------------
    // Defaults on the domain/config classes
    // -------------------------------------------------------------------------

    @Test
    fun `the config classes default to one hour of staleness and no insecure URLs`() {
        val actor = VerifierConfig.Jwks()
        assertEquals(3600L, actor.maxStaleSeconds)
        assertFalse(actor.allowInsecureUrl)

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
    // S18 - YAML: actor_authentication.verifier.max_stale_seconds
    // -------------------------------------------------------------------------

    @Test
    fun `S18 absent max_stale_seconds defaults to 3600 when the cache ttl is below it`() {
        assertEquals(3600L, parse().maxStaleSeconds)
        assertEquals(3600L, parse(cacheTtl = "300").maxStaleSeconds)
        assertEquals(3600L, parse(cacheTtl = "3600").maxStaleSeconds)
    }

    @Test
    fun `S18 absent max_stale_seconds follows a cache ttl above 3600`() {
        assertEquals(7200L, parse(cacheTtl = "7200").maxStaleSeconds)
    }

    @Test
    fun `S18 an explicit max_stale_seconds is used as given`() {
        assertEquals(600L, parse(maxStale = "600").maxStaleSeconds)
        assertEquals(86400L, parse(maxStale = "86400").maxStaleSeconds)
    }

    @Test
    fun `S18 max_stale_seconds equal to cache_ttl_seconds is accepted and one below is rejected`() {
        assertEquals(300L, parse(cacheTtl = "300", maxStale = "300").maxStaleSeconds)

        val ex = assertRejected(cacheTtl = "300", maxStale = "299")
        assertTrue(
            ex.message?.contains("actor_authentication.verifier.max_stale_seconds") == true,
            "Expected the error to name the key, got: ${ex.message}"
        )
    }

    @Test
    fun `S18 max_stale_seconds below the default cache ttl is rejected naming the key`() {
        val ex = assertRejected(maxStale = "100")
        assertTrue(
            ex.message?.contains("actor_authentication.verifier.max_stale_seconds") == true,
            "Expected the error to name the key, got: ${ex.message}"
        )
    }

    @Test
    fun `S18 max_stale_seconds below an explicit cache ttl is rejected naming the key`() {
        val ex = assertRejected(cacheTtl = "300", maxStale = "100")
        assertTrue(
            ex.message?.contains("actor_authentication.verifier.max_stale_seconds") == true,
            "Expected the error to name the key, got: ${ex.message}"
        )
    }

    @Test
    fun `S18 zero and negative max_stale_seconds are rejected naming the key`() {
        listOf("0", "-1", "-3600").forEach { bad ->
            val ex = assertRejected(maxStale = bad)
            assertTrue(
                ex.message?.contains("actor_authentication.verifier.max_stale_seconds") == true,
                "Expected the error for '$bad' to name the key, got: ${ex.message}"
            )
        }
    }

    @Test
    fun `S18 a non-numeric max_stale_seconds is rejected`() {
        assertRejected(maxStale = "\"abc\"")
        assertRejected(maxStale = "\"3600\"")
        assertRejected(maxStale = "1.5")
        assertRejected(maxStale = "true")
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
    fun `the actor config maps onto the key source field for field`() {
        val ks =
            VerifierConfig
                .Jwks(
                    oidcDiscovery = "https://idp.example.com/.well-known/openid-configuration",
                    cacheTtlSeconds = 120,
                    staleOnError = false,
                    maxStaleSeconds = 900,
                    allowInsecureUrl = true,
                    didAllowlist = listOf("did:web:a.example.com"),
                    didPattern = "did:web:*.example.com",
                    didStrictRelationship = false,
                ).toKeySource()

        assertEquals("https://idp.example.com/.well-known/openid-configuration", ks.oidcDiscovery)
        assertEquals(null, ks.jwksUri)
        assertEquals(null, ks.jwksPath)
        assertEquals(120L, ks.cacheTtlSeconds)
        assertFalse(ks.staleOnError)
        assertEquals(900L, ks.maxStaleSeconds)
        assertTrue(ks.allowInsecureUrl)
        assertEquals(listOf("did:web:a.example.com"), ks.didAllowlist)
        assertEquals("did:web:*.example.com", ks.didPattern)
        assertFalse(ks.didStrictRelationship)

        val uri = VerifierConfig.Jwks(jwksUri = "https://k.example.com/jwks.json").toKeySource()
        assertEquals("https://k.example.com/jwks.json", uri.jwksUri)
        assertEquals(null, uri.oidcDiscovery)

        val path = VerifierConfig.Jwks(jwksPath = "keys/jwks.json").toKeySource()
        assertEquals("keys/jwks.json", path.jwksPath)
    }

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
