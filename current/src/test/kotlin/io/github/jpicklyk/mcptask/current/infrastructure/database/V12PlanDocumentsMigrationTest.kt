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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test for the V12 migration (`V12__Plan_Documents.sql`) — the new `plan_documents`
 * table and its two differently-behaved FKs to `work_items` (`root_item_id` CASCADE,
 * `adopted_by_item_id` SET NULL).
 *
 * Follows the [V10ProjectConfigMigrationTest] pattern: hand-build a minimal pre-V12 `work_items`
 * table with raw SQL, apply the real V12 SQL file read straight off the classpath, then assert
 * table/index existence, the composite unique constraint on `(root_item_id, slug)`, and both FK
 * delete-action behaviors via raw JDBC. `PRAGMA foreign_keys = ON` is set explicitly since both
 * cascade and set-null behavior are under test.
 */
class V12PlanDocumentsMigrationTest {
    private lateinit var database: Database
    private lateinit var keepAliveConnection: Connection

    @BeforeEach
    fun setUp() {
        val dbName = "v12_plan_documents_${System.nanoTime()}"
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
     * Reads the real `V12__Plan_Documents.sql` off the classpath and executes each statement.
     * Strips full-line `--` comments, then splits on `;` — safe here since none of the migration's
     * statements contain an embedded semicolon (mirrors [V10ProjectConfigMigrationTest.applyV10Migration]).
     */
    private fun applyV12Migration() {
        val resourceStream =
            requireNotNull(
                Thread.currentThread().contextClassLoader.getResourceAsStream("db/migration/V12__Plan_Documents.sql")
            ) { "V12__Plan_Documents.sql not found on the test classpath" }
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

    private fun insertPlanDocument(
        id: UUID,
        rootItemId: UUID,
        slug: String,
        body: String,
        contentHash: String,
        status: String,
        adoptedByItemId: UUID?
    ) {
        keepAliveConnection
            .prepareStatement(
                """
                INSERT INTO plan_documents
                    (id, root_item_id, slug, body, content_hash, status, adopted_by_item_id, created_at, modified_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setBytes(1, uuidToBytes(id))
                stmt.setBytes(2, uuidToBytes(rootItemId))
                stmt.setString(3, slug)
                stmt.setString(4, body)
                stmt.setString(5, contentHash)
                stmt.setString(6, status)
                if (adoptedByItemId != null) stmt.setBytes(7, uuidToBytes(adoptedByItemId)) else stmt.setNull(7, java.sql.Types.BLOB)
                stmt.setTimestamp(8, java.sql.Timestamp.from(Instant.now()))
                stmt.setTimestamp(9, java.sql.Timestamp.from(Instant.now()))
                stmt.executeUpdate()
            }
    }

    private fun countPlanDocumentRows(rootItemId: UUID): Int {
        var count = 0
        keepAliveConnection.prepareStatement("SELECT COUNT(*) FROM plan_documents WHERE root_item_id = ?").use { stmt ->
            stmt.setBytes(1, uuidToBytes(rootItemId))
            stmt.executeQuery().use { rs ->
                if (rs.next()) count = rs.getInt(1)
            }
        }
        return count
    }

    private fun readAdoptedByItemId(id: UUID): UUID? {
        var result: UUID? = null
        keepAliveConnection.prepareStatement("SELECT adopted_by_item_id FROM plan_documents WHERE id = ?").use { stmt ->
            stmt.setBytes(1, uuidToBytes(id))
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val bytes = rs.getBytes(1)
                    result = bytes?.let { bytesToUuid(it) }
                }
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

    private fun bytesToUuid(bytes: ByteArray): UUID {
        val buf = ByteBuffer.wrap(bytes)
        return UUID(buf.long, buf.long)
    }

    @Test
    fun `V12 migration creates the plan_documents table and unique index`(): Unit =
        runBlocking {
            applyV12Migration()

            var hasTable = false
            transaction(db = database) {
                exec("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'plan_documents'") { rs ->
                    hasTable = rs.next()
                }
            }
            assertTrue(hasTable, "Expected plan_documents table to exist after V12")

            var hasIndex = false
            transaction(db = database) {
                exec(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_plan_documents_root_item_id_slug'"
                ) { rs ->
                    hasIndex = rs.next()
                }
            }
            assertTrue(hasIndex, "Expected idx_plan_documents_root_item_id_slug index to exist after V12")
        }

    @Test
    fun `V12 (root_item_id, slug) is unique — a second insert for the same pair fails`(): Unit =
        runBlocking {
            applyV12Migration()
            val root = UUID.randomUUID()
            insertWorkItem(root, "Root")
            insertPlanDocument(UUID.randomUUID(), root, "plan-a", "v1", "hash1", "pending", null)

            var threw = false
            try {
                insertPlanDocument(UUID.randomUUID(), root, "plan-a", "v2", "hash2", "pending", null)
            } catch (e: SQLException) {
                threw = true
            }
            assertTrue(threw, "Expected a UNIQUE constraint violation on a second insert for the same (root_item_id, slug)")
        }

    @Test
    fun `V12 the same slug is allowed under two different roots`(): Unit =
        runBlocking {
            applyV12Migration()
            val rootA = UUID.randomUUID()
            val rootB = UUID.randomUUID()
            insertWorkItem(rootA, "Root A")
            insertWorkItem(rootB, "Root B")
            insertPlanDocument(UUID.randomUUID(), rootA, "plan-a", "v1", "hash1", "pending", null)

            var threw = false
            try {
                insertPlanDocument(UUID.randomUUID(), rootB, "plan-a", "v1", "hash1", "pending", null)
            } catch (e: SQLException) {
                threw = true
            }
            assertTrue(!threw, "The same slug under a different root must be allowed")
        }

    @Test
    fun `V12 deleting the root work item cascades to delete its plan_documents rows`(): Unit =
        runBlocking {
            applyV12Migration()
            val root = UUID.randomUUID()
            insertWorkItem(root, "Root")
            insertPlanDocument(UUID.randomUUID(), root, "plan-a", "v1", "hash1", "pending", null)

            assertEquals(1, countPlanDocumentRows(root), "Sanity check: document row exists before delete")

            keepAliveConnection.prepareStatement("DELETE FROM work_items WHERE id = ?").use { stmt ->
                stmt.setBytes(1, uuidToBytes(root))
                stmt.executeUpdate()
            }

            assertEquals(0, countPlanDocumentRows(root), "Expected ON DELETE CASCADE to remove the plan_documents row")
        }

    @Test
    fun `V12 deleting the adopting work item sets adopted_by_item_id to null without deleting the row`(): Unit =
        runBlocking {
            applyV12Migration()
            val root = UUID.randomUUID()
            val adopter = UUID.randomUUID()
            val docId = UUID.randomUUID()
            insertWorkItem(root, "Root")
            insertWorkItem(adopter, "Adopter")
            insertPlanDocument(docId, root, "plan-a", "v1", "hash1", "adopted", adopter)

            assertEquals(adopter, readAdoptedByItemId(docId), "Sanity check: adoption recorded before delete")

            keepAliveConnection.prepareStatement("DELETE FROM work_items WHERE id = ?").use { stmt ->
                stmt.setBytes(1, uuidToBytes(adopter))
                stmt.executeUpdate()
            }

            assertEquals(1, countPlanDocumentRows(root), "Expected the document row to survive the adopter's deletion")
            assertNull(readAdoptedByItemId(docId), "Expected ON DELETE SET NULL to unlink the adopter")
        }
}
