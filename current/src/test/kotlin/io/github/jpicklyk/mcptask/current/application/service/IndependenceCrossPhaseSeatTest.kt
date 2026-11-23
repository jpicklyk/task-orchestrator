package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.model.claim.ActorClaim
import io.github.jpicklyk.mcptask.current.model.claim.ActorKind
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.note.Note
import io.github.jpicklyk.mcptask.current.model.note.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.model.schema.IndependenceConstraint
import io.github.jpicklyk.mcptask.current.model.schema.IndependenceMode
import io.github.jpicklyk.mcptask.current.model.schema.IndependencePolicy
import io.github.jpicklyk.mcptask.current.model.schema.IndependenceViolation
import io.github.jpicklyk.mcptask.current.model.schema.SeatDefinition
import io.github.jpicklyk.mcptask.current.model.schema.WorkItemSchema
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 43b60546) for the independence gate on a seat-aware schema: a seat's notes are the
 * keys it OWNS, so a label naming a seat declared for a different phase than the note's own role is not ownership.
 * Scenarios S1-S7 and the probes of the frozen test-plan (all EXISTING-SURFACE; only pre-existing declarations are used).
 *
 * Oracle: the test-plan rule R ("Owning seat": seats empty -> the label; else the label only if a seat of that name is declared
 * with phase == the note's role, else none), config-format.md "notes owned by" for independent_of, and the same_actor,
 * missing_actor and unverified clauses of the independence predicate (see IndependencePredicateTest). Never derived from the code.
 *
 * Fixture F: seats implementer@work, test-author@work, reviewer@review; notes implementation-notes (work, implementer),
 * test-manifest (work, test-author, independentOf implementer), x-review (review, label implementer: a seat of another phase).
 * "Same principal" = one actor claim id with Note.seat null on both notes.
 *
 * Negative-assertion fixture conditions: every "no violation against implementer" assertion has a control in the same test
 * (x-review declared in phase WORK, so the label is real ownership) that DOES produce one, so the assertion fails if the
 * cross-phase label were counted.
 *
 * Forbidden-construct declaration: none.
 */
class IndependenceCrossPhaseSeatTest {
    private val itemId: UUID = UUID.randomUUID()
    private val reject = IndependencePolicy(IndependenceMode.REJECT)

    private companion object {
        const val IMPL = "implementation-notes"
        const val MANIFEST = "test-manifest"
        const val XREV = "x-review"
    }

    private val seats =
        listOf(
            SeatDefinition(name = "implementer", phase = Role.WORK),
            SeatDefinition(name = "test-author", phase = Role.WORK),
            SeatDefinition(name = "reviewer", phase = Role.REVIEW)
        )

    private fun schemaF(
        xReviewRole: Role = Role.REVIEW,
        seatList: List<SeatDefinition> = seats,
        independentOf: List<String> = listOf("implementer"),
        xReviewSeat: String? = "implementer"
    ): WorkItemSchema =
        WorkItemSchema(
            type = "cross-phase-seat-test",
            notes =
                listOf(
                    NoteSchemaEntry(key = IMPL, role = Role.WORK, required = true, seat = "implementer"),
                    NoteSchemaEntry(
                        key = MANIFEST,
                        role = Role.WORK,
                        required = true,
                        seat = "test-author",
                        independentOf = independentOf
                    ),
                    NoteSchemaEntry(key = XREV, role = xReviewRole, required = true, seat = xReviewSeat)
                ),
            seats = seatList
        )

    private fun note(
        key: String,
        role: String,
        actor: String? = "local",
        body: String = "filled"
    ): Note =
        Note(
            itemId = itemId,
            key = key,
            role = role,
            body = body,
            actorClaim = actor?.let { ActorClaim(id = it, kind = ActorKind.SUBAGENT) },
            seat = null
        )

    private fun manifest() = note(MANIFEST, "work")

    private fun xReview(
        role: String = "review",
        actor: String? = "local",
        body: String = "filled"
    ) = note(XREV, role, actor, body)

    private fun complete(
        schema: WorkItemSchema,
        vararg notes: Note,
        policy: IndependencePolicy = reject
    ): List<IndependenceViolation> =
        assertNotNull(GatePredicate.violationsForComplete(schema, notes.toList(), policy), "a declaring entry exists, so a list")

    private fun againstImplementer(vs: List<IndependenceViolation>) = vs.filter { it.conflictingSeat == "implementer" }

    private val sameActorOnManifest =
        listOf(
            IndependenceViolation(
                key = MANIFEST,
                seat = "test-author",
                constraint = IndependenceConstraint.SAME_ACTOR,
                conflictingSeat = "implementer"
            )
        )

    @Test
    fun `S1 a cross-phase label on x-review does not make it the implementer's note - no violation and no block`() {
        val vs = complete(schemaF(), manifest(), xReview())
        assertEquals(emptyList(), vs)
        assertFalse(GatePredicate.blocksAdvance(vs, reject), "REJECT must not block")
    }

    @Test
    fun `S2 control - the same fixture with x-review in phase WORK is same_actor and blocks`() {
        val vs = complete(schemaF(xReviewRole = Role.WORK), manifest(), xReview(role = "work"))
        assertEquals(sameActorOnManifest, vs)
        assertTrue(GatePredicate.blocksAdvance(vs, reject), "REJECT blocks a real same-actor pair")
    }

    @Test
    fun `S3 an actor-less cross-phase x-review raises nothing against implementer, while the in-phase control raises missing_actor`() {
        val cross = complete(schemaF(), manifest(), xReview(actor = null))
        assertEquals(emptyList(), againstImplementer(cross))

        val control = complete(schemaF(xReviewRole = Role.WORK), manifest(), xReview(role = "work", actor = null))
        val againstControl = againstImplementer(control)
        assertTrue(againstControl.isNotEmpty(), "fixture can produce a violation against implementer")
        assertTrue(againstControl.all { it.constraint == IndependenceConstraint.MISSING_ACTOR }, againstControl.toString())
    }

    @Test
    fun `S4 require_verified raises no unverified violation against implementer for a cross-phase x-review`() {
        val policy = IndependencePolicy(IndependenceMode.REJECT, requireVerified = true)
        val cross = complete(schemaF(), manifest(), xReview(), policy = policy)
        assertEquals(emptyList(), againstImplementer(cross))
        // N's own unverified finding (conflictingSeat null) is about the declaring note and stays allowed
        assertTrue(cross.all { it.conflictingSeat == null }, cross.toString())

        val control = complete(schemaF(xReviewRole = Role.WORK), manifest(), xReview(role = "work"), policy = policy)
        assertTrue(
            againstImplementer(control).any { it.constraint == IndependenceConstraint.UNVERIFIED },
            "control: an in-phase owned note is checked for verification"
        )
    }

    @Test
    fun `S5 with no seats declared the label stands as ownership - same_actor against implementer`() {
        val vs = complete(schemaF(seatList = emptyList()), manifest(), xReview())
        val against = againstImplementer(vs)
        assertEquals(1, against.size, vs.toString())
        assertEquals(MANIFEST, against.single().key)
        assertEquals(IndependenceConstraint.SAME_ACTOR, against.single().constraint)
        assertTrue(GatePredicate.blocksAdvance(vs, reject))
    }

    @Test
    fun `S6 start gating only evaluates declaring entries of the current role`() {
        val schema = schemaF()
        val atReview = assertNotNull(GatePredicate.violationsForStart(schema, Role.REVIEW, listOf(manifest(), xReview()), reject))
        assertEquals(emptyList(), atReview.filter { it.key == MANIFEST })

        val atWork = assertNotNull(GatePredicate.violationsForStart(schema, Role.WORK, listOf(manifest(), xReview()), reject))
        assertEquals(emptyList(), atWork)

        // control: in-phase x-review is flagged at WORK start
        val control =
            assertNotNull(
                GatePredicate.violationsForStart(
                    schemaF(xReviewRole = Role.WORK),
                    Role.WORK,
                    listOf(manifest(), xReview(role = "work")),
                    reject
                )
            )
        assertEquals(sameActorOnManifest, control)
    }

    @Test
    fun `S7 only a same-phase owned note counts - a different principal on implementation-notes is no violation`() {
        val other = note(IMPL, "work", actor = "someone-else")
        assertEquals(emptyList(), complete(schemaF(), manifest(), xReview(), other))

        // control: the same principal on implementation-notes is same_actor
        val same = note(IMPL, "work", actor = "local")
        assertEquals(sameActorOnManifest, complete(schemaF(), manifest(), xReview(), same))
    }

    @Test
    fun `probe a duplicate seat name in independentOf does not make a cross-phase note count`() {
        val vs = complete(schemaF(independentOf = listOf("implementer", "implementer")), manifest(), xReview())
        assertEquals(emptyList(), againstImplementer(vs))
    }

    @Test
    fun `probe a label differing only in case names no declared seat so it is not ownership`() {
        // control: the exact-case label in the same phase is ownership
        val control =
            complete(
                schemaF(xReviewRole = Role.WORK, xReviewSeat = "implementer"),
                manifest(),
                xReview(role = "work")
            )
        assertEquals(sameActorOnManifest, control)

        val cased =
            complete(
                schemaF(xReviewRole = Role.WORK, xReviewSeat = "Implementer"),
                manifest(),
                xReview(role = "work")
            )
        assertEquals(emptyList(), againstImplementer(cased))
    }

    @Test
    fun `probe a blank-body x-review is not filled so it is no party to the comparison`() {
        val blank = complete(schemaF(xReviewRole = Role.WORK), manifest(), xReview(role = "work", body = "   "))
        assertEquals(emptyList(), againstImplementer(blank))

        val filled = complete(schemaF(xReviewRole = Role.WORK), manifest(), xReview(role = "work"))
        assertEquals(sameActorOnManifest, filled)
    }

    private fun againstSeat(
        vs: List<IndependenceViolation>,
        seat: String
    ) = vs.filter { it.conflictingSeat == seat }

    @Test
    fun `S18 an undeclared independent_of seat on a seat-aware schema owns no notes - a note labelled with it raises nothing`() {
        val schema = schemaF(independentOf = listOf("ghost"), xReviewSeat = "ghost")
        val vs = complete(schema, manifest(), xReview())
        assertEquals(emptyList(), againstSeat(vs, "ghost"))
        assertFalse(GatePredicate.blocksAdvance(vs, reject), "REJECT must not block on an undeclared seat's label")
    }

    @Test
    fun `S18b control - with no seats declared the same ghost label stands as ownership and is same_actor`() {
        val schema = schemaF(seatList = emptyList(), independentOf = listOf("ghost"), xReviewSeat = "ghost")
        val vs = complete(schema, manifest(), xReview())
        val against = againstSeat(vs, "ghost")
        assertEquals(1, against.size, vs.toString())
        assertEquals(MANIFEST, against.single().key)
        assertEquals(IndependenceConstraint.SAME_ACTOR, against.single().constraint)
        assertTrue(GatePredicate.blocksAdvance(vs, reject))
    }
}
