package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V26 recreates `work_items` (role nullable, lifecycle and pin columns, a relation CHECK) and re-creates the item FTS
 * tables over title, summary and description without the prefix index. Nothing is rewritten, so [expected] stays empty and
 * the harness compares every common column exactly.
 *
 * Oracle: the frozen `migration-assessment` section 1 (column list, NOT NULL flags, eight indexes, eight triggers, FTS
 * definitions) and section 3 (the verification list for this seed) and `task-scope` AC6 (CHECK and FK probes).
 *
 * [seed] (at V25) adds a NULL-description task and a task whose description holds [DESCRIPTION_TOKEN] (absent from every
 * title and summary), and records the pre-migration rowid and role of every `work_items` row.
 */
object SeedV26 : MigrationSeed(26) {
    private const val DESCRIPTION_TOKEN = "seedv26-descr-token"
    private const val UPDATED_TOKEN = "seedv26-updated-token"

    private val expectedColumns =
        listOf(
            "id" to false,
            "parent_id" to false,
            "title" to true,
            "description" to false,
            "summary" to true,
            "role" to false,
            "status_label" to false,
            "previous_role" to false,
            "priority" to true,
            "complexity" to false,
            "requires_verification" to true,
            "depth" to true,
            "metadata" to false,
            "tags" to false,
            "type" to false,
            "properties" to false,
            "created_at" to true,
            "modified_at" to true,
            "role_changed_at" to true,
            "version" to true,
            "claimed_by" to false,
            "claimed_at" to false,
            "claim_expires_at" to false,
            "original_claimed_at" to false,
            "root_id" to false,
            "lifecycle" to false,
            "pinned_server_cfg" to false,
            "pinned_root_cfg" to false,
            "pinned_schema_key" to false,
            "pinned_schema_layer" to false,
            "pinned_at" to false
        )

    private val newColumns =
        listOf("lifecycle", "pinned_server_cfg", "pinned_root_cfg", "pinned_schema_key", "pinned_schema_layer", "pinned_at")

    private val expectedIndexes =
        setOf(
            "idx_work_items_parent",
            "idx_work_items_role",
            "idx_work_items_depth",
            "idx_work_items_priority",
            "idx_work_items_role_changed",
            "idx_work_items_claimed_by",
            "idx_work_items_claim_expires",
            "idx_work_items_root_id"
        )

    private val expectedTriggers =
        setOf(
            "work_items_cycle_check",
            "work_items_cycle_check_update",
            "work_items_fts_trigram_ai",
            "work_items_fts_trigram_ad",
            "work_items_fts_trigram_au",
            "work_items_fts_text_ai",
            "work_items_fts_text_ad",
            "work_items_fts_text_au"
        )

    /** rowid -> lowercase hex id, and hex id -> role, as dumped at V25. */
    private var rowidToId: Map<Long, String> = emptyMap()
    private var roleById: Map<String, String> = emptyMap()

    override fun seed(conn: Connection) {
        BaselineDataset.extraItem(conn, "seed26-null-description", "queue", "backlog")
        BaselineDataset.extraItem(conn, "seed26-described", "work", "in-progress")
        updateDescription(conn, BaselineDataset.id("seed26-described"), "Prose carrying $DESCRIPTION_TOKEN only in the description")
        val rows = rows(conn, "SELECT rowid AS r, lower(hex(id)) AS hid, role FROM work_items")
        rowidToId = rows.associate { (it["r"] as Number).toLong() to it["hid"] as String }
        roleById = rows.associate { it["hid"] as String to it["role"] as String }
    }

    override fun verify(conn: Connection) {
        // 1. columns, order and NOT NULL flags exactly as the frozen DDL
        val xinfo = rows(conn, "SELECT name, \"notnull\" AS nn FROM pragma_table_xinfo('work_items')")
        assertEquals(
            expectedColumns,
            xinfo.map { it["name"] as String to ((it["nn"] as Number).toInt() == 1) },
            "work_items columns / NOT NULL flags"
        )

        // 2. indexes and triggers
        assertEquals(
            expectedIndexes,
            rows(conn, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'work_items' AND sql IS NOT NULL")
                .map { it["name"] as String }
                .toSet(),
            "work_items indexes"
        )
        val triggers =
            rows(conn, "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'work_items'")
                .associate { it["name"] as String to it["sql"] as String }
        assertEquals(expectedTriggers, triggers.keys, "work_items triggers")
        for ((name, sql) in triggers.filterKeys { it.startsWith("work_items_fts_") }) {
            assertTrue("description" in sql, "$name must carry description: $sql")
        }
        for (name in listOf("work_items_fts_trigram_au", "work_items_fts_text_au")) {
            assertTrue("UPDATE OF title, summary, description ON" in triggers.getValue(name).replace(Regex("\\s+"), " "), name)
        }

        // 3. FTS definitions: description indexed, no prefix on the item tables, prefix kept on the notes tables
        for (fts in listOf("work_items_fts_trigram", "work_items_fts_text")) {
            val sql = ddl(conn, fts)
            assertTrue("description" in sql, "$fts must index description: $sql")
            assertTrue("prefix" !in sql, "$fts must have no prefix option: $sql")
        }
        for (fts in listOf("notes_fts_trigram", "notes_fts_text")) {
            assertTrue("prefix='2 3'" in ddl(conn, fts), "$fts keeps its prefix index")
        }

        // 4. rowids and roles preserved; the new columns NULL on every row right after the migration (checked on a
        // scratch copy migrated WITHOUT the data steps: the pin-backfill step fills the pin set on this database)
        val rows = rows(conn, "SELECT rowid AS r, lower(hex(id)) AS hid, role FROM work_items")
        assertEquals(rowidToId, rows.associate { (it["r"] as Number).toLong() to it["hid"] as String }, "rowid -> id map")
        assertEquals(roleById, rows.associate { it["hid"] as String to it["role"] as String }, "role per id")
        migrationOnlyChecks()

        // 5. item FTS covers every row (NULL description included) and finds the description-only token
        val total = count(conn, "SELECT count(*) FROM work_items")
        for (fts in listOf("work_items_fts_trigram", "work_items_fts_text")) {
            assertEquals(total, count(conn, "SELECT count(*) FROM ${fts}_docsize"), "$fts document count")
            assertEquals(1, count(conn, match(fts, DESCRIPTION_TOKEN)), "$fts description token")
        }

        // 6. the update trigger fires on a description change
        val describedId = BaselineDataset.id("seed26-described")
        updateDescription(conn, describedId, "Now $UPDATED_TOKEN instead")
        for (fts in listOf("work_items_fts_trigram", "work_items_fts_text")) {
            assertEquals(1, count(conn, match(fts, UPDATED_TOKEN)), "$fts new description")
            assertEquals(0, count(conn, match(fts, DESCRIPTION_TOKEN)), "$fts old description")
        }
        updateDescription(conn, describedId, "Prose carrying $DESCRIPTION_TOKEN only in the description")

        // 7. CHECK and FK probes
        checkProbes(conn)
    }

    /** The migration alone adds the lifecycle and pin columns NULL on every row (baseline seeded at V25, bare chain). */
    private fun migrationOnlyChecks() {
        val dir = Files.createTempDirectory("seed-v26-only-")
        try {
            val url = UpgradeHarness.copyAt(25, File(dir.toFile(), "only.db"))
            DriverManager.getConnection(url).use { conn ->
                BaselineDataset.seed(conn)
                BaselineDataset.extraItem(conn, "seed26-null-description", "queue", "backlog")
            }
            UpgradeHarness.migrate(url)
            DriverManager.getConnection(url).use { conn ->
                assertTrue(count(conn, "SELECT count(*) FROM work_items") > 0, "the scratch copy holds work items")
                for (c in newColumns) {
                    assertEquals(0, count(conn, "SELECT count(*) FROM work_items WHERE $c IS NOT NULL"), "$c must be NULL after V26")
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun match(
        fts: String,
        token: String
    ): String = "SELECT count(*) FROM $fts WHERE $fts MATCH '\"$token\"'"

    private fun updateDescription(
        conn: Connection,
        id: ByteArray,
        text: String
    ) {
        conn.prepareStatement("UPDATE work_items SET description = ? WHERE id = ?").use { ps ->
            ps.setString(1, text)
            ps.setBytes(2, id)
            ps.executeUpdate()
        }
    }

    private fun checkProbes(conn: Connection) {
        val probeId = BaselineDataset.id("seed26-probe")
        val ts = BaselineDataset.TS

        fun insertProbe(
            role: String?,
            lifecycle: String?,
            layer: String? = null,
            pinnedAt: String? = null,
            rootCfg: ByteArray? = null
        ) {
            conn
                .prepareStatement(
                    "INSERT INTO work_items (id, title, role, lifecycle, pinned_schema_layer, pinned_at, pinned_root_cfg, " +
                        "created_at, modified_at, role_changed_at) VALUES (?, 'probe', ?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    ps.setBytes(1, probeId)
                    ps.setString(2, role)
                    ps.setString(3, lifecycle)
                    ps.setString(4, layer)
                    ps.setString(5, pinnedAt)
                    ps.setBytes(6, rootCfg)
                    ps.setString(7, ts)
                    ps.setString(8, ts)
                    ps.setString(9, ts)
                    ps.executeUpdate()
                }
        }

        fun cleanup() {
            conn.prepareStatement("DELETE FROM work_items WHERE id = ?").use { ps ->
                ps.setBytes(1, probeId)
                ps.executeUpdate()
            }
        }

        fun rejected(
            what: String,
            role: String?,
            lifecycle: String?,
            layer: String? = null,
            pinnedAt: String? = null
        ) {
            assertFailsWith<SQLException>("$what must be rejected") { insertProbe(role, lifecycle, layer, pinnedAt) }
            cleanup()
        }

        insertProbe(role = null, lifecycle = "none")
        cleanup()
        insertProbe(role = "queue", lifecycle = "auto", layer = "server", pinnedAt = BaselineDataset.TS_PLUS_3)
        cleanup()
        rejected("(NULL, NULL)", null, null)
        rejected("(NULL, 'auto')", null, "auto")
        rejected("('queue', 'none')", "queue", "none")
        rejected("role 'bogus'", "bogus", null)
        rejected("pinned_schema_layer 'bogus'", "queue", "auto", layer = "bogus")
        rejected("22-char pinned_at", "queue", "auto", pinnedAt = BaselineDataset.TS_PLUS_3.dropLast(1))
        rejected("24-char pinned_at", "queue", "auto", pinnedAt = BaselineDataset.TS_PLUS_3 + "0")

        conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        try {
            assertFailsWith<SQLException>("a pin naming no config_versions row must be rejected") {
                insertProbe("queue", "auto", rootCfg = BaselineDataset.id("no-such-config-version"))
            }
        } finally {
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }
            cleanup()
        }
        assertEquals(0, count(conn, "SELECT count(*) FROM work_items WHERE title = 'probe'"), "probe rows removed")
    }

    private fun ddl(
        conn: Connection,
        table: String
    ): String = rows(conn, "SELECT sql FROM sqlite_master WHERE name = '$table'").single()["sql"] as String

    private fun count(
        conn: Connection,
        sql: String
    ): Int = (rows(conn, sql).single().values.single() as Number).toInt()

    private fun rows(
        conn: Connection,
        sql: String
    ): List<Map<String, Any?>> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val meta = rs.metaData
                buildList { while (rs.next()) add((1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getObject(it) }) }
            }
        }
}
