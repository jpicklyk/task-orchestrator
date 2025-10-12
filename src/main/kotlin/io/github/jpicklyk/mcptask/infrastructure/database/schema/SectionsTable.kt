package io.github.jpicklyk.mcptask.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Exposed SQL table definition for the Section entity.
 */
object SectionsTable : Table("sections") {
    val id = uuid("id")
    val entityType = varchar("entity_type", 50)
    val entityId = uuid("entity_id")
    val title = varchar("title", 200)
    val usageDescription = text("usage_description")
    val content = text("content")
    val contentFormat = varchar("content_format", 50)
    val ordinal = integer("ordinal")
    val tags = text("tags") // Stored as comma-separated values
    val createdAt = timestamp("created_at")
    val modifiedAt = timestamp("modified_at")
    val version = long("version").default(1)

    override val primaryKey = PrimaryKey(id)

    // Create indices for faster querying
    init {
        index(isUnique = false, entityType, entityId)
        index(isUnique = false, entityId)
        index(isUnique = true, entityType, entityId, ordinal)
        index(isUnique = false, version)
    }
}
