package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.domain.model.BacklinkRow
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.validation.DuplicateDependencyException
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.DependenciesTable
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/**
 * SQLite implementation of DependencyStore.
 *
 * All methods except [findByFromItemId] and [findByToItemId] are `suspend` and run in a
 * [suspendTransaction], which joins an enclosing suspend transaction when one is open.
 */
class SQLiteDependencyRepository(
    private val databaseManager: DatabaseManager
) : DependencyStore {
    override suspend fun create(dependency: Dependency): Dependency =
        databaseManager.writeTx("DependencyStore.create") {
            insertDependencyInTransaction(dependency)
        }

    private fun insertDependencyInTransaction(dependency: Dependency): Dependency {
        // Backstop: IS_BLOCKED_BY is an input alias and is never stored.
        val dep = dependency.normalized()

        // Check for duplicate dependencies
        val existing =
            DependenciesTable
                .selectAll()
                .where {
                    (DependenciesTable.fromItemId eq dep.fromItemId) and
                        (DependenciesTable.toItemId eq dep.toItemId) and
                        (DependenciesTable.type eq dep.type.name)
                }.singleOrNull()

        if (existing != null) {
            throw DuplicateDependencyException("A dependency of this type already exists between these items")
        }

        DependenciesTable.insert {
            it[id] = dep.id
            it[fromItemId] = dep.fromItemId
            it[toItemId] = dep.toItemId
            it[type] = dep.type.name
            it[unblockAt] = dep.unblockAt
            it[createdAt] = dep.createdAt
        }
        return dep
    }

    override suspend fun findById(id: UUID): Dependency? =
        databaseManager.readTx {
            DependenciesTable
                .selectAll()
                .where { DependenciesTable.id eq id }
                .map { mapRowToDependency(it) }
                .singleOrNull()
        }

    override suspend fun findByItemId(itemId: UUID): List<Dependency> =
        databaseManager.readTx {
            DependenciesTable
                .selectAll()
                .where { (DependenciesTable.fromItemId eq itemId) or (DependenciesTable.toItemId eq itemId) }
                .map { mapRowToDependency(it) }
        }

    override fun findByFromItemId(fromItemId: UUID): List<Dependency> =
        databaseManager.readTxBlocking {
            DependenciesTable
                .selectAll()
                .where { DependenciesTable.fromItemId eq fromItemId }
                .map { mapRowToDependency(it) }
        }

    override fun findByToItemId(toItemId: UUID): List<Dependency> =
        databaseManager.readTxBlocking {
            DependenciesTable
                .selectAll()
                .where { DependenciesTable.toItemId eq toItemId }
                .map { mapRowToDependency(it) }
        }

    override suspend fun delete(id: UUID): Boolean =
        databaseManager.writeTx("DependencyStore.delete") {
            DependenciesTable.deleteWhere { DependenciesTable.id eq id } > 0
        }

    override suspend fun deleteByItemId(itemId: UUID): Int =
        databaseManager.writeTx("DependencyStore.deleteByItemId") {
            DependenciesTable.deleteWhere {
                (DependenciesTable.fromItemId eq itemId) or (DependenciesTable.toItemId eq itemId)
            }
        }

    override suspend fun createBatch(dependencies: List<Dependency>): List<Dependency> =
        databaseManager.writeTx("DependencyStore.createBatch") {
            if (dependencies.isEmpty()) {
                return@writeTx emptyList()
            }
            // Backstop: IS_BLOCKED_BY is an input alias and is never stored.
            val normalized = dependencies.map { it.normalized() }

            // Phase 1: Check for duplicates within the batch itself
            val seen = mutableSetOf<Triple<UUID, UUID, DependencyType>>()
            for (dep in normalized) {
                val key = Triple(dep.fromItemId, dep.toItemId, dep.type)
                if (!seen.add(key)) {
                    throw ValidationException(
                        "Duplicate dependency within batch: ${dep.fromItemId} -> ${dep.toItemId} (${dep.type})"
                    )
                }
            }

            // Phase 2: Check for duplicates against existing dependencies
            for (dep in normalized) {
                val existing =
                    DependenciesTable
                        .selectAll()
                        .where {
                            (DependenciesTable.fromItemId eq dep.fromItemId) and
                                (DependenciesTable.toItemId eq dep.toItemId) and
                                (DependenciesTable.type eq dep.type.name)
                        }.singleOrNull()

                if (existing != null) {
                    throw DuplicateDependencyException(
                        "A dependency of type ${dep.type} already exists between items ${dep.fromItemId} and ${dep.toItemId}"
                    )
                }
            }

            // Phase 3: insert. Cycle detection is not the store's job (DependencyCommandService owns it).
            for (dep in normalized) {
                DependenciesTable.insert {
                    it[id] = dep.id
                    it[fromItemId] = dep.fromItemId
                    it[toItemId] = dep.toItemId
                    it[type] = dep.type.name
                    it[unblockAt] = dep.unblockAt
                    it[createdAt] = dep.createdAt
                }
            }

            normalized
        }

    override suspend fun findByItemIds(itemIds: Set<UUID>): Map<UUID, List<Dependency>> {
        if (itemIds.isEmpty()) return emptyMap()
        return databaseManager.readTx {
            val deps =
                DependenciesTable
                    .selectAll()
                    .where { (DependenciesTable.fromItemId inList itemIds) or (DependenciesTable.toItemId inList itemIds) }
                    .map { mapRowToDependency(it) }

            val result = mutableMapOf<UUID, MutableList<Dependency>>()
            for (dep in deps) {
                if (dep.fromItemId in itemIds) {
                    result.getOrPut(dep.fromItemId) { mutableListOf() }.add(dep)
                }
                if (dep.toItemId in itemIds) {
                    result.getOrPut(dep.toItemId) { mutableListOf() }.add(dep)
                }
            }
            result
        }
    }

    private fun mapRowToDependency(row: ResultRow): Dependency =
        Dependency(
            id = row[DependenciesTable.id].value,
            fromItemId = row[DependenciesTable.fromItemId],
            toItemId = row[DependenciesTable.toItemId],
            type = DependencyType.fromString(row[DependenciesTable.type]) ?: DependencyType.BLOCKS,
            unblockAt = row[DependenciesTable.unblockAt],
            createdAt = row[DependenciesTable.createdAt]
        )

    // -----------------------------------------------------------------------
    // Graph-aware backlinks query
    // -----------------------------------------------------------------------

    /**
     * Returns reverse-direction dependency edges that point *at* [itemId].
     *
     * A backlink row represents another item whose dependency edge has [itemId] as the target:
     *   `dependencies.to_item_id = itemId`
     *
     * The result is a list of [BacklinkRow] values containing the source item's UUID, the
     * dependency type on that edge, and the source item's title (JOIN-fetched from `work_items`).
     *
     * Uses the existing `idx_<tablename>_to_item_id` index on [DependenciesTable] — no full scan.
     *
     * @param itemId UUID of the target item to find backlinks for.
     * @param type   Optional filter: when non-null, only edges of this type are returned.
     */
    override suspend fun backlinks(
        itemId: UUID,
        type: DependencyType?,
    ): List<BacklinkRow> =
        databaseManager.readTx {
            val uuidType = UUIDColumnType()

            // Build the query using Exposed DSL, joining to work_items for fromTitle.
            // DependenciesTable has TWO foreign key references to WorkItemsTable (from_item_id
            // and to_item_id). An unqualified innerJoin would throw IllegalStateException due to
            // the ambiguous FK. Use an explicit join with onColumn/otherColumn to specify that
            // we are joining dependencies.from_item_id = work_items.id (to fetch the source title).
            val baseQuery =
                DependenciesTable
                    .join(
                        WorkItemsTable,
                        JoinType.INNER,
                        onColumn = DependenciesTable.fromItemId,
                        otherColumn = WorkItemsTable.id,
                    ).selectAll()
                    .where {
                        (DependenciesTable.toItemId eq itemId).let { cond ->
                            if (type != null) {
                                cond and (DependenciesTable.type eq type.name)
                            } else {
                                cond
                            }
                        }
                    }

            baseQuery.mapNotNull { row ->
                val depType = DependencyType.fromString(row[DependenciesTable.type]) ?: return@mapNotNull null
                val fromItemId = row[DependenciesTable.fromItemId]
                val fromTitle = row[WorkItemsTable.title]
                BacklinkRow(
                    fromItemId = fromItemId,
                    type = depType,
                    fromTitle = fromTitle,
                )
            }
        }
}
