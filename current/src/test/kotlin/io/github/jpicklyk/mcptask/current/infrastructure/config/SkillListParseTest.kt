package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.note.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.model.schema.SkillMode
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 79a61aba / U1) for the parse side of `skill:` as a list and of `skill_mode`
 * on a note entry (scenarios S1, S5-S9, S11 and their probes of the frozen test-plan).
 *
 * Oracle: the frozen task-scope clauses "Decision", "Config" and "Soft findings", applied by hand: a single string or a
 * one-element list is the same entry; the entry's `skill` is its first skill; STRICT turns each finding into an error and
 * MIGRATION keeps it a warning at the same RFC 6901 path (`<entry>/skill`, `<entry>/skill/<i>`, `<entry>/skill_mode`);
 * a bad `skill` leaves no skill; a bad or orphan `skill_mode` leaves INVOKE; a blank STRING `skill` is kept as-is (today's
 * behaviour). The same parse serves a schema entry and a trait entry.
 *
 * Ambiguity, not resolved from the implementation: whether `skill: ~` (YAML null) draws a finding. The task-scope says
 * "neither string nor list" is a finding but names no null rule, so the null probe asserts only the entry (no skill) and
 * leaves the finding count unasserted.
 *
 * NEW-SURFACE: S1 and S11 bind to SkillMode, NoteSchemaEntry.skills and NoteSchemaEntry.skillMode; a plain revert only
 * fails to compile. Forbidden-construct declaration: none.
 */
class SkillListParseTest {
    private val schemaPath = "/work_item_schemas/t/notes/0"
    private val traitPath = "/traits/tr/notes/0"

    private fun schemaYaml(vararg extra: String): String =
        "work_item_schemas:\n  t:\n    notes:\n      - key: n\n        role: work\n        required: true\n" +
            extra.joinToString("") { "        $it\n" }

    private fun traitYaml(vararg extra: String): String =
        "work_item_schemas:\n  t:\n    notes: []\n" +
            "traits:\n  tr:\n    notes:\n      - key: n\n        role: work\n        required: true\n" +
            extra.joinToString("") { "        $it\n" }

    private fun parsed(
        yaml: String,
        mode: ParseMode
    ): ConfigDocument {
        val outcome = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = mode)
        return assertIs<ConfigDocumentParser.Outcome.Parsed>(outcome, "expected Parsed for $mode: $outcome").document
    }

    private fun schemaEntry(doc: ConfigDocument): NoteSchemaEntry =
        doc.workItemSchemas
            .getValue("t")
            .notes
            .single { it.key == "n" }

    private fun traitEntry(doc: ConfigDocument): NoteSchemaEntry = doc.traits.getValue("tr").single { it.key == "n" }

    private fun ConfigDocument.errorPaths() = errors.map { it.path }

    private fun ConfigDocument.warningPaths() = warningViolations.map { it.path }

    // ---------------------------------------------------------------------------------------------
    // S1 - a list with a method-on-diff mode, on a schema entry and a trait entry, STRICT
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 a skill list with method-on-diff parses with no STRICT error on a schema entry`() {
        val doc = parsed(schemaYaml("skill: [rule-a, rule-b]", "skill_mode: method-on-diff"), ParseMode.STRICT)

        assertEquals(emptyList(), doc.errors, "errors: ${doc.errors}")
        val entry = schemaEntry(doc)
        assertEquals("rule-a", entry.skill, "skill is the first skill")
        assertEquals(listOf("rule-a", "rule-b"), entry.skills)
        assertEquals(SkillMode.METHOD_ON_DIFF, entry.skillMode)
    }

    @Test
    fun `S1 the same list and mode parse identically on a trait entry`() {
        val doc = parsed(traitYaml("skill: [rule-a, rule-b]", "skill_mode: method-on-diff"), ParseMode.STRICT)

        assertEquals(emptyList(), doc.errors, "errors: ${doc.errors}")
        val entry = traitEntry(doc)
        assertEquals("rule-a", entry.skill)
        assertEquals(listOf("rule-a", "rule-b"), entry.skills)
        assertEquals(SkillMode.METHOD_ON_DIFF, entry.skillMode)
    }

    @Test
    fun `S1 probe - order is invocation order and the first skill follows it`() {
        val entry = schemaEntry(parsed(schemaYaml("skill: [rule-b, rule-a]"), ParseMode.STRICT))

        assertEquals("rule-b", entry.skill)
        assertEquals(listOf("rule-b", "rule-a"), entry.skills)
    }

    @Test
    fun `S1 a list parses the same in MIGRATION as in STRICT`() {
        val strict = parsed(schemaYaml("skill: [rule-a, rule-b]", "skill_mode: method-on-diff"), ParseMode.STRICT)
        val migration = parsed(schemaYaml("skill: [rule-a, rule-b]", "skill_mode: method-on-diff"), ParseMode.MIGRATION)

        assertEquals(schemaEntry(strict), schemaEntry(migration))
        assertEquals(emptyList(), migration.errors)
        assertEquals(emptyList(), migration.warningViolations.filter { it.path.startsWith(schemaPath) })
    }

    // ---------------------------------------------------------------------------------------------
    // S5 - a string and a one-element list are the same entry
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 skill a and skill list of a parse to equal entries with skills of just a and mode invoke`() {
        val asString = schemaEntry(parsed(schemaYaml("skill: rule-a"), ParseMode.STRICT))
        val asList = schemaEntry(parsed(schemaYaml("skill: [rule-a]"), ParseMode.STRICT))

        assertEquals(asString, asList)
        assertEquals("rule-a", asList.skill)
        assertEquals(listOf("rule-a"), asList.skills)
        assertEquals(SkillMode.INVOKE, asList.skillMode)
    }

    @Test
    fun `S5 a one-element list draws no STRICT error and no MIGRATION warning`() {
        val strict = parsed(schemaYaml("skill: [rule-a]"), ParseMode.STRICT)
        val migration = parsed(schemaYaml("skill: [rule-a]"), ParseMode.MIGRATION)

        assertEquals(emptyList(), strict.errors)
        assertEquals(emptyList(), migration.warningViolations.filter { it.path.startsWith(schemaPath) })
    }

    // ---------------------------------------------------------------------------------------------
    // S6 - a duplicate name: one finding at the later copy, the first kept
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 a duplicate skill name is one STRICT error at skill 1 and the first copy is kept`() {
        val strict = parsed(schemaYaml("skill: [rule-a, rule-a]"), ParseMode.STRICT)

        assertEquals(listOf("$schemaPath/skill/1"), strict.errorPaths())
        val entry = schemaEntry(strict)
        assertEquals("rule-a", entry.skill)
        assertEquals(listOf("rule-a"), entry.skills, "the duplicate is dropped, not kept twice")
    }

    @Test
    fun `S6 a duplicate in MIGRATION is one warning at the same path and serves as a single skill`() {
        val migration = parsed(schemaYaml("skill: [rule-a, rule-a]"), ParseMode.MIGRATION)

        assertEquals(emptyList(), migration.errors)
        assertEquals(1, migration.warningPaths().count { it == "$schemaPath/skill/1" }, "warnings: ${migration.warningViolations}")
        assertEquals(schemaEntry(parsed(schemaYaml("skill: rule-a"), ParseMode.MIGRATION)), schemaEntry(migration))
    }

    @Test
    fun `S6 a later duplicate among three names is reported at its own index`() {
        val strict = parsed(schemaYaml("skill: [rule-a, rule-b, rule-a]"), ParseMode.STRICT)

        assertEquals(listOf("$schemaPath/skill/2"), strict.errorPaths())
        assertEquals(listOf("rule-a", "rule-b"), schemaEntry(strict).skills)
    }

    // ---------------------------------------------------------------------------------------------
    // S7 - an explicit default mode is the default
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S7 an explicit skill_mode invoke equals the entry without skill_mode and draws no finding`() {
        val explicit = parsed(schemaYaml("skill: rule-a", "skill_mode: invoke"), ParseMode.STRICT)
        val implicit = parsed(schemaYaml("skill: rule-a"), ParseMode.STRICT)

        assertEquals(emptyList(), explicit.errors)
        assertEquals(schemaEntry(implicit), schemaEntry(explicit))
        assertEquals(SkillMode.INVOKE, schemaEntry(explicit).skillMode)
    }

    // ---------------------------------------------------------------------------------------------
    // S8 - a bad skill value: one STRICT error, a MIGRATION warning, no skill
    // ---------------------------------------------------------------------------------------------

    private fun assertBadSkill(
        extra: String,
        path: String
    ) {
        val strict = parsed(schemaYaml(extra), ParseMode.STRICT)
        assertEquals(listOf(path), strict.errorPaths(), "STRICT errors for `$extra`: ${strict.errors}")
        assertNull(schemaEntry(strict).skill, "no skill for `$extra`")
        assertEquals(emptyList(), schemaEntry(strict).skills, "no skills for `$extra`")

        val migration = parsed(schemaYaml(extra), ParseMode.MIGRATION)
        assertEquals(emptyList(), migration.errors, "MIGRATION is lenient for `$extra`")
        assertEquals(1, migration.warningPaths().count { it == path }, "MIGRATION warnings for `$extra`: ${migration.warningViolations}")
        assertNull(schemaEntry(migration).skill)
        assertEquals(emptyList(), schemaEntry(migration).skills)
    }

    @Test
    fun `S8 an empty list is a finding at skill`(): Unit = assertBadSkill("skill: []", "$schemaPath/skill")

    @Test
    fun `S8 a number is a finding at skill`(): Unit = assertBadSkill("skill: 7", "$schemaPath/skill")

    @Test
    fun `S8 a non-string element is a finding at skill 1 and the whole skill is ignored`(): Unit =
        assertBadSkill("skill: [rule-a, 3]", "$schemaPath/skill/1")

    @Test
    fun `S8 a blank element is a finding at skill 1 and the whole skill is ignored`(): Unit =
        assertBadSkill("skill: [rule-a, \"\"]", "$schemaPath/skill/1")

    @Test
    fun `S8 probe - a whitespace-only element is blank`(): Unit = assertBadSkill("skill: [rule-a, \"   \"]", "$schemaPath/skill/1")

    @Test
    fun `S8 the same findings are reported for a trait entry`() {
        val strict = parsed(traitYaml("skill: [rule-a, 3]"), ParseMode.STRICT)

        assertEquals(listOf("$traitPath/skill/1"), strict.errorPaths())
        assertNull(traitEntry(strict).skill)
    }

    // ---------------------------------------------------------------------------------------------
    // S9 - a bad or orphan skill_mode
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 an unknown skill_mode is one STRICT error at skill_mode and the mode is invoke, skill kept`() {
        val strict = parsed(schemaYaml("skill: rule-a", "skill_mode: diff"), ParseMode.STRICT)

        assertEquals(listOf("$schemaPath/skill_mode"), strict.errorPaths())
        val entry = schemaEntry(strict)
        assertEquals(SkillMode.INVOKE, entry.skillMode)
        assertEquals("rule-a", entry.skill)

        val migration = parsed(schemaYaml("skill: rule-a", "skill_mode: diff"), ParseMode.MIGRATION)
        assertEquals(emptyList(), migration.errors)
        assertEquals(1, migration.warningPaths().count { it == "$schemaPath/skill_mode" })
        assertEquals(SkillMode.INVOKE, schemaEntry(migration).skillMode)
    }

    @Test
    fun `S9 probe - the mode is exact and case-sensitive`() {
        val strict = parsed(schemaYaml("skill: rule-a", "skill_mode: Method-On-Diff"), ParseMode.STRICT)

        assertEquals(listOf("$schemaPath/skill_mode"), strict.errorPaths())
        assertEquals(SkillMode.INVOKE, schemaEntry(strict).skillMode)
    }

    @Test
    fun `S9 a non-string skill_mode is a finding at skill_mode and the mode is invoke`() {
        val strict = parsed(schemaYaml("skill: rule-a", "skill_mode: 5"), ParseMode.STRICT)

        assertEquals(listOf("$schemaPath/skill_mode"), strict.errorPaths())
        assertEquals(SkillMode.INVOKE, schemaEntry(strict).skillMode)
    }

    @Test
    fun `S9 method-on-diff with no skill is one finding at skill_mode and no mode is emitted`() {
        val strict = parsed(schemaYaml("skill_mode: method-on-diff"), ParseMode.STRICT)

        assertEquals(listOf("$schemaPath/skill_mode"), strict.errorPaths())
        val entry = schemaEntry(strict)
        assertNull(entry.skill)
        assertEquals(SkillMode.INVOKE, entry.skillMode)

        val migration = parsed(schemaYaml("skill_mode: method-on-diff"), ParseMode.MIGRATION)
        assertEquals(emptyList(), migration.errors)
        assertEquals(1, migration.warningPaths().count { it == "$schemaPath/skill_mode" })
        assertEquals(SkillMode.INVOKE, schemaEntry(migration).skillMode)
    }

    // ---------------------------------------------------------------------------------------------
    // Probes: null vs absent vs blank, CRLF
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `probe - skill absent has no skill and no finding`() {
        val strict = parsed(schemaYaml(), ParseMode.STRICT)

        assertEquals(emptyList(), strict.errors)
        val entry = schemaEntry(strict)
        assertNull(entry.skill)
        assertEquals(emptyList(), entry.skills)
        assertEquals(SkillMode.INVOKE, entry.skillMode)
    }

    @Test
    fun `probe - a YAML null skill leaves no skill`() {
        val entry = schemaEntry(parsed(schemaYaml("skill: ~"), ParseMode.MIGRATION))

        assertNull(entry.skill)
        assertEquals(emptyList(), entry.skills)
    }

    @Test
    fun `probe - a blank string skill is kept as-is with no finding`() {
        val strict = parsed(schemaYaml("skill: \"\""), ParseMode.STRICT)

        assertEquals(emptyList(), strict.errors, "a blank string stays today's behaviour: ${strict.errors}")
        assertEquals("", schemaEntry(strict).skill)
    }

    @Test
    fun `probe - CRLF line endings parse the list and mode the same as LF`() {
        val yaml = schemaYaml("skill: [rule-a, rule-b]", "skill_mode: method-on-diff")
        val crlf = parsed(yaml.replace("\n", "\r\n"), ParseMode.STRICT)

        assertEquals(emptyList(), crlf.errors)
        assertEquals(schemaEntry(parsed(yaml, ParseMode.STRICT)), schemaEntry(crlf))
    }

    // ---------------------------------------------------------------------------------------------
    // S17 - the live dogfood config: its security-assessment trait entry runs method-on-diff
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S17 live dogfood config parses STRICT clean and its security-assessment entry is method-on-diff`() {
        val live = String(Files.readAllBytes(StrictConfigCases.repoRoot().resolve(".taskorchestrator/config.yaml")), Charsets.UTF_8)

        val doc = parsed(live, ParseMode.STRICT)

        assertEquals(emptyList(), doc.errors, "live dogfood STRICT errors: ${doc.errors}")
        assertEquals(emptyList(), doc.warningViolations, "live dogfood STRICT warning violations")
        val entries = doc.traits.values.flatten() + doc.workItemSchemas.values.flatMap { it.notes }
        val security = entries.filter { it.key == "security-assessment" }
        assertTrue(security.isNotEmpty(), "the live config declares a security-assessment note")
        for (entry in security) {
            assertEquals(SkillMode.METHOD_ON_DIFF, entry.skillMode, "security-assessment mode")
            assertEquals("security-review", entry.skill)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // S11 - the entry invariant and SkillMode
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 skill a with skills of just b is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            NoteSchemaEntry(key = "n", role = Role.WORK, skill = "a", skills = listOf("b"))
        }
    }

    @Test
    fun `S11 skills with no skill is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            NoteSchemaEntry(key = "n", role = Role.WORK, skills = listOf("a", "b"))
        }
    }

    @Test
    fun `S11 skill a alone derives skills of just a and mode invoke`() {
        val entry = NoteSchemaEntry(key = "n", role = Role.WORK, skill = "a")

        assertEquals(listOf("a"), entry.skills)
        assertEquals(SkillMode.INVOKE, entry.skillMode)
    }

    @Test
    fun `S11 no skill derives an empty skills list`() {
        assertEquals(emptyList(), NoteSchemaEntry(key = "n", role = Role.WORK).skills)
    }

    @Test
    fun `S11 a first skill that matches skill with a longer list is accepted`() {
        val entry =
            NoteSchemaEntry(key = "n", role = Role.WORK, skill = "a", skills = listOf("a", "b"), skillMode = SkillMode.METHOD_ON_DIFF)

        assertEquals(listOf("a", "b"), entry.skills)
        assertEquals(SkillMode.METHOD_ON_DIFF, entry.skillMode)
    }

    @Test
    fun `S11 SkillMode wire values and exact-match lookup`() {
        assertEquals("invoke", SkillMode.INVOKE.wire)
        assertEquals("method-on-diff", SkillMode.METHOD_ON_DIFF.wire)
        assertEquals(SkillMode.INVOKE, SkillMode.fromConfigString("invoke"))
        assertEquals(SkillMode.METHOD_ON_DIFF, SkillMode.fromConfigString("method-on-diff"))
        assertNull(SkillMode.fromConfigString("Method-On-Diff"))
        assertNull(SkillMode.fromConfigString("METHOD_ON_DIFF"))
        assertNull(SkillMode.fromConfigString(" invoke"))
        assertNull(SkillMode.fromConfigString(""))
        assertTrue(SkillMode.entries.size == 2, "exactly two modes: ${SkillMode.entries}")
    }
}
