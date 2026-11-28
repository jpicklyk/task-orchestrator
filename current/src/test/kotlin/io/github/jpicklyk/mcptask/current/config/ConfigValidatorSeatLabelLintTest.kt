package io.github.jpicklyk.mcptask.current.config

import io.github.jpicklyk.mcptask.current.config.internal.wire.GlobalConfigFile
import io.github.jpicklyk.mcptask.current.config.internal.wire.NetworkntConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.config.internal.wire.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.model.config.ConfigScope
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 36da3c67) for the seat-name lint findings of [ConfigValidator.lint]:
 * (a) a note naming a seat that owns nothing, (b) an independent_of seat nobody declares, (c) an after name nobody
 * declares, (d) a trait dispatch override for a seat nobody declares for that phase. Scenarios S1 to S16 and the probes
 * of the frozen test-plan.
 *
 * Oracles (never read from a run): the diagnosis "Fix (frozen)" bullets (a) to (d): the finding paths, the reason
 * texts, the visible-seat set V (every trait seat here + every schema's effective seats here + every server-layer seat),
 * the exact-match and phase-strict rules, the orchestrator exception for notes, and the sort rule "distinct, sorted by
 * path then reason". Paths are RFC 6901 pointers with raw first-occurrence list indexes.
 *
 * Fixtures: every document parses under STRICT with no errors and validates with no errors (checked by [lint] before it
 * asks for findings). Every negative assertion is paired with a twin that differs only in the fixture condition that
 * would produce the finding (named in the assertion message).
 *
 * Domain invariants satisfied by construction: at most one enters seat per schema, every note role is a real Role.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions, no disjunctive assertions.
 */
@Tag("repo-read")
class ConfigValidatorSeatLabelLintTest {
    private val validator = ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { null })

    private fun yaml(vararg parts: List<String>): String = parts.flatMap { it }.joinToString("\n") + "\n"

    private fun note(
        key: String,
        role: String,
        required: Boolean,
        seat: String? = null,
        independentOf: String? = null,
    ): List<String> =
        listOfNotNull(
            "      - key: $key",
            "        role: $role",
            "        required: $required",
            seat?.let { "        seat: $it" },
            independentOf?.let { "        independent_of: $it" },
        )

    private val planImpl =
        listOf(
            "      - { name: planner, phase: queue }",
            "      - { name: implementer, phase: work, enters: true }",
        )

    private fun schemaX(
        seatLines: List<String>?,
        notes: List<List<String>>,
        defaultTraits: String? = null,
        name: String = "x",
    ): List<String> =
        buildList {
            add("work_item_schemas:")
            add("  $name:")
            defaultTraits?.let { add("    default_traits: [$it]") }
            if (seatLines != null) {
                add("    seats:")
                addAll(seatLines)
            }
            add("    notes:")
            notes.forEach { addAll(it) }
        }

    private fun trait(
        name: String,
        seatLines: List<String>? = null,
        notes: List<List<String>> = emptyList(),
        dispatchLines: List<String> = emptyList(),
    ): List<String> =
        buildList {
            add("traits:")
            add("  $name:")
            add("    applies_when: \"Always.\"")
            if (seatLines != null) {
                add("    seats:")
                addAll(seatLines)
            }
            if (notes.isNotEmpty()) {
                add("    notes:")
                notes.forEach { addAll(it) }
            }
            addAll(dispatchLines)
        }

    private fun dispatch(
        phase: String,
        vararg seatEntries: String,
    ): List<String> = listOf("    dispatch:", "      $phase:", "        seats:") + seatEntries.map { "          $it" }

    private fun parsed(text: String): ConfigDocumentParser.Outcome.Parsed {
        val outcome = YamlConfigDocumentParser.parse(text, warnOnMissingSchemas = false, mode = ParseMode.STRICT)
        val p = assertIs<ConfigDocumentParser.Outcome.Parsed>(outcome, "fixture must parse under STRICT: $outcome")
        assertEquals(emptyList(), p.document.errors, "fixture must be STRICT-clean")
        return p
    }

    private fun serverLayer(text: String): ConfigLayer = ConfigLayer(parsed(text).document, "server-fp", ConfigSource.GLOBAL)

    private fun lint(
        text: String,
        server: ConfigLayer? = null,
    ): List<ConfigViolation> {
        val p = parsed(text)
        assertEquals(emptyList(), validator.validate(p, ConfigScope.ROOT, server).errors, "fixture must validate without errors")
        return validator.lint(p, server, emptySet())
    }

    private fun List<ConfigViolation>.paths(sub: String): List<String> = filter { it.reason.contains(sub) }.map { it.path }

    private fun namesSeat(s: String) = "names seat '$s'"

    private fun indepSeat(s: String) = "independent_of seat '$s'"

    private val underAfter = "under after"
    private val overridesDispatch = "overrides dispatch"

    // ------------------------------------------------------------------ S1 (a, schema notes)

    @Test
    fun `S1 an optional note naming a seat the schema does not declare is flagged with the frozen text`() {
        val flagged =
            yaml(
                schemaX(
                    planImpl,
                    listOf(
                        note("scope", "queue", true, seat = "planner"),
                        note("impl", "work", true, seat = "implementer"),
                        note("extra", "work", false, seat = "reviewer"),
                    ),
                ),
            )
        val twin = flagged.replace("seat: reviewer", "seat: implementer")

        val found = lint(flagged).filter { it.reason.contains(namesSeat("reviewer")) }
        assertEquals(listOf("/work_item_schemas/x/notes/2"), found.map { it.path })
        assertEquals(
            "note 'extra' (work) names seat 'reviewer', which schema 'x' does not declare for the work phase; no seat owns it",
            found.single().reason,
        )
        assertEquals(emptyList(), lint(twin).paths("names seat"), "the twin naming a declared seat in its phase has no finding")
    }

    // ------------------------------------------------------------------ S2 (a, other phase)

    @Test
    fun `S2 an optional queue note owned by a work phase seat is flagged`() {
        val text =
            yaml(
                schemaX(
                    planImpl,
                    listOf(
                        note("scope", "queue", true, seat = "planner"),
                        note("early", "queue", false, seat = "implementer"),
                    ),
                ),
            )
        val twin = text.replace("key: early\n        role: queue", "key: early\n        role: work")

        assertEquals(listOf("/work_item_schemas/x/notes/1"), lint(text).paths(namesSeat("implementer")))
        assertEquals(emptyList(), lint(twin).paths("names seat"), "the twin with the note in the seat's own phase has no finding")
    }

    // ------------------------------------------------------------------ S3 (a, default trait seat)

    @Test
    fun `S3 a note owned by a seat that a default trait declares is not flagged`() {
        val withTrait =
            yaml(
                schemaX(
                    planImpl,
                    listOf(
                        note("scope", "queue", true, seat = "planner"),
                        note("tests", "work", false, seat = "test-author"),
                    ),
                    defaultTraits = "t",
                ),
                trait("t", seatLines = listOf("      - { name: test-author, phase: work }")),
            )
        val withoutTrait = withTrait.replace("    default_traits: [t]\n", "")

        assertEquals(emptyList(), lint(withTrait).paths("names seat"), "test-author is declared through default_traits")
        assertEquals(
            listOf("/work_item_schemas/x/notes/1"),
            lint(withoutTrait).paths(namesSeat("test-author")),
            "the same document without default_traits leaves the label unowned",
        )
    }

    // ------------------------------------------------------------------ S4 (a, orchestrator)

    @Test
    fun `S4 the orchestrator label is owned in any phase whether or not an orchestrator seat is declared`() {
        val withSeat =
            yaml(
                schemaX(
                    planImpl + "      - { name: orchestrator, phase: work }",
                    listOf(
                        note("scope", "queue", true, seat = "planner"),
                        note("audit", "review", false, seat = "orchestrator"),
                    ),
                ),
            )
        val withoutSeat = withSeat.replace("      - { name: orchestrator, phase: work }\n", "")

        assertEquals(emptyList(), lint(withSeat).paths("names seat"), "orchestrator(work) owns a review note")
        assertEquals(emptyList(), lint(withoutSeat).paths("names seat"), "orchestrator is a reserved owner, no seat needed")
    }

    // ------------------------------------------------------------------ S5 (seatless schema)

    @Test
    fun `S5 a seatless schema is never checked for seat names`() {
        val seatless =
            yaml(
                schemaX(
                    null,
                    listOf(note("scope", "queue", false, seat = "ghost", independentOf = "[ghost]")),
                ),
            )
        val seatAware =
            yaml(
                schemaX(
                    planImpl,
                    listOf(note("scope", "queue", false, seat = "ghost", independentOf = "[ghost]")),
                ),
            )

        assertEquals(emptyList(), lint(seatless).filter { it.reason.contains("ghost") }, "no seats declared: not seat-aware")
        val seatAwareFindings = lint(seatAware)
        assertEquals(
            listOf(
                ConfigViolation(
                    "/work_item_schemas/x/notes/0",
                    "note 'scope' (queue) names seat 'ghost', which schema 'x' does not declare for the queue phase; no seat owns it",
                ),
            ),
            seatAwareFindings.filter { it.reason.contains(namesSeat("ghost")) },
            "twin with seats declared flags the label",
        )
        assertEquals(
            listOf(
                ConfigViolation(
                    "/work_item_schemas/x/notes/0/independent_of/0",
                    "note 'scope' is independent_of seat 'ghost', which schema 'x' does not declare; the independence check skips it",
                ),
            ),
            seatAwareFindings.filter { it.reason.contains(indepSeat("ghost")) },
            "twin with seats declared flags independent_of",
        )
    }

    // ------------------------------------------------------------------ S6 (required own note stays the parser advisory)

    @Test
    fun `S6 a required own note with an undeclared seat has no lint finding and one parser warning`() {
        val text =
            yaml(
                schemaX(
                    planImpl,
                    listOf(
                        note("scope", "queue", true, seat = "planner"),
                        note("reqnote", "work", true, seat = "ghost"),
                    ),
                ),
            )
        val optional = text.replace("required: true\n        seat: ghost", "required: false\n        seat: ghost")
        val p = parsed(text)
        val result = validator.validate(p, ConfigScope.ROOT, null)

        assertTrue(result.isValid, "the fixture is valid")
        assertEquals(1, result.warnings.count { it.reason.contains("reqnote") }, "the parser advisory reports the key once")
        assertEquals(
            emptyList(),
            validator.lint(p, null, emptySet()).filter { it.path == "/work_item_schemas/x/notes/1" },
            "no lint finding at the required note's path",
        )
        assertEquals(
            listOf("/work_item_schemas/x/notes/1"),
            lint(optional).paths(namesSeat("ghost")),
            "twin: the same note optional is a lint finding",
        )
    }

    // ------------------------------------------------------------------ S7 (a, trait notes)

    @Test
    fun `S7 an unlisted seat-declaring trait is checked against every visible seat`() {
        fun doc(
            noteRole: String,
            seat: String,
            other: List<String> = emptyList(),
        ): String = yaml(trait("t", listOf("      - { name: ts, phase: work }"), listOf(note("tn", noteRole, false, seat = seat))), other)

        assertEquals(listOf("/traits/t/notes/0"), lint(doc("work", "ghost")).paths(namesSeat("ghost")))
        assertEquals(emptyList(), lint(doc("work", "ts")).paths("names seat"), "twin naming its own seat")

        val otherSchema = schemaX(planImpl, listOf(note("scope", "queue", true, seat = "planner")))
        assertEquals(
            emptyList(),
            lint(doc("queue", "planner", otherSchema)).paths("names seat"),
            "planner(queue) declared by another schema here owns a queue note",
        )
        assertEquals(
            listOf("/traits/t/notes/0"),
            lint(doc("work", "planner", otherSchema)).paths(namesSeat("planner")),
            "twin: planner is declared for the queue phase only, so a work note is unowned",
        )

        val server =
            serverLayer(
                yaml(
                    schemaX(
                        listOf("      - { name: planner, phase: queue }"),
                        listOf(note("scope", "queue", true, seat = "planner")),
                        name = "z",
                    ),
                ),
            )
        assertEquals(
            emptyList(),
            lint(doc("queue", "planner"), server).paths("names seat"),
            "planner(queue) declared only by a server-layer schema is visible",
        )
        assertEquals(
            listOf("/traits/t/notes/0"),
            lint(doc("queue", "planner")).paths(namesSeat("planner")),
            "twin: without the server layer the label is unowned",
        )
    }

    // ------------------------------------------------------------------ S8 (a, required trait note)

    @Test
    fun `S8 a required trait note is flagged only when no schema here lists the trait`() {
        val traitPart = trait("t", listOf("      - { name: ts, phase: work }"), listOf(note("tn", "work", true, seat = "ghost")))
        val unlisted = yaml(schemaX(planImpl, listOf(note("scope", "queue", true, seat = "planner"))), traitPart)
        val listed = yaml(schemaX(planImpl, listOf(note("scope", "queue", true, seat = "planner")), defaultTraits = "t"), traitPart)

        assertEquals(listOf("/traits/t/notes/0"), lint(unlisted).paths(namesSeat("ghost")))
        assertEquals(
            emptyList(),
            lint(listed).filter { it.path.startsWith("/traits/t/notes") },
            "twin: listed in default_traits, the parser reports it at that schema",
        )
    }

    // ------------------------------------------------------------------ S9 (seatless trait)

    @Test
    fun `S9 a seatless trait is not checked for note seat names`() {
        val seatless = yaml(trait("t", null, listOf(note("tn", "work", false, seat = "ghost"))))
        val seatful = yaml(trait("t", listOf("      - { name: ts, phase: work }"), listOf(note("tn", "work", false, seat = "ghost"))))

        assertEquals(emptyList(), lint(seatless).paths("names seat"))
        assertEquals(listOf("/traits/t/notes/0"), lint(seatful).paths(namesSeat("ghost")), "twin with a seat declared")
    }

    // ------------------------------------------------------------------ S10 (b, schema independent_of)

    private fun indepDoc(list: String): String =
        yaml(
            schemaX(
                planImpl + "      - { name: reviewer, phase: review }",
                listOf(
                    note("scope", "queue", true, seat = "planner"),
                    note("audit", "review", false, seat = "reviewer", independentOf = list),
                ),
            ),
        )

    @Test
    fun `S10 an independent_of name no seat declares is flagged at its list index`() {
        assertEquals(
            listOf("/work_item_schemas/x/notes/1/independent_of/0"),
            lint(indepDoc("[ghost]")).paths(indepSeat("ghost")),
        )
        val reason = lint(indepDoc("[ghost]")).single { it.reason.contains(indepSeat("ghost")) }.reason
        assertEquals(
            "note 'audit' is independent_of seat 'ghost', which schema 'x' does not declare; the independence check skips it",
            reason,
        )
        assertEquals(emptyList(), lint(indepDoc("[implementer]")).paths("independent_of seat"), "twin naming a declared seat")
        assertEquals(
            listOf("/work_item_schemas/x/notes/1/independent_of/0"),
            lint(indepDoc("[ghost, ghost]")).paths(indepSeat("ghost")),
            "a duplicate name is one finding at the first index",
        )
        assertEquals(
            listOf("/work_item_schemas/x/notes/1/independent_of/1"),
            lint(indepDoc("[planner, ghost]")).paths(indepSeat("ghost")),
            "another-phase seat is legal; the undeclared name is at index 1",
        )
    }

    // ------------------------------------------------------------------ S11 (b, trait independent_of)

    @Test
    fun `S11 a seat-declaring trait note independent_of is checked against every visible seat`() {
        fun doc(list: String): String =
            yaml(
                schemaX(planImpl, listOf(note("scope", "queue", true, seat = "planner"))),
                trait("t", listOf("      - { name: ts, phase: work }"), listOf(note("tn", "review", false, independentOf = list))),
            )

        assertEquals(emptyList(), lint(doc("[implementer]")).paths("independent_of seat"), "implementer is declared by a schema here")
        assertEquals(listOf("/traits/t/notes/0/independent_of/0"), lint(doc("[ghost]")).paths(indepSeat("ghost")))
    }

    // ------------------------------------------------------------------ S12 (validity and order)

    @Test
    fun `S12 the findings are warnings only, distinct and sorted`() {
        val text =
            yaml(
                schemaX(
                    planImpl,
                    listOf(
                        note("scope", "queue", true, seat = "planner"),
                        note("extra", "work", false, seat = "reviewer"),
                        note("early", "queue", false, seat = "implementer", independentOf = "[ghost, ghost]"),
                        note("impl", "work", true, seat = "implementer"),
                    ),
                ),
            )
        val p = parsed(text)
        val result = validator.validate(p, ConfigScope.ROOT, null)
        val findings = validator.lint(p, null, emptySet())
        val lintTexts = listOf("names seat", "independent_of seat", underAfter, overridesDispatch)

        assertTrue(result.isValid)
        assertEquals(
            listOf(
                ConfigViolation(
                    "/work_item_schemas/x/notes/1",
                    "note 'extra' (work) names seat 'reviewer', which schema 'x' does not declare for the work phase; no seat owns it",
                ),
                ConfigViolation(
                    "/work_item_schemas/x/notes/2",
                    "note 'early' (queue) names seat 'implementer', which schema 'x' does not declare for the queue phase; no seat owns it",
                ),
                ConfigViolation(
                    "/work_item_schemas/x/notes/2/independent_of/0",
                    "note 'early' is independent_of seat 'ghost', which schema 'x' does not declare; the independence check skips it",
                ),
            ),
            findings,
            "the two label findings and the one independent_of finding (duplicate ghost reported once), in path order",
        )
        assertFalse(result.warnings.any { w -> lintTexts.any { w.reason.contains(it) } }, "validate never carries a lint reason")
        assertEquals(findings.distinct(), findings)
        assertEquals(findings.sortedWith(compareBy({ it.path }, { it.reason })), findings)
    }

    // ------------------------------------------------------------------ S13 (live config)

    @Test
    fun `S13 the live repo config against the live floor has no seat-name finding`() {
        val root = repoRoot()
        val floor =
            GlobalConfigFile(root.resolve("deploy/global-config/.taskorchestrator/config.yaml")).layer()
                ?: throw AssertionError("the live floor must load")
        val liveValidator = ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { floor })
        val p = parsed(String(Files.readAllBytes(root.resolve(".taskorchestrator/config.yaml")), Charsets.UTF_8))
        assertTrue(p.document.traitSeats.containsKey("needs-test-author"), "the live file carries the seat-declaring trait the check reads")
        val findings = liveValidator.lint(p, floor, emptySet())

        for (sub in listOf("names seat '", "independent_of seat '", underAfter, overridesDispatch)) {
            assertEquals(emptyList(), findings.filter { it.reason.contains(sub) }, "no finding containing: $sub")
        }
    }

    private fun repoRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("claude-plugins")) && Files.isDirectory(dir.resolve("current"))) {
                return dir
            }
            dir = dir.parent
        }
        throw AssertionError("repo root not found")
    }

    // ------------------------------------------------------------------ S14 (c, schema after)

    private fun afterDoc(after: String): String =
        yaml(
            schemaX(
                listOf(
                    "      - { name: planner, phase: queue }",
                    "      - { name: implementer, phase: work, enters: true, after: $after }",
                ),
                listOf(note("scope", "queue", true, seat = "planner")),
            ),
        )

    @Test
    fun `S14 a schema seat after name no seat declares is flagged at its list index`() {
        assertEquals(listOf("/work_item_schemas/x/seats/1/after/0"), lint(afterDoc("[ghost]")).paths(underAfter))
        val reason = lint(afterDoc("[ghost]")).single { it.reason.contains(underAfter) }.reason
        assertEquals("seat 'implementer' lists 'ghost' under after, which schema 'x' does not declare", reason)
        assertEquals(emptyList(), lint(afterDoc("[planner]")).paths(underAfter), "twin: an other-phase seat is legal")
        assertEquals(
            listOf("/work_item_schemas/x/seats/1/after/1"),
            lint(afterDoc("[planner, ghost, ghost]")).paths(underAfter),
            "one finding at the first index of the undeclared name",
        )
    }

    // ------------------------------------------------------------------ S15 (c, trait after)

    @Test
    fun `S15 a trait seat after name is checked against every visible seat`() {
        fun doc(after: String): String =
            yaml(
                schemaX(planImpl, listOf(note("scope", "queue", true, seat = "planner"))),
                trait("t", listOf("      - { name: ts, phase: work, after: $after }")),
            )

        assertEquals(listOf("/traits/t/seats/0/after/0"), lint(doc("[ghost]")).paths(underAfter))
        assertEquals(emptyList(), lint(doc("[implementer]")).paths(underAfter), "implementer is declared by a schema here")
    }

    // ------------------------------------------------------------------ S16 (d, trait dispatch)

    private fun dispatchDoc(
        phase: String,
        entry: String,
        seatLines: List<String>? = null,
        schemaSeats: List<String>? = null,
    ): String =
        yaml(
            schemaX(schemaSeats ?: planImpl, listOf(note("scope", "queue", true, seat = "planner"))),
            trait("t", seatLines, dispatchLines = dispatch(phase, entry)),
        )

    @Test
    fun `S16 a dispatch override for a seat nobody declares for that phase is flagged`() {
        assertEquals(
            listOf("/traits/t/dispatch/work/seats/nobody"),
            lint(dispatchDoc("work", "nobody: { model: opus }")).paths(overridesDispatch),
        )
        val reason = lint(dispatchDoc("work", "nobody: { model: opus }")).single { it.reason.contains(overridesDispatch) }.reason
        assertEquals(
            "trait 't' overrides dispatch for work seat 'nobody', which no schema or trait here or in the server config declares for the work phase; the override never applies",
            reason,
        )
        assertEquals(
            emptyList(),
            lint(dispatchDoc("work", "implementer: { model: opus }")).paths(overridesDispatch),
            "twin naming implementer(work) declared here",
        )
        assertEquals(
            listOf("/traits/t/dispatch/review/seats/implementer"),
            lint(dispatchDoc("review", "implementer: { model: opus }")).paths(overridesDispatch),
            "phase-strict: implementer is declared for work, not review",
        )
        val orchestratorWork = planImpl + "      - { name: orchestrator, phase: work }"
        assertEquals(
            listOf("/traits/t/dispatch/review/seats/orchestrator"),
            lint(dispatchDoc("review", "orchestrator: { model: opus }", schemaSeats = orchestratorWork)).paths(overridesDispatch),
            "no orchestrator exception for dispatch",
        )
        assertEquals(
            emptyList(),
            lint(dispatchDoc("work", "orchestrator: { model: opus }", schemaSeats = orchestratorWork)).paths(overridesDispatch),
            "twin: orchestrator(work) with a work override",
        )
    }

    // ------------------------------------------------------------------ probes

    @Test
    fun `probe seat name matching is exact so a capitalized name is flagged`() {
        val planner =
            yaml(schemaX(planImpl, listOf(note("scope", "queue", false, seat = "Planner"))))
        val orch =
            yaml(
                schemaX(
                    planImpl + "      - { name: orchestrator, phase: work }",
                    listOf(note("audit", "review", false, seat = "Orchestrator")),
                ),
            )

        assertEquals(listOf("/work_item_schemas/x/notes/0"), lint(planner).paths(namesSeat("Planner")))
        assertEquals(listOf("/work_item_schemas/x/notes/0"), lint(orch).paths(namesSeat("Orchestrator")))
    }

    @Test
    fun `probe empty independent_of and after lists produce no finding`() {
        val text =
            yaml(
                schemaX(
                    listOf(
                        "      - { name: planner, phase: queue }",
                        "      - { name: implementer, phase: work, enters: true, after: [] }",
                    ),
                    listOf(note("scope", "queue", true, seat = "planner", independentOf = "[]")),
                ),
            )

        assertEquals(emptyList(), lint(text).filter { it.reason.contains("independent_of seat") || it.reason.contains(underAfter) })
    }

    @Test
    fun `probe a server-only trait is never reported`() {
        val server =
            serverLayer(
                yaml(trait("st", listOf("      - { name: sts, phase: work }"), listOf(note("sn", "work", false, seat = "ghost")))),
            )
        val root = yaml(schemaX(planImpl, listOf(note("scope", "queue", true, seat = "planner"))))

        assertEquals(emptyList(), lint(root, server).filter { it.path.startsWith("/traits") })
    }

    @Test
    fun `probe a seat key containing a slash is escaped in the pointer`() {
        val text = dispatchDoc("work", "\"/\": { model: opus }")

        assertEquals(listOf("/traits/t/dispatch/work/seats/~1"), lint(text).paths(overridesDispatch))
    }
}
