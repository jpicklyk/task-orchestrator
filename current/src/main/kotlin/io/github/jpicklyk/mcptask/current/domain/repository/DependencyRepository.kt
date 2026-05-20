package io.github.jpicklyk.mcptask.current.domain.repository

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.infrastructure.repository.BacklinkRow
import java.util.UUID

/**
 * Repository for managing WorkItem dependencies.
 * Uses non-suspend functions following the v2 pattern (needed for synchronous cascade detection).
 */
interface DependencyRepository {
    fun create(dependency: Dependency): Dependency

    /** Suspend variant for use within a [newSuspendedTransaction] context — joins the outer transaction. */
    suspend fun createSuspend(dependency: Dependency): Dependency

    fun findById(id: UUID): Dependency?

    fun findByItemId(itemId: UUID): List<Dependency>

    fun findByFromItemId(fromItemId: UUID): List<Dependency>

    fun findByToItemId(toItemId: UUID): List<Dependency>

    fun delete(id: UUID): Boolean

    fun deleteByItemId(itemId: UUID): Int

    fun createBatch(dependencies: List<Dependency>): List<Dependency>

    fun hasCyclicDependency(
        fromItemId: UUID,
        toItemId: UUID
    ): Boolean

    /**
     * Batch-fetch dependencies for multiple items in a single query.
     * Returns a map where each key is an item ID from [itemIds] and the value is
     * the list of dependencies that reference that item (as either fromItemId or toItemId).
     * A dependency shared between two queried items appears in both entries.
     */
    fun findByItemIds(itemIds: Set<UUID>): Map<UUID, List<Dependency>>

    /**
     * Returns reverse-direction dependency edges pointing *at* [itemId].
     *
     * Each [BacklinkRow] represents another item whose dependency edge has [itemId] as its target
     * (`dependencies.to_item_id = itemId`). Useful for finding all items that reference a given
     * item — e.g., "what items block REQ-42?" or "what items relate to FEAT-7?".
     *
     * Uses the existing index on `to_item_id` — no full table scan.
     *
     * @param itemId UUID of the target item to find backlinks for.
     * @param type   Optional filter: when non-null, only edges of this [DependencyType] are returned.
     */
    suspend fun backlinks(
        itemId: UUID,
        type: DependencyType? = null,
    ): List<BacklinkRow>
}
