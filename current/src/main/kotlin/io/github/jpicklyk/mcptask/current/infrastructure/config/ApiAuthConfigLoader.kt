package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import org.slf4j.LoggerFactory
import java.net.MalformedURLException
import java.net.URL

/** Confirm flag permitting plaintext http for [ApiAuthConfigLoader]'s `API_JWKS_URL`, restricted to a loopback host. */
private const val ALLOW_INSECURE_JWKS_URL_ENV = "API_JWKS_ALLOW_INSECURE_URL"

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
 * | `API_JWKS_URL` | jwks mode | — | JWKS endpoint URL. Required; startup fails if absent. Must be `https` — plaintext `http` is rejected unless [ALLOW_INSECURE_JWKS_URL_ENV] parses true (via [EnvBoolean.require]) AND the host is a literal loopback address (`localhost`, `127.x.x.x`, `::1`). |
 * | `API_JWKS_ALLOW_INSECURE_URL` | optional | `false` | Confirm flag permitting plaintext `http` for `API_JWKS_URL`, restricted to a loopback host (see above). Parsed like `API_ENABLED`. |
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
        return EnvBoolean.require("API_ENABLED", envResolver("API_ENABLED"), default = false)
    }

    private fun resolveApiAllowUnauthenticated(): Boolean {
        // Confirm flag for API_AUTH_MODE=none — parsed exactly like resolveApiEnabled. Two
        // independent keys must both be set to reach Unauthenticated: this key alone (with any
        // other API_AUTH_MODE) is a no-op, and API_AUTH_MODE=none alone (without this key) still
        // fails fast in resolveAuthMode below.
        return EnvBoolean.require("API_ALLOW_UNAUTHENTICATED", envResolver("API_ALLOW_UNAUTHENTICATED"), default = false)
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
        val parsedUrl =
            try {
                URL(url)
            } catch (e: MalformedURLException) {
                throw IllegalArgumentException(
                    "API_JWKS_URL '$url' is not a valid URL: ${e.message}. " +
                        "Provide a fully-qualified URL, e.g. 'https://auth.example.com/.well-known/jwks.json'.",
                )
            }
        validateJwksUrlScheme(parsedUrl, url)

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

    /**
     * Enforces the `API_JWKS_URL` scheme contract: `https` is always accepted; plaintext `http`
     * is accepted only when [ALLOW_INSECURE_JWKS_URL_ENV] parses true AND [url]'s host is a
     * literal loopback address. Any other scheme (`file`, `ftp`, `jar`, ...) is always rejected.
     *
     * The insecure-opt-in flag is resolved unconditionally (even for an `https` URL) so that a
     * malformed flag value fails startup fast rather than being silently ignored because it
     * happened not to be needed.
     */
    private fun validateJwksUrlScheme(
        url: URL,
        raw: String,
    ) {
        val allowInsecure =
            EnvBoolean.require(
                ALLOW_INSECURE_JWKS_URL_ENV,
                envResolver(ALLOW_INSECURE_JWKS_URL_ENV),
                default = false,
            )

        val scheme = url.protocol?.lowercase()
        if (scheme == "https") {
            return
        }

        if (scheme == "http" && allowInsecure) {
            if (isLoopbackHost(url.host)) {
                logger.warn(
                    "API_JWKS_URL '{}' uses plaintext http, permitted because {}=true and host '{}' is a " +
                        "loopback address. This must only be used for local development.",
                    raw,
                    ALLOW_INSECURE_JWKS_URL_ENV,
                    url.host,
                )
                return
            }
            throw IllegalArgumentException(
                "API_JWKS_URL '$raw' uses scheme 'http' with $ALLOW_INSECURE_JWKS_URL_ENV=true, but host " +
                    "'${url.host}' is not a loopback address. Plaintext http is permitted only for a loopback " +
                    "host (localhost, 127.x.x.x, ::1).",
            )
        }

        throw IllegalArgumentException(
            "API_JWKS_URL '$raw' uses scheme '$scheme'. JWKS key material must be fetched over https; " +
                "plaintext http is permitted only for a loopback host with $ALLOW_INSECURE_JWKS_URL_ENV=true.",
        )
    }

    /**
     * True when [host] is a literal loopback address: `localhost` (case-insensitive), an IPv4
     * literal beginning `127.` with four valid octets, or `::1` (with or without the `[...]`
     * literal-IPv6 brackets [URL.getHost] may retain). No DNS resolution is performed — a host
     * that merely resolves to loopback (or is crafted to look like one, e.g.
     * `127.0.0.1.evil.com` or `localhost.evil.com`) is rejected.
     */
    private fun isLoopbackHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.trim().lowercase().removeSurrounding("[", "]")
        if (normalized == "localhost" || normalized == "::1") return true
        if (normalized.startsWith("127.")) {
            val octets = normalized.split(".")
            return octets.size == 4 && octets.all { octet -> octet.toIntOrNull()?.let { it in 0..255 } == true }
        }
        return false
    }
}
