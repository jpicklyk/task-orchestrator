package io.github.jpicklyk.mcptask.current.upgrade.internal.sqlite.seeds

import io.github.jpicklyk.mcptask.current.upgrade.internal.sqlite.BaselineDataset
import io.github.jpicklyk.mcptask.current.upgrade.internal.sqlite.MigrationSeed
import io.github.jpicklyk.mcptask.current.upgrade.internal.sqlite.UpgradeHarness
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * V39 appends seven nullable columns to `decision_requests`, none with a default: `waiting_on`, `waiting_reason`,
 * `answer_decided_by`, `answer_basis`, `answer_relayed`, `answer_within_autonomy` and `answer_links`. No existing column is
 * modified or dropped, no table is recreated, no index, trigger or foreign key is added, and no row is backfilled: every pre-V39
 * row reads NULL in each new column.
 *
 * Oracle: the migration-assessment of item eb933b68, sections 1 to 4 and "Upgrade harness" (the seven column definitions and
 * their CHECK vocabularies, their order after `version`, no backfill, the unchanged index and foreign-key inventories). Nothing
 * is read back from the migration file or from the schema snapshot.
 *
 * [verify] runs after the chain reaches the LATEST schema, so the new columns are located by their position after every
 * pre-V39 column (captured by [seed]), never by "are the last columns".
 *
 * Fixture invariants by construction: the three seeded decisions use the baseline root (so `PRAGMA foreign_key_check` stays empty),
 * distinct dedupe keys (the partial unique index), and an answered row that carries answered_at and answered_by_id (the V36
 * table CHECK).
 */
object SeedV39 : MigrationSeed(39) {
    private val ROOT = BaselineDataset.id("root")
    private val OPEN = BaselineDataset.id("seed39-decision-open")
    private val ACKNOWLEDGED = BaselineDataset.id("seed39-decision-acknowledged")
    private val ANSWERED = BaselineDataset.id("seed39-decision-answered")
    private val TS = BaselineDataset.TS
    private const val OPTIONS = """[{"key":"a","label":"A"}]"""

    private val NEW_COLUMNS =
        listOf(
            Column("waiting_on", "TEXT", 0, null, 0),
            Column("waiting_reason", "TEXT", 0, null, 0),
            Column("answer_decided_by", "TEXT", 0, null, 0),
            Column("answer_basis", "TEXT", 0, null, 0),
            Column("answer_relayed", "INTEGER", 0, null, 0),
            Column("answer_within_autonomy", "INTEGER", 0, null, 0),
            Column("answer_links", "TEXT", 0, null, 0)
        )

    @Volatile
    private var before: Map<String, Int>? = null

    @Volatile
    private var columnsBefore: List<String>? = null

    @Volatile
    private var rowsBefore: Map<String, Map<String, String?>>? = null

    @Volatile
    private var objectsBefore: Pair<List<String>, List<Pair<String, String?>>>? = null

    @Volatile
    private var foreignKeysBefore: List<String>? = null

    override fun seed(conn: Connection) {
        insertDecision(conn, OPEN, "s39-open", "open")
        insertDecision(
            conn,
            ACKNOWLEDGED,
            "s39-acknowledged",
            "acknowledged",
            "ack_by_id" to "s39-acker",
            "ack_by_kind" to "human",
            "ack_at" to TS
        )
        insertDecision(
            conn,
            ANSWERED,
            "s39-answered",
            "answered",
            "answer_option" to "a",
            "answered_by_id" to "s39-answerer",
            "answered_by_kind" to "human",
            "answered_by_proof" to "verified",
            "answered_at" to TS
        )
        before = UpgradeHarness.userTables(conn).associateWith { count(conn, it) }
        columnsBefore = tableInfo(conn).map { it.name }
        rowsBefore = listOf(OPEN, ACKNOWLEDGED, ANSWERED).associate { hex(it) to rowOf(conn, it) }
        objectsBefore = objects(conn)
        foreignKeysBefore = foreignKeyRows(conn)
    }

    // expected() keeps its default (an empty map): no existing column is rewritten by this migration.

    override fun verify(conn: Connection) {
        verifyNewColumns(conn)
        verifyNoBackfill(conn)
        verifyExistingUntouched(conn)
        verifyChecks(conn)
    }

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Int,
        val default: String?,
        val pk: Int
    )

    /** Assessment section 1: seven nullable columns with no default, appended after every pre-V39 column in the stated order. */
    private fun verifyNewColumns(conn: Connection) {
        val pre = columnsBefore ?: error("SeedV39.seed did not run before the migration")
        val columns = tableInfo(conn)
        assertEquals(pre, columns.map { it.name }.take(pre.size), "decision_requests keeps its columns in order")
        assertEquals(NEW_COLUMNS, columns.drop(pre.size), "the seven new columns follow version, in migration order, nullable, no default")
        assertEquals("version", pre.last(), "fixture condition: version was the last column before V39")
    }

    /** Assessment section 3: nothing is backfilled; the seeded rows keep every old value and read NULL in the new columns. */
    private fun verifyNoBackfill(conn: Connection) {
        val any = NEW_COLUMNS.joinToString(" OR ") { "${it.name} IS NOT NULL" }
        assertEquals(0L, scalar(conn, "SELECT count(*) FROM decision_requests WHERE $any"), "no row has a value in a new column")
        val was = rowsBefore ?: error("SeedV39.seed did not run before the migration")
        for ((id, rowThen) in was) {
            val now = rowOf(conn, hexBytes(id))
            for ((column, value) in rowThen) {
                assertEquals(value, now[column], "decision $id column $column changed across V39")
            }
        }
        assertEquals(
            3L,
            scalar(conn, "SELECT count(*) FROM decision_requests WHERE dedupe_key LIKE 's39-%'"),
            "the three seeded rows survive"
        )
    }

    /** Assessment section 4: row counts, the decision_requests indexes and triggers, and its foreign keys are unchanged. */
    private fun verifyExistingUntouched(conn: Connection) {
        val counts = before ?: error("SeedV39.seed did not run before the migration")
        for ((table, rows) in SeedV29.afterRelocation(counts) - "data_steps") {
            assertEquals(rows, count(conn, table), "$table row count changed across V39")
        }
        assertEquals(objectsBefore, objects(conn), "the decision_requests indexes and triggers are unchanged by V39")
        assertEquals(foreignKeysBefore, foreignKeyRows(conn), "decision_requests gains no foreign key")
    }

    /** Assessment section 1: each column CHECK refuses an out-of-vocabulary value and accepts the vocabulary and NULL. */
    private fun verifyChecks(conn: Connection) {
        fun write(
            column: String,
            value: Any?
        ) {
            conn.prepareStatement("UPDATE decision_requests SET $column = ? WHERE id = ?").use { ps ->
                when (value) {
                    null -> ps.setNull(1, java.sql.Types.NULL)
                    is Int -> ps.setInt(1, value)
                    else -> ps.setString(1, value.toString())
                }
                ps.setBytes(2, OPEN)
                ps.executeUpdate()
            }
        }

        fun stored(column: String): String? = string(conn, "SELECT $column FROM decision_requests WHERE id = x'${hex(OPEN)}'")

        fun accepts(
            column: String,
            value: Any
        ) {
            write(column, value)
            assertEquals(value.toString(), stored(column), "control: $column accepts $value")
            write(column, null)
            assertNull(stored(column), "$column accepts NULL again")
        }

        fun refuses(
            column: String,
            value: Any
        ) {
            assertFailsWith<SQLException>("$column must refuse $value") { write(column, value) }
            assertNull(stored(column), "the refused write left $column NULL")
        }
        for (value in listOf("director", "user")) accepts("waiting_on", value)
        refuses("waiting_on", "nobody")
        refuses("waiting_on", "Director")
        accepts("waiting_reason", "r")
        accepts("waiting_reason", "r".repeat(500))
        refuses("waiting_reason", "")
        refuses("waiting_reason", "r".repeat(501))
        for (value in listOf("user", "director")) accepts("answer_decided_by", value)
        refuses("answer_decided_by", "agent")
        refuses("answer_decided_by", "User")
        for (value in listOf("autonomy", "delegation", "relayed")) accepts("answer_basis", value)
        refuses("answer_basis", "guess")
        refuses("answer_basis", "Autonomy")
        for (value in listOf(0, 1)) accepts("answer_relayed", value)
        refuses("answer_relayed", 2)
        refuses("answer_relayed", -1)
        for (value in listOf(0, 1)) accepts("answer_within_autonomy", value)
        refuses("answer_within_autonomy", 2)
        refuses("answer_within_autonomy", -1)
        accepts("answer_links", """[{"kind":"url","ref":"https://example.test/x"}]""")
    }

    // ---------------------------------------------------------------------------------------------------------- helpers

    private fun insertDecision(
        conn: Connection,
        id: ByteArray,
        dedupeKey: String,
        status: String,
        vararg more: Pair<String, Any?>
    ) {
        val values =
            linkedMapOf<String, Any?>(
                "id" to id,
                "root_id" to ROOT,
                "class" to "question",
                "answerers" to "any-actor",
                "severity" to "low",
                "status" to status,
                "dedupe_key" to dedupeKey,
                "scope_key" to "root",
                "question" to "Which one?",
                "options" to OPTIONS,
                "raised_by_id" to "s39-principal",
                "raised_by_kind" to "agent",
                "raised_at" to TS,
                "on_expiry" to "escalate"
            )
        for ((k, v) in more) values[k] = v
        val columns = values.keys.joinToString { "\"$it\"" }
        val marks = values.keys.joinToString { "?" }
        conn.prepareStatement("INSERT INTO decision_requests ($columns) VALUES ($marks)").use { ps ->
            values.values.forEachIndexed { i, v ->
                when (v) {
                    null -> ps.setNull(i + 1, java.sql.Types.NULL)
                    is ByteArray -> ps.setBytes(i + 1, v)
                    else -> ps.setString(i + 1, v.toString())
                }
            }
            ps.executeUpdate()
        }
    }

    /** Every column of the row as text (BLOBs as hex), keyed by name. */
    private fun rowOf(
        conn: Connection,
        id: ByteArray
    ): Map<String, String?> =
        conn.prepareStatement("SELECT * FROM decision_requests WHERE id = ?").use { ps ->
            ps.setBytes(1, id)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "decision ${hex(id)} vanished" }
                val meta = rs.metaData
                (1..meta.columnCount).associate { i ->
                    val value = rs.getObject(i)
                    meta.getColumnLabel(i) to (if (value is ByteArray) hex(value) else value?.toString())
                }
            }
        }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hexBytes(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun tableInfo(conn: Connection): List<Column> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_xinfo(decision_requests)").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Column(
                                rs.getString("name"),
                                rs.getString("type").uppercase(),
                                rs.getInt("notnull"),
                                rs.getString("dflt_value"),
                                rs.getInt("pk")
                            )
                        )
                    }
                }
            }
        }

    private fun foreignKeyRows(conn: Connection): List<String> =
        conn
            .createStatement()
            .use { st ->
                st.executeQuery("PRAGMA foreign_key_list(decision_requests)").use { rs ->
                    buildList {
                        while (rs.next()) {
                            add("${rs.getString("from")}->${rs.getString("table")}.${rs.getString("to")} ${rs.getString("on_delete")}")
                        }
                    }
                }
            }.sorted()

    private fun objects(conn: Connection): Pair<List<String>, List<Pair<String, String?>>> =
        conn.createStatement().use { st ->
            val indexes =
                st
                    .executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'decision_requests' ORDER BY name"
                    ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            val triggers =
                st
                    .executeQuery(
                        "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'decision_requests' ORDER BY name"
                    ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
            indexes to triggers
        }

    private fun count(
        conn: Connection,
        table: String
    ): Int = scalar(conn, "SELECT count(*) FROM $table").toInt()

    private fun scalar(
        conn: Connection,
        sql: String
    ): Long = conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0L } }

    private fun string(
        conn: Connection,
        sql: String
    ): String? = conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
}
