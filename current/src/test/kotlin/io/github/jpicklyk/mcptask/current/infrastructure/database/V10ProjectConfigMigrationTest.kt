package io.github.jpicklyk.mcptask.current.infrastructure.database

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for the V10 migration (`V10__Project_Config.sql`) — the new `project_config`
 * table and its `ON DELETE CASCADE` relationship to `work_items`.
 *
 * Follows the [V9RootIdMigrationTest] pattern: hand-build a minimal pre-V10 `work_items` table
 * with raw SQL (only the columns needed to insert/delete rows — `project_config` only references
 * `work_items.id`), apply the real V10 SQL file read straight off the classpath, then assert
 * table/index existence, the unique constraint on `root_item_id`, and cascade-delete behavior via
 * raw JDBC. `PRAGMA foreign_keys = ON` is set explicitly here (unlike [V9RootIdMigrationTest],
 * which doesn't need FK enforcement for an unconstrained column) since cascade delete IS the
 * behavior under test.
 */
class V10ProjectConfigMigrationTest {
    private lateinit var database: Database
    private lateinit var keepAliveConnection: Connection

    @BeforeEach
    fun setUp() {
        val dbName = "v10_project_config_${System.nanoTime()}"
        val jdbcUrl = "jdbc:sqlite:file:$dbName?mode=memory&cache=shared"
        keepAliveConnection = DriverManager.getConnection(jdbcUrl)
        keepAliveConnection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        database = Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        createMinimalWorkItemsTable()
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

    private fun createMinimalWorkItemsTable() {
        transaction(db = database) {
            exec(
                """
                CREATE TABLE work_items (
                    id    BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    title TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    /**
     * Reads the real `V10__Project_Config.sql` off the classpath and executes each statement.
     * Strips full-line `--` comments, then splits on `;` — safe here since none of the migration's
     * statements contain an embedded semicolon (mirrors [V9RootIdMigrationTest.applyV9Migration]).
     */
    private fun applyV10Migration() {
        val resourceStream =
            requireNotNull(
                Thread.currentThread().contextClassLoader.getResourceAsStream("db/migration/V10__Project_Config.sql")
            ) { "V10__Project_Config.sql not found on the test classpath" }
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

    private fun insertWorkItem(
        id: UUID,
        title: String
    ) {
        keepAliveConnection.prepareStatement("INSERT INTO work_items (id, title) VALUES (?, ?)").use { stmt ->
            stmt.setBytes(1, uuidToBytes(id))
            stmt.setString(2, title)
            stmt.executeUpdate()
        }
    }

    private fun insertProjectConfig(
        id: UUID,
        rootItemId: UUID,
        configYaml: String,
        fingerprint: String
    ) {
        keepAliveConnection
            .prepareStatement(
                """
                INSERT INTO project_config (id, root_item_id, config_yaml, fingerprint, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setBytes(1, uuidToBytes(id))
                stmt.setBytes(2, uuidToBytes(rootItemId))
                stmt.setString(3, configYaml)
                stmt.setString(4, fingerprint)
                stmt.setTimestamp(5, java.sql.Timestamp.from(Instant.now()))
                stmt.executeUpdate()
            }
    }

    private fun countProjectConfigRows(rootItemId: UUID): Int {
        var count = 0
        keepAliveConnection.prepareStatement("SELECT COUNT(*) FROM project_config WHERE root_item_id = ?").use { stmt ->
            stmt.setBytes(1, uuidToBytes(rootItemId))
            stmt.executeQuery().use { rs ->
                if (rs.next()) count = rs.getInt(1)
            }
        }
        return count
    }

    private fun uuidToBytes(id: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(id.mostSignificantBits)
        buf.putLong(id.leastSignificantBits)
        return buf.array()
    }

    @Test
    fun `V10 migration creates the project_config table and unique index`(): Unit =
        runBlocking {
            applyV10Migration()

            var hasTable = false
            transaction(db = database) {
                exec("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'project_config'") { rs ->
                    hasTable = rs.next()
                }
            }
            assertTrue(hasTable, "Expected project_config table to exist after V10")

            var hasIndex = false
            transaction(db = database) {
                exec(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_project_config_root_item_id'"
                ) { rs ->
                    hasIndex = rs.next()
                }
            }
            assertTrue(hasIndex, "Expected idx_project_config_root_item_id index to exist after V10")
        }

    @Test
    fun `V10 root_item_id is unique — a second insert for the same root fails`(): Unit =
        runBlocking {
            applyV10Migration()
            val root = UUID.randomUUID()
            insertWorkItem(root, "Root")
            insertProjectConfig(UUID.randomUUID(), root, "work_item_schemas: {}", "fp1")

            var threw = false
            try {
                insertProjectConfig(UUID.randomUUID(), root, "work_item_schemas: {}", "fp2")
            } catch (e: SQLException) {
                threw = true
            }
            assertTrue(threw, "Expected a UNIQUE constraint violation on a second insert for the same root_item_id")
        }

    @Test
    fun `V10 deleting the root work item cascades to delete its project_config row`(): Unit =
        runBlocking {
            applyV10Migration()
            val root = UUID.randomUUID()
            insertWorkItem(root, "Root")
            insertProjectConfig(UUID.randomUUID(), root, "work_item_schemas: {}", "fp1")

            assertEquals(1, countProjectConfigRows(root), "Sanity check: config row exists before delete")

            keepAliveConnection.prepareStatement("DELETE FROM work_items WHERE id = ?").use { stmt ->
                stmt.setBytes(1, uuidToBytes(root))
                stmt.executeUpdate()
            }

            assertEquals(0, countProjectConfigRows(root), "Expected ON DELETE CASCADE to remove the project_config row")
        }
}
