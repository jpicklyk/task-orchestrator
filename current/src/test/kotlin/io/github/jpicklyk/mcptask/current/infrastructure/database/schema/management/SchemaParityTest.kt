package io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management

import io.github.jpicklyk.mcptask.current.infrastructure.database.StartupIntegrity
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.WorkItemsTable
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.javaUuidSqlite
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.timestampSqlite
import io.github.jpicklyk.mcptask.current.test.sqlite.ClasspathScan
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural parity between the Flyway-migrated schema (the only schema path) and everything that
 * mirrors it, over EVERY table:
 *
 * - P-inv: the user tables in `sqlite_master` (minus Flyway's history table and the FTS tables and
 *   their shadow tables) equal the set of Exposed `Table` objects, discovered by scanning the
 *   production classes. There is no hand-kept list, so a new table needs no shared edit here.
 * - P-col: per table, the Exposed columns (name, SQLite affinity of `sqlType()`, nullability) equal
 *   `PRAGMA table_xinfo`, in both directions. Defaults and CHECKs are NOT compared on the Exposed
 *   side: the Exposed objects are query mappings only.
 * - P-snap: the normalized `sqlite_master` rows equal the committed snapshot, which is the gate for
 *   indexes, CHECKs, triggers and virtual tables.
 *
 * Regenerating the snapshot after an intentional migration: run this class, then copy
 * `current/build/schema-snapshot.actual.txt` over
 * `current/src/test/resources/schema/sqlite-schema-snapshot.txt` and review the diff.
 */
class SchemaParityTest {
    companion object {
        @JvmField
        @RegisterExtension
        val db = SqliteTestDatabase.perClass()

        private const val SNAPSHOT_RESOURCE = "/schema/sqlite-schema-snapshot.txt"
        private val SHADOW_SUFFIXES = listOf("_data", "_idx", "_content", "_docsize", "_config")

        fun isFtsOrShadow(name: String): Boolean =
            StartupIntegrity.FTS_TABLES.any { fts -> name == fts || SHADOW_SUFFIXES.any { name == fts + it } }

        /** Every Exposed `Table` object in the production classes (directory or jar), without a hand list. */
        fun exposedTables(): List<Table> =
            ClasspathScan
                .classesUnder(WorkItemsTable::class.java, "io/github/jpicklyk/mcptask/current/")
                .filter {
                    Table::class.java.isAssignableFrom(it) &&
                        !java.lang.reflect.Modifier
                            .isAbstract(it.modifiers)
                }.mapNotNull { cls -> runCatching { cls.getField("INSTANCE").get(null) as? Table }.getOrNull() }
                .sortedBy { it.tableName }
    }

    private data class DbColumn(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val pk: Boolean
    )

    private fun <T> withConn(block: (Connection) -> T): T = DriverManager.getConnection(db.jdbcUrl).use(block)

    private fun userTables(conn: Connection): Set<String> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { rs ->
                buildSet {
                    while (rs.next()) {
                        val n = rs.getString(1)
                        if (n != FlywayDatabaseSchemaManager.HISTORY_TABLE && !isFtsOrShadow(n)) add(n)
                    }
                }
            }
        }

    private fun readDbColumns(
        conn: Connection,
        table: String
    ): List<DbColumn> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_xinfo($table)").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(DbColumn(rs.getString("name"), rs.getString("type") ?: "", rs.getInt("notnull") == 1, rs.getInt("pk") > 0))
                    }
                }
            }
        }

    /** SQLite's declared-type to affinity rules (https://www.sqlite.org/datatype3.html section 3.1). */
    private fun affinity(declared: String): String {
        val t = declared.uppercase()
        return when {
            t.contains("INT") -> "INTEGER"
            t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT") -> "TEXT"
            t.isBlank() || t.contains("BLOB") -> "BLOB"
            t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB") -> "REAL"
            else -> "NUMERIC"
        }
    }

    /** All P-col mismatches for one Exposed table against the database columns (both directions). */
    private fun compareColumns(
        table: Table,
        dbColumns: List<DbColumn>
    ): List<String> =
        transaction(db = db.database) {
            val mismatches = mutableListOf<String>()
            val dbByName = dbColumns.associateBy { it.name }
            val exposedByName = table.columns.associateBy { it.name }
            val t = table.tableName
            (exposedByName.keys - dbByName.keys).sorted().forEach { mismatches += "$t.$it: in Exposed, not in the database" }
            (dbByName.keys - exposedByName.keys).sorted().forEach { mismatches += "$t.$it: in the database, not in Exposed" }
            for ((name, column) in exposedByName) {
                val dbCol = dbByName[name] ?: continue
                val exposedAffinity = affinity(column.columnType.sqlType())
                val dbAffinity = affinity(dbCol.type)
                if (exposedAffinity != dbAffinity) {
                    mismatches +=
                        "$t.$name: affinity Exposed=$exposedAffinity (${column.columnType.sqlType()}) database=$dbAffinity (${dbCol.type})"
                }
                val exposedNullable = column.columnType.nullable
                if (dbCol.pk) {
                    // Known, accepted pair: Flyway declares `id BLOB PRIMARY KEY` (SQLite reports notnull=0) while
                    // Exposed models the key as non-null. Asserting the exact pair means either side moving fails.
                    if (dbCol.notNull || exposedNullable) {
                        mismatches += "$t.$name: primary-key nullability pair changed from (database notnull=0, Exposed non-null)"
                    }
                } else if (dbCol.notNull == exposedNullable) {
                    mismatches += "$t.$name: nullability Exposed nullable=$exposedNullable database notnull=${dbCol.notNull}"
                }
            }
            mismatches
        }

    /** Normalized sqlite_master rows, one per line, sorted, without Flyway's table, FTS shadow tables and autoindexes. */
    private fun snapshotLines(conn: Connection): List<String> =
        conn.createStatement().use { st ->
            st
                .executeQuery(
                    "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND tbl_name != '" +
                        FlywayDatabaseSchemaManager.HISTORY_TABLE + "'"
                ).use { rs ->
                    buildList {
                        while (rs.next()) {
                            val name = rs.getString(2)
                            val tbl = rs.getString(3)
                            val type = rs.getString(1)
                            if (isFtsOrShadow(name) && !StartupIntegrity.FTS_TABLES.contains(name)) continue
                            val sql = (rs.getString(4) ?: "").replace(Regex("\\s+"), " ").trim()
                            add("$type|$name|$tbl|$sql")
                        }
                    }.sorted()
                }
        }

    private fun diff(
        actual: List<String>,
        expected: List<String>
    ): List<String> = (expected - actual.toSet()).map { "- $it" } + (actual - expected.toSet()).map { "+ $it" }

    private fun loadSnapshot(): List<String> {
        val stream =
            SchemaParityTest::class.java.getResourceAsStream(SNAPSHOT_RESOURCE)
                ?: error("missing test resource $SNAPSHOT_RESOURCE")
        return stream.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
    }

    @Test
    fun `P-inv user tables equal the Exposed table objects and cover every table`() {
        val tables = withConn { userTables(it) }
        val exposed = exposedTables().map { it.tableName }.toSet()
        assertEquals(tables, exposed, "database tables (minus Flyway history and FTS) must equal the Exposed Table objects")
        val expectedAtLeast =
            setOf(
                "work_items",
                "notes",
                "dependencies",
                "role_transitions",
                "project_config",
                "plan_documents",
                "resource_leases",
                "resource_lease_history"
            )
        assertTrue(tables.containsAll(expectedAtLeast), "all 8 V17 tables must be covered, got $tables")
    }

    @Test
    fun `P-col Exposed columns equal table_xinfo for every table in both directions`() {
        val failures = mutableListOf<String>()
        for (table in exposedTables()) {
            val dbColumns = withConn { readDbColumns(it, table.tableName) }
            failures += compareColumns(table, dbColumns)
        }
        assertTrue(failures.isEmpty(), "column parity failures:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `P-snap normalized sqlite_master equals the committed snapshot`() {
        val actual = withConn { snapshotLines(it) }
        val expected = loadSnapshot().sorted()
        val delta = diff(actual, expected)
        if (delta.isNotEmpty()) {
            val out = File("build/schema-snapshot.actual.txt")
            out.parentFile?.mkdirs()
            out.writeText(actual.joinToString("\n") + "\n", Charsets.UTF_8)
            error(
                "sqlite_master differs from the committed snapshot. If the change is intentional, copy " +
                    "${out.absolutePath} over current/src/test/resources/schema/sqlite-schema-snapshot.txt and review the diff.\n" +
                    delta.joinToString("\n")
            )
        }
    }

    @Test
    fun `P-snap every trigger is an FTS sync trigger or a cycle guard`() {
        val allowed = (StartupIntegrity.FTS_TRIGGERS + StartupIntegrity.CYCLE_TRIGGERS).toSet()
        val triggers =
            withConn { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'trigger'").use { rs ->
                        buildSet { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }
        assertEquals(allowed, triggers, "trigger inventory drifted; extend StartupIntegrity or allowlist the new trigger with a reason")
    }

    @Test
    fun `S9 red-proof a drifted stand-in table fails P-col`() {
        val dbColumns = withConn { readDbColumns(it, "notes") }
        assertEquals(emptyList(), compareColumns(NotesMirror, dbColumns), "the faithful mirror must pass")
        val drifted = compareColumns(DriftedNotesTable, dbColumns)
        assertTrue(drifted.any { "actor_kind" in it && "not in Exposed" in it }, "missing column not reported: $drifted")
        assertTrue(drifted.any { "bogus_column" in it }, "extra column not reported: $drifted")
        assertTrue(drifted.any { "affinity" in it && "key" in it }, "affinity drift not reported: $drifted")
        assertTrue(drifted.any { "nullability" in it && "body" in it }, "nullability drift not reported: $drifted")
    }

    @Test
    fun `S9 red-proof a snapshot missing a line fails P-snap`() {
        val actual = withConn { snapshotLines(it) }
        assertTrue(diff(actual, actual).isEmpty())
        val withoutOne = loadSnapshot().sorted().drop(1)
        assertTrue(diff(actual, withoutOne).isNotEmpty(), "a snapshot missing a line must differ")
        val withExtra = loadSnapshot().sorted() + "table|ghost|ghost|CREATE TABLE ghost (id BLOB)"
        assertTrue(diff(actual, withExtra).isNotEmpty(), "a snapshot with an extra line must differ")
    }
}

/** A faithful stand-in for `notes` used only to prove the comparison passes when nothing drifted. */
private object NotesMirror : Table("notes") {
    val id = javaUuidSqlite("id")
    val workItemId = javaUuidSqlite("work_item_id")
    val key = varchar("key", 200)
    val role = varchar("role", 20)
    val body = text("body")
    val createdAt = timestampSqlite("created_at")
    val modifiedAt = timestampSqlite("modified_at")
    val actorId = text("actor_id").nullable()
    val actorKind = text("actor_kind").nullable()
    val actorParent = text("actor_parent").nullable()
    val actorProof = text("actor_proof").nullable()
    val verificationStatus = text("verification_status").nullable()
    val verificationVerifier = text("verification_verifier").nullable()
    val verificationReason = text("verification_reason").nullable()
    val actorProofSha256 = text("actor_proof_sha256").nullable()
    val actorProofClaims = text("actor_proof_claims").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** DELIBERATELY drifted: lacks `actor_kind`, adds `bogus_column`, `key` is INTEGER, `body` is nullable. */
private object DriftedNotesTable : Table("notes") {
    val id = javaUuidSqlite("id")
    val workItemId = javaUuidSqlite("work_item_id")
    val key = integer("key")
    val role = varchar("role", 20)
    val body = text("body").nullable()
    val createdAt = timestampSqlite("created_at")
    val modifiedAt = timestampSqlite("modified_at")
    val actorId = text("actor_id").nullable()
    val actorParent = text("actor_parent").nullable()
    val actorProof = text("actor_proof").nullable()
    val verificationStatus = text("verification_status").nullable()
    val verificationVerifier = text("verification_verifier").nullable()
    val verificationReason = text("verification_reason").nullable()
    val actorProofSha256 = text("actor_proof_sha256").nullable()
    val actorProofClaims = text("actor_proof_claims").nullable()
    val bogus = text("bogus_column").nullable()
    override val primaryKey = PrimaryKey(id)
}
