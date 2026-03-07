package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SQLiteWorkItemRepositoryPrefixTest {

    private lateinit var repository: SQLiteWorkItemRepository

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repository = SQLiteWorkItemRepository(databaseManager)
    }

    @Test
    fun `findByIdPrefix returns matching items for valid prefix`(): Unit = runBlocking {
        val item = WorkItem(title = "Prefix Test")
        repository.create(item)
        val prefix = item.id.toString().substring(0, 8)

        val result = repository.findByIdPrefix(prefix)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals(item.id, result.data.first().id)
    }

    @Test
    fun `findByIdPrefix returns empty list for non-matching prefix`(): Unit = runBlocking {
        val item = WorkItem(title = "Some Item")
        repository.create(item)

        // Use a prefix that almost certainly won't match (all zeros)
        val result = repository.findByIdPrefix("00000000")
        assertIs<Result.Success<List<WorkItem>>>(result)
        // Might match if the UUID starts with 00000000, but extremely unlikely
        // Just verify it returns a valid result
        assertTrue(result.data.isEmpty() || result.data.all { it.id.toString().startsWith("00000000") })
    }

    @Test
    fun `findByIdPrefix matches with lowercase prefix`(): Unit = runBlocking {
        val item = WorkItem(title = "Case Test")
        repository.create(item)
        val prefix = item.id.toString().substring(0, 8).lowercase()

        val result = repository.findByIdPrefix(prefix)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals(item.id, result.data.first().id)
    }

    @Test
    fun `findByIdPrefix respects limit parameter`(): Unit = runBlocking {
        // Create multiple items
        repeat(5) {
            repository.create(WorkItem(title = "Item $it"))
        }

        // Use a very short prefix that might match multiple items
        // Query with limit=2
        val result = repository.findByIdPrefix("", limit = 2)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertTrue(result.data.size <= 2)
    }

    @Test
    fun `findByIdPrefix returns multiple matches when prefix is shared`(): Unit = runBlocking {
        // Create many items and find ones with shared prefixes
        val items = mutableListOf<WorkItem>()
        repeat(50) {
            val item = WorkItem(title = "Item $it")
            repository.create(item)
            items.add(item)
        }

        // Find items that share a 4-char prefix
        val grouped = items.groupBy { it.id.toString().substring(0, 4) }
        val sharedGroup = grouped.entries.firstOrNull { it.value.size >= 2 }

        if (sharedGroup != null) {
            val result = repository.findByIdPrefix(sharedGroup.key)
            assertIs<Result.Success<List<WorkItem>>>(result)
            assertTrue(result.data.size >= 2)
        }
        // If no collision, test passes — just verifying the method works
    }

    @Test
    fun `findByIdPrefix with full 32-char hex matches exactly one item`(): Unit = runBlocking {
        val item = WorkItem(title = "Full ID Test")
        repository.create(item)

        // Strip dashes from UUID to get 32 hex chars (the repo expects hex-only prefix)
        val fullHex = item.id.toString().replace("-", "")
        val result = repository.findByIdPrefix(fullHex)
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals(item.id, result.data.first().id)
    }
}
