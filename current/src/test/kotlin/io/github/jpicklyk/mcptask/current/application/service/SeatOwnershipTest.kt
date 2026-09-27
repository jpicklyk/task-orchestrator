package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `task-scope`/`task-scope-addendum`/`test-plan` notes on
 * item `79cd4f0c-10aa-484e-b378-d4d5c0f10430` (A1, stage A1b) — scenarios S5 and S6. Exercises
 * [computeMissingBySeat] directly: the dispatch contract's Public-API rule carves this function out
 * as itself a public entry point (not an internal helper standing in for the MCP/REST surface), so a
 * direct unit test is the correct harness here — [SeatServingMcpTest] covers the MCP-serving
 * scenarios (S10/S12/S13) through the real tool/ServerComposition path.
 *
 * Oracle for every assertion below: DEC-11 and `task-scope` §6 ("owner = note.seat naming a merged
 * seat whose phase == note.role; else unowned"; "missingBySeat = `{<seat>: [keys], ...,
 * \"unowned\": [keys]}`: non-empty buckets only ..., seats in merged order, `unowned` last, keys in
 * `missing` order") plus the `task-scope-addendum`'s S5/S6 scenario detail and its DEC-11/§6 owner
 * rule citations. Never derived from the implementation.
 *
 * Red-proof (orchestrator-run, `task-scope-addendum` "Red-proof recipes"):
 * - M3 (drop the `phase == note.role` owner check) reddens the S5 test below — a note whose `seat`
 *   names a real seat of a DIFFERENT phase would then be credited to that seat instead of `unowned`.
 * - M13 (include empty buckets) reddens the S6 "seat with nothing missing is absent" /
 *   "empty missingKeys returns an empty map" tests below.
 *
 * EXISTING-SURFACE per `test-plan`'s S5/S6 labels are not stated explicitly there (S5/S6 are listed
 * under EDGE without a NEW-SURFACE/EXISTING-SURFACE tag), but [computeMissingBySeat] and
 * [UNOWNED_SEAT_BUCKET] are themselves new declarations introduced by this stage's implementation
 * (A1b, commit `2ec4930c`) — so every scenario here is effectively NEW-SURFACE with the function
 * itself as the narrowest-revert target: reverting the fix removes the function these tests call,
 * which is compile-red, not behavioral red. Per §2's carve-out, the substitute verification is the
 * M3/M13 red-proof recipes above, run by the orchestrator against the landed implementation.
 */
class SeatOwnershipTest {
    private fun schema(
        seats: List<SeatDefinition>,
        notes: List<NoteSchemaEntry>,
    ): WorkItemSchema =
        WorkItemSchema(
            type = "seat-ownership-fixture",
            notes = notes,
            seats = seats,
        )

    // ─────────────────────────────────────────────────────────────────────
    // S5 — unowned bucket: seat null, undeclared seat, phase-mismatched seat
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S5 - a note whose seat is null, undeclared, or names a seat of a different phase lands in unowned`() {
        // schema seats: reviewer is a REVIEW-phase seat, implementer is a WORK-phase seat.
        val fixtureSchema =
            schema(
                seats =
                    listOf(
                        SeatDefinition(name = "reviewer", phase = Role.REVIEW),
                        SeatDefinition(name = "implementer", phase = Role.WORK),
                    ),
                notes =
                    listOf(
                        // q1: queue-phase note, seat null — not part of this call's missingKeys
                        // (the caller has already scoped missingKeys to the current WORK phase).
                        NoteSchemaEntry(key = "q1", role = Role.QUEUE, required = true, seat = null),
                        // w1: WORK-phase note naming "reviewer" — reviewer's declared phase is REVIEW,
                        // so this is a phase mismatch -> unowned (DEC-11).
                        NoteSchemaEntry(key = "w1", role = Role.WORK, required = true, seat = "reviewer"),
                        // w2: WORK-phase note naming an undeclared seat -> unowned.
                        NoteSchemaEntry(key = "w2", role = Role.WORK, required = true, seat = "ghost"),
                        // w3: WORK-phase note naming "implementer" — implementer's declared phase IS
                        // WORK, matching note.role -> owned by implementer.
                        NoteSchemaEntry(key = "w3", role = Role.WORK, required = true, seat = "implementer"),
                    ),
            )

        val result = computeMissingBySeat(fixtureSchema, listOf("w1", "w2", "w3"))

        assertEquals(
            linkedMapOf("implementer" to listOf("w3"), UNOWNED_SEAT_BUCKET to listOf("w1", "w2")),
            result,
        )
        assertEquals(
            listOf("implementer", UNOWNED_SEAT_BUCKET),
            result!!.keys.toList(),
            "bucket order must be merged-seat order (implementer, a WORK seat) with unowned LAST",
        )
        assertEquals(listOf("w1", "w2"), result[UNOWNED_SEAT_BUCKET], "unowned keys stay in missingKeys order")
    }

    @Test
    fun `probe - computeMissingBySeat returns null for a null schema (field omitted, not an empty object)`() {
        // Oracle: task-scope section 6 — "ALL new keys are ABSENT when not seatAware"; with no
        // schema at all there is nothing to consult, so the field must be omitted (null), never {}.
        assertNull(computeMissingBySeat(null, listOf("w1")))
    }

    @Test
    fun `probe - computeMissingBySeat returns null for a schema with no declared seats (not seat-aware)`() {
        // Oracle: task-scope section 6 / section 9 byte-identity — a seat-less schema must never
        // start emitting missingBySeat (that is exactly red-proof M1's failure mode for the whole
        // seat-aware surface, characterized at the seat-ownership unit directly here).
        val seatlessSchema =
            schema(
                seats = emptyList(),
                notes = listOf(NoteSchemaEntry(key = "w1", role = Role.WORK, required = true, seat = null)),
            )
        assertNull(computeMissingBySeat(seatlessSchema, listOf("w1")))
    }

    // ─────────────────────────────────────────────────────────────────────
    // S6 — shape: merged-seat bucket order, missing-order keys, absent/empty rules, cross-phase
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S6 - bucket order follows merged-seat declaration order, not note or missingKeys order`() {
        // Seats declared [a, b]; the note for seat b is declared BEFORE the note for seat a, and
        // missingKeys also lists kb1 before ka1 — the resulting map must still order buckets [a, b].
        val fixtureSchema =
            schema(
                seats = listOf(SeatDefinition(name = "a", phase = Role.WORK), SeatDefinition(name = "b", phase = Role.WORK)),
                notes =
                    listOf(
                        NoteSchemaEntry(key = "kb1", role = Role.WORK, required = true, seat = "b"),
                        NoteSchemaEntry(key = "ka1", role = Role.WORK, required = true, seat = "a"),
                    ),
            )

        val result = computeMissingBySeat(fixtureSchema, listOf("kb1", "ka1"))

        assertEquals(linkedMapOf("a" to listOf("ka1"), "b" to listOf("kb1")), result)
        assertEquals(listOf("a", "b"), result!!.keys.toList(), "bucket order must be merged-seat order [a, b]")
    }

    @Test
    fun `S6 - a seat with nothing missing is absent from the map`() {
        val fixtureSchema =
            schema(
                seats =
                    listOf(
                        SeatDefinition(name = "a", phase = Role.WORK),
                        SeatDefinition(name = "b", phase = Role.WORK),
                        SeatDefinition(name = "c", phase = Role.WORK),
                    ),
                notes =
                    listOf(
                        NoteSchemaEntry(key = "ka", role = Role.WORK, required = true, seat = "a"),
                        NoteSchemaEntry(key = "kb", role = Role.WORK, required = true, seat = "b"),
                    ),
            )

        // Seat "c" owns no note in this schema at all, so it can never appear regardless of input.
        val result = computeMissingBySeat(fixtureSchema, listOf("ka", "kb"))

        assertEquals(linkedMapOf("a" to listOf("ka"), "b" to listOf("kb")), result)
        assertTrue("c" !in result!!.keys, "a seat that owns nothing missing must be absent, not an empty list")
    }

    @Test
    fun `S6 - empty missingKeys on a seat-aware schema returns an empty map, not null`() {
        // Oracle: task-scope section 6 — "object may be {}" — a seat-aware schema with nothing
        // missing still reports the (empty) map, distinct from a seat-less schema's null/omission.
        val fixtureSchema =
            schema(
                seats = listOf(SeatDefinition(name = "a", phase = Role.WORK)),
                notes = listOf(NoteSchemaEntry(key = "ka", role = Role.WORK, required = true, seat = "a")),
            )

        val result = computeMissingBySeat(fixtureSchema, emptyList())

        assertEquals(emptyMap(), result)
        assertTrue(result != null, "a seat-aware schema with nothing missing must return {} , never null")
    }

    @Test
    fun `S6 - buckets span multiple phases when missingKeys mix queue and work notes (complete trigger)`() {
        // Oracle: task-scope-addendum S6 — "Complete trigger with queue and work notes missing ->
        // buckets drawn from both phases." Each NoteSchemaEntry carries its own `role`, so the
        // function needs no external "current phase" argument to place a queue-phase and a
        // work-phase missing key into their respective phase-matched seats in one call.
        val fixtureSchema =
            schema(
                seats =
                    listOf(
                        SeatDefinition(name = "planner", phase = Role.QUEUE),
                        SeatDefinition(name = "implementer", phase = Role.WORK),
                    ),
                notes =
                    listOf(
                        NoteSchemaEntry(key = "q", role = Role.QUEUE, required = true, seat = "planner"),
                        NoteSchemaEntry(key = "w", role = Role.WORK, required = true, seat = "implementer"),
                    ),
            )

        val result = computeMissingBySeat(fixtureSchema, listOf("q", "w"))

        assertEquals(linkedMapOf("planner" to listOf("q"), "implementer" to listOf("w")), result)
        assertEquals(
            listOf("planner", "implementer"),
            result!!.keys.toList(),
            "bucket order follows merged-seat declaration order across phases"
        )
    }
}
