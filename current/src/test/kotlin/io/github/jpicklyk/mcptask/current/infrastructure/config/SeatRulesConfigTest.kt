package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidationResult
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.config.GlobalConfigSource
import io.github.jpicklyk.mcptask.current.application.config.LayerBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.application.config.LayeredConfig
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.application.config.TraitResolver
import io.github.jpicklyk.mcptask.current.application.service.buildSeatsJson
import io.github.jpicklyk.mcptask.current.model.config.ConfigScope
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.model.schema.SeatDefinition
import io.github.jpicklyk.mcptask.current.model.schema.WorkItemSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item b02b8bdf / U3, G8) for the seat `rules:` key: scenarios S1 to S5 of the frozen
 * test-plan (the server half; S6 to S12 are the plugin and docs halves in `seat-rules.test.mjs` and `seat-rules-docs.test.mjs`).
 *
 * Oracles: task-scope AC1 (a seat declares `rules:` as an ordered list of rule keys, served on the seat in `items_read` schema and
 * context), D1 (each element is a string matching the rule-key pattern `^[a-z0-9][a-z0-9._-]{0,99}$` (RuleService.KEY_PATTERN),
 * a defect is a finding at `<seat entry>/rules` or `<seat entry>/rules/<i>`: a warning in MIGRATION, an error in STRICT, the valid
 * elements are kept, duplicates collapse keeping the first position), the layering rule that a trait's seats join the item's merged
 * seats, and the config JSON Schema contract (`additionalProperties: false`, one declared property per catalog key).
 * Absent, empty and null are three states: absent serves no `rules` member, `rules: []` serves `[]`.
 *
 * Negative-assertion conditions: every rejected document is paired with a repaired twin that differs only in the defective value;
 * the twin must be admitted, so a validator that rejects every seat fails the twin.
 *
 * Domain invariants satisfied by construction: [SeatDefinition] has no cross-field invariant beyond a non-blank name; documents are
 * parsed from YAML text with one `enters: true` seat per phase at most.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
class SeatRulesConfigTest {
    private fun doc(
        yaml: String,
        mode: ParseMode = ParseMode.MIGRATION
    ): ConfigDocument {
        val outcome = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = mode)
        return assertIs<ConfigDocumentParser.Outcome.Parsed>(outcome, "expected Parsed: $outcome").document
    }

    private fun validate(yaml: String): ConfigValidationResult =
        ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { null })
            .validate(yaml, YamlConfigDocumentParser, ConfigScope.ROOT)

    private fun errorAt(
        result: ConfigValidationResult,
        pointer: String
    ): Boolean = result.errors.any { it.path == pointer || it.path.startsWith("$pointer/") }

    private fun schemaYaml(rulesLine: String): String =
        "work_item_schemas:\n  bug-fix:\n    seats:\n" +
            "      - name: planner\n        phase: queue\n$rulesLine" +
            "    notes: []\n"

    private fun rulesOf(yaml: String): List<String>? =
        doc(yaml)
            .workItemSchemas
            .getValue("bug-fix")
            .seats
            .single { it.name == "planner" }
            .rules

    private val seatPath = "/work_item_schemas/bug-fix/seats/0"

    // ------------------------------------------------------------------ S1

    @Test
    fun `S1 a seat rules list parses in declared order and the twin document validates clean`() {
        val yaml = schemaYaml("        rules: [planning-sweeps, review-cited-claims]\n")
        assertEquals(listOf("planning-sweeps", "review-cited-claims"), rulesOf(yaml))
        assertEquals(emptyList(), validate(yaml).errors)
        // Order is semantic: the reversed declaration parses reversed.
        assertEquals(
            listOf("review-cited-claims", "planning-sweeps"),
            rulesOf(schemaYaml("        rules: [review-cited-claims, planning-sweeps]\n"))
        )
    }

    @Test
    fun `S1 items_read seat JSON serves the declared rules in order`() {
        val seats =
            listOf(
                SeatDefinition("planner", Role.QUEUE, rules = listOf("planning-sweeps", "review-cited-claims")),
                SeatDefinition("implementer", Role.WORK, enters = true)
            )
        val served = assertNotNull(buildSeatsJson(seats))
        val planner = served[0].jsonObject
        assertEquals(
            listOf("planning-sweeps", "review-cited-claims"),
            planner.getValue("rules").jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals("planner", planner.getValue("name").jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ S2

    @Test
    fun `S2 absent rules is null and served without a rules member, empty rules is an empty list and served as an empty array`() {
        val absentYaml = schemaYaml("")
        val absent = rulesOf(absentYaml)
        assertNull(absent, "absent must stay null, distinct from empty")
        val emptyYaml = schemaYaml("        rules: []\n")
        val empty = rulesOf(emptyYaml)
        assertEquals(emptyList(), empty, "an explicit empty list is declared-empty, not absent")

        val servedAbsent = assertNotNull(buildSeatsJson(listOf(SeatDefinition("planner", Role.QUEUE, rules = null))))
        assertFalse(servedAbsent[0].jsonObject.containsKey("rules"), "absent: no rules member: ${servedAbsent[0]}")
        val servedEmpty = assertNotNull(buildSeatsJson(listOf(SeatDefinition("planner", Role.QUEUE, rules = emptyList()))))
        assertEquals(JsonArray(emptyList()), servedEmpty[0].jsonObject["rules"], "declared empty: served []")
        assertEquals(emptyList(), validate(emptyYaml).errors)
    }

    @Test
    fun `S2 probe - a null rules value does not break the seat and is not a declared list`() {
        val yaml = schemaYaml("        rules: ~\n")
        val seat =
            doc(yaml)
                .workItemSchemas
                .getValue("bug-fix")
                .seats
                .single()
        assertEquals("planner", seat.name)
        assertTrue(seat.rules.isNullOrEmpty(), "null rules declares no rule: ${seat.rules}")
    }

    // ------------------------------------------------------------------ S3

    @Test
    fun `S3 a non-list rules value is one finding at the rules pointer - warning in MIGRATION, error in STRICT`() {
        val yaml = schemaYaml("        rules: abc\n")
        val d = doc(yaml)
        assertEquals(emptyList(), d.errors)
        assertEquals(1, d.warningViolations.count { it.path == "$seatPath/rules" }, "warnings: ${d.warningViolations}")
        assertTrue(errorAt(validate(yaml), "$seatPath/rules"), "strict errors: ${validate(yaml).errors}")
        // Twin: the same seat with a list is admitted.
        assertEquals(emptyList(), validate(schemaYaml("        rules: [abc]\n")).errors)
    }

    @Test
    fun `S3 each bad element is a finding at its own index and the valid elements are kept in order`() {
        val longKey = "a".repeat(101)
        val yaml = schemaYaml("        rules: [good-one, 42, \"Bad Key!\", \"$longKey\", fine.two]\n")
        val d = doc(yaml)
        val paths = d.warningViolations.map { it.path }
        for (i in listOf(1, 2, 3)) {
            assertEquals(1, paths.count { it == "$seatPath/rules/$i" }, "index $i in $paths")
        }
        assertFalse("$seatPath/rules/0" in paths, "a valid element has no finding: $paths")
        assertFalse("$seatPath/rules/4" in paths, "a valid element has no finding: $paths")
        assertEquals(listOf("good-one", "fine.two"), rulesOf(yaml))
        val strict = validate(yaml)
        for (i in listOf(1, 2, 3)) assertTrue(errorAt(strict, "$seatPath/rules/$i"), "strict index $i: ${strict.errors}")
    }

    @Test
    fun `S3 boundary - a 100 character key is admitted and a 101 character key is not`() {
        val ok = "a".repeat(100)
        assertEquals(listOf(ok), rulesOf(schemaYaml("        rules: [$ok]\n")))
        assertEquals(emptyList(), validate(schemaYaml("        rules: [$ok]\n")).errors)
        val tooLong = "a".repeat(101)
        assertTrue(errorAt(validate(schemaYaml("        rules: [$tooLong]\n")), "$seatPath/rules/0"))
    }

    @Test
    fun `S3 probes - casing, trailing space, empty string and null element are each rejected at index zero`() {
        val bad = listOf("Planning-Sweeps", "\"planning-sweeps \"", "\"\"", "~", "\"-leading-dash\"", "\"under_score_ok_but_Upper\"")
        for (element in bad) {
            val yaml = schemaYaml("        rules: [$element]\n")
            val paths = doc(yaml).warningViolations.map { it.path }
            assertTrue("$seatPath/rules/0" in paths, "$element: warnings $paths")
            assertTrue(errorAt(validate(yaml), "$seatPath/rules/0"), "$element: strict ${validate(yaml).errors}")
        }
        // Twins differ only in the defect: lower case, no space, a leading digit and the dot, dash and underscore separators.
        assertEquals(emptyList(), validate(schemaYaml("        rules: [planning-sweeps, 9lives, a.b_c-d]\n")).errors)
    }

    @Test
    fun `S3 a trait seat reports a bad rules element under the trait seat entry`() {
        val yaml =
            "traits:\n  t1:\n    seats:\n      - name: extra\n        phase: work\n        rules: [ok-key, \"Bad Key!\"]\n" +
                "    notes: []\n"
        val d = doc(yaml)
        assertTrue(
            d.warningViolations.any { it.path == "/traits/t1/seats/0/rules/1" },
            "warnings: ${d.warningViolations}"
        )
        assertEquals(
            listOf("ok-key"),
            d.traitSeats
                .getValue("t1")
                .single()
                .rules
        )
    }

    // ------------------------------------------------------------------ S4

    @Test
    fun `S4 duplicates collapse keeping the first position`() {
        assertEquals(listOf("b-rule", "a-rule"), rulesOf(schemaYaml("        rules: [b-rule, a-rule, b-rule, a-rule]\n")))
    }

    @Test
    fun `S4 a trait seat's rules survive the merge into the item schema, including a demoted second enters seat`() {
        val base = WorkItemSchema(type = "bug-fix", seats = listOf(SeatDefinition("implementer", Role.WORK, enters = true)))
        val traitDoc =
            ConfigDocument(
                workItemSchemas = mapOf("bug-fix" to base),
                traits = mapOf("t1" to emptyList()),
                traitSeats =
                    mapOf(
                        "t1" to
                            listOf(
                                SeatDefinition("z", Role.WORK, enters = true, rules = listOf("x-rule", "y-rule")),
                                SeatDefinition("e", Role.WORK, rules = emptyList())
                            )
                    )
            )
        val config =
            LayeredConfig(UUID.randomUUID(), null, LayerBackedGlobalLookup(ConfigLayer(traitDoc, "global-fp", ConfigSource.GLOBAL)))
        val item = WorkItem(title = "S4 item", type = "bug-fix", role = Role.WORK, depth = 0, traits = listOf("t1"))

        val merged = TraitResolver(config).mergeSchema(base, item.traits, item.id)

        val z = merged.seats.single { it.name == "z" }
        assertFalse(z.enters, "a second enters seat in the phase is demoted (existing rule)")
        assertEquals(listOf("x-rule", "y-rule"), z.rules, "the demotion must not drop the declared rules")
        assertEquals(emptyList(), merged.seats.single { it.name == "e" }.rules, "declared-empty stays empty, not null")
        assertNull(merged.seats.single { it.name == "implementer" }.rules, "an undeclared base seat stays null")
    }

    // ------------------------------------------------------------------ S5

    private fun seatEntrySchema(): JsonObject {
        val root = Json.parseToJsonElement(ConfigJsonSchema.text)

        fun walk(e: JsonElement): JsonObject? {
            when (e) {
                is JsonObject -> {
                    val props = e["properties"] as? JsonObject
                    if (props != null && props.containsKey("reads_exclude") && props.containsKey("phase")) return e
                    for (v in e.values) walk(v)?.let { return it }
                }
                is JsonArray -> for (v in e) walk(v)?.let { return it }
                else -> Unit
            }
            return null
        }
        return assertNotNull(walk(root), "the seat entry definition must be findable by its reads_exclude and phase properties")
    }

    @Test
    fun `S5 the config JSON schema declares seat rules as a string array with the key pattern and stays closed`() {
        val entry = seatEntrySchema()
        val rules =
            entry
                .getValue("properties")
                .jsonObject
                .getValue("rules")
                .jsonObject
        assertEquals("array", rules.getValue("type").jsonPrimitive.content)
        val items = rules.getValue("items").jsonObject
        assertEquals("string", items.getValue("type").jsonPrimitive.content)
        assertEquals("^[a-z0-9][a-z0-9._-]{0,99}\$", items.getValue("pattern").jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), entry["additionalProperties"], "the seat entry stays closed")
    }

    @Test
    fun `S5 the parser key catalog and the schema's seat properties name the same keys`() {
        val props = seatEntrySchema().getValue("properties").jsonObject.keys
        assertTrue("rules" in ConfigKeyCatalog.SEAT_ENTRY)
        assertEquals(ConfigKeyCatalog.SEAT_ENTRY, props)
    }

    @Test
    fun `S5 control - an unknown seat key is still rejected in STRICT`() {
        val yaml = schemaYaml("        rulez: [x]\n")
        assertTrue(errorAt(validate(yaml), "$seatPath/rulez"), "strict errors: ${validate(yaml).errors}")
    }
}
