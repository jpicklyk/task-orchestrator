package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.lifecycle.SeatCaller
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.note.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.model.schema.SeatDefinition
import io.github.jpicklyk.mcptask.current.model.schema.WorkItemSchema
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Independent tests (test-author seat, item 43b60546) for scenarios S16 and S17 of the frozen test-plan (EXISTING-SURFACE):
 * the two pre-existing readers of the owner rule, missingBySeat bucketing and the note-write seat rule, agree with it.
 *
 * Oracle: S16 the SeatOwnership KDoc and DEC-11 (owner = label naming a declared seat of the note's phase, else "unowned";
 * non-empty buckets only, seats in declared order, unowned last, keys in missing order; seatless schema gives null); S17 the
 * NoteSeatRule KDoc (an unowned key defers; a key owned by seat O refuses another seat) and rule R of the test-plan.
 *
 * Forbidden-construct declaration: none.
 */
class OwningSeatReadersParityTest {
    private val seats =
        listOf(
            SeatDefinition(name = "implementer", phase = Role.WORK),
            SeatDefinition(name = "reviewer", phase = Role.REVIEW)
        )

    private val notes =
        listOf(
            NoteSchemaEntry(key = "a", role = Role.WORK, seat = "implementer"),
            NoteSchemaEntry(key = "b", role = Role.WORK, seat = "reviewer"),
            NoteSchemaEntry(key = "c", role = Role.WORK, seat = "ghost"),
            NoteSchemaEntry(key = "d", role = Role.WORK, seat = null),
            NoteSchemaEntry(key = "e", role = Role.REVIEW, seat = "reviewer")
        )

    @Test
    fun `S16 missingBySeat buckets same-phase, other-phase, undeclared and null-seat keys with unowned last`() {
        val schema = WorkItemSchema(type = "t", notes = notes, seats = seats)
        val result = computeMissingBySeat(schema, listOf("a", "b", "c", "d", "e"))
        assertEquals(
            listOf(
                "implementer" to listOf("a"),
                "reviewer" to listOf("e"),
                UNOWNED_SEAT_BUCKET to listOf("b", "c", "d")
            ),
            result?.toList()
        )
    }

    @Test
    fun `S16 missingBySeat omits empty buckets and is null for a seatless schema`() {
        val schema = WorkItemSchema(type = "t", notes = notes, seats = seats)
        assertEquals(listOf("implementer" to listOf("a")), computeMissingBySeat(schema, listOf("a"))?.toList())
        assertEquals(emptyMap(), computeMissingBySeat(schema, emptyList()))
        assertNull(computeMissingBySeat(WorkItemSchema(type = "t", notes = notes), listOf("a", "b")))
    }

    @Test
    fun `S17 ownerSeatOf follows the rule - same phase label, other phase none, no seats none`() {
        assertEquals("implementer", NoteSeatRule.ownerSeatOf(notes[0], seats))
        assertNull(NoteSeatRule.ownerSeatOf(notes[1], seats))
        assertNull(NoteSeatRule.ownerSeatOf(notes[2], seats))
        assertNull(NoteSeatRule.ownerSeatOf(notes[3], seats))
        assertNull(NoteSeatRule.ownerSeatOf(notes[0], emptyList()))
    }

    @Test
    fun `S17 check defers on an other-phase key in an open run and refuses a foreign seat on an owned key`() {
        val itemId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val caller = SeatCaller(seat = "reviewer", runId = runId, itemId = itemId, human = false)

        assertEquals(SeatVerdict.Defer, NoteSeatRule.check(itemId, "b", notes[1], seats, runId, caller))

        // control: the same caller on a key genuinely owned by another seat is refused, so the Defer above is the rule's reading
        assertIs<SeatVerdict.Refuse>(NoteSeatRule.check(itemId, "a", notes[0], seats, runId, caller))
    }
}
