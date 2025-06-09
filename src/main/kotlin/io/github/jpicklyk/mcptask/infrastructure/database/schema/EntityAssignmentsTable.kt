package io.github.jpicklyk.mcptask.infrastructure.database.schema

import io.github.jpicklyk.mcptask.domain.model.AssignmentType
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Database table definition for entity assignments using Exposed ORM.
 * Tracks assignments of projects, features, and tasks to work sessions.
 */
object EntityAssignmentsTable : UUIDTable("entity_assignments") {
    /** Type of entity (PROJECT, FEATURE, TASK) */
    val entityType = text("entity_type")
    
    /** ID of the assigned entity */
    val entityId = uuid("entity_id")
    
    /** Session identifier */
    val sessionId = text("session_id")
    
    /** Type of assignment (EXCLUSIVE, COLLABORATIVE) */
    val assignmentType = enumerationByName("assignment_type", 20, AssignmentType::class)
    
    /** When the assignment was made */
    val assignedAt = timestamp("assigned_at")
    
    /** When the assignment expires */
    val expiresAt = timestamp("expires_at").nullable()
    
    /** Estimated completion time */
    val estimatedCompletion = timestamp("estimated_completion").nullable()
    
    /** Associated git branch */
    val gitBranch = text("git_branch").nullable()
    
    /** JSON with progress tracking metadata */
    val progressMetadata = text("progress_metadata").nullable()
    
    /** When the assignment record was created */
    val createdAt = timestamp("created_at")
    
    // Indexes and constraints
    init {
        foreignKey(sessionId to WorkSessionsTable.id)
        
        // Primary indexes for assignment queries
        index(isUnique = false, entityType, entityId)
        index(isUnique = false, sessionId)
        index(isUnique = false, assignmentType)
        index(isUnique = false, expiresAt)
        index(isUnique = false, gitBranch)
        
        // Additional performance indexes
        index(isUnique = false, entityId)
        index(isUnique = false, assignedAt)
    }
}