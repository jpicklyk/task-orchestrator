package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [ApiEventBus] — per-root topic pub/sub, ring buffer, backpressure.
 *
 * These tests do NOT use a Ktor server — they test the bus mechanics directly.
 * The HTTP SSE integration is covered by [EventRoutesTest].
 */
class ApiEventBusTest {
    // -------------------------------------------------------------------------
    // Basic publish / subscribe
    // -------------------------------------------------------------------------

    @Test
    fun `subscriber receives event matching its root set`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val root = UUID.randomUUID()
            val itemId = UUID.randomUUID()

            val flow = bus.subscribe("sub-1", setOf(root), lastEventId = null)

            val collected =
                async {
                    withTimeout(5.seconds) {
                        flow.take(1).toList()
                    }
                }

            delay(50)
            val event = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemId, modifiedAt = Instant.now())
            bus.publish(event, affectedRoots = setOf(root))

            val events = collected.await()
            assertEquals(1, events.size)
            assertEquals(ApiEventType.ITEM_CREATED, events[0].event)
            assertEquals(itemId.toString(), events[0].itemId)
            bus.unsubscribe("sub-1")
        }

    @Test
    fun `subscriber does NOT receive event for a different root`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val root1 = UUID.randomUUID()
            val root2 = UUID.randomUUID()
            val itemId = UUID.randomUUID()

            val flow = bus.subscribe("sub-r2-only", setOf(root2), lastEventId = null)

            // Collect the first event (if any) within a short bounded window. The publish targets
            // root1 only, so the root2 subscriber must NOT receive it: take(1) never completes and
            // withTimeoutOrNull returns null (no TimeoutCancellationException propagates out).
            val received =
                async {
                    withTimeoutOrNull(800) { flow.take(1).toList() }
                }

            delay(50)
            val event = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemId, modifiedAt = Instant.now())
            bus.publish(event, affectedRoots = setOf(root1)) // only root1

            val result = received.await()
            assertNull(result, "Subscriber for root2 should NOT receive root1 events (expected no event)")
            bus.unsubscribe("sub-r2-only")
        }

    @Test
    fun `unrestricted subscriber (empty rootIds) receives all events`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val root1 = UUID.randomUUID()
            val root2 = UUID.randomUUID()
            val itemId = UUID.randomUUID()

            val flow = bus.subscribe("sub-all", emptySet(), lastEventId = null)

            val collected =
                async {
                    withTimeout(5.seconds) {
                        flow.take(2).toList()
                    }
                }

            delay(50)
            bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemId, modifiedAt = Instant.now()), setOf(root1))
            bus.publish(bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = itemId, modifiedAt = Instant.now()), setOf(root2))

            val events = collected.await()
            assertEquals(2, events.size)
            assertEquals(ApiEventType.ITEM_CREATED, events[0].event)
            assertEquals(ApiEventType.ITEM_UPDATED, events[1].event)
            bus.unsubscribe("sub-all")
        }

    // -------------------------------------------------------------------------
    // Monotonic event IDs — independent namespace from /mcp
    // -------------------------------------------------------------------------

    @Test
    fun `event IDs are monotonically increasing and independent namespace`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val root = UUID.randomUUID()
            val flow = bus.subscribe("sub-id", setOf(root), lastEventId = null)

            val collected =
                async {
                    withTimeout(5.seconds) {
                        flow.take(3).toList()
                    }
                }

            delay(50)
            repeat(3) { bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED), setOf(root)) }

            val events = collected.await()
            assertEquals(3, events.size)
            // IDs must be strictly increasing
            assertTrue(events[0].id < events[1].id)
            assertTrue(events[1].id < events[2].id)
            // IDs start at 1 (first call) — they are independent from any other counter
            assertTrue(events[0].id >= 1)
            bus.unsubscribe("sub-id")
        }

    // -------------------------------------------------------------------------
    // Last-Event-ID replay
    // -------------------------------------------------------------------------

    @Test
    fun `Last-Event-ID replay - events after the given id are replayed from ring buffer`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val root = UUID.randomUUID()

            // Publish 5 events WITHOUT a subscriber
            val published = mutableListOf<ApiEvent>()
            repeat(5) { i ->
                val e =
                    bus.buildEvent(
                        ApiEventType.ITEM_CREATED,
                        itemId = UUID.randomUUID(),
                        modifiedAt = Instant.now(),
                    )
                published.add(e)
                bus.publish(e, setOf(root))
            }

            // Subscribe with lastEventId = id of the 3rd event (index 2)
            // → expect to receive events 4 and 5 (indices 3 and 4)
            val lastSeenId = published[2].id
            val flow = bus.subscribe("sub-replay", setOf(root), lastEventId = lastSeenId)

            val collected =
                async {
                    withTimeout(3.seconds) {
                        flow.take(2).toList() // only 2 buffered events to replay
                    }
                }

            val events = collected.await()
            assertEquals(2, events.size, "Expected 2 buffered events replayed (after id $lastSeenId)")
            assertEquals(published[3].id, events[0].id)
            assertEquals(published[4].id, events[1].id)
            bus.unsubscribe("sub-replay")
        }

    // -------------------------------------------------------------------------
    // Slow consumer → sync.lost backpressure
    // -------------------------------------------------------------------------

    @Test
    fun `backpressure - slow consumer receives sync_lost event on queue overflow`(): Unit =
        runBlocking {
            // Very small queue to trigger overflow easily
            val bus = ApiEventBus(bufferSize = 1000, connectionQueueSize = 4)
            val root = UUID.randomUUID()

            // Subscribe but do NOT collect (simulates a slow consumer)
            val flow = bus.subscribe("sub-slow", setOf(root), lastEventId = null)

            // Publish more events than the queue capacity
            repeat(10) { i ->
                bus.publish(
                    bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID()),
                    setOf(root),
                )
            }

            // Now collect — we should see at least one sync.lost event among the received events
            val collected =
                withTimeout(3.seconds) {
                    flow.take(4).toList() // capacity is 4
                }

            val hasSyncLost = collected.any { it.event == ApiEventType.SYNC_LOST }
            assertTrue(hasSyncLost, "Expected at least one sync.lost event on queue overflow. Got: ${collected.map { it.event }}")
            bus.unsubscribe("sub-slow")
        }

    // -------------------------------------------------------------------------
    // Bus-level events (sync.lost, auth.expired) broadcast to all
    // -------------------------------------------------------------------------

    @Test
    fun `bus-level events with empty affectedRoots are delivered to all subscribers`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val root1 = UUID.randomUUID()
            val root2 = UUID.randomUUID()

            val flow1 = bus.subscribe("sub-bus-1", setOf(root1), lastEventId = null)
            val flow2 = bus.subscribe("sub-bus-2", setOf(root2), lastEventId = null)

            val c1 = async { withTimeout(3.seconds) { flow1.take(1).toList() } }
            val c2 = async { withTimeout(3.seconds) { flow2.take(1).toList() } }

            delay(50)
            val evt = bus.buildEvent(ApiEventType.AUTH_EXPIRED, modifiedAt = Instant.now())
            bus.publish(evt, affectedRoots = emptySet()) // broadcast

            val events1 = c1.await()
            val events2 = c2.await()

            assertEquals(1, events1.size)
            assertEquals(ApiEventType.AUTH_EXPIRED, events1[0].event)
            assertEquals(1, events2.size)
            assertEquals(ApiEventType.AUTH_EXPIRED, events2[0].event)

            bus.unsubscribe("sub-bus-1")
            bus.unsubscribe("sub-bus-2")
        }

    // -------------------------------------------------------------------------
    // Event payload
    // -------------------------------------------------------------------------

    @Test
    fun `buildEvent populates all fields correctly`() {
        val bus = ApiEventBus()
        val itemId = UUID.randomUUID()
        val now = Instant.now()

        val event = bus.buildEvent(ApiEventType.ITEM_ADVANCED, itemId = itemId, modifiedAt = now, newRole = "work")

        assertTrue(event.id > 0)
        assertEquals(ApiEventType.ITEM_ADVANCED, event.event)
        assertEquals(itemId.toString(), event.itemId)
        assertEquals(now.toString(), event.modifiedAt)
        assertEquals("work", event.newRole)
    }

    @Test
    fun `buildEvent with no itemId or modifiedAt produces null fields`() {
        val bus = ApiEventBus()
        val event = bus.buildEvent(ApiEventType.SYNC_LOST)

        assertNull(event.itemId)
        assertNull(event.modifiedAt)
        assertNull(event.newRole)
    }

    // -------------------------------------------------------------------------
    // Last-Event-ID replay — root-scope filtering (security regression tests)
    // -------------------------------------------------------------------------

    @Test
    fun `Last-Event-ID replay does not leak out-of-scope root events to root-scoped subscriber`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val rootA = UUID.randomUUID()
            val rootB = UUID.randomUUID()

            // Publish 3 events for rootA without any subscriber (buffered in ring buffer)
            repeat(3) {
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                bus.publish(e, affectedRoots = setOf(rootA))
            }

            // Subscribe with rootIds={rootB} and replay from id=0
            // A token scoped to rootB must NOT receive the rootA-only buffered events
            val flow = bus.subscribe("sub-rootB-only", setOf(rootB), lastEventId = 0L)
            val received =
                async {
                    withTimeoutOrNull(500) { flow.take(1).toList() }
                }

            val result = received.await()
            assertNull(result, "Root-scoped subscriber must NOT receive replayed events from out-of-scope roots")
            bus.unsubscribe("sub-rootB-only")
        }

    @Test
    fun `Last-Event-ID replay delivers only in-scope events to root-scoped subscriber`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val rootA = UUID.randomUUID()
            val rootB = UUID.randomUUID()
            val itemA = UUID.randomUUID()
            val itemB = UUID.randomUUID()

            // Publish events for rootA and rootB without any subscriber (buffered)
            val evtA = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemA, modifiedAt = Instant.now())
            bus.publish(evtA, affectedRoots = setOf(rootA))
            val evtB = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemB, modifiedAt = Instant.now())
            bus.publish(evtB, affectedRoots = setOf(rootB))

            // Subscribe scoped to rootB — should only replay the rootB event
            val flow = bus.subscribe("sub-rootB-replay", setOf(rootB), lastEventId = 0L)

            val collected =
                async {
                    withTimeout(3.seconds) {
                        flow.take(1).toList()
                    }
                }

            val events = collected.await()
            assertEquals(1, events.size, "Expected exactly 1 in-scope replayed event")
            assertEquals(evtB.id, events[0].id, "Expected the rootB event, not the rootA event")
            assertEquals(itemB.toString(), events[0].itemId)
            bus.unsubscribe("sub-rootB-replay")
        }

    @Test
    fun `Last-Event-ID replay delivers all events to unrestricted subscriber`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val rootA = UUID.randomUUID()
            val rootB = UUID.randomUUID()

            // Publish 2 events for different roots (no subscriber yet)
            repeat(2) { i ->
                val root = if (i == 0) rootA else rootB
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                bus.publish(e, affectedRoots = setOf(root))
            }

            // Unrestricted subscriber (emptySet) should receive ALL buffered events
            val flow = bus.subscribe("sub-unrestricted", emptySet(), lastEventId = 0L)

            val collected =
                async {
                    withTimeout(3.seconds) {
                        flow.take(2).toList()
                    }
                }

            val events = collected.await()
            assertEquals(2, events.size, "Unrestricted subscriber must receive all replayed events regardless of roots")
            bus.unsubscribe("sub-unrestricted")
        }

    @Test
    fun `Last-Event-ID replay delivers bus-level events (emptySet roots) to all root-scoped subscribers`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val rootA = UUID.randomUUID()

            // Publish a bus-level event (sync.lost / auth.expired use emptySet as affectedRoots)
            val busEvt = bus.buildEvent(ApiEventType.AUTH_EXPIRED)
            bus.publish(busEvt, affectedRoots = emptySet())

            // Root-scoped subscriber MUST receive the bus-level event on replay
            val flow = bus.subscribe("sub-rootA-replay", setOf(rootA), lastEventId = 0L)

            val collected =
                async {
                    withTimeout(3.seconds) {
                        flow.take(1).toList()
                    }
                }

            val events = collected.await()
            assertEquals(1, events.size, "Bus-level events (emptySet affectedRoots) must replay to all subscribers")
            assertEquals(ApiEventType.AUTH_EXPIRED, events[0].event)
            bus.unsubscribe("sub-rootA-replay")
        }

    // -------------------------------------------------------------------------
    // Subscriber count and cleanup
    // -------------------------------------------------------------------------

    @Test
    fun `unsubscribe removes the subscriber and closes its channel`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val root = UUID.randomUUID()
            bus.subscribe("sub-cleanup", setOf(root), null)
            assertEquals(1, bus.subscriberCount())
            bus.unsubscribe("sub-cleanup")
            assertEquals(0, bus.subscriberCount())
        }

    @Test
    fun `unsubscribe is idempotent`() {
        val bus = ApiEventBus()
        bus.unsubscribe("non-existent")
        // should not throw
    }
}
