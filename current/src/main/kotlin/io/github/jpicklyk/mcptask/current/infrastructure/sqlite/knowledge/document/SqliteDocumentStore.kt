package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.knowledge.document

import io.github.jpicklyk.mcptask.current.application.port.DocumentStore
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.DocumentSummary
import io.github.jpicklyk.mcptask.current.domain.model.DocumentVersion
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQL_IN_CHUNK_SIZE
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.readTx
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.writeTx
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.DocumentVersionsTable
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.DocumentsTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of [DocumentStore] over [DocumentsTable] and [DocumentVersionsTable].
 *
 * Every write joins the ambient unit (see `writeTx`). [updateCurrent] is a single compare-and-set UPDATE keyed on
 * `(id, version)`. Timestamps go through the shared UTC column type (millisecond precision), so a returned document
 * is always re-read from its row rather than echoed from the caller.
 */
class SqliteDocumentStore(
    private val databaseManager: DatabaseManager
) : DocumentStore {
    override suspend fun get(
        rootItemId: UUID,
        kind: DocumentKind,
        slug: String
    ): Document? =
        databaseManager.readTx {
            DocumentsTable
                .selectAll()
                .where {
                    (DocumentsTable.rootItemId eq rootItemId) and
                        (DocumentsTable.kind eq kind.toDbValue()) and
                        (DocumentsTable.slug eq slug)
                }.singleOrNull()
                ?.let(::toDocument)
        }

    override suspend fun getById(id: UUID): Document? =
        databaseManager.readTx {
            DocumentsTable
                .selectAll()
                .where { DocumentsTable.id eq id }
                .singleOrNull()
                ?.let(::toDocument)
        }

    override suspend fun list(
        rootItemId: UUID,
        kind: DocumentKind?,
        includeArchived: Boolean
    ): List<DocumentSummary> =
        databaseManager.readTx {
            var condition: Op<Boolean> = DocumentsTable.rootItemId eq rootItemId
            if (kind != null) condition = condition and (DocumentsTable.kind eq kind.toDbValue())
            if (!includeArchived) condition = condition and DocumentsTable.archivedAt.isNull()
            DocumentsTable
                .select(
                    DocumentsTable.id,
                    DocumentsTable.rootItemId,
                    DocumentsTable.kind,
                    DocumentsTable.slug,
                    DocumentsTable.title,
                    DocumentsTable.contentHash,
                    DocumentsTable.version,
                    DocumentsTable.archivedAt,
                    DocumentsTable.createdAt,
                    DocumentsTable.modifiedAt
                ).where { condition }
                .orderBy(DocumentsTable.kind to SortOrder.ASC, DocumentsTable.slug to SortOrder.ASC)
                .map(::toSummary)
        }

    override suspend fun findBySlugs(
        rootItemId: UUID,
        slugs: Collection<String>
    ): List<DocumentSummary> {
        if (slugs.isEmpty()) return emptyList()
        return databaseManager.readTx {
            slugs.distinct().chunked(SQL_IN_CHUNK_SIZE).flatMap { chunk ->
                DocumentsTable
                    .select(
                        DocumentsTable.id,
                        DocumentsTable.rootItemId,
                        DocumentsTable.kind,
                        DocumentsTable.slug,
                        DocumentsTable.title,
                        DocumentsTable.contentHash,
                        DocumentsTable.version,
                        DocumentsTable.archivedAt,
                        DocumentsTable.createdAt,
                        DocumentsTable.modifiedAt
                    ).where { (DocumentsTable.rootItemId eq rootItemId) and (DocumentsTable.slug inList chunk) }
                    .map(::toSummary)
            }
        }
    }

    override suspend fun insert(document: Document): Document =
        databaseManager.writeTx("DocumentStore.insert") {
            DocumentsTable.insert {
                it[id] = document.id
                it[rootItemId] = document.rootItemId
                it[kind] = document.kind.toDbValue()
                it[slug] = document.slug
                it[title] = document.title
                it[body] = document.body
                it[contentHash] = document.contentHash
                it[version] = document.version
                it[archivedAt] = document.archivedAt
                it[createdAt] = document.createdAt
                it[modifiedAt] = document.modifiedAt
            }
            DocumentsTable
                .selectAll()
                .where { DocumentsTable.id eq document.id }
                .single()
                .let(::toDocument)
        }

    override suspend fun updateCurrent(
        id: UUID,
        expectedVersion: Int,
        body: String,
        contentHash: String,
        title: String?,
        now: Instant
    ): Boolean =
        databaseManager.writeTx("DocumentStore.updateCurrent") {
            val updated =
                DocumentsTable.update({ (DocumentsTable.id eq id) and (DocumentsTable.version eq expectedVersion) }) {
                    it[DocumentsTable.body] = body
                    it[DocumentsTable.contentHash] = contentHash
                    it[DocumentsTable.title] = title
                    it[DocumentsTable.version] = expectedVersion + 1
                    it[DocumentsTable.modifiedAt] = now
                }
            updated == 1
        }

    override suspend fun appendVersion(version: DocumentVersion) {
        databaseManager.writeTx("DocumentStore.appendVersion") {
            DocumentVersionsTable.insert {
                it[id] = version.id
                it[documentId] = version.documentId
                it[DocumentVersionsTable.version] = version.version
                it[body] = version.body
                it[contentHash] = version.contentHash
                it[actorId] = version.actorId
                it[createdAt] = version.createdAt
            }
        }
    }

    override suspend fun listVersions(id: UUID): List<DocumentVersion> =
        databaseManager.readTx {
            DocumentVersionsTable
                .selectAll()
                .where { DocumentVersionsTable.documentId eq id }
                .orderBy(DocumentVersionsTable.version, SortOrder.ASC)
                .map(::toVersion)
        }

    override suspend fun getVersion(
        id: UUID,
        version: Int
    ): DocumentVersion? =
        databaseManager.readTx {
            DocumentVersionsTable
                .selectAll()
                .where { (DocumentVersionsTable.documentId eq id) and (DocumentVersionsTable.version eq version) }
                .singleOrNull()
                ?.let(::toVersion)
        }

    override suspend fun setArchived(
        id: UUID,
        at: Instant?
    ): Boolean =
        databaseManager.writeTx("DocumentStore.setArchived") {
            DocumentsTable.update({ DocumentsTable.id eq id }) {
                it[archivedAt] = at
            } == 1
        }

    override suspend fun delete(id: UUID): Boolean =
        databaseManager.writeTx("DocumentStore.delete") {
            // History first and explicitly: the FK cascade only runs on a connection with foreign_keys=ON.
            DocumentVersionsTable.deleteWhere { DocumentVersionsTable.documentId eq id }
            DocumentsTable.deleteWhere { DocumentsTable.id eq id } > 0
        }

    private fun toDocument(row: ResultRow): Document =
        Document(
            id = row[DocumentsTable.id],
            rootItemId = row[DocumentsTable.rootItemId],
            kind = DocumentKind.fromDbValue(row[DocumentsTable.kind]),
            slug = row[DocumentsTable.slug],
            title = row[DocumentsTable.title],
            body = row[DocumentsTable.body],
            contentHash = row[DocumentsTable.contentHash],
            version = row[DocumentsTable.version],
            archivedAt = row[DocumentsTable.archivedAt],
            createdAt = row[DocumentsTable.createdAt],
            modifiedAt = row[DocumentsTable.modifiedAt]
        )

    private fun toSummary(row: ResultRow): DocumentSummary =
        DocumentSummary(
            id = row[DocumentsTable.id],
            rootItemId = row[DocumentsTable.rootItemId],
            kind = DocumentKind.fromDbValue(row[DocumentsTable.kind]),
            slug = row[DocumentsTable.slug],
            title = row[DocumentsTable.title],
            contentHash = row[DocumentsTable.contentHash],
            version = row[DocumentsTable.version],
            archivedAt = row[DocumentsTable.archivedAt],
            createdAt = row[DocumentsTable.createdAt],
            modifiedAt = row[DocumentsTable.modifiedAt]
        )

    private fun toVersion(row: ResultRow): DocumentVersion =
        DocumentVersion(
            id = row[DocumentVersionsTable.id],
            documentId = row[DocumentVersionsTable.documentId],
            version = row[DocumentVersionsTable.version],
            body = row[DocumentVersionsTable.body],
            contentHash = row[DocumentVersionsTable.contentHash],
            actorId = row[DocumentVersionsTable.actorId],
            createdAt = row[DocumentVersionsTable.createdAt]
        )
}
