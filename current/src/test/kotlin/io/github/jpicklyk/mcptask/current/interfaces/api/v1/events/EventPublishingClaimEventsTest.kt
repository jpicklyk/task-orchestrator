package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.ReleaseResult
import io.github.jpicklyk.mcptask.current.test.SQLiteRepositoryTestBase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Claim/release SSE event coverage for [EventPublishingRepositoryProvider] — item 646b12a6.
 *
 * Uses [SQLiteRepositoryTestBase] because the canonical claim SQL pattern (auto-release of an
 * agent's other held claims, TTL-based eviction) uses SQLite-specific date functions H2 does not
 * support. Oracles: [R] = current/docs/api-rest.md §21 "Claim/release note"; [D] = the item's
 * `diagnosis` note's Fix mapping.
 *
 * Every scenario subscribes, performs the write under test, then drains via [drainDelivered] —
 * see that helper's KDoc for why no fixed wait is needed (O5, item 646b12a6).
 */
class EventPublishingClaimEventsTest : SQLiteRepositoryTestBase() {
    /** S5: claim(Y, agent) success → item.updated itemId=Y. [R "field updated"][D] */
    @Test
    fun `S5 successful claim emits item updated for the claimed item`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(repositoryProvider, bus)

            val y = provider.workItemRepository().create(WorkItem(title = "Y5", depth = 0)).getOrNull()!!

            val flow = bus.subscribe("s5", emptySet(), lastEventId = null)

            val result = provider.workItemRepository().claim(y.id, "agent1", ttlSeconds = 900)

            assertTrue(result is ClaimResult.Success, "expected ClaimResult.Success, got: $result")
            val events = bus.drainDelivered("s5", flow)
            assertEquals(1, events.size, "expected exactly 1 event, got: $events")
            assertEquals(ApiEventType.ITEM_UPDATED, events[0].event)
            assertEquals(y.id.toString(), events[0].itemId)
        }

    /**
     * S6 (NEW-SURFACE): agent holds X, claims Y → item.updated for Y AND X;
     * `ClaimResult.Success.releasedItemIds == [X]`.
     * Narrowest-revert recipe: keep `ClaimResult.Success.releasedItemIds`, revert only its
     * population in `claim()` (or the decorator's iteration over it) — that reverts to
     * behavioral red (only Y's item.updated is emitted, releasedItemIds stays empty) without
     * touching the field's declaration. [D]
     */
    @Test
    fun `S6 claiming a new item while holding another emits item updated for both and reports the released id`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(repositoryProvider, bus)

            val x = provider.workItemRepository().create(WorkItem(title = "X6", depth = 0)).getOrNull()!!
            val y = provider.workItemRepository().create(WorkItem(title = "Y6", depth = 0)).getOrNull()!!
            val priorClaim = provider.workItemRepository().claim(x.id, "agent1", ttlSeconds = 900)
            assertTrue(priorClaim is ClaimResult.Success, "setup: expected agent1 to hold X, got: $priorClaim")

            val flow = bus.subscribe("s6", emptySet(), lastEventId = null)

            val result = provider.workItemRepository().claim(y.id, "agent1", ttlSeconds = 900)

            assertTrue(result is ClaimResult.Success, "expected ClaimResult.Success, got: $result")
            assertEquals(listOf(x.id), (result as ClaimResult.Success).releasedItemIds)

            val events = bus.drainDelivered("s6", flow)
            assertEquals(2, events.size, "expected exactly 2 events, got: $events")
            assertTrue(events.all { it.event == ApiEventType.ITEM_UPDATED })
            assertEquals(setOf(x.id.toString(), y.id.toString()), events.map { it.itemId }.toSet())
        }

    /** S7: release(Y, holder) success → 1 item.updated Y. [D] */
    @Test
    fun `S7 successful release emits item updated for the released item`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(repositoryProvider, bus)

            val y = provider.workItemRepository().create(WorkItem(title = "Y7", depth = 0)).getOrNull()!!
            val claim = provider.workItemRepository().claim(y.id, "agent1", ttlSeconds = 900)
            assertTrue(claim is ClaimResult.Success, "setup: expected agent1 to hold Y, got: $claim")

            val flow = bus.subscribe("s7", emptySet(), lastEventId = null)

            val result = provider.workItemRepository().release(y.id, "agent1")

            assertTrue(result is ReleaseResult.Success, "expected ReleaseResult.Success, got: $result")
            val events = bus.drainDelivered("s7", flow)
            assertEquals(1, events.size, "expected exactly 1 event, got: $events")
            assertEquals(ApiEventType.ITEM_UPDATED, events[0].event)
            assertEquals(y.id.toString(), events[0].itemId)
        }

    /**
     * S10: claim(Y) while held by another agent (AlreadyClaimed) → 0 events; the agent's prior
     * claim X untouched → no X event either. [D]
     */
    @Test
    fun `S10 claim attempt on an item held by another agent emits no events`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(repositoryProvider, bus)

            val x = provider.workItemRepository().create(WorkItem(title = "X10", depth = 0)).getOrNull()!!
            val y = provider.workItemRepository().create(WorkItem(title = "Y10", depth = 0)).getOrNull()!!
            val claimX = provider.workItemRepository().claim(x.id, "agent1", ttlSeconds = 900)
            assertTrue(claimX is ClaimResult.Success, "setup: expected agent1 to hold X, got: $claimX")
            val claimY = provider.workItemRepository().claim(y.id, "agent2", ttlSeconds = 900)
            assertTrue(claimY is ClaimResult.Success, "setup: expected agent2 to hold Y, got: $claimY")

            val flow = bus.subscribe("s10", emptySet(), lastEventId = null)

            val result = provider.workItemRepository().claim(y.id, "agent1", ttlSeconds = 900)

            assertTrue(result is ClaimResult.AlreadyClaimed, "expected AlreadyClaimed, got: $result")
            val events = bus.drainDelivered("s10", flow)
            assertTrue(events.isEmpty(), "no events may be published on a failed (AlreadyClaimed) claim attempt, got: $events")
        }

    /** S13: release by non-holder (NotClaimedByYou) → 0 events. [D] */
    @Test
    fun `S13 release attempt by a non-holder emits no events`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(repositoryProvider, bus)

            val y = provider.workItemRepository().create(WorkItem(title = "Y13", depth = 0)).getOrNull()!!
            val claim = provider.workItemRepository().claim(y.id, "holder", ttlSeconds = 900)
            assertTrue(claim is ClaimResult.Success, "setup: expected holder to hold Y, got: $claim")

            val flow = bus.subscribe("s13", emptySet(), lastEventId = null)

            val result = provider.workItemRepository().release(y.id, "impostor")

            assertTrue(result is ReleaseResult.NotClaimedByYou, "expected NotClaimedByYou, got: $result")
            val events = bus.drainDelivered("s13", flow)
            assertTrue(
                events.isEmpty(),
                "no events may be published on a failed (NotClaimedByYou) release attempt, got: $events",
            )
        }

    /**
     * S16 (NEW-SURFACE, same recipe as S6): claim refresh (same agent re-claims Y it holds) →
     * item.updated Y only, releasedItemIds empty. Narrowest-revert recipe: same as S6 — keep the
     * field, revert only its population/iteration.
     */
    @Test
    fun `S16 claim refresh by the same holder emits item updated for that item only with no released ids`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(repositoryProvider, bus)

            val y = provider.workItemRepository().create(WorkItem(title = "Y16", depth = 0)).getOrNull()!!
            val firstClaim = provider.workItemRepository().claim(y.id, "agent1", ttlSeconds = 900)
            assertTrue(firstClaim is ClaimResult.Success, "setup: expected agent1 to hold Y, got: $firstClaim")

            val flow = bus.subscribe("s16", emptySet(), lastEventId = null)

            val result = provider.workItemRepository().claim(y.id, "agent1", ttlSeconds = 900)

            assertTrue(result is ClaimResult.Success, "expected ClaimResult.Success, got: $result")
            assertEquals(emptyList<java.util.UUID>(), (result as ClaimResult.Success).releasedItemIds)

            val events = bus.drainDelivered("s16", flow)
            assertEquals(1, events.size, "expected exactly 1 event, got: $events")
            assertEquals(ApiEventType.ITEM_UPDATED, events[0].event)
            assertEquals(y.id.toString(), events[0].itemId)
        }
}
