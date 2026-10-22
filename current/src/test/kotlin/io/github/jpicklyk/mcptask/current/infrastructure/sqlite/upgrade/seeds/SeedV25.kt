package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V25 replaces the one-row `project_config` table with the versioned store: every `project_config` row becomes version 1
 * of its root (same id, `source = 'migration'`, `created_at = updated_at`, no canonical form yet), the row is also the
 * root's head, each entry of its V11 `fingerprint_history` becomes a `config_known_fingerprints` row, and `project_config`
 * (with its index) is dropped.
 *
 * Oracle: migration-assessment section 1 (the frozen DDL: columns, order, types, CHECK set, five indexes) and section 3
 * and 4 (the copy rules, the verification list for this seed: per-root version / head / known-fingerprint rows with a
 * 2-entry, a NULL and a malformed history, column lists, index names, unchanged counts of the other tables, CHECK probes).
 *
 * Roots seeded here, besides the baseline root: "two" (2-entry history), "three" (NULL history), "four" (malformed history
 * text), "five" (duplicate, non-text and trailing entries), "six" (YAML that does not parse). The harness runs the
 * production data steps after the migration and before [verify], so the rows are checked in that state (canonical form
 * filled for every parseable YAML, left NULL for "six"); the migration's own output (canonical form still NULL) is
 * checked separately on a scratch database migrated WITHOUT the data steps ([migrationOnlyChecks]).
 */
object SeedV25 : MigrationSeed(25) {
    private const val AT = "2026-03-01 10:15:30.123"

    private class Cfg(
        val rootName: String,
        val configName: String,
        val yaml: String,
        val fingerprint: String,
        val updatedAt: String,
        val history: String?,
        val known: Set<String>,
        val canonicalAfterSteps: String?
    )

    private val baseline =
        Cfg(
            rootName = "root",
            configName = "project-config",
            yaml = "project:\n  name: synthetic-baseline\n",
            fingerprint = "baseline-fingerprint-0001",
            updatedAt = BaselineDataset.TS,
            history = "[\"baseline-fingerprint-0000\"]",
            known = setOf("baseline-fingerprint-0000"),
            canonicalAfterSteps = "{\"project\":{\"name\":\"synthetic-baseline\"}}"
        )

    private val added =
        listOf(
            Cfg(
                "v24-root-two",
                "v24-config-two",
                "project:\n  name: two\n",
                "v24-fp-two",
                BaselineDataset.TS_PLUS_1,
                "[\"v24-fp-two-old1\",\"v24-fp-two-old2\"]",
                setOf("v24-fp-two-old1", "v24-fp-two-old2"),
                "{\"project\":{\"name\":\"two\"}}"
            ),
            Cfg("v24-root-three", "v24-config-three", "a: 3\n", "v24-fp-three", BaselineDataset.TS_PLUS_2, null, emptySet(), "{\"a\":3}"),
            Cfg(
                "v24-root-four",
                "v24-config-four",
                "a: 4\n",
                "v24-fp-four",
                "2026-03-02 00:00:00.000",
                "not json [",
                emptySet(),
                "{\"a\":4}"
            ),
            Cfg(
                "v24-root-five",
                "v24-config-five",
                "a: 5\n",
                "v24-fp-five",
                "2026-03-03 01:02:03.004",
                "[\"dup-fp\",\"dup-fp\",7,\"tail-fp\"]",
                setOf("dup-fp", "tail-fp"),
                "{\"a\":5}"
            ),
            Cfg(
                "v24-root-six",
                "v24-config-six",
                "work_item_schemas: [\ninvalid yaml: :\n  - broken",
                "v24-fp-six",
                "2026-03-04 12:00:00.999",
                null,
                emptySet(),
                null
            )
        )

    private val all = listOf(baseline) + added

    /** Row counts of every user table at the pre-migration version, captured by [seed] and compared by [verify]. */
    @Volatile
    private var before: Map<String, Int>? = null

    private fun insertRoot(
        conn: Connection,
        name: String
    ) = BaselineDataset.insert(
        conn,
        "work_items",
        "id" to BaselineDataset.id(name),
        "parent_id" to null,
        "root_id" to BaselineDataset.id(name),
        "title" to "Seeded $name",
        "summary" to "",
        "role" to "queue",
        "status_label" to null,
        "priority" to "medium",
        "complexity" to 5,
        "requires_verification" to 0,
        "depth" to 0,
        // Typed like every baseline row: a NULL type would be filled by the production type-backfill step.
        "type" to "feature-task",
        "created_at" to BaselineDataset.TS,
        "modified_at" to BaselineDataset.TS_PLUS_1,
        "role_changed_at" to BaselineDataset.TS_PLUS_2,
        "version" to 1
    )

    private fun insertProjectConfig(
        conn: Connection,
        cfg: Cfg
    ) = BaselineDataset.insert(
        conn,
        "project_config",
        "id" to BaselineDataset.id(cfg.configName),
        "root_item_id" to BaselineDataset.id(cfg.rootName),
        "config_yaml" to cfg.yaml,
        "fingerprint" to cfg.fingerprint,
        "updated_at" to cfg.updatedAt,
        "fingerprint_history" to cfg.history
    )

    override fun seed(conn: Connection) {
        for (cfg in added) {
            insertRoot(conn, cfg.rootName)
            insertProjectConfig(conn, cfg)
        }
        before = UpgradeHarness.userTables(conn).associateWith { count(conn, it) }
    }

    /** Each `project_config` row is version 1 of its root in `config_versions`, under the same id. */
    override fun relocated(
        table: String,
        row: Map<String, Any?>
    ): MigrationSeed.Relocated? {
        if (table != "project_config") return null
        val id = row["id"]?.toString() ?: return null
        val moved =
            mapOf("root_item_id" to "root_id", "config_yaml" to "yaml", "fingerprint" to "fingerprint", "updated_at" to "created_at")
                .filterKeys { it in row }
                .entries
                .associate { (old, new) -> new to row[old] }
        return MigrationSeed.Relocated("config_versions", id, moved + ("id" to id))
    }

    // ---------------------------------------------------------------------------------------------

    override fun verify(conn: Connection) {
        assertEquals(0, scalar(conn, "SELECT count(*) FROM sqlite_master WHERE name = 'project_config'"), "project_config is dropped")
        assertEquals(
            0,
            scalar(conn, "SELECT count(*) FROM sqlite_master WHERE name = 'idx_project_config_root_item_id'"),
            "its index is dropped with it"
        )

        verifyShape(conn)
        verifyIndexes(conn)
        verifyRows(conn)
        verifyOtherTablesUnchanged(conn)
        verifyChecks(conn)
        migrationOnlyChecks()
    }

    private fun columns(
        conn: Connection,
        table: String
    ): List<List<Any>> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            listOf(rs.getString("name"), rs.getString("type"), rs.getInt("notnull"), rs.getInt("pk"))
                        )
                    }
                }
            }
        }

    private fun verifyShape(conn: Connection) {
        val versions = columns(conn, "config_versions")
        assertEquals(
            listOf(
                "id",
                "scope",
                "root_id",
                "version",
                "yaml",
                "fingerprint",
                "canonical_json",
                "model_version",
                "source",
                "principal_id",
                "req_id",
                "created_at",
                "warnings_json"
            ),
            versions.map { it[0] },
            "config_versions columns, in order"
        )
        assertEquals(
            listOf("BLOB", "TEXT", "BLOB", "INTEGER", "TEXT", "TEXT", "TEXT", "INTEGER", "TEXT", "TEXT", "TEXT", "TEXT", "TEXT"),
            versions.map { it[1] },
            "config_versions column types"
        )
        val notNull = versions.associate { (it[0] as String) to (it[2] as Int) }
        for (c in listOf(
            "scope",
            "version",
            "yaml",
            "fingerprint",
            "source",
            "created_at"
        )) {
            assertEquals(1, notNull[c], "config_versions.$c is NOT NULL")
        }
        for (c in listOf(
            "root_id",
            "canonical_json",
            "model_version",
            "principal_id",
            "req_id",
            "warnings_json"
        )) {
            assertEquals(0, notNull[c], "config_versions.$c is nullable")
        }
        assertEquals(listOf("id"), versions.filter { (it[3] as Int) > 0 }.map { it[0] }, "id is the only PK column of config_versions")

        val heads = columns(conn, "config_heads")
        assertEquals(listOf("scope", "root_id", "version_id", "updated_at"), heads.map { it[0] }, "config_heads columns, in order")
        assertEquals(listOf("TEXT", "BLOB", "BLOB", "TEXT"), heads.map { it[1] }, "config_heads column types")
        assertEquals(listOf(1, 0, 1, 1), heads.map { it[2] }, "config_heads NOT NULL flags")

        val known = columns(conn, "config_known_fingerprints")
        assertEquals(listOf("root_id", "fingerprint"), known.map { it[0] }, "config_known_fingerprints columns, in order")
        assertEquals(listOf("BLOB", "TEXT"), known.map { it[1] }, "config_known_fingerprints column types")
        assertEquals(listOf(1, 1), known.map { it[2] }, "both columns NOT NULL")
        assertEquals(listOf(1, 2), known.map { it[3] }, "composite primary key (root_id, fingerprint)")
    }

    private fun verifyIndexes(conn: Connection) {
        fun indexes(table: String): Map<String, Pair<Boolean, Boolean>> =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA index_list($table)").use { rs ->
                    buildMap { while (rs.next()) put(rs.getString("name"), (rs.getInt("unique") == 1) to (rs.getInt("partial") == 1)) }
                }
            }
        val versions = indexes("config_versions")
        assertEquals(true to true, versions["idx_config_versions_root_version"], "unique partial index on (root_id, version)")
        assertEquals(true to true, versions["idx_config_versions_server_version"], "unique partial index on (version) for the server scope")
        assertEquals(false to false, versions["idx_config_versions_root_fingerprint"], "plain index on (root_id, fingerprint)")
        val heads = indexes("config_heads")
        assertEquals(true to true, heads["idx_config_heads_root"], "unique partial index on root_id")
        assertEquals(true to true, heads["idx_config_heads_server"], "unique partial index on scope for the server head")
    }

    private fun verifyRows(conn: Connection) {
        for (cfg in all) {
            val root = BaselineDataset.id(cfg.rootName)
            val label = cfg.rootName
            conn
                .prepareStatement(
                    "SELECT id, scope, version, yaml, fingerprint, canonical_json, model_version, source, principal_id, req_id, " +
                        "created_at, warnings_json " +
                        "FROM config_versions WHERE root_id = ?"
                ).use { ps ->
                    ps.setBytes(1, root)
                    ps.executeQuery().use { rs ->
                        assertTrue(rs.next(), "$label: one version row")
                        assertTrue(
                            BaselineDataset.id(cfg.configName).contentEquals(rs.getBytes("id")),
                            "$label: version 1 keeps the project_config id"
                        )
                        assertEquals("root", rs.getString("scope"), label)
                        assertEquals(1, rs.getInt("version"), label)
                        assertEquals(cfg.yaml, rs.getString("yaml"), label)
                        assertEquals(cfg.fingerprint, rs.getString("fingerprint"), label)
                        assertEquals("migration", rs.getString("source"), label)
                        assertEquals(cfg.updatedAt, rs.getString("created_at"), "$label: created_at is the old updated_at")
                        assertNull(rs.getString("principal_id"), label)
                        assertNull(rs.getString("req_id"), label)
                        assertNull(rs.getString("warnings_json"), label)
                        assertEquals(
                            cfg.canonicalAfterSteps,
                            rs.getString("canonical_json"),
                            "$label: canonical form after the config-canonicalize step"
                        )
                        if (cfg.canonicalAfterSteps == null) {
                            assertNull(rs.getObject("model_version"), "$label: an unparseable row keeps a NULL model_version")
                        } else {
                            assertEquals(1, rs.getInt("model_version"), "$label: model_version set by the step")
                        }
                        assertTrue(!rs.next(), "$label: exactly one version row")
                    }
                }
            conn.prepareStatement("SELECT scope, version_id, updated_at FROM config_heads WHERE root_id = ?").use { ps ->
                ps.setBytes(1, root)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next(), "$label: a head row")
                    assertEquals("root", rs.getString("scope"), label)
                    assertTrue(
                        BaselineDataset.id(cfg.configName).contentEquals(rs.getBytes("version_id")),
                        "$label: the head points at version 1"
                    )
                    assertEquals(cfg.updatedAt, rs.getString("updated_at"), label)
                    assertTrue(!rs.next(), "$label: exactly one head row")
                }
            }
            val known =
                conn.prepareStatement("SELECT fingerprint FROM config_known_fingerprints WHERE root_id = ?").use { ps ->
                    ps.setBytes(1, root)
                    ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
                }
            assertEquals(cfg.known, known, "$label: known fingerprints come from the text entries of the old history")
        }
        assertEquals(all.size, count(conn, "config_versions"), "one version row per former project_config row")
        assertEquals(all.size, count(conn, "config_heads"), "one head row per former project_config row")
        assertEquals(all.sumOf { it.known.size }, count(conn, "config_known_fingerprints"), "no stray known-fingerprint rows")

        val applied = all.count { it.canonicalAfterSteps != null }
        assertEquals(
            applied,
            scalar(conn, "SELECT rows_affected FROM data_steps WHERE name = 'config-canonicalize'"),
            "the config-canonicalize step recorded the number of rows it filled (the unparseable row is not counted)"
        )
    }

    private fun verifyOtherTablesUnchanged(conn: Connection) {
        val pre = before ?: error("SeedV25.seed did not run before the migration")
        for ((table, rows) in SeedV29.afterRelocation(pre)) {
            if (table == "project_config" || table == "data_steps") continue
            assertEquals(rows, count(conn, table), "$table row count changed across V25")
        }
        assertEquals(pre.getValue("project_config"), count(conn, "config_versions"), "every project_config row was copied once")
    }

    // ---------------------------------------------------------------------------------------------
    // CHECK, partial-unique-index and NOT NULL probes (rows are removed again)
    // ---------------------------------------------------------------------------------------------

    private fun bytes(): ByteArray = BaselineDataset.bytes(UUID.randomUUID())

    @Suppress("LongParameterList")
    private fun insertVersion(
        conn: Connection,
        id: ByteArray = bytes(),
        scope: String? = "root",
        root: ByteArray? = bytes(),
        version: Int? = 1,
        yaml: String? = "y",
        source: String? = "push",
        createdAt: String? = AT
    ) {
        conn
            .prepareStatement(
                "INSERT INTO config_versions (id, scope, root_id, version, yaml, fingerprint, source, created_at) VALUES (?, ?, ?, ?, ?, 'f', ?, ?)"
            ).use { ps ->
                ps.setBytes(1, id)
                ps.setStringOrNull(2, scope)
                if (root == null) ps.setNull(3, Types.BLOB) else ps.setBytes(3, root)
                if (version == null) ps.setNull(4, Types.INTEGER) else ps.setInt(4, version)
                ps.setStringOrNull(5, yaml)
                ps.setStringOrNull(6, source)
                ps.setStringOrNull(7, createdAt)
                ps.executeUpdate()
            }
    }

    private fun java.sql.PreparedStatement.setStringOrNull(
        index: Int,
        value: String?
    ) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private fun insertHead(
        conn: Connection,
        scope: String? = "root",
        root: ByteArray? = bytes(),
        versionId: ByteArray? = bytes(),
        updatedAt: String? = AT
    ) {
        conn.prepareStatement("INSERT INTO config_heads (scope, root_id, version_id, updated_at) VALUES (?, ?, ?, ?)").use { ps ->
            ps.setStringOrNull(1, scope)
            if (root == null) ps.setNull(2, Types.BLOB) else ps.setBytes(2, root)
            if (versionId == null) ps.setNull(3, Types.BLOB) else ps.setBytes(3, versionId)
            ps.setStringOrNull(4, updatedAt)
            ps.executeUpdate()
        }
    }

    private fun insertKnown(
        conn: Connection,
        root: ByteArray,
        fingerprint: String?
    ) {
        conn.prepareStatement("INSERT INTO config_known_fingerprints (root_id, fingerprint) VALUES (?, ?)").use { ps ->
            ps.setBytes(1, root)
            ps.setStringOrNull(2, fingerprint)
            ps.executeUpdate()
        }
    }

    private fun verifyChecks(conn: Connection) {
        conn.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }
        val versionsBefore = count(conn, "config_versions")
        val headsBefore = count(conn, "config_heads")
        val knownBefore = count(conn, "config_known_fingerprints")
        val probeRoot = bytes()
        val probeRoot2 = bytes()

        // config_versions CHECKs
        assertFailsWith<SQLException>("an unknown scope must be rejected") { insertVersion(conn, scope = "bogus") }
        assertFailsWith<SQLException>(
            "scope 'server' with a root_id must be rejected"
        ) { insertVersion(conn, scope = "server", root = bytes()) }
        assertFailsWith<SQLException>(
            "scope 'root' without a root_id must be rejected"
        ) { insertVersion(conn, scope = "root", root = null) }
        assertFailsWith<SQLException>("version 0 must be rejected") { insertVersion(conn, version = 0) }
        assertFailsWith<SQLException>("a negative version must be rejected") { insertVersion(conn, version = -1) }
        assertFailsWith<SQLException>("an unknown source must be rejected") { insertVersion(conn, source = "bogus") }
        assertFailsWith<SQLException>(
            "a 22-character created_at must be rejected"
        ) { insertVersion(conn, createdAt = "2026-03-01 10:15:30.12") }
        assertFailsWith<SQLException>(
            "a 24-character created_at must be rejected"
        ) { insertVersion(conn, createdAt = "2026-03-01 10:15:30.1234") }
        assertFailsWith<SQLException>("a NULL created_at must be rejected") { insertVersion(conn, createdAt = null) }
        assertFailsWith<SQLException>("a NULL yaml must be rejected") { insertVersion(conn, yaml = null) }
        assertFailsWith<SQLException>("a NULL version must be rejected") { insertVersion(conn, version = null) }
        assertEquals(versionsBefore, count(conn, "config_versions"), "no rejected insert left a row")

        // accepted shapes at every CHECK boundary, one per allowed source and scope
        for ((n, source) in listOf("push", "file-import", "migration", "rollback", "reset").withIndex()) {
            insertVersion(conn, root = probeRoot, version = n + 1, source = source)
        }
        assertFailsWith<SQLException>("a duplicate (root, version) must be rejected by the partial unique index") {
            insertVersion(conn, root = probeRoot, version = 3)
        }
        insertVersion(conn, root = probeRoot2, version = 1)
        insertVersion(conn, scope = "server", root = null, version = 1)
        assertFailsWith<SQLException>("a second server version 1 must be rejected by the partial unique index") {
            insertVersion(conn, scope = "server", root = null, version = 1)
        }
        insertVersion(conn, scope = "server", root = null, version = 2)

        // config_heads
        assertFailsWith<SQLException>("a head with scope server and a root_id must be rejected") {
            insertHead(conn, scope = "server", root = bytes())
        }
        assertFailsWith<SQLException>(
            "a head with scope root and no root_id must be rejected"
        ) { insertHead(conn, scope = "root", root = null) }
        assertFailsWith<SQLException>("an unknown head scope must be rejected") { insertHead(conn, scope = "bogus") }
        assertFailsWith<SQLException>("a NULL version_id must be rejected") { insertHead(conn, versionId = null) }
        assertFailsWith<SQLException>(
            "a 22-character head updated_at must be rejected"
        ) { insertHead(conn, updatedAt = "2026-03-01 10:15:30.12") }
        assertFailsWith<SQLException>("a 24-character head updated_at must be rejected") {
            insertHead(conn, updatedAt = "2026-03-01 10:15:30.1234")
        }
        insertHead(conn, root = probeRoot)
        assertFailsWith<SQLException>("a second head for the same root must be rejected by the partial unique index") {
            insertHead(conn, root = probeRoot)
        }
        insertHead(conn, root = probeRoot2)
        insertHead(conn, scope = "server", root = null)
        assertFailsWith<SQLException>("a second server head must be rejected by the partial unique index") {
            insertHead(conn, scope = "server", root = null)
        }

        // config_known_fingerprints
        insertKnown(conn, probeRoot, "k-1")
        insertKnown(conn, probeRoot2, "k-1")
        assertFailsWith<SQLException>(
            "a duplicate (root, fingerprint) must be rejected by the primary key"
        ) { insertKnown(conn, probeRoot, "k-1") }
        assertFailsWith<SQLException>("a NULL fingerprint must be rejected") { insertKnown(conn, probeRoot, null) }

        // clean up every probe row so later steps see the migrated data only
        for (root in listOf(probeRoot, probeRoot2)) {
            for (table in listOf("config_versions", "config_heads", "config_known_fingerprints")) {
                conn.prepareStatement("DELETE FROM $table WHERE root_id = ?").use { ps ->
                    ps.setBytes(1, root)
                    ps.executeUpdate()
                }
            }
        }
        conn.createStatement().use {
            it.executeUpdate("DELETE FROM config_versions WHERE scope = 'server'")
            it.executeUpdate("DELETE FROM config_heads WHERE scope = 'server'")
        }
        assertEquals(versionsBefore, count(conn, "config_versions"), "the probe rows are removed again")
        assertEquals(headsBefore, count(conn, "config_heads"))
        assertEquals(knownBefore, count(conn, "config_known_fingerprints"))
    }

    // ---------------------------------------------------------------------------------------------
    // The migration's own output, before any data step
    // ---------------------------------------------------------------------------------------------

    private fun migrationOnlyChecks() {
        val dir = Files.createTempDirectory("seed-v25-only-")
        try {
            val url = UpgradeHarness.copyAt(24, File(dir.toFile(), "only.db"))
            java.sql.DriverManager.getConnection(url).use { conn ->
                insertRoot(conn, "mo-root")
                insertProjectConfig(conn, Cfg("mo-root", "mo-config", "a: 1\n", "mo-fp", AT, "[\"mo-old\"]", setOf("mo-old"), null))
            }
            UpgradeHarness.migrate(url)
            java.sql.DriverManager.getConnection(url).use { conn ->
                conn
                    .prepareStatement(
                        "SELECT canonical_json, model_version, source, version FROM config_versions WHERE root_id = ?"
                    ).use { ps ->
                        ps.setBytes(1, BaselineDataset.id("mo-root"))
                        ps.executeQuery().use { rs ->
                            assertTrue(rs.next(), "the migration copied the project_config row")
                            assertNull(
                                rs.getString("canonical_json"),
                                "the migration itself leaves canonical_json NULL (the data step fills it)"
                            )
                            assertNull(rs.getObject("model_version"), "the migration itself leaves model_version NULL")
                            assertEquals("migration", rs.getString("source"))
                            assertEquals(1, rs.getInt("version"))
                        }
                    }
                assertEquals(1, scalar(conn, "SELECT count(*) FROM config_known_fingerprints WHERE fingerprint = 'mo-old'"))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun scalar(
        conn: Connection,
        sql: String
    ): Int =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use {
                it.next()
                it.getInt(1)
            }
        }

    private fun count(
        conn: Connection,
        table: String
    ): Int = scalar(conn, "SELECT count(*) FROM $table")
}
