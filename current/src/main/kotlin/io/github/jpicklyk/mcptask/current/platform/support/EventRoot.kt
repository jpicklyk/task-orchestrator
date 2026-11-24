package io.github.jpicklyk.mcptask.current.platform.support

import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.platform.port.AncestorChains
import java.util.UUID

/**
 * The root an item's events belong to: its denormalized `rootId`, else the depth-0 ancestor found through
 * [hierarchy], else the item's own id (a root, or an orphan whose chain cannot be resolved). Never null.
 */
suspend fun eventRootOf(
    item: WorkItem,
    hierarchy: AncestorChains
): UUID = item.rootId ?: eventRootOf(item.id, hierarchy)

/** [eventRootOf] for an item known only by id: the first id of its ancestor chain, else the id itself. */
suspend fun eventRootOf(
    itemId: UUID,
    hierarchy: AncestorChains
): UUID = hierarchy.findAncestorChains(setOf(itemId))[itemId]?.firstOrNull()?.id ?: itemId
