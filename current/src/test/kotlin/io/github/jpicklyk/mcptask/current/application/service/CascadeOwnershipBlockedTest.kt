package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.FENCE_T
import io.github.jpicklyk.mcptask.current.test.FenceRig
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.actorJson
import io.github.jpicklyk.mcptask.current.test.advanceDirect
import io.github.jpicklyk.mcptask.current.test.advanceTool
import io.github.jpicklyk.mcptask.current.test.arr
import io.github.jpicklyk.mcptask.current.test.completeTreeOp
import io.github.jpicklyk.mcptask.current.test.deleteOp
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.newFenceRig
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.onlyResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.strings
import io.github.jpicklyk.mcptask.current.test.success
import io.github.jpicklyk.mcptask.current.test.testAccess
import io.github.jpicklyk.mcptask.current.test.text
import io.github.jpicklyk.mcptask.current.test.transitionOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 46a83aca) for ownership-blocked cascades: scenarios S6 and S7 of the frozen test-plan
 * (plus the fail-closed null caller of decision D6 and the start-cascade reach of decision D8).
 *
 * Oracles (frozen, planning phase; none read from the implementation): task-scope item 4 ("a parent with an active claim by
 * another holder is suppressed as an ownership-blocked cascade event: `applied` false, `blocked: "ownership"` on the wire,
 * `reason: not_claim_holder` on items_write, checked before `gate`; the parent keeps its role and claim; the primary still
 * commits; the chain stops there; a parent held by the caller, unclaimed, or lapsed cascades as today and entering TERMINAL clears
 * its claim; a null caller fences on any active claim; the rule is per write, so START cascades are fenced too"), AC2, and the
 * pre-existing cascade wire shape `{itemId, previousRole, targetRole, applied, blocked?, ...}` of api-reference.md. S6 pins the
 * whole cascade element with an exact-equality assertion (no extra members), per test-plan S6.
 *
 * S6 cells are NEW-SURFACE (the `ownershipBlocked` flag, the caller parameter); S7 cells are EXISTING-SURFACE controls (the same
 * calls without a foreign holder cascade today). Every negative assertion has a control through the same call shape.
 *
 * Fixtures: P (the held parent) and C (its only child) are in WORK so completing C cascades P to terminal; claims are taken at the
 * settable clock so the claim invariants hold by construction. The caller is a distinct principal per call
 * ([testAccess]), whose audited id is the bare id the claim is compared with.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class CascadeOwnershipBlockedTest {
    private val clock = SettableClock(FENCE_T)

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private fun rig(dir: Path): FenceRig = newFenceRig(sqlite.db, dir, clock)

    private val agentA = ActorClaim(id = "agent-a", kind = ActorKind.SUBAGENT)

    /** The element of one `complete` of [child] by principal [by] through the advance tool. */
    private suspend fun FenceRig.completeAs(
        by: String,
        child: WorkItem,
        trigger: String = "complete"
    ): JsonObject = ctx.advanceTool(transitionOp(child.id, trigger), access = testAccess(by)).onlyResult()

    private fun cascadeJson(
        parent: WorkItem,
        from: String,
        to: String,
        applied: Boolean,
        blocked: String? = null
    ): JsonObject =
        buildJsonObject {
            put("itemId", parent.id.toString())
            put("previousRole", from)
            put("targetRole", to)
            put("applied", applied)
            blocked?.let { put("blocked", it) }
        }

    /** P in WORK held by [holder] (null: unheld) and its only child C in WORK. */
    private suspend fun FenceRig.family(holder: String?): Pair<WorkItem, WorkItem> {
        val parent = make("P", role = Role.WORK)
        val held = if (holder != null) heldBy(parent, holder) else parent
        return held to make("C", held, role = Role.WORK)
    }

    // ------------------------------------------------------------------ S6

    @Test
    fun `S6 completing the last child by a non-holder reports the held parent as ownership-blocked, exactly, and leaves it untouched`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val (parent, child) = fx.family("agent-b")

            val (element, rows) = fx.rig.written { fx.completeAs("agent-a", child) }

            assertEquals(true, element.flag("ok"), "the primary transition commits: $element")
            assertEquals("terminal", element.text("newRole"))
            assertEquals(
                listOf(cascadeJson(parent, "work", "terminal", applied = false, blocked = "ownership")),
                element.arr("cascades").map { it.jsonObject },
                "the cascade entry is exactly the documented shape with blocked: ownership"
            )
            val after = fx.reload(parent)
            assertEquals(Role.WORK, after.role, "the held parent keeps its role")
            assertEquals("agent-b", after.claimedBy, "the held parent keeps its claim")
            assertEquals(0, fx.d.transitions(parent).size, "no transition row for the parent")
            assertTrue(
                rows.none { it.type == "item.transitioned" && it.entityId == parent.id },
                "no transition event for the parent: $rows"
            )
            assertEquals(Role.TERMINAL, fx.reload(child).role)
            assertFalse("agent-b" in element.toString(), "the holder's identity is not echoed: $element")
        }

    @Test
    fun `S6 the same call shape cascades an unheld parent - the control for the ownership-blocked report`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val (parent, child) = fx.family(null)

            val element = fx.completeAs("agent-a", child)

            assertEquals(
                listOf(cascadeJson(parent, "work", "terminal", applied = true)),
                element.arr("cascades").map { it.jsonObject }
            )
            assertEquals(Role.TERMINAL, fx.reload(parent).role)
        }

    @Test
    fun `S6 the service-level event carries ownershipBlocked, applied false and no other gate flag`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val (parent, child) = fx.family("agent-b")

            val result = fx.ctx.advanceDirect(child, "complete", actor = agentA).success()

            val event = result.cascadeEvents.single()
            assertEquals(parent.id, event.itemId)
            assertEquals(parent.title, event.title)
            assertEquals(Role.WORK, event.previousRole)
            assertEquals(Role.TERMINAL, event.targetRole)
            assertFalse(event.applied)
            assertTrue(event.ownershipBlocked)
            assertFalse(event.gateBlocked || event.resourceBlocked || event.roleBlocked || event.dependencyBlocked, "$event")
            assertTrue(event.gateMissingNotes.isEmpty() && event.blockers.isEmpty() && event.contendedResources.isEmpty(), "$event")
            assertNull(event.error)
        }

    @Test
    fun `S6 a null caller fails closed - a cascade with no caller id is ownership-blocked on any active claim`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val (parent, child) = fx.family("agent-b")

            val result = fx.ctx.advanceDirect(child, "complete", actor = null).success()

            val event = result.cascadeEvents.single()
            assertEquals(parent.id, event.itemId)
            assertTrue(event.ownershipBlocked, "no caller id fences on any active claim: $event")
            assertFalse(event.applied)
            assertEquals(Role.WORK, fx.reload(parent).role)
        }

    @Test
    fun `S6 ownership precedes the other cascade gates - a held parent with unmet notes reports ownership, not the gate`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val gatedParent = fx.d.item("gated P", Role.WORK, type = "p11-plain")
            val gatedChild = fx.d.item("gated C", Role.WORK, parent = gatedParent)

            // control first: with nobody holding the parent its missing required notes block the cascade on the gate
            val control = fx.completeAs("agent-a", gatedChild)
            val controlEntry = control.arr("cascades").single().jsonObject
            assertEquals("gate", controlEntry.text("blocked"), "control: the unheld parent is gate-blocked: $controlEntry")

            val heldParent = fx.d.item("held gated P", Role.WORK, type = "p11-plain")
            fx.heldBy(heldParent, "agent-b")
            val heldChild = fx.d.item("held gated C", Role.WORK, parent = heldParent)

            val element = fx.completeAs("agent-a", heldChild)

            val entry = element.arr("cascades").single().jsonObject
            assertEquals("ownership", entry.text("blocked"), "ownership is reported before the gate: $entry")
            assertFalse("missing" in entry, "the missing-notes detail of the lower-priority gate is not echoed: $entry")
        }

    @Test
    fun `S6 the same ownership block is reported by complete_tree inside the child's cascades`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val (parent, child) = fx.family("agent-b")

            val body = fx.ctx.itemsWrite(completeTreeOp("itemIds" to strings(child.id.toString())), actor = actorJson("agent-a"))

            val entry =
                body
                    .okResult()
                    .getValue(
                        "items"
                    ).jsonArray
                    .map { it.jsonObject }
                    .single { it.text("itemId") == child.id.toString() }
            assertEquals("applied", entry.text("outcome"))
            val cascade = entry.arr("cascades").single().jsonObject
            assertEquals(parent.id.toString(), cascade.text("itemId"))
            assertEquals(false, cascade.flag("applied"))
            assertEquals("ownership", cascade.text("blocked"))
            assertEquals(Role.WORK, fx.reload(parent).role)
            assertEquals(Role.TERMINAL, fx.reload(child).role, "the primary completion committed")
        }

    @Test
    fun `S6 items_write delete of the last open child reports the held parent with reason not_claim_holder`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val parent = fx.heldBy(fx.make("P", role = Role.WORK), "agent-b")
            val done = fx.make("done kid", parent, role = Role.TERMINAL)
            val open = fx.make("open kid", parent)

            val body = fx.ctx.itemsWrite(deleteOp(open.id), actor = actorJson("agent-a"))

            val element = body.okResult()
            val cascade = element.arr("cascade").single().jsonObject
            assertEquals(parent.id.toString(), cascade.text("itemId"))
            assertEquals("work", cascade.text("from"))
            assertEquals("terminal", cascade.text("to"))
            assertEquals(false, cascade.flag("applied"))
            assertEquals("not_claim_holder", cascade.text("reason"))
            assertEquals(Role.WORK, fx.reload(parent).role)
            assertEquals("agent-b", fx.reload(parent).claimedBy)
            assertEquals(Role.TERMINAL, fx.reload(done).role)
            assertEquals(2, fx.count("work_items"), "only the open child is gone: parent and done kid remain")
        }

    @Test
    fun `S6 items_write delete cascades an unheld parent - the control for the not_claim_holder reason`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val parent = fx.make("P", role = Role.WORK)
            fx.make("done kid", parent, role = Role.TERMINAL)
            val open = fx.make("open kid", parent)

            val element = fx.ctx.itemsWrite(deleteOp(open.id), actor = actorJson("agent-a")).okResult()

            val cascade = element.arr("cascade").single().jsonObject
            assertEquals(true, cascade.flag("applied"))
            assertFalse("reason" in cascade, "an applied cascade carries no reason: $cascade")
            assertEquals(Role.TERMINAL, fx.reload(parent).role)
        }

    // ------------------------------------------------------------------ S7

    @Test
    fun `S7 a parent held by the caller itself cascades to terminal and its claim is cleared`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val (parent, child) = fx.family("agent-a")

            val element = fx.completeAs("agent-a", child)

            assertEquals(listOf(cascadeJson(parent, "work", "terminal", applied = true)), element.arr("cascades").map { it.jsonObject })
            val after = fx.reload(parent)
            assertEquals(Role.TERMINAL, after.role)
            assertNull(after.claimedBy, "entering TERMINAL clears the claim")
        }

    @Test
    fun `S7 a parent whose claim lapsed cascades - refused one millisecond before the expiry, applied at exactly the expiry`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val (parentEarly, childEarly) = fx.family("agent-b")
            clock.set(fx.expiry.minusMillis(1))

            val early = fx.completeAs("agent-a", childEarly)
            assertEquals(
                "ownership",
                early
                    .arr("cascades")
                    .single()
                    .jsonObject
                    .text("blocked"),
                "E - 1 ms: still held"
            )
            assertEquals(Role.WORK, fx.reload(parentEarly).role)

            clock.set(FENCE_T)
            val (parentLate, childLate) = fx.family("agent-b")
            clock.set(fx.expiry)
            val late = fx.completeAs("agent-a", childLate)
            assertEquals(
                listOf(cascadeJson(parentLate, "work", "terminal", applied = true)),
                late.arr("cascades").map { it.jsonObject },
                "at exactly the expiry the claim is lapsed and fences nothing"
            )
            assertEquals(Role.TERMINAL, fx.reload(parentLate).role)
        }

    @Test
    fun `S7 the chain stops at a held grandparent - the unheld parent is applied, the grandparent is ownership-blocked`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val grand = fx.heldBy(fx.make("G", role = Role.WORK), "agent-b")
            val parent = fx.make("P", grand, role = Role.WORK)
            val child = fx.make("C", parent, role = Role.WORK)

            val element = fx.completeAs("agent-a", child)

            assertEquals(
                listOf(
                    cascadeJson(parent, "work", "terminal", applied = true),
                    cascadeJson(grand, "work", "terminal", applied = false, blocked = "ownership")
                ),
                element.arr("cascades").map { it.jsonObject }
            )
            assertEquals(Role.TERMINAL, fx.reload(parent).role)
            assertEquals(Role.WORK, fx.reload(grand).role)
            assertEquals("agent-b", fx.reload(grand).claimedBy)
        }

    @Test
    fun `S7 nothing above a blocked parent is evaluated - a held parent stops the chain below an unheld grandparent`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val grand = fx.make("G", role = Role.WORK)
            val parent = fx.heldBy(fx.make("P", grand, role = Role.WORK), "agent-b")
            val child = fx.make("C", parent, role = Role.WORK)

            val element = fx.completeAs("agent-a", child)

            assertEquals(
                listOf(cascadeJson(parent, "work", "terminal", applied = false, blocked = "ownership")),
                element.arr("cascades").map { it.jsonObject },
                "only the blocked parent is reported; the grandparent is not evaluated"
            )
            assertEquals(Role.WORK, fx.reload(grand).role)
            assertEquals(0, fx.d.transitions(grand).size)
        }

    // ------------------------------------------------------------------ D8: the rule is per write, START cascades included

    @Test
    fun `D8 a start cascade into a queue parent held by another principal is ownership-blocked, an unheld one applies`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val fx = rig(dir)
            val heldParent = fx.heldBy(fx.make("held P"), "agent-b")
            val heldChild = fx.make("held C", heldParent)
            val freeParent = fx.make("free P")
            val freeChild = fx.make("free C", freeParent)

            val control = fx.completeAs("agent-a", freeChild, trigger = "start")
            assertEquals(
                listOf(cascadeJson(freeParent, "queue", "work", applied = true)),
                control.arr("cascades").map { it.jsonObject },
                "control: the unheld queue parent is started by its child's start"
            )

            val blocked = fx.completeAs("agent-a", heldChild, trigger = "start")

            assertEquals(
                listOf(cascadeJson(heldParent, "queue", "work", applied = false, blocked = "ownership")),
                blocked.arr("cascades").map { it.jsonObject }
            )
            assertEquals(Role.QUEUE, fx.reload(heldParent).role)
            assertEquals(Role.WORK, fx.reload(heldChild).role, "the primary start committed")
        }
}
