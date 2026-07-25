package io.github.jpicklyk.mcptask.current.domain.model

import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * A server-enforced TTL lease held by a WorkItem on a shared external resource (see
 * [ResourceRequirement] / [ResourceDefinition]).
 *
 * Backed by `resource_leases` (see
 * [io.github.jpicklyk.mcptask.current.infrastructure.database.schema.ResourceLeasesTable] /
 * `V15__Resource_Leases.sql`). "Active" (not expired) is a lazy, read-time notion — see
 * [io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository] KDoc — this type
 * itself carries no `isActive` flag; callers compare [expiresAt] against the DB clock, or rely on
 * repository methods that already filter to active rows.
 *
 * @property id Stable identifier for this lease row.
 * @property resourceKey The resource namespace key this lease holds (matches
 *   [ResourceDefinition.key] / [ResourceRequirement.key]).
 * @property holderItemId The WorkItem holding this lease. FK `ON DELETE CASCADE` — deleting the
 *   holder releases its leases automatically.
 * @property acquiredByActorId Opaque actor identifier that performed the acquisition, if known.
 *   Audit-only; never used for lease-ownership decisions (ownership is by [holderItemId]).
 * @property acquiredAt When the current lease term began. Refreshed on same-holder re-acquire.
 * @property expiresAt TTL-based expiry, computed DB-side. A lease with `expiresAt <= dbNow()` is
 *   treated as absent by every repository read path (lazy expiry — no background sweep).
 * @property originalAcquiredAt Timestamp of the FIRST acquisition of this (resourceKey, holderItemId)
 *   pair — preserved across same-holder re-acquires/refreshes; reset only when a different holder
 *   acquires the key after the prior lease lapsed. Mirrors [WorkItem.originalClaimedAt].
 * @property version Optimistic-concurrency / audit counter, incremented on each write to this row.
 */
data class ResourceLease(
    val id: UUID = UUID.randomUUID(),
    val resourceKey: String,
    val holderItemId: UUID,
    val acquiredByActorId: String? = null,
    val acquiredAt: Instant,
    val expiresAt: Instant,
    val originalAcquiredAt: Instant,
    val version: Int = 0,
) {
    init {
        validate()
    }

    /**
     * Coherence checks mirroring [WorkItem]'s claim-field invariant style (see
     * `WorkItem.validate()`): timestamps must be internally ordered.
     */
    fun validate() {
        if (resourceKey.isBlank()) throw ValidationException("resourceKey must not be blank")
        if (acquiredAt.isAfter(expiresAt)) {
            throw ValidationException("acquiredAt must not be after expiresAt")
        }
        if (originalAcquiredAt.isAfter(acquiredAt)) {
            throw ValidationException("originalAcquiredAt must not be after acquiredAt")
        }
    }
}
