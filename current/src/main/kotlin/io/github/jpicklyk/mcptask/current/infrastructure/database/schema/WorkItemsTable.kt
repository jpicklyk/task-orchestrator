package io.github.jpicklyk.mcptask.current.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

object WorkItemsTable : UUIDTable("work_items") {
    val parentId = uuid("parent_id").nullable()
    val title = text("title")
    val description = text("description").nullable()
    val summary = text("summary").default("")
    val role = varchar("role", 20).default("queue")
    val statusLabel = text("status_label").nullable()
    val previousRole = varchar("previous_role", 20).nullable()
    val priority = varchar("priority", 20).default("medium")
    val complexity = integer("complexity").default(5)
    val depth = integer("depth").default(0)
    val metadata = text("metadata").nullable()
    val tags = text("tags").nullable()
    val createdAt = timestamp("created_at")
    val modifiedAt = timestamp("modified_at")
    val roleChangedAt = timestamp("role_changed_at")
    val version = long("version").default(1)

    init {
        foreignKey(parentId to WorkItemsTable.id)
        index(isUnique = false, parentId)
        index(isUnique = false, role)
        index(isUnique = false, depth)
        index(isUnique = false, priority)
        index(isUnique = false, columns = arrayOf(role, roleChangedAt))
    }
}
