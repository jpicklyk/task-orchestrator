package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant
import java.util.UUID

/**
 * An append-only audit record of one resource-lease HOLD INTERVAL — answers "who held resource R
 * at time T" (see `resource_lease_history` /
 * [io.github.jpicklyk.mcptask.current.infrastructure.database.schema.ResourceLeaseHistoryTable]).
 *
 * Unlike [ResourceLease] (the live, mutable "current holder" row), this type is immutable audit
 * history: one row per hold interval, appended on acquire and closed exactly once — never deleted
 * or otherwise mutated after closing. [holderItemId] carries NO foreign key — the row must remain
 * readable after the holder work item is deleted.
 *
 * An interval is "held at" instant `T` iff `acquiredAt <= T < coalesce(releasedAt, expiresAt)` —
 * see [io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository.findHoldersAt].
 *
 * @property id Stable identifier for this history row.
 * @property resourceKey The resource namespace key held during this interval.
 * @property holderItemId The WorkItem that held the key during this interval. No FK — may
 *   reference a since-deleted work item.
 * @property acquiredByActorId Opaque actor identifier that acquired this interval, if known.
 * @property acquiredAt When this hold interval began.
 * @property expiresAt TTL-based expiry as of the last write to this row. Refreshed in place on a
 *   same-holder re-acquire while the interval is open; frozen at its final value once closed.
 * @property releasedAt When this interval closed. Null while the interval is OPEN.
 * @property releaseReason Why the interval closed: `"released"` (normal release), `"expired"`
 *   (stolen by a different holder after TTL lapse), or `"force_released"` (ADMIN override). Null
 *   while open.
 * @property releasedByActorId Opaque actor identifier that performed the closing action —
 *   populated for `"released"` / `"force_released"`; null for a passive `"expired"` close, since
 *   the STEALING holder's acquire caused the close, not an explicit release action.
 */
data class ResourceLeaseInterval(
    val id: UUID = UUID.randomUUID(),
    val resourceKey: String,
    val holderItemId: UUID,
    val acquiredByActorId: String? = null,
    val acquiredAt: Instant,
    val expiresAt: Instant,
    val releasedAt: Instant? = null,
    val releaseReason: String? = null,
    val releasedByActorId: String? = null,
)
