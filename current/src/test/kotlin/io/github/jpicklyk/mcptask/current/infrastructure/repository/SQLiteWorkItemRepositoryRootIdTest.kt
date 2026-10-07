package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.application.service.ItemHierarchyValidator
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Integration tests for the denormalized `root_id` column against a real in-memory SQLite
 * database (pattern: [SQLiteWorkItemRepositoryFindInScopeTest] — SQLite rather than H2, since
 * these exercises go through [ItemHierarchyValidator.recomputeDescendantDepths]'s
 * [WorkItemRepository.findDescendants] which relies on a SQLite-specific recursive CTE).
 *
 * These simulate what the two application-layer call sites (CreateItemHandler /
 * UpdateItemHandler and their REST equivalents in ItemWriteRoutes) compute — the repository
 * itself doesn't compute `rootId`, it just persists and round-trips whatever value it's given —
 * so each test drives the repository the same way those handlers do.
 */
class SQLiteWorkItemRepositoryRootIdTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var repository: SQLiteWorkItemRepository
    private lateinit var validator: ItemHierarchyValidator

    @BeforeEach
    fun setUp() {
        database = sqliteDb.database
        databaseManager = sqliteDb.databaseManager
        repository = SQLiteWorkItemRepository(databaseManager)
        validator = ItemHierarchyValidator()
    }

    private fun create(item: WorkItem): WorkItem =
        runBlocking {
            val result = repository.create(item)
            assertIs<Result.Success<WorkItem>>(result, "Expected item '${item.title}' to be created")
            result.data
        }

    private fun rootIdOf(id: UUID): UUID? =
        runBlocking {
            val result = repository.getById(id)
            assertIs<Result.Success<WorkItem>>(result)
            result.data.rootId
        }

    @Test
    fun `create stamps rootId for root, child, and grandchild`(): Unit =
        runBlocking {
            // Root: rootId == own id, mirroring CreateItemHandler's "parentId == null -> own id".
            val rootId = UUID.randomUUID()
            val root = create(WorkItem(id = rootId, title = "Root", depth = 0, rootId = rootId, role = Role.QUEUE))

            // Child: rootId inherited from parent (root.rootId ?: root.id).
            val child =
                create(
                    WorkItem(
                        title = "Child",
                        parentId = root.id,
                        depth = 1,
                        rootId = root.rootId ?: root.id,
                        role = Role.QUEUE
                    )
                )

            // Grandchild: rootId still inherited from the same original root.
            val grandchild =
                create(
                    WorkItem(
                        title = "Grandchild",
                        parentId = child.id,
                        depth = 2,
                        rootId = child.rootId ?: child.id,
                        role = Role.QUEUE
                    )
                )

            assertEquals(root.id, rootIdOf(root.id), "Root's rootId must be its own id")
            assertEquals(root.id, rootIdOf(child.id), "Child must inherit the root's id")
            assertEquals(root.id, rootIdOf(grandchild.id), "Grandchild must inherit the same root id")
        }

    @Test
    fun `reparent restamps rootId for the moved item and every descendant`(): Unit =
        runBlocking {
            // Root A -> B -> C
            val rootA = create(WorkItem(title = "Root A", depth = 0).let { it.copy(rootId = it.id) })
            val b = create(WorkItem(title = "B", parentId = rootA.id, depth = 1, rootId = rootA.id))
            val c = create(WorkItem(title = "C", parentId = b.id, depth = 2, rootId = rootA.id))

            // Independent Root D
            val rootD = create(WorkItem(title = "Root D", depth = 0).let { it.copy(rootId = it.id) })

            // Move B under Root D — same depth (1), different root subtree entirely.
            val movedB =
                b.update { item ->
                    item.copy(parentId = rootD.id, depth = 1, rootId = rootD.id)
                }
            val updateResult = repository.update(movedB)
            assertIs<Result.Success<WorkItem>>(updateResult)

            // Cascade: depth delta is 0 (B stayed at depth 1) but rootId must still restamp C.
            val cascadeResult = validator.recomputeDescendantDepths(b.id, 0, rootD.id, repository)
            assertIs<Result.Success<Unit>>(cascadeResult)

            assertEquals(rootD.id, rootIdOf(b.id), "B's rootId must flip to Root D after reparent")
            assertEquals(rootD.id, rootIdOf(c.id), "C must inherit B's new root (Root D), not the old Root A")
            assertEquals(rootA.id, rootIdOf(rootA.id), "Root A itself is untouched by B's move")
        }

    @Test
    fun `move-to-root makes the item its own root`(): Unit =
        runBlocking {
            val root = create(WorkItem(title = "Root", depth = 0).let { it.copy(rootId = it.id) })
            val leaf = create(WorkItem(title = "Leaf", parentId = root.id, depth = 1, rootId = root.id))

            // Move leaf to root: parentId = null, depth = 0, rootId = own id.
            val movedLeaf =
                leaf.update { item ->
                    item.copy(parentId = null, depth = 0, rootId = leaf.id)
                }
            val updateResult = repository.update(movedLeaf)
            assertIs<Result.Success<WorkItem>>(updateResult)

            assertEquals(leaf.id, rootIdOf(leaf.id), "Leaf must become its own root after moving to root level")
        }
}
