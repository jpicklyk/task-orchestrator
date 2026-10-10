package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.NoteRef
import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.NotesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of NoteStore.
 */
class SQLiteNoteRepository(
    private val databaseManager: DatabaseManager
) : NoteStore {
    override suspend fun getById(id: UUID): Note? =
        databaseManager.readTx {
            val row = NotesTable.selectAll().where { NotesTable.id eq id }.singleOrNull()
            if (row != null) {
                mapRowToNote(row)
            } else {
                null
            }
        }

    /**
     * Upserts a single [Note] row in [NotesTable] atomically using a single
     * INSERT … ON CONFLICT DO UPDATE statement (via Exposed's dialect-agnostic `upsert()`).
     *
     * **Must be called within an existing transaction** — this function does NOT open its own
     * transaction. Use [upsert] for the public API that wraps this in a transaction.
     *
     * **Concurrency safety:** The prior implementation used SELECT-then-branch-to-UPDATE-or-INSERT,
     * which had a TOCTOU race: two concurrent writers for the same (itemId, key) could both see
     * `existing == null` and both attempt INSERT. The second INSERT would hit the UNIQUE constraint
     * on (work_item_id, key) and its write would be silently dropped. Using a single atomic
     * statement eliminates the race — the DB resolves the conflict in one serialised operation.
     *
     * **Statement shape:** Exposed emits `INSERT … ON CONFLICT(work_item_id, key) DO UPDATE SET …`,
     * a single atomic statement.
     *
     * **Row identity / immutability:** On the conflict (update) path, the `onUpdate` block
     * below enumerates ONLY the mutable columns. The immutable `id` (primary key) and
     * `created_at` columns are deliberately omitted, so the pre-existing row keeps its original
     * id and createdAt — only body/role/modifiedAt/actor/verification change. This preserves
     * the invariant asserted by `upsert preserves original note id and createdAt on update`.
     *
     * **FTS5 sync:** Because the SQLite statement is `INSERT … ON CONFLICT DO UPDATE` (NOT
     * `INSERT OR REPLACE`), the existing row's `rowid` is preserved on conflict. The notes
     * external-content FTS5 tables (`notes_fts_trigram`, `notes_fts_text`) sync via AFTER
     * INSERT/UPDATE/DELETE triggers keyed on `rowid`; an INSERT-OR-REPLACE would delete+reinsert
     * the row with a new rowid and orphan the FTS index, whereas ON CONFLICT DO UPDATE fires the
     * AFTER UPDATE trigger and keeps the FTS rowid stable. The fresh-insert path fires the
     * AFTER INSERT trigger as before.
     *
     * Returns the note with the correct ID (existing ID preserved on conflict, new ID on fresh insert).
     */
    private fun upsertRow(note: Note): Note {
        note.validate()
        val now = Instant.now()

        // Perform a single atomic upsert keyed on (itemId, key) — the UNIQUE index columns.
        //
        // body{}      — INSERT values for the fresh-insert path (all columns, including id/createdAt).
        // onUpdate{}  — DO UPDATE SET clause for the conflict path: ONLY mutable columns.
        //               id, itemId, key, and createdAt are intentionally NOT updated so the
        //               existing row keeps its identity and creation timestamp.
        NotesTable.upsert(
            keys = arrayOf(NotesTable.itemId, NotesTable.key),
            onUpdate = {
                it[NotesTable.role] = note.role
                it[NotesTable.body] = note.body
                it[NotesTable.modifiedAt] = now
                it[NotesTable.actorId] = note.actorClaim?.id
                it[NotesTable.actorKind] = note.actorClaim?.kind?.toJsonString()
                it[NotesTable.actorParent] = note.actorClaim?.parent
                // Actor proofs (JWTs) are no longer persisted — only forensic evidence (hash +
                // verified claims) is. See migration V17__Store_Actor_Proof_Evidence.sql.
                it[NotesTable.actorProof] = null
                it[NotesTable.actorProofSha256] = note.verification?.proofSha256
                it[NotesTable.actorProofClaims] = note.verification?.proofClaims?.toJsonStringOrNull()
                it[NotesTable.verificationStatus] = note.verification?.status?.toJsonString()
                it[NotesTable.verificationVerifier] = note.verification?.verifier
                it[NotesTable.verificationReason] = note.verification?.reason
            },
        ) {
            it[id] = note.id
            it[itemId] = note.itemId
            it[key] = note.key
            it[role] = note.role
            it[body] = note.body
            it[createdAt] = note.createdAt
            it[modifiedAt] = now
            it[NotesTable.actorId] = note.actorClaim?.id
            it[NotesTable.actorKind] = note.actorClaim?.kind?.toJsonString()
            it[NotesTable.actorParent] = note.actorClaim?.parent
            it[NotesTable.actorProof] = null
            it[NotesTable.actorProofSha256] = note.verification?.proofSha256
            it[NotesTable.actorProofClaims] = note.verification?.proofClaims?.toJsonStringOrNull()
            it[NotesTable.verificationStatus] = note.verification?.status?.toJsonString()
            it[NotesTable.verificationVerifier] = note.verification?.verifier
            it[NotesTable.verificationReason] = note.verification?.reason
        }

        // Read back the canonical row to obtain the actual ID (preserved from the pre-existing
        // row on conflict, or the note.id we just inserted on a fresh row) and the canonical
        // createdAt (unchanged on conflict).
        val row =
            NotesTable
                .selectAll()
                .where { (NotesTable.itemId eq note.itemId) and (NotesTable.key eq note.key) }
                .singleOrNull()
                ?: error("Note not found after upsert: (${note.itemId}, ${note.key})")

        return mapRowToNote(row)
    }

    override suspend fun upsert(note: Note): Note =
        databaseManager.writeTx("NoteStore.upsert") {
            upsertRow(note)
        }

    override suspend fun delete(id: UUID): Boolean =
        databaseManager.writeTx("NoteStore.delete") {
            val deletedCount = NotesTable.deleteWhere { NotesTable.id eq id }
            deletedCount > 0
        }

    override suspend fun deleteByItemId(itemId: UUID): Int =
        databaseManager.writeTx("NoteStore.deleteByItemId") {
            val deletedCount = NotesTable.deleteWhere { NotesTable.itemId eq itemId }
            deletedCount
        }

    override suspend fun findByItemId(
        itemId: UUID,
        role: String?
    ): List<Note> =
        databaseManager.readTx {
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
            notes
        }

    override suspend fun findByItemIds(itemIds: Set<UUID>): Map<UUID, List<Note>> {
        if (itemIds.isEmpty()) return emptyMap()
        return databaseManager.readTx {
            val notes =
                NotesTable
                    .selectAll()
                    .where { NotesTable.itemId inList itemIds }
                    .map { mapRowToNote(it) }
            notes.groupBy { it.itemId }
        }
    }

    override suspend fun findRefsByItemIds(itemIds: Set<UUID>): Map<UUID, List<NoteRef>> {
        if (itemIds.isEmpty()) return emptyMap()
        return databaseManager.readTx {
            NotesTable
                .select(NotesTable.id, NotesTable.itemId, NotesTable.key, NotesTable.role)
                .where { NotesTable.itemId inList itemIds }
                .map { NoteRef(it[NotesTable.id].value, it[NotesTable.itemId], it[NotesTable.key], it[NotesTable.role]) }
                .groupBy { it.itemId }
        }
    }

    override suspend fun findByItemIdAndKey(
        itemId: UUID,
        key: String
    ): Note? =
        databaseManager.readTx {
            val row =
                NotesTable
                    .selectAll()
                    .where { (NotesTable.itemId eq itemId) and (NotesTable.key eq key) }
                    .singleOrNull()
            row?.let { mapRowToNote(it) }
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
                        // Never surface a legacy/unscrubbed raw proof — defense in depth alongside
                        // the V17 scrub. actor_proof is written NULL on every insert/update path.
                        proof = null
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
                        reason = row[NotesTable.verificationReason],
                        proofSha256 = row[NotesTable.actorProofSha256],
                        proofClaims = parseProofClaimsOrNull(row[NotesTable.actorProofClaims], logger, "Note $noteId")
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

    companion object {
        private val logger = LoggerFactory.getLogger(SQLiteNoteRepository::class.java)
    }
}
