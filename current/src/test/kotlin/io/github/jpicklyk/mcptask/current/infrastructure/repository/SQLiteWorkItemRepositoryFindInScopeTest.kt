package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Integration tests for [SQLiteWorkItemRepository.findInScope] and [countInScope].
 *
 * Uses an in-memory SQLite database with the base schema (no FTS5 virtual tables).
 *
 * SQLite is used rather than H2 so the recursive CTE path is exercised — the H2 BFS fallback
 * in [SQLiteWorkItemRepository.resolveScopeIds] is a safety net for tests that explicitly
 * need H2, but scope tests should exercise the SQLite CTE path.
 *
 * Test coverage per task-scope acceptance criteria (Phase 0):
 * 1. Roots included — findInScope({R1}) includes R1 itself (not just descendants)
 * 2. Sibling-root isolation — two trees with identical shapes; only queried root's tree returned
 * 3. Deep tree off-by-one — depth-10 chain; all nodes included, sibling chains excluded
 * 4. Multi-root union — findInScope({R1, R2}) returns both subtrees, no duplicates
 * 5. Empty rootIds — returns empty result immediately
 * 6. countInScope matches findInScope().size
 */
class SQLiteWorkItemRepositoryFindInScopeTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var repository: SQLiteWorkItemRepository
    private lateinit var keepAliveConnection: Connection

    @BeforeEach
    fun setUp() {
        val dbName = "find_in_scope_${System.nanoTime()}"
        val jdbcUrl = "jdbc:sqlite:file:$dbName?mode=memory&cache=shared"
        keepAliveConnection = DriverManager.getConnection(jdbcUrl)
        database = Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        databaseManager = DatabaseManager(database)
        createBaseSchema()
        repository = SQLiteWorkItemRepository(databaseManager)
    }

    @AfterEach
    fun tearDown() {
        try {
            TransactionManager.closeAndUnregister(database)
        } catch (_: Exception) {
        }
        try {
            keepAliveConnection.close()
        } catch (_: Exception) {
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Schema setup
    // ────────────────────────────────────────────────────────────────────────

    private fun createBaseSchema() {
        transaction(db = database) {
            exec(
                """
                CREATE TABLE IF NOT EXISTS work_items (
                    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    parent_id BLOB REFERENCES work_items(id),
                    root_id BLOB,
                    title TEXT NOT NULL,
                    description TEXT,
                    summary TEXT NOT NULL DEFAULT '',
                    role TEXT NOT NULL DEFAULT 'queue'
                        CHECK (role IN ('queue', 'work', 'review', 'blocked', 'terminal')),
                    status_label TEXT,
                    previous_role TEXT CHECK (
                        previous_role IS NULL OR previous_role IN ('queue', 'work', 'review', 'blocked', 'terminal')
                    ),
                    priority TEXT NOT NULL DEFAULT 'medium'
                        CHECK (priority IN ('high', 'medium', 'low')),
                    complexity INTEGER,
                    requires_verification INTEGER NOT NULL DEFAULT 0,
                    depth INTEGER NOT NULL DEFAULT 0,
                    metadata TEXT,
                    tags TEXT,
                    type TEXT,
                    properties TEXT,
                    created_at TIMESTAMP NOT NULL,
                    modified_at TIMESTAMP NOT NULL,
                    role_changed_at TIMESTAMP NOT NULL,
                    version INTEGER NOT NULL DEFAULT 1,
                    claimed_by TEXT DEFAULT NULL,
                    claimed_at TEXT DEFAULT NULL,
                    claim_expires_at TEXT DEFAULT NULL,
                    original_claimed_at TEXT DEFAULT NULL
                )
                """.trimIndent()
            )
            exec("CREATE INDEX IF NOT EXISTS idx_work_items_parent ON work_items(parent_id)")
            exec("CREATE INDEX IF NOT EXISTS idx_work_items_role ON work_items(role)")

            // Minimal sibling tables (repository provider requires them to exist)
            exec(
                """
                CREATE TABLE IF NOT EXISTS notes (
                    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    work_item_id BLOB NOT NULL REFERENCES work_items(id),
                    key TEXT NOT NULL,
                    role TEXT NOT NULL DEFAULT 'queue',
                    body TEXT NOT NULL DEFAULT '',
                    created_at TIMESTAMP NOT NULL,
                    modified_at TIMESTAMP NOT NULL,
                    actor_id TEXT, actor_kind TEXT, actor_parent TEXT, actor_proof TEXT,
                    verification_status TEXT, verification_verifier TEXT, verification_reason TEXT
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE IF NOT EXISTS dependencies (
                    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    from_item_id BLOB NOT NULL REFERENCES work_items(id),
                    to_item_id BLOB NOT NULL REFERENCES work_items(id),
                    type TEXT NOT NULL,
                    unblock_at TEXT,
                    created_at TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE IF NOT EXISTS role_transitions (
                    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    item_id BLOB NOT NULL REFERENCES work_items(id),
                    from_role TEXT,
                    to_role TEXT NOT NULL,
                    trigger TEXT NOT NULL,
                    summary TEXT,
                    transition_at TIMESTAMP NOT NULL,
                    actor_id TEXT, actor_kind TEXT, actor_parent TEXT, actor_proof TEXT,
                    verification_status TEXT, verification_verifier TEXT, verification_reason TEXT
                )
                """.trimIndent()
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun newItem(
        title: String,
        parentId: UUID? = null,
        depth: Int = 0,
        role: Role = Role.QUEUE,
    ): WorkItem =
        WorkItem(
            title = title,
            parentId = parentId,
            depth = depth,
            role = role,
        )

    private fun create(item: WorkItem): WorkItem =
        runBlocking {
            val result = repository.create(item)
            assertIs<Result.Success<WorkItem>>(result, "Expected item '${item.title}' to be created")
            result.data
        }

    private fun findInScope(vararg ids: UUID): List<WorkItem> =
        runBlocking {
            val result = repository.findInScope(rootIds = ids.toSet())
            assertIs<Result.Success<List<WorkItem>>>(result)
            result.data
        }

    private fun countInScope(vararg ids: UUID): Int =
        runBlocking {
            val result = repository.countInScope(rootIds = ids.toSet())
            assertIs<Result.Success<Int>>(result)
            result.data
        }

    // ────────────────────────────────────────────────────────────────────────
    // Test 1: Root is included in the result (CTE seed check)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `findInScope includes the root item itself`(): Unit =
        runBlocking {
            val root = create(newItem("root-A"))
            val child = create(newItem("child-A", parentId = root.id, depth = 1))

            val items = findInScope(root.id)
            val ids = items.map { it.id }.toSet()

            assertTrue(root.id in ids, "Root item must be included in findInScope results")
            assertTrue(child.id in ids, "Child item must be included in findInScope results")
            assertEquals(2, items.size, "Expected exactly 2 items (root + child)")
        }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2: Sibling-root isolation
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `findInScope for R1 does not include items from R2's subtree`(): Unit =
        runBlocking {
            // Two parallel trees with the same shape: root → child → grandchild
            val r1 = create(newItem("root-1"))
            val c1 = create(newItem("child-1", parentId = r1.id, depth = 1))
            val gc1 = create(newItem("grandchild-1", parentId = c1.id, depth = 2))

            val r2 = create(newItem("root-2"))
            val c2 = create(newItem("child-2", parentId = r2.id, depth = 1))
            val gc2 = create(newItem("grandchild-2", parentId = c2.id, depth = 2))

            // Query only R1's subtree
            val items = findInScope(r1.id)
            val ids = items.map { it.id }.toSet()

            assertTrue(r1.id in ids, "R1 root must be included")
            assertTrue(c1.id in ids, "R1 child must be included")
            assertTrue(gc1.id in ids, "R1 grandchild must be included")
            assertTrue(r2.id !in ids, "R2 root must NOT be included when querying R1's scope")
            assertTrue(c2.id !in ids, "R2 child must NOT be included")
            assertTrue(gc2.id !in ids, "R2 grandchild must NOT be included")
            assertEquals(3, items.size)
        }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3: Deep tree off-by-one (depth-10 chain)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `findInScope returns all 10 nodes of a depth-10 chain and excludes sibling chains`(): Unit =
        runBlocking {
            // Build a linear chain: root → c1 → c2 → … → c9 (10 total items)
            val chain = mutableListOf<WorkItem>()
            var parentId: UUID? = null
            for (i in 0 until 10) {
                val item = create(newItem("node-$i", parentId = parentId, depth = i))
                chain.add(item)
                parentId = item.id
            }
            val chainRoot = chain.first()

            // Build a separate sibling chain (also 3 nodes) not connected to the main chain
            val sibRoot = create(newItem("sib-root"))
            val sibC1 = create(newItem("sib-c1", parentId = sibRoot.id, depth = 1))
            val sibC2 = create(newItem("sib-c2", parentId = sibC1.id, depth = 2))

            val items = findInScope(chainRoot.id)
            val ids = items.map { it.id }.toSet()

            // All 10 nodes in the main chain must be present
            for (node in chain) {
                assertTrue(node.id in ids, "Node '${node.title}' at depth ${node.depth} must be in scope")
            }
            assertEquals(10, items.size, "Expected exactly 10 items in depth-10 chain scope")

            // Sibling chain must be excluded
            assertTrue(sibRoot.id !in ids, "Sibling root must not be in scope")
            assertTrue(sibC1.id !in ids, "Sibling child must not be in scope")
            assertTrue(sibC2.id !in ids, "Sibling grandchild must not be in scope")
        }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4: Multi-root union (no duplicates)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `findInScope with multiple roots returns union of both subtrees without duplicates`(): Unit =
        runBlocking {
            val r1 = create(newItem("root-1"))
            val c1 = create(newItem("child-1", parentId = r1.id, depth = 1))

            val r2 = create(newItem("root-2"))
            val c2 = create(newItem("child-2", parentId = r2.id, depth = 1))

            val unrelated = create(newItem("unrelated-root"))
            val unrelatedChild = create(newItem("unrelated-child", parentId = unrelated.id, depth = 1))

            val items = findInScope(r1.id, r2.id)
            val ids = items.map { it.id }.toSet()

            // Both subtrees included
            assertTrue(r1.id in ids)
            assertTrue(c1.id in ids)
            assertTrue(r2.id in ids)
            assertTrue(c2.id in ids)

            // Unrelated subtree excluded
            assertTrue(unrelated.id !in ids)
            assertTrue(unrelatedChild.id !in ids)

            // No duplicates
            assertEquals(items.size, ids.size, "findInScope must not return duplicate items")
            assertEquals(4, items.size)
        }

    // ────────────────────────────────────────────────────────────────────────
    // Test 5: Empty rootIds → empty result
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `findInScope with empty rootIds returns empty list`(): Unit =
        runBlocking {
            create(newItem("some-item"))

            val result = repository.findInScope(rootIds = emptySet())
            assertIs<Result.Success<List<WorkItem>>>(result)
            assertTrue(result.data.isEmpty(), "Empty rootIds must produce an empty result")
        }

    @Test
    fun `countInScope with empty rootIds returns 0`(): Unit =
        runBlocking {
            create(newItem("some-item"))

            val result = repository.countInScope(rootIds = emptySet())
            assertIs<Result.Success<Int>>(result)
            assertEquals(0, result.data)
        }

    // ────────────────────────────────────────────────────────────────────────
    // Test 6: countInScope matches findInScope().size
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `countInScope matches findInScope size across all fixture shapes`(): Unit =
        runBlocking {
            // Single root with 2 children
            val r = create(newItem("root"))
            create(newItem("c1", parentId = r.id, depth = 1))
            create(newItem("c2", parentId = r.id, depth = 1))

            // Unrelated item (should not be counted)
            create(newItem("unrelated"))

            val findCount = findInScope(r.id).size
            val count = countInScope(r.id)

            assertEquals(findCount, count, "countInScope must match findInScope().size")
            assertEquals(3, count, "Expected root + 2 children = 3")
        }

    @Test
    fun `countInScope matches findInScope for multi-root queries`(): Unit =
        runBlocking {
            val r1 = create(newItem("multi-root-1"))
            val c1a = create(newItem("c1a", parentId = r1.id, depth = 1))
            val c1b = create(newItem("c1b", parentId = r1.id, depth = 1))

            val r2 = create(newItem("multi-root-2"))
            val c2a = create(newItem("c2a", parentId = r2.id, depth = 1))

            val unrelated = create(newItem("unrelated-for-multi"))

            val findItems = findInScope(r1.id, r2.id)
            val count = countInScope(r1.id, r2.id)

            assertEquals(findItems.size, count, "countInScope must match findInScope().size for multi-root")
            assertEquals(5, count, "Expected r1, c1a, c1b, r2, c2a = 5 items")

            // The unrelated item must not be counted
            assertTrue(findItems.none { it.id == unrelated.id })
        }

    // ────────────────────────────────────────────────────────────────────────
    // Test 7: Filter combination (role filter within scope)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `findInScope with role filter returns only matching items within scope`(): Unit =
        runBlocking {
            val root = create(newItem("root-filter"))
            val queueChild = create(newItem("queue-child", parentId = root.id, depth = 1, role = Role.QUEUE))
            val workChild = create(newItem("work-child", parentId = root.id, depth = 1, role = Role.WORK))

            val result =
                runBlocking {
                    repository.findInScope(rootIds = setOf(root.id), role = Role.WORK)
                }
            assertIs<Result.Success<List<WorkItem>>>(result)

            val ids = result.data.map { it.id }.toSet()
            assertTrue(workChild.id in ids, "WORK-role child must be included")
            assertTrue(queueChild.id !in ids, "QUEUE-role child must be excluded by role filter")
            // Root itself has role QUEUE so it should also be excluded
            assertTrue(root.id !in ids, "Root (QUEUE) must be excluded by role=WORK filter")

            assertEquals(1, result.data.size)
        }
}
