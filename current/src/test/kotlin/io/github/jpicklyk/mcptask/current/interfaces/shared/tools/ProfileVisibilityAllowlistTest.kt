package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Allowlist guard for tool profile membership (Q29, plan section 2 and U23).
 *
 * `profile` is a required constructor argument of every select and op, so a member cannot silently pick a
 * profile by omission. This guard closes the other half: choosing KNOWLEDGE by mistake. The knowledge
 * profile must expose EXACTLY the allowlist below, so a new knowledge-visible member has to be added here
 * deliberately, and every orchestration-only member named in plan section 2 must stay hidden from it.
 */
class ProfileVisibilityAllowlistTest {
    private val registry = ToolRegistry(buildToolSpecs())

    private fun members(profile: ToolProfile): Map<String, Set<String>> =
        registry.tools
            .associate { it.name to visibleNames(it, profile).toSet() }
            .filterValues { it.isNotEmpty() }

    @Test
    fun `knowledge profile exposes exactly the allowlisted tool and member set`() {
        assertEquals(KNOWLEDGE_ALLOWLIST, members(ToolProfile.KNOWLEDGE))
    }

    @Test
    fun `every plan section 2 hidden member is absent from knowledge and present in orchestration when registered`() {
        val knowledge = members(ToolProfile.KNOWLEDGE)
        val orchestration = members(ToolProfile.ORCHESTRATION)
        var checked = 0
        for (spec in registry.tools) {
            val all = (spec as? SelectToolSpec)?.selects?.map { it.name } ?: (spec as OpsToolSpec<*>).ops.map { it.name }
            val hidden =
                if (spec.name in HIDDEN_WHOLE_TOOLS) all.toSet() else HIDDEN_MEMBERS[spec.name].orEmpty().intersect(all.toSet())
            for (member in hidden) {
                checked++
                assertTrue(member !in knowledge[spec.name].orEmpty(), "${spec.name}.$member must be hidden from knowledge")
                assertTrue(member in orchestration[spec.name].orEmpty(), "${spec.name}.$member must be visible in orchestration")
            }
        }
        for (tool in HIDDEN_WHOLE_TOOLS) {
            assertTrue(registry[tool] != null, "tool $tool is expected to be registered (Q23 added config)")
            assertTrue(tool in orchestration, "tool $tool must be visible in orchestration")
        }
        assertTrue(checked > 0, "no hidden member of plan section 2 is registered; the guard would be vacuous")
        for (tool in HIDDEN_WHOLE_TOOLS) {
            assertTrue(tool !in knowledge, "tool $tool must be wholly absent from knowledge")
        }
    }

    @Test
    fun `knowledge members are a subset of orchestration members for every tool`() {
        val orchestration = members(ToolProfile.ORCHESTRATION)
        for ((tool, names) in members(ToolProfile.KNOWLEDGE)) {
            assertTrue(
                orchestration[tool].orEmpty().containsAll(names),
                "$tool: knowledge members ${names - orchestration[tool].orEmpty()} are missing from orchestration"
            )
        }
    }

    @Test
    fun `director profile exposes exactly the allowlisted tool and member set`() {
        assertEquals(DIRECTOR_ALLOWLIST, members(ToolProfile.DIRECTOR))
    }

    @Test
    fun `director members are also orchestration members and the knowledge allowlist is unchanged by director`() {
        // R5 AC2/AC3: a director member is its current set plus DIRECTOR, so none is director-only today and the
        // knowledge and orchestration visibility of every member is what it was before the third profile.
        val orchestration = members(ToolProfile.ORCHESTRATION)
        for ((tool, names) in members(ToolProfile.DIRECTOR)) {
            assertTrue(
                orchestration[tool].orEmpty().containsAll(names),
                "$tool: director members ${names - orchestration[tool].orEmpty()} are missing from orchestration"
            )
        }
        assertEquals(KNOWLEDGE_ALLOWLIST, members(ToolProfile.KNOWLEDGE))
    }

    private companion object {
        /** R5 AC3: exactly the items_read reads and the deps_read reads. Nothing that can carry a note, document or transition body. */
        val DIRECTOR_ALLOWLIST: Map<String, Set<String>> =
            mapOf(
                "items_read" to setOf("get", "list", "overview", "blocked"),
                "deps_read" to setOf("list", "graph")
            )

        /** Tools plan section 2 hides entirely from knowledge. */
        val HIDDEN_WHOLE_TOOLS = setOf("advance", "config")

        /** Individual members plan section 2 hides from knowledge. `repin` is skipped while it is not registered. */
        val HIDDEN_MEMBERS =
            mapOf(
                "items_read" to setOf("context", "next", "blocked"),
                "items_write" to setOf("create_tree", "complete_tree", "repin")
            )

        val KNOWLEDGE_ALLOWLIST: Map<String, Set<String>> =
            mapOf(
                "items_read" to setOf("get", "list", "schema", "search", "overview", "related"),
                "items_write" to setOf("create", "update", "delete"),
                "notes_read" to setOf("get", "list"),
                "notes_write" to setOf("upsert", "delete"),
                "deps_read" to setOf("list", "graph"),
                "deps_write" to setOf("create", "delete", "linear", "fan_out", "fan_in"),
                "docs_read" to setOf("get", "list", "history", "links"),
                "docs_write" to setOf("upsert", "archive", "unarchive", "delete", "attach", "detach")
            )
    }
}
