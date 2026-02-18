package io.github.jpicklyk.mcptask.current.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

object NotesTable : UUIDTable("notes") {
    val itemId = uuid("work_item_id")
    val key = varchar("key", 200)
    val role = varchar("role", 20)
    val body = text("body").default("")
    val createdAt = timestamp("created_at")
    val modifiedAt = timestamp("modified_at")

    init {
        foreignKey(itemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        uniqueIndex(itemId, key)
        index(isUnique = false, itemId)
        index(isUnique = false, role)
    }
}
