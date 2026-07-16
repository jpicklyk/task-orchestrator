package io.github.jpicklyk.mcptask.current.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Per-root store of agent-authored planning documents, stashed ahead of adoption into a real
 * WorkItem (see [io.github.jpicklyk.mcptask.current.application.service.PlanDocumentService]).
 *
 * `(rootItemId, slug)` is unique — a stash to an existing `pending` slug overwrites it in place;
 * a stash to an `adopted` slug is rejected by the service layer (adoption is a one-way transition,
 * not enforced by a DB constraint). Mirrors [ProjectConfigTable]'s style: BLOB id default, a plain
 * unique-index pair rather than an inline `UNIQUE` column.
 *
 * Two FKs to [WorkItemsTable] with deliberately different delete actions — see
 * `V12__Plan_Documents.sql` for the full rationale: [rootItemId] cascades (the document belongs to
 * its root), [adoptedByItemId] sets null (deleting the adopting item only unlinks the adoption).
 */
object PlanDocumentsTable : UUIDTable("plan_documents") {
    val rootItemId = javaUUID("root_item_id")
    val slug = text("slug")
    val body = text("body")
    val contentHash = text("content_hash")

    /** Raw string value of [io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus] — "pending" or "adopted". */
    val status = text("status")
    val adoptedByItemId = javaUUID("adopted_by_item_id").nullable()
    val createdAt = timestamp("created_at")
    val modifiedAt = timestamp("modified_at")

    init {
        foreignKey(rootItemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        foreignKey(adoptedByItemId to WorkItemsTable.id, onDelete = ReferenceOption.SET_NULL)
        uniqueIndex(rootItemId, slug)
    }
}
