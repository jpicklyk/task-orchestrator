package io.github.jpicklyk.mcptask.domain.model

import java.time.Instant
import java.util.*

/**
 * Represents an active work session from a Claude Code instance or MCP client.
 * Sessions track active work, manage locks, and coordinate with git worktrees.
 */
data class WorkSession(
    /** Unique session identifier */
    val sessionId: String,
    
    /** Information about the Claude Code instance */
    val instanceInfo: InstanceInfo,
    
    /** When the session was started */
    val startedAt: Instant = Instant.now(),
    
    /** Last recorded activity timestamp */
    val lastActivity: Instant = Instant.now(),
    
    /** Tasks currently being worked on */
    val activeTasks: Set<UUID> = emptySet(),
    
    /** Features currently assigned to this session */
    val activeFeatures: Set<UUID> = emptySet(),
    
    /** Projects currently being worked on */
    val activeProjects: Set<UUID> = emptySet(),
    
    /** Git worktree information if applicable */
    val gitWorktree: GitWorktreeInfo? = null,
    
    /** Supported operations and capabilities */
    val capabilities: Set<String> = emptySet()
) {
    /**
     * Validates that the session meets all business rules.
     */
    fun validate() {
        require(sessionId.isNotBlank()) { "Session ID must not be empty" }
        require(lastActivity.isAfter(startedAt.minusSeconds(1))) { "Last activity must be at or after session start" }
        instanceInfo.validate()
        gitWorktree?.validate()
    }
    
    /**
     * Checks if this session is considered inactive based on the last activity.
     */
    fun isInactive(timeoutMinutes: Long = 120): Boolean {
        val threshold = Instant.now().minusSeconds(timeoutMinutes * 60)
        return lastActivity.isBefore(threshold)
    }
    
    /**
     * Updates the last activity timestamp to now.
     */
    fun updateActivity(): WorkSession {
        return copy(lastActivity = Instant.now())
    }
    
    /**
     * Adds a task to the active tasks set.
     */
    fun addActiveTask(taskId: UUID): WorkSession {
        return copy(activeTasks = activeTasks + taskId)
    }
    
    /**
     * Removes a task from the active tasks set.
     */
    fun removeActiveTask(taskId: UUID): WorkSession {
        return copy(activeTasks = activeTasks - taskId)
    }
    
    /**
     * Adds a feature to the active features set.
     */
    fun addActiveFeature(featureId: UUID): WorkSession {
        return copy(activeFeatures = activeFeatures + featureId)
    }
    
    /**
     * Removes a feature from the active features set.
     */
    fun removeActiveFeature(featureId: UUID): WorkSession {
        return copy(activeFeatures = activeFeatures - featureId)
    }
}

/**
 * Information about the Claude Code instance or MCP client.
 */
data class InstanceInfo(
    /** Client identifier (e.g., "claude-desktop", "claude-code") */
    val clientId: String,
    
    /** Client version */
    val version: String,
    
    /** Host machine identifier */
    val hostname: String? = null,
    
    /** User identifier if available */
    val userContext: String? = null
) {
    fun validate() {
        require(clientId.isNotBlank()) { "Client ID must not be empty" }
        require(version.isNotBlank()) { "Version must not be empty" }
    }
}

/**
 * Git worktree information for filesystem isolation and branch coordination.
 */
data class GitWorktreeInfo(
    /** Filesystem path to the worktree */
    val worktreePath: String,
    
    /** Git branch name */
    val branchName: String,
    
    /** Common base commit for merge planning */
    val baseCommit: String,
    
    /** Last sync with main branch */
    val lastSync: Instant = Instant.now(),
    
    /** What this worktree is assigned to (TASK, FEATURE, PROJECT) */
    val assignedScope: LockScope,
    
    /** ID of the assigned entity */
    val assignedEntityId: UUID
) {
    fun validate() {
        require(worktreePath.isNotBlank()) { "Worktree path must not be empty" }
        require(branchName.isNotBlank()) { "Branch name must not be empty" }
        require(baseCommit.isNotBlank()) { "Base commit must not be empty" }
    }
    
    /**
     * Checks if the worktree needs to be synced with the main branch.
     */
    fun needsSync(thresholdHours: Long = 4): Boolean {
        val threshold = Instant.now().minusSeconds(thresholdHours * 3600)
        return lastSync.isBefore(threshold)
    }
    
    /**
     * Updates the last sync timestamp.
     */
    fun updateSync(): GitWorktreeInfo {
        return copy(lastSync = Instant.now())
    }
}

/**
 * Session lifecycle states for tracking session progression.
 */
enum class SessionState {
    /** Session is active and working */
    ACTIVE,
    
    /** Session is idle but not expired */
    IDLE,
    
    /** Session has expired and should be cleaned up */
    EXPIRED,
    
    /** Session was explicitly terminated */
    TERMINATED
}

/**
 * Configuration for session management.
 */
data class SessionConfig(
    /** Default session timeout in minutes */
    val defaultTimeoutMinutes: Long = 120,
    
    /** Maximum session timeout in minutes */
    val maxTimeoutMinutes: Long = 480,
    
    /** Heartbeat interval in minutes */
    val heartbeatIntervalMinutes: Long = 10,
    
    /** Cleanup interval for expired sessions in minutes */
    val cleanupIntervalMinutes: Long = 15
) {
    fun validate() {
        require(defaultTimeoutMinutes > 0) { "Default timeout must be positive" }
        require(maxTimeoutMinutes >= defaultTimeoutMinutes) { "Max timeout must be >= default timeout" }
        require(heartbeatIntervalMinutes > 0) { "Heartbeat interval must be positive" }
        require(cleanupIntervalMinutes > 0) { "Cleanup interval must be positive" }
    }
}