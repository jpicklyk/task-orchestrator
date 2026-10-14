package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import io.github.jpicklyk.mcptask.current.test.tools.ToolGoldens
import io.github.jpicklyk.mcptask.current.test.tools.ToolSurfaceChecks
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The planned-surface guard (R5 AC9, scenarios S8, S12 and S17 of the frozen test-plan).
 *
 * The planned fixtures under `golden/tools/planned/` describe tools and fields that later items add. The guard keeps them
 * honest: production compact total plus the planned sizes must fit each profile's ceiling, a planned full entry must fit the
 * per-tool ceiling, and a planned file that no longer describes something NEW (a full entry for a tool already visible in that
 * profile, or a delta property already in the production rendering) is stale and must be replaced by the real golden.
 *
 * Oracles: task-scope AC8 and AC9 (ceilings 8,000 / 22,000 / 42,000 / 14,000; production + planned within each), decision D4
 * (format: `<tool>.<profile>.json` is a full tools/list entry sized by `ToolGoldens.compact`; `<tool>.<profile>.delta.json` is a
 * JSON object of properties to add, sized by its compact length plus 2; stale = a full entry for a visible tool or a delta
 * property already rendered; every json file counts) and decision D5 (the exact list of planned files and, per file, the members
 * and properties it carries). Expected values are written from those clauses, never read from a run.
 *
 * Negative-assertion conditions: every "is flagged" test builds a planned directory holding ONLY the offending file, so the
 * single expected violation can only come from that file; every "is not flagged" control uses the same file shape with the one
 * offending property removed.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 *
 * Domain invariants satisfied by construction: planned entries are decoded as SDK `Tool` objects (name plus an object
 * `inputSchema`) and sized with the same function the budget guard uses, so a fixture cannot be a different shape from a real
 * entry.
 */
internal object PlannedSurfaceChecks {
    val DIR: Path = ToolGoldens.DIR.resolve("planned")

    private val NAME = Regex("^([a-z][a-z0-9_]*)[.](knowledge|orchestration|director)([.]delta)?[.]json$")

    /** The per-profile figures a violation message and a measurement report need. */
    data class Totals(
        val production: Int,
        val planned: Int,
        val ceiling: Int
    ) {
        val sum: Int get() = production + planned
    }

    private class Planned(
        val file: String,
        val tool: String,
        val profile: ToolProfile,
        val delta: Boolean,
        val size: Int,
        val keys: Set<String>
    )

    private fun productionProperties(
        registry: ToolRegistry,
        tool: String,
        profile: ToolProfile
    ): Set<String>? {
        val spec = registry[tool] ?: return null
        val rendered = McpOperationAdapter.toolFor(spec, profile) ?: return null
        val schema = McpJson.encodeToJsonElement(Tool.serializer(), rendered).jsonObject["inputSchema"]?.jsonObject ?: return emptySet()
        val top = schema["properties"]?.jsonObject ?: return emptySet()
        return if (spec is OpsToolSpec<*>) {
            top["ops"]
                ?.jsonObject
                ?.get("items")
                ?.jsonObject
                ?.get("properties")
                ?.jsonObject
                ?.keys
                .orEmpty()
        } else {
            top.keys
        }
    }

    private fun jsonFiles(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        Files.walk(dir).use { stream ->
            return stream
                .filter {
                    Files.isRegularFile(it) &&
                        it.fileName
                            .toString()
                            .lowercase()
                            .endsWith(".json")
                }.sorted()
                .toList()
        }
    }

    private fun relative(
        dir: Path,
        file: Path
    ): String = dir.relativize(file).joinToString("/") { it.toString() }

    /** Parses every planned file; unreadable or malformed ones become messages instead of entries. */
    private fun scan(
        registry: ToolRegistry,
        dir: Path,
        out: MutableList<String>
    ): List<Planned> {
        val planned = mutableListOf<Planned>()
        for (file in jsonFiles(dir)) {
            val rel = relative(dir, file)
            val match = NAME.matchEntire(file.fileName.toString())
            if (match == null || file.parent != dir) {
                out += "$rel: not a planned fixture name (<tool>.<knowledge|orchestration|director>[.delta].json directly in planned/)"
                continue
            }
            val tool = match.groupValues[1]
            val profile = ToolProfile.entries.single { it.wire == match.groupValues[2] }
            val delta = match.groupValues[3].isNotEmpty()
            val text = Files.readString(file)
            if (delta) {
                val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                if (obj == null) {
                    out += "$rel: a delta must be a JSON object of properties"
                    continue
                }
                planned += Planned(rel, tool, profile, true, obj.toString().length + 2, obj.keys)
                val production = productionProperties(registry, tool, profile)
                val already = obj.keys.filter { production != null && it in production }
                if (already.isNotEmpty()) out += "$rel: stale, $already already in the production ${profile.wire} rendering of $tool"
            } else {
                val entry = runCatching { McpJson.decodeFromString(Tool.serializer(), text) }.getOrNull()
                if (entry == null) {
                    out += "$rel: not a tools/list entry"
                    continue
                }
                val size = ToolGoldens.compact(entry).length
                planned += Planned(rel, tool, profile, false, size, emptySet())
                if (size > ToolSurfaceChecks.PER_TOOL_CEILING) out += "$rel: $size chars exceeds ${ToolSurfaceChecks.PER_TOOL_CEILING}"
                val spec = registry[tool]
                if (spec != null && McpOperationAdapter.toolFor(spec, profile) != null) {
                    out += "$rel: stale, $tool is already visible in ${profile.wire}; replace the planned file with the real golden"
                }
            }
        }
        return planned
    }

    fun totals(
        registry: ToolRegistry,
        dir: Path = DIR,
        ceiling: (ToolProfile) -> Int = ToolSurfaceChecks::totalCeiling
    ): Map<ToolProfile, Totals> {
        val planned = scan(registry, dir, mutableListOf())
        return ToolProfile.entries.associateWith { profile ->
            Totals(
                production = ToolSurfaceChecks.sizes(registry, profile).values.sum(),
                planned = planned.filter { it.profile == profile }.sumOf { it.size },
                ceiling = ceiling(profile)
            )
        }
    }

    /** One message per fault: a bad file name, an unparseable file, a stale file, an oversize entry, a profile over its ceiling. */
    fun violations(
        registry: ToolRegistry,
        dir: Path = DIR,
        ceiling: (ToolProfile) -> Int = ToolSurfaceChecks::totalCeiling
    ): List<String> {
        val out = mutableListOf<String>()
        scan(registry, dir, out)
        for ((profile, t) in totals(registry, dir, ceiling)) {
            val over = t.sum > t.ceiling
            if (over) {
                out += "total/${profile.wire}: production ${t.production} + planned ${t.planned} = ${t.sum} exceeds ${t.ceiling}"
            }
        }
        return out
    }
}

class PlannedSurfaceGuardTest {
    private val production = ToolRegistry(buildToolSpecs())

    private fun entry(
        name: String,
        descriptionLength: Int = 0
    ) = """{"name":"$name","description":"${"x".repeat(descriptionLength)}","inputSchema":{"type":"object"}}"""

    private fun sizeOf(json: String): Int = ToolGoldens.compact(McpJson.decodeFromString(Tool.serializer(), json)).length

    /** A full planned entry whose compact size is EXACTLY [target]. */
    private fun entryOfSize(
        name: String,
        target: Int
    ): String {
        val padded = entry(name, target - sizeOf(entry(name, 0)))
        assertEquals(target, sizeOf(padded), "fixture sizing: the planned entry must measure exactly the target")
        return padded
    }

    private fun write(
        dir: Path,
        file: String,
        text: String
    ): Path {
        val path = dir.resolve(file)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return path
    }

    private fun only(
        dir: Path,
        file: String,
        text: String,
        registry: ToolRegistry = production
    ): List<String> {
        write(dir, file, text)
        return PlannedSurfaceChecks.violations(registry, dir)
    }

    private val noProduction = ToolRegistry(emptyList())

    // ------------------------------------------------------------------ S8 the production directory

    @Test
    fun `S8 the production registry plus the planned fixtures is within every ceiling and nothing is stale`() {
        assertEquals(emptyList(), PlannedSurfaceChecks.violations(production))
        for ((profile, t) in PlannedSurfaceChecks.totals(production)) {
            assertTrue(t.sum <= t.ceiling, "${profile.wire}: production ${t.production} + planned ${t.planned} = ${t.sum} > ${t.ceiling}")
        }
    }

    @Test
    fun `S8 the guard measures the planned files, so the production directory is not vacuous`() {
        val totals = PlannedSurfaceChecks.totals(production)
        assertTrue(
            totals.getValue(ToolProfile.DIRECTOR).planned > 0,
            "director has planned files (runs_read, runs_write, the report delta)"
        )
        assertTrue(totals.getValue(ToolProfile.ORCHESTRATION).planned > 0, "orchestration has planned files")
        assertTrue(totals.getValue(ToolProfile.KNOWLEDGE).planned > 0, "knowledge has the notes_write takeover delta")
    }

    @Test
    fun `S8 the guard uses the frozen ceilings by default`() {
        val totals = PlannedSurfaceChecks.totals(production)
        assertEquals(22_000, totals.getValue(ToolProfile.KNOWLEDGE).ceiling)
        assertEquals(42_000, totals.getValue(ToolProfile.ORCHESTRATION).ceiling)
        assertEquals(14_000, totals.getValue(ToolProfile.DIRECTOR).ceiling)
    }

    // ------------------------------------------------------------------ S8 / S12 boundaries

    @Test
    fun `S8 a director total of exactly 14000 passes and 14001 is flagged`(
        @TempDir dir: Path
    ) {
        write(dir, "a_tool.director.json", entryOfSize("a_tool", 7_000))
        write(dir, "b_tool.director.json", entryOfSize("b_tool", 7_000))
        assertEquals(emptyList(), PlannedSurfaceChecks.violations(noProduction, dir))

        write(dir, "b_tool.director.json", entryOfSize("b_tool", 7_001))
        val violations = PlannedSurfaceChecks.violations(noProduction, dir)
        assertEquals(1, violations.size, violations.toString())
        assertTrue(violations.single().startsWith("total/director"), violations.single())
    }

    @Test
    fun `S8 an orchestration total of exactly 42000 passes and 42001 is flagged`(
        @TempDir dir: Path
    ) {
        for (i in 1..6) write(dir, "t${i}_tool.orchestration.json", entryOfSize("t${i}_tool", 7_000))
        assertEquals(emptyList(), PlannedSurfaceChecks.violations(noProduction, dir))

        write(dir, "t6_tool.orchestration.json", entryOfSize("t6_tool", 7_001))
        val violations = PlannedSurfaceChecks.violations(noProduction, dir)
        assertEquals(1, violations.size, violations.toString())
        assertTrue(violations.single().startsWith("total/orchestration"), violations.single())
    }

    @Test
    fun `S8 a knowledge total of exactly 22000 passes and 22001 is flagged`(
        @TempDir dir: Path
    ) {
        write(dir, "a_tool.knowledge.json", entryOfSize("a_tool", 7_333))
        write(dir, "b_tool.knowledge.json", entryOfSize("b_tool", 7_333))
        write(dir, "c_tool.knowledge.json", entryOfSize("c_tool", 7_334))
        assertEquals(emptyList(), PlannedSurfaceChecks.violations(noProduction, dir))

        write(dir, "c_tool.knowledge.json", entryOfSize("c_tool", 7_335))
        val violations = PlannedSurfaceChecks.violations(noProduction, dir)
        assertEquals(1, violations.size, violations.toString())
        assertTrue(violations.single().startsWith("total/knowledge"), violations.single())
    }

    @Test
    fun `S8 the production compact total counts toward the ceiling together with the planned entry`(
        @TempDir dir: Path
    ) {
        val registry = FixtureTools().registry
        val productionTotal = ToolSurfaceChecks.sizes(registry, ToolProfile.KNOWLEDGE).values.sum()
        val planned = entry("new_tool", 40)
        val plannedSize = sizeOf(planned)
        write(dir, "new_tool.knowledge.json", planned)

        val exact: (ToolProfile) -> Int = { if (it == ToolProfile.KNOWLEDGE) productionTotal + plannedSize else 1_000_000 }
        assertEquals(emptyList(), PlannedSurfaceChecks.violations(registry, dir, exact), "production + planned == ceiling passes")

        val oneShort: (ToolProfile) -> Int = { if (it == ToolProfile.KNOWLEDGE) productionTotal + plannedSize - 1 else 1_000_000 }
        val violations = PlannedSurfaceChecks.violations(registry, dir, oneShort)
        assertEquals(1, violations.size, violations.toString())
        assertTrue(violations.single().startsWith("total/knowledge"), violations.single())
    }

    @Test
    fun `S12 a delta counts toward the ceiling with its compact length plus 2`(
        @TempDir dir: Path
    ) {
        val registry = FixtureTools().registry
        val productionTotal = ToolSurfaceChecks.sizes(registry, ToolProfile.KNOWLEDGE).values.sum()
        val delta = """{"takeover":{"type":"boolean","description":"Take over."}}"""
        write(dir, "fx_write.knowledge.delta.json", delta)
        val deltaSize = delta.length + 2

        val exact: (ToolProfile) -> Int = { if (it == ToolProfile.KNOWLEDGE) productionTotal + deltaSize else 1_000_000 }
        assertEquals(emptyList(), PlannedSurfaceChecks.violations(registry, dir, exact), "a delta of compact length + 2 fits exactly")

        val oneShort: (ToolProfile) -> Int = { if (it == ToolProfile.KNOWLEDGE) productionTotal + deltaSize - 1 else 1_000_000 }
        val violations = PlannedSurfaceChecks.violations(registry, dir, oneShort)
        assertEquals(1, violations.size, "a delta that is ignored by the sum would pass here: $violations")
        assertTrue(violations.single().startsWith("total/knowledge"), violations.single())
    }

    // ------------------------------------------------------------------ S12 each fault alone

    @Test
    fun `S12 a planned entry of exactly 8000 characters passes and one of 8001 is flagged`(
        @TempDir dir: Path
    ) {
        assertEquals(
            emptyList(),
            only(dir, "big_tool.director.json", entryOfSize("big_tool", 8_000), noProduction).filterNot {
                it.startsWith("total/")
            }
        )
        val violations =
            only(
                dir,
                "big_tool.director.json",
                entryOfSize("big_tool", 8_001),
                noProduction
            ).filterNot { it.startsWith("total/") }
        assertEquals(1, violations.size, violations.toString())
        assertTrue("big_tool.director.json" in violations.single() && "8001" in violations.single(), violations.single())
    }

    @Test
    fun `S12 a full entry for a tool already visible in that profile is stale, and one for a tool not visible there is not`(
        @TempDir visible: Path,
        @TempDir hidden: Path
    ) {
        val stale = only(visible, "items_read.director.json", entry("items_read"))
        assertEquals(1, stale.size, stale.toString())
        assertTrue("items_read.director.json" in stale.single() && "stale" in stale.single(), stale.single())

        assertEquals(emptyList(), only(hidden, "advance.director.json", entry("advance")), "control: advance is not a director tool")
    }

    @Test
    fun `S12 a full entry for a tool no profile has yet is not stale`(
        @TempDir dir: Path
    ) {
        assertEquals(emptyList(), only(dir, "runs_probe.director.json", entry("runs_probe")))
    }

    @Test
    fun `S12 a select delta property already in the production rendering is stale and a new one is not`(
        @TempDir stale: Path,
        @TempDir fresh: Path
    ) {
        val property = """{"type":"string","description":"Which read to run."}"""
        val violations = only(stale, "items_read.director.delta.json", """{"select":$property}""")
        assertEquals(1, violations.size, violations.toString())
        assertTrue("items_read.director.delta.json" in violations.single() && "[select]" in violations.single(), violations.single())

        assertEquals(emptyList(), only(fresh, "items_read.director.delta.json", """{"zz_new_field":$property}"""))
    }

    @Test
    fun `S12 an ops delta property already in ops items is stale and a new one is not`(
        @TempDir stale: Path,
        @TempDir fresh: Path
    ) {
        val property = """{"type":"string","description":"The operation this element runs."}"""
        val violations = only(stale, "notes_write.knowledge.delta.json", """{"op":$property}""")
        assertEquals(1, violations.size, violations.toString())
        assertTrue("notes_write.knowledge.delta.json" in violations.single() && "[op]" in violations.single(), violations.single())

        assertEquals(
            emptyList(),
            only(fresh, "notes_write.knowledge.delta.json", """{"takeover":{"type":"boolean","description":"Take over."}}""")
        )
    }

    @Test
    fun `S12 an unparseable planned file is flagged whatever its kind`(
        @TempDir a: Path,
        @TempDir b: Path,
        @TempDir c: Path,
        @TempDir d: Path
    ) {
        for ((dir, file, text) in listOf(
            Triple(a, "bad_tool.director.json", "{not json"),
            Triple(b, "bad_tool.director.delta.json", "{not json"),
            Triple(c, "bad_tool.director.delta.json", "[1,2]"),
            Triple(d, "bad_tool.director.json", """{"foo":1}""")
        )) {
            val violations = only(dir, file, text, noProduction)
            assertEquals(1, violations.size, "$file <- $text: $violations")
            assertTrue(file in violations.single(), violations.single())
        }
    }

    @Test
    fun `S12 an unknown profile, an upper-case extension and a nested directory are each flagged`(
        @TempDir a: Path,
        @TempDir b: Path,
        @TempDir c: Path,
        @TempDir d: Path
    ) {
        for ((dir, file) in listOf(
            a to "runs_probe.directors.json",
            b to "runs_probe.director.JSON",
            c to "nested/runs_probe.director.json",
            d to "runs_probe.dir.delta.json"
        )) {
            val violations = only(dir, file, entry("runs_probe"), noProduction)
            assertEquals(1, violations.size, "$file: $violations")
            assertTrue(file in violations.single(), violations.single())
        }
    }

    @Test
    fun `S12 every json file counts, so a valid file next to a bad one is still checked`(
        @TempDir dir: Path
    ) {
        write(dir, "good_tool.director.json", entry("good_tool"))
        write(dir, "bad_tool.director.json", "{not json")
        val violations = PlannedSurfaceChecks.violations(noProduction, dir)
        assertEquals(1, violations.size, violations.toString())
        assertTrue("bad_tool.director.json" in violations.single(), violations.single())
    }

    // ------------------------------------------------------------------ S17 the planned set

    private fun planned(): Map<String, JsonObject> =
        Files.list(PlannedSurfaceChecks.DIR).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .toList()
                .associate { it.fileName.toString() to Json.parseToJsonElement(Files.readString(it)).jsonObject }
        }

    @Test
    fun `S17 the planned directory holds exactly the eight delta files of decision D5, the runs tools now real (R20)`() {
        // R9 made report visible in director and R20 made runs_read and runs_write visible, so their full planned entries became
        // stale; what the later items still add stays reserved as deltas.
        assertEquals(
            setOf(
                "runs_read.orchestration.delta.json",
                "runs_read.director.delta.json",
                "runs_write.orchestration.delta.json",
                "runs_write.director.delta.json",
                "report.director.delta.json",
                "advance.orchestration.delta.json",
                "notes_write.knowledge.delta.json",
                "notes_write.orchestration.delta.json"
            ),
            planned().keys
        )
    }

    @Test
    fun `S17 there is no report outside director and no mark anywhere`() {
        val names = planned().keys
        assertTrue(names.none { it.startsWith("report.") && !it.startsWith("report.director.") }, "S23: report is director only: $names")
        assertTrue(names.none { it.startsWith("mark.") }, "mark is REST-only and has no planned MCP file: $names")
    }

    @Test
    fun `S17 no full planned entry remains for a runs tool, so every runs fixture is a delta`() {
        val names = planned().keys
        for (file in listOf("runs_read.orchestration", "runs_read.director", "runs_write.orchestration", "runs_write.director")) {
            assertTrue("$file.json" !in names, "$file.json is stale once the tool is visible and must be replaced by a delta")
            assertTrue("$file.delta.json" in names, "$file.delta.json keeps the budget of the members R20 does not ship")
        }
    }

    @Test
    fun `S17 the runs deltas add exactly the properties of the members R20 does not ship`() {
        val files = planned()
        assertEquals(setOf("itemId", "ended"), files.getValue("runs_read.orchestration.delta.json").keys)
        assertEquals(setOf("decisionId", "overdue"), files.getValue("runs_read.director.delta.json").keys)
        assertEquals(
            setOf(
                "itemId",
                "seat",
                "outcome",
                "links",
                "class",
                "question",
                "options",
                "recommended",
                "context",
                "dedupeKey",
                "severity",
                "block",
                "summary"
            ),
            files.getValue("runs_write.orchestration.delta.json").keys
        )
        assertEquals(setOf("decisionId", "option", "text", "rationale"), files.getValue("runs_write.director.delta.json").keys)
    }

    @Test
    fun `S17 the other four deltas add exactly the properties decision D5 and R9 give`() {
        val files = planned()
        assertEquals(setOf("usage", "reason", "decisionId"), files.getValue("advance.orchestration.delta.json").keys)
        assertEquals(setOf("runId", "anchorId"), files.getValue("report.director.delta.json").keys)
        assertEquals(setOf("takeover"), files.getValue("notes_write.knowledge.delta.json").keys)
        assertEquals(setOf("takeover"), files.getValue("notes_write.orchestration.delta.json").keys)
    }

    @Test
    fun `S17 every planned property carries a description`() {
        for ((file, json) in planned()) {
            if (file.endsWith(".delta.json")) {
                for ((property, schema) in json) {
                    val description = schema.jsonObject["description"]?.jsonPrimitive?.content
                    assertTrue(!description.isNullOrBlank(), "$file: delta property $property has no description")
                }
            }
        }
    }
}
