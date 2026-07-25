package io.github.jpicklyk.mcptask.current.domain.repository

import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import java.util.UUID

/**
 * Outcome of [ResourceLeaseRepository.acquireAll].
 *
 * Acquisition is all-or-nothing across every requested key: if any key is held by a different
 * item, NOTHING is written for this call — not even for the keys that were free — and every
 * contended key (not just the first one found) is reported back in [Contended.contendedKeys] so
 * the caller can surface a complete picture in a single round trip.
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

    /** An unexpected database exception occurred; the operation did not complete. */
    data class DBError(
        val cause: Exception
    ) : LeaseAcquireResult()
}

/** Outcome of [ResourceLeaseRepository.releaseAllForItem] / [ResourceLeaseRepository.forceReleaseByKey]. */
sealed class LeaseReleaseResult {
    /** The delete succeeded; [releasedCount] rows were removed (0 if none matched — not an error). */
    data class Success(
        val releasedCount: Int
    ) : LeaseReleaseResult()

    /** An unexpected database exception occurred; the operation did not complete. */
    data class DBError(
        val cause: Exception
    ) : LeaseReleaseResult()
}

/**
 * Persists server-enforced TTL leases on shared external resources (see
 * [io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement] /
 * [io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition] and
 * [io.github.jpicklyk.mcptask.current.infrastructure.database.schema.ResourceLeasesTable]).
 *
 * This is the storage + concurrency primitive only — no gate enforcement and no MCP tool surface.
 * A follow-on task wires `acquireAll` / `releaseAllForItem` into the work-phase-entry gate.
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
     *   performs no config lookups.
     */
    suspend fun acquireAll(
        holderItemId: UUID,
        actorId: String?,
        requirements: List<Pair<String, Int>>
    ): LeaseAcquireResult

    /** Releases every active or expired lease row held by [holderItemId]. Never touches other items' rows. */
    suspend fun releaseAllForItem(holderItemId: UUID): LeaseReleaseResult

    /**
     * Force-releases every row (any holder) for [resourceKey] — an administrative override for
     * unsticking a resource without waiting out its TTL. Not used by normal acquire/release flow.
     */
    suspend fun forceReleaseByKey(resourceKey: String): LeaseReleaseResult

    /** Returns the active (non-expired) leases among [keys], across all holders. */
    suspend fun findActiveByKeys(keys: List<String>): List<ResourceLease>

    /** Returns every active (non-expired) lease held by [holderItemId]. */
    suspend fun findActiveForItem(holderItemId: UUID): List<ResourceLease>

    /** Returns every active (non-expired) lease in the table, across all keys and holders. */
    suspend fun findAllActive(): List<ResourceLease>
}
