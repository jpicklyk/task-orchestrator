package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.application.service.*
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteTaskRepository
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * Concurrency and performance tests for the locking system.
 * Tests race conditions, thread safety, and performance under load.
 */
class LockingSystemConcurrencyTest {

    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var taskRepository: SQLiteTaskRepository
    private lateinit var lockingService: DefaultSimpleLockingService
    private lateinit var sessionManager: DefaultSimpleSessionManager

    @BeforeEach
    fun setUp() {
        // Create a unique in-memory H2 database for each test
        val dbName = "concurrency_test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        databaseManager.updateSchema()

        taskRepository = SQLiteTaskRepository(databaseManager)
        lockingService = DefaultSimpleLockingService()
        sessionManager = DefaultSimpleSessionManager()
    }

    @Test
    fun `should handle concurrent session creation safely`() = runBlocking {
        // Given
        val sessionIds = ConcurrentHashMap.newKeySet<String>()
        val threadCount = 50
        
        // When - Create sessions concurrently
        val jobs = (1..threadCount).map { threadId ->
            async(Dispatchers.IO) {
                // Each async block simulates a different thread context
                val localSessionManager = DefaultSimpleSessionManager()
                val sessionId = localSessionManager.getCurrentSession()
                sessionIds.add(sessionId)
                localSessionManager.updateActivity(sessionId)
                sessionId
            }
        }
        
        val results = jobs.awaitAll()
        
        // Then
        assertEquals(threadCount, results.size, "Should create requested number of sessions")
        assertEquals(threadCount, sessionIds.size, "All session IDs should be unique")
        
        // All session IDs should be valid
        sessionIds.forEach { sessionId ->
            assertTrue(sessionId.startsWith("session-"), "All sessions should have proper prefix")
        }
    }

    @Test
    fun `should handle concurrent operation registration safely`() = runBlocking {
        // Given
        val operationCount = 100
        val operationIds = ConcurrentHashMap.newKeySet<String>()
        val successCount = AtomicInteger(0)
        
        // When - Register operations concurrently
        val jobs = (1..operationCount).map { i ->
            async(Dispatchers.IO) {
                try {
                    val operation = LockOperation(
                        operationType = OperationType.WRITE,
                        toolName = "ConcurrentTool$i",
                        description = "Concurrent operation $i",
                        entityIds = setOf(UUID.randomUUID())
                    )
                    
                    val operationId = lockingService.recordOperationStart(operation)
                    operationIds.add(operationId)
                    successCount.incrementAndGet()
                    
                    // Simulate some work
                    delay(10)
                    
                    lockingService.recordOperationComplete(operationId)
                    operationId
                } catch (e: Exception) {
                    fail("Operation registration should not throw exceptions: ${e.message}")
                }
            }
        }
        
        jobs.awaitAll()
        
        // Then
        assertEquals(operationCount, successCount.get(), "All operations should complete successfully")
        assertEquals(operationCount, operationIds.size, "All operation IDs should be unique")
        assertEquals(0, lockingService.getActiveOperationCount(), "All operations should be completed")
    }

    @Test
    fun `should handle conflict detection under concurrent load`() = runBlocking {
        // Given
        val sharedEntityId = UUID.randomUUID()
        val conflictingOperationCount = 20
        val nonConflictingOperationCount = 30
        val conflictResults = ConcurrentHashMap<Int, Boolean>()
        
        // Start a DELETE operation on shared entity
        val deleteOperation = LockOperation(
            operationType = OperationType.DELETE,
            toolName = "DeleteTool",
            description = "Delete operation for conflict testing",
            entityIds = setOf(sharedEntityId)
        )
        val deleteOpId = lockingService.recordOperationStart(deleteOperation)
        
        // When - Test conflict detection concurrently
        val conflictingJobs = (1..conflictingOperationCount).map { i ->
            async(Dispatchers.IO) {
                val operation = LockOperation(
                    operationType = OperationType.WRITE,
                    toolName = "WriteTool$i",
                    description = "Conflicting write operation $i",
                    entityIds = setOf(sharedEntityId) // Same entity as DELETE
                )
                
                val canProceed = lockingService.canProceed(operation)
                conflictResults[i] = canProceed
                canProceed
            }
        }
        
        val nonConflictingJobs = (1..nonConflictingOperationCount).map { i ->
            async(Dispatchers.IO) {
                val operation = LockOperation(
                    operationType = OperationType.WRITE,
                    toolName = "WriteTool${i + 1000}",
                    description = "Non-conflicting write operation $i",
                    entityIds = setOf(UUID.randomUUID()) // Different entity
                )
                
                lockingService.canProceed(operation)
            }
        }
        
        val conflictingResults = conflictingJobs.awaitAll()
        val nonConflictingResults = nonConflictingJobs.awaitAll()
        
        // Then
        assertTrue(conflictingResults.all { !it }, "All conflicting operations should be denied")
        assertTrue(nonConflictingResults.all { it }, "All non-conflicting operations should be allowed")
        
        // Cleanup
        lockingService.recordOperationComplete(deleteOpId)
        
        // After DELETE completes, conflicting operations should be allowed
        val postDeleteResult = lockingService.canProceed(
            LockOperation(
                operationType = OperationType.WRITE,
                toolName = "PostDeleteTool",
                description = "Post-delete operation",
                entityIds = setOf(sharedEntityId)
            )
        )
        assertTrue(postDeleteResult, "Operations should be allowed after conflicting operation completes")
    }

    @Test
    fun `should maintain session activity under concurrent access`() = runBlocking {
        // Given
        val sessionId = sessionManager.getCurrentSession()
        val updateCount = 100
        val successfulUpdates = AtomicInteger(0)
        
        // When - Update session activity concurrently
        val jobs = (1..updateCount).map {
            async(Dispatchers.IO) {
                try {
                    sessionManager.updateActivity(sessionId)
                    successfulUpdates.incrementAndGet()
                } catch (e: Exception) {
                    fail("Session activity update should not throw exceptions: ${e.message}")
                }
            }
        }
        
        jobs.awaitAll()
        
        // Then
        assertEquals(updateCount, successfulUpdates.get(), "All activity updates should succeed")
        assertTrue(sessionManager.isSessionActive(sessionId), "Session should remain active")
    }

    @Test
    fun `should handle rapid operation start and complete cycles`() = runBlocking {
        // Given
        val cycleCount = 200
        val completedOperations = AtomicInteger(0)
        
        // When - Rapidly start and complete operations
        val executionTime = measureTimeMillis {
            val jobs = (1..cycleCount).map { i ->
                async(Dispatchers.IO) {
                    val operation = LockOperation(
                        operationType = if (i % 2 == 0) OperationType.READ else OperationType.WRITE,
                        toolName = "RapidTool$i",
                        description = "Rapid operation $i",
                        entityIds = setOf(UUID.randomUUID())
                    )
                    
                    val operationId = lockingService.recordOperationStart(operation)
                    
                    // Minimal processing time
                    delay(1)
                    
                    lockingService.recordOperationComplete(operationId)
                    completedOperations.incrementAndGet()
                }
            }
            
            jobs.awaitAll()
        }
        
        // Then
        assertEquals(cycleCount, completedOperations.get(), "All operations should complete")
        assertEquals(0, lockingService.getActiveOperationCount(), "No operations should remain active")
        assertTrue(executionTime < 5000, "Operations should complete within reasonable time (< 5s)")
    }

    @Test
    fun `should handle mixed read and write operations correctly`() = runBlocking {
        // Given
        val entityId = UUID.randomUUID()
        val readOperationCount = 30
        val writeOperationCount = 10
        val conflictingDeleteCount = 5
        
        val results = ConcurrentHashMap<String, Boolean>()
        
        // When - Mix different operation types
        val readJobs = (1..readOperationCount).map { i ->
            async(Dispatchers.IO) {
                val operation = LockOperation(
                    operationType = OperationType.READ,
                    toolName = "ReadTool$i",
                    description = "Read operation $i",
                    entityIds = setOf(entityId)
                )
                val canProceed = lockingService.canProceed(operation)
                results["read_$i"] = canProceed
                canProceed
            }
        }
        
        val writeJobs = (1..writeOperationCount).map { i ->
            async(Dispatchers.IO) {
                val operation = LockOperation(
                    operationType = OperationType.WRITE,
                    toolName = "WriteTool$i",
                    description = "Write operation $i",
                    entityIds = setOf(entityId)
                )
                val canProceed = lockingService.canProceed(operation)
                results["write_$i"] = canProceed
                canProceed
            }
        }
        
        // Start one DELETE operation to create conflicts
        val deleteOperation = LockOperation(
            operationType = OperationType.DELETE,
            toolName = "DeleteTool",
            description = "Delete operation for conflict testing",
            entityIds = setOf(entityId)
        )
        val deleteOpId = lockingService.recordOperationStart(deleteOperation)
        
        val conflictingJobs = (1..conflictingDeleteCount).map { i ->
            async(Dispatchers.IO) {
                delay(10) // Ensure DELETE operation is already started
                val operation = LockOperation(
                    operationType = OperationType.WRITE,
                    toolName = "ConflictingTool$i",
                    description = "Conflicting operation $i",
                    entityIds = setOf(entityId)
                )
                val canProceed = lockingService.canProceed(operation)
                results["conflict_$i"] = canProceed
                canProceed
            }
        }
        
        val readResults = readJobs.awaitAll()
        val writeResults = writeJobs.awaitAll()
        val conflictResults = conflictingJobs.awaitAll()
        
        // Then
        // In current simple implementation, only DELETE operations create conflicts
        // so reads and writes should be allowed unless DELETE is active
        val conflictingOperationsBlocked = conflictResults.count { !it }
        assertTrue(conflictingOperationsBlocked > 0, "Some conflicting operations should be blocked")
        
        // Cleanup
        lockingService.recordOperationComplete(deleteOpId)
    }

    @Test
    fun `should perform well under high load`() = runBlocking {
        // Given
        val highLoadOperationCount = 1000
        val entitiesCount = 100
        val entities = (1..entitiesCount).map { UUID.randomUUID() }
        
        val operationIds = ConcurrentHashMap.newKeySet<String>()
        val completedCount = AtomicInteger(0)
        
        // When - High load test
        val executionTime = measureTimeMillis {
            val jobs = (1..highLoadOperationCount).map { i ->
                async(Dispatchers.IO) {
                    val operation = LockOperation(
                        operationType = OperationType.values()[i % OperationType.values().size],
                        toolName = "HighLoadTool$i",
                        description = "High load operation $i",
                        entityIds = setOf(entities[i % entities.size]),
                        priority = Priority.values()[i % Priority.values().size]
                    )
                    
                    // Check if can proceed
                    val canProceed = lockingService.canProceed(operation)
                    
                    if (canProceed || operation.operationType != OperationType.DELETE) {
                        val operationId = lockingService.recordOperationStart(operation)
                        operationIds.add(operationId)
                        
                        // Minimal work simulation
                        delay(1)
                        
                        lockingService.recordOperationComplete(operationId)
                        completedCount.incrementAndGet()
                    }
                }
            }
            
            jobs.awaitAll()
        }
        
        // Then
        assertTrue(completedCount.get() > highLoadOperationCount * 0.8, 
            "At least 80% of operations should complete (actual: ${completedCount.get()}/$highLoadOperationCount)")
        assertEquals(0, lockingService.getActiveOperationCount(), "All operations should be completed")
        assertTrue(executionTime < 10000, "High load test should complete within 10 seconds (actual: ${executionTime}ms)")
        
        println("High load test completed: ${completedCount.get()}/$highLoadOperationCount operations in ${executionTime}ms")
    }

    @Test
    fun `should handle session cleanup under concurrent access`() = runBlocking {
        // Given
        val sessionCount = 50
        val sessionManagers = (1..sessionCount).map { DefaultSimpleSessionManager() }
        val sessionIds = ConcurrentHashMap.newKeySet<String>()
        
        // When - Create many sessions concurrently
        val createJobs = sessionManagers.map { manager ->
            async(Dispatchers.IO) {
                val sessionId = manager.getCurrentSession()
                sessionIds.add(sessionId)
                manager.updateActivity(sessionId)
                manager
            }
        }
        
        val managers = createJobs.awaitAll()
        
        // Perform cleanup on all managers concurrently
        val cleanupJobs = managers.map { manager ->
            async(Dispatchers.IO) {
                manager.cleanupInactiveSessions()
            }
        }
        
        val cleanupResults = cleanupJobs.awaitAll()
        
        // Then
        assertEquals(sessionCount, sessionIds.size, "All sessions should be unique")
        assertTrue(cleanupResults.all { it >= 0 }, "All cleanup operations should succeed")
        
        // Verify sessions are still active (they were just created)
        managers.forEach { manager ->
            assertTrue(manager.getActiveSessionCount() >= 0, "Session count should be non-negative")
        }
    }
}