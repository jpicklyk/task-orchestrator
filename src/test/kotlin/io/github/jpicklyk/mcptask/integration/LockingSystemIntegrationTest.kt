package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.application.service.*
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ManageContainerTool
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Integration tests for the locking system using real H2 database.
 * Tests the complete flow from tools through services to database.
 */
class LockingSystemIntegrationTest {

    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var taskRepository: SQLiteTaskRepository
    private lateinit var lockingService: DefaultSimpleLockingService
    private lateinit var sessionManager: DefaultSimpleSessionManager
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        // Create a unique in-memory H2 database for each test
        val dbName = "locking_test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        databaseManager.updateSchema()

        // Initialize repositories
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
        taskRepository = SQLiteTaskRepository(databaseManager)
        
        // Initialize locking components
        lockingService = DefaultSimpleLockingService()
        sessionManager = DefaultSimpleSessionManager()
        
        context = ToolExecutionContext(repositoryProvider)
    }

    @Test
    fun `should track operations during task creation and updates`() = runBlocking {
        // Given - Create a task first
        val task = Task(
            title = "Integration Test Task",
            summary = "Task for locking integration test",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM
        )
        val createResult = taskRepository.create(task)
        assertTrue(createResult is Result.Success)

        // Create update tool with locking
        val updateTool = ManageContainerTool()
        val updateParams = JsonObject(mapOf(
            "operation" to JsonPrimitive("update"),
            "containerType" to JsonPrimitive("task"),
            "id" to JsonPrimitive(task.id.toString()),
            "title" to JsonPrimitive("Updated Task Title"),
            "status" to JsonPrimitive("in_progress")
        ))

        // When - Perform update operation
        assertEquals(0, lockingService.getActiveOperationCount(), "Should start with no active operations")
        
        val operationId = lockingService.recordOperationStart(
            LockOperation(
                operationType = OperationType.WRITE,
                toolName = "ManageContainerTool",
                description = "Updating task ${task.id}",
                entityIds = setOf(task.id)
            )
        )
        
        assertEquals(1, lockingService.getActiveOperationCount(), "Should have 1 active operation")

        // Execute the actual update
        val updateResult = updateTool.execute(updateParams, context)
        assertTrue(updateResult is JsonObject)
        val resultObj = updateResult as JsonObject
        assertEquals(true, (resultObj["success"] as? JsonPrimitive)?.content?.toBoolean())

        // Complete the operation
        lockingService.recordOperationComplete(operationId)
        assertEquals(0, lockingService.getActiveOperationCount(), "Should have no active operations after completion")
    }

    @Test
    fun `should prevent conflicting operations on same entity`() = runBlocking {
        // Given - Create a task
        val task = Task(
            title = "Conflict Test Task",
            summary = "Task for conflict testing",
            status = TaskStatus.PENDING
        )
        val createResult = taskRepository.create(task)
        assertTrue(createResult is Result.Success)

        // Start a DELETE operation
        val deleteOperation = LockOperation(
            operationType = OperationType.DELETE,
            toolName = "DeleteTaskTool",
            description = "Deleting task ${task.id}",
            entityIds = setOf(task.id)
        )
        
        val updateOperation = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "ManageContainerTool",
            description = "Updating task ${task.id}",
            entityIds = setOf(task.id)
        )

        // When
        val deleteOpId = lockingService.recordOperationStart(deleteOperation)
        val canUpdateProceed = lockingService.canProceed(updateOperation)

        // Then
        assertFalse(canUpdateProceed, "Update should be blocked when delete is active")

        // Cleanup
        lockingService.recordOperationComplete(deleteOpId)
        assertTrue(lockingService.canProceed(updateOperation), "Update should be allowed after delete completes")
    }

    @Test
    fun `should allow concurrent operations on different entities`() = runBlocking {
        // Given - Create two tasks
        val task1 = Task(
            title = "Task 1",
            summary = "First task",
            status = TaskStatus.PENDING
        )
        val task2 = Task(
            title = "Task 2", 
            summary = "Second task",
            status = TaskStatus.PENDING
        )
        
        taskRepository.create(task1)
        taskRepository.create(task2)

        val operation1 = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "ManageContainerTool",
            description = "Updating task ${task1.id}",
            entityIds = setOf(task1.id)
        )

        val operation2 = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "ManageContainerTool",
            description = "Updating task ${task2.id}",
            entityIds = setOf(task2.id)
        )

        // When
        val op1Id = lockingService.recordOperationStart(operation1)
        val canOp2Proceed = lockingService.canProceed(operation2)

        // Then
        assertTrue(canOp2Proceed, "Operations on different entities should not conflict")
        assertEquals(1, lockingService.getActiveOperationCount())

        val op2Id = lockingService.recordOperationStart(operation2)
        assertEquals(2, lockingService.getActiveOperationCount())

        // Cleanup
        lockingService.recordOperationComplete(op1Id)
        lockingService.recordOperationComplete(op2Id)
    }

    @Test
    fun `should manage session lifecycle correctly`() {
        // Given
        val initialSessionCount = sessionManager.getActiveSessionCount()
        
        // When - Get current session
        val sessionId1 = sessionManager.getCurrentSession()
        assertNotNull(sessionId1)
        assertTrue(sessionId1.startsWith("session-"))
        
        // Subsequent calls should return same session
        val sessionId2 = sessionManager.getCurrentSession()
        assertEquals(sessionId1, sessionId2, "Should return same session in same context")
        
        // Update session activity
        sessionManager.updateActivity(sessionId1)
        assertTrue(sessionManager.isSessionActive(sessionId1))
        
        // Session count should increase
        assertTrue(sessionManager.getActiveSessionCount() > initialSessionCount)
    }

    @Test
    fun `should integrate session management with locking operations`() = runBlocking {
        // Given
        val sessionId = sessionManager.getCurrentSession()
        val task = Task(
            title = "Session Integration Task",
            summary = "Task for session integration test",
            status = TaskStatus.PENDING
        )
        taskRepository.create(task)

        val operation = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "ManageContainerTool",
            description = "Session-tracked operation on ${task.id}",
            entityIds = setOf(task.id),
            metadata = mapOf("sessionId" to sessionId)
        )

        // When
        sessionManager.updateActivity(sessionId)
        
        // Check operation can proceed before starting
        assertTrue(lockingService.canProceed(operation))
        
        val operationId = lockingService.recordOperationStart(operation)
        
        // Then
        assertTrue(sessionManager.isSessionActive(sessionId))
        assertEquals(1, lockingService.getActiveOperationCount())
        
        // Verify same operation is now blocked (conflict with itself)
        assertFalse(lockingService.canProceed(operation))
        
        // Complete operation
        lockingService.recordOperationComplete(operationId)
        assertEquals(0, lockingService.getActiveOperationCount())
        
        // After completion, operation should be able to proceed again
        assertTrue(lockingService.canProceed(operation))
    }

    @Test
    fun `should handle complex entity relationship scenarios`() = runBlocking {
        // Given - Create a task with sections
        val task = Task(
            title = "Complex Relationship Task",
            summary = "Task with sections for relationship testing",
            status = TaskStatus.PENDING
        )
        taskRepository.create(task)
        
        // Simulate section operations
        val taskOperation = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "ManageContainerTool",
            description = "Updating task ${task.id}",
            entityIds = setOf(task.id)
        )
        
        val sectionOperation = LockOperation(
            operationType = OperationType.SECTION_EDIT,
            toolName = "UpdateSectionTool",
            description = "Editing sections for task ${task.id}",
            entityIds = setOf(task.id), // Section operations should reference parent task
            metadata = mapOf("operationType" to "section_edit")
        )

        // When
        val taskOpId = lockingService.recordOperationStart(taskOperation)
        
        // Section editing should be allowed concurrently with task updates
        // (in a more sophisticated system, this might require compatibility rules)
        val canSectionProceed = lockingService.canProceed(sectionOperation)
        
        // Then
        assertTrue(canSectionProceed, "Section operations should be compatible with task updates")
        
        val sectionOpId = lockingService.recordOperationStart(sectionOperation)
        assertEquals(2, lockingService.getActiveOperationCount())
        
        // Cleanup
        lockingService.recordOperationComplete(taskOpId)
        lockingService.recordOperationComplete(sectionOpId)
    }

    @Test
    fun `should track operation duration and performance metrics`() = runBlocking {
        // Given
        val task = Task(
            title = "Performance Test Task",
            summary = "Task for performance testing",
            status = TaskStatus.PENDING
        )
        taskRepository.create(task)

        val operation = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "ManageContainerTool",
            description = "Performance test operation",
            entityIds = setOf(task.id),
            expectedDurationMinutes = 5
        )

        // When
        val startTime = System.currentTimeMillis()
        val operationId = lockingService.recordOperationStart(operation)
        
        // Simulate some work
        Thread.sleep(50)
        
        val longestRunning = lockingService.getLongestRunningOperation()
        
        // Then
        assertNotNull(longestRunning)
        assertEquals(operationId, longestRunning!!.first)
        assertTrue(longestRunning.second >= 50, "Duration should be at least 50ms")
        
        // Complete operation
        lockingService.recordOperationComplete(operationId)
        
        // After completion, no longest running operation should exist
        assertNull(lockingService.getLongestRunningOperation())
    }

    @Test
    fun `should handle bulk operations correctly`() = runBlocking {
        // Given - Create multiple tasks
        val tasks = (1..3).map { i ->
            Task(
                title = "Bulk Task $i",
                summary = "Task $i for bulk operation testing",
                status = TaskStatus.PENDING
            ).also { taskRepository.create(it) }
        }

        val bulkOperation = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "BulkUpdateTool",
            description = "Bulk updating multiple tasks",
            entityIds = tasks.map { it.id }.toSet()
        )

        val singleTaskOperation = LockOperation(
            operationType = OperationType.DELETE,
            toolName = "DeleteTaskTool", 
            description = "Deleting single task",
            entityIds = setOf(tasks[1].id) // Overlaps with bulk operation
        )

        // When
        val bulkOpId = lockingService.recordOperationStart(bulkOperation)
        val canSingleProceed = lockingService.canProceed(singleTaskOperation)

        // Then
        assertFalse(canSingleProceed, "Single DELETE should be blocked when bulk operation is active on same entity")
        
        // Test with non-overlapping entity
        val nonOverlappingTask = Task(
            title = "Non-overlapping Task",
            summary = "Task not in bulk operation",
            status = TaskStatus.PENDING
        )
        taskRepository.create(nonOverlappingTask)
        
        val nonOverlappingOperation = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "ManageContainerTool",
            description = "Updating non-overlapping task",
            entityIds = setOf(nonOverlappingTask.id)
        )
        
        assertTrue(lockingService.canProceed(nonOverlappingOperation), "Non-overlapping operations should proceed")
        
        // Cleanup
        lockingService.recordOperationComplete(bulkOpId)
    }
}