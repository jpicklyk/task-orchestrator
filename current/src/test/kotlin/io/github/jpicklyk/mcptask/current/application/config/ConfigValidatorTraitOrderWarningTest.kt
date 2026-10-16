package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.infrastructure.config.NetworkntConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item acb209e0) for the `default_traits` order advisory of [ConfigValidator]: a WARN
 * (never an error) when, for some phase and seat, the earliest override in a schema's `default_traits` list is not
 * model-selecting while a later trait's override is.
 *
 * Oracles (frozen `test-plan` / `task-scope` notes): DEF = task-scope "Change" item 2 (one WARN at
 * `/work_item_schemas/<t>/default_traits/<later index>` per (phase, seat), whose reason names both traits and the seat;
 * duplicates count at their first position; trait definitions come per-root first, then the server layer; warnings never
 * reject a document); RFC 6901 for the pointer syntax. Scenario ids S9..S13 are the test-plan ids; every scenario is
 * EXISTING-SURFACE. S14 (zero warnings on the dogfood fixture) is the pre-existing `ConfigValidatorTest` S1, not repeated here.
 *
 * The warning's wording is not declared beyond "names both traits and the seat", so reasons are checked with `contains`.
 */
class ConfigValidatorTraitOrderWarningTest {
    private val delegated =
        """
          delegated:
            dispatch:
              queue:
                seats:
                  planner: { model: opus }
              work:
                agent: task-orchestrator:implementer
                seats:
                  implementer:  { model: sonnet }
                  test-author:  { agent: null, model: sonnet }
                  orchestrator: { agent: null }
              review:
                agent: task-orchestrator:reviewer
                effort: high
                seats:
                  reviewer: { model: opus }
        """

    private val complexImplementation =
        """
          complex-implementation:
            dispatch:
              work:
                seats:
                  implementer: { model: opus }
        """

    private val highStakes =
        """
          high-stakes:
            dispatch:
              queue:
                seats:
                  planner: { model: fable }
              review:
                seats:
                  reviewer: { model: fable }
        """

    private val plainTrait =
        """
          plain-trait:
            notes:
              - key: plain-note
                role: work
                required: false
        """

    private fun traitsBlock(vararg traits: String): String =
        "traits:\n" + traits.joinToString("\n") { it.trimIndent().prependIndent("  ") } + "\n"

    private fun schemaBlock(defaultTraits: String?): String =
        "work_item_schemas:\n" +
            "  t:\n" +
            "    lifecycle: auto\n" +
            (defaultTraits?.let { "    default_traits: $it\n" } ?: "") +
            "    seats:\n" +
            "      - { name: planner,      phase: queue }\n" +
            "      - { name: implementer,  phase: work, enters: true }\n" +
            "      - { name: test-author,  phase: work, after: [implementer] }\n" +
            "      - { name: orchestrator, phase: work, after: [implementer] }\n" +
            "      - { name: reviewer,     phase: review }\n" +
            "    notes:\n" +
            "      - key: spec\n" +
            "        role: queue\n" +
            "        required: true\n" +
            "        seat: planner\n"

    private fun serverLayer(yaml: String): ConfigLayer {
        val parsed = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)
        check(parsed is ConfigDocumentParser.Outcome.Parsed) { "server-layer fixture must parse: $parsed" }
        return ConfigLayer(parsed.document, "server-fp", ConfigSource.GLOBAL)
    }

    private fun validate(
        yaml: String,
        server: ConfigLayer? = null
    ): ConfigValidationResult =
        ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { server })
            .validate(yaml, YamlConfigDocumentParser, ConfigScope.ROOT)

    private fun orderWarnings(result: ConfigValidationResult): List<ConfigViolation> =
        result.warnings.filter { it.path.startsWith("/work_item_schemas/t/default_traits") }

    private fun docWith(
        defaultTraits: String?,
        vararg traits: String
    ): String = traitsBlock(*traits) + schemaBlock(defaultTraits)

    // ---------------------------------------------------------------------------------------------
    // S9 - [delegated, complex-implementation] warns once, at the later trait's index
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 delegated before complex-implementation yields one warning naming both traits and the seat, and no error`() {
        val result = validate(docWith("[delegated, complex-implementation]", delegated, complexImplementation))

        assertEquals(emptyList(), result.errors, "the advisory is a warning, never an error")
        assertTrue(result.isValid)
        val warnings = orderWarnings(result)
        assertEquals(1, warnings.size, "warnings: ${result.warnings}")
        assertEquals("/work_item_schemas/t/default_traits/1", warnings.single().path)
        val reason = warnings.single().reason
        assertTrue("delegated" in reason, reason)
        assertTrue("complex-implementation" in reason, reason)
        assertTrue("implementer" in reason, reason)
    }

    // ---------------------------------------------------------------------------------------------
    // S10 - the reverse order is silent
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S10 complex-implementation before delegated yields no warning`() {
        val result = validate(docWith("[complex-implementation, delegated]", delegated, complexImplementation))

        assertEquals(emptyList(), result.errors)
        assertEquals(emptyList(), result.warnings)
    }

    // ---------------------------------------------------------------------------------------------
    // S11 - high-stakes claims two seats: one warning per seat, both at the later index
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 delegated before high-stakes yields one warning for the planner and one for the reviewer`() {
        val result = validate(docWith("[delegated, high-stakes]", delegated, highStakes))

        assertEquals(emptyList(), result.errors)
        val warnings = orderWarnings(result)
        assertEquals(2, warnings.size, "warnings: ${result.warnings}")
        assertEquals(setOf("/work_item_schemas/t/default_traits/1"), warnings.map { it.path }.toSet())
        assertEquals(1, warnings.count { "planner" in it.reason }, "warnings: $warnings")
        assertEquals(1, warnings.count { "reviewer" in it.reason }, "warnings: $warnings")
        assertTrue(warnings.all { "delegated" in it.reason && "high-stakes" in it.reason }, "warnings: $warnings")
    }

    // ---------------------------------------------------------------------------------------------
    // S12 - a later trait that is not model-selecting does not warn
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S12 a later seat-only trait that sets agent, or a trait with no dispatch, yields no warning`() {
        val agentSetting =
            """
              agent-setting:
                dispatch:
                  work:
                    seats:
                      implementer: { agent: custom-agent, model: opus }
            """

        val setsAgent = validate(docWith("[delegated, agent-setting]", delegated, agentSetting))
        val noDispatch = validate(docWith("[delegated, plain-trait]", delegated, plainTrait))

        assertEquals(emptyList(), setsAgent.errors)
        assertEquals(emptyList(), setsAgent.warnings, "an override that sets the agent is not model-selecting")
        assertEquals(emptyList(), noDispatch.errors)
        assertEquals(emptyList(), noDispatch.warnings)
    }

    // ---------------------------------------------------------------------------------------------
    // S13 - trait definitions that live only in the server layer count
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S13 traits defined only in the server layer still produce the warning`() {
        val server = serverLayer(traitsBlock(delegated, complexImplementation))

        val result = validate(schemaBlock("[delegated, complex-implementation]"), server)

        assertEquals(emptyList(), result.errors)
        val warnings = orderWarnings(result)
        assertEquals(1, warnings.size, "warnings: ${result.warnings}")
        assertEquals("/work_item_schemas/t/default_traits/1", warnings.single().path)
    }

    // ---------------------------------------------------------------------------------------------
    // Probes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `probe a duplicate delegated counts at its first position`() {
        val result = validate(docWith("[delegated, complex-implementation, delegated]", delegated, complexImplementation))

        val warnings = orderWarnings(result)
        assertEquals(1, warnings.size, "warnings: ${result.warnings}")
        assertEquals("/work_item_schemas/t/default_traits/1", warnings.single().path)
    }

    @Test
    fun `probe the pointer index is the later trait's position in the list`() {
        val result = validate(docWith("[plain-trait, delegated, complex-implementation]", delegated, complexImplementation, plainTrait))

        val warnings = orderWarnings(result)
        assertEquals(1, warnings.size, "warnings: ${result.warnings}")
        assertEquals("/work_item_schemas/t/default_traits/2", warnings.single().path)
    }

    @Test
    fun `probe an empty default_traits list and an absent one yield no warning`() {
        val empty = validate(docWith("[]", delegated, complexImplementation))
        val absent = validate(docWith(null, delegated, complexImplementation))

        assertEquals(emptyList(), empty.errors)
        assertEquals(emptyList(), empty.warnings)
        assertEquals(emptyList(), absent.errors)
        assertEquals(emptyList(), absent.warnings)
    }

    @Test
    fun `probe a per-root redefinition with a phase-level profile is not seat-only, so no warning`() {
        val server = serverLayer(traitsBlock(delegated, complexImplementation))
        val perRootComplex =
            """
              complex-implementation:
                dispatch:
                  work:
                    effort: low
                    seats:
                      implementer: { model: opus }
            """

        val result = validate(traitsBlock(perRootComplex) + schemaBlock("[delegated, complex-implementation]"), server)

        assertEquals(emptyList(), result.errors)
        assertEquals(emptyList(), result.warnings, "the per-root definition wins and has a phase-level profile")
    }

    @Test
    fun `probe a seat-only override with a null model is not model-selecting, so no warning`() {
        val noModel =
            """
              no-model:
                dispatch:
                  work:
                    seats:
                      implementer: { model: null, effort: low }
            """

        val result = validate(docWith("[delegated, no-model]", delegated, noModel))

        assertEquals(emptyList(), result.errors)
        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun `probe a wrong-case trait name is the unknown-trait error and produces no order warning`() {
        val result = validate(docWith("[Delegated, complex-implementation]", delegated, complexImplementation))

        assertTrue(result.errors.any { it.path.startsWith("/work_item_schemas/t/default_traits") }, "errors: ${result.errors}")
        assertEquals(emptyList(), orderWarnings(result))
    }

    @Test
    fun `probe a repeated validation returns an equal result`() {
        val yaml = docWith("[delegated, high-stakes]", delegated, highStakes)

        assertEquals(validate(yaml), validate(yaml))
    }
}
