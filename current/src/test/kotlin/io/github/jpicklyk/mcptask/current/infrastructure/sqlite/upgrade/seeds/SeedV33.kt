package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V33 adds the empty tables `usage_daily` (the per-day, per-dimension rollup of `call_log`) and `usage_fold` (the one-row fold
 * watermark). Pure `CREATE TABLE` twice: no index statement, no trigger, no row touched, no existing table changed.
 *
 * Oracle: the frozen migration-assessment of item 515ec0a4, section 1 (the DDL: the 18 `usage_daily` columns in the plan's
 * order, every one NOT NULL, the nine-column composite primary key `day, root_id, tool, operation, surface, host,
 * principal_kind, outcome, error_code`, `length(day) = 10`, `outcome IN ('ok', 'error')`, `calls >= 1`, every other metric
 * `>= 0`; `usage_fold` with `singleton INTEGER PRIMARY KEY CHECK (singleton = 1)`, `length(folded_through) = 10`,
 * `length(folded_at) = 23`; no foreign key, no default, no extra index), section 2 (a NULL never conflicts in a primary key, so
 * the dimensions are NOT NULL and an absent value is the sentinel `''` or the zero-length blob; an `ON CONFLICT (pk) DO UPDATE`
 * upsert must therefore find the existing row), section 3 (both tables start empty: nothing is backfilled) and its verification
 * list, plus sqlite.org/lang_upsert.html and sqlite.org/optoverview.html (a range on the leading primary-key column is served by
 * the key's autoindex). Nothing is read back from the migration file.
 */
object SeedV33 : MigrationSeed(33) {
    private val ROOT = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
    private val ZERO_ROOT = ByteArray(16)
    private val EMPTY_ROOT = ByteArray(0)

    /** Row counts of every user table at the pre-migration version, captured by [seed] and compared by [verify]. */
    @Volatile
    private var before: Map<String, Int>? = null

    private val DAILY_COLUMNS =
        listOf(
            Triple("day", "TEXT", 1),
            Triple("root_id", "BLOB", 2),
            Triple("tool", "TEXT", 3),
            Triple("operation", "TEXT", 4),
            Triple("surface", "TEXT", 5),
            Triple("host", "TEXT", 6),
            Triple("principal_kind", "TEXT", 7),
            Triple("outcome", "TEXT", 8),
            Triple("error_code", "TEXT", 9),
            Triple("calls", "INTEGER", 0),
            Triple("errors", "INTEGER", 0),
            Triple("request_bytes", "INTEGER", 0),
            Triple("response_bytes", "INTEGER", 0),
            Triple("response_tokens_est", "INTEGER", 0),
            Triple("latency_ms_sum", "INTEGER", 0),
            Triple("latency_ms_max", "INTEGER", 0),
            Triple("retries", "INTEGER", 0),
            Triple("replays", "INTEGER", 0)
        )

    private val METRICS =
        listOf("errors", "request_bytes", "response_bytes", "response_tokens_est", "latency_ms_sum", "latency_ms_max", "retries", "replays")

    override fun seed(conn: Connection) {
        before = UpgradeHarness.userTables(conn).associateWith { count(conn, it) }
    }

    // expected() keeps its default (an empty map): no existing column is rewritten by this migration.

    override fun verify(conn: Connection) {
        verifyUsageDailyColumns(conn)
        verifyUsageFoldColumns(conn)
        verifyNoExtraObjects(conn)
        verifyStartsEmptyAndExistingTablesUntouched(conn)
        verifyDailyChecks(conn)
        verifyFoldChecks(conn)
        verifyUpsertAndSentinels(conn)
        verifyQueryPlan(conn)
    }

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Int,
        val default: String?,
        val pk: Int
    )

    private fun tableInfo(
        conn: Connection,
        table: String
    ): List<Column> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
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

    private fun verifyUsageDailyColumns(conn: Connection) {
        val columns = tableInfo(conn, "usage_daily")
        assertEquals(DAILY_COLUMNS.map { it.first }, columns.map { it.name }, "usage_daily columns, in the plan's order")
        assertEquals(DAILY_COLUMNS.map { it.second }, columns.map { it.type.uppercase() }, "usage_daily column types")
        assertEquals(DAILY_COLUMNS.map { it.third }, columns.map { it.pk }, "the nine dimensions are the primary key, in key order")
        assertTrue(
            columns.all { it.notNull == 1 },
            "every usage_daily column is NOT NULL: ${columns.filter { it.notNull != 1 }.map { it.name }}"
        )
        assertTrue(
            columns.all {
                it.default == null
            },
            "no usage_daily column has a default: ${columns.filter { it.default != null }.map { it.name }}"
        )
    }

    private fun verifyUsageFoldColumns(conn: Connection) {
        val columns = tableInfo(conn, "usage_fold")
        assertEquals(listOf("singleton", "folded_through", "folded_at"), columns.map { it.name }, "usage_fold columns, in order")
        assertEquals(listOf("INTEGER", "TEXT", "TEXT"), columns.map { it.type.uppercase() })
        assertEquals(listOf(1, 0, 0), columns.map { it.pk }, "singleton is the only key column")
        assertEquals(listOf(1, 1), columns.drop(1).map { it.notNull }, "folded_through and folded_at are NOT NULL")
        assertTrue(columns.all { it.default == null }, "no usage_fold column has a default")
    }

    private fun verifyNoExtraObjects(conn: Connection) {
        assertEquals(
            0L,
            scalar(
                conn,
                "SELECT count(*) FROM sqlite_master WHERE tbl_name IN ('usage_daily', 'usage_fold') AND type <> 'table' AND sql IS NOT NULL"
            ),
            "pure CREATE TABLE: no explicit index, trigger or view on the new tables"
        )
        val indexes =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA index_list(usage_daily)").use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }
            }
        assertEquals(listOf("sqlite_autoindex_usage_daily_1"), indexes, "usage_daily has only its primary-key autoindex")
        assertEquals(
            0L,
            scalar(conn, "SELECT count(*) FROM pragma_index_list('usage_fold')"),
            "usage_fold has no index: an INTEGER PRIMARY KEY is the rowid"
        )
        assertEquals(
            0L,
            scalar(conn, "SELECT count(*) FROM pragma_foreign_key_list('usage_daily')") +
                scalar(conn, "SELECT count(*) FROM pragma_foreign_key_list('usage_fold')"),
            "no foreign key: a fold row outlives the root it names"
        )
    }

    private fun verifyStartsEmptyAndExistingTablesUntouched(conn: Connection) {
        assertEquals(0, count(conn, "usage_daily"), "the migration backfills nothing into usage_daily")
        assertEquals(0, count(conn, "usage_fold"), "the migration writes no watermark")
        val counts = before ?: error("SeedV33.seed did not run before the migration")
        for ((table, rows) in SeedV29.afterRelocation(counts) - "data_steps") {
            assertEquals(rows, count(conn, table), "$table row count changed across V33")
        }
    }

    @Suppress("LongParameterList")
    private fun insertDaily(
        conn: Connection,
        day: String? = "2026-03-06",
        root: ByteArray? = ROOT,
        tool: String? = "seed33.tool",
        operation: String? = "",
        outcome: String? = "ok",
        calls: Long? = 1,
        metrics: Map<String, Long> = emptyMap(),
        onConflict: String = ""
    ) {
        val values = METRICS.map { metrics[it] ?: 0L }
        // A zero-length blob is written as the SQL literal x'': a driver may bind an empty byte array as NULL.
        val rootSql = if (root != null && root.isEmpty()) "x''" else "?"
        conn
            .prepareStatement(
                "INSERT INTO usage_daily (day, root_id, tool, operation, surface, host, principal_kind, outcome, error_code, calls, " +
                    METRICS.joinToString() +
                    ") VALUES (?, $rootSql, ?, ?, 'mcp', '', '', ?, '', ?, " + METRICS.joinToString { "?" } + ") " + onConflict
            ).use { ps ->
                var i = 1
                if (day == null) ps.setNull(i++, Types.VARCHAR) else ps.setString(i++, day)
                if (root == null) {
                    ps.setNull(i++, Types.BLOB)
                } else if (root.isNotEmpty()) {
                    ps.setBytes(i++, root)
                }
                if (tool == null) ps.setNull(i++, Types.VARCHAR) else ps.setString(i++, tool)
                if (operation == null) ps.setNull(i++, Types.VARCHAR) else ps.setString(i++, operation)
                if (outcome == null) ps.setNull(i++, Types.VARCHAR) else ps.setString(i++, outcome)
                if (calls == null) ps.setNull(i++, Types.INTEGER) else ps.setLong(i++, calls)
                values.forEach { v -> ps.setLong(i++, v) }
                ps.executeUpdate()
            }
    }

    private fun verifyDailyChecks(conn: Connection) {
        // Accepted shapes at the boundary of every CHECK.
        insertDaily(conn, tool = "seed33.ok-a")
        insertDaily(conn, tool = "seed33.ok-b", outcome = "error", calls = 1)
        insertDaily(conn, tool = "seed33.ok-zero", metrics = METRICS.associateWith { 0L })

        assertFailsWith<SQLException>(
            "a 9-character day must be rejected"
        ) { insertDaily(conn, tool = "seed33.bad-day9", day = "2026-3-06") }
        assertFailsWith<SQLException>(
            "an 11-character day must be rejected"
        ) { insertDaily(conn, tool = "seed33.bad-day11", day = "2026-03-06Z") }
        assertFailsWith<SQLException>("an outcome other than ok or error must be rejected") {
            insertDaily(conn, tool = "seed33.bad-outcome", outcome = "x")
        }
        assertFailsWith<SQLException>("calls 0 must be rejected") { insertDaily(conn, tool = "seed33.bad-calls", calls = 0) }
        assertFailsWith<SQLException>("calls -1 must be rejected") { insertDaily(conn, tool = "seed33.bad-calls-neg", calls = -1) }
        for (metric in METRICS) {
            assertFailsWith<SQLException>("$metric -1 must be rejected") {
                insertDaily(
                    conn,
                    tool = "seed33.bad-$metric",
                    metrics =
                        mapOf(
                            metric to -1L
                        )
                )
            }
        }
        assertFailsWith<SQLException>("a NULL day must be rejected") { insertDaily(conn, tool = "seed33.null-day", day = null) }
        assertFailsWith<SQLException>("a NULL root_id must be rejected") { insertDaily(conn, tool = "seed33.null-root", root = null) }
        assertFailsWith<SQLException>("a NULL tool must be rejected") { insertDaily(conn, tool = null) }
        assertFailsWith<SQLException>("a NULL operation must be rejected") { insertDaily(conn, tool = "seed33.null-op", operation = null) }
        assertFailsWith<SQLException>("a NULL outcome must be rejected") { insertDaily(conn, tool = "seed33.null-outcome", outcome = null) }
        assertFailsWith<SQLException>("a NULL calls must be rejected") { insertDaily(conn, tool = "seed33.null-calls", calls = null) }
        assertFailsWith<SQLException>("a duplicate primary key must be rejected without an upsert clause") {
            insertDaily(conn, tool = "seed33.ok-a")
        }

        conn.createStatement().use { it.executeUpdate("DELETE FROM usage_daily WHERE tool LIKE 'seed33.%'") }
        assertEquals(0, count(conn, "usage_daily"), "the probe rows are removed again")
    }

    private fun insertFold(
        conn: Connection,
        singleton: Long? = 1,
        through: String? = "2026-03-05",
        at: String? = "2026-03-06 10:15:30.123"
    ) {
        conn.prepareStatement("INSERT INTO usage_fold (singleton, folded_through, folded_at) VALUES (?, ?, ?)").use { ps ->
            if (singleton == null) ps.setNull(1, Types.INTEGER) else ps.setLong(1, singleton)
            if (through == null) ps.setNull(2, Types.VARCHAR) else ps.setString(2, through)
            if (at == null) ps.setNull(3, Types.VARCHAR) else ps.setString(3, at)
            ps.executeUpdate()
        }
    }

    private fun verifyFoldChecks(conn: Connection) {
        insertFold(conn)
        assertEquals(1, count(conn, "usage_fold"))
        assertFailsWith<SQLException>("a second row with singleton 1 must be rejected") { insertFold(conn) }
        assertFailsWith<SQLException>("singleton 2 must be rejected") { insertFold(conn, singleton = 2) }
        assertFailsWith<SQLException>("singleton 0 must be rejected") { insertFold(conn, singleton = 0) }
        conn.createStatement().use { it.executeUpdate("DELETE FROM usage_fold") }

        assertFailsWith<SQLException>("a 9-character folded_through must be rejected") { insertFold(conn, through = "2026-3-05") }
        assertFailsWith<SQLException>("an 11-character folded_through must be rejected") { insertFold(conn, through = "2026-03-05Z") }
        assertFailsWith<SQLException>("a 22-character folded_at must be rejected") { insertFold(conn, at = "2026-03-06 10:15:30.12") }
        assertFailsWith<SQLException>("a 24-character folded_at must be rejected") { insertFold(conn, at = "2026-03-06 10:15:30.1234") }
        assertFailsWith<SQLException>("a NULL folded_through must be rejected") { insertFold(conn, through = null) }
        assertFailsWith<SQLException>("a NULL folded_at must be rejected") { insertFold(conn, at = null) }
        assertEquals(0, count(conn, "usage_fold"), "every rejected insert left no row, and the probe row is removed")
    }

    private fun verifyUpsertAndSentinels(conn: Connection) {
        val upsert =
            "ON CONFLICT (day, root_id, tool, operation, surface, host, principal_kind, outcome, error_code) " +
                "DO UPDATE SET calls = excluded.calls, errors = excluded.errors"

        insertDaily(conn, tool = "seed33.upsert", calls = 3, metrics = mapOf("errors" to 1L), onConflict = upsert)
        insertDaily(conn, tool = "seed33.upsert", calls = 5, metrics = mapOf("errors" to 2L), onConflict = upsert)
        assertEquals(
            1L,
            scalar(conn, "SELECT count(*) FROM usage_daily WHERE tool = 'seed33.upsert'"),
            "the same key upserts into the same row"
        )
        assertEquals(
            5L,
            scalar(conn, "SELECT calls FROM usage_daily WHERE tool = 'seed33.upsert'"),
            "the upsert replaces the metric, it does not add"
        )
        assertEquals(2L, scalar(conn, "SELECT errors FROM usage_daily WHERE tool = 'seed33.upsert'"))

        // The absent-root sentinel (zero-length blob) and a real 16-byte root are different keys; an all-zero 16-byte root is a third.
        insertDaily(conn, tool = "seed33.upsert", root = EMPTY_ROOT, calls = 7, onConflict = upsert)
        insertDaily(conn, tool = "seed33.upsert", root = ZERO_ROOT, calls = 9, onConflict = upsert)
        assertEquals(
            3L,
            scalar(conn, "SELECT count(*) FROM usage_daily WHERE tool = 'seed33.upsert'"),
            "x'' is distinct from a 16-byte root, even an all-zero one"
        )
        insertDaily(conn, tool = "seed33.upsert", root = EMPTY_ROOT, calls = 8, onConflict = upsert)
        assertEquals(
            3L,
            scalar(conn, "SELECT count(*) FROM usage_daily WHERE tool = 'seed33.upsert'"),
            "a second sentinel row upserts into the first (a NULL root would have inserted a duplicate)"
        )
        assertEquals(8L, scalar(conn, "SELECT calls FROM usage_daily WHERE tool = 'seed33.upsert' AND length(root_id) = 0"))
        assertEquals(
            1L,
            scalar(conn, "SELECT count(*) FROM usage_daily WHERE tool = 'seed33.upsert' AND root_id = x'00000000000000000000000000000000'")
        )
        assertEquals(
            1L,
            scalar(
                conn,
                "SELECT count(*) FROM usage_daily WHERE tool = 'seed33.upsert' AND typeof(root_id) = 'blob' AND length(root_id) = 0"
            ),
            "the sentinel stays a blob"
        )

        conn.createStatement().use { it.executeUpdate("DELETE FROM usage_daily WHERE tool = 'seed33.upsert'") }
        assertEquals(0, count(conn, "usage_daily"), "the probe rows are removed again")
    }

    private fun verifyQueryPlan(conn: Connection) {
        val window = plan(conn, "SELECT calls FROM usage_daily WHERE day >= '2026-03-01' AND day < '2026-03-07'")
        assertTrue("sqlite_autoindex_usage_daily_1" in window, "a day range is served by the primary-key autoindex: $window")
        val windowAndRoot = plan(conn, "SELECT calls FROM usage_daily WHERE day >= '2026-03-01' AND day < '2026-03-07' AND root_id = x'01'")
        assertTrue(
            "sqlite_autoindex_usage_daily_1" in windowAndRoot,
            "a day range with a root is served by the same autoindex: $windowAndRoot"
        )
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
}
