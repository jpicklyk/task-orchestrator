package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.ItemDeleteResult
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.WorkItemsTable
import io.github.jpicklyk.mcptask.current.test.inUnit
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteLimits
import java.sql.Connection
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F-016: a recursive delete releases leases in bulk and deletes level by level (deepest traversal
 * level first, computed from parentId links) instead of one row at a time. Runs on real SQLite.
 */
class WorkItemDeletionBulkSubtreeTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val repositoryProvider get() = db.repositoryProvider()

    private val repo: WorkItemRepository get() = repositoryProvider.workItemRepository()
    private val deletion get() = ToolExecutionContext(repositoryProvider, unitOfWork = db.unitOfWork()).itemCommandService

    private fun deleted(outcome: Outcome<ItemDeleteResult>?) = (outcome as Outcome.Ok).value.let { it.id to it.descendantsDeleted }

    private suspend fun create(
        title: String,
        parentId: UUID? = null,
        depth: Int = 0,
    ): WorkItem = repo.create(WorkItem(parentId = parentId, depth = depth, title = title))

    private suspend fun exists(id: UUID) = repo.getById(id) != null

    @Test
    fun `T2 recursive delete of a tree larger than the bind-variable limit deletes every row and cascades`(): Unit =
        runBlocking {
            val childCount = 24
            val perChild = 49
            lateinit var root: WorkItem
            val all = mutableListOf<UUID>()
            db.unitOfWork().inUnit {
                root = create("root")
                for (c in 0 until childCount) {
                    val child = create("c$c", root.id, 1)
                    all += child.id
                    for (g in 0 until perChild) all += create("c$c-g$g", child.id, 2).id
                }
            }
            val expectedDescendants = childCount + childCount * perChild
            assertEquals(expectedDescendants, all.size)

            val external = create("external")
            val noted = all.last()
            repositoryProvider.noteRepository().upsert(Note(itemId = noted, key = "k", role = "work", body = "b"))
            val dep = repositoryProvider.dependencyRepository().create(Dependency(fromItemId = external.id, toItemId = noted))
            val lease = repositoryProvider.resourceLeaseRepository()
            assertIs<LeaseAcquireResult.Success>(lease.acquireAll(all.first(), "a", listOf("res" to 900)))

            var outcome: Outcome<ItemDeleteResult>? = null
            db.unitOfWork().inUnit {
                val conn = TransactionManager.current().connection.connection as Connection
                conn.unwrap(SQLiteConnection::class.java).setLimit(SQLiteLimits.SQLITE_LIMIT_VARIABLE_NUMBER, 600)
                outcome = deletion.delete(root.id, recursive = true)
            }

            assertEquals(root.id to expectedDescendants, deleted(outcome))
            assertTrue(!exists(root.id))
            assertTrue(all.none { exists(it) })
            assertTrue((repositoryProvider.noteRepository().findByItemId(noted)!!).isEmpty())
            assertNull(repositoryProvider.dependencyRepository().findById(dep.id))
            assertTrue(lease.findActiveForItem(all.first()).isEmpty())
            assertTrue(exists(external.id))
        }

    @Test
    fun `T4 recursive delete ignores a stale stored depth and orders by parent links`(): Unit =
        runBlocking {
            val root = create("root")
            val a = create("a", root.id, 1)
            val b = create("b", a.id, 2)
            val c = create("c", b.id, 3)
            val sibling = create("sibling", root.id, 1)
            // Stale stored depth: a parent carries a LARGER depth than its descendants, so a
            // depth-descending per-row delete would remove a before b (FK violation). Depths stay > 0 so
            // the rows still map to valid WorkItems.
            transaction(db = db.database) {
                WorkItemsTable.update({ WorkItemsTable.id eq a.id }) { it[depth] = 5 }
                WorkItemsTable.update({ WorkItemsTable.id eq b.id }) { it[depth] = 2 }
                WorkItemsTable.update({ WorkItemsTable.id eq c.id }) { it[depth] = 1 }
            }

            val outcome = deletion.delete(root.id, recursive = true)

            assertEquals(root.id to 4, deleted(outcome))
            assertTrue(listOf(root, a, b, c, sibling).none { exists(it.id) })
        }
}
