package io.github.jpicklyk.mcptask.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Database table definition for task locks using Exposed ORM.
 */
object TaskLocksTable : UUIDTable("task_locks") {
    val lockScope = enumerationByName("lock_scope", 20, io.github.jpicklyk.mcptask.domain.model.LockScope::class)
    val entityId = uuid("entity_id")
    val sessionId = varchar("session_id", 255).references(WorkSessionsTable.id)
    val lockType = enumerationByName("lock_type", 20, io.github.jpicklyk.mcptask.domain.model.LockType::class)
    val lockedAt = timestamp("locked_at")
    val expiresAt = timestamp("expires_at")
    val lastRenewed = timestamp("last_renewed")
    val lockContext = text("lock_context") // JSON with operation, git context, assignment type
    val affectedEntities = text("affected_entities").nullable() // JSON array of all affected entity IDs
    val createdAt = timestamp("created_at")
    
    init {
        index(isUnique = false, lockScope, entityId)
        index(isUnique = false, sessionId)
        index(isUnique = false, expiresAt)
        index(isUnique = false, affectedEntities)
        index(isUnique = false, lockType)
        index(isUnique = false, lockedAt)
    }
}