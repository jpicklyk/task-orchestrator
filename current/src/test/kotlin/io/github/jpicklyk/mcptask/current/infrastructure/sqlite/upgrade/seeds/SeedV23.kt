package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepKind
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V23 only adds the empty `data_steps` table: every existing table is unchanged and nothing is backfilled.
 * Oracle: migration-assessment section 1 of item 6b998895 (the frozen DDL: column order and types, the NOT NULL TEXT
 * primary key, the CHECK set, no index beyond the primary-key autoindex) and section 4 (the verification list for this
 * seed: column list and order, PK, no extra index, empty table, existing counts unchanged, CHECK probes).
 *
 * "Starts empty" is checked against the production step set, not against a literal zero: the harness runs the production
 * data steps after the migration and before [verify], so the table holds exactly the names of the registered ONCE steps
 * (none in Q2a). Anything else in the table would be a row the migration or a stray writer put there.
 */
object SeedV23 : MigrationSeed(23) {
    private const val AT = "2026-03-01 10:15:30.123"

    /** Row counts of every user table at the pre-migration version, captured by [seed] and compared by [verify]. */
    @Volatile
    private var before: Map<String, Int>? = null

    override fun seed(conn: Connection) {
        before = UpgradeHarness.userTables(conn).associateWith { count(conn, it) }
    }

    override fun verify(conn: Connection) {
        val info =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(data_steps)").use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                listOf(rs.getString("name"), rs.getString("type"), rs.getInt("notnull"), rs.getInt("pk"))
                            )
                        }
                    }
                }
            }
        assertEquals(
            listOf("name", "applied_at", "rows_affected", "binary_version"),
            info.map { it[0] },
            "data_steps columns, in order"
        )
        assertEquals(listOf("TEXT", "TEXT", "INTEGER", "TEXT"), info.map { it[1] }, "data_steps column types")
        assertEquals(listOf(1, 1, 1, 1), info.map { it[2] }, "every column is declared NOT NULL")
        assertEquals(
            listOf("name" to 1),
            info
                .filter {
                    it[3] as Int > 0
                }.map { (it[0] as String) to (it[2] as Int) },
            "name is the only PK column"
        )

        val indexes =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA index_list(data_steps)").use { rs ->
                    buildList { while (rs.next()) add(rs.getString("name")) }
                }
            }
        assertTrue(indexes.all { it.startsWith("sqlite_autoindex_data_steps") }, "no index besides the primary-key autoindex: $indexes")

        val expectedNames = productionOnceStepNames(conn)
        assertEquals(expectedNames, names(conn), "data_steps holds exactly the registered ONCE steps (the migration backfills nothing)")
        val pre = before ?: error("SeedV23.seed did not run before the migration")
        for ((table, rows) in pre) assertEquals(rows, count(conn, table), "$table row count changed across V23")

        // Accepted shapes, at the boundaries of every CHECK.
        insert(conn, "seed23-ok")
        insert(conn, "a")
        insert(conn, "n".repeat(64))
        insert(conn, "seed23-zero-rows", rows = 0)

        assertFailsWith<SQLException>("an empty name must be rejected") { insert(conn, "") }
        assertFailsWith<SQLException>("a 65-character name must be rejected") { insert(conn, "n".repeat(65)) }
        assertFailsWith<SQLException>("a NULL name must be rejected") { insert(conn, null) }
        assertFailsWith<SQLException>("a duplicate name must be rejected") { insert(conn, "seed23-ok") }
        assertFailsWith<SQLException>(
            "a 22-character applied_at must be rejected"
        ) { insert(conn, "seed23-at22", at = "2026-03-01 10:15:30.12") }
        assertFailsWith<SQLException>(
            "a 24-character applied_at must be rejected"
        ) { insert(conn, "seed23-at24", at = "2026-03-01 10:15:30.1234") }
        assertFailsWith<SQLException>("a NULL applied_at must be rejected") { insert(conn, "seed23-atnull", at = null) }
        assertFailsWith<SQLException>("rows_affected -1 must be rejected") { insert(conn, "seed23-neg", rows = -1) }
        assertFailsWith<SQLException>("a NULL rows_affected must be rejected") { insert(conn, "seed23-rownull", rows = null) }
        assertFailsWith<SQLException>("a NULL binary_version must be rejected") { insert(conn, "seed23-vernull", version = null) }

        conn.createStatement().use {
            it.executeUpdate("DELETE FROM data_steps WHERE name IN ('seed23-ok', 'a', '${"n".repeat(64)}', 'seed23-zero-rows')")
        }
        assertEquals(expectedNames, names(conn), "the probe rows are removed again")
    }

    /** The ONCE steps of the production composition for the database behind [conn] (a re-run skips every recorded one). */
    private fun productionOnceStepNames(conn: Connection): Set<String> {
        val dir = Files.createTempDirectory("seed-v23-")
        try {
            val (_, plan) = UpgradeHarness.runProductionDataStepsWithPlan(conn.metaData.url, dir.toFile())
            return plan.filter { it.kind == DataStepKind.ONCE }.map { it.name }.toSet()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun names(conn: Connection): Set<String> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT name FROM data_steps").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
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
        name: String?,
        at: String? = AT,
        rows: Int? = 1,
        version: String? = "4.0.0-seed"
    ) {
        conn.prepareStatement("INSERT INTO data_steps (name, applied_at, rows_affected, binary_version) VALUES (?, ?, ?, ?)").use { ps ->
            if (name == null) ps.setNull(1, java.sql.Types.VARCHAR) else ps.setString(1, name)
            if (at == null) ps.setNull(2, java.sql.Types.VARCHAR) else ps.setString(2, at)
            if (rows == null) ps.setNull(3, java.sql.Types.INTEGER) else ps.setInt(3, rows)
            if (version == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, version)
            ps.executeUpdate()
        }
    }
}
