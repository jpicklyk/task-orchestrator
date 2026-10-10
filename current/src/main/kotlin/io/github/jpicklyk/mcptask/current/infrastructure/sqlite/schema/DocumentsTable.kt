package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Versioned documents (plans, rules, pages), one row per `(rootItemId, kind, slug)`, holding the CURRENT body.
 * Mirrors `V24__Documents_And_Links.sql`.
 *
 * [docRowid] is the table's explicit rowid alias (`INTEGER PRIMARY KEY`): the document FTS tables key on it, and VACUUM
 * never renumbers it. It is assigned by SQLite on insert and never set by the store. [id] is the stable identity.
 */
object DocumentsTable : Table("documents") {
    val docRowid = long("doc_rowid").autoIncrement()
    val id = javaUuidSqlite("id")
    val rootItemId = javaUuidSqlite("root_item_id")

    /** Raw [io.github.jpicklyk.mcptask.current.domain.model.DocumentKind] value: `plan`, `rule` or `page`. */
    val kind = text("kind")
    val slug = text("slug")
    val title = text("title").nullable()
    val body = text("body")
    val contentHash = text("content_hash")
    val version = integer("version")
    val archivedAt = utcTimestamp("archived_at").nullable()
    val createdAt = utcTimestamp("created_at")
    val modifiedAt = utcTimestamp("modified_at")

    override val primaryKey = PrimaryKey(docRowid)

    init {
        foreignKey(rootItemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        uniqueIndex(id)
        uniqueIndex(rootItemId, kind, slug)
        index(isUnique = false, rootItemId)
    }
}
