package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * TEST-AUTHOR INDEPENDENT SUITE for item `a3ebd108` -- SSE `Last-Event-ID` replay after
 * ring-buffer eviction gets a documented `sync.lost` sentinel instead of silently dropping events.
 * Bus-level scenarios only; HTTP-level scenarios (S6-S9) live in [SyncLostSseDeliveryTest].
 *
 * Written from the frozen `test-plan` note (`a2ab9e58`) per the test-author protocol (Wave C,
 * `plans/bugwave3-2026-09.md`): blind to the implementer's diff, the implementer's own tests, and
 * `implementation-notes` / `session-tracking` / `diagnosis`. Oracles come from the test-plan's
 * citations, never from what the bus currently returns:
 *
 * - Sentinel contract (id oracle): `event=sync.lost`, `itemId=null`,
 *   `id = oldestRetained.id - 1` (or `idCounter.get()` when the buffer is empty); never enters the
 *   ring buffer; FIRST emission, before replay.
 * - The gap predicate is `<`, not `<=`: `lastEventId == oldestRetained.id - 1` is the no-gap
 *   boundary (S3a); one below that boundary is a gap (S3b).
 * - `api-rest.md` §21, `ApiEventBus`/`ApiEvent` KDoc, WHATWG SSE (`id:` sets the reconnect cursor).
 *
 * S-ids are stable identifiers frozen in `test-plan` -- do not renumber.
 */
class SyncLostReplayTest {
    // -------------------------------------------------------------------------------------------
    // S1 -- happy: eviction produces sync.lost/buffer_evicted, then exactly the retained tail
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S1 - reconnecting after eviction yields sync_lost buffer_evicted then exactly the retained tail in order`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 4)
            val published = mutableListOf<ApiEvent>()
            repeat(10) {
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, emptySet())
            }
            // lastEventId = id of the 2nd published event -- long evicted (only the last 4 remain).
            val flow = bus.subscribe("s1", emptySet(), lastEventId = published[1].id)
            val collected = withTimeout(5.seconds) { flow.take(5).toList() }

            assertEquals(5, collected.size, "S1: expected sentinel + the 4 retained events. Got: $collected")
            assertEquals(ApiEventType.SYNC_LOST, collected[0].event, "S1: first frame must be the sentinel")
            assertEquals(SyncLostReason.BUFFER_EVICTED, collected[0].reason, "S1: reason must be buffer_evicted. Got: ${collected[0]}")
            assertNull(collected[0].itemId, "S1: the sentinel must carry no itemId")
            assertEquals(published[5].id, collected[0].id, "S1: sentinel id must equal oldestRetained.id - 1 (id#6)")
            assertEquals(
                published.drop(6).map { it.id },
                collected.drop(1).map { it.id },
                "S1: exactly events 7..10 must replay, in order, after the sentinel",
            )
            bus.unsubscribe("s1")
        }

    // -------------------------------------------------------------------------------------------
    // S2 -- happy: lastEventId inside the retained window replays with zero sync.lost
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S2 - lastEventId within the retained window replays exactly the events after it with no sentinel`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 10)
            val published = mutableListOf<ApiEvent>()
            repeat(5) {
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, emptySet())
            }
            val flow = bus.subscribe("s2", emptySet(), lastEventId = published[1].id)
            val collected = withTimeout(5.seconds) { flow.take(3).toList() }

            assertEquals(3, collected.size, "S2: expected exactly events 3,4,5. Got: $collected")
            assertEquals(published.drop(2).map { it.id }, collected.map { it.id })
            assertTrue(
                collected.none { it.event == ApiEventType.SYNC_LOST },
                "S2: no eviction occurred, so no sentinel is expected. Got: $collected"
            )
            bus.unsubscribe("s2")
        }

    // -------------------------------------------------------------------------------------------
    // S3 -- boundary pair: the gap predicate is "<", not "<="
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S3a - lastEventId at oldestRetained_id minus 1 yields no sentinel and a full replay`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 3)
            val published = mutableListOf<ApiEvent>()
            repeat(5) {
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, emptySet())
            }
            val oldestRetainedId = published[2].id // bufferSize=3 retains ids 3,4,5
            val flow = bus.subscribe("s3a", emptySet(), lastEventId = oldestRetainedId - 1)
            val collected = withTimeout(5.seconds) { flow.take(3).toList() }

            assertEquals(3, collected.size)
            assertTrue(
                collected.none { it.event == ApiEventType.SYNC_LOST },
                "S3a: boundary lastEventId=oldest-1 must not produce a sentinel. Got: $collected",
            )
            assertEquals(published.drop(2).map { it.id }, collected.map { it.id })
            bus.unsubscribe("s3a")
        }

    @Test
    fun `S3b - lastEventId one below the no-gap boundary yields a sentinel`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 3)
            val published = mutableListOf<ApiEvent>()
            repeat(5) {
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, emptySet())
            }
            val oldestRetainedId = published[2].id
            val flow = bus.subscribe("s3b", emptySet(), lastEventId = oldestRetainedId - 2)
            val collected = withTimeout(5.seconds) { flow.take(4).toList() } // sentinel + 3,4,5

            assertEquals(4, collected.size)
            assertEquals(
                ApiEventType.SYNC_LOST,
                collected[0].event,
                "S3b: one below the no-gap boundary must yield a sync.lost sentinel first. Got: $collected",
            )
            assertEquals(oldestRetainedId - 1, collected[0].id, "S3b: sentinel id must equal oldestRetained.id - 1")
            assertNull(collected[0].itemId)
            assertEquals(published.drop(2).map { it.id }, collected.drop(1).map { it.id })
            bus.unsubscribe("s3b")
        }

    // -------------------------------------------------------------------------------------------
    // S4 -- edge: no Last-Event-ID after eviction -> no sentinel/replay, live only
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S4 - no Last-Event-ID after eviction yields no sentinel and no replay, live events only`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 3)
            repeat(5) {
                bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now()), emptySet())
            }
            val flow = bus.subscribe("s4", emptySet(), lastEventId = null)
            val collected = async { withTimeout(5.seconds) { flow.take(1).toList() } }

            delay(50)
            val live = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
            bus.publish(live, emptySet())

            val events = collected.await()
            assertEquals(1, events.size, "S4: expected exactly the live event, no replay/sentinel. Got: $events")
            assertEquals(live.id, events[0].id)
            assertNotEquals(ApiEventType.SYNC_LOST, events[0].event, "S4: absent Last-Event-ID must never produce a sentinel")
            bus.unsubscribe("s4")
        }

    // -------------------------------------------------------------------------------------------
    // S5 -- failure: lastEventId beyond the high-water mark (e.g. after a restart)
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S5 - a lastEventId beyond the current counter yields sync_lost unknown_event_id then a full replay`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 3)
            val published = mutableListOf<ApiEvent>()
            repeat(5) {
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, emptySet())
            }
            val oldestRetainedId = published[2].id
            val bogus = published.last().id + 100
            val flow = bus.subscribe("s5", emptySet(), lastEventId = bogus)
            val collected = withTimeout(5.seconds) { flow.take(4).toList() }

            assertEquals(4, collected.size)
            assertEquals(ApiEventType.SYNC_LOST, collected[0].event)
            assertEquals(
                SyncLostReason.UNKNOWN_EVENT_ID,
                collected[0].reason,
                "S5: an id beyond the high-water mark must report unknown_event_id, not buffer_evicted. Got: ${collected[0]}",
            )
            assertEquals(oldestRetainedId - 1, collected[0].id)
            assertNull(collected[0].itemId)
            assertEquals(published.drop(2).map { it.id }, collected.drop(1).map { it.id })
            bus.unsubscribe("s5")
        }

    // -------------------------------------------------------------------------------------------
    // S10 -- ordering: sentinel.id < every id after it; reconnect at sentinel.id reconverges
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S10 - the sentinel id is lower than everything replayed after it, and reconnecting at that id yields no sentinel`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 4)
            val published = mutableListOf<ApiEvent>()
            repeat(10) {
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, emptySet())
            }
            val firstFlow = bus.subscribe("s10-first", emptySet(), lastEventId = published[1].id)
            val firstCollected = withTimeout(5.seconds) { firstFlow.take(5).toList() }
            bus.unsubscribe("s10-first")

            val sentinel = firstCollected.first()
            assertEquals(ApiEventType.SYNC_LOST, sentinel.event)
            assertTrue(
                firstCollected.drop(1).all { it.id > sentinel.id },
                "S10: every id delivered after the sentinel on this connection must exceed the sentinel id. Got: $firstCollected",
            )

            val secondFlow = bus.subscribe("s10-reconnect", emptySet(), lastEventId = sentinel.id)
            val secondCollected = withTimeout(5.seconds) { secondFlow.take(4).toList() }
            assertTrue(
                secondCollected.none { it.event == ApiEventType.SYNC_LOST },
                "S10: reconnecting exactly at the sentinel id must not re-trigger a sentinel. Got: $secondCollected",
            )
            assertEquals(
                firstCollected.drop(1).map { it.id },
                secondCollected.map { it.id },
                "S10: reconnecting at the sentinel id must reconverge to the same tail",
            )
            bus.unsubscribe("s10-reconnect")
        }

    // -------------------------------------------------------------------------------------------
    // S11 -- a live queue-overflow sentinel carries reason=queue_overflow, itemId=null
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S11 - a queue-overflow sentinel carries reason queue_overflow with a null itemId`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 1000, connectionQueueSize = 4)
            val root = UUID.randomUUID()
            val flow = bus.subscribe("s11", setOf(root), lastEventId = null)

            repeat(10) {
                bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID()), setOf(root))
            }

            val collected = withTimeout(5.seconds) { flow.take(4).toList() }
            val overflow = collected.firstOrNull { it.event == ApiEventType.SYNC_LOST }
            assertNotNull(overflow, "S11: expected at least one sync.lost among the received events. Got: ${collected.map { it.event }}")
            assertEquals(
                SyncLostReason.QUEUE_OVERFLOW,
                overflow!!.reason,
                "S11: overflow sentinel must carry reason=queue_overflow. Got: $overflow"
            )
            assertNull(overflow.itemId, "S11: overflow sentinel must carry no itemId")
            bus.unsubscribe("s11")
        }

    // -------------------------------------------------------------------------------------------
    // S12 -- the ring buffer never stores an auto-generated sentinel; healthy cursors see none
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S12 - the ring buffer never retains an auto-generated sentinel, and a concurrent healthy cursor sees none`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 4)
            val published = mutableListOf<ApiEvent>()
            repeat(10) {
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, emptySet())
            }

            // Trigger a sentinel for an evicted-cursor subscriber; this must not enter the buffer.
            val evictedFlow = bus.subscribe("s12-evicted", emptySet(), lastEventId = published[1].id)
            withTimeout(5.seconds) { evictedFlow.take(5).toList() }
            bus.unsubscribe("s12-evicted")

            assertTrue(
                bus.ringBufferSnapshot().none { it.event == ApiEventType.SYNC_LOST },
                "S12: the ring buffer must never retain an auto-generated sync.lost sentinel. Got: ${bus.ringBufferSnapshot()}",
            )

            // A healthy-cursor subscriber (lastEventId inside the retained window) must see no sentinel.
            val healthyFlow = bus.subscribe("s12-healthy", emptySet(), lastEventId = published[6].id) // id#7, in-window
            val healthyCollected = withTimeout(5.seconds) { healthyFlow.take(3).toList() }
            assertTrue(
                healthyCollected.none { it.event == ApiEventType.SYNC_LOST },
                "S12: a healthy-cursor subscriber must not see a sentinel. Got: $healthyCollected",
            )
            assertEquals(published.drop(7).map { it.id }, healthyCollected.map { it.id })
            bus.unsubscribe("s12-healthy")
        }

    // -------------------------------------------------------------------------------------------
    // Adversarial probes (recorded in test-manifest regardless of outcome)
    // -------------------------------------------------------------------------------------------

    @Test
    fun `probe - bufferSize 0 does not crash publish and still delivers live events`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 0)
            repeat(3) {
                bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now()), emptySet())
            }
            // Resume requested against an always-empty buffer: sentinel id falls back to idCounter.get().
            val flow = bus.subscribe("probe-buf0", emptySet(), lastEventId = 1L)
            val collected = withTimeout(5.seconds) { flow.take(1).toList() }
            assertEquals(
                ApiEventType.SYNC_LOST,
                collected[0].event,
                "probe: an empty buffer with a resume request must still surface a sentinel, not crash"
            )
            assertEquals(
                3L,
                collected[0].id,
                "probe: with bufferSize=0 the sentinel id must fall back to the current counter (3 events built)"
            )
            bus.unsubscribe("probe-buf0")

            // No crash publishing further events, and no history is retained.
            val flow2 = bus.subscribe("probe-buf0-live", emptySet(), lastEventId = null)
            val live = async { withTimeout(5.seconds) { flow2.take(1).toList() } }
            delay(50)
            val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID())
            bus.publish(e, emptySet())
            val events = live.await()
            assertEquals(1, events.size)
            assertEquals(e.id, events[0].id)
            bus.unsubscribe("probe-buf0-live")
        }

    @Test
    fun `probe - bufferSize 1 evicts down to a single retained event without crashing`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 1)
            val published = mutableListOf<ApiEvent>()
            repeat(3) {
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, emptySet())
            }
            val flow = bus.subscribe("probe-buf1", emptySet(), lastEventId = published[0].id)
            val collected = withTimeout(5.seconds) { flow.take(2).toList() }
            assertEquals(2, collected.size, "probe: expected sentinel + the single retained event. Got: $collected")
            assertEquals(ApiEventType.SYNC_LOST, collected[0].event)
            assertEquals(published[2].id, collected[1].id, "probe: only the most recent event must be retained with bufferSize=1")
            bus.unsubscribe("probe-buf1")
        }

    @Test
    fun `probe - lastEventId 0 before any event is published yields no sentinel`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 4)
            val flow = bus.subscribe("probe-zero", emptySet(), lastEventId = 0L)
            val collected = async { withTimeout(5.seconds) { flow.take(1).toList() } }
            delay(50)
            val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID())
            bus.publish(e, emptySet())
            val events = collected.await()
            assertEquals(1, events.size)
            assertNotEquals(
                ApiEventType.SYNC_LOST,
                events[0].event,
                "probe: lastEventId=0 against a never-published bus must not be treated as a gap. Got: $events",
            )
            bus.unsubscribe("probe-zero")
        }

    @Test
    fun `probe - a negative lastEventId is treated as a gap and yields a sentinel`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 3)
            repeat(5) {
                bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now()), emptySet())
            }
            val flow = bus.subscribe("probe-negative", emptySet(), lastEventId = -5L)
            val collected = withTimeout(5.seconds) { flow.take(4).toList() }
            assertEquals(
                ApiEventType.SYNC_LOST,
                collected[0].event,
                "probe: a negative lastEventId must still be recognized as a gap. Got: $collected"
            )
            assertNull(collected[0].itemId)
            // The frozen oracle names buffer_evicted vs. unknown_event_id for two specific cases
            // (evicted-but-issued vs. beyond-the-counter); it does not classify a negative id.
            // This probe only asserts the sentinel fires with a valid, recognized reason -- the
            // specific classification for negative ids is recorded as an open arbitration item in
            // test-manifest, not resolved here by reading the implementation.
            assertTrue(
                collected[0].reason == SyncLostReason.BUFFER_EVICTED || collected[0].reason == SyncLostReason.UNKNOWN_EVENT_ID,
                "probe: reason must be one of the two documented sentinel reasons. Got: ${collected[0].reason}",
            )
            bus.unsubscribe("probe-negative")
        }

    @Test
    fun `probe - Long_MAX_VALUE lastEventId yields an unknown_event_id sentinel like a smaller out-of-range id`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 3)
            val published = mutableListOf<ApiEvent>()
            repeat(5) {
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, emptySet())
            }
            val oldestRetainedId = published[2].id
            val flow = bus.subscribe("probe-maxlong", emptySet(), lastEventId = Long.MAX_VALUE)
            val collected = withTimeout(5.seconds) { flow.take(4).toList() }
            assertEquals(ApiEventType.SYNC_LOST, collected[0].event)
            assertEquals(
                SyncLostReason.UNKNOWN_EVENT_ID,
                collected[0].reason,
                "probe: Long.MAX_VALUE is far beyond the counter -> unknown_event_id. Got: ${collected[0]}"
            )
            assertEquals(oldestRetainedId - 1, collected[0].id)
            bus.unsubscribe("probe-maxlong")
        }

    @Test
    fun `probe - reconnecting twice at the same evicted lastEventId is idempotent`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 4)
            val published = mutableListOf<ApiEvent>()
            repeat(10) {
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, emptySet())
            }
            val cursor = published[1].id

            val first = withTimeout(5.seconds) { bus.subscribe("probe-dup-1", emptySet(), lastEventId = cursor).take(5).toList() }
            bus.unsubscribe("probe-dup-1")
            val second = withTimeout(5.seconds) { bus.subscribe("probe-dup-2", emptySet(), lastEventId = cursor).take(5).toList() }
            bus.unsubscribe("probe-dup-2")

            assertEquals(
                first.map { it.id to it.event to it.reason },
                second.map { it.id to it.event to it.reason },
                "probe: reconnecting twice at the same evicted lastEventId must produce identical sentinel + replay sets",
            )
        }
}
