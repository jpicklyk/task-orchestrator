package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests [ItemHierarchyValidator.recomputeDescendantDepths] against a real in-memory H2 database
 * (via [DefaultRepositoryProvider] / [DirectDatabaseSchemaManager] — the same setup used by
 * [io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsToolTest]) rather than a
 * mocked repository, so the fix is exercised against real `findDescendants` + `update` behavior.
 *
 * Bug context: reparenting an item previously updated only the moved item's own depth — every
 * descendant kept its stale absolute depth. These tests cover the cascade helper directly; the
 * MCP (`ManageItemsToolTest`) and REST (`WriteRoutesTest`) paths cover the two call sites that
 * wire this helper in.
 */
class ItemHierarchyValidatorTest {
    private lateinit var repo: WorkItemRepository
    private lateinit var validator: ItemHierarchyValidator

    @BeforeEach
    fun setUp() {
        val dbName = "hierarchy_validator_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repo = DefaultRepositoryProvider(databaseManager).workItemRepository()
        validator = ItemHierarchyValidator()
    }

    private fun create(item: WorkItem): WorkItem =
        runBlocking {
            val result = repo.create(item)
            assertIs<Result.Success<WorkItem>>(result, "Expected item '${item.title}' to be created")
            result.data
        }

    private fun depthOf(id: UUID): Int =
        runBlocking {
            val result = repo.getById(id)
            assertIs<Result.Success<WorkItem>>(result)
            result.data.depth
        }

    @Test
    fun `delta zero is a no-op`(): Unit =
        runBlocking {
            val root = create(WorkItem(title = "root", depth = 0))
            val child = create(WorkItem(title = "child", parentId = root.id, depth = 1))

            val result = validator.recomputeDescendantDepths(root.id, 0, repo)

            assertIs<Result.Success<Unit>>(result)
            assertEquals(1, depthOf(child.id), "Child depth must be untouched when delta is 0")
        }

    @Test
    fun `applies positive delta to every descendant of a 3-level tree`(): Unit =
        runBlocking {
            // root(0) -> child(1) -> grandchild(2)
            val root = create(WorkItem(title = "root", depth = 0))
            val child = create(WorkItem(title = "child", parentId = root.id, depth = 1))
            val grandchild = create(WorkItem(title = "grandchild", parentId = child.id, depth = 2))

            // Simulate root having just moved one level deeper (delta = +1); recompute root's
            // descendants only (child, grandchild) — the root's own depth write is the caller's job.
            val result = validator.recomputeDescendantDepths(root.id, 1, repo)

            assertIs<Result.Success<Unit>>(result)
            assertEquals(2, depthOf(child.id), "Child should shift from depth 1 to 2")
            assertEquals(3, depthOf(grandchild.id), "Grandchild should shift from depth 2 to 3")
        }

    @Test
    fun `applies negative delta to every descendant when moving to a shallower position`(): Unit =
        runBlocking {
            // root(0) -> b(1) -> c(2) -> d(3). B is the item being moved (to root, depth 1 -> 0);
            // recomputeDescendantDepths is called for B's descendants only (c, d) — c keeps a
            // non-null parentId (b) throughout, so depth never drops below 1 for it, satisfying
            // the domain invariant (parentId != null => depth >= 1).
            val root = create(WorkItem(title = "root", depth = 0))
            val b = create(WorkItem(title = "b", parentId = root.id, depth = 1))
            val c = create(WorkItem(title = "c", parentId = b.id, depth = 2))
            val d = create(WorkItem(title = "d", parentId = c.id, depth = 3))

            // B moved from depth 1 to depth 0 (became root): delta = -1.
            val result = validator.recomputeDescendantDepths(b.id, -1, repo)

            assertIs<Result.Success<Unit>>(result)
            assertEquals(1, depthOf(c.id), "C should shift from depth 2 to 1")
            assertEquals(2, depthOf(d.id), "D should shift from depth 3 to 2")
        }

    @Test
    fun `no descendants is a successful no-op`(): Unit =
        runBlocking {
            val leaf = create(WorkItem(title = "leaf", depth = 0))

            val result = validator.recomputeDescendantDepths(leaf.id, 2, repo)

            assertIs<Result.Success<Unit>>(result)
        }

    @Test
    fun `descendant update preserves monotonic modifiedAt`(): Unit =
        runBlocking {
            val root = create(WorkItem(title = "root", depth = 0))
            val child = create(WorkItem(title = "child", parentId = root.id, depth = 1))

            validator.recomputeDescendantDepths(root.id, 1, repo)

            val updated = (repo.getById(child.id) as Result.Success<WorkItem>).data
            assertEquals(2, updated.depth)
            assertTrue(!updated.modifiedAt.isBefore(child.modifiedAt), "modifiedAt must not regress")
        }
}
