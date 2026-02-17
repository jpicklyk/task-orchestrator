package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteDependencyRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SQLiteDependencyRepositoryTest {

    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var depRepository: SQLiteDependencyRepository
    private lateinit var workItemRepository: SQLiteWorkItemRepository

    // Pre-created work item IDs for dependency tests
    private lateinit var itemA: UUID
    private lateinit var itemB: UUID
    private lateinit var itemC: UUID
    private lateinit var itemD: UUID

    @BeforeEach
    fun setUp() = runBlocking {
        val dbName = "test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        depRepository = SQLiteDependencyRepository(databaseManager)
        workItemRepository = SQLiteWorkItemRepository(databaseManager)

        // Create work items for foreign key references
        val a = WorkItem(title = "Item A")
        val b = WorkItem(title = "Item B")
        val c = WorkItem(title = "Item C")
        val d = WorkItem(title = "Item D")
        workItemRepository.create(a)
        workItemRepository.create(b)
        workItemRepository.create(c)
        workItemRepository.create(d)
        itemA = a.id
        itemB = b.id
        itemC = c.id
        itemD = d.id
    }

    // --- Create ---

    @Test
    fun `create dependency`() = runBlocking {
        val dep = Dependency(fromItemId = itemA, toItemId = itemB)
        val result = depRepository.create(dep)
        assertEquals(dep.id, result.id)
        assertEquals(itemA, result.fromItemId)
        assertEquals(itemB, result.toItemId)
        assertEquals(DependencyType.BLOCKS, result.type)
    }

    @Test
    fun `create dependency with unblockAt`() = runBlocking {
        val dep = Dependency(fromItemId = itemA, toItemId = itemB, unblockAt = "work")
        val result = depRepository.create(dep)
        assertEquals("work", result.unblockAt)
    }

    // --- findById ---

    @Test
    fun `findById returns dependency`() = runBlocking {
        val dep = Dependency(fromItemId = itemA, toItemId = itemB)
        depRepository.create(dep)

        val result = depRepository.findById(dep.id)
        assertNotNull(result)
        assertEquals(dep.id, result.id)
        assertEquals(itemA, result.fromItemId)
        assertEquals(itemB, result.toItemId)
    }

    @Test
    fun `findById returns null for non-existent`() = runBlocking {
        val result = depRepository.findById(UUID.randomUUID())
        assertNull(result)
    }

    // --- findByItemId ---

    @Test
    fun `findByItemId returns all deps for item`() = runBlocking {
        depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))
        depRepository.create(Dependency(fromItemId = itemC, toItemId = itemA))

        val result = depRepository.findByItemId(itemA)
        assertEquals(2, result.size)
    }

    @Test
    fun `findByItemId returns empty for item with no deps`() = runBlocking {
        val result = depRepository.findByItemId(itemD)
        assertTrue(result.isEmpty())
    }

    // --- findByFromItemId / findByToItemId ---

    @Test
    fun `findByFromItemId returns outgoing deps`() = runBlocking {
        depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))
        depRepository.create(Dependency(fromItemId = itemA, toItemId = itemC))
        depRepository.create(Dependency(fromItemId = itemB, toItemId = itemC))

        val result = depRepository.findByFromItemId(itemA)
        assertEquals(2, result.size)
        assertTrue(result.all { it.fromItemId == itemA })
    }

    @Test
    fun `findByToItemId returns incoming deps`() = runBlocking {
        depRepository.create(Dependency(fromItemId = itemA, toItemId = itemC))
        depRepository.create(Dependency(fromItemId = itemB, toItemId = itemC))

        val result = depRepository.findByToItemId(itemC)
        assertEquals(2, result.size)
        assertTrue(result.all { it.toItemId == itemC })
    }

    // --- delete ---

    @Test
    fun `delete removes dependency`() = runBlocking {
        val dep = Dependency(fromItemId = itemA, toItemId = itemB)
        depRepository.create(dep)

        val deleted = depRepository.delete(dep.id)
        assertTrue(deleted)

        val result = depRepository.findById(dep.id)
        assertNull(result)
    }

    @Test
    fun `delete non-existent returns false`() = runBlocking {
        val result = depRepository.delete(UUID.randomUUID())
        assertFalse(result)
    }

    // --- deleteByItemId ---

    @Test
    fun `deleteByItemId removes all deps for item`() = runBlocking {
        depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))
        depRepository.create(Dependency(fromItemId = itemC, toItemId = itemA))

        val deletedCount = depRepository.deleteByItemId(itemA)
        assertEquals(2, deletedCount)

        assertTrue(depRepository.findByItemId(itemA).isEmpty())
    }

    // --- createBatch ---

    @Test
    fun `createBatch creates multiple deps`() = runBlocking {
        val deps = listOf(
            Dependency(fromItemId = itemA, toItemId = itemB),
            Dependency(fromItemId = itemB, toItemId = itemC),
            Dependency(fromItemId = itemC, toItemId = itemD)
        )
        val result = depRepository.createBatch(deps)
        assertEquals(3, result.size)

        // Verify all were created
        assertNotNull(depRepository.findById(deps[0].id))
        assertNotNull(depRepository.findById(deps[1].id))
        assertNotNull(depRepository.findById(deps[2].id))
    }

    @Test
    fun `createBatch with empty list returns empty`() = runBlocking {
        val result = depRepository.createBatch(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `createBatch rejects duplicates within batch`() = runBlocking {
        val deps = listOf(
            Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS),
            Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS) // duplicate
        )
        assertThrows<ValidationException> {
            depRepository.createBatch(deps)
        }
    }

    @Test
    fun `createBatch rejects duplicates against existing`() = runBlocking {
        depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS))

        val deps = listOf(
            Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS) // already exists
        )
        assertThrows<ValidationException> {
            depRepository.createBatch(deps)
        }
    }

    // --- Cyclic dependency detection ---

    @Test
    fun `hasCyclicDependency - A to B then B to A is cycle`() = runBlocking {
        depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))

        val isCyclic = depRepository.hasCyclicDependency(itemB, itemA)
        assertTrue(isCyclic)
    }

    @Test
    fun `hasCyclicDependency - A to B, B to C then C to A is transitive cycle`() = runBlocking {
        depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))
        depRepository.create(Dependency(fromItemId = itemB, toItemId = itemC))

        val isCyclic = depRepository.hasCyclicDependency(itemC, itemA)
        assertTrue(isCyclic)
    }

    @Test
    fun `hasCyclicDependency - no cycle returns false`() = runBlocking {
        depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))

        val isCyclic = depRepository.hasCyclicDependency(itemC, itemA)
        assertFalse(isCyclic)
    }

    @Test
    fun `create auto-rejects cyclic dependency`() = runBlocking {
        depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))

        assertThrows<ValidationException> {
            depRepository.create(Dependency(fromItemId = itemB, toItemId = itemA))
        }
    }

    @Test
    fun `create auto-rejects transitive cyclic dependency`() = runBlocking {
        depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))
        depRepository.create(Dependency(fromItemId = itemB, toItemId = itemC))

        assertThrows<ValidationException> {
            depRepository.create(Dependency(fromItemId = itemC, toItemId = itemA))
        }
    }

    @Test
    fun `create rejects duplicate dependency`() = runBlocking {
        depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))

        assertThrows<ValidationException> {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))
        }
    }

    @Test
    fun `different types between same items are allowed`() = runBlocking {
        depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS))
        depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.RELATES_TO))

        val deps = depRepository.findByFromItemId(itemA)
        assertEquals(2, deps.size)
    }
}
