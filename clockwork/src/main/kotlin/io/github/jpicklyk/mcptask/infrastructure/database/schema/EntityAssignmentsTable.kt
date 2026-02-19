package io.github.jpicklyk.mcptask.infrastructure.database.schema

import io.github.jpicklyk.mcptask.domain.model.AssignmentType
import io.github.jpicklyk.mcptask.domain.model.EntityType
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Database table definition for entity assignments using Exposed ORM.
 */
object EntityAssignmentsTable : UUIDTable("entity_assignments") {
    val entityType = enumerationByName("entity_type", 20, EntityType::class)
    val entityId = uuid("entity_id")
    val sessionId = varchar("session_id", 255).references(WorkSessionsTable.id)
    val assignmentType = enumerationByName("assignment_type", 20, AssignmentType::class)
    val assignedAt = timestamp("assigned_at")
    val expiresAt = timestamp("expires_at").nullable()
    val estimatedCompletion = timestamp("estimated_completion").nullable()
    val gitBranch = varchar("git_branch", 255).nullable() // Associated git branch
    val progressMetadata = text("progress_metadata").nullable() // JSON with progress tracking
    val createdAt = timestamp("created_at")
    
    init {
        index(isUnique = false, entityType, entityId)
        index(isUnique = false, sessionId)
        index(isUnique = false, expiresAt)
        index(isUnique = false, assignmentType)
        index(isUnique = false, gitBranch)
    }
}