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
    fun setUp() =
        runBlocking {
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
    fun `create dependency`() =
        runBlocking {
            val dep = Dependency(fromItemId = itemA, toItemId = itemB)
            val result = depRepository.create(dep)
            assertEquals(dep.id, result.id)
            assertEquals(itemA, result.fromItemId)
            assertEquals(itemB, result.toItemId)
            assertEquals(DependencyType.BLOCKS, result.type)
        }

    @Test
    fun `create dependency with unblockAt`() =
        runBlocking {
            val dep = Dependency(fromItemId = itemA, toItemId = itemB, unblockAt = "work")
            val result = depRepository.create(dep)
            assertEquals("work", result.unblockAt)
        }

    // --- findById ---

    @Test
    fun `findById returns dependency`() =
        runBlocking {
            val dep = Dependency(fromItemId = itemA, toItemId = itemB)
            depRepository.create(dep)

            val result = depRepository.findById(dep.id)
            assertNotNull(result)
            assertEquals(dep.id, result.id)
            assertEquals(itemA, result.fromItemId)
            assertEquals(itemB, result.toItemId)
        }

    @Test
    fun `findById returns null for non-existent`() =
        runBlocking {
            val result = depRepository.findById(UUID.randomUUID())
            assertNull(result)
        }

    // --- findByItemId ---

    @Test
    fun `findByItemId returns all deps for item`() =
        runBlocking {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))
            depRepository.create(Dependency(fromItemId = itemC, toItemId = itemA))

            val result = depRepository.findByItemId(itemA)
            assertEquals(2, result.size)
        }

    @Test
    fun `findByItemId returns empty for item with no deps`() =
        runBlocking {
            val result = depRepository.findByItemId(itemD)
            assertTrue(result.isEmpty())
        }

    // --- findByFromItemId / findByToItemId ---

    @Test
    fun `findByFromItemId returns outgoing deps`() =
        runBlocking {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemC))
            depRepository.create(Dependency(fromItemId = itemB, toItemId = itemC))

            val result = depRepository.findByFromItemId(itemA)
            assertEquals(2, result.size)
            assertTrue(result.all { it.fromItemId == itemA })
        }

    @Test
    fun `findByToItemId returns incoming deps`() =
        runBlocking {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemC))
            depRepository.create(Dependency(fromItemId = itemB, toItemId = itemC))

            val result = depRepository.findByToItemId(itemC)
            assertEquals(2, result.size)
            assertTrue(result.all { it.toItemId == itemC })
        }

    // --- delete ---

    @Test
    fun `delete removes dependency`() =
        runBlocking {
            val dep = Dependency(fromItemId = itemA, toItemId = itemB)
            depRepository.create(dep)

            val deleted = depRepository.delete(dep.id)
            assertTrue(deleted)

            val result = depRepository.findById(dep.id)
            assertNull(result)
        }

    @Test
    fun `delete non-existent returns false`() =
        runBlocking {
            val result = depRepository.delete(UUID.randomUUID())
            assertFalse(result)
        }

    // --- deleteByItemId ---

    @Test
    fun `deleteByItemId removes all deps for item`() =
        runBlocking {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))
            depRepository.create(Dependency(fromItemId = itemC, toItemId = itemA))

            val deletedCount = depRepository.deleteByItemId(itemA)
            assertEquals(2, deletedCount)

            assertTrue(depRepository.findByItemId(itemA).isEmpty())
        }

    // --- createBatch ---

    @Test
    fun `createBatch creates multiple deps`() =
        runBlocking {
            val deps =
                listOf(
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
    fun `createBatch with empty list returns empty`() =
        runBlocking {
            val result = depRepository.createBatch(emptyList())
            assertTrue(result.isEmpty())
        }

    @Test
    fun `createBatch rejects duplicates within batch`() =
        runBlocking {
            val deps =
                listOf(
                    Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS),
                    Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS) // duplicate
                )
            assertThrows<ValidationException> {
                depRepository.createBatch(deps)
            }
        }

    @Test
    fun `createBatch rejects duplicates against existing`() =
        runBlocking {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS))

            val deps =
                listOf(
                    Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS) // already exists
                )
            assertThrows<ValidationException> {
                depRepository.createBatch(deps)
            }
        }

    // --- Cyclic dependency detection ---

    @Test
    fun `hasCyclicDependency - A to B then B to A is cycle`() =
        runBlocking {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))

            val isCyclic = depRepository.hasCyclicDependency(itemB, itemA)
            assertTrue(isCyclic)
        }

    @Test
    fun `hasCyclicDependency - A to B, B to C then C to A is transitive cycle`() =
        runBlocking {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))
            depRepository.create(Dependency(fromItemId = itemB, toItemId = itemC))

            val isCyclic = depRepository.hasCyclicDependency(itemC, itemA)
            assertTrue(isCyclic)
        }

    @Test
    fun `hasCyclicDependency - no cycle returns false`() =
        runBlocking {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))

            val isCyclic = depRepository.hasCyclicDependency(itemC, itemA)
            assertFalse(isCyclic)
        }

    @Test
    fun `create auto-rejects cyclic dependency`() =
        runBlocking {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))

            assertThrows<ValidationException> {
                depRepository.create(Dependency(fromItemId = itemB, toItemId = itemA))
            }
        }

    @Test
    fun `create auto-rejects transitive cyclic dependency`() =
        runBlocking {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))
            depRepository.create(Dependency(fromItemId = itemB, toItemId = itemC))

            assertThrows<ValidationException> {
                depRepository.create(Dependency(fromItemId = itemC, toItemId = itemA))
            }
        }

    @Test
    fun `create rejects duplicate dependency`() =
        runBlocking {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))

            assertThrows<ValidationException> {
                depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))
            }
        }

    @Test
    fun `different types between same items are allowed`() =
        runBlocking {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS))
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.RELATES_TO))

            val deps = depRepository.findByFromItemId(itemA)
            assertEquals(2, deps.size)
        }

    // --- RELATES_TO should not participate in cycle detection ---

    @Test
    fun `hasCyclicDependency - RELATES_TO edge does not create blocking path`() =
        runBlocking {
            // A RELATES_TO B is informational — it should not create a blocking path
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.RELATES_TO))

            // "Would adding B BLOCKS A create a cycle?" — No, because RELATES_TO is not a blocking edge
            val isCyclic = depRepository.hasCyclicDependency(itemB, itemA)
            assertFalse(isCyclic, "RELATES_TO should not be treated as a blocking path in cycle detection")
        }

    @Test
    fun `create succeeds when only RELATES_TO exists in reverse direction`() =
        runBlocking {
            // A RELATES_TO B is informational
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.RELATES_TO))

            // B BLOCKS A should succeed — RELATES_TO doesn't create a blocking cycle
            val dep = depRepository.create(Dependency(fromItemId = itemB, toItemId = itemA, type = DependencyType.BLOCKS))
            assertNotNull(dep)
            assertEquals(itemB, dep.fromItemId)
            assertEquals(itemA, dep.toItemId)
        }

    @Test
    fun `RELATES_TO at end of BLOCKS chain does not cause false cycle`() =
        runBlocking {
            // Build a BLOCKS chain: A -> B -> C
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS))
            depRepository.create(Dependency(fromItemId = itemB, toItemId = itemC, type = DependencyType.BLOCKS))

            // C RELATES_TO A is informational — not a blocking cycle back to A
            val dep = depRepository.create(Dependency(fromItemId = itemC, toItemId = itemA, type = DependencyType.RELATES_TO))
            assertNotNull(dep, "RELATES_TO closing a BLOCKS chain should not be rejected as a cycle")
        }

    @Test
    fun `mixed BLOCKS and IS_BLOCKED_BY cycle detection still works after fix`() =
        runBlocking {
            // A BLOCKS B — real blocking relationship
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS))

            // "Would adding B BLOCKS A create a cycle?" — Yes, this is a real blocking cycle
            val isCyclic = depRepository.hasCyclicDependency(itemB, itemA)
            assertTrue(isCyclic, "Real BLOCKS cycle should still be detected")
        }

    @Test
    fun `createBatch succeeds with RELATES_TO closing a BLOCKS chain`() =
        runBlocking {
            // Build a BLOCKS chain: A -> B -> C
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS))
            depRepository.create(Dependency(fromItemId = itemB, toItemId = itemC, type = DependencyType.BLOCKS))

            // Batch-create C RELATES_TO A — should succeed since RELATES_TO is informational
            val batch =
                depRepository.createBatch(
                    listOf(Dependency(fromItemId = itemC, toItemId = itemA, type = DependencyType.RELATES_TO))
                )
            assertEquals(1, batch.size, "Batch with RELATES_TO closing a BLOCKS chain should succeed")
        }

    // --- findByItemIds ---

    @Test
    fun `findByItemIds returns deps grouped by item`(): Unit =
        runBlocking {
            // A->B, B->C
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))
            depRepository.create(Dependency(fromItemId = itemB, toItemId = itemC))

            val result = depRepository.findByItemIds(setOf(itemA, itemC))

            // map[A] should have A->B
            val aDeps = result[itemA]!!
            assertEquals(1, aDeps.size)
            assertEquals(itemA, aDeps[0].fromItemId)
            assertEquals(itemB, aDeps[0].toItemId)

            // map[C] should have B->C
            val cDeps = result[itemC]!!
            assertEquals(1, cDeps.size)
            assertEquals(itemB, cDeps[0].fromItemId)
            assertEquals(itemC, cDeps[0].toItemId)
        }

    @Test
    fun `findByItemIds shared dep appears in both groups`(): Unit =
        runBlocking {
            // A->B
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))

            val result = depRepository.findByItemIds(setOf(itemA, itemB))

            // A->B should appear in both A's list and B's list
            val aDeps = result[itemA]!!
            assertEquals(1, aDeps.size)
            val bDeps = result[itemB]!!
            assertEquals(1, bDeps.size)
            // Both reference the same dependency
            assertEquals(aDeps[0].id, bDeps[0].id)
        }

    @Test
    fun `findByItemIds single item matches findByItemId`(): Unit =
        runBlocking {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))
            depRepository.create(Dependency(fromItemId = itemC, toItemId = itemA))

            val batchResult = depRepository.findByItemIds(setOf(itemA))
            val singleResult = depRepository.findByItemId(itemA)

            val batchDeps = batchResult[itemA]!!.sortedBy { it.id }
            val singleDeps = singleResult.sortedBy { it.id }

            assertEquals(singleDeps.size, batchDeps.size)
            for (i in singleDeps.indices) {
                assertEquals(singleDeps[i].id, batchDeps[i].id)
            }
        }

    @Test
    fun `findByItemIds empty set returns empty map`(): Unit =
        runBlocking {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))

            val result = depRepository.findByItemIds(emptySet())
            assertTrue(result.isEmpty())
        }

    @Test
    fun `findByItemIds item with no deps absent from result`(): Unit =
        runBlocking {
            // Only A->B exists, D has no deps
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))

            val result = depRepository.findByItemIds(setOf(itemD))
            assertFalse(result.containsKey(itemD))
        }

    @Test
    fun `findByItemIds includes all dependency types`(): Unit =
        runBlocking {
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS))
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemC, type = DependencyType.RELATES_TO))

            val result = depRepository.findByItemIds(setOf(itemA))
            val aDeps = result[itemA]!!
            assertEquals(2, aDeps.size)

            val types = aDeps.map { it.type }.toSet()
            assertTrue(types.contains(DependencyType.BLOCKS))
            assertTrue(types.contains(DependencyType.RELATES_TO))
        }

    @Test
    fun `findByItemIds large set correct grouping`(): Unit =
        runBlocking {
            // A->B->C->D chain
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB))
            depRepository.create(Dependency(fromItemId = itemB, toItemId = itemC))
            depRepository.create(Dependency(fromItemId = itemC, toItemId = itemD))

            val result = depRepository.findByItemIds(setOf(itemA, itemB, itemC, itemD))

            // A: has A->B (as from)
            assertEquals(1, result[itemA]!!.size)
            // B: has A->B (as to) and B->C (as from)
            assertEquals(2, result[itemB]!!.size)
            // C: has B->C (as to) and C->D (as from)
            assertEquals(2, result[itemC]!!.size)
            // D: has C->D (as to)
            assertEquals(1, result[itemD]!!.size)
        }
}
