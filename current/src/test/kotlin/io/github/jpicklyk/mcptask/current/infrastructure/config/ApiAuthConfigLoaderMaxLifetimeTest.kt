package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Independent test-author coverage for item 3dcfcbab, Part 1 (REST/API side):
 * `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS`, parsed by [ApiAuthConfigLoader].
 *
 * Oracle (task-scope, frozen at queue phase): "Env API_JWKS_MAX_TOKEN_LIFETIME_SECONDS ->
 * ApiAuthConfig.Jwks.maxTokenLifetimeSeconds: Long = 86400 ... Parse in
 * ApiAuthConfigLoader.loadJwks: non-numeric or <= 0 -> IllegalArgumentException (do NOT copy the
 * toLongOrNull-silent-default of API_JWKS_CACHE_TTL_SECONDS)." Absent -> defaults to 86400L
 * (`DEFAULT_MAX_TOKEN_LIFETIME_SECONDS` constant, corroborated independently by the
 * `ApiAuthConfig.Jwks` constructor default of the same value).
 */
class ApiAuthConfigLoaderMaxLifetimeTest {
    private fun env(vararg pairs: Pair<String, String?>): (String) -> String? {
        val map = mapOf(*pairs)
        return { key -> map[key] }
    }

    private fun baseJwksEnv(vararg extra: Pair<String, String?>): Map<String, String?> =
        mapOf(
            "API_ENABLED" to "true",
            "API_AUTH_MODE" to "jwks",
            "API_JWKS_URL" to "https://idp.example.com/.well-known/jwks.json",
            "API_JWKS_ISSUER" to "https://idp.example.com",
            "API_JWKS_AUDIENCE" to "task-orchestrator-api",
            "API_JWKS_ALGORITHMS" to "RS256",
        ) + extra.toMap()

    // -------------------------------------------------------------------------
    // Default
    // -------------------------------------------------------------------------

    @Test
    fun `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS absent defaults to 86400`() {
        val loader = ApiAuthConfigLoader(envResolver = env(*baseJwksEnv().toList().toTypedArray()))
        val config = loader.load()

        assertInstanceOf(ApiAuthConfig.Jwks::class.java, config)
        assertEquals(86400L, (config as ApiAuthConfig.Jwks).maxTokenLifetimeSeconds)
    }

    // -------------------------------------------------------------------------
    // Valid override
    // -------------------------------------------------------------------------

    @Test
    fun `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS of 3600 overrides the default`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(*baseJwksEnv("API_JWKS_MAX_TOKEN_LIFETIME_SECONDS" to "3600").toList().toTypedArray()),
            )
        val config = loader.load() as ApiAuthConfig.Jwks

        assertEquals(3600L, config.maxTokenLifetimeSeconds)
    }

    // -------------------------------------------------------------------------
    // S16 - invalid values fail fast (unlike API_JWKS_CACHE_TTL_SECONDS's silent default)
    // -------------------------------------------------------------------------

    @Test
    fun `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS of a non-numeric value throws`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(*baseJwksEnv("API_JWKS_MAX_TOKEN_LIFETIME_SECONDS" to "abc").toList().toTypedArray()),
            )

        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(
            ex.message!!.contains("API_JWKS_MAX_TOKEN_LIFETIME_SECONDS"),
            "Expected the error to name the offending env var, got: ${ex.message}"
        )
    }

    @Test
    fun `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS of 0 throws`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(*baseJwksEnv("API_JWKS_MAX_TOKEN_LIFETIME_SECONDS" to "0").toList().toTypedArray()),
            )

        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(
            ex.message!!.contains("API_JWKS_MAX_TOKEN_LIFETIME_SECONDS"),
            "Expected the error to name the offending env var, got: ${ex.message}"
        )
    }

    @Test
    fun `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS of a negative value throws`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(*baseJwksEnv("API_JWKS_MAX_TOKEN_LIFETIME_SECONDS" to "-1").toList().toTypedArray()),
            )

        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(
            ex.message!!.contains("API_JWKS_MAX_TOKEN_LIFETIME_SECONDS"),
            "Expected the error to name the offending env var, got: ${ex.message}"
        )
    }

    // -------------------------------------------------------------------------
    // Overflow ceiling (ADDENDUM, fix cycle 4fe7bd3d): MAX_TOKEN_LIFETIME_SECONDS_CEILING caps
    // API_JWKS_MAX_TOKEN_LIFETIME_SECONDS from above. Oracle: the ADDENDUM declares
    // MAX_TOKEN_LIFETIME_SECONDS_CEILING = 3_153_600_000L (100 years, in seconds), and task-scope
    // states a wrong/invalid value fails startup.
    // -------------------------------------------------------------------------

    @Test
    fun `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS one above the ceiling throws`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(
                        *baseJwksEnv(
                            "API_JWKS_MAX_TOKEN_LIFETIME_SECONDS" to (MAX_TOKEN_LIFETIME_SECONDS_CEILING + 1).toString()
                        ).toList().toTypedArray()
                    ),
            )

        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(
            ex.message!!.contains("API_JWKS_MAX_TOKEN_LIFETIME_SECONDS"),
            "Expected the error to name the offending env var, got: ${ex.message}"
        )
    }

    @Test
    fun `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS exactly at the ceiling is accepted`() {
        val loader =
            ApiAuthConfigLoader(
                envResolver =
                    env(
                        *baseJwksEnv(
                            "API_JWKS_MAX_TOKEN_LIFETIME_SECONDS" to MAX_TOKEN_LIFETIME_SECONDS_CEILING.toString()
                        ).toList().toTypedArray()
                    ),
            )
        val config = loader.load() as ApiAuthConfig.Jwks

        assertEquals(MAX_TOKEN_LIFETIME_SECONDS_CEILING, config.maxTokenLifetimeSeconds)
    }
}
