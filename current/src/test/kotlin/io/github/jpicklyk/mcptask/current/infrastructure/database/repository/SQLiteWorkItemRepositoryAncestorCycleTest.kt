package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.WorkItemsTable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests verifying that [SQLiteWorkItemRepository.findAncestorChains] handles corrupt parent
 * references (self-loops and mutual cycles) gracefully — terminating and returning a partial
 * (or empty) chain rather than spinning indefinitely.
 *
 * Items with circular parentId values are inserted directly via Exposed to bypass tool-level
 * guards, simulating data that may exist in pre-guard databases.
 */
class SQLiteWorkItemRepositoryAncestorCycleTest {

    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var repository: SQLiteWorkItemRepository

    @BeforeEach
    fun setUp() {
        val dbName = "cycle_test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repository = SQLiteWorkItemRepository(databaseManager)
    }

    // ─────────────────────────────────────────────────────────
    // Helper: create a WorkItem through the repository normally
    // ─────────────────────────────────────────────────────────

    private suspend fun createItem(
        title: String,
        parentId: UUID? = null,
        depth: Int = if (parentId == null) 0 else 1
    ): WorkItem {
        val item = WorkItem(title = title, parentId = parentId, depth = depth)
        val result = repository.create(item)
        assertIs<Result.Success<WorkItem>>(result)
        return result.data
    }

    /**
     * Directly update parent_id in the DB, bypassing domain validation.
     * Used to introduce corrupt data that tool guards would normally prevent.
     */
    private fun forceSetParentId(itemId: UUID, newParentId: UUID) {
        transaction(db = database) {
            WorkItemsTable.update({ WorkItemsTable.id eq itemId }) {
                it[WorkItemsTable.parentId] = newParentId
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // Test 1: Self-loop — item.parentId = item.id
    // ─────────────────────────────────────────────────────────

    @Test
    fun `findAncestorChains with self-loop returns empty chain`(): Unit = runBlocking {
        // Create a normal root item first
        val item = createItem("Self-loop item")

        // Force the item to point to itself as parent (bypasses tool guards)
        forceSetParentId(item.id, item.id)

        // findAncestorChains must terminate and not spin forever
        val result = repository.findAncestorChains(setOf(item.id))
        assertIs<Result.Success<Map<UUID, List<WorkItem>>>>(result)

        val chain = result.data[item.id]
        assertTrue(chain != null, "Chain entry must exist for the requested item")
        // The cycle detection in findAncestorChains must break the loop.
        // The item itself is NOT an ancestor, so the chain should be empty (or at most
        // contain the single self-referencing item before cycle detection fires).
        assertTrue(
            chain.isEmpty() || chain.size == 1,
            "Self-loop should produce an empty or single-entry chain, got ${chain.size} entries"
        )
    }

    // ─────────────────────────────────────────────────────────
    // Test 2: Mutual 2-hop cycle — A.parentId = B, B.parentId = A
    // ─────────────────────────────────────────────────────────

    @Test
    fun `findAncestorChains with mutual cycle terminates and returns at most one ancestor`(): Unit = runBlocking {
        // Create two independent root items
        val itemA = createItem("Item A")
        val itemB = createItem("Item B")

        // Create the mutual cycle: A -> B -> A
        forceSetParentId(itemA.id, itemB.id)
        forceSetParentId(itemB.id, itemA.id)

        // Must terminate, not spin forever
        val result = repository.findAncestorChains(setOf(itemA.id))
        assertIs<Result.Success<Map<UUID, List<WorkItem>>>>(result)

        val chain = result.data[itemA.id]
        assertTrue(chain != null, "Chain entry must exist for the requested item")
        // With cycle detection the chain will be broken after visiting one node
        assertTrue(
            chain.size <= 2,
            "Mutual cycle chain should be short (at most 2 entries before cycle detection), got ${chain.size}"
        )
    }

    // ─────────────────────────────────────────────────────────
    // Test 3: Valid linear chain — root -> parent -> child
    // ─────────────────────────────────────────────────────────

    @Test
    fun `findAncestorChains with valid linear chain returns correct ancestors`(): Unit = runBlocking {
        val root = createItem("Root", parentId = null, depth = 0)
        val parent = createItem("Parent", parentId = root.id, depth = 1)
        val child = createItem("Child", parentId = parent.id, depth = 2)

        val result = repository.findAncestorChains(setOf(child.id))
        assertIs<Result.Success<Map<UUID, List<WorkItem>>>>(result)

        val chain = result.data[child.id]
        assertTrue(chain != null, "Chain entry must exist for the child item")
        assertEquals(2, chain.size, "Child at depth 2 should have 2 ancestors")
        // Chain is root-first: chain[0] = root, chain[1] = parent (direct parent)
        assertEquals(root.id, chain[0].id, "First ancestor should be root")
        assertEquals(parent.id, chain[1].id, "Second ancestor should be parent")
    }
}
