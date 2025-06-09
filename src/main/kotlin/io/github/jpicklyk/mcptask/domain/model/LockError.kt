package io.github.jpicklyk.mcptask.domain.model

import java.time.Instant
import java.util.*

/**
 * Comprehensive error types for the locking system.
 * Provides detailed context and user-friendly error messages for concurrency conflicts.
 */
sealed class LockError(
    open val message: String,
    open val code: String,
    open val timestamp: Instant = Instant.now(),
    open val context: LockErrorContext? = null
) {
    
    /**
     * Lock acquisition failed due to conflicting locks.
     */
    data class LockConflict(
        override val message: String,
        val conflictingLocks: List<TaskLock>,
        val requestedLock: LockRequest,
        val suggestions: List<ConflictResolution>,
        override val context: LockErrorContext? = null
    ) : LockError(message, "LOCK_CONFLICT", context = context)
    
    /**
     * Lock has expired and operation cannot proceed.
     */
    data class LockExpired(
        override val message: String,
        val expiredLock: TaskLock,
        val operationId: String,
        override val context: LockErrorContext? = null
    ) : LockError(message, "LOCK_EXPIRED", context = context)
    
    /**
     * Session is invalid or has expired.
     */
    data class InvalidSession(
        override val message: String,
        val sessionId: String,
        val reason: SessionInvalidReason,
        override val context: LockErrorContext? = null
    ) : LockError(message, "INVALID_SESSION", context = context)
    
    /**
     * Operation timed out while waiting for locks.
     */
    data class OperationTimeout(
        override val message: String,
        val timeoutDuration: Long,
        val waitingForLocks: List<UUID>,
        override val context: LockErrorContext? = null
    ) : LockError(message, "OPERATION_TIMEOUT", context = context)
    
    /**
     * Lock escalation failed due to system constraints.
     */
    data class EscalationFailed(
        override val message: String,
        val currentLock: TaskLock,
        val requestedLockType: LockType,
        val reason: EscalationFailureReason,
        override val context: LockErrorContext? = null
    ) : LockError(message, "ESCALATION_FAILED", context = context)
    
    /**
     * Lock hierarchy violation detected.
     */
    data class HierarchyViolation(
        override val message: String,
        val parentEntityId: UUID,
        val childEntityId: UUID,
        val violationType: HierarchyViolationType,
        override val context: LockErrorContext? = null
    ) : LockError(message, "HIERARCHY_VIOLATION", context = context)
    
    /**
     * System resource constraints prevent lock acquisition.
     */
    data class ResourceConstraint(
        override val message: String,
        val constraintType: ResourceConstraintType,
        val currentUsage: Int,
        val maxAllowed: Int,
        override val context: LockErrorContext? = null
    ) : LockError(message, "RESOURCE_CONSTRAINT", context = context)
}

/**
 * Context information for lock errors to provide better debugging and user experience.
 */
data class LockErrorContext(
    val operationName: String,
    val entityType: EntityType,
    val entityId: UUID,
    val sessionId: String,
    val requestTimestamp: Instant,
    val userAgent: String? = null,
    val additionalMetadata: Map<String, String> = emptyMap()
)

/**
 * Represents a lock request that failed.
 */
data class LockRequest(
    val entityId: UUID,
    val scope: LockScope,
    val lockType: LockType,
    val sessionId: String,
    val operationName: String,
    val expectedDuration: Long,
    val priority: Priority = Priority.MEDIUM
)

/**
 * Suggested resolution strategies for lock conflicts.
 */
sealed class ConflictResolution(
    open val strategy: String,
    open val description: String,
    open val estimatedWaitTime: Long? = null
) {
    
    /**
     * Wait for the conflicting lock to be released.
     */
    data class WaitForRelease(
        val lockId: UUID,
        val lockHolder: String,
        override val estimatedWaitTime: Long?
    ) : ConflictResolution(
        "WAIT_FOR_RELEASE",
        "Wait for the current operation to complete",
        estimatedWaitTime
    )
    
    /**
     * Request lock escalation to a compatible type.
     */
    data class RequestEscalation(
        val fromType: LockType,
        val toType: LockType,
        val justification: String
    ) : ConflictResolution(
        "REQUEST_ESCALATION",
        "Upgrade lock to $toType for compatibility"
    )
    
    /**
     * Suggest working on alternative entities.
     */
    data class AlternativeEntities(
        val alternatives: List<AlternativeEntity>
    ) : ConflictResolution(
        "ALTERNATIVE_ENTITIES",
        "Consider working on related entities instead"
    )
    
    /**
     * Break down operation into smaller, compatible operations.
     */
    data class SplitOperation(
        val suggestedOperations: List<String>,
        val compatibleLockTypes: List<LockType>
    ) : ConflictResolution(
        "SPLIT_OPERATION",
        "Break down into smaller operations"
    )
    
    /**
     * Retry the operation after a delay.
     */
    data class RetryAfterDelay(
        val delaySeconds: Long,
        val reason: String
    ) : ConflictResolution(
        "RETRY_AFTER_DELAY", 
        "Retry operation after $delaySeconds seconds: $reason"
    )
}

/**
 * Alternative entity suggestion for conflict resolution.
 */
data class AlternativeEntity(
    val entityId: UUID,
    val entityType: EntityType,
    val title: String,
    val reason: String,
    val similarity: Double // 0.0 to 1.0
)

/**
 * Reasons why a session might be invalid.
 */
enum class SessionInvalidReason {
    EXPIRED,
    NOT_FOUND,
    REVOKED,
    MALFORMED,
    INACTIVE
}

/**
 * Reasons why lock escalation might fail.
 */
enum class EscalationFailureReason {
    INCOMPATIBLE_LOCK_TYPE,
    INSUFFICIENT_PERMISSIONS,
    SYSTEM_CONSTRAINT,
    CONCURRENT_MODIFICATION,
    ESCALATION_LIMIT_REACHED
}

/**
 * Types of hierarchy violations.
 */
enum class HierarchyViolationType {
    PARENT_LOCK_REQUIRED,
    CHILD_LOCK_CONFLICT,
    CIRCULAR_DEPENDENCY,
    CROSS_HIERARCHY_CONFLICT
}

/**
 * Types of resource constraints.
 */
enum class ResourceConstraintType {
    MAX_CONCURRENT_LOCKS,
    MAX_SESSION_LOCKS,
    MAX_ENTITY_LOCKS,
    MEMORY_LIMIT,
    CONNECTION_LIMIT
}