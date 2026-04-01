package io.github.jpicklyk.mcptask.current.infrastructure.service

import io.github.jpicklyk.mcptask.current.application.service.TreeDepSpec
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeInput
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeResult
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.DependenciesTable
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteNoteRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.util.UUID

/**
 * Infrastructure-layer implementation of [WorkTreeExecutor].
 *
 * Uses Exposed table objects directly inside a single [newSuspendedTransaction] so that
 * all inserts (items, dependencies, notes) are committed atomically. Any exception thrown
 * during execution causes a full rollback — no orphaned rows.
 */
class SQLiteWorkTreeService(
    private val databaseManager: DatabaseManager,
    private val workItemRepo: SQLiteWorkItemRepository,
    private val noteRepo: SQLiteNoteRepository
) : WorkTreeExecutor {
    override suspend fun execute(input: WorkTreeInput): WorkTreeResult =
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val createdItems = mutableListOf<WorkItem>()
            val refToId = mutableMapOf<String, UUID>()
            val itemIdToRef = input.refToItem.entries.associate { (ref, item) -> item.id to ref }

            // 1. Insert all WorkItems via shared helper (no inner transaction)
            for (item in input.items) {
                val insertResult = workItemRepo.insertRow(item)
                if (insertResult is Result.Error) {
                    throw IllegalStateException("Failed to insert WorkItem '${item.id}': ${insertResult.error.message}")
                }
                createdItems.add(item)
                val ref = itemIdToRef[item.id]
                if (ref != null) refToId[ref] = item.id
            }

            // 2. Insert dependencies using DependenciesTable directly
            // Cycle check before insertion — new items CAN form cycles among themselves
            val cycleError = detectInMemoryCycle(input.deps)
            if (cycleError != null) {
                throw IllegalStateException(cycleError)
            }
            val createdDeps = mutableListOf<Dependency>()
            for (spec in input.deps) {
                val fromId =
                    refToId[spec.fromRef]
                        ?: throw IllegalStateException("Dependency ref '${spec.fromRef}' not found in created items")
                val toId =
                    refToId[spec.toRef]
                        ?: throw IllegalStateException("Dependency ref '${spec.toRef}' not found in created items")
                val dep =
                    Dependency(
                        fromItemId = fromId,
                        toItemId = toId,
                        type = spec.type,
                        unblockAt = spec.unblockAt
                    )
                DependenciesTable.insert {
                    it[id] = dep.id
                    it[fromItemId] = dep.fromItemId
                    it[toItemId] = dep.toItemId
                    it[type] = dep.type.name
                    it[unblockAt] = dep.unblockAt
                    it[createdAt] = dep.createdAt
                }
                createdDeps.add(dep)
            }

            // 3. Upsert notes via shared helper (no inner transaction)
            val createdNotes = mutableListOf<Note>()
            for (note in input.notes) {
                when (val upsertResult = noteRepo.upsertRow(note)) {
                    is Result.Success -> createdNotes.add(upsertResult.data)
                    is Result.Error -> throw IllegalStateException("Failed to upsert Note '${note.id}': ${upsertResult.error.message}")
                }
            }

            WorkTreeResult(
                items = createdItems,
                refToId = refToId,
                deps = createdDeps,
                notes = createdNotes
            )
        }

    /**
     * Performs an in-memory DFS cycle detection on the given dependency specs.
     * Returns an error message string if a cycle is found, or null if no cycle exists.
     * RELATES_TO edges are excluded from cycle detection (they are bidirectional by nature).
     */
    private fun detectInMemoryCycle(deps: List<TreeDepSpec>): String? {
        // Build adjacency map from fromRef -> list of toRefs (for BLOCKS and IS_BLOCKED_BY only)
        val adj = mutableMapOf<String, MutableList<String>>()
        for (dep in deps) {
            if (dep.type == DependencyType.RELATES_TO) continue
            adj.getOrPut(dep.fromRef) { mutableListOf() }.add(dep.toRef)
        }

        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()

        fun dfs(node: String): String? {
            if (node in inStack) return node // cycle found
            if (node in visited) return null // already fully explored
            visited.add(node)
            inStack.add(node)
            for (neighbor in adj[node] ?: emptyList()) {
                dfs(neighbor)?.let { return it }
            }
            inStack.remove(node)
            return null
        }

        // Check every node as a potential cycle start
        for (ref in adj.keys) {
            dfs(ref)?.let { return "Circular dependency detected involving ref '$it'" }
        }
        return null
    }
}
