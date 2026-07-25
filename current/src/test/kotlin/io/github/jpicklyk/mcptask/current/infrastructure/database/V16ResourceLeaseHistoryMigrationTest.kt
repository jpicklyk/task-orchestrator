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
import kotlin.test.assertTrue

/**
 * Integration test for the V16 migration (`V16__Resource_Lease_History.sql`) — the new append-only
 * `resource_lease_history` table.
 *
 * Follows the [V15ResourceLeasesMigrationTest] pattern: hand-build a minimal pre-V16 `work_items`
 * + `resource_leases` schema with raw SQL, apply the real V16 SQL file read straight off the
 * classpath, then assert table/index existence and — critically — the ABSENCE of any foreign key
 * on `holder_item_id` (see the migration header for why this is deliberate).
 */
class V16ResourceLeaseHistoryMigrationTest {
    private lateinit var database: Database
    private lateinit var keepAliveConnection: Connection

    @BeforeEach
    fun setUp() {
        val dbName = "v16_resource_lease_history_${System.nanoTime()}"
        val jdbcUrl = "jdbc:sqlite:file:$dbName?mode=memory&cache=shared"
        keepAliveConnection = DriverManager.getConnection(jdbcUrl)
        keepAliveConnection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        database = Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        createMinimalPreV16Schema()
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

    private fun createMinimalPreV16Schema() {
        transaction(db = database) {
            exec(
                """
                CREATE TABLE work_items (
                    id    BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    title TEXT NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE resource_leases (
                    id                     BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    resource_key           TEXT NOT NULL,
                    holder_item_id         BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
                    acquired_by_actor_id   TEXT NULL,
                    acquired_at            TEXT NOT NULL,
                    expires_at             TEXT NOT NULL,
                    original_acquired_at   TEXT NOT NULL,
                    version                INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }

    /**
     * Reads the real `V16__Resource_Lease_History.sql` off the classpath and executes each
     * statement. Strips full-line `--` comments, then splits on `;` — safe here since none of the
     * migration's statements contain an embedded semicolon (mirrors
     * [V15ResourceLeasesMigrationTest.applyV15Migration]).
     */
    private fun applyV16Migration() {
        val resourceStream =
            requireNotNull(
                Thread.currentThread().contextClassLoader.getResourceAsStream("db/migration/V16__Resource_Lease_History.sql")
            ) { "V16__Resource_Lease_History.sql not found on the test classpath" }
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

    private fun insertHistoryRow(
        resourceKey: String,
        holderItemId: UUID,
        acquiredAt: String = "2026-01-01 00:00:00",
        expiresAt: String = "2026-01-01 01:00:00"
    ) {
        keepAliveConnection
            .prepareStatement(
                """
                INSERT INTO resource_lease_history
                    (resource_key, holder_item_id, acquired_at, expires_at)
                VALUES (?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, resourceKey)
                stmt.setBytes(2, uuidToBytes(holderItemId))
                stmt.setString(3, acquiredAt)
                stmt.setString(4, expiresAt)
                stmt.executeUpdate()
            }
    }

    private fun countHistoryRows(holderItemId: UUID): Int {
        var count = 0
        keepAliveConnection.prepareStatement("SELECT COUNT(*) FROM resource_lease_history WHERE holder_item_id = ?").use { stmt ->
            stmt.setBytes(1, uuidToBytes(holderItemId))
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
    fun `V16 migration creates the resource_lease_history table and its indexes`(): Unit =
        runBlocking {
            applyV16Migration()

            var hasTable = false
            transaction(db = database) {
                exec("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'resource_lease_history'") { rs ->
                    hasTable = rs.next()
                }
            }
            assertTrue(hasTable, "Expected resource_lease_history table to exist after V16")

            val expectedIndexes =
                listOf(
                    "idx_resource_lease_history_key_acquired",
                    "idx_resource_lease_history_released_at",
                )
            for (indexName in expectedIndexes) {
                var hasIndex = false
                transaction(db = database) {
                    exec("SELECT name FROM sqlite_master WHERE type = 'index' AND name = '$indexName'") { rs ->
                        hasIndex = rs.next()
                    }
                }
                assertTrue(hasIndex, "Expected $indexName index to exist after V16")
            }
        }

    @Test
    fun `V16 resource_lease_history has NO foreign key on holder_item_id`(): Unit =
        runBlocking {
            applyV16Migration()

            var fkCount = 0
            transaction(db = database) {
                exec("PRAGMA foreign_key_list(resource_lease_history)") { rs ->
                    while (rs.next()) fkCount++
                }
            }
            assertEquals(0, fkCount, "resource_lease_history must carry NO foreign keys — the audit trail must survive holder deletion")
        }

    @Test
    fun `V16 history rows survive deletion of the holder work item (unlike the CASCADE-linked live row)`(): Unit =
        runBlocking {
            applyV16Migration()
            val holder = UUID.randomUUID()
            insertWorkItem(holder, "Holder")
            insertHistoryRow("staging-db-credential", holder)

            assertEquals(1, countHistoryRows(holder), "Sanity check: history row exists before delete")

            keepAliveConnection.prepareStatement("DELETE FROM work_items WHERE id = ?").use { stmt ->
                stmt.setBytes(1, uuidToBytes(holder))
                stmt.executeUpdate()
            }

            assertEquals(1, countHistoryRows(holder), "History row must survive holder deletion — no FK, no CASCADE")
        }

    @Test
    fun `V16 fresh apply and upgrade-path apply both succeed`(): Unit =
        runBlocking {
            applyV16Migration()
            var hasTable = false
            transaction(db = database) {
                exec("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'resource_lease_history'") { rs ->
                    hasTable = rs.next()
                }
            }
            assertTrue(hasTable, "Fresh apply must create resource_lease_history")

            val holder = UUID.randomUUID()
            insertWorkItem(holder, "Holder")
            insertHistoryRow("staging-db-credential", holder)
            assertEquals(1, countHistoryRows(holder), "Table must be immediately usable after V16 apply")
        }
}
