package io.github.jpicklyk.mcptask.current.domain.lifecycle

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Independent tests (test-author seat, item f9fab3df) for TransitionPolicy over a lifecycle-free ("NONE", role == null) item.
 *
 * Oracles (frozen test-plan): O1 plan v4-phase2-core 3.12 (every user trigger on NONE is invalid_transition, every cascade is
 * NotApplicable), O3 Trigger.Structural KDoc (structural triggers are evaluated by fixed rules and bypass the gate list) plus the
 * closed-parent rule, O5 task-scope Build 3 (fromRole wire "none", allowed [], NotApplicable reason LIFECYCLE, structural
 * Reparent/Delete of a NONE item -> Allow(null, null) with the old-parent TerminalCascade follow-up, gate order unchanged).
 * The detail shape follows the declared ErrorDetail.InvalidTransition(itemId, fromRole, trigger, allowed).
 */
class TransitionPolicyNoneTest {
    private val policy = TransitionPolicy()

    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val oldParent = UUID.fromString("00000000-0000-0000-0000-0000000000a0")
    private val newParent = UUID.fromString("00000000-0000-0000-0000-0000000000a1")

    private val userTriggers = Trigger.User.entries

    private val cascades: List<Trigger.Cascade> =
        listOf(Trigger.Cascade.Complete(false), Trigger.Cascade.Complete(true), Trigger.Cascade.Start, Trigger.Cascade.Reopen)

    private val schemas: List<SchemaFacts> =
        LifecycleMode.entries.flatMap { lc -> listOf(false, true).map { review -> SchemaFacts(review, lc) } }

    private val auto = SchemaFacts(false, LifecycleMode.AUTO)

    private fun none(
        previousRole: Role? = null,
        parent: UUID? = null,
        schema: SchemaFacts = auto,
        children: ChildFacts = ChildFacts.NONE,
        ownership: OwnershipFacts = OwnershipFacts.NONE
    ) = TransitionSnapshot(
        item = ItemFacts(id, null, previousRole, parent),
        schema = schema,
        children = children,
        ownership = ownership
    )

    // S6: every user trigger on a null role is rejected by the TABLE gate with an invalid_transition from "none".
    @Test
    fun `S6 every user trigger on a null role is rejected by the table gate with fromRole none and no allowed triggers`() {
        for (schema in schemas) {
            for (t in userTriggers) {
                val r = assertIs<Decision.Reject>(policy.evaluate(none(schema = schema), t), "trigger=${t.wire} schema=$schema")
                assertEquals(GateId.TABLE, r.gate, "trigger=${t.wire} schema=$schema")
                assertEquals(ErrorCode.INVALID_TRANSITION, r.error.code, "trigger=${t.wire} schema=$schema")
                assertEquals(ErrorDetail.InvalidTransition(id, "none", t.wire, emptyList()), r.error.detail, "trigger=${t.wire}")
            }
        }
    }

    @Test
    fun `S6 resume on a null role with a previous role is still rejected from none`() {
        for (prev in Role.entries) {
            val r = assertIs<Decision.Reject>(policy.evaluate(none(previousRole = prev), Trigger.User.RESUME), "prev=$prev")
            assertEquals(GateId.TABLE, r.gate, "prev=$prev")
            assertEquals(ErrorCode.INVALID_TRANSITION, r.error.code, "prev=$prev")
            assertEquals(ErrorDetail.InvalidTransition(id, "none", "resume", emptyList()), r.error.detail, "prev=$prev")
        }
    }

    // S7: every cascade on a null role is not applicable because of the lifecycle, including under an AUTO schema.
    @Test
    fun `S7 every cascade on a null role is NotApplicable with the lifecycle reason`() {
        for (schema in schemas) {
            for (c in cascades) {
                assertEquals(
                    Decision.NotApplicable(NotApplicableReason.LIFECYCLE),
                    policy.evaluate(none(schema = schema), c),
                    "cascade=$c schema=$schema"
                )
            }
        }
    }

    @Test
    fun `S7 cascades on a null role with children all terminal or active stay NotApplicable for the lifecycle`() {
        for (children in listOf(ChildFacts(2, 2), ChildFacts(2, 1), ChildFacts(2, 0))) {
            for (c in cascades) {
                assertEquals(
                    Decision.NotApplicable(NotApplicableReason.LIFECYCLE),
                    policy.evaluate(none(children = children), c),
                    "cascade=$c children=$children"
                )
            }
        }
    }

    // S8: structural triggers bypass the table; a null-role item keeps today's old-parent follow-up.
    @Test
    fun `S8 delete of a null role item with a parent allows null to null and cascades the old parent`() {
        val a = assertIs<Decision.Allow>(policy.evaluate(none(parent = oldParent), Trigger.Structural.Delete))
        assertEquals(null, a.from)
        assertEquals(null, a.target)
        assertEquals(listOf<FollowUp>(FollowUp.TerminalCascade(oldParent, false)), a.followUps)
        assertEquals(emptyList(), a.acquireLeases)
    }

    @Test
    fun `S8 delete of a root null role item has no follow-up`() {
        val a = assertIs<Decision.Allow>(policy.evaluate(none(), Trigger.Structural.Delete))
        assertEquals(null, a.from)
        assertEquals(null, a.target)
        assertEquals(emptyList(), a.followUps)
    }

    @Test
    fun `S8 reparent of a null role item to a non-terminal parent allows null to null and cascades the old parent`() {
        for (parentRole in listOf(Role.QUEUE, Role.WORK, Role.REVIEW, Role.BLOCKED)) {
            val target = ParentFacts(newParent, parentRole, LifecycleMode.AUTO)
            val a =
                assertIs<Decision.Allow>(
                    policy.evaluate(none(parent = oldParent), Trigger.Structural.Reparent(target)),
                    "parent=$parentRole"
                )
            assertEquals(null, a.from, "parent=$parentRole")
            assertEquals(null, a.target, "parent=$parentRole")
            assertEquals(listOf<FollowUp>(FollowUp.TerminalCascade(oldParent, false)), a.followUps, "parent=$parentRole")
        }
    }

    @Test
    fun `S8 reparent of a null role item to the root allows null to null and cascades the old parent`() {
        val a = assertIs<Decision.Allow>(policy.evaluate(none(parent = oldParent), Trigger.Structural.Reparent(null)))
        assertEquals(null, a.from)
        assertEquals(null, a.target)
        assertEquals(listOf<FollowUp>(FollowUp.TerminalCascade(oldParent, false)), a.followUps)
    }

    @Test
    fun `S8 reparent of a null role item under an AUTO terminal parent is still rejected by the table gate`() {
        val target = ParentFacts(newParent, Role.TERMINAL, LifecycleMode.AUTO)
        val r = assertIs<Decision.Reject>(policy.evaluate(none(parent = oldParent), Trigger.Structural.Reparent(target)))
        assertEquals(GateId.TABLE, r.gate)
        assertEquals(ErrorCode.INVALID_TRANSITION, r.error.code)
        assertEquals(ErrorDetail.InvalidTransition(newParent, "terminal", "reparent", listOf("reopen")), r.error.detail)
    }

    @Test
    fun `S8 reparent of a null role item under a MANUAL or PERMANENT terminal parent is allowed`() {
        for (lc in listOf(LifecycleMode.MANUAL, LifecycleMode.PERMANENT)) {
            val target = ParentFacts(newParent, Role.TERMINAL, lc)
            assertIs<Decision.Allow>(policy.evaluate(none(parent = oldParent), Trigger.Structural.Reparent(target)), "lifecycle=$lc")
        }
    }

    // S13: gate order is unchanged: ownership is evaluated before the table, so a foreign claim wins over the lifecycle-free rejection.
    @Test
    fun `S13 a null role item held by another caller is rejected by the ownership gate before the table gate`() {
        val held = OwnershipFacts(enforced = true, activeHolder = "holder-1", callerId = "intruder-2")
        for (t in userTriggers) {
            val r = assertIs<Decision.Reject>(policy.evaluate(none(ownership = held), t), "trigger=${t.wire}")
            assertEquals(GateId.OWNERSHIP, r.gate, "trigger=${t.wire}")
            assertEquals(ErrorCode.NOT_CLAIM_HOLDER, r.error.code, "trigger=${t.wire}")
            assertEquals(ErrorDetail.NotClaimHolder(id), r.error.detail, "trigger=${t.wire}")
        }
    }

    @Test
    fun `S13 ownership does not apply to cascades on a null role item`() {
        val held = OwnershipFacts(enforced = true, activeHolder = "holder-1", callerId = "intruder-2")
        assertEquals(
            Decision.NotApplicable(NotApplicableReason.LIFECYCLE),
            policy.evaluate(none(ownership = held), Trigger.Cascade.Start)
        )
    }
}
