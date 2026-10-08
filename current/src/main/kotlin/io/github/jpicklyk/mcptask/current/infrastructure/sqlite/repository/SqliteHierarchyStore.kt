package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ChildPlacement
import io.github.jpicklyk.mcptask.current.application.port.HierarchyStore
import io.github.jpicklyk.mcptask.current.domain.model.AncestorChain
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * SQLite [HierarchyStore]: downward and upward walks over `parent_id`. No write methods in P7 - placement
 * (`parent_id`, `root_id`, `depth`) is still written by [SqliteItemStore.insertRow] / [SqliteItemStore.update], and
 * the placement-write guard pins that surface until a later item moves those writes here.
 */
class SqliteHierarchyStore(
    private val databaseManager: DatabaseManager
) : HierarchyStore {
    private val logger = LoggerFactory.getLogger(SqliteHierarchyStore::class.java)

    override suspend fun findDescendants(id: UUID): List<WorkItem> =
        databaseManager.readTx {
            val ids = HierarchyTraversal.descendantIds(id)
            if (ids.isEmpty()) emptyList() else WorkItemRows.loadByIds(ids)
        }

    override suspend fun descendantIds(id: UUID): Set<UUID> =
        databaseManager.readTx {
            HierarchyTraversal.descendantIds(id)
        }

    override suspend fun findAncestorChains(itemIds: Set<UUID>): Map<UUID, List<WorkItem>> =
        findAncestorChainsDetailed(itemIds).mapValues { (_, chain) -> chain.ancestors }

    override suspend fun findAncestorChainsDetailed(itemIds: Set<UUID>): Map<UUID, AncestorChain> {
        if (itemIds.isEmpty()) return emptyMap()
        return databaseManager.readTx {
            val cache = mutableMapOf<UUID, WorkItem>()

            // Fetch the input items themselves first, then BFS upward fetching each missing parent level.
            val inputItems = WorkItemRows.loadByIds(itemIds)
            inputItems.forEach { cache[it.id] = it }
            var toFetch = inputItems.mapNotNull { it.parentId }.toSet() - cache.keys
            while (toFetch.isNotEmpty()) {
                val fetched = WorkItemRows.loadByIds(toFetch)
                fetched.forEach { cache[it.id] = it }
                toFetch = fetched.mapNotNull { it.parentId }.toSet() - cache.keys
            }

            // Both early exits are corruption, not "this item is shallow": each is reported on the returned
            // AncestorChain so callers that decide safety on chain completeness can tell the two apart.
            itemIds.associateWith { itemId ->
                val chain = mutableListOf<WorkItem>()
                val visited = mutableSetOf<UUID>()
                var truncationReason: String? = null
                var parentId = cache[itemId]?.parentId
                while (parentId != null) {
                    if (!visited.add(parentId)) {
                        logger.warn("Cycle detected in ancestor chain for item $itemId at parent $parentId - breaking")
                        truncationReason = AncestorChain.REASON_CYCLE
                        break
                    }
                    val ancestor = cache[parentId]
                    if (ancestor == null) {
                        logger.warn("Ancestor $parentId of item $itemId is missing - chain truncated")
                        truncationReason = AncestorChain.REASON_MISSING_ANCESTOR
                        break
                    }
                    chain.add(0, ancestor) // prepend so order is root -> direct parent
                    parentId = ancestor.parentId
                }
                AncestorChain(
                    ancestors = chain,
                    truncated = truncationReason != null,
                    truncationReason = truncationReason
                )
            }
        }
    }

    override suspend fun resolveChildPlacement(parentId: UUID): ChildPlacement? =
        databaseManager.readTx {
            val row = WorkItemsTable.selectAll().where { WorkItemsTable.id eq parentId }.singleOrNull() ?: return@readTx null
            val parent = WorkItemRows.toWorkItem(row)
            ChildPlacement(
                parentId = parentId,
                depth = parent.depth + 1,
                rootId = parent.rootId ?: parent.id
            )
        }
}
