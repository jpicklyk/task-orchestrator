package io.github.jpicklyk.mcptask.current.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

object DependenciesTable : UUIDTable("dependencies") {
    val fromItemId = uuid("from_item_id")
    val toItemId = uuid("to_item_id")
    val type = varchar("type", 20).default("BLOCKS")
    val unblockAt = varchar("unblock_at", 20).nullable()
    val createdAt = timestamp("created_at")

    init {
        foreignKey(fromItemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        foreignKey(toItemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        uniqueIndex(fromItemId, toItemId, type)
        index(isUnique = false, fromItemId)
        index(isUnique = false, toItemId)
    }
}
