package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.infrastructure.util.ResponseUtil
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Service for handling lock-related errors and providing user-friendly error messages
 * with actionable suggestions for conflict resolution.
 */
interface LockErrorHandler {
    /**
     * Converts a LockError into a user-friendly JSON response.
     */
    fun handleLockError(error: LockError): JsonElement
    
    /**
     * Suggests alternative actions when a lock conflict occurs.
     */
    fun suggestAlternatives(
        conflictingLocks: List<TaskLock>,
        requestedLock: LockRequest,
        context: LockErrorContext?
    ): List<ConflictResolution>
    
    /**
     * Formats error messages for different audiences (end users vs developers).
     */
    fun formatErrorMessage(error: LockError, audienceType: AudienceType): String
}

/**
 * Target audience for error messages.
 */
enum class AudienceType {
    END_USER,       // Claude and other AI agents
    DEVELOPER,      // Human developers debugging
    SYSTEM_LOG      // System logging and monitoring
}

/**
 * Default implementation of LockErrorHandler.
 */
class DefaultLockErrorHandler : LockErrorHandler {
    
    private val logger = LoggerFactory.getLogger(DefaultLockErrorHandler::class.java)
    
    override fun handleLockError(error: LockError): JsonElement {
        logger.debug("Handling lock error: ${error.code} - ${error.message}")
        
        return when (error) {
            is LockError.LockConflict -> handleLockConflict(error)
            is LockError.LockExpired -> handleLockExpired(error)
            is LockError.InvalidSession -> handleInvalidSession(error)
            is LockError.OperationTimeout -> handleOperationTimeout(error)
            is LockError.EscalationFailed -> handleEscalationFailed(error)
            is LockError.HierarchyViolation -> handleHierarchyViolation(error)
            is LockError.ResourceConstraint -> handleResourceConstraint(error)
        }
    }
    
    private fun handleLockConflict(error: LockError.LockConflict): JsonElement {
        val conflictDetails = buildJsonObject {
            put("conflictingLocks", buildJsonArray {
                error.conflictingLocks.forEach { lock ->
                    add(buildJsonObject {
                        put("lockId", lock.id.toString())
                        put("entityId", lock.entityId.toString())
                        put("scope", lock.scope.name)
                        put("lockType", lock.lockType.name)
                        put("sessionId", lock.sessionId)
                        put("lockedAt", lock.lockedAt.toString())
                        put("expiresAt", lock.expiresAt.toString())
                        put("remainingTime", Duration.between(lock.lockedAt, lock.expiresAt).toMinutes())
                    })
                }
            })
            
            put("requestedOperation", buildJsonObject {
                put("entityId", error.requestedLock.entityId.toString())
                put("scope", error.requestedLock.scope.name)
                put("lockType", error.requestedLock.lockType.name)
                put("operationName", error.requestedLock.operationName)
                put("sessionId", error.requestedLock.sessionId)
            })
            
            put("suggestions", buildJsonArray {
                error.suggestions.forEach { suggestion ->
                    add(formatConflictResolution(suggestion))
                }
            })
            
            if (error.context != null) {
                put("context", formatErrorContext(error.context))
            }
        }
        
        return ResponseUtil.createErrorResponse(
            message = formatErrorMessage(error, AudienceType.END_USER),
            code = error.code,
            details = "Lock conflict with ${error.conflictingLocks.size} conflicting locks",
            additionalData = conflictDetails
        )
    }
    
    private fun handleLockExpired(error: LockError.LockExpired): JsonElement {
        val details = buildJsonObject {
            put("expiredLock", buildJsonObject {
                put("lockId", error.expiredLock.id.toString())
                put("entityId", error.expiredLock.entityId.toString())
                put("expirationTime", error.expiredLock.expiresAt.toString())
                put("timeSinceExpiration", Duration.between(error.expiredLock.expiresAt, error.timestamp).toMinutes())
            })
            put("operationId", error.operationId)
            put("suggestion", "Restart the operation to acquire a new lock")
        }
        
        return ResponseUtil.createErrorResponse(
            message = formatErrorMessage(error, AudienceType.END_USER),
            code = error.code,
            details = "Lock expired for operation ${error.operationId}",
            additionalData = details
        )
    }
    
    private fun handleInvalidSession(error: LockError.InvalidSession): JsonElement {
        val details = buildJsonObject {
            put("sessionId", error.sessionId)
            put("reason", error.reason.name)
            put("suggestion", when (error.reason) {
                SessionInvalidReason.EXPIRED -> "Start a new session to continue working"
                SessionInvalidReason.NOT_FOUND -> "Session not found - you may need to reconnect"
                SessionInvalidReason.REVOKED -> "Session was revoked - please contact administrator"
                SessionInvalidReason.MALFORMED -> "Invalid session format - please reconnect"
                SessionInvalidReason.INACTIVE -> "Session is inactive - resume activity to reactivate"
            })
        }
        
        return ResponseUtil.createErrorResponse(
            message = formatErrorMessage(error, AudienceType.END_USER),
            code = error.code,
            details = "Session ${error.sessionId} is ${error.reason.name.lowercase()}",
            additionalData = details
        )
    }
    
    private fun handleOperationTimeout(error: LockError.OperationTimeout): JsonElement {
        val details = buildJsonObject {
            put("timeoutDuration", error.timeoutDuration)
            put("waitingForLocks", buildJsonArray {
                error.waitingForLocks.forEach { add(it.toString()) }
            })
            put("suggestions", buildJsonArray {
                add("Retry the operation")
                add("Consider working on a different task")
                add("Wait for concurrent operations to complete")
            })
        }
        
        return ResponseUtil.createErrorResponse(
            message = formatErrorMessage(error, AudienceType.END_USER),
            code = error.code,
            details = "Operation timed out after ${error.timeoutDuration}ms",
            additionalData = details
        )
    }
    
    private fun handleEscalationFailed(error: LockError.EscalationFailed): JsonElement {
        val details = buildJsonObject {
            put("currentLock", buildJsonObject {
                put("lockType", error.currentLock.lockType.name)
                put("entityId", error.currentLock.entityId.toString())
            })
            put("requestedLockType", error.requestedLockType.name)
            put("failureReason", error.reason.name)
            put("suggestion", when (error.reason) {
                EscalationFailureReason.INCOMPATIBLE_LOCK_TYPE -> "Consider using a different operation approach"
                EscalationFailureReason.INSUFFICIENT_PERMISSIONS -> "You may need additional permissions for this operation"
                EscalationFailureReason.SYSTEM_CONSTRAINT -> "System is at capacity - try again later"
                EscalationFailureReason.CONCURRENT_MODIFICATION -> "Another user is modifying this entity"
                EscalationFailureReason.ESCALATION_LIMIT_REACHED -> "Maximum lock escalations reached for this session"
            })
        }
        
        return ResponseUtil.createErrorResponse(
            message = formatErrorMessage(error, AudienceType.END_USER),
            code = error.code,
            details = "Lock escalation failed: ${error.reason.name.lowercase().replace('_', ' ')}",
            additionalData = details
        )
    }
    
    private fun handleHierarchyViolation(error: LockError.HierarchyViolation): JsonElement {
        val details = buildJsonObject {
            put("parentEntityId", error.parentEntityId.toString())
            put("childEntityId", error.childEntityId.toString())
            put("violationType", error.violationType.name)
            put("suggestion", when (error.violationType) {
                HierarchyViolationType.PARENT_LOCK_REQUIRED -> "Acquire lock on parent entity first"
                HierarchyViolationType.CHILD_LOCK_CONFLICT -> "Release child entity locks before locking parent"
                HierarchyViolationType.CIRCULAR_DEPENDENCY -> "Break the circular dependency by releasing one of the locks"
                HierarchyViolationType.CROSS_HIERARCHY_CONFLICT -> "Work within a single hierarchy branch"
            })
        }
        
        return ResponseUtil.createErrorResponse(
            message = formatErrorMessage(error, AudienceType.END_USER),
            code = error.code,
            details = "Lock hierarchy violation: ${error.violationType.name.lowercase().replace('_', ' ')}",
            additionalData = details
        )
    }
    
    private fun handleResourceConstraint(error: LockError.ResourceConstraint): JsonElement {
        val details = buildJsonObject {
            put("constraintType", error.constraintType.name)
            put("currentUsage", error.currentUsage)
            put("maxAllowed", error.maxAllowed)
            put("utilizationPercentage", (error.currentUsage.toDouble() / error.maxAllowed * 100).toInt())
            put("suggestion", when (error.constraintType) {
                ResourceConstraintType.MAX_CONCURRENT_LOCKS -> "Release some locks before acquiring new ones"
                ResourceConstraintType.MAX_SESSION_LOCKS -> "Complete current operations before starting new ones"
                ResourceConstraintType.MAX_ENTITY_LOCKS -> "This entity has reached its lock limit"
                ResourceConstraintType.MEMORY_LIMIT -> "System memory constraints - try again later"
                ResourceConstraintType.CONNECTION_LIMIT -> "Too many active connections - try again later"
            })
        }
        
        return ResponseUtil.createErrorResponse(
            message = formatErrorMessage(error, AudienceType.END_USER),
            code = error.code,
            details = "Resource constraint: ${error.constraintType.name.lowercase().replace('_', ' ')} (${error.currentUsage}/${error.maxAllowed})",
            additionalData = details
        )
    }
    
    override fun suggestAlternatives(
        conflictingLocks: List<TaskLock>,
        requestedLock: LockRequest,
        context: LockErrorContext?
    ): List<ConflictResolution> {
        val suggestions = mutableListOf<ConflictResolution>()
        
        // Analyze conflicting locks to suggest appropriate resolutions
        conflictingLocks.forEach { lock ->
            val remainingTime = Duration.between(Instant.now(), lock.expiresAt).toMinutes()
            
            if (remainingTime <= 10) {
                suggestions.add(
                    ConflictResolution.WaitForRelease(
                        lockId = lock.id,
                        lockHolder = lock.sessionId,
                        estimatedWaitTime = remainingTime
                    )
                )
            }
            
            // Suggest lock escalation if compatible
            if (isEscalationPossible(lock.lockType, requestedLock.lockType)) {
                suggestions.add(
                    ConflictResolution.RequestEscalation(
                        fromType = lock.lockType,
                        toType = requestedLock.lockType,
                        justification = "Operation compatibility"
                    )
                )
            }
        }
        
        // Suggest splitting operations for complex operations
        if (requestedLock.lockType == LockType.EXCLUSIVE && requestedLock.operationName.contains("update")) {
            suggestions.add(
                ConflictResolution.SplitOperation(
                    suggestedOperations = listOf("Read current state", "Prepare changes", "Apply changes"),
                    compatibleLockTypes = listOf(LockType.SHARED_READ, LockType.SHARED_WRITE, LockType.EXCLUSIVE)
                )
            )
        }
        
        // Default retry suggestion
        if (suggestions.isEmpty()) {
            suggestions.add(
                ConflictResolution.RetryAfterDelay(
                    delaySeconds = 30,
                    reason = "Allow concurrent operations to complete"
                )
            )
        }
        
        return suggestions
    }
    
    override fun formatErrorMessage(error: LockError, audienceType: AudienceType): String {
        return when (audienceType) {
            AudienceType.END_USER -> formatForEndUser(error)
            AudienceType.DEVELOPER -> formatForDeveloper(error)
            AudienceType.SYSTEM_LOG -> formatForSystemLog(error)
        }
    }
    
    private fun formatForEndUser(error: LockError): String {
        return when (error) {
            is LockError.LockConflict -> "Cannot proceed: Another operation is currently modifying this resource. ${formatConflictSuggestion(error.suggestions.firstOrNull())}"
            is LockError.LockExpired -> "Your session has expired. Please restart the operation to continue."
            is LockError.InvalidSession -> "Your session is no longer valid. Please reconnect to continue working."
            is LockError.OperationTimeout -> "Operation timed out while waiting for resource access. Please try again."
            is LockError.EscalationFailed -> "Cannot upgrade operation permissions. ${error.reason.name.lowercase().replace('_', ' ')}"
            is LockError.HierarchyViolation -> "Cannot lock this resource due to dependency constraints. Please work on related items in the correct order."
            is LockError.ResourceConstraint -> "System is at capacity (${error.currentUsage}/${error.maxAllowed}). Please try again later or complete existing operations first."
        }
    }
    
    private fun formatForDeveloper(error: LockError): String {
        return "${error.code}: ${error.message} [${error.timestamp}]"
    }
    
    private fun formatForSystemLog(error: LockError): String {
        return "LockError[${error.code}] ${error.message} at ${DateTimeFormatter.ISO_INSTANT.format(error.timestamp)}"
    }
    
    private fun formatConflictSuggestion(suggestion: ConflictResolution?): String {
        return when (suggestion) {
            is ConflictResolution.WaitForRelease -> "Estimated wait time: ${suggestion.estimatedWaitTime} minutes."
            is ConflictResolution.RetryAfterDelay -> "Try again in ${suggestion.delaySeconds} seconds."
            is ConflictResolution.AlternativeEntities -> "Consider working on related items instead."
            else -> "Please try again later."
        }
    }
    
    private fun formatConflictResolution(resolution: ConflictResolution): JsonObject {
        return buildJsonObject {
            put("strategy", resolution.strategy)
            put("description", resolution.description)
            
            when (resolution) {
                is ConflictResolution.WaitForRelease -> {
                    put("lockId", resolution.lockId.toString())
                    put("lockHolder", resolution.lockHolder)
                    resolution.estimatedWaitTime?.let { put("estimatedWaitTime", it) }
                }
                is ConflictResolution.RequestEscalation -> {
                    put("fromType", resolution.fromType.name)
                    put("toType", resolution.toType.name)
                    put("justification", resolution.justification)
                }
                is ConflictResolution.AlternativeEntities -> {
                    put("alternatives", buildJsonArray {
                        resolution.alternatives.forEach { alt ->
                            add(buildJsonObject {
                                put("entityId", alt.entityId.toString())
                                put("entityType", alt.entityType.name)
                                put("title", alt.title)
                                put("reason", alt.reason)
                                put("similarity", alt.similarity)
                            })
                        }
                    })
                }
                is ConflictResolution.SplitOperation -> {
                    put("suggestedOperations", buildJsonArray {
                        resolution.suggestedOperations.forEach { add(it) }
                    })
                    put("compatibleLockTypes", buildJsonArray {
                        resolution.compatibleLockTypes.forEach { add(it.name) }
                    })
                }
                is ConflictResolution.RetryAfterDelay -> {
                    put("delaySeconds", resolution.delaySeconds)
                    put("reason", resolution.reason)
                }
            }
        }
    }
    
    private fun formatErrorContext(context: LockErrorContext): JsonObject {
        return buildJsonObject {
            put("operationName", context.operationName)
            put("entityType", context.entityType.name)
            put("entityId", context.entityId.toString())
            put("sessionId", context.sessionId)
            put("requestTimestamp", context.requestTimestamp.toString())
            context.userAgent?.let { put("userAgent", it) }
            if (context.additionalMetadata.isNotEmpty()) {
                put("additionalMetadata", buildJsonObject {
                    context.additionalMetadata.forEach { (key, value) ->
                        put(key, value)
                    }
                })
            }
        }
    }
    
    private fun isEscalationPossible(currentType: LockType, requestedType: LockType): Boolean {
        // Define escalation compatibility matrix
        return when (currentType) {
            LockType.SHARED_READ -> requestedType in listOf(LockType.SHARED_WRITE, LockType.EXCLUSIVE)
            LockType.SHARED_WRITE -> requestedType == LockType.EXCLUSIVE
            LockType.SECTION_WRITE -> requestedType in listOf(LockType.SHARED_WRITE, LockType.EXCLUSIVE)
            LockType.EXCLUSIVE -> false // Cannot escalate from exclusive
            LockType.NONE -> true // Can escalate to any type
        }
    }
}