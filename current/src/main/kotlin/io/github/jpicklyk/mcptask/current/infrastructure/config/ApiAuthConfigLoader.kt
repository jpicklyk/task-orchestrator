package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import org.slf4j.LoggerFactory
import java.net.MalformedURLException
import java.net.URL

/**
 * Environment-variable-driven loader for [ApiAuthConfig].
 *
 * Reads the following environment variables (see §9 of [plans/api-layer.md]):
 *
 * | Variable | Required when | Default | Description |
 * |----------|--------------|---------|-------------|
 * | `API_ENABLED` | optional | `false` | Master API switch. Unset or `false` → [ApiAuthConfig.Disabled]. `true` opts in (then `API_AUTH_MODE` is required). |
 * | `API_AUTH_MODE` | `API_ENABLED=true` | — | `bearer` or `jwks`. Also accepts `none` when `API_ALLOW_UNAUTHENTICATED=true` (see below) → [ApiAuthConfig.Unauthenticated]. Unset / invalid / `none` without the confirm flag → startup failure. |
 * | `API_ALLOW_UNAUTHENTICATED` | optional | `false` | Confirm flag required alongside `API_AUTH_MODE=none` to opt into [ApiAuthConfig.Unauthenticated]. Parsed like `API_ENABLED`. Ignored when `API_AUTH_MODE` is not `none`. |
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
            logger.info("API is disabled (API_ENABLED unset or false)")
            return ApiAuthConfig.Disabled
        }

        val allowUnauthenticated = resolveApiAllowUnauthenticated()
        val authMode = resolveAuthMode(allowUnauthenticated)
        return when (authMode) {
            "bearer" -> loadBearer()
            "jwks" -> loadJwks()
            "none" -> {
                logger.warn(
                    "API_AUTH_MODE=none with API_ALLOW_UNAUTHENTICATED=true: the REST API will run " +
                        "UNAUTHENTICATED. This must only be used on a loopback-bound local server.",
                )
                ApiAuthConfig.Unauthenticated
            }
            else -> throw IllegalArgumentException(
                "API_AUTH_MODE='$authMode' is not supported. Valid values: bearer, jwks.",
            )
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun resolveApiEnabled(): Boolean {
        // Default-OFF: when API_ENABLED is unset, the REST API is disabled. This keeps the stock
        // container (stdio, no API_* vars) booting cleanly — an enabled API hard-requires
        // API_AUTH_MODE and would otherwise crash a default deployment at startup. The API is an
        // opt-in layer the operator turns on with API_ENABLED=true + API_AUTH_MODE.
        val raw = envResolver("API_ENABLED") ?: return false
        return when (raw.lowercase().trim()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> throw IllegalArgumentException(
                "API_ENABLED has invalid value '$raw'. Expected: true or false.",
            )
        }
    }

    private fun resolveApiAllowUnauthenticated(): Boolean {
        // Confirm flag for API_AUTH_MODE=none — parsed exactly like resolveApiEnabled. Two
        // independent keys must both be set to reach Unauthenticated: this key alone (with any
        // other API_AUTH_MODE) is a no-op, and API_AUTH_MODE=none alone (without this key) still
        // fails fast in resolveAuthMode below.
        val raw = envResolver("API_ALLOW_UNAUTHENTICATED") ?: return false
        return when (raw.lowercase().trim()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> throw IllegalArgumentException(
                "API_ALLOW_UNAUTHENTICATED has invalid value '$raw'. Expected: true or false.",
            )
        }
    }

    private fun resolveAuthMode(allowUnauthenticated: Boolean): String {
        val raw = envResolver("API_AUTH_MODE")
        if (raw.isNullOrBlank()) {
            throw IllegalArgumentException(
                "API_AUTH_MODE is required when API_ENABLED=true. " +
                    "Valid values: bearer, jwks (or none, with API_ALLOW_UNAUTHENTICATED=true).",
            )
        }
        val normalised = raw.lowercase().trim()
        if (normalised == "none") {
            if (!allowUnauthenticated) {
                throw IllegalArgumentException(
                    "API_AUTH_MODE=none requires API_ALLOW_UNAUTHENTICATED=true to confirm this opt-in " +
                        "(the REST API would otherwise run unauthenticated). Valid values: bearer, jwks, " +
                        "or none with API_ALLOW_UNAUTHENTICATED=true.",
                )
            }
            return normalised
        }
        if (normalised !in listOf("bearer", "jwks")) {
            throw IllegalArgumentException(
                "API_AUTH_MODE='$raw' is not valid. Valid values: bearer, jwks, none " +
                    "(requires API_ALLOW_UNAUTHENTICATED=true).",
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

        // Fail-fast URL validation — reject malformed URLs at startup rather than at first JWKS fetch
        try {
            URL(url)
        } catch (e: MalformedURLException) {
            throw IllegalArgumentException(
                "API_JWKS_URL '$url' is not a valid URL: ${e.message}. " +
                    "Provide a fully-qualified URL, e.g. 'https://auth.example.com/.well-known/jwks.json'.",
            )
        }

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
