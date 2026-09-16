package io.github.jpicklyk.mcptask.current.application.service

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item `c7751104` (frozen `test-plan` note, queue phase):
 * [IdempotencyCache.getOrCompute] must not serialize unrelated keys behind one process-wide lock —
 * `compute()` runs OUTSIDE the lock; only the read-check and the final store take it. A per-key
 * in-flight entry lets same-key latecomers coalesce onto the owner's computation while different
 * keys never block each other.
 *
 * Oracles (frozen before this file existed, per the `test-plan` note and the `diagnosis` note it
 * cites): the `getOrCompute` concurrency contract pasted verbatim in the dispatch contract's
 * `DECLARATIONS for c7751104` block — same-key concurrent callers run `compute()` exactly once;
 * different keys never block each other; a throwing `compute()` propagates the ORIGINAL throwable
 * to every joined caller (`ExecutionException` unwrapped) and caches nothing; the in-flight entry
 * is removed in a `finally` whether `compute()` returned or threw.
 *
 * All concurrency scenarios rendezvous via [CountDownLatch] with a bounded `await` as the failure
 * detector — never a sleep-until-green. `IdempotencyCacheTest.kt:183`/`:339` (same-key caching,
 * same-key concurrent idempotency) are untouched and must stay green.
 *
 * Arbitration record: a prior test author disclosed a src/main over-read and was replaced before
 * any test was written for this item — this file is a fresh, independent authorship pass.
 */
private class BoomException(
    message: String
) : RuntimeException(message)

class IdempotencyCacheConcurrencyTest {
    // -------------------------------------------------------------------------
    // S1 EDGE / EXISTING-SURFACE — different keys never block each other
    // -------------------------------------------------------------------------

    @Test
    fun `S1 two different keys with computes that rendezvous both complete`() {
        val cache = IdempotencyCache()
        val latchA = CountDownLatch(1)
        val latchB = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futureA =
                executor.submit<String> {
                    cache.getOrCompute("actor-A", UUID.randomUUID()) {
                        latchA.countDown()
                        assertTrue(
                            latchB.await(10, TimeUnit.SECONDS),
                            "B's compute never ran — A is still blocking B on a shared lock",
                        )
                        "result-A"
                    }
                }
            val futureB =
                executor.submit<String> {
                    cache.getOrCompute("actor-B", UUID.randomUUID()) {
                        latchB.countDown()
                        assertTrue(
                            latchA.await(10, TimeUnit.SECONDS),
                            "A's compute never ran — B is still blocking A on a shared lock",
                        )
                        "result-B"
                    }
                }

            // Different (actorId, requestId) keys must not serialize: both computes rendezvous
            // and return. Pre-fix, the single process-wide write lock held across compute() means
            // whichever thread enters first blocks the other from ever reaching its own latch
            // countdown, and the waiting assertTrue above times out — behavioural red.
            assertEquals("result-A", futureA.get(20, TimeUnit.SECONDS))
            assertEquals("result-B", futureB.get(20, TimeUnit.SECONDS))
        } finally {
            executor.shutdown()
        }
    }

    // -------------------------------------------------------------------------
    // S3 EDGE / EXISTING-SURFACE — same-key latecomer coalesces onto an in-flight compute
    // (plain revert stays green here — see test-plan: "no-regression guard, not red-proof")
    // -------------------------------------------------------------------------

    @Test
    fun `S3 same key latecomer arrives while compute is in flight coalesces to a single compute`() {
        val cache = IdempotencyCache()
        val actorId = "actor-shared"
        val requestId = UUID.randomUUID()
        val computeCount = AtomicInteger(0)
        val computeStarted = CountDownLatch(1)
        val releaseCompute = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futureFirst =
                executor.submit<String> {
                    cache.getOrCompute(actorId, requestId) {
                        computeCount.incrementAndGet()
                        computeStarted.countDown()
                        assertTrue(releaseCompute.await(10, TimeUnit.SECONDS), "compute was never released")
                        "shared-value"
                    }
                }

            assertTrue(computeStarted.await(10, TimeUnit.SECONDS), "first compute never started")

            // S7 (NEW-SURFACE inFlightSize()) embedded here: exactly one key is in flight while its
            // compute is parked. Pre-fix this method does not exist (compile-red on a plain
            // revert) — see the S7 tests below for the after-completion half of the contract.
            assertEquals(1, cache.inFlightSize(), "exactly one key must be in flight while its compute is parked")

            val futureSecond =
                executor.submit<String> {
                    cache.getOrCompute(actorId, requestId) {
                        computeCount.incrementAndGet()
                        "should-not-run"
                    }
                }

            releaseCompute.countDown()

            assertEquals("shared-value", futureFirst.get(20, TimeUnit.SECONDS))
            assertEquals("shared-value", futureSecond.get(20, TimeUnit.SECONDS), "latecomer must receive the owner's result")
            assertEquals(1, computeCount.get(), "compute() must run exactly once for coalesced same-key callers")
        } finally {
            executor.shutdown()
        }
    }

    // -------------------------------------------------------------------------
    // S4 FAILURE / EXISTING-SURFACE — a throwing compute propagates the original throwable,
    // caches nothing, and a later same-key call recomputes
    // -------------------------------------------------------------------------

    @Test
    fun `S4 compute that throws propagates the original throwable, caches nothing, and a later call recomputes`() {
        val cache = IdempotencyCache()
        val actorId = "actor-throws"
        val requestId = UUID.randomUUID()
        val computeCount = AtomicInteger(0)

        val thrown =
            assertFailsWith<BoomException> {
                cache.getOrCompute(actorId, requestId) {
                    computeCount.incrementAndGet()
                    throw BoomException("boom")
                }
            }
        assertEquals("boom", thrown.message, "the ORIGINAL throwable, not a wrapper, must propagate")

        // Nothing was cached by the throwing compute.
        assertNull(cache.get(actorId, requestId))

        val second =
            cache.getOrCompute(actorId, requestId) {
                computeCount.incrementAndGet()
                "recovered"
            }
        assertEquals("recovered", second)
        assertEquals(2, computeCount.get(), "a later same-key call must re-compute, not replay a cached failure")
    }

    @Test
    fun `S4b concurrent latecomer joining a throwing compute receives the original throwable unwrapped`() {
        val cache = IdempotencyCache()
        val actorId = "actor-throws-concurrent"
        val requestId = UUID.randomUUID()
        val computeStarted = CountDownLatch(1)
        val releaseCompute = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val ownerFuture =
                executor.submit<Throwable?> {
                    try {
                        cache.getOrCompute(actorId, requestId) {
                            computeStarted.countDown()
                            assertTrue(releaseCompute.await(10, TimeUnit.SECONDS), "owner compute was never released")
                            throw BoomException("boom-concurrent")
                        }
                        null
                    } catch (e: Throwable) {
                        e
                    }
                }

            assertTrue(computeStarted.await(10, TimeUnit.SECONDS), "owner compute never started")

            val latecomerFuture =
                executor.submit<Throwable?> {
                    try {
                        cache.getOrCompute(actorId, requestId) { "should-not-run" }
                        null
                    } catch (e: Throwable) {
                        e
                    }
                }

            releaseCompute.countDown()

            val ownerThrowable = ownerFuture.get(20, TimeUnit.SECONDS)
            val latecomerThrowable = latecomerFuture.get(20, TimeUnit.SECONDS)

            assertIs<BoomException>(ownerThrowable, "owner must see its own thrown exception, not a wrapper")
            assertIs<BoomException>(
                latecomerThrowable,
                "latecomer must see the ORIGINAL throwable (unwrapped from ExecutionException), " +
                    "not silently recompute its own body",
            )
            assertEquals("boom-concurrent", latecomerThrowable.message)
        } finally {
            executor.shutdown()
        }
    }

    // -------------------------------------------------------------------------
    // S7 EDGE / NEW-SURFACE — inFlightSize() is zero at rest, after success and after failure
    // -------------------------------------------------------------------------

    @Test
    fun `S7 inFlightSize is zero after a returning compute completes`() {
        val cache = IdempotencyCache()
        assertEquals(0, cache.inFlightSize())
        cache.getOrCompute("actor-1", UUID.randomUUID()) { "done" }
        assertEquals(0, cache.inFlightSize(), "the in-flight entry must be removed after a successful compute")
    }

    @Test
    fun `S7 inFlightSize is zero after a throwing compute completes`() {
        val cache = IdempotencyCache()
        assertEquals(0, cache.inFlightSize())
        assertFailsWith<BoomException> {
            cache.getOrCompute("actor-1", UUID.randomUUID()) { throw BoomException("boom") }
        }
        assertEquals(0, cache.inFlightSize(), "the in-flight entry must be removed even when compute throws")
    }

    // -------------------------------------------------------------------------
    // Probes (per test-plan: record every probe attempted, including no-finding ones)
    // -------------------------------------------------------------------------

    @Test
    fun `probe maxCapacity=1 with two concurrent distinct keys does not deadlock or corrupt state`() {
        val cache = IdempotencyCache(maxCapacity = 1)
        val latchA = CountDownLatch(1)
        val latchB = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futureA =
                executor.submit<String> {
                    cache.getOrCompute("actor-cap-A", UUID.randomUUID()) {
                        latchA.countDown()
                        assertTrue(latchB.await(10, TimeUnit.SECONDS))
                        "A"
                    }
                }
            val futureB =
                executor.submit<String> {
                    cache.getOrCompute("actor-cap-B", UUID.randomUUID()) {
                        latchB.countDown()
                        assertTrue(latchA.await(10, TimeUnit.SECONDS))
                        "B"
                    }
                }

            assertEquals("A", futureA.get(20, TimeUnit.SECONDS))
            assertEquals("B", futureB.get(20, TimeUnit.SECONDS))
            assertTrue(cache.size() <= 1, "cache size must never exceed maxCapacity even under concurrent unrelated writes")
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `probe same-actor different-requestId and different-actor same-requestId stay independent under concurrency`() {
        val cache = IdempotencyCache()
        val sharedRequestId = UUID.randomUUID()
        val latch1 = CountDownLatch(1)
        val latch2 = CountDownLatch(1)
        val latch3 = CountDownLatch(1)
        val latch4 = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)

        try {
            // Same actor, different requestId.
            val f1 =
                executor.submit<String> {
                    cache.getOrCompute("actor-shared", UUID.randomUUID()) {
                        latch1.countDown()
                        assertTrue(latch2.await(10, TimeUnit.SECONDS))
                        "r1"
                    }
                }
            val f2 =
                executor.submit<String> {
                    cache.getOrCompute("actor-shared", UUID.randomUUID()) {
                        latch2.countDown()
                        assertTrue(latch1.await(10, TimeUnit.SECONDS))
                        "r2"
                    }
                }
            // Different actor, same requestId.
            val f3 =
                executor.submit<String> {
                    cache.getOrCompute("actor-X", sharedRequestId) {
                        latch3.countDown()
                        assertTrue(latch4.await(10, TimeUnit.SECONDS))
                        "r3"
                    }
                }
            val f4 =
                executor.submit<String> {
                    cache.getOrCompute("actor-Y", sharedRequestId) {
                        latch4.countDown()
                        assertTrue(latch3.await(10, TimeUnit.SECONDS))
                        "r4"
                    }
                }

            assertEquals("r1", f1.get(20, TimeUnit.SECONDS))
            assertEquals("r2", f2.get(20, TimeUnit.SECONDS))
            assertEquals("r3", f3.get(20, TimeUnit.SECONDS))
            assertEquals("r4", f4.get(20, TimeUnit.SECONDS))
        } finally {
            executor.shutdown()
        }
    }
}
