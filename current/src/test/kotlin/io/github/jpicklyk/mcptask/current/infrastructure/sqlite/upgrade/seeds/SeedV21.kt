package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V21 only adds the empty `call_log` table (plus two indexes): every existing table is unchanged.
 * Oracle: migration-assessment section 1 of item 8abb69e2 (the frozen DDL: column order, the two indexes, the
 * NOT NULL TEXT primary key and the CHECK set) and section 5 (the verification list for this seed).
 */
object SeedV21 : MigrationSeed(21) {
    private const val AT = "2026-03-01 10:15:30.123"

    /** Row counts of every user table at the pre-migration version, captured by [seed] and compared by [verify]. */
    @Volatile
    private var before: Map<String, Int>? = null

    override fun seed(conn: Connection) {
        before = UpgradeHarness.userTables(conn).associateWith { count(conn, it) }
    }

    override fun verify(conn: Connection) {
        val columns =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(call_log)").use { rs ->
                    buildList { while (rs.next()) add(rs.getString("name")) }
                }
            }
        assertEquals(
            listOf(
                "req_id",
                "at",
                "principal_id",
                "principal_kind",
                "proof_status",
                "host",
                "session_id",
                "run_id",
                "seat",
                "surface",
                "tool",
                "operation",
                "target_ids",
                "target_versions",
                "request_shape",
                "outcome",
                "error_code",
                "attempts",
                "latency_ms",
                "request_bytes",
                "response_bytes",
                "response_tokens_est",
                "token_method",
                "replayed",
                "batch_size",
                "failed_count",
                "result_count",
                "eligible_count"
            ),
            columns,
            "call_log columns, in order"
        )

        val pk =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(call_log)").use { rs ->
                    buildList { while (rs.next()) if (rs.getInt("pk") > 0) add(rs.getString("name") to rs.getInt("notnull")) }
                }
            }
        assertEquals(listOf("req_id" to 1), pk, "req_id is the only primary-key column and it is declared NOT NULL")

        val indexes =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA index_list(call_log)").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString("name")) }
                }
            }
        for (name in listOf("idx_call_log_at", "idx_call_log_tool_at")) assertTrue(name in indexes, "$name missing: $indexes")
        assertEquals(listOf("tool", "at"), indexColumns(conn, "idx_call_log_tool_at"), "idx_call_log_tool_at column order")
        assertEquals(listOf("at"), indexColumns(conn, "idx_call_log_at"), "idx_call_log_at columns")

        assertEquals(0, count(conn, "call_log"), "call_log starts empty (no backfill)")
        val pre = before ?: error("SeedV21.seed did not run before the migration")
        for ((table, rows) in pre) assertEquals(rows, count(conn, table), "$table row count changed across V21")

        // Accepted shape: the minimal valid row, with replayed taking its default of 0.
        insert(conn, "ok00001a")
        assertEquals(
            0,
            conn.createStatement().use { st ->
                st.executeQuery("SELECT replayed FROM call_log WHERE req_id = 'ok00001a'").use {
                    it.next()
                    it.getInt(1)
                }
            },
            "replayed defaults to 0"
        )

        assertFailsWith<SQLException>("a 7-character req_id must be rejected") { insert(conn, "short07") }
        assertFailsWith<SQLException>("a 9-character req_id must be rejected") { insert(conn, "long00009") }
        assertFailsWith<SQLException>("a NULL req_id must be rejected") { insert(conn, null) }
        assertFailsWith<SQLException>("a 22-character at must be rejected") { insert(conn, "at000022", at = "2026-03-01 10:15:30.12") }
        assertFailsWith<SQLException>("outcome 'maybe' must be rejected") { insert(conn, "outmaybe", outcome = "maybe") }
        assertFailsWith<SQLException>("attempts 0 must be rejected") { insert(conn, "att00000", attempts = 0) }
        assertFailsWith<SQLException>("latency_ms -1 must be rejected") { insert(conn, "lat0000m", latency = -1) }
        assertFailsWith<SQLException>("replayed 2 must be rejected") { insert(conn, "rep00002", replayed = 2) }
        assertFailsWith<SQLException>("a duplicate req_id must be rejected") { insert(conn, "ok00001a") }

        conn.createStatement().use { it.executeUpdate("DELETE FROM call_log") }
        assertEquals(0, count(conn, "call_log"))
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
    ): Int =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM $table").use {
                it.next()
                it.getInt(1)
            }
        }

    @Suppress("LongParameterList")
    private fun insert(
        conn: Connection,
        reqId: String?,
        at: String = AT,
        outcome: String = "ok",
        attempts: Int = 1,
        latency: Int = 0,
        replayed: Int? = null
    ) {
        val cols = "req_id, at, surface, tool, outcome, attempts, latency_ms" + if (replayed != null) ", replayed" else ""
        val marks = "?, ?, ?, ?, ?, ?, ?" + if (replayed != null) ", ?" else ""
        conn.prepareStatement("INSERT INTO call_log ($cols) VALUES ($marks)").use { ps ->
            if (reqId == null) ps.setNull(1, java.sql.Types.VARCHAR) else ps.setString(1, reqId)
            ps.setString(2, at)
            ps.setString(3, "mcp")
            ps.setString(4, "seed21.probe")
            ps.setString(5, outcome)
            ps.setInt(6, attempts)
            ps.setInt(7, latency)
            if (replayed != null) ps.setInt(8, replayed)
            ps.executeUpdate()
        }
    }
}
