package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.ReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.support.writeOutcome
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.ClaimReleaseReason
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What one [ClaimService.sweepExpired] pass found: claims newly reported as expired and lapsed lease rows removed. */
data class ExpirySweep(
    val claimsExpired: Int,
    val leasesExpired: Int
)

/**
 * The single owner of claim and resource-lease writes AND of their events (plan sections 3.6, 3.7).
 *
 * Every method runs in [UnitOfWork.write]: it joins the ambient unit when there is one (advance, delete, work
 * tree), else opens its own. [Outcome.Err] is a fault only (the unit is rolled back); a domain outcome such as
 * [ClaimResult.AlreadyClaimed] is `Ok`.
 *
 * ## Events
 *
 * For an ACTIVE instance the rows match what the P8 decorator wrote: `claim.acquired`, `claim.released` (reasons
 * `released`, `cleared`, `superseded`), a keyed `claim.rejected` and `lease.acquired` / `lease.released` /
 * `lease.rejected`. Rejections go through `EventSink.recordRejection`, so they survive the caller's rollback.
 *
 * ## Expiry (Rule X)
 *
 * A claim is lapsed when `claimExpiresAt <= unit now`; a lease row when `expires_at <= now`; one boundary for both.
 * Whenever this class overwrites, clears or removes a lapsed instance (a take-over, a holder's own re-claim, a
 * supersede, a release, [clearClaim], a lease acquire over a lapsed row, a lease release, a force release, the sweep)
 * it records `claim.expired` / `lease.expired` for it and NOT released / superseded / cleared. Exactly one expired row
 * exists per lapsed instance, whichever detector gets there first:
 *
 * - Claims are NOT consumed by detection: the claim columns stay, so `isExpired`, `claimStatus=expired` and the
 *   health-check expired counts keep their meaning. The one-row guarantee comes from the event log: a row is written
 *   only when the latest `claim.expired` row of the item does not already name the same holder and `expiresAt`.
 * - Lapsed lease rows ARE consumed: they are removed (their open history interval closes as `expired`).
 * - A claim with `claimedBy` set but no recorded expiry has no instant to report and is never reported.
 * - Read-only tools never detect (the reader pool cannot write); an advance unit that commits reports its item's
 *   lapsed claim via [detectExpiry], and the hourly [sweepExpired] backstops everything else.
 */
class ClaimService(
    private val repositoryProvider: RepositoryProvider,
    private val unitOfWork: UnitOfWork
) {
    // -------------------------------------------------------------------------
    // Claims
    // -------------------------------------------------------------------------

    /**
     * Claims [itemId] for [agentId] (or refreshes the agent's own claim: a heartbeat is a re-claim). Every OTHER item
     * the agent held is auto-released.
     */
    suspend fun claim(
        itemId: UUID,
        agentId: String,
        ttlSeconds: Int = DEFAULT_CLAIM_TTL_SECONDS
    ): Outcome<ClaimResult> =
        unitOfWork.writeOutcome("ClaimService.claim") {
            val items = repositoryProvider.workItemRepository()
            val prior = items.getById(itemId)
            val heldBefore = items.findHeldBy(agentId).associateBy { it.id }

            val result = items.claim(itemId, agentId, ttlSeconds)
            when (result) {
                is ClaimResult.Success -> {
                    val out = mutableListOf<DomainEvent>()
                    // The target's previous claim, if it had lapsed (take-over, or the holder's own late re-claim).
                    if (prior != null) out += newClaimExpiredEvents(listOf(prior), now)
                    out += DomainEvent.ClaimAcquired(result.item.id, rootOf(result.item), agentId, ttlSeconds)
                    // Every OTHER item this agent held was auto-released as part of this claim.
                    val released = result.releasedItemIds.map { id -> heldBefore[id] to id }
                    val lapsed = released.mapNotNull { (item, _) -> item }.filter { isLapsed(it, now) }
                    val lapsedIds = lapsed.mapTo(HashSet()) { it.id }
                    out += newClaimExpiredEvents(lapsed, now)
                    for ((_, id) in released) {
                        if (id !in lapsedIds) out += DomainEvent.ClaimReleased(id, rootOfId(id), ClaimReleaseReason.SUPERSEDED)
                    }
                    events.record(out)
                }
                // A rejection row must survive the caller's unit (a keyed claim rolls back on it).
                is ClaimResult.AlreadyClaimed ->
                    events.recordRejection(DomainEvent.ClaimRejected(itemId, rootOfId(itemId), result.retryAfterMs))
                is ClaimResult.NotFound, is ClaimResult.TerminalItem -> Unit
            }
            result
        }

    /** Releases the claim [agentId] holds on [itemId]. A lapsed claim reports `claim.expired` instead of a release. */
    suspend fun release(
        itemId: UUID,
        agentId: String
    ): Outcome<ReleaseResult> =
        unitOfWork.writeOutcome("ClaimService.release") {
            val prior = repositoryProvider.workItemRepository().getById(itemId)
            val result = repositoryProvider.workItemRepository().release(itemId, agentId)
            if (result is ReleaseResult.Success) {
                val lapsed = prior?.takeIf { isLapsed(it, now) }
                if (lapsed != null) {
                    events.record(newClaimExpiredEvents(listOf(lapsed), now))
                } else {
                    events.record(DomainEvent.ClaimReleased(result.item.id, rootOf(result.item), ClaimReleaseReason.RELEASED))
                }
            }
            result
        }

    /**
     * Clears the claim columns of [itemId] unconditionally; `Ok(true)` when the item exists. A row is recorded only
     * when a claim was actually held: `claim.released` (`cleared`) for an active one, `claim.expired` for a lapsed one.
     */
    suspend fun clearClaim(itemId: UUID): Outcome<Boolean> =
        unitOfWork.writeOutcome("ClaimService.clearClaim") {
            val prior = repositoryProvider.workItemRepository().getById(itemId)
            val result = repositoryProvider.workItemRepository().clear(itemId)
            if (result && prior?.claimedBy != null) {
                if (isLapsed(prior, now)) {
                    events.record(newClaimExpiredEvents(listOf(prior), now))
                } else {
                    events.record(DomainEvent.ClaimReleased(itemId, rootOf(prior), ClaimReleaseReason.CLEARED))
                }
            }
            result
        }

    /**
     * Reports [item]'s claim as expired when it has lapsed and no `claim.expired` row names it yet. The claim columns
     * are left alone. Called by an advance for the item it evaluates; `Ok(true)` when a row was written.
     */
    suspend fun detectExpiry(item: WorkItem): Outcome<Boolean> =
        unitOfWork.writeOutcome("ClaimService.detectExpiry") {
            if (!isLapsed(item, now)) return@writeOutcome false
            val fresh = newClaimExpiredEvents(listOf(item), now)
            events.record(fresh)
            fresh.isNotEmpty()
        }

    // -------------------------------------------------------------------------
    // Leases
    // -------------------------------------------------------------------------

    /**
     * Acquires (or refreshes) a lease on every distinct key of [requested], all or nothing. Lapsed rows on those keys are
     * reported as `lease.expired`; other holders' lapsed rows are removed, the holder's own is refreshed in place.
     */
    suspend fun acquireLeases(
        holderItemId: UUID,
        actorId: String?,
        requested: List<Pair<String, Int>>
    ): Outcome<LeaseAcquireResult> =
        unitOfWork.writeOutcome("ClaimService.acquireLeases") {
            // One lease and one event per key: the first declaration's TTL wins (first-trait-wins merge order).
            val requirements = requested.distinctBy { it.first }
            val leases = repositoryProvider.resourceLeaseRepository()
            // Read before the acquire: it refreshes the holder's own lapsed row and the rows would no longer qualify.
            val lapsed = if (requirements.isEmpty()) emptyList() else leases.findLapsed(keys = requirements.map { it.first }.distinct())
            val result = leases.acquireAll(holderItemId, actorId, requirements)
            when (result) {
                is LeaseAcquireResult.Success -> {
                    val others = lapsed.filter { it.holderItemId != holderItemId }
                    if (others.isNotEmpty()) leases.deleteLapsed(others.mapTo(HashSet()) { it.id })
                    val out = mutableListOf<DomainEvent>()
                    out += leaseExpiredEvents(lapsed)
                    if (result.leases.isNotEmpty()) {
                        val root = rootOfId(holderItemId)
                        for (lease in result.leases) {
                            out +=
                                DomainEvent.LeaseAcquired(
                                    holderItemId,
                                    root,
                                    lease.resourceKey,
                                    Duration.between(lease.acquiredAt, lease.expiresAt).seconds
                                )
                        }
                    }
                    events.record(out)
                }
                is LeaseAcquireResult.Contended ->
                    events.recordRejection(
                        DomainEvent.LeaseRejected(holderItemId, rootOfId(holderItemId), result.contendedKeys, result.retryAfterMs)
                    )
            }
            result
        }

    /**
     * Releases every lease row held by any of [holderItemIds]: `lease.released` per ACTIVE (holder, key), and
     * `lease.expired` per lapsed row the release also removes. An empty set is a no-op.
     */
    suspend fun releaseLeases(holderItemIds: Set<UUID>): Outcome<LeaseReleaseResult> =
        unitOfWork.writeOutcome("ClaimService.releaseLeases") {
            if (holderItemIds.isEmpty()) return@writeOutcome LeaseReleaseResult.Success(0)
            val leases = repositoryProvider.resourceLeaseRepository()
            val single = holderItemIds.singleOrNull()
            val active =
                if (single != null) {
                    leases.findActiveForItem(single)
                } else {
                    leases.findAllActive().filter { it.holderItemId in holderItemIds }
                }
            val lapsed = leases.findLapsed(holderItemIds = holderItemIds)
            val result = if (single != null) leases.releaseAllForItem(single) else leases.releaseAllForItems(holderItemIds)
            val released = (result as? LeaseReleaseResult.Success)?.releasedCount ?: 0
            val out = mutableListOf<DomainEvent>()
            if (released > 0) {
                for ((holder, rows) in active.groupBy { it.holderItemId }) {
                    val root = rootOfId(holder)
                    for (lease in rows) out += DomainEvent.LeaseReleased(holder, root, lease.resourceKey, 1, forced = false)
                }
            }
            out += leaseExpiredEvents(lapsed)
            events.record(out)
            result
        }

    /** Force-releases every holder of [resourceKey] (administrative); same event rules as [releaseLeases], with `forced`. */
    suspend fun forceReleaseLease(
        resourceKey: String,
        actorId: String?
    ): Outcome<LeaseReleaseResult> =
        unitOfWork.writeOutcome("ClaimService.forceReleaseLease") {
            val leases = repositoryProvider.resourceLeaseRepository()
            val holders = leases.findActiveByKeys(listOf(resourceKey)).map { it.holderItemId }.distinct()
            val lapsed = leases.findLapsed(keys = listOf(resourceKey))
            val result = leases.forceReleaseByKey(resourceKey, actorId)
            val released = (result as? LeaseReleaseResult.Success)?.releasedCount ?: 0
            val out = mutableListOf<DomainEvent>()
            if (released > 0) {
                for (holder in holders) out += DomainEvent.LeaseReleased(holder, rootOfId(holder), resourceKey, 1, forced = true)
            }
            out += leaseExpiredEvents(lapsed)
            events.record(out)
            result
        }

    // -------------------------------------------------------------------------
    // Sweep
    // -------------------------------------------------------------------------

    /**
     * One expiry pass in one unit: reports every lapsed claim not yet reported (the claim columns stay) and removes
     * every lapsed lease row with its `lease.expired` row. Idempotent: a second pass finds nothing new.
     */
    suspend fun sweepExpired(): Outcome<ExpirySweep> =
        unitOfWork.writeOutcome("ClaimService.sweepExpired") {
            val claimEvents = newClaimExpiredEvents(repositoryProvider.workItemRepository().findLapsedClaims(), now)
            val leases = repositoryProvider.resourceLeaseRepository()
            val lapsedLeases = leases.findLapsed()
            if (lapsedLeases.isNotEmpty()) leases.deleteLapsed(lapsedLeases.mapTo(HashSet()) { it.id })
            events.record(claimEvents + leaseExpiredEvents(lapsedLeases))
            ExpirySweep(claimEvents.size, lapsedLeases.size)
        }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** True when [item] holds a claim whose recorded expiry is at or before [now]. */
    private fun isLapsed(
        item: WorkItem,
        now: Instant
    ): Boolean {
        val expiresAt = item.claimExpiresAt ?: return false
        return item.claimedBy != null && !expiresAt.isAfter(now)
    }

    /**
     * `claim.expired` rows for the lapsed claims among [items] that no existing row names yet (matched on holder and
     * `expiresAt` against the item's latest `claim.expired` row). One batched lookup.
     */
    private suspend fun newClaimExpiredEvents(
        items: List<WorkItem>,
        now: Instant
    ): List<DomainEvent> {
        val lapsed = items.filter { isLapsed(it, now) }.distinctBy { it.id }
        if (lapsed.isEmpty()) return emptyList()
        val latest = repositoryProvider.eventStore().latestOfType(DomainEvent.CLAIM_EXPIRED, lapsed.mapTo(HashSet()) { it.id })
        return lapsed
            .filterNot { names(latest[it.id], it) }
            .map { DomainEvent.ClaimExpired(it.id, rootOf(it), it.claimedBy, it.claimExpiresAt) }
    }

    /** True when [record] is a `claim.expired` row for [item]'s current claim instance. */
    private fun names(
        record: EventRecord?,
        item: WorkItem
    ): Boolean {
        if (record == null) return false
        val data =
            try {
                Json.parseToJsonElement(record.data).jsonObject
            } catch (e: IllegalArgumentException) {
                return false
            }
        return text(data, "holder") == item.claimedBy && text(data, "expiresAt") == item.claimExpiresAt?.toString()
    }

    private fun text(
        data: JsonObject,
        key: String
    ): String? = (data[key] as? JsonPrimitive)?.contentOrNull

    private suspend fun leaseExpiredEvents(lapsed: List<ResourceLease>): List<DomainEvent> =
        lapsed.map { DomainEvent.LeaseExpired(it.holderItemId, rootOfId(it.holderItemId), it.resourceKey, it.expiresAt) }

    private suspend fun rootOf(item: WorkItem): UUID = eventRootOf(item, repositoryProvider.workItemRepository())

    private suspend fun rootOfId(itemId: UUID): UUID {
        val hierarchy = repositoryProvider.workItemRepository()
        val item = repositoryProvider.workItemRepository().getById(itemId)
        return if (item != null) eventRootOf(item, hierarchy) else eventRootOf(itemId, hierarchy)
    }

    companion object {
        /** The claim TTL when the caller names none. */
        const val DEFAULT_CLAIM_TTL_SECONDS = 900
    }
}
