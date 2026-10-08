package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerComposition
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SF2 follow-up on item `79cd4f0c` (A1 review): S8 (trait-seats wholesale, dispatch-block wholesale,
 * base-schema-seats LEGACY vs LAYERED), S9 (a per-item trait cannot redefine a base seat), and S2(b)
 * (the task-scope section 2 YAML pushed as a PER-ROOT config, not just a global file) -- all exercised
 * through a REAL per-root push (`projectConfigRepository().upsert`) resolved by the REAL
 * [EffectiveConfigResolver] wired inside a REAL [ServerComposition.build], observed through the public
 * MCP tools ([QueryItemsTool] for type-based resolution, [GetContextTool] for item-based resolution) --
 * per the Public-API rule (`LayeredConfigSeatTest`'s existing S8/S9 coverage calls `LayeredConfig`'s
 * `internal` merge functions directly with hand-built [ConfigLayer]s, which the independent review
 * flagged: SF2). `LayeredConfigSeatTest` is left in place per the dispatch's instruction; this file adds
 * the resolver-level, real-push coverage alongside it.
 *
 * Every scenario below is deliberately built with TWO roots sharing one global config: one root gets
 * the per-root push under test, the other gets no push (or a different push) at all. Every assertion
 * is checked on BOTH roots and shown to DIFFER, so that a mutation which resolves the item's schema
 * against the global layer only (ignoring `rootId` -- the exact regression this file's dispatch names,
 * `EffectiveConfigResolver.layered(item.rootId)` degrading to `layered(null)`) collapses the two roots'
 * results together and reddens the cross-root inequality, not just a single fixed expectation.
 *
 * Oracle: `task-scope-addendum` S8/S9/S2(b) (merged-seat order, per-trait wholesale replacement,
 * base-schema-seat source following `schema_resolution`, a per-item trait's redefinition of a base
 * seat being dropped) -- the same text `LayeredConfigSeatTest`'s KDoc cites, applied here through the
 * resolver's public surface instead of `LayeredConfig`'s internals.
 */
class SeatPerRootResolverTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private fun buildDatabaseManager(): DatabaseManager = db.databaseManager

    private fun materializeGlobalConfig(
        tempDir: Path,
        content: String,
    ) {
        val configDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        Files.write(configDir.resolve("config.yaml"), content.toByteArray(Charsets.UTF_8))
    }

    private fun buildComposition(
        tempDir: Path,
        globalConfig: String,
    ): CompositionResult {
        materializeGlobalConfig(tempDir, globalConfig)
        val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") tempDir.toString() else null }
        return ServerComposition(
            appConfig = appConfig,
            databaseManager = buildDatabaseManager(),
            shutdownCoordinator = ShutdownCoordinator()
        ).build()
    }

    private fun createRoot(
        composition: CompositionResult,
        title: String,
    ): UUID =
        runBlocking {
            composition.toolContext.repositoryProvider
                .workItemRepository()
                .create(WorkItem(title = title, type = "project", depth = 0))!!
                .id
        }

    private fun pushPerRoot(
        composition: CompositionResult,
        rootId: UUID,
        yaml: String,
    ) {
        runBlocking {
            composition.toolContext.repositoryProvider
                .projectConfigRepository()
                .upsert(rootId, yaml)
                ?: error("fixture: per-root push failed for $rootId")
        }
    }

    private fun createChild(
        composition: CompositionResult,
        rootId: UUID,
        type: String,
        role: Role = Role.WORK,
        properties: String? = null,
    ): WorkItem =
        runBlocking {
            composition.toolContext.repositoryProvider
                .workItemRepository()
                .create(
                    WorkItem(
                        title = "child of $rootId",
                        type = type,
                        role = role,
                        parentId = rootId,
                        rootId = rootId,
                        depth = 1,
                        properties = properties,
                    ),
                )!!
        }

    private fun schemaByType(
        composition: CompositionResult,
        type: String,
        rootId: UUID?,
    ): JsonObject {
        val params =
            buildJsonObject {
                put("operation", JsonPrimitive("schema"))
                put("type", JsonPrimitive(type))
                if (rootId != null) put("rootId", JsonPrimitive(rootId.toString()))
            }
        val result = runBlocking { QueryItemsTool().execute(params, composition.toolContext) }
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "expected query_items(schema) success: $obj")
        return obj["data"] as JsonObject
    }

    private fun getContextData(
        composition: CompositionResult,
        itemId: UUID,
    ): JsonObject {
        val result =
            runBlocking {
                GetContextTool().execute(buildJsonObject { put("itemId", JsonPrimitive(itemId.toString())) }, composition.toolContext)
            }
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "expected get_context success: $obj")
        return obj["data"] as JsonObject
    }

    private fun seatNames(schemaData: JsonObject): List<String> =
        (schemaData["seats"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonObject["name"]!!.jsonPrimitive.content } ?: emptyList()

    // ─────────────────────────────────────────────────────────────────────
    // S8a -- a per-root trait declared with notes only excludes the trait's global seats wholesale,
    // through the resolver via a real push. Contrast root (no push) keeps the global trait's seat.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S8a per-root trait redeclared with notes only excludes the global trait's seats wholesale (real push, through the resolver)`(
        @TempDir tempDir: Path,
    ) {
        val globalConfig =
            """
            work_item_schemas:
              bug-fix:
                default_traits: [T]
                notes: []
            traits:
              T:
                seats:
                  - { name: s1, phase: work }
                notes:
                  - { key: g-note, role: queue }
            """.trimIndent()
        val perRootYaml =
            """
            work_item_schemas:
              bug-fix:
                default_traits: [T]
                notes: []
            traits:
              T:
                notes:
                  - { key: pr-note, role: queue }
            """.trimIndent()

        val composition = buildComposition(tempDir, globalConfig)
        val pushedRoot = createRoot(composition, "S8a pushed root")
        pushPerRoot(composition, pushedRoot, perRootYaml)
        val controlRoot = createRoot(composition, "S8a control root (no push)")

        val pushedItem = createChild(composition, pushedRoot, "bug-fix")
        val controlItem = createChild(composition, controlRoot, "bug-fix")

        val pushedSeats = seatNames(getContextData(composition, pushedItem.id))
        val controlSeats = seatNames(getContextData(composition, controlItem.id))

        assertTrue(pushedSeats.none { it == "s1" }, "the per-root trait's absent seats must win wholesale over global's s1: $pushedSeats")
        assertTrue(controlSeats.contains("s1"), "sanity: without a push, the global trait's seat s1 must still be served: $controlSeats")
        assertFalse(pushedSeats == controlSeats, "the pushed root's resolution must differ from the un-pushed control root's")
    }

    // ─────────────────────────────────────────────────────────────────────
    // S8b -- a per-root trait supplying only a per-seat dispatch override replaces the WHOLE dispatch
    // block (byRole + bySeat), so the global phase-level agent must not leak through.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S8b per-root dispatch block wholesale -- no global phase-level agent leak into the per-root seat override (real push)`(
        @TempDir tempDir: Path,
    ) {
        val globalConfig =
            """
            work_item_schemas:
              bug-fix:
                default_traits: [T2]
            traits:
              T2:
                seats:
                  - { name: s1, phase: work }
                dispatch:
                  work:
                    agent: GA
                    seats:
                      s1: { model: g }
            """.trimIndent()
        val perRootYaml =
            """
            work_item_schemas:
              bug-fix:
                default_traits: [T2]
            traits:
              T2:
                seats:
                  - { name: s1, phase: work }
                dispatch:
                  work:
                    seats:
                      s1: { model: p }
            """.trimIndent()

        val composition = buildComposition(tempDir, globalConfig)
        val pushedRoot = createRoot(composition, "S8b pushed root")
        pushPerRoot(composition, pushedRoot, perRootYaml)
        val controlRoot = createRoot(composition, "S8b control root (no push)")

        val pushedItem = createChild(composition, pushedRoot, "bug-fix")
        val controlItem = createChild(composition, controlRoot, "bug-fix")

        val pushedDispatchBySeat = getContextData(composition, pushedItem.id)["dispatchBySeat"]!!.jsonObject
        val controlDispatchBySeat = getContextData(composition, controlItem.id)["dispatchBySeat"]!!.jsonObject

        val pushedS1 = pushedDispatchBySeat["s1"]!!.jsonObject
        assertEquals(setOf("model"), pushedS1.keys, "per-root wholesale replacement: only model, no leaked global agent: $pushedS1")
        assertEquals("p", pushedS1["model"]!!.jsonPrimitive.content)

        val controlS1 = controlDispatchBySeat["s1"]!!.jsonObject
        assertEquals(
            setOf("agent", "model"),
            controlS1.keys,
            "sanity: without a push, the global phase-default agent AND seat override both apply: $controlS1",
        )
        assertEquals("GA", controlS1["agent"]!!.jsonPrimitive.content)
        assertEquals("g", controlS1["model"]!!.jsonPrimitive.content)
        assertFalse(pushedS1 == controlS1, "the pushed root's dispatch-by-seat must differ from the un-pushed control root's")
    }

    // ─────────────────────────────────────────────────────────────────────
    // S8c (N2) -- base-schema seats follow the layer schema_resolution selects: LEGACY lets the
    // per-root `default` schema shadow global's EXACT type match; `layered` lets global's exact match
    // win instead. Same per-root fixture on both roots, ONLY schema_resolution differs, so a mutation
    // that ignores the mode reddens (both roots would otherwise resolve identically).
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S8c base-schema seats follow schema_resolution -- LEGACY per-root default shadows global's exact type, layered lets it win (N2)`(
        @TempDir tempDir: Path,
    ) {
        val globalConfig =
            """
            work_item_schemas:
              special-type:
                seats:
                  - { name: g1, phase: work }
            """.trimIndent()

        fun perRootYaml(layered: Boolean) =
            (if (layered) "schema_resolution: layered\n" else "") +
                """
                work_item_schemas:
                  default:
                    seats:
                      - { name: p1, phase: work }
                """.trimIndent()

        val composition = buildComposition(tempDir, globalConfig)
        val legacyRoot = createRoot(composition, "S8c legacy root")
        pushPerRoot(composition, legacyRoot, perRootYaml(layered = false))
        val layeredRoot = createRoot(composition, "S8c layered root")
        pushPerRoot(composition, layeredRoot, perRootYaml(layered = true))

        val legacySeats = seatNames(schemaByType(composition, "special-type", legacyRoot))
        val layeredSeats = seatNames(schemaByType(composition, "special-type", layeredRoot))

        assertEquals(listOf("p1"), legacySeats, "N2/LEGACY: the per-root default schema must shadow global's exact-type match")
        assertEquals(listOf("g1"), layeredSeats, "N2/layered: global's exact-type match must win over the per-root default")
        assertFalse(legacySeats == layeredSeats, "toggling schema_resolution alone must change which layer's base schema is served")
    }

    // ─────────────────────────────────────────────────────────────────────
    // S9 -- a per-item trait redefining a base seat by name is dropped; the base seat's phase/enters
    // survive; a second `enters:true` seat in the same phase is demoted, not thrown -- through
    // get_context, not LayeredConfig.mergeTraits directly.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S9 a per-item trait cannot redefine a base seat's phase -- observed through get_context via a real per-root push`(
        @TempDir tempDir: Path,
    ) {
        val perRootYaml =
            """
            work_item_schemas:
              bug-fix:
                seats:
                  - { name: implementer, phase: work, enters: true }
                notes:
                  - { key: dummy, role: work }
            traits:
              t1:
                seats:
                  - { name: implementer, phase: review }
                  - { name: z, phase: work, enters: true }
                notes:
                  - { key: t1note, role: queue }
            """.trimIndent()

        val composition = buildComposition(tempDir, "work_item_schemas: {}\n")
        val root = createRoot(composition, "S9 root")
        pushPerRoot(composition, root, perRootYaml)
        val item = createChild(composition, root, "bug-fix", role = Role.WORK, properties = """{"traits":["t1"]}""")

        val data = getContextData(composition, item.id)
        val seats = data["seats"]!!.jsonArray.map { it.jsonObject }
        val seatNames = seats.map { it["name"]!!.jsonPrimitive.content }

        assertTrue(
            seatNames.contains("implementer"),
            "the base seat 'implementer' must still be a WORK-phase seat -- the trait's redefinition to 'review' must be dropped: $seatNames",
        )
        val implementerSeat = seats.first { it["name"]!!.jsonPrimitive.content == "implementer" }
        assertTrue(
            implementerSeat["enters"]!!.jsonPrimitive.boolean,
            "the base seat's enters:true must survive the dropped trait redefinition",
        )

        val zSeat = seats.first { it["name"]!!.jsonPrimitive.content == "z" }
        assertFalse(
            zSeat.containsKey("enters"),
            "a second enters:true seat in the same phase (work) must be demoted to false (omitted key)"
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // S2(b) -- the task-scope section 2 YAML, pushed as a PER-ROOT config (not the T0/A1a global-file
    // half SeatConfigParseTest already covers), gives the same merged seat order through the
    // resolver. The control root (no push, global declares nothing for "bug-fix") proves the item's
    // rootId is what supplies the schema at all.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S2b the task-scope section 2 YAML pushed as a per-root config gives the merged seat order through the resolver`(
        @TempDir tempDir: Path,
    ) {
        val perRootYaml =
            """
            work_item_schemas:
              bug-fix:
                default_traits: [needs-test-author]
                seats:
                  - { name: planner,      phase: queue }
                  - { name: implementer,  phase: work, enters: true }
                  - { name: extractor,    phase: work, after: [implementer] }
                  - { name: orchestrator, phase: work }
                  - { name: reviewer,     phase: review }
                notes:
                  - { key: diagnosis, role: queue, required: true, seat: planner }
                  - { key: implementation-notes, role: work, required: true, seat: implementer }
                  - { key: session-tracking, role: work, required: true, seat: orchestrator }
            traits:
              needs-test-author:
                seats:
                  - { name: test-author, phase: work, after: [extractor], reads_exclude: [implementation-notes] }
                notes:
                  - { key: test-plan, role: queue, required: true, seat: planner }
                  - { key: test-manifest, role: work, required: true, seat: test-author, independent_of: [implementer] }
            """.trimIndent()

        val composition = buildComposition(tempDir, "work_item_schemas: {}\n")
        val pushedRoot = createRoot(composition, "S2b pushed root")
        pushPerRoot(composition, pushedRoot, perRootYaml)
        val controlRoot = createRoot(composition, "S2b control root (no push, no global bug-fix schema)")

        val pushedItem = createChild(composition, pushedRoot, "bug-fix", role = Role.WORK)
        val controlItem = createChild(composition, controlRoot, "bug-fix", role = Role.WORK)

        val pushedData = getContextData(composition, pushedItem.id)
        // Current-phase (work) merged order, base seats first then trait seats: [implementer,
        // extractor, orchestrator, test-author] -- reviewer (review) and planner (queue) excluded.
        assertEquals(
            listOf("implementer", "extractor", "orchestrator", "test-author"),
            pushedData["seats"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
            "merged seat order must be base seats first, then trait seats, filtered to the current (work) phase",
        )

        val controlData = getContextData(composition, controlItem.id)
        assertFalse(
            controlData.containsKey("seats"),
            "the control root has no per-root push and the global config declares no 'bug-fix' schema -- the item must be schema-free: $controlData",
        )
    }
}
