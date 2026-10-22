package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import java.sql.Connection
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V31 appends one nullable column to `call_log` (`root_id` BLOB) and adds three indexes: the full `idx_call_log_root_at` on
 * `call_log(root_id, at)`, and the two partial indexes `idx_call_log_run_at` on `call_log(run_id, at)` and `idx_events_run_seq` on
 * `events(run_id, seq)`, both `WHERE run_id IS NOT NULL`. No existing column is rewritten, no row is touched, no table is
 * recreated.
 *
 * Oracle: the frozen migration-assessment of item 6a52466e, sections 1 to 4 (the four statements, the column type BLOB nullable
 * with no default, no CHECK and no foreign key, appended after `lifecycle_free_rejections`, existing rows read NULL, no backfill,
 * the three index definitions with their exact column order, the partial predicate) and the verification list of section 4, plus
 * sqlite.org/partialindex.html section 2.2 (a partial index whose predicate is `c IS NOT NULL` serves a query constraining
 * `c = <value>`). Nothing is read back from the migration file.
 */
object SeedV31 : MigrationSeed(31) {
    private const val ROW_WITH = "v31row01"
    private const val ROW_WITHOUT = "v31row02"
    private const val ROW_RUN = "v31row03"
    private const val AT = "2026-03-06 10:15:30.123"

    private val ROOT = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
    private val RUN = byteArrayOf(16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1)

    /** Row counts of every user table at the pre-migration version, captured by [seed] and compared by [verify]. */
    @Volatile
    private var before: Map<String, Int>? = null

    /** The number of `call_log` rows the baseline put there before the migration, captured by [seed]. */
    @Volatile
    private var callLogBefore: Int? = null

    override fun seed(conn: Connection) {
        before = UpgradeHarness.userTables(conn).associateWith { count(conn, it) }
        callLogBefore = count(conn, "call_log")
    }

    // expected() keeps its default (an empty map): no existing column is rewritten by this migration.

    override fun verify(conn: Connection) {
        verifyColumn(conn)
        verifyExistingRows(conn)
        verifyIndexes(conn)
        verifyQueryPlans(conn)
        verifyRoundTrip(conn)
    }

    private fun verifyColumn(conn: Connection) {
        val columns = tableInfo(conn)
        val index = columns.indexOfFirst { it.name == "root_id" }
        assertTrue(index >= 1, "call_log gains a root_id column: ${columns.map { it.name }}")
        val root = columns[index]
        assertEquals("lifecycle_free_rejections", columns[index - 1].name, "root_id is appended directly after lifecycle_free_rejections")
        assertEquals("BLOB", root.type.uppercase(), "root_id type")
        assertEquals(0, root.notNull, "root_id is nullable")
        assertNull(root.default, "root_id has no default")
        assertEquals(0, root.pk, "root_id is not part of the key")
        assertEquals(1, columns.count { it.name == "root_id" }, "root_id appears once")
    }

    private fun verifyExistingRows(conn: Connection) {
        val pre = callLogBefore ?: error("SeedV31.seed did not run before the migration")
        assertTrue(pre > 0, "fixture: the baseline dataset holds call_log rows before V31")
        assertEquals(pre, count(conn, "call_log"), "the migration neither adds nor removes call_log rows")
        assertEquals(
            0L,
            scalar(conn, "SELECT count(*) FROM call_log WHERE root_id IS NOT NULL"),
            "a pre-existing row reads NULL root_id (no backfill)"
        )
        val counts = before ?: error("SeedV31.seed did not run before the migration")
        for ((table, rows) in SeedV29.afterRelocation(counts) - "data_steps") {
            assertEquals(rows, count(conn, table), "$table row count changed across V31")
        }
    }

    private fun verifyIndexes(conn: Connection) {
        val callLog = indexList(conn, "call_log")
        val events = indexList(conn, "events")

        val rootAt = callLog["idx_call_log_root_at"] ?: error("idx_call_log_root_at missing: ${callLog.keys}")
        assertEquals(listOf("root_id", "at"), indexColumns(conn, "idx_call_log_root_at"), "idx_call_log_root_at column order")
        assertEquals(0, rootAt.partial, "idx_call_log_root_at is a full index")
        assertEquals(0, rootAt.unique, "idx_call_log_root_at is not unique")

        val runAt = callLog["idx_call_log_run_at"] ?: error("idx_call_log_run_at missing: ${callLog.keys}")
        assertEquals(listOf("run_id", "at"), indexColumns(conn, "idx_call_log_run_at"), "idx_call_log_run_at column order")
        assertEquals(1, runAt.partial, "idx_call_log_run_at is a partial index")
        assertEquals(0, runAt.unique, "idx_call_log_run_at is not unique")
        assertPartialPredicate(conn, "idx_call_log_run_at")

        val runSeq = events["idx_events_run_seq"] ?: error("idx_events_run_seq missing: ${events.keys}")
        assertEquals(listOf("run_id", "seq"), indexColumns(conn, "idx_events_run_seq"), "idx_events_run_seq column order")
        assertEquals(1, runSeq.partial, "idx_events_run_seq is a partial index")
        assertEquals(0, runSeq.unique, "idx_events_run_seq is not unique")
        assertPartialPredicate(conn, "idx_events_run_seq")

        // The V21 indexes are untouched.
        assertEquals(listOf("tool", "at"), indexColumns(conn, "idx_call_log_tool_at"), "idx_call_log_tool_at survives")
        assertEquals(listOf("at"), indexColumns(conn, "idx_call_log_at"), "idx_call_log_at survives")
    }

    private fun assertPartialPredicate(
        conn: Connection,
        index: String
    ) {
        val sql = text(conn, "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = '$index'") ?: error("no sql for $index")
        val normalized = sql.uppercase().replace(Regex("\\s+"), " ")
        assertTrue("WHERE RUN_ID IS NOT NULL" in normalized, "$index is declared WHERE run_id IS NOT NULL: $sql")
    }

    private fun verifyQueryPlans(conn: Connection) {
        val runLiteral = "x'" + RUN.joinToString("") { "%02x".format(it) } + "'"
        val rootLiteral = "x'" + ROOT.joinToString("") { "%02x".format(it) } + "'"
        val callLogRun = plan(conn, "SELECT * FROM call_log WHERE run_id = $runLiteral ORDER BY at")
        assertTrue("idx_call_log_run_at" in callLogRun, "a run_id lookup on call_log uses the partial index: $callLogRun")
        val eventsRun = plan(conn, "SELECT * FROM events WHERE run_id = $runLiteral ORDER BY seq")
        assertTrue("idx_events_run_seq" in eventsRun, "a run_id lookup on events uses the partial index: $eventsRun")
        val callLogRoot = plan(conn, "SELECT * FROM call_log WHERE root_id = $rootLiteral")
        assertTrue("idx_call_log_root_at" in callLogRoot, "a root_id lookup on call_log uses the root index: $callLogRoot")
    }

    private fun verifyRoundTrip(conn: Connection) {
        val pre = callLogBefore ?: error("SeedV31.seed did not run before the migration")
        insertProbe(conn, ROW_WITH, root = ROOT, run = null)
        insertProbe(conn, ROW_WITHOUT, root = null, run = null)

        val stored =
            conn.prepareStatement("SELECT root_id FROM call_log WHERE req_id = ?").use { ps ->
                ps.setString(1, ROW_WITH)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getBytes(1)
                }
            }
        assertContentEquals(ROOT, stored, "root_id round-trips as written")
        assertEquals(
            1L,
            scalar(conn, "SELECT count(*) FROM call_log WHERE req_id = '$ROW_WITHOUT' AND root_id IS NULL"),
            "a row that sets no root reads NULL, not an empty blob"
        )

        // A partial index holds only rows with a run: rows with a NULL run_id are not in it, a row with a run is.
        val inIndex = "SELECT count(*) FROM call_log INDEXED BY idx_call_log_run_at WHERE run_id IS NOT NULL"
        val withRunBefore = scalar(conn, inIndex)
        assertEquals(
            scalar(conn, "SELECT count(*) FROM call_log WHERE run_id IS NOT NULL"),
            withRunBefore,
            "the partial index covers exactly the rows that have a run"
        )
        insertProbe(conn, ROW_RUN, root = ROOT, run = RUN)
        assertEquals(withRunBefore + 1, scalar(conn, inIndex), "a row with a run_id enters the partial index")
        assertEquals(
            1L,
            scalar(
                conn,
                "SELECT count(*) FROM call_log INDEXED BY idx_call_log_run_at WHERE run_id = x'" +
                    RUN.joinToString("") { "%02x".format(it) } +
                    "'"
            ),
            "the run row is found through the partial index"
        )

        conn.createStatement().use { it.executeUpdate("DELETE FROM call_log WHERE req_id IN ('$ROW_WITH', '$ROW_WITHOUT', '$ROW_RUN')") }
        assertEquals(pre, count(conn, "call_log"), "the probe rows are removed again")
        assertEquals(withRunBefore, scalar(conn, inIndex), "the partial index shrinks back")
    }

    private fun insertProbe(
        conn: Connection,
        reqId: String,
        root: ByteArray?,
        run: ByteArray?
    ) {
        conn
            .prepareStatement(
                "INSERT INTO call_log (req_id, at, surface, tool, outcome, attempts, latency_ms, root_id, run_id) " +
                    "VALUES (?, ?, 'mcp', 'seed31.probe', 'ok', 1, 0, ?, ?)"
            ).use { ps ->
                ps.setString(1, reqId)
                ps.setString(2, AT)
                if (root == null) ps.setNull(3, java.sql.Types.BLOB) else ps.setBytes(3, root)
                if (run == null) ps.setNull(4, java.sql.Types.BLOB) else ps.setBytes(4, run)
                ps.executeUpdate()
            }
    }

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Int,
        val default: String?,
        val pk: Int
    )

    private data class IndexInfo(
        val unique: Int,
        val partial: Int
    )

    private fun tableInfo(conn: Connection): List<Column> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(call_log)").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Column(
                                rs.getString("name"),
                                rs.getString("type"),
                                rs.getInt("notnull"),
                                rs.getString("dflt_value"),
                                rs.getInt("pk")
                            )
                        )
                    }
                }
            }
        }

    private fun indexList(
        conn: Connection,
        table: String
    ): Map<String, IndexInfo> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list($table)").use { rs ->
                buildMap { while (rs.next()) put(rs.getString("name"), IndexInfo(rs.getInt("unique"), rs.getInt("partial"))) }
            }
        }

    private fun indexColumns(
        conn: Connection,
        index: String
    ): List<String> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA index_info($index)").use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }
        }

    private fun plan(
        conn: Connection,
        sql: String
    ): String =
        conn.createStatement().use { st ->
            st.executeQuery("EXPLAIN QUERY PLAN $sql").use { rs ->
                buildList { while (rs.next()) add(rs.getString("detail")) }.joinToString(" | ")
            }
        }

    private fun count(
        conn: Connection,
        table: String
    ): Int = scalar(conn, "SELECT count(*) FROM $table").toInt()

    private fun scalar(
        conn: Connection,
        sql: String
    ): Long = conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0L } }

    private fun text(
        conn: Connection,
        sql: String
    ): String? = conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
}
