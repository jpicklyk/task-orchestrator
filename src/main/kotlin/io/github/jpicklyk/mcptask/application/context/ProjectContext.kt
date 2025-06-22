package io.github.jpicklyk.mcptask.application.context

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Global project context manager for MCP server sessions.
 * 
 * This allows users to set a current project context once and have all subsequent
 * operations automatically scoped to that project. This improves both security
 * (preventing accidental cross-project data access) and usability (no need to
 * specify projectId for every operation).
 * 
 * The context is stored per session/connection to support multiple concurrent users.
 * 
 * Example usage:
 * ```
 * // Set the current project
 * ProjectContext.setCurrentProject(sessionId, projectId)
 * 
 * // Get the current project (returns null if not set)
 * val currentProjectId = ProjectContext.getCurrentProject(sessionId)
 * 
 * // Clear the project context
 * ProjectContext.clearCurrentProject(sessionId)
 * ```
 */
object ProjectContext {
    // Thread-safe map to store project context per session
    private val sessionProjects = ConcurrentHashMap<String, UUID>()
    
    /**
     * Sets the current project for a session.
     * 
     * @param sessionId The unique session identifier
     * @param projectId The project ID to set as current
     */
    fun setCurrentProject(sessionId: String, projectId: UUID) {
        sessionProjects[sessionId] = projectId
    }
    
    /**
     * Gets the current project for a session.
     * 
     * @param sessionId The unique session identifier
     * @return The current project ID, or null if not set
     */
    fun getCurrentProject(sessionId: String): UUID? {
        return sessionProjects[sessionId]
    }
    
    /**
     * Clears the current project for a session.
     * 
     * @param sessionId The unique session identifier
     */
    fun clearCurrentProject(sessionId: String) {
        sessionProjects.remove(sessionId)
    }
    
    /**
     * Checks if a session has a current project set.
     * 
     * @param sessionId The unique session identifier
     * @return true if a project is set, false otherwise
     */
    fun hasCurrentProject(sessionId: String): Boolean {
        return sessionProjects.containsKey(sessionId)
    }
    
    /**
     * Clears all project contexts. Useful for cleanup during shutdown.
     */
    fun clearAll() {
        sessionProjects.clear()
    }
    
    /**
     * Gets the number of active sessions with project contexts.
     * 
     * @return The number of sessions with active project contexts
     */
    fun getActiveSessionCount(): Int {
        return sessionProjects.size
    }
}