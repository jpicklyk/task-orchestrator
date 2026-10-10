package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import java.util.UUID

/**
 * Immutable config versions (one row per version per scope). Mirrors `V24__Config_Versions.sql`.
 *
 * [rootId] is NULL only for the server scope. [canonicalJson] / [modelVersion] are NULL until the
 * `config-canonicalize` data step fills them on rows copied by the migration. [createdAt] is persisted as the
 * canonical 23-character UTC text. [warningsJson] is a JSON array of strings (NULL = none).
 */
object ConfigVersionsTable : IdTable<UUID>("config_versions") {
    override val id: Column<EntityID<UUID>> = javaUuidSqlite("id").autoGenerate().entityId()
    override val primaryKey = PrimaryKey(id)

    val scope = text("scope")
    val rootItemId = javaUuidSqlite("root_id").nullable()
    val version = integer("version")
    val yaml = text("yaml")
    val fingerprint = text("fingerprint")
    val canonicalJson = text("canonical_json").nullable()
    val modelVersion = integer("model_version").nullable()
    val versionSource = text("source")
    val principalId = text("principal_id").nullable()
    val reqId = text("req_id").nullable()
    val createdAt = utcTimestampText("created_at")
    val warningsJson = text("warnings_json").nullable()

    init {
        foreignKey(rootItemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
    }
}
