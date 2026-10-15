package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.StartupIntegrity
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V37 adds two nullable columns to `work_items`: `blocked_reason TEXT` with a value CHECK (NULL or one of `awaiting_decision`,
 * `dependency`, `resource`, `manual`) and `blocked_ref BLOB` (a decision id, no foreign key). No existing column is modified or
 * dropped, no table is recreated, no index or trigger is added and no row is rewritten or backfilled: a pre-V37 BLOCKED row keeps
 * both columns NULL.
 *
 * Oracle: the frozen migration-assessment of item 57d4dfc0, sections 1 to 4 (the two column definitions with type, nullability,
 * default and the CHECK vocabulary, the deliberate absence of a foreign key on `blocked_ref`, no data migration, the unchanged
 * index and trigger inventories) and the verification list (a) to (e) of section 4. Nothing is read back from the migration file or
 * from the schema snapshot; the column definitions below are written from the assessment alone.
 *
 * [verify] runs after the chain reaches the LATEST schema, so a column a later migration appends must not fail it: the new columns
 * are located by their position after every pre-V37 column (captured by [seed]), never by "is the last column".
 *
 * FTS integrity and the startup inventory are also checked by the harness itself ([UpgradeHarness.checkUpgrade]); [verify] adds the
 * explicit before/after comparison of the work_items triggers and indexes and the FTS check around a write to the new column.
 */
object SeedV37 : MigrationSeed(37) {
    private const val QUEUED = "s37-queued"
    private const val WORKING = "s37-working"
    private const val IN_REVIEW = "s37-in-review"
    private const val FINISHED = "s37-finished"
    private const val BLOCKED_FROM_WORK = "s37-blocked-from-work"
    private const val BLOCKED_FROM_QUEUE = "s37-blocked-from-queue"

    private val VALUES = listOf("awaiting_decision", "dependency", "resource", "manual")

    /** Row counts of every user table at the pre-migration version, captured by [seed] and compared by [verify]. */
    @Volatile
    private var before: Map<String, Int>? = null

    /** The work_items column names at the pre-migration version. */
    @Volatile
    private var columnsBefore: List<String>? = null

    /** The work_items index names and trigger (name, sql) pairs at the pre-migration version. */
    @Volatile
    private var objectsBefore: Pair<List<String>, List<Pair<String, String?>>>? = null

    /** The work_items foreign keys at the pre-migration version. */
    @Volatile
    private var foreignKeysBefore: List<String>? = null

    override fun seed(conn: Connection) {
        // One item per role, including two BLOCKED rows (previous_role work and queue) that the migration must leave untouched.
        BaselineDataset.extraItem(conn, QUEUED, "queue", null)
        BaselineDataset.extraItem(conn, WORKING, "work", "in-progress")
        BaselineDataset.extraItem(conn, IN_REVIEW, "review", null)
        BaselineDataset.extraItem(conn, FINISHED, "terminal", "done")
        BaselineDataset.extraItem(conn, BLOCKED_FROM_WORK, "blocked", null)
        BaselineDataset.extraItem(conn, BLOCKED_FROM_QUEUE, "blocked", null)
        setPreviousRole(conn, BLOCKED_FROM_WORK, "work")
        setPreviousRole(conn, BLOCKED_FROM_QUEUE, "queue")
        before = UpgradeHarness.userTables(conn).associateWith { count(conn, it) }
        columnsBefore = names(conn)
        objectsBefore = objects(conn)
        foreignKeysBefore = foreignKeyRows(conn)
    }

    private fun setPreviousRole(
        conn: Connection,
        name: String,
        role: String
    ) {
        conn.prepareStatement("UPDATE work_items SET previous_role = ? WHERE id = ?").use { ps ->
            ps.setString(1, role)
            ps.setBytes(2, BaselineDataset.id(name))
            ps.executeUpdate()
        }
    }

    // expected() keeps its default (an empty map): no existing column is rewritten by this migration.

    override fun verify(conn: Connection) {
        verifyNewColumns(conn)
        verifyNoBackfill(conn)
        verifyExistingObjectsUntouched(conn)
        verifyReasonCheck(conn)
        verifyRefHasNoForeignKey(conn)
        verifyFts(conn)
    }

    // ---------------------------------------------------------------------------------------------------------- columns

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Int,
        val default: String?,
        val pk: Int
    )

    /** Assessment verification (a): both ADD COLUMNs are nullable with no default, appended in order after every pre-V37 column. */
    private fun verifyNewColumns(conn: Connection) {
        val pre = columnsBefore ?: error("SeedV37.seed did not run before the migration")
        val columns = tableInfo(conn)
        assertEquals(
            Column("blocked_reason", "TEXT", 0, null, 0),
            columns.single { it.name == "blocked_reason" },
            "work_items.blocked_reason (TEXT, notnull 0, default NULL, pk 0)"
        )
        assertEquals(
            Column("blocked_ref", "BLOB", 0, null, 0),
            columns.single { it.name == "blocked_ref" },
            "work_items.blocked_ref (BLOB, notnull 0, default NULL, pk 0)"
        )
        // ADD COLUMN appends: the pre-V37 columns keep their order and the two new columns follow them (later migrations may append more).
        assertEquals(pre, columns.map { it.name }.take(pre.size), "work_items keeps its columns in order")
        assertEquals("blocked_reason", columns[pre.size].name, "blocked_reason is appended right after them")
        assertEquals("blocked_ref", columns[pre.size + 1].name, "blocked_ref follows blocked_reason")
    }

    /** Assessment section 3 / verification (b): nothing is backfilled and the seeded rows are unchanged. */
    private fun verifyNoBackfill(conn: Connection) {
        assertEquals(0L, scalar(conn, "SELECT count(*) FROM work_items WHERE blocked_reason IS NOT NULL"), "no row has a blocked reason")
        assertEquals(0L, scalar(conn, "SELECT count(*) FROM work_items WHERE blocked_ref IS NOT NULL"), "no row has a blocked ref")
        assertEquals(
            "blocked",
            string(conn, "SELECT role FROM work_items WHERE id = x'${hex(BaselineDataset.id(BLOCKED_FROM_WORK))}'"),
            "the legacy blocked row (previous_role work) is still blocked"
        )
        assertEquals(
            "work",
            string(conn, "SELECT previous_role FROM work_items WHERE id = x'${hex(BaselineDataset.id(BLOCKED_FROM_WORK))}'"),
            "its previous role is untouched"
        )
        assertEquals(
            "blocked",
            string(conn, "SELECT role FROM work_items WHERE id = x'${hex(BaselineDataset.id(BLOCKED_FROM_QUEUE))}'"),
            "the legacy blocked row (previous_role queue) is still blocked"
        )
        assertEquals(
            "queue",
            string(conn, "SELECT previous_role FROM work_items WHERE id = x'${hex(BaselineDataset.id(BLOCKED_FROM_QUEUE))}'"),
            "its previous role is untouched"
        )
        assertEquals(
            3L,
            scalar(conn, "SELECT count(*) FROM work_items WHERE role = 'blocked'"),
            "exactly the baseline's blocked row and the two seeded ones are blocked"
        )
        for ((name, role) in listOf(QUEUED to "queue", WORKING to "work", IN_REVIEW to "review", FINISHED to "terminal")) {
            assertEquals(
                role,
                string(conn, "SELECT role FROM work_items WHERE id = x'${hex(BaselineDataset.id(name))}'"),
                "$name keeps role $role"
            )
        }
    }

    /** Assessment verification (c): every table keeps its row count, and the work_items indexes, triggers and foreign keys are unchanged. */
    private fun verifyExistingObjectsUntouched(conn: Connection) {
        val counts = before ?: error("SeedV37.seed did not run before the migration")
        for ((table, rows) in SeedV29.afterRelocation(counts) - "data_steps") {
            assertEquals(rows, count(conn, table), "$table row count changed across V37")
        }
        val was = objectsBefore ?: error("SeedV37.seed did not run before the migration")
        assertEquals(was, objects(conn), "the work_items indexes and triggers are unchanged by V37 (no new index, no recreation)")
        assertEquals(8, was.second.size, "work_items keeps its 6 + 2 triggers")
        assertEquals(foreignKeysBefore, foreignKeyRows(conn), "work_items gains no foreign key (blocked_ref is not one)")
    }

    // ----------------------------------------------------------------------------------------------------------------- check

    /** Assessment verification (d): control write first, then each CHECK rejection and each vocabulary acceptance. */
    private fun verifyReasonCheck(conn: Connection) {
        val target = BaselineDataset.id(BLOCKED_FROM_WORK)

        fun write(value: String?) {
            conn.prepareStatement("UPDATE work_items SET blocked_reason = ? WHERE id = ?").use { ps ->
                if (value == null) ps.setNull(1, java.sql.Types.NULL) else ps.setString(1, value)
                ps.setBytes(2, target)
                ps.executeUpdate()
            }
        }

        fun stored(): String? = string(conn, "SELECT blocked_reason FROM work_items WHERE id = x'${hex(target)}'")

        // Control first: a valid write is accepted, so every rejection below is attributable to its one changed value.
        write("manual")
        assertEquals("manual", stored(), "control: a valid reason is accepted and stored")

        for (bad in listOf("Manual", "MANUAL", "awaiting-decision", "awaitingDecision", "", " manual", "blocked", "decision")) {
            assertFailsWith<SQLException>("blocked_reason '$bad' must be rejected") { write(bad) }
            assertEquals("manual", stored(), "the rejected write '$bad' left the stored value alone")
        }
        for (good in VALUES) {
            write(good)
            assertEquals(good, stored(), "blocked_reason '$good' is accepted and stored")
        }
        write(null)
        assertNull(stored(), "NULL is accepted (the legacy and non-blocked value)")
        // The CHECK is on the column, not the role: the vocabulary applies to a row of any role.
        conn.prepareStatement("UPDATE work_items SET blocked_reason = 'bogus' WHERE id = ?").use { ps ->
            ps.setBytes(1, BaselineDataset.id(WORKING))
            assertFailsWith<SQLException>("a bogus reason on a work row must be rejected") { ps.executeUpdate() }
        }
        assertEquals(
            0L,
            scalar(conn, "SELECT count(*) FROM work_items WHERE blocked_reason IS NOT NULL"),
            "the probes leave every blocked_reason NULL again"
        )
    }

    /** Assessment section 1 / verification (d): `blocked_ref` takes any 16-byte BLOB and NULL, with no foreign key even when enforced. */
    private fun verifyRefHasNoForeignKey(conn: Connection) {
        val target = BaselineDataset.id(BLOCKED_FROM_WORK)
        val ghost = BaselineDataset.bytes(UUID.nameUUIDFromBytes("seed37-no-such-decision".toByteArray()))
        conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        try {
            conn.prepareStatement("UPDATE work_items SET blocked_ref = ? WHERE id = ?").use { ps ->
                ps.setBytes(1, ghost)
                ps.setBytes(2, target)
                assertEquals(1, ps.executeUpdate(), "a ref naming no row is accepted (no foreign key), even with enforcement on")
            }
            assertEquals(
                "blob",
                string(conn, "SELECT typeof(blocked_ref) FROM work_items WHERE id = x'${hex(target)}'"),
                "blocked_ref stores a BLOB"
            )
            assertEquals(
                16L,
                scalar(conn, "SELECT length(blocked_ref) FROM work_items WHERE id = x'${hex(target)}'"),
                "a 16-byte UUID BLOB, like every id column"
            )
            assertEquals(
                1L,
                scalar(conn, "SELECT count(*) FROM work_items WHERE blocked_ref = x'${hex(ghost)}'"),
                "the stored BLOB compares equal to the bytes written"
            )
            conn.prepareStatement("UPDATE work_items SET blocked_ref = NULL WHERE id = ?").use { ps ->
                ps.setBytes(1, target)
                assertEquals(1, ps.executeUpdate(), "NULL is accepted")
            }
            assertEquals(
                0,
                conn.createStatement().use { st ->
                    st.executeQuery("PRAGMA foreign_key_check").use { rs ->
                        var n = 0
                        while (rs.next()) n++
                        n
                    }
                },
                "PRAGMA foreign_key_check is empty after the probes"
            )
        } finally {
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }
        }
        assertEquals(0L, scalar(conn, "SELECT count(*) FROM work_items WHERE blocked_ref IS NOT NULL"), "the probes leave every ref NULL")
    }

    /** Assessment verification (e): the FTS indexes of work_items pass integrity and a write to blocked_reason alone leaves them as they were. */
    private fun verifyFts(conn: Connection) {
        val ftsTables = StartupIntegrity.FTS_BACKING.filterValues { it == "work_items" }.keys
        assertTrue(ftsTables.isNotEmpty(), "work_items has FTS tables")

        fun docsizes(fts: String): List<String> = strings(conn, "SELECT id || ':' || hex(sz) FROM ${fts}_docsize ORDER BY id")

        val snapshot = ftsTables.associateWith { docsizes(it) }
        conn.prepareStatement("UPDATE work_items SET blocked_reason = 'resource' WHERE id = ?").use { ps ->
            ps.setBytes(1, BaselineDataset.id(BLOCKED_FROM_WORK))
            ps.executeUpdate()
        }
        try {
            for (fts in ftsTables) {
                assertEquals(snapshot.getValue(fts), docsizes(fts), "$fts is untouched by a write to blocked_reason alone")
                conn.createStatement().use { it.execute("INSERT INTO $fts($fts, rank) VALUES('integrity-check', 1)") }
                assertTrue(
                    scalar(conn, "SELECT count(*) FROM $fts WHERE $fts MATCH '${BaselineDataset.ITEM_FTS_TOKEN}'") >= 1,
                    "$fts still finds the baseline token after the write"
                )
            }
        } finally {
            conn.prepareStatement("UPDATE work_items SET blocked_reason = NULL WHERE id = ?").use { ps ->
                ps.setBytes(1, BaselineDataset.id(BLOCKED_FROM_WORK))
                ps.executeUpdate()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------- helpers

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

    private fun names(conn: Connection): List<String> = tableInfo(conn).map { it.name }

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

    /** The work_items index names and (trigger name, sql) pairs, ordered by name. */
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

    private fun strings(
        conn: Connection,
        sql: String
    ): List<String> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
}
