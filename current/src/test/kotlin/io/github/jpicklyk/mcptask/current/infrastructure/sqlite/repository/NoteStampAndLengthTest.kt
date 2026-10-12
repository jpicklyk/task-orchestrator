package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.inUnit
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Q32 carry-in q11 (task-scope G4), scenarios S19 and S20: a note's stamps come from the unit's clock (not the domain
 * object's wall-clock default), and the store-agnostic `bodyLength` counts code points as SQLite's `LENGTH` does.
 */
class NoteStampAndLengthTest {
    private val clock = SettableClock(Instant.parse("2026-05-01T08:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private fun item(): WorkItem = runBlocking { db.repositoryProvider().workItemRepository().create(WorkItem(title = "Note owner")) }

    @Test
    fun `S19 createdAt and modifiedAt equal the unit clock on create, and modifiedAt follows it on update`(): Unit =
        runBlocking {
            val owner = item()
            val notes = db.repositoryProvider().noteRepository()
            // The domain default would be the wall clock (2026-10 or later), never the settable clock's May instant.
            lateinit var created: Note
            db.unitOfWork().inUnit { created = notes.upsert(Note(itemId = owner.id, key = "spec", role = "queue", body = "v1")) }
            assertEquals(Instant.parse("2026-05-01T08:00:00Z"), created.createdAt)
            assertEquals(Instant.parse("2026-05-01T08:00:00Z"), created.modifiedAt)

            clock.advanceSeconds(90)
            lateinit var updated: Note
            db.unitOfWork().inUnit { updated = notes.upsert(Note(itemId = owner.id, key = "spec", role = "queue", body = "v2")) }
            assertEquals(created.createdAt, updated.createdAt, "an update keeps the creation stamp")
            assertEquals(Instant.parse("2026-05-01T08:01:30Z"), updated.modifiedAt, "an update is stamped by the unit clock")
        }

    /** A [NoteStore] that forwards every member to [delegate] except the summary read, which keeps the interface default. */
    private class DefaultSummaries(
        private val delegate: NoteStore
    ) : NoteStore {
        override suspend fun getById(id: UUID) = delegate.getById(id)

        override suspend fun upsert(note: Note) = delegate.upsert(note)

        override suspend fun delete(id: UUID) = delegate.delete(id)

        override suspend fun deleteByItemId(itemId: UUID) = delegate.deleteByItemId(itemId)

        override suspend fun findByItemId(
            itemId: UUID,
            role: String?
        ) = delegate.findByItemId(itemId, role)

        override suspend fun findByItemIdAndKey(
            itemId: UUID,
            key: String
        ) = delegate.findByItemIdAndKey(itemId, key)

        override suspend fun findByItemIds(itemIds: Set<UUID>) = delegate.findByItemIds(itemIds)
    }

    @Test
    fun `S20 the default bodyLength of a supplementary-character body equals SQLite LENGTH`(): Unit =
        runBlocking {
            val owner = item()
            val sqlite = db.repositoryProvider().noteRepository()
            // "a", U+1F600 (a surrogate pair in UTF-16), "b": 3 code points, 4 UTF-16 chars.
            val body = "a😀b"
            db.unitOfWork().inUnit { sqlite.upsert(Note(itemId = owner.id, key = "emoji", role = "work", body = body)) }

            val fromSql = sqlite.findSummariesByItemId(owner.id).single().bodyLength
            val fromDefault = DefaultSummaries(sqlite).findSummariesByItemId(owner.id).single().bodyLength
            assertEquals(3, fromSql, "control: SQLite LENGTH counts code points")
            assertEquals(fromSql, fromDefault)
        }
}
