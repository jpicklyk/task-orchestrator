package io.github.jpicklyk.mcptask.infrastructure.database.schema

import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Database table definition for Projects using Exposed ORM.
 * Updated to support unified architecture with search vectors.
 */
object ProjectsTable : UUIDTable("projects") {
    val name = varchar("name", 255)
    val summary = text("summary")
    val description = text("description").nullable()
    val status = enumerationByName("status", 20, ProjectStatus::class)
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
        // Indexes for common queries
        index(isUnique = false, status)
        index(isUnique = false, createdAt)
        index(isUnique = false, modifiedAt)
        index(isUnique = false, version)

        // Performance index for search
        index(isUnique = false, searchVector)
    }
}
