package io.github.jpicklyk.mcptask.current.config

import io.github.jpicklyk.mcptask.current.config.internal.wire.NetworkntConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.config.internal.wire.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.model.config.ConfigScope
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item c5eec1ca / F2-A) for [ConfigValidator.lint]: the warning-only structural findings
 * L1 to L9 of the frozen task-scope, scenarios S1 to S11 and S15 of the test-plan.
 *
 * Oracles (never read from a run): task-scope AC2 (the nine checks, each with its pointer form and, for L4 and L5, its reason
 * text), AC3 (no lint finding is an error or a warning and none changes validity), AC5 (a clean document has no findings), AC7
 * (no check for a project rule that shadows a protocol rule), and the sort rule "distinct, sorted by path then reason". Pointers
 * are RFC 6901 as [ConfigValidator.pointer] builds them. Where task-scope names a reason text only for L4 and L5 the tests
 * assert paths, never the wording of the other reasons.
 *
 * Fixtures: every document parses under [ParseMode.STRICT] with no errors and validates with no errors (checked by [lint]
 * before it asks for findings). A server layer is a parsed document wrapped in a [ConfigLayer]. Every negative assertion is
 * paired with a twin that differs only in the fixture condition that would produce the finding.
 *
 * Domain invariants satisfied by construction: at most one `enters: true` seat per schema, every note `seat` names a seat the
 * schema or one of its traits declares, every note role is a real [io.github.jpicklyk.mcptask.current.model.item.Role].
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions, no disjunctive assertions.
 */
class ConfigValidatorLintTest {
    private val validator = ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { null })

    private fun yaml(vararg lines: String): String = lines.joinToString("\n") + "\n"

    /** One note entry at list indent 6 (a schema's `notes:`), `guidance` and `skill` written as given. */
    private fun note(
        key: String,
        role: String,
        required: Boolean,
        seat: String? = null,
        guidance: String? = null,
        skill: String? = null,
    ): Array<String> =
        listOfNotNull(
            "      - key: $key",
            "        role: $role",
            "        required: $required",
            seat?.let { "        seat: $it" },
            guidance?.let { "        guidance: \"$it\"" },
            skill?.let { "        skill: $it" },
        ).toTypedArray()

    private fun parsed(text: String): ConfigDocumentParser.Outcome.Parsed {
        val outcome = YamlConfigDocumentParser.parse(text, warnOnMissingSchemas = false, mode = ParseMode.STRICT)
        val p = assertIs<ConfigDocumentParser.Outcome.Parsed>(outcome, "fixture must parse under STRICT: $outcome")
        assertEquals(emptyList(), p.document.errors, "fixture must be STRICT-clean")
        return p
    }

    private fun serverLayer(text: String): ConfigLayer = ConfigLayer(parsed(text).document, "server-fp", ConfigSource.GLOBAL)

    private fun lint(
        text: String,
        served: Set<String> = emptySet(),
        server: ConfigLayer? = null,
    ): List<ConfigViolation> {
        val p = parsed(text)
        assertEquals(emptyList(), validator.validate(p, ConfigScope.ROOT, server).errors, "fixture must validate without errors")
        return validator.lint(p, server, served)
    }

    private fun List<ConfigViolation>.paths(re: Regex): List<String> = map { it.path }.filter { re.matches(it) }

    private val seatRe = Regex("/(work_item_schemas|traits)/[^/]+/seats/\\d+")
    private val noteRe = Regex("/(work_item_schemas|traits)/[^/]+/notes/\\d+")
    private val guidanceRe = Regex(".*/notes/\\d+/guidance")
    private val typeRe = Regex("/work_item_schemas/[^/]+")
    private val traitRe = Regex("/traits/[^/]+")
    private val rulesRe = Regex(".*/seats/\\d+/rules/\\d+")
    private val seatSkillsRe = Regex(".*/seats/\\d+/skills/\\d+")
    private val noteSkillRe = Regex(".*/notes/\\d+/skill(/\\d+)?")

    // ------------------------------------------------------------------ S1 (L1, schema seats)

    @Test
    fun `S1 a schema seat that owns no note of its phase is flagged and the twin that owns one is not`() {
        val flagged =
            yaml(
                "work_item_schemas:",
                "  x:",
                "    seats:",
                "      - { name: planner, phase: queue }",
                "      - { name: implementer, phase: work, enters: true }",
                "    notes:",
                *note("scope", "queue", true, seat = "planner"),
            )
        val twin =
            flagged +
                note("impl", "work", true, seat = "implementer").joinToString("\n", postfix = "\n")

        assertEquals(listOf("/work_item_schemas/x/seats/1"), lint(flagged).paths(seatRe))
        assertEquals(emptyList(), lint(twin).paths(seatRe), "the same schema with a work note owned by implementer has no L1 finding")
    }

    @Test
    fun `S1 a seat owning only a note of another phase is flagged`() {
        val text =
            yaml(
                "work_item_schemas:",
                "  x:",
                "    seats:",
                "      - { name: planner, phase: queue }",
                "      - { name: implementer, phase: work, enters: true }",
                "    notes:",
                *note("scope", "queue", true, seat = "planner"),
                *note("early", "queue", true, seat = "implementer"),
            )

        assertEquals(listOf("/work_item_schemas/x/seats/1"), lint(text).paths(seatRe))
    }

    @Test
    fun `S1 a seat is satisfied by a work note that comes from a default trait`() {
        val withTrait =
            yaml(
                "work_item_schemas:",
                "  x:",
                "    default_traits: [t]",
                "    seats:",
                "      - { name: planner, phase: queue }",
                "      - { name: implementer, phase: work, enters: true }",
                "    notes:",
                *note("scope", "queue", true, seat = "planner"),
                "traits:",
                "  t:",
                "    applies_when: \"Always.\"",
                "    notes:",
                *note("trait-work", "work", false, seat = "implementer"),
            )
        val withoutTrait = withTrait.replace("    default_traits: [t]\n", "")

        assertEquals(emptyList(), lint(withTrait).paths(seatRe), "implementer owns trait-work through default_traits")
        assertEquals(
            listOf("/work_item_schemas/x/seats/1"),
            lint(withoutTrait).paths(seatRe),
            "the same document without default_traits leaves implementer with no work note",
        )
    }

    // ------------------------------------------------------------------ S2 (L1, trait seats)

    @Test
    fun `S2 a trait seat is satisfied by a listing schema note or its own note, and an orphan trait seat is flagged`() {
        val text =
            yaml(
                "work_item_schemas:",
                "  x:",
                "    default_traits: [t]",
                "    seats:",
                "      - { name: implementer, phase: work, enters: true }",
                "    notes:",
                *note("impl", "work", true, seat = "implementer"),
                *note("tm", "work", true, seat = "test-author"),
                "traits:",
                "  t:",
                "    applies_when: \"Always.\"",
                "    seats:",
                "      - { name: test-author, phase: work }",
                "  v:",
                "    applies_when: \"Always.\"",
                "    seats:",
                "      - { name: v-seat, phase: work }",
                "    notes:",
                *note("v-own", "work", false, seat = "v-seat"),
                "  u:",
                "    applies_when: \"Always.\"",
                "    seats:",
                "      - { name: u-seat, phase: work }",
            )

        assertEquals(
            listOf("/traits/u/seats/0"),
            lint(text).paths(seatRe),
            "t is owned through schema x, v through its own note, u through nothing",
        )
    }

    // ------------------------------------------------------------------ S3 (L2)

    private val l2Doc =
        yaml(
            "work_item_schemas:",
            "  x:",
            "    seats:",
            "      - { name: planner, phase: queue }",
            "      - { name: implementer, phase: work, enters: true }",
            "    notes:",
            *note("scope", "queue", true, seat = "planner"),
            *note("impl", "work", true, seat = "implementer"),
            *note("opt", "queue", false),
            *note("unseated-req", "queue", true),
            "  plain:",
            "    notes:",
            *note("o", "queue", false),
            "traits:",
            "  t:",
            "    applies_when: \"Always.\"",
            "    seats:",
            "      - { name: tseat, phase: work }",
            "    notes:",
            *note("own", "work", false, seat = "tseat"),
            *note("free", "work", false),
        )

    @Test
    fun `S3 an optional note with no seat is flagged on a seat-aware schema and on a trait with seats, never on a seatless schema`() {
        assertEquals(
            listOf("/traits/t/notes/1", "/work_item_schemas/x/notes/2"),
            lint(l2Doc).paths(noteRe),
            "opt (seat-aware schema) and free (trait with seats) are flagged; o (seatless plain) is not",
        )
    }

    @Test
    fun `S3 a required unseated note is not lint, it stays an ordinary warning`() {
        val p = parsed(l2Doc)
        val warnings = validator.validate(p, ConfigScope.ROOT, null).warnings

        assertFalse(lint(l2Doc).paths(noteRe).contains("/work_item_schemas/x/notes/3"), "unseated-req is not an L2 finding")
        assertTrue(
            warnings.any { (it.path + " " + it.reason).contains("unseated-req") },
            "the existing advisory warning still reports the required unseated note: $warnings",
        )
    }

    // ------------------------------------------------------------------ S4 (L3)

    @Test
    fun `S4 identical trimmed guidance across schemas and a trait is one finding at the first occurrence naming the others`() {
        val text =
            yaml(
                "work_item_schemas:",
                "  a:",
                "    notes:",
                *note("ka", "queue", false, guidance = "Same text"),
                "  b:",
                "    notes:",
                *note("kb0", "queue", false, guidance = "Other text"),
                *note("kb1", "queue", false, guidance = "Same text"),
                "  c:",
                "    notes:",
                *note("kc0", "queue", false, guidance = "Same text."),
                *note("kc1", "queue", false, guidance = "   "),
                *note("kc2", "queue", false, guidance = "   "),
                "traits:",
                "  t:",
                "    applies_when: \"Always.\"",
                "    notes:",
                *note("kt", "queue", false, guidance = "  Same text  "),
            )

        val findings = lint(text).filter { guidanceRe.matches(it.path) }

        assertEquals(listOf("/work_item_schemas/a/notes/0/guidance"), findings.map { it.path })
        val reason = findings.single().reason
        assertTrue(reason.contains("b/kb1"), "names the schema occurrence: $reason")
        assertTrue(reason.contains("t/kt"), "names the trait occurrence (trim applied): $reason")
        assertFalse(reason.contains("c/"), "'Same text.' and blank guidance are not part of the group: $reason")
    }

    // ------------------------------------------------------------------ S5 (L4)

    private val serverTypes =
        yaml(
            "work_item_schemas:",
            "  ft:",
            "    notes:",
            *note("a", "queue", false),
            "  srv:",
            "    notes:",
            *note("s", "queue", false),
        )

    @Test
    fun `S5 a per-root type that shadows a server type with changes lists what differs, and server-only types are not reported`() {
        val text =
            yaml(
                "work_item_schemas:",
                "  ft:",
                "    notes:",
                *note("a", "queue", true),
                *note("b", "queue", false),
            )

        val findings = lint(text, server = serverLayer(serverTypes)).filter { typeRe.matches(it.path) }

        assertEquals(listOf("/work_item_schemas/ft"), findings.map { it.path })
        assertTrue(findings.single().reason.contains("differs in: notes (added: b; changed: a)"), findings.single().reason)
    }

    @Test
    fun `S5 a per-root type identical to the server type is reported as shadowing an identical type`() {
        val text =
            yaml(
                "work_item_schemas:",
                "  ft:",
                "    notes:",
                *note("a", "queue", false),
            )

        val findings = lint(text, server = serverLayer(serverTypes)).filter { typeRe.matches(it.path) }

        assertEquals(listOf("/work_item_schemas/ft"), findings.map { it.path })
        assertTrue(findings.single().reason.contains("shadows an identical server type"), findings.single().reason)
    }

    @Test
    fun `S5 a lifecycle difference is reported`() {
        val server = serverLayer(yaml("work_item_schemas:", "  ft:", "    lifecycle: auto", "    notes: []"))
        val text = yaml("work_item_schemas:", "  ft:", "    lifecycle: manual", "    notes: []")

        val findings = lint(text, server = server).filter { typeRe.matches(it.path) }

        assertEquals(listOf("/work_item_schemas/ft"), findings.map { it.path })
        assertTrue(findings.single().reason.contains("differs in: lifecycle"), findings.single().reason)
    }

    @Test
    fun `S5 no server layer, or a type the server lacks, gives no shadow finding`() {
        val text =
            yaml(
                "work_item_schemas:",
                "  ft:",
                "    notes:",
                *note("a", "queue", false),
            )
        val other = text.replace("  ft:", "  other:")

        assertEquals(emptyList(), lint(text, server = null).paths(typeRe), "no server layer")
        assertEquals(emptyList(), lint(other, server = serverLayer(serverTypes)).paths(typeRe), "server lacks 'other'")
        assertEquals(
            listOf("/work_item_schemas/ft"),
            lint(text, server = serverLayer(serverTypes)).paths(typeRe),
            "control: the same document against a server that defines ft is reported",
        )
    }

    // ------------------------------------------------------------------ S6 (L5)

    private fun traitDoc(
        whenText: String,
        seatName: String,
        description: String? = null,
    ): String =
        yaml(
            "work_item_schemas: {}",
            "traits:",
            "  t:",
            *listOfNotNull(description?.let { "    description: \"$it\"" }).toTypedArray(),
            "    applies_when: \"$whenText\"",
            "    seats:",
            "      - { name: $seatName, phase: work }",
        )

    @Test
    fun `S6 a per-root trait that shadows a server trait lists the dimensions that differ in the stated order`() {
        val server = serverLayer(traitDoc("Server when.", "s"))
        val text = traitDoc("Root when.", "s2")

        val findings = lint(text, server = server).filter { it.path == "/traits/t" }

        assertEquals(1, findings.size, "exactly one finding at /traits/t: $findings")
        assertTrue(findings.single().reason.contains("differs in: seats, applies_when"), findings.single().reason)
    }

    @Test
    fun `S6 an identical per-root trait is reported as shadowing an identical server trait`() {
        val doc = traitDoc("Same when.", "s", description = "Same description.")

        val findings = lint(doc, server = serverLayer(doc)).filter { it.path == "/traits/t" }

        assertEquals(1, findings.size, "exactly one finding at /traits/t: $findings")
        assertTrue(findings.single().reason.contains("shadows an identical server trait"), findings.single().reason)
    }

    @Test
    fun `S6 a trait defined only in the per-root document gives no shadow finding`() {
        val doc = traitDoc("Same when.", "s")

        assertEquals(emptyList(), lint(doc, server = null).paths(traitRe))
        assertEquals(
            emptyList(),
            lint(doc, server = serverLayer(doc.replace("  t:", "  other:"))).paths(traitRe),
            "the server defines a different trait name",
        )
    }

    // ------------------------------------------------------------------ S7 (L6)

    @Test
    fun `S7 a trait with no applies_when, or a blank one, is flagged and a trait with text is not`() {
        val text =
            yaml(
                "work_item_schemas: {}",
                "traits:",
                "  a:",
                "    description: \"Only a description.\"",
                "  b:",
                "    description: \"Blank when.\"",
                "    applies_when: \"   \"",
                "  c:",
                "    description: \"Has a when.\"",
                "    applies_when: \"Real.\"",
            )

        assertEquals(listOf("/traits/a", "/traits/b"), lint(text).paths(traitRe))
    }

    // ------------------------------------------------------------------ S8 (L7)

    private val rulesDoc =
        yaml(
            "work_item_schemas:",
            "  x:",
            "    seats:",
            "      - { name: implementer, phase: work, enters: true, rules: [alpha, beta] }",
            "    notes:",
            *note("impl", "work", true, seat = "implementer"),
            "traits:",
            "  t:",
            "    applies_when: \"Always.\"",
            "    seats:",
            "      - { name: ts, phase: work, rules: [alpha, beta] }",
            "    notes:",
            *note("tn", "work", false, seat = "ts"),
        )

    @Test
    fun `S8 a seat rule that is not served is flagged on schema seats and trait seats`() {
        assertEquals(
            listOf("/traits/t/seats/0/rules/1", "/work_item_schemas/x/seats/0/rules/1"),
            lint(rulesDoc, served = setOf("alpha")).paths(rulesRe),
        )
    }

    @Test
    fun `S8 with nothing served every seat rule entry is flagged`() {
        assertEquals(
            listOf(
                "/traits/t/seats/0/rules/0",
                "/traits/t/seats/0/rules/1",
                "/work_item_schemas/x/seats/0/rules/0",
                "/work_item_schemas/x/seats/0/rules/1",
            ),
            lint(rulesDoc, served = emptySet()).paths(rulesRe),
        )
    }

    @Test
    fun `S8 an empty rules list and an absent rules key give no finding`() {
        val text =
            yaml(
                "work_item_schemas:",
                "  x:",
                "    seats:",
                "      - { name: planner, phase: queue, rules: [] }",
                "      - { name: implementer, phase: work, enters: true }",
                "    notes:",
                *note("scope", "queue", true, seat = "planner"),
                *note("impl", "work", true, seat = "implementer"),
            )

        assertEquals(emptyList(), lint(text, served = emptySet()).paths(rulesRe))
    }

    @Test
    fun `S8 a differently cased rule key never reaches lint because STRICT rejects it as an error first`() {
        val upper = rulesDoc.replace("rules: [alpha, beta]", "rules: [Alpha]")
        val lower = rulesDoc.replace("rules: [alpha, beta]", "rules: [alpha]")

        val rejected = validator.validate(upper, YamlConfigDocumentParser, ConfigScope.ROOT)

        assertEquals(
            listOf("/traits/t/seats/0/rules/0", "/work_item_schemas/x/seats/0/rules/0"),
            rejected.errors.map { it.path }.sorted(),
            "an uppercase rule key is a STRICT error at the entry: ${rejected.errors}",
        )
        val twin = validator.validate(lower, YamlConfigDocumentParser, ConfigScope.ROOT)
        assertEquals(emptyList(), twin.errors, "control: the lowercase twin")
        assertEquals(emptyList(), lint(lower, served = setOf("alpha")).paths(rulesRe), "control: served alpha is not flagged")
    }

    @Test
    fun `S8 a duplicated unserved entry is flagged and a served duplicate is not`() {
        val dup = rulesDoc.replace("rules: [alpha, beta]", "rules: [beta, beta]")

        val flagged = lint(dup, served = emptySet()).paths(rulesRe)
        assertTrue(flagged.contains("/work_item_schemas/x/seats/0/rules/0"), "first duplicate is flagged: $flagged")
        assertTrue(
            flagged.all { it.matches(Regex("/(traits/t|work_item_schemas/x)/seats/0/rules/[01]")) },
            "no finding outside the declared entries: $flagged",
        )
        assertEquals(emptyList(), lint(dup, served = setOf("beta")).paths(rulesRe), "control: served beta is not flagged")
    }

    @Test
    fun `S8 a pointer names the position in the document as written when an earlier entry is a duplicate`() {
        // Raw entries: 0 alpha (served), 1 alpha (served), 2 beta (unserved). The parser collapses the repeated alpha, so a
        // pointer built from the kept list would name /rules/1 (a served entry); the oracle is the document as written.
        val dup = rulesDoc.replace("rules: [alpha, beta]", "rules: [alpha, alpha, beta]")

        assertEquals(
            listOf("/traits/t/seats/0/rules/2", "/work_item_schemas/x/seats/0/rules/2"),
            lint(dup, served = setOf("alpha")).paths(rulesRe),
            "only the raw third entry (beta) is unserved",
        )

        val unservedFirst = lint(dup, served = setOf("beta")).paths(rulesRe)
        assertTrue(
            unservedFirst.contains("/work_item_schemas/x/seats/0/rules/0") && unservedFirst.contains("/traits/t/seats/0/rules/0"),
            "control: with beta served the raw alpha at index 0 is flagged on both seats: $unservedFirst",
        )
        assertTrue(
            unservedFirst.none { it.endsWith("/rules/2") },
            "control: the served beta at raw index 2 is never flagged: $unservedFirst",
        )
    }

    // ------------------------------------------------------------------ S9 (L8)

    @Test
    fun `S9 a seat skill that is a served rule is flagged and a plugin-prefixed or unknown skill is not`() {
        val text =
            yaml(
                "work_item_schemas:",
                "  x:",
                "    seats:",
                "      - { name: implementer, phase: work, enters: true, skills: [alpha, \"plugin:x\", other] }",
                "    notes:",
                *note("impl", "work", true, seat = "implementer"),
            )

        assertEquals(
            listOf("/work_item_schemas/x/seats/0/skills/0"),
            lint(text, served = setOf("alpha")).paths(seatSkillsRe),
        )
        assertEquals(emptyList(), lint(text, served = emptySet()).paths(seatSkillsRe), "control: nothing served, nothing flagged")
    }

    @Test
    fun `S9 a pointer names the position in the document as written when an earlier entry is a duplicate`() {
        // Raw entries: 0 other, 1 other, 2 alpha (a served rule). The parser collapses the repeated other, so a pointer built
        // from the kept list would name /skills/1 (the unserved other's slot shifted); the oracle is the document as written.
        val text =
            yaml(
                "work_item_schemas:",
                "  x:",
                "    seats:",
                "      - { name: implementer, phase: work, enters: true, skills: [other, other, alpha] }",
                "    notes:",
                *note("impl", "work", true, seat = "implementer"),
                "traits:",
                "  t:",
                "    applies_when: \"Always.\"",
                "    seats:",
                "      - { name: ts, phase: work, skills: [other, other, alpha] }",
                "    notes:",
                *note("tn", "work", false, seat = "ts"),
            )

        assertEquals(
            listOf("/traits/t/seats/0/skills/2", "/work_item_schemas/x/seats/0/skills/2"),
            lint(text, served = setOf("alpha")).paths(seatSkillsRe),
        )
        assertEquals(emptyList(), lint(text, served = setOf("beta")).paths(seatSkillsRe), "control: nothing named is served")
    }

    @Test
    fun `S9 an empty skills list and an absent skills key give no finding`() {
        val text =
            yaml(
                "work_item_schemas:",
                "  x:",
                "    seats:",
                "      - { name: planner, phase: queue, skills: [] }",
                "      - { name: implementer, phase: work, enters: true }",
                "    notes:",
                *note("scope", "queue", true, seat = "planner"),
                *note("impl", "work", true, seat = "implementer"),
            )

        assertEquals(emptyList(), lint(text, served = setOf("alpha")).paths(seatSkillsRe))
    }

    // ------------------------------------------------------------------ S10 (L9)

    @Test
    fun `S10 a note skill that is a served rule is flagged in string and list form at the right pointer`() {
        val text =
            yaml(
                "work_item_schemas:",
                "  x:",
                "    notes:",
                *note("n0", "queue", false, skill = "alpha"),
                *note("n1", "queue", false, skill = "[x, alpha]"),
                *note("n2", "queue", false, skill = "beta"),
            )

        assertEquals(
            listOf("/work_item_schemas/x/notes/0/skill", "/work_item_schemas/x/notes/1/skill/1"),
            lint(text, served = setOf("alpha")).paths(noteSkillRe),
        )
        assertEquals(emptyList(), lint(text, served = emptySet()).paths(noteSkillRe), "control: unserved skills are not flagged")
    }

    // ------------------------------------------------------------------ S11 (AC3 and the sort rule)

    private val allChecksDoc =
        yaml(
            "work_item_schemas:",
            "  x:",
            "    default_traits: [t]",
            "    seats:",
            "      - { name: planner, phase: queue }",
            "      - { name: implementer, phase: work, enters: true, rules: [beta], skills: [alpha] }",
            "    notes:",
            *note("impl", "work", true, seat = "implementer", guidance = "Dup guidance.", skill = "alpha"),
            *note("free", "queue", false, guidance = "Dup guidance."),
            "  shadowed:",
            "    notes:",
            *note("n", "queue", true),
            "traits:",
            "  t:",
            "    notes:",
            *note("tn", "work", false, guidance = "Dup guidance."),
            "  st:",
            "    applies_when: \"Root when.\"",
        )

    private val allChecksServer =
        serverLayer(
            yaml(
                "work_item_schemas:",
                "  shadowed:",
                "    notes:",
                *note("n", "queue", false),
                "traits:",
                "  st:",
                "    applies_when: \"Server when.\"",
            ),
        )

    @Test
    fun `S11 one document that triggers every check yields exactly the expected findings, sorted and distinct`() {
        val findings = lint(allChecksDoc, served = setOf("alpha"), server = allChecksServer)

        assertEquals(
            listOf(
                "/traits/st",
                "/traits/t",
                "/work_item_schemas/shadowed",
                "/work_item_schemas/x/notes/0/guidance",
                "/work_item_schemas/x/notes/0/skill",
                "/work_item_schemas/x/notes/1",
                "/work_item_schemas/x/seats/0",
                "/work_item_schemas/x/seats/1/rules/0",
                "/work_item_schemas/x/seats/1/skills/0",
            ).sorted(),
            findings.map { it.path },
        )
        assertEquals(findings.sortedWith(compareBy({ it.path }, { it.reason })), findings, "sorted by path then reason")
        assertEquals(findings.distinct(), findings, "no duplicate finding")
        assertTrue(findings.all { it.reason.isNotBlank() })
    }

    @Test
    fun `S11 lint findings are not errors and not warnings and the document stays valid`() {
        val p = parsed(allChecksDoc)
        val result = validator.validate(p, ConfigScope.ROOT, allChecksServer)
        val findings = validator.lint(p, allChecksServer, setOf("alpha"))
        assertTrue(findings.isNotEmpty(), "precondition: the document has lint findings")

        assertEquals(emptyList(), result.errors)
        assertTrue(result.isValid)
        for (f in findings) {
            assertFalse(result.errors.contains(f), "finding is not an error: $f")
            assertFalse(result.warnings.contains(f), "finding is not a warning: $f")
            assertFalse(result.warnings.any { it.reason == f.reason }, "no warning carries a lint reason: $f")
        }
    }

    @Test
    fun `S11 the overload without a server layer uses the validator's configured server layer`() {
        val configured = ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { allChecksServer })
        val p = parsed(allChecksDoc)

        assertEquals(
            validator.lint(p, allChecksServer, setOf("alpha")),
            configured.lint(p, setOf("alpha")),
        )
        assertTrue(
            validator.lint(p, null, setOf("alpha")).none { it.path == "/work_item_schemas/shadowed" },
            "control: without a server layer the shadow finding is absent",
        )
    }

    @Test
    fun `S11 a document with none of the patterns has no findings`() {
        val clean =
            yaml(
                "work_item_schemas:",
                "  x:",
                "    seats:",
                "      - { name: planner, phase: queue }",
                "      - { name: implementer, phase: work, enters: true }",
                "    notes:",
                *note("scope", "queue", true, seat = "planner"),
                *note("impl", "work", true, seat = "implementer"),
            )

        assertEquals(emptyList(), lint(clean, served = setOf("alpha")))
    }

    // ------------------------------------------------------------------ S15 (AC7)

    @Test
    fun `S15 a seat rule naming a served protocol rule is not flagged`() {
        val text =
            yaml(
                "work_item_schemas:",
                "  x:",
                "    seats:",
                "      - { name: implementer, phase: work, enters: true, rules: [protocol.in-phase-seat] }",
                "    notes:",
                *note("impl", "work", true, seat = "implementer"),
            )

        assertEquals(emptyList(), lint(text, served = setOf("protocol.in-phase-seat")))
        assertEquals(
            listOf("/work_item_schemas/x/seats/0/rules/0"),
            lint(text, served = emptySet()).paths(rulesRe),
            "control: the same entry is flagged when the key is not served",
        )
    }
}
