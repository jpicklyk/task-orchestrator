package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.service.TreeDepSpec
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeInput
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
 * Write-path SSE event coverage for [EventPublishingRepositoryProvider] — item 646b12a6.
 *
 * Oracles: [R] = current/docs/api-rest.md §21 Event Types table; [D] = the item's `diagnosis`
 * note's Fix mapping; [T] = [DeferredEventPublisher] KDoc invariants (rollback publishes nothing).
 * Harness: a decorated provider over a real (H2) [buildH2RepositoryProvider], with an unrestricted
 * subscriber (`rootIds = emptySet()`, matches every event regardless of root resolution) connected
 * BEFORE the write under test, per the item's frozen `test-plan`.
 *
 * Every scenario subscribes, performs the write under test, then drains via [drainDelivered] —
 * see that helper's KDoc for why no fixed wait is needed (O5, item 646b12a6).
 */
class EventPublishingWriteCoverageTest {
    // -------------------------------------------------------------------------
    // S1 — createBatch happy path
    // -------------------------------------------------------------------------

    /** S1: createBatch([A→B, A→C]) → exactly 2 dependency.added, itemId=A each. [R][D] */
    @Test
    fun `S1 createBatch of two dependencies from the same origin emits two dependency added events`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val a = provider.workItemRepository().create(WorkItem(title = "A", depth = 0)).getOrNull()!!
            val b = provider.workItemRepository().create(WorkItem(title = "B", depth = 0)).getOrNull()!!
            val c = provider.workItemRepository().create(WorkItem(title = "C", depth = 0)).getOrNull()!!

            val flow = bus.subscribe("s1", emptySet(), lastEventId = null)

            provider.dependencyRepository().createBatch(
                listOf(
                    Dependency(fromItemId = a.id, toItemId = b.id, type = DependencyType.BLOCKS),
                    Dependency(fromItemId = a.id, toItemId = c.id, type = DependencyType.BLOCKS),
                ),
            )

            val events = bus.drainDelivered("s1", flow)
            assertEquals(2, events.size, "expected exactly 2 events, got: $events")
            assertTrue(events.all { it.event == ApiEventType.DEPENDENCY_ADDED })
            assertEquals(setOf(a.id.toString()), events.map { it.itemId }.toSet())
        }

    // -------------------------------------------------------------------------
    // S2 — dependency deleteByItemId happy path
    // -------------------------------------------------------------------------

    /**
     * S2: dep deleteByItemId(B) with edges A→B, B→C → 2 dependency.removed, itemIds {A,B}
     * (per pre-read edge, itemId = that edge's fromItemId). [R][D]
     */
    @Test
    fun `S2 dependency deleteByItemId emits dependency removed per pre-read edge`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val a = provider.workItemRepository().create(WorkItem(title = "A2", depth = 0)).getOrNull()!!
            val b = provider.workItemRepository().create(WorkItem(title = "B2", depth = 0)).getOrNull()!!
            val c = provider.workItemRepository().create(WorkItem(title = "C2", depth = 0)).getOrNull()!!
            provider.dependencyRepository().createBatch(
                listOf(
                    Dependency(fromItemId = a.id, toItemId = b.id, type = DependencyType.BLOCKS),
                    Dependency(fromItemId = b.id, toItemId = c.id, type = DependencyType.BLOCKS),
                ),
            )

            val flow = bus.subscribe("s2", emptySet(), lastEventId = null)

            provider.dependencyRepository().deleteByItemId(b.id)

            val events = bus.drainDelivered("s2", flow)
            assertEquals(2, events.size, "expected exactly 2 events, got: $events")
            assertTrue(events.all { it.event == ApiEventType.DEPENDENCY_REMOVED })
            assertEquals(setOf(a.id.toString(), b.id.toString()), events.map { it.itemId }.toSet())
        }

    // -------------------------------------------------------------------------
    // S3 — note deleteByItemId happy path
    // -------------------------------------------------------------------------

    /** S3: note deleteByItemId(X) with 2 notes → exactly 1 note.deleted itemId=X. [D] */
    @Test
    fun `S3 note deleteByItemId with multiple notes emits exactly one note deleted event`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val x = provider.workItemRepository().create(WorkItem(title = "X3", depth = 0)).getOrNull()!!
            provider.noteRepository().upsert(Note(itemId = x.id, key = "note-a", role = "work", body = "a"))
            provider.noteRepository().upsert(Note(itemId = x.id, key = "note-b", role = "work", body = "b"))

            val flow = bus.subscribe("s3", emptySet(), lastEventId = null)

            provider.noteRepository().deleteByItemId(x.id)

            val events = bus.drainDelivered("s3", flow)
            assertEquals(1, events.size, "expected exactly 1 event, got: $events")
            assertEquals(ApiEventType.NOTE_DELETED, events[0].event)
            assertEquals(x.id.toString(), events[0].itemId)
        }

    // -------------------------------------------------------------------------
    // S4 — item deleteAll happy path (mixed real + missing ids)
    // -------------------------------------------------------------------------

    /** S4: item deleteAll({a,b,missingId}) → item.deleted for a and b only. [R][D] */
    @Test
    fun `S4 deleteAll emits item deleted only for ids that actually existed`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val a = provider.workItemRepository().create(WorkItem(title = "A4", depth = 0)).getOrNull()!!
            val b = provider.workItemRepository().create(WorkItem(title = "B4", depth = 0)).getOrNull()!!
            val missingId = UUID.randomUUID()

            val flow = bus.subscribe("s4", emptySet(), lastEventId = null)

            provider.workItemRepository().deleteAll(setOf(a.id, b.id, missingId))

            val events = bus.drainDelivered("s4", flow)
            assertEquals(2, events.size, "expected exactly 2 events, got: $events")
            assertTrue(events.all { it.event == ApiEventType.ITEM_DELETED })
            assertEquals(setOf(a.id.toString(), b.id.toString()), events.map { it.itemId }.toSet())
        }

    // -------------------------------------------------------------------------
    // S8 — work-tree creation happy path (root-first ordering across all 3 event kinds)
    // -------------------------------------------------------------------------

    /**
     * S8: decorated `workTreeExecutor().execute(root R + children C1,C2 + dep C1→C2 + note on C1)`
     * → 3 item.created {R,C1,C2} R first, 1 dependency.added itemId=C1, 1 note.upserted itemId=C1.
     * [R][D]
     */
    @Test
    fun `S8 work tree creation emits item created root-first plus dependency added and note upserted`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val rootId = UUID.randomUUID()
            val c1Id = UUID.randomUUID()
            val c2Id = UUID.randomUUID()
            val root = WorkItem(id = rootId, title = "Tree Root S8", depth = 0)
            val c1 = WorkItem(id = c1Id, parentId = rootId, depth = 1, title = "Child C1 S8")
            val c2 = WorkItem(id = c2Id, parentId = rootId, depth = 1, title = "Child C2 S8")
            val input =
                WorkTreeInput(
                    items = listOf(root, c1, c2),
                    refToItem = mapOf("R" to root, "C1" to c1, "C2" to c2),
                    deps = listOf(TreeDepSpec(fromRef = "C1", toRef = "C2", type = DependencyType.BLOCKS, unblockAt = null)),
                    notes = listOf(Note(itemId = c1Id, key = "tree-note-s8", role = "work", body = "tree note")),
                )

            val flow = bus.subscribe("s8", emptySet(), lastEventId = null)

            provider.workTreeExecutor().execute(input)

            val events = bus.drainDelivered("s8", flow)
            assertEquals(5, events.size, "expected exactly 5 events, got: $events")

            val itemCreated = events.filter { it.event == ApiEventType.ITEM_CREATED }
            assertEquals(3, itemCreated.size)
            assertEquals(
                setOf(rootId.toString(), c1Id.toString(), c2Id.toString()),
                itemCreated.map { it.itemId }.toSet(),
            )
            assertEquals(ApiEventType.ITEM_CREATED, events[0].event)
            assertEquals(rootId.toString(), events[0].itemId, "root item.created must be emitted first")

            val depAdded = events.filter { it.event == ApiEventType.DEPENDENCY_ADDED }
            assertEquals(1, depAdded.size)
            assertEquals(c1Id.toString(), depAdded[0].itemId)

            val noteUpserted = events.filter { it.event == ApiEventType.NOTE_UPSERTED }
            assertEquals(1, noteUpserted.size)
            assertEquals(c1Id.toString(), noteUpserted[0].itemId)
        }

    // -------------------------------------------------------------------------
    // S9 — createBatch failure (in-batch duplicate)
    // -------------------------------------------------------------------------

    /** S9: createBatch with an in-batch duplicate throws → 0 events. [D "per RETURNED dep"] */
    @Test
    fun `S9 createBatch with an in-batch duplicate throws and emits no events`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val a = provider.workItemRepository().create(WorkItem(title = "A9", depth = 0)).getOrNull()!!
            val b = provider.workItemRepository().create(WorkItem(title = "B9", depth = 0)).getOrNull()!!

            val flow = bus.subscribe("s9", emptySet(), lastEventId = null)

            var threw = false
            try {
                provider.dependencyRepository().createBatch(
                    listOf(
                        Dependency(fromItemId = a.id, toItemId = b.id, type = DependencyType.BLOCKS),
                        Dependency(fromItemId = a.id, toItemId = b.id, type = DependencyType.BLOCKS),
                    ),
                )
            } catch (e: Exception) {
                threw = true
            }

            assertTrue(threw, "expected an exception for an in-batch duplicate dependency edge")
            val events = bus.drainDelivered("s9", flow)
            assertTrue(events.isEmpty(), "no dependency.added event may be published on a thrown createBatch, got: $events")
        }

    // -------------------------------------------------------------------------
    // S11 — work-tree creation failure (cyclic dependency)
    // -------------------------------------------------------------------------

    /** S11: execute with cyclic deps throws → 0 events. [D][T] */
    @Test
    fun `S11 work tree creation with a cyclic dependency throws and emits no events`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val rootId = UUID.randomUUID()
            val c1Id = UUID.randomUUID()
            val c2Id = UUID.randomUUID()
            val root = WorkItem(id = rootId, title = "Tree Root S11", depth = 0)
            val c1 = WorkItem(id = c1Id, parentId = rootId, depth = 1, title = "Child C1 S11")
            val c2 = WorkItem(id = c2Id, parentId = rootId, depth = 1, title = "Child C2 S11")
            val input =
                WorkTreeInput(
                    items = listOf(root, c1, c2),
                    refToItem = mapOf("R" to root, "C1" to c1, "C2" to c2),
                    deps =
                        listOf(
                            TreeDepSpec(fromRef = "C1", toRef = "C2", type = DependencyType.BLOCKS, unblockAt = null),
                            TreeDepSpec(fromRef = "C2", toRef = "C1", type = DependencyType.BLOCKS, unblockAt = null),
                        ),
                    notes = emptyList(),
                )

            val flow = bus.subscribe("s11", emptySet(), lastEventId = null)

            var threw = false
            try {
                provider.workTreeExecutor().execute(input)
            } catch (e: Exception) {
                threw = true
            }

            assertTrue(threw, "expected an exception for a cyclic in-tree dependency")
            val events = bus.drainDelivered("s11", flow)
            assertTrue(events.isEmpty(), "no events may be published when work-tree creation throws, got: $events")
        }

    // -------------------------------------------------------------------------
    // S12 — work-tree creation inside a rolled-back outer transaction
    // -------------------------------------------------------------------------

    /**
     * S12: execute called inside `workItemRepository().inTransaction { execute(...); throw }`
     * → 0 events after rollback, 0 rows. [T][D decision (b)]
     */
    @Test
    fun `S12 work tree creation inside a rolled back transaction emits no events and persists no rows`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val rootId = UUID.randomUUID()
            val c1Id = UUID.randomUUID()
            val c2Id = UUID.randomUUID()
            val root = WorkItem(id = rootId, title = "Tree Root S12", depth = 0)
            val c1 = WorkItem(id = c1Id, parentId = rootId, depth = 1, title = "Child C1 S12")
            val c2 = WorkItem(id = c2Id, parentId = rootId, depth = 1, title = "Child C2 S12")
            val input =
                WorkTreeInput(
                    items = listOf(root, c1, c2),
                    refToItem = mapOf("R" to root, "C1" to c1, "C2" to c2),
                    deps = listOf(TreeDepSpec(fromRef = "C1", toRef = "C2", type = DependencyType.BLOCKS, unblockAt = null)),
                    notes = listOf(Note(itemId = c1Id, key = "tree-note-s12", role = "work", body = "tree note")),
                )

            val flow = bus.subscribe("s12", emptySet(), lastEventId = null)

            var threw = false
            try {
                provider.workItemRepository().inTransaction {
                    provider.workTreeExecutor().execute(input)
                    throw RuntimeException("forced rollback for S12")
                }
            } catch (e: RuntimeException) {
                threw = true
            }

            assertTrue(threw, "the forced exception must propagate out of inTransaction")
            val events = bus.drainDelivered("s12", flow)
            assertTrue(events.isEmpty(), "no events may be published once the outer transaction rolls back, got: $events")

            val rows = provider.workItemRepository().findByIds(setOf(rootId, c1Id, c2Id)).getOrNull()
            assertTrue(rows.isNullOrEmpty(), "no rows may persist after the outer transaction rolls back, got: $rows")
        }

    // -------------------------------------------------------------------------
    // S14 — edge: no-op writes emit nothing
    // -------------------------------------------------------------------------

    /**
     * S14: createBatch(empty), deleteAll(emptySet), note/dep deleteByItemId on an item with none
     * → 0 events each. [D "if count>0"]
     */
    @Test
    fun `S14 no-op writes emit no events`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val lonely = provider.workItemRepository().create(WorkItem(title = "Lonely S14", depth = 0)).getOrNull()!!

            run {
                val flow = bus.subscribe("s14-a", emptySet(), lastEventId = null)
                provider.dependencyRepository().createBatch(emptyList())
                assertTrue(bus.drainDelivered("s14-a", flow).isEmpty(), "createBatch(emptyList()) must emit no events")
            }
            run {
                val flow = bus.subscribe("s14-b", emptySet(), lastEventId = null)
                provider.workItemRepository().deleteAll(emptySet())
                assertTrue(bus.drainDelivered("s14-b", flow).isEmpty(), "deleteAll(emptySet()) must emit no events")
            }
            run {
                val flow = bus.subscribe("s14-c", emptySet(), lastEventId = null)
                provider.noteRepository().deleteByItemId(lonely.id)
                assertTrue(bus.drainDelivered("s14-c", flow).isEmpty(), "deleteByItemId on an item with no notes must emit no events")
            }
            run {
                val flow = bus.subscribe("s14-d", emptySet(), lastEventId = null)
                provider.dependencyRepository().deleteByItemId(lonely.id)
                assertTrue(
                    bus.drainDelivered("s14-d", flow).isEmpty(),
                    "deleteByItemId on an item with no dependencies must emit no events",
                )
            }
        }

    // -------------------------------------------------------------------------
    // S15 — edge: attach-mode tree (existing root not re-created)
    // -------------------------------------------------------------------------

    /** S15: attach-mode tree (existing root in refToItem, not in items) → no item.created for it. [D] */
    @Test
    fun `S15 attach-mode work tree does not emit item created for the pre-existing root`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val existingRoot =
                provider.workItemRepository().create(WorkItem(title = "Existing Root S15", depth = 0)).getOrNull()!!
            val c1Id = UUID.randomUUID()
            val c1 = WorkItem(id = c1Id, parentId = existingRoot.id, depth = 1, title = "Attached Child S15")
            val input =
                WorkTreeInput(
                    items = listOf(c1),
                    refToItem = mapOf("R" to existingRoot, "C1" to c1),
                    deps = emptyList(),
                    notes = emptyList(),
                )

            val flow = bus.subscribe("s15", emptySet(), lastEventId = null)

            provider.workTreeExecutor().execute(input)

            val events = bus.drainDelivered("s15", flow)
            assertEquals(1, events.size, "expected exactly 1 event, got: $events")
            assertEquals(ApiEventType.ITEM_CREATED, events[0].event)
            assertEquals(c1Id.toString(), events[0].itemId)
        }
}
