package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Simplified locking service for Phase 2 implementation.
 * Provides basic lock management without full database integration.
 */
interface SimpleLockingService {
    /**
     * Checks if the current operation can proceed without conflicts.
     */
    suspend fun canProceed(operation: LockOperation): Boolean
    
    /**
     * Records the start of an operation for monitoring.
     */
    suspend fun recordOperationStart(operation: LockOperation): String
    
    /**
     * Records the completion of an operation.
     */
    suspend fun recordOperationComplete(operationId: String)
}

/**
 * Represents an operation being performed for basic lock checking.
 */
data class LockOperation(
    val operationType: OperationType,
    val toolName: String,
    val description: String,
    val expectedDurationMinutes: Int = 30,
    val priority: Priority = Priority.MEDIUM,
    val entityIds: Set<UUID> = emptySet(),
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Types of operations that can be performed.
 */
enum class OperationType {
    READ,           // Reading data
    WRITE,          // Writing data
    SECTION_EDIT,   // Editing sections
    DELETE,         // Deleting entities
    CREATE,         // Creating entities
    STRUCTURE_CHANGE // Changing entity structure
}

/**
 * Simple in-memory implementation of locking service.
 */
class DefaultSimpleLockingService : SimpleLockingService {
    
    private val logger = LoggerFactory.getLogger(DefaultSimpleLockingService::class.java)
    
    // Track active operations in memory
    private val activeOperations = ConcurrentHashMap<String, LockOperation>()
    private val operationStartTimes = ConcurrentHashMap<String, Instant>()
    
    override suspend fun canProceed(operation: LockOperation): Boolean {
        // For now, always allow operations to proceed
        // In Phase 3, this will check for actual conflicts
        logger.debug("Checking if operation '${operation.description}' can proceed")
        
        // Simple conflict detection: check if any exclusive operations are running on same entities
        val hasConflicts = activeOperations.values.any { activeOp ->
            activeOp.operationType == OperationType.DELETE && 
            activeOp.entityIds.intersect(operation.entityIds).isNotEmpty()
        }
        
        return !hasConflicts
    }
    
    override suspend fun recordOperationStart(operation: LockOperation): String {
        val operationId = "op-${UUID.randomUUID()}"
        activeOperations[operationId] = operation
        operationStartTimes[operationId] = Instant.now()
        
        logger.debug("Started operation '$operationId': ${operation.description}")
        return operationId
    }
    
    override suspend fun recordOperationComplete(operationId: String) {
        activeOperations.remove(operationId)
        operationStartTimes.remove(operationId)
        
        logger.debug("Completed operation '$operationId'")
    }
    
    /**
     * Gets statistics about active operations.
     */
    fun getActiveOperationCount(): Int = activeOperations.size
    
    /**
     * Gets the longest running operation.
     */
    fun getLongestRunningOperation(): Pair<String, Long>? {
        val now = Instant.now()
        return operationStartTimes.entries.maxByOrNull { entry ->
            java.time.Duration.between(entry.value, now).toMillis()
        }?.let { entry ->
            entry.key to java.time.Duration.between(entry.value, now).toMillis()
        }
    }
}