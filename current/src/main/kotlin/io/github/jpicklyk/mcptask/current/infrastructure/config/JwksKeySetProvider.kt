package io.github.jpicklyk.mcptask.current.infrastructure.config

import com.nimbusds.jose.jwk.JWKSet
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant

/**
 * Provides a [JWKSet] for JWT signature verification.
 *
 * Implementations are responsible for loading keys from a configured source (remote URI,
 * OIDC discovery document, or local file) and caching the result according to the
 * configured TTL.
 */
interface JwksKeySetProvider {
    /**
     * Returns the current [JWKSet]. Implementations may cache and refresh transparently.
     */
    suspend fun getKeySet(): JWKSet

    /**
     * Returns the issuer resolved via OIDC discovery, or null if discovery was not configured
     * or has not yet run. Explicit [VerifierConfig.Jwks.issuer] overrides this value at the
     * verifier level — this method only returns what was discovered.
     */
    fun getResolvedIssuer(): String?

    /**
     * Releases any underlying resources (e.g., HTTP client connections).
     */
    fun close()
}

/**
 * Default [JwksKeySetProvider] backed by [VerifierConfig.Jwks].
 *
 * Key-loading strategy (all sources are merged if multiple are configured):
 * 1. **OIDC discovery** — fetches `oidcDiscovery` URL, extracts `jwks_uri` (and optionally
 *    `issuer`) from the response JSON, then fetches the JWKS from that URI.
 * 2. **Direct URI** — fetches `jwksUri` directly and parses the response as a JWKS.
 * 3. **Local file** — reads `jwksPath` from the filesystem (resolved relative to
 *    `AGENT_CONFIG_DIR` or `user.dir`) and parses it as a JWKS.
 *
 * Results are cached for [VerifierConfig.Jwks.cacheTtlSeconds] seconds.
 */
class DefaultJwksKeySetProvider(
    private val config: VerifierConfig.Jwks,
    private val clock: Clock = Clock.systemUTC()
) : JwksKeySetProvider {
    private val logger = LoggerFactory.getLogger(DefaultJwksKeySetProvider::class.java)

    @Volatile
    private var cached: Pair<JWKSet, Instant>? = null

    private val refreshLock = Mutex()

    /** URI discovered via OIDC, cached after first successful discovery call. */
    @Volatile
    private var resolvedJwksUri: String? = null

    /** Issuer discovered via OIDC (only populated when discovery is used). */
    @Volatile
    private var resolvedIssuer: String? = null

    /** Lazy HTTP client — created only when a remote fetch is needed. */
    private var httpClient: HttpClient? = null

    override suspend fun getKeySet(): JWKSet {
        // Fast path: return cached value if still valid.
        cached?.let { (set, fetchedAt) ->
            if (clock.instant().isBefore(fetchedAt.plusSeconds(config.cacheTtlSeconds))) {
                return set
            }
        }

        return refreshLock.withLock {
            // Double-check after acquiring the lock.
            cached?.let { (set, fetchedAt) ->
                if (clock.instant().isBefore(fetchedAt.plusSeconds(config.cacheTtlSeconds))) {
                    return@withLock set
                }
            }

            val fresh = fetchKeySet()
            cached = Pair(fresh, clock.instant())
            fresh
        }
    }

    override fun getResolvedIssuer(): String? = resolvedIssuer

    override fun close() {
        httpClient?.close()
        httpClient = null
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private suspend fun fetchKeySet(): JWKSet {
        // Step 1: Resolve OIDC discovery if configured and not yet resolved.
        if (config.oidcDiscovery != null && resolvedJwksUri == null) {
            try {
                runOidcDiscovery(config.oidcDiscovery)
            } catch (e: Exception) {
                logger.warn("OIDC discovery failed for '{}': {}", config.oidcDiscovery, e.message)
            }
        }

        // Explicit config values override discovered values.
        val effectiveJwksUri = config.jwksUri ?: resolvedJwksUri

        val uriKeys: JWKSet? =
            effectiveJwksUri?.let { uri ->
                try {
                    val body = httpGet(uri)
                    JWKSet.parse(body)
                } catch (e: Exception) {
                    logger.warn("Failed to fetch JWKS from URI '{}': {}", uri, e.message)
                    throw e
                }
            }

        val pathKeys: JWKSet? =
            config.jwksPath?.let { relPath ->
                try {
                    val basePath =
                        Paths.get(
                            System.getenv("AGENT_CONFIG_DIR") ?: System.getProperty("user.dir")
                        )
                    val resolved = basePath.resolve(relPath)
                    val content = resolved.toFile().readText()
                    JWKSet.parse(content)
                } catch (e: Exception) {
                    logger.warn("Failed to load JWKS from path '{}': {}", relPath, e.message)
                    throw e
                }
            }

        return when {
            uriKeys != null && pathKeys != null ->
                JWKSet(uriKeys.keys + pathKeys.keys)
            uriKeys != null -> uriKeys
            pathKeys != null -> pathKeys
            else -> throw IllegalStateException(
                "No JWKS source produced a key set — check oidcDiscovery, jwksUri, and jwksPath configuration"
            )
        }
    }

    private suspend fun runOidcDiscovery(discoveryUrl: String) {
        val body = httpGet(discoveryUrl)
        val json = Json.parseToJsonElement(body).jsonObject
        val discovered = json["jwks_uri"]?.jsonPrimitive?.content
        val issuer = json["issuer"]?.jsonPrimitive?.content
        if (discovered != null) {
            resolvedJwksUri = discovered
            logger.debug("OIDC discovery resolved jwks_uri={}", discovered)
        }
        if (issuer != null) {
            resolvedIssuer = issuer
            logger.debug("OIDC discovery resolved issuer={}", issuer)
        }
    }

    private suspend fun httpGet(url: String): String {
        val client = httpClient ?: HttpClient(CIO).also { httpClient = it }
        return client.get(url).bodyAsText()
    }
}
