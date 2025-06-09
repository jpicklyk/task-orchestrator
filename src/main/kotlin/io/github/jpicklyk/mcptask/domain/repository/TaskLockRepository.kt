package io.github.jpicklyk.mcptask.domain.repository

import io.github.jpicklyk.mcptask.domain.model.*
import java.time.Instant
import java.util.*

/**
 * Repository interface for managing task locks in the system.
 * Provides operations for lock acquisition, release, and conflict detection.
 */
interface TaskLockRepository {
    
    /**
     * Creates a new lock in the repository.
     * @param lock The lock to create
     * @return Result containing the created lock or an error
     */
    suspend fun create(lock: TaskLock): Result<TaskLock>
    
    /**
     * Retrieves a lock by its ID.
     * @param lockId The ID of the lock to retrieve
     * @return Result containing the lock if found, or an error
     */
    suspend fun getById(lockId: UUID): Result<TaskLock?>
    
    /**
     * Retrieves all active locks for a specific entity and scope.
     * @param scope The scope of the locks to retrieve
     * @param entityId The ID of the entity
     * @return Result containing the list of active locks
     */
    suspend fun getActiveLocksForEntity(scope: LockScope, entityId: UUID): Result<List<TaskLock>>
    
    /**
     * Retrieves all active locks for multiple entities.
     * @param entities List of scope and entity ID pairs
     * @return Result containing the list of active locks
     */
    suspend fun getActiveLocksForEntities(entities: List<Pair<LockScope, UUID>>): Result<List<TaskLock>>
    
    /**
     * Retrieves all active locks for a specific scope.
     * @param scope The scope to filter by
     * @return Result containing the list of active locks
     */
    suspend fun getActiveLocksForScope(scope: LockScope): Result<List<TaskLock>>
    
    /**
     * Retrieves all active locks for a specific session.
     * @param sessionId The session ID
     * @return Result containing the list of active locks for the session
     */
    suspend fun getActiveLocksForSession(sessionId: String): Result<List<TaskLock>>
    
    /**
     * Retrieves all active locks in the system.
     * @return Result containing all active locks
     */
    suspend fun getAllActiveLocks(): Result<List<TaskLock>>
    
    /**
     * Updates an existing lock (typically for renewal).
     * @param lock The updated lock
     * @return Result containing the updated lock or an error
     */
    suspend fun update(lock: TaskLock): Result<TaskLock>
    
    /**
     * Deletes a lock by its ID.
     * @param lockId The ID of the lock to delete
     * @return Result indicating success or failure
     */
    suspend fun delete(lockId: UUID): Result<Unit>
    
    /**
     * Deletes all locks for a specific session.
     * @param sessionId The session ID
     * @return Result containing the number of locks deleted
     */
    suspend fun deleteAllLocksForSession(sessionId: String): Result<Int>
    
    /**
     * Deletes all expired locks.
     * @param beforeTime Only delete locks that expired before this time
     * @return Result containing the number of expired locks deleted
     */
    suspend fun deleteExpiredLocks(beforeTime: Instant = Instant.now()): Result<Int>
    
    /**
     * Checks if acquiring a lock would conflict with existing locks.
     * @param scope The scope of the proposed lock
     * @param entityId The entity ID for the proposed lock
     * @param lockType The type of lock being proposed
     * @param sessionId The session requesting the lock
     * @return Result containing true if there would be a conflict, false otherwise
     */
    suspend fun checkLockConflict(
        scope: LockScope,
        entityId: UUID,
        lockType: LockType,
        sessionId: String
    ): Result<Boolean>
    
    /**
     * Finds locks that would be affected by a hierarchical lock.
     * For example, if locking a feature, find all task locks within that feature.
     * @param scope The scope of the parent lock
     * @param entityId The entity ID of the parent
     * @return Result containing the list of affected locks
     */
    suspend fun findAffectedLocks(scope: LockScope, entityId: UUID): Result<List<TaskLock>>
    
    /**
     * Renews a lock by extending its expiration time.
     * @param lockId The ID of the lock to renew
     * @param newExpirationTime The new expiration time
     * @return Result containing the renewed lock or an error
     */
    suspend fun renewLock(lockId: UUID, newExpirationTime: Instant): Result<TaskLock>
    
    /**
     * Gets lock statistics for monitoring and observability.
     * @return Result containing lock statistics
     */
    suspend fun getLockStatistics(): Result<LockStatistics>
}

/**
 * Statistics about locks in the system for monitoring.
 */
data class LockStatistics(
    /** Total number of active locks */
    val totalActiveLocks: Int,
    
    /** Number of locks by scope */
    val locksByScope: Map<LockScope, Int>,
    
    /** Number of locks by type */
    val locksByType: Map<LockType, Int>,
    
    /** Number of active sessions with locks */
    val activeSessionsWithLocks: Int,
    
    /** Average lock duration in minutes */
    val averageLockDurationMinutes: Double,
    
    /** Number of expired locks that need cleanup */
    val expiredLocksCount: Int,
    
    /** Top 5 most locked entities */
    val mostLockedEntities: List<Pair<UUID, Int>>
)