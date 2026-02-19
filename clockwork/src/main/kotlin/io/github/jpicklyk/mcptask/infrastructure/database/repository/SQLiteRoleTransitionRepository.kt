package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.schema.RoleTransitionsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.andWhere
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
                it[id] = transition.id.toString()
                it[entityId] = transition.entityId.toString()
                it[entityType] = transition.entityType
                it[fromRole] = transition.fromRole
                it[toRole] = transition.toRole
                it[fromStatus] = transition.fromStatus
                it[toStatus] = transition.toStatus
                it[transitionedAt] = transition.transitionedAt.toString()
                it[trigger] = transition.trigger
                it[summary] = transition.summary
            }
            Result.Success(transition)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to create role transition: ${e.message}"))
    }

    override suspend fun findByEntityId(
        entityId: UUID,
        entityType: String?
    ): Result<List<RoleTransition>> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val query = RoleTransitionsTable.selectAll().where { RoleTransitionsTable.entityId eq entityId.toString() }

            val filtered = if (entityType != null) {
                query.andWhere { RoleTransitionsTable.entityType eq entityType }
            } else {
                query
            }

            val transitions = filtered
                .orderBy(RoleTransitionsTable.transitionedAt)
                .map { it.toRoleTransition() }

            Result.Success(transitions)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to find role transitions by entity ID: ${e.message}"))
    }

    override suspend fun findByTimeRange(
        startTime: Instant,
        endTime: Instant,
        entityType: String?,
        role: String?
    ): Result<List<RoleTransition>> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            var query = RoleTransitionsTable.selectAll().where {
                (RoleTransitionsTable.transitionedAt greaterEq startTime.toString()) and
                (RoleTransitionsTable.transitionedAt lessEq endTime.toString())
            }

            if (entityType != null) {
                query = query.andWhere { RoleTransitionsTable.entityType eq entityType }
            }

            if (role != null) {
                query = query.andWhere { RoleTransitionsTable.toRole eq role }
            }

            val transitions = query
                .orderBy(RoleTransitionsTable.transitionedAt)
                .map { it.toRoleTransition() }

            Result.Success(transitions)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to find role transitions by time range: ${e.message}"))
    }

    override suspend fun deleteByEntityId(entityId: UUID): Result<Int> = try {
        newSuspendedTransaction(db = databaseManager.getDatabase()) {
            val count = RoleTransitionsTable.deleteWhere {
                RoleTransitionsTable.entityId eq entityId.toString()
            }
            Result.Success(count)
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("Failed to delete role transitions by entity ID: ${e.message}"))
    }

    private fun ResultRow.toRoleTransition(): RoleTransition {
        return RoleTransition(
            id = UUID.fromString(this[RoleTransitionsTable.id]),
            entityId = UUID.fromString(this[RoleTransitionsTable.entityId]),
            entityType = this[RoleTransitionsTable.entityType],
            fromRole = this[RoleTransitionsTable.fromRole],
            toRole = this[RoleTransitionsTable.toRole],
            fromStatus = this[RoleTransitionsTable.fromStatus],
            toStatus = this[RoleTransitionsTable.toStatus],
            transitionedAt = Instant.parse(this[RoleTransitionsTable.transitionedAt]),
            trigger = this[RoleTransitionsTable.trigger],
            summary = this[RoleTransitionsTable.summary]
        )
    }
}
