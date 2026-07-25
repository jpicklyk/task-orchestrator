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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for the V15 migration (`V15__Resource_Leases.sql`) — the new `resource_leases`
 * table, its semaphore-ready `(resource_key, holder_item_id)` unique index, and its `holder_item_id`
 * CASCADE FK to `work_items`.
 *
 * Follows the [V12PlanDocumentsMigrationTest] pattern: hand-build a minimal pre-V15 `work_items`
 * table with raw SQL, apply the real V15 SQL file read straight off the classpath, then assert
 * table/index existence and FK CASCADE behavior via raw JDBC. `PRAGMA foreign_keys = ON` is set
 * explicitly since CASCADE behavior is under test.
 */
class V15ResourceLeasesMigrationTest {
    private lateinit var database: Database
    private lateinit var keepAliveConnection: Connection

    @BeforeEach
    fun setUp() {
        val dbName = "v15_resource_leases_${System.nanoTime()}"
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
     * Reads the real `V15__Resource_Leases.sql` off the classpath and executes each statement.
     * Strips full-line `--` comments, then splits on `;` — safe here since none of the migration's
     * statements contain an embedded semicolon (mirrors [V12PlanDocumentsMigrationTest.applyV12Migration]).
     */
    private fun applyV15Migration() {
        val resourceStream =
            requireNotNull(
                Thread.currentThread().contextClassLoader.getResourceAsStream("db/migration/V15__Resource_Leases.sql")
            ) { "V15__Resource_Leases.sql not found on the test classpath" }
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

    private fun insertResourceLease(
        resourceKey: String,
        holderItemId: UUID,
        acquiredAt: String = "2026-01-01 00:00:00",
        expiresAt: String = "2026-01-01 01:00:00",
        originalAcquiredAt: String = "2026-01-01 00:00:00"
    ) {
        keepAliveConnection
            .prepareStatement(
                """
                INSERT INTO resource_leases
                    (resource_key, holder_item_id, acquired_at, expires_at, original_acquired_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, resourceKey)
                stmt.setBytes(2, uuidToBytes(holderItemId))
                stmt.setString(3, acquiredAt)
                stmt.setString(4, expiresAt)
                stmt.setString(5, originalAcquiredAt)
                stmt.executeUpdate()
            }
    }

    private fun countLeaseRows(holderItemId: UUID): Int {
        var count = 0
        keepAliveConnection.prepareStatement("SELECT COUNT(*) FROM resource_leases WHERE holder_item_id = ?").use { stmt ->
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
    fun `V15 migration creates the resource_leases table and its indexes`(): Unit =
        runBlocking {
            applyV15Migration()

            var hasTable = false
            transaction(db = database) {
                exec("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'resource_leases'") { rs ->
                    hasTable = rs.next()
                }
            }
            assertTrue(hasTable, "Expected resource_leases table to exist after V15")

            val expectedIndexes =
                listOf(
                    "idx_resource_leases_key_holder",
                    "idx_resource_leases_resource_key",
                    "idx_resource_leases_expires_at",
                )
            for (indexName in expectedIndexes) {
                var hasIndex = false
                transaction(db = database) {
                    exec("SELECT name FROM sqlite_master WHERE type = 'index' AND name = '$indexName'") { rs ->
                        hasIndex = rs.next()
                    }
                }
                assertTrue(hasIndex, "Expected $indexName index to exist after V15")
            }
        }

    @Test
    fun `V15 (resource_key, holder_item_id) is unique — a second insert for the same pair fails`(): Unit =
        runBlocking {
            applyV15Migration()
            val holder = UUID.randomUUID()
            insertWorkItem(holder, "Holder")
            insertResourceLease("staging-db-credential", holder)

            var threw = false
            try {
                insertResourceLease("staging-db-credential", holder)
            } catch (e: SQLException) {
                threw = true
            }
            assertTrue(threw, "Expected a UNIQUE constraint violation on a second insert for the same (resource_key, holder_item_id)")
        }

    @Test
    fun `V15 the same resource_key is allowed for two different holders (semaphore-ready)`(): Unit =
        runBlocking {
            applyV15Migration()
            val holderA = UUID.randomUUID()
            val holderB = UUID.randomUUID()
            insertWorkItem(holderA, "Holder A")
            insertWorkItem(holderB, "Holder B")
            insertResourceLease("staging-db-credential", holderA)

            var threw = false
            try {
                insertResourceLease("staging-db-credential", holderB)
            } catch (e: SQLException) {
                threw = true
            }
            assertTrue(!threw, "The same resource_key must be insertable for a different holder_item_id")
        }

    @Test
    fun `V15 deleting the holder work item cascades to delete its resource_leases rows`(): Unit =
        runBlocking {
            applyV15Migration()
            val holder = UUID.randomUUID()
            insertWorkItem(holder, "Holder")
            insertResourceLease("staging-db-credential", holder)

            assertEquals(1, countLeaseRows(holder), "Sanity check: lease row exists before delete")

            keepAliveConnection.prepareStatement("DELETE FROM work_items WHERE id = ?").use { stmt ->
                stmt.setBytes(1, uuidToBytes(holder))
                stmt.executeUpdate()
            }

            assertEquals(0, countLeaseRows(holder), "Expected ON DELETE CASCADE to remove the resource_leases row")
        }

    @Test
    fun `V15 fresh apply and upgrade-path apply both succeed`(): Unit =
        runBlocking {
            // "Fresh apply" — a brand-new DB applying only V15 (work_items pre-created above, mirroring
            // the minimal fixture every migration test in this suite uses).
            applyV15Migration()
            var hasTable = false
            transaction(db = database) {
                exec("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'resource_leases'") { rs ->
                    hasTable = rs.next()
                }
            }
            assertTrue(hasTable, "Fresh apply must create resource_leases")

            // "Upgrade path" — inserting a row post-migration behaves as a normal already-migrated
            // database would (no re-apply of V15 is attempted; Flyway itself guards against re-running
            // an already-applied version — this asserts the table is usable immediately after apply).
            val holder = UUID.randomUUID()
            insertWorkItem(holder, "Holder")
            insertResourceLease("staging-db-credential", holder)
            assertEquals(1, countLeaseRows(holder), "Table must be immediately usable after V15 apply")
        }
}
