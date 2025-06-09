package io.github.jpicklyk.mcptask.domain.repository

import io.github.jpicklyk.mcptask.domain.model.*
import java.time.Instant
import java.util.*

/**
 * Repository interface for managing work sessions in the system.
 * Provides operations for session lifecycle management and tracking.
 */
interface WorkSessionRepository {
    
    /**
     * Creates a new work session.
     * @param session The session to create
     * @return Result containing the created session or an error
     */
    suspend fun create(session: WorkSession): Result<WorkSession>
    
    /**
     * Retrieves a session by its ID.
     * @param sessionId The ID of the session to retrieve
     * @return Result containing the session if found, or null if not found
     */
    suspend fun getById(sessionId: String): Result<WorkSession?>
    
    /**
     * Retrieves all active sessions (not expired or terminated).
     * @return Result containing the list of active sessions
     */
    suspend fun getActiveSessions(): Result<List<WorkSession>>
    
    /**
     * Retrieves all sessions, including inactive ones.
     * @return Result containing all sessions
     */
    suspend fun getAllSessions(): Result<List<WorkSession>>
    
    /**
     * Updates an existing session.
     * @param session The updated session
     * @return Result containing the updated session or an error
     */
    suspend fun update(session: WorkSession): Result<WorkSession>
    
    /**
     * Updates the last activity timestamp for a session.
     * @param sessionId The session ID
     * @param activityTime The new activity timestamp (defaults to now)
     * @return Result containing the updated session or an error
     */
    suspend fun updateActivity(sessionId: String, activityTime: Instant = Instant.now()): Result<WorkSession>
    
    /**
     * Deletes a session by its ID.
     * @param sessionId The ID of the session to delete
     * @return Result indicating success or failure
     */
    suspend fun delete(sessionId: String): Result<Unit>
    
    /**
     * Deletes all inactive sessions.
     * @param inactiveThresholdMinutes Sessions inactive longer than this are considered for deletion
     * @return Result containing the number of sessions deleted
     */
    suspend fun deleteInactiveSessions(inactiveThresholdMinutes: Long = 120): Result<Int>
    
    /**
     * Finds sessions that are working on a specific entity.
     * @param entityType The type of entity (PROJECT, FEATURE, TASK)
     * @param entityId The ID of the entity
     * @return Result containing sessions working on the entity
     */
    suspend fun getSessionsWorkingOnEntity(entityType: String, entityId: UUID): Result<List<WorkSession>>
    
    /**
     * Finds sessions with git worktrees assigned to a specific scope.
     * @param scope The worktree scope (TASK, FEATURE, PROJECT)
     * @param entityId The entity ID assigned to the worktree
     * @return Result containing sessions with matching worktrees
     */
    suspend fun getSessionsWithWorktree(scope: LockScope, entityId: UUID): Result<List<WorkSession>>
    
    /**
     * Gets session statistics for monitoring.
     * @return Result containing session statistics
     */
    suspend fun getSessionStatistics(): Result<SessionStatistics>
    
    /**
     * Checks if a session exists and is active.
     * @param sessionId The session ID to check
     * @return Result containing true if session exists and is active
     */
    suspend fun isSessionActive(sessionId: String): Result<Boolean>
    
    /**
     * Registers or updates session capabilities.
     * @param sessionId The session ID
     * @param capabilities Set of capability strings
     * @return Result containing the updated session
     */
    suspend fun updateCapabilities(sessionId: String, capabilities: Set<String>): Result<WorkSession>
    
    /**
     * Associates a git worktree with a session.
     * @param sessionId The session ID
     * @param worktreeInfo The worktree information
     * @return Result containing the updated session
     */
    suspend fun setGitWorktree(sessionId: String, worktreeInfo: GitWorktreeInfo): Result<WorkSession>
    
    /**
     * Removes git worktree association from a session.
     * @param sessionId The session ID
     * @return Result containing the updated session
     */
    suspend fun clearGitWorktree(sessionId: String): Result<WorkSession>
}

/**
 * Statistics about sessions in the system for monitoring.
 */
data class SessionStatistics(
    /** Total number of sessions */
    val totalSessions: Int,
    
    /** Number of active sessions */
    val activeSessions: Int,
    
    /** Number of idle sessions */
    val idleSessions: Int,
    
    /** Number of expired sessions */
    val expiredSessions: Int,
    
    /** Sessions by client type */
    val sessionsByClient: Map<String, Int>,
    
    /** Average session duration in minutes */
    val averageSessionDurationMinutes: Double,
    
    /** Number of sessions with git worktrees */
    val sessionsWithWorktrees: Int,
    
    /** Most active sessions (by entity count) */
    val mostActiveSessions: List<Pair<String, Int>>,
    
    /** Distribution of session capabilities */
    val capabilityDistribution: Map<String, Int>
)