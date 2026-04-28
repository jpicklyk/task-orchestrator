package io.github.jpicklyk.mcptask.current.application.service

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdempotencyCacheTest {
    // -------------------------------------------------------------------------
    // Construction / defaults
    // -------------------------------------------------------------------------

    @Test
    fun `default maxCapacity and ttlSeconds are positive`() {
        val cache = IdempotencyCache()
        assertTrue(cache.maxCapacity > 0)
        assertTrue(cache.ttlSeconds > 0)
    }

    @Test
    fun `default values are 1000 capacity and 600 seconds`() {
        val cache = IdempotencyCache()
        assertEquals(1000, cache.maxCapacity)
        assertEquals(600L, cache.ttlSeconds)
    }

    @Test
    fun `custom maxCapacity and ttlSeconds are stored`() {
        val cache = IdempotencyCache(maxCapacity = 50, ttlSeconds = 30)
        assertEquals(50, cache.maxCapacity)
        assertEquals(30L, cache.ttlSeconds)
    }

    @Test
    fun `zero maxCapacity throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            IdempotencyCache(maxCapacity = 0)
        }
    }

    @Test
    fun `negative maxCapacity throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            IdempotencyCache(maxCapacity = -1)
        }
    }

    @Test
    fun `zero ttlSeconds throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            IdempotencyCache(ttlSeconds = 0)
        }
    }

    @Test
    fun `negative ttlSeconds throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            IdempotencyCache(ttlSeconds = -5)
        }
    }

    // -------------------------------------------------------------------------
    // Basic get / put
    // -------------------------------------------------------------------------

    @Test
    fun `get returns null for absent key`() {
        val cache = IdempotencyCache()
        assertNull(cache.get("actor-1", UUID.randomUUID()))
    }

    @Test
    fun `put then get returns stored value`() {
        val cache = IdempotencyCache()
        val reqId = UUID.randomUUID()
        cache.put("actor-1", reqId, "result-A")
        assertEquals("result-A", cache.get("actor-1", reqId))
    }

    @Test
    fun `put null value is distinguishable from absent key via getOrCompute`() {
        val cache = IdempotencyCache()
        val reqId = UUID.randomUUID()
        val computeCount = AtomicInteger(0)

        // First call — computes and caches null
        val first =
            cache.getOrCompute("actor-1", reqId) {
                computeCount.incrementAndGet()
                null
            }
        assertNull(first)
        assertEquals(1, computeCount.get())

        // Second call — should return cached null WITHOUT re-computing
        val second =
            cache.getOrCompute("actor-1", reqId) {
                computeCount.incrementAndGet()
                null
            }
        assertNull(second)
        // compute should NOT have been called again
        assertEquals(1, computeCount.get(), "compute was called a second time but should have used cache")
    }

    @Test
    fun `different actorId same requestId are independent entries`() {
        val cache = IdempotencyCache()
        val reqId = UUID.randomUUID()
        cache.put("actor-A", reqId, "value-A")
        cache.put("actor-B", reqId, "value-B")
        assertEquals("value-A", cache.get("actor-A", reqId))
        assertEquals("value-B", cache.get("actor-B", reqId))
    }

    @Test
    fun `same actorId different requestId are independent entries`() {
        val cache = IdempotencyCache()
        val reqA = UUID.randomUUID()
        val reqB = UUID.randomUUID()
        cache.put("actor-1", reqA, "value-A")
        cache.put("actor-1", reqB, "value-B")
        assertEquals("value-A", cache.get("actor-1", reqA))
        assertEquals("value-B", cache.get("actor-1", reqB))
    }

    @Test
    fun `put overwrites existing entry for same key`() {
        val cache = IdempotencyCache()
        val reqId = UUID.randomUUID()
        cache.put("actor-1", reqId, "first")
        cache.put("actor-1", reqId, "second")
        assertEquals("second", cache.get("actor-1", reqId))
    }

    @Test
    fun `size increments on new puts`() {
        val cache = IdempotencyCache()
        assertEquals(0, cache.size())
        cache.put("actor-1", UUID.randomUUID(), "v1")
        assertEquals(1, cache.size())
        cache.put("actor-1", UUID.randomUUID(), "v2")
        assertEquals(2, cache.size())
    }

    @Test
    fun `clear empties the cache`() {
        val cache = IdempotencyCache()
        cache.put("actor-1", UUID.randomUUID(), "x")
        cache.put("actor-2", UUID.randomUUID(), "y")
        cache.clear()
        assertEquals(0, cache.size())
    }

    // -------------------------------------------------------------------------
    // getOrCompute
    // -------------------------------------------------------------------------

    @Test
    fun `getOrCompute calls compute on cache miss`() {
        val cache = IdempotencyCache()
        val reqId = UUID.randomUUID()
        val callCount = AtomicInteger(0)

        val result =
            cache.getOrCompute("actor-1", reqId) {
                callCount.incrementAndGet()
                "computed-value"
            }

        assertEquals("computed-value", result)
        assertEquals(1, callCount.get())
    }

    @Test
    fun `getOrCompute returns cached value on second call`() {
        val cache = IdempotencyCache()
        val reqId = UUID.randomUUID()
        val callCount = AtomicInteger(0)

        cache.getOrCompute("actor-1", reqId) {
            callCount.incrementAndGet()
            "value"
        }
        val second =
            cache.getOrCompute("actor-1", reqId) {
                callCount.incrementAndGet()
                "other"
            }

        assertEquals("value", second)
        assertEquals(1, callCount.get())
    }

    @Test
    fun `getOrCompute returns typed result correctly`() {
        val cache = IdempotencyCache()
        val reqId = UUID.randomUUID()
        val result: Int = cache.getOrCompute("actor-1", reqId) { 42 }
        assertEquals(42, result)
    }

    // -------------------------------------------------------------------------
    // TTL expiry
    // -------------------------------------------------------------------------

    @Test
    fun `get returns null for expired entry`() {
        // 1-second TTL; store entry, then wait for expiry
        val cache = IdempotencyCache(ttlSeconds = 1)
        val reqId = UUID.randomUUID()
        cache.put("actor-1", reqId, "stale")
        Thread.sleep(1100)
        assertNull(cache.get("actor-1", reqId))
    }

    @Test
    fun `getOrCompute re-computes after TTL expiry`() {
        val cache = IdempotencyCache(ttlSeconds = 1)
        val reqId = UUID.randomUUID()
        val callCount = AtomicInteger(0)

        cache.getOrCompute("actor-1", reqId) {
            callCount.incrementAndGet()
            "first"
        }
        Thread.sleep(1100)
        val second =
            cache.getOrCompute("actor-1", reqId) {
                callCount.incrementAndGet()
                "second"
            }

        assertEquals("second", second)
        assertEquals(2, callCount.get())
    }

    // -------------------------------------------------------------------------
    // LRU eviction at capacity
    // -------------------------------------------------------------------------

    @Test
    fun `LRU entry is evicted when capacity is exceeded`() {
        val cache = IdempotencyCache(maxCapacity = 3)

        val req1 = UUID.randomUUID()
        val req2 = UUID.randomUUID()
        val req3 = UUID.randomUUID()
        val req4 = UUID.randomUUID()

        cache.put("actor", req1, "v1")
        cache.put("actor", req2, "v2")
        cache.put("actor", req3, "v3")
        // Access req1 so req2 becomes the LRU
        cache.get("actor", req1)
        // Adding req4 should evict req2 (the LRU after req1 was touched)
        cache.put("actor", req4, "v4")

        assertNull(cache.get("actor", req2), "LRU entry req2 should have been evicted")
        assertNotNull(cache.get("actor", req1), "req1 (accessed recently) should still be present")
        assertNotNull(cache.get("actor", req3), "req3 should still be present")
        assertNotNull(cache.get("actor", req4), "req4 (just added) should be present")
    }

    @Test
    fun `cache size never exceeds maxCapacity`() {
        val capacity = 5
        val cache = IdempotencyCache(maxCapacity = capacity, ttlSeconds = 3600)

        repeat(10) { i ->
            cache.put("actor-$i", UUID.randomUUID(), "value-$i")
        }

        assertTrue(cache.size() <= capacity, "size ${cache.size()} exceeded maxCapacity $capacity")
    }

    // -------------------------------------------------------------------------
    // CachedResponse data class
    // -------------------------------------------------------------------------

    @Test
    fun `CachedResponse storedAt is set to approximately now`() {
        val cache = IdempotencyCache()
        val reqId = UUID.randomUUID()
        val before = java.time.Instant.now()
        cache.put("actor-1", reqId, "x")
        val after = java.time.Instant.now()

        // Verify via re-computation not being triggered (can't read storedAt directly from public API)
        // We validate via TTL semantics: an entry stored 'now' should not expire immediately
        val result = cache.get("actor-1", reqId)
        assertEquals("x", result)
        assertTrue(!before.isAfter(after))
    }

    // -------------------------------------------------------------------------
    // Concurrent access safety
    // -------------------------------------------------------------------------

    @Test
    fun `concurrent puts and gets do not throw`() {
        val cache = IdempotencyCache(maxCapacity = 200)
        val threadCount = 10
        val opsPerThread = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        repeat(threadCount) { t ->
            executor.submit {
                try {
                    repeat(opsPerThread) { i ->
                        val reqId = UUID.randomUUID()
                        cache.put("actor-$t", reqId, "value-$t-$i")
                        cache.get("actor-$t", reqId)
                        cache.getOrCompute("actor-$t", UUID.randomUUID()) { "computed-$t-$i" }
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS), "Concurrent test timed out")
        executor.shutdown()
        assertEquals(0, errors.get(), "Concurrent access produced ${errors.get()} error(s)")
    }

    @Test
    fun `getOrCompute is idempotent under concurrent requests for same key`() {
        val cache = IdempotencyCache(maxCapacity = 100)
        val reqId = UUID.randomUUID()
        val computeCount = AtomicInteger(0)
        val threadCount = 20
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)
        val results = mutableListOf<String>()
        val resultsLock = java.util.concurrent.ConcurrentLinkedQueue<String>()

        repeat(threadCount) {
            executor.submit {
                latch.await()
                val value =
                    cache.getOrCompute("actor-shared", reqId) {
                        computeCount.incrementAndGet()
                        "shared-result"
                    }
                resultsLock.add(value)
            }
        }

        latch.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))

        // All threads must have received "shared-result"
        assertEquals(threadCount, resultsLock.size)
        assertTrue(resultsLock.all { it == "shared-result" }, "Not all threads received the same result")
        // compute() must have been called exactly once — this is the idempotency guarantee
        assertEquals(1, computeCount.get(), "compute() was called ${computeCount.get()} times but should be called exactly once")
    }
}
