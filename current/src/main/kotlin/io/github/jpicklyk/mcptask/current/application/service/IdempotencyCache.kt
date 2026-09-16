package io.github.jpicklyk.mcptask.current.application.service

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
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
 * - **Thread-safe** — a [ReentrantReadWriteLock] guards all accesses to the LRU store. Because the
 *   underlying [java.util.LinkedHashMap] mutates on every read (to maintain LRU order), even
 *   lookups take the write lock. [size] is the only truly read-only operation.
 * - **Per-key computation, not a process-wide one** — [getOrCompute] holds [lock] only for the
 *   cache read-check and the final store write; `compute()` itself runs OUTSIDE the lock, guarded
 *   instead by a per-key in-flight entry ([inFlight]). Concurrent callers on the SAME key still
 *   coalesce onto a single `compute()` (the TOCTOU guarantee); callers on DIFFERENT keys no longer
 *   serialise behind each other. This matters because the same instance is shared by the REST
 *   write routes and the idempotent MCP tools, so one slow computation used to stall both.
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

    /**
     * Computations currently running, one entry per key. The entry is created atomically by
     * [java.util.concurrent.ConcurrentHashMap.computeIfAbsent] so exactly one caller per key owns
     * the [FutureTask] and runs it; same-key latecomers join that task instead of starting a
     * second `compute()`. Entries are removed in a `finally` — a computation that threw is neither
     * cached nor replayed.
     *
     * Deliberately NOT guarded by [lock]: the whole point is that a computation for one key does
     * not block callers holding a different key.
     */
    private val inFlight = ConcurrentHashMap<CacheKey, FutureTask<Any?>>()

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
     * Concurrency contract:
     * - `compute()` runs OUTSIDE [lock]; only the cache read-check and the store write take it.
     * - Concurrent callers on the SAME key run `compute()` exactly once and all receive its
     *   result (no TOCTOU: the in-flight entry, not the lock, provides that guarantee).
     * - Concurrent callers on DIFFERENT keys never block each other.
     * - If `compute()` throws, the ORIGINAL throwable propagates to every joined caller
     *   (unwrapped from the [FutureTask]), nothing is cached, and a later call for the same key
     *   computes again.
     *
     * @param actorId   The actor's identifier (session ID, container hostname, JWT jti, etc.).
     * @param requestId Client-supplied idempotency key for this request.
     * @param compute   Lambda that produces the result when no cached entry exists. Callers on the
     *   REST surface must read the request body BEFORE calling — a body read inside `compute()`
     *   holds this key's in-flight entry for the duration of client-paced network I/O.
     * @return Cached or freshly computed result.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getOrCompute(
        actorId: String,
        requestId: UUID,
        compute: () -> T
    ): T {
        val key = CacheKey(actorId, requestId)

        // 1. Fast path — the narrowed critical section: a cache read-check and nothing else.
        //    The write lock is still required by the LRU LinkedHashMap (see get()).
        lock.write {
            val entry = store[key]
            if (entry != null && !isExpired(entry)) return entry.value as T
        }

        // 2. Slow path — one in-flight computation per key. computeIfAbsent is atomic per key, so
        //    exactly one caller becomes the owner; same-key latecomers join the same FutureTask.
        var owner = false
        val task =
            inFlight.computeIfAbsent(key) {
                owner = true
                // The mapping function only CREATES the task. Running it here would execute
                // compute() inside a ConcurrentHashMap mapping function — forbidden (recursive
                // update) and it would block every other key hashing to the same bin.
                FutureTask<Any?> {
                    // Re-check under the lock: between this caller's fast-path miss and here, a
                    // previous owner may have completed and already been removed from inFlight.
                    val cached = lock.write { store[key]?.takeIf { !isExpired(it) } }
                    if (cached != null) {
                        cached.value
                    } else {
                        val result = compute()
                        storeComputed(key, result)
                        result
                    }
                }
            }

        if (owner) {
            try {
                task.run()
            } finally {
                // Always retire the entry — including when compute() threw, so a failed
                // computation is neither cached nor handed to the next caller for this key.
                inFlight.remove(key, task)
            }
        }

        return try {
            task.get() as T
        } catch (e: ExecutionException) {
            // Surface what compute() actually threw, not the FutureTask wrapper.
            throw e.cause ?: e
        }
    }

    /**
     * Returns the number of entries currently in the store (including potentially expired ones
     * that have not yet been lazily evicted). Exposed primarily for testing and diagnostics.
     */
    fun size(): Int = lock.read { store.size }

    /**
     * Number of computations currently in flight — one entry per key whose `compute()` is running.
     *
     * Zero at rest: [getOrCompute] removes its entry in a `finally`, whether `compute()` returned
     * or threw. A persistently non-zero value at rest would mean a leaked in-flight entry, which
     * would make that key's future requests wait on a task nobody is running. Exposed for testing
     * and diagnostics.
     */
    fun inFlightSize(): Int = inFlight.size

    /**
     * Removes all entries from the cache. Exposed for testing.
     */
    fun clear(): Unit = lock.write { store.clear() }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Stores a freshly computed value under [lock], applying the same TTL cleanup and LRU
     * eviction [put] does. Called from inside the in-flight task, before it completes, so a
     * same-key caller arriving after the task is retired sees the cached entry.
     */
    private fun storeComputed(
        key: CacheKey,
        value: Any?
    ): Unit =
        lock.write {
            val now = Instant.now()
            evictExpired(now)
            if (store.size >= maxCapacity) {
                store.remove(store.keys.first())
            }
            store[key] = CachedResponse(value = value, storedAt = now)
        }

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
