package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Fingerprints a root's config is known to have had before the V25 migration (the former
 * `project_config.fingerprint_history`). Read by the fast-forward guard for roots without a reset. Mirrors
 * `V25__Config_Versions.sql`.
 */
object ConfigKnownFingerprintsTable : Table("config_known_fingerprints") {
    val rootItemId = javaUuidSqlite("root_id")
    val fingerprint = text("fingerprint")

    override val primaryKey = PrimaryKey(rootItemId, fingerprint)

    init {
        foreignKey(rootItemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
    }
}
