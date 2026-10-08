package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentAdoptOutcome
import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentStashOutcome
import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentStore
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocument
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentSummary
import io.github.jpicklyk.mcptask.current.infrastructure.security.sha256Hex
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.PlanDocumentsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of [PlanDocumentStore], backed by [PlanDocumentsTable].
 *
 * [stash] uses the same read-inside-transaction-then-upsert pattern as
 * [SQLiteProjectConfigRepository.upsert]: the ADOPTED-conflict guard needs the row's prior status,
 * which is read first inside the same transaction as the upsert itself. This table is
 * low-write-frequency (plan stashes, not a hot path), so the extra read is not a meaningful
 * bottleneck or TOCTOU window in practice — the whole read+write happens under one transaction.
 */
class SQLitePlanDocumentRepository(
    private val databaseManager: DatabaseManager
) : PlanDocumentStore {
    override suspend fun stash(
        rootItemId: UUID,
        slug: String,
        body: String
    ): PlanDocumentStashOutcome =
        databaseManager.writeTx("PlanDocumentStore.stash") {
            val existing =
                PlanDocumentsTable
                    .selectAll()
                    .where { (PlanDocumentsTable.rootItemId eq rootItemId) and (PlanDocumentsTable.slug eq slug) }
                    .singleOrNull()

            if (existing != null && existing[PlanDocumentsTable.status] == PlanDocumentStatus.ADOPTED.toDbValue()) {
                return@writeTx PlanDocumentStashOutcome.AdoptedConflict(mapRowToPlanDocument(existing))
            }

            val contentHash = computeContentHash(body)
            val now = Instant.now()

            PlanDocumentsTable.upsert(
                keys = arrayOf(PlanDocumentsTable.rootItemId, PlanDocumentsTable.slug),
                onUpdate = {
                    it[PlanDocumentsTable.body] = body
                    it[PlanDocumentsTable.contentHash] = contentHash
                    it[PlanDocumentsTable.modifiedAt] = now
                    // status/adoptedByItemId are left untouched on update — the guard above already
                    // guarantees this path only runs when the existing row (if any) is PENDING.
                },
            ) {
                it[PlanDocumentsTable.rootItemId] = rootItemId
                it[PlanDocumentsTable.slug] = slug
                it[PlanDocumentsTable.body] = body
                it[PlanDocumentsTable.contentHash] = contentHash
                it[PlanDocumentsTable.status] = PlanDocumentStatus.PENDING.toDbValue()
                it[PlanDocumentsTable.adoptedByItemId] = null
                it[PlanDocumentsTable.createdAt] = now
                it[PlanDocumentsTable.modifiedAt] = now
            }

            val stored =
                PlanDocumentsTable
                    .selectAll()
                    .where { (PlanDocumentsTable.rootItemId eq rootItemId) and (PlanDocumentsTable.slug eq slug) }
                    .single()

            PlanDocumentStashOutcome.Stored(mapRowToPlanDocument(stored))
        }

    override suspend fun get(
        rootItemId: UUID,
        slug: String
    ): PlanDocument? =
        databaseManager.readTx {
            val row =
                PlanDocumentsTable
                    .selectAll()
                    .where { (PlanDocumentsTable.rootItemId eq rootItemId) and (PlanDocumentsTable.slug eq slug) }
                    .singleOrNull()
            row?.let { mapRowToPlanDocument(it) }
        }

    override suspend fun list(
        rootItemId: UUID,
        status: PlanDocumentStatus?
    ): List<PlanDocumentSummary> =
        databaseManager.readTx {
            var query =
                PlanDocumentsTable
                    .select(
                        PlanDocumentsTable.id,
                        PlanDocumentsTable.rootItemId,
                        PlanDocumentsTable.slug,
                        PlanDocumentsTable.contentHash,
                        PlanDocumentsTable.status,
                        PlanDocumentsTable.adoptedByItemId,
                        PlanDocumentsTable.createdAt,
                        PlanDocumentsTable.modifiedAt,
                    ).where { PlanDocumentsTable.rootItemId eq rootItemId }
            if (status != null) {
                query = query.andWhere { PlanDocumentsTable.status eq status.toDbValue() }
            }
            val rows = query.orderBy(PlanDocumentsTable.slug, SortOrder.ASC).toList()
            rows.map { mapRowToPlanDocumentSummary(it) }
        }

    override suspend fun markAdopted(
        rootItemId: UUID,
        slug: String,
        adoptedByItemId: UUID
    ): PlanDocumentAdoptOutcome =
        databaseManager.writeTx("PlanDocumentStore.markAdopted") {
            markAdoptedRow(rootItemId, slug, adoptedByItemId)
        }

    /**
     * Raw (no own transaction) counterpart to [markAdopted], for callers already inside an open
     * transaction — specifically
     * [io.github.jpicklyk.mcptask.current.infrastructure.sqlite.service.SQLiteWorkTreeService], which must
     * mark a plan document ADOPTED in the SAME transaction as `create_work_tree`'s item/dependency/
     * note inserts. Doing so means a race (the document being adopted by a concurrent call between
     * the tool's pre-check read and this call) is caught here and rolls back the WHOLE tree — via the
     * caller throwing on a non-[PlanDocumentAdoptOutcome.Adopted] result — rather than silently
     * double-materializing the same document.
     *
     * **Must be called within an existing transaction** — this function does NOT open its own.
     */
    internal fun markAdoptedRow(
        rootItemId: UUID,
        slug: String,
        adoptedByItemId: UUID
    ): PlanDocumentAdoptOutcome {
        val existing =
            PlanDocumentsTable
                .selectAll()
                .where { (PlanDocumentsTable.rootItemId eq rootItemId) and (PlanDocumentsTable.slug eq slug) }
                .singleOrNull()
                ?: return PlanDocumentAdoptOutcome.NotFound

        if (existing[PlanDocumentsTable.status] == PlanDocumentStatus.ADOPTED.toDbValue()) {
            return PlanDocumentAdoptOutcome.AlreadyAdopted(mapRowToPlanDocument(existing))
        }

        val now = Instant.now()
        PlanDocumentsTable.update({ (PlanDocumentsTable.rootItemId eq rootItemId) and (PlanDocumentsTable.slug eq slug) }) {
            it[PlanDocumentsTable.status] = PlanDocumentStatus.ADOPTED.toDbValue()
            it[PlanDocumentsTable.adoptedByItemId] = adoptedByItemId
            it[PlanDocumentsTable.modifiedAt] = now
        }

        val updated =
            PlanDocumentsTable
                .selectAll()
                .where { (PlanDocumentsTable.rootItemId eq rootItemId) and (PlanDocumentsTable.slug eq slug) }
                .single()
        return PlanDocumentAdoptOutcome.Adopted(mapRowToPlanDocument(updated))
    }

    override fun computeContentHash(body: String): String = sha256Hex(body.toByteArray(Charsets.UTF_8))

    private fun mapRowToPlanDocument(row: ResultRow): PlanDocument =
        PlanDocument(
            id = row[PlanDocumentsTable.id].value,
            rootItemId = row[PlanDocumentsTable.rootItemId],
            slug = row[PlanDocumentsTable.slug],
            body = row[PlanDocumentsTable.body],
            contentHash = row[PlanDocumentsTable.contentHash],
            status = PlanDocumentStatus.fromDbValue(row[PlanDocumentsTable.status]),
            adoptedByItemId = row[PlanDocumentsTable.adoptedByItemId],
            createdAt = row[PlanDocumentsTable.createdAt],
            modifiedAt = row[PlanDocumentsTable.modifiedAt]
        )

    private fun mapRowToPlanDocumentSummary(row: ResultRow): PlanDocumentSummary =
        PlanDocumentSummary(
            id = row[PlanDocumentsTable.id].value,
            rootItemId = row[PlanDocumentsTable.rootItemId],
            slug = row[PlanDocumentsTable.slug],
            contentHash = row[PlanDocumentsTable.contentHash],
            status = PlanDocumentStatus.fromDbValue(row[PlanDocumentsTable.status]),
            adoptedByItemId = row[PlanDocumentsTable.adoptedByItemId],
            createdAt = row[PlanDocumentsTable.createdAt],
            modifiedAt = row[PlanDocumentsTable.modifiedAt]
        )
}
