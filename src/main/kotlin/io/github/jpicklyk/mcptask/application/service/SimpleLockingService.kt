package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    
    /**
     * Atomically attempts to acquire a lock for the given operation.
     * This combines conflict checking and operation recording in a single atomic operation
     * to prevent TOCTOU race conditions.
     */
    suspend fun tryAcquireLock(operation: LockOperation): LockAcquisitionResult
}

/**
 * Result of attempting to acquire a lock atomically.
 */
sealed class LockAcquisitionResult {
    /**
     * Lock was successfully acquired.
     */
    data class Success(val operationId: String) : LockAcquisitionResult()
    
    /**
     * Lock acquisition failed due to conflicts.
     */
    data class Conflict(val conflictingOperations: List<LockOperation>) : LockAcquisitionResult()
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
class DefaultSimpleLockingService(
    /** Operation timeout in minutes - operations older than this are considered expired */
    private val operationTimeoutMinutes: Long = 2
) : SimpleLockingService {
    
    private val logger = LoggerFactory.getLogger(DefaultSimpleLockingService::class.java)
    
    // Track active operations in memory
    private val activeOperations = ConcurrentHashMap<String, LockOperation>()
    private val operationStartTimes = ConcurrentHashMap<String, Instant>()
    
    // Mutex to ensure atomic lock acquisition and prevent TOCTOU race conditions
    private val lockMutex = Mutex()
    
    override suspend fun canProceed(operation: LockOperation): Boolean {
        logger.debug("Checking if operation '${operation.description}' can proceed")
        
        // Clean up expired operations first (lazy cleanup)
        cleanupExpiredOperations()
        
        // Conflict detection logic:
        // 1. DELETE operations are blocked by any other operation on the same entity
        // 2. Other operations are blocked by DELETE operations on the same entity  
        // 3. WRITE operations are blocked by other WRITE operations on the same entity
        val hasConflicts = activeOperations.values.any { activeOp ->
            val entityOverlap = activeOp.entityIds.intersect(operation.entityIds).isNotEmpty()
            
            when {
                // DELETE operations are blocked by any operation on same entities
                operation.operationType == OperationType.DELETE && entityOverlap -> true
                // Other operations are blocked by DELETE operations on same entities  
                activeOp.operationType == OperationType.DELETE && entityOverlap -> true
                // WRITE operations are blocked by other WRITE operations on same entities
                operation.operationType == OperationType.WRITE && activeOp.operationType == OperationType.WRITE && entityOverlap -> true
                // CREATE operations are blocked by other CREATE operations on same entities (prevents duplicate creation)
                operation.operationType == OperationType.CREATE && activeOp.operationType == OperationType.CREATE && entityOverlap -> true
                // STRUCTURE_CHANGE operations are blocked by any non-READ operation on same entities
                operation.operationType == OperationType.STRUCTURE_CHANGE && activeOp.operationType != OperationType.READ && entityOverlap -> true
                activeOp.operationType == OperationType.STRUCTURE_CHANGE && operation.operationType != OperationType.READ && entityOverlap -> true
                // No conflicts for other operation combinations (READ operations never conflict)
                else -> false
            }
        }
        
        logger.debug("Operation '${operation.description}' ${if (hasConflicts) "blocked due to conflicts" else "can proceed"}")
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
    
    override suspend fun tryAcquireLock(operation: LockOperation): LockAcquisitionResult {
        return lockMutex.withLock {
            logger.debug("Attempting to acquire lock for operation '${operation.description}'")
            
            // Clean up expired operations first (lazy cleanup)
            cleanupExpiredOperations()
            
            // Check for conflicts using the same logic as canProceed
            val conflictingOperations = activeOperations.values.filter { activeOp ->
                val entityOverlap = activeOp.entityIds.intersect(operation.entityIds).isNotEmpty()
                
                when {
                    // DELETE operations are blocked by any operation on same entities
                    operation.operationType == OperationType.DELETE && entityOverlap -> true
                    // Other operations are blocked by DELETE operations on same entities  
                    activeOp.operationType == OperationType.DELETE && entityOverlap -> true
                    // WRITE operations are blocked by other WRITE operations on same entities
                    operation.operationType == OperationType.WRITE && activeOp.operationType == OperationType.WRITE && entityOverlap -> true
                    // CREATE operations are blocked by other CREATE operations on same entities (prevents duplicate creation)
                    operation.operationType == OperationType.CREATE && activeOp.operationType == OperationType.CREATE && entityOverlap -> true
                    // STRUCTURE_CHANGE operations are blocked by any non-READ operation on same entities
                    operation.operationType == OperationType.STRUCTURE_CHANGE && activeOp.operationType != OperationType.READ && entityOverlap -> true
                    activeOp.operationType == OperationType.STRUCTURE_CHANGE && operation.operationType != OperationType.READ && entityOverlap -> true
                    // No conflicts for other operation combinations (READ operations never conflict)
                    else -> false
                }
            }
            
            if (conflictingOperations.isNotEmpty()) {
                logger.debug("Operation '${operation.description}' blocked due to ${conflictingOperations.size} conflicting operation(s)")
                return@withLock LockAcquisitionResult.Conflict(conflictingOperations)
            }
            
            // No conflicts found - acquire the lock atomically
            val operationId = "op-${UUID.randomUUID()}"
            activeOperations[operationId] = operation
            operationStartTimes[operationId] = Instant.now()
            
            logger.debug("Successfully acquired lock for operation '${operation.description}' with ID '$operationId'")
            LockAcquisitionResult.Success(operationId)
        }
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
    
    /**
     * Cleans up operations that have exceeded the timeout duration.
     * This prevents crashed agents from creating permanent deadlocks.
     */
    private fun cleanupExpiredOperations(): Int {
        val now = Instant.now()
        val timeoutThreshold = now.minusSeconds(operationTimeoutMinutes * 60)
        
        val expiredOperations = operationStartTimes.filter { (_, startTime) ->
            startTime.isBefore(timeoutThreshold)
        }
        
        expiredOperations.keys.forEach { operationId ->
            activeOperations.remove(operationId)
            operationStartTimes.remove(operationId)
            logger.info("Cleaned up expired operation '$operationId' (timeout: ${operationTimeoutMinutes} minutes)")
        }
        
        return expiredOperations.size
    }
    
    /**
     * Manually triggers cleanup of expired operations and returns count of cleaned operations.
     * Useful for monitoring and diagnostics.
     */
    fun forceCleanupExpiredOperations(): Int = cleanupExpiredOperations()
}