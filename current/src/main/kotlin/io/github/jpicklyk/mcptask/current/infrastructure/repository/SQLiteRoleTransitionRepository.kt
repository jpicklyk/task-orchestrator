package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.RoleTransitionsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of RoleTransitionRepository.
 */
class SQLiteRoleTransitionRepository(
    private val databaseManager: DatabaseManager
) : RoleTransitionRepository {
    override suspend fun create(transition: RoleTransition): Result<RoleTransition> =
        databaseManager.suspendedTransaction("Failed to create RoleTransition") {
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
                it[RoleTransitionsTable.actorId] = transition.actorClaim?.id
                it[RoleTransitionsTable.actorKind] = transition.actorClaim?.kind?.toJsonString()
                it[RoleTransitionsTable.actorParent] = transition.actorClaim?.parent
                it[RoleTransitionsTable.actorProof] = transition.actorClaim?.proof
                it[RoleTransitionsTable.verificationStatus] = transition.verification?.status?.toJsonString()
                it[RoleTransitionsTable.verificationVerifier] = transition.verification?.verifier
                it[RoleTransitionsTable.verificationReason] = transition.verification?.reason
            }
            Result.Success(transition)
        }

    override suspend fun findByItemId(
        itemId: UUID,
        limit: Int
    ): Result<List<RoleTransition>> =
        databaseManager.suspendedTransaction("Failed to find RoleTransitions by itemId") {
            val transitions =
                RoleTransitionsTable
                    .selectAll()
                    .where { RoleTransitionsTable.itemId eq itemId }
                    .orderBy(RoleTransitionsTable.transitionedAt, SortOrder.DESC)
                    .limit(limit)
                    .map { mapRowToRoleTransition(it) }
            Result.Success(transitions)
        }

    override suspend fun findByTimeRange(
        startTime: Instant,
        endTime: Instant,
        role: String?,
        limit: Int
    ): Result<List<RoleTransition>> =
        databaseManager.suspendedTransaction("Failed to find RoleTransitions by time range") {
            var query =
                RoleTransitionsTable.selectAll().where {
                    (RoleTransitionsTable.transitionedAt greaterEq startTime) and
                        (RoleTransitionsTable.transitionedAt lessEq endTime)
                }

            if (role != null) {
                query =
                    query.andWhere {
                        (RoleTransitionsTable.fromRole eq role) or (RoleTransitionsTable.toRole eq role)
                    }
            }

            val transitions =
                query
                    .orderBy(RoleTransitionsTable.transitionedAt, SortOrder.DESC)
                    .limit(limit)
                    .map { mapRowToRoleTransition(it) }
            Result.Success(transitions)
        }

    override suspend fun findSince(
        since: Instant,
        limit: Int
    ): Result<List<RoleTransition>> =
        databaseManager.suspendedTransaction("Failed to find transitions since $since") {
            val results =
                RoleTransitionsTable
                    .selectAll()
                    .where { RoleTransitionsTable.transitionedAt greaterEq since }
                    .orderBy(RoleTransitionsTable.transitionedAt, SortOrder.DESC)
                    .limit(limit)
                    .map { mapRowToRoleTransition(it) }
            Result.Success(results)
        }

    override suspend fun deleteByItemId(itemId: UUID): Result<Int> =
        databaseManager.suspendedTransaction("Failed to delete RoleTransitions by itemId") {
            val deletedCount = RoleTransitionsTable.deleteWhere { RoleTransitionsTable.itemId eq itemId }
            Result.Success(deletedCount)
        }

    private fun mapRowToRoleTransition(row: ResultRow): RoleTransition {
        val transitionId = row[RoleTransitionsTable.id].value
        val actorClaim =
            row[RoleTransitionsTable.actorId]?.let { actorId ->
                val kindStr = row[RoleTransitionsTable.actorKind]
                if (kindStr == null) {
                    logger.warn(
                        "RoleTransition {}: actorId present but actorKind is null; skipping actor",
                        transitionId
                    )
                    return@let null
                }
                try {
                    ActorClaim(
                        id = actorId,
                        kind = ActorKind.fromString(kindStr),
                        parent = row[RoleTransitionsTable.actorParent],
                        proof = row[RoleTransitionsTable.actorProof]
                    )
                } catch (e: IllegalArgumentException) {
                    logger.warn("RoleTransition {}: invalid actorKind '{}'; skipping actor", transitionId, kindStr)
                    null
                }
            }
        val verification =
            row[RoleTransitionsTable.verificationStatus]?.let { status ->
                try {
                    VerificationResult(
                        status = VerificationStatus.fromString(status),
                        verifier = row[RoleTransitionsTable.verificationVerifier],
                        reason = row[RoleTransitionsTable.verificationReason]
                    )
                } catch (e: IllegalArgumentException) {
                    logger.warn(
                        "RoleTransition {}: invalid verificationStatus '{}'; skipping verification",
                        transitionId,
                        status
                    )
                    null
                }
            }
        return RoleTransition(
            id = transitionId,
            itemId = row[RoleTransitionsTable.itemId],
            fromRole = row[RoleTransitionsTable.fromRole],
            toRole = row[RoleTransitionsTable.toRole],
            fromStatusLabel = row[RoleTransitionsTable.fromStatusLabel],
            toStatusLabel = row[RoleTransitionsTable.toStatusLabel],
            trigger = row[RoleTransitionsTable.trigger],
            summary = row[RoleTransitionsTable.summary],
            transitionedAt = row[RoleTransitionsTable.transitionedAt],
            actorClaim = actorClaim,
            verification = verification
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SQLiteRoleTransitionRepository::class.java)
    }
}
