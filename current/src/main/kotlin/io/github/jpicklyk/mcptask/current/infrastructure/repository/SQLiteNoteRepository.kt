package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.NotesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of NoteRepository.
 */
class SQLiteNoteRepository(
    private val databaseManager: DatabaseManager
) : NoteRepository {
    override suspend fun getById(id: UUID): Result<Note> =
        databaseManager.suspendedTransaction("Failed to get Note by id") {
            val row = NotesTable.selectAll().where { NotesTable.id eq id }.singleOrNull()
            if (row != null) {
                Result.Success(mapRowToNote(row))
            } else {
                Result.Error(RepositoryError.NotFound(id, "Note not found with id: $id"))
            }
        }

    /**
     * Upserts a single [Note] row in [NotesTable] using select-then-update-or-insert logic.
     *
     * **Must be called within an existing transaction** — this function does NOT open its own
     * transaction. Use [upsert] for the public API that wraps this in a transaction.
     *
     * Returns the note with the correct ID and timestamps (existing ID on update, original on insert).
     */
    internal fun upsertRow(note: Note): Result<Note> {
        note.validate()
        // Check if a note with the same (itemId, key) already exists
        val existing =
            NotesTable
                .selectAll()
                .where { (NotesTable.itemId eq note.itemId) and (NotesTable.key eq note.key) }
                .singleOrNull()

        return if (existing != null) {
            // Update existing note (last-writer-wins for actor attribution)
            val existingId = existing[NotesTable.id].value
            val now = Instant.now()
            NotesTable.update({ NotesTable.id eq existingId }) {
                it[body] = note.body
                it[role] = note.role
                it[modifiedAt] = now
                it[NotesTable.actorId] = note.actorClaim?.id
                it[NotesTable.actorKind] = note.actorClaim?.kind?.toJsonString()
                it[NotesTable.actorParent] = note.actorClaim?.parent
                it[NotesTable.actorProof] = note.actorClaim?.proof
                it[NotesTable.verificationStatus] = note.verification?.status?.toJsonString()
                it[NotesTable.verificationVerifier] = note.verification?.verifier
                it[NotesTable.verificationReason] = note.verification?.reason
            }
            // Return the updated note with the existing ID and updated timestamp
            Result.Success(note.copy(id = existingId, modifiedAt = now))
        } else {
            // Insert new note
            NotesTable.insert {
                it[id] = note.id
                it[itemId] = note.itemId
                it[key] = note.key
                it[role] = note.role
                it[body] = note.body
                it[createdAt] = note.createdAt
                it[modifiedAt] = note.modifiedAt
                it[NotesTable.actorId] = note.actorClaim?.id
                it[NotesTable.actorKind] = note.actorClaim?.kind?.toJsonString()
                it[NotesTable.actorParent] = note.actorClaim?.parent
                it[NotesTable.actorProof] = note.actorClaim?.proof
                it[NotesTable.verificationStatus] = note.verification?.status?.toJsonString()
                it[NotesTable.verificationVerifier] = note.verification?.verifier
                it[NotesTable.verificationReason] = note.verification?.reason
            }
            Result.Success(note)
        }
    }

    override suspend fun upsert(note: Note): Result<Note> =
        databaseManager.suspendedTransaction("Failed to upsert Note") {
            upsertRow(note)
        }

    override suspend fun delete(id: UUID): Result<Boolean> =
        databaseManager.suspendedTransaction("Failed to delete Note") {
            val deletedCount = NotesTable.deleteWhere { NotesTable.id eq id }
            Result.Success(deletedCount > 0)
        }

    override suspend fun deleteByItemId(itemId: UUID): Result<Int> =
        databaseManager.suspendedTransaction("Failed to delete Notes by itemId") {
            val deletedCount = NotesTable.deleteWhere { NotesTable.itemId eq itemId }
            Result.Success(deletedCount)
        }

    override suspend fun findByItemId(
        itemId: UUID,
        role: String?
    ): Result<List<Note>> =
        databaseManager.suspendedTransaction("Failed to find Notes by itemId") {
            val notes =
                if (role != null) {
                    NotesTable
                        .selectAll()
                        .where { (NotesTable.itemId eq itemId) and (NotesTable.role eq role) }
                } else {
                    NotesTable
                        .selectAll()
                        .where { NotesTable.itemId eq itemId }
                }.map { mapRowToNote(it) }
            Result.Success(notes)
        }

    override suspend fun findByItemIds(itemIds: Set<UUID>): Result<Map<UUID, List<Note>>> {
        if (itemIds.isEmpty()) return Result.Success(emptyMap())
        return databaseManager.suspendedTransaction("Failed to find Notes by itemIds") {
            val notes =
                NotesTable
                    .selectAll()
                    .where { NotesTable.itemId inList itemIds }
                    .map { mapRowToNote(it) }
            Result.Success(notes.groupBy { it.itemId })
        }
    }

    override suspend fun findByItemIdAndKey(
        itemId: UUID,
        key: String
    ): Result<Note?> =
        databaseManager.suspendedTransaction("Failed to find Note by itemId and key") {
            val row =
                NotesTable
                    .selectAll()
                    .where { (NotesTable.itemId eq itemId) and (NotesTable.key eq key) }
                    .singleOrNull()
            Result.Success(row?.let { mapRowToNote(it) })
        }

    private fun mapRowToNote(row: ResultRow): Note {
        val noteId = row[NotesTable.id].value
        val actorClaim =
            row[NotesTable.actorId]?.let { actorId ->
                val kindStr = row[NotesTable.actorKind]
                if (kindStr == null) {
                    logger.warn("Note {}: actorId present but actorKind is null; skipping actor", noteId)
                    return@let null
                }
                try {
                    ActorClaim(
                        id = actorId,
                        kind = ActorKind.fromString(kindStr),
                        parent = row[NotesTable.actorParent],
                        proof = row[NotesTable.actorProof]
                    )
                } catch (e: IllegalArgumentException) {
                    logger.warn("Note {}: invalid actorKind '{}'; skipping actor", noteId, kindStr)
                    null
                }
            }
        val verification =
            row[NotesTable.verificationStatus]?.let { status ->
                try {
                    VerificationResult(
                        status = VerificationStatus.fromString(status),
                        verifier = row[NotesTable.verificationVerifier],
                        reason = row[NotesTable.verificationReason]
                    )
                } catch (e: IllegalArgumentException) {
                    logger.warn("Note {}: invalid verificationStatus '{}'; skipping verification", noteId, status)
                    null
                }
            }
        return Note(
            id = noteId,
            itemId = row[NotesTable.itemId],
            key = row[NotesTable.key],
            role = row[NotesTable.role],
            body = row[NotesTable.body],
            createdAt = row[NotesTable.createdAt],
            modifiedAt = row[NotesTable.modifiedAt],
            actorClaim = actorClaim,
            verification = verification
        )
    }

    // -----------------------------------------------------------------------
    // FTS5 full-text search
    // -----------------------------------------------------------------------

    /**
     * Full-text search on note bodies using the V7 FTS5 virtual tables.
     *
     * **H2 (test environment):** FTS5 is SQLite-only. When the current dialect is H2,
     * this method returns an empty [SearchResult] immediately.
     *
     * @param sanitizedFtsQuery FTS5 query string, already sanitized by the caller (T5 — QueryNotesTool).
     * @param matchMode Which FTS table(s) to query.
     * @param scope     Optional structural scope filters. [SearchScope.itemId] narrows to notes on
     *   that specific item; [SearchScope.ancestorId] narrows to notes whose item_id is in the subtree.
     * @param limit     Maximum hits to return (enforced at 100; default 20).
     * @param offset    Zero-based page offset.
     */
    suspend fun ftsSearch(
        sanitizedFtsQuery: String,
        matchMode: SearchMatchMode = SearchMatchMode.AUTO,
        scope: SearchScope? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): SearchResult {
        val effectiveLimit = limit.coerceIn(1, 100)

        return try {
            newSuspendedTransaction(db = databaseManager.getDatabase()) {
                // currentDialect is only accessible within an active transaction.
                // FTS5 is SQLite-only — return empty for H2 (test environment).
                if (currentDialect is H2Dialect) {
                    return@newSuspendedTransaction SearchResult(hits = emptyList(), totalHits = 0, nextOffset = null)
                }
                val uuidType = UUIDColumnType()
                val varcharType = VarCharColumnType(4000)

                // Inline RRF helper — T4 will extract this to RrfFusion.kt.
                fun rrfScore(
                    rank: Double,
                    k: Double = 60.0
                ): Double = 1.0 / (k + rank)

                // Build optional subtree CTE (filters notes to those in the subtree under ancestorId).
                val subtreeCteClause =
                    if (scope?.ancestorId != null) {
                        """
                        WITH RECURSIVE subtree(id) AS (
                            SELECT id FROM work_items WHERE id = ?
                            UNION ALL
                            SELECT wi.id FROM work_items wi JOIN subtree s ON wi.parent_id = s.id
                        )
                        """.trimIndent()
                    } else {
                        ""
                    }

                // Build additional WHERE fragments for scope filters.
                val extraWhereParts = mutableListOf<String>()
                if (scope?.itemId != null) extraWhereParts.add("n.work_item_id = ?")
                if (scope?.ancestorId != null) extraWhereParts.add("n.work_item_id IN subtree")
                // role filter on notes (not work_items.role — notes themselves have a role column)
                // scope.role maps to the note's role field.
                val extraWhere = if (extraWhereParts.isEmpty()) "" else " AND " + extraWhereParts.joinToString(" AND ")

                // Build positional args for one FTS query.
                fun buildArgs(): List<Pair<org.jetbrains.exposed.v1.core.ColumnType<*>, Any?>> {
                    val args = mutableListOf<Pair<org.jetbrains.exposed.v1.core.ColumnType<*>, Any?>>()
                    if (scope?.ancestorId != null) args.add(uuidType to scope.ancestorId)
                    args.add(varcharType to sanitizedFtsQuery) // FTS MATCH param
                    if (scope?.itemId != null) args.add(uuidType to scope.itemId)
                    return args
                }

                data class NoteHit(
                    val rowid: Long,
                    val rank: Double,
                    val snippet: String,
                    val matchedTable: String,
                    val noteId: java.util.UUID,
                    val itemId: java.util.UUID,
                    val noteKey: String,
                )

                val trigramHits = mutableMapOf<Long, NoteHit>()
                val textHits = mutableMapOf<Long, NoteHit>()

                fun runFtsQuery(
                    ftsTable: String,
                    hitMap: MutableMap<Long, NoteHit>,
                    tableName: String,
                ) {
                    val sql =
                        """
                        ${subtreeCteClause.ifEmpty { "" }}
                        SELECT
                            ft.rowid,
                            ft.rank,
                            snippet($ftsTable, 0, '<mark>', '</mark>', '…', 32) AS snip,
                            n.id AS note_id,
                            n.work_item_id AS item_id,
                            n.key AS note_key
                        FROM $ftsTable ft
                        JOIN notes n ON n.rowid = ft.rowid
                        WHERE ft MATCH ?$extraWhere
                        ORDER BY ft.rank
                        LIMIT ${effectiveLimit + offset + 1}
                        """.trimIndent()

                    exec(sql, args = buildArgs()) { rs ->
                        while (rs.next()) {
                            val rowid = rs.getLong("rowid")
                            val rank = rs.getDouble("rank")
                            val snippet = rs.getString("snip") ?: ""
                            val rawNoteId = rs.getObject("note_id")
                            val rawItemId = rs.getObject("item_id")

                            @Suppress("UNCHECKED_CAST")
                            val noteId = uuidType.valueFromDB(rawNoteId!!) as java.util.UUID

                            @Suppress("UNCHECKED_CAST")
                            val itemId = uuidType.valueFromDB(rawItemId!!) as java.util.UUID
                            val noteKey = rs.getString("note_key") ?: ""
                            hitMap[rowid] = NoteHit(rowid, rank, snippet, tableName, noteId, itemId, noteKey)
                        }
                    }
                }

                when (matchMode) {
                    SearchMatchMode.SUBSTRING -> runFtsQuery("notes_fts_trigram", trigramHits, "trigram")
                    SearchMatchMode.TEXT -> runFtsQuery("notes_fts_text", textHits, "text")
                    SearchMatchMode.AUTO -> {
                        runFtsQuery("notes_fts_trigram", trigramHits, "trigram")
                        runFtsQuery("notes_fts_text", textHits, "text")
                    }
                }

                val allRowIds = (trigramHits.keys + textHits.keys).toSet()
                if (allRowIds.isEmpty()) {
                    return@newSuspendedTransaction SearchResult(
                        hits = emptyList(),
                        totalHits = 0,
                        nextOffset = null,
                    )
                }

                // RRF fusion: assign row numbers then compute fused score.
                data class FusedDoc(
                    val rowid: Long,
                    val noteId: java.util.UUID,
                    val itemId: java.util.UUID,
                    val noteKey: String,
                    val trigramRank: Double?,
                    val textRank: Double?,
                    var trigramRowNum: Int = Int.MAX_VALUE,
                    var textRowNum: Int = Int.MAX_VALUE,
                )

                val docs = mutableMapOf<Long, FusedDoc>()
                for (rowid in allRowIds) {
                    val hit = trigramHits[rowid] ?: textHits[rowid]!!
                    docs[rowid] =
                        FusedDoc(
                            rowid = rowid,
                            noteId = hit.noteId,
                            itemId = hit.itemId,
                            noteKey = hit.noteKey,
                            trigramRank = trigramHits[rowid]?.rank,
                            textRank = textHits[rowid]?.rank,
                        )
                }

                trigramHits.entries
                    .sortedBy { it.value.rank }
                    .forEachIndexed { idx, (rowid, _) -> docs[rowid]?.trigramRowNum = idx + 1 }
                textHits.entries
                    .sortedBy { it.value.rank }
                    .forEachIndexed { idx, (rowid, _) -> docs[rowid]?.textRowNum = idx + 1 }

                val ranked =
                    docs.values
                        .map { doc ->
                            val score =
                                (if (doc.trigramRowNum < Int.MAX_VALUE) rrfScore(doc.trigramRowNum.toDouble()) else 0.0) +
                                    (if (doc.textRowNum < Int.MAX_VALUE) rrfScore(doc.textRowNum.toDouble()) else 0.0)
                            doc to score
                        }.sortedByDescending { it.second }

                val totalHits = ranked.size
                val pageSlice = ranked.drop(offset).take(effectiveLimit)

                val hits =
                    pageSlice.map { (doc, score) ->
                        val trigramHit = trigramHits[doc.rowid]
                        val textHit = textHits[doc.rowid]
                        val snippet =
                            when {
                                trigramHit != null && textHit != null ->
                                    if ((trigramHit.rank) <= (textHit.rank)) trigramHit.snippet else textHit.snippet
                                trigramHit != null -> trigramHit.snippet
                                else -> textHit!!.snippet
                            }

                        val matchedIn = mutableListOf<String>()
                        if (trigramHit != null) matchedIn.add("trigram")
                        if (textHit != null) matchedIn.add("text")

                        SearchHit(
                            kind = "note",
                            itemId = doc.itemId,
                            noteKey = doc.noteKey,
                            field = "body",
                            snippet = snippet,
                            score = score,
                            matchedIn = matchedIn,
                            trigramRank = doc.trigramRank,
                            textRank = doc.textRank,
                        )
                    }

                val nextOffset =
                    if (offset + effectiveLimit < totalHits) offset + effectiveLimit else null

                SearchResult(
                    hits = hits,
                    totalHits = totalHits,
                    nextOffset = nextOffset,
                    truncated = totalHits > 100,
                )
            }
        } catch (e: Exception) {
            logger.error("FTS5 note search failed: ${e.message}", e)
            SearchResult(hits = emptyList(), totalHits = 0, nextOffset = null)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SQLiteNoteRepository::class.java)
    }
}
