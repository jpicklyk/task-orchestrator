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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test for the V14 migration (`V14__Add_Consumed_Credentials.sql`) — the additive
 * `consumed_credentials` TEXT column on `role_transitions` (T1: credentialRefs audit field).
 *
 * Follows the [V9RootIdMigrationTest] pattern: hand-build the *pre-migration* `role_transitions`
 * schema (matching V1__Current_Initial_Schema.sql's original columns, i.e. without the later
 * actor/verification columns which are irrelevant to this ALTER TABLE ADD COLUMN and without
 * `consumed_credentials`), seed rows via raw JDBC, apply the real V14 SQL file straight off the
 * classpath, then assert the column exists, defaults to NULL on both pre-existing and freshly
 * inserted rows, and round-trips an explicit value.
 */
class V14ConsumedCredentialsMigrationTest {
    private lateinit var database: Database
    private lateinit var keepAliveConnection: Connection

    @BeforeEach
    fun setUp() {
        val dbName = "v14_consumed_credentials_${System.nanoTime()}"
        val jdbcUrl = "jdbc:sqlite:file:$dbName?mode=memory&cache=shared"
        keepAliveConnection = DriverManager.getConnection(jdbcUrl)
        database = Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        createPreV14Schema()
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

    /** Minimal pre-V14 schema: work_items (FK target) + role_transitions without consumed_credentials. */
    private fun createPreV14Schema() {
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
                CREATE TABLE role_transitions (
                    id                  BLOB PRIMARY KEY DEFAULT (randomblob(16)),
                    item_id             BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
                    from_role           VARCHAR(20) NOT NULL,
                    to_role             VARCHAR(20) NOT NULL,
                    from_status_label   TEXT,
                    to_status_label     TEXT,
                    trigger             VARCHAR(50) NOT NULL,
                    summary             TEXT,
                    transitioned_at     TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
            exec("CREATE INDEX idx_role_trans_item ON role_transitions(item_id)")
            exec("CREATE INDEX idx_role_trans_time ON role_transitions(transitioned_at)")
        }
    }

    /**
     * Reads the real `V14__Add_Consumed_Credentials.sql` off the classpath and executes it.
     * Strips full-line `--` comments, then splits on `;` (mirrors [V9RootIdMigrationTest]) — safe
     * here since the migration's single ALTER TABLE statement contains no embedded semicolon.
     */
    private fun applyV14Migration() {
        val resourceStream =
            requireNotNull(
                Thread.currentThread().contextClassLoader.getResourceAsStream(
                    "db/migration/V14__Add_Consumed_Credentials.sql"
                )
            ) { "V14__Add_Consumed_Credentials.sql not found on the test classpath" }
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

    private fun uuidToBytes(id: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(id.mostSignificantBits)
        buf.putLong(id.leastSignificantBits)
        return buf.array()
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

    private fun insertPreV14Transition(
        id: UUID,
        itemId: UUID
    ) {
        keepAliveConnection
            .prepareStatement(
                """
                INSERT INTO role_transitions (id, item_id, from_role, to_role, trigger, transitioned_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setBytes(1, uuidToBytes(id))
                stmt.setBytes(2, uuidToBytes(itemId))
                stmt.setString(3, "queue")
                stmt.setString(4, "work")
                stmt.setString(5, "start")
                stmt.setTimestamp(6, Timestamp.from(Instant.now()))
                stmt.executeUpdate()
            }
    }

    @Test
    fun `V14 migration adds the consumed_credentials column`(): Unit =
        runBlocking {
            applyV14Migration()

            var hasColumn = false
            transaction(db = database) {
                exec("PRAGMA table_info(role_transitions)") { rs ->
                    while (rs.next()) {
                        if (rs.getString("name") == "consumed_credentials") hasColumn = true
                    }
                }
            }
            assertTrue(hasColumn, "Expected consumed_credentials column to exist after V14")
        }

    @Test
    fun `V14 pre-existing rows default consumed_credentials to NULL`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val transitionId = UUID.randomUUID()
            insertWorkItem(itemId, "Pre-existing item")
            insertPreV14Transition(transitionId, itemId)

            applyV14Migration()

            var value: String? = "not-read"
            keepAliveConnection
                .prepareStatement("SELECT consumed_credentials FROM role_transitions WHERE id = ?")
                .use { stmt ->
                    stmt.setBytes(1, uuidToBytes(transitionId))
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next(), "Expected the pre-existing row to still be present after V14")
                        value = rs.getString(1)
                    }
                }
            assertNull(value, "Expected consumed_credentials to default to NULL for a pre-existing row")
        }

    @Test
    fun `V14 freshly inserted row without consumed_credentials defaults to NULL`(): Unit =
        runBlocking {
            applyV14Migration()

            val itemId = UUID.randomUUID()
            val transitionId = UUID.randomUUID()
            insertWorkItem(itemId, "Fresh item")
            insertPreV14Transition(transitionId, itemId)

            var value: String? = "not-read"
            keepAliveConnection
                .prepareStatement("SELECT consumed_credentials FROM role_transitions WHERE id = ?")
                .use { stmt ->
                    stmt.setBytes(1, uuidToBytes(transitionId))
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        value = rs.getString(1)
                    }
                }
            assertNull(value, "Expected consumed_credentials to default to NULL when omitted on insert")
        }

    @Test
    fun `V14 round-trips an explicit JSON array value`(): Unit =
        runBlocking {
            applyV14Migration()

            val itemId = UUID.randomUUID()
            val transitionId = UUID.randomUUID()
            insertWorkItem(itemId, "Item with credentialRefs")
            val jsonValue = """["vault:prod-db-password","github-pat-ci"]"""

            keepAliveConnection
                .prepareStatement(
                    """
                    INSERT INTO role_transitions
                        (id, item_id, from_role, to_role, trigger, transitioned_at, consumed_credentials)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setBytes(1, uuidToBytes(transitionId))
                    stmt.setBytes(2, uuidToBytes(itemId))
                    stmt.setString(3, "queue")
                    stmt.setString(4, "work")
                    stmt.setString(5, "start")
                    stmt.setTimestamp(6, Timestamp.from(Instant.now()))
                    stmt.setString(7, jsonValue)
                    stmt.executeUpdate()
                }

            var value: String? = null
            keepAliveConnection
                .prepareStatement("SELECT consumed_credentials FROM role_transitions WHERE id = ?")
                .use { stmt ->
                    stmt.setBytes(1, uuidToBytes(transitionId))
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        value = rs.getString(1)
                    }
                }
            assertEquals(jsonValue, value)
        }
}
