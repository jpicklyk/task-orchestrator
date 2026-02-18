package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.Dependency
import io.github.jpicklyk.mcptask.domain.model.DependencyType
import io.github.jpicklyk.mcptask.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.domain.validation.ValidationException
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.schema.DependenciesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*

/**
 * SQLite implementation of DependencyRepository.
 */
class SQLiteDependencyRepository(private val databaseManager: DatabaseManager) : DependencyRepository {

    override fun create(dependency: Dependency): Dependency = transaction(databaseManager.getDatabase()) {
        // Check for cyclic dependencies before creating (call internal method without transaction)
        if (checkCyclicDependencyInternal(dependency.fromTaskId, dependency.toTaskId)) {
            throw ValidationException("Creating this dependency would result in a circular dependency")
        }

        // Check for duplicate dependencies
        val existingDependency = DependenciesTable
            .selectAll().where {
                (DependenciesTable.fromTaskId eq dependency.fromTaskId) and
                        (DependenciesTable.toTaskId eq dependency.toTaskId) and
                        (DependenciesTable.type eq dependency.type)
            }
            .singleOrNull()

        if (existingDependency != null) {
            throw ValidationException("A dependency of this type already exists between these tasks")
        }

        DependenciesTable.insert {
            it[id] = dependency.id
            it[fromTaskId] = dependency.fromTaskId
            it[toTaskId] = dependency.toTaskId
            it[type] = dependency.type
            it[unblockAt] = dependency.unblockAt
            it[createdAt] = dependency.createdAt
        }
        dependency
    }

    override fun createBatch(dependencies: List<Dependency>): List<Dependency> = transaction(databaseManager.getDatabase()) {
        if (dependencies.isEmpty()) {
            return@transaction emptyList()
        }

        // Phase 1: Check for duplicates within the batch itself
        val seen = mutableSetOf<Triple<UUID, UUID, DependencyType>>()
        for (dep in dependencies) {
            val key = Triple(dep.fromTaskId, dep.toTaskId, dep.type)
            if (!seen.add(key)) {
                throw ValidationException(
                    "Duplicate dependency within batch: ${dep.fromTaskId} -> ${dep.toTaskId} (${dep.type})"
                )
            }
        }

        // Phase 2: Check for duplicates against existing dependencies
        for (dep in dependencies) {
            val existing = DependenciesTable
                .selectAll().where {
                    (DependenciesTable.fromTaskId eq dep.fromTaskId) and
                            (DependenciesTable.toTaskId eq dep.toTaskId) and
                            (DependenciesTable.type eq dep.type)
                }
                .singleOrNull()

            if (existing != null) {
                throw ValidationException(
                    "A dependency of type ${dep.type} already exists between tasks ${dep.fromTaskId} and ${dep.toTaskId}"
                )
            }
        }

        // Phase 3: Incremental cycle detection — check and insert each dependency sequentially.
        // Uses the existing checkCyclicDependencyInternal which matches the established DFS
        // reachability semantics (checks if toTaskId can reach fromTaskId in the effective graph).
        // Each dependency is inserted before checking the next, so subsequent checks see earlier
        // batch members in the graph. Transaction rollback handles atomicity on failure.
        for (dep in dependencies) {
            if (checkCyclicDependencyInternal(dep.fromTaskId, dep.toTaskId)) {
                throw ValidationException(
                    "Creating these dependencies would result in a circular dependency chain"
                )
            }

            DependenciesTable.insert {
                it[id] = dep.id
                it[fromTaskId] = dep.fromTaskId
                it[toTaskId] = dep.toTaskId
                it[type] = dep.type
                it[unblockAt] = dep.unblockAt
                it[createdAt] = dep.createdAt
            }
        }

        dependencies
    }

    override fun findById(id: UUID): Dependency? = transaction(databaseManager.getDatabase()) {
        DependenciesTable.selectAll().where { DependenciesTable.id eq id }
            .map { it.toDependency() }
            .singleOrNull()
    }

    override fun findByTaskId(taskId: UUID): List<Dependency> = transaction(databaseManager.getDatabase()) {
        DependenciesTable.selectAll()
            .where { (DependenciesTable.fromTaskId eq taskId) or (DependenciesTable.toTaskId eq taskId) }
            .map { it.toDependency() }
    }

    override fun findByFromTaskId(fromTaskId: UUID): List<Dependency> = transaction(databaseManager.getDatabase()) {
        DependenciesTable.selectAll().where { DependenciesTable.fromTaskId eq fromTaskId }
            .map { it.toDependency() }
    }

    override fun findByToTaskId(toTaskId: UUID): List<Dependency> = transaction(databaseManager.getDatabase()) {
        DependenciesTable.selectAll().where { DependenciesTable.toTaskId eq toTaskId }
            .map { it.toDependency() }
    }

    override fun delete(id: UUID): Boolean = transaction(databaseManager.getDatabase()) {
        DependenciesTable.deleteWhere { DependenciesTable.id eq id } > 0
    }

    override fun deleteByTaskId(taskId: UUID): Int = transaction(databaseManager.getDatabase()) {
        DependenciesTable.deleteWhere {
            (DependenciesTable.fromTaskId eq taskId) or (DependenciesTable.toTaskId eq taskId)
        }
    }

    /**
     * Checks if adding a dependency from fromTaskId to toTaskId would create a cycle.
     * A cycle exists if there's already a path from toTaskId back to fromTaskId.
     *
     * This method wraps the internal check in a transaction for external callers.
     */
    override fun hasCyclicDependency(fromTaskId: UUID, toTaskId: UUID): Boolean = transaction(databaseManager.getDatabase()) {
        checkCyclicDependencyInternal(fromTaskId, toTaskId)
    }

    /**
     * Internal cyclic dependency check that must be called within an existing transaction.
     * Used by create() which already has a transaction context.
     */
    private fun checkCyclicDependencyInternal(fromTaskId: UUID, toTaskId: UUID): Boolean {
        // If they're the same task, it's definitely a cycle
        if (fromTaskId == toTaskId) {
            return true
        }

        // Use depth-first search to check for cycles
        val visited = mutableSetOf<UUID>()
        val visiting = mutableSetOf<UUID>()

        fun hasCycle(currentTaskId: UUID): Boolean {
            // If we've already fully explored this node, it doesn't lead to a cycle
            if (currentTaskId in visited) {
                return false
            }

            // If we're currently visiting this node, we found a cycle
            if (currentTaskId in visiting) {
                return true
            }

            // Mark as currently visiting
            visiting.add(currentTaskId)

            // Check all the tasks that this task depends on (outgoing edges)
            val dependencies = DependenciesTable.selectAll().where { DependenciesTable.fromTaskId eq currentTaskId }
                .map { it.toDependency() }
            for (dependency in dependencies) {
                // If the dependency type is BLOCKS or RELATES_TO, follow it
                if (dependency.type != DependencyType.IS_BLOCKED_BY) {
                    // If we reach the original fromTaskId, we found a cycle
                    if (dependency.toTaskId == fromTaskId) {
                        return true
                    }

                    // Recursively check dependencies
                    if (hasCycle(dependency.toTaskId)) {
                        return true
                    }
                }
            }

            // Also check for IS_BLOCKED_BY in the reverse direction
            val reverseDependencies = DependenciesTable.selectAll().where { DependenciesTable.toTaskId eq currentTaskId }
                .map { it.toDependency() }
            for (dependency in reverseDependencies) {
                if (dependency.type == DependencyType.IS_BLOCKED_BY) {
                    // If we reach the original fromTaskId, we found a cycle
                    if (dependency.fromTaskId == fromTaskId) {
                        return true
                    }

                    // Recursively check dependencies
                    if (hasCycle(dependency.fromTaskId)) {
                        return true
                    }
                }
            }

            // Done visiting this node
            visiting.remove(currentTaskId)
            visited.add(currentTaskId)

            return false
        }

        // Start DFS from the toTaskId
        return hasCycle(toTaskId)
    }

    private fun ResultRow.toDependency(): Dependency {
        return Dependency(
            id = this[DependenciesTable.id].value,
            fromTaskId = this[DependenciesTable.fromTaskId],
            toTaskId = this[DependenciesTable.toTaskId],
            type = this[DependenciesTable.type],
            unblockAt = this[DependenciesTable.unblockAt],
            createdAt = this[DependenciesTable.createdAt]
        )
    }
}