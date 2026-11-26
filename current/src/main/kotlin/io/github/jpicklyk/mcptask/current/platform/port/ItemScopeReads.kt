package io.github.jpicklyk.mcptask.current.platform.port

import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import java.util.UUID

/**
 * The item reads the platform's scope and id helpers (`ScopeGuard`, `ScopedIds`, `IdResolver`) need: one item by id,
 * several by id, and short-id resolution. The item store extends it, so a caller holding the store passes it as is.
 */
interface ItemScopeReads {
    suspend fun getById(id: UUID): WorkItem?

    /**
     * Fetch multiple items by ID in one query. Missing IDs are silently omitted.
     */
    suspend fun findByIds(ids: Set<UUID>): List<WorkItem>

    /**
     * The items whose id, as 32 lower-case hex digits without dashes, starts with [prefix]
     * (compared ignoring case), in ascending id order, at most [limit] of them. Backs short-id
     * resolution (`IdResolver`); a prefix that is not hex, or longer than 32 digits, matches
     * nothing.
     */
    suspend fun findByIdPrefix(
        prefix: String,
        limit: Int = 10
    ): List<WorkItem>
}
