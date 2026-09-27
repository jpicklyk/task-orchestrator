package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.domain.model.SeatDispatchOverride
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [YamlSchemaParser.parseRoot]'s seat-parsing contract (item `79cd4f0c`, stage A1a):
 * schema-level and trait-level `seats:` lists, note-entry `seat`/`independent_of`, the F1-F4 fatal
 * structural checks (thrown as [ConfigStructureException]), and the W1/W2/W3/W4/W6 load warnings
 * that touch the seat dimension. Mirrors [YamlSchemaParserDispatchTest]'s and
 * [YamlSchemaParserRoleWarningTest]'s fixture conventions: real YAML text parsed via
 * [SafeConstructor], the same shape [YamlWorkItemSchemaService]/[PerRootConfigService] parse.
 *
 * Scope: the PARSE side only, per the dispatch contract's `SeatConfigParseTest` row (S2-S4). The
 * global-file-startup and per-root-push-rejection halves of S3/S4 are owned by A1c's
 * `SeatConfigLoadFailClosedTest` — both wiring layers funnel through the same [YamlSchemaParser]
 * exercised directly here, so this file proves the shared, wrapped structural checks, not the
 * wrapping. S7-S9 (layering/merge/dispatch-by-seat) are owned by `LayeredConfigSeatTest`.
 *
 * Independent test authorship per the `needs-test-author` trait: oracles come from the item's
 * frozen `task-scope` §2/§3 and `task-scope-addendum` S2-S4, never from reading the parser's
 * source. Warnings are asserted by substring (the offending key/value), never full warning text.
 */
class SeatConfigParseTest {
    private fun parse(yaml: String): YamlSchemaParser.ParsedConfig {
        @Suppress("UNCHECKED_CAST")
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlSchemaParser.parseRoot(root, warnOnMissingSchemas = false)
    }

    // ──────────────────────────────────────────────
    // S2 — happy path: task-scope §2's full example round-trips through parseRoot
    // ──────────────────────────────────────────────

    @Test
    fun `S2 task-scope §2 example parses schema seats, trait seats and note seat linkage`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    default_traits: [needs-test-author]
                    seats:
                      - { name: planner,      phase: queue }
                      - { name: implementer,  phase: work, enters: true }
                      - { name: extractor,    phase: work, after: [implementer] }
                      - { name: orchestrator, phase: work }
                      - { name: reviewer,     phase: review }
                    notes:
                      - { key: diagnosis, role: queue, required: true, seat: planner }
                      - { key: implementation-notes, role: work, required: true, seat: implementer }
                      - { key: session-tracking, role: work, required: true, seat: orchestrator }
                traits:
                  needs-test-author:
                    seats:
                      - { name: test-author, phase: work, after: [extractor], reads_exclude: [implementation-notes] }
                    notes:
                      - { key: test-plan, role: queue, required: true, seat: planner }
                      - { key: test-manifest, role: work, required: true, seat: test-author, independent_of: [implementer] }
                """.trimIndent()
            )

        val schema = parsed.workItemSchemas.getValue("bug-fix")
        assertEquals(
            listOf(
                SeatDefinition("planner", Role.QUEUE),
                SeatDefinition("implementer", Role.WORK, enters = true),
                SeatDefinition("extractor", Role.WORK, after = listOf("implementer")),
                SeatDefinition("orchestrator", Role.WORK),
                SeatDefinition("reviewer", Role.REVIEW)
            ),
            schema.seats,
            "schema seats must parse in declared order, verbatim"
        )

        assertEquals(
            listOf(SeatDefinition("test-author", Role.WORK, after = listOf("extractor"), readsExclude = listOf("implementation-notes"))),
            parsed.traitSeats["needs-test-author"]
        )

        val diagnosis = schema.notes.single { it.key == "diagnosis" }
        assertEquals("planner", diagnosis.seat)
        val implementationNotes = schema.notes.single { it.key == "implementation-notes" }
        assertEquals("implementer", implementationNotes.seat)
        val sessionTracking = schema.notes.single { it.key == "session-tracking" }
        assertEquals("orchestrator", sessionTracking.seat)

        val traitNotes = parsed.traits.getValue("needs-test-author")
        val testPlan = traitNotes.single { it.key == "test-plan" }
        assertEquals("planner", testPlan.seat)
        assertTrue(testPlan.independentOf.isEmpty())
        val testManifest = traitNotes.single { it.key == "test-manifest" }
        assertEquals("test-author", testManifest.seat)
        assertEquals(listOf("implementer"), testManifest.independentOf)
    }

    // ──────────────────────────────────────────────
    // S3 — F1: two `enters: true` seats in one phase is a fatal structural error
    // ──────────────────────────────────────────────

    @Test
    fun `S3 F1a two enters true seats in one phase within one schema's own seats list is fatal`() {
        val exception =
            assertFailsWith<ConfigStructureException> {
                parse(
                    """
                    work_item_schemas:
                      bug-fix:
                        seats:
                          - { name: a, phase: work, enters: true }
                          - { name: b, phase: work, enters: true }
                    """.trimIndent()
                )
            }
        assertTrue(exception.message.orEmpty().contains("work"), "message should name the offending phase: ${exception.message}")
    }

    @Test
    fun `S3 F1b two enters true seats split across a schema and its same-document default_trait is fatal`() {
        val exception =
            assertFailsWith<ConfigStructureException> {
                parse(
                    """
                    work_item_schemas:
                      bug-fix:
                        default_traits: [t1]
                        seats:
                          - { name: a, phase: work, enters: true }
                    traits:
                      t1:
                        seats:
                          - { name: b, phase: work, enters: true }
                    """.trimIndent()
                )
            }
        assertTrue(exception.message.orEmpty().contains("work"), "message should name the offending phase: ${exception.message}")
    }

    // ──────────────────────────────────────────────
    // S4 — F2 duplicate seat name, F3 reserved name, F4 after-cycle: fatal
    // ──────────────────────────────────────────────

    @Test
    fun `S4 F2 a duplicate seat name within one schema's own seats list is fatal`() {
        assertFailsWith<ConfigStructureException> {
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: a, phase: work }
                      - { name: a, phase: review }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `S4 F2 a duplicate seat name split across a schema and its same-document default_trait is fatal`() {
        assertFailsWith<ConfigStructureException> {
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    default_traits: [t1]
                    seats:
                      - { name: a, phase: work }
                traits:
                  t1:
                    seats:
                      - { name: a, phase: review }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `S4 F3 a seat named unowned is rejected as the reserved bucket name`() {
        assertFailsWith<ConfigStructureException> {
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: unowned, phase: work }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `probe -- a seat named Unowned (mixed case) is not reserved -- F3 is an exact-match check only`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: Unowned, phase: work }
                """.trimIndent()
            )
        val schema = parsed.workItemSchemas.getValue("bug-fix")
        assertEquals(listOf(SeatDefinition("Unowned", Role.WORK)), schema.seats)
    }

    @Test
    fun `S4 F4 a two-seat after cycle within one seats list is fatal`() {
        assertFailsWith<ConfigStructureException> {
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: a, phase: work, after: [b] }
                      - { name: b, phase: work, after: [a] }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `S4 F4 a non-cyclic linear after chain parses fine (negative control)`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: a, phase: work }
                      - { name: b, phase: work, after: [a] }
                      - { name: c, phase: work, after: [b] }
                """.trimIndent()
            )
        val schema = parsed.workItemSchemas.getValue("bug-fix")
        assertEquals(listOf("a", "b", "c"), schema.seats.map { it.name })
        assertEquals(listOf("b"), schema.seats.single { it.name == "c" }.after)
    }

    // ──────────────────────────────────────────────
    // S4 — W1 unknown note-entry key; seat/independent_of are known keys (no warning)
    // ──────────────────────────────────────────────

    @Test
    fun `S4 W1 an unknown note-entry key produces a warning naming the note key and the offending field`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    notes:
                      - { key: diagnosis, role: queue, foo: bar }
                """.trimIndent()
            )
        assertTrue(parsed.warnings.any { it.contains("diagnosis") && it.contains("foo") }, "warnings: ${parsed.warnings}")
    }

    @Test
    fun `S4 W1 the seat and independent_of note-entry keys are known and produce no warning`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    notes:
                      - { key: diagnosis, role: queue, seat: planner, independent_of: [implementer] }
                """.trimIndent()
            )
        assertTrue(parsed.warnings.isEmpty(), "seat/independent_of are documented keys: ${parsed.warnings}")
    }

    // ──────────────────────────────────────────────
    // S4 — W2 unknown top-level section
    // ──────────────────────────────────────────────

    @Test
    fun `S4 W2 an unknown top-level section produces a warning naming it`() {
        val parsed =
            parse(
                """
                bar: {}
                work_item_schemas:
                  default:
                    notes: []
                """.trimIndent()
            )
        assertTrue(parsed.warnings.any { it.contains("bar") }, "warnings: ${parsed.warnings}")
    }

    // ──────────────────────────────────────────────
    // S4 — W3 unknown schema-level / trait-level key; `seats:` itself is known (no warning)
    // ──────────────────────────────────────────────

    @Test
    fun `S4 W3 an unknown schema-level key and an unknown trait-level key each produce a warning`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    baz: 1
                    notes: []
                traits:
                  t1:
                    qux: 1
                    notes: []
                """.trimIndent()
            )
        assertTrue(parsed.warnings.any { it.contains("bug-fix") && it.contains("baz") }, "warnings: ${parsed.warnings}")
        assertTrue(parsed.warnings.any { it.contains("t1") && it.contains("qux") }, "warnings: ${parsed.warnings}")
    }

    @Test
    fun `S4 W3 a seats key at schema level and trait level is known and produces no unknown-key warning`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: a, phase: work }
                    notes: []
                traits:
                  t1:
                    seats:
                      - { name: b, phase: work }
                    notes: []
                """.trimIndent()
            )
        assertTrue(
            parsed.warnings.none { it.contains("seats") },
            "`seats` is a known schema-level and trait-level key: ${parsed.warnings}"
        )
    }

    // ──────────────────────────────────────────────
    // S4 — W4 malformed seat entries
    // ──────────────────────────────────────────────

    @Test
    fun `S4 W4 a seat entry with a missing or blank name is skipped, valid entries survive`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { phase: work }
                      - { name: "", phase: work }
                      - { name: ok, phase: work }
                """.trimIndent()
            )
        val schema = parsed.workItemSchemas.getValue("bug-fix")
        assertEquals(listOf(SeatDefinition("ok", Role.WORK)), schema.seats)
        assertTrue(parsed.warnings.isNotEmpty(), "a missing/blank seat name must warn")
    }

    @Test
    fun `S4 W4 a seat entry with an invalid phase is skipped with a warning naming the seat and the bad phase`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: a, phase: bogus }
                      - { name: b, phase: work }
                """.trimIndent()
            )
        val schema = parsed.workItemSchemas.getValue("bug-fix")
        assertEquals(listOf(SeatDefinition("b", Role.WORK)), schema.seats)
        assertTrue(parsed.warnings.any { it.contains("a") && it.contains("bogus") }, "warnings: ${parsed.warnings}")
    }

    @Test
    fun `probe -- a mixed-case phase value Work is invalid and the seat is skipped`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: a, phase: Work }
                """.trimIndent()
            )
        val schema = parsed.workItemSchemas.getValue("bug-fix")
        assertTrue(schema.seats.isEmpty(), "phase values are case-sensitive; Work must not match work")
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test
    fun `S4 W4 a non-boolean enters value is coerced to false with a warning`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: a, phase: work, enters: "true" }
                """.trimIndent()
            )
        val seat =
            parsed.workItemSchemas
                .getValue("bug-fix")
                .seats
                .single()
        assertEquals(SeatDefinition("a", Role.WORK, enters = false), seat)
        assertTrue(parsed.warnings.any { it.contains("a") && it.contains("enters") }, "warnings: ${parsed.warnings}")
    }

    @Test
    fun `S4 W4 non-list after and reads_exclude values are coerced to empty lists, and an unknown seat-entry key warns`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: a, phase: work, after: "b", reads_exclude: "x", bogus_field: 1 }
                """.trimIndent()
            )
        val seat =
            parsed.workItemSchemas
                .getValue("bug-fix")
                .seats
                .single()
        assertEquals("a", seat.name)
        assertEquals(Role.WORK, seat.phase)
        assertTrue(seat.after.isEmpty(), "a non-list after value must coerce to empty, not throw")
        assertTrue(seat.readsExclude.isEmpty(), "a non-list reads_exclude value must coerce to empty, not throw")
        assertTrue(parsed.warnings.isNotEmpty(), "malformed after/reads_exclude/unknown key must produce at least one warning")
    }

    // ──────────────────────────────────────────────
    // S4 — W6 malformed dispatch-by-seat override; a seats-only phase map is valid
    // ──────────────────────────────────────────────

    @Test
    fun `S4 W6 a seat dispatch override with only an unknown field is dropped with a warning naming the field`() {
        val parsed =
            parse(
                """
                traits:
                  t1:
                    dispatch:
                      work:
                        seats:
                          x: { colour: red }
                """.trimIndent()
            )
        assertTrue(
            parsed.traitDispatchBySeat["t1"]?.get(Role.WORK)?.containsKey("x") != true,
            "a seat override with no valid fields must not be stored"
        )
        assertTrue(parsed.warnings.any { it.contains("t1") && it.contains("colour") }, "warnings: ${parsed.warnings}")
    }

    @Test
    fun `a phase map carrying only seats is valid -- no phase profile, no unknown-field warning`() {
        val parsed =
            parse(
                """
                traits:
                  t1:
                    dispatch:
                      work:
                        seats:
                          x: { model: m1 }
                """.trimIndent()
            )
        assertNull(parsed.traitDispatch["t1"]?.get(Role.WORK), "a seats-only phase map carries no phase-level dispatch profile")
        assertEquals(SeatDispatchOverride(model = "m1"), parsed.traitDispatchBySeat["t1"]?.get(Role.WORK)?.get("x"))
        assertTrue(
            parsed.warnings.none { it.contains("t1") && it.contains("valid field") },
            "seats-only must not be flagged as a phase map with no valid fields: ${parsed.warnings}"
        )
    }

    @Test
    fun `an explicit null seat-override field is recorded as cleared`() {
        val parsed =
            parse(
                """
                traits:
                  t1:
                    dispatch:
                      work:
                        seats:
                          x: { agent: null }
                """.trimIndent()
            )
        assertEquals(SeatDispatchOverride(cleared = setOf("agent")), parsed.traitDispatchBySeat["t1"]?.get(Role.WORK)?.get("x"))
    }

    @Test
    fun `probe -- a blank seat-override agent field is dropped, not treated as an explicit clear`() {
        val parsed =
            parse(
                """
                traits:
                  t1:
                    dispatch:
                      work:
                        seats:
                          x: { agent: "" }
                """.trimIndent()
            )
        val override = parsed.traitDispatchBySeat["t1"]?.get(Role.WORK)?.get("x")
        assertNull(override?.agent, "a blank field value must be dropped")
        assertFalse(override?.cleared.orEmpty().contains("agent"), "a blank value is a drop, not a null-clear")
        assertTrue(parsed.warnings.any { it.contains("t1") && it.contains("agent") }, "warnings: ${parsed.warnings}")
    }

    // ──────────────────────────────────────────────
    // Probes: empty vs null seats; reused seat name across different (unrelated) traits; `after`
    // naming a seat in another phase
    // ──────────────────────────────────────────────

    @Test
    fun `probe -- an empty seats list is equivalent to omitting the key entirely`() {
        val parsed = parse("work_item_schemas:\n  bug-fix:\n    seats: []\n    notes: []\n")
        val schema = parsed.workItemSchemas.getValue("bug-fix")
        assertTrue(schema.seats.isEmpty())
        assertFalse(schema.isSeatAware())
    }

    @Test
    fun `probe -- an explicit null seats key is equivalent to omitting the key entirely`() {
        val parsed = parse("work_item_schemas:\n  bug-fix:\n    seats: ~\n    notes: []\n")
        assertTrue(
            parsed.workItemSchemas
                .getValue("bug-fix")
                .seats
                .isEmpty()
        )
    }

    @Test
    fun `probe -- the same seat name reused across two unrelated traits is not a duplicate-name conflict`() {
        val parsed =
            parse(
                """
                traits:
                  t1:
                    seats:
                      - { name: shared, phase: work }
                  t2:
                    seats:
                      - { name: shared, phase: work }
                """.trimIndent()
            )
        assertEquals(listOf(SeatDefinition("shared", Role.WORK)), parsed.traitSeats["t1"])
        assertEquals(listOf(SeatDefinition("shared", Role.WORK)), parsed.traitSeats["t2"])
    }

    @Test
    fun `probe -- an after reference naming a seat in a different phase is served as declared, no failure`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  bug-fix:
                    seats:
                      - { name: a, phase: queue }
                      - { name: b, phase: work, after: [a] }
                """.trimIndent()
            )
        val seatB =
            parsed.workItemSchemas
                .getValue("bug-fix")
                .seats
                .single { it.name == "b" }
        assertEquals(listOf("a"), seatB.after, "after is served verbatim; cross-phase after references are not validated at parse time")
    }
}
