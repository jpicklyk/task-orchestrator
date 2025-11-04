package io.github.jpicklyk.mcptask.infrastructure.database.schema

import io.github.jpicklyk.mcptask.domain.model.FeatureStatus
import io.github.jpicklyk.mcptask.domain.model.Priority
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Database table definition for Features using Exposed ORM.
 * Updated to support unified architecture with search vectors.
 */
object FeaturesTable : UUIDTable("features") {
    val projectId = uuid("project_id").nullable()
    val name = text("name")
    val summary = text("summary")
    val description = text("description").nullable()
    val status = enumerationByName("status", 20, FeatureStatus::class)
    val priority = enumerationByName("priority", 10, Priority::class)
    val createdAt = timestamp("created_at")
    val modifiedAt = timestamp("modified_at")

    // Remove deprecated comma-separated tags column
    // Tags are now handled by the unified EntityTagsTable
    // val tags = text("tags").default("")

    // Optimistic locking
    val version = long("version").default(1)

    // Search optimization for full-text search
    val searchVector = text("search_vector").nullable()

    init {
        foreignKey(projectId to ProjectsTable.id)

        // Indexes for common queries
        index(isUnique = false, projectId)
        index(isUnique = false, status)
        index(isUnique = false, priority)
        index(isUnique = false, createdAt)
        index(isUnique = false, modifiedAt)
        index(isUnique = false, version)

        // Performance index for search
        index(isUnique = false, searchVector)
    }
}
