package io.github.jpicklyk.mcptask.current.infrastructure.database

import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test for the V9 migration (`V9__Add_Root_Id.sql`) — the denormalized `root_id`
 * column and its recursive-CTE backfill.
 *
 * Follows the [Fts5MigrationTest] pattern: hand-build the *pre-migration* schema (V7/V8-final
 * state, no `root_id` column) with raw SQL, seed rows with raw JDBC inserts (the domain
 * repository can't be used here — it now always writes `root_id`, which doesn't exist until
 * this migration runs), then apply the real V9 SQL file read straight off the classpath (not a
 * hand-copied duplicate, to avoid drift between this test and the migration it verifies) and
 * assert the backfilled values.
 */
class V9RootIdMigrationTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var keepAliveConnection: Connection

    @BeforeEach
    fun setUp() {
        val dbName = "v9_root_id_${System.nanoTime()}"
        val jdbcUrl = "jdbc:sqlite:file:$dbName?mode=memory&cache=shared"
        keepAliveConnection = DriverManager.getConnection(jdbcUrl)
        database = Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        databaseManager = DatabaseManager(database)
        createPreV9Schema()
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
    // Pre-migration (V7/V8-final) schema — deliberately WITHOUT root_id
    // ────────────────────────────────────────────────────────────────────────

    private fun createPreV9Schema() {
        transaction(db = database) {
            exec(
                """
                CREATE TABLE work_items (
                    id                   BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    parent_id            BLOB REFERENCES work_items(id),
                    title                TEXT NOT NULL,
                    description          TEXT,
                    summary              TEXT NOT NULL DEFAULT '',
                    role                 TEXT NOT NULL DEFAULT 'queue',
                    status_label         TEXT,
                    previous_role        TEXT,
                    priority             TEXT NOT NULL DEFAULT 'medium',
                    complexity           INTEGER,
                    requires_verification INTEGER NOT NULL DEFAULT 0,
                    depth                INTEGER NOT NULL DEFAULT 0,
                    metadata             TEXT,
                    tags                 TEXT,
                    type                 TEXT,
                    properties           TEXT,
                    created_at           TIMESTAMP NOT NULL,
                    modified_at          TIMESTAMP NOT NULL,
                    role_changed_at      TIMESTAMP NOT NULL,
                    version              INTEGER NOT NULL DEFAULT 1,
                    claimed_by           TEXT DEFAULT NULL,
                    claimed_at           TEXT DEFAULT NULL,
                    claim_expires_at     TEXT DEFAULT NULL,
                    original_claimed_at  TEXT DEFAULT NULL
                )
                """.trimIndent()
            )
            exec("CREATE INDEX idx_work_items_parent ON work_items(parent_id)")
            exec("CREATE INDEX idx_work_items_role ON work_items(role)")
            exec("CREATE INDEX idx_work_items_depth ON work_items(depth)")
        }
    }

    /**
     * Reads the real `V9__Add_Root_Id.sql` off the classpath and executes each statement.
     * Strips full-line `--` comments, then splits on `;` — safe here because none of the
     * migration's statements contain an embedded semicolon.
     */
    private fun applyV9Migration() {
        val resourceStream =
            requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("db/migration/V9__Add_Root_Id.sql")) {
                "V9__Add_Root_Id.sql not found on the test classpath"
            }
        val sqlText = resourceStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val withoutComments =
            sqlText
                .lineSequence()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
        val statements =
            withoutComments
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        transaction(db = database) {
            statements.forEach { statement -> exec(statement) }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Raw seeding / reading helpers (bypass the repository — root_id doesn't exist pre-migration)
    // ────────────────────────────────────────────────────────────────────────

    private fun insertRawItem(
        id: UUID,
        parentId: UUID?,
        title: String,
        depth: Int = if (parentId == null) 0 else 1,
    ) {
        // created_at/modified_at/role_changed_at must be bound as a JDBC java.sql.Timestamp,
        // matching what Exposed's timestamp() column type writes/reads through the driver.
        // Neither a raw ISO-8601 string (java.time.Instant.toString()) nor SQLite's own
        // datetime('now') text format is what the bundled xerial/sqlite-jdbc driver expects when
        // parsing a TIMESTAMP column back out — both produce "Error parsing time stamp" on read.
        val now = java.sql.Timestamp.from(java.time.Instant.now())
        keepAliveConnection
            .prepareStatement(
                """
                INSERT INTO work_items
                    (id, parent_id, title, summary, role, priority, depth,
                     created_at, modified_at, role_changed_at, version)
                VALUES (?, ?, ?, '', 'queue', 'medium', ?, ?, ?, ?, 1)
                """.trimIndent()
            ).use { stmt ->
                stmt.setBytes(1, uuidToBytes(id))
                if (parentId != null) stmt.setBytes(2, uuidToBytes(parentId)) else stmt.setNull(2, java.sql.Types.BLOB)
                stmt.setString(3, title)
                stmt.setInt(4, depth)
                stmt.setTimestamp(5, now)
                stmt.setTimestamp(6, now)
                stmt.setTimestamp(7, now)
                stmt.executeUpdate()
            }
    }

    private fun rootIdOfRaw(id: UUID): UUID? {
        var result: UUID? = null
        keepAliveConnection.prepareStatement("SELECT root_id FROM work_items WHERE id = ?").use { stmt ->
            stmt.setBytes(1, uuidToBytes(id))
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    result = rs.getBytes("root_id")?.let { bytesToUuid(it) }
                }
            }
        }
        return result
    }

    private fun uuidToBytes(id: UUID): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(16)
        buf.putLong(id.mostSignificantBits)
        buf.putLong(id.leastSignificantBits)
        return buf.array()
    }

    private fun bytesToUuid(bytes: ByteArray): UUID {
        val buf = java.nio.ByteBuffer.wrap(bytes)
        return UUID(buf.long, buf.long)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tests
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `V9 migration adds the root_id column and index`(): Unit =
        runBlocking {
            applyV9Migration()

            var hasColumn = false
            transaction(db = database) {
                exec("PRAGMA table_info(work_items)") { rs ->
                    while (rs.next()) {
                        if (rs.getString("name") == "root_id") hasColumn = true
                    }
                }
            }
            assertTrue(hasColumn, "Expected work_items.root_id column to exist after V9")

            var hasIndex = false
            transaction(db = database) {
                exec("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_work_items_root_id'") { rs ->
                    hasIndex = rs.next()
                }
            }
            assertTrue(hasIndex, "Expected idx_work_items_root_id index to exist after V9")
        }

    @Test
    fun `V9 backfill assigns root_id correctly on a seeded multi-root 3-level tree`(): Unit =
        runBlocking {
            // Root A -> B -> C (3 levels)
            val rootA = UUID.randomUUID()
            val b = UUID.randomUUID()
            val c = UUID.randomUUID()
            insertRawItem(rootA, null, "Root A")
            insertRawItem(b, rootA, "B")
            insertRawItem(c, b, "C")

            // Independent second root: Root B -> D
            val rootB = UUID.randomUUID()
            val d = UUID.randomUUID()
            insertRawItem(rootB, null, "Root B")
            insertRawItem(d, rootB, "D")

            // Orphan: parent_id points at an id that was never inserted — must stay NULL,
            // the recursive CTE never reaches it (it isn't seeded from a parent_id IS NULL row
            // and its "parent" doesn't exist to be walked from either).
            val orphan = UUID.randomUUID()
            insertRawItem(orphan, UUID.randomUUID(), "Orphan")

            applyV9Migration()

            assertEquals(rootA, rootIdOfRaw(rootA), "Root A must be its own root")
            assertEquals(rootA, rootIdOfRaw(b), "B must inherit Root A's id")
            assertEquals(rootA, rootIdOfRaw(c), "C (grandchild) must inherit Root A's id")

            assertEquals(rootB, rootIdOfRaw(rootB), "Root B must be its own root")
            assertEquals(rootB, rootIdOfRaw(d), "D must inherit Root B's id, not Root A's")

            assertNull(rootIdOfRaw(orphan), "Orphaned row (unreachable parent) must remain NULL after backfill")
        }

    @Test
    fun `V9 backfill is visible through the repository mapper after migration`(): Unit =
        runBlocking {
            val root = UUID.randomUUID()
            val child = UUID.randomUUID()
            insertRawItem(root, null, "Root")
            insertRawItem(child, root, "Child")

            applyV9Migration()

            val repository = SQLiteWorkItemRepository(databaseManager)
            val rootResult = repository.getById(root)
            val childResult = repository.getById(child)

            assertIs<Result.Success<*>>(rootResult)
            assertIs<Result.Success<*>>(childResult)
            assertEquals(root, (rootResult as Result.Success).data.rootId)
            assertEquals(root, (childResult as Result.Success).data.rootId)
        }
}
