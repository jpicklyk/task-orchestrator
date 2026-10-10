package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * The head pointer of each config scope: one row per root (and at most one for the server scope), pointing at the
 * current [ConfigVersionsTable] row. Mirrors `V25__Config_Versions.sql`; uniqueness is carried by partial unique
 * indexes there. [updatedAt] is the canonical 23-character UTC text.
 */
object ConfigHeadsTable : Table("config_heads") {
    val scope = text("scope")
    val rootItemId = javaUuidSqlite("root_id").nullable()
    val versionId = javaUuidSqlite("version_id")
    val updatedAt = utcTimestampText("updated_at")

    init {
        foreignKey(rootItemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        foreignKey(versionId to ConfigVersionsTable.id)
    }
}
