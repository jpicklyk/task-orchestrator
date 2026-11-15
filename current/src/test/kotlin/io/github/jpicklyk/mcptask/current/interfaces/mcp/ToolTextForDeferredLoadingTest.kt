package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.bootstrap.profileInstructions
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDescriber
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSchemaRenderer
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolView
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.visibleNames
import io.github.jpicklyk.mcptask.current.test.tools.ToolSurfaceChecks
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Oracles shared by this class and the two HTTP profile tests (item a23ba692, test-author seat).
 *
 * Oracle sources: task-scope items 1-4 and acceptance criteria AC1-AC6 (TS), interface-contract 6 (IC6), and the retired 3.x
 * tool names in `claude-plugins/task-orchestrator/scripts/lib/contract-checks.mjs` (RETIRED_3X_TOOLS minus `complete_tree`,
 * which is an `items_write` op in 4.0 and so legitimate prose).
 */
internal object ToolTextOracle {
    val registry: ToolRegistry by lazy { ToolRegistry(buildToolSpecs()) }

    val retired3x: List<String> =
        listOf(
            "advance_item",
            "claim_item",
            "create_work_tree",
            "get_blocked_items",
            "get_context",
            "get_next_item",
            "get_next_status",
            "manage_dependencies",
            "manage_items",
            "manage_notes",
            "manage_plan_documents",
            "manage_project_config",
            "query_dependencies",
            "query_items",
            "query_notes",
            "query_rules"
        )

    /**
     * A word-bounded, case-insensitive mention of [name]: an underscore matches `_`, space or `-`, an optional plural `s`
     * is allowed, and neighbouring letters, digits or underscores make it a different word.
     */
    fun mention(name: String): Regex {
        val body = name.split("_").joinToString("[_ -]") { Regex.escape(it) }
        return Regex("(?<![A-Za-z0-9_])" + body + "s?(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    }

    fun mentions(
        text: String,
        name: String
    ): Boolean = mention(name).containsMatchIn(text)

    fun served(profile: ToolProfile): List<ToolSpec> = registry.tools.filter { McpOperationAdapter.toolFor(it, profile) != null }

    fun servedDescription(
        spec: ToolSpec,
        profile: ToolProfile
    ): String = assertNotNull(McpOperationAdapter.toolFor(spec, profile), "${spec.name} is served in $profile").description.orEmpty()

    /** Select or op names of [spec] that the REST view shows and [profile] does not. */
    fun hidden(
        spec: ToolSpec,
        profile: ToolProfile
    ): Set<String> = visibleNames(spec, ToolView.Rest).toSet() - visibleNames(spec, profile).toSet()

    fun cap(profile: ToolProfile): Int =
        when (profile) {
            ToolProfile.ORCHESTRATION -> 1_200
            ToolProfile.DIRECTOR -> 700
            ToolProfile.KNOWLEDGE -> 700
        }

    fun sentences(text: String): List<String> = text.trim().split(Regex("(?<=[.!?])\\s+"))

    /** Every way [text] fails AC1, AC2 and the frozen instruction oracles for [profile]; empty when it passes. */
    fun instructionViolations(
        profile: ToolProfile,
        text: String
    ): List<String> {
        val out = mutableListOf<String>()
        if (!mentions(text, profile.wire)) out += "does not name the profile '${profile.wire}'"
        if ("describe" !in text) out += "does not mention describe"
        val servedNames = served(profile).map { it.name }
        for (name in servedNames) if (!mentions(text, name)) out += "does not name the served tool $name"
        for (spec in registry.tools) {
            if (spec.name !in servedNames && mentions(text, spec.name)) out += "names ${spec.name}, which ${profile.wire} does not serve"
        }
        for (name in retired3x) if (mentions(text, name)) out += "names the retired 3.x tool $name"
        if (text.length > cap(profile)) out += "is ${text.length} characters, over the ${cap(profile)} cap"
        if ("mcp__" in text || "mcp-task-orchestrator" in text) out += "names a client-side entry key"
        when (profile) {
            ToolProfile.ORCHESTRATION -> {
                if ("seatToken" !in text) out += "lacks seatToken"
                val all = sentences(text)
                if (all.none { s -> s.lowercase().let { "decision" in it && "answer" in it && "director" in it } }) {
                    out += "no sentence says decisions are answered on the director profile"
                }
                if (all.none { s -> s.lowercase().let { "director" in it && "paus" in it && "resum" in it } }) {
                    out += "no sentence says runs are paused and resumed on the director profile"
                }
                if (!mentions(text, "items_read") ||
                    !Regex("\\bcontext\\b").containsMatchIn(text) ||
                    !Regex("\\bnext\\b").containsMatchIn(text)
                ) {
                    out += "does not say to start with items_read context or next"
                }
                if (all.last().lowercase().let { "load" !in it || "together" !in it }) {
                    out += "the last sentence does not say to load the needed tools together"
                }
            }

            ToolProfile.DIRECTOR -> {
                if (!Regex("bod(y|ies)", RegexOption.IGNORE_CASE).containsMatchIn(text)) out += "does not say it serves no body read"
            }

            ToolProfile.KNOWLEDGE -> {
                if (!Regex("transition", RegexOption.IGNORE_CASE).containsMatchIn(text)) out += "does not say it has no phase transitions"
                if (!Regex("\\bruns?\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) out += "does not say it has no runs"
            }
        }
        return out
    }

    fun assertInstructions(
        profile: ToolProfile,
        text: String
    ) {
        val violations = instructionViolations(profile, text)
        assertTrue(violations.isEmpty(), "instructions for ${profile.wire} (${text.length} chars): $violations in: $text")
    }

    /** Description of every property at every depth of [schema], keyed by a path such as `/ops[]/deps[]/from`. */
    fun propertyDescriptions(schema: JsonObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()

        fun go(
            node: JsonElement,
            path: String
        ) {
            val obj = node as? JsonObject ?: return
            (obj["properties"] as? JsonObject)?.forEach { (name, child) ->
                val childPath = "$path/$name"
                ((child as? JsonObject)?.get("description") as? JsonPrimitive)?.let { out[childPath] = it.content }
                go(child, childPath)
            }
            obj["items"]?.let { go(it, "$path[]") }
        }
        go(schema, "")
        return out
    }

    fun paramDocs(
        toolName: String,
        profile: ToolProfile
    ): Map<String, String> {
        val spec = assertNotNull(registry[toolName], "$toolName is registered")
        val schema = assertNotNull(ToolSchemaRenderer.inputSchema(spec, profile), "$toolName renders a schema in $profile")
        return propertyDescriptions(schema)
    }
}

/**
 * Independent tests (test-author seat, item a23ba692) for the instructions and descriptions that must work when a client
 * defers tool schemas. Scenarios S1-S12 of the frozen test-plan; S13 is in ToolSchemaRendererTest. The HTTP initialize
 * path is covered by McpProfileHttpTest (orchestration, knowledge) and McpDirectorProfileTest (director) through
 * [ToolTextOracle.assertInstructions].
 */
class ToolTextForDeferredLoadingTest {
    private val registry get() = ToolTextOracle.registry

    private fun spec(name: String): ToolSpec = assertNotNull(registry[name], "$name is registered")

    // ------------------------------------------------------------------ S1 S2 S3 S4 instructions (EXISTING-SURFACE)

    @Test
    fun `S1 S3 S4 each profile's instructions satisfy the frozen oracle`() {
        for (profile in ToolProfile.entries) ToolTextOracle.assertInstructions(profile, profileInstructions(profile))
    }

    @Test
    fun `S2 the three instruction texts are distinct and within their caps`() {
        val texts = ToolProfile.entries.associateWith { profileInstructions(it) }
        assertEquals(3, texts.values.toSet().size, "three distinct texts: $texts")
        for ((profile, text) in texts) {
            assertTrue(text.length <= ToolTextOracle.cap(profile), "${profile.wire}: ${text.length} > ${ToolTextOracle.cap(profile)}")
        }
        assertEquals(1_200, ToolTextOracle.cap(ToolProfile.ORCHESTRATION))
    }

    @Test
    fun `S1 probe the oracle detects a cap overrun, a missing tool and a foreign tool`() {
        val director = profileInstructions(ToolProfile.DIRECTOR)
        val cap = ToolTextOracle.cap(ToolProfile.DIRECTOR)
        val atCap = director.padEnd(cap, '.')
        val overCap = director.padEnd(cap + 1, '.')
        assertTrue(ToolTextOracle.instructionViolations(ToolProfile.DIRECTOR, atCap).none { "cap" in it }, "exactly at the cap passes")
        assertTrue(ToolTextOracle.instructionViolations(ToolProfile.DIRECTOR, overCap).any { "cap" in it }, "cap + 1 fails")

        val knowledge = profileInstructions(ToolProfile.KNOWLEDGE)
        assertTrue(
            ToolTextOracle.instructionViolations(ToolProfile.KNOWLEDGE, "$knowledge Also runs_write.").any { "runs_write" in it },
            "a tool the knowledge profile does not serve is flagged"
        )
        assertTrue(
            ToolTextOracle
                .instructionViolations(
                    ToolProfile.KNOWLEDGE,
                    "$knowledge Use mcp__mcp-task-orchestrator__items_read."
                ).isNotEmpty(),
            "a client-side entry key is flagged"
        )
        assertTrue(
            ToolTextOracle.instructionViolations(ToolProfile.KNOWLEDGE, "$knowledge Then manage_items.").any { "retired" in it },
            "a retired 3.x tool name is flagged"
        )
    }

    @Test
    fun `S1 probe the matcher accepts spelling variants and rejects neighbouring words`() {
        assertTrue(ToolTextOracle.mentions("Then End seat.", "end_seat"))
        assertTrue(ToolTextOracle.mentions("then end-seat now", "end_seat"))
        assertTrue(ToolTextOracle.mentions("ENDS: end_seats", "end_seat"))
        assertTrue(ToolTextOracle.mentions("Search and Mark", "search"))
        assertTrue(ToolTextOracle.mentions("Search and Mark", "mark"))
        assertTrue(!ToolTextOracle.mentions("runs_reader", "runs_read"))
        assertTrue(!ToolTextOracle.mentions("backend seat", "end_seat"))
        assertTrue(!ToolTextOracle.mentions("marked", "mark"))
    }

    // ------------------------------------------------------------------ S5 S9 descriptions (EXISTING-SURFACE)

    @Test
    fun `S5 no description names a select or op that its profile does not serve`() {
        val faults = mutableListOf<String>()
        for (profile in ToolProfile.entries) {
            for (spec in ToolTextOracle.served(profile)) {
                val text = ToolTextOracle.servedDescription(spec, profile)
                for (hidden in ToolTextOracle.hidden(spec, profile)) {
                    if (ToolTextOracle.mentions(text, hidden)) faults += "${spec.name}/${profile.wire} names hidden '$hidden': $text"
                }
            }
        }
        assertTrue(faults.isEmpty(), "AC3: ${faults.joinToString("\n")}")
    }

    @Test
    fun `S5 control the hidden sets are not empty where the plan says members are hidden`() {
        assertTrue(
            ToolTextOracle.hidden(spec("runs_write"), ToolProfile.ORCHESTRATION).containsAll(setOf("pause", "resume", "ack", "answer"))
        )
        assertTrue(
            ToolTextOracle.hidden(spec("runs_write"), ToolProfile.DIRECTOR).containsAll(setOf("update", "assign", "end_seat", "raise"))
        )
        assertTrue(ToolTextOracle.hidden(spec("runs_read"), ToolProfile.ORCHESTRATION).containsAll(setOf("decisions", "decision")))
        assertTrue("seats" in ToolTextOracle.hidden(spec("runs_read"), ToolProfile.DIRECTOR))
        assertTrue(ToolTextOracle.hidden(spec("items_read"), ToolProfile.DIRECTOR).containsAll(setOf("schema", "search", "related")))
        assertTrue(ToolTextOracle.hidden(spec("items_read"), ToolProfile.KNOWLEDGE).containsAll(setOf("context", "next", "blocked")))
        assertTrue(ToolTextOracle.hidden(spec("items_write"), ToolProfile.KNOWLEDGE).containsAll(setOf("create_tree", "complete_tree")))
    }

    @Test
    fun `S6 the items_read description served to orchestration names context next blocked and history`() {
        val text = ToolTextOracle.servedDescription(spec("items_read"), ToolProfile.ORCHESTRATION)
        for (select in listOf("context", "next", "blocked", "history")) {
            assertTrue(ToolTextOracle.mentions(text, select), "AC4: items_read/orchestration must name $select: $text")
        }
    }

    @Test
    fun `S7 the knowledge items_write description does not speak of trees and report opens with its purpose`() {
        val write = ToolTextOracle.servedDescription(spec("items_write"), ToolProfile.KNOWLEDGE)
        assertTrue("tree" !in write.lowercase(), "knowledge cannot create or complete trees: $write")
        for (profile in ToolProfile.entries) {
            if (ToolTextOracle.served(profile).none { it.name == "report" }) continue
            val report = ToolTextOracle.servedDescription(spec("report"), profile)
            assertTrue(!report.startsWith("Reports"), "report/${profile.wire} opens with what it is for, not the word Reports: $report")
        }
    }

    @Test
    fun `S9 every served description points at describe`() {
        for (profile in ToolProfile.entries) {
            for (spec in ToolTextOracle.served(profile)) {
                val text = ToolTextOracle.servedDescription(spec, profile)
                assertTrue("describe" in text, "${spec.name}/${profile.wire} must mention describe: $text")
            }
        }
    }

    @Test
    fun `S11 the REST description of runs_write still names the whole operation set`() {
        val runsWrite = spec("runs_write")
        val rest = ToolSchemaRenderer.description(runsWrite)
        val described =
            ToolDescriber
                .describe(runsWrite, ToolView.Rest, null)
                .getValue("description")
                .jsonPrimitive.content
        for (op in listOf("pause", "answer", "mark", "update", "assign")) {
            assertTrue(ToolTextOracle.mentions(rest, op), "REST description must name $op: $rest")
            assertTrue(ToolTextOracle.mentions(described, op), "REST describe must name $op: $described")
        }
    }

    // ------------------------------------------------------------------ S12 describe (NEW-SURFACE, decision D1)

    @Test
    fun `S12 describe true answers with the description of the serving profile`() {
        val faults = mutableListOf<String>()
        for (profile in listOf(ToolProfile.ORCHESTRATION, ToolProfile.DIRECTOR)) {
            for (spec in ToolTextOracle.served(profile)) {
                val text =
                    ToolDescriber
                        .describe(spec, profile, null)
                        .getValue("description")
                        .jsonPrimitive.content
                for (hidden in ToolTextOracle.hidden(spec, profile)) {
                    if (ToolTextOracle.mentions(text, hidden)) {
                        faults +=
                            "describe ${spec.name}/${profile.wire} names hidden '$hidden': $text"
                    }
                }
            }
        }
        assertTrue(faults.isEmpty(), faults.joinToString("\n"))
        val runsWrite =
            ToolDescriber
                .describe(
                    spec("runs_write"),
                    ToolProfile.ORCHESTRATION,
                    null
                ).getValue("description")
                .jsonPrimitive.content
        assertTrue(
            ToolTextOracle.mentions(runsWrite, "assign"),
            "control: orchestration runs_write describe still names assign: $runsWrite"
        )
    }

    // ------------------------------------------------------------------ S8 parameter text (EXISTING-SURFACE)

    @Test
    fun `S8 the enforced limits are stated in the parameter text`() {
        val items = ToolTextOracle.paramDocs("items_write", ToolProfile.ORCHESTRATION)
        assertTrue("500" in items.getValue("/ops[]/title"), "title limit 500: ${items["/ops[]/title"]}")
        assertTrue(Regex("2,?000").containsMatchIn(items.getValue("/ops[]/summary")), "summary limit 2000: ${items["/ops[]/summary"]}")
        assertTrue("100" in items.getValue("/ops[]/traits"), "trait name limit 100: ${items["/ops[]/traits"]}")

        val notes = ToolTextOracle.paramDocs("notes_write", ToolProfile.ORCHESTRATION)
        val body = notes.getValue("/ops[]/body")
        assertTrue(Regex("65,?536").containsMatchIn(body), "note body limit 65536: $body")
        assertTrue("byte" in body.lowercase(), "note body limit is in bytes: $body")
    }

    @Test
    fun `S8 dependency direction is stated on both ends in create_tree and in deps_write`() {
        val tree = ToolTextOracle.paramDocs("items_write", ToolProfile.ORCHESTRATION)
        val treeFrom = tree.filterKeys { it.endsWith("deps[]/from") }
        val treeTo = tree.filterKeys { it.endsWith("deps[]/to") }
        assertTrue(treeFrom.isNotEmpty() && treeTo.isNotEmpty(), "create_tree declares deps[].from and deps[].to: ${tree.keys}")
        treeFrom.forEach { (path, doc) -> assertTrue("blocker" in doc.lowercase(), "$path must say the blocker: $doc") }
        treeTo.forEach { (path, doc) -> assertTrue("blocked" in doc.lowercase(), "$path must say the blocked item: $doc") }

        val deps = ToolTextOracle.paramDocs("deps_write", ToolProfile.ORCHESTRATION)
        assertTrue("blocker" in deps.getValue("/ops[]/from").lowercase(), "deps_write from: ${deps["/ops[]/from"]}")
        assertTrue("blocked" in deps.getValue("/ops[]/to").lowercase(), "deps_write to: ${deps["/ops[]/to"]}")
    }

    // ------------------------------------------------------------------ S10 budget (EXISTING-SURFACE)

    @Test
    fun `S10 the production registry is inside the size ceilings and the ceilings are unchanged`() {
        assertEquals(8_000, ToolSurfaceChecks.PER_TOOL_CEILING)
        assertEquals(22_000, ToolSurfaceChecks.KNOWLEDGE_TOTAL_CEILING)
        assertEquals(42_000, ToolSurfaceChecks.ORCHESTRATION_TOTAL_CEILING)
        assertEquals(14_000, ToolSurfaceChecks.DIRECTOR_TOTAL_CEILING)
        assertEquals(emptyList(), ToolSurfaceChecks.budgetViolations(registry))
    }
}
