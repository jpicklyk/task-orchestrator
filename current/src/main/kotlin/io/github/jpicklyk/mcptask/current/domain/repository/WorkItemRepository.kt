package io.github.jpicklyk.mcptask.current.domain.repository

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.time.Instant
import java.util.UUID

interface WorkItemRepository {
    suspend fun getById(id: UUID): Result<WorkItem>
    suspend fun create(item: WorkItem): Result<WorkItem>
    suspend fun update(item: WorkItem): Result<WorkItem>
    suspend fun delete(id: UUID): Result<Boolean>
    suspend fun findByParent(parentId: UUID, limit: Int = 50): Result<List<WorkItem>>
    suspend fun findByRole(role: Role, limit: Int = 50): Result<List<WorkItem>>
    suspend fun findByDepth(depth: Int, limit: Int = 50): Result<List<WorkItem>>
    suspend fun findRoot(): Result<WorkItem?>
    suspend fun search(query: String, limit: Int = 20): Result<List<WorkItem>>
    suspend fun count(): Result<Long>
    suspend fun findChildren(parentId: UUID): Result<List<WorkItem>>

    /**
     * Find work items matching multiple filter criteria.
     * All non-null filters are combined with AND logic. Tags use OR logic within the list.
     */
    suspend fun findByFilters(
        parentId: UUID? = null,
        depth: Int? = null,
        role: Role? = null,
        priority: Priority? = null,
        tags: List<String>? = null,
        query: String? = null,
        createdAfter: Instant? = null,
        createdBefore: Instant? = null,
        modifiedAfter: Instant? = null,
        modifiedBefore: Instant? = null,
        roleChangedAfter: Instant? = null,
        roleChangedBefore: Instant? = null,
        sortBy: String? = null,
        sortOrder: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Result<List<WorkItem>>

    /**
     * Count work items matching multiple filter criteria (same filters as findByFilters, no pagination).
     * Returns the total number of matching rows regardless of any limit/offset.
     */
    suspend fun countByFilters(
        parentId: UUID? = null,
        depth: Int? = null,
        role: Role? = null,
        priority: Priority? = null,
        tags: List<String>? = null,
        query: String? = null,
        createdAfter: Instant? = null,
        createdBefore: Instant? = null,
        modifiedAfter: Instant? = null,
        modifiedBefore: Instant? = null,
        roleChangedAfter: Instant? = null,
        roleChangedBefore: Instant? = null
    ): Result<Int>

    /**
     * Count direct children of a work item grouped by their current role.
     * Returns a map of Role to count. Roles with zero children are omitted.
     */
    suspend fun countChildrenByRole(parentId: UUID): Result<Map<Role, Int>>

    /**
     * Find all root items (items with no parent).
     * Unlike findRoot() which expects a single root, this returns all parentless items.
     */
    suspend fun findRootItems(limit: Int = 50): Result<List<WorkItem>>

    /**
     * Find all descendants of the given item (children, grandchildren, etc.) recursively.
     * Does not include the item itself.
     */
    suspend fun findDescendants(id: UUID): Result<List<WorkItem>>

    /**
     * Fetch multiple items by ID in one query. Missing IDs are silently omitted.
     */
    suspend fun findByIds(ids: Set<UUID>): Result<List<WorkItem>>

    /**
     * Delete multiple items by ID in one query. Returns the number of rows deleted.
     */
    suspend fun deleteAll(ids: Set<UUID>): Result<Int>

    /**
     * For each itemId, resolve its full ancestor chain (root -> direct parent).
     * Returns Map<itemId, List<WorkItem>> ordered root-first, ancestors only (item itself excluded).
     * Items with no parent (depth=0 root items) map to an empty list.
     */
    suspend fun findAncestorChains(itemIds: Set<UUID>): Result<Map<UUID, List<WorkItem>>>
}
