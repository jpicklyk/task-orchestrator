package io.github.jpicklyk.mcptask.current.infrastructure.service

import io.github.jpicklyk.mcptask.current.application.service.TreeDepSpec
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeInput
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeResult
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.DependenciesTable
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.NotesTable
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.util.UUID

/**
 * Infrastructure-layer implementation of [WorkTreeExecutor].
 *
 * Uses Exposed table objects directly inside a single [newSuspendedTransaction] so that
 * all inserts (items, dependencies, notes) are committed atomically. Any exception thrown
 * during execution causes a full rollback — no orphaned rows.
 */
class SQLiteWorkTreeService(private val databaseManager: DatabaseManager) : WorkTreeExecutor {

    override suspend fun execute(input: WorkTreeInput): WorkTreeResult =
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val createdItems = mutableListOf<io.github.jpicklyk.mcptask.current.domain.model.WorkItem>()
            val refToId = mutableMapOf<String, UUID>()
            val itemIdToRef = input.refToItem.entries.associate { (ref, item) -> item.id to ref }

            // 1. Insert all WorkItems using WorkItemsTable directly
            for (item in input.items) {
                item.validate()
                WorkItemsTable.insert {
                    it[id] = item.id
                    it[parentId] = item.parentId
                    it[title] = item.title
                    it[description] = item.description
                    it[summary] = item.summary
                    it[role] = item.role.name.lowercase()
                    it[statusLabel] = item.statusLabel
                    it[previousRole] = item.previousRole?.name?.lowercase()
                    it[priority] = item.priority.name.lowercase()
                    it[complexity] = item.complexity
                    it[requiresVerification] = item.requiresVerification
                    it[depth] = item.depth
                    it[metadata] = item.metadata
                    it[tags] = item.tags
                    it[createdAt] = item.createdAt
                    it[modifiedAt] = item.modifiedAt
                    it[roleChangedAt] = item.roleChangedAt
                    it[version] = item.version
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
                val fromId = refToId[spec.fromRef]
                    ?: throw IllegalStateException("Dependency ref '${spec.fromRef}' not found in created items")
                val toId = refToId[spec.toRef]
                    ?: throw IllegalStateException("Dependency ref '${spec.toRef}' not found in created items")
                val dep = Dependency(
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

            // 3. Upsert notes using NotesTable directly
            val createdNotes = mutableListOf<Note>()
            for (note in input.notes) {
                note.validate()
                val existing = NotesTable.selectAll()
                    .where { (NotesTable.itemId eq note.itemId) and (NotesTable.key eq note.key) }
                    .singleOrNull()
                if (existing != null) {
                    val existingId = existing[NotesTable.id].value
                    val now = Instant.now()
                    NotesTable.update({ NotesTable.id eq existingId }) {
                        it[body] = note.body
                        it[role] = note.role
                        it[modifiedAt] = now
                    }
                    createdNotes.add(note.copy(id = existingId, modifiedAt = now))
                } else {
                    NotesTable.insert {
                        it[id] = note.id
                        it[itemId] = note.itemId
                        it[key] = note.key
                        it[role] = note.role
                        it[body] = note.body
                        it[createdAt] = note.createdAt
                        it[modifiedAt] = note.modifiedAt
                    }
                    createdNotes.add(note)
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
            if (node in inStack) return node  // cycle found
            if (node in visited) return null  // already fully explored
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
