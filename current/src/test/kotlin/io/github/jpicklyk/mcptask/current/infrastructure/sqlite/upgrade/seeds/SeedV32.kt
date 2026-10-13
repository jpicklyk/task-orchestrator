package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * V32 adds two new tables, `runs` and `run_items`, with the indexes `idx_runs_root_status` on `runs(root_id, status)` and
 * `idx_run_items_item` on `run_items(item_id)`. No existing table, column, index or trigger is touched and no row is rewritten.
 *
 * Oracle: the frozen migration-assessment of item 8a71510c, sections 1 to 4 (the column lists with their types, nullability and
 * defaults; the closed-vocabulary CHECKs on status, pause_reason and terminal_reason; the length CHECKs on label, title and the
 * 23-character timestamps; `stall_after_s > 0`; the three foreign keys, all ON DELETE CASCADE; the two indexes with column order;
 * no data migration) and the verification list of section 4 (CHECK rejections, foreign key list, a JDBC round trip for both
 * cascade directions and for a root delete with a closed run, every other table's count unchanged). Nothing is read back from
 * the migration file.
 */
object SeedV32 : MigrationSeed(32) {
    private val ROOT = BaselineDataset.id("seed32-root")
    private val CHILD_A = BaselineDataset.id("seed32-child-a")
    private val CHILD_B = BaselineDataset.id("seed32-child-b")
    private val RUN_ONE = BaselineDataset.id("seed32-run-one")
    private val RUN_TWO = BaselineDataset.id("seed32-run-two")
    private val RUN_PROBE = BaselineDataset.id("seed32-run-probe")
    private val TS = BaselineDataset.TS

    /** Row counts of every user table at the pre-migration version, captured by [seed] and compared by [verify]. */
    @Volatile
    private var before: Map<String, Int>? = null

    override fun seed(conn: Connection) {
        before = UpgradeHarness.userTables(conn).associateWith { count(conn, it) }
    }

    // expected() keeps its default (an empty map): no existing column is rewritten by this migration.

    override fun verify(conn: Connection) {
        verifyRunsColumns(conn)
        verifyRunItemsColumns(conn)
        verifyIndexes(conn)
        verifyForeignKeys(conn)
        verifyExistingTablesUntouched(conn)
        verifyChecks(conn)
        verifyDefaults(conn)
        verifyCascades(conn)
    }

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Int,
        val default: String?,
        val pk: Int
    )

    private fun col(
        name: String,
        type: String,
        notNull: Int = 0,
        default: String? = null,
        pk: Int = 0
    ) = Column(name, type, notNull, default, pk)

    private fun verifyRunsColumns(conn: Connection) {
        val expected =
            listOf(
                col("id", "BLOB", notNull = 1, pk = 1),
                col("root_id", "BLOB", notNull = 1),
                col("scope_id", "BLOB"),
                col("label", "TEXT"),
                col("title", "TEXT"),
                col("status", "TEXT", notNull = 1),
                col("pause_reason", "TEXT"),
                col("pause_decision_id", "BLOB"),
                col("terminal_reason", "TEXT"),
                col("opened_by", "TEXT"),
                col("opened_by_kind", "TEXT"),
                col("opened_at", "TEXT", notNull = 1),
                col("closed_by", "TEXT"),
                col("closed_at", "TEXT"),
                col("coordinator_seen_at", "TEXT"),
                col("stall_after_s", "INTEGER", notNull = 1),
                col("budget", "TEXT"),
                col("reported_tokens", "INTEGER", notNull = 1, default = "0"),
                col("reported_cost_micro", "INTEGER", notNull = 1, default = "0"),
                col("observed_tokens", "INTEGER", notNull = 1, default = "0"),
                col("hints", "TEXT"),
                col("marks", "TEXT"),
                col("version", "INTEGER", notNull = 1, default = "1")
            )
        assertEquals(expected, tableInfo(conn, "runs"), "runs has exactly the frozen columns, types, nullability and defaults")
    }

    private fun verifyRunItemsColumns(conn: Connection) {
        val expected =
            listOf(
                col("run_id", "BLOB", notNull = 1, pk = 1),
                col("item_id", "BLOB", notNull = 1, pk = 2),
                col("added_at", "TEXT", notNull = 1)
            )
        assertEquals(expected, tableInfo(conn, "run_items"), "run_items has exactly the frozen columns and the (run_id, item_id) key")
    }

    private fun verifyIndexes(conn: Connection) {
        val runs = indexList(conn, "runs")
        val runIndex = runs["idx_runs_root_status"] ?: error("idx_runs_root_status missing: ${runs.keys}")
        assertEquals(listOf("root_id", "status"), indexColumns(conn, "idx_runs_root_status"), "idx_runs_root_status column order")
        assertEquals(0, runIndex.unique, "idx_runs_root_status is not unique")
        assertEquals(0, runIndex.partial, "idx_runs_root_status is a full index")

        val items = indexList(conn, "run_items")
        val itemIndex = items["idx_run_items_item"] ?: error("idx_run_items_item missing: ${items.keys}")
        assertEquals(listOf("item_id"), indexColumns(conn, "idx_run_items_item"), "idx_run_items_item column")
        assertEquals(0, itemIndex.unique, "idx_run_items_item is not unique")
        assertEquals(0, itemIndex.partial, "idx_run_items_item is a full index")
    }

    private fun verifyForeignKeys(conn: Connection) {
        val runs = foreignKeys(conn, "runs")
        assertEquals(
            listOf(FkRow(from = "root_id", table = "work_items", to = "id", onDelete = "CASCADE")),
            runs,
            "runs.root_id references work_items(id) ON DELETE CASCADE and nothing else is a foreign key (scope_id and pause_decision_id have none)"
        )
        val items = foreignKeys(conn, "run_items").sortedBy { it.from }
        assertEquals(
            listOf(
                FkRow(from = "item_id", table = "work_items", to = "id", onDelete = "CASCADE"),
                FkRow(from = "run_id", table = "runs", to = "id", onDelete = "CASCADE")
            ),
            items,
            "run_items references runs(id) and work_items(id), both ON DELETE CASCADE"
        )
    }

    private fun verifyExistingTablesUntouched(conn: Connection) {
        val counts = before ?: error("SeedV32.seed did not run before the migration")
        for ((table, rows) in SeedV29.afterRelocation(counts) - "data_steps") {
            assertEquals(rows, count(conn, table), "$table row count changed across V32")
        }
        assertEquals(0, count(conn, "runs"), "the migration creates runs empty (no backfill)")
        assertEquals(0, count(conn, "run_items"), "the migration creates run_items empty (no backfill)")
    }

    private fun verifyChecks(conn: Connection) {
        // Control first: the baseline insert is accepted, so every rejection below is attributable to its one changed value.
        insertRun(conn, RUN_PROBE)
        deleteRun(conn, RUN_PROBE)

        fun rejected(
            what: String,
            vararg overrides: Pair<String, Any?>
        ) {
            assertFailsWith<SQLException>("$what must be rejected by a CHECK") { insertRun(conn, RUN_PROBE, *overrides) }
            deleteRun(conn, RUN_PROBE)
        }

        fun accepted(
            what: String,
            vararg overrides: Pair<String, Any?>
        ) {
            insertRun(conn, RUN_PROBE, *overrides)
            assertEquals(1, countRuns(conn, RUN_PROBE), "$what must be accepted and stored")
            deleteRun(conn, RUN_PROBE)
        }

        rejected("status 'Open' (case-sensitive vocabulary)", "status" to "Open")
        rejected("status 'archived'", "status" to "archived")
        rejected("stall_after_s 0", "stall_after_s" to 0)
        rejected("stall_after_s -1", "stall_after_s" to -1)
        rejected("a 65-character label", "label" to "l".repeat(65))
        rejected("a 201-character title", "title" to "t".repeat(201))
        rejected("pause_reason 'bogus'", "status" to "paused", "pause_reason" to "bogus")
        rejected("terminal_reason 'bogus'", "status" to "closed", "terminal_reason" to "bogus")
        rejected("a 22-character opened_at", "opened_at" to TS.dropLast(1))
        rejected("a 24-character opened_at", "opened_at" to TS + "0")
        rejected("a 22-character closed_at", "status" to "closed", "terminal_reason" to "completed", "closed_at" to TS.dropLast(1))
        rejected("a 22-character coordinator_seen_at", "coordinator_seen_at" to TS.dropLast(1))

        // The boundaries and every vocabulary member are accepted, so the rejections above are not an over-strict table.
        accepted("a 64-character label", "label" to "l".repeat(64))
        accepted("a 200-character title", "title" to "t".repeat(200))
        accepted("stall_after_s 1", "stall_after_s" to 1)
        for (status in listOf("open", "paused", "closed")) accepted("status $status", "status" to status)
        for (reason in listOf("budget", "attempts", "manual")) {
            accepted(
                "pause_reason $reason",
                "status" to "paused",
                "pause_reason" to reason
            )
        }
        for (reason in listOf("completed", "cancelled", "failed", "abandoned", "budget_exhausted")) {
            accepted("terminal_reason $reason", "status" to "closed", "terminal_reason" to reason, "closed_at" to TS)
        }

        // run_items: a 22-character added_at is rejected, a 23-character one is accepted (needs real parents).
        withForeignKeys(conn) {
            insertItem(conn, ROOT, parent = null, depth = 0)
            insertRun(conn, RUN_ONE)
            insertItem(conn, CHILD_A, parent = ROOT, depth = 1)
            assertFailsWith<SQLException>(
                "a 22-character added_at must be rejected"
            ) { insertMember(conn, RUN_ONE, CHILD_A, TS.dropLast(1)) }
            insertMember(conn, RUN_ONE, CHILD_A, TS)
            assertFailsWith<SQLException>("the (run_id, item_id) key must reject a duplicate membership") {
                insertMember(conn, RUN_ONE, CHILD_A, TS)
            }
            deleteRun(conn, RUN_ONE)
            deleteItem(conn, CHILD_A)
            deleteItem(conn, ROOT)
        }
        assertEquals(
            0L,
            scalar(conn, "SELECT count(*) FROM work_items WHERE id IN (x'${hex(ROOT)}', x'${hex(CHILD_A)}')"),
            "probe items removed"
        )
    }

    private fun verifyDefaults(conn: Connection) {
        insertRun(conn, RUN_PROBE)
        try {
            val row =
                conn
                    .prepareStatement(
                        "SELECT reported_tokens, reported_cost_micro, observed_tokens, version, label, title, budget, hints, marks, " +
                            "scope_id, pause_reason, pause_decision_id, terminal_reason, closed_at, coordinator_seen_at FROM runs WHERE id = ?"
                    ).use { ps ->
                        ps.setBytes(1, RUN_PROBE)
                        ps.executeQuery().use { rs ->
                            check(rs.next()) { "probe run vanished" }
                            (1..15).map { rs.getObject(it) }
                        }
                    }
            assertEquals(
                listOf(0, 0, 0, 1),
                row.take(4).map {
                    (it as Number).toInt()
                },
                "reported_tokens, reported_cost_micro, observed_tokens default 0; version defaults 1"
            )
            row.drop(4).forEachIndexed { i, v -> assertNull(v, "nullable column #${i + 4} reads NULL when not supplied") }
        } finally {
            deleteRun(conn, RUN_PROBE)
        }
    }

    private fun verifyCascades(conn: Connection) {
        withForeignKeys(conn) {
            // 1. Deleting an item drops its membership rows and leaves the run.
            insertItem(conn, ROOT, parent = null, depth = 0)
            insertItem(conn, CHILD_A, parent = ROOT, depth = 1)
            insertItem(conn, CHILD_B, parent = ROOT, depth = 1)
            insertRun(conn, RUN_ONE)
            insertMember(conn, RUN_ONE, CHILD_A, TS)
            insertMember(conn, RUN_ONE, CHILD_B, TS)
            assertEquals(2, countMembers(conn, RUN_ONE), "control: both memberships exist")
            deleteItem(conn, CHILD_A)
            assertEquals(1, countMembers(conn, RUN_ONE), "deleting an item drops exactly its run_items row")
            assertEquals(1, countRuns(conn, RUN_ONE), "deleting an item leaves the run itself")

            // 2. Deleting a run drops its membership rows and leaves the items.
            deleteRun(conn, RUN_ONE)
            assertEquals(0, countMembers(conn, RUN_ONE), "deleting a run drops its run_items rows")
            assertEquals(
                1L,
                scalar(conn, "SELECT count(*) FROM work_items WHERE id = x'${hex(CHILD_B)}'"),
                "deleting a run leaves its items"
            )

            // 3. Deleting a root drops its (closed) runs.
            insertRun(conn, RUN_TWO, "status" to "closed", "terminal_reason" to "completed", "closed_at" to TS)
            deleteItem(conn, CHILD_B)
            assertEquals(1, countRuns(conn, RUN_TWO), "control: the closed run exists before the root delete")
            deleteItem(conn, ROOT)
            assertEquals(0, countRuns(conn, RUN_TWO), "deleting the root drops the runs that name it")

            // 4. The foreign key is real: a run naming a root that does not exist is rejected while enforcement is on.
            val ghost = BaselineDataset.id("seed32-no-such-root")
            assertFailsWith<SQLException>("a run naming no work item must be rejected") { insertRun(conn, RUN_PROBE, "root_id" to ghost) }
            assertEquals(0, countRuns(conn, RUN_PROBE), "the rejected run left no row")
        }
        assertEquals(0, count(conn, "runs"), "the round trip leaves runs empty again")
        assertEquals(0, count(conn, "run_items"), "the round trip leaves run_items empty again")
        assertEquals(
            0,
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA foreign_key_check").use { rs ->
                    var n = 0
                    while (rs.next()) n++
                    n
                }
            },
            "PRAGMA foreign_key_check is empty after the migration and the round trip"
        )
    }

    private fun withForeignKeys(
        conn: Connection,
        block: () -> Unit
    ) {
        conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        try {
            block()
        } finally {
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }
        }
    }

    private fun insertItem(
        conn: Connection,
        id: ByteArray,
        parent: ByteArray?,
        depth: Int
    ) {
        conn
            .prepareStatement(
                "INSERT INTO work_items (id, parent_id, title, role, lifecycle, depth, created_at, modified_at, role_changed_at) " +
                    "VALUES (?, ?, 'seed32 probe', 'queue', NULL, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setBytes(1, id)
                if (parent == null) ps.setNull(2, java.sql.Types.BLOB) else ps.setBytes(2, parent)
                ps.setInt(3, depth)
                ps.setString(4, TS)
                ps.setString(5, TS)
                ps.setString(6, TS)
                ps.executeUpdate()
            }
    }

    private fun deleteItem(
        conn: Connection,
        id: ByteArray
    ) {
        conn.prepareStatement("DELETE FROM work_items WHERE id = ?").use { ps ->
            ps.setBytes(1, id)
            ps.executeUpdate()
        }
    }

    /** Inserts a run with the minimal valid values for the NOT NULL columns; [overrides] replace or add columns by name. */
    private fun insertRun(
        conn: Connection,
        id: ByteArray,
        vararg overrides: Pair<String, Any?>
    ) {
        val values =
            linkedMapOf<String, Any?>(
                "id" to id,
                "root_id" to ROOT,
                "status" to "open",
                "opened_at" to TS,
                "stall_after_s" to 600
            )
        for ((k, v) in overrides) values[k] = v
        val columns = values.keys.joinToString()
        val marks = values.keys.joinToString { "?" }
        conn.prepareStatement("INSERT INTO runs ($columns) VALUES ($marks)").use { ps ->
            values.values.forEachIndexed { i, v ->
                when (v) {
                    null -> ps.setNull(i + 1, java.sql.Types.NULL)
                    is ByteArray -> ps.setBytes(i + 1, v)
                    is Int -> ps.setInt(i + 1, v)
                    else -> ps.setString(i + 1, v.toString())
                }
            }
            ps.executeUpdate()
        }
    }

    private fun deleteRun(
        conn: Connection,
        id: ByteArray
    ) {
        conn.prepareStatement("DELETE FROM runs WHERE id = ?").use { ps ->
            ps.setBytes(1, id)
            ps.executeUpdate()
        }
    }

    private fun insertMember(
        conn: Connection,
        run: ByteArray,
        item: ByteArray,
        addedAt: String
    ) {
        conn.prepareStatement("INSERT INTO run_items (run_id, item_id, added_at) VALUES (?, ?, ?)").use { ps ->
            ps.setBytes(1, run)
            ps.setBytes(2, item)
            ps.setString(3, addedAt)
            ps.executeUpdate()
        }
    }

    private fun countMembers(
        conn: Connection,
        run: ByteArray
    ): Int = scalar(conn, "SELECT count(*) FROM run_items WHERE run_id = x'${hex(run)}'").toInt()

    private fun countRuns(
        conn: Connection,
        run: ByteArray
    ): Int = scalar(conn, "SELECT count(*) FROM runs WHERE id = x'${hex(run)}'").toInt()

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private data class IndexInfo(
        val unique: Int,
        val partial: Int
    )

    private data class FkRow(
        val from: String,
        val table: String,
        val to: String,
        val onDelete: String
    )

    private fun tableInfo(
        conn: Connection,
        table: String
    ): List<Column> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_xinfo($table)").use { rs ->
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

    private fun foreignKeys(
        conn: Connection,
        table: String
    ): List<FkRow> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA foreign_key_list($table)").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            FkRow(rs.getString("from"), rs.getString("table"), rs.getString("to"), rs.getString("on_delete"))
                        )
                    }
                }
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
