package io.github.jpicklyk.mcptask.current.domain.lifecycle

import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionTable.Cell
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionTable.InvalidReason
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionTable.Resolution
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f9fab3df) for the lifecycle-free ("NONE", role == null) row of the transition table.
 *
 * Oracles (frozen test-plan): O1 plan v4-phase2-core 3.12 (NONE: every user trigger is Invalid with the lifecycle-free reason, every
 * cascade is NotApplicable), O3 the Trigger.Structural KDoc (not part of the table; cell() throws IllegalArgumentException for them),
 * O5 task-scope Build 2 (Resolution.Invalid(allowed, reason = NOT_IN_TABLE) default so existing constructions keep their equality).
 * Every expectation below is hand-written data; production code is never asked to compute an expected value.
 */
class TransitionTableNoneTest {
    private val userTriggers: List<Trigger.User> =
        listOf(
            Trigger.User.START,
            Trigger.User.COMPLETE,
            Trigger.User.BLOCK,
            Trigger.User.HOLD,
            Trigger.User.RESUME,
            Trigger.User.CANCEL,
            Trigger.User.REOPEN
        )

    private val cascades: List<Trigger.Cascade> =
        listOf(Trigger.Cascade.Complete(false), Trigger.Cascade.Complete(true), Trigger.Cascade.Start, Trigger.Cascade.Reopen)

    private val previousRoles: List<Role?> = listOf(null) + Role.entries

    private val allFacts: List<SchemaFacts> =
        LifecycleMode.entries.flatMap { lc -> listOf(false, true).map { review -> SchemaFacts(review, lc) } }

    private val someId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")

    private val structurals: List<Trigger.Structural> =
        listOf(Trigger.Structural.Create(null), Trigger.Structural.Reparent(null), Trigger.Structural.Delete)

    // S1: 7 user triggers x 2 hasReviewPhase x 3 lifecycles = 42 cells, all Invalid.
    @Test
    fun `S1 cell for a null role is Invalid for every user trigger under every schema fact`() {
        var cells = 0
        for (facts in allFacts) {
            for (t in userTriggers) {
                cells++
                assertEquals(Cell.Invalid, TransitionTable.cell(null, t, facts), "trigger=${t.wire} facts=$facts")
            }
        }
        assertEquals(42, cells)
    }

    // S2: Invalid with an empty allowed list and the LIFECYCLE_FREE reason, whatever the previous role.
    @Test
    fun `S2 resolve for a null role is Invalid with no allowed triggers and the lifecycle-free reason`() {
        val expected = Resolution.Invalid(emptyList(), InvalidReason.LIFECYCLE_FREE)
        var resolutions = 0
        for (facts in allFacts) {
            for (t in userTriggers) {
                for (prev in previousRoles) {
                    resolutions++
                    assertEquals(expected, TransitionTable.resolve(null, t, facts, prev), "trigger=${t.wire} prev=$prev facts=$facts")
                }
            }
        }
        assertEquals(252, resolutions)
    }

    @Test
    fun `S2 resume with a work previous role does not resume a null role`() {
        val facts = SchemaFacts(false, LifecycleMode.AUTO)
        assertEquals(
            Resolution.Invalid(emptyList(), InvalidReason.LIFECYCLE_FREE),
            TransitionTable.resolve(null, Trigger.User.RESUME, facts, Role.WORK)
        )
        assertEquals(Cell.Invalid, TransitionTable.cell(null, Trigger.User.RESUME, facts))
    }

    @Test
    fun `S2 the lifecycle-free reason is distinct from the not-in-table reason`() {
        val free = Resolution.Invalid(emptyList(), InvalidReason.LIFECYCLE_FREE)
        val notInTable = Resolution.Invalid(emptyList(), InvalidReason.NOT_IN_TABLE)
        assertNotEquals(free, notInTable)
    }

    // S3: every cascade is NotApplicable for a null role, regardless of facts.lifecycle (including AUTO).
    @Test
    fun `S3 cell and resolve for a null role are NotApplicable for every cascade under every lifecycle`() {
        for (facts in allFacts) {
            for (c in cascades) {
                assertEquals(Cell.NotApplicable, TransitionTable.cell(null, c, facts), "cascade=$c facts=$facts")
                for (prev in previousRoles) {
                    assertEquals(
                        Resolution.NotApplicable,
                        TransitionTable.resolve(null, c, facts, prev),
                        "cascade=$c prev=$prev facts=$facts"
                    )
                }
            }
        }
    }

    // S4: no user trigger is allowed from a null role.
    @Test
    fun `S4 allowedUserTriggers for a null role is empty for every previous role and schema fact`() {
        for (facts in allFacts) {
            for (prev in previousRoles) {
                assertEquals(emptyList(), TransitionTable.allowedUserTriggers(null, facts, prev), "prev=$prev facts=$facts")
            }
        }
    }

    // S5: structural triggers are not part of the table, null role or not.
    @Test
    fun `S5 cell and resolve for a null role reject structural triggers with IllegalArgumentException`() {
        val facts = SchemaFacts(false, LifecycleMode.AUTO)
        for (s in structurals) {
            assertFailsWith<IllegalArgumentException>("cell $s") { TransitionTable.cell(null, s, facts) }
            assertFailsWith<IllegalArgumentException>("resolve $s") { TransitionTable.resolve(null, s, facts, null) }
        }
    }

    // S9: existing rows keep the NOT_IN_TABLE reason and the pre-existing equality.
    @Test
    fun `S9 invalid cells of non-null roles carry the not-in-table reason`() {
        val facts = SchemaFacts(false, LifecycleMode.AUTO)
        assertEquals(
            Resolution.Invalid(
                listOf(Trigger.User.START, Trigger.User.COMPLETE, Trigger.User.BLOCK, Trigger.User.HOLD, Trigger.User.CANCEL),
                InvalidReason.NOT_IN_TABLE
            ),
            TransitionTable.resolve(Role.QUEUE, Trigger.User.RESUME, facts, null)
        )
        assertEquals(
            Resolution.Invalid(listOf(Trigger.User.REOPEN), InvalidReason.NOT_IN_TABLE),
            TransitionTable.resolve(Role.TERMINAL, Trigger.User.START, facts, null)
        )
    }

    @Test
    fun `S9 Invalid constructed without a reason equals Invalid with the not-in-table reason`() {
        val list = listOf(Trigger.User.CANCEL)
        assertEquals(Resolution.Invalid(list, InvalidReason.NOT_IN_TABLE), Resolution.Invalid(list))
        assertEquals(
            Resolution.Invalid(list),
            TransitionTable.resolve(Role.BLOCKED, Trigger.User.START, SchemaFacts(false, LifecycleMode.AUTO), null)
        )
    }

    // Probe: the null row must not leak the QUEUE row (QUEUE START is To(WORK)) nor ToPrevious.
    @Test
    fun `probe a null role never resolves to a target role`() {
        for (facts in allFacts) {
            for (t in userTriggers + cascades) {
                for (prev in previousRoles) {
                    val r = TransitionTable.resolve(null, t, facts, prev)
                    assertTrue(r !is Resolution.To, "trigger=${t.wire} prev=$prev facts=$facts resolved to $r")
                }
                val c = TransitionTable.cell(null, t, facts)
                assertTrue(c !is Cell.To && c != Cell.ToPrevious, "cell trigger=${t.wire} facts=$facts gave $c")
            }
        }
    }

    // Probe: the facts types accept a null role (compile-level plumbing check).
    @Test
    fun `probe ItemFacts and ParentFacts accept a null role`() {
        assertEquals(null, ItemFacts(someId, null).role)
        assertEquals(null, ParentFacts(someId, null, LifecycleMode.AUTO).role)
    }
}
