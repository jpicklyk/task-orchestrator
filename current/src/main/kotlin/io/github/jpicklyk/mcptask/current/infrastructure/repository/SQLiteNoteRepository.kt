package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.NotesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.inList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
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
            // Update existing note
            val existingId = existing[NotesTable.id].value
            val now = Instant.now()
            NotesTable.update({ NotesTable.id eq existingId }) {
                it[body] = note.body
                it[role] = note.role
                it[modifiedAt] = now
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

    private fun mapRowToNote(row: ResultRow): Note =
        Note(
            id = row[NotesTable.id].value,
            itemId = row[NotesTable.itemId],
            key = row[NotesTable.key],
            role = row[NotesTable.role],
            body = row[NotesTable.body],
            createdAt = row[NotesTable.createdAt],
            modifiedAt = row[NotesTable.modifiedAt]
        )
}
