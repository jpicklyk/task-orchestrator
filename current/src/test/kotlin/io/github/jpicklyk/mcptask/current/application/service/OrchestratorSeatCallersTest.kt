package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.lifecycle.internal.domain.GatePredicate
import io.github.jpicklyk.mcptask.current.lifecycle.internal.domain.computeMissingBySeat
import io.github.jpicklyk.mcptask.current.model.claim.ActorClaim
import io.github.jpicklyk.mcptask.current.model.claim.ActorKind
import io.github.jpicklyk.mcptask.current.model.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.note.Note
import io.github.jpicklyk.mcptask.current.model.note.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.model.run.RunSeat
import io.github.jpicklyk.mcptask.current.model.schema.IndependenceConstraint
import io.github.jpicklyk.mcptask.current.model.schema.IndependenceMode
import io.github.jpicklyk.mcptask.current.model.schema.IndependencePolicy
import io.github.jpicklyk.mcptask.current.model.schema.IndependenceViolation
import io.github.jpicklyk.mcptask.current.model.schema.SeatCaller
import io.github.jpicklyk.mcptask.current.model.schema.SeatDefinition
import io.github.jpicklyk.mcptask.current.model.schema.WorkItemSchema
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 34d41fe6) for scenarios S6 to S10 of the frozen test-plan (EXISTING-SURFACE): the
 * callers of the owner rule (missingBySeat, the note-write seat rule, the independence gate) treat an orchestrator-labelled
 * note of another phase as owned by orchestrator.
 *
 * Oracle: the diagnosis "Caller behaviour change" 1 to 3, the NoteSeatRule KDoc (coordinator token of THIS run allowed iff the
 * owner is the coordinator-writable seat; human allowed; any other caller refused with note_owned_by_other; no open run defers),
 * the SeatOwnership KDoc (seat order, unowned last, non-empty buckets) and the GatePredicate check order.
 *
 * Fixture O: seats planner@QUEUE, implementer@WORK, orchestrator@WORK, reviewer@REVIEW; rc = review-checklist (REVIEW, required,
 * reviewer); oc = orchestrator-confirmation (REVIEW, required, orchestrator); item I, open run R.
 *
 * Negative-assertion fixture conditions: each Refuse is paired with Allow for the coordinator or human on the same key (so the
 * key is owned, not deferred); the S10 control uses a different actor id and must give no violation, while the main case with the
 * same id gives one, so the violation is produced by the cross-phase orchestrator ownership alone.
 *
 * Forbidden-construct declaration: none.
 */
class OrchestratorSeatCallersTest {
    private val seatsO =
        listOf(
            SeatDefinition(name = "planner", phase = Role.QUEUE),
            SeatDefinition(name = "implementer", phase = Role.WORK),
            SeatDefinition(name = "orchestrator", phase = Role.WORK),
            SeatDefinition(name = "reviewer", phase = Role.REVIEW)
        )

    private val rc = NoteSchemaEntry(key = "review-checklist", role = Role.REVIEW, required = true, seat = "reviewer")
    private val oc = NoteSchemaEntry(key = "orchestrator-confirmation", role = Role.REVIEW, required = true, seat = "orchestrator")

    private val itemId: UUID = UUID.randomUUID()
    private val runId: UUID = UUID.randomUUID()
    private val otherRunId: UUID = UUID.randomUUID()

    private val coordinator = SeatCaller(seat = RunSeat.ORCHESTRATOR, runId = runId, itemId = null, human = false)
    private val human = SeatCaller(seat = null, runId = null, itemId = null, human = true)

    private fun check(
        caller: SeatCaller,
        openRun: UUID? = runId,
        seats: List<SeatDefinition> = seatsO
    ): SeatVerdict = NoteSeatRule.check(itemId, oc.key, oc, seats, openRun, caller)

    private fun assertRefusedOwnedByOrchestrator(verdict: SeatVerdict) {
        val refuse = assertIs<SeatVerdict.Refuse>(verdict)
        assertEquals(ErrorDetail.NoteOwnedByOther(itemId, oc.key, "orchestrator"), refuse.error.detail)
    }

    // ---- S6 missingBySeat

    @Test
    fun `S6 a missing orchestrator review note buckets under orchestrator and not unowned`() {
        val schema = WorkItemSchema(type = "t", notes = listOf(rc, oc), seats = seatsO)
        val result = computeMissingBySeat(schema, listOf(oc.key, rc.key))
        assertEquals(
            listOf("orchestrator" to listOf(oc.key), "reviewer" to listOf(rc.key)),
            result?.toList()
        )
    }

    // ---- S7 / S8 / S9 NoteSeatRule

    @Test
    fun `S7 the run coordinator and a human may write the orchestrator-owned note`() {
        assertEquals(SeatVerdict.Allow, check(coordinator))
        assertEquals(SeatVerdict.Allow, check(human))
    }

    @Test
    fun `S8 a reviewer member token, no token and another run's coordinator are refused with the owner named`() {
        assertRefusedOwnedByOrchestrator(check(SeatCaller(seat = "reviewer", runId = runId, itemId = itemId, human = false)))
        assertRefusedOwnedByOrchestrator(check(SeatCaller.NONE))
        assertRefusedOwnedByOrchestrator(check(SeatCaller(seat = RunSeat.ORCHESTRATOR, runId = otherRunId, itemId = null, human = false)))
    }

    @Test
    fun `S8 a member token of the orchestrator seat bound to this item is allowed, the same seat on another item is refused`() {
        assertEquals(SeatVerdict.Allow, check(SeatCaller(seat = "orchestrator", runId = runId, itemId = itemId, human = false)))
        assertRefusedOwnedByOrchestrator(
            check(SeatCaller(seat = "orchestrator", runId = runId, itemId = UUID.randomUUID(), human = false))
        )
    }

    @Test
    fun `S8 control - without the orchestrator seat declared the key is unowned and every caller defers`() {
        val withoutOrchestrator = seatsO.filter { it.name != "orchestrator" }
        assertEquals(
            SeatVerdict.Defer,
            check(SeatCaller(seat = "reviewer", runId = runId, itemId = itemId, human = false), seats = withoutOrchestrator)
        )
    }

    @Test
    fun `S9 with no open run every caller defers`() {
        val callers =
            listOf(
                coordinator,
                human,
                SeatCaller.NONE,
                SeatCaller(seat = "reviewer", runId = runId, itemId = itemId, human = false),
                SeatCaller(seat = "orchestrator", runId = runId, itemId = itemId, human = false)
            )
        callers.forEach { assertEquals(SeatVerdict.Defer, check(it, openRun = null), "caller $it") }
    }

    @Test
    fun `S9 ownerSeatOf agrees with the owner rule and is null when no seats are declared`() {
        assertEquals("orchestrator", NoteSeatRule.ownerSeatOf(oc, seatsO))
        assertEquals(null, NoteSeatRule.ownerSeatOf(oc, emptyList()))
    }

    // ---- S10 independence gate

    private val reject = IndependencePolicy(IndependenceMode.REJECT)

    private fun schemaWithIndependence(): WorkItemSchema =
        WorkItemSchema(
            type = "t",
            notes = listOf(rc.copy(independentOf = listOf("orchestrator")), oc),
            seats = seatsO
        )

    private fun filled(
        entry: NoteSchemaEntry,
        actor: String
    ): Note =
        Note(
            itemId = itemId,
            key = entry.key,
            role = entry.role.name.lowercase(),
            body = "filled",
            actorClaim = ActorClaim(id = actor, kind = ActorKind.SUBAGENT),
            seat = null
        )

    @Test
    fun `S10 independent of orchestrator counts the orchestrator note of another phase and flags the same actor`() {
        val notes = listOf(filled(rc, "local"), filled(oc, "local"))
        val violations = GatePredicate.violationsForComplete(schemaWithIndependence(), notes, reject)
        assertEquals(
            listOf(IndependenceViolation("review-checklist", "reviewer", IndependenceConstraint.SAME_ACTOR, "orchestrator")),
            violations
        )
        assertTrue(GatePredicate.blocksAdvance(violations, reject))
    }

    @Test
    fun `S10 control - a different actor on the orchestrator note gives no violation and does not block`() {
        val notes = listOf(filled(rc, "local"), filled(oc, "other"))
        val violations = GatePredicate.violationsForComplete(schemaWithIndependence(), notes, reject)
        assertEquals(emptyList(), violations)
        assertTrue(!GatePredicate.blocksAdvance(violations, reject))
    }
}
