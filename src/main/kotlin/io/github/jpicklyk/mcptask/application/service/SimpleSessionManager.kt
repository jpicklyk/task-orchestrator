package io.github.jpicklyk.mcptask.application.service

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Simplified session manager for Phase 2 implementation.
 * Provides basic session tracking without full database integration.
 */
interface SimpleSessionManager {
    /**
     * Gets or creates a session for the current request context.
     */
    fun getCurrentSession(): String
    
    /**
     * Updates the activity timestamp for a session.
     */
    fun updateActivity(sessionId: String)
    
    /**
     * Checks if a session is active.
     */
    fun isSessionActive(sessionId: String): Boolean
}

/**
 * Simple in-memory implementation of session manager.
 */
class DefaultSimpleSessionManager : SimpleSessionManager {
    
    private val logger = LoggerFactory.getLogger(DefaultSimpleSessionManager::class.java)
    
    // Track sessions in memory
    private val activeSessions = ConcurrentHashMap<String, Instant>()
    
    // Thread-local storage for current session context
    private val currentSessionContext = ThreadLocal<String>()
    
    override fun getCurrentSession(): String {
        // Check if we have a session in the current context
        currentSessionContext.get()?.let { sessionId ->
            if (isSessionActive(sessionId)) {
                return sessionId
            }
        }
        
        // Create a new session
        val sessionId = "session-${UUID.randomUUID()}"
        activeSessions[sessionId] = Instant.now()
        currentSessionContext.set(sessionId)
        
        logger.debug("Created new session: $sessionId")
        return sessionId
    }
    
    override fun updateActivity(sessionId: String) {
        activeSessions[sessionId] = Instant.now()
        logger.debug("Updated activity for session: $sessionId")
    }
    
    override fun isSessionActive(sessionId: String): Boolean {
        val lastActivity = activeSessions[sessionId] ?: return false
        val threshold = Instant.now().minusSeconds(7200) // 2 hours
        return lastActivity.isAfter(threshold)
    }
    
    /**
     * Gets the number of active sessions.
     */
    fun getActiveSessionCount(): Int = activeSessions.size
    
    /**
     * Cleans up inactive sessions.
     */
    fun cleanupInactiveSessions(): Int {
        val threshold = Instant.now().minusSeconds(7200) // 2 hours
        val inactiveSessions = activeSessions.filter { (_, lastActivity) ->
            lastActivity.isBefore(threshold)
        }
        
        inactiveSessions.keys.forEach { sessionId ->
            activeSessions.remove(sessionId)
        }
        
        if (inactiveSessions.isNotEmpty()) {
            logger.info("Cleaned up ${inactiveSessions.size} inactive sessions")
        }
        
        return inactiveSessions.size
    }
}