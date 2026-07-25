package io.github.jpicklyk.mcptask.current.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Server-enforced TTL lease store for shared external resources (see
 * [io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement] /
 * [io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition]). Mirrors
 * `V15__Resource_Leases.sql` exactly.
 *
 * `(resource_key, holder_item_id)` is unique — deliberately NOT unique on [resourceKey] alone, so
 * a future fan-in ("semaphore", `maxHolders > 1`) enforcement mode can store multiple concurrent
 * holder rows for the same key without a schema change. v1 enforcement still only ever allows a
 * single active holder per key (checked in application code, not by this constraint — see
 * `SQLiteResourceLeaseRepository.acquireAll`). Follows [PlanDocumentsTable]'s style: BLOB id
 * default, a plain unique-index pair rather than an inline `UNIQUE` column.
 *
 * [acquiredAt] / [expiresAt] / [originalAcquiredAt] are declared via [timestamp] exactly like
 * [WorkItemsTable]'s `claimedAt` / `claimExpiresAt` / `originalClaimedAt` claim columns — both
 * store ISO-8601 TEXT under SQLite (see `V5__Add_Claim_Fields.sql` and `V15__Resource_Leases.sql`
 * for the underlying column type rationale).
 *
 * [budgetLimit] / [budgetUsed] / [budgetWindowSeconds] are reserved for a future rate-budget
 * enforcement mode — unused by the current repository surface, always null for now.
 */
object ResourceLeasesTable : UUIDTable("resource_leases") {
    val resourceKey = text("resource_key")
    val holderItemId = javaUUID("holder_item_id")
    val acquiredByActorId = text("acquired_by_actor_id").nullable()
    val acquiredAt = timestamp("acquired_at")
    val expiresAt = timestamp("expires_at")
    val originalAcquiredAt = timestamp("original_acquired_at")
    val budgetLimit = integer("budget_limit").nullable()
    val budgetUsed = integer("budget_used").nullable()
    val budgetWindowSeconds = integer("budget_window_seconds").nullable()
    val version = integer("version").default(0)

    init {
        foreignKey(holderItemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        uniqueIndex(resourceKey, holderItemId)
        index(isUnique = false, resourceKey)
        index(isUnique = false, expiresAt)
    }
}
