package io.github.jpicklyk.mcptask.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Database table definition for the WorkSession entity using Exposed ORM.
 * Tracks active Claude Code instances and their work sessions.
 */
object WorkSessionsTable : IdTable<String>("work_sessions") {
    /** Primary key - session identifier */
    override val id: Column<String> = text("session_id")
    
    /** Client identifier (e.g., "claude-desktop", "claude-code") */
    val clientId = text("client_id")
    
    /** Client version */
    val clientVersion = text("client_version")
    
    /** Host machine identifier */
    val hostname = text("hostname").nullable()
    
    /** User identifier if available */
    val userContext = text("user_context").nullable()
    
    /** When the session was started */
    val startedAt = timestamp("started_at")
    
    /** Last recorded activity timestamp */
    val lastActivity = timestamp("last_activity")
    
    /** JSON array of supported capabilities */
    val capabilities = text("capabilities")
    
    /** JSON with git worktree details */
    val gitWorktreeInfo = text("git_worktree_info").nullable()
    
    /** JSON with current assignments by scope */
    val activeAssignments = text("active_assignments").nullable()
    
    /** Additional JSON metadata */
    val metadata = text("metadata").nullable()
    
    /** When the session record was created */
    val createdAt = timestamp("created_at")
    
    override val primaryKey = PrimaryKey(id)
    
    // Indexes for common queries
    init {
        index(isUnique = false, lastActivity)
        index(isUnique = false, clientId)
        index(isUnique = false, startedAt)
        index(isUnique = false, hostname)
    }
}