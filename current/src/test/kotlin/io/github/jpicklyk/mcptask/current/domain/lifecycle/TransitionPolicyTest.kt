package io.github.jpicklyk.mcptask.current.domain.lifecycle

import io.github.jpicklyk.mcptask.current.domain.error.Blocker
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.GateViolation
import io.github.jpicklyk.mcptask.current.domain.error.ResourceRef
import io.github.jpicklyk.mcptask.current.domain.graph.BlockingEdge
import io.github.jpicklyk.mcptask.current.domain.graph.UnsatisfiedBlocker
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceConstraint
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceMode
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceViolation
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.Role.BLOCKED
import io.github.jpicklyk.mcptask.current.domain.model.Role.QUEUE
import io.github.jpicklyk.mcptask.current.domain.model.Role.REVIEW
import io.github.jpicklyk.mcptask.current.domain.model.Role.TERMINAL
import io.github.jpicklyk.mcptask.current.domain.model.Role.WORK
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Oracle: task-scope Gates / Matrix / KEEP-CHANGE list (queue-phase, frozen before implementation), plan
 * v4-phase1-core 3.3-3.4, envelope section 4 for error codes.
 */
class TransitionPolicyTest {
    private val policy = TransitionPolicy()

    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val parentId = UUID.fromString("00000000-0000-0000-0000-0000000000a0")
    private val blockerId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")

    private val auto = SchemaFacts(false, LifecycleMode.AUTO)
    private val autoReview = SchemaFacts(true, LifecycleMode.AUTO)

    private fun snap(
        role: Role,
        previousRole: Role? = null,
        parent: UUID? = null,
        schema: SchemaFacts = auto,
        notes: List<RequiredNote>? = null,
        filled: Set<String> = emptySet(),
        independence: IndependenceFacts? = null,
        children: ChildFacts = ChildFacts.NONE,
        blockers: List<BlockerState> = emptyList(),
        ownership: OwnershipFacts = OwnershipFacts.NONE,
        lease: LeaseFacts = LeaseFacts.NONE,
        seat: SeatFacts = SeatFacts.NONE
    ) = TransitionSnapshot(
        item = ItemFacts(id, role, previousRole, parent),
        schema = schema,
        requiredNotes = notes,
        filledKeys = filled,
        independence = independence,
        children = children,
        blockers = blockers,
        ownership = ownership,
        lease = lease,
        seat = seat
    )

    private val unmet = BlockerState(BlockingEdge(blockerId, id), WORK)

    private fun eval(
        s: TransitionSnapshot,
        t: Trigger
    ): Decision = policy.evaluate(s, t)

    private fun rejected(
        s: TransitionSnapshot,
        t: Trigger
    ): Decision.Reject = assertIs<Decision.Reject>(eval(s, t))

    private fun allowed(
        s: TransitionSnapshot,
        t: Trigger
    ): Decision.Allow = assertIs<Decision.Allow>(eval(s, t))

    // ---------------------------------------------------------------------------------------------
    // S6 gate order: OWNERSHIP -> TABLE -> WARRANT -> HOLD -> DEPENDENCY -> NOTE -> LEASE
    // ---------------------------------------------------------------------------------------------

    private val scopeNote = RequiredNote("scope", QUEUE)
    private val heldOwnership = OwnershipFacts(enforced = true, activeHolder = "holder-1", callerId = "intruder-2")
    private val contended =
        LeaseFacts(enforced = true, exclusiveKeys = listOf("gradle"), heldElsewhere = listOf("gradle"), retryAfterMs = 5000L)

    @Test
    fun `S6 start from queue failing every gate reports each gate in order as the earlier ones clear`() {
        // Step 1: everything fails -> OWNERSHIP wins.
        var s =
            snap(QUEUE, blockers = listOf(unmet), notes = listOf(scopeNote), ownership = heldOwnership, lease = contended)
        var r = rejected(s, Trigger.User.START)
        assertEquals(GateId.OWNERSHIP, r.gate)
        assertEquals(ErrorCode.NOT_CLAIM_HOLDER, r.error.code)
        assertEquals(ErrorDetail.NotClaimHolder(id), r.error.detail)

        // Step 2: clear ownership -> DEPENDENCY.
        s = s.copy(ownership = OwnershipFacts.NONE)
        r = rejected(s, Trigger.User.START)
        assertEquals(GateId.DEPENDENCY, r.gate)
        assertEquals(ErrorCode.DEPENDENCY_UNMET, r.error.code)

        // Step 3: clear blockers -> NOTE.
        s = s.copy(blockers = emptyList())
        r = rejected(s, Trigger.User.START)
        assertEquals(GateId.NOTE, r.gate)
        assertEquals(ErrorCode.GATE_BLOCKED, r.error.code)

        // Step 4: fill notes -> LEASE.
        s = s.copy(filledKeys = setOf("scope"))
        r = rejected(s, Trigger.User.START)
        assertEquals(GateId.LEASE, r.gate)
        assertEquals(ErrorCode.RESOURCE_UNAVAILABLE, r.error.code)

        // Step 5: release the lease contention -> Allow, acquiring the exclusive key.
        s = s.copy(lease = contended.copy(heldElsewhere = emptyList()))
        val allow = allowed(s, Trigger.User.START)
        assertEquals(Decision.Allow(QUEUE, WORK, null, listOf("gradle"), emptyList()), allow)
    }

    @Test
    fun `S6 ownership rejects before the table for a trigger that is invalid anyway`() {
        val r = rejected(snap(QUEUE, ownership = heldOwnership), Trigger.User.RESUME)
        assertEquals(GateId.OWNERSHIP, r.gate)
        assertEquals(ErrorCode.NOT_CLAIM_HOLDER, r.error.code)
    }

    @Test
    fun `S6 the holder resuming from queue gets invalid_transition not a claim error`() {
        val holder = OwnershipFacts(true, "holder-1", "holder-1")
        val r = rejected(snap(QUEUE, ownership = holder), Trigger.User.RESUME)
        assertEquals(GateId.TABLE, r.gate)
        assertEquals(ErrorCode.INVALID_TRANSITION, r.error.code)
    }

    @Test
    fun `S6 ownership gate passes when not enforced or no live holder and fails closed on missing caller`() {
        assertTrue(eval(snap(QUEUE, ownership = OwnershipFacts(false, "holder-1", "intruder-2")), Trigger.User.START) is Decision.Allow)
        assertTrue(eval(snap(QUEUE, ownership = OwnershipFacts(true, null, "anyone")), Trigger.User.START) is Decision.Allow)
        assertTrue(eval(snap(QUEUE, ownership = OwnershipFacts(true, "holder-1", "holder-1")), Trigger.User.START) is Decision.Allow)
        // empty vs absent: a null caller is not the holder.
        val r = rejected(snap(QUEUE, ownership = OwnershipFacts(true, "holder-1", null)), Trigger.User.START)
        assertEquals(ErrorCode.NOT_CLAIM_HOLDER, r.error.code)
    }

    @Test
    fun `S6 cascades and structural triggers bypass ownership`() {
        val s = snap(QUEUE, ownership = heldOwnership, children = ChildFacts(2, 2))
        assertTrue(eval(s, Trigger.Cascade.Complete(false)) is Decision.Allow)
        assertTrue(eval(snap(QUEUE, ownership = heldOwnership), Trigger.Structural.Delete) is Decision.Allow)
    }

    // ---------------------------------------------------------------------------------------------
    // S7 error payloads
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S7 table rejection carries invalid_transition detail with the allowed wires`() {
        val r = rejected(snap(QUEUE), Trigger.User.RESUME)
        assertEquals(GateId.TABLE, r.gate)
        assertEquals(ErrorCode.INVALID_TRANSITION, r.error.code)
        assertEquals(
            ErrorDetail.InvalidTransition(id, "queue", "resume", listOf("start", "complete", "block", "hold", "cancel")),
            r.error.detail
        )
        assertNotNull(r.error.fix)
    }

    @Test
    fun `S7 table rejection from terminal lists only reopen`() {
        val r = rejected(snap(TERMINAL), Trigger.User.START)
        assertEquals(ErrorDetail.InvalidTransition(id, "terminal", "start", listOf("reopen")), r.error.detail)
    }

    @Test
    fun `S7 table rejection from blocked with previous role lists resume and cancel`() {
        val r = rejected(snap(BLOCKED, previousRole = QUEUE), Trigger.User.START)
        assertEquals(ErrorDetail.InvalidTransition(id, "blocked", "start", listOf("resume", "cancel")), r.error.detail)
    }

    @Test
    fun `S7 dependency rejection carries blocker id and lowercase role and context`() {
        val r = rejected(snap(QUEUE, blockers = listOf(unmet)), Trigger.User.START)
        assertEquals(GateId.DEPENDENCY, r.gate)
        assertEquals(ErrorDetail.DependencyUnmet(id, listOf(Blocker(blockerId, "work"))), r.error.detail)
        assertEquals(RejectContext.Dependency(listOf(UnsatisfiedBlocker(blockerId, WORK, TERMINAL))), r.context)
    }

    @Test
    fun `S7 note rejection is gate_blocked with the missing notes and the item id in the fix`() {
        val r = rejected(snap(QUEUE, notes = listOf(scopeNote)), Trigger.User.START)
        assertEquals(GateId.NOTE, r.gate)
        assertEquals(ErrorCode.GATE_BLOCKED, r.error.code)
        assertEquals(RejectContext.Notes(listOf(scopeNote), null), r.context)
        val detail = assertIs<ErrorDetail.GateBlocked>(r.error.detail)
        assertEquals(listOf("scope"), detail.missing.map { it.key })
        assertTrue(assertNotNull(r.error.fix).contains(id.toString()))
    }

    @Test
    fun `S7 lease rejection carries the contended key and retry hint`() {
        val r = rejected(snap(QUEUE, lease = contended), Trigger.User.START)
        assertEquals(GateId.LEASE, r.gate)
        assertEquals(ErrorDetail.ResourceUnavailable(id, listOf(ResourceRef("gradle", "exclusive")), 5000L), r.error.detail)
        assertEquals(RejectContext.Lease(listOf("gradle"), 5000L), r.context)
    }

    // ---------------------------------------------------------------------------------------------
    // S8 KEEP / CHANGE
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 C4 resume from blocked into work is dependency gated`() {
        val r = rejected(snap(BLOCKED, previousRole = WORK, blockers = listOf(unmet)), Trigger.User.RESUME)
        assertEquals(GateId.DEPENDENCY, r.gate)
        assertEquals(ErrorCode.DEPENDENCY_UNMET, r.error.code)
        val r2 = rejected(snap(BLOCKED, previousRole = REVIEW, blockers = listOf(unmet)), Trigger.User.RESUME)
        assertEquals(GateId.DEPENDENCY, r2.gate)
    }

    @Test
    fun `S8 C4 resume into queue is not dependency gated`() {
        val a = allowed(snap(BLOCKED, previousRole = QUEUE, blockers = listOf(unmet)), Trigger.User.RESUME)
        assertEquals(BLOCKED, a.from)
        assertEquals(QUEUE, a.target)
    }

    @Test
    fun `S8 C5 cancel is exempt from the dependency gate from every role`() {
        for (role in listOf(QUEUE, WORK, REVIEW, BLOCKED)) {
            val a = allowed(snap(role, previousRole = if (role == BLOCKED) WORK else null, blockers = listOf(unmet)), Trigger.User.CANCEL)
            assertEquals(TERMINAL, a.target, "cancel from $role")
            assertEquals(role, a.from)
        }
    }

    @Test
    fun `S8 complete from queue work and review with unmet blockers is still dependency gated`() {
        for (role in listOf(QUEUE, WORK, REVIEW)) {
            val r = rejected(snap(role, blockers = listOf(unmet)), Trigger.User.COMPLETE)
            assertEquals(GateId.DEPENDENCY, r.gate, "complete from $role")
        }
    }

    @Test
    fun `S8 targets outside work review terminal are not dependency gated`() {
        assertEquals(BLOCKED, allowed(snap(QUEUE, blockers = listOf(unmet)), Trigger.User.BLOCK).target)
        assertEquals(BLOCKED, allowed(snap(WORK, blockers = listOf(unmet)), Trigger.User.HOLD).target)
        assertEquals(QUEUE, allowed(snap(TERMINAL, blockers = listOf(unmet)), Trigger.User.REOPEN).target)
    }

    @Test
    fun `S8 satisfied blocker and threshold-aware blocker pass the dependency gate`() {
        val satisfied = BlockerState(BlockingEdge(blockerId, id), TERMINAL)
        assertEquals(WORK, allowed(snap(QUEUE, blockers = listOf(satisfied)), Trigger.User.START).target)
        val atThreshold = BlockerState(BlockingEdge(blockerId, id, WORK), WORK)
        assertEquals(WORK, allowed(snap(QUEUE, blockers = listOf(atThreshold)), Trigger.User.START).target)
    }

    @Test
    fun `S8 unreadable blocker role fails closed and is reported as unknown`() {
        val unreadable = BlockerState(BlockingEdge(blockerId, id), null)
        val r = rejected(snap(QUEUE, blockers = listOf(unreadable)), Trigger.User.START)
        assertEquals(GateId.DEPENDENCY, r.gate)
        assertEquals(ErrorDetail.DependencyUnmet(id, listOf(Blocker(blockerId, StandardGates.UNKNOWN_ROLE))), r.error.detail)
        assertEquals("unknown", StandardGates.UNKNOWN_ROLE)
        assertEquals(RejectContext.Dependency(listOf(UnsatisfiedBlocker(blockerId, null, TERMINAL))), r.context)
    }

    @Test
    fun `S8 BLOCKED blocker never satisfies the gate`() {
        val blockedBlocker = BlockerState(BlockingEdge(blockerId, id, QUEUE), BLOCKED)
        assertEquals(GateId.DEPENDENCY, rejected(snap(QUEUE, blockers = listOf(blockedBlocker)), Trigger.User.START).gate)
    }

    @Test
    fun `S8 C6 start cascade is dependency gated`() {
        val r = rejected(snap(QUEUE, blockers = listOf(unmet), parent = parentId), Trigger.Cascade.Start)
        assertEquals(GateId.DEPENDENCY, r.gate)
        val ok = allowed(snap(QUEUE), Trigger.Cascade.Start)
        assertEquals(WORK, ok.target)
    }

    @Test
    fun `S8 C7 reopen cascade is dependency gated`() {
        val r = rejected(snap(TERMINAL, blockers = listOf(unmet)), Trigger.Cascade.Reopen)
        assertEquals(GateId.DEPENDENCY, r.gate)
        assertEquals(WORK, allowed(snap(TERMINAL), Trigger.Cascade.Reopen).target)
    }

    @Test
    fun `S8 C8 start cascade is not applicable under manual and permanent lifecycles`() {
        for (lc in listOf(LifecycleMode.MANUAL, LifecycleMode.PERMANENT)) {
            val d = eval(snap(QUEUE, schema = SchemaFacts(false, lc)), Trigger.Cascade.Start)
            assertEquals(Decision.NotApplicable(NotApplicableReason.LIFECYCLE), d, "lifecycle $lc")
        }
    }

    @Test
    fun `S8 cascade complete and reopen are also not applicable under manual and permanent`() {
        for (lc in listOf(LifecycleMode.MANUAL, LifecycleMode.PERMANENT)) {
            val schema = SchemaFacts(false, lc)
            assertEquals(
                Decision.NotApplicable(NotApplicableReason.LIFECYCLE),
                eval(snap(QUEUE, schema = schema, children = ChildFacts(2, 2)), Trigger.Cascade.Complete(false))
            )
            assertEquals(
                Decision.NotApplicable(NotApplicableReason.LIFECYCLE),
                eval(snap(TERMINAL, schema = schema), Trigger.Cascade.Reopen)
            )
        }
    }

    @Test
    fun `S8 C1 create always lands in queue`() {
        val a = allowed(TransitionSnapshot.forCreate(id, null), Trigger.Structural.Create(null))
        assertEquals(QUEUE, a.target)
        assertEquals(emptyList(), a.followUps)
        val parent = ParentFacts(parentId, WORK, LifecycleMode.AUTO)
        assertEquals(QUEUE, allowed(TransitionSnapshot.forCreate(id, parent), Trigger.Structural.Create(parent)).target)
    }

    @Test
    fun `S8 C2 create under a terminal auto parent is rejected with invalid_transition on the parent`() {
        val parent = ParentFacts(parentId, TERMINAL, LifecycleMode.AUTO)
        val r = rejected(TransitionSnapshot.forCreate(id, parent), Trigger.Structural.Create(parent))
        assertEquals(GateId.TABLE, r.gate)
        assertEquals(ErrorCode.INVALID_TRANSITION, r.error.code)
        assertEquals(ErrorDetail.InvalidTransition(parentId, "terminal", "create", listOf("reopen")), r.error.detail)
    }

    @Test
    fun `S8 C2 reparent under a terminal auto parent is rejected and manual or permanent parents are allowed`() {
        val parent = ParentFacts(parentId, TERMINAL, LifecycleMode.AUTO)
        val r = rejected(snap(QUEUE), Trigger.Structural.Reparent(parent))
        assertEquals(GateId.TABLE, r.gate)
        assertEquals(ErrorDetail.InvalidTransition(parentId, "terminal", "reparent", listOf("reopen")), r.error.detail)
        for (lc in listOf(LifecycleMode.MANUAL, LifecycleMode.PERMANENT)) {
            val ok = ParentFacts(parentId, TERMINAL, lc)
            assertTrue(eval(snap(QUEUE), Trigger.Structural.Reparent(ok)) is Decision.Allow, "reparent $lc")
            assertTrue(eval(TransitionSnapshot.forCreate(id, ok), Trigger.Structural.Create(ok)) is Decision.Allow, "create $lc")
        }
    }

    @Test
    fun `S8 C2 non-terminal auto parent allows create and reparent`() {
        for (role in listOf(QUEUE, WORK, REVIEW, BLOCKED)) {
            val parent = ParentFacts(parentId, role, LifecycleMode.AUTO)
            assertTrue(eval(snap(QUEUE), Trigger.Structural.Reparent(parent)) is Decision.Allow, "reparent under $role")
            val created = allowed(TransitionSnapshot.forCreate(id, parent), Trigger.Structural.Create(parent))
            assertEquals(QUEUE, created.target, "create under $role")
        }
    }

    // O1: plan 3.3 "dependency gate on every entry into TERMINAL including cascades"; only a USER cancel is exempt.
    @Test
    fun `S8 O1 complete cascade is dependency gated for both cancelOrigin values`() {
        for (cancelOrigin in listOf(false, true)) {
            val s = snap(QUEUE, blockers = listOf(unmet), children = ChildFacts(2, 2))
            val r = rejected(s, Trigger.Cascade.Complete(cancelOrigin))
            assertEquals(GateId.DEPENDENCY, r.gate, "cancelOrigin=$cancelOrigin")
            assertEquals(ErrorCode.DEPENDENCY_UNMET, r.error.code, "cancelOrigin=$cancelOrigin")
            assertEquals(ErrorDetail.DependencyUnmet(id, listOf(Blocker(blockerId, "work"))), r.error.detail)
        }
    }

    @Test
    fun `S8 O1 complete cascade with satisfied blockers passes the dependency gate for both cancelOrigin values`() {
        val satisfied = BlockerState(BlockingEdge(blockerId, id), TERMINAL)
        for (cancelOrigin in listOf(false, true)) {
            val s = snap(QUEUE, blockers = listOf(satisfied), children = ChildFacts(2, 2))
            assertEquals(TERMINAL, allowed(s, Trigger.Cascade.Complete(cancelOrigin)).target, "cancelOrigin=$cancelOrigin")
        }
    }

    // O2: plan 3.3 gate 5 "Lease: entry into WORK" applies to Cascade.Start and Cascade.Reopen.
    @Test
    fun `S11 O2 start cascade is lease gated`() {
        val r = rejected(snap(QUEUE, lease = contended), Trigger.Cascade.Start)
        assertEquals(GateId.LEASE, r.gate)
        assertEquals(ErrorCode.RESOURCE_UNAVAILABLE, r.error.code)
        assertEquals(ErrorDetail.ResourceUnavailable(id, listOf(ResourceRef("gradle", "exclusive")), 5000L), r.error.detail)
        assertEquals(RejectContext.Lease(listOf("gradle"), 5000L), r.context)
        val free = LeaseFacts(true, listOf("gradle", "db"), emptyList(), null)
        assertEquals(listOf("gradle", "db"), allowed(snap(QUEUE, lease = free), Trigger.Cascade.Start).acquireLeases)
    }

    @Test
    fun `S11 O2 reopen cascade is lease gated`() {
        val r = rejected(snap(TERMINAL, lease = contended), Trigger.Cascade.Reopen)
        assertEquals(GateId.LEASE, r.gate)
        assertEquals(ErrorCode.RESOURCE_UNAVAILABLE, r.error.code)
        assertEquals(RejectContext.Lease(listOf("gradle"), 5000L), r.context)
        val free = LeaseFacts(true, listOf("gradle"), emptyList(), null)
        assertEquals(listOf("gradle"), allowed(snap(TERMINAL, lease = free), Trigger.Cascade.Reopen).acquireLeases)
    }

    @Test
    fun `S8 C3 reparent and delete emit a terminal cascade on the old parent`() {
        val re = allowed(snap(QUEUE, parent = parentId), Trigger.Structural.Reparent(null))
        assertEquals(QUEUE, re.target)
        assertEquals(listOf<FollowUp>(FollowUp.TerminalCascade(parentId, false)), re.followUps)
        val del = allowed(snap(QUEUE, parent = parentId), Trigger.Structural.Delete)
        assertEquals(QUEUE, del.target)
        assertEquals(listOf<FollowUp>(FollowUp.TerminalCascade(parentId, false)), del.followUps)
        assertEquals(emptyList(), allowed(snap(QUEUE), Trigger.Structural.Delete).followUps)
    }

    // ---------------------------------------------------------------------------------------------
    // S9 hold, S16 warrant
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 complete cascade on a blocked auto parent with all children terminal is rejected by hold`() {
        val r = rejected(snap(BLOCKED, previousRole = QUEUE, children = ChildFacts(2, 2)), Trigger.Cascade.Complete(false))
        assertEquals(GateId.HOLD, r.gate)
        assertEquals(ErrorCode.INVALID_TRANSITION, r.error.code)
        assertEquals(ErrorDetail.InvalidTransition(id, "blocked", "cascade", listOf("resume", "cancel")), r.error.detail)
    }

    @Test
    fun `S9 the same cascade completes a non-blocked parent`() {
        for (role in listOf(QUEUE, WORK, REVIEW)) {
            val a = allowed(snap(role, children = ChildFacts(3, 3)), Trigger.Cascade.Complete(false))
            assertEquals(TERMINAL, a.target, "from $role")
        }
    }

    @Test
    fun `S16 warrant requires children and all of them terminal`() {
        assertEquals(
            Decision.NotApplicable(NotApplicableReason.NO_CHILDREN),
            eval(snap(QUEUE, children = ChildFacts.NONE), Trigger.Cascade.Complete(false))
        )
        assertEquals(
            Decision.NotApplicable(NotApplicableReason.NO_CHILDREN),
            eval(snap(QUEUE, children = ChildFacts(0, 0)), Trigger.Cascade.Complete(false))
        )
        assertEquals(
            Decision.NotApplicable(NotApplicableReason.CHILDREN_ACTIVE),
            eval(snap(QUEUE, children = ChildFacts(3, 2)), Trigger.Cascade.Complete(false))
        )
        assertEquals(WORK, allowed(snap(QUEUE), Trigger.Cascade.Start).target)
    }

    // O5: gate order WARRANT before HOLD (task-scope order: TABLE -> cascade warrant -> HOLD).
    @Test
    fun `S9 O5 warrant is evaluated before hold for a blocked auto parent with active children`() {
        assertEquals(
            Decision.NotApplicable(NotApplicableReason.CHILDREN_ACTIVE),
            eval(snap(BLOCKED, previousRole = QUEUE, children = ChildFacts(3, 2)), Trigger.Cascade.Complete(false))
        )
        assertEquals(
            Decision.NotApplicable(NotApplicableReason.NO_CHILDREN),
            eval(snap(BLOCKED, previousRole = QUEUE, children = ChildFacts.NONE), Trigger.Cascade.Complete(false))
        )
    }

    @Test
    fun `S16 complete cascade on a terminal parent is not applicable`() {
        assertTrue(eval(snap(TERMINAL, children = ChildFacts(2, 2)), Trigger.Cascade.Complete(false)) is Decision.NotApplicable)
    }

    @Test
    fun `S16 start cascade on a non-queue item is not applicable`() {
        for (role in listOf(WORK, REVIEW, BLOCKED, TERMINAL)) {
            assertTrue(eval(snap(role, previousRole = QUEUE), Trigger.Cascade.Start) is Decision.NotApplicable, "from $role")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // S10 notes
    // ---------------------------------------------------------------------------------------------

    private val queueNote = RequiredNote("q-note", QUEUE)
    private val workNote = RequiredNote("w-note", WORK)
    private val reviewNote = RequiredNote("r-note", REVIEW)
    private val allNotes = listOf(queueNote, workNote, reviewNote)

    @Test
    fun `S10 start requires only the notes of the current phase`() {
        // queue item: only q-note counts; w-note and r-note are unfilled but irrelevant.
        val r = rejected(snap(QUEUE, notes = allNotes), Trigger.User.START)
        assertEquals(RejectContext.Notes(listOf(queueNote), null), r.context)
        val a = allowed(snap(QUEUE, notes = allNotes, filled = setOf("q-note")), Trigger.User.START)
        assertEquals(WORK, a.target)
    }

    @Test
    fun `S10 start from work with review phase needs only work notes`() {
        val s = snap(WORK, notes = allNotes, filled = setOf("w-note"), schema = autoReview)
        assertEquals(REVIEW, allowed(s, Trigger.User.START).target)
        val r = rejected(snap(WORK, notes = allNotes, filled = setOf("q-note"), schema = autoReview), Trigger.User.START)
        assertEquals(RejectContext.Notes(listOf(workNote), null), r.context)
    }

    @Test
    fun `S10 complete requires the notes of all phases`() {
        val r = rejected(snap(WORK, notes = allNotes, filled = setOf("w-note")), Trigger.User.COMPLETE)
        assertEquals(GateId.NOTE, r.gate)
        assertEquals(RejectContext.Notes(listOf(queueNote, reviewNote), null), r.context)
        val onlyLast = rejected(snap(QUEUE, notes = allNotes, filled = setOf("q-note", "w-note")), Trigger.User.COMPLETE)
        assertEquals(RejectContext.Notes(listOf(reviewNote), null), onlyLast.context)
        val ok = allowed(snap(WORK, notes = allNotes, filled = setOf("q-note", "w-note", "r-note")), Trigger.User.COMPLETE)
        assertEquals(TERMINAL, ok.target)
    }

    @Test
    fun `S10 complete cascade requires all notes unless it originated from a cancel`() {
        val s = snap(QUEUE, notes = allNotes, children = ChildFacts(2, 2))
        assertEquals(GateId.NOTE, rejected(s, Trigger.Cascade.Complete(false)).gate)
        assertEquals(TERMINAL, allowed(s, Trigger.Cascade.Complete(true)).target)
    }

    @Test
    fun `S10 notes gate is skipped for cancel block hold resume and reopen and for reopen cascade`() {
        assertEquals(TERMINAL, allowed(snap(WORK, notes = allNotes), Trigger.User.CANCEL).target)
        assertEquals(BLOCKED, allowed(snap(WORK, notes = allNotes), Trigger.User.BLOCK).target)
        assertEquals(BLOCKED, allowed(snap(QUEUE, notes = allNotes), Trigger.User.HOLD).target)
        assertEquals(WORK, allowed(snap(BLOCKED, previousRole = WORK, notes = allNotes), Trigger.User.RESUME).target)
        assertEquals(QUEUE, allowed(snap(TERMINAL, notes = allNotes), Trigger.User.REOPEN).target)
        assertEquals(WORK, allowed(snap(TERMINAL, notes = allNotes), Trigger.Cascade.Reopen).target)
    }

    @Test
    fun `S10 start cascade checks the current phase notes only`() {
        val r = rejected(snap(QUEUE, notes = allNotes), Trigger.Cascade.Start)
        assertEquals(RejectContext.Notes(listOf(queueNote), null), r.context)
        assertEquals(WORK, allowed(snap(QUEUE, notes = listOf(workNote)), Trigger.Cascade.Start).target)
    }

    @Test
    fun `S10 null required notes means schema-free and skips the gate while an empty list passes`() {
        assertEquals(TERMINAL, allowed(snap(WORK, notes = null), Trigger.User.COMPLETE).target)
        assertEquals(TERMINAL, allowed(snap(WORK, notes = emptyList()), Trigger.User.COMPLETE).target)
    }

    private val violation =
        IndependenceViolation(
            key = "impl",
            seat = "test-author",
            constraint = IndependenceConstraint.SAME_ACTOR,
            conflictingSeat = "implementer"
        )

    @Test
    fun `S10 reject-mode independence block with no missing notes reports the violation not a missing note`() {
        val s =
            snap(
                QUEUE,
                notes = emptyList(),
                independence = IndependenceFacts(IndependenceMode.REJECT, listOf(violation), listOf(violation))
            )
        val r = rejected(s, Trigger.User.START)
        assertEquals(GateId.NOTE, r.gate)
        assertEquals(ErrorCode.GATE_BLOCKED, r.error.code)
        assertEquals(RejectContext.Notes(emptyList(), listOf(violation)), r.context)
        val detail = assertIs<ErrorDetail.GateBlocked>(r.error.detail)
        assertEquals(emptyList(), detail.missing)
        assertEquals(
            listOf(GateViolation("independence", "impl: same_actor conflicts with seat implementer")),
            detail.violations
        )
    }

    @Test
    fun `S10 warn-mode independence violations are carried on Allow and do not block`() {
        val s =
            snap(
                QUEUE,
                notes = emptyList(),
                independence = IndependenceFacts(IndependenceMode.WARN, listOf(violation), listOf(violation))
            )
        assertEquals(listOf(violation), allowed(s, Trigger.User.START).violations)
    }

    @Test
    fun `S10 reject-mode violations that are all waived do not block`() {
        val waived = violation.copy(waived = true)
        val s =
            snap(
                QUEUE,
                notes = emptyList(),
                independence = IndependenceFacts(IndependenceMode.REJECT, listOf(waived), listOf(waived))
            )
        assertEquals(listOf(waived), allowed(s, Trigger.User.START).violations)
    }

    @Test
    fun `S10 independence uses the current phase list for start and the all-phases list for complete`() {
        val onlyAll = IndependenceFacts(IndependenceMode.REJECT, emptyList(), listOf(violation))
        val startDecision = eval(snap(QUEUE, notes = emptyList(), independence = onlyAll), Trigger.User.START)
        assertTrue(startDecision is Decision.Allow, "start looks at currentPhase only: $startDecision")
        val completeDecision = eval(snap(QUEUE, notes = emptyList(), independence = onlyAll), Trigger.User.COMPLETE)
        assertEquals(GateId.NOTE, assertIs<Decision.Reject>(completeDecision).gate)
    }

    @Test
    fun `S10 a skipped notes gate leaves violations null on Allow`() {
        val s =
            snap(QUEUE, notes = emptyList(), independence = IndependenceFacts(IndependenceMode.WARN, listOf(violation), listOf(violation)))
        assertEquals(null, allowed(s, Trigger.User.CANCEL).violations)
    }

    // ---------------------------------------------------------------------------------------------
    // S11 lease
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 entering work with a contended exclusive key is rejected with resource_unavailable`() {
        val r = rejected(snap(QUEUE, lease = contended), Trigger.User.START)
        assertEquals(GateId.LEASE, r.gate)
        assertEquals(ErrorCode.RESOURCE_UNAVAILABLE, r.error.code)
    }

    @Test
    fun `S11 lease not enforced allows and acquires nothing`() {
        val a = allowed(snap(QUEUE, lease = contended.copy(enforced = false)), Trigger.User.START)
        assertEquals(emptyList(), a.acquireLeases)
    }

    @Test
    fun `S11 uncontended exclusive keys are acquired on entry to work and none declared acquires nothing`() {
        val free = LeaseFacts(true, listOf("gradle", "db"), emptyList(), null)
        assertEquals(listOf("gradle", "db"), allowed(snap(QUEUE, lease = free), Trigger.User.START).acquireLeases)
        assertEquals(
            emptyList(),
            allowed(snap(QUEUE, lease = LeaseFacts(true, emptyList(), emptyList(), null)), Trigger.User.START).acquireLeases
        )
    }

    @Test
    fun `S11 work to review ignores the lease`() {
        val a = allowed(snap(WORK, schema = autoReview, lease = contended), Trigger.User.START)
        assertEquals(REVIEW, a.target)
        assertEquals(emptyList(), a.acquireLeases)
    }

    @Test
    fun `S11 resume into work is lease gated and resume into queue is not`() {
        assertEquals(GateId.LEASE, rejected(snap(BLOCKED, previousRole = WORK, lease = contended), Trigger.User.RESUME).gate)
        assertEquals(QUEUE, allowed(snap(BLOCKED, previousRole = QUEUE, lease = contended), Trigger.User.RESUME).target)
    }

    // ---------------------------------------------------------------------------------------------
    // S5 follow-ups
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 complete emits a non-cancel terminal cascade and cancel emits a cancel-origin one`() {
        assertEquals(
            listOf<FollowUp>(FollowUp.TerminalCascade(parentId, false)),
            allowed(snap(WORK, parent = parentId), Trigger.User.COMPLETE).followUps
        )
        assertEquals(
            listOf<FollowUp>(FollowUp.TerminalCascade(parentId, true)),
            allowed(snap(WORK, parent = parentId), Trigger.User.CANCEL).followUps
        )
    }

    @Test
    fun `S5 cascade complete inherits cancelOrigin`() {
        val s = snap(QUEUE, parent = parentId, children = ChildFacts(1, 1))
        assertEquals(listOf<FollowUp>(FollowUp.TerminalCascade(parentId, false)), allowed(s, Trigger.Cascade.Complete(false)).followUps)
        assertEquals(listOf<FollowUp>(FollowUp.TerminalCascade(parentId, true)), allowed(s, Trigger.Cascade.Complete(true)).followUps)
    }

    @Test
    fun `S5 a user start into work emits a start cascade and a start into review or terminal does not`() {
        assertEquals(
            listOf<FollowUp>(FollowUp.StartCascade(parentId)),
            allowed(snap(QUEUE, parent = parentId), Trigger.User.START).followUps
        )
        assertEquals(
            emptyList(),
            allowed(snap(WORK, parent = parentId, schema = autoReview), Trigger.User.START).followUps
        )
    }

    @Test
    fun `S5 resume into work emits a start cascade`() {
        // task-scope: a user transition into work starts the parent.
        assertEquals(
            listOf<FollowUp>(FollowUp.StartCascade(parentId)),
            allowed(snap(BLOCKED, previousRole = WORK, parent = parentId), Trigger.User.RESUME).followUps
        )
    }

    @Test
    fun `S5 reopen emits a reopen cascade`() {
        assertEquals(
            listOf<FollowUp>(FollowUp.ReopenCascade(parentId)),
            allowed(snap(TERMINAL, parent = parentId), Trigger.User.REOPEN).followUps
        )
    }

    @Test
    fun `S5 cascades into work emit no follow-up and block emits none`() {
        assertEquals(emptyList(), allowed(snap(QUEUE, parent = parentId), Trigger.Cascade.Start).followUps)
        assertEquals(emptyList(), allowed(snap(TERMINAL, parent = parentId), Trigger.Cascade.Reopen).followUps)
        assertEquals(emptyList(), allowed(snap(QUEUE, parent = parentId), Trigger.User.BLOCK).followUps)
    }

    @Test
    fun `S5 an item without a parent has no follow-ups`() {
        assertEquals(emptyList(), allowed(snap(WORK), Trigger.User.COMPLETE).followUps)
        assertEquals(emptyList(), allowed(snap(WORK), Trigger.User.CANCEL).followUps)
        assertEquals(emptyList(), allowed(snap(QUEUE), Trigger.User.START).followUps)
        assertEquals(emptyList(), allowed(snap(TERMINAL), Trigger.User.REOPEN).followUps)
    }

    // ---------------------------------------------------------------------------------------------
    // Allow shape and replay
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `allow reports from and target and the table result for plain transitions`() {
        assertEquals(Decision.Allow(WORK, TERMINAL, null, emptyList(), emptyList()), eval(snap(WORK), Trigger.User.START))
        assertEquals(
            Decision.Allow(WORK, REVIEW, null, emptyList(), emptyList()),
            eval(snap(WORK, schema = autoReview), Trigger.User.START)
        )
    }

    @Test
    fun `replay evaluate twice gives equal decisions`() {
        val s = snap(QUEUE, blockers = listOf(unmet), notes = listOf(scopeNote))
        assertEquals(eval(s, Trigger.User.START), eval(s, Trigger.User.START))
        val ok = snap(QUEUE)
        assertEquals(eval(ok, Trigger.User.START), eval(ok, Trigger.User.START))
    }

    @Test
    fun `wire of a role is its lowercase name`() {
        assertEquals("queue", QUEUE.wire())
        assertEquals("terminal", TERMINAL.wire())
        assertEquals("blocked", BLOCKED.wire())
    }

    // ---------------------------------------------------------------------------------------------
    // R23 (item 9536cf09) SEAT gate. Oracle: frozen task-scope "What to build" 3 (gate rules, the
    // allowedSeats rule, the error shape) and decisions D3-D6; plan 4.1 R23 table. Gate order:
    // OWNERSHIP -> SEAT -> TABLE -> WARRANT -> HOLD -> DEPENDENCY -> NOTE -> LEASE.
    // ---------------------------------------------------------------------------------------------

    private val runId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val otherRunId = UUID.fromString("00000000-0000-0000-0000-0000000000c2")
    private val otherItemId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")

    private val seatSet =
        listOf(
            SeatDefinition("planner", QUEUE, enters = true),
            SeatDefinition("builder", WORK, enters = true),
            SeatDefinition("checker", WORK, transitions = listOf("complete")),
            SeatDefinition("orchestrator", WORK),
            SeatDefinition("silent", WORK, transitions = emptyList()),
            SeatDefinition("reviewer", REVIEW, enters = true)
        )

    private fun seatFacts(
        caller: SeatCaller,
        enforced: Boolean = true,
        openRun: UUID? = runId,
        seats: List<SeatDefinition> = seatSet
    ) = SeatFacts(enforced = enforced, openRunId = openRun, seats = seats, caller = caller)

    private fun memberSeat(
        seat: String,
        run: UUID = runId,
        item: UUID? = id
    ) = SeatCaller(seat = seat, runId = run, itemId = item, human = false)

    private val coordinatorCaller = SeatCaller(seat = "coordinator", runId = runId, itemId = null, human = false)
    private val humanCaller = SeatCaller(seat = null, runId = null, itemId = null, human = true)

    private fun seatRejected(
        s: TransitionSnapshot,
        t: Trigger,
        label: String
    ): Decision.Reject {
        val r = rejected(s, t)
        assertEquals(GateId.SEAT, r.gate, "$label: gate")
        assertEquals(ErrorCode.SEAT_FORBIDDEN, r.error.code, "$label: code")
        return r
    }

    private fun seatDetail(r: Decision.Reject) = assertIs<ErrorDetail.SeatForbidden>(r.error.detail)

    @Test
    fun `R23 S9 gate order puts SEAT after OWNERSHIP and before TABLE DEPENDENCY NOTE and LEASE`() {
        // Everything fails at once: ownership answers first.
        var s =
            snap(
                QUEUE,
                blockers = listOf(unmet),
                notes = listOf(scopeNote),
                ownership = heldOwnership,
                lease = contended,
                seat = seatFacts(SeatCaller.NONE)
            )
        assertEquals(GateId.OWNERSHIP, rejected(s, Trigger.User.START).gate)

        // Clear ownership: the seat refusal answers before dependency, notes and lease.
        s = s.copy(ownership = OwnershipFacts.NONE)
        assertEquals(GateId.SEAT, rejected(s, Trigger.User.START).gate)

        // The coordinator passes the seat gate, so the next gates answer in their order.
        s = s.copy(seat = seatFacts(coordinatorCaller))
        assertEquals(GateId.DEPENDENCY, rejected(s, Trigger.User.START).gate)
        s = s.copy(blockers = emptyList())
        assertEquals(GateId.NOTE, rejected(s, Trigger.User.START).gate)
        s = s.copy(filledKeys = setOf("scope"))
        assertEquals(GateId.LEASE, rejected(s, Trigger.User.START).gate)
    }

    @Test
    fun `R23 S9 a seat refusal answers before the transition table and the table answers once the seat passes`() {
        // resume from queue is invalid in the table; the token-less agent is stopped by the seat gate first.
        seatRejected(snap(QUEUE, seat = seatFacts(SeatCaller.NONE)), Trigger.User.RESUME, "token-less resume from queue")
        // start from terminal is invalid in the table; the builder token is stopped by the seat gate first.
        seatRejected(snap(TERMINAL, seat = seatFacts(memberSeat("builder"))), Trigger.User.START, "builder start from terminal")
        // control: with a passing caller the table is what refuses.
        val viaCoordinator = rejected(snap(QUEUE, seat = seatFacts(coordinatorCaller)), Trigger.User.RESUME)
        assertEquals(GateId.TABLE, viaCoordinator.gate)
        assertEquals(ErrorCode.INVALID_TRANSITION, viaCoordinator.error.code)
        assertEquals(GateId.TABLE, rejected(snap(TERMINAL, seat = seatFacts(humanCaller)), Trigger.User.START).gate)
    }

    @Test
    fun `R23 S2 a member seat starts only into the phase it enters`() {
        val builder = seatFacts(memberSeat("builder"))
        assertEquals(WORK, allowed(snap(QUEUE, seat = builder), Trigger.User.START).target)
        // the planner enters queue, not work
        seatRejected(snap(QUEUE, seat = seatFacts(memberSeat("planner"))), Trigger.User.START, "planner start into work")
        // a seat that does not enter at all
        seatRejected(snap(QUEUE, seat = seatFacts(memberSeat("orchestrator"))), Trigger.User.START, "non-entering seat")
        // start from work lands in review (review-phase schema) or terminal; the builder enters neither
        seatRejected(snap(WORK, schema = autoReview, seat = builder), Trigger.User.START, "builder start into review")
        seatRejected(snap(WORK, seat = builder), Trigger.User.START, "builder start into terminal")
        // the reviewer enters review
        val reviewer = seatFacts(memberSeat("reviewer"))
        assertEquals(REVIEW, allowed(snap(WORK, schema = autoReview, seat = reviewer), Trigger.User.START).target)
        seatRejected(snap(QUEUE, seat = reviewer), Trigger.User.START, "reviewer start into work")
    }

    @Test
    fun `R23 S2 a member seat runs the default block and hold, and complete only when it declares it`() {
        val builder = seatFacts(memberSeat("builder"))
        assertEquals(BLOCKED, allowed(snap(QUEUE, seat = builder), Trigger.User.BLOCK).target)
        assertEquals(BLOCKED, allowed(snap(WORK, seat = builder), Trigger.User.HOLD).target)
        // not declared: complete, cancel, resume, reopen
        seatRejected(snap(WORK, seat = builder), Trigger.User.COMPLETE, "builder complete")
        seatRejected(snap(WORK, seat = builder), Trigger.User.CANCEL, "builder cancel")
        seatRejected(snap(BLOCKED, previousRole = WORK, seat = builder), Trigger.User.RESUME, "builder resume")
        seatRejected(snap(TERMINAL, seat = builder), Trigger.User.REOPEN, "builder reopen")

        // the checker declares [complete] only: complete passes, the defaults no longer apply
        val checker = seatFacts(memberSeat("checker"))
        assertEquals(TERMINAL, allowed(snap(WORK, seat = checker), Trigger.User.COMPLETE).target)
        seatRejected(snap(WORK, seat = checker), Trigger.User.BLOCK, "checker block")
        seatRejected(snap(WORK, seat = checker), Trigger.User.HOLD, "checker hold")

        // a declared EMPTY list is not the same as an absent one: nothing is allowed
        val silent = seatFacts(memberSeat("silent"))
        seatRejected(snap(QUEUE, seat = silent), Trigger.User.BLOCK, "silent block")
        seatRejected(snap(WORK, seat = silent), Trigger.User.HOLD, "silent hold")
        // control: the seat with no declaration gets the defaults
        assertEquals(BLOCKED, allowed(snap(QUEUE, seat = seatFacts(memberSeat("orchestrator"))), Trigger.User.BLOCK).target)

        // control for every refusal above: the coordinator of the run does the same transitions
        val coordinator = seatFacts(coordinatorCaller)
        assertEquals(TERMINAL, allowed(snap(WORK, seat = coordinator), Trigger.User.COMPLETE).target)
        assertEquals(TERMINAL, allowed(snap(WORK, seat = coordinator), Trigger.User.CANCEL).target)
        assertEquals(WORK, allowed(snap(BLOCKED, previousRole = WORK, seat = coordinator), Trigger.User.RESUME).target)
        assertEquals(QUEUE, allowed(snap(TERMINAL, seat = coordinator), Trigger.User.REOPEN).target)
    }

    @Test
    fun `R23 S3 the coordinator of this run may run every user trigger the table allows`() {
        val c = seatFacts(coordinatorCaller)
        assertEquals(WORK, allowed(snap(QUEUE, seat = c), Trigger.User.START).target)
        assertEquals(TERMINAL, allowed(snap(WORK, seat = c), Trigger.User.COMPLETE).target)
        assertEquals(BLOCKED, allowed(snap(QUEUE, seat = c), Trigger.User.BLOCK).target)
        assertEquals(BLOCKED, allowed(snap(WORK, seat = c), Trigger.User.HOLD).target)
        assertEquals(QUEUE, allowed(snap(BLOCKED, previousRole = QUEUE, seat = c), Trigger.User.RESUME).target)
        assertEquals(TERMINAL, allowed(snap(QUEUE, seat = c), Trigger.User.CANCEL).target)
        assertEquals(QUEUE, allowed(snap(TERMINAL, seat = c), Trigger.User.REOPEN).target)
    }

    @Test
    fun `R23 S5 a human is exempt and the same snapshot refuses a token-less agent`() {
        val human = seatFacts(humanCaller)
        val cases =
            listOf(
                snap(QUEUE, seat = human) to Trigger.User.START,
                snap(WORK, seat = human) to Trigger.User.COMPLETE,
                snap(WORK, seat = human) to Trigger.User.CANCEL,
                snap(TERMINAL, seat = human) to Trigger.User.REOPEN
            )
        for ((s, t) in cases) {
            assertTrue(eval(s, t) is Decision.Allow, "human $t from ${s.item.role}")
            seatRejected(s.copy(seat = seatFacts(SeatCaller.NONE)), t, "agent $t from ${s.item.role}")
        }
    }

    @Test
    fun `R23 S7 a token bound to another run, another item, no item or an undeclared seat is refused`() {
        val cases =
            listOf(
                "token of another run" to memberSeat("builder", run = otherRunId),
                "token for another item" to memberSeat("builder", item = otherItemId),
                "member seat without an item" to memberSeat("builder", item = null),
                "seat the schema does not declare" to memberSeat("ghost"),
                "seat name with another case" to memberSeat("Builder"),
                "seat name with trailing space" to memberSeat("builder "),
                "coordinator of another run" to SeatCaller("coordinator", otherRunId, null, false)
            )
        for ((label, caller) in cases) {
            val r = seatRejected(snap(QUEUE, seat = seatFacts(caller)), Trigger.User.START, label)
            assertEquals(caller.seat, seatDetail(r).seat, "$label: the refusal names the presented seat")
        }
        // control: the exact token passes
        assertEquals(WORK, allowed(snap(QUEUE, seat = seatFacts(memberSeat("builder"))), Trigger.User.START).target)
    }

    @Test
    fun `R23 S7 and S8 the refusal carries the item, the presented seat, the action and the allowed seats`() {
        // member seat on start: allowedSeats = coordinator, then the seat entering the target phase
        val start = seatRejected(snap(QUEUE, seat = seatFacts(memberSeat("planner"))), Trigger.User.START, "planner start")
        assertEquals(ErrorDetail.SeatForbidden(id, "planner", "start", listOf("coordinator", "builder")), start.error.detail)
        assertTrue(start.error.message.contains("start") && start.error.message.contains(id.toString()), start.error.message)

        // token-less agent: the seat is absent (null), not an empty string
        val tokenless = seatRejected(snap(QUEUE, seat = seatFacts(SeatCaller.NONE)), Trigger.User.START, "token-less start")
        assertEquals(ErrorDetail.SeatForbidden(id, null, "start", listOf("coordinator", "builder")), tokenless.error.detail)

        // other triggers: the seats whose effective transitions contain the trigger, in declared order
        val complete = seatRejected(snap(WORK, seat = seatFacts(memberSeat("builder"))), Trigger.User.COMPLETE, "builder complete")
        assertEquals(ErrorDetail.SeatForbidden(id, "builder", "complete", listOf("coordinator", "checker")), complete.error.detail)
        val block = seatRejected(snap(WORK, seat = seatFacts(memberSeat("checker"))), Trigger.User.BLOCK, "checker block")
        assertEquals(
            ErrorDetail.SeatForbidden(id, "checker", "block", listOf("coordinator", "planner", "builder", "orchestrator", "reviewer")),
            block.error.detail
        )
        val cancel = seatRejected(snap(WORK, seat = seatFacts(memberSeat("builder"))), Trigger.User.CANCEL, "builder cancel")
        assertEquals(ErrorDetail.SeatForbidden(id, "builder", "cancel", listOf("coordinator")), cancel.error.detail)

        // a start into review lists the reviewer
        val review =
            seatRejected(snap(WORK, schema = autoReview, seat = seatFacts(SeatCaller.NONE)), Trigger.User.START, "start into review")
        assertEquals(ErrorDetail.SeatForbidden(id, null, "start", listOf("coordinator", "reviewer")), review.error.detail)
    }

    @Test
    fun `R23 the seat gate does not apply outside enforcement, outside an open run, without seats or to non-user triggers`() {
        val enforcedNone = seatFacts(SeatCaller.NONE)
        // control: the enforced snapshot refuses
        seatRejected(snap(QUEUE, seat = enforcedNone), Trigger.User.START, "control")
        assertTrue(eval(snap(QUEUE, seat = enforcedNone.copy(enforced = false)), Trigger.User.START) is Decision.Allow, "not enforced")
        assertTrue(eval(snap(QUEUE, seat = enforcedNone.copy(openRunId = null)), Trigger.User.START) is Decision.Allow, "no open run")
        assertTrue(eval(snap(QUEUE, seat = enforcedNone.copy(seats = emptyList())), Trigger.User.START) is Decision.Allow, "no seats")

        // cascades and structural triggers never carry a caller (decision D3)
        assertTrue(eval(snap(QUEUE, parent = parentId, seat = enforcedNone), Trigger.Cascade.Start) is Decision.Allow, "cascade start")
        assertTrue(eval(snap(TERMINAL, seat = enforcedNone), Trigger.Cascade.Reopen) is Decision.Allow, "cascade reopen")
        assertTrue(eval(snap(QUEUE, seat = enforcedNone), Trigger.Structural.Delete) is Decision.Allow, "structural delete")
    }

    @Test
    fun `R23 replaying the same refused evaluation gives the same decision`() {
        val s = snap(QUEUE, seat = seatFacts(memberSeat("planner")))
        assertEquals(eval(s, Trigger.User.START), eval(s, Trigger.User.START))
    }
}
