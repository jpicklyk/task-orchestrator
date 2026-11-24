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
 * Independent tests (test-author seat, item 33269b76) for scenarios S1 to S14 and the probes of the frozen test-plan:
 * the lint and the unowned-required-note warning of [ConfigValidator] read the schema the gate reads (the document's
 * schema merged with its default traits, per-root traits replacing server traits of the same name), not an ad hoc merge.
 *
 * Oracles (never read from a run): a note's owning seat is the seat its entry names, when declared for the note's own
 * phase or when it is the orchestrator seat in any phase (the OwningSeat doc comment); the merge is base notes first,
 * then trait notes, one entry per key, content from the first declaration, required when ANY declaration says so, base
 * seats first then trait seats, first declaration of a name wins (the TraitResolver doc comment); a per-root trait of a
 * name replaces every dimension of the server trait of that name (the LayeredConfig doc table). The warning text and
 * path are the pre-existing advisory text at /work_item_schemas/<schema>.
 *
 * Fixtures: every document parses under STRICT without errors and validates without errors (asserted by the helpers)
 * unless the scenario is the invalid one (S9). Negative assertions are paired with a twin that differs only in the one
 * fixture condition that produces the finding, named in the test.
 *
 * Domain invariants satisfied by construction: at most one enters seat per phase in each document, every note role is a
 * real role.
 *
 * Forbidden-construct declaration: none.
 */
class ConfigValidatorRealMergeTest {
    private val validator = ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { null })

    private fun parsed(text: String): ConfigDocumentParser.Outcome.Parsed {
        val outcome = YamlConfigDocumentParser.parse(text, warnOnMissingSchemas = false, mode = ParseMode.STRICT)
        val p = assertIs<ConfigDocumentParser.Outcome.Parsed>(outcome, "fixture must parse under STRICT: " + outcome)
        assertEquals(emptyList(), p.document.errors, "fixture must be STRICT-clean")
        return p
    }

    private fun server(text: String): ConfigLayer = ConfigLayer(parsed(text).document, "server-fp", ConfigSource.GLOBAL)

    private fun check(
        text: String,
        server: ConfigLayer?,
        scope: ConfigScope = ConfigScope.ROOT,
    ): ConfigValidationResult {
        val result = validator.validate(parsed(text), scope, server)
        assertEquals(emptyList(), result.errors, "fixture must validate without errors")
        return result
    }

    private fun warnings(
        text: String,
        server: ConfigLayer?,
    ): List<ConfigViolation> = check(text, server).warnings

    private fun lint(
        text: String,
        server: ConfigLayer?,
    ): List<ConfigViolation> {
        check(text, server)
        return validator.lint(parsed(text), server, emptySet())
    }

    private fun adv(
        schema: String,
        key: String,
        role: String,
    ) = ConfigViolation(
        "/work_item_schemas/" + schema,
        "Schema '" + schema + "': required note '" + key + "' (role '" + role + "') is not owned by any declared seat; " +
            "listed under 'unowned' in missingBySeat",
    )

    private fun List<ConfigViolation>.at(path: String) = filter { it.path == path }

    private val unownedFragment = "not owned by any declared seat"

    private fun serverSeatTrait(
        seatLine: String,
        name: String = "srv",
    ) = (
        "traits:\n  " + name + ":\n    applies_when: \"Always.\"\n    seats:\n      - " + seatLine + "\n"
    )

    private val seatlessServerTrait =
        "traits:\n  srv:\n    applies_when: \"Always.\"\n    notes:\n      - { key: extra, role: work, required: false }\n"

    private val s1Doc =
        """
        work_item_schemas:
          x:
            default_traits: [srv]
            notes:
              - { key: spec, role: queue, required: true }
        """.trimIndent() + "\n"

    // ------------------------------------------------------------------ S1

    @Test
    fun `S1 a server trait seat makes a required unlabelled note unowned and the advisory names it`() {
        val srv = server(serverSeatTrait("{ name: implementer, phase: work }"))

        assertEquals(listOf(adv("x", "spec", "queue")), warnings(s1Doc, srv))
        assertEquals(emptyList(), lint(s1Doc, srv).at("/work_item_schemas/x/notes/0"), "a merged-required note is never a lint finding")
    }

    @Test
    fun `S1 twin - a seatless server trait leaves the schema seatless so no advisory`() {
        val srv = server(seatlessServerTrait)

        assertEquals(emptyList(), warnings(s1Doc, srv), "the only difference to S1 is that the trait declares no seat")
    }

    // ------------------------------------------------------------------ S2, S3

    private fun upgradeDoc(
        upRequired: Boolean,
        withTrait: Boolean,
    ) = (
        (
            if (withTrait) {
                "traits:\n  up:\n    applies_when: \"Always.\"\n    notes:\n      - { key: spec, role: queue, required: " + upRequired +
                    " }\n"
            } else {
                ""
            }
        ) +
            "work_item_schemas:\n  x:\n    default_traits: [up]\n    seats:\n      - { name: planner, phase: queue }\n" +
            "    notes:\n      - { key: spec, role: queue, required: false }\n"
    )

    private val serverUp = { required: Boolean ->
        "traits:\n  up:\n    applies_when: \"Always.\"\n    notes:\n      - { key: spec, role: queue, required: " + required + " }\n"
    }

    @Test
    fun `S2 a document trait that upgrades an optional own note to required draws the advisory and no lint`() {
        val doc = upgradeDoc(upRequired = true, withTrait = true)

        assertEquals(listOf(adv("x", "spec", "queue")), warnings(doc, null))
        assertEquals(emptyList(), lint(doc, null).at("/work_item_schemas/x/notes/0"), "merged-required notes are skipped by lint")
    }

    @Test
    fun `S2 twin - the same trait keeping the note optional gives the unseated finding and no advisory`() {
        val doc = upgradeDoc(upRequired = false, withTrait = true)

        assertEquals(emptyList(), warnings(doc, null))
        val findings = lint(doc, null).at("/work_item_schemas/x/notes/0")
        assertEquals(1, findings.size, "findings: " + findings)
        assertTrue(findings.single().reason.contains("optional note 'spec' has no seat"), findings.single().reason)
    }

    @Test
    fun `S3 a server-only trait that upgrades an optional own note to required draws the advisory and no lint`() {
        val doc = upgradeDoc(upRequired = true, withTrait = false)
        val srv = server(serverUp(true))

        assertEquals(listOf(adv("x", "spec", "queue")), warnings(doc, srv))
        assertEquals(emptyList(), lint(doc, srv).at("/work_item_schemas/x/notes/0"))
    }

    @Test
    fun `S3 twin - the server trait keeping the note optional gives the unseated finding and no advisory`() {
        val doc = upgradeDoc(upRequired = false, withTrait = false)
        val srv = server(serverUp(false))

        assertEquals(emptyList(), warnings(doc, srv))
        assertEquals(1, lint(doc, srv).at("/work_item_schemas/x/notes/0").size)
    }

    // ------------------------------------------------------------------ S4, S5

    private val twoTraitServer =
        "traits:\n" +
            "  a:\n    applies_when: \"Always.\"\n    seats:\n      - { name: x, phase: work }\n" +
            "  b:\n    applies_when: \"Always.\"\n    seats:\n      - { name: x, phase: review }\n"

    private fun orderDoc(order: String) =
        "work_item_schemas:\n  s:\n    default_traits: [" + order + "]\n    notes:\n" +
            "      - { key: r1, role: review, required: false, seat: x }\n" +
            "      - { key: r2, role: review, required: true, seat: x }\n" +
            "      - { key: w, role: work, required: true, seat: x }\n"

    @Test
    fun `S4 first declaration of a seat name wins so the later review seat is dropped`() {
        val srv = server(twoTraitServer)
        val doc = orderDoc("a, b")

        assertEquals(
            listOf(adv("s", "r2", "review")),
            warnings(doc, srv),
            "x is a work seat only, so required r2 (review) is unowned and w is owned"
        )
        val names = lint(doc, srv).filter { it.reason.contains("names seat") }
        assertEquals(listOf("/work_item_schemas/s/notes/0"), names.map { it.path }, "only optional r1 is a lint finding")
        assertTrue(names.single().reason.contains("note 'r1' (review) names seat 'x'"), names.single().reason)
    }

    @Test
    fun `S5 reversing the trait order makes x a review seat so the work note is the unowned one`() {
        val srv = server(twoTraitServer)
        val doc = orderDoc("b, a")

        assertEquals(listOf(adv("s", "w", "work")), warnings(doc, srv))
        assertEquals(emptyList(), lint(doc, srv).filter { it.reason.contains("names seat") }, "r1 is owned by the review seat x")
    }

    // ------------------------------------------------------------------ S6

    private fun s6Doc() =
        "work_item_schemas:\n  x:\n    default_traits: [srv]\n    seats:\n      - { name: planner, phase: queue }\n    notes:\n" +
            "      - { key: scope, role: queue, required: true, seat: planner }\n" +
            "      - { key: impl, role: work, required: true, seat: implementer }\n"

    @Test
    fun `S6 a note labelled with a seat only a server trait declares is owned and draws no advisory`() {
        val srv = server(serverSeatTrait("{ name: implementer, phase: work }"))

        assertEquals(emptyList(), warnings(s6Doc(), srv))
    }

    @Test
    fun `S6 twin - the server trait seat declared for the queue phase leaves the work note unowned`() {
        val srv = server(serverSeatTrait("{ name: implementer, phase: queue }"))

        assertEquals(listOf(adv("x", "impl", "work")), warnings(s6Doc(), srv))
    }

    // ------------------------------------------------------------------ S7

    @Test
    fun `S7 parity with no server layer - the advisories and the lint findings of one merged schema`() {
        val doc =
            """
            traits:
              t:
                applies_when: "Always."
                seats:
                  - { name: implementer, phase: work }
                notes:
                  - { key: impl, role: work, required: true, seat: implementer }
                  - { key: treq, role: review, required: true }
            work_item_schemas:
              p:
                default_traits: [t]
                seats:
                  - { name: planner, phase: queue }
                notes:
                  - { key: scope, role: queue, required: true, seat: planner }
                  - { key: opt, role: queue, required: false }
                  - { key: lab, role: work, required: false, seat: ghost }
                  - { key: req, role: review, required: true }
            """.trimIndent() + "\n"

        assertEquals(listOf(adv("p", "req", "review"), adv("p", "treq", "review")), warnings(doc, null))
        val findings = lint(doc, null)
        assertEquals(
            listOf("/work_item_schemas/p/notes/1", "/work_item_schemas/p/notes/2"),
            findings.map { it.path },
            "findings: " + findings
        )
        assertTrue(findings[0].reason.contains("optional note 'opt' has no seat"), findings[0].reason)
        assertTrue(findings[1].reason.contains("note 'lab' (work) names seat 'ghost'"), findings[1].reason)
    }

    // ------------------------------------------------------------------ S8

    @Test
    fun `S8 a server trait with notes only leaves a seatless schema seatless so no advisory`() {
        val srv = server("traits:\n  n:\n    applies_when: \"Always.\"\n    notes:\n      - { key: more, role: work, required: true }\n")
        val doc =
            "work_item_schemas:\n  x:\n    default_traits: [n]\n    notes:\n      - { key: spec, role: queue, required: true }\n"

        assertEquals(emptyList(), warnings(doc, srv))
    }

    // ------------------------------------------------------------------ S9

    @Test
    fun `S9 an unknown default trait is one error at its index and the document is invalid`() {
        val doc =
            "work_item_schemas:\n  x:\n    default_traits: [missing]\n    seats:\n      - { name: planner, phase: queue }\n" +
                "    notes:\n      - { key: spec, role: queue, required: true, seat: planner }\n"

        val result = validator.validate(parsed(doc), ConfigScope.ROOT, null)

        assertFalse(result.isValid)
        assertEquals(listOf("/work_item_schemas/x/default_traits/0"), result.errors.map { it.path }, "errors: " + result.errors)
        assertTrue(
            result.errors
                .single()
                .reason
                .contains("missing"),
            result.errors.single().reason
        )
    }

    // ------------------------------------------------------------------ S10

    @Test
    fun `S10 a server-scope document is its own server layer`() {
        val doc = serverSeatTrait("{ name: implementer, phase: work }") + s1Doc

        val result = validator.validate(parsed(doc), ConfigScope.SERVER, null)

        assertEquals(emptyList(), result.errors)
        assertEquals(listOf(adv("x", "spec", "queue")), result.warnings)
    }

    // ------------------------------------------------------------------ S11

    @Test
    fun `S11 the STRICT parse alone no longer emits the unowned-note advisory`() {
        val p = parsed(s6Doc())

        assertEquals(emptyList(), p.document.warningViolations.filter { it.reason.contains(unownedFragment) })
        assertEquals(emptyList(), p.document.warnings.filter { it.contains(unownedFragment) })
        val ownDoc =
            "work_item_schemas:\n  x:\n    seats:\n      - { name: planner, phase: queue }\n" +
                "    notes:\n      - { key: spec, role: queue, required: true }\n"
        assertEquals(
            emptyList(),
            parsed(ownDoc).document.warningViolations.filter { it.reason.contains(unownedFragment) },
            "even a plainly unowned note is reported by validation, not by the parse",
        )
        assertEquals(listOf(adv("x", "spec", "queue")), warnings(ownDoc, null), "validation reports it")
    }

    // ------------------------------------------------------------------ S12

    @Test
    fun `S12 an enters seat in both the schema and the server trait does not change the advisory`() {
        fun doc(enters: Boolean) =
            "work_item_schemas:\n  x:\n    default_traits: [srv]\n    seats:\n" +
                "      - { name: lead, phase: work, enters: " + enters + " }\n" +
                "    notes:\n      - { key: spec, role: queue, required: true }\n"
        val withEnters = warnings(doc(true), server(serverSeatTrait("{ name: implementer, phase: work, enters: true }")))
        val withoutEnters = warnings(doc(false), server(serverSeatTrait("{ name: implementer, phase: work, enters: false }")))

        assertEquals(listOf(adv("x", "spec", "queue")), withEnters)
        assertEquals(withoutEnters, withEnters)
    }

    // ------------------------------------------------------------------ S13

    private val traitSeatServer = serverSeatTrait("{ name: qa, phase: review }", name = "t")

    private fun shadowDoc(
        shadowT: Boolean,
        shadowS: Boolean
    ) = (if (shadowT) "traits:\n  t:\n    applies_when: \"Always.\"\n" else "") +
        (if (!shadowT) "traits:\n" else "") +
        "  u:\n    applies_when: \"Always.\"\n    seats:\n      - { name: lead, phase: work }\n    notes:\n" +
        "      - { key: k, role: review, required: false, seat: qa }\n" +
        (
            if (shadowS) {
                "work_item_schemas:\n  s:\n    seats:\n      - { name: lead, phase: work }\n" +
                    "    notes:\n      - { key: own, role: work, required: false, seat: lead }\n"
            } else {
                ""
            }
        )

    private fun traitFindings(
        doc: String,
        srv: ConfigLayer,
    ) = lint(doc, srv).filter { it.path == "/traits/u/notes/0" }

    @Test
    fun `S13 a document trait of the same name replaces the server trait so its seat is no longer visible`() {
        val findings = traitFindings(shadowDoc(shadowT = true, shadowS = false), server(traitSeatServer))

        assertEquals(1, findings.size, "findings: " + findings)
        assertTrue(findings.single().reason.contains("note 'k' (review) names seat 'qa'"), findings.single().reason)
    }

    @Test
    fun `S13 twin - without the shadowing document trait the server seat is visible and there is no finding`() {
        assertEquals(emptyList(), traitFindings(shadowDoc(shadowT = false, shadowS = false), server(traitSeatServer)))
    }

    @Test
    fun `S13 a document schema of the same name replaces the server schema so its seat is no longer visible`() {
        val serverSchema =
            server(
                "work_item_schemas:\n  s:\n    seats:\n      - { name: qa, phase: review }\n    notes:\n" +
                    "      - { key: sq, role: review, required: false, seat: qa }\n",
            )

        val shadowed = traitFindings(shadowDoc(shadowT = false, shadowS = true), serverSchema)
        val visible = traitFindings(shadowDoc(shadowT = false, shadowS = false), serverSchema)

        assertEquals(1, shadowed.size, "findings: " + shadowed)
        assertEquals(emptyList(), visible, "twin without the shadowing document schema")
    }

    // ------------------------------------------------------------------ S14

    @Test
    fun `S14 the advisory names exactly the required notes with a null, other-phase or undeclared seat`() {
        val doc =
            """
            work_item_schemas:
              bug-fix:
                seats:
                  - { name: implementer, phase: work }
                  - { name: reviewer, phase: review }
                notes:
                  - { key: q1, role: queue, required: true }
                  - { key: w1, role: work, required: true, seat: reviewer }
                  - { key: w2, role: work, required: true, seat: ghost }
                  - { key: w3, role: work, required: true, seat: implementer }
            """.trimIndent() + "\n"

        val result = warnings(doc, null)

        assertEquals(
            listOf(adv("bug-fix", "q1", "queue"), adv("bug-fix", "w1", "work"), adv("bug-fix", "w2", "work")),
            result,
            "q1 null seat, w1 seat of another phase, w2 undeclared seat; w3 is owned",
        )
    }

    // ------------------------------------------------------------------ probes

    @Test
    fun `probe a trait listed twice in default_traits is merged once`() {
        val srv = server(serverSeatTrait("{ name: implementer, phase: work }"))
        val doc =
            "work_item_schemas:\n  x:\n    default_traits: [srv, srv]\n    notes:\n      - { key: spec, role: queue, required: true }\n"

        assertEquals(listOf(adv("x", "spec", "queue")), warnings(doc, srv))
    }

    @Test
    fun `probe an orchestrator seat in the work phase owns a required review note`() {
        fun doc(owner: String) =
            "work_item_schemas:\n  x:\n    seats:\n      - { name: planner, phase: queue }\n      - { name: orchestrator, phase: work }\n" +
                "    notes:\n      - { key: confirm, role: review, required: true, seat: " + owner + " }\n"

        assertEquals(emptyList(), warnings(doc("orchestrator"), null))
        assertEquals(
            listOf(adv("x", "confirm", "review")),
            warnings(doc("planner"), null),
            "twin: a non-orchestrator seat of another phase owns nothing"
        )
    }

    @Test
    fun `probe an empty default_traits list behaves as an absent one`() {
        fun doc(traits: String) =
            "work_item_schemas:\n  x:\n" + traits + "    seats:\n      - { name: planner, phase: queue }\n" +
                "    notes:\n      - { key: spec, role: queue, required: true }\n"

        assertEquals(listOf(adv("x", "spec", "queue")), warnings(doc("    default_traits: []\n"), null))
        assertEquals(listOf(adv("x", "spec", "queue")), warnings(doc(""), null))
    }

    @Test
    fun `probe note keys differing in case are distinct keys so the trait note does not upgrade the own note`() {
        val doc =
            "traits:\n  up:\n    applies_when: \"Always.\"\n    notes:\n      - { key: Spec, role: queue, required: true }\n" +
                "work_item_schemas:\n  x:\n    default_traits: [up]\n    seats:\n      - { name: planner, phase: queue }\n" +
                "    notes:\n      - { key: spec, role: queue, required: false }\n"

        assertEquals(listOf(adv("x", "Spec", "queue")), warnings(doc, null), "only the capitalised trait note is required and unowned")
        val own = lint(doc, null).at("/work_item_schemas/x/notes/0")
        assertEquals(1, own.size, "the lower-case own note stays optional and unseated: " + own)
    }
}
