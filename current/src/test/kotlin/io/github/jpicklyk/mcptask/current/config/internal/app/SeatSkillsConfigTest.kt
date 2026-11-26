package io.github.jpicklyk.mcptask.current.config.internal.app

import io.github.jpicklyk.mcptask.current.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.config.ConfigSource
import io.github.jpicklyk.mcptask.current.config.ConfigValidationResult
import io.github.jpicklyk.mcptask.current.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.config.GlobalConfigSource
import io.github.jpicklyk.mcptask.current.config.LayerBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.config.ParseMode
import io.github.jpicklyk.mcptask.current.config.internal.wire.ConfigJsonSchema
import io.github.jpicklyk.mcptask.current.config.internal.wire.ConfigKeyCatalog
import io.github.jpicklyk.mcptask.current.config.internal.wire.NetworkntConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.config.internal.wire.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.lifecycle.internal.app.buildSeatsJson
import io.github.jpicklyk.mcptask.current.model.config.ConfigScope
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.model.note.NoteSchemaEntry
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
 * Independent tests (test-author seat, item 8fc593a7 / F3-A) for the seat `skills:` key: scenarios S1 to S6 and S8 of the frozen
 * test-plan (S7 and S11 are in `SeatSkillsServingTest`, S9 and S10 in `CallerSeatTest`, S12 is dropped).
 *
 * Oracles (never read from a run): task-scope AC1 (a seat accepts `skills` as a list of non-blank strings; absent is null, `[]` is an
 * empty list; a non-list or a blank element is a finding, as for seat rules), AC2 (served only when declared), AC5 (merge unchanged:
 * first declaration wins, a per-root trait replaces a server trait whole), D1 (each element is a String that is not blank, kept
 * verbatim without trim or pattern; a non-string or blank element is a soft finding at `<entry>/skills/<i>` and dropped, the rest
 * kept in order; duplicates collapse keeping the first position with no finding; a non-list value, null included, is a soft finding
 * at `<entry>/skills` and the field is null; soft = warning in MIGRATION, error in STRICT; JSON schema
 * `{"type":"array","items":{"type":"string","minLength":1}}`, no uniqueItems), D3 (served right after rules, only when non-null) and
 * the config JSON Schema contract (a closed seat entry, one declared property per catalog key).
 *
 * Negative-assertion conditions: every rejected document is paired with a repaired twin that differs only in the defective value;
 * the twin must be admitted, so a validator that rejects every seat fails the twin.
 *
 * Domain invariants satisfied by construction: [SeatDefinition] has no cross-field invariant beyond a non-blank name; documents are
 * parsed from YAML text with at most one `enters: true` seat per phase.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
class SeatSkillsConfigTest {
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

    private fun schemaYaml(skillsLine: String): String =
        "work_item_schemas:\n  bug-fix:\n    seats:\n" +
            "      - name: planner\n        phase: queue\n$skillsLine" +
            "    notes: []\n"

    private fun skillsOf(yaml: String): List<String>? =
        doc(yaml)
            .workItemSchemas
            .getValue("bug-fix")
            .seats
            .single { it.name == "planner" }
            .skills

    private val seatPath = "/work_item_schemas/bug-fix/seats/0"

    private fun skillFindingPaths(yaml: String): List<String> =
        doc(yaml).warningViolations.map { it.path }.filter { it == "$seatPath/skills" || it.startsWith("$seatPath/skills/") }

    // ------------------------------------------------------------------ S1

    @Test
    fun `S1 a seat skills list parses in declared order, keeps a plugin prefix, and the document validates clean`() {
        val yaml = schemaYaml("        skills: [alpha, \"plugin:beta\"]\n")
        assertEquals(listOf("alpha", "plugin:beta"), skillsOf(yaml))
        assertEquals(emptyList(), validate(yaml).errors, "strict validation of a declared skills list")
        assertEquals(emptyList(), skillFindingPaths(yaml))
        // Order is semantic: the reversed declaration parses reversed.
        assertEquals(
            listOf("plugin:beta", "alpha"),
            skillsOf(schemaYaml("        skills: [\"plugin:beta\", alpha]\n"))
        )
    }

    // ------------------------------------------------------------------ S2

    @Test
    fun `S2 absent skills is null and served without a skills member, empty skills is an empty list and served as an empty array`() {
        val absentYaml = schemaYaml("")
        assertNull(skillsOf(absentYaml), "absent must stay null, distinct from empty")
        val emptyYaml = schemaYaml("        skills: []\n")
        assertEquals(emptyList(), skillsOf(emptyYaml), "an explicit empty list is declared-empty, not absent")
        assertEquals(emptyList(), validate(absentYaml).errors)
        assertEquals(emptyList(), validate(emptyYaml).errors)
        assertEquals(emptyList(), skillFindingPaths(emptyYaml), "an empty list is no finding")

        val servedAbsent = assertNotNull(buildSeatsJson(listOf(SeatDefinition("planner", Role.QUEUE, skills = null))))
        assertFalse(servedAbsent[0].jsonObject.containsKey("skills"), "absent: no skills member: ${servedAbsent[0]}")
        val servedEmpty = assertNotNull(buildSeatsJson(listOf(SeatDefinition("planner", Role.QUEUE, skills = emptyList()))))
        assertEquals(JsonArray(emptyList()), servedEmpty[0].jsonObject["skills"], "declared empty: served []")
        val servedDeclared =
            assertNotNull(buildSeatsJson(listOf(SeatDefinition("planner", Role.QUEUE, skills = listOf("zeta", "plugin:beta", "alpha")))))
        assertEquals(
            listOf("zeta", "plugin:beta", "alpha"),
            servedDeclared[0]
                .jsonObject
                .getValue("skills")
                .jsonArray
                .map { it.jsonPrimitive.content },
            "declared: served in order"
        )
    }

    // ------------------------------------------------------------------ S3

    @Test
    fun `S3 a non-list skills value is one finding at the skills pointer and the field is null - warning in MIGRATION, error in STRICT`() {
        for (line in listOf("        skills: abc\n", "        skills: ~\n")) {
            val yaml = schemaYaml(line)
            val d = doc(yaml)
            assertEquals(emptyList(), d.errors, line)
            assertEquals(1, d.warningViolations.count { it.path == "$seatPath/skills" }, "$line warnings: ${d.warningViolations}")
            assertNull(skillsOf(yaml), "$line: a non-list value leaves the field null")
            assertTrue(errorAt(validate(yaml), "$seatPath/skills"), "$line strict errors: ${validate(yaml).errors}")
        }
        // Twin: the same seat with a list is admitted.
        assertEquals(emptyList(), validate(schemaYaml("        skills: [abc]\n")).errors)
    }

    // ------------------------------------------------------------------ S4

    @Test
    fun `S4 each non-string or blank element is a finding at its own index and the valid elements are kept in order`() {
        val yaml = schemaYaml("        skills: [ok, 42, \"\", \"  \", ~, fine]\n")
        assertEquals(
            listOf("$seatPath/skills/1", "$seatPath/skills/2", "$seatPath/skills/3", "$seatPath/skills/4"),
            skillFindingPaths(yaml).sorted(),
            "findings at indexes 1 to 4 only, one each"
        )
        assertEquals(listOf("ok", "fine"), skillsOf(yaml))
        val strict = validate(yaml)
        for (i in 1..4) assertTrue(errorAt(strict, "$seatPath/skills/$i"), "strict index $i: ${strict.errors}")
        assertFalse(errorAt(strict, "$seatPath/skills/0"), "a valid element has no strict error: ${strict.errors}")
        assertFalse(errorAt(strict, "$seatPath/skills/5"), "a valid element has no strict error: ${strict.errors}")
        // Twin: the same list without the defective elements is clean.
        assertEquals(emptyList(), validate(schemaYaml("        skills: [ok, fine]\n")).errors)
    }

    @Test
    fun `S4 a trait seat reports a bad skills element under the trait seat entry`() {
        val yaml =
            "traits:\n  t1:\n    seats:\n      - name: extra\n        phase: work\n        skills: [ok-skill, 42]\n" +
                "    notes: []\n"
        val d = doc(yaml)
        assertTrue(
            d.warningViolations.any { it.path == "/traits/t1/seats/0/skills/1" },
            "warnings: ${d.warningViolations}"
        )
        assertEquals(
            listOf("ok-skill"),
            d.traitSeats
                .getValue("t1")
                .single()
                .skills
        )
    }

    // ------------------------------------------------------------------ S5

    @Test
    fun `S5 duplicates collapse keeping the first position with no finding`() {
        val yaml = schemaYaml("        skills: [b, a, b]\n")
        assertEquals(listOf("b", "a"), skillsOf(yaml))
        assertEquals(emptyList(), skillFindingPaths(yaml), "a duplicate is no finding")
        assertEquals(emptyList(), validate(yaml).errors, "a duplicate is no strict error")
    }

    @Test
    fun `S5 casing is kept and a name with leading or inner whitespace is kept verbatim`() {
        assertEquals(listOf("Foo", "foo"), skillsOf(schemaYaml("        skills: [Foo, foo]\n")))
        val yaml = schemaYaml("        skills: [\" x\", \"a b\"]\n")
        assertEquals(listOf(" x", "a b"), skillsOf(yaml), "no trim, no pattern")
        assertEquals(emptyList(), skillFindingPaths(yaml))
        assertEquals(emptyList(), validate(yaml).errors)
    }

    // ------------------------------------------------------------------ S6

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
    fun `S6 the config JSON schema declares seat skills as an array of non-empty strings without pattern or uniqueness and stays closed`() {
        val entry = seatEntrySchema()
        val skills =
            entry
                .getValue("properties")
                .jsonObject
                .getValue("skills")
                .jsonObject
        assertEquals("array", skills.getValue("type").jsonPrimitive.content)
        val items = skills.getValue("items").jsonObject
        assertEquals("string", items.getValue("type").jsonPrimitive.content)
        assertEquals(JsonPrimitive(1), items["minLength"], "a blank-by-length element is a schema error")
        assertFalse(items.containsKey("pattern"), "a plugin skill carries a prefix: no pattern: $items")
        assertFalse(skills.containsKey("uniqueItems"), "duplicates are not an error: $skills")
        assertEquals(JsonPrimitive(false), entry["additionalProperties"], "the seat entry stays closed")
    }

    @Test
    fun `S6 the parser key catalog and the schema's seat properties name the same keys, skills included`() {
        val props = seatEntrySchema().getValue("properties").jsonObject.keys
        assertTrue("skills" in ConfigKeyCatalog.SEAT_ENTRY)
        assertTrue("skills" in props)
        assertEquals(ConfigKeyCatalog.SEAT_ENTRY, props)
    }

    @Test
    fun `S6 control - an unknown seat key such as skillz or Skills is still rejected in STRICT while skills is not`() {
        for (key in listOf("skillz", "Skills")) {
            val yaml = schemaYaml("        $key: [x]\n")
            assertTrue(errorAt(validate(yaml), "$seatPath/$key"), "$key strict errors: ${validate(yaml).errors}")
        }
        assertEquals(emptyList(), validate(schemaYaml("        skills: [x]\n")).errors, "the twin spelled right is clean")
    }

    // ------------------------------------------------------------------ S8

    private fun globalLookup(doc: ConfigDocument) = LayerBackedGlobalLookup(ConfigLayer(doc, "global-fp", ConfigSource.GLOBAL))

    @Test
    fun `S8 a trait seat's skills survive the merge into the item schema, including a demoted second enters seat`() {
        val base = WorkItemSchema(type = "bug-fix", seats = listOf(SeatDefinition("implementer", Role.WORK, enters = true)))
        val traitDoc =
            ConfigDocument(
                workItemSchemas = mapOf("bug-fix" to base),
                traits = mapOf("t1" to emptyList()),
                traitSeats =
                    mapOf(
                        "t1" to
                            listOf(
                                SeatDefinition("z", Role.WORK, enters = true, skills = listOf("x-skill", "plugin:y")),
                                SeatDefinition("e", Role.WORK, skills = emptyList())
                            )
                    )
            )
        val config = LayeredConfig(UUID.randomUUID(), null, globalLookup(traitDoc))
        val item = WorkItem(title = "S8 item", type = "bug-fix", role = Role.WORK, depth = 0, traits = listOf("t1"))

        val merged = TraitResolver(config).mergeSchema(base, item.traits, item.id)

        val z = merged.seats.single { it.name == "z" }
        assertFalse(z.enters, "a second enters seat in the phase is demoted (existing rule)")
        assertEquals(listOf("x-skill", "plugin:y"), z.skills, "the demotion must not drop the declared skills")
        assertEquals(emptyList(), merged.seats.single { it.name == "e" }.skills, "declared-empty stays empty, not null")
        assertNull(merged.seats.single { it.name == "implementer" }.skills, "an undeclared base seat stays null")
    }

    @Test
    fun `S8 a same-named base seat wins and the trait's skills are not added`() {
        fun mergedImplementer(baseSkills: List<String>?): SeatDefinition {
            val base =
                WorkItemSchema(
                    type = "bug-fix",
                    seats = listOf(SeatDefinition("implementer", Role.WORK, enters = true, skills = baseSkills))
                )
            val traitDoc =
                ConfigDocument(
                    workItemSchemas = mapOf("bug-fix" to base),
                    traits = mapOf("t1" to emptyList()),
                    traitSeats = mapOf("t1" to listOf(SeatDefinition("implementer", Role.WORK, skills = listOf("from-trait"))))
                )
            val config = LayeredConfig(UUID.randomUUID(), null, globalLookup(traitDoc))
            val item = WorkItem(title = "S8 item", type = "bug-fix", role = Role.WORK, depth = 0, traits = listOf("t1"))
            return TraitResolver(config).mergeSchema(base, item.traits, item.id).seats.single { it.name == "implementer" }
        }
        assertNull(mergedImplementer(null).skills, "the base seat declares none, and the trait's list is not borrowed")
        assertEquals(listOf("from-base"), mergedImplementer(listOf("from-base")).skills, "the base list stands")
    }

    @Test
    fun `S8 a per-root trait replaces the server trait whole, so a seat without skills serves none`() {
        val base = WorkItemSchema(type = "bug-fix", seats = listOf(SeatDefinition("implementer", Role.WORK, enters = true)))
        val globalDoc =
            ConfigDocument(
                workItemSchemas = mapOf("bug-fix" to base),
                traits = mapOf("T" to listOf(NoteSchemaEntry(key = "global-note", role = Role.QUEUE))),
                traitSeats = mapOf("T" to listOf(SeatDefinition("s1", Role.WORK, skills = listOf("global-skill"))))
            )
        val perRootDoc =
            ConfigDocument(
                workItemSchemas = emptyMap(),
                traits = mapOf("T" to listOf(NoteSchemaEntry(key = "per-root-note", role = Role.QUEUE))),
                traitSeats = mapOf("T" to listOf(SeatDefinition("s1", Role.WORK)))
            )
        val item = WorkItem(title = "S8 item", type = "bug-fix", role = Role.WORK, depth = 0, traits = listOf("T"))

        // Control: with no per-root layer the server trait's seat serves its skills.
        val serverOnly = LayeredConfig(UUID.randomUUID(), null, globalLookup(globalDoc))
        val serverMerged = TraitResolver(serverOnly).mergeSchema(base, item.traits, item.id)
        assertEquals(listOf("global-skill"), serverMerged.seats.single { it.name == "s1" }.skills)

        val layered = LayeredConfig(UUID.randomUUID(), ConfigLayer(perRootDoc, "pr-fp", ConfigSource.PER_ROOT), globalLookup(globalDoc))
        val merged = TraitResolver(layered).mergeSchema(base, item.traits, item.id)
        val s1 = merged.seats.single { it.name == "s1" }
        assertNull(s1.skills, "the per-root trait declares s1 without skills, so the server list does not leak through")
        val served = assertNotNull(buildSeatsJson(merged.seats))
        val servedS1 = served.map { it.jsonObject }.single { it["name"]?.jsonPrimitive?.content == "s1" }
        assertFalse(servedS1.containsKey("skills"), "no skills member is served: $servedS1")
    }
}
