package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SQLiteWorkItemRepositoryFilterTest {

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

    // =====================================================================
    // findByFilters tests
    // =====================================================================

    @Test
    fun `findByFilters with no filters returns all items`() = runBlocking {
        repository.create(WorkItem(title = "Item 1"))
        repository.create(WorkItem(title = "Item 2"))
        repository.create(WorkItem(title = "Item 3"))

        val result = repository.findByFilters()
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(3, result.data.size)
    }

    @Test
    fun `findByFilters by role`() = runBlocking {
        repository.create(WorkItem(title = "Queue item", role = Role.QUEUE))
        repository.create(WorkItem(title = "Work item", role = Role.WORK))
        repository.create(WorkItem(title = "Another queue", role = Role.QUEUE))

        val result = repository.findByFilters(role = Role.QUEUE)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(2, result.data.size)
        assertTrue(result.data.all { it.role == Role.QUEUE })
    }

    @Test
    fun `findByFilters by priority`() = runBlocking {
        repository.create(WorkItem(title = "High prio", priority = Priority.HIGH))
        repository.create(WorkItem(title = "Low prio", priority = Priority.LOW))
        repository.create(WorkItem(title = "High prio 2", priority = Priority.HIGH))

        val result = repository.findByFilters(priority = Priority.HIGH)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(2, result.data.size)
        assertTrue(result.data.all { it.priority == Priority.HIGH })
    }

    @Test
    fun `findByFilters by parentId`() = runBlocking {
        val parent = WorkItem(title = "Parent", depth = 0)
        repository.create(parent)
        repository.create(WorkItem(title = "Child 1", parentId = parent.id, depth = 1))
        repository.create(WorkItem(title = "Child 2", parentId = parent.id, depth = 1))
        repository.create(WorkItem(title = "Orphan", depth = 0))

        val result = repository.findByFilters(parentId = parent.id)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(2, result.data.size)
        assertTrue(result.data.all { it.parentId == parent.id })
    }

    @Test
    fun `findByFilters by depth`() = runBlocking {
        val parent = WorkItem(title = "Depth 0", depth = 0)
        repository.create(parent)
        repository.create(WorkItem(title = "Depth 1", parentId = parent.id, depth = 1))

        val result = repository.findByFilters(depth = 0)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("Depth 0", result.data[0].title)
    }

    @Test
    fun `findByFilters by tags - single tag`() = runBlocking {
        repository.create(WorkItem(title = "Tagged", tags = "bug"))
        repository.create(WorkItem(title = "Not tagged"))

        val result = repository.findByFilters(tags = listOf("bug"))
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("Tagged", result.data[0].title)
    }

    @Test
    fun `findByFilters by tags - multiple tags OR logic`() = runBlocking {
        repository.create(WorkItem(title = "Bug item", tags = "bug"))
        repository.create(WorkItem(title = "Feature item", tags = "feature"))
        repository.create(WorkItem(title = "No tags"))

        val result = repository.findByFilters(tags = listOf("bug", "feature"))
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(2, result.data.size)
        val titles = result.data.map { it.title }.toSet()
        assertTrue("Bug item" in titles)
        assertTrue("Feature item" in titles)
    }

    @Test
    fun `findByFilters by tags - tag at start, middle, end of comma-separated string`() = runBlocking {
        repository.create(WorkItem(title = "Start", tags = "bug,feature"))
        repository.create(WorkItem(title = "Middle", tags = "alpha,bug,beta"))
        repository.create(WorkItem(title = "End", tags = "alpha,bug"))
        repository.create(WorkItem(title = "Alone", tags = "bug"))
        repository.create(WorkItem(title = "No match", tags = "feature,alpha"))

        val result = repository.findByFilters(tags = listOf("bug"))
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(4, result.data.size)
        val titles = result.data.map { it.title }.toSet()
        assertTrue("Start" in titles)
        assertTrue("Middle" in titles)
        assertTrue("End" in titles)
        assertTrue("Alone" in titles)
    }

    @Test
    fun `findByFilters by tags - no false positives on tag substring`() = runBlocking {
        repository.create(WorkItem(title = "Has bugfix", tags = "bugfix"))
        repository.create(WorkItem(title = "Has bug", tags = "bug"))
        repository.create(WorkItem(title = "Has debug", tags = "debug"))

        val result = repository.findByFilters(tags = listOf("bug"))
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("Has bug", result.data[0].title)
    }

    @Test
    fun `findByFilters by createdAfter and createdBefore`() = runBlocking {
        val t1 = Instant.parse("2025-01-01T00:00:00Z")
        val t2 = Instant.parse("2025-06-01T00:00:00Z")
        val t3 = Instant.parse("2025-12-01T00:00:00Z")

        repository.create(WorkItem(title = "Old", createdAt = t1, modifiedAt = t1, roleChangedAt = t1))
        repository.create(WorkItem(title = "Mid", createdAt = t2, modifiedAt = t2, roleChangedAt = t2))
        repository.create(WorkItem(title = "New", createdAt = t3, modifiedAt = t3, roleChangedAt = t3))

        val result = repository.findByFilters(
            createdAfter = Instant.parse("2025-03-01T00:00:00Z"),
            createdBefore = Instant.parse("2025-09-01T00:00:00Z")
        )
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("Mid", result.data[0].title)
    }

    @Test
    fun `findByFilters by modifiedAfter`() = runBlocking {
        val t1 = Instant.parse("2025-01-01T00:00:00Z")
        val t2 = Instant.parse("2025-12-01T00:00:00Z")

        repository.create(WorkItem(title = "Old mod", createdAt = t1, modifiedAt = t1, roleChangedAt = t1))
        repository.create(WorkItem(title = "New mod", createdAt = t1, modifiedAt = t2, roleChangedAt = t1))

        val result = repository.findByFilters(modifiedAfter = Instant.parse("2025-06-01T00:00:00Z"))
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("New mod", result.data[0].title)
    }

    @Test
    fun `findByFilters with text query`() = runBlocking {
        repository.create(WorkItem(title = "Authentication module"))
        repository.create(WorkItem(title = "Database layer", summary = "Auth integration"))
        repository.create(WorkItem(title = "Unrelated stuff"))

        val result = repository.findByFilters(query = "Auth")
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(2, result.data.size)
        val titles = result.data.map { it.title }.toSet()
        assertTrue("Authentication module" in titles)
        assertTrue("Database layer" in titles)
    }

    @Test
    fun `findByFilters with sortBy created asc`() = runBlocking {
        val t1 = Instant.parse("2025-01-01T00:00:00Z")
        val t2 = Instant.parse("2025-06-01T00:00:00Z")
        val t3 = Instant.parse("2025-12-01T00:00:00Z")

        repository.create(WorkItem(title = "Mid", createdAt = t2, modifiedAt = t2, roleChangedAt = t2))
        repository.create(WorkItem(title = "Old", createdAt = t1, modifiedAt = t1, roleChangedAt = t1))
        repository.create(WorkItem(title = "New", createdAt = t3, modifiedAt = t3, roleChangedAt = t3))

        val result = repository.findByFilters(sortBy = "created", sortOrder = "asc")
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(3, result.data.size)
        assertEquals("Old", result.data[0].title)
        assertEquals("Mid", result.data[1].title)
        assertEquals("New", result.data[2].title)
    }

    @Test
    fun `findByFilters with sortBy modified desc`() = runBlocking {
        val t1 = Instant.parse("2025-01-01T00:00:00Z")
        val t2 = Instant.parse("2025-06-01T00:00:00Z")
        val t3 = Instant.parse("2025-12-01T00:00:00Z")

        repository.create(WorkItem(title = "Old mod", createdAt = t1, modifiedAt = t1, roleChangedAt = t1))
        repository.create(WorkItem(title = "New mod", createdAt = t1, modifiedAt = t3, roleChangedAt = t1))
        repository.create(WorkItem(title = "Mid mod", createdAt = t1, modifiedAt = t2, roleChangedAt = t1))

        val result = repository.findByFilters(sortBy = "modified", sortOrder = "desc")
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(3, result.data.size)
        assertEquals("New mod", result.data[0].title)
        assertEquals("Mid mod", result.data[1].title)
        assertEquals("Old mod", result.data[2].title)
    }

    @Test
    fun `findByFilters with limit`() = runBlocking {
        for (i in 1..10) {
            repository.create(WorkItem(title = "Item $i"))
        }

        val result = repository.findByFilters(limit = 3)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(3, result.data.size)
    }

    @Test
    fun `findByFilters with multiple filters combined`() = runBlocking {
        val parent = WorkItem(title = "Parent", depth = 0)
        repository.create(parent)

        repository.create(WorkItem(
            title = "Match",
            parentId = parent.id,
            depth = 1,
            role = Role.WORK,
            priority = Priority.HIGH
        ))
        repository.create(WorkItem(
            title = "Wrong role",
            parentId = parent.id,
            depth = 1,
            role = Role.QUEUE,
            priority = Priority.HIGH
        ))
        repository.create(WorkItem(
            title = "Wrong priority",
            parentId = parent.id,
            depth = 1,
            role = Role.WORK,
            priority = Priority.LOW
        ))
        repository.create(WorkItem(
            title = "Wrong parent",
            depth = 0,
            role = Role.WORK,
            priority = Priority.HIGH
        ))

        val result = repository.findByFilters(
            parentId = parent.id,
            role = Role.WORK,
            priority = Priority.HIGH
        )
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("Match", result.data[0].title)
    }

    // =====================================================================
    // countChildrenByRole tests
    // =====================================================================

    @Test
    fun `countChildrenByRole returns correct counts`() = runBlocking {
        val parent = WorkItem(title = "Parent", depth = 0)
        repository.create(parent)

        repository.create(WorkItem(title = "Queue 1", parentId = parent.id, depth = 1, role = Role.QUEUE))
        repository.create(WorkItem(title = "Queue 2", parentId = parent.id, depth = 1, role = Role.QUEUE))
        repository.create(WorkItem(title = "Work 1", parentId = parent.id, depth = 1, role = Role.WORK))
        repository.create(WorkItem(title = "Terminal 1", parentId = parent.id, depth = 1, role = Role.TERMINAL))
        repository.create(WorkItem(title = "Terminal 2", parentId = parent.id, depth = 1, role = Role.TERMINAL))
        repository.create(WorkItem(title = "Terminal 3", parentId = parent.id, depth = 1, role = Role.TERMINAL))

        val result = repository.countChildrenByRole(parent.id)
        assertIs<Result.Success<Map<Role, Int>>>(result)
        val counts = result.data
        assertEquals(2, counts[Role.QUEUE])
        assertEquals(1, counts[Role.WORK])
        assertEquals(3, counts[Role.TERMINAL])
        // Roles with no children should not be in the map
        assertTrue(Role.REVIEW !in counts)
        assertTrue(Role.BLOCKED !in counts)
    }

    @Test
    fun `countChildrenByRole with no children returns empty map`() = runBlocking {
        val parent = WorkItem(title = "Parent", depth = 0)
        repository.create(parent)

        val result = repository.countChildrenByRole(parent.id)
        assertIs<Result.Success<Map<Role, Int>>>(result)
        assertTrue(result.data.isEmpty())
    }

    // =====================================================================
    // findRootItems tests
    // =====================================================================

    @Test
    fun `findRootItems returns only root items`() = runBlocking {
        val root1 = WorkItem(title = "Root 1", depth = 0)
        val root2 = WorkItem(title = "Root 2", depth = 0)
        repository.create(root1)
        repository.create(root2)
        repository.create(WorkItem(title = "Child", parentId = root1.id, depth = 1))

        val result = repository.findRootItems()
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(2, result.data.size)
        val titles = result.data.map { it.title }.toSet()
        assertTrue("Root 1" in titles)
        assertTrue("Root 2" in titles)
    }

    @Test
    fun `findRootItems with limit`() = runBlocking {
        for (i in 1..5) {
            repository.create(WorkItem(title = "Root $i", depth = 0))
        }

        val result = repository.findRootItems(limit = 2)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(2, result.data.size)
    }

    // =====================================================================
    // roleChangedAfter / roleChangedBefore filter tests
    // =====================================================================

    @Test
    fun `findByFilters with roleChangedAfter filters correctly`() = runBlocking {
        val t1 = Instant.parse("2025-01-01T00:00:00Z")
        val t2 = Instant.parse("2025-06-01T00:00:00Z")
        val t3 = Instant.parse("2025-12-01T00:00:00Z")

        repository.create(WorkItem(title = "Old role change", createdAt = t1, modifiedAt = t1, roleChangedAt = t1))
        repository.create(WorkItem(title = "Recent role change", createdAt = t1, modifiedAt = t1, roleChangedAt = t3))

        val result = repository.findByFilters(roleChangedAfter = t2)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("Recent role change", result.data[0].title)
    }

    @Test
    fun `findByFilters with roleChangedBefore filters correctly`() = runBlocking {
        val t1 = Instant.parse("2025-01-01T00:00:00Z")
        val t2 = Instant.parse("2025-06-01T00:00:00Z")
        val t3 = Instant.parse("2025-12-01T00:00:00Z")

        repository.create(WorkItem(title = "Old role change", createdAt = t1, modifiedAt = t1, roleChangedAt = t1))
        repository.create(WorkItem(title = "Recent role change", createdAt = t1, modifiedAt = t1, roleChangedAt = t3))

        val result = repository.findByFilters(roleChangedBefore = t2)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("Old role change", result.data[0].title)
    }

    @Test
    fun `findByFilters with roleChangedAfter and roleChangedBefore combined filters correctly`() = runBlocking {
        val t1 = Instant.parse("2025-01-01T00:00:00Z")
        val t2 = Instant.parse("2025-03-01T00:00:00Z")
        val t3 = Instant.parse("2025-06-01T00:00:00Z")
        val t4 = Instant.parse("2025-09-01T00:00:00Z")
        val t5 = Instant.parse("2025-12-01T00:00:00Z")

        repository.create(WorkItem(title = "Before range", createdAt = t1, modifiedAt = t1, roleChangedAt = t1))
        repository.create(WorkItem(title = "In range", createdAt = t1, modifiedAt = t1, roleChangedAt = t3))
        repository.create(WorkItem(title = "After range", createdAt = t1, modifiedAt = t1, roleChangedAt = t5))

        val result = repository.findByFilters(
            roleChangedAfter = t2,
            roleChangedBefore = t4
        )
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("In range", result.data[0].title)
    }
}
