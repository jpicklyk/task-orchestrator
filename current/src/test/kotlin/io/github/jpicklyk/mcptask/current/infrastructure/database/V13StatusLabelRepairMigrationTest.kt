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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Integration test for the V13 migration (`V13__Repair_Terminal_Status_Labels.sql`) — the
 * data-only repair for bug 100da214 (a "start" trigger resolving to TERMINAL stamped the
 * work-phase "in-progress" label instead of the terminal "done" label).
 *
 * Follows the [V12PlanDocumentsMigrationTest] pattern: hand-build a minimal pre-V13 `work_items`
 * table with raw SQL (just the columns the migration touches), apply the real V13 SQL file read
 * straight off the classpath, then assert the UPDATE's effect via raw JDBC.
 */
class V13StatusLabelRepairMigrationTest {
    private lateinit var database: Database
    private lateinit var keepAliveConnection: Connection

    @BeforeEach
    fun setUp() {
        val dbName = "v13_status_label_repair_${System.nanoTime()}"
        val jdbcUrl = "jdbc:sqlite:file:$dbName?mode=memory&cache=shared"
        keepAliveConnection = DriverManager.getConnection(jdbcUrl)
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
                    id           BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    title        TEXT NOT NULL,
                    role         TEXT NOT NULL DEFAULT 'queue',
                    status_label TEXT
                )
                """.trimIndent()
            )
        }
    }

    /**
     * Reads the real `V13__Repair_Terminal_Status_Labels.sql` off the classpath and executes each
     * statement. Strips full-line `--` comments, then splits on `;` (mirrors
     * [V12PlanDocumentsMigrationTest.applyV12Migration]) — safe here since the migration's single
     * UPDATE statement contains no embedded semicolon.
     */
    private fun applyV13Migration() {
        val resourceStream =
            requireNotNull(
                Thread.currentThread().contextClassLoader.getResourceAsStream(
                    "db/migration/V13__Repair_Terminal_Status_Labels.sql"
                )
            ) { "V13__Repair_Terminal_Status_Labels.sql not found on the test classpath" }
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
        title: String,
        role: String,
        statusLabel: String?
    ) {
        keepAliveConnection
            .prepareStatement("INSERT INTO work_items (id, title, role, status_label) VALUES (?, ?, ?, ?)")
            .use { stmt ->
                stmt.setBytes(1, uuidToBytes(id))
                stmt.setString(2, title)
                stmt.setString(3, role)
                if (statusLabel != null) stmt.setString(4, statusLabel) else stmt.setNull(4, java.sql.Types.VARCHAR)
                stmt.executeUpdate()
            }
    }

    private fun readStatusLabel(id: UUID): String? {
        var result: String? = null
        keepAliveConnection.prepareStatement("SELECT status_label FROM work_items WHERE id = ?").use { stmt ->
            stmt.setBytes(1, uuidToBytes(id))
            stmt.executeQuery().use { rs ->
                if (rs.next()) result = rs.getString(1)
            }
        }
        return result
    }

    private fun uuidToBytes(id: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(id.mostSignificantBits)
        buf.putLong(id.leastSignificantBits)
        return buf.array()
    }

    @Test
    fun `V13 repairs a terminal row mislabeled in-progress to done`(): Unit =
        runBlocking {
            val id = UUID.randomUUID()
            insertWorkItem(id, "Mislabeled Terminal", role = "terminal", statusLabel = "in-progress")

            applyV13Migration()

            assertEquals("done", readStatusLabel(id), "Terminal row stuck on the work-phase label must be repaired to 'done'")
        }

    @Test
    fun `V13 does not touch a cancelled terminal row`(): Unit =
        runBlocking {
            val id = UUID.randomUUID()
            insertWorkItem(id, "Cancelled", role = "terminal", statusLabel = "cancelled")

            applyV13Migration()

            assertEquals("cancelled", readStatusLabel(id), "Cancelled rows must be left untouched")
        }

    @Test
    fun `V13 is idempotent for a terminal row already labeled done`(): Unit =
        runBlocking {
            val id = UUID.randomUUID()
            insertWorkItem(id, "Already Done", role = "terminal", statusLabel = "done")

            applyV13Migration()

            assertEquals("done", readStatusLabel(id))
        }

    @Test
    fun `V13 does not touch a non-terminal row`(): Unit =
        runBlocking {
            val id = UUID.randomUUID()
            insertWorkItem(id, "Still In Progress", role = "work", statusLabel = "in-progress")

            applyV13Migration()

            assertEquals("in-progress", readStatusLabel(id), "Non-terminal rows must be left untouched")
        }

    @Test
    fun `V13 leaves a terminal row with a NULL status_label untouched`(): Unit =
        runBlocking {
            val id = UUID.randomUUID()
            insertWorkItem(id, "Null Label Terminal", role = "terminal", statusLabel = null)

            applyV13Migration()

            assertNull(readStatusLabel(id), "NULL status_label on a terminal row is not this bug's symptom and must be left alone")
        }
}
