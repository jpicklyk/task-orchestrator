package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/*
 * Helpers shared by the orchestration selects of `items_read` (`context`, `next`, `blocked`). Plain functions over the
 * compact [ItemView]; no select owns them.
 */

/** The ancestor chains (root-first, the item excluded) of [items] in one read; empty without a read when [wanted] is false or [items] is empty. */
internal suspend fun ancestorChains(
    env: ToolExecutionContext,
    items: Collection<WorkItem>,
    wanted: Boolean
): Map<UUID, List<WorkItem>> =
    if (wanted && items.isNotEmpty()) {
        env.workItemRepository().findAncestorChains(items.map { it.id }.toSet())
    } else {
        emptyMap()
    }

/** [view] with the `ancestors` of [item] the caller under [scope] may see (decision D3), when [chains] was read for it. */
internal fun withAncestors(
    view: JsonObject,
    item: WorkItem,
    chains: Map<UUID, List<WorkItem>>,
    scope: AccessScope
): JsonObject {
    val chain = chains[item.id] ?: return view
    return ItemView.with(view, "ancestors", JsonArray(ItemView.visibleAncestors(chain, scope).map { ItemView.compact(it) }))
}
