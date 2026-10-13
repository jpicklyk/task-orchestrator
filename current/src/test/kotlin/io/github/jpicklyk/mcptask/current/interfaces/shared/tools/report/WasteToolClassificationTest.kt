package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report

import io.github.jpicklyk.mcptask.current.application.report.ReportFieldDocs
import io.github.jpicklyk.mcptask.current.application.report.ReportService
import io.github.jpicklyk.mcptask.current.application.report.WasteToolClasses
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDescriber
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.ParityCoverage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 03f3520d, R12) for scenario S18 of the frozen test-plan (the tool-class guard) and
 * the registered surface of the `waste` select.
 *
 * Oracles (never read from a run). S18, task-scope point 4: READ = items_read, notes_read, docs_read, deps_read; WRITE =
 * items_write, notes_write, deps_write, docs_write, advance; EXCLUDED = report, config; every registered tool name is in exactly
 * one class, so a tool added later without a classification fails here. The surface, task-scope points 1 and 7: `waste` is a
 * select of `report` visible in the director profile only (like `usage`), takes exactly rootId, since and until (no groupBy,
 * limit or cursor), and rootId, since and until keep ONE schema across the selects of the tool (a shared field-doc constant set
 * whose `until` text carries the waste cap "At most 31 days after since (waste: 7).").
 *
 * The task-scope names `buildMcpTools()` for the guard; no such function is declared, and the registry of the production tools
 * is `buildToolSpecs()` (the list `ReportParityTest` and `ParityCoverageGuardTest` use), so the guard reads that list. This
 * substitution is recorded in the test-manifest.
 *
 * Forbidden-construct declaration: none.
 */
class WasteToolClassificationTest {
    private val registry = ToolRegistry(buildToolSpecs())

    // ------------------------------------------------------------------ S18 classification

    @Test
    fun `S18 the three classes hold exactly the tools of the task-scope`() {
        assertEquals(setOf("items_read", "notes_read", "docs_read", "deps_read"), WasteToolClasses.READ)
        assertEquals(setOf("items_write", "notes_write", "deps_write", "docs_write", "advance"), WasteToolClasses.WRITE)
        assertEquals(setOf("report", "config"), WasteToolClasses.EXCLUDED)
    }

    @Test
    fun `S18 the classes are pairwise disjoint`() {
        assertEquals(emptySet(), WasteToolClasses.READ intersect WasteToolClasses.WRITE, "READ and WRITE")
        assertEquals(emptySet(), WasteToolClasses.READ intersect WasteToolClasses.EXCLUDED, "READ and EXCLUDED")
        assertEquals(emptySet(), WasteToolClasses.WRITE intersect WasteToolClasses.EXCLUDED, "WRITE and EXCLUDED")
    }

    @Test
    fun `S18 every registered tool name is in exactly one class`() {
        val names = registry.tools.map { it.name }
        assertTrue(names.isNotEmpty(), "fixture: the registry holds tools")
        val classes = mapOf("READ" to WasteToolClasses.READ, "WRITE" to WasteToolClasses.WRITE, "EXCLUDED" to WasteToolClasses.EXCLUDED)
        val problems =
            names.mapNotNull { name ->
                val holding = classes.filterValues { name in it }.keys
                if (holding.size == 1) null else "$name is in ${holding.size} classes: $holding"
            }
        assertEquals(emptyList(), problems, "every tool of the registry needs exactly one waste class")
    }

    @Test
    fun `S18 no class names a tool the registry does not have`() {
        val names = registry.tools.map { it.name }.toSet()
        val all = WasteToolClasses.READ + WasteToolClasses.WRITE + WasteToolClasses.EXCLUDED
        assertEquals(emptySet(), all - names, "a stale class entry never matches a row")
    }

    // ------------------------------------------------------------------ surface of the select

    @Test
    fun `S16 report lists usage and waste, and waste is visible in the director profile only`() {
        assertEquals(listOf("usage", "waste"), ParityCoverage.members(registry).filter { it.first == "report" }.map { it.second })
        assertEquals(setOf(ToolProfile.DIRECTOR), ParityCoverage.profilesOf(registry, "report", "waste"))
        assertEquals(ToolProfile.DIRECTOR, ParityCoverage.mcpLegProfile(registry, "report", "waste"))
    }

    private fun operations(): Map<String, JsonObject> {
        val spec = assertNotNull(registry["report"])
        return ToolDescriber
            .describe(spec, ToolProfile.DIRECTOR, null)
            .getValue("operations")
            .jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("name").jsonPrimitive.content }
    }

    private fun properties(operation: JsonObject) =
        operation
            .getValue("parameters")
            .jsonObject
            .getValue("properties")
            .jsonObject

    @Test
    fun `S10 waste takes rootId, since and until and no groupBy, limit or cursor`() {
        val waste = properties(assertNotNull(operations()["waste"], "the describe listing names the waste select"))

        assertTrue(waste.keys.containsAll(setOf("rootId", "since", "until")), "inputs: ${waste.keys}")
        for (absent in listOf("groupBy", "limit", "cursor", "runId", "anchorId", "principalId", "tool")) {
            assertTrue(absent !in waste.keys, "$absent is not an input of waste: ${waste.keys}")
        }
    }

    @Test
    fun `rootId, since and until keep one schema across the selects and until carries the waste cap`() {
        val ops = operations()
        val usage = properties(assertNotNull(ops["usage"]))
        val waste = properties(assertNotNull(ops["waste"]))

        for (shared in listOf("rootId", "since", "until")) {
            assertEquals(usage.getValue(shared), waste.getValue(shared), "$shared has one schema in both selects")
        }
        val until = waste.getValue("until").toString()
        assertTrue("(waste: 7)" in until, "until documents the waste cap: $until")
        assertTrue("At most 31 days after since" in until, "until still documents the usage cap: $until")
        assertTrue(ReportFieldDocs.UNTIL.endsWith("At most 31 days after since (waste: 7)."), ReportFieldDocs.UNTIL)
    }

    @Test
    fun `the waste cap and the default window are seven days`() {
        assertEquals(Duration.ofDays(7), ReportService.MAX_WASTE_WINDOW)
        assertEquals(Duration.ofDays(7), ReportService.DEFAULT_WINDOW)
        assertEquals(Duration.ofDays(31), ReportService.MAX_RAW_WINDOW)
    }
}
