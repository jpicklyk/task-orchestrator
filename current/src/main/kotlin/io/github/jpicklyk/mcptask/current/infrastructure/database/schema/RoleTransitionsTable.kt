package io.github.jpicklyk.mcptask.current.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

object RoleTransitionsTable : UUIDTable("role_transitions") {
    val itemId = uuid("item_id")
    val fromRole = varchar("from_role", 20)
    val toRole = varchar("to_role", 20)
    val fromStatusLabel = text("from_status_label").nullable()
    val toStatusLabel = text("to_status_label").nullable()
    val trigger = varchar("trigger", 50)
    val summary = text("summary").nullable()
    val transitionedAt = timestamp("transitioned_at")

    init {
        foreignKey(itemId to WorkItemsTable.id)
        index(isUnique = false, itemId)
        index(isUnique = false, transitionedAt)
    }
}
