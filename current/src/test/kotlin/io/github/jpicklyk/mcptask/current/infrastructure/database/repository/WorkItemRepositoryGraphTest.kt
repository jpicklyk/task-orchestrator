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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkItemRepositoryGraphTest {

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

    // ─────────────────────────────────────────────────────────
    // Helper: create a WorkItem directly in the repository
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

    // ─────────────────────────────────────────────────────────
    // findByIds tests
    // ─────────────────────────────────────────────────────────

    @Test
    fun `findByIds with empty set returns empty list`(): Unit = runBlocking {
        val result = repository.findByIds(emptySet())
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertTrue(result.data.isEmpty())
    }

    @Test
    fun `findByIds with single existing id returns that item`(): Unit = runBlocking {
        val item = createItem("Single Item")

        val result = repository.findByIds(setOf(item.id))
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals(item.id, result.data[0].id)
        assertEquals("Single Item", result.data[0].title)
    }

    @Test
    fun `findByIds with multiple ids returns all matching items`(): Unit = runBlocking {
        val itemA = createItem("Item A")
        val itemB = createItem("Item B")
        val itemC = createItem("Item C")

        val result = repository.findByIds(setOf(itemA.id, itemB.id, itemC.id))
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(3, result.data.size)
        val ids = result.data.map { it.id }.toSet()
        assertTrue(itemA.id in ids)
        assertTrue(itemB.id in ids)
        assertTrue(itemC.id in ids)
    }

    @Test
    fun `findByIds with nonexistent ids silently omits them`(): Unit = runBlocking {
        val item = createItem("Real Item")
        val fakeId = UUID.randomUUID()

        val result = repository.findByIds(setOf(item.id, fakeId))
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertEquals(1, result.data.size)
        assertEquals(item.id, result.data[0].id)
    }

    @Test
    fun `findByIds with only nonexistent ids returns empty list`(): Unit = runBlocking {
        val fakeId1 = UUID.randomUUID()
        val fakeId2 = UUID.randomUUID()

        val result = repository.findByIds(setOf(fakeId1, fakeId2))
        assertIs<Result.Success<List<WorkItem>>>(result)
        assertTrue(result.data.isEmpty())
    }

    // ─────────────────────────────────────────────────────────
    // findAncestorChains tests
    // ─────────────────────────────────────────────────────────

    @Test
    fun `findAncestorChains with empty set returns empty map`(): Unit = runBlocking {
        val result = repository.findAncestorChains(emptySet())
        assertIs<Result.Success<Map<UUID, List<WorkItem>>>>(result)
        assertTrue(result.data.isEmpty())
    }

    @Test
    fun `findAncestorChains for depth-0 root item returns empty ancestor list`(): Unit = runBlocking {
        val root = createItem("Root", parentId = null, depth = 0)

        val result = repository.findAncestorChains(setOf(root.id))
        assertIs<Result.Success<Map<UUID, List<WorkItem>>>>(result)
        val chain = result.data[root.id]
        assertTrue(chain != null)
        assertTrue(chain.isEmpty(), "Root item should have no ancestors, but got: $chain")
    }

    @Test
    fun `findAncestorChains for depth-1 item returns one ancestor (the root)`(): Unit = runBlocking {
        val root = createItem("Root", parentId = null, depth = 0)
        val child = createItem("Child", parentId = root.id, depth = 1)

        val result = repository.findAncestorChains(setOf(child.id))
        assertIs<Result.Success<Map<UUID, List<WorkItem>>>>(result)
        val chain = result.data[child.id]
        assertTrue(chain != null)
        assertEquals(1, chain.size)
        assertEquals(root.id, chain[0].id)
    }

    @Test
    fun `findAncestorChains for depth-2 item returns two ancestors root-first`(): Unit = runBlocking {
        val root = createItem("Root", parentId = null, depth = 0)
        val middle = createItem("Middle", parentId = root.id, depth = 1)
        val leaf = createItem("Leaf", parentId = middle.id, depth = 2)

        val result = repository.findAncestorChains(setOf(leaf.id))
        assertIs<Result.Success<Map<UUID, List<WorkItem>>>>(result)
        val chain = result.data[leaf.id]
        assertTrue(chain != null)
        assertEquals(2, chain.size)
        // root-first ordering: chain[0] = root, chain[1] = middle (direct parent)
        assertEquals(root.id, chain[0].id)
        assertEquals(middle.id, chain[1].id)
    }

    @Test
    fun `findAncestorChains for batch of mixed-depth items resolves correctly`(): Unit = runBlocking {
        val root = createItem("Root", parentId = null, depth = 0)
        val child = createItem("Child", parentId = root.id, depth = 1)
        val grandchild = createItem("Grandchild", parentId = child.id, depth = 2)
        val anotherRoot = createItem("AnotherRoot", parentId = null, depth = 0)

        val result = repository.findAncestorChains(
            setOf(root.id, child.id, grandchild.id, anotherRoot.id)
        )
        assertIs<Result.Success<Map<UUID, List<WorkItem>>>>(result)
        val data = result.data

        // root has no ancestors
        val rootChain = data[root.id]
        assertTrue(rootChain != null)
        assertTrue(rootChain.isEmpty())

        // child has root as its one ancestor
        val childChain = data[child.id]
        assertTrue(childChain != null)
        assertEquals(1, childChain.size)
        assertEquals(root.id, childChain[0].id)

        // grandchild has [root, child] in that order
        val grandchildChain = data[grandchild.id]
        assertTrue(grandchildChain != null)
        assertEquals(2, grandchildChain.size)
        assertEquals(root.id, grandchildChain[0].id)
        assertEquals(child.id, grandchildChain[1].id)

        // another root has no ancestors
        val anotherRootChain = data[anotherRoot.id]
        assertTrue(anotherRootChain != null)
        assertTrue(anotherRootChain.isEmpty())
    }
}
