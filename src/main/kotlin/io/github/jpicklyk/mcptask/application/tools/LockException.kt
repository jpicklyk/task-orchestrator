package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.domain.model.LockError
import io.github.jpicklyk.mcptask.domain.model.TaskLock
import java.util.*

/**
 * Base exception for all lock-related errors in tool execution.
 * These exceptions are caught by SimpleLockAwareToolDefinition and converted
 * to user-friendly error responses.
 */
abstract class LockException(
    message: String,
    val lockError: LockError,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Thrown when a lock conflict prevents an operation from proceeding.
 */
class LockConflictException(
    val conflictingLocks: List<TaskLock>,
    val requestedOperation: String,
    lockError: LockError.LockConflict
) : LockException("Lock conflict: ${lockError.message}", lockError)

/**
 * Thrown when an operation's lock has expired.
 */
class LockExpiredException(
    val expiredLock: TaskLock,
    val operationId: String,
    lockError: LockError.LockExpired
) : LockException("Lock expired: ${lockError.message}", lockError)

/**
 * Thrown when the session is invalid or expired.
 */
class InvalidSessionException(
    val sessionId: String,
    lockError: LockError.InvalidSession
) : LockException("Invalid session: ${lockError.message}", lockError)

/**
 * Thrown when an operation times out waiting for locks.
 */
class OperationTimeoutException(
    val timeoutDuration: Long,
    val waitingForLocks: List<UUID>,
    lockError: LockError.OperationTimeout
) : LockException("Operation timeout: ${lockError.message}", lockError)

/**
 * Thrown when lock escalation fails.
 */
class EscalationFailedException(
    val currentLock: TaskLock,
    val requestedLockType: io.github.jpicklyk.mcptask.domain.model.LockType,
    lockError: LockError.EscalationFailed
) : LockException("Lock escalation failed: ${lockError.message}", lockError)

/**
 * Thrown when a lock hierarchy violation is detected.
 */
class HierarchyViolationException(
    val parentEntityId: UUID,
    val childEntityId: UUID,
    lockError: LockError.HierarchyViolation
) : LockException("Lock hierarchy violation: ${lockError.message}", lockError)

/**
 * Thrown when system resource constraints prevent lock acquisition.
 */
class ResourceConstraintException(
    val constraintType: io.github.jpicklyk.mcptask.domain.model.ResourceConstraintType,
    val currentUsage: Int,
    val maxAllowed: Int,
    lockError: LockError.ResourceConstraint
) : LockException("Resource constraint: ${lockError.message}", lockError)