package io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management

import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.WorkItemsTable
import io.github.jpicklyk.mcptask.current.test.SQLiteRepositoryTestBase
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Structural parity between the Exposed-generated `work_items` schema
 * (`SchemaUtils.create(WorkItemsTable)` — the path both `DirectDatabaseSchemaManager` (:48-58,
 * :189) and [SQLiteRepositoryTestBase] use) and the Flyway-migrated terminal schema
 * (`V7__FTS5_And_Unbounded_Depth.sql`). A gap here is production-facing for every Direct-mode
 * (`USE_FLYWAY=false`) database, not merely a test-fixture looseness (diagnosis note, 97f8632f).
 *
 * Index NAMES are intentionally NOT compared — Flyway names its indexes explicitly
 * (`idx_work_items_*`) while Exposed auto-generates its own names; only the column SET each
 * index covers is compared. CHECK constraint TEXT is compared via `sqlite_master.sql`
 * substring/regex extraction because SQLite exposes no PRAGMA for CHECK clauses.
 *
 * `S6` proves this suite is capable of failing: a deliberately drifted stand-in table
 * ([DriftedWorkItemsTable], missing the `role` CHECK and the `claim_expires_at` index) is fed
 * through the same comparison helpers used by S2/S3, and the helpers are asserted to fail. A
 * parity test that cannot fail verifies nothing (diagnosis note, ROOT CAUSE).
 */
class SchemaParityTest {
    private val keepAliveConnections = mutableListOf<Connection>()
    private val registeredDatabases = mutableListOf<Database>()

    @AfterEach
    fun tearDown() {
        registeredDatabases.forEach { db -> runCatching { TransactionManager.closeAndUnregister(db) } }
        registeredDatabases.clear()
        keepAliveConnections.forEach { conn -> runCatching { conn.close() } }
        keepAliveConnections.clear()
    }

    private data class MemoryDb(
        val jdbcUrl: String,
        val keepAlive: Connection
    )

    /**
     * Opens a fresh, uniquely-named shared-cache in-memory SQLite db and keeps a connection
     * open for the test's lifetime so the schema isn't dropped between statements — the same
     * keep-alive pattern [SQLiteRepositoryTestBase] and `BaseFts5RepositoryTest` use.
     */
    private fun openSharedMemoryDb(name: String): MemoryDb {
        val jdbcUrl = "jdbc:sqlite:file:${name}_${System.nanoTime()}?mode=memory&cache=shared"
        val keepAlive = DriverManager.getConnection(jdbcUrl)
        keepAliveConnections += keepAlive
        return MemoryDb(jdbcUrl, keepAlive)
    }

    /**
     * Runs the full Flyway migration chain (V1..latest, including V7's CHECKs and index)
     * against a fresh in-memory db and returns a connection into it.
     */
    private fun flywayMigratedConnection(): Connection {
        val db = openSharedMemoryDb("schema_parity_flyway")
        FlywayDatabaseSchemaManager(db.jdbcUrl).updateSchema()
        return db.keepAlive
    }

    /**
     * Creates ONLY [table] via Exposed's `SchemaUtils.create` against a fresh in-memory db and
     * returns a connection into it.
     */
    private fun exposedCreatedConnection(table: Table): Connection {
        val db = openSharedMemoryDb("schema_parity_exposed")
        val database = Database.connect(url = db.jdbcUrl, driver = "org.sqlite.JDBC")
        registeredDatabases += database
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        transaction(db = database) { SchemaUtils.create(table) }
        return db.keepAlive
    }

    // ────────────────────────────────────────────────────────────────────────
    // Structural readers (raw JDBC — deliberately independent of the Exposed
    // DSL under test, so the oracle isn't "what Exposed itself reports").
    // ────────────────────────────────────────────────────────────────────────

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val default: String?
    )

    /**
     * Reads `PRAGMA table_info(tableName)`, normalizing the SQL type keyword's case (SQLite
     * type names/keywords are case-insensitive) so cross-schema comparisons aren't tripped up
     * by incidental casing differences between hand-written SQL and Exposed-generated DDL.
     */
    private fun readColumns(
        connection: Connection,
        tableName: String
    ): List<ColumnInfo> {
        val out = mutableListOf<ColumnInfo>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($tableName)").use { rs ->
                while (rs.next()) {
                    out +=
                        ColumnInfo(
                            name = rs.getString("name"),
                            type = rs.getString("type").trim().uppercase(),
                            notNull = rs.getInt("notnull") != 0,
                            default = rs.getString("dflt_value")?.trim(),
                        )
                }
            }
        }
        return out.sortedBy { it.name }
    }

    /**
     * Reads every index on [tableName] as the SET of columns it covers (via `PRAGMA index_list`
     * + `PRAGMA index_info`) — never the index name, which Flyway and Exposed assign
     * differently for the same logical index.
     */
    private fun readIndexColumnSets(
        connection: Connection,
        tableName: String
    ): Set<Set<String>> {
        val indexNames = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_list($tableName)").use { rs ->
                while (rs.next()) indexNames += rs.getString("name")
            }
        }
        return indexNames
            .map { indexName ->
                val columns = mutableSetOf<String>()
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA index_info('${indexName.replace("'", "''")}')").use { rs ->
                        while (rs.next()) columns += rs.getString("name")
                    }
                }
                columns
            }.toSet()
    }

    /**
     * Reads the literal `CREATE TABLE` text for [tableName] from `sqlite_master.sql` — the
     * only place SQLite exposes CHECK constraint text (no PRAGMA covers it).
     */
    private fun readCreateTableSql(
        connection: Connection,
        tableName: String
    ): String {
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '${tableName.replace("'", "''")}'",
                ).use { rs ->
                    check(rs.next()) { "table '$tableName' not found in sqlite_master" }
                    return rs.getString("sql") ?: ""
                }
        }
    }

    /**
     * Extracts every top-level `CHECK(...)` clause body, tolerating exactly one level of nested
     * parens (the `IN (...)` lists this schema's CHECKs use).
     */
    private fun extractCheckClauses(createTableSql: String): List<String> =
        Regex("""CHECK\s*\(((?:[^()]|\([^()]*\))*)\)""", RegexOption.IGNORE_CASE)
            .findAll(createTableSql)
            .map { it.groupValues[1] }
            .toList()

    /**
     * Finds the CHECK clause referencing [columnName] as a whole identifier — a word-boundary
     * match so a lookup for "role" does not also match inside "previous_role".
     */
    private fun findCheckClauseFor(
        createTableSql: String,
        columnName: String
    ): String? {
        val boundary = Regex("\\b${Regex.escape(columnName)}\\b")
        return extractCheckClauses(createTableSql).firstOrNull { boundary.containsMatchIn(it) }
    }

    private fun assertCheckEnumerates(
        createTableSql: String,
        columnName: String,
        values: List<String>,
        label: String,
    ) {
        val clause = findCheckClauseFor(createTableSql, columnName)
        assertNotNull(clause, "$label: no CHECK clause found referencing column '$columnName' in: $createTableSql")
        for (value in values) {
            assertTrue(
                clause!!.contains("'$value'"),
                "$label: CHECK clause for '$columnName' does not enumerate '$value' — clause: $clause",
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // S1 — column parity
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S1 column name, type, notnull and default parity between Flyway and Exposed`() {
        val flywayColumns = readColumns(flywayMigratedConnection(), "work_items")
        val exposedColumns = readColumns(exposedCreatedConnection(WorkItemsTable), "work_items")

        assertEquals(
            flywayColumns.map { it.name }.toSet(),
            exposedColumns.map { it.name }.toSet(),
            "column name sets differ between the Flyway-migrated and Exposed-generated work_items table",
        )

        val exposedByName = exposedColumns.associateBy { it.name }
        for (flywayColumn in flywayColumns) {
            val exposedColumn = exposedByName.getValue(flywayColumn.name)
            assertEquals(
                flywayColumn.notNull,
                exposedColumn.notNull,
                "NOT NULL mismatch on column '${flywayColumn.name}': " +
                    "flyway=${flywayColumn.notNull} exposed=${exposedColumn.notNull}",
            )
            assertEquals(
                flywayColumn.type,
                exposedColumn.type,
                "type mismatch on column '${flywayColumn.name}': flyway=${flywayColumn.type} exposed=${exposedColumn.type}",
            )
            assertEquals(
                flywayColumn.default,
                exposedColumn.default,
                "default mismatch on column '${flywayColumn.name}': " +
                    "flyway=${flywayColumn.default} exposed=${exposedColumn.default}",
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // S2 — index column-set parity, including the NEW claim_expires index
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S2 index column-set parity including claim_expires`() {
        val flywaySets = readIndexColumnSets(flywayMigratedConnection(), "work_items")
        val exposedSets = readIndexColumnSets(exposedCreatedConnection(WorkItemsTable), "work_items")

        assertEquals(
            flywaySets,
            exposedSets,
            "index column-sets differ (index NAMES are expected to differ — only column sets are " +
                "compared): flyway=$flywaySets exposed=$exposedSets",
        )
        assertTrue(
            exposedSets.contains(setOf("claim_expires_at")),
            "Exposed schema is missing an index on claim_expires_at " +
                "(the idx_work_items_claim_expires equivalent) — exposed indexes: $exposedSets",
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // S3-S5 — CHECK constraint parity
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S3 role CHECK clause parity`() {
        val expected = listOf("queue", "work", "review", "blocked", "terminal")
        assertCheckEnumerates(readCreateTableSql(flywayMigratedConnection(), "work_items"), "role", expected, "flyway")
        assertCheckEnumerates(
            readCreateTableSql(exposedCreatedConnection(WorkItemsTable), "work_items"),
            "role",
            expected,
            "exposed",
        )
    }

    @Test
    fun `S4 previous_role CHECK clause parity (nullable or enum)`() {
        val expected = listOf("queue", "work", "review", "blocked", "terminal")
        val schemas =
            listOf(
                "flyway" to readCreateTableSql(flywayMigratedConnection(), "work_items"),
                "exposed" to readCreateTableSql(exposedCreatedConnection(WorkItemsTable), "work_items"),
            )
        for ((label, sql) in schemas) {
            val clause = findCheckClauseFor(sql, "previous_role")
            assertNotNull(clause, "$label: no CHECK clause found for previous_role")
            assertTrue(
                clause!!.contains("IS NULL", ignoreCase = true),
                "$label: previous_role CHECK does not allow NULL — clause: $clause",
            )
            for (value in expected) {
                assertTrue(
                    clause.contains("'$value'"),
                    "$label: previous_role CHECK missing '$value' — clause: $clause",
                )
            }
        }
    }

    @Test
    fun `S5 priority CHECK clause parity`() {
        val expected = listOf("high", "medium", "low")
        assertCheckEnumerates(readCreateTableSql(flywayMigratedConnection(), "work_items"), "priority", expected, "flyway")
        assertCheckEnumerates(
            readCreateTableSql(exposedCreatedConnection(WorkItemsTable), "work_items"),
            "priority",
            expected,
            "exposed",
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // S6 — self red-proof: a deliberately drifted fixture MUST fail the comparison
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S6 red-proof — drifted fixture fails the role CHECK comparison`() {
        val driftedSql = readCreateTableSql(exposedCreatedConnection(DriftedWorkItemsTable), "work_items")

        val failure =
            assertThrows(AssertionError::class.java) {
                assertCheckEnumerates(
                    driftedSql,
                    "role",
                    listOf("queue", "work", "review", "blocked", "terminal"),
                    "drifted",
                )
            }
        assertTrue(
            failure.message?.contains("role") == true,
            "expected the AssertionError to name the missing 'role' CHECK, got: ${failure.message}",
        )
    }

    @Test
    fun `S6 red-proof — drifted fixture fails the claim_expires index comparison`() {
        val driftedSets = readIndexColumnSets(exposedCreatedConnection(DriftedWorkItemsTable), "work_items")
        assertFalse(
            driftedSets.contains(setOf("claim_expires_at")),
            "the drifted fixture was built to omit the claim_expires_at index — if this assertion " +
                "fails, the fixture no longer demonstrates the gap S6 exists to prove",
        )
    }

    @Test
    fun `S6 red-proof — comparing the drifted fixture against the real Flyway schema fails`() {
        val flywaySets = readIndexColumnSets(flywayMigratedConnection(), "work_items")
        val driftedSets = readIndexColumnSets(exposedCreatedConnection(DriftedWorkItemsTable), "work_items")

        assertThrows(AssertionError::class.java) {
            assertEquals(flywaySets, driftedSets, "expected this assertion to fail — the drifted fixture omits an index")
        }
    }
}

/**
 * A DELIBERATELY drifted stand-in for [WorkItemsTable] — every column present, but missing the
 * `role` CHECK constraint and the `claim_expires_at` index. Exists ONLY so [SchemaParityTest]
 * can prove it is capable of failing (S6): a parity test that cannot go red against a schema
 * carrying the exact gap the real fix closes would not be verifying anything.
 */
private object DriftedWorkItemsTable : UUIDTable("work_items") {
    val parentId = javaUUID("parent_id").nullable()
    val rootId = javaUUID("root_id").nullable()
    val title = text("title")
    val description = text("description").nullable()
    val summary = text("summary").default("")
    val role = varchar("role", 20).default("queue") // NO check() — the deliberate S6 gap
    val statusLabel = text("status_label").nullable()
    val previousRole = varchar("previous_role", 20).nullable()
    val priority = varchar("priority", 20).default("medium")
    val complexity = integer("complexity").nullable()
    val requiresVerification = bool("requires_verification").default(false)
    val depth = integer("depth").default(0)
    val metadata = text("metadata").nullable()
    val tags = text("tags").nullable()
    val type = text("type").nullable()
    val properties = text("properties").nullable()
    val createdAt = timestamp("created_at")
    val modifiedAt = timestamp("modified_at")
    val roleChangedAt = timestamp("role_changed_at")
    val version = long("version").default(1)
    val claimedBy = text("claimed_by").nullable()
    val claimedAt = timestamp("claimed_at").nullable()
    val claimExpiresAt = timestamp("claim_expires_at").nullable() // NO index — the other deliberate S6 gap
    val originalClaimedAt = timestamp("original_claimed_at").nullable()

    init {
        foreignKey(parentId to DriftedWorkItemsTable.id)
        index(isUnique = false, parentId)
        index(isUnique = false, rootId)
        index(isUnique = false, role)
        index(isUnique = false, depth)
        index(isUnique = false, priority)
        index(isUnique = false, columns = arrayOf(role, roleChangedAt))
        index(isUnique = false, claimedBy)
        // previous_role and priority CHECKs are kept intentionally so S6 isolates the role +
        // claim_expires gaps rather than failing every CHECK comparison at once.
        check("chk_work_items_previous_role") {
            previousRole.isNull() or previousRole.inList(listOf("queue", "work", "review", "blocked", "terminal"))
        }
        check("chk_work_items_priority") { priority.inList(listOf("high", "medium", "low")) }
    }
}

/**
 * S7/S8 — FK enforcement on [SQLiteRepositoryTestBase] after this item's declared edit
 * (`PRAGMA foreign_keys=ON` immediately before the base's `SchemaUtils.create`, ~:57). Uses the
 * base's own `database` directly — no second in-memory database needed for this pair.
 */
class WorkItemsForeignKeyEnforcementTest : SQLiteRepositoryTestBase() {
    private fun countNotesWithKey(key: String): Int {
        var count = 0
        transaction(db = database) {
            val escaped = key.replace("'", "''")
            val result: Int? =
                exec("SELECT COUNT(*) AS c FROM notes WHERE key = '$escaped'") { rs ->
                    if (rs.next()) rs.getInt("c") else 0
                }
            count = result ?: 0
        }
        return count
    }

    @Test
    fun `S7 dangling parent insert now throws once foreign_keys is ON`() {
        val failure =
            assertThrows(Exception::class.java) {
                transaction(db = database) {
                    exec(
                        """
                        INSERT INTO notes (id, work_item_id, key, role, body, created_at, modified_at)
                        VALUES (randomblob(16), randomblob(16), 'dangling-note', 'queue', 'body', datetime('now'), datetime('now'))
                        """.trimIndent(),
                    )
                }
            }
        val message = (failure.message ?: "") + (failure.cause?.message ?: "")
        assertTrue(
            message.contains("FOREIGN KEY", ignoreCase = true),
            "expected a FOREIGN KEY constraint violation once PRAGMA foreign_keys=ON is enabled on " +
                "this base, got: $message",
        )
    }

    @Test
    fun `S8 deleting a work item cascades to its notes`() {
        transaction(db = database) {
            exec(
                """
                INSERT INTO work_items (id, title, created_at, modified_at, role_changed_at)
                VALUES (randomblob(16), 'fk-cascade-parent', datetime('now'), datetime('now'), datetime('now'))
                """.trimIndent(),
            )
            exec(
                """
                INSERT INTO notes (id, work_item_id, key, role, body, created_at, modified_at)
                SELECT randomblob(16), id, 'fk-cascade-test-note', 'queue', 'body', datetime('now'), datetime('now')
                FROM work_items WHERE title = 'fk-cascade-parent'
                """.trimIndent(),
            )
        }
        assertEquals(
            1,
            countNotesWithKey("fk-cascade-test-note"),
            "fixture setup: expected exactly one note before the parent work_item is deleted",
        )

        transaction(db = database) {
            exec("DELETE FROM work_items WHERE title = 'fk-cascade-parent'")
        }

        assertEquals(
            0,
            countNotesWithKey("fk-cascade-test-note"),
            "deleting the parent work_item should cascade-delete its notes " +
                "(V1__Current_Initial_Schema.sql ON DELETE CASCADE) now that foreign_keys is ON",
        )
    }
}
