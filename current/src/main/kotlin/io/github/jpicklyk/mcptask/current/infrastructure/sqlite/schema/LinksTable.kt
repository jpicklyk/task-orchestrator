package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Polymorphic links between entities of one root. Mirrors `V24__Documents_And_Links.sql`.
 *
 * The ends ([fromKind]/[fromId], [toKind]/[toId]) carry no foreign key: a link is provenance and outlives the entity
 * it names, except that an item-delete cascade explicitly removes the links it orphans (adopted_by is kept). [toId] is NULL while the target is unresolved ([toRef] then holds its text). Only [rootItemId] cascades.
 */
object LinksTable : Table("links") {
    val id = javaUuidSqlite("id")
    val rootItemId = javaUuidSqlite("root_item_id")
    val fromKind = text("from_kind")
    val fromId = javaUuidSqlite("from_id")
    val toKind = text("to_kind")
    val toId = javaUuidSqlite("to_id").nullable()
    val toRef = text("to_ref").nullable()
    val relation = text("relation")
    val anchor = text("anchor").nullable()
    val createdAt = utcTimestamp("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        foreignKey(rootItemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        index(isUnique = false, fromKind, fromId)
        index(isUnique = false, toKind, toId)
    }
}
