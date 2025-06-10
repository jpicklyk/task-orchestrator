package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import java.util.*

class LockErrorHandlerTest {
    
    private val errorHandler = DefaultLockErrorHandler()
    
    @Test
    fun `should handle lock conflict with user-friendly message`() {
        // Arrange
        val conflictingLock = TaskLock(
            scope = LockScope.TASK,
            entityId = UUID.randomUUID(),
            sessionId = "session-123",
            lockType = LockType.EXCLUSIVE,
            expiresAt = Instant.now().plusSeconds(600),
            lockContext = LockContext(
                operation = "update_task"
            )
        )
        
        val requestedLock = LockRequest(
            entityId = conflictingLock.entityId,
            scope = LockScope.TASK,
            lockType = LockType.SHARED_WRITE,
            sessionId = "session-456",
            operationName = "add_section",
            expectedDuration = 1800L
        )
        
        val suggestions = listOf(
            ConflictResolution.WaitForRelease(
                lockId = conflictingLock.id,
                lockHolder = conflictingLock.sessionId,
                estimatedWaitTime = 10L
            )
        )
        
        val lockConflict = LockError.LockConflict(
            message = "Cannot acquire lock due to conflicting exclusive lock",
            conflictingLocks = listOf(conflictingLock),
            requestedLock = requestedLock,
            suggestions = suggestions
        )
        
        // Act
        val result = errorHandler.handleLockError(lockConflict)
        
        // Assert
        assertTrue(result is JsonObject)
        val jsonResult = result.jsonObject
        
        assertEquals("false", jsonResult["success"]?.jsonPrimitive?.content)
        assertEquals("LOCK_CONFLICT", jsonResult["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
        
        val message = jsonResult["message"]?.jsonPrimitive?.content
        assertNotNull(message)
        assertTrue(message!!.contains("Another operation is currently modifying"))
        assertTrue(message.contains("Estimated wait time: 10 minutes"))
        
        val details = jsonResult["error"]?.jsonObject?.get("additionalData")?.jsonObject
        assertNotNull(details)
        assertNotNull(details!!["conflictingLocks"])
        assertNotNull(details["suggestions"])
    }
    
    @Test
    fun `should handle session expiration with clear guidance`() {
        // Arrange
        val sessionError = LockError.InvalidSession(
            message = "Session has expired after period of inactivity",
            sessionId = "session-expired-123",
            reason = SessionInvalidReason.EXPIRED
        )
        
        // Act
        val result = errorHandler.handleLockError(sessionError)
        
        // Assert
        val jsonResult = result.jsonObject
        assertEquals("INVALID_SESSION", jsonResult["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
        
        val details = jsonResult["error"]?.jsonObject?.get("additionalData")?.jsonObject
        assertEquals("EXPIRED", details!!["reason"]?.jsonPrimitive?.content)
        assertTrue(details["suggestion"]?.jsonPrimitive?.content!!.contains("Start a new session"))
    }
    
    @Test
    fun `should suggest appropriate conflict resolutions`() {
        // Arrange
        val conflictingLock = TaskLock(
            scope = LockScope.TASK,
            entityId = UUID.randomUUID(),
            sessionId = "other-session",
            lockType = LockType.SHARED_READ,
            expiresAt = Instant.now().plusSeconds(300), // 5 minutes
            lockContext = LockContext(
                operation = "read_task"
            )
        )
        
        val requestedLock = LockRequest(
            entityId = conflictingLock.entityId,
            scope = LockScope.TASK,
            lockType = LockType.EXCLUSIVE,
            sessionId = "my-session",
            operationName = "update_task",
            expectedDuration = 1800L
        )
        
        // Act
        val suggestions = errorHandler.suggestAlternatives(
            conflictingLocks = listOf(conflictingLock),
            requestedLock = requestedLock,
            context = null
        )
        
        // Assert
        assertFalse(suggestions.isEmpty())
        
        // Should suggest waiting since the lock expires soon
        val waitSuggestion = suggestions.find { it is ConflictResolution.WaitForRelease }
        assertNotNull(waitSuggestion)
        val actualWaitTime = (waitSuggestion as ConflictResolution.WaitForRelease).estimatedWaitTime
        assertNotNull(actualWaitTime)
        // Allow for small timing differences (4-5 minutes is acceptable)
        assertTrue(actualWaitTime!! in 4L..5L, "Expected wait time 4-5 minutes, got $actualWaitTime")
        
        // Should suggest escalation since SHARED_READ can escalate to EXCLUSIVE
        val escalationSuggestion = suggestions.find { it is ConflictResolution.RequestEscalation }
        assertNotNull(escalationSuggestion)
    }
    
    @Test
    fun `should format messages appropriately for different audiences`() {
        // Arrange
        val lockConflict = LockError.LockConflict(
            message = "Lock conflict detected",
            conflictingLocks = emptyList(),
            requestedLock = LockRequest(
                entityId = UUID.randomUUID(),
                scope = LockScope.TASK,
                lockType = LockType.EXCLUSIVE,
                sessionId = "session",
                operationName = "test",
                expectedDuration = 1800L
            ),
            suggestions = emptyList()
        )
        
        // Act
        val endUserMessage = errorHandler.formatErrorMessage(lockConflict, AudienceType.END_USER)
        val developerMessage = errorHandler.formatErrorMessage(lockConflict, AudienceType.DEVELOPER)
        val systemLogMessage = errorHandler.formatErrorMessage(lockConflict, AudienceType.SYSTEM_LOG)
        
        // Assert
        // End user message should be friendly and actionable
        assertTrue(endUserMessage.contains("Cannot proceed"))
        assertTrue(endUserMessage.contains("operation is currently modifying"))
        
        // Developer message should include error code
        assertTrue(developerMessage.contains("LOCK_CONFLICT"))
        
        // System log message should include timestamp format
        assertTrue(systemLogMessage.contains("LockError[LOCK_CONFLICT]"))
    }
    
    @Test
    fun `should handle resource constraints with usage information`() {
        // Arrange
        val resourceError = LockError.ResourceConstraint(
            message = "Maximum concurrent locks exceeded",
            constraintType = ResourceConstraintType.MAX_CONCURRENT_LOCKS,
            currentUsage = 95,
            maxAllowed = 100
        )
        
        // Act
        val result = errorHandler.handleLockError(resourceError)
        
        // Assert
        val jsonResult = result.jsonObject
        val details = jsonResult["error"]?.jsonObject?.get("additionalData")?.jsonObject
        
        assertEquals(95, details!!["currentUsage"]?.jsonPrimitive?.content?.toInt())
        assertEquals(100, details["maxAllowed"]?.jsonPrimitive?.content?.toInt())
        assertEquals(95, details["utilizationPercentage"]?.jsonPrimitive?.content?.toInt())
        assertTrue(details["suggestion"]?.jsonPrimitive?.content!!.contains("Release some locks"))
    }
    
    @Test
    fun `should handle hierarchy violations with guidance`() {
        // Arrange
        val parentId = UUID.randomUUID()
        val childId = UUID.randomUUID()
        
        val hierarchyError = LockError.HierarchyViolation(
            message = "Parent lock required before locking child",
            parentEntityId = parentId,
            childEntityId = childId,
            violationType = HierarchyViolationType.PARENT_LOCK_REQUIRED
        )
        
        // Act
        val result = errorHandler.handleLockError(hierarchyError)
        
        // Assert
        val jsonResult = result.jsonObject
        val details = jsonResult["error"]?.jsonObject?.get("additionalData")?.jsonObject
        
        assertEquals(parentId.toString(), details!!["parentEntityId"]?.jsonPrimitive?.content)
        assertEquals(childId.toString(), details["childEntityId"]?.jsonPrimitive?.content)
        assertEquals("PARENT_LOCK_REQUIRED", details["violationType"]?.jsonPrimitive?.content)
        assertTrue(details["suggestion"]?.jsonPrimitive?.content!!.contains("Acquire lock on parent entity first"))
    }
}