package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TaskLockRepository
import io.github.jpicklyk.mcptask.infrastructure.database.schema.TaskLocksTable
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.v1.core.transactions.transaction
import org.jetbrains.exposed.v1.sql.*
import org.jetbrains.exposed.v1.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.v1.sql.SqlExpressionBuilder.less
import java.time.Instant
import java.util.*

/**
 * SQLite implementation of TaskLockRepository using Exposed ORM.
 */
class SQLiteTaskLockRepository : TaskLockRepository {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    override suspend fun create(lock: TaskLock): Result<TaskLock> = try {
        transaction {
            TaskLocksTable.insert { row ->
                row[id] = lock.id
                row[lockScope] = lock.scope
                row[entityId] = lock.entityId
                row[sessionId] = lock.sessionId
                row[lockType] = lock.lockType
                row[lockedAt] = lock.lockedAt
                row[expiresAt] = lock.expiresAt
                row[lastRenewed] = lock.lastRenewed
                row[lockContext] = json.encodeToString(lock.lockContext)
                row[affectedEntities] = if (lock.affectedEntities.isNotEmpty()) {
                    json.encodeToString(lock.affectedEntities.map { it.toString() })
                } else null
                row[createdAt] = lock.lockedAt
            }
            Result.Success(lock)
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to create lock: ${e.message}", e)
    }
    
    override suspend fun getById(lockId: UUID): Result<TaskLock?> = try {
        transaction {
            val row = TaskLocksTable.select { TaskLocksTable.id eq lockId }.singleOrNull()
            Result.Success(row?.let { mapRowToTaskLock(it) })
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to get lock by ID: ${e.message}", e)
    }
    
    override suspend fun getActiveLocksForEntity(scope: LockScope, entityId: UUID): Result<List<TaskLock>> = try {
        transaction {
            val now = Instant.now()
            val rows = TaskLocksTable.select {
                (TaskLocksTable.lockScope eq scope) and
                (TaskLocksTable.entityId eq entityId) and
                (TaskLocksTable.expiresAt greater now)
            }.toList()
            
            Result.Success(rows.map { mapRowToTaskLock(it) })
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to get active locks for entity: ${e.message}", e)
    }
    
    override suspend fun getActiveLocksForEntities(entities: List<Pair<LockScope, UUID>>): Result<List<TaskLock>> = try {
        transaction {
            val now = Instant.now()
            val conditions = entities.map { (scope, entityId) ->
                (TaskLocksTable.lockScope eq scope) and (TaskLocksTable.entityId eq entityId)
            }
            
            if (conditions.isEmpty()) {
                return@transaction Result.Success(emptyList<TaskLock>())
            }
            
            val combinedCondition = conditions.reduce { acc, condition -> acc or condition }
            val rows = TaskLocksTable.select {
                combinedCondition and (TaskLocksTable.expiresAt greater now)
            }.toList()
            
            Result.Success(rows.map { mapRowToTaskLock(it) })
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to get active locks for entities: ${e.message}", e)
    }
    
    override suspend fun getActiveLocksForScope(scope: LockScope): Result<List<TaskLock>> = try {
        transaction {
            val now = Instant.now()
            val rows = TaskLocksTable.select {
                (TaskLocksTable.lockScope eq scope) and
                (TaskLocksTable.expiresAt greater now)
            }.toList()
            
            Result.Success(rows.map { mapRowToTaskLock(it) })
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to get active locks for scope: ${e.message}", e)
    }
    
    override suspend fun getActiveLocksForSession(sessionId: String): Result<List<TaskLock>> = try {
        transaction {
            val now = Instant.now()
            val rows = TaskLocksTable.select {
                (TaskLocksTable.sessionId eq sessionId) and
                (TaskLocksTable.expiresAt greater now)
            }.toList()
            
            Result.Success(rows.map { mapRowToTaskLock(it) })
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to get active locks for session: ${e.message}", e)
    }
    
    override suspend fun getAllActiveLocks(): Result<List<TaskLock>> = try {
        transaction {
            val now = Instant.now()
            val rows = TaskLocksTable.select {
                TaskLocksTable.expiresAt greater now
            }.toList()
            
            Result.Success(rows.map { mapRowToTaskLock(it) })
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to get all active locks: ${e.message}", e)
    }
    
    override suspend fun update(lock: TaskLock): Result<TaskLock> = try {
        transaction {
            val updatedRows = TaskLocksTable.update({ TaskLocksTable.id eq lock.id }) { row ->
                row[lockScope] = lock.scope
                row[entityId] = lock.entityId
                row[sessionId] = lock.sessionId
                row[lockType] = lock.lockType
                row[lockedAt] = lock.lockedAt
                row[expiresAt] = lock.expiresAt
                row[lastRenewed] = lock.lastRenewed
                row[lockContext] = json.encodeToString(lock.lockContext)
                row[affectedEntities] = if (lock.affectedEntities.isNotEmpty()) {
                    json.encodeToString(lock.affectedEntities.map { it.toString() })
                } else null
            }
            
            if (updatedRows > 0) {
                Result.Success(lock)
            } else {
                Result.Error(ErrorCodes.RESOURCE_NOT_FOUND, "Lock not found")
            }
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to update lock: ${e.message}", e)
    }
    
    override suspend fun delete(lockId: UUID): Result<Unit> = try {
        transaction {
            val deletedRows = TaskLocksTable.deleteWhere { TaskLocksTable.id eq lockId }
            if (deletedRows > 0) {
                Result.Success(Unit)
            } else {
                Result.Error(ErrorCodes.RESOURCE_NOT_FOUND, "Lock not found")
            }
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to delete lock: ${e.message}", e)
    }
    
    override suspend fun deleteAllLocksForSession(sessionId: String): Result<Int> = try {
        transaction {
            val deletedRows = TaskLocksTable.deleteWhere { TaskLocksTable.sessionId eq sessionId }
            Result.Success(deletedRows)
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to delete locks for session: ${e.message}", e)
    }
    
    override suspend fun deleteExpiredLocks(beforeTime: Instant): Result<Int> = try {
        transaction {
            val deletedRows = TaskLocksTable.deleteWhere { TaskLocksTable.expiresAt less beforeTime }
            Result.Success(deletedRows)
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to delete expired locks: ${e.message}", e)
    }
    
    override suspend fun checkLockConflict(
        scope: LockScope,
        entityId: UUID,
        lockType: LockType,
        sessionId: String
    ): Result<Boolean> = try {
        transaction {
            val now = Instant.now()
            
            // Get all active locks for this entity
            val existingLocks = TaskLocksTable.select {
                (TaskLocksTable.lockScope eq scope) and
                (TaskLocksTable.entityId eq entityId) and
                (TaskLocksTable.expiresAt greater now)
            }.map { mapRowToTaskLock(it) }
            
            // Check for conflicts based on lock compatibility rules
            val hasConflict = existingLocks.any { existingLock ->
                // Same session can usually acquire additional locks (with some exceptions)
                if (existingLock.sessionId == sessionId) {
                    // Same session conflicts only with exclusive locks when trying to acquire exclusive
                    existingLock.lockType == LockType.EXCLUSIVE && lockType == LockType.EXCLUSIVE
                } else {
                    // Different session - check compatibility matrix
                    !areLocksCompatible(existingLock.lockType, lockType)
                }
            }
            
            Result.Success(hasConflict)
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to check lock conflict: ${e.message}", e)
    }
    
    override suspend fun findAffectedLocks(scope: LockScope, entityId: UUID): Result<List<TaskLock>> = try {
        // This is a simplified implementation - in practice, you'd need to query
        // the entity relationships to find child entities
        transaction {
            val now = Instant.now()
            val rows = TaskLocksTable.select {
                (TaskLocksTable.expiresAt greater now)
            }.filter { row ->
                val affectedEntitiesJson = row[TaskLocksTable.affectedEntities]
                if (affectedEntitiesJson != null) {
                    val affectedIds: List<String> = json.decodeFromString(affectedEntitiesJson)
                    affectedIds.contains(entityId.toString())
                } else {
                    false
                }
            }
            
            Result.Success(rows.map { mapRowToTaskLock(it) })
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to find affected locks: ${e.message}", e)
    }
    
    override suspend fun renewLock(lockId: UUID, newExpirationTime: Instant): Result<TaskLock> = try {
        transaction {
            val existingLock = TaskLocksTable.select { TaskLocksTable.id eq lockId }.singleOrNull()
                ?.let { mapRowToTaskLock(it) }
                ?: return@transaction Result.Error<TaskLock>(ErrorCodes.RESOURCE_NOT_FOUND, "Lock not found")
            
            val renewedLock = existingLock.renew(newExpirationTime)
            
            TaskLocksTable.update({ TaskLocksTable.id eq lockId }) { row ->
                row[expiresAt] = renewedLock.expiresAt
                row[lastRenewed] = renewedLock.lastRenewed
            }
            
            Result.Success(renewedLock)
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to renew lock: ${e.message}", e)
    }
    
    override suspend fun getLockStatistics(): Result<LockStatistics> = try {
        transaction {
            val now = Instant.now()
            val activeLocks = TaskLocksTable.select {
                TaskLocksTable.expiresAt greater now
            }.toList()
            
            val totalActiveLocks = activeLocks.size
            val locksByScope = activeLocks.groupBy { it[TaskLocksTable.lockScope] }
                .mapValues { it.value.size }
            val locksByType = activeLocks.groupBy { it[TaskLocksTable.lockType] }
                .mapValues { it.value.size }
            
            val activeSessionsWithLocks = activeLocks.map { it[TaskLocksTable.sessionId] }.distinct().size
            
            val expiredLocks = TaskLocksTable.select {
                TaskLocksTable.expiresAt lessEq now
            }.count().toInt()
            
            // Calculate average lock duration
            val lockDurations = activeLocks.map { row ->
                val lockedAt = row[TaskLocksTable.lockedAt]
                val expiresAt = row[TaskLocksTable.expiresAt]
                java.time.Duration.between(lockedAt, expiresAt).toMinutes().toDouble()
            }
            val averageDuration = if (lockDurations.isNotEmpty()) {
                lockDurations.average()
            } else 0.0
            
            // Find most locked entities (simplified)
            val entityCounts = activeLocks.groupBy { it[TaskLocksTable.entityId] }
                .mapValues { it.value.size }
                .toList()
                .sortedByDescending { it.second }
                .take(5)
            
            Result.Success(
                LockStatistics(
                    totalActiveLocks = totalActiveLocks,
                    locksByScope = locksByScope,
                    locksByType = locksByType,
                    activeSessionsWithLocks = activeSessionsWithLocks,
                    averageLockDurationMinutes = averageDuration,
                    expiredLocksCount = expiredLocks,
                    mostLockedEntities = entityCounts
                )
            )
        }
    } catch (e: Exception) {
        Result.Error(ErrorCodes.DATABASE_ERROR, "Failed to get lock statistics: ${e.message}", e)
    }
    
    private fun mapRowToTaskLock(row: ResultRow): TaskLock {
        val affectedEntitiesJson = row[TaskLocksTable.affectedEntities]
        val affectedEntities = if (affectedEntitiesJson != null) {
            val entityStrings: List<String> = json.decodeFromString(affectedEntitiesJson)
            entityStrings.map { UUID.fromString(it) }.toSet()
        } else {
            emptySet()
        }
        
        return TaskLock(
            id = row[TaskLocksTable.id].value,
            scope = row[TaskLocksTable.lockScope],
            entityId = row[TaskLocksTable.entityId],
            sessionId = row[TaskLocksTable.sessionId],
            lockType = row[TaskLocksTable.lockType],
            lockedAt = row[TaskLocksTable.lockedAt],
            expiresAt = row[TaskLocksTable.expiresAt],
            lastRenewed = row[TaskLocksTable.lastRenewed],
            lockContext = json.decodeFromString(row[TaskLocksTable.lockContext]),
            affectedEntities = affectedEntities
        )
    }
    
    private fun areLocksCompatible(existingType: LockType, newType: LockType): Boolean {
        return when (existingType) {
            LockType.EXCLUSIVE -> false  // Exclusive blocks everything
            LockType.SHARED_READ -> newType == LockType.SHARED_READ  // Shared read only allows other shared reads
            LockType.SHARED_WRITE -> newType in setOf(LockType.SHARED_READ, LockType.SHARED_WRITE, LockType.SECTION_WRITE)
            LockType.SECTION_WRITE -> newType in setOf(LockType.SHARED_READ, LockType.SHARED_WRITE, LockType.SECTION_WRITE)
            LockType.NONE -> true  // No lock allows everything
        }
    }
}