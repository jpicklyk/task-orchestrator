package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.domain.error.VersionConflictException
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SQLiteWorkItemRepositoryTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private val repositoryProvider get() = sqliteDb.repositoryProvider()

    private lateinit var repository: WorkItemRepository

    @BeforeEach
    fun setUp() {
        // The SqliteTestDatabase extension has started the database before this @BeforeEach
        repository = repositoryProvider.workItemRepository()
    }

    // --- CRUD ---

    @Test
    fun `create and getById returns the item`() =
        runBlocking {
            val item = WorkItem(title = "Test task")
            val createResult = repository.create(item)
            assertNotNull(createResult)

            val getResult = repository.getById(item.id)
            assertNotNull(getResult)
            assertEquals(item.title, getResult.title)
            assertEquals(item.id, getResult.id)
        }

    @Test
    fun `create preserves all fields`() =
        runBlocking {
            val item =
                WorkItem(
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
            assertNotNull(result)
            val retrieved = result
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
    fun `update modifies item`() =
        runBlocking {
            val item = WorkItem(title = "Original title")
            repository.create(item)

            val updated = item.copy(title = "Updated title")
            val updateResult = repository.update(updated)
            assertNotNull(updateResult)
            assertEquals(item.version + 1, updateResult.version)

            val getResult = repository.getById(item.id)
            assertNotNull(getResult)
            assertEquals("Updated title", getResult.title)
        }

    @Test
    fun `delete removes item`() =
        runBlocking {
            val item = WorkItem(title = "To be deleted")
            repository.create(item)

            val deleteResult = repository.delete(item.id)
            assertNotNull(deleteResult)
            assertTrue(deleteResult)

            val getResult = repository.getById(item.id)
            assertNull(getResult)
        }

    @Test
    fun `delete non-existent item returns false`() =
        runBlocking {
            val result = repository.delete(UUID.randomUUID())
            assertNotNull(result)
            assertEquals(false, result)
        }

    // --- Parent-child hierarchy ---

    @Test
    fun `create with parentId`() =
        runBlocking {
            val parent = WorkItem(title = "Parent", depth = 0)
            repository.create(parent)

            val child = WorkItem(title = "Child", parentId = parent.id, depth = 1)
            val result = repository.create(child)
            assertNotNull(result)

            val getResult = repository.getById(child.id)
            assertNotNull(getResult)
            assertEquals(parent.id, getResult.parentId)
            assertEquals(1, getResult.depth)
        }

    @Test
    fun `findByParent returns children`() =
        runBlocking {
            val parent = WorkItem(title = "Parent", depth = 0)
            repository.create(parent)

            val child1 = WorkItem(title = "Child 1", parentId = parent.id, depth = 1)
            val child2 = WorkItem(title = "Child 2", parentId = parent.id, depth = 1)
            repository.create(child1)
            repository.create(child2)

            val result = repository.findByParent(parent.id)
            assertNotNull(result)
            assertEquals(2, result.size)
            val titles = result.map { it.title }.toSet()
            assertTrue("Child 1" in titles)
            assertTrue("Child 2" in titles)
        }

    @Test
    fun `findByParent returns empty for no children`() =
        runBlocking {
            val parent = WorkItem(title = "Parent", depth = 0)
            repository.create(parent)

            val result = repository.findByParent(parent.id)
            assertNotNull(result)
            assertTrue(result.isEmpty())
        }

    // --- findByRole ---

    @Test
    fun `findByRole filters correctly`() =
        runBlocking {
            repository.create(WorkItem(title = "Queue item 1", role = Role.QUEUE))
            repository.create(WorkItem(title = "Queue item 2", role = Role.QUEUE))
            repository.create(WorkItem(title = "Work item", role = Role.WORK))

            val result = repository.findByRole(Role.QUEUE)
            assertNotNull(result)
            assertEquals(2, result.size)
            assertTrue(result.all { it.role == Role.QUEUE })
        }

    @Test
    fun `findByRole returns empty for no matches`() =
        runBlocking {
            repository.create(WorkItem(title = "Queue item", role = Role.QUEUE))

            val result = repository.findByRole(Role.TERMINAL)
            assertNotNull(result)
            assertTrue(result.isEmpty())
        }

    // --- findByDepth ---

    @Test
    fun `findByDepth filters correctly`() =
        runBlocking {
            val parent = WorkItem(title = "Root", depth = 0)
            repository.create(parent)
            repository.create(WorkItem(title = "Child", parentId = parent.id, depth = 1))

            val depthZero = repository.findByDepth(0)
            assertNotNull(depthZero)
            assertEquals(1, depthZero.size)
            assertEquals("Root", depthZero[0].title)

            val depthOne = repository.findByDepth(1)
            assertNotNull(depthOne)
            assertEquals(1, depthOne.size)
            assertEquals("Child", depthOne[0].title)
        }

    // --- findProjectRoots ---

    @Test
    fun `findProjectRoots returns only depth-0 project-typed items`() =
        runBlocking {
            val projectA = WorkItem(title = "Project A", depth = 0, type = "project")
            val projectB = WorkItem(title = "Project B", depth = 0, type = "project")
            val plainRoot = WorkItem(title = "Plain root", depth = 0)
            repository.create(projectA)
            repository.create(projectB)
            repository.create(plainRoot)
            // project-typed child must not match (depth filter)
            repository.create(WorkItem(title = "Nested", parentId = projectA.id, depth = 1, type = "project"))

            val result = repository.findProjectRoots()
            assertNotNull(result)
            assertEquals(setOf("Project A", "Project B"), result.map { it.title }.toSet())
        }

    @Test
    fun `findProjectRoots returns empty when no project anchors exist`() =
        runBlocking {
            repository.create(WorkItem(title = "Plain root", depth = 0))

            val result = repository.findProjectRoots()
            assertNotNull(result)
            assertEquals(emptyList(), result)
        }

    // --- search ---

    @Test
    fun `search by title`() =
        runBlocking {
            repository.create(WorkItem(title = "Authentication module"))
            repository.create(WorkItem(title = "Database layer"))

            val result = repository.search("Auth")
            assertNotNull(result)
            assertEquals(1, result.size)
            assertEquals("Authentication module", result[0].title)
        }

    @Test
    fun `search by summary`() =
        runBlocking {
            repository.create(WorkItem(title = "Task A", summary = "Fix login bug"))
            repository.create(WorkItem(title = "Task B", summary = "Add feature"))

            val result = repository.search("login")
            assertNotNull(result)
            assertEquals(1, result.size)
            assertEquals("Task A", result[0].title)
        }

    @Test
    fun `search returns empty for no matches`() =
        runBlocking {
            repository.create(WorkItem(title = "Something"))

            val result = repository.search("nonexistent")
            assertNotNull(result)
            assertTrue(result.isEmpty())
        }

    // --- count ---

    @Test
    fun `count returns correct count`() =
        runBlocking {
            assertEquals(0L, repository.count())

            repository.create(WorkItem(title = "Item 1"))
            repository.create(WorkItem(title = "Item 2"))
            repository.create(WorkItem(title = "Item 3"))

            val result = repository.count()
            assertNotNull(result)
            assertEquals(3L, result)
        }

    // --- findChildren ---

    @Test
    fun `findChildren returns correct children`() =
        runBlocking {
            val parent = WorkItem(title = "Parent", depth = 0)
            repository.create(parent)

            val child1 = WorkItem(title = "Child 1", parentId = parent.id, depth = 1)
            val child2 = WorkItem(title = "Child 2", parentId = parent.id, depth = 1)
            val unrelated = WorkItem(title = "Unrelated", depth = 0)
            repository.create(child1)
            repository.create(child2)
            repository.create(unrelated)

            val result = repository.findChildren(parent.id)
            assertNotNull(result)
            assertEquals(2, result.size)
        }

    // --- Optimistic locking ---

    @Test
    fun `update with wrong version fails with ConflictError`() =
        runBlocking {
            val item = WorkItem(title = "Original")
            repository.create(item)

            // First update succeeds (version 1 -> 2)
            val updated1 = item.copy(title = "Updated 1")
            val result1 = repository.update(updated1)
            assertNotNull(result1)
            assertEquals(2L, result1.version)

            // Second update with original version (1) should fail
            val updated2 = item.copy(title = "Updated 2")
            // P5b: a lost optimistic lock now THROWS VersionConflictException (was Result.Error(ConflictError)).
            val conflict = assertFailsWith<VersionConflictException> { repository.update(updated2) }
            assertEquals(1L, conflict.expected)
            assertEquals(2L, conflict.actual)
        }

    // --- getById not found ---

    @Test
    fun `getById with non-existent UUID returns NotFound`() =
        runBlocking {
            val result = repository.getById(UUID.randomUUID())
            assertNull(result)
        }

    // --- type and properties persistence ---

    @Test
    fun `create and retrieve item with type and properties`() =
        runBlocking {
            val item = WorkItem(title = "Typed item", type = "feature", properties = """{"foo":"bar"}""")
            repository.create(item)

            val result = repository.getById(item.id)
            assertNotNull(result)
            assertEquals("feature", result.type)
            assertEquals("""{"foo":"bar"}""", result.properties)
        }

    @Test
    fun `type and properties default to null when not provided`() =
        runBlocking {
            val item = WorkItem(title = "No type item")
            repository.create(item)

            val result = repository.getById(item.id)
            assertNotNull(result)
            assertEquals(null, result.type)
            assertEquals(null, result.properties)
        }

    @Test
    fun `update preserves type and properties`() =
        runBlocking {
            val item = WorkItem(title = "Original", type = "bug", properties = """{"severity":"high"}""")
            repository.create(item)

            val updated = item.copy(title = "Updated")
            repository.update(updated)

            val result = repository.getById(item.id)
            assertNotNull(result)
            assertEquals("bug", result.type)
            assertEquals("""{"severity":"high"}""", result.properties)
        }

    @Test
    fun `update can change type and properties`() =
        runBlocking {
            val item = WorkItem(title = "Original", type = "task", properties = null)
            repository.create(item)

            val updated = item.copy(type = "feature", properties = """{"complexity":3}""")
            repository.update(updated)

            val result = repository.getById(item.id)
            assertNotNull(result)
            assertEquals("feature", result.type)
            assertEquals("""{"complexity":3}""", result.properties)
        }
}
