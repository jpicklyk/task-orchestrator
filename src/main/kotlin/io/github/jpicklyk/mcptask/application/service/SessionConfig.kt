package io.github.jpicklyk.mcptask.application.service

/**
 * Configuration for session management.
 */
data class SessionConfig(
    /** Default session timeout in minutes */
    val defaultTimeoutMinutes: Long = 120, // 2 hours
    
    /** How often to run session cleanup in minutes */
    val cleanupIntervalMinutes: Long = 30,
    
    /** Enable automatic session creation */
    val enableAutoSessionCreation: Boolean = true,
    
    /** Enable session heartbeat */
    val enableHeartbeat: Boolean = true,
    
    /** Heartbeat interval in minutes */
    val heartbeatIntervalMinutes: Long = 10,
    
    /** Maximum number of active sessions per client */
    val maxSessionsPerClient: Int = 5,
    
    /** Enable session statistics collection */
    val enableStatistics: Boolean = true
)