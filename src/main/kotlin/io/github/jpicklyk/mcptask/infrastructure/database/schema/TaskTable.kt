package io.github.jpicklyk.mcptask.infrastructure.database.schema

import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.model.TaskLockStatus
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Database table definition for the Task entity using Exposed ORM.
 * Note: Task tags are now managed through the unified EntityTagsTable.
 */
object TaskTable : UUIDTable("tasks") {
    // Optional reference to a parent project
    val projectId = uuid("project_id").nullable()
    
    // Optional reference to a parent feature
    val featureId = uuid("feature_id").nullable()
    
    // Required task properties
    val title = text("title")
    val summary = text("summary")
    val description = text("description").nullable()
    val status = enumerationByName("status", 20, TaskStatus::class)
    val priority = enumerationByName("priority", 20, Priority::class)
    val complexity = integer("complexity")
    
    // Timestamps
    val createdAt = timestamp("created_at")
    val modifiedAt = timestamp("modified_at")
    
    // Locking system fields
    val version = long("version").default(1)
    val lastModifiedBy = text("last_modified_by").nullable()
    val lockStatus = enumerationByName("lock_status", 20, TaskLockStatus::class).default(TaskLockStatus.UNLOCKED)
    
    // Search optimization
    val searchVector = text("search_vector").nullable()
    
    // Indexes for common queries
    init {
        foreignKey(projectId to ProjectsTable.id)
        foreignKey(featureId to FeaturesTable.id)
        index(isUnique = false, projectId)
        index(isUnique = false, featureId)
        index(isUnique = false, status)
        index(isUnique = false, priority)
        index(isUnique = false, version)
        index(isUnique = false, lockStatus)
        index(isUnique = false, lastModifiedBy)

        // Performance indexes for search and filtering
        index(isUnique = false, searchVector)
        index(isUnique = false, status, priority)
        index(isUnique = false, featureId, status)
        index(isUnique = false, projectId, status)
        // Note: priority descending + createdAt ascending index created in migration
    }
}
