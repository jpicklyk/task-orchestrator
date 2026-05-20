package io.github.jpicklyk.mcptask.current.infrastructure.repository

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
 * Integration tests verifying unbounded hierarchy depth after the depth-cap removal in V7.
 *
 * Uses an in-memory SQLite database with base schema + cycle-detection triggers.
 * FTS5 virtual tables are NOT created since hierarchy tests don't require FTS5.
 * The `findDescendants` function uses ExposedConnection.prepareStatement() + executeQuery()
 * for the WITH RECURSIVE CTE to avoid the xerial/sqlite-jdbc driver rejecting exec()
 * calls on SELECT-returning CTEs with "Query returns results".
 *
 * Test names follow plan §16.5 — communicating agent-visible behaviour.
 */
class DeepHierarchyTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var repository: SQLiteWorkItemRepository
    private lateinit var keepAliveConnection: Connection

    @BeforeEach
    fun setUp() {
        val dbName = "deep_hierarchy_${System.nanoTime()}"
        val jdbcUrl = "jdbc:sqlite:file:$dbName?mode=memory&cache=shared"
        keepAliveConnection = DriverManager.getConnection(jdbcUrl)
        database = Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        databaseManager = DatabaseManager(database)
        createSchema()
        repository = SQLiteWorkItemRepository(databaseManager)
    }

    /**
     * Creates the minimum schema needed for hierarchy tests:
     * - work_items table (base schema, no depth CHECK constraint)
     * - notes, dependencies, role_transitions (required by repository provider)
     * - Cycle-detection triggers on parent_id
     *
     * FTS5 virtual tables are intentionally omitted — hierarchy tests don't require them.
     */
    private fun createSchema() {
        transaction(db = database) {
            // work_items — no depth CHECK constraint (V7 removed it)
            exec(
                """
                CREATE TABLE IF NOT EXISTS work_items (
                    id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    parent_id BLOB REFERENCES work_items(id),
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
            exec("CREATE INDEX IF NOT EXISTS idx_work_items_depth ON work_items(depth)")
            exec("CREATE INDEX IF NOT EXISTS idx_work_items_priority ON work_items(priority)")
            exec("CREATE INDEX IF NOT EXISTS idx_work_items_role_changed ON work_items(role, role_changed_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_work_items_claimed_by ON work_items(claimed_by)")
            exec("CREATE INDEX IF NOT EXISTS idx_work_items_claim_expires ON work_items(claim_expires_at)")

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

            // Cycle detection — BEFORE INSERT (fires when creating new items with a parent)
            // Mirrors V7 migration + DirectDatabaseSchemaManager: includes self-reference guard
            // because the recursive CTE below cannot catch parent_id = id on INSERT (the row
            // doesn't exist yet, so the ancestor walk seeds empty).
            exec(
                """
                CREATE TRIGGER IF NOT EXISTS work_items_cycle_check
                BEFORE INSERT ON work_items
                WHEN NEW.parent_id IS NOT NULL
                BEGIN
                    SELECT RAISE(ABORT, 'cycle detected: parent_id equals id (self-reference)')
                    WHERE NEW.parent_id = NEW.id;

                    SELECT RAISE(ABORT, 'cycle detected: parent_id references a descendant of this item')
                    WHERE EXISTS (
                        WITH RECURSIVE ancestors(node_id) AS (
                            SELECT parent_id FROM work_items
                            WHERE id = NEW.parent_id AND parent_id IS NOT NULL
                            UNION ALL
                            SELECT wi.parent_id FROM work_items wi
                            JOIN ancestors a ON wi.id = a.node_id
                            WHERE wi.parent_id IS NOT NULL
                        )
                        SELECT 1 FROM ancestors WHERE node_id = NEW.id
                    );
                END
                """.trimIndent()
            )
            // Cycle detection — BEFORE UPDATE OF parent_id (fires on reparenting)
            exec(
                """
                CREATE TRIGGER IF NOT EXISTS work_items_cycle_check_update
                BEFORE UPDATE OF parent_id ON work_items
                WHEN NEW.parent_id IS NOT NULL
                BEGIN
                    SELECT RAISE(ABORT, 'cycle detected: parent_id equals id (self-reference)')
                    WHERE NEW.parent_id = NEW.id;

                    SELECT RAISE(ABORT, 'cycle detected: parent_id references a descendant of this item')
                    WHERE EXISTS (
                        WITH RECURSIVE ancestors(node_id) AS (
                            SELECT parent_id FROM work_items
                            WHERE id = NEW.parent_id AND parent_id IS NOT NULL
                            UNION ALL
                            SELECT wi.parent_id FROM work_items wi
                            JOIN ancestors a ON wi.id = a.node_id
                            WHERE wi.parent_id IS NOT NULL
                        )
                        SELECT 1 FROM ancestors WHERE node_id = NEW.id
                    );
                END
                """.trimIndent()
            )
        }
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
    // Depth creation
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `creates items at depth 7 without error`(): Unit =
        runBlocking {
            var currentParentId: UUID? = null
            var currentDepth = 0

            repeat(7) { level ->
                val item =
                    WorkItem(
                        title = "Depth-$level item",
                        parentId = currentParentId,
                        depth = currentDepth,
                    )
                val result = repository.create(item)
                assertIs<Result.Success<WorkItem>>(result, "Expected depth-$level item to be created without error")
                currentParentId = result.data.id
                currentDepth++
            }

            // 7 items at depths 0..6
            assertEquals(6, currentDepth - 1, "Expected to reach depth 6 (7 items, depths 0..6)")
        }

    @Test
    fun `existing depth-3 items continue to function after depth cap removal`(): Unit =
        runBlocking {
            val root = repository.create(WorkItem(title = "Root", depth = 0))
            assertIs<Result.Success<WorkItem>>(root)
            val d1 = repository.create(WorkItem(title = "Depth 1", parentId = root.data.id, depth = 1))
            assertIs<Result.Success<WorkItem>>(d1)
            val d2 = repository.create(WorkItem(title = "Depth 2", parentId = d1.data.id, depth = 2))
            assertIs<Result.Success<WorkItem>>(d2)
            val d3 = repository.create(WorkItem(title = "Depth 3", parentId = d2.data.id, depth = 3))
            assertIs<Result.Success<WorkItem>>(d3)

            val fetched = repository.getById(d3.data.id)
            assertIs<Result.Success<WorkItem>>(fetched)
            assertEquals(3, fetched.data.depth, "Expected depth-3 item to be retrievable")
            assertEquals("Depth 3", fetched.data.title)
        }

    // ────────────────────────────────────────────────────────────────────────
    // findDescendants — recursive CTE
    //
    // Uses ExposedConnection.prepareStatement() + executeQuery() to run the
    // WITH RECURSIVE CTE, bypassing Exposed's exec() routing through executeUpdate()
    // which the xerial/sqlite-jdbc JDBC driver rejects for SELECT-returning CTEs.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `findDescendants returns full subtree in a single recursive query`(): Unit =
        runBlocking {
            val root = repository.create(WorkItem(title = "Root", depth = 0))
            assertIs<Result.Success<WorkItem>>(root)

            var parentId = root.data.id
            val expectedDescendantIds = mutableSetOf<UUID>()

            for (level in 1..5) {
                val item =
                    repository.create(
                        WorkItem(title = "Level $level", parentId = parentId, depth = level)
                    )
                assertIs<Result.Success<WorkItem>>(item)
                expectedDescendantIds.add(item.data.id)
                parentId = item.data.id
            }

            // findDescendants should return all 5 descendants in a single recursive CTE.
            val result = repository.findDescendants(root.data.id)
            assertIs<Result.Success<List<WorkItem>>>(result)

            val foundIds = result.data.map { it.id }.toSet()
            assertEquals(
                expectedDescendantIds,
                foundIds,
                "findDescendants should return all 5 descendants of the root item"
            )
        }

    @Test
    fun `findDescendants returns empty list for a leaf item with no children`(): Unit =
        runBlocking {
            val leaf = repository.create(WorkItem(title = "Leaf item", depth = 0))
            assertIs<Result.Success<WorkItem>>(leaf)

            val result = repository.findDescendants(leaf.data.id)
            assertIs<Result.Success<List<WorkItem>>>(result)
            assertTrue(result.data.isEmpty(), "Leaf item should have no descendants")
        }

    // ────────────────────────────────────────────────────────────────────────
    // Cycle detection — SQLite trigger
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `cycle detection trigger rejects parent that would form loop`(): Unit =
        runBlocking {
            val root = repository.create(WorkItem(title = "Root item", depth = 0))
            assertIs<Result.Success<WorkItem>>(root)
            val child =
                repository.create(
                    WorkItem(title = "Child item", parentId = root.data.id, depth = 1)
                )
            assertIs<Result.Success<WorkItem>>(child)

            // Attempt to set root.parentId = child (root -> child -> root = cycle).
            // Use raw JDBC to bypass domain validation and trigger the DB trigger directly.
            var triggerFired = false
            var errorMessage = ""
            try {
                keepAliveConnection
                    .prepareStatement(
                        "UPDATE work_items SET parent_id = ? WHERE id = ?"
                    ).use { stmt ->
                        stmt.setBytes(1, uuidToBytes(child.data.id))
                        stmt.setBytes(2, uuidToBytes(root.data.id))
                        stmt.executeUpdate()
                    }
            } catch (e: Exception) {
                triggerFired = true
                errorMessage = e.message ?: ""
            }

            assertTrue(triggerFired, "Expected cycle-detection trigger to reject loop-forming parent_id update")
            assertTrue(
                "cycle" in errorMessage.lowercase(),
                "Expected error to mention 'cycle', got: '$errorMessage'"
            )
        }

    @Test
    fun `cycle detection trigger allows linear chains of arbitrary depth`(): Unit =
        runBlocking {
            var parentId: UUID? = null
            var depth = 0
            for (i in 0 until 10) {
                val item =
                    repository.create(
                        WorkItem(title = "Chain item $i", parentId = parentId, depth = depth)
                    )
                assertIs<Result.Success<WorkItem>>(item, "Expected chain item $i to be created without cycle error")
                parentId = item.data.id
                depth++
            }
            assertEquals(9, depth - 1, "Expected 10 items at depths 0..9")
        }

    @Test
    fun `INSERT with parent_id equal to id is rejected by self-reference trigger`(): Unit =
        runBlocking {
            // The recursive-CTE guard cannot catch this on INSERT because the row being
            // written doesn't yet exist — the ancestor walk seeds empty. The dedicated
            // NEW.parent_id = NEW.id check fires instead.
            val selfId = UUID.randomUUID()
            var triggerFired = false
            var errorMessage = ""
            try {
                keepAliveConnection
                    .prepareStatement(
                        """
                        INSERT INTO work_items (id, parent_id, title, summary, role, priority, depth,
                            created_at, modified_at, role_changed_at)
                        VALUES (?, ?, 'Self-parent', '', 'queue', 'medium', 0,
                            datetime('now'), datetime('now'), datetime('now'))
                        """.trimIndent()
                    ).use { stmt ->
                        val bytes = uuidToBytes(selfId)
                        stmt.setBytes(1, bytes)
                        stmt.setBytes(2, bytes)
                        stmt.executeUpdate()
                    }
            } catch (e: Exception) {
                triggerFired = true
                errorMessage = e.message ?: ""
            }

            assertTrue(triggerFired, "Expected self-reference trigger to reject INSERT where parent_id = id")
            assertTrue(
                "self-reference" in errorMessage.lowercase() || "cycle" in errorMessage.lowercase(),
                "Expected error to mention 'cycle' or 'self-reference', got: '$errorMessage'"
            )
        }

    @Test
    fun `UPDATE setting parent_id equal to id is rejected by self-reference trigger`(): Unit =
        runBlocking {
            val item = repository.create(WorkItem(title = "Standalone item", depth = 0))
            assertIs<Result.Success<WorkItem>>(item)

            var triggerFired = false
            var errorMessage = ""
            try {
                keepAliveConnection
                    .prepareStatement("UPDATE work_items SET parent_id = ? WHERE id = ?")
                    .use { stmt ->
                        val bytes = uuidToBytes(item.data.id)
                        stmt.setBytes(1, bytes)
                        stmt.setBytes(2, bytes)
                        stmt.executeUpdate()
                    }
            } catch (e: Exception) {
                triggerFired = true
                errorMessage = e.message ?: ""
            }

            assertTrue(triggerFired, "Expected self-reference trigger to reject UPDATE setting parent_id = id")
            assertTrue(
                "self-reference" in errorMessage.lowercase() || "cycle" in errorMessage.lowercase(),
                "Expected error to mention 'cycle' or 'self-reference', got: '$errorMessage'"
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // Helper
    // ────────────────────────────────────────────────────────────────────────

    private fun uuidToBytes(id: UUID): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(16)
        buf.putLong(id.mostSignificantBits)
        buf.putLong(id.leastSignificantBits)
        return buf.array()
    }
}
