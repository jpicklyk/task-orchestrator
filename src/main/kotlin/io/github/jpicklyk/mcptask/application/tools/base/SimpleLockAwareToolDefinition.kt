package io.github.jpicklyk.mcptask.application.tools.base

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.service.LockErrorHandler
import io.github.jpicklyk.mcptask.application.service.DefaultLockErrorHandler
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.LockException
import io.github.jpicklyk.mcptask.domain.model.LockError
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.infrastructure.util.ResponseUtil
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Simple enhanced BaseToolDefinition that maintains backwards compatibility
 * while allowing for future locking integration.
 */
abstract class SimpleLockAwareToolDefinition(
    private val lockingService: SimpleLockingService? = null,
    private val sessionManager: SimpleSessionManager? = null,
    private val errorHandler: LockErrorHandler = DefaultLockErrorHandler()
) : BaseToolDefinition() {
    
    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        return try {
            executeInternal(params, context)
        } catch (e: LockException) {
            // Handle lock-specific exceptions with enhanced error messages
            logger.info("Lock error in tool ${name}: ${e.lockError.code} - ${e.message}")
            handleLockError(e.lockError)
        } catch (e: ToolValidationException) {
            ResponseUtil.createErrorResponse(
                e.message ?: "Validation failed",
                "VALIDATION_ERROR"
            )
        } catch (e: Exception) {
            logger.error("Error in tool execution for ${name}", e)
            ResponseUtil.createErrorResponse(
                "Tool execution failed: ${e.message}",
                "INTERNAL_ERROR"
            )
        }
    }
    
    /**
     * Internal execution method that must be implemented by all tools.
     * This maintains backwards compatibility with existing tools.
     */
    protected abstract suspend fun executeInternal(
        params: JsonElement, 
        context: ToolExecutionContext
    ): JsonElement
    
    /**
     * Determines whether this tool should use the locking system in the future.
     * Default is false for backwards compatibility.
     */
    protected open fun shouldUseLocking(): Boolean = false
    
    // Utility methods for entity extraction
    
    protected fun extractUuidFromParameters(parameters: JsonElement, paramName: String): UUID? {
        return try {
            val paramsObj = parameters as? JsonObject ?: return null
            parameters[paramName]?.let { element ->
                if (element is JsonPrimitive && element.isString) {
                    UUID.fromString(element.content)
                } else null
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract UUID from parameter ${paramName}", e)
            null
        }
    }
    
    protected fun extractStringFromParameters(parameters: JsonElement, paramName: String): String? {
        return try {
            val paramsObj = parameters as? JsonObject ?: return null
            parameters[paramName]?.let { element ->
                if (element is JsonPrimitive && element.isString) {
                    element.content
                } else null
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract string from parameter ${paramName}", e)
            null
        }
    }
    
    /**
     * Helper method to convert repository Result to JsonElement.
     */
    protected fun <T> handleRepositoryResult(
        result: io.github.jpicklyk.mcptask.domain.repository.Result<T>,
        successMessage: String? = null,
        dataTransform: (T) -> JsonElement
    ): JsonElement {
        return when (result) {
            is io.github.jpicklyk.mcptask.domain.repository.Result.Success -> {
                ResponseUtil.createSuccessResponse(
                    successMessage,
                    dataTransform(result.data)
                )
            }
            is io.github.jpicklyk.mcptask.domain.repository.Result.Error -> {
                when (result.error) {
                    is RepositoryError.NotFound -> ResponseUtil.createErrorResponse(
                        result.error.message,
                        "RESOURCE_NOT_FOUND"
                    )
                    is RepositoryError.ValidationError -> ResponseUtil.createErrorResponse(
                        result.error.message,
                        "VALIDATION_ERROR"
                    )
                    is RepositoryError.ConflictError -> ResponseUtil.createErrorResponse(
                        result.error.message,
                        "CONFLICT_ERROR"
                    )
                    is RepositoryError.DatabaseError -> ResponseUtil.createErrorResponse(
                        result.error.message,
                        "DATABASE_ERROR"
                    )
                    is RepositoryError.UnknownError -> ResponseUtil.createErrorResponse(
                        result.error.message,
                        "INTERNAL_ERROR"
                    )
                }
            }
        }
    }
    
    /**
     * Handles lock-specific errors with user-friendly messaging and suggestions.
     */
    protected fun handleLockError(lockError: LockError): JsonElement {
        logger.debug("Handling lock error in tool ${name}: ${lockError.code}")
        return errorHandler.handleLockError(lockError)
    }
    
    /**
     * Creates a lock error context for better error reporting.
     */
    protected fun createLockErrorContext(
        operationName: String,
        entityType: io.github.jpicklyk.mcptask.domain.model.EntityType,
        entityId: UUID,
        sessionId: String,
        additionalMetadata: Map<String, String> = emptyMap()
    ): io.github.jpicklyk.mcptask.domain.model.LockErrorContext {
        return io.github.jpicklyk.mcptask.domain.model.LockErrorContext(
            operationName = operationName,
            entityType = entityType,
            entityId = entityId,
            sessionId = sessionId,
            requestTimestamp = java.time.Instant.now(),
            userAgent = "mcp-task-orchestrator",
            additionalMetadata = additionalMetadata
        )
    }
    
    /**
     * Executes an operation with proper locking lifecycle management.
     * This ensures operations are recorded, conflicts are detected, and cleanup happens.
     */
    protected suspend fun executeWithLocking(
        operationName: String,
        entityType: io.github.jpicklyk.mcptask.domain.model.EntityType,
        entityId: UUID,
        operation: suspend () -> JsonElement
    ): JsonElement {
        // If locking is disabled, execute directly
        if (!shouldUseLocking() || lockingService == null || sessionManager == null) {
            return operation()
        }
        
        val lockOperation = io.github.jpicklyk.mcptask.application.service.LockOperation(
            operationType = mapOperationToLockType(operationName),
            toolName = name,
            description = "Tool operation: $operationName on ${entityType.name}",
            entityIds = setOf(entityId)
        )
        
        // Atomically attempt to acquire the lock
        val lockResult = lockingService.tryAcquireLock(lockOperation)
        
        when (lockResult) {
            is io.github.jpicklyk.mcptask.application.service.LockAcquisitionResult.Conflict -> {
                val context = createLockErrorContext(operationName, entityType, entityId, sessionManager.getCurrentSession())
                val conflictError = LockError.LockConflict(
                    message = "Operation cannot proceed due to conflicting locks",
                    conflictingLocks = emptyList(),
                    requestedLock = io.github.jpicklyk.mcptask.domain.model.LockRequest(
                        entityId = entityId,
                        scope = io.github.jpicklyk.mcptask.domain.model.LockScope.TASK,
                        lockType = io.github.jpicklyk.mcptask.domain.model.LockType.SHARED_WRITE,
                        sessionId = sessionManager.getCurrentSession(),
                        operationName = operationName,
                        expectedDuration = 120L // 2 minutes
                    ),
                    suggestions = errorHandler.suggestAlternatives(emptyList(), 
                        io.github.jpicklyk.mcptask.domain.model.LockRequest(
                            entityId = entityId,
                            scope = io.github.jpicklyk.mcptask.domain.model.LockScope.TASK,
                            lockType = io.github.jpicklyk.mcptask.domain.model.LockType.SHARED_WRITE,
                            sessionId = sessionManager.getCurrentSession(),
                            operationName = operationName,
                            expectedDuration = 120L
                        ), context),
                    context = context
                )
                return handleLockError(conflictError)
            }
            is io.github.jpicklyk.mcptask.application.service.LockAcquisitionResult.Success -> {
                // Lock acquired successfully, continue with operation
                val operationId = lockResult.operationId
                
                try {
                    // Execute the actual operation
                    return operation()
                } finally {
                    // Always clean up the lock, even if operation fails
                    lockingService.recordOperationComplete(operationId)
                }
            }
        }
    }
    
    /**
     * Helper method to extract entity ID from different parameter structures.
     */
    protected fun extractEntityId(params: JsonElement, paramName: String = "id"): UUID {
        return extractUuidFromParameters(params, paramName)
            ?: throw ToolValidationException("Missing or invalid $paramName parameter")
    }
    
    private fun mapOperationToLockType(operationName: String): io.github.jpicklyk.mcptask.application.service.OperationType {
        return when {
            operationName.contains("read", ignoreCase = true) -> io.github.jpicklyk.mcptask.application.service.OperationType.READ
            operationName.contains("update", ignoreCase = true) -> io.github.jpicklyk.mcptask.application.service.OperationType.WRITE
            operationName.contains("delete", ignoreCase = true) -> io.github.jpicklyk.mcptask.application.service.OperationType.DELETE
            operationName.contains("create", ignoreCase = true) -> io.github.jpicklyk.mcptask.application.service.OperationType.CREATE
            operationName.contains("section", ignoreCase = true) -> io.github.jpicklyk.mcptask.application.service.OperationType.SECTION_EDIT
            else -> io.github.jpicklyk.mcptask.application.service.OperationType.WRITE
        }
    }
}