package io.github.jpicklyk.mcptask.current.platform.port

import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import java.util.UUID

/**
 * The ancestor chains of items, root first, ancestors only: the one read an event's root is found through. The item
 * hierarchy store implements it, so the platform names no item store.
 */
fun interface AncestorChains {
    /** For each of [itemIds], its ancestor chain root-first (the item itself excluded); a root maps to an empty list. */
    suspend fun findAncestorChains(itemIds: Set<UUID>): Map<UUID, List<WorkItem>>
}
