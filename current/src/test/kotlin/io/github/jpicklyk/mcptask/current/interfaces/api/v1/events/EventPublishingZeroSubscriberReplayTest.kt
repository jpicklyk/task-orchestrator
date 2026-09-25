package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.buildH2RepositoryProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * O3 — the 4 guarded delete paths must publish (buffered, UNRESOLVED) even at 0 subscribers, so a
 * later `Last-Event-ID` resume can still replay them — item 646b12a6 follow-up.
 *
 * Oracles: [R21] current/docs/api-rest.md §21 Delivery guarantee + Last-Event-ID + Bulk-write note
 * ("every domain event ... is published after the write's database transaction commits" / a
 * reconnect replays "events with `id > Last-Event-ID`" / "removing all of an item's dependencies
 * emits one `dependency.removed` per edge", with no subscriber condition). [K] = [ApiEventBus] KDoc
 * for `publish`/`subscribe`, on the fail-closed treatment of unresolved ring-buffer entries.
 *
 * Harness (per the item's frozen `test-plan`): all setup writes happen with NO subscriber
 * connected — every write in this file, setup or under-test, sees `subscriberCount() == 0` at the
 * moment [EventPublishingRepositoryProvider.resolveRoots] is consulted, so every buffered entry is
 * UNRESOLVED. Capture `w = bus.ringBufferSnapshot().last().id` right before the write under test,
 * perform it (asserting `subscriberCount() == 0` first), then `subscribe(id, emptySet(), lastEventId
 * = w)` (an UNRESTRICTED subscriber — entitled to unresolved entries) and [drainDelivered] the
 * replay.
 */
class EventPublishingZeroSubscriberReplayTest {
    private fun lastBufferedId(bus: ApiEventBus): Long = bus.ringBufferSnapshot().last().id

    // -------------------------------------------------------------------------
    // S1 — note delete(id) at 0 subscribers
    // -------------------------------------------------------------------------

    /** S1: note delete(id) at 0 subscribers → replay has exactly 1 note.deleted, itemId=X, no sync.lost. [R21] */
    @Test
    fun `S1 note delete at zero subscribers is still replayed on resume`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val x = provider.workItemRepository().create(WorkItem(title = "X-S1", depth = 0)).getOrNull()!!
            val note =
                provider
                    .noteRepository()
                    .upsert(Note(itemId = x.id, key = "note-s1", role = "work", body = "a"))
                    .getOrNull()!!

            val w = lastBufferedId(bus)
            assertEquals(0, bus.subscriberCount(), "setup must leave zero subscribers connected")

            provider.noteRepository().delete(note.id)

            val flow = bus.subscribe("zsr-s1", emptySet(), lastEventId = w)
            val events = bus.drainDelivered("zsr-s1", flow)

            assertEquals(1, events.size, "expected exactly 1 replayed event, got: $events")
            assertEquals(ApiEventType.NOTE_DELETED, events[0].event)
            assertEquals(x.id.toString(), events[0].itemId)
            assertTrue(events.none { it.event == ApiEventType.SYNC_LOST }, "no sync.lost expected, got: $events")
        }

    // -------------------------------------------------------------------------
    // S2 — dependency delete(id) at 0 subscribers
    // -------------------------------------------------------------------------

    /** S2: dep delete(id) A→B → 1 dependency.removed, itemId=A. [R21] */
    @Test
    fun `S2 dependency delete at zero subscribers is still replayed on resume`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val a = provider.workItemRepository().create(WorkItem(title = "A-S2", depth = 0)).getOrNull()!!
            val b = provider.workItemRepository().create(WorkItem(title = "B-S2", depth = 0)).getOrNull()!!
            val dep = provider.dependencyRepository().create(Dependency(fromItemId = a.id, toItemId = b.id, type = DependencyType.BLOCKS))

            val w = lastBufferedId(bus)
            assertEquals(0, bus.subscriberCount(), "setup must leave zero subscribers connected")

            provider.dependencyRepository().delete(dep.id)

            val flow = bus.subscribe("zsr-s2", emptySet(), lastEventId = w)
            val events = bus.drainDelivered("zsr-s2", flow)

            assertEquals(1, events.size, "expected exactly 1 replayed event, got: $events")
            assertEquals(ApiEventType.DEPENDENCY_REMOVED, events[0].event)
            assertEquals(a.id.toString(), events[0].itemId)
        }

    // -------------------------------------------------------------------------
    // S3 — dependency deleteByItemId at 0 subscribers
    // -------------------------------------------------------------------------

    /** S3: dep deleteByItemId(B) with A→B and B→C → 2 dependency.removed, itemIds {A,B}. [R21 bulk-write note] */
    @Test
    fun `S3 dependency deleteByItemId at zero subscribers is still replayed on resume`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val a = provider.workItemRepository().create(WorkItem(title = "A-S3", depth = 0)).getOrNull()!!
            val b = provider.workItemRepository().create(WorkItem(title = "B-S3", depth = 0)).getOrNull()!!
            val c = provider.workItemRepository().create(WorkItem(title = "C-S3", depth = 0)).getOrNull()!!
            provider.dependencyRepository().create(Dependency(fromItemId = a.id, toItemId = b.id, type = DependencyType.BLOCKS))
            provider.dependencyRepository().create(Dependency(fromItemId = b.id, toItemId = c.id, type = DependencyType.BLOCKS))

            val w = lastBufferedId(bus)
            assertEquals(0, bus.subscriberCount(), "setup must leave zero subscribers connected")

            provider.dependencyRepository().deleteByItemId(b.id)

            val flow = bus.subscribe("zsr-s3", emptySet(), lastEventId = w)
            val events = bus.drainDelivered("zsr-s3", flow)

            assertEquals(2, events.size, "expected exactly 2 replayed events, got: $events")
            assertTrue(events.all { it.event == ApiEventType.DEPENDENCY_REMOVED })
            assertEquals(setOf(a.id.toString(), b.id.toString()), events.map { it.itemId }.toSet())
        }

    // -------------------------------------------------------------------------
    // S4 — item deleteAll at 0 subscribers
    // -------------------------------------------------------------------------

    /** S4: item deleteAll({a,b,missing}) → 2 item.deleted {a,b}. [R21] */
    @Test
    fun `S4 item deleteAll at zero subscribers is still replayed on resume`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val a = provider.workItemRepository().create(WorkItem(title = "A-S4", depth = 0)).getOrNull()!!
            val b = provider.workItemRepository().create(WorkItem(title = "B-S4", depth = 0)).getOrNull()!!
            val missingId = UUID.randomUUID()

            val w = lastBufferedId(bus)
            assertEquals(0, bus.subscriberCount(), "setup must leave zero subscribers connected")

            provider.workItemRepository().deleteAll(setOf(a.id, b.id, missingId))

            val flow = bus.subscribe("zsr-s4", emptySet(), lastEventId = w)
            val events = bus.drainDelivered("zsr-s4", flow)

            assertEquals(2, events.size, "expected exactly 2 replayed events, got: $events")
            assertTrue(events.all { it.event == ApiEventType.ITEM_DELETED })
            assertEquals(setOf(a.id.toString(), b.id.toString()), events.map { it.itemId }.toSet())
        }

    // -------------------------------------------------------------------------
    // S5 — regression pin: the 2 paths that already published at 0 subscribers
    // -------------------------------------------------------------------------

    /**
     * S5 regression pin: item delete(id) and note deleteByItemId at 0 subscribers → 1 event each.
     * These two paths had no `subscriberCount()` guard even before this fix — green before and
     * after. [R21]
     */
    @Test
    fun `S5 item delete and note deleteByItemId at zero subscribers were already replayed and remain so`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            run {
                val x = provider.workItemRepository().create(WorkItem(title = "X-S5a", depth = 0)).getOrNull()!!
                val w = lastBufferedId(bus)
                assertEquals(0, bus.subscriberCount())

                provider.workItemRepository().delete(x.id)

                val flow = bus.subscribe("zsr-s5a", emptySet(), lastEventId = w)
                val events = bus.drainDelivered("zsr-s5a", flow)
                assertEquals(1, events.size, "expected exactly 1 replayed event, got: $events")
                assertEquals(ApiEventType.ITEM_DELETED, events[0].event)
                assertEquals(x.id.toString(), events[0].itemId)
            }

            run {
                val y = provider.workItemRepository().create(WorkItem(title = "Y-S5b", depth = 0)).getOrNull()!!
                provider.noteRepository().upsert(Note(itemId = y.id, key = "note-s5b", role = "work", body = "a"))
                val w = lastBufferedId(bus)
                assertEquals(0, bus.subscriberCount())

                provider.noteRepository().deleteByItemId(y.id)

                val flow = bus.subscribe("zsr-s5b", emptySet(), lastEventId = w)
                val events = bus.drainDelivered("zsr-s5b", flow)
                assertEquals(1, events.size, "expected exactly 1 replayed event, got: $events")
                assertEquals(ApiEventType.NOTE_DELETED, events[0].event)
                assertEquals(y.id.toString(), events[0].itemId)
            }
        }

    // -------------------------------------------------------------------------
    // S6 — failure: delete of a nonexistent id emits nothing
    // -------------------------------------------------------------------------

    /** S6: note delete / dep delete of a nonexistent id → 0 replayed events. [R21: only committed deletes are events] */
    @Test
    fun `S6 delete of a nonexistent note or dependency id emits no events`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            run {
                val w = bus.ringBufferSnapshot().lastOrNull()?.id
                assertEquals(0, bus.subscriberCount())

                provider.noteRepository().delete(UUID.randomUUID())

                val flow = bus.subscribe("zsr-s6a", emptySet(), lastEventId = w)
                val events = bus.drainDelivered("zsr-s6a", flow)
                assertTrue(events.isEmpty(), "no event may be published for a nonexistent note id, got: $events")
            }

            run {
                val w = bus.ringBufferSnapshot().lastOrNull()?.id
                assertEquals(0, bus.subscriberCount())

                provider.dependencyRepository().delete(UUID.randomUUID())

                val flow = bus.subscribe("zsr-s6b", emptySet(), lastEventId = w)
                val events = bus.drainDelivered("zsr-s6b", flow)
                assertTrue(events.isEmpty(), "no event may be published for a nonexistent dependency id, got: $events")
            }
        }

    // -------------------------------------------------------------------------
    // S7 — failure: no-op bulk deletes emit nothing
    // -------------------------------------------------------------------------

    /** S7: deleteAll(emptySet()), dep deleteByItemId on an item with no edges, at 0 subscribers → 0 events. */
    @Test
    fun `S7 no-op bulk deletes at zero subscribers emit no events`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val lonely = provider.workItemRepository().create(WorkItem(title = "Lonely-S7", depth = 0)).getOrNull()!!

            run {
                val w = lastBufferedId(bus)
                assertEquals(0, bus.subscriberCount())

                provider.workItemRepository().deleteAll(emptySet())

                val flow = bus.subscribe("zsr-s7a", emptySet(), lastEventId = w)
                val events = bus.drainDelivered("zsr-s7a", flow)
                assertTrue(events.isEmpty(), "deleteAll(emptySet()) must emit no events, got: $events")
            }

            run {
                val w = lastBufferedId(bus)
                assertEquals(0, bus.subscriberCount())

                provider.dependencyRepository().deleteByItemId(lonely.id)

                val flow = bus.subscribe("zsr-s7b", emptySet(), lastEventId = w)
                val events = bus.drainDelivered("zsr-s7b", flow)
                assertTrue(
                    events.isEmpty(),
                    "deleteByItemId on an item with no dependencies must emit no events, got: $events",
                )
            }
        }

    // -------------------------------------------------------------------------
    // S8 — edge: ROOT-SCOPED resume never sees an unresolved delete event
    // -------------------------------------------------------------------------

    /**
     * S8: ROOT-SCOPED resume (`rootIds={root}`) after an S1-style delete at 0 subscribers → 0
     * replayed delete events and no sync.lost. Pins the documented fail-closed behaviour: an
     * unresolved ring-buffer entry (the no-subscriber perf guard in `resolveRoots`) is replayed
     * only to UNRESTRICTED subscribers, never to a root-scoped one — even though the deleted item
     * IS that root. [K]
     */
    @Test
    fun `S8 a root-scoped resume never receives an unresolved zero-subscriber delete event`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val x = provider.workItemRepository().create(WorkItem(title = "X-S8", depth = 0)).getOrNull()!!
            val note =
                provider
                    .noteRepository()
                    .upsert(Note(itemId = x.id, key = "note-s8", role = "work", body = "a"))
                    .getOrNull()!!

            val w = lastBufferedId(bus)
            assertEquals(0, bus.subscriberCount(), "setup must leave zero subscribers connected")

            provider.noteRepository().delete(note.id)

            val flow = bus.subscribe("zsr-s8", setOf(x.id), lastEventId = w)
            val events = bus.drainDelivered("zsr-s8", flow)

            assertTrue(
                events.none { it.event == ApiEventType.NOTE_DELETED },
                "a root-scoped subscriber must not receive an unresolved delete event, got: $events",
            )
            assertTrue(events.none { it.event == ApiEventType.SYNC_LOST }, "no sync.lost expected, got: $events")
        }
}
