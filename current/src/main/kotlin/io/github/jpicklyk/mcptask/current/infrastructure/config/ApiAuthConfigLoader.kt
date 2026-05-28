package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import org.slf4j.LoggerFactory

/**
 * Environment-variable-driven loader for [ApiAuthConfig].
 *
 * Reads the following environment variables (see §9 of [plans/api-layer.md]):
 *
 * | Variable | Required when | Default | Description |
 * |----------|--------------|---------|-------------|
 * | `API_ENABLED` | always | `true` | Master API switch. `false` → [ApiAuthConfig.Disabled]. |
 * | `API_AUTH_MODE` | `API_ENABLED=true` | — | `bearer` or `jwks`. Unset / `none` / invalid → startup failure. |
 * | `API_TOKENS_PATH` | bearer mode | `/run/secrets/api-tokens.yaml` | Path to the bearer token secret file. |
 * | `API_JWKS_URL` | jwks mode | — | JWKS endpoint URL. Required; startup fails if absent. |
 * | `API_JWKS_ISSUER` | jwks mode | — | Expected `iss` claim. Required. |
 * | `API_JWKS_AUDIENCE` | jwks mode | — | Expected `aud` claim. Required. |
 * | `API_JWKS_ALGORITHMS` | jwks mode | — | Comma-separated algorithm allowlist (e.g. `RS256,EdDSA`). Required. |
 * | `API_JWKS_CACHE_TTL_SECONDS` | jwks mode | `300` | JWKS cache TTL in seconds. |
 *
 * **Fail-fast guarantee:** any misconfiguration throws [IllegalArgumentException] or
 * [IllegalStateException] with a descriptive message.  The caller (main entrypoint) must
 * treat this as a fatal startup failure and exit non-zero.
 *
 * @param envResolver Injectable resolver for environment variables.  Defaults to
 *   [System::getenv].  Tests inject a fake to avoid mutating the JVM environment.
 */
class ApiAuthConfigLoader(
    private val envResolver: (String) -> String? = System::getenv,
) {
    private val logger = LoggerFactory.getLogger(ApiAuthConfigLoader::class.java)

    /**
     * Loads and validates the API authentication configuration.
     *
     * @throws IllegalArgumentException if any required variable is missing, invalid, or
     *   inconsistent (e.g., API_AUTH_MODE=none, bearer mode with missing token file, etc.).
     */
    fun load(): ApiAuthConfig {
        val apiEnabled = resolveApiEnabled()

        if (!apiEnabled) {
            logger.info("API is disabled (API_ENABLED=false)")
            return ApiAuthConfig.Disabled
        }

        val authMode = resolveAuthMode()
        return when (authMode) {
            "bearer" -> loadBearer()
            "jwks" -> loadJwks()
            else -> throw IllegalArgumentException(
                "API_AUTH_MODE='$authMode' is not supported. Valid values: bearer, jwks.",
            )
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun resolveApiEnabled(): Boolean {
        val raw = envResolver("API_ENABLED") ?: return true
        return when (raw.lowercase().trim()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> throw IllegalArgumentException(
                "API_ENABLED has invalid value '$raw'. Expected: true or false.",
            )
        }
    }

    private fun resolveAuthMode(): String {
        val raw = envResolver("API_AUTH_MODE")
        if (raw.isNullOrBlank()) {
            throw IllegalArgumentException(
                "API_AUTH_MODE is required when API_ENABLED=true. " +
                    "Valid values: bearer, jwks. " +
                    "There is no 'none' mode — the API always requires authentication.",
            )
        }
        val normalised = raw.lowercase().trim()
        if (normalised == "none") {
            throw IllegalArgumentException(
                "API_AUTH_MODE=none is not allowed. The API always requires authentication. " +
                    "Valid values: bearer, jwks.",
            )
        }
        if (normalised !in listOf("bearer", "jwks")) {
            throw IllegalArgumentException(
                "API_AUTH_MODE='$raw' is not valid. Valid values: bearer, jwks.",
            )
        }
        return normalised
    }

    private fun loadBearer(): ApiAuthConfig.Bearer {
        val tokensPath =
            envResolver("API_TOKENS_PATH")?.trim()?.takeIf { it.isNotBlank() }
                ?: "/run/secrets/api-tokens.yaml"

        logger.info("Loading bearer tokens from '{}'", tokensPath)

        val store = BearerTokenStore(filePath = tokensPath)
        // load() performs full fail-fast validation and throws on any misconfig
        return store.load()
    }

    private fun loadJwks(): ApiAuthConfig.Jwks {
        val url =
            envResolver("API_JWKS_URL")?.trim()?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException(
                    "API_JWKS_URL is required when API_AUTH_MODE=jwks.",
                )

        val issuer =
            envResolver("API_JWKS_ISSUER")?.trim()?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException(
                    "API_JWKS_ISSUER is required when API_AUTH_MODE=jwks.",
                )

        val audience =
            envResolver("API_JWKS_AUDIENCE")?.trim()?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException(
                    "API_JWKS_AUDIENCE is required when API_AUTH_MODE=jwks.",
                )

        val algorithmsRaw =
            envResolver("API_JWKS_ALGORITHMS")?.trim()?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException(
                    "API_JWKS_ALGORITHMS is required when API_AUTH_MODE=jwks. " +
                        "Provide a comma-separated list of algorithms, e.g. 'RS256,EdDSA'.",
                )
        val algorithms =
            algorithmsRaw
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        if (algorithms.isEmpty()) {
            throw IllegalArgumentException(
                "API_JWKS_ALGORITHMS is empty after parsing '$algorithmsRaw'. " +
                    "At least one algorithm is required.",
            )
        }

        val cacheTtlSeconds =
            envResolver("API_JWKS_CACHE_TTL_SECONDS")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.toLongOrNull()
                ?: 300L

        if (cacheTtlSeconds <= 0) {
            throw IllegalArgumentException(
                "API_JWKS_CACHE_TTL_SECONDS must be a positive integer, got '$cacheTtlSeconds'.",
            )
        }

        logger.info(
            "JWKS auth configured: url={}, issuer={}, audience={}, algorithms={}, cacheTtlSeconds={}",
            url,
            issuer,
            audience,
            algorithms,
            cacheTtlSeconds,
        )

        return ApiAuthConfig.Jwks(
            url = url,
            issuer = issuer,
            audience = audience,
            algorithms = algorithms,
            cacheTtlSeconds = cacheTtlSeconds,
        )
    }
}
