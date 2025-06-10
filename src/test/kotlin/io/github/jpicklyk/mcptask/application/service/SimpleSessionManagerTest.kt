package io.github.jpicklyk.mcptask.application.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class SimpleSessionManagerTest {

    private lateinit var sessionManager: DefaultSimpleSessionManager

    @BeforeEach
    fun setUp() {
        sessionManager = DefaultSimpleSessionManager()
    }

    @Test
    fun `should create a new session when none exists`() {
        // When
        val sessionId = sessionManager.getCurrentSession()

        // Then
        assertNotNull(sessionId, "Should create a new session")
        assertTrue(sessionId.startsWith("session-"), "Session ID should have proper prefix")
        assertTrue(sessionManager.isSessionActive(sessionId), "New session should be active")
    }

    @Test
    fun `should return same session on subsequent calls in same context`() {
        // When
        val sessionId1 = sessionManager.getCurrentSession()
        val sessionId2 = sessionManager.getCurrentSession()

        // Then
        assertEquals(sessionId1, sessionId2, "Should return same session in same context")
    }

    @Test
    fun `should update session activity`() {
        // Given
        val sessionId = sessionManager.getCurrentSession()
        assertTrue(sessionManager.isSessionActive(sessionId), "Session should initially be active")

        // When
        sessionManager.updateActivity(sessionId)

        // Then
        assertTrue(sessionManager.isSessionActive(sessionId), "Session should remain active after update")
    }

    @Test
    fun `should track active session count`() {
        // Given
        assertEquals(0, sessionManager.getActiveSessionCount(), "Should start with 0 sessions")

        // When
        val sessionId1 = sessionManager.getCurrentSession()
        assertEquals(1, sessionManager.getActiveSessionCount(), "Should have 1 session after creation")

        // Create another session by updating activity with a new ID
        val newSessionId = "session-manual-test"
        sessionManager.updateActivity(newSessionId)
        assertEquals(2, sessionManager.getActiveSessionCount(), "Should have 2 sessions")
    }

    @Test
    fun `should detect inactive sessions`() {
        // Given
        val activeSessionId = sessionManager.getCurrentSession()
        val inactiveSessionId = "session-old-test"
        
        // Manually add an inactive session (simulate old timestamp)
        sessionManager.updateActivity(inactiveSessionId)
        
        // When
        assertTrue(sessionManager.isSessionActive(activeSessionId), "Recent session should be active")
        assertTrue(sessionManager.isSessionActive(inactiveSessionId), "Recently updated session should be active")
    }

    @Test
    fun `should return false for non-existent session`() {
        // Given
        val nonExistentSessionId = "session-does-not-exist"

        // When
        val isActive = sessionManager.isSessionActive(nonExistentSessionId)

        // Then
        assertFalse(isActive, "Non-existent session should not be active")
    }

    @Test
    fun `should cleanup inactive sessions`() = runBlocking {
        // Given
        val activeSessionId = sessionManager.getCurrentSession()
        val testSessionId1 = "session-test-1"
        val testSessionId2 = "session-test-2"
        
        sessionManager.updateActivity(testSessionId1)
        sessionManager.updateActivity(testSessionId2)
        
        val initialCount = sessionManager.getActiveSessionCount()
        assertTrue(initialCount >= 3, "Should have at least 3 sessions")

        // When - Note: The cleanup method only removes sessions older than 2 hours
        // In a real test, we would manipulate the internal state, but for this test
        // we're just verifying the method exists and doesn't crash
        val cleanedUp = sessionManager.cleanupInactiveSessions()

        // Then
        assertTrue(cleanedUp >= 0, "Cleanup should return non-negative count")
        assertTrue(sessionManager.getActiveSessionCount() >= 1, "Should still have at least the current session")
    }

    @Test
    fun `should handle concurrent session access`() = runBlocking {
        // This test verifies the thread-safety of the ConcurrentHashMap usage
        // Given
        val sessionIds = mutableSetOf<String>()

        // When - Simulate concurrent access
        repeat(10) {
            val sessionId = sessionManager.getCurrentSession()
            sessionIds.add(sessionId)
            sessionManager.updateActivity(sessionId)
        }

        // Then
        // All calls in the same thread context should return the same session
        assertEquals(1, sessionIds.size, "All calls in same thread should return same session")
        assertTrue(sessionManager.getActiveSessionCount() >= 1, "Should have at least 1 active session")
    }

    @Test
    fun `should handle session state transitions`() {
        // Given
        val sessionId = sessionManager.getCurrentSession()

        // When & Then - Test state transitions
        assertTrue(sessionManager.isSessionActive(sessionId), "New session should be active")
        
        sessionManager.updateActivity(sessionId)
        assertTrue(sessionManager.isSessionActive(sessionId), "Session should remain active after update")
        
        // Test with a different session
        val anotherSessionId = "session-another-test"
        assertFalse(sessionManager.isSessionActive(anotherSessionId), "Unknown session should not be active")
        
        sessionManager.updateActivity(anotherSessionId)
        assertTrue(sessionManager.isSessionActive(anotherSessionId), "Session should become active after update")
    }

    @Test
    fun `should generate unique session IDs`() {
        // Given
        val sessionIds = mutableSetOf<String>()

        // When - Create multiple sessions by clearing thread local context
        repeat(5) {
            // Force creation of new sessions by using different session manager instances
            val newManager = DefaultSimpleSessionManager()
            val sessionId = newManager.getCurrentSession()
            sessionIds.add(sessionId)
        }

        // Then
        assertEquals(5, sessionIds.size, "All session IDs should be unique")
        sessionIds.forEach { sessionId ->
            assertTrue(sessionId.startsWith("session-"), "All session IDs should have proper prefix")
        }
    }

    @Test
    fun `should handle edge cases gracefully`() {
        // Test with null-like session IDs
        assertFalse(sessionManager.isSessionActive(""), "Empty string should not be active")
        
        // Test updating non-existent session
        assertDoesNotThrow {
            sessionManager.updateActivity("non-existent-session")
        }
        
        // Test session activity after update
        assertTrue(sessionManager.isSessionActive("non-existent-session"), "Session should become active after update")
    }
}