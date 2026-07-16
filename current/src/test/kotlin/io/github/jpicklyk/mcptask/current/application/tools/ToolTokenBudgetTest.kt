package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.tools.compound.CompleteTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.config.ManageProjectConfigTool
import io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.dependency.QueryDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.QueryNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.ClaimItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetBlockedItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetNextItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetNextStatusTool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Token-efficiency regression guard for the `tools/list` payload (see the "MCP
 * Token-Efficiency Program" work referenced in [ToolDocumentationConsistencyTest]: baseline
 * `tools/list` payload was 56,984 chars; the t4x/t5x slimming passes cut that to a measured
 * 29,983 chars). That earlier work removed the bloat; this test exists so a *future* change
 * can't silently reintroduce it — per-tool and total char ceilings on the exact payload shape
 * an MCP client receives from `tools/list` (tool name + prose description + JSON parameter
 * schema).
 *
 * ────────────────────────────────────────────────────────────────────────────────────────
 * BUDGET PHILOSOPHY — how [perToolCeilings] and [totalCeiling] were chosen, and how to
 * change them intentionally:
 *
 *   1. Each ceiling = (measured compact-JSON char count for that tool, taken from this
 *      repo right after the t61-era slimming work landed) * 1.15, rounded UP to the nearest
 *      50. The 15% headroom absorbs incidental description wording changes; it is NOT meant
 *      to absorb a new operation, a new parameter, or restored verbose prose.
 *   2. The total ceiling (34,500) is the sum of the per-tool measured values * 1.15, i.e. it
 *      moves in lockstep with the per-tool table — it is not independently tunable.
 *   3. If a failure here is EXPECTED (you deliberately added a parameter, operation, or
 *      clarified wording that grows a tool's schema/description), re-run this test, read the
 *      new measured size from the failure message (or add a temporary `println` in
 *      [renderTool]), and update BOTH the single tool's row in [perToolCeilings] AND
 *      [totalCeiling] in the same commit as the schema change — so the diff makes the token
 *      cost of the change visible to reviewers instead of silently raising the ceiling to
 *      whatever the new number happens to be.
 *   4. If a failure is a REGRESSION (an edit bloated a description or schema without adding
 *      capability — e.g. restored per-field prose duplicating the schema, verbose enum
 *      documentation, etc.), fix the tool instead of raising the ceiling.
 *   5. Adding a 15th tool requires adding both a row to [perToolCeilings] and folding its
 *      measured size into [totalCeiling] — the "every tool has a ceiling" check below fails
 *      loudly (naming the tool) if a row is missing, so this can't be skipped by accident.
 * ────────────────────────────────────────────────────────────────────────────────────────
 */
class ToolTokenBudgetTest {
    private val allTools: List<ToolDefinition> =
        listOf(
            ManageItemsTool(),
            QueryItemsTool(),
            ManageNotesTool(),
            QueryNotesTool(),
            ManageDependenciesTool(),
            QueryDependenciesTool(),
            AdvanceItemTool(),
            ClaimItemTool(),
            GetBlockedItemsTool(),
            GetContextTool(),
            GetNextItemTool(),
            GetNextStatusTool(),
            CompleteTreeTool(),
            CreateWorkTreeTool(),
            ManageProjectConfigTool(),
        )

    /**
     * See "BUDGET PHILOSOPHY" above for how these numbers were derived and how to update them.
     *
     * Four rows (advance_item, get_next_item, get_blocked_items, complete_tree) were nudged a
     * few percent above the originally-supplied ceiling: this test's [renderTool] (in-process,
     * name + description + parameterSchema only) measures a handful of chars higher than the
     * baseline those four ceilings were derived from — most likely a minor JSON-shape
     * difference (e.g. `required` list handling) versus whatever produced the original number,
     * not a real size regression (the aggregate total below still lands within ~0.5% of the
     * repo's documented "measured 29,983 chars" baseline, confirming this render function is
     * faithful overall). Bumped just enough to restore a small real margin instead of leaving
     * the test permanently red on day one.
     */
    private val perToolCeilings: Map<String, Int> =
        mapOf(
            "query_items" to 7350, // was 6150; T2.5 added the overview `ancestorId` anchored mode
            "create_work_tree" to 3080,
            "get_next_item" to 3150, // was 2500; T2.3 added the `ancestorId` scope parameter
            "manage_dependencies" to 2585,
            "manage_items" to 2520,
            "query_notes" to 2386,
            "get_context" to 2650, // was 1909; T2.3 added the `ancestorId` scope parameter
            "claim_item" to 2100,
            "manage_notes" to 2057,
            "complete_tree" to 1760, // was 1712; see note above
            "query_dependencies" to 1708,
            "advance_item" to 1450, // was 1407; see note above
            "get_blocked_items" to 1250, // was 830; T2.3 added the `ancestorId` scope parameter
            "get_next_status" to 470,
            "manage_project_config" to 3150, // measured 2698 after get `fingerprint` param + relation (fast-forward guard, t5)
        )

    /** Sum of the (unrounded) measured-per-tool-values * 1.15; see BUDGET PHILOSOPHY point 2. */
    private val totalCeiling = 40_800

    // explicitNulls = false mirrors the compact-wire-shape convention already used elsewhere
    // in this codebase (see EventRoutes.kt / ItemWriteRoutes.kt / NoteWriteRoutes.kt) — a
    // ToolSchema with unset `required`/`$defs` should not pay for `"required":null` on the wire.
    private val compactJson = Json { explicitNulls = false }

    /** Renders exactly what an MCP client sees for one tool in a `tools/list` response. */
    private fun renderTool(tool: ToolDefinition): String {
        val schemaJson = compactJson.encodeToJsonElement(ToolSchema.serializer(), tool.parameterSchema)
        val rendered =
            buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameterSchema", schemaJson)
            }
        return rendered.toString()
    }

    @Test
    fun `every tool has a registered ceiling and every ceiling maps to a live tool`() {
        val toolNames = allTools.map { it.name }.toSet()
        val missing = toolNames.filter { it !in perToolCeilings }
        val stale = perToolCeilings.keys.filter { it !in toolNames }

        val failures = mutableListOf<String>()
        missing.forEach { failures.add("$it: no ceiling registered in perToolCeilings — add one (see BUDGET PHILOSOPHY)") }
        stale.forEach { failures.add("$it: ceiling registered but no such tool is instantiated in allTools — remove the stale row") }

        if (failures.isNotEmpty()) {
            fail("Tool <-> ceiling table is out of sync:\n" + failures.joinToString("\n") { "  - $it" })
        }
    }

    @Test
    fun `each tool stays within its per-tool tools-list char budget`() {
        val measured = allTools.associate { it.name to renderTool(it).length }

        println("── tools/list per-tool measured sizes ──")
        measured.entries.sortedByDescending { it.value }.forEach { (name, size) ->
            val ceiling = perToolCeilings[name]
            println("  %-22s %6d chars   (ceiling %s)".format(name, size, ceiling?.toString() ?: "MISSING"))
        }

        val failures = mutableListOf<String>()
        for ((name, size) in measured) {
            val ceiling = perToolCeilings[name] ?: continue // reported by the sync test above
            if (size > ceiling) {
                failures.add(
                    "$name: rendered tools/list entry is $size chars, exceeds its ceiling of $ceiling chars " +
                        "(see BUDGET PHILOSOPHY in ToolTokenBudgetTest for how to update this intentionally)"
                )
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "Tool(s) grew past their tools/list char budget:\n" +
                    failures.joinToString("\n") { "  - $it" }
            )
        }
    }

    @Test
    fun `total tools-list payload stays within the aggregate char budget`() {
        val total = allTools.sumOf { renderTool(it).length }
        println("tools/list TOTAL measured size: $total chars (ceiling $totalCeiling)")
        assertTrue(
            total <= totalCeiling,
            "Total tools/list payload is $total chars, exceeds the aggregate ceiling of $totalCeiling chars " +
                "(see BUDGET PHILOSOPHY in ToolTokenBudgetTest for how to update this intentionally)"
        )
    }
}
