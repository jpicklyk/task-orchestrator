package io.github.jpicklyk.mcptask.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Unified table for managing tags across all business entities.
 * This design normalizes tag storage and allows for efficient querying.
 */
object EntityTagsTable : UUIDTable("entity_tags") {
    /** The UUID of the entity (task, feature, or project) */
    val entityId = uuid("entity_id")

    /** The type of entity this tag belongs to - stored as varchar */
    val entityType = varchar("entity_type", 20)

    /** The tag value */
    val tag = varchar("tag", 100)

    /** When this tag was added to the entity */
    val createdAt = timestamp("created_at")

    init {
        // Ensure each entity can have each tag only once
        uniqueIndex(entityId, entityType, tag)

        // Index for efficient tag-based queries
        index(isUnique = false, tag)

        // Index for efficient entity lookups
        index(isUnique = false, entityId, entityType)
    }
}
