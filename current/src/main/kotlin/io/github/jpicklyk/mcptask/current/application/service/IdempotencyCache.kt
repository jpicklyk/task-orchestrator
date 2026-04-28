package io.github.jpicklyk.mcptask.current.application.service

import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * In-memory LRU cache keyed on `(actorId, requestId)` pairs.
 *
 * Used by mutating MCP tools to detect duplicate requests from the same actor: if a tool
 * receives a request with the same `(actorId, requestId)` it already processed, it returns
 * the cached result instead of re-executing the operation.
 *
 * Design decisions:
 * - **Single-instance scope** — no restart persistence needed; a fresh JVM always starts with
 *   an empty cache. Persistent HA semantics are deferred to a future v2 storage layer.
 * - **LRU eviction** — once [maxCapacity] entries are stored, the least-recently-used entry is
 *   removed to bound memory consumption.
 * - **TTL expiry** — entries older than [ttlSeconds] are treated as absent. Expired entries are
 *   cleaned lazily on [get]/[getOrCompute] and eagerly during [put] when the cache is full.
 * - **Thread-safe** — a [ReentrantReadWriteLock] guards all accesses. Because the underlying
 *   [java.util.LinkedHashMap] mutates on every read (to maintain LRU order), all operations
 *   including [get] and [getOrCompute] take the write lock. [size] is the only truly
 *   read-only operation.
 *
 * @param maxCapacity Maximum number of live (non-expired) entries to retain (default 1000).
 * @param ttlSeconds  Time-to-live per entry in seconds (default 600 = 10 minutes).
 */
class IdempotencyCache(
    val maxCapacity: Int = 1000,
    val ttlSeconds: Long = 600L
) {
    init {
        require(maxCapacity > 0) { "maxCapacity must be positive, got $maxCapacity" }
        require(ttlSeconds > 0) { "ttlSeconds must be positive, got $ttlSeconds" }
    }

    /**
     * A single cached response entry.
     *
     * @property value    The cached result value (may be null — null is a valid cached result).
     * @property storedAt The instant at which the entry was written.
     */
    data class CachedResponse(
        val value: Any?,
        val storedAt: Instant
    )

    /**
     * Composite cache key combining the actor identifier and the request's idempotency UUID.
     */
    private data class CacheKey(
        val actorId: String,
        val requestId: UUID
    )

    /**
     * Access-ordered LinkedHashMap used as the underlying LRU container.
     * `accessOrder = true` moves the accessed entry to the tail on every [get]/[put],
     * so the head is always the least-recently-used entry.
     *
     * Note: LinkedHashMap itself is not thread-safe; all accesses are guarded by [lock].
     * The third constructor parameter is `accessOrder` — set to `true` for LRU ordering.
     */
    private val store: LinkedHashMap<CacheKey, CachedResponse> =
        LinkedHashMap(16, 0.75f, true)

    private val lock = ReentrantReadWriteLock()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the cached value for `(actorId, requestId)` if it exists and has not expired.
     * Returns null otherwise (both "not found" and "expired" map to null).
     *
     * Uses a **write lock** because [store] is a `LinkedHashMap(accessOrder = true)` which
     * mutates its internal linked-list on every [LinkedHashMap.get] call to track LRU order.
     * Calling [LinkedHashMap.get] under a shared read lock from multiple threads concurrently
     * would cause a data race and potential [java.util.ConcurrentModificationException].
     */
    fun get(
        actorId: String,
        requestId: UUID
    ): Any? =
        lock.write {
            val key = CacheKey(actorId, requestId)
            val entry = store[key] ?: return null
            if (isExpired(entry)) null else entry.value
        }

    /**
     * Stores `value` for `(actorId, requestId)`.
     *
     * If the cache is at [maxCapacity] after expiry cleanup, the least-recently-used entry
     * is evicted to make room.
     */
    fun put(
        actorId: String,
        requestId: UUID,
        value: Any?
    ): Unit =
        lock.write {
            val key = CacheKey(actorId, requestId)
            val now = Instant.now()
            // Remove expired entries first to avoid evicting live data unnecessarily
            evictExpired(now)
            // If still at capacity after expiry cleanup, remove the LRU entry
            if (store.size >= maxCapacity) {
                val lruKey = store.keys.first()
                store.remove(lruKey)
            }
            store[key] = CachedResponse(value = value, storedAt = now)
        }

    /**
     * Returns the cached result for `(actorId, requestId)` if present and non-expired,
     * otherwise calls [compute], caches its result, and returns it.
     *
     * This is the primary entry point for idempotent tool implementations.
     *
     * ```kotlin
     * val result = idempotencyCache.getOrCompute(actor.id, requestId) {
     *     // perform the mutating operation here
     *     doWork()
     * }
     * ```
     *
     * @param actorId   The actor's identifier (session ID, container hostname, JWT jti, etc.).
     * @param requestId Client-supplied idempotency key for this request.
     * @param compute   Lambda that produces the result when no cached entry exists.
     * @return Cached or freshly computed result.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getOrCompute(
        actorId: String,
        requestId: UUID,
        compute: () -> T
    ): T {
        // Hold the write lock across the entire check-compute-store cycle to prevent
        // TOCTOU: without this, concurrent threads on a cache miss would all pass the
        // "not found" check and each call compute(), defeating the idempotency guarantee.
        //
        // The write lock is also required by the LRU LinkedHashMap — see get() comment.
        //
        // Note: compute() (the actual tool execution) runs while the write lock is held.
        // This is intentional: for our use case the MCP tool already serialises through
        // SQLite's own write lock, so holding the cache write lock for the duration adds
        // no additional practical contention.
        return lock.write {
            val key = CacheKey(actorId, requestId)
            val entry = store[key]
            if (entry != null && !isExpired(entry)) {
                entry.value as T
            } else {
                val result = compute()
                val now = Instant.now()
                evictExpired(now)
                if (store.size >= maxCapacity) {
                    store.remove(store.keys.first())
                }
                store[key] = CachedResponse(value = result, storedAt = now)
                result
            }
        }
    }

    /**
     * Returns the number of entries currently in the store (including potentially expired ones
     * that have not yet been lazily evicted). Exposed primarily for testing and diagnostics.
     */
    fun size(): Int = lock.read { store.size }

    /**
     * Removes all entries from the cache. Exposed for testing.
     */
    fun clear(): Unit = lock.write { store.clear() }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun isExpired(entry: CachedResponse): Boolean {
        val expiresAt = entry.storedAt.plusSeconds(ttlSeconds)
        return Instant.now().isAfter(expiresAt)
    }

    /**
     * Removes all entries whose TTL has elapsed. Must be called within a write lock.
     */
    private fun evictExpired(now: Instant) {
        val iter = store.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val expiresAt = entry.value.storedAt.plusSeconds(ttlSeconds)
            if (now.isAfter(expiresAt)) {
                iter.remove()
            }
        }
    }
}
