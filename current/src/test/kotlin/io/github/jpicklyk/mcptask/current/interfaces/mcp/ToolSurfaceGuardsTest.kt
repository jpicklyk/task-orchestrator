package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.ClaimItemTool
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSchemaRenderer
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import io.github.jpicklyk.mcptask.current.test.tools.ToolGoldens
import io.github.jpicklyk.mcptask.current.test.tools.ToolSurfaceChecks
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 713c5c61) for the registry halves of the guards, scenarios S15 (token budget),
 * S17 (documentation) and S18 (names disjoint from `buildMcpTools()`) of the frozen test-plan. S18's second clause (the 3.x
 * guard tests stay green) is verified by running `ToolTokenBudgetTest`, `ToolDocumentationConsistencyTest` and `LayeringTest`
 * unchanged.
 *
 * Oracles: task-scope D11 (budgets measure the compact `tools/list` entry of `toolFor(spec, profile)`: per tool at most 8,000
 * characters in every profile, knowledge total at most 22,000, orchestration total at most 38,000, over the registry only), D12
 * (every property at every depth, including `ops.items` and nested classes, has a non-blank description; every rendered tool
 * description contains `DESCRIBE_HINT`) and the Contract note that startup fails when a registry name equals a 3.x tool name.
 *
 * The measuring and checking functions are the test-support [ToolSurfaceChecks]; boundary behaviour is proved on fixture tools
 * padded to an exact measured size, so a `<` versus `<=` mistake or a wrong-profile rendering changes a result here.
 */
class ToolSurfaceGuardsTest {
    @Serializable
    data class Nested(
        val x: Int
    )

    @Serializable
    data class DeepReq(
        @Doc("Outer field.") val inner: Nested
    )

    @Serializable
    data class NakedReq(
        val bare: Int
    )

    private fun padded(
        name: String,
        targetChars: Int,
        profile: ToolProfile = ToolProfile.KNOWLEDGE
    ): OpsToolSpec<String> {
        fun build(descriptionLength: Int) =
            OpsToolSpec<String>(
                name = name,
                description = "x".repeat(descriptionLength),
                ops =
                    listOf(
                        OpSpec<FixtureTools.CreateReq, String>(
                            name = "make",
                            doc = "Make.",
                            profile = profile,
                            capability = ApiCapability.WRITE_ITEMS,
                            request = serializer<FixtureTools.CreateReq>(),
                            execute = { _, _, _ -> Outcome.Ok("x") }
                        )
                    ),
                codec = FixtureTools.StringCodec,
                element = { buildJsonObject { } }
            )
        val base = ToolGoldens.compact(McpOperationAdapter.toolFor(build(0), profile)!!).length
        val spec = build(targetChars - base)
        val measured = ToolGoldens.compact(McpOperationAdapter.toolFor(spec, profile)!!).length
        assertEquals(targetChars, measured, "fixture sizing: the padded tool must measure exactly the target")
        return spec
    }

    private fun registryOf(vararg specs: ToolSpec) = ToolRegistry(specs.toList())

    // ------------------------------------------------------------------ S15 per tool

    @Test
    fun `S15 a tool of exactly 8000 characters and one of 7999 pass`() {
        assertEquals(emptyList(), ToolSurfaceChecks.budgetViolations(registryOf(padded("pad_tool", 8_000))))
        assertEquals(emptyList(), ToolSurfaceChecks.budgetViolations(registryOf(padded("pad_tool", 7_999))))
    }

    @Test
    fun `S15 a tool of 8001 characters is flagged by name and profile`() {
        val violations = ToolSurfaceChecks.budgetViolations(registryOf(padded("pad_tool", 8_001)))
        assertEquals(2, violations.size, "the tool is visible in both profiles, once per profile: $violations")
        assertTrue(violations.all { "pad_tool" in it }, violations.toString())
        assertTrue(violations.any { "knowledge" in it } && violations.any { "orchestration" in it }, violations.toString())
    }

    @Test
    fun `S15 an orchestration-only tool over the ceiling is flagged for orchestration only`() {
        val violations = ToolSurfaceChecks.budgetViolations(registryOf(padded("pad_tool", 8_001, ToolProfile.ORCHESTRATION)))
        assertEquals(1, violations.size, violations.toString())
        assertTrue("orchestration" in violations.single(), violations.single())
        assertEquals(
            emptyMap(),
            ToolSurfaceChecks.sizes(registryOf(padded("pad_tool", 2_000, ToolProfile.ORCHESTRATION)), ToolProfile.KNOWLEDGE)
        )
    }

    // ------------------------------------------------------------------ S15 totals

    @Test
    fun `S15 a knowledge total of 22000 passes and 22001 is flagged while orchestration stays within its ceiling`() {
        val ok = registryOf(padded("pad_a", 7_333), padded("pad_b", 7_333), padded("pad_c", 7_334))
        assertEquals(22_000, ToolSurfaceChecks.sizes(ok, ToolProfile.KNOWLEDGE).values.sum())
        assertEquals(emptyList(), ToolSurfaceChecks.budgetViolations(ok))

        val over = registryOf(padded("pad_a", 7_333), padded("pad_b", 7_334), padded("pad_c", 7_334))
        assertEquals(22_001, ToolSurfaceChecks.sizes(over, ToolProfile.KNOWLEDGE).values.sum())
        val violations = ToolSurfaceChecks.budgetViolations(over)
        assertEquals(1, violations.size, violations.toString())
        assertTrue("total" in violations.single() && "knowledge" in violations.single(), violations.single())
        assertTrue(ToolSurfaceChecks.sizes(over, ToolProfile.ORCHESTRATION).values.sum() <= ToolSurfaceChecks.ORCHESTRATION_TOTAL_CEILING)
    }

    @Test
    fun `S15 an orchestration total of 38000 passes and 38001 is flagged`() {
        val ok = registryOf(*(1..5).map { padded("pad_$it", 7_600, ToolProfile.ORCHESTRATION) }.toTypedArray())
        assertEquals(38_000, ToolSurfaceChecks.sizes(ok, ToolProfile.ORCHESTRATION).values.sum())
        assertEquals(emptyList(), ToolSurfaceChecks.budgetViolations(ok))

        val sizes = listOf(7_600, 7_600, 7_600, 7_600, 7_601)
        val over =
            registryOf(*sizes.mapIndexed { i, size -> padded("pad_" + ('a' + i), size, ToolProfile.ORCHESTRATION) }.toTypedArray())
        assertEquals(38_001, ToolSurfaceChecks.sizes(over, ToolProfile.ORCHESTRATION).values.sum())
        val violations = ToolSurfaceChecks.budgetViolations(over)
        assertEquals(1, violations.size, violations.toString())
        assertTrue("total" in violations.single() && "orchestration" in violations.single(), violations.single())
    }

    @Test
    fun `S15 knowledge is measured on the knowledge rendering, which is smaller than the orchestration one`() {
        val registry = FixtureTools().registry
        val knowledge = ToolSurfaceChecks.sizes(registry, ToolProfile.KNOWLEDGE)
        val orchestration = ToolSurfaceChecks.sizes(registry, ToolProfile.ORCHESTRATION)
        assertEquals(setOf("fx_read", "fx_write"), knowledge.keys)
        assertEquals(setOf("fx_read", "fx_peek", "fx_write", "fx_admin"), orchestration.keys)
        assertTrue(
            knowledge.getValue("fx_read") < orchestration.getValue("fx_read"),
            "audit adds schema to orchestration: $knowledge vs $orchestration"
        )
        assertTrue(
            knowledge.getValue("fx_write") < orchestration.getValue("fx_write"),
            "purge adds schema to orchestration: $knowledge vs $orchestration"
        )
    }

    @Test
    fun `S15 the fixture registry and the production registry are within budget`() {
        assertEquals(emptyList(), ToolSurfaceChecks.budgetViolations(FixtureTools().registry))
        assertEquals(emptyList(), ToolSurfaceChecks.budgetViolations(ToolRegistry(buildToolSpecs())))
    }

    @Test
    fun `S15 the ceilings are the frozen numbers`() {
        assertEquals(8_000, ToolSurfaceChecks.PER_TOOL_CEILING)
        assertEquals(22_000, ToolSurfaceChecks.KNOWLEDGE_TOTAL_CEILING)
        assertEquals(38_000, ToolSurfaceChecks.ORCHESTRATION_TOTAL_CEILING)
    }

    // ------------------------------------------------------------------ S17 documentation

    @Test
    fun `S17 control - the fixture registry and the production registry have no documentation fault`() {
        assertEquals(emptyList(), ToolSurfaceChecks.docViolations(FixtureTools().registry))
        assertEquals(emptyList(), ToolSurfaceChecks.docViolations(ToolRegistry(buildToolSpecs())))
    }

    @Test
    fun `S17 the walk reaches ops items and nested class properties`() {
        val deep =
            OpsToolSpec<String>(
                name = "deep_tool",
                description = "Deep.",
                ops =
                    listOf(
                        OpSpec<DeepReq, String>(
                            name = "dig",
                            doc = "Dig.",
                            capability = ApiCapability.WRITE_ITEMS,
                            request = serializer<DeepReq>(),
                            execute = {
                                _,
                                _,
                                _
                                ->
                                Outcome.Ok("x")
                            }
                        )
                    ),
                codec = FixtureTools.StringCodec,
                element = { buildJsonObject { } }
            )
        val schema = ToolSchemaRenderer.inputSchema(deep, ToolProfile.KNOWLEDGE)!!
        val paths = ToolSurfaceChecks.propertyPaths(schema)
        assertTrue("/ops[]/inner/x" in paths, "the nested property must be present in the rendered schema: $paths")
        assertTrue("/ops[]/op" in paths && "/ops[]/idempotencyKey" in paths && "/describe" in paths && "/atomic" in paths, paths.toString())
    }

    @Test
    fun `S17 an undocumented nested property is flagged with its path`() {
        val deep =
            OpsToolSpec<String>(
                name = "deep_tool",
                description = "Deep.",
                ops =
                    listOf(
                        OpSpec<DeepReq, String>(
                            name = "dig",
                            doc = "Dig.",
                            capability = ApiCapability.WRITE_ITEMS,
                            request = serializer<DeepReq>(),
                            execute = {
                                _,
                                _,
                                _
                                ->
                                Outcome.Ok("x")
                            }
                        )
                    ),
                codec = FixtureTools.StringCodec,
                element = { buildJsonObject { } }
            )
        val violations = ToolSurfaceChecks.docViolations(registryOf(deep))
        assertTrue(violations.isNotEmpty(), "Nested.x carries no @Doc")
        assertTrue(violations.all { "/ops[]/inner/x" in it }, violations.toString())
    }

    @Test
    fun `S17 an undocumented top-level request field is flagged for both tool kinds`() {
        val ops =
            OpsToolSpec<String>(
                name = "naked_ops",
                description = "Naked.",
                ops =
                    listOf(
                        OpSpec<NakedReq, String>(
                            name = "go",
                            doc = "Go.",
                            capability = ApiCapability.WRITE_ITEMS,
                            request = serializer<NakedReq>(),
                            execute = {
                                _,
                                _,
                                _
                                ->
                                Outcome.Ok("x")
                            }
                        )
                    ),
                codec = FixtureTools.StringCodec,
                element = { buildJsonObject { } }
            )
        val select =
            SelectToolSpec(
                "naked_sel",
                "Naked.",
                listOf(
                    SelectSpec.Single<NakedReq, String>(
                        name = "go",
                        doc = "Go.",
                        request = serializer<NakedReq>(),
                        handler = { _, _, _ -> Outcome.Ok("x") },
                        data = { JsonPrimitive(it) }
                    )
                )
            )
        val violations = ToolSurfaceChecks.docViolations(registryOf(ops, select))
        assertTrue(violations.any { "naked_ops" in it && "/ops[]/bare" in it }, violations.toString())
        assertTrue(violations.any { "naked_sel" in it && "/bare" in it }, violations.toString())
    }

    @Test
    fun `S17 every rendered tool description carries the describe hint in every profile`() {
        val registry = FixtureTools().registry
        for (spec in registry.tools) {
            assertTrue(ToolSchemaRenderer.DESCRIBE_HINT in ToolSchemaRenderer.description(spec), spec.name)
            for (profile in ToolProfile.entries) {
                val tool = McpOperationAdapter.toolFor(spec, profile) ?: continue
                assertTrue(ToolSchemaRenderer.DESCRIBE_HINT in tool.description.orEmpty(), "${spec.name}/$profile")
            }
        }
    }

    // ------------------------------------------------------------------ S18

    // The production 3.x list is empty since Q21/Q23; the test-only 3.x drivers keep the guard checked against real names.
    private val legacyTools = buildMcpTools() + listOf(AdvanceItemTool(), ClaimItemTool())

    @Test
    fun `S18 the fixture and production registries are disjoint from the 3x tools`() {
        val legacyNames = legacyTools.map { it.name }.toSet()
        assertTrue(legacyNames.isNotEmpty(), "control: the 3.x tool list is not empty")
        assertEquals(
            emptySet(),
            FixtureTools()
                .registry.tools
                .map { it.name }
                .toSet() intersect legacyNames
        )
        assertEquals(emptySet(), buildToolSpecs().map { it.name }.toSet() intersect legacyNames)
        checkRegistryDisjoint(FixtureTools().registry, legacyTools)
        checkRegistryDisjoint(ToolRegistry(buildToolSpecs()), legacyTools)
    }

    @Test
    fun `S18 a registry tool named like a 3x tool fails startup naming the tool`() {
        for (legacy in legacyTools.map { it.name }) {
            val clash =
                OpsToolSpec<String>(
                    name = legacy,
                    description = "Clash.",
                    ops =
                        listOf(
                            OpSpec<FixtureTools.CreateReq, String>(
                                name = "make",
                                doc = "Make.",
                                capability = ApiCapability.WRITE_ITEMS,
                                request = serializer<FixtureTools.CreateReq>(),
                                execute = { _, _, _ -> Outcome.Ok("x") }
                            )
                        ),
                    codec = FixtureTools.StringCodec,
                    element = { buildJsonObject { } }
                )
            val failure =
                assertFails(
                    "a registry tool named '$legacy' must be refused"
                ) { checkRegistryDisjoint(ToolRegistry(listOf(clash)), legacyTools) }
            assertTrue(legacy in failure.message.orEmpty(), "the failure must name '$legacy': ${failure.message}")
        }
    }

    @Test
    fun `S18 a clash is found among several non-clashing registry tools`() {
        val fx = FixtureTools()
        val clash = SelectToolSpec("claim_item", "Clash.", fx.readTool.selects)
        val failure = assertFails { checkRegistryDisjoint(ToolRegistry(listOf(fx.readTool, fx.writeTool, clash)), legacyTools) }
        assertTrue("claim_item" in failure.message.orEmpty(), failure.message)
    }
}
