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
 * V38 appends one nullable column to `work_items`: `builds INTEGER` with a CHECK (NULL, 0 or 1). No existing column is modified
 * or dropped, no table is recreated, no index, trigger or foreign key is added, and no row is backfilled: every pre-V38 row
 * reads NULL, which the eligibility predicate treats as "builds".
 *
 * Oracle: the migration-assessment of item 6aa122da, sections 1 to 4 (the column definition, no backfill, the unchanged index,
 * trigger and foreign-key inventories, the CHECK vocabulary). Nothing is read back from the migration file or the snapshot.
 *
 * [verify] runs after the chain reaches the LATEST schema, so the new column is located by its position after every pre-V38
 * column (captured by [seed]), never by "is the last column".
 */
object SeedV38 : MigrationSeed(38) {
    private const val QUEUED = "s38-queued"
    private const val WORKING = "s38-working"
    private const val FINISHED = "s38-finished"
    private const val LIFECYCLE_FREE = "s38-lifecycle-free"

    @Volatile
    private var before: Map<String, Int>? = null

    @Volatile
    private var columnsBefore: List<String>? = null

    @Volatile
    private var objectsBefore: Pair<List<String>, List<Pair<String, String?>>>? = null

    @Volatile
    private var foreignKeysBefore: List<String>? = null

    override fun seed(conn: Connection) {
        BaselineDataset.extraItem(conn, QUEUED, "queue", null)
        BaselineDataset.extraItem(conn, WORKING, "work", "in-progress")
        BaselineDataset.extraItem(conn, FINISHED, "terminal", "done")
        BaselineDataset.extraItem(conn, LIFECYCLE_FREE, "queue", null)
        // A lifecycle-free row: NULL role with lifecycle 'none' (the V26 relation CHECK).
        conn.prepareStatement("UPDATE work_items SET role = NULL, lifecycle = 'none' WHERE id = ?").use { ps ->
            ps.setBytes(1, BaselineDataset.id(LIFECYCLE_FREE))
            ps.executeUpdate()
        }
        before = UpgradeHarness.userTables(conn).associateWith { count(conn, it) }
        columnsBefore = tableInfo(conn).map { it.name }
        objectsBefore = objects(conn)
        foreignKeysBefore = foreignKeyRows(conn)
    }

    // expected() keeps its default (an empty map): no existing column is rewritten by this migration.

    override fun verify(conn: Connection) {
        verifyNewColumn(conn)
        verifyNoBackfill(conn)
        verifyExistingObjectsUntouched(conn)
        verifyBuildsCheck(conn)
    }

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Int,
        val default: String?,
        val pk: Int
    )

    /** Assessment section 1: one nullable INTEGER with no default, appended after every pre-V38 column. */
    private fun verifyNewColumn(conn: Connection) {
        val pre = columnsBefore ?: error("SeedV38.seed did not run before the migration")
        val columns = tableInfo(conn)
        assertEquals(
            Column("builds", "INTEGER", 0, null, 0),
            columns.single { it.name == "builds" },
            "work_items.builds (INTEGER, notnull 0, default NULL, pk 0)"
        )
        assertEquals(pre, columns.map { it.name }.take(pre.size), "work_items keeps its columns in order")
        assertEquals("builds", columns[pre.size].name, "builds is appended right after them")
    }

    /** Assessment section 3: nothing is backfilled; every row, seeded or baseline, reads NULL. */
    private fun verifyNoBackfill(conn: Connection) {
        assertEquals(0L, scalar(conn, "SELECT count(*) FROM work_items WHERE builds IS NOT NULL"), "no row has a builds value")
        assertNull(
            string(conn, "SELECT role FROM work_items WHERE id = x'${hex(BaselineDataset.id(LIFECYCLE_FREE))}'"),
            "the lifecycle-free row keeps its NULL role"
        )
        for ((name, role) in listOf(QUEUED to "queue", WORKING to "work", FINISHED to "terminal")) {
            assertEquals(
                role,
                string(conn, "SELECT role FROM work_items WHERE id = x'${hex(BaselineDataset.id(name))}'"),
                "$name keeps role $role"
            )
        }
    }

    /** Assessment section 4: row counts, the work_items indexes and triggers, and its foreign keys are unchanged. */
    private fun verifyExistingObjectsUntouched(conn: Connection) {
        val counts = before ?: error("SeedV38.seed did not run before the migration")
        for ((table, rows) in SeedV29.afterRelocation(counts) - "data_steps") {
            assertEquals(rows, count(conn, table), "$table row count changed across V38")
        }
        assertEquals(objectsBefore, objects(conn), "the work_items indexes and triggers are unchanged by V38")
        assertEquals(foreignKeysBefore, foreignKeyRows(conn), "work_items gains no foreign key")
    }

    /** Assessment section 4: the CHECK accepts 0, 1 and NULL and rejects 2. */
    private fun verifyBuildsCheck(conn: Connection) {
        val target = BaselineDataset.id(QUEUED)

        fun write(value: Int?) {
            conn.prepareStatement("UPDATE work_items SET builds = ? WHERE id = ?").use { ps ->
                if (value == null) ps.setNull(1, java.sql.Types.INTEGER) else ps.setInt(1, value)
                ps.setBytes(2, target)
                ps.executeUpdate()
            }
        }

        fun stored(): String? = string(conn, "SELECT builds FROM work_items WHERE id = x'${hex(target)}'")

        write(1)
        assertEquals("1", stored(), "control: 1 is accepted and stored")
        write(0)
        assertEquals("0", stored(), "0 is accepted and stored")
        assertFailsWith<SQLException>("builds 2 must be rejected") { write(2) }
        assertEquals("0", stored(), "the rejected write left the stored value alone")
        write(null)
        assertNull(stored(), "NULL is accepted")
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun tableInfo(conn: Connection): List<Column> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_xinfo(work_items)").use { rs ->
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
                st.executeQuery("PRAGMA foreign_key_list(work_items)").use { rs ->
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
                st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'work_items' ORDER BY name").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            val triggers =
                st
                    .executeQuery(
                        "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'work_items' ORDER BY name"
                    ).use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                    }
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
