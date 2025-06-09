package io.github.jpicklyk.mcptask.infrastructure.database.schema

import io.github.jpicklyk.mcptask.domain.model.LockScope
import io.github.jpicklyk.mcptask.domain.model.LockType
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Database table definition for the TaskLock entity using Exposed ORM.
 * Stores locks for hierarchical coordination across projects, features, tasks, and sections.
 */
object TaskLocksTable : UUIDTable("task_locks") {
    /** Scope of the lock (PROJECT, FEATURE, TASK, SECTION) */
    val lockScope = enumerationByName("lock_scope", 20, LockScope::class)
    
    /** ID of the entity being locked */
    val entityId = uuid("entity_id")
    
    /** Session identifier of the lock holder */
    val sessionId = text("session_id")
    
    /** Type of lock acquired */
    val lockType = enumerationByName("lock_type", 20, LockType::class)
    
    /** When the lock was acquired */
    val lockedAt = timestamp("locked_at")
    
    /** When the lock will automatically expire */
    val expiresAt = timestamp("expires_at")
    
    /** Last time the lock was renewed */
    val lastRenewed = timestamp("last_renewed")
    
    /** JSON context information about the lock */
    val lockContext = text("lock_context")
    
    /** JSON array of all affected entity IDs */
    val affectedEntities = text("affected_entities").nullable()
    
    /** When the lock record was created */
    val createdAt = timestamp("created_at")
    
    // Indexes and constraints
    init {
        foreignKey(sessionId to WorkSessionsTable.sessionId)
        
        // Primary indexes for hierarchical queries
        index(isUnique = false, lockScope, entityId)
        index(isUnique = false, sessionId)
        index(isUnique = false, expiresAt)
        index(isUnique = false, lockType)
        index(isUnique = false, lockedAt)
        
        // Additional performance indexes
        index(isUnique = false, entityId)
        index(isUnique = false, lockScope)
    }
}