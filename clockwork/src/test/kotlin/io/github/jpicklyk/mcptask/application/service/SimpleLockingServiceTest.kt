package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.Priority
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class SimpleLockingServiceTest {

    private lateinit var lockingService: DefaultSimpleLockingService

    @BeforeEach
    fun setUp() {
        lockingService = DefaultSimpleLockingService()
    }

    @Test
    fun `should allow non-conflicting operations to proceed`() = runBlocking {
        // Given
        val operation = LockOperation(
            operationType = OperationType.READ,
            toolName = "GetTaskTool",
            description = "Reading task data",
            entityIds = setOf(UUID.randomUUID())
        )

        // When
        val canProceed = lockingService.canProceed(operation)

        // Then
        assertTrue(canProceed, "Non-conflicting operation should be allowed to proceed")
    }

    @Test
    fun `should detect conflict with DELETE operation on same entity`() = runBlocking {
        // Given
        val entityId = UUID.randomUUID()
        val deleteOperation = LockOperation(
            operationType = OperationType.DELETE,
            toolName = "DeleteTaskTool",
            description = "Deleting task",
            entityIds = setOf(entityId)
        )
        val writeOperation = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "UpdateTaskTool", 
            description = "Updating task",
            entityIds = setOf(entityId)
        )

        // When
        val deleteOpId = lockingService.recordOperationStart(deleteOperation)
        val canProceed = lockingService.canProceed(writeOperation)

        // Then
        assertFalse(canProceed, "Write operation should be blocked when DELETE operation is active on same entity")

        // Cleanup
        lockingService.recordOperationComplete(deleteOpId)
    }

    @Test
    fun `should allow operations on different entities`() = runBlocking {
        // Given
        val entityId1 = UUID.randomUUID()
        val entityId2 = UUID.randomUUID()
        val deleteOperation = LockOperation(
            operationType = OperationType.DELETE,
            toolName = "DeleteTaskTool",
            description = "Deleting task 1",
            entityIds = setOf(entityId1)
        )
        val writeOperation = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "UpdateTaskTool",
            description = "Updating task 2", 
            entityIds = setOf(entityId2)
        )

        // When
        val deleteOpId = lockingService.recordOperationStart(deleteOperation)
        val canProceed = lockingService.canProceed(writeOperation)

        // Then
        assertTrue(canProceed, "Operations on different entities should not conflict")

        // Cleanup
        lockingService.recordOperationComplete(deleteOpId)
    }

    @Test
    fun `should record and complete operations correctly`() = runBlocking {
        // Given
        val operation = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "UpdateTaskTool",
            description = "Updating task",
            entityIds = setOf(UUID.randomUUID())
        )

        // When
        assertEquals(0, lockingService.getActiveOperationCount(), "Should start with no active operations")
        
        val operationId = lockingService.recordOperationStart(operation)
        assertEquals(1, lockingService.getActiveOperationCount(), "Should have 1 active operation after start")
        
        lockingService.recordOperationComplete(operationId)
        assertEquals(0, lockingService.getActiveOperationCount(), "Should have no active operations after completion")
    }

    @Test
    fun `should track longest running operation`() = runBlocking {
        // Given
        val operation1 = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "Tool1",
            description = "Operation 1",
            entityIds = setOf(UUID.randomUUID())
        )
        val operation2 = LockOperation(
            operationType = OperationType.READ,
            toolName = "Tool2", 
            description = "Operation 2",
            entityIds = setOf(UUID.randomUUID())
        )

        // When
        val opId1 = lockingService.recordOperationStart(operation1)
        delay(50) // Ensure different start times
        val opId2 = lockingService.recordOperationStart(operation2)

        val longestRunning = lockingService.getLongestRunningOperation()

        // Then
        assertNotNull(longestRunning, "Should find longest running operation")
        assertEquals(opId1, longestRunning!!.first, "First operation should be longest running")
        assertTrue(longestRunning.second >= 50, "Duration should be at least 50ms")

        // Cleanup
        lockingService.recordOperationComplete(opId1)
        lockingService.recordOperationComplete(opId2)
    }

    @Test
    fun `should handle multiple overlapping entities in conflict detection`() = runBlocking {
        // Given
        val entity1 = UUID.randomUUID()
        val entity2 = UUID.randomUUID()
        val entity3 = UUID.randomUUID()
        
        val deleteOperation = LockOperation(
            operationType = OperationType.DELETE,
            toolName = "BulkDeleteTool",
            description = "Bulk delete operation",
            entityIds = setOf(entity1, entity2)
        )
        val writeOperation = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "UpdateTaskTool",
            description = "Update operation",
            entityIds = setOf(entity2, entity3) // Overlaps with entity2
        )

        // When
        val deleteOpId = lockingService.recordOperationStart(deleteOperation)
        val canProceed = lockingService.canProceed(writeOperation)

        // Then
        assertFalse(canProceed, "Should detect conflict when entity sets overlap")

        // Cleanup
        lockingService.recordOperationComplete(deleteOpId)
    }

    @Test
    fun `should allow operation after conflicting operation completes`() = runBlocking {
        // Given
        val entityId = UUID.randomUUID()
        val deleteOperation = LockOperation(
            operationType = OperationType.DELETE,
            toolName = "DeleteTaskTool",
            description = "Deleting task",
            entityIds = setOf(entityId)
        )
        val writeOperation = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "UpdateTaskTool",
            description = "Updating task",
            entityIds = setOf(entityId)
        )

        // When
        val deleteOpId = lockingService.recordOperationStart(deleteOperation)
        assertFalse(lockingService.canProceed(writeOperation), "Should be blocked initially")
        
        lockingService.recordOperationComplete(deleteOpId)
        assertTrue(lockingService.canProceed(writeOperation), "Should be allowed after delete completes")
    }

    @Test
    fun `should handle operation with no entity IDs`() = runBlocking {
        // Given
        val operationWithNoEntities = LockOperation(
            operationType = OperationType.READ,
            toolName = "ListTasksTool",
            description = "Listing all tasks",
            entityIds = emptySet()
        )

        // When
        val canProceed = lockingService.canProceed(operationWithNoEntities)
        val operationId = lockingService.recordOperationStart(operationWithNoEntities)

        // Then
        assertTrue(canProceed, "Operations with no specific entities should be allowed")
        assertEquals(1, lockingService.getActiveOperationCount())

        // Cleanup
        lockingService.recordOperationComplete(operationId)
    }

    @Test
    fun `should support different operation types`() = runBlocking {
        // Given
        val operations = listOf(
            LockOperation(OperationType.READ, "ReadTool", "Reading"),
            LockOperation(OperationType.WRITE, "WriteTool", "Writing"),
            LockOperation(OperationType.CREATE, "CreateTool", "Creating"),
            LockOperation(OperationType.SECTION_EDIT, "SectionTool", "Editing section"),
            LockOperation(OperationType.STRUCTURE_CHANGE, "StructureTool", "Changing structure")
        )

        // When & Then
        operations.forEach { operation ->
            assertTrue(lockingService.canProceed(operation), "All operation types should be supported: ${operation.operationType}")
        }
    }

    @Test
    fun `should support different priorities`() = runBlocking {
        // Given
        val highPriorityOp = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "HighPriorityTool",
            description = "High priority operation",
            priority = Priority.HIGH
        )
        val lowPriorityOp = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "LowPriorityTool", 
            description = "Low priority operation",
            priority = Priority.LOW
        )

        // When
        val highOpId = lockingService.recordOperationStart(highPriorityOp)
        val lowOpId = lockingService.recordOperationStart(lowPriorityOp)

        // Then
        assertEquals(2, lockingService.getActiveOperationCount())

        // Cleanup
        lockingService.recordOperationComplete(highOpId)
        lockingService.recordOperationComplete(lowOpId)
    }

    @Test
    fun `should detect conflict between WRITE operations on same entity`() = runBlocking {
        // Given
        val entityId = UUID.randomUUID()
        val writeOperation1 = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "UpdateTaskTool",
            description = "Agent A updating task",
            entityIds = setOf(entityId)
        )
        val writeOperation2 = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "UpdateTaskTool",
            description = "Agent B updating task",
            entityIds = setOf(entityId)
        )

        // When
        val opId1 = lockingService.recordOperationStart(writeOperation1)
        val canProceed = lockingService.canProceed(writeOperation2)

        // Then
        assertFalse(canProceed, "Second WRITE operation should be blocked when first WRITE operation is active on same entity")

        // Cleanup
        lockingService.recordOperationComplete(opId1)
    }

    @Test 
    fun `should cleanup expired operations automatically`() = runBlocking {
        // Given - Use short timeout for testing
        val shortTimeoutService = DefaultSimpleLockingService(operationTimeoutMinutes = 0) // 0 minutes = immediate expiration
        val entityId = UUID.randomUUID()
        val operation = LockOperation(
            operationType = OperationType.WRITE,
            toolName = "UpdateTaskTool",
            description = "Operation that will expire",
            entityIds = setOf(entityId)
        )

        // When
        val opId = shortTimeoutService.recordOperationStart(operation)
        assertEquals(1, shortTimeoutService.getActiveOperationCount(), "Should have 1 active operation")
        
        // Wait a moment then try another operation (this triggers cleanup)
        delay(100) // 100ms delay to ensure expiration
        val canProceed = shortTimeoutService.canProceed(operation)

        // Then
        assertTrue(canProceed, "Should be able to proceed after expired operation is cleaned up")
        assertEquals(0, shortTimeoutService.getActiveOperationCount(), "Expired operation should be cleaned up")
    }
}