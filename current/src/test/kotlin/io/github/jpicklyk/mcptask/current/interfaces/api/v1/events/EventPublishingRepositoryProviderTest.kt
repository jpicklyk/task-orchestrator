package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.buildH2RepositoryProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Security regression tests for [EventPublishingRepositoryProvider] dependency-event scoping.
 *
 * These tests verify that dependency.added / dependency.removed events are NOT broadcast to
 * subscribers outside the affected root's scope when the root is known (cached). This covers
 * Leak #2 from the scope-isolation bug fix.
 */
class EventPublishingRepositoryProviderTest {
    // -------------------------------------------------------------------------
    // Leak 2 — dependency event scoping via cache
    // -------------------------------------------------------------------------

    /**
     * Non-suspend dependency.create publishes to scoped subscriber for a cached root.
     *
     * Scenario: item A under root-R is created (primes the root cache), then a dependency
     * is created from A to another item. A subscriber scoped to root-R should receive
     * the dependency.added event; a subscriber scoped to root-S (different root) must NOT.
     */
    @Test
    fun `non-suspend dependency create is scoped to cached root - out-of-scope subscriber does not receive event`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            // Create two root items (depth=0). We must subscribe a warm-up subscriber BEFORE these
            // writes: resolveRoots() short-circuits to emptySet (and does NOT populate rootCache)
            // when subscriberCount()==0 as a performance guard. The cache is only warmed when a
            // subscriber is connected during the item writes — which mirrors a live dashboard.
            val warmupFlow = bus.subscribe("sub-warmup", emptySet(), lastEventId = null)
            // Drain the warm-up subscriber in the background so its channel does not back-pressure.
            val warmupDrain = async { warmupFlow.take(Int.MAX_VALUE).toList() }

            val rootR = provider.workItemRepository().create(WorkItem(title = "Root R", depth = 0)).getOrNull()!!
            val rootS = provider.workItemRepository().create(WorkItem(title = "Root S", depth = 0)).getOrNull()!!

            // Create item A as a child of rootR (this primes the cache: itemA → {rootR.id})
            val itemA =
                provider
                    .workItemRepository()
                    .create(
                        WorkItem(title = "Item A", parentId = rootR.id, depth = 1)
                    ).getOrNull()!!

            // Create item B as a child of rootR (needed for the dependency target)
            val itemB =
                provider
                    .workItemRepository()
                    .create(
                        WorkItem(title = "Item B", parentId = rootR.id, depth = 1)
                    ).getOrNull()!!

            // Subscriber scoped to rootS should NOT receive dependency events for rootR items
            val flowS = bus.subscribe("sub-rootS", setOf(rootS.id), lastEventId = null)
            val receivedByS =
                async {
                    withTimeoutOrNull(600) { flowS.take(1).toList() }
                }

            delay(30)

            // Create dependency from itemA → itemB via the non-suspend path.
            // itemA's root is cached → resolveRootsCached returns {rootR.id} → scoped, not broadcast.
            provider.dependencyRepository().create(
                Dependency(
                    fromItemId = itemA.id,
                    toItemId = itemB.id,
                    type = DependencyType.BLOCKS,
                )
            )

            val result = receivedByS.await()
            assertNull(
                result,
                "Subscriber scoped to rootS must NOT receive dependency.added event for rootR items (got: $result)",
            )
            bus.unsubscribe("sub-rootS")
            bus.unsubscribe("sub-warmup")
            warmupDrain.cancel()
        }

    /**
     * Non-suspend dependency.create delivers event to the correct root-scoped subscriber.
     *
     * A warm-up subscriber is connected BEFORE the item writes so resolveRoots() populates the
     * cache (it short-circuits to emptySet when subscriberCount()==0). This ensures the
     * dependency event is delivered because itemA's root is genuinely IN the scoped subscriber's
     * root set — not merely because of a cold-cache broadcast.
     */
    @Test
    fun `non-suspend dependency create delivers event to in-scope subscriber`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            // Warm-up subscriber present during writes so the root cache is populated.
            val warmupFlow = bus.subscribe("sub-warmup-2", emptySet(), lastEventId = null)
            val warmupDrain = async { warmupFlow.take(Int.MAX_VALUE).toList() }

            // Create root item and child (primes the cache: itemA → {rootR.id})
            val rootR = provider.workItemRepository().create(WorkItem(title = "Root R2", depth = 0)).getOrNull()!!
            val itemA =
                provider
                    .workItemRepository()
                    .create(
                        WorkItem(title = "Item A2", parentId = rootR.id, depth = 1)
                    ).getOrNull()!!
            val itemB =
                provider
                    .workItemRepository()
                    .create(
                        WorkItem(title = "Item B2", parentId = rootR.id, depth = 1)
                    ).getOrNull()!!

            // Subscriber scoped to rootR SHOULD receive the dependency event
            val flowR = bus.subscribe("sub-rootR", setOf(rootR.id), lastEventId = null)
            val collectedByR =
                async {
                    withTimeout(5.seconds) { flowR.take(1).toList() }
                }

            delay(30)

            provider.dependencyRepository().create(
                Dependency(
                    fromItemId = itemA.id,
                    toItemId = itemB.id,
                    type = DependencyType.BLOCKS,
                )
            )

            val events = collectedByR.await()
            assertEquals(1, events.size, "In-scope subscriber must receive the dependency.added event")
            assertEquals(ApiEventType.DEPENDENCY_ADDED, events[0].event)
            assertEquals(itemA.id.toString(), events[0].itemId)
            bus.unsubscribe("sub-rootR")
            bus.unsubscribe("sub-warmup-2")
            warmupDrain.cancel()
        }

    /**
     * When the root cache is empty (cold start, item never written through the decorated provider
     * in this session), the non-suspend path cannot resolve the roots of the event it publishes.
     * Since ffce70f6 such an event FAILS CLOSED (`rootsResolved = false`): it reaches only
     * unrestricted subscribers and never a root-scoped one. The pre-fix "broadcast fallback" this
     * test used to pin handed root-scoped subscribers metadata about roots outside their scope.
     *
     * Orchestrator sweep repair for the ffce70f6 contract change (bug wave 4).
     */
    @Test
    fun `non-suspend dependency create on a cold root cache fails closed - unrestricted subscriber only`(): Unit =
        runBlocking {
            // Use an undecorated delegate to pre-create the items (bypasses cache population)
            val delegate = buildH2RepositoryProvider()
            val uncachedItemId = UUID.randomUUID()
            val anotherItemId = UUID.randomUUID()

            // Pre-create items directly through the UNDECORATED provider (no cache warm-up)
            delegate.workItemRepository().create(WorkItem(id = uncachedItemId, title = "Cold Item", depth = 0))
            delegate.workItemRepository().create(WorkItem(id = anotherItemId, title = "Cold Target", depth = 0))
            delegate.dependencyRepository() // ensure repo is initialized

            // Now wrap the same delegate with the event provider — cache is cold for these items
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val rootX = UUID.randomUUID()
            // Root-scoped subscriber on an unrelated root: must NOT see an unresolved event.
            val flowX = bus.subscribe("sub-rootX-cold", setOf(rootX), lastEventId = null)
            // Unrestricted subscriber: entitled to everything, so it still receives it.
            val flowAll = bus.subscribe("sub-all-cold", emptySet(), lastEventId = null)
            val receivedX =
                async {
                    withTimeoutOrNull(1.seconds) { flowX.take(1).toList() }
                }
            val receivedAll =
                async {
                    withTimeout(3.seconds) { flowAll.take(1).toList() }
                }

            delay(30)

            // Create dependency via the non-suspend path on an uncached item — roots unresolved
            provider.dependencyRepository().create(
                Dependency(
                    fromItemId = uncachedItemId,
                    toItemId = anotherItemId,
                    type = DependencyType.RELATES_TO,
                )
            )

            val all = receivedAll.await()
            assertEquals(1, all.size, "unrestricted subscriber must receive the unresolved-root event")
            assertEquals(ApiEventType.DEPENDENCY_ADDED, all[0].event)
            assertNull(
                receivedX.await(),
                "an unresolved-root event must fail closed: the rootX-scoped subscriber must receive nothing",
            )
            bus.unsubscribe("sub-rootX-cold")
            bus.unsubscribe("sub-all-cold")
        }
}
