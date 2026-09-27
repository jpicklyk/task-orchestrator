package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.domain.model.SeatDispatchOverride
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [LayeredConfig]'s seat-layering and dispatch-by-seat merge rules (item `79cd4f0c`,
 * stage A1a), scenarios S7-S9 of the frozen `test-plan`/`task-scope-addendum`. Mirrors
 * [SchemaResolutionPrecedenceTest]'s and [EffectiveConfigResolverTest]'s style of constructing
 * [ConfigDocument]/[ConfigLayer]/[LayerBackedGlobalLookup] fixtures directly and calling
 * [LayeredConfig] methods — including its `internal` merge functions, which are visible from this
 * module's test source set (confirmed for [io.github.jpicklyk.mcptask.current.infrastructure.config.YamlSchemaParser],
 * an `internal object` in a different package, so the same module-wide visibility applies here).
 *
 * Scope: S7 (dispatchBySeat overlay/clear/filter), S8 (per-root trait-seat and dispatch-block
 * wholesale replacement, base-schema seats following whichever layer supplied the base schema),
 * S9 (a per-item trait cannot redefine a base seat's phase/enters; a second `enters:true` in a
 * phase is demoted). S2-S4's parse-only halves are owned by `SeatConfigParseTest`.
 *
 * Independent test authorship per the `needs-test-author` trait: oracles come from the item's
 * frozen `task-scope` §4 layering table and `task-scope-addendum` S7-S9, never from reading
 * [LayeredConfig]'s source.
 */
class LayeredConfigSeatTest {
    private fun globalLookup(doc: ConfigDocument): GlobalConfigLookup =
        LayerBackedGlobalLookup(ConfigLayer(doc, "global-fp", ConfigSource.GLOBAL))

    // ──────────────────────────────────────────────
    // S7 — dispatchBySeat: item-trait override claims the pair first, defaultTraits fall through,
    // agent:null clears, filtered to the resolved schema's merged seats for that phase
    // ──────────────────────────────────────────────

    @Test
    fun `S7 dispatchBySeat overlays the first-claiming trait's override, clears null fields, and filters to merged seats`() {
        val doc =
            ConfigDocument(
                workItemSchemas = emptyMap(),
                traits = emptyMap(),
                traitDispatch = mapOf("t-def" to mapOf(Role.WORK to DispatchProfile(agent = "A", effort = "high"))),
                traitDispatchBySeat =
                    mapOf(
                        "t-def" to
                            mapOf(
                                Role.WORK to
                                    mapOf(
                                        "x" to SeatDispatchOverride(model = "m1"),
                                        "y" to SeatDispatchOverride(cleared = setOf("agent"))
                                    )
                            ),
                        "t-item" to
                            mapOf(
                                Role.WORK to mapOf("x" to SeatDispatchOverride(model = "m2")),
                                Role.REVIEW to mapOf("ghost" to SeatDispatchOverride(agent = "G"))
                            )
                    )
            )
        val config = LayeredConfig(UUID.randomUUID(), null, globalLookup(doc))
        val schemaSeats = listOf(SeatDefinition("x", Role.WORK), SeatDefinition("y", Role.WORK))
        // dispatchTraitsFor order: item traits first, then defaultTraits, distinct.
        val traits = listOf("t-item", "t-def")

        val dispatch = config.mergeDispatch(traits)
        assertEquals(DispatchProfile(agent = "A", effort = "high"), dispatch[Role.WORK], "today's phase-default rule is unchanged")

        val byseat = config.mergeDispatchBySeat(traits, schemaSeats)
        assertEquals(setOf(Role.WORK), byseat.keys, "no REVIEW key: 'ghost' is not in the schema's merged seats")

        val work = byseat.getValue(Role.WORK)
        assertEquals(setOf("x", "y"), work.keys)
        assertEquals(
            DispatchProfile(agent = "A", model = "m2", effort = "high"),
            work["x"],
            "t-item claims x first; its model overlays the phase default's agent/effort"
        )
        assertEquals(
            DispatchProfile(agent = null, model = null, effort = "high"),
            work["y"],
            "only t-def declares y; its cleared agent nulls out, model/effort fall through the phase default"
        )
    }

    @Test
    fun `S7 a seat-less schema yields no dispatchBySeat entry even when the same traits declare seat overrides`() {
        val doc =
            ConfigDocument(
                workItemSchemas = emptyMap(),
                traits = emptyMap(),
                traitDispatchBySeat = mapOf("t-def" to mapOf(Role.WORK to mapOf("x" to SeatDispatchOverride(model = "m1"))))
            )
        val config = LayeredConfig(UUID.randomUUID(), null, globalLookup(doc))

        val byseat = config.mergeDispatchBySeat(listOf("t-def"), emptyList())

        assertTrue(byseat.isEmpty(), "no merged seats -> dispatchBySeat must be empty, not carry the trait's seat override")
    }

    // ──────────────────────────────────────────────
    // S8 — per-root layering: trait seats and the dispatch block (byRole+bySeat) are wholesale
    // per-trait units; base-schema seats travel with whichever layer supplied the base schema
    // ──────────────────────────────────────────────

    @Test
    fun `S8 a per-root trait declared with notes only excludes the trait's global seats wholesale`() {
        val globalDoc =
            ConfigDocument(
                workItemSchemas = emptyMap(),
                traits = mapOf("T" to listOf(NoteSchemaEntry(key = "global-note", role = Role.QUEUE))),
                traitSeats = mapOf("T" to listOf(SeatDefinition("s1", Role.WORK)))
            )
        val perRootDoc =
            ConfigDocument(
                workItemSchemas = emptyMap(),
                traits = mapOf("T" to listOf(NoteSchemaEntry(key = "per-root-note", role = Role.QUEUE)))
                // no traitSeats entry for T at all -> per-root's (empty) seats win wholesale
            )
        val config = LayeredConfig(UUID.randomUUID(), ConfigLayer(perRootDoc, "pr-fp", ConfigSource.PER_ROOT), globalLookup(globalDoc))

        assertEquals(
            emptyList(),
            config.traitSeats("T"),
            "per-root declares T (notes only), so its absent seats win wholesale over global's s1 -- same unit as trait notes"
        )
    }

    @Test
    fun `S8 a trait the per-root document does not declare at all falls through to the global trait's seats`() {
        val globalDoc =
            ConfigDocument(
                workItemSchemas = emptyMap(),
                traits = mapOf("T" to listOf(NoteSchemaEntry(key = "global-note", role = Role.QUEUE))),
                traitSeats = mapOf("T" to listOf(SeatDefinition("s1", Role.WORK)))
            )
        val perRootDoc = ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap())
        val config = LayeredConfig(UUID.randomUUID(), ConfigLayer(perRootDoc, "pr-fp", ConfigSource.PER_ROOT), globalLookup(globalDoc))

        assertEquals(listOf(SeatDefinition("s1", Role.WORK)), config.traitSeats("T"))
    }

    @Test
    fun `S8 a per-root trait supplying only a per-seat override replaces the whole dispatch block, phase-level profile included`() {
        val globalDoc =
            ConfigDocument(
                workItemSchemas = emptyMap(),
                traits = mapOf("T" to emptyList()),
                traitDispatch = mapOf("T" to mapOf(Role.WORK to DispatchProfile(agent = "GA"))),
                traitDispatchBySeat = mapOf("T" to mapOf(Role.WORK to mapOf("s1" to SeatDispatchOverride(agent = "GA-seat"))))
            )
        val perRootDoc =
            ConfigDocument(
                workItemSchemas = emptyMap(),
                traits = mapOf("T" to emptyList()),
                traitDispatchBySeat = mapOf("T" to mapOf(Role.WORK to mapOf("s1" to SeatDispatchOverride(model = "p"))))
                // no traitDispatch entry for T -> the whole block (byRole+bySeat) is per-root's
            )
        val config = LayeredConfig(UUID.randomUUID(), ConfigLayer(perRootDoc, "pr-fp", ConfigSource.PER_ROOT), globalLookup(globalDoc))

        assertTrue(
            config.traitDispatch("T").isEmpty(),
            "per-root supplies T's dispatch block (bySeat only), so global's phase-level profile must not leak through"
        )

        val byseat = config.mergeDispatchBySeat(listOf("T"), listOf(SeatDefinition("s1", Role.WORK)))
        assertEquals(
            DispatchProfile(model = "p"),
            byseat[Role.WORK]?.get("s1"),
            "global's agent must not leak in; only per-root's s1 override applies, over an empty phase default"
        )
    }

    @Test
    fun `S8 base-schema seats travel with whichever layer supplied the base schema -- no cross-layer seat merge`() {
        val globalDoc =
            ConfigDocument(
                workItemSchemas = mapOf("default" to WorkItemSchema(type = "default", seats = listOf(SeatDefinition("g1", Role.WORK)))),
                traits = emptyMap()
            )

        val perRootDocLegacy =
            ConfigDocument(
                workItemSchemas = mapOf("default" to WorkItemSchema(type = "default", seats = listOf(SeatDefinition("p1", Role.WORK)))),
                traits = emptyMap(),
                schemaResolution = SchemaResolutionMode.LEGACY
            )
        val legacyConfig =
            LayeredConfig(UUID.randomUUID(), ConfigLayer(perRootDocLegacy, "pr-fp", ConfigSource.PER_ROOT), globalLookup(globalDoc))
        val legacyResult = legacyConfig.resolveBaseSchema("unknown", emptyList())!!
        assertEquals(ConfigSource.PER_ROOT, legacyResult.source)
        assertEquals(
            listOf(SeatDefinition("p1", Role.WORK)),
            legacyResult.schema.seats,
            "LEGACY resolves the per-root default; its seats must not merge with global's g1"
        )

        val perRootDocLayered =
            ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap(), schemaResolution = SchemaResolutionMode.LAYERED)
        val layeredConfig =
            LayeredConfig(UUID.randomUUID(), ConfigLayer(perRootDocLayered, "pr-fp", ConfigSource.PER_ROOT), globalLookup(globalDoc))
        val layeredResult = layeredConfig.resolveBaseSchema("default", emptyList())!!
        assertEquals(ConfigSource.GLOBAL, layeredResult.source)
        assertEquals(listOf(SeatDefinition("g1", Role.WORK)), layeredResult.schema.seats)
    }

    // ──────────────────────────────────────────────
    // S9 — a per-item trait cannot redefine a base seat's phase/enters; a second `enters:true` in
    // the same phase is demoted; resolution does not throw
    // ──────────────────────────────────────────────

    @Test
    fun `S9 a per-item trait redefining a base seat by name is dropped -- base phase and enters survive, resolution does not throw`() {
        val baseSchema = WorkItemSchema(type = "bug-fix", seats = listOf(SeatDefinition("implementer", Role.WORK, enters = true)))
        val doc =
            ConfigDocument(
                workItemSchemas = mapOf("bug-fix" to baseSchema),
                traits = mapOf("t1" to emptyList()),
                traitSeats =
                    mapOf(
                        "t1" to
                            listOf(
                                SeatDefinition("implementer", Role.REVIEW),
                                SeatDefinition("z", Role.WORK, enters = true)
                            )
                    )
            )
        val config = LayeredConfig(UUID.randomUUID(), null, globalLookup(doc))
        val item = WorkItem(title = "S9 item", type = "bug-fix", role = Role.WORK, depth = 0, properties = """{"traits":["t1"]}""")

        val merged = config.mergeTraits(item, baseSchema)

        val implementer = merged.seats.single { it.name == "implementer" }
        assertEquals(Role.WORK, implementer.phase, "a trait may add seats but not redefine a base seat -- the base phase wins")
        assertTrue(implementer.enters, "the base seat's enters must survive; the trait's redefinition is dropped, not merged in")

        val z = merged.seats.single { it.name == "z" }
        assertFalse(z.enters, "a second enters:true seat in the same phase (work) is demoted to false, not thrown")

        assertEquals(listOf("implementer", "z"), merged.seats.map { it.name }, "base seats first, then trait seats, dropped-name aside")
    }

    // ──────────────────────────────────────────────
    // S2 (resolver half) — resolveSchema's merged seat order is base seats, then trait seats,
    // exercised through EffectiveConfigResolver rather than LayeredConfig.mergeTraits directly, so
    // a mutation that merges trait seats before base seats reddens even when the names don't
    // collide (task-scope §2 example order; task-scope §4 merged-order row; red: M14).
    // ──────────────────────────────────────────────

    @Test
    fun `S2 resolveSchema merges base seats before trait seats, non-overlapping names, via EffectiveConfigResolver`(): Unit =
        runBlocking {
            val doc =
                ConfigDocument(
                    workItemSchemas =
                        mapOf(
                            "bug-fix" to
                                WorkItemSchema(
                                    type = "bug-fix",
                                    defaultTraits = listOf("t-def"),
                                    seats = listOf(SeatDefinition("a", Role.WORK), SeatDefinition("b", Role.WORK))
                                )
                        ),
                    traits = mapOf("t-def" to emptyList()),
                    traitSeats = mapOf("t-def" to listOf(SeatDefinition("c", Role.WORK), SeatDefinition("d", Role.WORK)))
                )
            val resolver = EffectiveConfigResolver(globalLookup(doc), perRoot = null)
            val item = WorkItem(title = "S2 item", type = "bug-fix", role = Role.WORK, depth = 0)

            val resolved = resolver.resolveSchema(item)!!

            assertEquals(
                listOf("a", "b", "c", "d"),
                resolved.seats.map { it.name },
                "merged order must be base seats first, then trait seats -- a mutation merging trait seats first must redden this"
            )
        }

    // ──────────────────────────────────────────────
    // S7 (resolver half) — resolveDispatchBySeat's item-traits-first-then-defaultTraits walk order
    // (dispatchTraitsFor), exercised through EffectiveConfigResolver rather than
    // LayeredConfig.mergeDispatchBySeat with a pre-ordered list, so a mutation that walks
    // defaultTraits before item traits reddens (task-scope §4; test-plan S7; red: M4b).
    // ──────────────────────────────────────────────

    @Test
    fun `S7 resolveDispatchBySeat walks item traits before defaultTraits -- the item trait's override wins`(): Unit =
        runBlocking {
            val doc =
                ConfigDocument(
                    workItemSchemas = emptyMap(),
                    traits = mapOf("t-item" to emptyList(), "t-def" to emptyList()),
                    traitDispatchBySeat =
                        mapOf(
                            "t-item" to mapOf(Role.WORK to mapOf("x" to SeatDispatchOverride(model = "m2"))),
                            "t-def" to mapOf(Role.WORK to mapOf("x" to SeatDispatchOverride(model = "m1")))
                        )
                )
            val resolver = EffectiveConfigResolver(globalLookup(doc), perRoot = null)
            val item = WorkItem(title = "S7 item", type = "bug-fix", role = Role.WORK, depth = 0, properties = """{"traits":["t-item"]}""")
            val resolvedSchema =
                WorkItemSchema(type = "bug-fix", defaultTraits = listOf("t-def"), seats = listOf(SeatDefinition("x", Role.WORK)))

            val byseat = resolver.resolveDispatchBySeat(item, resolvedSchema)

            assertEquals(
                "m2",
                byseat[Role.WORK]?.get("x")?.model,
                "item trait t-item must be walked before defaultTraits t-def and claim the (work, x) pair first"
            )
        }
}
