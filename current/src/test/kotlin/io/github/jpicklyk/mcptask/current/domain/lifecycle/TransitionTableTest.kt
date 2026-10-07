package io.github.jpicklyk.mcptask.current.domain.lifecycle

import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionTable.Cell
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionTable.Resolution
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.Role.BLOCKED
import io.github.jpicklyk.mcptask.current.domain.model.Role.QUEUE
import io.github.jpicklyk.mcptask.current.domain.model.Role.REVIEW
import io.github.jpicklyk.mcptask.current.domain.model.Role.TERMINAL
import io.github.jpicklyk.mcptask.current.domain.model.Role.WORK
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Oracle: task-scope Matrix (the table IS the spec) and plan v4-phase1-core 3.3. The expectation is hand-written data
 * below; production code is never asked to compute an expected value.
 */
class TransitionTableTest {
    private val start = Trigger.User.START
    private val complete = Trigger.User.COMPLETE
    private val block = Trigger.User.BLOCK
    private val hold = Trigger.User.HOLD
    private val resume = Trigger.User.RESUME
    private val cancel = Trigger.User.CANCEL
    private val reopen = Trigger.User.REOPEN

    private val cascadeComplete = Trigger.Cascade.Complete(false)
    private val cascadeCompleteCancel = Trigger.Cascade.Complete(true)
    private val cascadeStart = Trigger.Cascade.Start
    private val cascadeReopen = Trigger.Cascade.Reopen

    private val allTriggers: List<Trigger> =
        listOf(start, complete, block, hold, resume, cancel, reopen, cascadeComplete, cascadeCompleteCancel, cascadeStart, cascadeReopen)

    /**
     * User matrix, hand-written. Value = target role; null = Invalid. B/resume is handled separately (target is the previous role).
     * W/start depends on hasReview and is handled in [userTarget].
     */
    private val userRows: Map<Role, Map<Trigger.User, Role?>> =
        mapOf(
            QUEUE to
                mapOf(
                    start to WORK,
                    complete to TERMINAL,
                    block to BLOCKED,
                    hold to BLOCKED,
                    resume to null,
                    cancel to TERMINAL,
                    reopen to null
                ),
            WORK to
                mapOf(
                    start to null,
                    complete to TERMINAL,
                    block to BLOCKED,
                    hold to BLOCKED,
                    resume to null,
                    cancel to TERMINAL,
                    reopen to null
                ),
            REVIEW to
                mapOf(
                    start to TERMINAL,
                    complete to TERMINAL,
                    block to BLOCKED,
                    hold to BLOCKED,
                    resume to null,
                    cancel to TERMINAL,
                    reopen to null
                ),
            BLOCKED to
                mapOf(start to null, complete to null, block to null, hold to null, resume to null, cancel to TERMINAL, reopen to null),
            TERMINAL to mapOf(start to null, complete to null, block to null, hold to null, resume to null, cancel to null, reopen to QUEUE)
        )

    private fun userTarget(
        role: Role,
        t: Trigger.User,
        hasReview: Boolean
    ): Role? = if (role == WORK && t == start) (if (hasReview) REVIEW else TERMINAL) else userRows.getValue(role).getValue(t)

    /** Cascade table (AUTO only). Value = target role; absent = NotApplicable. */
    private fun cascadeTarget(
        role: Role,
        t: Trigger.Cascade
    ): Role? =
        when (t) {
            is Trigger.Cascade.Complete -> if (role == TERMINAL) null else TERMINAL
            Trigger.Cascade.Start -> if (role == QUEUE) WORK else null
            Trigger.Cascade.Reopen -> if (role == TERMINAL) WORK else null
        }

    private val declared = listOf(start, complete, block, hold, resume, cancel, reopen)

    /** Expected allowed user triggers, in declaration order, per the matrix. */
    private fun expectedAllowed(
        role: Role,
        hasReview: Boolean,
        previousRole: Role?
    ): List<Trigger.User> =
        declared.filter { t ->
            when {
                role == BLOCKED && t == resume -> previousRole in setOf(QUEUE, WORK, REVIEW)
                else -> userTarget(role, t, hasReview) != null
            }
        }

    // S1: 5 roles x 11 triggers x 2 hasReview x 3 lifecycles = 330 cells.
    @Test
    fun `S1 resolve matches the hand-written matrix for all 330 cells`() {
        var cells = 0
        for (lifecycle in LifecycleMode.entries) {
            for (hasReview in listOf(false, true)) {
                val facts = SchemaFacts(hasReview, lifecycle)
                for (role in Role.entries) {
                    for (trigger in allTriggers) {
                        cells++
                        val label = "role=$role trigger=${trigger.wire}/$trigger review=$hasReview lifecycle=$lifecycle"
                        val expected: Resolution =
                            when (trigger) {
                                is Trigger.User ->
                                    when {
                                        role == BLOCKED && trigger == resume -> Resolution.To(WORK)
                                        else -> {
                                            val target = userTarget(role, trigger, hasReview)
                                            if (target == null) {
                                                Resolution.Invalid(expectedAllowed(role, hasReview, WORK))
                                            } else {
                                                Resolution.To(target)
                                            }
                                        }
                                    }
                                is Trigger.Cascade -> {
                                    val target = if (lifecycle == LifecycleMode.AUTO) cascadeTarget(role, trigger) else null
                                    if (target == null) Resolution.NotApplicable else Resolution.To(target)
                                }
                                is Trigger.Structural -> error("not in matrix")
                            }
                        assertEquals(expected, TransitionTable.resolve(role, trigger, facts, WORK), label)
                    }
                }
            }
        }
        assertEquals(330, cells)
    }

    @Test
    fun `S3 cell gives To for ordinary cells and ToPrevious for blocked resume`() {
        for (lifecycle in LifecycleMode.entries) {
            for (hasReview in listOf(false, true)) {
                val facts = SchemaFacts(hasReview, lifecycle)
                for (role in Role.entries) {
                    for (t in declared) {
                        val expected: Cell =
                            when {
                                role == BLOCKED && t == resume -> Cell.ToPrevious
                                else -> userTarget(role, t, hasReview)?.let { Cell.To(it) } ?: Cell.Invalid
                            }
                        assertEquals(expected, TransitionTable.cell(role, t, facts), "role=$role t=$t review=$hasReview lc=$lifecycle")
                    }
                }
            }
        }
    }

    @Test
    fun `S3 cell for cascades follows the AUTO-only table`() {
        for (lifecycle in LifecycleMode.entries) {
            val facts = SchemaFacts(false, lifecycle)
            for (role in Role.entries) {
                for (t in listOf(cascadeComplete, cascadeCompleteCancel, cascadeStart, cascadeReopen)) {
                    val target = if (lifecycle == LifecycleMode.AUTO) cascadeTarget(role, t) else null
                    val expected: Cell = target?.let { Cell.To(it) } ?: Cell.NotApplicable
                    assertEquals(expected, TransitionTable.cell(role, t, facts), "role=$role t=$t lc=$lifecycle")
                }
            }
        }
    }

    @Test
    fun `S3 work start is review with a review phase and terminal without`() {
        assertEquals(Cell.To(REVIEW), TransitionTable.cell(WORK, start, SchemaFacts(true, LifecycleMode.AUTO)))
        assertEquals(Cell.To(TERMINAL), TransitionTable.cell(WORK, start, SchemaFacts(false, LifecycleMode.AUTO)))
    }

    // S1b: B + resume depends on previousRole.
    @Test
    fun `S1b blocked resume resolves to previous role only for queue work review`() {
        val facts = SchemaFacts(false, LifecycleMode.AUTO)
        for (prev in listOf(QUEUE, WORK, REVIEW)) {
            assertEquals(Resolution.To(prev), TransitionTable.resolve(BLOCKED, resume, facts, prev), "prev=$prev")
        }
        for (prev in listOf(null, BLOCKED, TERMINAL)) {
            assertEquals(Resolution.Invalid(listOf(cancel)), TransitionTable.resolve(BLOCKED, resume, facts, prev), "prev=$prev")
        }
    }

    // S2: Invalid.allowed equals allowedUserTriggers, in declaration order.
    @Test
    fun `S2 allowedUserTriggers lists the matrix in declaration order`() {
        assertEquals(
            listOf(start, complete, block, hold, cancel),
            TransitionTable.allowedUserTriggers(QUEUE, SchemaFacts(false, LifecycleMode.AUTO), null)
        )
        assertEquals(
            listOf(start, complete, block, hold, cancel),
            TransitionTable.allowedUserTriggers(WORK, SchemaFacts(true, LifecycleMode.AUTO), null)
        )
        assertEquals(
            listOf(start, complete, block, hold, cancel),
            TransitionTable.allowedUserTriggers(REVIEW, SchemaFacts(false, LifecycleMode.MANUAL), null)
        )
        assertEquals(listOf(resume, cancel), TransitionTable.allowedUserTriggers(BLOCKED, SchemaFacts(false, LifecycleMode.AUTO), QUEUE))
        assertEquals(listOf(cancel), TransitionTable.allowedUserTriggers(BLOCKED, SchemaFacts(false, LifecycleMode.AUTO), null))
        assertEquals(listOf(reopen), TransitionTable.allowedUserTriggers(TERMINAL, SchemaFacts(false, LifecycleMode.PERMANENT), null))
    }

    @Test
    fun `S2 allowedUserTriggers equals the data-derived expectation for every role review and previous role`() {
        for (hasReview in listOf(false, true)) {
            for (role in Role.entries) {
                for (prev in listOf<Role?>(null) + Role.entries) {
                    assertEquals(
                        expectedAllowed(role, hasReview, prev),
                        TransitionTable.allowedUserTriggers(role, SchemaFacts(hasReview, LifecycleMode.AUTO), prev),
                        "role=$role review=$hasReview prev=$prev"
                    )
                }
            }
        }
    }

    @Test
    fun `S2 an Invalid resolution carries exactly allowedUserTriggers`() {
        val facts = SchemaFacts(false, LifecycleMode.AUTO)
        assertEquals(Resolution.Invalid(listOf(start, complete, block, hold, cancel)), TransitionTable.resolve(QUEUE, resume, facts, null))
        assertEquals(Resolution.Invalid(listOf(reopen)), TransitionTable.resolve(TERMINAL, complete, facts, null))
    }

    // S4
    @Test
    fun `S4 parse is case insensitive for all seven user triggers`() {
        val names = listOf("start", "complete", "block", "hold", "resume", "cancel", "reopen")
        for ((i, n) in names.withIndex()) {
            assertEquals(declared[i], Trigger.User.parse(n))
            assertEquals(declared[i], Trigger.User.parse(n.uppercase()))
            assertEquals(declared[i], Trigger.User.parse(n.replaceFirstChar { it.uppercase() }))
        }
    }

    @Test
    fun `S4 parse returns null for cascade empty and unknown`() {
        assertNull(Trigger.User.parse("cascade"))
        assertNull(Trigger.User.parse(""))
        assertNull(Trigger.User.parse("foo"))
        assertNull(Trigger.User.parse(" start"))
    }

    @Test
    fun `S4 user triggers declare in the documented order with lowercase wires`() {
        assertEquals(declared, Trigger.User.entries)
        assertEquals(listOf("start", "complete", "block", "hold", "resume", "cancel", "reopen"), Trigger.User.entries.map { it.wire })
    }

    @Test
    fun `S4 cascade triggers share the cascade wire`() {
        assertEquals("cascade", Trigger.Cascade.WIRE)
        for (t in listOf(cascadeComplete, cascadeCompleteCancel, cascadeStart, cascadeReopen)) {
            assertEquals("cascade", t.wire)
        }
    }

    @Test
    fun `S4 Cascade Complete defaults cancelOrigin to false`() {
        assertEquals(Trigger.Cascade.Complete(false), Trigger.Cascade.Complete())
    }

    // Structural triggers are not table-driven.
    @Test
    fun `structural triggers are rejected by cell and resolve`() {
        val facts = SchemaFacts(false, LifecycleMode.AUTO)
        val structurals = listOf(Trigger.Structural.Create(null), Trigger.Structural.Reparent(null), Trigger.Structural.Delete)
        for (t in structurals) {
            assertFailsWith<IllegalArgumentException>("cell $t") { TransitionTable.cell(QUEUE, t, facts) }
            assertFailsWith<IllegalArgumentException>("resolve $t") { TransitionTable.resolve(QUEUE, t, facts, null) }
        }
        val parent = ParentFacts(UUID.fromString("00000000-0000-0000-0000-0000000000f1"), TERMINAL, LifecycleMode.AUTO)
        assertFailsWith<IllegalArgumentException> { TransitionTable.cell(QUEUE, Trigger.Structural.Create(parent), facts) }
    }

    // Probe: replay.
    @Test
    fun `replay resolve twice is equal`() {
        val facts = SchemaFacts(true, LifecycleMode.AUTO)
        assertEquals(TransitionTable.resolve(BLOCKED, resume, facts, REVIEW), TransitionTable.resolve(BLOCKED, resume, facts, REVIEW))
    }

    @Test
    fun `SCHEMA_FREE is a usable SchemaFacts`() {
        // schema-free items still resolve the user matrix; WORK start with SCHEMA_FREE is either REVIEW or TERMINAL per its hasReviewPhase.
        val r = TransitionTable.resolve(QUEUE, start, SchemaFacts.SCHEMA_FREE, null)
        assertEquals(Resolution.To(WORK), r)
    }
}
