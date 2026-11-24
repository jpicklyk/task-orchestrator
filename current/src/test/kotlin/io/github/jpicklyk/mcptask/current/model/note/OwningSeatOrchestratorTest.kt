package io.github.jpicklyk.mcptask.current.model.note

import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.schema.SeatDefinition
import io.github.jpicklyk.mcptask.current.model.schema.WorkItemSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Independent tests (test-author seat, item 34d41fe6) for scenarios S1 to S5 of the frozen test-plan (EXISTING-SURFACE): the
 * orchestrator seat owns a note labelled with it in any phase.
 *
 * Oracle: the diagnosis "Fix (frozen)" (the label equal to SeatDefinition.ORCHESTRATOR_WRITABLE_SEAT is owned when a seat of
 * that exact name is declared, whatever its phase; seatless unchanged; every other seat stays phase-matched; keysOf inherits).
 *
 * Fixture O: seats planner@QUEUE, implementer@WORK, orchestrator@WORK, reviewer@REVIEW.
 *
 * Negative assertions: S2 is controlled by S1 (the same entry is owned with the orchestrator seat declared); S3 is controlled
 * by the S1 and S4 pairing (a phase-mismatched label of another seat stays unowned while the orchestrator one is owned).
 *
 * Forbidden-construct declaration: none.
 */
class OwningSeatOrchestratorTest {
    private val seatsO =
        listOf(
            SeatDefinition(name = "planner", phase = Role.QUEUE),
            SeatDefinition(name = "implementer", phase = Role.WORK),
            SeatDefinition(name = "orchestrator", phase = Role.WORK),
            SeatDefinition(name = "reviewer", phase = Role.REVIEW)
        )

    private val rc = NoteSchemaEntry(key = "review-checklist", role = Role.REVIEW, required = true, seat = "reviewer")
    private val oc = NoteSchemaEntry(key = "orchestrator-confirmation", role = Role.REVIEW, required = true, seat = "orchestrator")
    private val st = NoteSchemaEntry(key = "st", role = Role.WORK, seat = "orchestrator")

    @Test
    fun `S1 a review note labelled orchestrator is owned by orchestrator although the seat is declared for work`() {
        assertEquals("orchestrator", OwningSeat.of(oc, seatsO))
    }

    @Test
    fun `S1 a queue note labelled orchestrator is owned by orchestrator`() {
        val queueNote = NoteSchemaEntry(key = "q", role = Role.QUEUE, seat = "orchestrator")
        assertEquals("orchestrator", OwningSeat.of(queueNote, seatsO))
    }

    @Test
    fun `S2 without an orchestrator seat the same review note is unowned`() {
        val withoutOrchestrator = seatsO.filter { it.name != "orchestrator" }
        assertNull(OwningSeat.of(oc, withoutOrchestrator))
    }

    @Test
    fun `S3 a review note labelled with a work seat stays unowned while the orchestrator one is owned`() {
        val other = NoteSchemaEntry(key = "x", role = Role.REVIEW, seat = "implementer")
        assertNull(OwningSeat.of(other, seatsO))
        assertEquals("orchestrator", OwningSeat.of(oc, seatsO))
    }

    @Test
    fun `S4 seatless schema returns the label whatever the phase`() {
        assertEquals("orchestrator", OwningSeat.of(oc, emptyList()))
        val other = NoteSchemaEntry(key = "x", role = Role.REVIEW, seat = "implementer")
        assertEquals("implementer", OwningSeat.of(other, emptyList()))
    }

    @Test
    fun `S5 keysOf orchestrator holds its labelled notes of every phase and nothing else`() {
        assertEquals(
            setOf("orchestrator-confirmation", "st"),
            OwningSeat.keysOf("orchestrator", listOf(rc, oc, st), seatsO)
        )
        assertEquals(setOf("review-checklist"), OwningSeat.keysOf("reviewer", listOf(rc, oc, st), seatsO))
    }

    @Test
    fun `S5 the schema accessors agree with keysOf and of`() {
        val schema = WorkItemSchema(type = "t", notes = listOf(rc, oc, st), seats = seatsO)
        assertEquals(setOf("orchestrator-confirmation", "st"), schema.noteKeysOwnedBy("orchestrator"))
        assertEquals("orchestrator", schema.owningSeatOf("orchestrator-confirmation"))
        assertNull(schema.owningSeatOf("no-such-note"))
    }

    @Test
    fun `probe a label that differs only in case from the orchestrator seat is unowned`() {
        val upper = NoteSchemaEntry(key = "u", role = Role.REVIEW, seat = "Orchestrator")
        assertNull(OwningSeat.of(upper, seatsO))
        assertEquals("orchestrator", OwningSeat.of(oc, seatsO))
    }

    @Test
    fun `probe a capitalised label on a note of another phase is unowned`() {
        val upperQueue = NoteSchemaEntry(key = "u", role = Role.QUEUE, seat = "Orchestrator")
        assertNull(OwningSeat.of(upperQueue, seatsO))
    }

    @Test
    fun `probe a null entry and an empty label are unowned`() {
        assertNull(OwningSeat.of(null, seatsO))
        assertNull(OwningSeat.of(NoteSchemaEntry(key = "e", role = Role.REVIEW, seat = ""), seatsO))
        assertNull(OwningSeat.of(NoteSchemaEntry(key = "n", role = Role.REVIEW, seat = null), seatsO))
    }
}
