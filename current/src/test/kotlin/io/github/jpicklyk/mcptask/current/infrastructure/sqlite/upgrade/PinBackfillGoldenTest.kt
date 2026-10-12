package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStep
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepKind
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepPhase
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepReport
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerComposition
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------------------------------------------------
// Shared golden-pin fixtures (also used by GoldenV17UpgradeTest and LifecycleColumnInvariantTest)
// ---------------------------------------------------------------------------------------------------------------------

/** The server file the pin tests mount: `feature-task` is the type of every golden item, `task-b` a second type. */
internal const val PIN_SERVER_YAML =
    "work_item_schemas:\n" +
        "  feature-task:\n" +
        "    lifecycle: manual\n" +
        "    notes: []\n" +
        "  task-b:\n" +
        "    lifecycle: auto\n" +
        "    notes: []\n"

/** The golden root's per-root document: a `default` schema but NO `feature-task`, so LEGACY gives the root default. */
internal const val PIN_ROOT_DEFAULT_YAML =
    "work_item_schemas:\n" +
        "  task-a:\n" +
        "    lifecycle: manual\n" +
        "    notes: []\n" +
        "  default:\n" +
        "    lifecycle: permanent\n" +
        "    notes: []\n"

/** Same shape, but the per-root document declares `feature-task` itself. */
internal const val PIN_ROOT_EXACT_YAML =
    "work_item_schemas:\n" +
        "  feature-task:\n" +
        "    lifecycle: permanent\n" +
        "    notes: []\n" +
        "  default:\n" +
        "    lifecycle: manual\n" +
        "    notes: []\n"

internal fun uuidOf(bytes: ByteArray?): UUID? =
    bytes?.let {
        val buf = ByteBuffer.wrap(it)
        UUID(buf.long, buf.long)
    }

internal fun <T> sqlRows(
    url: String,
    sql: String,
    bind: (PreparedStatement) -> Unit = {},
    read: (ResultSet) -> T
): List<T> =
    DriverManager.getConnection(url).use { conn ->
        conn.prepareStatement(sql).use { ps ->
            bind(ps)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }
    }

/** The head version id of the server scope (null when there is none) read straight from `config_heads`. */
internal fun serverHeadId(url: String): UUID? =
    sqlRows(url, "SELECT version_id FROM config_heads WHERE scope = 'server'") { uuidOf(it.getBytes(1)) }.singleOrNull()

/** The head version id of [rootName]'s per-root config (null when the root has no head). */
internal fun rootHeadId(
    url: String,
    rootName: String
): UUID? =
    sqlRows(
        url,
        "SELECT version_id FROM config_heads WHERE scope <> 'server' AND root_id = ?",
        { it.setBytes(1, BaselineDataset.id(rootName)) }
    ) { uuidOf(it.getBytes(1)) }.singleOrNull()

/** The six V26 pin columns plus `version` / `modified_at` of one work item row. */
internal data class PinRow(
    val serverCfg: UUID?,
    val rootCfg: UUID?,
    val key: String?,
    val layer: String?,
    val lifecycle: String?,
    val pinnedAt: String?,
    val version: Long,
    val modifiedAt: String?
)

internal fun pinRow(
    url: String,
    name: String
): PinRow =
    sqlRows(
        url,
        "SELECT pinned_server_cfg, pinned_root_cfg, pinned_schema_key, pinned_schema_layer, lifecycle, pinned_at, version, " +
            "modified_at FROM work_items WHERE id = ?",
        { it.setBytes(1, BaselineDataset.id(name)) }
    ) {
        PinRow(
            uuidOf(it.getBytes(1)),
            uuidOf(it.getBytes(2)),
            it.getString(3),
            it.getString(4),
            it.getString(5),
            it.getString(6),
            it.getLong(7),
            it.getString(8)
        )
    }.single()

internal fun scalarInt(
    url: String,
    sql: String
): Int = sqlRows(url, sql) { it.getInt(1) }.single()

/**
 * A copy of the golden V17 database whose root `project_config` row carries [perRootYaml] (the row is deleted when null),
 * after [seed] ran against it, migrated to the latest schema. No data step has run yet.
 */
internal fun migratedGolden(
    dir: File,
    name: String,
    perRootYaml: String?,
    seed: (Connection) -> Unit = {}
): String {
    val golden = GoldenV17.copyTo(File(dir, "$name.sqlite"))
    val url = UpgradeHarness.urlFor(golden)
    DriverManager.getConnection(url).use { conn ->
        if (perRootYaml != null) {
            conn.prepareStatement("UPDATE project_config SET config_yaml = ? WHERE root_item_id = ?").use { ps ->
                ps.setString(1, perRootYaml)
                ps.setBytes(2, BaselineDataset.id("root"))
                assertEquals(1, ps.executeUpdate(), "the golden carries exactly one project_config row for its root")
            }
        } else {
            conn.prepareStatement("DELETE FROM project_config WHERE root_item_id = ?").use { ps ->
                ps.setBytes(1, BaselineDataset.id("root"))
                ps.executeUpdate()
            }
        }
        seed(conn)
    }
    UpgradeHarness.migrate(url)
    return url
}

/** Boots the production data steps against [url] with [serverYaml] mounted as the server file (none when null). */
internal fun bootProductionSteps(
    url: String,
    dir: File,
    runName: String,
    serverYaml: String?
): Triple<File, DataStepReport, List<DataStep>> {
    val runDir = File(dir, "run-$runName").also { it.mkdirs() }
    if (serverYaml != null) {
        val config = File(runDir, "empty-agent-config/.taskorchestrator/config.yaml")
        config.parentFile.mkdirs()
        config.writeText(serverYaml)
    }
    val (report, plan) = UpgradeHarness.runProductionDataStepsWithPlan(url, runDir)
    return Triple(runDir, report, plan)
}

/** What the production resolver says about one item of a stepped database: its stored pin and its `forItem` base schema. */
internal data class ResolvedBase(
    val pinKey: String?,
    val pinLayer: String?,
    val pinLifecycle: String?,
    val pinServer: UUID?,
    val pinRoot: UUID?,
    val baseKey: String?,
    val baseSource: ConfigSource?,
    val baseLifecycle: LifecycleMode?
)

/**
 * Opens the production composition over [url] (the config dir is the one [bootProductionSteps] mounted under [runDir]) and
 * returns, for every `work_items` row, its pin and the base schema `EffectiveConfigResolver.forItem` resolves for it.
 */
internal fun resolveGoldenBases(
    url: String,
    runDir: File
): Map<UUID, ResolvedBase> {
    val configDir = File(runDir, "empty-agent-config").also { it.mkdirs() }
    val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") configDir.absolutePath else null }
    val databaseManager = DatabaseManager(appConfig = appConfig)
    check(databaseManager.initialize(url)) { "could not open $url" }
    try {
        val composition = ServerComposition(appConfig, databaseManager, ShutdownCoordinator()).build()
        val ids = sqlRows(url, "SELECT id FROM work_items") { uuidOf(it.getBytes(1))!! }
        return runBlocking {
            val unit =
                composition.unitOfWork.write("resolveGoldenBases") {
                    val repo = composition.toolContext.repositoryProvider.workItemRepository()
                    val out = linkedMapOf<UUID, ResolvedBase>()
                    for (id in ids) {
                        val item = checkNotNull(repo.getById(id)) { "row $id unreadable through the repository" }
                        val pin = item.pin
                        val entry =
                            composition.toolContext.configResolver
                                .forItem(item)
                                .baseSchemaEntry(item.type)
                        out[id] =
                            ResolvedBase(
                                pin?.schemaKey,
                                pin?.schemaLayer?.wire,
                                pin?.lifecycle?.name?.lowercase(),
                                pin?.serverVersionId,
                                pin?.rootVersionId,
                                entry?.key,
                                entry?.source,
                                entry?.schema?.lifecycleMode
                            )
                    }
                    Outcome.Ok(out)
                }
            when (unit) {
                is Outcome.Ok -> unit.value
                is Outcome.Err -> error("resolve unit failed: ${unit.error.message}")
            }
        }
    } finally {
        databaseManager.shutdown()
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// The tests
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Independent tests for the `pin-backfill` data step (item 934b25e4): scenarios S2 and S11 of the frozen test-plan plus
 * probes. The input is a COPY of the checked-in golden V17 database that the tests alter with SQL before migrating (the
 * `TypeBackfillGoldenTest` recipe); the step runs through the production composition, so its registration is part of
 * what is proved.
 *
 * Oracles (frozen task-scope AC2 and decisions, migration-assessment s3, plan s3.7 PIN phase, U8b/U8c; none read from the
 * implementation): the step is named `pin-backfill`, phase PIN, kind ONCE, no `after`; for every `work_items` row with
 * `pinned_at IS NULL` it writes `pinned_server_cfg` = the server head id (the file-import version when a server file is
 * mounted, NULL otherwise), `pinned_root_cfg` = the row's root head id (NULL for a root without a head or a row without a
 * root), and `(pinned_schema_key, pinned_schema_layer, lifecycle)` from the LEGACY resolution of the row's type
 * (`schema_resolution`-mode aware: under the default mode a per-root `default` shadows a server schema of the row's exact
 * type; a NULL / blank / `untyped` type contributes no tag, so only the `default` steps can match; nothing matching gives
 * NULL key, layer and lifecycle); `pinned_at` is a 23-character UTC text; it bumps no `version` or `modified_at`, records
 * no event, is idempotent through the `pinned_at IS NULL` predicate, and its ledger `rows_affected` is the number of rows
 * it updated. The expected lifecycle values are the literals written in the YAML fixtures above.
 */
class PinBackfillGoldenTest {
    @TempDir
    lateinit var dir: File

    private val stepName = "pin-backfill"

    private fun insertItem(
        conn: Connection,
        name: String,
        type: String?,
        tags: String?,
        root: String?,
        isRoot: Boolean = false
    ) {
        BaselineDataset.insert(
            conn,
            "work_items",
            "id" to BaselineDataset.id(name),
            "parent_id" to if (isRoot || root == null) null else BaselineDataset.id(root),
            "root_id" to if (isRoot) BaselineDataset.id(name) else root?.let { BaselineDataset.id(it) },
            "title" to "Probe $name",
            "summary" to "",
            "role" to "queue",
            "priority" to "medium",
            "complexity" to 5,
            "requires_verification" to 0,
            "depth" to if (isRoot || root == null) 0 else 1,
            "tags" to tags,
            "type" to type,
            "created_at" to BaselineDataset.TS,
            "modified_at" to BaselineDataset.TS_PLUS_1,
            "role_changed_at" to BaselineDataset.TS_PLUS_2,
            "version" to 1
        )
    }

    private fun insertRootConfig(
        conn: Connection,
        root: String,
        yaml: String,
        fingerprint: String
    ) {
        BaselineDataset.insert(
            conn,
            "project_config",
            "id" to BaselineDataset.id("project-config-$root"),
            "root_item_id" to BaselineDataset.id(root),
            "config_yaml" to yaml,
            "fingerprint" to fingerprint,
            "updated_at" to BaselineDataset.TS,
            "fingerprint_history" to "[]"
        )
    }

    private val layeredRootYaml =
        "schema_resolution: layered\nwork_item_schemas:\n  default:\n    lifecycle: manual\n    notes: []\n"

    /** Roots root2 (no config) and root3 (layered per-root config) plus one probe row per resolution case. */
    private fun seedProbes(conn: Connection) {
        insertItem(conn, "root2", "feature-task", null, null, isRoot = true)
        insertItem(conn, "root3", "feature-task", null, null, isRoot = true)
        insertRootConfig(conn, "root3", layeredRootYaml, "pin-fingerprint-root3")
        insertItem(conn, "bf-a", "task-a", "q", "root")
        insertItem(conn, "bf-b", "task-b", "q", "root")
        insertItem(conn, "bf-c", "task-b", "q", "root2")
        insertItem(conn, "bf-d", null, "zzz", "root2")
        insertItem(conn, "bf-e", "untyped", "q", "root")
        insertItem(conn, "bf-f", "task-b", "q", null)
        insertItem(conn, "bf-g", "task-b", "q", "root3")
    }

    private val probeNames = listOf("root2", "root3", "bf-a", "bf-b", "bf-c", "bf-d", "bf-e", "bf-f", "bf-g")
    private val goldenNames = BaselineDataset.ITEM_NAMES

    private fun totalRows(url: String) = scalarInt(url, "SELECT COUNT(*) FROM work_items")

    private fun unpinnedRows(url: String) = scalarInt(url, "SELECT COUNT(*) FROM work_items WHERE pinned_at IS NULL")

    private fun ledgerRows(url: String): Int? =
        sqlRows(url, "SELECT rows_affected FROM data_steps WHERE name = '$stepName'") { it.getInt(1) }.singleOrNull()

    private fun ledgerCount(url: String) = scalarInt(url, "SELECT COUNT(*) FROM data_steps WHERE name = '$stepName'")

    private val timestampText = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")

    private fun assertPin(
        url: String,
        label: String,
        name: String,
        server: UUID?,
        root: UUID?,
        key: String?,
        layer: String?,
        lifecycle: String?
    ) {
        val row = pinRow(url, name)
        assertEquals(server, row.serverCfg, "$label: pinned_server_cfg of $name")
        assertEquals(root, row.rootCfg, "$label: pinned_root_cfg of $name")
        assertEquals(key, row.key, "$label: pinned_schema_key of $name")
        assertEquals(layer, row.layer, "$label: pinned_schema_layer of $name")
        assertEquals(lifecycle, row.lifecycle, "$label: lifecycle of $name")
        val at = assertNotNull(row.pinnedAt, "$label: pinned_at of $name is set")
        assertTrue(timestampText.matches(at), "$label: pinned_at '$at' is 23-character UTC text")
    }

    private fun clearPin(
        url: String,
        name: String
    ) {
        DriverManager.getConnection(url).use { conn ->
            conn
                .prepareStatement(
                    "UPDATE work_items SET pinned_at = NULL, pinned_server_cfg = NULL, pinned_root_cfg = NULL, " +
                        "pinned_schema_key = NULL, pinned_schema_layer = NULL, lifecycle = NULL WHERE id = ?"
                ).use {
                    it.setBytes(1, BaselineDataset.id(name))
                    assertEquals(1, it.executeUpdate())
                }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The step's frozen identity
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the step carries the frozen name, phase and kind and declares no ordering`() {
        val step = PinBackfillStep(File(dir, "no-config.yaml").toPath())

        assertEquals("pin-backfill", step.name)
        assertEquals(DataStepPhase.PIN, step.phase)
        assertEquals(DataStepKind.ONCE, step.kind)
        assertEquals(emptySet(), step.after, "the phase order alone places pin-backfill after the item backfills")
    }

    // ---------------------------------------------------------------------------------------------
    // S2 - golden copy with a server file, per-root documents and probe rows
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 every row is pinned to the heads and to its LEGACY base schema, with nothing else touched`() {
        val url = migratedGolden(dir, "s2", PIN_ROOT_DEFAULT_YAML, ::seedProbes)
        val total = totalRows(url)
        assertEquals(8 + probeNames.size, total, "fixture: the eight golden rows plus the probe rows")
        assertEquals(total, unpinnedRows(url), "fixture: nothing is pinned before the run")
        val everyName = goldenNames + probeNames
        val stampsBefore = everyName.associateWith { pinRow(url, it).let { r -> r.version to r.modifiedAt } }
        val eventsBefore = scalarInt(url, "SELECT COUNT(*) FROM events")

        val (_, report, plan) = bootProductionSteps(url, dir, "s2", PIN_SERVER_YAML)

        val names = plan.map { it.name }
        assertTrue(stepName in names, "the step is registered in the production plan: $names")
        assertTrue(stepName in report.applied, "first run applies it: $report")
        assertTrue(names.indexOf(stepName) > names.indexOf("type-backfill"), "pin-backfill runs after type-backfill: $names")
        assertTrue(names.indexOf(stepName) > names.indexOf("traits-from-properties"), "after traits-from-properties: $names")
        assertTrue(names.indexOf(stepName) > names.indexOf("server-config-import"), "after the server import: $names")

        val server = assertNotNull(serverHeadId(url), "the mounted server file is imported as the server head")
        val rootHead = assertNotNull(rootHeadId(url, "root"))
        val root3Head = assertNotNull(rootHeadId(url, "root3"))
        assertNull(rootHeadId(url, "root2"), "fixture: root2 has no per-root config")

        // The eight golden rows: type feature-task under a root whose document has a default but no feature-task.
        // LEGACY: the per-root default shadows the server feature-task (the Q9 rule would have chosen the server schema).
        for (name in goldenNames) assertPin(url, "S2 golden", name, server, rootHead, "default", "root", "permanent")
        assertPin(url, "S2 per-root tag", "bf-a", server, rootHead, "task-a", "root", "manual")
        assertPin(url, "S2 per-root default shadows server task-b", "bf-b", server, rootHead, "default", "root", "permanent")
        assertPin(url, "S2 no per-root layer", "bf-c", server, null, "task-b", "server", "auto")
        assertPin(url, "S2 unmatched type", "bf-d", server, null, null, null, null)
        assertPin(url, "S2 untyped reaches the default steps only", "bf-e", server, rootHead, "default", "root", "permanent")
        assertPin(url, "S2 row without a root", "bf-f", server, null, "task-b", "server", "auto")
        assertPin(url, "S2 layered per-root mode", "bf-g", server, root3Head, "task-b", "server", "auto")
        assertPin(url, "S2 second root", "root2", server, null, "feature-task", "server", "manual")
        assertPin(url, "S2 layered root itself", "root3", server, root3Head, "feature-task", "server", "manual")

        assertEquals(total, ledgerRows(url), "data_steps.rows_affected is the number of rows updated")
        assertEquals(1, ledgerCount(url), "exactly one ledger row for the step")
        assertEquals(0, unpinnedRows(url))
        val stampsAfter = everyName.associateWith { pinRow(url, it).let { r -> r.version to r.modifiedAt } }
        assertEquals(stampsBefore, stampsAfter, "no row's version or modified_at changes (a backfill is not an edit)")
        assertEquals(eventsBefore, scalarInt(url, "SELECT COUNT(*) FROM events"), "a backfill records no event")
        assertEquals(0, scalarInt(url, "SELECT COUNT(*) FROM events WHERE type = 'item.pinned'"))
    }

    @Test
    fun `S2 a per-root document that declares the exact type wins over the server schema and the per-root default`() {
        val url = migratedGolden(dir, "s2-exact", PIN_ROOT_EXACT_YAML)

        bootProductionSteps(url, dir, "s2-exact", PIN_SERVER_YAML)

        val server = serverHeadId(url)
        val rootHead = rootHeadId(url, "root")
        for (name in goldenNames) assertPin(url, "S2 exact", name, server, rootHead, "feature-task", "root", "permanent")
    }

    @Test
    fun `S2 with no server file mounted the server pin is NULL and the per-root layer alone decides`() {
        val url = migratedGolden(dir, "s2-noserver", PIN_ROOT_DEFAULT_YAML, ::seedProbes)

        bootProductionSteps(url, dir, "s2-noserver", null)

        assertNull(serverHeadId(url), "fixture: no server file, no server head")
        val rootHead = assertNotNull(rootHeadId(url, "root"))
        for (name in goldenNames) assertPin(url, "S2 no server", name, null, rootHead, "default", "root", "permanent")
        assertPin(url, "S2 no server", "bf-a", null, rootHead, "task-a", "root", "manual")
        assertPin(url, "S2 no layer at all", "bf-c", null, null, null, null, null)
        assertPin(url, "S2 no layer at all", "bf-f", null, null, null, null, null)
        assertPin(url, "S2 layered root without a server layer", "bf-g", null, rootHeadId(url, "root3"), "default", "root", "manual")
    }

    @Test
    fun `probe - a per-root document that does not parse counts as no per-root layer and does not fail the run`() {
        val url = migratedGolden(dir, "bad-yaml", "work_item_schemas: [unclosed", ::seedProbes)

        val (_, report, _) = bootProductionSteps(url, dir, "bad-yaml", PIN_SERVER_YAML)

        assertTrue(stepName in report.applied, "the run completes and applies the step: $report")
        assertEquals(0, unpinnedRows(url))
        val pinned = pinRow(url, "bf-b")
        assertEquals("task-b", pinned.key, "with no per-root layer the server task-b is the only match")
        assertEquals("server", pinned.layer)
        assertEquals(serverHeadId(url), pinned.serverCfg)
    }

    // ---------------------------------------------------------------------------------------------
    // S11 - second boot and the ledger / predicate
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 a second boot skips the step, changes nothing and leaves one ledger row`() {
        val url = migratedGolden(dir, "s11", PIN_ROOT_DEFAULT_YAML, ::seedProbes)
        bootProductionSteps(url, dir, "s11", PIN_SERVER_YAML)
        val itemsAfterFirst = DriverManager.getConnection(url).use { UpgradeHarness.dump(it) }.getValue("work_items")
        val ledger = ledgerRows(url)

        val (_, second, _) = bootProductionSteps(url, dir, "s11", PIN_SERVER_YAML)

        assertTrue(stepName in second.skipped, "second run skips it: $second")
        assertTrue(stepName !in second.applied, "$second")
        val itemsAfterSecond = DriverManager.getConnection(url).use { UpgradeHarness.dump(it) }.getValue("work_items")
        assertEquals(itemsAfterFirst, itemsAfterSecond, "rows byte-identical")
        assertEquals(1, ledgerCount(url))
        assertEquals(ledger, ledgerRows(url), "the ledger count is not rewritten")
    }

    @Test
    fun `S11 a skipped step is decided by the ledger, so a NULLed row stays NULL while the ledger row exists`() {
        val url = migratedGolden(dir, "s11-ledger", PIN_ROOT_DEFAULT_YAML, ::seedProbes)
        bootProductionSteps(url, dir, "s11-ledger", PIN_SERVER_YAML)
        clearPin(url, "bf-a")

        val (_, second, _) = bootProductionSteps(url, dir, "s11-ledger", PIN_SERVER_YAML)

        assertTrue(stepName in second.skipped, "$second")
        assertNull(pinRow(url, "bf-a").pinnedAt, "ONCE: the step does not run again while its ledger row exists")
    }

    @Test
    fun `S11 a re-run pins only the rows with a NULL pinned_at and never rewrites an already pinned row`() {
        val url = migratedGolden(dir, "s11-rerun", PIN_ROOT_DEFAULT_YAML, ::seedProbes)
        bootProductionSteps(url, dir, "s11-rerun", PIN_SERVER_YAML)
        val server = serverHeadId(url)
        val rootHead = rootHeadId(url, "root")
        val customAt = "2026-01-02 03:04:05.678"
        DriverManager.getConnection(url).use { conn ->
            // bf-a is already pinned to a hand-chosen schema; bf-c has lost its pin; the ledger row is removed so the step may run.
            conn
                .prepareStatement(
                    "UPDATE work_items SET pinned_schema_key = 'hand-chosen', pinned_schema_layer = 'server', " +
                        "lifecycle = 'permanent', pinned_at = ? WHERE id = ?"
                ).use {
                    it.setString(1, customAt)
                    it.setBytes(2, BaselineDataset.id("bf-a"))
                    assertEquals(1, it.executeUpdate())
                }
            conn.createStatement().use { it.executeUpdate("DELETE FROM data_steps WHERE name = '$stepName'") }
        }
        clearPin(url, "bf-c")

        val (_, report, _) = bootProductionSteps(url, dir, "s11-rerun", PIN_SERVER_YAML)

        assertTrue(stepName in report.applied, "with the ledger row gone the step runs again: $report")
        assertEquals(1, ledgerRows(url), "rows_affected counts only the one row that had a NULL pinned_at")
        val kept = pinRow(url, "bf-a")
        assertEquals("hand-chosen", kept.key, "an already pinned row is never rewritten")
        assertEquals("server", kept.layer)
        assertEquals("permanent", kept.lifecycle)
        assertEquals(customAt, kept.pinnedAt)
        assertPin(url, "S11 re-pinned row", "bf-c", server, null, "task-b", "server", "auto")
        assertEquals(rootHead, pinRow(url, "bf-b").rootCfg, "a pinned bystander is untouched")
    }
}
