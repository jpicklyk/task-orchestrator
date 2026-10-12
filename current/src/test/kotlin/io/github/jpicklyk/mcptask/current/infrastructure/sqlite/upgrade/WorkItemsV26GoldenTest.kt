package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import io.github.jpicklyk.mcptask.current.application.port.Analyzer
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.ScopeFilter
import io.github.jpicklyk.mcptask.current.application.port.TextQuery
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 8666e73e / Q10) that run the checked-in golden 3.16 database through the
 * `work_items` migration (V26: nullable role with the lifecycle relation CHECK, six lifecycle / config-pin columns, item
 * FTS over title + summary + description without the dead prefix index).
 *
 * Oracles (frozen before any V26 code existed): migration-assessment section 1 (the DDL: column list and order, NOT NULL
 * set, CHECKs, FKs, the 8 indexes, the 8 triggers, the FTS declarations), task-scope AC1-AC9 and test-plan S1-S12.
 * Expected data comes from the golden itself: BaselineDataset literals (every item has the description "Description of
 * <name>", roles root/feature/task4 work, task1 blocked, task2/task3 terminal, task5/task6 queue) and the manifest row
 * counts. Where a value is "unchanged by the migration" it is read from the unmigrated copy of the golden, not hard-coded.
 *
 * The pre-V26 golden carries none of the new columns (the dataset drops them below V26), so every migrated row must hold
 * NULL in all six.
 *
 * Fixture invariants: raw probe rows set only the columns they exercise (title and the three NOT NULL timestamps are always
 * present); every probe row is removed afterwards and the row count and FTS document counts are re-checked. Foreign keys
 * are enforced only on the one connection that probes them (SQLite default is OFF on plain JDBC).
 *
 * Forbidden-construct declaration: none.
 */
class WorkItemsV26GoldenTest {
    @TempDir
    lateinit var dir: File

    private val laterSeeds get() = MigrationSeed.discover().filter { it.version > 17 }

    private val itemFts = listOf("work_items_fts_text", "work_items_fts_trigram")

    private val expectedRoles =
        mapOf(
            "root" to "work",
            "feature" to "work",
            "task1" to "blocked",
            "task2" to "terminal",
            "task3" to "terminal",
            "task4" to "work",
            "task5" to "queue",
            "task6" to "queue"
        )

    private val newColumns =
        listOf("lifecycle", "pinned_server_cfg", "pinned_root_cfg", "pinned_schema_key", "pinned_schema_layer", "pinned_at")

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun <T> Connection.query(
        sql: String,
        read: (ResultSet) -> T
    ): List<T> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) add(read(rs))
                }
            }
        }

    private fun Connection.int(sql: String): Int = query(sql) { it.getInt(1) }.single()

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun freshGolden(name: String): String = UpgradeHarness.urlFor(GoldenV17.copyTo(File(dir, "$name.sqlite")))

    private fun migratedGolden(name: String): String = freshGolden(name).also { UpgradeHarness.migrate(it) }

    private fun open(url: String): Connection = DriverManager.getConnection(url)

    private fun hits(
        conn: Connection,
        fts: String,
        match: String
    ): Int = conn.int("SELECT count(*) FROM $fts WHERE $fts MATCH '$match'")

    private fun docs(
        conn: Connection,
        fts: String
    ): Int = conn.int("SELECT count(*) FROM ${fts}_docsize")

    private fun integrity(
        conn: Connection,
        fts: String
    ) {
        conn.exec("INSERT INTO $fts($fts, rank) VALUES('integrity-check', 1)")
    }

    private fun normalizedSql(
        conn: Connection,
        type: String,
        name: String
    ): String =
        conn
            .query("SELECT sql FROM sqlite_master WHERE type = '$type' AND name = '$name'") { it.getString(1) }
            .single()
            .lowercase()
            .replace(Regex("\\s+"), " ")

    private fun insertRow(
        conn: Connection,
        id: ByteArray,
        vararg cols: Pair<String, Any?>
    ) {
        val values =
            linkedMapOf<String, Any?>(
                "id" to id,
                "title" to "probe row",
                "created_at" to BaselineDataset.TS,
                "modified_at" to BaselineDataset.TS_PLUS_1,
                "role_changed_at" to BaselineDataset.TS_PLUS_2
            )
        cols.forEach { values[it.first] = it.second }
        val sql = "INSERT INTO work_items (${values.keys.joinToString()}) VALUES (${values.keys.joinToString { "?" }})"
        conn.prepareStatement(sql).use { ps ->
            values.values.forEachIndexed { i, v ->
                when (v) {
                    null -> ps.setNull(i + 1, Types.NULL)
                    is ByteArray -> ps.setBytes(i + 1, v)
                    else -> ps.setString(i + 1, v.toString())
                }
            }
            ps.executeUpdate()
        }
    }

    private fun rowCount(
        conn: Connection,
        id: ByteArray
    ): Int = conn.int("SELECT count(*) FROM work_items WHERE id = x'${hex(id)}'")

    private fun deleteRow(
        conn: Connection,
        id: ByteArray
    ) {
        conn.exec("DELETE FROM work_items WHERE id = x'${hex(id)}'")
    }

    private fun assertRejected(
        conn: Connection,
        label: String,
        expectedMessage: String,
        vararg cols: Pair<String, Any?>
    ) {
        val id = BaselineDataset.id("probe:$label")
        val e = assertFailsWith<SQLException>("$label must be rejected") { insertRow(conn, id, *cols) }
        assertTrue(expectedMessage in e.message.orEmpty().lowercase(), "$label: rejected for the wrong reason: ${e.message}")
        assertEquals(0, rowCount(conn, id), "$label: a rejected insert must leave no row")
    }

    private fun assertAccepted(
        conn: Connection,
        label: String,
        vararg cols: Pair<String, Any?>
    ) {
        val id = BaselineDataset.id("probe:$label")
        insertRow(conn, id, *cols)
        assertEquals(1, rowCount(conn, id), "$label must be accepted")
        deleteRow(conn, id)
        assertEquals(0, rowCount(conn, id), "$label: probe row cleanup")
    }

    private fun assertGoldenIntact(conn: Connection) {
        assertEquals(8, conn.int("SELECT count(*) FROM work_items"), "probe rows must be removed")
        for (fts in itemFts) {
            assertEquals(8, docs(conn, fts), "$fts document count after probes")
            integrity(conn, fts)
        }
    }

    private fun setDescription(
        url: String,
        id: UUID,
        description: String?
    ) {
        open(url).use { c ->
            c.exec("PRAGMA busy_timeout = 5000")
            c.prepareStatement("UPDATE work_items SET description = ? WHERE id = ?").use { ps ->
                ps.setString(1, description)
                ps.setBytes(2, BaselineDataset.bytes(id))
                assertEquals(1, ps.executeUpdate(), "exactly one item gets the description")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 bare chain - the golden database upgrades through V26 with every row, FK and FTS index intact`() {
        val url = freshGolden("s1")
        val before = open(url).use { UpgradeHarness.dump(it) }
        val probe = UpgradeHarness.migrate(url)
        open(url).use { conn ->
            val applied = conn.query("SELECT version FROM flyway_schema_history WHERE success = 1") { it.getString(1) }
            assertTrue("26" in applied, "vacuity control: migration 26 must be part of the chain, got $applied")
            val failures = UpgradeHarness.checkUpgrade(conn, before, laterSeeds, probe)
            assertTrue(failures.isEmpty(), "golden upgrade failures:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun `S2 every golden item keeps its role and the lifecycle and five pin columns are NULL in all 8 rows`() {
        val url = freshGolden("s2")

        fun rolesByHexId(conn: Connection): Map<String, String?> =
            conn.query("SELECT id, role FROM work_items") { hex(it.getBytes(1)) to it.getString(2) }.toMap()

        val rolesBefore = open(url).use { rolesByHexId(it) }
        val expected = expectedRoles.mapKeys { hex(BaselineDataset.id(it.key)) }
        assertEquals(expected, rolesBefore, "oracle control: the unmigrated golden holds the BaselineDataset roles")

        UpgradeHarness.migrate(url)
        open(url).use { conn ->
            assertEquals(expected, rolesByHexId(conn), "every item keeps its pre-migration role")
            val nulls = newColumns.joinToString(" AND ") { "$it IS NULL" }
            assertEquals(8, conn.int("SELECT count(*) FROM work_items WHERE $nulls"), "all six new columns are NULL in all 8 rows")
            val present = conn.query("SELECT name FROM pragma_table_xinfo('work_items')") { it.getString(1) }
            assertTrue(present.containsAll(newColumns), "vacuity control: the six new columns exist, got $present")
        }
    }

    @Test
    fun `S3 MATCH description returns every item on both item FTS tables after V26 and none before`() {
        val oldUrl = freshGolden("s3-old")
        open(oldUrl).use { conn ->
            assertEquals(0, hits(conn, "work_items_fts_text", "description"), "description is not indexed before V26 (text)")
            assertEquals(0, hits(conn, "work_items_fts_trigram", "Description"), "description is not indexed before V26 (trigram)")
        }

        val url = migratedGolden("s3")
        open(url).use { conn ->
            assertEquals(8, hits(conn, "work_items_fts_text", "description"))
            assertEquals(8, hits(conn, "work_items_fts_trigram", "Description"))
            assertEquals(8, hits(conn, "work_items_fts_trigram", "description"), "trigram is case-insensitive")
            // the term lives in the description column and in no other column
            assertEquals(8, hits(conn, "work_items_fts_text", "description : description"))
            assertEquals(0, hits(conn, "work_items_fts_text", "title : description"))
            assertEquals(0, hits(conn, "work_items_fts_text", "summary : description"))
            // title content is still indexed under the title column and nowhere else
            for (fts in itemFts) {
                assertEquals(1, hits(conn, fts, "zebrafish"), "$fts: the seeded title token is still found")
                assertEquals(1, hits(conn, fts, "title : zebrafish"), "$fts: ... under the title column")
                assertEquals(0, hits(conn, fts, "description : zebrafish"), "$fts: ... and not under the description column")
            }
        }
    }

    @Test
    fun `S4 item FTS tables declare description and no prefix index while the notes FTS tables keep prefix 2 3`() {
        val url = migratedGolden("s4")
        open(url).use { conn ->
            for (fts in itemFts) {
                val sql = normalizedSql(conn, "table", fts)
                assertTrue(Regex("fts5\\( ?title, ?summary, ?description\\b").containsMatchIn(sql), "$fts column list: $sql")
                assertTrue("content='work_items'" in sql.replace(" ", ""), "$fts stays external-content over work_items: $sql")
                assertTrue("content_rowid='rowid'" in sql.replace(" ", ""), "$fts keeps content_rowid: $sql")
                assertFalse("prefix" in sql, "$fts must not declare a prefix index: $sql")
            }
            assertTrue("trigram" in normalizedSql(conn, "table", "work_items_fts_trigram"))
            assertTrue("porter unicode61 remove_diacritics 2" in normalizedSql(conn, "table", "work_items_fts_text"))
            for (fts in listOf("notes_fts_trigram", "notes_fts_text")) {
                val sql = normalizedSql(conn, "table", fts)
                assertTrue(Regex("prefix ?= ?['\"]2 3['\"]").containsMatchIn(sql), "$fts keeps its prefix index: $sql")
            }
        }
    }

    @Test
    fun `S5 every work_items rowid still maps to the same id and the rowid gap at 1 is preserved`() {
        val url = freshGolden("s5")
        val ridMap = { conn: Connection ->
            conn.query("SELECT rowid, id FROM work_items ORDER BY rowid") {
                it.getLong(1) to
                    hex(it.getBytes(2))
            }
        }
        val before = open(url).use { ridMap(it) }
        assertEquals(8, before.size)
        assertEquals(2L, before.first().first, "oracle control: the golden starts at rowid 2")

        UpgradeHarness.migrate(url)
        open(url).use { conn ->
            assertEquals(before, ridMap(conn), "rowid to id mapping must be identical")
            assertEquals(2, conn.int("SELECT min(rowid) FROM work_items"))
        }
    }

    @Test
    fun `S6 work_items keeps exactly its 8 indexes and 8 triggers and the FTS triggers carry description`() {
        val url = migratedGolden("s6")
        open(url).use { conn ->
            val indexes =
                conn.query(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'work_items' AND name NOT LIKE 'sqlite_autoindex%'"
                ) { it.getString(1) }
            assertEquals(
                setOf(
                    "idx_work_items_parent",
                    "idx_work_items_role",
                    "idx_work_items_depth",
                    "idx_work_items_priority",
                    "idx_work_items_role_changed",
                    "idx_work_items_claimed_by",
                    "idx_work_items_claim_expires",
                    "idx_work_items_root_id"
                ),
                indexes.toSet()
            )
            assertEquals(8, indexes.size, "no duplicate or extra index: $indexes")

            val triggers =
                conn.query(
                    "SELECT name FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'work_items'"
                ) { it.getString(1) }
            val ftsTriggers =
                listOf("work_items_fts_trigram", "work_items_fts_text").flatMap { t -> listOf("${t}_ai", "${t}_ad", "${t}_au") }
            assertEquals(
                setOf("work_items_cycle_check", "work_items_cycle_check_update") + ftsTriggers,
                triggers.toSet()
            )
            assertEquals(8, triggers.size, "no duplicate or extra trigger: $triggers")

            for (name in ftsTriggers) {
                val sql = normalizedSql(conn, "trigger", name)
                assertTrue("description" in sql, "$name must carry description: $sql")
                assertTrue(name.substringBeforeLast('_') in sql, "$name must write its own FTS table: $sql")
                if (name.endsWith("_au")) {
                    assertTrue("update of title, summary, description" in sql, "$name must be the restricted update form: $sql")
                }
            }

            assertEquals(
                0,
                conn.int("SELECT count(*) FROM sqlite_master WHERE sql LIKE '%work_items_new%'"),
                "the temporary table name must not survive in any schema object"
            )
        }
    }

    @Test
    fun `S13 work_items has exactly the frozen columns, NOT NULL set, types, role default and foreign keys`() {
        val oldUrl = freshGolden("s13-old")

        fun fksToWorkItems(
            conn: Connection,
            table: String
        ): List<String> =
            conn
                .query("SELECT \"from\", \"table\" FROM pragma_foreign_key_list('$table')") { it.getString(1) to it.getString(2) }
                .filter { it.second == "work_items" }
                .map { it.first }

        val before = open(oldUrl).use { c -> listOf("notes", "dependencies", "role_transitions").associateWith { fksToWorkItems(c, it) } }
        assertTrue(before.values.all { it.isNotEmpty() }, "oracle control: the unmigrated child tables reference work_items: $before")

        val url = migratedGolden("s13")
        open(url).use { conn ->
            class Col(
                val name: String,
                val type: String,
                val notNull: Boolean,
                val default: String?,
                val pk: Int
            )

            val cols =
                conn.query("SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_xinfo('work_items')") {
                    Col(it.getString(1), it.getString(2).uppercase(), it.getInt(3) == 1, it.getString(4), it.getInt(5))
                }
            assertEquals(
                listOf(
                    "id",
                    "parent_id",
                    "title",
                    "description",
                    "summary",
                    "role",
                    "status_label",
                    "previous_role",
                    "priority",
                    "complexity",
                    "requires_verification",
                    "depth",
                    "metadata",
                    "tags",
                    "type",
                    "properties",
                    "created_at",
                    "modified_at",
                    "role_changed_at",
                    "version",
                    "claimed_by",
                    "claimed_at",
                    "claim_expires_at",
                    "original_claimed_at",
                    "root_id",
                    "lifecycle",
                    "pinned_server_cfg",
                    "pinned_root_cfg",
                    "pinned_schema_key",
                    "pinned_schema_layer",
                    "pinned_at"
                ),
                cols.map { it.name },
                "column list and order"
            )
            val byName = cols.associateBy { it.name }
            assertEquals(
                setOf(
                    "title",
                    "summary",
                    "priority",
                    "requires_verification",
                    "depth",
                    "created_at",
                    "modified_at",
                    "role_changed_at",
                    "version"
                ),
                cols.filter { it.notNull }.map { it.name }.toSet(),
                "NOT NULL set (role is no longer in it)"
            )
            assertEquals(listOf("id"), cols.filter { it.pk > 0 }.map { it.name })
            assertEquals("'queue'", byName.getValue("role").default, "role keeps DEFAULT 'queue'")
            assertEquals("TEXT", byName.getValue("role").type)
            assertEquals("TIMESTAMP", byName.getValue("created_at").type)
            val newTypes =
                mapOf(
                    "lifecycle" to "TEXT",
                    "pinned_server_cfg" to "BLOB",
                    "pinned_root_cfg" to "BLOB",
                    "pinned_schema_key" to "TEXT",
                    "pinned_schema_layer" to "TEXT",
                    "pinned_at" to "TEXT"
                )
            assertEquals(newTypes, newColumns.associateWith { byName.getValue(it).type })

            val fks =
                conn
                    .query("SELECT \"from\", \"table\", \"to\", on_delete FROM pragma_foreign_key_list('work_items')") {
                        listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
                    }
            assertEquals(
                setOf("parent_id", "pinned_server_cfg", "pinned_root_cfg"),
                fks.map { it[0] }.toSet(),
                "foreign keys: the self reference survives, the two pins are added, root_id has none"
            )
            assertEquals(3, fks.size, "no duplicate foreign key: $fks")
            val byColumn = fks.associateBy { it[0] }
            assertEquals(listOf("work_items", "id"), byColumn.getValue("parent_id").subList(1, 3))
            for (pin in listOf("pinned_server_cfg", "pinned_root_cfg")) {
                assertEquals(
                    listOf("config_versions", "id", "NO ACTION"),
                    byColumn.getValue(pin).subList(1, 4),
                    "$pin is a plain REFERENCES"
                )
            }

            for ((table, columnsBefore) in before) {
                // V29 dropped role_transitions (its rows live in events, which has no foreign key).
                if (table == "role_transitions") continue
                assertEquals(columnsBefore, fksToWorkItems(conn, table), "$table keeps its foreign keys to work_items by name")
            }
        }
    }

    @Test
    fun `S7 real startup accepts the migrated golden and every item reads back with its seeded role`() {
        val url = freshGolden("s7")
        val before = open(url).use { UpgradeHarness.dump(it) }
        val config = AppConfig.fromEnv { name -> if (name == "DB_COMPACT_ON_UPGRADE") "true" else null }
        val expected =
            mapOf(
                "root" to Role.WORK,
                "feature" to Role.WORK,
                "task1" to Role.BLOCKED,
                "task2" to Role.TERMINAL,
                "task3" to Role.TERMINAL,
                "task4" to Role.WORK,
                "task5" to Role.QUEUE,
                "task6" to Role.QUEUE
            )
        assertEquals(BaselineDataset.ITEM_NAMES.toSet(), expected.keys, "oracle control: every baseline item is covered")

        val manager = DatabaseManager(appConfig = config)
        try {
            assertTrue(manager.initialize(url), "initialize must accept the golden database")
            assertTrue(manager.updateSchema(), "updateSchema must migrate and compact the golden database")
            open(url).use { conn ->
                assertEquals(1, conn.int("PRAGMA user_version"), "the first start after the upgrade runs the one-time compaction")
                val failures = UpgradeHarness.checkUpgrade(conn, before, laterSeeds, null)
                assertTrue(failures.isEmpty(), "failures after real startup:\n" + failures.joinToString("\n"))
                for (fts in itemFts) {
                    assertEquals(8, hits(conn, fts, "description"), "$fts still indexes description after the compaction rebuild")
                }
                val nulls = newColumns.joinToString(" AND ") { "$it IS NULL" }
                assertEquals(8, conn.int("SELECT count(*) FROM work_items WHERE $nulls"))
            }
            val provider = DefaultRepositoryProvider(manager)
            runBlocking {
                for ((name, role) in expected) {
                    val item = assertNotNull(provider.workItemRepository().getById(BaselineDataset.uuid(name)), "$name unreadable")
                    assertEquals(role, item.role, "$name keeps its role through the repository")
                }
            }
        } finally {
            manager.shutdown()
        }

        // a second boot over the already migrated and compacted file is accepted (replay of the startup path)
        val again = DatabaseManager(appConfig = config)
        try {
            assertTrue(again.initialize(url), "second initialize")
            assertTrue(again.updateSchema(), "second updateSchema")
            open(url).use { conn ->
                assertEquals(1, conn.int("PRAGMA user_version"), "the compaction does not run twice")
                assertEquals(8, hits(conn, "work_items_fts_text", "description"))
            }
        } finally {
            again.shutdown()
        }
    }

    @Test
    fun `S8 a description-only term is a search candidate under both analyzers with field description and a marked snippet`() {
        SqliteTestDatabase.open().use { db ->
            val provider = db.repositoryProvider()
            val index = provider.searchIndex()
            runBlocking {
                suspend fun create(
                    title: String,
                    summary: String
                ): WorkItem = assertNotNull(provider.workItemRepository().create(WorkItem(title = title, summary = summary, depth = 0)))

                val descOnly = create("plain heading", "")
                val titleOnly = create("marmalade heading", "other words")
                val both = create("marmalade both", "x")
                val neither = create("unrelated", "nothing")
                setDescription(db.jdbcUrl, descOnly.id, "this jar holds marmalade preserves")
                setDescription(db.jdbcUrl, titleOnly.id, "unrelated words")
                setDescription(db.jdbcUrl, both.id, "more marmalade sentence")
                setDescription(db.jdbcUrl, neither.id, "plain words")

                for (analyzer in Analyzer.entries) {
                    val byOwner =
                        index
                            .candidates(
                                Corpus.ITEM,
                                TextQuery(listOf("marmalade")),
                                analyzer,
                                ScopeFilter()
                            ).associateBy { it.ownerItemId }
                    assertEquals(setOf(descOnly.id, titleOnly.id, both.id), byOwner.keys, "$analyzer: candidates")

                    val d = byOwner.getValue(descOnly.id)
                    assertEquals("description", d.field, "$analyzer: the term is only in the description")
                    assertTrue(
                        "<mark>marmalade</mark>" in d.snippet.lowercase(),
                        "$analyzer: description snippet marks the term: ${d.snippet}"
                    )
                    assertTrue("preserves" in d.snippet, "$analyzer: the snippet is the description text: ${d.snippet}")
                    assertFalse("plain heading" in d.snippet, "$analyzer: the snippet must not be the title text: ${d.snippet}")

                    val t = byOwner.getValue(titleOnly.id)
                    assertEquals("title", t.field, "$analyzer: a title-only term still reports title")
                    assertTrue("<mark>marmalade</mark>" in t.snippet.lowercase(), "$analyzer: ${t.snippet}")
                    assertFalse("unrelated words" in t.snippet, "$analyzer: the snippet must come from the title: ${t.snippet}")

                    val b = byOwner.getValue(both.id)
                    assertEquals("title", b.field, "$analyzer: both columns match, title wins")
                    assertFalse("sentence" in b.snippet, "$analyzer: the snippet must come from the title: ${b.snippet}")
                }
            }
        }
    }

    @Test
    fun `S8 the migrated golden answers a description search with all 8 items and a title search with one`() {
        val url = migratedGolden("s8-golden")
        val manager = DatabaseManager(appConfig = AppConfig.fromEnv { null })
        try {
            assertTrue(manager.initialize(url), "initialize must accept the migrated golden")
            val index = DefaultRepositoryProvider(manager).searchIndex()
            runBlocking {
                val all = BaselineDataset.ITEM_NAMES.map { BaselineDataset.uuid(it) }.toSet()
                for (analyzer in Analyzer.entries) {
                    val found = index.candidates(Corpus.ITEM, TextQuery(listOf("description")), analyzer, ScopeFilter())
                    assertEquals(all, found.map { it.ownerItemId }.toSet(), "$analyzer: every item has the description term")
                    assertEquals(8, found.size, "$analyzer: one candidate per item")
                    assertTrue(found.all { it.field == "description" }, "$analyzer: ${found.map { it.field }}")
                    assertTrue(found.all { "<mark>description</mark>" in it.snippet.lowercase() }, "$analyzer: ${found.map { it.snippet }}")

                    val title =
                        index.candidates(Corpus.ITEM, TextQuery(listOf(BaselineDataset.ITEM_FTS_TOKEN)), analyzer, ScopeFilter()).single()
                    assertEquals(BaselineDataset.uuid("task1"), title.ownerItemId)
                    assertEquals("title", title.field, "$analyzer: the title token is reported as title")
                }
            }
        } finally {
            manager.shutdown()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Failure: the CHECKs and the pin foreign keys (raw probes)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 the role and lifecycle relation CHECK accepts only the NULL-none pairing and the role enum is case sensitive`() {
        val url = migratedGolden("s9")
        open(url).use { conn ->
            val check = "check constraint failed"
            val accepted: List<Pair<String?, String?>> =
                listOf(null to "none", "queue" to null, "queue" to "auto", "work" to "auto", "terminal" to null)
            accepted.forEachIndexed { i, (role, lifecycle) ->
                assertAccepted(conn, "ok-$i", "role" to role, "lifecycle" to lifecycle)
            }
            val rejected: List<Pair<String?, String?>> =
                listOf(
                    null to null,
                    null to "auto",
                    "queue" to "none",
                    "terminal" to "none",
                    "bogus" to "auto",
                    "Queue" to "auto",
                    null to "None",
                    null to ""
                )
            rejected.forEachIndexed { i, (role, lifecycle) ->
                assertRejected(conn, "bad-$i", check, "role" to role, "lifecycle" to lifecycle)
            }
            // the CHECKs that predate V26 are carried over
            assertRejected(conn, "previous-role", check, "previous_role" to "bogus")
            assertAccepted(conn, "previous-role-ok", "previous_role" to "work")
            assertRejected(conn, "priority", check, "priority" to "bogus")
            assertAccepted(conn, "priority-ok", "priority" to "high")
            // an omitted role still takes the column default
            val defaulted = BaselineDataset.id("probe:default-role")
            insertRow(conn, defaulted)
            assertEquals("queue", conn.query("SELECT role FROM work_items WHERE id = x'${hex(defaulted)}'") { it.getString(1) }.single())
            deleteRow(conn, defaulted)

            assertGoldenIntact(conn)
        }
    }

    @Test
    fun `S10 pin layer and timestamp CHECKs and the pin foreign keys hold`() {
        val url = migratedGolden("s10")
        open(url).use { conn ->
            val check = "check constraint failed"
            assertAccepted(conn, "layer-server", "pinned_schema_layer" to "server")
            assertAccepted(conn, "layer-root", "pinned_schema_layer" to "root")
            assertAccepted(conn, "layer-null", "pinned_schema_layer" to null)
            assertRejected(conn, "layer-bogus", check, "pinned_schema_layer" to "bogus")
            assertRejected(conn, "layer-case", check, "pinned_schema_layer" to "Root")

            val ok23 = "2026-03-01 10:15:33.123"
            assertEquals(23, ok23.length, "oracle control: the accepted timestamp is 23 characters")
            assertAccepted(conn, "at-23", "pinned_at" to ok23)
            assertAccepted(conn, "at-23-t", "pinned_at" to ok23.replace(' ', 'T'))
            assertAccepted(conn, "at-null", "pinned_at" to null)
            assertRejected(conn, "at-22", check, "pinned_at" to ok23.dropLast(1))
            assertRejected(conn, "at-24", check, "pinned_at" to ok23 + "4")
            assertRejected(conn, "at-empty", check, "pinned_at" to "")

            val configId = conn.query("SELECT id FROM config_versions LIMIT 1") { it.getBytes(1) }.single()
            val dangling = BaselineDataset.id("probe:no-such-config")
            conn.exec("PRAGMA foreign_keys = ON")
            try {
                val fk = "foreign key constraint failed"
                assertRejected(conn, "fk-root-dangling", fk, "pinned_root_cfg" to dangling)
                assertRejected(conn, "fk-server-dangling", fk, "pinned_server_cfg" to dangling)
                assertAccepted(conn, "fk-root-ok", "pinned_root_cfg" to configId)
                assertAccepted(conn, "fk-server-ok", "pinned_server_cfg" to configId)
                assertAccepted(conn, "fk-null", "pinned_root_cfg" to null, "pinned_server_cfg" to null)
            } finally {
                conn.exec("PRAGMA foreign_keys = OFF")
            }

            assertGoldenIntact(conn)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Edge: the sync triggers
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 insert update and delete keep both item FTS tables in step with title summary and description`() {
        val url = migratedGolden("s11")
        open(url).use { conn ->
            val task1 = BaselineDataset.id("task1")

            // _au on description: the new token is found and the old description leaves the index
            conn.exec("UPDATE work_items SET description = 'xylophone-v26' WHERE id = x'${hex(task1)}'")
            for (fts in itemFts) {
                assertEquals(1, hits(conn, fts, "xylophone"), "$fts: the new description is indexed")
                assertEquals(7, hits(conn, fts, "description"), "$fts: the replaced description is no longer indexed")
                assertEquals(8, docs(conn, fts), "$fts: document count")
            }

            // _au on title
            conn.exec("UPDATE work_items SET title = 'Quasar headline' WHERE id = x'${hex(task1)}'")
            for (fts in itemFts) {
                assertEquals(1, hits(conn, fts, "quasar"), "$fts: the new title is indexed")
                assertEquals(0, hits(conn, fts, "zebrafish"), "$fts: the old title leaves the index")
                assertEquals(1, hits(conn, fts, "xylophone"), "$fts: a title update keeps the description entry")
            }

            // an update of a column outside the indexed set changes nothing
            conn.exec("UPDATE work_items SET priority = 'high' WHERE id = x'${hex(task1)}'")
            for (fts in itemFts) {
                assertEquals(8, docs(conn, fts), "$fts: document count after a priority-only update")
                assertEquals(1, hits(conn, fts, "quasar"), "$fts")
                assertEquals(1, hits(conn, fts, "xylophone"), "$fts")
                integrity(conn, fts)
            }

            // _ai and _ad carry description
            val probe = BaselineDataset.id("probe:sync")
            insertRow(conn, probe, "description" to "pangolin-inserted")
            for (fts in itemFts) {
                assertEquals(1, hits(conn, fts, "pangolin"), "$fts: an inserted row is indexed by its description")
                assertEquals(9, docs(conn, fts))
            }
            deleteRow(conn, probe)
            for (fts in itemFts) {
                assertEquals(0, hits(conn, fts, "pangolin"), "$fts: a deleted row leaves the index")
                assertEquals(8, docs(conn, fts))
                integrity(conn, fts)
            }
        }
    }

    @Test
    fun `S12 a row with a NULL description is indexed as a document and survives description changes`() {
        val url = migratedGolden("s12")
        open(url).use { conn ->
            val probe = BaselineDataset.id("probe:null-description")
            insertRow(conn, probe, "description" to null)
            assertEquals(9, conn.int("SELECT count(*) FROM work_items"))
            for (fts in itemFts) {
                assertEquals(9, docs(conn, fts), "$fts: a NULL description still counts as one document")
                integrity(conn, fts)
            }

            conn.exec("UPDATE work_items SET description = 'tapir-token' WHERE id = x'${hex(probe)}'")
            for (fts in itemFts) {
                assertEquals(1, hits(conn, fts, "tapir"), "$fts: NULL to text")
                integrity(conn, fts)
            }

            conn.exec("UPDATE work_items SET description = NULL WHERE id = x'${hex(probe)}'")
            for (fts in itemFts) {
                assertEquals(0, hits(conn, fts, "tapir"), "$fts: text to NULL removes the old entry")
                assertEquals(9, docs(conn, fts))
                integrity(conn, fts)
            }

            deleteRow(conn, probe)
            assertGoldenIntact(conn)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Probes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `P1 a second rebuild of each item FTS table leaves counts and matches unchanged`() {
        val url = migratedGolden("p1")
        open(url).use { conn ->
            for (fts in itemFts) {
                conn.exec("INSERT INTO $fts($fts) VALUES('rebuild')")
                conn.exec("INSERT INTO $fts($fts) VALUES('rebuild')")
                assertEquals(8, docs(conn, fts), "$fts: document count after rebuilds")
                assertEquals(8, hits(conn, fts, "description"), "$fts: description matches after rebuilds")
                assertEquals(1, hits(conn, fts, "zebrafish"), "$fts: title matches after rebuilds")
                integrity(conn, fts)
            }
        }
    }

    @Test
    fun `P2 a description with FTS syntax characters is found literally and does not match a lookalike`() {
        SqliteTestDatabase.open().use { db ->
            val provider = db.repositoryProvider()
            val index = provider.searchIndex()
            runBlocking {
                val literal = assertNotNull(provider.workItemRepository().create(WorkItem(title = "literal holder", depth = 0)))
                val lookalike = assertNotNull(provider.workItemRepository().create(WorkItem(title = "lookalike holder", depth = 0)))
                setDescription(db.jdbcUrl, literal.id, "before a:b -c after")
                setDescription(db.jdbcUrl, lookalike.id, "before a b c after")

                val substring = index.candidates(Corpus.ITEM, TextQuery(listOf("a:b -c")), Analyzer.SUBSTRING, ScopeFilter())
                assertEquals(listOf(literal.id), substring.map { it.ownerItemId }, "the literal text matches only the row that holds it")
                assertEquals("description", substring.single().field)

                val stemmed = index.candidates(Corpus.ITEM, TextQuery(listOf("a:b -c")), Analyzer.STEMMED, ScopeFilter())
                assertTrue(
                    literal.id in stemmed.map { it.ownerItemId },
                    "the stemmed query is rendered literally, not as column filter or NOT"
                )
            }
        }
    }
}
