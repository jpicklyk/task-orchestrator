package io.github.jpicklyk.mcptask.current.infrastructure.config

import com.nimbusds.jose.jwk.JWKSet
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant

/**
 * Snapshot of the cache state at the time the last key set was served.
 *
 * @param fromStaleCache True if the last [JwksKeySetProvider.getKeySet] call returned a
 *   key set from an expired cache entry (i.e., the refresh failed and stale fallback was used).
 * @param ageSeconds Age in seconds of the cached entry at the time it was served, or null if
 *   the key set was freshly fetched or no fetch has occurred yet.
 */
data class CacheState(
    val fromStaleCache: Boolean,
    val ageSeconds: Long?
)

/**
 * Thrown by [JwksKeySetProvider.getKeySetForIssuer] when the given issuer DID does not
 * match the configured [VerifierConfig.Jwks.didAllowlist] or [VerifierConfig.Jwks.didPattern].
 */
class IssuerNotTrustedException(
    issuer: String
) : Exception("issuer not in DID trust policy: $issuer")

/**
 * Bundles a fetched (or cached) [JWKSet] with the [CacheState] that describes how it was served.
 *
 * Returning [CacheState] alongside the keys eliminates the need for a separate
 * `getCacheState()` side-channel and prevents concurrent-call races where one call's
 * cache state could clobber another's shared instance field.
 *
 * @param keys The JWK set to use for verification.
 * @param cacheState The cache state at the moment this result was produced.
 */
data class JwksResult(
    val keys: JWKSet,
    val cacheState: CacheState
)

/**
 * Provides a [JwksResult] for JWT signature verification.
 *
 * Implementations are responsible for loading keys from a configured source (remote URI,
 * OIDC discovery document, or local file) and caching the result according to the
 * configured TTL.
 */
interface JwksKeySetProvider {
    /**
     * Returns the current [JwksResult]. Implementations may cache and refresh transparently.
     * The returned [JwksResult.cacheState] reflects whether this specific call was served
     * from a stale entry or freshly fetched.
     */
    suspend fun getKeySet(): JwksResult

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

    /**
     * Returns the [JwksResult] for a specific issuer DID. Used under DID-rooted trust.
     * Resolves the issuer via the configured DidResolverRegistry, projects the resulting
     * DID document into a JWKSet via DidDocumentJwksExtractor, and caches the result
     * per-issuer with TTL semantics matching getKeySet().
     *
     * @throws IssuerNotTrustedException if the issuer does not match the configured
     *   didAllowlist or didPattern.
     */
    suspend fun getKeySetForIssuer(issuer: String): JwksResult
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
    private val clock: Clock = Clock.systemUTC(),
    private val didResolverRegistry: DidResolverRegistry = DidResolverRegistry(listOf(DidWebResolver())),
    private val didDocumentJwksExtractor: DidDocumentJwksExtractor =
        DidDocumentJwksExtractor(strictRelationship = config.didStrictRelationship),
    /**
     * Optional [HttpClientEngine] for testing. When provided, the HTTP client will use this engine
     * instead of the default CIO engine, allowing tests to mock HTTP responses hermetically.
     */
    internal val httpClientEngineForTest: HttpClientEngine? = null
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

    /** Thread-safe lazy HTTP client — created only when a remote fetch is needed. */
    private val httpClientDelegate =
        lazy {
            val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
                followRedirects = false
                install(HttpTimeout) {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = SOCKET_TIMEOUT_MS
                }
            }
            if (httpClientEngineForTest != null) {
                HttpClient(httpClientEngineForTest, configure)
            } else {
                HttpClient(CIO) { configure(this) }
            }
        }
    private val httpClient: HttpClient by httpClientDelegate

    /** Per-issuer DID cache with LRU eviction at [MAX_DID_CACHE_ENTRIES] entries. */
    private val didCache =
        object : LinkedHashMap<String, Pair<JWKSet, Instant>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Pair<JWKSet, Instant>>?): Boolean {
                val shouldEvict = size > MAX_DID_CACHE_ENTRIES
                if (shouldEvict) {
                    logger.warn(
                        "DID cache LRU eviction at capacity {} — fleet churn or cache size too small",
                        MAX_DID_CACHE_ENTRIES
                    )
                }
                return shouldEvict
            }
        }
    private val didCacheLock = Mutex()

    override suspend fun getKeySet(): JwksResult {
        val now = clock.instant()

        // Fast path: return cached value if still valid.
        cached?.let { (set, fetchedAt) ->
            if (now.isBefore(fetchedAt.plusSeconds(config.cacheTtlSeconds))) {
                return JwksResult(set, FRESH_CACHE)
            }
        }

        return refreshLock.withLock {
            val nowInLock = clock.instant()

            // Double-check after acquiring the lock.
            cached?.let { (set, fetchedAt) ->
                if (nowInLock.isBefore(fetchedAt.plusSeconds(config.cacheTtlSeconds))) {
                    return@withLock JwksResult(set, FRESH_CACHE)
                }
            }

            // Capture the previous cache entry before attempting refresh.
            val previousCached = cached

            try {
                val fresh = fetchKeySet()
                cached = Pair(fresh, nowInLock)
                JwksResult(fresh, FRESH_CACHE)
            } catch (e: Exception) {
                if (config.staleOnError && previousCached != null) {
                    val (staleSet, fetchedAt) = previousCached
                    val ageSeconds = nowInLock.epochSecond - fetchedAt.epochSecond
                    logger.warn(
                        "JWKS refresh failed ({}); serving stale cache entry (age={}s). staleOnError=true",
                        e.message,
                        ageSeconds
                    )
                    JwksResult(staleSet, CacheState(fromStaleCache = true, ageSeconds = ageSeconds))
                } else {
                    throw e
                }
            }
        }
    }

    override suspend fun getKeySetForIssuer(issuer: String): JwksResult {
        if (!isIssuerTrusted(issuer)) {
            throw IssuerNotTrustedException(issuer)
        }
        val now = clock.instant()

        // Fast path: return cached value if still valid.
        didCacheLock.withLock {
            didCache[issuer]?.let { (set, fetchedAt) ->
                if (now.isBefore(fetchedAt.plusSeconds(config.cacheTtlSeconds))) {
                    return JwksResult(set, FRESH_CACHE)
                }
            }
        }

        // Slow path: resolve, extract, cache.
        return didCacheLock.withLock {
            val nowInLock = clock.instant()
            // Double-check inside lock.
            didCache[issuer]?.let { (set, fetchedAt) ->
                if (nowInLock.isBefore(fetchedAt.plusSeconds(config.cacheTtlSeconds))) {
                    return@withLock JwksResult(set, FRESH_CACHE)
                }
            }

            val previousCached = didCache[issuer]
            try {
                val doc = didResolverRegistry.resolve(issuer)
                val jwks = didDocumentJwksExtractor.extract(doc)
                didCache[issuer] = Pair(jwks, nowInLock)
                JwksResult(jwks, FRESH_CACHE)
            } catch (e: Exception) {
                if (config.staleOnError && previousCached != null) {
                    val (staleSet, fetchedAt) = previousCached
                    val ageSeconds = nowInLock.epochSecond - fetchedAt.epochSecond
                    logger.warn(
                        "DID resolution failed for {} ({}); serving stale cache (age={}s)",
                        issuer,
                        e.message,
                        ageSeconds
                    )
                    JwksResult(staleSet, CacheState(fromStaleCache = true, ageSeconds = ageSeconds))
                } else {
                    throw e
                }
            }
        }
    }

    private fun isIssuerTrusted(issuer: String): Boolean {
        if (config.didAllowlist.contains(issuer)) return true
        config.didPattern?.let { pattern ->
            if (matchesGlob(issuer, pattern)) return true
        }
        return false
    }

    // Glob match: "*" matches any single DID path segment (i.e., any sequence of characters
    // that does NOT contain ":"). This prevents sub-path hijack where, for example,
    // "did:web:host:agents:alice:fake" would wrongly match "did:web:host:agents:*" under an
    // unrestricted ".*" wildcard. No "**" escape-hatch is provided in v1.
    // Compile pattern to regex once per call (acceptable; patterns are typically short).
    internal fun matchesGlob(
        value: String,
        pattern: String
    ): Boolean {
        val regex =
            pattern
                .split("*")
                .joinToString("[^:]*") { Regex.escape(it) }
                .let { Regex("^$it$") }
        return regex.matches(value)
    }

    override fun getResolvedIssuer(): String? = resolvedIssuer

    override fun close() {
        if (httpClientDelegate.isInitialized()) {
            httpClient.close()
        }
        didResolverRegistry.closeAll()
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
        val response = httpClient.get(url)

        // Reject non-200 responses (including 3xx — we never follow redirects).
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("HTTP ${response.status.value} fetching JWKS from $url")
        }

        // Validate Content-Type: accept application/json or application/jwk-set+json.
        val contentType = response.contentType()
        if (contentType == null || !isAcceptableJwksContentType(contentType)) {
            throw IllegalStateException(
                "unexpected Content-Type '$contentType' from $url; expected application/json or application/jwk-set+json"
            )
        }

        // Read body with a hard cap to prevent unbounded memory consumption.
        val bytes = response.bodyAsChannel().readRemaining(MAX_BODY_BYTES.toLong() + 1).readByteArray()
        if (bytes.size > MAX_BODY_BYTES) {
            throw IllegalStateException(
                "response body from $url exceeds maximum allowed size of ${MAX_BODY_BYTES} bytes"
            )
        }
        return bytes.toString(Charsets.UTF_8)
    }

    /** Returns true if the given [ContentType] is acceptable for a JWKS endpoint response. */
    private fun isAcceptableJwksContentType(contentType: ContentType): Boolean =
        contentType.match(ContentType.Application.Json) ||
            contentType.match(ContentType("application", "jwk-set+json"))

    companion object {
        private val FRESH_CACHE = CacheState(fromStaleCache = false, ageSeconds = null)
        private const val MAX_DID_CACHE_ENTRIES = 256

        /** Maximum response body size accepted from a JWKS or OIDC discovery endpoint (1 MiB). */
        const val MAX_BODY_BYTES: Int = 1 * 1024 * 1024

        private const val REQUEST_TIMEOUT_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val SOCKET_TIMEOUT_MS = 5_000L
    }
}
