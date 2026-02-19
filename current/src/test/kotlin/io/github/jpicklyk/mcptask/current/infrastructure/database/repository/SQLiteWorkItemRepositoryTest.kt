package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SQLiteWorkItemRepositoryTest {

    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var repository: SQLiteWorkItemRepository

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repository = SQLiteWorkItemRepository(databaseManager)
    }

    // --- CRUD ---

    @Test
    fun `create and getById returns the item`() = runBlocking {
        val item = WorkItem(title = "Test task")
        val createResult = repository.create(item)
        assertIs<Result.Success<WorkItem>>(createResult)

        val getResult = repository.getById(item.id)
        assertIs<Result.Success<WorkItem>>(getResult)
        assertEquals(item.title, getResult.data.title)
        assertEquals(item.id, getResult.data.id)
    }

    @Test
    fun `create preserves all fields`() = runBlocking {
        val item = WorkItem(
            title = "Full task",
            description = "A description",
            summary = "A summary",
            role = Role.WORK,
            statusLabel = "in-progress",
            previousRole = Role.QUEUE,
            priority = Priority.HIGH,
            complexity = 8,
            depth = 0,
            metadata = """{"key":"value"}""",
            tags = "bug,critical"
        )
        repository.create(item)

        val result = repository.getById(item.id)
        assertIs<Result.Success<WorkItem>>(result)
        val retrieved = result.data
        assertEquals("Full task", retrieved.title)
        assertEquals("A description", retrieved.description)
        assertEquals("A summary", retrieved.summary)
        assertEquals(Role.WORK, retrieved.role)
        assertEquals("in-progress", retrieved.statusLabel)
        assertEquals(Role.QUEUE, retrieved.previousRole)
        assertEquals(Priority.HIGH, retrieved.priority)
        assertEquals(8, retrieved.complexity)
        assertEquals(0, retrieved.depth)
        assertEquals("""{"key":"value"}""", retrieved.metadata)
        assertEquals("bug,critical", retrieved.tags)
    }

    @Test
    fun `update modifies item`() = runBlocking {
        val item = WorkItem(title = "Original title")
        repository.create(item)

        val updated = item.copy(title = "Updated title")
        val updateResult = repository.update(updated)
        assertIs<Result.Success<WorkItem>>(updateResult)
        assertEquals(item.version + 1, updateResult.data.version)

        val getResult = repository.getById(item.id)
        assertIs<Result.Success<WorkItem>>(getResult)
        assertEquals("Updated title", getResult.data.title)
    }

    @Test
    fun `delete removes item`() = runBlocking {
        val item = WorkItem(title = "To be deleted")
        repository.create(item)

        val deleteResult = repository.delete(item.id)
        assertIs<Result.Success<Boolean>>(deleteResult)
        assertTrue(deleteResult.data)

        val getResult = repository.getById(item.id)
        assertIs<Result.Error>(getResult)
        assertIs<RepositoryError.NotFound>(getResult.error)
    }

    @Test
    fun `delete non-existent item returns false`() = runBlocking {
        val result = repository.delete(UUID.randomUUID())
        assertIs<Result.Success<Boolean>>(result)
        assertEquals(false, result.data)
    }

    // --- Parent-child hierarchy ---

    @Test
    fun `create with parentId`() = runBlocking {
        val parent = WorkItem(title = "Parent", depth = 0)
        repository.create(parent)

        val child = WorkItem(title = "Child", parentId = parent.id, depth = 1)
        val result = repository.create(child)
        assertIs<Result.Success<WorkItem>>(result)

        val getResult = repository.getById(child.id)
        assertIs<Result.Success<WorkItem>>(getResult)
        assertEquals(parent.id, getResult.data.parentId)
        assertEquals(1, getResult.data.depth)
    }

    @Test
    fun `findByParent returns children`() = runBlocking {
        val parent = WorkItem(title = "Parent", depth = 0)
        repository.create(parent)

        val child1 = WorkItem(title = "Child 1", parentId = parent.id, depth = 1)
        val child2 = WorkItem(title = "Child 2", parentId = parent.id, depth = 1)
        repository.create(child1)
        repository.create(child2)

        val result = repository.findByParent(parent.id)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(2, result.data.size)
        val titles = result.data.map { it.title }.toSet()
        assertTrue("Child 1" in titles)
        assertTrue("Child 2" in titles)
    }

    @Test
    fun `findByParent returns empty for no children`() = runBlocking {
        val parent = WorkItem(title = "Parent", depth = 0)
        repository.create(parent)

        val result = repository.findByParent(parent.id)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertTrue(result.data.isEmpty())
    }

    // --- findByRole ---

    @Test
    fun `findByRole filters correctly`() = runBlocking {
        repository.create(WorkItem(title = "Queue item 1", role = Role.QUEUE))
        repository.create(WorkItem(title = "Queue item 2", role = Role.QUEUE))
        repository.create(WorkItem(title = "Work item", role = Role.WORK))

        val result = repository.findByRole(Role.QUEUE)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(2, result.data.size)
        assertTrue(result.data.all { it.role == Role.QUEUE })
    }

    @Test
    fun `findByRole returns empty for no matches`() = runBlocking {
        repository.create(WorkItem(title = "Queue item", role = Role.QUEUE))

        val result = repository.findByRole(Role.TERMINAL)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertTrue(result.data.isEmpty())
    }

    // --- findByDepth ---

    @Test
    fun `findByDepth filters correctly`() = runBlocking {
        val parent = WorkItem(title = "Root", depth = 0)
        repository.create(parent)
        repository.create(WorkItem(title = "Child", parentId = parent.id, depth = 1))

        val depthZero = repository.findByDepth(0)
        assertIs<Result.Success<List<WorkItem>>>(depthZero)
        assertEquals(1, depthZero.data.size)
        assertEquals("Root", depthZero.data[0].title)

        val depthOne = repository.findByDepth(1)
        assertIs<Result.Success<List<WorkItem>>>(depthOne)
        assertEquals(1, depthOne.data.size)
        assertEquals("Child", depthOne.data[0].title)
    }

    // --- findRoot ---

    @Test
    fun `findRoot returns root item`() = runBlocking {
        val root = WorkItem(title = "Root", depth = 0, parentId = null)
        repository.create(root)

        val result = repository.findRoot()
        assertIs<Result.Success<WorkItem?>>(result)
        assertNotNull(result.data)
        assertEquals("Root", result.data!!.title)
    }

    @Test
    fun `findRoot returns null when no root exists`() = runBlocking {
        val result = repository.findRoot()
        assertIs<Result.Success<WorkItem?>>(result)
        assertEquals(null, result.data)
    }

    // --- search ---

    @Test
    fun `search by title`() = runBlocking {
        repository.create(WorkItem(title = "Authentication module"))
        repository.create(WorkItem(title = "Database layer"))

        val result = repository.search("Auth")
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("Authentication module", result.data[0].title)
    }

    @Test
    fun `search by summary`() = runBlocking {
        repository.create(WorkItem(title = "Task A", summary = "Fix login bug"))
        repository.create(WorkItem(title = "Task B", summary = "Add feature"))

        val result = repository.search("login")
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("Task A", result.data[0].title)
    }

    @Test
    fun `search returns empty for no matches`() = runBlocking {
        repository.create(WorkItem(title = "Something"))

        val result = repository.search("nonexistent")
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertTrue(result.data.isEmpty())
    }

    // --- count ---

    @Test
    fun `count returns correct count`() = runBlocking {
        assertEquals(0L, (repository.count() as Result.Success).data)

        repository.create(WorkItem(title = "Item 1"))
        repository.create(WorkItem(title = "Item 2"))
        repository.create(WorkItem(title = "Item 3"))

        val result = repository.count()
        assertIs<Result.Success<Long>>(result)
        assertEquals(3L, result.data)
    }

    // --- findChildren ---

    @Test
    fun `findChildren returns correct children`() = runBlocking {
        val parent = WorkItem(title = "Parent", depth = 0)
        repository.create(parent)

        val child1 = WorkItem(title = "Child 1", parentId = parent.id, depth = 1)
        val child2 = WorkItem(title = "Child 2", parentId = parent.id, depth = 1)
        val unrelated = WorkItem(title = "Unrelated", depth = 0)
        repository.create(child1)
        repository.create(child2)
        repository.create(unrelated)

        val result = repository.findChildren(parent.id)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(2, result.data.size)
    }

    // --- Optimistic locking ---

    @Test
    fun `update with wrong version fails with ConflictError`() = runBlocking {
        val item = WorkItem(title = "Original")
        repository.create(item)

        // First update succeeds (version 1 -> 2)
        val updated1 = item.copy(title = "Updated 1")
        val result1 = repository.update(updated1)
        assertIs<Result.Success<WorkItem>>(result1)
        assertEquals(2L, result1.data.version)

        // Second update with original version (1) should fail
        val updated2 = item.copy(title = "Updated 2")
        val result2 = repository.update(updated2)
        assertIs<Result.Error>(result2)
        assertIs<RepositoryError.ConflictError>(result2.error)
    }

    // --- getById not found ---

    @Test
    fun `getById with non-existent UUID returns NotFound`() = runBlocking {
        val result = repository.getById(UUID.randomUUID())
        assertIs<Result.Error>(result)
        assertIs<RepositoryError.NotFound>(result.error)
    }
}
