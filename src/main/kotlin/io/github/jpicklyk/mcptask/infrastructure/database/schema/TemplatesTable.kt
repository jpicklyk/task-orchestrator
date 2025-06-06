package io.github.jpicklyk.mcptask.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Exposed SQL table definition for the Template entity.
 */
object TemplatesTable : Table("templates") {
    val id = uuid("id")
    val name = varchar("name", 200)
    val description = text("description")
    val targetEntityType = varchar("target_entity_type", 50)
    val isBuiltIn = bool("is_built_in").default(false)
    val isProtected = bool("is_protected").default(false)
    val isEnabled = bool("is_enabled").default(true)
    val createdBy = varchar("created_by", 200).nullable()
    val tags = text("tags") // Stored as comma-separated values
    val createdAt = timestamp("created_at")
    val modifiedAt = timestamp("modified_at")

    override val primaryKey = PrimaryKey(id)

    // Create indices for faster querying
    init {
        index(isUnique = true, name)
        index(isUnique = false, targetEntityType)
        index(isUnique = false, isBuiltIn)
        index(isUnique = false, isEnabled)
    }
}

/**
 * Exposed SQL table definition for the TemplateSection entity.
 */
object TemplateSectionsTable : Table("template_sections") {
    val id = uuid("id")
    val templateId = uuid("template_id").references(TemplatesTable.id)
    val title = varchar("title", 200)
    val usageDescription = text("usage_description")
    val contentSample = text("content_sample")
    val contentFormat = varchar("content_format", 50)
    val ordinal = integer("ordinal")
    val isRequired = bool("is_required").default(false)
    val tags = text("tags") // Stored as comma-separated values

    override val primaryKey = PrimaryKey(id)

    // Create indices for faster querying
    init {
        index(isUnique = false, templateId)
        index(isUnique = true, templateId, ordinal)
    }
}
