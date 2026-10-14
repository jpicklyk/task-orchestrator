package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The director body allow-list guard (R5 AC3 and AC10, scenarios S2 and S13 of the frozen test-plan) and the proof that the
 * guard itself can fail.
 *
 * A select or op whose response can carry a note, document or transition-summary body must never be director-visible, so
 * the director set is an allow-list BY NAME: any member visible to DIRECTOR that is not on the list is a violation, however
 * it got there (director() alone, or composed with knowledge() or orchestration()).
 *
 * Oracles: task-scope AC3 (the director set is exactly items_read get/list/overview/blocked and deps_read list/graph,
 * nothing else) and AC10 (allow-list guard, by name); plan section 3.1 / S12 (the director altitude rule: no note-body read
 * operation is loaded). The expected sets are written from those clauses, never read from a run.
 *
 * Negative-assertion conditions: every "not flagged" assertion is paired with a flagged twin built from the SAME fixture
 * with only the profile set changed, so a guard that flags nothing (or everything) fails one of the pair.
 *
 * Domain invariants satisfied by construction: each fixture member declares a non-blank doc and a request class whose only
 * field carries a `@Doc`; no member touches the database.
 */
class DirectorVisibilityGuardTest {
    /**
     * The AC3 allow-list: tool name to member names. R9 added `report usage` (aggregates only, no note or document body); R20 added
     * `runs_read` get and list and `runs_write` open, close, pause and resume (the run view carries no note, document or
     * transition-summary body, and `hints` are short structured strings; plan 3.4c).
     */
    private val allowed: Map<String, Set<String>> =
        mapOf(
            "items_read" to setOf("get", "list", "overview", "blocked"),
            "deps_read" to setOf("list", "graph"),
            "report" to setOf("usage"),
            "runs_read" to setOf("get", "list"),
            "runs_write" to setOf("open", "close", "pause", "resume")
        )

    @Serializable
    data class MarkerReq(
        @Doc("Marker.") val marker: String = "x"
    )

    private fun select(
        tool: String,
        vararg members: Pair<String, Set<ToolProfile>>
    ): SelectToolSpec =
        SelectToolSpec(
            tool,
            "Fixture.",
            members.map { (name, profiles) ->
                SelectSpec.Single<MarkerReq, String>(
                    name = name,
                    doc = "Doc.",
                    profiles = profiles,
                    request = serializer<MarkerReq>(),
                    handler = { _, _, _ -> Outcome.Ok("x") },
                    data = { JsonPrimitive(it) }
                )
            }
        )

    private fun ops(
        tool: String,
        vararg members: Pair<String, Set<ToolProfile>>
    ): OpsToolSpec<String> =
        OpsToolSpec(
            name = tool,
            description = "Fixture.",
            ops =
                members.map { (name, profiles) ->
                    OpSpec<MarkerReq, String>(
                        name = name,
                        doc = "Doc.",
                        profiles = profiles,
                        capability = ApiCapability.WRITE_ITEMS,
                        request = serializer<MarkerReq>(),
                        execute = { _, _, _ -> Outcome.Ok("x") }
                    )
                },
            codec = FixtureTools.StringCodec,
            element = { buildJsonObject { } }
        )

    /** Every member of [registry] whose declared profile set contains DIRECTOR, as `tool.member`. */
    private fun directorMembers(registry: ToolRegistry): List<String> =
        registry.tools.flatMap { spec ->
            when (spec) {
                is SelectToolSpec -> spec.selects.filter { ToolProfile.DIRECTOR in it.profiles }.map { "${spec.name}.${it.name}" }
                is OpsToolSpec<*> -> spec.ops.filter { ToolProfile.DIRECTOR in it.profiles }.map { "${spec.name}.${it.name}" }
            }
        }

    /** The guard: every director-visible member that is not on [allowed]. */
    private fun violations(registry: ToolRegistry): List<String> =
        directorMembers(registry).filter { qualified ->
            val (tool, member) = qualified.split(".", limit = 2)
            member !in allowed[tool].orEmpty()
        }

    private fun registryOf(vararg specs: ToolSpec) = ToolRegistry(specs.toList())

    // ------------------------------------------------------------------ S2 production

    @Test
    fun `S2 the production director set is exactly the allow-list`() {
        val production = ToolRegistry(buildToolSpecs())
        val byTool =
            directorMembers(
                production
            ).groupBy({ it.substringBefore(".") }, { it.substringAfter(".") }).mapValues { it.value.toSet() }
        assertEquals(allowed, byTool)
    }

    @Test
    fun `S2 the production registry passes the guard and only items_read, deps_read, report and the runs tools are director tools`() {
        val production = ToolRegistry(buildToolSpecs())
        assertEquals(emptyList(), violations(production))
        val directorTools = production.visible(ToolProfile.DIRECTOR).map { it.name }.toSet()
        assertEquals(setOf("items_read", "deps_read", "report", "runs_read", "runs_write"), directorTools)
        for (tool in listOf("notes_read", "notes_write", "docs_read", "docs_write", "advance", "config", "items_write", "deps_write")) {
            assertTrue(
                tool !in directorTools,
                "$tool can carry or change a note, document or transition body and must not be director-visible"
            )
        }
    }

    @Test
    fun `S2 the director set is also what the visibility predicate reports`() {
        val production = ToolRegistry(buildToolSpecs())
        for (spec in production.tools) {
            val visible = visibleNames(spec, ToolProfile.DIRECTOR).toSet()
            assertEquals(allowed[spec.name].orEmpty(), visible, "${spec.name}: director-visible member names")
        }
    }

    // ------------------------------------------------------------------ S13 the guard can fail

    @Test
    fun `S13 notes_read get made director-visible is flagged and the same fixture without director is not`() {
        val leaking = registryOf(select("notes_read", "get" to (knowledge() + director()), "list" to knowledge()))
        assertEquals(listOf("notes_read.get"), violations(leaking))

        val clean = registryOf(select("notes_read", "get" to knowledge(), "list" to knowledge()))
        assertEquals(emptyList(), violations(clean))
    }

    @Test
    fun `S13 items_read search made director-visible is flagged while the four allowed reads next to it are not`() {
        val members =
            listOf("get", "list", "overview", "blocked", "search").map { it to director() }.toTypedArray()
        val leaking = registryOf(select("items_read", *members))
        assertEquals(listOf("items_read.search"), violations(leaking))

        val withoutSearch = registryOf(select("items_read", *members.filter { it.first != "search" }.toTypedArray()))
        assertEquals(emptyList(), violations(withoutSearch))
    }

    @Test
    fun `S13 an op that writes a document or a transition is flagged however director is composed in`() {
        for (profiles in listOf(director(), orchestration() + director(), knowledge() + director())) {
            val leaking = registryOf(ops("docs_write", "upsert" to profiles))
            assertEquals(listOf("docs_write.upsert"), violations(leaking), "profiles=$profiles")
        }
        val clean = registryOf(ops("docs_write", "upsert" to knowledge()))
        assertEquals(emptyList(), violations(clean))
    }

    @Test
    fun `S13 the allow-list is by name so a renamed or extra member of an allowed tool is flagged`() {
        val renamed = registryOf(select("items_read", "get_all" to director()))
        assertEquals(listOf("items_read.get_all"), violations(renamed))
        val inAllowedTool = registryOf(select("deps_read", "list" to director(), "history" to director()))
        assertEquals(listOf("deps_read.history"), violations(inAllowedTool))
    }

    @Test
    fun `S13 a REST-only or non-director member is never flagged`() {
        val registry =
            registryOf(
                select("notes_read", "get" to emptySet<ToolProfile>(), "list" to orchestration()),
                ops("docs_write", "upsert" to knowledge())
            )
        assertEquals(emptyList(), directorMembers(registry))
        assertEquals(emptyList(), violations(registry))
    }
}
