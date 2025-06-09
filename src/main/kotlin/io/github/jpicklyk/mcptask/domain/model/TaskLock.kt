package io.github.jpicklyk.mcptask.domain.model

import java.time.Instant
import java.util.*

/**
 * Represents a lock on an entity (project, feature, task, or section) to prevent concurrent modifications.
 * Supports hierarchical locking where parent locks coordinate access to child entities.
 */
data class TaskLock(
    /** Unique identifier for the lock */
    val id: UUID = UUID.randomUUID(),
    
    /** Scope of the lock (what level is being locked) */
    val scope: LockScope,
    
    /** ID of the entity being locked (could be projectId, featureId, taskId, or sectionId) */
    val entityId: UUID,
    
    /** Session identifier of the holder */
    val sessionId: String,
    
    /** Type of lock acquired */
    val lockType: LockType,
    
    /** When the lock was acquired */
    val lockedAt: Instant = Instant.now(),
    
    /** When the lock will automatically expire */
    val expiresAt: Instant,
    
    /** Last time the lock was renewed */
    val lastRenewed: Instant = Instant.now(),
    
    /** Context information about what operation requires the lock */
    val lockContext: LockContext,
    
    /** All entity IDs affected by this lock (for hierarchical locks) */
    val affectedEntities: Set<UUID> = emptySet()
) {
    /**
     * Validates that the lock meets all business rules.
     */
    fun validate() {
        require(sessionId.isNotBlank()) { "Session ID must not be empty" }
        require(expiresAt.isAfter(lockedAt)) { "Expiration time must be after lock acquisition time" }
        require(lastRenewed.isAfter(lockedAt.minusSeconds(1))) { "Last renewed time must be at or after lock acquisition" }
    }
    
    /**
     * Checks if this lock is currently expired.
     */
    fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)
    
    /**
     * Checks if this lock needs renewal (within threshold of expiration).
     */
    fun needsRenewal(thresholdMinutes: Long = 5): Boolean {
        val threshold = Instant.now().plusSeconds(thresholdMinutes * 60)
        return threshold.isAfter(expiresAt)
    }
    
    /**
     * Creates a renewed version of this lock with extended expiration.
     */
    fun renew(newExpirationTime: Instant): TaskLock {
        require(newExpirationTime.isAfter(Instant.now())) { "New expiration time must be in the future" }
        return copy(
            expiresAt = newExpirationTime,
            lastRenewed = Instant.now()
        )
    }
}

/**
 * Defines the scope/level of a lock in the hierarchy.
 */
enum class LockScope {
    /** Lock entire project (all features/tasks within) */
    PROJECT,
    
    /** Lock entire feature (all tasks within) */
    FEATURE,
    
    /** Lock specific task */
    TASK,
    
    /** Lock specific sections across any entity */
    SECTION
}

/**
 * Defines the type of lock and its access permissions.
 */
enum class LockType {
    /** Full write access, blocks all other operations */
    EXCLUSIVE,
    
    /** Read access, allows other reads but blocks writes */
    SHARED_READ,
    
    /** Write access, allows coordination between agents */
    SHARED_WRITE,
    
    /** Write access to specific sections only */
    SECTION_WRITE,
    
    /** No lock needed (for read-only operations) */
    NONE
}

/**
 * Provides context about why a lock was acquired and what operation requires it.
 */
data class LockContext(
    /** The operation that requires the lock */
    val operation: String,
    
    /** Specific sections being targeted (for section-specific locks) */
    val targetSections: List<UUID>? = null,
    
    /** Git context information for worktree integration */
    val gitContext: GitContext? = null,
    
    /** Assignment type for feature/project assignments */
    val assignmentType: AssignmentType? = null,
    
    /** Additional metadata for the lock */
    val metadata: Map<String, String> = emptyMap()
) {
    fun validate() {
        require(operation.isNotBlank()) { "Operation must not be empty" }
    }
}

/**
 * Git worktree context for lock coordination with filesystem isolation.
 */
data class GitContext(
    /** Filesystem path to the worktree */
    val worktreePath: String?,
    
    /** Git branch name */
    val branchName: String?,
    
    /** Common base commit for merge planning */
    val baseCommit: String?,
    
    /** Files being modified in this operation */
    val affectedFiles: List<String>? = null
)

/**
 * Type of assignment for feature/project coordination.
 */
enum class AssignmentType {
    /** Agent gets exclusive access to entire scope */
    EXCLUSIVE,
    
    /** Agent shares scope with others */
    COLLABORATIVE
}

/**
 * Represents the current lock status of a task for quick reference.
 */
enum class TaskLockStatus {
    /** Available for any operation */
    UNLOCKED,
    
    /** Exclusively locked */
    LOCKED_EXCLUSIVE,
    
    /** Shared read locks active */
    LOCKED_SHARED,
    
    /** Section-specific locks active */
    LOCKED_SECTION
}

/**
 * Result of a lock acquisition attempt.
 */
sealed class LockResult {
    /** Lock was successfully acquired */
    data class Success(val lock: TaskLock) : LockResult()
    
    /** Lock acquisition failed due to conflict */
    data class Conflict(
        val message: String,
        val existingLocks: List<TaskLock>,
        val suggestedAlternatives: List<UUID> = emptyList()
    ) : LockResult()
    
    /** Lock acquisition failed due to version mismatch */
    data class VersionMismatch(val currentVersion: Long, val expectedVersion: Long) : LockResult()
    
    /** Lock acquisition failed due to validation error */
    data class ValidationError(val message: String) : LockResult()
}