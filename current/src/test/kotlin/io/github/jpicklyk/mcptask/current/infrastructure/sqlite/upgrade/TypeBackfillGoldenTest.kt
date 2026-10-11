package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepKind
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepPhase
import io.github.jpicklyk.mcptask.current.application.upgrade.LegacyTypeInference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests for the `type-backfill` data step (item 2c67eeca): scenarios S13 and S14 of the frozen test-plan plus
 * probes. The input is a COPY of the checked-in golden V17 database (see [GoldenV17]) that the tests alter with SQL before
 * migrating, as task-scope D11 prescribes; the step is exercised through the production composition
 * ([UpgradeHarness.runProductionDataStepsWithPlan]), so its registration is part of what is proved.
 *
 * The global config reaches the step the way production supplies it: the harness fixes `AGENT_CONFIG_DIR` to
 * `<runDir>/empty-agent-config`, and the global file is `<AGENT_CONFIG_DIR>/.taskorchestrator/config.yaml` (documented in
 * the project README on the config directory), so each scenario writes that file before the run.
 *
 * Oracles (frozen task-scope 1e, AC7, AC8 decisions D7 D8 D9 D10 D11 and the frozen inference rule 1d, none read from the
 * implementation): the step is named `type-backfill`, phase ITEM_BACKFILL, kind ONCE, no `after`; it types every row whose
 * `type` is NULL or blank from its tags using the per-root config YAML (each root's head `config_versions` row since V25)
 * and the global file; it writes `type` only (no `version` or `modified_at` bump, no event rows); it records its
 * updated-row count in `data_steps.rows_affected`; a second boot skips it; a per-root YAML that does not parse counts as no
 * per-root layer and never fails the run; a row with a NULL `root_id` is inferred against the global layer only; typed rows
 * are never rewritten.
 */
class TypeBackfillGoldenTest {
    @TempDir
    lateinit var dir: File

    private val stepName = "type-backfill"

    private data class Seed(
        val name: String,
        val tags: String?,
        val type: String?,
        val under: String = "root",
        val rootNull: Boolean = false,
    )

    private fun hexId(name: String): String = BaselineDataset.id(name).joinToString("") { "%02x".format(it) }

    private fun insertRoot2(conn: Connection) {
        BaselineDataset.insert(
            conn,
            "work_items",
            "id" to BaselineDataset.id("root2"),
            "root_id" to BaselineDataset.id("root2"),
            "title" to "Second root",
            "summary" to "",
            "role" to "queue",
            "priority" to "medium",
            "complexity" to 5,
            "requires_verification" to 0,
            "depth" to 0,
            "type" to "feature-task",
            "created_at" to BaselineDataset.TS,
            "modified_at" to BaselineDataset.TS_PLUS_1,
            "role_changed_at" to BaselineDataset.TS_PLUS_2,
            "version" to 1,
        )
    }

    private fun insertSeed(
        conn: Connection,
        seed: Seed,
    ) {
        BaselineDataset.insert(
            conn,
            "work_items",
            "id" to BaselineDataset.id(seed.name),
            "parent_id" to if (seed.rootNull) null else BaselineDataset.id(seed.under),
            "root_id" to if (seed.rootNull) null else BaselineDataset.id(seed.under),
            "title" to "Probe ${seed.name}",
            "summary" to "",
            "role" to "queue",
            "priority" to "medium",
            "complexity" to 5,
            "requires_verification" to 0,
            "depth" to if (seed.rootNull) 0 else 1,
            "tags" to seed.tags,
            "type" to seed.type,
            "created_at" to BaselineDataset.TS,
            "modified_at" to BaselineDataset.TS_PLUS_1,
            "role_changed_at" to BaselineDataset.TS_PLUS_2,
            "version" to 1,
        )
    }

    /** A V17 golden copy with [perRootYaml] for the baseline root (null deletes its row) and [seeds], migrated to latest. */
    private fun prepare(
        name: String,
        perRootYaml: String?,
        seeds: List<Seed>,
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
            insertRoot2(conn)
            seeds.forEach { insertSeed(conn, it) }
        }
        UpgradeHarness.migrate(url)
        return url
    }

    private fun bootSteps(
        url: String,
        name: String,
        globalYaml: String?,
    ) = File(dir, "run-$name").let { runDir ->
        runDir.mkdirs()
        if (globalYaml != null) {
            val config = File(runDir, "empty-agent-config/.taskorchestrator/config.yaml")
            config.parentFile.mkdirs()
            config.writeText(globalYaml)
        }
        UpgradeHarness.runProductionDataStepsWithPlan(url, runDir)
    }

    private fun typeOf(
        url: String,
        name: String,
    ): String? =
        DriverManager.getConnection(url).use { conn ->
            conn.prepareStatement("SELECT type FROM work_items WHERE id = ?").use { ps ->
                ps.setBytes(1, BaselineDataset.id(name))
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next(), "row $name must exist")
                    rs.getString(1)
                }
            }
        }

    private fun scalar(
        url: String,
        sql: String,
    ): Int =
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun untypedCount(url: String) = scalar(url, "SELECT COUNT(*) FROM work_items WHERE type IS NULL OR trim(type) = ''")

    private fun ledgerRows(url: String): Int? =
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT rows_affected FROM data_steps WHERE name = '$stepName'").use { rs ->
                    if (rs.next()) rs.getInt(1) else null
                }
            }
        }

    /**
     * The dump of [url] minus what the other production step writes: `config-canonicalize` (phase CONFIG_CANONICALIZE,
     * before this step) fills `config_versions.canonical_json` / `model_version` and records its own `data_steps` row;
     * `traits-from-properties` (same phase, after this step) moves `work_items.properties.traits` into `item_traits`.
     */
    private fun dumpOf(url: String) =
        DriverManager.getConnection(url).use { UpgradeHarness.dump(it) }.mapValues { (table, rows) ->
            when (table) {
                "config_versions" -> rows.mapValues { (_, row) -> row - "canonical_json" - "model_version" }
                "data_steps" -> rows.filterValues { it["name"] == stepName }
                "work_items" -> rows.mapValues { (_, row) -> row + ("properties" to withoutTraitsKey(row["properties"])) }
                "item_traits" -> emptyMap()
                else -> rows
            }
        }

    /**
     * [properties] with only the `traits` key removed (an emptied bag reads as null), so every other properties key
     * still takes part in the comparison; parsed and re-serialized so both sides compare in one canonical form.
     */
    private fun withoutTraitsKey(properties: Any?): Any? {
        val text = properties as? String ?: return properties
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return text
        val rest = JsonObject(parsed - "traits")
        return if (rest.isEmpty()) null else rest.toString()
    }

    private val perRootYaml = "work_item_schemas:\n  task-a:\n    notes: []\n  default:\n    notes: []\n"
    private val globalYaml = "work_item_schemas:\n  task-b:\n    notes: []\n"

    // ---------------------------------------------------------------------------------------------
    // The step's frozen identity
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the step carries the frozen name, phase and kind and declares no ordering`() {
        val step = TypeBackfillStep(File(dir, "no-config.yaml").toPath())

        assertEquals("type-backfill", step.name)
        assertEquals(DataStepPhase.ITEM_BACKFILL, step.phase)
        assertEquals(DataStepKind.ONCE, step.kind)
        assertEquals(emptySet(), step.after, "Q15 declares after: type-backfill, so type-backfill itself waits for nothing")
    }

    // ---------------------------------------------------------------------------------------------
    // S13 - golden copy with four untyped rows
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S13 the step types the four unresolved rows per the inference rule, touches nothing else, and a second run skips it`() {
        val seeds =
            listOf(
                Seed("bf-a", "q,task-a", null),
                Seed("bf-b", "q,task-b", null),
                Seed("bf-c", "task-b", null, under = "root2"),
                Seed("bf-d", "nothing", "   ", under = "root2"),
                Seed("bf-typed", "task-a", "keep-me"),
            )
        val url = prepare("s13", perRootYaml, seeds)
        assertEquals(4, untypedCount(url), "fixture: exactly the four unresolved rows are NULL or blank before the run")
        val before = dumpOf(url)

        val (report, plan) = bootSteps(url, "s13", globalYaml)

        assertTrue(stepName in plan.map { it.name }, "the step is registered in the production plan: ${plan.map { it.name }}")
        assertTrue(stepName in report.applied, "first run applies it: $report")
        assertEquals("task-a", typeOf(url, "bf-a"), "per-root tag match")
        assertEquals("default", typeOf(url, "bf-b"), "legacy: the per-root default shadows the global tag task-b")
        assertEquals("task-b", typeOf(url, "bf-c"), "a root without a per-root config resolves against the global layer")
        assertEquals("untyped", typeOf(url, "bf-d"), "a blank type with no match is the residue value")
        assertEquals("keep-me", typeOf(url, "bf-typed"), "a typed row is never rewritten, whatever its tags match")
        assertEquals(0, untypedCount(url))
        assertEquals(4, ledgerRows(url), "data_steps.rows_affected is the number of rows updated")

        val after = dumpOf(url)
        for ((table, rows) in before) {
            if (table == "data_steps") continue
            if (table != "work_items") {
                assertEquals(rows, after[table], "table $table is untouched (no events, notes or config rewritten)")
                continue
            }
            val changed = listOf("bf-a", "bf-b", "bf-c", "bf-d").map { hexId(it) }.toSet()
            assertEquals(rows.keys, after[table]!!.keys, "no work item row added or removed")
            for ((id, row) in rows) {
                val now = after[table]!![id]!!
                assertEquals(row - "type", now - "type", "row $id: only the type column may change (version and modified_at stay)")
                if (id !in changed) assertEquals(row["type"], now["type"], "row $id was typed and stays as it was")
            }
        }
        assertEquals(1, after["data_steps"]!!.size, "exactly one ledger row for the step")

        val (second, _) = bootSteps(url, "s13", globalYaml)
        assertTrue(stepName in second.skipped, "second run skips it: $second")
        assertTrue(stepName !in second.applied, "$second")
        assertEquals(after, dumpOf(url), "a second boot changes nothing")
    }

    // ---------------------------------------------------------------------------------------------
    // S14 - golden with nothing to backfill
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S14 on the untouched golden the step is applied with zero rows and the work items are unchanged`() {
        val golden = GoldenV17.copyTo(File(dir, "s14.sqlite"))
        val url = UpgradeHarness.urlFor(golden)
        UpgradeHarness.migrate(url)
        assertEquals(0, untypedCount(url), "fixture: every golden item is typed")
        val before = dumpOf(url)

        val (report, _) = bootSteps(url, "s14", null)

        assertTrue(stepName in report.applied, "$report")
        assertEquals(0, ledgerRows(url))
        val after = dumpOf(url)
        assertEquals(before.filterKeys { it != "data_steps" }, after.filterKeys { it != "data_steps" })
    }

    // ---------------------------------------------------------------------------------------------
    // Probes - config shapes and row shapes
    // ---------------------------------------------------------------------------------------------

    private fun probe(
        name: String,
        perRoot: String?,
        global: String?,
        vararg seeds: Seed,
    ): String {
        val url = prepare(name, perRoot, seeds.toList())
        val (report, _) = bootSteps(url, name, global)
        assertTrue(stepName in report.applied, "$name: the run must complete and apply the step: $report")
        assertEquals(0, untypedCount(url), "$name: nothing is left unresolved")
        return url
    }

    @Test
    fun `probe - a per-root YAML that does not parse counts as no per-root layer and does not fail the run`() {
        val url =
            probe(
                "bad-yaml",
                "work_item_schemas: [unclosed",
                globalYaml,
                Seed("p1", "task-a,task-b", null),
            )

        assertEquals("task-b", typeOf(url, "p1"))
    }

    @Test
    fun `probe - a per-root YAML whose root is not a map counts as no per-root layer`() {
        val url = probe("list-yaml", "- task-a\n- default\n", globalYaml, Seed("p1", "task-a,task-b", null))

        assertEquals("task-b", typeOf(url, "p1"))
    }

    @Test
    fun `probe - a note_schemas-only YAML declares its keys`() {
        val url = probe("note-schemas", "note_schemas:\n  task-c: []\n", null, Seed("p1", "task-c", null))

        assertEquals("task-c", typeOf(url, "p1"))
    }

    @Test
    fun `probe - work_item_schemas takes precedence over note_schemas when both are present`() {
        val url =
            probe(
                "both-sections",
                "work_item_schemas:\n  task-a:\n    notes: []\nnote_schemas:\n  task-c: []\n",
                null,
                Seed("p1", "task-c", null),
                Seed("p2", "task-a", null),
            )

        assertEquals("untyped", typeOf(url, "p1"), "task-c is declared only in the ignored note_schemas section")
        assertEquals("task-a", typeOf(url, "p2"))
    }

    @Test
    fun `probe - a row with a NULL root_id is inferred against the global layer only`() {
        val url =
            probe(
                "null-root",
                perRootYaml,
                globalYaml,
                Seed("p1", "task-b", null, rootNull = true),
                Seed("p2", "task-a", null, rootNull = true),
            )

        assertEquals("task-b", typeOf(url, "p1"))
        assertEquals("untyped", typeOf(url, "p2"), "task-a is declared only per-root, which a root-less row has no layer for")
    }

    @Test
    fun `probe - with no global config file only the per-root layer applies`() {
        val url =
            probe(
                "no-global",
                perRootYaml,
                null,
                Seed("p1", "task-a", null),
                Seed("p2", "zzz", null),
                Seed("p3", "zzz", null, under = "root2"),
            )

        assertEquals("task-a", typeOf(url, "p1"))
        assertEquals("default", typeOf(url, "p2"))
        assertEquals("untyped", typeOf(url, "p3"), "root2 has no per-root config and there is no global file")
    }

    @Test
    fun `probe - tags are trimmed, blank entries dropped and duplicates harmless`() {
        val url =
            probe(
                "tag-shapes",
                "work_item_schemas:\n  task-a:\n    notes: []\n",
                null,
                Seed("p1", " x , task-a ", null),
                Seed("p2", ",,task-a,task-a,", null),
                Seed("p3", null, null),
                Seed("p4", "", null),
            )

        assertEquals("task-a", typeOf(url, "p1"))
        assertEquals("task-a", typeOf(url, "p2"))
        assertEquals("untyped", typeOf(url, "p3"), "NULL tags")
        assertEquals("untyped", typeOf(url, "p4"), "empty tags")
    }

    @Test
    fun `probe - a global layered mode lets a global tag beat the per-root default`() {
        val url =
            probe(
                "global-layered",
                perRootYaml,
                "schema_resolution: layered\nwork_item_schemas:\n  task-b:\n    notes: []\n",
                Seed("p1", "task-b", null),
            )

        assertEquals("task-b", typeOf(url, "p1"))
    }

    @Test
    fun `probe - a per-root isolated mode ignores the global layer`() {
        val url =
            probe(
                "root-isolated",
                "schema_resolution: isolated\nwork_item_schemas:\n  task-a:\n    notes: []\n",
                "work_item_schemas:\n  task-b:\n    notes: []\n  default:\n    notes: []\n",
                Seed("p1", "task-b", null),
                Seed("p2", "task-a", null),
            )

        assertEquals("untyped", typeOf(url, "p1"), "the global tag and the global default are never reached")
        assertEquals("task-a", typeOf(url, "p2"))
    }

    @Test
    fun `probe - a schema_resolution spelled in the wrong case is treated as absent, so legacy applies`() {
        val url =
            probe(
                "mode-case",
                "schema_resolution: Layered\nwork_item_schemas:\n  task-a:\n    notes: []\n  default:\n    notes: []\n",
                globalYaml,
                Seed("p1", "task-b", null),
            )

        assertEquals("default", typeOf(url, "p1"), "legacy: the per-root default wins; layered would have given task-b")
    }

    // ---------------------------------------------------------------------------------------------
    // layerOf - the key-only YAML scan
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `layerOf reads the schema keys and the schema_resolution of a config document`() {
        val layer =
            TypeBackfillStep.layerOf("schema_resolution: layered\nwork_item_schemas:\n  a:\n    notes: []\n  b: {}\n")

        assertEquals(LegacyTypeInference.Layer(setOf("a", "b"), LegacyTypeInference.Mode.LAYERED), layer)
    }

    @Test
    fun `layerOf falls back to note_schemas keys and reports an unset or unrecognised mode as null`() {
        assertEquals(LegacyTypeInference.Layer(setOf("n1", "n2"), null), TypeBackfillStep.layerOf("note_schemas:\n  n1: []\n  n2: []\n"))
        assertEquals(
            LegacyTypeInference.Layer(setOf("a"), null),
            TypeBackfillStep.layerOf("schema_resolution: Isolated\nwork_item_schemas:\n  a: {}\n")
        )
        assertEquals(
            LegacyTypeInference.Layer(setOf("a"), null),
            TypeBackfillStep.layerOf("schema_resolution: 5\nwork_item_schemas:\n  a: {}\n")
        )
        assertEquals(
            LegacyTypeInference.Layer(setOf("a"), LegacyTypeInference.Mode.ISOLATED),
            TypeBackfillStep.layerOf("schema_resolution: isolated\nwork_item_schemas:\n  a: {}\n")
        )
    }

    @Test
    fun `layerOf of a map with no schema section is an empty layer and of anything else is null`() {
        assertEquals(LegacyTypeInference.Layer(emptySet(), null), TypeBackfillStep.layerOf("project:\n  name: x\n"))
        assertEquals(LegacyTypeInference.Layer(emptySet(), null), TypeBackfillStep.layerOf("{}"))
        assertNull(TypeBackfillStep.layerOf(""))
        assertNull(TypeBackfillStep.layerOf("- a\n- b\n"))
        assertNull(TypeBackfillStep.layerOf("just a scalar"))
        assertNull(TypeBackfillStep.layerOf("work_item_schemas: [unclosed"))
        assertNull(TypeBackfillStep.layerOf("a: b: c: ["))
    }
}
