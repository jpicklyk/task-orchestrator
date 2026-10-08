package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteNoteRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SQLiteNoteRepositoryTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var noteRepository: SQLiteNoteRepository
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var testItemId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            database = sqliteDb.database
            databaseManager = sqliteDb.databaseManager
            noteRepository = SQLiteNoteRepository(databaseManager)
            workItemRepository = SQLiteWorkItemRepository(databaseManager)

            // Create a work item for foreign key references
            val item = WorkItem(title = "Test item")
            workItemRepository.create(item)
            testItemId = item.id
        }

    // --- Upsert creates new note ---

    @Test
    fun `upsert creates new note`() =
        runBlocking {
            val note = Note(itemId = testItemId, key = "requirements", role = "queue", body = "Must do X")
            val result = noteRepository.upsert(note)
            assertNotNull(result)
            assertEquals("requirements", result.key)
            assertEquals("queue", result.role)
            assertEquals("Must do X", result.body)
        }

    // --- Upsert updates existing note ---

    @Test
    fun `upsert updates existing note with same itemId and key`() =
        runBlocking {
            val note1 = Note(itemId = testItemId, key = "requirements", role = "queue", body = "Original")
            noteRepository.upsert(note1)

            val note2 = Note(itemId = testItemId, key = "requirements", role = "work", body = "Updated")
            val result = noteRepository.upsert(note2)
            assertNotNull(result)
            assertEquals("Updated", result.body)
            assertEquals("work", result.role)

            // Verify only one note exists with this key
            val findResult = noteRepository.findByItemId(testItemId)
            assertNotNull(findResult)
            assertEquals(1, findResult.size)
        }

    // --- getById ---

    @Test
    fun `getById returns note`() =
        runBlocking {
            val note = Note(itemId = testItemId, key = "test-key", role = "queue")
            noteRepository.upsert(note)

            val result = noteRepository.getById(note.id)
            assertNotNull(result)
            assertEquals(note.id, result.id)
            assertEquals("test-key", result.key)
        }

    @Test
    fun `getById returns NotFound for non-existent`() =
        runBlocking {
            val result = noteRepository.getById(UUID.randomUUID())
            assertNull(result)
        }

    // --- delete ---

    @Test
    fun `delete removes note`() =
        runBlocking {
            val note = Note(itemId = testItemId, key = "to-delete", role = "queue")
            noteRepository.upsert(note)

            val deleteResult = noteRepository.delete(note.id)
            assertNotNull(deleteResult)
            assertTrue(deleteResult)

            val getResult = noteRepository.getById(note.id)
            assertNull(getResult)
        }

    @Test
    fun `delete non-existent note returns false`() =
        runBlocking {
            val result = noteRepository.delete(UUID.randomUUID())
            assertNotNull(result)
            assertEquals(false, result)
        }

    // --- deleteByItemId ---

    @Test
    fun `deleteByItemId removes all notes for item`() =
        runBlocking {
            noteRepository.upsert(Note(itemId = testItemId, key = "note-1", role = "queue"))
            noteRepository.upsert(Note(itemId = testItemId, key = "note-2", role = "work"))
            noteRepository.upsert(Note(itemId = testItemId, key = "note-3", role = "review"))

            val result = noteRepository.deleteByItemId(testItemId)
            assertNotNull(result)
            assertEquals(3, result)

            val findResult = noteRepository.findByItemId(testItemId)
            assertNotNull(findResult)
            assertTrue(findResult.isEmpty())
        }

    // --- findByItemId ---

    @Test
    fun `findByItemId returns all notes`() =
        runBlocking {
            noteRepository.upsert(Note(itemId = testItemId, key = "note-a", role = "queue", body = "A"))
            noteRepository.upsert(Note(itemId = testItemId, key = "note-b", role = "work", body = "B"))

            val result = noteRepository.findByItemId(testItemId)
            assertNotNull(result)
            assertEquals(2, result.size)
        }

    @Test
    fun `findByItemId with role filter`() =
        runBlocking {
            noteRepository.upsert(Note(itemId = testItemId, key = "queue-note", role = "queue"))
            noteRepository.upsert(Note(itemId = testItemId, key = "work-note", role = "work"))
            noteRepository.upsert(Note(itemId = testItemId, key = "review-note", role = "review"))

            val result = noteRepository.findByItemId(testItemId, role = "work")
            assertNotNull(result)
            assertEquals(1, result.size)
            assertEquals("work", result[0].role)
            assertEquals("work-note", result[0].key)
        }

    @Test
    fun `findByItemId returns empty for different item`() =
        runBlocking {
            noteRepository.upsert(Note(itemId = testItemId, key = "note", role = "queue"))

            val result = noteRepository.findByItemId(UUID.randomUUID())
            assertNotNull(result)
            assertTrue(result.isEmpty())
        }

    // --- findByItemIdAndKey ---

    @Test
    fun `findByItemIdAndKey returns specific note`() =
        runBlocking {
            noteRepository.upsert(Note(itemId = testItemId, key = "specific-key", role = "queue", body = "Found me"))

            val result = noteRepository.findByItemIdAndKey(testItemId, "specific-key")
            assertNotNull(result)
            assertNotNull(result)
            assertEquals("Found me", result.body)
            assertEquals("specific-key", result.key)
        }

    @Test
    fun `findByItemIdAndKey returns null for non-existent key`() =
        runBlocking {
            noteRepository.upsert(Note(itemId = testItemId, key = "existing-key", role = "queue"))

            val result = noteRepository.findByItemIdAndKey(testItemId, "non-existent-key")
            assertNull(result)
        }

    @Test
    fun `findByItemIdAndKey returns null for wrong itemId`() =
        runBlocking {
            noteRepository.upsert(Note(itemId = testItemId, key = "my-key", role = "queue"))

            val result = noteRepository.findByItemIdAndKey(UUID.randomUUID(), "my-key")
            assertNull(result)
        }

    // --- findByItemIds ---

    @Test
    fun `findByItemIds returns notes grouped by item ID`() =
        runBlocking {
            // Create a second work item
            val item2 = WorkItem(title = "Second item")
            workItemRepository.create(item2)
            val item2Id = item2.id

            // Create notes for both items
            noteRepository.upsert(Note(itemId = testItemId, key = "note-a", role = "queue", body = "A"))
            noteRepository.upsert(Note(itemId = testItemId, key = "note-b", role = "work", body = "B"))
            noteRepository.upsert(Note(itemId = item2Id, key = "note-c", role = "queue", body = "C"))

            val result = noteRepository.findByItemIds(setOf(testItemId, item2Id))
            assertNotNull(result)

            val grouped = result
            assertEquals(2, grouped.size, "Should have entries for both items")
            assertEquals(2, grouped[testItemId]?.size, "First item should have 2 notes")
            assertEquals(1, grouped[item2Id]?.size, "Second item should have 1 note")
            assertEquals("C", grouped[item2Id]!![0].body)
        }

    @Test
    fun `findByItemIds with empty set returns empty map`() =
        runBlocking {
            val result = noteRepository.findByItemIds(emptySet())
            assertNotNull(result)
            assertTrue(result.isEmpty())
        }

    @Test
    fun `findByItemIds with no matching items returns empty map`() =
        runBlocking {
            // testItemId has no notes yet
            val result = noteRepository.findByItemIds(setOf(UUID.randomUUID(), UUID.randomUUID()))
            assertNotNull(result)
            assertTrue(result.isEmpty())
        }

    // --- M5: ID-preservation and createdAt immutability on upsert ---

    // --- Actor attribution ---

    @Test
    fun `upsert creates note with actor claim`() =
        runBlocking {
            val actor = ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT, parent = "orch-1")
            val verification =
                VerificationResult(
                    status = VerificationStatus.UNCHECKED,
                    verifier = "noop"
                )
            val note =
                Note(
                    itemId = testItemId,
                    key = "actor-note",
                    role = "work",
                    body = "body text",
                    actorClaim = actor,
                    verification = verification
                )
            noteRepository.upsert(note)

            val result = noteRepository.findByItemId(testItemId)
            assertNotNull(result)
            assertEquals(1, result.size)
            val found = result[0]
            assertNotNull(found.actorClaim)
            assertEquals("agent-1", found.actorClaim.id)
            assertEquals(ActorKind.SUBAGENT, found.actorClaim.kind)
            assertEquals("orch-1", found.actorClaim.parent)
            assertNull(found.actorClaim.proof)
            assertNotNull(found.verification)
            assertEquals(VerificationStatus.UNCHECKED, found.verification.status)
            assertEquals("noop", found.verification.verifier)
        }

    @Test
    fun `upsert updates note replaces actor claim`() =
        runBlocking {
            val actor1 = ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT)
            val actor2 = ActorClaim(id = "agent-2", kind = ActorKind.ORCHESTRATOR)

            val note1 =
                Note(
                    itemId = testItemId,
                    key = "replace-actor-note",
                    role = "work",
                    body = "first version",
                    actorClaim = actor1
                )
            noteRepository.upsert(note1)

            val note2 =
                Note(
                    itemId = testItemId,
                    key = "replace-actor-note",
                    role = "work",
                    body = "second version",
                    actorClaim = actor2,
                    verification = VerificationResult(status = VerificationStatus.VERIFIED, verifier = "v2")
                )
            noteRepository.upsert(note2)

            val result = noteRepository.findByItemIdAndKey(testItemId, "replace-actor-note")
            assertNotNull(result)
            assertNotNull(result)
            val found = result
            assertEquals("second version", found.body)
            assertNotNull(found.actorClaim)
            assertEquals("agent-2", found.actorClaim.id)
            assertEquals(ActorKind.ORCHESTRATOR, found.actorClaim.kind)
            assertNotNull(found.verification)
            assertEquals(VerificationStatus.VERIFIED, found.verification.status)
        }

    @Test
    fun `upsert without actor preserves null actor`() =
        runBlocking {
            val note =
                Note(
                    itemId = testItemId,
                    key = "no-actor-note",
                    role = "queue",
                    body = "no actor here"
                )
            noteRepository.upsert(note)

            val result = noteRepository.findByItemIdAndKey(testItemId, "no-actor-note")
            assertNotNull(result)
            assertNull(result.actorClaim)
            assertNull(result.verification)
        }

    @Test
    fun `upsert preserves original note id and createdAt on update`(): Unit =
        runBlocking {
            // Insert the original note
            val originalNote = Note(itemId = testItemId, key = "immutable-key", role = "queue", body = "Original body")
            val insertResult = noteRepository.upsert(originalNote)
            assertNotNull(insertResult)
            val inserted = insertResult

            val originalId = inserted.id
            val originalCreatedAt = inserted.createdAt
            val originalModifiedAt = inserted.modifiedAt

            // Small sleep to ensure modifiedAt timestamp will differ
            Thread.sleep(5)

            // Upsert again with same (itemId, key) but different body
            val updatedNote = Note(itemId = testItemId, key = "immutable-key", role = "work", body = "Updated body")
            val updateResult = noteRepository.upsert(updatedNote)
            assertNotNull(updateResult)
            val updated = updateResult

            // ID must be the same as the original (not the new note's UUID)
            assertEquals(originalId, updated.id, "Note id must be preserved on upsert update")

            // Body must reflect the new content
            assertEquals("Updated body", updated.body, "Body should be updated")

            // modifiedAt must have advanced
            assertTrue(
                updated.modifiedAt.isAfter(originalModifiedAt) || updated.modifiedAt == originalModifiedAt,
                "modifiedAt should be updated or equal (clock resolution)"
            )

            // Read back from DB to verify createdAt immutability
            val fromDb = noteRepository.getById(originalId)
            assertNotNull(fromDb)
            val dbNote = fromDb
            assertEquals(originalId, dbNote.id, "DB note id should match original")
            assertEquals("Updated body", dbNote.body, "DB note body should be updated")
            assertEquals(
                originalCreatedAt.epochSecond,
                dbNote.createdAt.epochSecond,
                "createdAt in DB should not be changed by upsert update"
            )
        }

    /**
     * BUG1-REGRESSION: Concurrent upsert for the same (itemId, key) must not lose either write.
     *
     * Prior implementation used SELECT-then-INSERT-or-UPDATE (TOCTOU). Two threads that both
     * saw `existing == null` would both attempt INSERT; the second INSERT would hit the UNIQUE
     * constraint on (work_item_id, key) and return Result.Error, silently dropping the write.
     *
     * The atomic upsert fix uses a single INSERT … ON CONFLICT DO UPDATE statement (run under
     * an IMMEDIATE transaction with busy_timeout; SQLITE_BUSY would be reported, not retried), so exactly one of the two writes wins and the other becomes
     * a conflict-resolved update. After both threads complete, exactly one row must exist and
     * both threads must have received Result.Success (not Result.Error).
     */
    @Test
    fun `concurrent upsert for same itemId and key — both return Success, exactly one row persisted`(): Unit =
        runBlocking {
            val key = "concurrent-upsert-key"
            val body1 = "body from thread 1"
            val body2 = "body from thread 2"

            val executor = Executors.newFixedThreadPool(2)
            val startGate = CountDownLatch(1)
            val results = arrayOfNulls<Note>(2)

            val future1 =
                executor.submit {
                    startGate.await()
                    results[0] =
                        runBlocking {
                            noteRepository.upsert(
                                Note(itemId = testItemId, key = key, role = "queue", body = body1)
                            )
                        }
                }
            val future2 =
                executor.submit {
                    startGate.await()
                    results[1] =
                        runBlocking {
                            noteRepository.upsert(
                                Note(itemId = testItemId, key = key, role = "queue", body = body2)
                            )
                        }
                }

            startGate.countDown()
            future1.get(10, TimeUnit.SECONDS)
            future2.get(10, TimeUnit.SECONDS)
            executor.shutdown()

            // Both threads must have received Success — no write is silently dropped.
            assertNotNull(results[0], "Thread 1 upsert must return Success")
            assertNotNull(results[1], "Thread 2 upsert must return Success")

            // Exactly one row must exist for this (itemId, key) pair.
            val findResult = noteRepository.findByItemId(testItemId)
            assertNotNull(findResult)
            val notesForKey = findResult.filter { it.key == key }
            assertEquals(1, notesForKey.size, "Exactly one note row must exist after concurrent upserts")

            // The persisted body must be one of the two submitted values (not corrupted).
            val persistedBody = notesForKey[0].body
            assertTrue(
                persistedBody == body1 || persistedBody == body2,
                "Persisted body must be one of the two submitted values but was: $persistedBody"
            )
        }
}
