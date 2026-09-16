package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.buildH2RepositoryProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Independent test authorship for item 0e9d5675 (needs-test-author).
 *
 * Oracles (frozen in test-plan note 46ed4b0a, before this file was written):
 *  O1 current/docs/api-rest.md:1462-1475,:1493 — monotonic id namespace; Last-Event-ID ring-buffer
 *     replay; a data event denotes a persisted change.
 *  O2 this item's diagnosis note, "Corrections" 3 — the four real enclosing sites able to roll
 *     back AFTER a publish (RoleTransitionHandler, DeleteItemHandler, UpdateItemHandler reparent,
 *     the REST PATCH reparent cascade) — these tests exercise the same *shape* (update / delete
 *     inside an outer `inTransaction` that then rolls back) directly through the decorator, one
 *     level below those call sites.
 *  O3 WorkItemRepository.kt:100-111 KDoc: "if [block] throws, all writes are rolled back
 *     atomically".
 *
 * Unlike [DeferredEventPublisherTest], these tests drive [EventPublishingRepositoryProvider] end
 * to end — real decorated `create`/`update`/`delete`/note-`upsert` calls — so they also pin the
 * unchanged success path (S8) and confirm the fix is orthogonal to subscriber count (S9).
 *
 * S4, S5, S8 are EXISTING-SURFACE: [EventPublishingRepositoryProvider]'s public surface,
 * [WorkItemRepository.inTransaction][io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository],
 * `create`/`update`/`delete`/`upsert` all predate this fix. A plain revert of the fix (routing
 * every publish site back through immediate `eventBus.publish(eventBus.buildEvent(...), roots)`)
 * yields genuine behavioral red on S4/S5 (rolled-back events would leak) without touching
 * compilation. S9 is the same shape with an explicit zero-subscriber precondition.
 */
class EventPublishingTransactionRollbackTest {
    // -------------------------------------------------------------------------
    // S4 — update() inside a rolling-back transaction
    // -------------------------------------------------------------------------

    @Test
    fun `S4 - update inside a rolling-back transaction publishes zero item_updated or item_advanced events`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            // Created OUTSIDE any transaction opened by this test, so it already published
            // synchronously (its own internal suspendTransaction commits before the decorator
            // publishes). We only assert on events published AFTER this baseline.
            val item = provider.workItemRepository().create(WorkItem(title = "S4 Item", depth = 0)).getOrNull()!!
            val baselineCount = bus.ringBufferSnapshot().size

            var caught: Throwable? = null
            try {
                provider.workItemRepository().inTransaction {
                    provider.workItemRepository().update(item.copy(title = "S4 Item Renamed"))
                    throw IllegalStateException("boom")
                }
            } catch (e: IllegalStateException) {
                caught = e
            }
            assertTrue(caught is IllegalStateException, "expected the transaction failure to propagate, got: $caught")

            val afterEvents = bus.ringBufferSnapshot().drop(baselineCount)
            assertTrue(
                afterEvents.none { it.event == ApiEventType.ITEM_UPDATED || it.event == ApiEventType.ITEM_ADVANCED },
                "a rolled-back update must publish zero item.updated/item.advanced events (got: $afterEvents)",
            )
        }

    // -------------------------------------------------------------------------
    // S5 — cascading delete()s inside a rolling-back transaction
    // -------------------------------------------------------------------------

    @Test
    fun `S5 - cascading deletes inside a rolling-back transaction publish zero item_deleted events`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val itemA = provider.workItemRepository().create(WorkItem(title = "S5 A", depth = 0)).getOrNull()!!
            val itemB = provider.workItemRepository().create(WorkItem(title = "S5 B", depth = 0)).getOrNull()!!
            val baselineCount = bus.ringBufferSnapshot().size

            var caught: Throwable? = null
            try {
                provider.workItemRepository().inTransaction {
                    provider.workItemRepository().delete(itemA.id)
                    provider.workItemRepository().delete(itemB.id)
                    throw IllegalStateException("boom")
                }
            } catch (e: IllegalStateException) {
                caught = e
            }
            assertTrue(caught is IllegalStateException, "expected the transaction failure to propagate, got: $caught")

            val afterEvents = bus.ringBufferSnapshot().drop(baselineCount)
            assertTrue(
                afterEvents.none { it.event == ApiEventType.ITEM_DELETED },
                "a rolled-back cascade delete must publish zero item.deleted events, mirroring " +
                    "DeleteItemHandler's shape per diagnosis §Corrections-3 (got: $afterEvents)",
            )
        }

    // -------------------------------------------------------------------------
    // S8 — success path (no enclosing transaction) is unchanged
    // -------------------------------------------------------------------------

    @Test
    fun `S8 - outside any transaction, create then note upsert still deliver item_created then note_upserted in order`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)
            val baselineCount = bus.ringBufferSnapshot().size

            val item = provider.workItemRepository().create(WorkItem(title = "S8 Item", depth = 0)).getOrNull()!!
            provider.noteRepository().upsert(Note(itemId = item.id, key = "s8-note", role = "queue", body = "hello"))

            val events = bus.ringBufferSnapshot().drop(baselineCount)
            assertEquals(2, events.size, "create + note upsert must each publish exactly one event (got: $events)")
            assertEquals(ApiEventType.ITEM_CREATED, events[0].event)
            assertEquals(ApiEventType.NOTE_UPSERTED, events[1].event)
            assertTrue(events[0].id < events[1].id, "events published outside a transaction keep commit-time id order")
        }

    // -------------------------------------------------------------------------
    // S9 — the fix is orthogonal to subscriber count
    // -------------------------------------------------------------------------

    @Test
    fun `S9 - with zero subscribers a rolled-back transaction still leaves the ring buffer empty`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)
            assertEquals(0, bus.subscriberCount(), "precondition: no subscriber connected")

            val item = provider.workItemRepository().create(WorkItem(title = "S9 Item", depth = 0)).getOrNull()!!
            val baselineCount = bus.ringBufferSnapshot().size

            var caught: Throwable? = null
            try {
                provider.workItemRepository().inTransaction {
                    provider.workItemRepository().update(item.copy(title = "S9 Item Renamed"))
                    throw IllegalStateException("boom")
                }
            } catch (e: IllegalStateException) {
                caught = e
            }
            assertTrue(caught is IllegalStateException, "expected the transaction failure to propagate, got: $caught")

            assertEquals(0, bus.subscriberCount(), "still zero subscribers")
            assertEquals(
                baselineCount,
                bus.ringBufferSnapshot().size,
                "a rolled-back publish adds nothing to the ring buffer regardless of subscriber count",
            )
        }
}
