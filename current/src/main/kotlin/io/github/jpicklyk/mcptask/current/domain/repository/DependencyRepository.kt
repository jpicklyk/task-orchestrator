package io.github.jpicklyk.mcptask.current.domain.repository

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
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
}
