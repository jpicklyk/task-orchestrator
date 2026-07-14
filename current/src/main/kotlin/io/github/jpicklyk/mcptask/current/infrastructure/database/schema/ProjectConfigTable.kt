package io.github.jpicklyk.mcptask.current.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * One row per project root (a depth-0 WorkItem): a raw YAML config document scoped to that root,
 * plus a content fingerprint used by [io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService]
 * to detect changes and hot-reload its in-memory parse without a server restart.
 *
 * Mirrors [RoleTransitionsTable]'s style: a single FK to [WorkItemsTable] with `ON DELETE CASCADE`
 * so deleting a root item automatically removes its config row.
 */
object ProjectConfigTable : UUIDTable("project_config") {
    val rootItemId = javaUUID("root_item_id")
    val configYaml = text("config_yaml")
    val fingerprint = text("fingerprint")
    val updatedAt = timestamp("updated_at")

    init {
        foreignKey(rootItemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        uniqueIndex(rootItemId)
    }
}
