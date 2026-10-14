package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceConstraint
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceMode
import io.github.jpicklyk.mcptask.current.domain.model.IndependencePolicy
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceViolation
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 95a22e6e, R22) for the seat half of the independence gate: two notes are the same
 * actor only when BOTH the actor identity and the seat of the note are equal, so one principal acting under two seat assignments
 * is two parties. Scenario S7 of the frozen test-plan, with the probes of section 6 that apply to a pair compare.
 *
 * Oracle: task-scope "Notes" (`GatePredicate` independence compares `(actor_id, seat)`), the R21 ruling 8 two-column model of the
 * orchestrator-decisions note (D11 is SUPERSEDED: `notes.seat` is ATTRIBUTION, the seat of the latest writer, NULL without a token;
 * the compared pair is the attribution pair `(actor, seat)` and never the `owner_id`/`owner_seat` pair; "a token-less note (seat
 * NULL) and a seat note of the same principal are distinct pairs; strict enforcement of that gap is R23"), and the pre-existing semantics of `IndependencePredicateTest` (same actor id and
 * no seats = `same_actor`; an absent actor on either side = `missing_actor`, fail closed; `violationsForComplete` returns an
 * empty list when nothing conflicts and only REJECT blocks). The expected violations are written by hand from those clauses.
 *
 * Negative-assertion fixture conditions: every "no same_actor" assertion is paired with a control that differs in exactly the
 * seat (or the actor) and DOES produce `same_actor`, so the assertion fails if the compare ignores the seat.
 *
 * Domain invariants satisfied by construction: notes carry their own key and item id; the schema declares the two keys with
 * their seats and `independentOf`, exactly the shape of the existing predicate tests.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class IndependenceSeatPairTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val itemId: UUID = UUID.randomUUID()

    private companion object {
        const val S_KEY = "implementation-notes"
        const val N_KEY = "test-manifest"
    }

    private val schema =
        WorkItemSchema(
            type = "seat-pair-test",
            notes =
                listOf(
                    NoteSchemaEntry(key = S_KEY, role = Role.WORK, required = true, seat = "implementer"),
                    NoteSchemaEntry(
                        key = N_KEY,
                        role = Role.WORK,
                        required = true,
                        seat = "test-author",
                        independentOf = listOf("implementer")
                    )
                )
        )

    private val warn = IndependencePolicy(IndependenceMode.WARN)
    private val reject = IndependencePolicy(IndependenceMode.REJECT)

    private fun note(
        key: String,
        actor: String?,
        seat: String?,
        ownerId: String? = null,
        ownerSeat: String? = null
    ): Note =
        Note(
            itemId = itemId,
            key = key,
            role = "work",
            body = "filled",
            actorClaim = actor?.let { ActorClaim(id = it, kind = ActorKind.SUBAGENT) },
            seat = seat,
            ownerId = ownerId,
            ownerSeat = ownerSeat
        )

    private fun violations(
        s: Note,
        n: Note,
        policy: IndependencePolicy = warn
    ): List<IndependenceViolation>? = GatePredicate.violationsForComplete(schema, listOf(s, n), policy)

    private val sameActorOnN =
        listOf(
            IndependenceViolation(
                key = N_KEY,
                seat = "test-author",
                constraint = IndependenceConstraint.SAME_ACTOR,
                conflictingSeat = "implementer"
            )
        )

    // ------------------------------------------------------------------ S7: the pair compare

    @Test
    fun `S7 one actor under two different seats is two parties - no same_actor`() {
        assertEquals(
            emptyList(),
            violations(note(S_KEY, "local", "implementer"), note(N_KEY, "local", "test-author")),
            "(local, implementer) vs (local, test-author)"
        )
        // control: the same two notes written under ONE seat are the same party, so the compare above is about the seat
        assertEquals(
            sameActorOnN,
            violations(note(S_KEY, "local", "implementer"), note(N_KEY, "local", "implementer")),
            "(local, implementer) vs (local, implementer)"
        )
    }

    @Test
    fun `S7 an equal actor and equal seat is same_actor in WARN and REJECT, and only REJECT blocks`() {
        val s = note(S_KEY, "local", "implementer")
        val n = note(N_KEY, "local", "implementer")

        val warnViolations = violations(s, n, warn)
        assertEquals(sameActorOnN, warnViolations)
        assertFalse(GatePredicate.blocksAdvance(warnViolations, warn), "WARN never blocks")

        val rejectViolations = violations(s, n, reject)
        assertEquals(sameActorOnN, rejectViolations)
        assertTrue(GatePredicate.blocksAdvance(rejectViolations, reject), "REJECT blocks the same pair")
    }

    @Test
    fun `S7 REJECT does not block one actor under two seats`() {
        val rejectViolations = violations(note(S_KEY, "local", "implementer"), note(N_KEY, "local", "test-author"), reject)
        assertEquals(emptyList(), rejectViolations)
        assertFalse(GatePredicate.blocksAdvance(rejectViolations, reject))
    }

    @Test
    fun `S7 the same seat held by two different actors is two parties`() {
        assertEquals(emptyList(), violations(note(S_KEY, "agent-a", "implementer"), note(N_KEY, "agent-b", "implementer")))
        // control: same seat, same actor is the conflict
        assertEquals(sameActorOnN, violations(note(S_KEY, "agent-a", "implementer"), note(N_KEY, "agent-a", "implementer")))
    }

    // ------------------------------------------------------------------ W5: the compare is the attribution pair, never the owner pair

    @Test
    fun `W5 the same owner pair on both notes does not make two different writers one party`() {
        // Both notes are owned by (shared-owner, A); their latest writers are (local, implementer) and (local, test-author).
        val s = note(S_KEY, "local", "implementer", ownerId = "shared-owner", ownerSeat = "A")
        val n = note(N_KEY, "local", "test-author", ownerId = "shared-owner", ownerSeat = "A")
        assertEquals(emptyList(), violations(s, n), "different attribution pairs pass even when the owner pairs are equal")
        assertEquals(emptyList(), violations(s, n, reject), "and REJECT does not block them")
        // control: the same attribution pair with DIFFERENT owner pairs is still the same party, so the compare reads (actor, seat)
        assertEquals(
            sameActorOnN,
            violations(
                note(S_KEY, "local", "implementer", ownerId = "owner-one", ownerSeat = "A"),
                note(N_KEY, "local", "implementer", ownerId = "owner-two", ownerSeat = "B")
            ),
            "equal (actor, seat) with different owner pairs"
        )
    }

    @Test
    fun `W5 probe an owner seat is never the seat compared`() {
        assertEquals(
            sameActorOnN,
            violations(
                note(S_KEY, "local", "implementer"),
                note(N_KEY, "local", "implementer", ownerId = "someone", ownerSeat = "test-author")
            ),
            "owner_seat test-author is not the seat compared: the attribution seat implementer is"
        )
        assertEquals(
            emptyList(),
            violations(
                note(S_KEY, "local", "implementer", ownerId = "local", ownerSeat = "test-author"),
                note(N_KEY, "local", "test-author", ownerId = "local", ownerSeat = "test-author")
            ),
            "the first note's owner seat equals the second note's attribution seat; the attribution seats still differ"
        )
    }

    // ------------------------------------------------------------------ S7: token-less notes keep the pre-seat behaviour

    @Test
    fun `S7 two token-less notes of one actor are still the same party - the pair is (actor, no seat) on both sides`() {
        assertEquals(sameActorOnN, violations(note(S_KEY, "local", null), note(N_KEY, "local", null)))
        // control: a different actor on the same token-less shape is not a conflict
        assertEquals(emptyList(), violations(note(S_KEY, "agent-a", null), note(N_KEY, "agent-b", null)))
    }

    @Test
    fun `S7 probe a token-less note and a seat note of one actor are distinct pairs both ways (the gap R23 closes)`() {
        assertEquals(emptyList(), violations(note(S_KEY, "local", null), note(N_KEY, "local", "test-author")), "S token-less, N seated")
        assertEquals(emptyList(), violations(note(S_KEY, "local", "implementer"), note(N_KEY, "local", null)), "S seated, N token-less")
        // controls: the equal shapes of each side do conflict
        assertEquals(sameActorOnN, violations(note(S_KEY, "local", "test-author"), note(N_KEY, "local", "test-author")))
        assertEquals(sameActorOnN, violations(note(S_KEY, "local", null), note(N_KEY, "local", null)))
    }

    // ------------------------------------------------------------------ S7 probes: the seat compare is exact

    @Test
    fun `S7 probe the seat compare is exact - case, padding and a prefix are different seats`() {
        for (spelling in listOf("Implementer", "IMPLEMENTER", "implementer ", " implementer", "implement", "implementers")) {
            assertEquals(
                emptyList(),
                violations(note(S_KEY, "local", "implementer"), note(N_KEY, "local", spelling)),
                "seat spelled [$spelling]"
            )
        }
        assertEquals(sameActorOnN, violations(note(S_KEY, "local", "implementer"), note(N_KEY, "local", "implementer")), "control: exact")
    }

    @Test
    fun `S7 probe an empty seat is its own value, not an absent one`() {
        // empty vs absent vs a name are three distinct states of the seat
        assertEquals(sameActorOnN, violations(note(S_KEY, "local", ""), note(N_KEY, "local", "")), "empty equals empty")
        assertEquals(emptyList(), violations(note(S_KEY, "local", ""), note(N_KEY, "local", null)), "empty vs absent")
        assertEquals(emptyList(), violations(note(S_KEY, "local", ""), note(N_KEY, "local", "implementer")), "empty vs a name")
    }

    @Test
    fun `S7 probe the actor compare stays exact next to an equal seat`() {
        for (spelling in listOf("Local", "LOCAL", "local ", " local")) {
            assertEquals(
                emptyList(),
                violations(note(S_KEY, "local", "implementer"), note(N_KEY, spelling, "implementer")),
                "actor spelled [$spelling]"
            )
        }
        assertEquals(sameActorOnN, violations(note(S_KEY, "local", "implementer"), note(N_KEY, "local", "implementer")), "control: exact")
    }

    // ------------------------------------------------------------------ S7: a seat never rescues a missing actor

    @Test
    fun `S7 an actor-less note is missing_actor whatever its seat - fail closed`() {
        val expected =
            listOf(
                IndependenceViolation(
                    key = N_KEY,
                    seat = "test-author",
                    constraint = IndependenceConstraint.MISSING_ACTOR,
                    conflictingSeat = null
                )
            )
        assertEquals(expected, violations(note(S_KEY, "s-agent", "implementer"), note(N_KEY, null, "test-author")), "N actor-less, seated")
        assertEquals(expected, violations(note(S_KEY, "s-agent", "implementer"), note(N_KEY, null, null)), "N actor-less, token-less")
        // control: the same N with an actor is not missing
        assertEquals(emptyList(), violations(note(S_KEY, "s-agent", "implementer"), note(N_KEY, "n-agent", "test-author")))
    }

    // ------------------------------------------------------------------ the seat is stored with the note

    @Test
    fun `the note seat survives a write and a read through the real note store, and a note without one reads null`() =
        runBlocking {
            val provider = db.repositoryProvider()
            val item = provider.workItemRepository().create(WorkItem(title = "seat round trip item", type = "seat-pair-test"))
            assertNotNull(item)
            val seated = note("seated-note", "local", "implementer", ownerId = "local", ownerSeat = "planner").copy(itemId = item.id)
            val bare = note("bare-note", "local", null).copy(itemId = item.id)
            assertNotNull(provider.noteRepository().upsert(seated))
            assertNotNull(provider.noteRepository().upsert(bare))

            val stored = provider.noteRepository().findByItemId(item.id).associateBy { it.key }
            assertEquals("implementer", stored.getValue("seated-note").seat, "the attribution seat is stored")
            assertEquals("planner", stored.getValue("seated-note").ownerSeat, "the owner seat is stored apart from the attribution seat")
            assertNull(stored.getValue("bare-note").ownerSeat, "a note without an owner seat reads null")
            assertNull(stored.getValue("bare-note").seat, "a token-less note has no seat")

            // the stored pair feeds the predicate: read-back notes of one actor under two seats are two parties
            val readBackS = stored.getValue("seated-note").copy(key = S_KEY)
            val readBackN = stored.getValue("seated-note").copy(key = N_KEY, seat = "test-author")
            assertEquals(emptyList(), violations(readBackS, readBackN))
            assertEquals(sameActorOnN, violations(readBackS, readBackN.copy(seat = "implementer")))
        }
}
