package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.request.Doc
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.OpsToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.SelectSpec
import io.github.jpicklyk.mcptask.current.platform.tool.SelectToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.ToolProfile
import io.github.jpicklyk.mcptask.current.platform.tool.ToolSchemaRenderer
import io.github.jpicklyk.mcptask.current.platform.tool.ToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.director
import io.github.jpicklyk.mcptask.current.platform.tool.knowledge
import io.github.jpicklyk.mcptask.current.platform.tool.orchestration
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import io.github.jpicklyk.mcptask.current.test.tools.ToolGoldens
import io.github.jpicklyk.mcptask.current.test.tools.ToolSurfaceChecks
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 713c5c61) for the registry halves of the guards, scenarios S15 (token budget)
 * and S17 (documentation) of the frozen test-plan. S18 (names disjoint from the 3.x tool list) was retired by Q32 with the
 * 3.x tools themselves: there is no second tool list left to clash with. These two scenarios are the registry's only
 * budget and documentation guards (the 3.x ToolTokenBudgetTest and ToolDocumentationConsistencyTest are gone).
 *
 * Oracles: task-scope D11 (budgets measure the compact `tools/list` entry of `toolFor(spec, profile)`: per tool at most 9,000
 * characters in every profile, over the registry only; the knowledge, orchestration and director total ceilings were removed
 * by B1 stage 2, owner ruling 2026-11-27), D12
 * (every property at every depth, including `ops.items` and nested classes, has a non-blank description; every rendered tool
 * description contains `DESCRIBE_HINT`).
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
        profile: ToolProfile = ToolProfile.KNOWLEDGE,
        visibleIn: Set<ToolProfile>? = null
    ): OpsToolSpec<String> {
        fun build(descriptionLength: Int) =
            OpsToolSpec<String>(
                name = name,
                description = "x".repeat(descriptionLength),
                ops =
                    listOf(
                        OpSpec<ToolExecutionContext, FixtureTools.CreateReq, String, RepositoryProvider>(
                            name = "make",
                            doc = "Make.",
                            // R5 mechanical migration: the pre-R5 single-profile declaration as its profile set.
                            profiles =
                                visibleIn
                                    ?: when (profile) {
                                        ToolProfile.KNOWLEDGE -> knowledge()
                                        ToolProfile.ORCHESTRATION -> orchestration()
                                        ToolProfile.DIRECTOR -> director()
                                    },
                            capability = ApiCapability.WRITE_ITEMS,
                            services = ToolExecutionContext::class,
                            stores = RepositoryProvider::class,
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
    fun `S15 a tool of exactly 9000 characters and one of 8999 pass`() {
        // Each profile's boundary is proved with a fixture padded on that profile's own rendering: the orchestration
        // rendering is larger than the knowledge one (it adds the top-level seatToken property), so one fixture visible
        // in both profiles cannot measure exactly 9000 on both.
        for (size in listOf(9_000, 8_999)) {
            val bothProfiles = registryOf(padded("pad_tool", size, ToolProfile.ORCHESTRATION, visibleIn = knowledge()))
            assertEquals(size, ToolSurfaceChecks.sizes(bothProfiles, ToolProfile.ORCHESTRATION).getValue("pad_tool"))
            assertTrue(ToolSurfaceChecks.sizes(bothProfiles, ToolProfile.KNOWLEDGE).getValue("pad_tool") <= size)
            assertEquals(emptyList(), ToolSurfaceChecks.budgetViolations(bothProfiles), "orchestration-measured $size")

            val knowledgeOnly = registryOf(padded("pad_tool", size, ToolProfile.KNOWLEDGE, visibleIn = setOf(ToolProfile.KNOWLEDGE)))
            assertEquals(size, ToolSurfaceChecks.sizes(knowledgeOnly, ToolProfile.KNOWLEDGE).getValue("pad_tool"))
            assertEquals(emptyMap(), ToolSurfaceChecks.sizes(knowledgeOnly, ToolProfile.ORCHESTRATION))
            assertEquals(emptyList(), ToolSurfaceChecks.budgetViolations(knowledgeOnly), "knowledge-measured $size")
        }
    }

    @Test
    fun `S15 a tool of 9001 characters is flagged by name and profile`() {
        val violations = ToolSurfaceChecks.budgetViolations(registryOf(padded("pad_tool", 9_001)))
        assertEquals(2, violations.size, "the tool is visible in both profiles, once per profile: $violations")
        assertTrue(violations.all { "pad_tool" in it }, violations.toString())
        assertTrue(violations.any { "knowledge" in it } && violations.any { "orchestration" in it }, violations.toString())
    }

    @Test
    fun `S15 an orchestration-only tool over the ceiling is flagged for orchestration only`() {
        val violations = ToolSurfaceChecks.budgetViolations(registryOf(padded("pad_tool", 9_001, ToolProfile.ORCHESTRATION)))
        assertEquals(1, violations.size, violations.toString())
        assertTrue("orchestration" in violations.single(), violations.single())
        assertEquals(
            emptyMap(),
            ToolSurfaceChecks.sizes(registryOf(padded("pad_tool", 2_000, ToolProfile.ORCHESTRATION)), ToolProfile.KNOWLEDGE)
        )
    }

    @Test
    fun `S15 a director tool of 9001 characters is flagged by name for director`() {
        val violations = ToolSurfaceChecks.budgetViolations(registryOf(padded("pad_tool", 9_001, ToolProfile.DIRECTOR)))
        assertEquals(1, violations.size, violations.toString())
        assertTrue("pad_tool" in violations.single() && "director" in violations.single(), violations.single())
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
    fun `S15 the per-tool ceiling is the frozen number`() {
        assertEquals(9_000, ToolSurfaceChecks.PER_TOOL_CEILING)
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
                        OpSpec<ToolExecutionContext, DeepReq, String, RepositoryProvider>(
                            name = "dig",
                            doc = "Dig.",
                            capability = ApiCapability.WRITE_ITEMS,
                            profiles = knowledge(),
                            services = ToolExecutionContext::class,
                            stores = RepositoryProvider::class,
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
                        OpSpec<ToolExecutionContext, DeepReq, String, RepositoryProvider>(
                            name = "dig",
                            doc = "Dig.",
                            capability = ApiCapability.WRITE_ITEMS,
                            profiles = knowledge(),
                            services = ToolExecutionContext::class,
                            stores = RepositoryProvider::class,
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
                        OpSpec<ToolExecutionContext, NakedReq, String, RepositoryProvider>(
                            name = "go",
                            doc = "Go.",
                            capability = ApiCapability.WRITE_ITEMS,
                            profiles = knowledge(),
                            services = ToolExecutionContext::class,
                            stores = RepositoryProvider::class,
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
                    SelectSpec.Single<ToolExecutionContext, NakedReq, String>(
                        name = "go",
                        doc = "Go.",
                        profiles = knowledge(),
                        services = ToolExecutionContext::class,
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
}
