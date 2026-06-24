package io.github.jpicklyk.mcptask.current.interfaces.api.v1.cors

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.ktor.http.HttpMethod
import io.ktor.server.plugins.cors.CORSConfig

/**
 * Configures the CORS plugin from environment variables.
 *
 * Environment variables:
 * - `CORS_ALLOWED_ORIGINS` — comma-separated list of allowed origins (e.g. `https://app.example.com`).
 *   Empty / unset = no cross-origin requests allowed (all preflight returns 403).
 * - `CORS_ALLOWED_METHODS` — comma-separated HTTP methods. Default: GET, POST, PATCH, PUT, DELETE, OPTIONS.
 * - `CORS_ALLOWED_HEADERS` — comma-separated request headers. Default: Authorization, Content-Type, If-Match.
 * - `CORS_EXPOSE_HEADERS` — comma-separated response headers exposed to JS. Default: ETag, Last-Event-ID.
 * - `CORS_MAX_AGE_SECONDS` — preflight cache duration in seconds. Default: 3600.
 *
 * Design decisions:
 * - `anyHost()` is NEVER called — empty allowlist means no cross-origin access.
 * - Credentials (`Access-Control-Allow-Credentials`) are NOT exposed — bearer tokens go in the
 *   Authorization header, not cookies, so cookie-based credential sharing is unnecessary.
 * - Origins are stripped of their scheme prefix (`https://`, `http://`) before being passed to
 *   [allowHost], which takes the host component and a `schemes` list separately.
 */
fun CORSConfig.configureCors(appConfig: AppConfig = AppConfig.fromEnv()) {
    val rawOrigins = appConfig.corsAllowedOrigins
    val methods = appConfig.corsAllowedMethods
    val headers = appConfig.corsAllowedHeaders
    val exposeHeaders = appConfig.corsExposeHeaders
    val maxAge = appConfig.corsMaxAgeSeconds

    // Register each allowed origin; strip scheme prefix and pass schemes explicitly.
    rawOrigins.forEach { origin ->
        val (host, schemes) = parseOrigin(origin)
        allowHost(host, schemes = schemes)
    }

    // HTTP methods — parse each as HttpMethod
    methods.forEach { method -> allowMethod(HttpMethod.parse(method)) }

    // Request headers the browser is allowed to send
    headers.forEach { header -> allowHeader(header) }

    // Response headers the browser JS is allowed to read
    exposeHeaders.forEach { header -> exposeHeader(header) }

    maxAgeInSeconds = maxAge

    // anyHost() intentionally omitted — empty allowlist blocks all cross-origin requests.
    // Access-Control-Allow-Credentials intentionally omitted — bearer in header, not cookies.
}

/**
 * Parses an origin string like `https://app.example.com` or `http://localhost:3000`
 * into a (host, schemes) pair for [CORSConfig.allowHost].
 *
 * The Ktor [CORSConfig.allowHost] API takes the host portion (no scheme) and a separate
 * [schemes] list. We split the origin at `://` and infer the scheme, defaulting to both
 * http and https when the origin has no scheme prefix.
 */
private fun parseOrigin(origin: String): Pair<String, List<String>> =
    when {
        origin.startsWith("https://") -> Pair(origin.removePrefix("https://"), listOf("https"))
        origin.startsWith("http://") -> Pair(origin.removePrefix("http://"), listOf("http"))
        else -> Pair(origin, listOf("http", "https")) // bare host — allow both schemes
    }
