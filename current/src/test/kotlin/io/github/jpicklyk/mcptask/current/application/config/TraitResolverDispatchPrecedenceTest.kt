package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Independent tests (test-author seat, item acb209e0) for the per-seat dispatch precedence rule of
 * [TraitResolver.dispatchBySeat]: a model-selecting seat-only trait outranks `delegated` whatever the trait order.
 *
 * Oracles (frozen `test-plan` / `task-scope` notes): AC = the item summary and task-scope "Acceptance"; DEF = task-scope
 * "Definitions" and "Change" (seat-only trait = no phase-level profile in any phase and a non-empty per-seat map;
 * model-selecting override = a seat-only trait's override with a non-null model that neither sets nor clears `agent`;
 * the first model-selecting override in trait order wins the seat, otherwise the first override in order; the winner
 * replaces the others whole and is laid over the phase default; phase-level `dispatch()` stays first-wins by order);
 * AT = the `SeatDispatchOverride.applyTo` kdoc (a field in `cleared` is nulled out). Scenario ids S1..S8 are the
 * test-plan ids; every scenario is EXISTING-SURFACE.
 *
 * Fixtures are YAML parsed by [YamlConfigDocumentParser], in the same shape as
 * `current/src/test/resources/config/dogfood-4.0.yaml`, so the dispatch blocks reach the resolver as production config does.
 */
class TraitResolverDispatchPrecedenceTest {
    private val seats =
        listOf(
            SeatDefinition("planner", Role.QUEUE),
            SeatDefinition("implementer", Role.WORK, enters = true),
            SeatDefinition("test-author", Role.WORK, after = listOf("implementer")),
            SeatDefinition("orchestrator", Role.WORK, after = listOf("implementer")),
            SeatDefinition("reviewer", Role.REVIEW)
        )

    // The three dogfood traits this item is about, verbatim from dogfood-4.0.yaml.
    private val dogfoodTraitsYaml =
        """
        traits:
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
          complex-implementation:
            dispatch:
              work:
                seats:
                  implementer: { model: opus }
          high-stakes:
            dispatch:
              queue:
                seats:
                  planner: { model: fable }
              review:
                seats:
                  reviewer: { model: fable }
        """.trimIndent()

    private val implementerAgent = "task-orchestrator:implementer"
    private val reviewerAgent = "task-orchestrator:reviewer"

    /** The dogfood traits plus [extraTraits], written at column 0 as `name:` blocks and nested under `traits:`. */
    private fun traitsYaml(extraTraits: String = ""): String =
        if (extraTraits.isEmpty()) dogfoodTraitsYaml else dogfoodTraitsYaml + "\n" + extraTraits.trimIndent().prependIndent("  ")

    private fun parse(yaml: String): ConfigDocument {
        val parsed = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)
        check(parsed is ConfigDocumentParser.Outcome.Parsed) { "fixture must parse: $parsed" }
        return parsed.document
    }

    private fun globalLookup(doc: ConfigDocument): GlobalConfigLookup =
        LayerBackedGlobalLookup(ConfigLayer(doc, "global-fp", ConfigSource.GLOBAL))

    private fun resolver(
        globalYaml: String,
        perRootYaml: String? = null
    ): TraitResolver {
        val perRoot = perRootYaml?.let { ConfigLayer(parse(it), "per-root-fp", ConfigSource.PER_ROOT) }
        return TraitResolver(LayeredConfig(UUID.randomUUID(), perRoot, globalLookup(parse(globalYaml))))
    }

    private fun dispatchBySeat(
        traits: List<String>,
        extraTraits: String = "",
        perRootYaml: String? = null
    ): Map<Role, Map<String, DispatchProfile>> = resolver(traitsYaml(extraTraits), perRootYaml).dispatchBySeat(traits, seats)

    private fun Map<Role, Map<String, DispatchProfile>>.profile(
        phase: Role,
        seat: String
    ): DispatchProfile? = this[phase]?.get(seat)

    // ---------------------------------------------------------------------------------------------
    // S1 - [delegated, complex-implementation] and the reverse resolve identically; implementer is opus
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 delegated before complex-implementation resolves the same map as the reverse order`() {
        val delegatedFirst = dispatchBySeat(listOf("delegated", "complex-implementation"))
        val delegatedLast = dispatchBySeat(listOf("complex-implementation", "delegated"))

        assertEquals(delegatedLast, delegatedFirst, "trait order must not change the resolved per-seat dispatch")
        assertEquals(
            DispatchProfile(agent = implementerAgent, model = "opus", effort = null),
            delegatedFirst.profile(Role.WORK, "implementer"),
            "the model comes from complex-implementation, the agent from delegated's work phase profile"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // S2 - [delegated, high-stakes] and the reverse: planner and reviewer resolve to fable
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 delegated before high-stakes resolves planner and reviewer to fable, same as the reverse order`() {
        val delegatedFirst = dispatchBySeat(listOf("delegated", "high-stakes"))
        val delegatedLast = dispatchBySeat(listOf("high-stakes", "delegated"))

        assertEquals(delegatedLast, delegatedFirst)
        assertEquals(
            DispatchProfile(agent = null, model = "fable", effort = null),
            delegatedFirst.profile(Role.QUEUE, "planner"),
            "queue has no phase-level profile, so the planner keeps no agent or effort"
        )
        assertEquals(
            DispatchProfile(agent = reviewerAgent, model = "fable", effort = "high"),
            delegatedFirst.profile(Role.REVIEW, "reviewer"),
            "the reviewer keeps delegated's review-phase agent and effort; only the model is overridden"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // S3 - delegated keeps every seat the model-selecting trait does not claim
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S3 under delegated and complex-implementation the unclaimed seats keep delegated's overrides`() {
        val result = dispatchBySeat(listOf("delegated", "complex-implementation"))

        assertEquals(
            DispatchProfile(agent = null, model = "sonnet", effort = null),
            result.profile(Role.WORK, "test-author"),
            "delegated's test-author override clears the agent and sets sonnet"
        )
        assertEquals(
            DispatchProfile(agent = null, model = null, effort = null),
            result.profile(Role.WORK, "orchestrator"),
            "delegated's orchestrator override clears the agent and sets nothing else"
        )
        assertEquals(DispatchProfile(agent = null, model = "opus", effort = null), result.profile(Role.QUEUE, "planner"))
        assertEquals(
            DispatchProfile(agent = reviewerAgent, model = "opus", effort = "high"),
            result.profile(Role.REVIEW, "reviewer")
        )
    }

    // ---------------------------------------------------------------------------------------------
    // S4 - a seat-only override that sets agent is not model-selecting: order still decides
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S4 a seat-only override that sets agent follows trait order`() {
        val extra =
            """
            agent-setting:
              dispatch:
                work:
                  seats:
                    implementer: { agent: custom-agent, model: opus }
            """

        val delegatedFirst = dispatchBySeat(listOf("delegated", "agent-setting"), extra)
        val delegatedLast = dispatchBySeat(listOf("agent-setting", "delegated"), extra)

        assertEquals(
            DispatchProfile(agent = implementerAgent, model = "sonnet", effort = null),
            delegatedFirst.profile(Role.WORK, "implementer"),
            "an agent-setting override is not model-selecting, so the earlier delegated override still wins"
        )
        assertEquals(
            DispatchProfile(agent = "custom-agent", model = "opus", effort = null),
            delegatedLast.profile(Role.WORK, "implementer"),
            "listed first, the agent-setting override wins by order"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // S5 - a seat-only override that clears agent (agent: null) is not model-selecting either
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 a seat-only override that clears agent follows trait order`() {
        val extra =
            """
            agent-clearing:
              dispatch:
                work:
                  seats:
                    implementer: { agent: null, model: opus }
            """

        val delegatedFirst = dispatchBySeat(listOf("delegated", "agent-clearing"), extra)
        val delegatedLast = dispatchBySeat(listOf("agent-clearing", "delegated"), extra)

        assertEquals(
            DispatchProfile(agent = implementerAgent, model = "sonnet", effort = null),
            delegatedFirst.profile(Role.WORK, "implementer"),
            "clearing the agent counts as touching it: the earlier delegated override wins"
        )
        assertEquals(
            DispatchProfile(agent = null, model = "opus", effort = null),
            delegatedLast.profile(Role.WORK, "implementer"),
            "listed first, the agent-clearing override wins by order and nulls the phase agent"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // S6 - a trait with a phase-level profile is not seat-only, even when it also sets a seat model
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 a trait with a phase-level profile is not seat-only and follows trait order`() {
        val extra =
            """
            phase-and-seat:
              dispatch:
                work:
                  effort: low
                  seats:
                    implementer: { model: opus }
            """

        val delegatedFirst = dispatchBySeat(listOf("delegated", "phase-and-seat"), extra)
        val delegatedLast = dispatchBySeat(listOf("phase-and-seat", "delegated"), extra)

        assertEquals(
            DispatchProfile(agent = implementerAgent, model = "sonnet", effort = null),
            delegatedFirst.profile(Role.WORK, "implementer"),
            "not seat-only, so not model-selecting: delegated, listed first, wins and supplies the phase default"
        )
        assertEquals(
            DispatchProfile(agent = null, model = "opus", effort = "low"),
            delegatedLast.profile(Role.WORK, "implementer"),
            "listed first, the trait wins and the phase default is its own work profile"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // S7 - between two model-selecting seat-only traits, trait order decides
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S7 two model-selecting seat-only traits on one seat resolve by order`() {
        val extra =
            """
            model-a:
              dispatch:
                work:
                  seats:
                    implementer: { model: opus }
            model-b:
              dispatch:
                work:
                  seats:
                    implementer: { model: haiku }
            """

        assertEquals("opus", dispatchBySeat(listOf("model-a", "model-b"), extra).profile(Role.WORK, "implementer")?.model)
        assertEquals("haiku", dispatchBySeat(listOf("model-b", "model-a"), extra).profile(Role.WORK, "implementer")?.model)
        assertEquals(
            "opus",
            dispatchBySeat(listOf("delegated", "model-a", "model-b"), extra).profile(Role.WORK, "implementer")?.model,
            "delegated ahead of two model-selecting traits: the first of those two still wins"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // S8 - end to end through EffectiveConfigResolver over the dogfood fixture
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 EffectiveConfigResolver resolves implementer opus for item traits in either order`() {
        val resolver = EffectiveConfigResolver(globalLookup(parse(StrictConfigCases.dogfoodFixture)), perRoot = null)

        fun implementerFor(vararg itemTraits: String): DispatchProfile? =
            runBlocking {
                val item =
                    WorkItem(
                        title = "S8 item",
                        type = "feature-task",
                        role = Role.WORK,
                        depth = 0,
                        traits = itemTraits.toList()
                    )
                val schema = resolver.resolveSchema(item)!!
                resolver.resolveDispatchBySeat(item, schema).profile(Role.WORK, "implementer")
            }

        val expected = DispatchProfile(agent = implementerAgent, model = "opus", effort = null)
        assertEquals(expected, implementerFor("delegated", "complex-implementation"))
        assertEquals(expected, implementerFor("complex-implementation", "delegated"))
        assertEquals(expected, implementerFor("complex-implementation"), "delegated arrives through default_traits")
    }

    // ---------------------------------------------------------------------------------------------
    // Probes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `probe a per-root redefinition of complex-implementation with a phase profile is not seat-only`() {
        val perRoot =
            """
            traits:
              complex-implementation:
                dispatch:
                  work:
                    effort: low
                    seats:
                      implementer: { model: opus }
            """.trimIndent()

        val delegatedFirst = dispatchBySeat(listOf("delegated", "complex-implementation"), perRootYaml = perRoot)
        val delegatedLast = dispatchBySeat(listOf("complex-implementation", "delegated"), perRootYaml = perRoot)

        assertEquals(
            DispatchProfile(agent = implementerAgent, model = "sonnet", effort = null),
            delegatedFirst.profile(Role.WORK, "implementer"),
            "the per-root declaration replaces the global one wholesale and has a phase profile, so order decides"
        )
        assertEquals(
            DispatchProfile(agent = null, model = "opus", effort = "low"),
            delegatedLast.profile(Role.WORK, "implementer")
        )
    }

    @Test
    fun `probe a seat-only override with a null model is not model-selecting`() {
        val extra =
            """
            no-model:
              dispatch:
                work:
                  seats:
                    implementer: { model: null, effort: low }
            """

        assertEquals(
            DispatchProfile(agent = implementerAgent, model = "sonnet", effort = null),
            dispatchBySeat(listOf("delegated", "no-model"), extra).profile(Role.WORK, "implementer")
        )
        assertEquals(
            DispatchProfile(agent = implementerAgent, model = null, effort = "low"),
            dispatchBySeat(listOf("no-model", "delegated"), extra).profile(Role.WORK, "implementer"),
            "no model-selecting override anywhere, so the first by order (no-model) wins and clears the model"
        )
    }

    @Test
    fun `probe a repeated call returns an equal result`() {
        val traits = listOf("delegated", "complex-implementation", "high-stakes")
        val resolver = resolver(traitsYaml())

        assertEquals(resolver.dispatchBySeat(traits, seats), resolver.dispatchBySeat(traits, seats))
    }

    @Test
    fun `probe phase-level dispatch stays first-wins by trait order`() {
        val resolver = resolver(traitsYaml())

        assertEquals(
            DispatchProfile(agent = implementerAgent),
            resolver.dispatch(listOf("complex-implementation", "delegated"))[Role.WORK],
            "complex-implementation has no phase-level profile; delegated supplies the work default"
        )
    }
}
