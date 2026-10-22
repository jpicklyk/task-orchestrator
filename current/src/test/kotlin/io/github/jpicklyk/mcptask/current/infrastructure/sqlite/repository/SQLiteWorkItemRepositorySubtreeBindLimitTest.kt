package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.inUnit
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteLimits
import java.sql.Connection
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * F-016: subtree reads and bulk deletes must not bind one variable per id.
 *
 * The bundled SQLite build allows 250,000 bound variables, so a realistic tree never hits the real
 * limit. These tests lower SQLITE_LIMIT_VARIABLE_NUMBER on the transaction's own connection (the
 * limit dies with that connection) to a value below the tree size but at or above
 * [SQL_IN_CHUNK_SIZE], and run the repository calls inside that same transaction (nested
 * repository transactions join the outer one, so they reuse the limited connection).
 */
class SQLiteWorkItemRepositorySubtreeBindLimitTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private val database get() = sqliteDb.database
    private val repositoryProvider get() = sqliteDb.repositoryProvider()

    private val limit = 600
    private val childCount = 24
    private val grandchildrenPerChild = 49
    private val descendantCount = childCount + childCount * grandchildrenPerChild // 1,200

    private val repo: WorkItemRepository get() = repositoryProvider.workItemRepository()

    private data class BigTree(
        val root: WorkItem,
        val descendantIds: Set<UUID>,
    )

    private suspend fun buildBigTree(): BigTree {
        val ids = LinkedHashSet<UUID>()
        lateinit var root: WorkItem
        sqliteDb.unitOfWork().inUnit {
            root = repo.create(WorkItem(title = "root", depth = 0))
            for (c in 0 until childCount) {
                val child = repo.create(WorkItem(parentId = root.id, depth = 1, title = "c$c"))
                ids += child.id
                for (g in 0 until grandchildrenPerChild) {
                    val gc = repo.create(WorkItem(parentId = child.id, depth = 2, title = "c$c-g$g"))
                    ids += gc.id
                }
            }
        }
        return BigTree(root, ids)
    }

    private suspend fun <T> withVariableLimit(block: suspend () -> T): T {
        var out: T? = null
        sqliteDb.unitOfWork().inUnit {
            val conn = TransactionManager.current().connection.connection as Connection
            conn.unwrap(SQLiteConnection::class.java).setLimit(SQLiteLimits.SQLITE_LIMIT_VARIABLE_NUMBER, limit)
            out = block()
        }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    @Test
    fun `T1 findDescendants returns every descendant of a tree larger than the bind-variable limit`(): Unit =
        runBlocking {
            val tree = buildBigTree()
            assertEquals(descendantCount, tree.descendantIds.size)

            val result = withVariableLimit { repo.findDescendants(tree.root.id) }

            assertNotNull(result, "findDescendants must not fail under a low variable limit: $result")
            assertEquals(tree.descendantIds, result.map { it.id }.toSet())
            assertEquals(descendantCount, result.size)
        }

    @Test
    fun `T5 findByIds and deleteAll handle more ids than the bind-variable limit`(): Unit =
        runBlocking {
            val tree = buildBigTree()
            val ids = tree.descendantIds

            val found = withVariableLimit { repo.findByIds(ids) }
            assertNotNull(found, "findByIds must chunk: $found")
            assertEquals(ids, found.map { it.id }.toSet())

            // Only the grandchildren (no row in the set is the parent of another) are FK-safe to
            // delete in a single deleteAll.
            val grandchildren =
                (repo.findDescendants(tree.root.id)!!)
                    .filter { it.depth == 2 }
                    .map { it.id }
                    .toSet()
            assertEquals(childCount * grandchildrenPerChild, grandchildren.size)
            val deleted = withVariableLimit { repo.deleteAll(grandchildren) }
            assertNotNull(deleted, "deleteAll must chunk: $deleted")
            assertEquals(grandchildren.size, deleted)

            val remaining = repo.findDescendants(tree.root.id)
            assertEquals(childCount, remaining.size)
        }
}
