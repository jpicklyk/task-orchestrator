package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.buildH2RepositoryProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Independent test authorship for item 33e96efd (needs-test-author).
 *
 * REFRAMED scope: this item does not change transaction-join behaviour (the ten blocking
 * `transaction{}` sites already join an enclosing `suspendTransaction` on the same [Database]
 * per the diagnosis's Exposed-bytecode corroboration). The live, user-visible defect is that
 * [EventPublishingRepositoryProvider]'s `create`/`delete` decorators could only call the
 * cache-only `resolveRootsCached` (the interface was non-suspend), so a `dependency.added` /
 * `dependency.removed` event for an item whose root was never cached carried `rootsResolved =
 * false` and was withheld from every root-scoped SSE subscriber. The fix makes 8 of 10
 * [io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository] methods `suspend`
 * and switches the decorator to the suspend `resolveRoots`, which does a real DB lookup
 * ([io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository.findAncestorChains])
 * on a cache miss instead of giving up.
 *
 * Oracles (frozen in test-plan note `558ec4a3-bce6-4234-b450-1dea9e51d218`, before this file was
 * written):
 *  O1 `EventPublishingRepositoryProvider.kt:121-135` (per DECLARATIONS) — an empty resolved root
 *     set is "could not resolve" and is withheld from root-scoped subscribers; a resolvable root
 *     must actually be resolved, not merely looked up in the cache.
 *  O2 `DeferredEventPublisher` KDoc `:48-58` (per DECLARATIONS / diagnosis note
 *     `85bd57e6-d0a3-4a6d-a244-daf0b8fcb586`) — publish-on-commit: no open transaction publishes
 *     synchronously; inside a transaction, publication is held until commit and discarded on
 *     rollback.
 *  O3 `WorkItemRepository.kt:100-111` KDoc — `inTransaction` rolls back all writes atomically if
 *     its block throws.
 *
 * Surface labels (test-plan): every scenario below is EXISTING-SURFACE — `create`/`delete`/
 * `findById` and `EventPublishingRepositoryProvider`'s decorator all predate this fix; the fix
 * only adds a `suspend` modifier (the same test source compiles unchanged before and after) and
 * swaps which private resolver the decorator calls. Red-proof for S1-S3 is a plain revert of that
 * `resolveRootsCached` -> `resolveRoots` switch (behavioural red, orchestrator-run). S4-S6 guard
 * already-correct wave-4 publish-on-commit behaviour that this fix must not regress; no
 * behavioural red is possible for them (reverting the fix does not change their outcome) —
 * substitute verification: the reviewer confirms each asserted value traces to its cited KDoc
 * clause (O2/O3), per the test-plan's declared substitute for this shape.
 *
 * Every subscriber-visible scenario waits on the public [ApiEventBus.subscriberCount] gate
 * (`awaitSubscriberCount`) rather than a fixed sleep: this is the exact counter `resolveRoots`
 * itself checks (subscriberCount()==0 short-circuits to emptySet — the diagnosis's "performance
 * guard", unchanged by this fix), so waiting on it is a real condition, not a guess at timing.
 */
class DependencyEventRootScopingTest {
    /**
     * Polls the public [ApiEventBus.subscriberCount] until it reaches [expected], bounded by
     * [timeoutMs]. Used instead of a fixed sleep: subscription registration timing is not
     * observable from this test any other way, but the exact counter the fix's `resolveRoots`
     * gates on is public and safe to poll.
     */
    private suspend fun awaitSubscriberCount(
        bus: ApiEventBus,
        expected: Int,
        timeoutMs: Long = 2_000,
    ) {
        withTimeout(timeoutMs) {
            while (bus.subscriberCount() < expected) {
                delay(5)
            }
        }
    }

    /** Root R (depth 0) plus two children A, B (depth 1), created with zero subscribers connected. */
    private suspend fun createRootAndChildren(
        provider: EventPublishingRepositoryProvider,
        label: String,
    ): Triple<WorkItem, WorkItem, WorkItem> {
        val root = provider.workItemRepository().create(WorkItem(title = "$label root", depth = 0)).getOrNull()!!
        val itemA =
            provider
                .workItemRepository()
                .create(WorkItem(title = "$label A", parentId = root.id, depth = 1))
                .getOrNull()!!
        val itemB =
            provider
                .workItemRepository()
                .create(WorkItem(title = "$label B", parentId = root.id, depth = 1))
                .getOrNull()!!
        return Triple(root, itemA, itemB)
    }

    // -------------------------------------------------------------------------
    // S1 — cold-cache create reaches a root-scoped subscriber
    // -------------------------------------------------------------------------

    @Test
    fun `S1 - cold-cache dependency create reaches a root-scoped subscriber once connected`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            // No subscriber connected during these writes: the root cache stays cold for A/B.
            val (root, itemA, itemB) = createRootAndChildren(provider, "S1")
            assertEquals(0, bus.subscriberCount(), "precondition: cache is cold, nothing listened during setup")

            val flowR = bus.subscribe("s1-sub-root", setOf(root.id), lastEventId = null)
            val received = async { withTimeout(5.seconds) { flowR.take(1).toList() } }
            awaitSubscriberCount(bus, 1)

            provider.dependencyRepository().create(
                Dependency(fromItemId = itemA.id, toItemId = itemB.id, type = DependencyType.BLOCKS),
            )

            val events = received.await()
            assertEquals(1, events.size, "root-scoped subscriber must receive the cold-cache dependency.added event")
            assertEquals(ApiEventType.DEPENDENCY_ADDED, events[0].event)
            assertEquals(itemA.id.toString(), events[0].itemId)
            bus.unsubscribe("s1-sub-root")
        }

    // -------------------------------------------------------------------------
    // S2 — cold-cache delete reaches a root-scoped subscriber
    // -------------------------------------------------------------------------

    @Test
    fun `S2 - cold-cache dependency delete reaches a root-scoped subscriber once connected`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val (root, itemA, itemB) = createRootAndChildren(provider, "S2")
            // Create the edge itself with zero subscribers too — its own add-event is irrelevant here.
            val dep =
                provider.dependencyRepository().create(
                    Dependency(fromItemId = itemA.id, toItemId = itemB.id, type = DependencyType.BLOCKS),
                )
            assertEquals(0, bus.subscriberCount(), "precondition: still cold, no listener during setup")

            val flowR = bus.subscribe("s2-sub-root", setOf(root.id), lastEventId = null)
            val received = async { withTimeout(5.seconds) { flowR.take(1).toList() } }
            awaitSubscriberCount(bus, 1)

            val deleted = provider.dependencyRepository().delete(dep.id)
            assertTrue(deleted, "delete of an existing dependency must report success")

            val events = received.await()
            assertEquals(1, events.size, "root-scoped subscriber must receive the cold-cache dependency.removed event")
            assertEquals(ApiEventType.DEPENDENCY_REMOVED, events[0].event)
            assertEquals(itemA.id.toString(), events[0].itemId)
            bus.unsubscribe("s2-sub-root")
        }

    // -------------------------------------------------------------------------
    // S3 — an out-of-scope subscriber still receives nothing (guards against
    // closing the hole by broadcasting instead of resolving)
    // -------------------------------------------------------------------------

    @Test
    fun `S3 - cold-cache dependency create does not reach an out-of-scope subscriber`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val (_, itemA, itemB) = createRootAndChildren(provider, "S3")
            val unrelatedRoot =
                provider.workItemRepository().create(WorkItem(title = "S3 unrelated root", depth = 0)).getOrNull()!!

            val flowS = bus.subscribe("s3-sub-unrelated", setOf(unrelatedRoot.id), lastEventId = null)
            val received = async { withTimeoutOrNull(1.seconds) { flowS.take(1).toList() } }
            awaitSubscriberCount(bus, 1)

            provider.dependencyRepository().create(
                Dependency(fromItemId = itemA.id, toItemId = itemB.id, type = DependencyType.BLOCKS),
            )

            assertNull(received.await(), "a subscriber scoped to an unrelated root must receive nothing")
            bus.unsubscribe("s3-sub-unrelated")
        }

    // -------------------------------------------------------------------------
    // S4 — rollback: a dependency create inside a rolling-back transaction
    // publishes and persists nothing (wave-4 shape preserved)
    // -------------------------------------------------------------------------

    @Test
    fun `S4 - dependency create inside a rolling-back transaction publishes nothing and persists nothing`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val (_, itemA, itemB) = createRootAndChildren(provider, "S4")

            // Unrestricted subscriber: entitled to everything, so a leaked event would still show here.
            val flowAll = bus.subscribe("s4-sub-all", emptySet(), lastEventId = null)
            val received = async { withTimeoutOrNull(1.seconds) { flowAll.take(1).toList() } }
            awaitSubscriberCount(bus, 1)

            val depId = UUID.randomUUID()
            var caught: Throwable? = null
            try {
                provider.workItemRepository().inTransaction {
                    provider.dependencyRepository().create(
                        Dependency(id = depId, fromItemId = itemA.id, toItemId = itemB.id, type = DependencyType.BLOCKS),
                    )
                    throw IllegalStateException("boom")
                }
            } catch (e: IllegalStateException) {
                caught = e
            }
            assertTrue(caught is IllegalStateException, "expected the transaction failure to propagate, got: $caught")

            assertNull(received.await(), "a rolled-back dependency create must publish zero dependency.added events")
            assertNull(
                provider.dependencyRepository().findById(depId),
                "a rolled-back dependency create must not persist",
            )
            bus.unsubscribe("s4-sub-all")
        }

    // -------------------------------------------------------------------------
    // S5 — commit ordering: a dependency create inside a committing transaction
    // is observed only after the block returns, in enqueue order
    // -------------------------------------------------------------------------

    @Test
    fun `S5 - dependency create inside a committing transaction is observed only after commit, in enqueue order`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val (_, itemA, itemB) = createRootAndChildren(provider, "S5")
            val baselineCount = bus.ringBufferSnapshot().size

            provider.workItemRepository().inTransaction {
                provider.dependencyRepository().create(
                    Dependency(fromItemId = itemA.id, toItemId = itemB.id, type = DependencyType.BLOCKS),
                )
                assertEquals(
                    baselineCount,
                    bus.ringBufferSnapshot().size,
                    "the dependency event must not be visible before the enclosing transaction commits",
                )
                provider.workItemRepository().update(itemA.copy(title = "S5 A renamed"))
            }

            val events = bus.ringBufferSnapshot().drop(baselineCount)
            assertEquals(
                2,
                events.size,
                "both the dependency create and the item update must publish exactly one event each on commit",
            )
            assertEquals(ApiEventType.DEPENDENCY_ADDED, events[0].event, "the dependency event was enqueued first")
            assertEquals(ApiEventType.ITEM_UPDATED, events[1].event, "the item update event was enqueued second")
            assertTrue(events[0].id < events[1].id, "events published on commit keep enqueue order")
        }

    // -------------------------------------------------------------------------
    // S6 — standalone create (no enclosing transaction) still publishes synchronously
    // -------------------------------------------------------------------------

    @Test
    fun `S6 - standalone dependency create, with no enclosing transaction, publishes synchronously`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val (_, itemA, itemB) = createRootAndChildren(provider, "S6")
            val baselineCount = bus.ringBufferSnapshot().size

            provider.dependencyRepository().create(
                Dependency(fromItemId = itemA.id, toItemId = itemB.id, type = DependencyType.BLOCKS),
            )

            val events = bus.ringBufferSnapshot().drop(baselineCount)
            assertEquals(
                1,
                events.size,
                "a standalone create outside any transaction must publish exactly one event before returning",
            )
            assertEquals(ApiEventType.DEPENDENCY_ADDED, events[0].event)
            assertEquals(itemA.id.toString(), events[0].itemId)
        }

    // -------------------------------------------------------------------------
    // Probes (test-plan: "Probes")
    // -------------------------------------------------------------------------

    /** Probe (a): duplicate edge twice — second throws, no second event. */
    @Test
    fun `probe - duplicate edge is rejected and publishes no second event`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val (_, itemA, itemB) = createRootAndChildren(provider, "ProbeA")
            val baselineCount = bus.ringBufferSnapshot().size

            provider.dependencyRepository().create(
                Dependency(fromItemId = itemA.id, toItemId = itemB.id, type = DependencyType.BLOCKS),
            )
            assertEquals(1, bus.ringBufferSnapshot().drop(baselineCount).size, "first create publishes exactly one event")

            assertThrows<ValidationException> {
                provider.dependencyRepository().create(
                    Dependency(fromItemId = itemA.id, toItemId = itemB.id, type = DependencyType.BLOCKS),
                )
            }

            assertEquals(
                1,
                bus.ringBufferSnapshot().drop(baselineCount).size,
                "a rejected duplicate must not publish a second dependency.added event",
            )
        }

    /** Probe (b): self-edge A->A is rejected at [Dependency] construction, before any repository call. */
    @Test
    fun `probe - self-referencing dependency is rejected at construction before any repository call`() {
        val itemA = UUID.randomUUID()
        assertThrows<ValidationException> {
            Dependency(fromItemId = itemA, toItemId = itemA)
        }
    }

    /** Probe (c): RELATES_TO skips cycle detection but still publishes like any other type. */
    @Test
    fun `probe - RELATES_TO dependency publishes an event despite skipping cycle detection`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val (_, itemA, itemB) = createRootAndChildren(provider, "ProbeC")
            val baselineCount = bus.ringBufferSnapshot().size

            // A -> B and B -> A would be a rejected cycle for BLOCKS; RELATES_TO is exempt from
            // cycle detection, so both creates must succeed and both must publish.
            provider.dependencyRepository().create(
                Dependency(fromItemId = itemA.id, toItemId = itemB.id, type = DependencyType.RELATES_TO),
            )
            provider.dependencyRepository().create(
                Dependency(fromItemId = itemB.id, toItemId = itemA.id, type = DependencyType.RELATES_TO),
            )

            val events = bus.ringBufferSnapshot().drop(baselineCount)
            assertEquals(2, events.size, "both RELATES_TO creates must publish; neither is rejected as a cycle")
            assertTrue(events.all { it.event == ApiEventType.DEPENDENCY_ADDED })
        }

    /** Probe (d): delete of an unknown id returns false and publishes no event. */
    @Test
    fun `probe - deleting an unknown dependency id returns false and publishes no event`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)
            val baselineCount = bus.ringBufferSnapshot().size

            val result = provider.dependencyRepository().delete(UUID.randomUUID())

            assertFalse(result, "deleting an id that was never created must return false")
            assertEquals(baselineCount, bus.ringBufferSnapshot().size, "no dependency.removed event for an unknown id")
        }

    /** Probe (e): two creates in one transaction flush in enqueue order on commit. */
    @Test
    fun `probe - two dependency creates in one transaction flush in enqueue order on commit`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val root = provider.workItemRepository().create(WorkItem(title = "ProbeE root", depth = 0)).getOrNull()!!
            val itemA =
                provider
                    .workItemRepository()
                    .create(WorkItem(title = "ProbeE A", parentId = root.id, depth = 1))
                    .getOrNull()!!
            val itemB =
                provider
                    .workItemRepository()
                    .create(WorkItem(title = "ProbeE B", parentId = root.id, depth = 1))
                    .getOrNull()!!
            val itemC =
                provider
                    .workItemRepository()
                    .create(WorkItem(title = "ProbeE C", parentId = root.id, depth = 1))
                    .getOrNull()!!
            val baselineCount = bus.ringBufferSnapshot().size

            provider.workItemRepository().inTransaction {
                provider.dependencyRepository().create(
                    Dependency(fromItemId = itemA.id, toItemId = itemB.id, type = DependencyType.BLOCKS),
                )
                provider.dependencyRepository().create(
                    Dependency(fromItemId = itemB.id, toItemId = itemC.id, type = DependencyType.BLOCKS),
                )
            }

            val events = bus.ringBufferSnapshot().drop(baselineCount)
            assertEquals(
                2,
                events.size,
                "both dependency creates in the same transaction must publish exactly one event each on commit",
            )
            assertEquals(itemA.id.toString(), events[0].itemId, "first enqueued create is observed first")
            assertEquals(itemB.id.toString(), events[1].itemId, "second enqueued create is observed second")
            assertTrue(events[0].id < events[1].id, "commit-time ids preserve enqueue order")
        }
}
