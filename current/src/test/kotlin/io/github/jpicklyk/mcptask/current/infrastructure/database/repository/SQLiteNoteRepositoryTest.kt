package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteNoteRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SQLiteNoteRepositoryTest {

    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var noteRepository: SQLiteNoteRepository
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var testItemId: UUID

    @BeforeEach
    fun setUp() = runBlocking {
        val dbName = "test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        noteRepository = SQLiteNoteRepository(databaseManager)
        workItemRepository = SQLiteWorkItemRepository(databaseManager)

        // Create a work item for foreign key references
        val item = WorkItem(title = "Test item")
        workItemRepository.create(item)
        testItemId = item.id
    }

    // --- Upsert creates new note ---

    @Test
    fun `upsert creates new note`() = runBlocking {
        val note = Note(itemId = testItemId, key = "requirements", role = "queue", body = "Must do X")
        val result = noteRepository.upsert(note)
        assertIs<Result.Success<Note>>(result)
        assertEquals("requirements", result.data.key)
        assertEquals("queue", result.data.role)
        assertEquals("Must do X", result.data.body)
    }

    // --- Upsert updates existing note ---

    @Test
    fun `upsert updates existing note with same itemId and key`() = runBlocking {
        val note1 = Note(itemId = testItemId, key = "requirements", role = "queue", body = "Original")
        noteRepository.upsert(note1)

        val note2 = Note(itemId = testItemId, key = "requirements", role = "work", body = "Updated")
        val result = noteRepository.upsert(note2)
        assertIs<Result.Success<Note>>(result)
        assertEquals("Updated", result.data.body)
        assertEquals("work", result.data.role)

        // Verify only one note exists with this key
        val findResult = noteRepository.findByItemId(testItemId)
        assertIs<Result.Success<List<Note>>>(findResult)
        assertEquals(1, findResult.data.size)
    }

    // --- getById ---

    @Test
    fun `getById returns note`() = runBlocking {
        val note = Note(itemId = testItemId, key = "test-key", role = "queue")
        noteRepository.upsert(note)

        val result = noteRepository.getById(note.id)
        assertIs<Result.Success<Note>>(result)
        assertEquals(note.id, result.data.id)
        assertEquals("test-key", result.data.key)
    }

    @Test
    fun `getById returns NotFound for non-existent`() = runBlocking {
        val result = noteRepository.getById(UUID.randomUUID())
        assertIs<Result.Error>(result)
        assertIs<RepositoryError.NotFound>(result.error)
    }

    // --- delete ---

    @Test
    fun `delete removes note`() = runBlocking {
        val note = Note(itemId = testItemId, key = "to-delete", role = "queue")
        noteRepository.upsert(note)

        val deleteResult = noteRepository.delete(note.id)
        assertIs<Result.Success<Boolean>>(deleteResult)
        assertTrue(deleteResult.data)

        val getResult = noteRepository.getById(note.id)
        assertIs<Result.Error>(getResult)
    }

    @Test
    fun `delete non-existent note returns false`() = runBlocking {
        val result = noteRepository.delete(UUID.randomUUID())
        assertIs<Result.Success<Boolean>>(result)
        assertEquals(false, result.data)
    }

    // --- deleteByItemId ---

    @Test
    fun `deleteByItemId removes all notes for item`() = runBlocking {
        noteRepository.upsert(Note(itemId = testItemId, key = "note-1", role = "queue"))
        noteRepository.upsert(Note(itemId = testItemId, key = "note-2", role = "work"))
        noteRepository.upsert(Note(itemId = testItemId, key = "note-3", role = "review"))

        val result = noteRepository.deleteByItemId(testItemId)
        assertIs<Result.Success<Int>>(result)
        assertEquals(3, result.data)

        val findResult = noteRepository.findByItemId(testItemId)
        assertIs<Result.Success<List<Note>>>(findResult)
        assertTrue(findResult.data.isEmpty())
    }

    // --- findByItemId ---

    @Test
    fun `findByItemId returns all notes`() = runBlocking {
        noteRepository.upsert(Note(itemId = testItemId, key = "note-a", role = "queue", body = "A"))
        noteRepository.upsert(Note(itemId = testItemId, key = "note-b", role = "work", body = "B"))

        val result = noteRepository.findByItemId(testItemId)
        assertIs<Result.Success<List<Note>>>(result)
        assertEquals(2, result.data.size)
    }

    @Test
    fun `findByItemId with role filter`() = runBlocking {
        noteRepository.upsert(Note(itemId = testItemId, key = "queue-note", role = "queue"))
        noteRepository.upsert(Note(itemId = testItemId, key = "work-note", role = "work"))
        noteRepository.upsert(Note(itemId = testItemId, key = "review-note", role = "review"))

        val result = noteRepository.findByItemId(testItemId, role = "work")
        assertIs<Result.Success<List<Note>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("work", result.data[0].role)
        assertEquals("work-note", result.data[0].key)
    }

    @Test
    fun `findByItemId returns empty for different item`() = runBlocking {
        noteRepository.upsert(Note(itemId = testItemId, key = "note", role = "queue"))

        val result = noteRepository.findByItemId(UUID.randomUUID())
        assertIs<Result.Success<List<Note>>>(result)
        assertTrue(result.data.isEmpty())
    }

    // --- findByItemIdAndKey ---

    @Test
    fun `findByItemIdAndKey returns specific note`() = runBlocking {
        noteRepository.upsert(Note(itemId = testItemId, key = "specific-key", role = "queue", body = "Found me"))

        val result = noteRepository.findByItemIdAndKey(testItemId, "specific-key")
        assertIs<Result.Success<Note?>>(result)
        assertNotNull(result.data)
        assertEquals("Found me", result.data!!.body)
        assertEquals("specific-key", result.data!!.key)
    }

    @Test
    fun `findByItemIdAndKey returns null for non-existent key`() = runBlocking {
        noteRepository.upsert(Note(itemId = testItemId, key = "existing-key", role = "queue"))

        val result = noteRepository.findByItemIdAndKey(testItemId, "non-existent-key")
        assertIs<Result.Success<Note?>>(result)
        assertNull(result.data)
    }

    @Test
    fun `findByItemIdAndKey returns null for wrong itemId`() = runBlocking {
        noteRepository.upsert(Note(itemId = testItemId, key = "my-key", role = "queue"))

        val result = noteRepository.findByItemIdAndKey(UUID.randomUUID(), "my-key")
        assertIs<Result.Success<Note?>>(result)
        assertNull(result.data)
    }
}
