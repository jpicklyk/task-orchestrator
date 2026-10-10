package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Append-only document history: one row per version of a document, version 1 included. Mirrors
 * `V23__Documents_And_Links.sql`; rows cascade away with their document.
 */
object DocumentVersionsTable : Table("document_versions") {
    val id = javaUuidSqlite("id")
    val documentId = javaUuidSqlite("document_id")
    val version = integer("version")
    val body = text("body")
    val contentHash = text("content_hash")
    val actorId = text("actor_id").nullable()
    val createdAt = utcTimestamp("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        foreignKey(documentId to DocumentsTable.id, onDelete = ReferenceOption.CASCADE)
        uniqueIndex(documentId, version)
    }
}
