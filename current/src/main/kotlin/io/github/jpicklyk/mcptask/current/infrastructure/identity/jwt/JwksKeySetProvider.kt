package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import com.nimbusds.jose.jwk.JWKSet
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.infrastructure.identity.EgressGuard
import io.github.jpicklyk.mcptask.current.infrastructure.identity.requireHttpsOrLoopbackKeySource
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Snapshot of the cache state at the time a key set was served.
 *
 * @param fromStaleCache True if the key set was served from an entry whose refresh failed (stale
 *   fallback, bounded by [KeySourceConfig.maxStaleSeconds]).
 * @param ageSeconds Age in seconds of the served entry, measured from its real fetch time, or null
 *   when the key set was fresh.
 */
data class CacheState(
    val fromStaleCache: Boolean,
    val ageSeconds: Long?
)

/**
 * Thrown by [JwksKeySetProvider.getKeySet] when a
 * refresh failed and the last good key set is older than [KeySourceConfig.maxStaleSeconds], so it
 * may no longer be served (a revoked key must not stay trusted forever during an outage).
 */
class KeySetUnavailableException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

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
     *
     * @throws KeySetUnavailableException when a refresh failed and the cached entry is too old.
     */
    suspend fun getKeySet(): JwksResult

    /**
     * Returns the issuer resolved via OIDC discovery, or null if discovery was not configured
     * or has not yet run. An explicit verifier issuer overrides this value at the verifier level --
     * this method only returns what was discovered.
     */
    fun getResolvedIssuer(): String?

    /**
     * Releases any underlying resources (e.g., HTTP client connections).
     */
    fun close()

    /**
     * Forces one refresh because a token named a `kid` the current key set does not contain (a
     * rotated-in key).
     *
     * Returns the refreshed result, or null when the refresh was declined (rate-limited, or the
     * source is in failure backoff) or failed. Never throws for a fetch failure.
     */
    suspend fun refreshForUnknownKid(kid: String): JwksResult?
}

/**
 * Default [JwksKeySetProvider] backed by a [KeySourceConfig].
 *
 * Key-loading strategy (all sources are merged if multiple are configured):
 * 1. **OIDC discovery** -- fetches `oidcDiscovery` URL, extracts `jwks_uri` (and optionally
 *    `issuer`) from the response JSON, then fetches the JWKS from that URI.
 * 2. **Direct URI** -- fetches `jwksUri` directly and parses the response as a JWKS.
 * 3. **Local file** -- reads `jwksPath` from the filesystem (resolved relative to
 *    `AGENT_CONFIG_DIR` or `user.dir`) and parses it as a JWKS.
 *
 * Caching: a good key set is served for
 * [KeySourceConfig.cacheTtlSeconds]. When a refresh fails and [KeySourceConfig.staleOnError] is set,
 * the last good set is served stale while its age (from its real fetch time) is at most
 * [KeySourceConfig.maxStaleSeconds], and the next refresh is deferred by a backoff of 15 s doubling
 * per consecutive failure up to 300 s, reset by a success -- so callers between attempts take the
 * fast path instead of each re-fetching. Past max-stale, [KeySetUnavailableException] is thrown.
 * [refreshForUnknownKid] forces at most one refresh per 30 s per provider.
 *
 * Every remote fetch passes [egressGuard] first (JWKS, OIDC discovery document and the jwks_uri it
 * names).
 */
class DefaultJwksKeySetProvider(
    private val config: KeySourceConfig,
    /** The directory `jwks_path` resolves against (the agent config base directory, built once by the composition root). */
    private val configBaseDir: Path,
    private val clock: Clock = Clock.systemUTC(),
    /** Egress guard applied before every remote fetch; loopback admitted only with [KeySourceConfig.allowInsecureUrl]. */
    private val egressGuard: EgressGuard = EgressGuard.forConfig(config.allowInsecureUrl),
    /**
     * Optional [HttpClientEngine] for testing. When provided, the HTTP client will use this engine
     * instead of the default CIO engine, allowing tests to mock HTTP responses hermetically.
     */
    internal val httpClientEngineForTest: HttpClientEngine? = null
) : JwksKeySetProvider {
    private val logger = LoggerFactory.getLogger(DefaultJwksKeySetProvider::class.java)

    /**
     * A cached key set. [fetchedAt] is the real fetch time (all age / max-stale arithmetic uses it);
     * [nextRefreshAt] is when the next refresh may be attempted (TTL after a success, the backoff
     * after a failure); [failures] counts consecutive refresh failures since the last success.
     */
    private data class CachedKeySet(
        val keys: JWKSet,
        val fetchedAt: Instant,
        val nextRefreshAt: Instant,
        val failures: Int = 0
    )

    @Volatile
    private var cached: CachedKeySet? = null

    private val refreshLock = Mutex()

    /** Last forced unknown-kid refresh (one per [UNKNOWN_KID_REFRESH_INTERVAL_SECONDS]). */
    private val lastForcedRefresh = AtomicReference<Instant?>(null)

    /** URI discovered via OIDC, cached after first successful discovery call. */
    @Volatile
    private var resolvedJwksUri: String? = null

    /** Issuer discovered via OIDC (only populated when discovery is used). */
    @Volatile
    private var resolvedIssuer: String? = null

    /** Thread-safe lazy HTTP client -- created only when a remote fetch is needed. */
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

    override suspend fun getKeySet(): JwksResult {
        // Fast path: no refresh due yet (fresh entry, or a stale entry inside its failure backoff).
        cached?.let { entry ->
            val now = clock.instant()
            if (now.isBefore(entry.nextRefreshAt)) return serve(entry, now, STATIC_LABEL)
        }

        return refreshLock.withLock {
            val nowInLock = clock.instant()

            // Double-check after acquiring the lock (single-flight: one refresh per due window).
            val previous = cached
            if (previous != null && nowInLock.isBefore(previous.nextRefreshAt)) {
                return@withLock serve(previous, nowInLock, STATIC_LABEL)
            }

            try {
                val fresh = fetchKeySet()
                cached = freshEntry(fresh, nowInLock)
                JwksResult(fresh, FRESH_CACHE)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                val backedOff = recordFailure(previous, nowInLock) ?: throw e
                cached = backedOff
                serveStaleAfterFailure(backedOff, nowInLock, STATIC_LABEL, e)
            }
        }
    }

    override suspend fun refreshForUnknownKid(kid: String): JwksResult? {
        val now = clock.instant()
        val previousEntry = cached
        // Subject to the same backoff: during an outage an unknown kid must not force extra fetches.
        if (previousEntry != null && previousEntry.failures > 0 && now.isBefore(previousEntry.nextRefreshAt)) {
            logger.debug("Unknown kid '{}': key source in failure backoff; forced refresh declined", kid)
            return null
        }
        val last = lastForcedRefresh.get()
        if (last != null && now.isBefore(last.plusSeconds(UNKNOWN_KID_REFRESH_INTERVAL_SECONDS))) {
            logger.debug("Unknown kid '{}': forced refresh rate-limited", kid)
            return null
        }
        if (!lastForcedRefresh.compareAndSet(last, now)) return null

        return try {
            forceRefreshStatic(now)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("Forced JWKS refresh for an unknown kid failed: {}", e.message)
            null
        }
    }

    private suspend fun forceRefreshStatic(now: Instant): JwksResult =
        refreshLock.withLock {
            val previous = cached
            try {
                val fresh = fetchKeySet()
                cached = freshEntry(fresh, now)
                JwksResult(fresh, FRESH_CACHE)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                recordFailure(previous, now)?.let { cached = it }
                throw e
            }
        }

    private fun freshEntry(
        keys: JWKSet,
        now: Instant
    ) = CachedKeySet(keys = keys, fetchedAt = now, nextRefreshAt = now.plusSeconds(config.cacheTtlSeconds))

    /**
     * The entry to keep after a failed refresh: the previous good set with its real [CachedKeySet.fetchedAt]
     * and the next attempt deferred by the backoff. Null when there is nothing usable to keep
     * (no previous entry, or stale serving disabled) -- the caller then rethrows the failure.
     */
    private fun recordFailure(
        previous: CachedKeySet?,
        now: Instant
    ): CachedKeySet? {
        if (previous == null || !config.staleOnError) return null
        val failures = previous.failures + 1
        return previous.copy(nextRefreshAt = now.plusSeconds(backoffSeconds(failures)), failures = failures)
    }

    /** Serves an entry on the fast path: fresh when no failure is recorded, else bounded-stale. */
    private fun serve(
        entry: CachedKeySet,
        now: Instant,
        source: String
    ): JwksResult {
        if (entry.failures == 0) return JwksResult(entry.keys, FRESH_CACHE)
        val ageSeconds = ageSeconds(entry, now)
        if (ageSeconds > config.maxStaleSeconds) {
            throw KeySetUnavailableException(
                "key set for $source is unavailable: last refresh failed and the cached key set is " +
                    "${ageSeconds}s old, past max_stale_seconds=${config.maxStaleSeconds}"
            )
        }
        return JwksResult(entry.keys, CacheState(fromStaleCache = true, ageSeconds = ageSeconds))
    }

    private fun serveStaleAfterFailure(
        entry: CachedKeySet,
        now: Instant,
        source: String,
        failure: Exception
    ): JwksResult {
        val ageSeconds = ageSeconds(entry, now)
        if (ageSeconds > config.maxStaleSeconds) {
            throw KeySetUnavailableException(
                "key set for $source is unavailable: refresh failed (${failure.message}) and the cached key " +
                    "set is ${ageSeconds}s old, past max_stale_seconds=${config.maxStaleSeconds}",
                failure
            )
        }
        logger.warn(
            "Key set refresh failed for {} ({}); serving stale cache entry (age={}s, next attempt in {}s)",
            source,
            failure.message,
            ageSeconds,
            entry.nextRefreshAt.epochSecond - now.epochSecond
        )
        return JwksResult(entry.keys, CacheState(fromStaleCache = true, ageSeconds = ageSeconds))
    }

    /** Age from the real fetch time -- never from [CachedKeySet.nextRefreshAt]. */
    private fun ageSeconds(
        entry: CachedKeySet,
        now: Instant
    ): Long = now.epochSecond - entry.fetchedAt.epochSecond

    override fun getResolvedIssuer(): String? = resolvedIssuer

    override fun close() {
        if (httpClientDelegate.isInitialized()) {
            httpClient.close()
        }
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
                e.rethrowIfCancellation()
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
                    e.rethrowIfCancellation()
                    logger.warn("Failed to fetch JWKS from URI '{}': {}", uri, e.message)
                    throw e
                }
            }

        val pathKeys: JWKSet? =
            config.jwksPath?.let { relPath ->
                try {
                    val resolved = configBaseDir.resolve(relPath)
                    val content = resolved.toFile().readText()
                    JWKSet.parse(content)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
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
            // Validate BEFORE assigning resolvedJwksUri/resolvedIssuer: a discovery document
            // (even one reached over https) could point jwks_uri at a plaintext http endpoint,
            // which would let a network attacker substitute keys during the subsequent key
            // fetch. A rejection here throws, is caught by fetchKeySet's caller (logged as a
            // WARN), and leaves no discovered source -- getKeySet then has nothing to fetch and
            // the verifier reports UNAVAILABLE, same as any other discovery failure. The
            // discovered URI's host is egress-guarded when it is fetched (httpGet).
            validateDiscoveredJwksUri(discovered)
            resolvedJwksUri = discovered
            logger.debug("OIDC discovery resolved jwks_uri={}", discovered)
        }
        if (issuer != null) {
            resolvedIssuer = issuer
            logger.debug("OIDC discovery resolved issuer={}", issuer)
        }
    }

    /**
     * Enforces the same https-or-loopback rule as
     * `requireHttpsOrLoopbackKeySource` on the `jwks_uri` discovered via
     * OIDC discovery, using [KeySourceConfig.allowInsecureUrl]. The statically-configured
     * `oidcDiscovery`/`jwksUri` values are validated once, at config-load time; this covers the
     * URI the discovery DOCUMENT names, which is only known at fetch time and could differ from
     * (or be redirected away from) the discovery URL's own scheme.
     */
    private fun validateDiscoveredJwksUri(discoveredUri: String) =
        requireHttpsOrLoopbackKeySource(discoveredUri, "OIDC-discovered jwks_uri", config.allowInsecureUrl, logger)

    private suspend fun httpGet(url: String): String {
        // Egress guard before every fetch (resolution blocks, so it runs on the IO dispatcher).
        withContext(Dispatchers.IO) { egressGuard.check(url) }
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
        private const val STATIC_LABEL = "the static JWKS source"

        /** First retry delay after a failed refresh; doubles per consecutive failure. */
        const val BACKOFF_INITIAL_SECONDS: Long = 15

        /** Cap on the retry delay after consecutive failed refreshes. */
        const val BACKOFF_MAX_SECONDS: Long = 300

        /** Minimum spacing between forced unknown-kid refreshes, per provider. */
        const val UNKNOWN_KID_REFRESH_INTERVAL_SECONDS: Long = 30

        /** Maximum response body size accepted from a JWKS or OIDC discovery endpoint (1 MiB). */
        const val MAX_BODY_BYTES: Int = 1 * 1024 * 1024

        private const val REQUEST_TIMEOUT_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val SOCKET_TIMEOUT_MS = 5_000L

        /** Retry delay after [failures] consecutive failed refreshes: 15, 30, 60, 120, 240, then 300 s. */
        internal fun backoffSeconds(failures: Int): Long {
            var delay = BACKOFF_INITIAL_SECONDS
            repeat((failures - 1).coerceAtLeast(0)) {
                delay = (delay * 2).coerceAtMost(BACKOFF_MAX_SECONDS)
            }
            return delay
        }
    }
}
