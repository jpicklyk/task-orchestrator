package io.github.jpicklyk.mcptask.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Database table definition for work sessions using Exposed ORM.
 */
object WorkSessionsTable : IdTable<String>("work_sessions") {
    override val id = varchar("session_id", 255).entityId()
    override val primaryKey = PrimaryKey(id)
    
    val clientId = varchar("client_id", 100)
    val clientVersion = varchar("client_version", 50)
    val hostname = varchar("hostname", 255).nullable()
    val userContext = varchar("user_context", 255).nullable()
    val startedAt = timestamp("started_at")
    val lastActivity = timestamp("last_activity")
    val capabilities = text("capabilities") // JSON array
    val gitWorktreeInfo = text("git_worktree_info").nullable() // JSON with worktree details
    val activeAssignments = text("active_assignments").nullable() // JSON with current assignments by scope
    val metadata = text("metadata").nullable() // JSON
    val createdAt = timestamp("created_at")
    
    init {
        index(isUnique = false, lastActivity)
        index(isUnique = false, clientId)
        index(isUnique = false, startedAt)
    }
}