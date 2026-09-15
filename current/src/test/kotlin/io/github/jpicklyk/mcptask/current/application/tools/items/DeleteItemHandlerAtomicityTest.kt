package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wraps a real [WorkItemRepository] and fails [delete] for exactly one chosen id, returning
 * [Result.Error] rather than throwing. Every other member — including [WorkItemRepository.inTransaction],
 * [WorkItemRepository.findDescendants], and [WorkItemRepository.findChildren] — delegates to
 * [delegate] unchanged.
 *
 * Per O3 (WorkItemRepository.inTransaction KDoc: "if [block] throws, all writes are rolled back
 * atomically") and the test-plan's stated fix shape (delete() returns Result.Error, it does not
 * throw — the fix must THROW inside inTransaction to roll back, then catch outside to keep the
 * per-id `failures` envelope), this wrapper only ever hands the atomicity fix a [Result.Error] to
 * react to. It never throws itself — whether that Result.Error gets converted into a rollback is
 * exactly what these tests are probing.
 */
private class FailOnIdWorkItemRepository(
    private val delegate: WorkItemRepository,
    private val failingId: UUID
) : WorkItemRepository by delegate {
    override suspend fun delete(id: UUID): Result<Boolean> =
        if (id == failingId) {
            Result.Error(RepositoryError.DatabaseError("Simulated delete failure for $id"))
        } else {
            delegate.delete(id)
        }
}

/** Wraps a real [RepositoryProvider], substituting [failingWorkItemRepo] for [workItemRepository]. */
private class FailOnIdRepositoryProvider(
    private val delegate: RepositoryProvider,
    private val failingWorkItemRepo: WorkItemRepository
) : RepositoryProvider by delegate {
    override fun workItemRepository(): WorkItemRepository = failingWorkItemRepo
}

/**
 * Independent test authorship for item c75085d3 (needs-test-author).
 *
 * Oracles (frozen in test-plan note ce0d93aa, before implementation was read):
 *  O1 api-reference.md sec-manage_items-delete: `deleted` counts roots+descendants;
 *     `descendantsDeleted` present only when recursive AND descendants were deleted;
 *     non-recursive delete of a parent fails via a child-count check.
 *  O2 Stated algorithm: recursive delete is all-or-nothing per requested root id — any failure
 *     leaves EVERY row of that root's subtree present.
 *  O3 WorkItemRepository.inTransaction KDoc: "if [block] throws, all writes are rolled back
 *     atomically".
 *  O4 NotesTable.kt / DependenciesTable.kt: FK onDelete=CASCADE.
 *
 * Seam: [FailOnIdWorkItemRepository] / [FailOnIdRepositoryProvider] wrap the real H2-backed
 * repository so [WorkItemRepository.inTransaction] stays real (no MockRepositoryProvider —
 * that harness never runs a real rollback).
 */
class DeleteItemHandlerAtomicityTest {
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var context: ToolExecutionContext
    private val handler = DeleteItemHandler()

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
    }

    /** A context whose workItemRepository().delete() fails for [failingId]; everything else is real. */
    private fun contextFailingOn(failingId: UUID): ToolExecutionContext {
        val failing = FailOnIdWorkItemRepository(repositoryProvider.workItemRepository(), failingId)
        return ToolExecutionContext(FailOnIdRepositoryProvider(repositoryProvider, failing))
    }

    private fun idsArray(vararg ids: UUID) = JsonArray(ids.map { JsonPrimitive(it.toString()) })

    private suspend fun createItem(
        title: String,
        parentId: UUID? = null,
        depth: Int = 0
    ): WorkItem {
        val item = WorkItem(parentId = parentId, depth = depth, title = title)
        val result = repositoryProvider.workItemRepository().create(item)
        assertTrue(result is Result.Success, "fixture creation of '$title' failed: $result")
        return (result as Result.Success).data
    }

    private suspend fun exists(id: UUID): Boolean = repositoryProvider.workItemRepository().getById(id).isSuccess()

    private data class Tree(
        val root: WorkItem,
        val child: WorkItem,
        val grandchild: WorkItem
    )

    private suspend fun threeLevelTree(): Tree {
        val root = createItem("Root")
        val child = createItem("Child", parentId = root.id, depth = 1)
        val grandchild = createItem("Grandchild", parentId = child.id, depth = 2)
        return Tree(root, child, grandchild)
    }

    private fun failuresOf(data: JsonObject): List<JsonObject> = data["failures"]?.jsonArray?.map { it.jsonObject } ?: emptyList()

    // ──────────────────────────────────────────────
    // HAPPY
    // ──────────────────────────────────────────────

    @Test
    fun `S1 recursive delete of root removes root, child, and grandchild atomically`() =
        runBlocking {
            val tree = threeLevelTree()

            val response = handler.execute(idsArray(tree.root.id), true, context) as JsonObject
            val data = response["data"] as JsonObject

            assertEquals(3, data["deleted"]!!.jsonPrimitive.int, "root + child + grandchild")
            assertEquals(0, data["failed"]!!.jsonPrimitive.int)
            assertEquals(2, data["descendantsDeleted"]!!.jsonPrimitive.int, "child + grandchild, per O1")
            val ids = data["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(ids.contains(tree.root.id.toString()))

            assertFalse(exists(tree.root.id), "root must be gone")
            assertFalse(exists(tree.child.id), "child must be gone")
            assertFalse(exists(tree.grandchild.id), "grandchild must be gone")
        }

    @Test
    fun `S2 recursive delete of a childless leaf omits descendantsDeleted`() =
        runBlocking {
            val leaf = createItem("Leaf")

            val response = handler.execute(idsArray(leaf.id), true, context) as JsonObject
            val data = response["data"] as JsonObject

            assertEquals(1, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(0, data["failed"]!!.jsonPrimitive.int)
            assertTrue(data["descendantsDeleted"] == null, "no descendants deleted -> field absent per O1")
            assertFalse(exists(leaf.id))
        }

    // ──────────────────────────────────────────────
    // FAILURE — atomicity (O2, O3)
    // ──────────────────────────────────────────────

    @Test
    fun `S3 failure deleting the middle descendant leaves root, child, and grandchild all present`() =
        runBlocking {
            val tree = threeLevelTree()

            val response = handler.execute(idsArray(tree.root.id), true, contextFailingOn(tree.child.id)) as JsonObject
            val data = response["data"] as JsonObject

            assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val failures = failuresOf(data)
            assertEquals(1, failures.size)
            assertTrue(
                failures[0]["error"]!!.jsonPrimitive.content.contains("Failed to delete descendant"),
                "actual: ${failures[0]}"
            )

            assertTrue(exists(tree.root.id), "root must remain — O2 all-or-nothing")
            assertTrue(exists(tree.child.id), "child must remain — O2 all-or-nothing")
            assertTrue(exists(tree.grandchild.id), "grandchild must remain — O2 all-or-nothing")
        }

    @Test
    fun `S4 failure deleting the deepest leaf leaves root, child, and grandchild all present`() =
        runBlocking {
            val tree = threeLevelTree()

            val response = handler.execute(idsArray(tree.root.id), true, contextFailingOn(tree.grandchild.id)) as JsonObject
            val data = response["data"] as JsonObject

            assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)

            assertTrue(exists(tree.root.id), "root must remain — O2 all-or-nothing")
            assertTrue(exists(tree.child.id), "child must remain — O2 all-or-nothing")
            assertTrue(exists(tree.grandchild.id), "grandchild must remain — O2 all-or-nothing")
        }

    @Test
    fun `S5 failure deleting the root after descendants already succeeded leaves all three present`() =
        runBlocking {
            val tree = threeLevelTree()

            val response = handler.execute(idsArray(tree.root.id), true, contextFailingOn(tree.root.id)) as JsonObject
            val data = response["data"] as JsonObject

            assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)

            assertTrue(exists(tree.root.id), "root delete failed but must not be partially committed")
            assertTrue(exists(tree.child.id), "child delete succeeded pre-failure but must be rolled back")
            assertTrue(exists(tree.grandchild.id), "grandchild delete succeeded pre-failure but must be rolled back")
        }

    @Test
    fun `S6 non-recursive delete of a parent fails with a recursive hint and leaves parent and child`() =
        runBlocking {
            val root = createItem("Parent")
            val child = createItem("Child", parentId = root.id, depth = 1)

            val response = handler.execute(idsArray(root.id), false, context) as JsonObject
            val data = response["data"] as JsonObject

            assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val failures = failuresOf(data)
            assertEquals(1, failures.size)
            val error = failures[0]["error"]!!.jsonPrimitive.content
            assertTrue(error.contains("child item(s)"), "actual: $error")
            assertTrue(error.contains("Use recursive=true"), "actual: $error")

            assertTrue(exists(root.id))
            assertTrue(exists(child.id))
        }

    // ──────────────────────────────────────────────
    // EDGE
    // ──────────────────────────────────────────────

    @Test
    fun `S7 batch delete partitions per root id — one subtree failure does not block the other root's delete`() =
        runBlocking {
            val a = createItem("A")
            val aChild = createItem("A-child", parentId = a.id, depth = 1)
            val b = createItem("B")

            val response = handler.execute(idsArray(a.id, b.id), true, contextFailingOn(aChild.id)) as JsonObject
            val data = response["data"] as JsonObject

            assertEquals(1, data["deleted"]!!.jsonPrimitive.int, "B's subtree succeeds")
            assertEquals(1, data["failed"]!!.jsonPrimitive.int, "A's subtree fails")

            assertTrue(exists(a.id), "A's tx scope failed — A intact")
            assertTrue(exists(aChild.id), "A's tx scope failed — A-child intact")
            assertFalse(exists(b.id), "B's tx scope succeeded independently")
        }

    @Test
    fun `S8a grandchild note and dependency edge are gone after a successful recursive delete`() =
        runBlocking {
            val tree = threeLevelTree()
            val external = createItem("External")
            repositoryProvider.noteRepository().upsert(
                Note(itemId = tree.grandchild.id, key = "test-note", role = "work", body = "hello")
            )
            val dependency =
                repositoryProvider.dependencyRepository().create(
                    Dependency(fromItemId = external.id, toItemId = tree.grandchild.id)
                )

            val response = handler.execute(idsArray(tree.root.id), true, context) as JsonObject
            val data = response["data"] as JsonObject
            assertEquals(3, data["deleted"]!!.jsonPrimitive.int)

            val notes = repositoryProvider.noteRepository().findByItemId(tree.grandchild.id)
            assertTrue(notes is Result.Success && notes.data.isEmpty(), "note must cascade-delete (O4): $notes")
            assertEquals(null, repositoryProvider.dependencyRepository().findById(dependency.id), "dependency must cascade-delete (O4)")
        }

    @Test
    fun `S8b grandchild note and dependency edge survive a failed recursive delete`() =
        runBlocking {
            val tree = threeLevelTree()
            val external = createItem("External")
            repositoryProvider.noteRepository().upsert(
                Note(itemId = tree.grandchild.id, key = "test-note", role = "work", body = "hello")
            )
            val dependency =
                repositoryProvider.dependencyRepository().create(
                    Dependency(fromItemId = external.id, toItemId = tree.grandchild.id)
                )

            val response = handler.execute(idsArray(tree.root.id), true, contextFailingOn(tree.child.id)) as JsonObject
            val data = response["data"] as JsonObject
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)

            val notes = repositoryProvider.noteRepository().findByItemId(tree.grandchild.id)
            assertTrue(notes is Result.Success && notes.data.any { it.key == "test-note" }, "note must survive rollback: $notes")
            assertTrue(
                repositoryProvider.dependencyRepository().findById(dependency.id) != null,
                "dependency must survive rollback"
            )
        }

    @Test
    fun `S9 recursive delete of a nonexistent id fails as not-found without touching anything else`() =
        runBlocking {
            val untouched = createItem("Untouched")
            val missingId = UUID.randomUUID()

            val response = handler.execute(idsArray(missingId), true, context) as JsonObject
            val data = response["data"] as JsonObject

            assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val failures = failuresOf(data)
            assertTrue(failures[0]["error"]!!.jsonPrimitive.content.contains("not found"), "actual: ${failures[0]}")

            assertTrue(exists(untouched.id), "unrelated item must be untouched")
        }

    // ──────────────────────────────────────────────
    // ADVERSARIAL PROBES
    // ──────────────────────────────────────────────

    @Test
    fun `probe duplicate id in the same batch — first delete succeeds, second reports not-found`() =
        runBlocking {
            val leaf = createItem("Leaf")

            val response = handler.execute(idsArray(leaf.id, leaf.id), true, context) as JsonObject
            val data = response["data"] as JsonObject

            assertEquals(1, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val failures = failuresOf(data)
            assertTrue(failures[0]["error"]!!.jsonPrimitive.content.contains("not found"), "actual: ${failures[0]}")
        }

    @Test
    fun `probe replay — repeating a successful recursive delete reports not-found the second time`() =
        runBlocking {
            val tree = threeLevelTree()
            handler.execute(idsArray(tree.root.id), true, context)

            val replay = handler.execute(idsArray(tree.root.id), true, context) as JsonObject
            val data = replay["data"] as JsonObject

            assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val failures = failuresOf(data)
            assertTrue(failures[0]["error"]!!.jsonPrimitive.content.contains("not found"), "actual: ${failures[0]}")
        }

    @Test
    fun `probe empty itemIds array deletes nothing and fails nothing`() =
        runBlocking {
            val response = handler.execute(idsArray(), true, context) as JsonObject
            val data = response["data"] as JsonObject

            assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(0, data["failed"]!!.jsonPrimitive.int)
            assertTrue(data["ids"]!!.jsonArray.isEmpty())
        }

    @Test
    fun `probe JsonNull element in itemIds is rejected with a per-id failure entry`() =
        runBlocking {
            // Arbitration (case 2, test wrong): the manage_items contract (itemIds param
            // description: "Array of item UUIDs or hex prefixes (4+ chars) for delete")
            // guarantees rejection via a per-id failure entry but does not specify error
            // wording. Assert only what the contract promises: nothing deleted, one failure,
            // with a non-blank error naming the id requirement — not an exact message string.
            val response = handler.execute(JsonArray(listOf(JsonNull)), false, context) as JsonObject
            val data = response["data"] as JsonObject

            assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val failures = failuresOf(data)
            assertEquals(1, failures.size)
            val error = failures[0]["error"]!!.jsonPrimitive.content
            assertTrue(error.isNotBlank(), "actual: ${failures[0]}")
            assertTrue(
                error.contains("UUID", ignoreCase = true) || error.contains("hex prefix", ignoreCase = true),
                "expected the error to mention the id-format requirement; actual: $error"
            )
        }

    @Test
    fun `probe boundary — failure two levels deep in a four-level tree leaves all four rows present`() =
        runBlocking {
            val root = createItem("Root4")
            val child = createItem("Child4", parentId = root.id, depth = 1)
            val grandchild = createItem("Grandchild4", parentId = child.id, depth = 2)
            val greatGrandchild = createItem("GreatGrandchild4", parentId = grandchild.id, depth = 3)

            val response = handler.execute(idsArray(root.id), true, contextFailingOn(grandchild.id)) as JsonObject
            val data = response["data"] as JsonObject

            assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)

            assertTrue(exists(root.id))
            assertTrue(exists(child.id))
            assertTrue(exists(grandchild.id))
            assertTrue(exists(greatGrandchild.id))
        }
}
