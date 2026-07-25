package io.github.jpicklyk.mcptask.current.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Append-only audit history of resource-lease hold intervals — answers "who held resource R at
 * time T" (see [io.github.jpicklyk.mcptask.current.domain.model.ResourceLeaseInterval]). Mirrors
 * `V16__Resource_Lease_History.sql` exactly.
 *
 * Deliberately carries NO foreign key on [holderItemId] to `WorkItemsTable` — see the migration
 * header for the full rationale. In short: this table is an audit trail that must survive both
 * `ON DELETE CASCADE` of the live [ResourceLeasesTable] rows and deletion of the holder work item
 * itself, so [holderItemId] may reference a work item that no longer exists.
 *
 * One row per hold INTERVAL, not per lease event — [acquiredAt] marks when the interval opened;
 * [releasedAt] / [releaseReason] mark when and why it closed (both null while the interval is
 * open). A same-holder TTL refresh extends [expiresAt] on the OPEN row in place rather than
 * opening a new interval; stealing an expired lease closes the prior holder's interval
 * (`released_at` = its own prior `expires_at`, `release_reason` = "expired") before a new interval
 * opens for the new holder — see `SQLiteResourceLeaseRepository.acquireAll`.
 */
object ResourceLeaseHistoryTable : UUIDTable("resource_lease_history") {
    val resourceKey = text("resource_key")
    val holderItemId = javaUUID("holder_item_id")
    val acquiredByActorId = text("acquired_by_actor_id").nullable()
    val acquiredAt = timestamp("acquired_at")
    val expiresAt = timestamp("expires_at")
    val releasedAt = timestamp("released_at").nullable()
    val releaseReason = text("release_reason").nullable()
    val releasedByActorId = text("released_by_actor_id").nullable()

    init {
        index(isUnique = false, resourceKey, acquiredAt)
        index(isUnique = false, releasedAt)
    }
}
