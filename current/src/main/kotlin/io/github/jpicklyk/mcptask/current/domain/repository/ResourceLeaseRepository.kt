package io.github.jpicklyk.mcptask.current.domain.repository

import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLeaseInterval
import java.time.Instant
import java.util.UUID

/**
 * Outcome of [ResourceLeaseRepository.acquireAll].
 *
 * Acquisition is all-or-nothing across every requested key: if any key is held by a different
 * item, NOTHING is written for this call — not even for the keys that were free — and every
 * contended key (not just the first one found) is reported back in [Contended.contendedKeys] so
 * the caller can surface a complete picture in a single round trip. A database failure is thrown.
 */
sealed class LeaseAcquireResult {
    /** Every requested key was acquired (or refreshed, for keys already held by [holderItemId]). */
    data class Success(
        val leases: List<ResourceLease>
    ) : LeaseAcquireResult()

    /**
     * At least one requested key is actively held by a different item. [contendedKeys] lists ALL
     * such keys (not just the first). [retryAfterMs] is the soonest time (in milliseconds, computed
     * against the DB clock) until any of the contended leases expires — a hint for backoff, not a
     * guarantee the key will be free at that instant.
     */
    data class Contended(
        val contendedKeys: List<String>,
        val retryAfterMs: Long
    ) : LeaseAcquireResult()
}

/** Outcome of [ResourceLeaseRepository.releaseAllForItem] / [ResourceLeaseRepository.forceReleaseByKey]. A database failure is thrown. */
sealed class LeaseReleaseResult {
    /** The delete succeeded; [releasedCount] rows were removed (0 if none matched — not an error). */
    data class Success(
        val releasedCount: Int
    ) : LeaseReleaseResult()
}

/**
 * Persists server-enforced TTL leases on shared external resources (see
 * [io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement] /
 * [io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition] and
 * [io.github.jpicklyk.mcptask.current.infrastructure.database.schema.ResourceLeasesTable]).
 *
 * This is the storage + concurrency primitive only — no gate enforcement and no MCP tool surface.
 * `acquireAll` / `releaseAllForItem` are wired into the work-phase-entry gate by
 * [io.github.jpicklyk.mcptask.current.application.service.AdvanceService] (acquire, release) and
 * into item deletion by
 * [io.github.jpicklyk.mcptask.current.application.tools.items.WorkItemDeletion].
 *
 * ## "Active" is a lazy, read-time notion
 *
 * There is no background expiry sweep. A lease row with `expires_at <= dbNow()` is treated as
 * ABSENT by every read path here (`findActive*`) and as FREE (stealable) by [acquireAll]'s
 * per-key holder-count check. Expired rows are simply overwritten in place on the next acquire for
 * that key (or left as harmless stale rows until then) — callers never observe an expired lease
 * through this interface.
 *
 * ## Isolation from claims
 *
 * Acquiring or releasing leases must NEVER touch other items' lease rows, or the `work_items`
 * claim columns (`claimed_by` / `claimed_at` / `claim_expires_at` / `original_claimed_at`). A
 * lease and a claim on the same item are independent lifecycles.
 */
interface ResourceLeaseRepository {
    /**
     * Atomically acquires (or, for a key already held by [holderItemId], refreshes) a lease on
     * every key in [requirements]. All-or-nothing: if any key is actively held by a DIFFERENT
     * item, no writes occur for this call and [LeaseAcquireResult.Contended] lists every contended
     * key. A same-holder re-acquire extends `expiresAt`/refreshes `acquiredAt` while preserving the
     * original `originalAcquiredAt`; a fresh acquire (or one following the prior holder's lease
     * expiring) sets all three timestamps together.
     *
     * @param holderItemId The WorkItem acquiring the leases.
     * @param actorId Optional opaque actor identifier recorded on each acquired/refreshed row
     *   (audit-only — see [ResourceLease.acquiredByActorId]).
     * @param requirements Pre-resolved `(resourceKey, ttlSeconds)` pairs — TTL resolution
     *   (registry default vs. per-requirement override) is the caller's responsibility; this method
     *   performs no config lookups. Each `ttlSeconds` must be positive.
     *
     * Concurrency: the call runs inside the caller's write unit (IMMEDIATE, single writer), so the
     * contention pre-pass and the writes cannot interleave with another writer; SQLITE_BUSY is retried
     * by the unit itself. A database failure is thrown.
     */
    suspend fun acquireAll(
        holderItemId: UUID,
        actorId: String?,
        requirements: List<Pair<String, Int>>
    ): LeaseAcquireResult

    /** Releases every active or expired lease row held by [holderItemId]. Never touches other items' rows. */
    suspend fun releaseAllForItem(holderItemId: UUID): LeaseReleaseResult

    /**
     * Releases every lease row held by any item in [holderItemIds] — the bulk form of
     * [releaseAllForItem], used by a recursive delete so releasing a subtree's leases is not one
     * round trip per descendant. Returns [LeaseReleaseResult.Success] with the total number of
     * lease rows removed; an empty set is a no-op returning `Success(0)`.
     *
     * The default implementation loops [releaseAllForItem] (sums the counts); implementations with a
     * set-based statement should override it.
     */
    suspend fun releaseAllForItems(holderItemIds: Set<UUID>): LeaseReleaseResult {
        var total = 0
        for (holderItemId in holderItemIds) {
            when (val result = releaseAllForItem(holderItemId)) {
                is LeaseReleaseResult.Success -> total += result.releasedCount
            }
        }
        return LeaseReleaseResult.Success(total)
    }

    /**
     * Force-releases every row (any holder) for [resourceKey] — an administrative override for
     * unsticking a resource without waiting out its TTL. Not used by normal acquire/release flow.
     *
     * Authorization is the CALLER's responsibility — this layer performs no permission checks; the
     * REST surface gates this behind the ADMIN capability.
     *
     * @param actorId Optional opaque actor identifier of the acting principal, recorded as
     *   `released_by_actor_id` on every closed [ResourceLeaseInterval] history row (audit-only).
     */
    suspend fun forceReleaseByKey(
        resourceKey: String,
        actorId: String? = null
    ): LeaseReleaseResult

    /** Returns the active (non-expired) leases among [keys], across all holders. */
    suspend fun findActiveByKeys(keys: List<String>): List<ResourceLease>

    /** Returns every active (non-expired) lease held by [holderItemId]. */
    suspend fun findActiveForItem(holderItemId: UUID): List<ResourceLease>

    /** Returns every active (non-expired) lease in the table, across all keys and holders. */
    suspend fun findAllActive(): List<ResourceLease>

    /**
     * Returns every [ResourceLeaseInterval] (open or closed) that was held at instant [at] —
     * i.e. `acquiredAt <= at < coalesce(releasedAt, expiresAt)` — optionally restricted to
     * [resourceKey]. Ordered newest-first by [ResourceLeaseInterval.acquiredAt]. Answers "who held
     * resource R at time T" for post-incident diagnosis; see the `resource_lease_history` table.
     */
    suspend fun findHoldersAt(
        resourceKey: String?,
        at: Instant
    ): List<ResourceLeaseInterval>

    /**
     * Returns the most recent [ResourceLeaseInterval] rows (open or closed), newest-first by
     * [ResourceLeaseInterval.acquiredAt], optionally restricted to [resourceKey] and capped at
     * [limit] rows. Backs the no-`at` "recent activity" view.
     */
    suspend fun findRecentIntervals(
        resourceKey: String?,
        limit: Int
    ): List<ResourceLeaseInterval>
}
