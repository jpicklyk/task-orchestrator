package io.github.jpicklyk.mcptask.current.domain.repository

import io.github.jpicklyk.mcptask.current.domain.model.BacklinkRow
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import java.util.UUID

/**
 * Repository for managing WorkItem dependencies.
 *
 * Methods are `suspend` so their implementations can open a `suspendTransaction` that JOINS an
 * enclosing one (Exposed's `TransactionContextElement` carries the outer transaction across
 * suspension points), and — just as importantly — so decorators can call suspend collaborators.
 * The event-publishing decorator needs that: a non-suspend `create`/`delete` cannot resolve an
 * item's root ancestry from the database, only from an in-memory cache, and a cold-cache miss
 * withholds `dependency.added` / `dependency.removed` from every root-scoped SSE subscriber.
 *
 * [findByFromItemId] and [findByToItemId] are deliberately still non-suspend; migrating them is
 * tracked as a follow-up.
 */
interface DependencyRepository {
    suspend fun create(dependency: Dependency): Dependency

    suspend fun findById(id: UUID): Dependency?

    suspend fun findByItemId(itemId: UUID): List<Dependency>

    fun findByFromItemId(fromItemId: UUID): List<Dependency>

    fun findByToItemId(toItemId: UUID): List<Dependency>

    suspend fun delete(id: UUID): Boolean

    suspend fun deleteByItemId(itemId: UUID): Int

    suspend fun createBatch(dependencies: List<Dependency>): List<Dependency>

    /**
     * Checks whether adding a proposed BLOCKS-style edge from [fromItemId] to [toItemId] would
     * create a cycle in the blocker->blocked dependency graph.
     *
     * Despite the parameter names (kept for source/binary compatibility), this call means
     * (blocker, blocked) of the proposed blocking edge — i.e. [fromItemId] is the item that would
     * block [toItemId]. For a `Dependency`, pass its [Dependency.blockerId] and
     * [Dependency.blockedId] rather than its raw `fromItemId`/`toItemId`: those only coincide for
     * type BLOCKS and are swapped for IS_BLOCKED_BY. Not meaningful for RELATES_TO (no blocking
     * semantics) — callers should skip the check when either accessor returns null.
     */
    suspend fun hasCyclicDependency(
        fromItemId: UUID,
        toItemId: UUID
    ): Boolean

    /**
     * Batch-fetch dependencies for multiple items in a single query.
     * Returns a map where each key is an item ID from [itemIds] and the value is
     * the list of dependencies that reference that item (as either fromItemId or toItemId).
     * A dependency shared between two queried items appears in both entries.
     */
    suspend fun findByItemIds(itemIds: Set<UUID>): Map<UUID, List<Dependency>>

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
