package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.DependenciesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * SQLite implementation of DependencyRepository.
 * Uses non-suspend functions with synchronous transactions (needed for cascade detection).
 */
class SQLiteDependencyRepository(private val databaseManager: DatabaseManager) : DependencyRepository {

    override fun create(dependency: Dependency): Dependency = transaction(databaseManager.getDatabase()) {
        // Check for cyclic dependencies before creating
        if (checkCyclicDependencyInternal(dependency.fromItemId, dependency.toItemId)) {
            throw ValidationException("Creating this dependency would result in a circular dependency")
        }

        // Check for duplicate dependencies
        val existing = DependenciesTable.selectAll().where {
            (DependenciesTable.fromItemId eq dependency.fromItemId) and
                    (DependenciesTable.toItemId eq dependency.toItemId) and
                    (DependenciesTable.type eq dependency.type.name)
        }.singleOrNull()

        if (existing != null) {
            throw ValidationException("A dependency of this type already exists between these items")
        }

        DependenciesTable.insert {
            it[id] = dependency.id
            it[fromItemId] = dependency.fromItemId
            it[toItemId] = dependency.toItemId
            it[type] = dependency.type.name
            it[unblockAt] = dependency.unblockAt
            it[createdAt] = dependency.createdAt
        }
        dependency
    }

    override fun findById(id: UUID): Dependency? = transaction(databaseManager.getDatabase()) {
        DependenciesTable.selectAll().where { DependenciesTable.id eq id }
            .map { mapRowToDependency(it) }
            .singleOrNull()
    }

    override fun findByItemId(itemId: UUID): List<Dependency> = transaction(databaseManager.getDatabase()) {
        DependenciesTable.selectAll()
            .where { (DependenciesTable.fromItemId eq itemId) or (DependenciesTable.toItemId eq itemId) }
            .map { mapRowToDependency(it) }
    }

    override fun findByFromItemId(fromItemId: UUID): List<Dependency> = transaction(databaseManager.getDatabase()) {
        DependenciesTable.selectAll()
            .where { DependenciesTable.fromItemId eq fromItemId }
            .map { mapRowToDependency(it) }
    }

    override fun findByToItemId(toItemId: UUID): List<Dependency> = transaction(databaseManager.getDatabase()) {
        DependenciesTable.selectAll()
            .where { DependenciesTable.toItemId eq toItemId }
            .map { mapRowToDependency(it) }
    }

    override fun delete(id: UUID): Boolean = transaction(databaseManager.getDatabase()) {
        DependenciesTable.deleteWhere { DependenciesTable.id eq id } > 0
    }

    override fun deleteByItemId(itemId: UUID): Int = transaction(databaseManager.getDatabase()) {
        DependenciesTable.deleteWhere {
            (DependenciesTable.fromItemId eq itemId) or (DependenciesTable.toItemId eq itemId)
        }
    }

    override fun createBatch(dependencies: List<Dependency>): List<Dependency> = transaction(databaseManager.getDatabase()) {
        if (dependencies.isEmpty()) {
            return@transaction emptyList()
        }

        // Phase 1: Check for duplicates within the batch itself
        val seen = mutableSetOf<Triple<UUID, UUID, DependencyType>>()
        for (dep in dependencies) {
            val key = Triple(dep.fromItemId, dep.toItemId, dep.type)
            if (!seen.add(key)) {
                throw ValidationException(
                    "Duplicate dependency within batch: ${dep.fromItemId} -> ${dep.toItemId} (${dep.type})"
                )
            }
        }

        // Phase 2: Check for duplicates against existing dependencies
        for (dep in dependencies) {
            val existing = DependenciesTable.selectAll().where {
                (DependenciesTable.fromItemId eq dep.fromItemId) and
                        (DependenciesTable.toItemId eq dep.toItemId) and
                        (DependenciesTable.type eq dep.type.name)
            }.singleOrNull()

            if (existing != null) {
                throw ValidationException(
                    "A dependency of type ${dep.type} already exists between items ${dep.fromItemId} and ${dep.toItemId}"
                )
            }
        }

        // Phase 3: Incremental cycle detection - check and insert each dependency sequentially.
        // Each dependency is inserted before checking the next, so subsequent checks see earlier
        // batch members in the graph. Transaction rollback handles atomicity on failure.
        for (dep in dependencies) {
            if (checkCyclicDependencyInternal(dep.fromItemId, dep.toItemId)) {
                throw ValidationException(
                    "Creating these dependencies would result in a circular dependency chain"
                )
            }

            DependenciesTable.insert {
                it[id] = dep.id
                it[fromItemId] = dep.fromItemId
                it[toItemId] = dep.toItemId
                it[type] = dep.type.name
                it[unblockAt] = dep.unblockAt
                it[createdAt] = dep.createdAt
            }
        }

        dependencies
    }

    override fun hasCyclicDependency(fromItemId: UUID, toItemId: UUID): Boolean = transaction(databaseManager.getDatabase()) {
        checkCyclicDependencyInternal(fromItemId, toItemId)
    }

    /**
     * Internal cyclic dependency check that must be called within an existing transaction.
     * Uses DFS to check if adding an edge from [fromItemId] to [toItemId] would create a cycle.
     * A cycle exists if there's already a path from toItemId back to fromItemId.
     */
    private fun checkCyclicDependencyInternal(fromItemId: UUID, toItemId: UUID): Boolean {
        if (fromItemId == toItemId) return true

        val visited = mutableSetOf<UUID>()
        val visiting = mutableSetOf<UUID>()

        fun hasCycle(currentItemId: UUID): Boolean {
            if (currentItemId in visited) return false
            if (currentItemId in visiting) return true

            visiting.add(currentItemId)

            // Follow outgoing BLOCKS edges (not IS_BLOCKED_BY)
            val outgoing = DependenciesTable.selectAll()
                .where { DependenciesTable.fromItemId eq currentItemId }
                .map { mapRowToDependency(it) }

            for (dep in outgoing) {
                if (dep.type != DependencyType.IS_BLOCKED_BY) {
                    if (dep.toItemId == fromItemId) return true
                    if (hasCycle(dep.toItemId)) return true
                }
            }

            // Follow incoming IS_BLOCKED_BY edges (reverse direction)
            val incoming = DependenciesTable.selectAll()
                .where { DependenciesTable.toItemId eq currentItemId }
                .map { mapRowToDependency(it) }

            for (dep in incoming) {
                if (dep.type == DependencyType.IS_BLOCKED_BY) {
                    if (dep.fromItemId == fromItemId) return true
                    if (hasCycle(dep.fromItemId)) return true
                }
            }

            visiting.remove(currentItemId)
            visited.add(currentItemId)
            return false
        }

        return hasCycle(toItemId)
    }

    private fun mapRowToDependency(row: ResultRow): Dependency {
        return Dependency(
            id = row[DependenciesTable.id].value,
            fromItemId = row[DependenciesTable.fromItemId],
            toItemId = row[DependenciesTable.toItemId],
            type = DependencyType.fromString(row[DependenciesTable.type]) ?: DependencyType.BLOCKS,
            unblockAt = row[DependenciesTable.unblockAt],
            createdAt = row[DependenciesTable.createdAt]
        )
    }
}
