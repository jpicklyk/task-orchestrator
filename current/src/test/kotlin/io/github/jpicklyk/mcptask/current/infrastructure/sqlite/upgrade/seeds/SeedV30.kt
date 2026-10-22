package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V30 appends two nullable columns to `call_log` (`hit_signals` TEXT, `lifecycle_free_rejections` INTEGER) and nothing else: no
 * existing column is rewritten, no row is touched, no table is recreated, no index is added.
 *
 * Oracle: the frozen migration-assessment of item df10fafb, sections 1 to 4 (the two statements, their types, nullable with no
 * default and no CHECK, appended after `eligible_count`, existing rows read NULL, no backfill, no index) and the verification list
 * of section 4 for this seed. Nothing is read back from the migration file.
 */
object SeedV30 : MigrationSeed(30) {
    private const val ROW_WITH = "v30row01"
    private const val ROW_WITHOUT = "v30row02"
    private const val AT = "2026-03-05 10:15:30.123"
    private const val SIGNALS = """[{"id":"00000000-0000-4000-8000-000000000030","kind":"item","signals":["edges"]}]"""

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
        val columns = tableInfo(conn)
        assertTrue(columns.size >= 3, "call_log keeps its columns and gains two: ${columns.map { it.name }}")
        // Name-relative: later migrations (V31 root_id) append after these two columns.
        val hitIndex = columns.indexOfFirst { it.name == "hit_signals" }
        assertTrue(hitIndex >= 1, "call_log has a hit_signals column after eligible_count: ${columns.map { it.name }}")
        val hit = columns[hitIndex]
        val lifecycle = columns.getOrNull(hitIndex + 1) ?: error("no column after hit_signals: ${columns.map { it.name }}")
        assertEquals("hit_signals", hit.name, "first appended column")
        assertEquals("TEXT", hit.type.uppercase(), "hit_signals type")
        assertEquals(0, hit.notNull, "hit_signals is nullable")
        assertNull(hit.default, "hit_signals has no default")
        assertEquals(0, hit.pk, "hit_signals is not part of the key")
        assertEquals("lifecycle_free_rejections", lifecycle.name, "second appended column")
        assertEquals("INTEGER", lifecycle.type.uppercase(), "lifecycle_free_rejections type")
        assertEquals(0, lifecycle.notNull, "lifecycle_free_rejections is nullable")
        assertNull(lifecycle.default, "lifecycle_free_rejections has no default")
        assertEquals(0, lifecycle.pk, "lifecycle_free_rejections is not part of the key")
        assertEquals("eligible_count", columns[hitIndex - 1].name, "the new columns are appended directly after eligible_count")

        // No index covers either new column (the reports filter by tool and at, which the V21 index already serves).
        for (index in indexNames(conn)) {
            val covered = indexColumns(conn, index)
            assertTrue(
                "hit_signals" !in covered && "lifecycle_free_rejections" !in covered,
                "index $index must not cover a V30 column: $covered"
            )
        }

        // Existing rows read NULL in both columns; the baseline put rows there, so the check is not vacuous.
        val pre = callLogBefore ?: error("SeedV30.seed did not run before the migration")
        assertTrue(pre > 0, "fixture: the baseline dataset holds call_log rows before V30")
        assertEquals(pre, count(conn, "call_log"), "the migration neither adds nor removes call_log rows")
        assertEquals(
            0L,
            scalar(conn, "SELECT count(*) FROM call_log WHERE hit_signals IS NOT NULL OR lifecycle_free_rejections IS NOT NULL"),
            "a pre-existing row reads NULL in both new columns (no backfill)"
        )

        // Every user table keeps its row count, except data_steps: the harness runs the production data steps after the chain, and
        // they record themselves there (one row per step), which is not something this migration does.
        val counts = before ?: error("SeedV30.seed did not run before the migration")
        for ((table, rows) in SeedV29.afterRelocation(counts) - "data_steps") {
            assertEquals(rows, count(conn, table), "$table row count changed across V30")
        }

        // A row carrying both values reads them back exactly; a row carrying neither reads NULL, not 0 and not ''.
        conn
            .prepareStatement(
                "INSERT INTO call_log (req_id, at, surface, tool, outcome, attempts, latency_ms, hit_signals, lifecycle_free_rejections) " +
                    "VALUES (?, ?, 'mcp', 'seed30.probe', 'ok', 1, 0, ?, ?)"
            ).use { ps ->
                ps.setString(1, ROW_WITH)
                ps.setString(2, AT)
                ps.setString(3, SIGNALS)
                ps.setInt(4, 4)
                ps.executeUpdate()
            }
        conn
            .prepareStatement(
                "INSERT INTO call_log (req_id, at, surface, tool, outcome, attempts, latency_ms) " +
                    "VALUES (?, ?, 'mcp', 'seed30.probe', 'ok', 1, 0)"
            ).use { ps ->
                ps.setString(1, ROW_WITHOUT)
                ps.setString(2, AT)
                ps.executeUpdate()
            }
        val stored = text(conn, "SELECT hit_signals FROM call_log WHERE req_id = '$ROW_WITH'")
        assertEquals(SIGNALS, stored, "hit_signals round-trips as written")
        assertEquals(4L, scalar(conn, "SELECT lifecycle_free_rejections FROM call_log WHERE req_id = '$ROW_WITH'"))
        assertEquals(
            1L,
            scalar(
                conn,
                "SELECT count(*) FROM call_log WHERE req_id = '$ROW_WITHOUT' AND hit_signals IS NULL AND lifecycle_free_rejections IS NULL"
            ),
            "a row that sets neither column reads NULL in both"
        )
        conn.createStatement().use { it.executeUpdate("DELETE FROM call_log WHERE req_id IN ('$ROW_WITH', '$ROW_WITHOUT')") }
        assertEquals(pre, count(conn, "call_log"), "the probe rows are removed again")
    }

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Int,
        val default: String?,
        val pk: Int
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

    private fun indexNames(conn: Connection): List<String> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(call_log)").use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }
        }

    private fun indexColumns(
        conn: Connection,
        index: String
    ): List<String> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA index_info($index)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
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
