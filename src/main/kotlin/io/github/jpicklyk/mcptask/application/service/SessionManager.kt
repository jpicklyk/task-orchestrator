package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.WorkSessionRepository
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for managing work sessions in the task locking system.
 * Handles session lifecycle, automatic detection, cleanup, and heartbeat management.
 */
interface SessionManager {
    /**
     * Gets or creates a session for the current request context.
     * @param requestContext Information about the current request/client
     * @return The session ID for the current context
     */
    suspend fun getCurrentSession(requestContext: RequestContext? = null): String
    
    /**
     * Registers a new session explicitly.
     * @param instanceInfo Information about the client instance
     * @param sessionId Optional specific session ID (auto-generated if null)
     * @return The created session
     */
    suspend fun registerSession(instanceInfo: InstanceInfo, sessionId: String? = null): Result<WorkSession>
    
    /**
     * Updates the last activity timestamp for a session.
     * @param sessionId The session ID to update
     * @return The updated session or error
     */
    suspend fun updateActivity(sessionId: String): Result<WorkSession>
    
    /**
     * Terminates a session gracefully.
     * @param sessionId The session ID to terminate
     * @return Success or error result
     */
    suspend fun terminateSession(sessionId: String): Result<Unit>
    
    /**
     * Checks if a session exists and is active.
     * @param sessionId The session ID to check
     * @return True if the session is active
     */
    suspend fun isSessionActive(sessionId: String): Boolean
    
    /**
     * Gets session information by ID.
     * @param sessionId The session ID
     * @return The session or null if not found
     */
    suspend fun getSession(sessionId: String): Result<WorkSession?>
    
    /**
     * Starts the session cleanup background process.
     */
    fun startCleanupProcess()
    
    /**
     * Stops the session cleanup background process.
     */
    fun stopCleanupProcess()
}

/**
 * Context information extracted from MCP requests for session management.
 */
data class RequestContext(
    /** Client type identifier */
    val clientId: String,
    
    /** Client version */
    val version: String,
    
    /** Host information if available */
    val hostname: String? = null,
    
    /** User context if available */
    val userContext: String? = null,
    
    /** Request metadata */
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Default implementation of SessionManager.
 */
class DefaultSessionManager(
    private val sessionRepository: WorkSessionRepository,
    private val config: SessionConfig = SessionConfig()
) : SessionManager {
    
    private val logger = LoggerFactory.getLogger(DefaultSessionManager::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var cleanupJob: Job? = null
    
    // Cache for session lookups to reduce database queries
    private val sessionCache = ConcurrentHashMap<String, WorkSession>()
    
    // Thread-local storage for current session context
    private val currentSessionContext = ThreadLocal<String>()
    
    override suspend fun getCurrentSession(requestContext: RequestContext?): String {
        // First check if we have a session in the current context
        currentSessionContext.get()?.let { sessionId ->
            if (isSessionActive(sessionId)) {
                return sessionId
            }
        }
        
        // If no context is provided, generate a default one
        val context = requestContext ?: RequestContext(
            clientId = "unknown",
            version = "1.0.0",
            hostname = System.getProperty("java.version"),
            userContext = System.getProperty("user.name")
        )
        
        // Try to find an existing active session for this client
        val existingSessions = sessionRepository.getActiveSessions().getOrNull() ?: emptyList()
        val matchingSession = existingSessions.find { session ->
            session.instanceInfo.clientId == context.clientId &&
            session.instanceInfo.hostname == context.hostname &&
            session.instanceInfo.userContext == context.userContext
        }
        
        return if (matchingSession != null) {
            // Update activity and cache the session
            updateActivity(matchingSession.sessionId)
            sessionCache[matchingSession.sessionId] = matchingSession
            currentSessionContext.set(matchingSession.sessionId)
            matchingSession.sessionId
        } else {
            // Create a new session
            val instanceInfo = InstanceInfo(
                clientId = context.clientId,
                version = context.version,
                hostname = context.hostname,
                userContext = context.userContext
            )
            
            val result = registerSession(instanceInfo)
            when (result) {
                is Result.Success -> {
                    currentSessionContext.set(result.data.sessionId)
                    result.data.sessionId
                }
                is Result.Error -> {
                    logger.error("Failed to create session: ${result.message}")
                    // Fall back to a generated session ID
                    val fallbackId = "session-${UUID.randomUUID()}"
                    currentSessionContext.set(fallbackId)
                    fallbackId
                }
            }
        }
    }
    
    override suspend fun registerSession(instanceInfo: InstanceInfo, sessionId: String?): Result<WorkSession> {
        val id = sessionId ?: "session-${UUID.randomUUID()}"
        
        val session = WorkSession(
            sessionId = id,
            instanceInfo = instanceInfo,
            startedAt = Instant.now(),
            lastActivity = Instant.now(),
            capabilities = getDefaultCapabilities()
        )
        
        return when (val result = sessionRepository.create(session)) {
            is Result.Success -> {
                sessionCache[id] = session
                logger.info("Created new session: $id for client ${instanceInfo.clientId}")
                Result.Success(session)
            }
            is Result.Error -> {
                logger.error("Failed to create session: ${result.message}")
                result
            }
        }
    }
    
    override suspend fun updateActivity(sessionId: String): Result<WorkSession> {
        return when (val result = sessionRepository.updateActivity(sessionId)) {
            is Result.Success -> {
                sessionCache[sessionId] = result.data
                Result.Success(result.data)
            }
            is Result.Error -> {
                // Remove from cache if update failed
                sessionCache.remove(sessionId)
                logger.warn("Failed to update activity for session $sessionId: ${result.message}")
                result
            }
        }
    }
    
    override suspend fun terminateSession(sessionId: String): Result<Unit> {
        return when (val result = sessionRepository.delete(sessionId)) {
            is Result.Success -> {
                sessionCache.remove(sessionId)
                if (currentSessionContext.get() == sessionId) {
                    currentSessionContext.remove()
                }
                logger.info("Terminated session: $sessionId")
                Result.Success(Unit)
            }
            is Result.Error -> {
                logger.error("Failed to terminate session $sessionId: ${result.message}")
                result
            }
        }
    }
    
    override suspend fun isSessionActive(sessionId: String): Boolean {
        // Check cache first
        sessionCache[sessionId]?.let { cachedSession ->
            if (!cachedSession.isInactive(config.defaultTimeoutMinutes)) {
                return true
            } else {
                sessionCache.remove(sessionId)
            }
        }
        
        return when (val result = sessionRepository.isSessionActive(sessionId)) {
            is Result.Success -> result.data
            is Result.Error -> {
                logger.debug("Error checking session activity for $sessionId: ${result.message}")
                false
            }
        }
    }
    
    override suspend fun getSession(sessionId: String): Result<WorkSession?> {
        // Check cache first
        sessionCache[sessionId]?.let { cachedSession ->
            return Result.Success(cachedSession)
        }
        
        return when (val result = sessionRepository.getById(sessionId)) {
            is Result.Success -> {
                result.data?.let { session ->
                    sessionCache[sessionId] = session
                }
                result
            }
            is Result.Error -> result
        }
    }
    
    override fun startCleanupProcess() {
        if (cleanupJob?.isActive == true) {
            logger.debug("Cleanup process is already running")
            return
        }
        
        cleanupJob = scope.launch {
            logger.info("Starting session cleanup process with interval of ${config.cleanupIntervalMinutes} minutes")
            
            while (true) {
                try {
                    performCleanup()
                    delay(config.cleanupIntervalMinutes * 60 * 1000) // Convert minutes to milliseconds
                } catch (e: Exception) {
                    logger.error("Error in session cleanup process", e)
                    delay(60000) // Wait 1 minute before retrying
                }
            }
        }
    }
    
    override fun stopCleanupProcess() {
        cleanupJob?.cancel()
        cleanupJob = null
        logger.info("Stopped session cleanup process")
    }
    
    private suspend fun performCleanup() {
        logger.debug("Performing session cleanup")
        
        try {
            // Clean up inactive sessions
            val deletedCount = sessionRepository.deleteInactiveSessions(config.defaultTimeoutMinutes)
            when (deletedCount) {
                is Result.Success -> {
                    if (deletedCount.data > 0) {
                        logger.info("Cleaned up ${deletedCount.data} inactive sessions")
                    }
                    
                    // Clear cache entries for deleted sessions
                    val activeSessions = sessionRepository.getActiveSessions().getOrNull() ?: emptyList()
                    val activeSessionIds = activeSessions.map { it.sessionId }.toSet()
                    
                    sessionCache.keys.removeAll { sessionId ->
                        !activeSessionIds.contains(sessionId)
                    }
                }
                is Result.Error -> {
                    logger.error("Failed to clean up inactive sessions: ${deletedCount.message}")
                }
            }
            
        } catch (e: Exception) {
            logger.error("Error during session cleanup", e)
        }
    }
    
    private fun getDefaultCapabilities(): Set<String> {
        return setOf(
            "task_management",
            "section_editing",
            "template_application",
            "feature_coordination",
            "concurrent_operations"
        )
    }
}

/**
 * Session context holder for managing the current session across request processing.
 */
object SessionContext {
    private val sessionThreadLocal = ThreadLocal<String>()
    
    /**
     * Sets the current session ID for this thread.
     */
    fun setCurrentSession(sessionId: String) {
        sessionThreadLocal.set(sessionId)
    }
    
    /**
     * Gets the current session ID for this thread.
     */
    fun getCurrentSession(): String? {
        return sessionThreadLocal.get()
    }
    
    /**
     * Clears the current session for this thread.
     */
    fun clearCurrentSession() {
        sessionThreadLocal.remove()
    }
    
    /**
     * Executes a block with a specific session context.
     */
    inline fun <T> withSession(sessionId: String, block: () -> T): T {
        val previousSession = getCurrentSession()
        try {
            setCurrentSession(sessionId)
            return block()
        } finally {
            if (previousSession != null) {
                setCurrentSession(previousSession)
            } else {
                clearCurrentSession()
            }
        }
    }
}