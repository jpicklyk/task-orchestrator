package io.github.jpicklyk.mcptask.current.application.support

import io.github.jpicklyk.mcptask.current.application.port.HierarchyStore
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.util.UUID

/**
 * The root an item's events belong to: its denormalized `rootId`, else the depth-0 ancestor found through
 * [hierarchy], else the item's own id (a root, or an orphan whose chain cannot be resolved). Never null.
 */
suspend fun eventRootOf(
    item: WorkItem,
    hierarchy: HierarchyStore
): UUID = item.rootId ?: eventRootOf(item.id, hierarchy)

/** [eventRootOf] for an item known only by id: the first id of its ancestor chain, else the id itself. */
suspend fun eventRootOf(
    itemId: UUID,
    hierarchy: HierarchyStore
): UUID = hierarchy.findAncestorChains(setOf(itemId))[itemId]?.firstOrNull()?.id ?: itemId
