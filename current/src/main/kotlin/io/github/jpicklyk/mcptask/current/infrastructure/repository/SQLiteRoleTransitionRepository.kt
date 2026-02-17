package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.RoleTransitionsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of RoleTransitionRepository.
 */
class SQLiteRoleTransitionRepository(private val databaseManager: DatabaseManager) : RoleTransitionRepository {

    override suspend fun create(transition: RoleTransition): Result<RoleTransition> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            RoleTransitionsTable.insert {
                it[id] = transition.id
                it[itemId] = transition.itemId
                it[fromRole] = transition.fromRole
                it[toRole] = transition.toRole
                it[fromStatusLabel] = transition.fromStatusLabel
                it[toStatusLabel] = transition.toStatusLabel
                it[trigger] = transition.trigger
                it[summary] = transition.summary
                it[transitionedAt] = transition.transitionedAt
            }
            Result.Success(transition)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to create RoleTransition: ${e.message}", e))
    }

    override suspend fun findByItemId(itemId: UUID, limit: Int): Result<List<RoleTransition>> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val transitions = RoleTransitionsTable.selectAll()
                .where { RoleTransitionsTable.itemId eq itemId }
                .orderBy(RoleTransitionsTable.transitionedAt, SortOrder.DESC)
                .limit(limit)
                .map { mapRowToRoleTransition(it) }
            Result.Success(transitions)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to find RoleTransitions by itemId: ${e.message}", e))
    }

    override suspend fun findByTimeRange(
        startTime: Instant,
        endTime: Instant,
        role: String?,
        limit: Int
    ): Result<List<RoleTransition>> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            var query = RoleTransitionsTable.selectAll().where {
                (RoleTransitionsTable.transitionedAt greaterEq startTime) and
                        (RoleTransitionsTable.transitionedAt lessEq endTime)
            }

            if (role != null) {
                query = query.andWhere {
                    (RoleTransitionsTable.fromRole eq role) or (RoleTransitionsTable.toRole eq role)
                }
            }

            val transitions = query
                .orderBy(RoleTransitionsTable.transitionedAt, SortOrder.DESC)
                .limit(limit)
                .map { mapRowToRoleTransition(it) }
            Result.Success(transitions)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to find RoleTransitions by time range: ${e.message}", e))
    }

    override suspend fun deleteByItemId(itemId: UUID): Result<Int> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val deletedCount = RoleTransitionsTable.deleteWhere { RoleTransitionsTable.itemId eq itemId }
            Result.Success(deletedCount)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to delete RoleTransitions by itemId: ${e.message}", e))
    }

    private fun mapRowToRoleTransition(row: ResultRow): RoleTransition {
        return RoleTransition(
            id = row[RoleTransitionsTable.id].value,
            itemId = row[RoleTransitionsTable.itemId],
            fromRole = row[RoleTransitionsTable.fromRole],
            toRole = row[RoleTransitionsTable.toRole],
            fromStatusLabel = row[RoleTransitionsTable.fromStatusLabel],
            toStatusLabel = row[RoleTransitionsTable.toStatusLabel],
            trigger = row[RoleTransitionsTable.trigger],
            summary = row[RoleTransitionsTable.summary],
            transitionedAt = row[RoleTransitionsTable.transitionedAt]
        )
    }
}
