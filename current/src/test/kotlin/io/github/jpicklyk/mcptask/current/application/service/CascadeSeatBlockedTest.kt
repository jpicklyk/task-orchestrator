package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.domain.lifecycle.SeatCaller
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advanceTool
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.Opened
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.Reply
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.SeatWorld
import io.github.jpicklyk.mcptask.current.parity.ParitySeeder
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.parity.parityAuthConfig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.rejections
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.success
import io.github.jpicklyk.mcptask.current.test.tools.ItemContextProbe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seat schemas for the cascade-seat tests: a seat-aware `seat-task` (planner enters queue, builder enters work, checker declares only
 * complete, canceller declares only cancel, orchestrator enters nothing), a seat-aware `gated-seat-task` with one required queue note,
 * and the seat-less `plain-task`.
 */
private const val CASCADE_SEAT_YAML = """
work_item_schemas:
  seat-task:
    seats:
      - { name: planner, phase: queue, enters: true }
      - { name: builder, phase: work, enters: true }
      - { name: checker, phase: work, transitions: [complete] }
      - { name: canceller, phase: work, transitions: [cancel] }
      - { name: orchestrator, phase: work }
    notes:
      - key: build-notes
        role: work
        required: false
        seat: builder
  gated-seat-task:
    seats:
      - { name: planner, phase: queue, enters: true }
      - { name: builder, phase: work, enters: true }
      - { name: checker, phase: work, transitions: [complete] }
    notes:
      - key: scope
        role: queue
        required: true
        seat: planner
  plain-task:
    notes: []
"""

/** The seat schemas the real-token tests run under (seated-task: builder enters work, checker declares complete; plain-task seat-less). */
private const val CASCADE_WIRE_CONFIG = """
work_item_schemas:
  seated-task:
    seats:
      - { name: builder, phase: work, enters: true }
      - { name: checker, phase: work, transitions: [complete] }
      - { name: orchestrator, phase: work }
    notes:
      - key: build-notes
        role: work
        required: false
        seat: builder
  plain-task:
    notes: []
"""

/** One cascade scenario shape: the trigger sent to the child, the roles the parent and child hold before, and the roles after. */
private enum class Kind(
    val trigger: String,
    val parentRole: Role,
    val childRole: Role,
    val childAfter: Role,
    val parentAfter: Role
) {
    START("start", Role.QUEUE, Role.QUEUE, Role.WORK, Role.WORK),
    COMPLETE("complete", Role.WORK, Role.WORK, Role.TERMINAL, Role.TERMINAL),

    /** A cancel-origin terminal cascade (decision D3: it maps to the complete trigger, not to a cancel of the parent). */
    CANCEL("cancel", Role.WORK, Role.WORK, Role.TERMINAL, Role.TERMINAL),
    REOPEN("reopen", Role.TERMINAL, Role.TERMINAL, Role.QUEUE, Role.WORK)
}

/** A root, its only child P (the parent a cascade evaluates), P's only child C (the write), and the run (null when none was opened). */
private class Fam(
    val root: WorkItem,
    val parent: WorkItem,
    val child: WorkItem,
    val run: UUID?
)

/**
 * Independent tests (test-author seat, item 88ab4416) for the SEAT check on cascades: a cascade into a seat-enforced member of an open
 * run applies only when the write's caller could send the equivalent user trigger to that parent itself; otherwise it is reported
 * `applied: false` with `seatBlocked`, the primary commits, the parent is untouched and the chain stops.
 *
 * Oracles (frozen at base a84eed24, none read from an implementation): task-scope "Defect class" 1-5, decisions D1-D6 and AC1-AC6;
 * the R23 seat table (a seat token may send start only into a phase its seat enters, complete / cancel only when declared, the
 * run's coordinator and a human credential carrying no seat token may send anything, a token acts on its own bound item only);
 * the api-reference cascade contract ("Suppressed, reported": `applied: false`, `blocked: seat`, `items_write` reason
 * `seat_forbidden`, checked after ownership and before every other gate, parent keeps role and claim, chain stops, a suppressed
 * cascade never fails the primary, a non-applicable cascade has no entry). The roles a coordinator-applied cascade produces are
 * the same documented cascade targets (start: queue to work; terminal: work to terminal; reopen: terminal to work).
 *
 * Surface labels (assigned by the plan seat, frozen in test-plan): every behavioural assertion on roles, rows and `applied` is
 * EXISTING-SURFACE; assertions on `seatBlocked`, `blocked: seat` and `seat_forbidden` are NEW-SURFACE.
 *
 * Negative-assertion fixture conditions: every suppression is paired with the same call shape under a caller that passes
 * (coordinator or human), asserting the parent moves, so the fixture would cascade without a seat check; every order scenario
 * is paired with the control in which the lower gate really blocks.
 *
 * Domain invariants satisfied by construction: items are created through the driver (children carry their parent's depth + 1 and
 * root), a parent's role is set at creation, runs are seeded by the run store seeder with the parent as the member.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class CascadeSeatBlockedTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun seatDriver(dir: Path) = P11Driver(EventLogRig.build(db.db, dir, CASCADE_SEAT_YAML))

    private suspend fun seedRun(
        root: WorkItem,
        members: List<WorkItem>,
        status: RunStatus = RunStatus.OPEN
    ): UUID {
        val provider = db.repositoryProvider()
        val run =
            ParitySeeder(provider.workItemRepository(), provider.dependencyRepository(), provider, db.unitOfWork()).run(
                id = UUID.randomUUID(),
                rootId = root.id,
                items = members.map { it.id },
                status = status,
                openedBy = "api:orch",
                openedByKind = "service",
                coordinatorToken = null
            )
        return run.id
    }

    private fun memberSeat(
        seat: String,
        run: UUID,
        item: WorkItem?
    ) = SeatCaller(seat = seat, runId = run, itemId = item?.id, human = false)

    private fun coordinatorOf(run: UUID) = SeatCaller(seat = "coordinator", runId = run, itemId = null, human = false)

    private val humanCaller = SeatCaller(seat = null, runId = null, itemId = null, human = true)

    /**
     * root, P (the parent, [parentType], holding the role [kind] needs), C (plain-task, holding the child role [kind] needs). The run
     * is seeded with P as its member when [member], empty otherwise, and not at all when [status] is null.
     */
    private suspend fun P11Driver.family(
        kind: Kind,
        label: String,
        parentType: String? = "seat-task",
        member: Boolean = true,
        status: RunStatus? = RunStatus.OPEN
    ): Fam {
        val root = item("root $label")
        val parent = item("parent $label", kind.parentRole, type = parentType, parent = root)
        val child = item("child $label", kind.childRole, type = "plain-task", parent = parent)
        val run = status?.let { seedRun(root, if (member) listOf(parent) else emptyList(), it) }
        return Fam(root, parent, child, run)
    }

    private fun Fam.withRun(run: UUID) = Fam(root, parent, child, run)

    /**
     * Drives [kind] on the child with [caller] and asserts the seat suppression: the primary committed, exactly one cascade entry (the
     * chain stops), `applied` false and `seatBlocked`, no other gate flag, and the parent untouched (role, previous role, label,
     * claim, role-change instant, no transition row, no event row naming it, no rejection row anywhere).
     */
    private suspend fun P11Driver.suppressed(
        kind: Kind,
        fam: Fam,
        caller: SeatCaller,
        label: String,
        actor: String? = null
    ): AdvanceCascadeEvent {
        val before = reload(fam.parent)
        val transitionsBefore = transitions(fam.parent).size
        val mark = rig.maxSeq()

        val result = advanceService(fam.child, kind.trigger, actor, caller).success()

        assertEquals(kind.childAfter, role(fam.child), "$label: the primary transition committed")
        val event = result.cascadeEvents.single()
        assertEquals(fam.parent.id, event.itemId, "$label: the only cascade entry is the member parent")
        assertEquals(kind.parentRole, event.previousRole, "$label: previousRole")
        assertEquals(kind.parentAfter, event.targetRole, "$label: targetRole")
        assertFalse(event.applied, "$label: $event")
        assertTrue(event.seatBlocked, "$label: $event")
        assertFalse(
            event.ownershipBlocked || event.gateBlocked || event.resourceBlocked || event.roleBlocked || event.dependencyBlocked,
            "$label: $event"
        )
        assertTrue(event.gateMissingNotes.isEmpty() && event.blockers.isEmpty() && event.contendedResources.isEmpty(), "$label: $event")
        assertEquals(null, event.error, "$label: a suppressed cascade is not an error")

        val after = reload(fam.parent)
        assertEquals(before.role, after.role, "$label: parent role unchanged")
        assertEquals(before.previousRole, after.previousRole, "$label: parent previous role unchanged")
        assertEquals(before.statusLabel, after.statusLabel, "$label: parent label unchanged")
        assertEquals(before.claimedBy, after.claimedBy, "$label: parent claim unchanged")
        assertEquals(before.roleChangedAt, after.roleChangedAt, "$label: parent role-change instant unchanged")
        assertEquals(transitionsBefore, transitions(fam.parent).size, "$label: no transition row for the parent")
        val rows = rig.rowsAfter(mark)
        assertTrue(rows.none { it.entityId == fam.parent.id }, "$label: no event row names the parent: $rows")
        assertEquals(emptyList(), rows.rejections(), "$label: no rejection row")
        return event
    }

    /** Drives [kind] on the child with [caller] and asserts the parent's cascade entry applied and the parent moved to its target. */
    private suspend fun P11Driver.cascaded(
        kind: Kind,
        fam: Fam,
        caller: SeatCaller,
        label: String,
        actor: String? = null
    ): AdvanceCascadeEvent {
        val transitionsBefore = transitions(fam.parent).size

        val result = advanceService(fam.child, kind.trigger, actor, caller).success()

        assertEquals(kind.childAfter, role(fam.child), "$label: the primary transition committed")
        val event = result.cascadeEvents.single { it.itemId == fam.parent.id }
        assertTrue(event.applied, "$label: $event")
        assertFalse(event.seatBlocked, "$label: $event")
        assertEquals(kind.parentAfter, role(fam.parent), "$label: the parent moved")
        assertEquals(transitionsBefore + 1, transitions(fam.parent).size, "$label: one transition row for the parent")
        return event
    }

    // ---------------------------------------------------------------------------------------------
    // S1 - S3: the three cascade directions, token-less (AC1)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 a token-less start of a non-member child leaves the seat-enforced member parent in queue, the coordinator starts it`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val blockedFam = d.family(Kind.START, "token-less")
            val controlFam = d.family(Kind.START, "coordinator")

            d.suppressed(Kind.START, blockedFam, SeatCaller.NONE, "token-less start cascade")
            d.cascaded(Kind.START, controlFam, coordinatorOf(controlFam.run!!), "control: the coordinator starts the same shape")
        }

    @Test
    fun `S2 a token-less complete or cancel of the last child leaves the member parent in work, the coordinator terminates it`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            for (kind in listOf(Kind.COMPLETE, Kind.CANCEL)) {
                val blockedFam = d.family(kind, "token-less ${kind.name}")
                val controlFam = d.family(kind, "coordinator ${kind.name}")

                d.suppressed(kind, blockedFam, SeatCaller.NONE, "token-less ${kind.name}")
                d.cascaded(kind, controlFam, coordinatorOf(controlFam.run!!), "control: coordinator ${kind.name}")
            }
        }

    @Test
    fun `S3 a token-less reopen of a terminal non-member child leaves the terminal member parent terminal, the coordinator reopens it`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val blockedFam = d.family(Kind.REOPEN, "token-less")
            val controlFam = d.family(Kind.REOPEN, "coordinator")

            d.suppressed(Kind.REOPEN, blockedFam, SeatCaller.NONE, "token-less reopen cascade")
            d.cascaded(Kind.REOPEN, controlFam, coordinatorOf(controlFam.run!!), "control: the coordinator reopens the same shape")
        }

    // ---------------------------------------------------------------------------------------------
    // S5: the callers that pass (AC3, D1)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 the coordinator of the run and a human credential cascade into the member parent in every direction`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            for (kind in Kind.entries) {
                // fixture condition: the same shape is suppressed for a token-less agent
                d.suppressed(kind, d.family(kind, "control ${kind.name}"), SeatCaller.NONE, "control ${kind.name}")

                val viaCoordinator = d.family(kind, "coordinator ${kind.name}")
                d.cascaded(kind, viaCoordinator, coordinatorOf(viaCoordinator.run!!), "coordinator ${kind.name}")
                d.cascaded(kind, d.family(kind, "human ${kind.name}"), humanCaller, "human ${kind.name}")
            }
        }

    // ---------------------------------------------------------------------------------------------
    // S6 / S7: a seat token is judged against the equivalent trigger on the parent itself (D1)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 a token bound to the parent cascades when its seat may send the equivalent trigger - builder start, checker complete, cancel`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)

            val start = d.family(Kind.START, "builder start")
            d.cascaded(Kind.START, start, memberSeat("builder", start.run!!, start.parent), "builder bound to the parent starts it")

            val complete = d.family(Kind.COMPLETE, "checker complete")
            d.cascaded(
                Kind.COMPLETE,
                complete,
                memberSeat("checker", complete.run!!, complete.parent),
                "checker bound to the parent completes it"
            )

            // a cancel-origin terminal cascade is the complete trigger (D3): the checker declares complete only, and it cascades
            val cancel = d.family(Kind.CANCEL, "checker cancel")
            d.cascaded(
                Kind.CANCEL,
                cancel,
                memberSeat("checker", cancel.run!!, cancel.parent),
                "checker bound to the parent, cancel-origin cascade"
            )
        }

    @Test
    fun `S7 a token that cannot send the equivalent trigger to the parent is suppressed - wrong seat, bound item, run or seat name`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val foreignRoot = d.item("foreign root")
            val foreignAnchor = d.item("foreign anchor", Role.QUEUE, type = "seat-task", parent = foreignRoot)
            val foreignRun = seedRun(foreignRoot, listOf(foreignAnchor))

            // builder bound to the parent: builder declares no complete, so a complete cascade is suppressed
            val builderComplete = d.family(Kind.COMPLETE, "builder complete")
            d.suppressed(
                Kind.COMPLETE,
                builderComplete,
                memberSeat("builder", builderComplete.run!!, builderComplete.parent),
                "builder bound to the parent, complete cascade"
            )

            // planner bound to the parent enters queue, not work: a start cascade into work is suppressed
            val plannerStart = d.family(Kind.START, "planner start")
            d.suppressed(
                Kind.START,
                plannerStart,
                memberSeat("planner", plannerStart.run!!, plannerStart.parent),
                "planner bound to the parent, start cascade"
            )

            // an undeclared seat name
            val ghostStart = d.family(Kind.START, "ghost start")
            d.suppressed(
                Kind.START,
                ghostStart,
                memberSeat("ghost", ghostStart.run!!, ghostStart.parent),
                "undeclared seat bound to the parent"
            )

            // the coordinator of ANOTHER run
            val foreignCoordinator = d.family(Kind.START, "foreign coordinator")
            d.suppressed(Kind.START, foreignCoordinator, coordinatorOf(foreignRun), "the coordinator token of another run")

            // a builder token of another run bound to its own item
            val foreignBuilder = d.family(Kind.START, "foreign builder")
            d.suppressed(Kind.START, foreignBuilder, memberSeat("builder", foreignRun, foreignAnchor), "a builder token of another run")

            // a builder token bound to a SIBLING member of the same run (not the parent)
            val sibling = d.family(Kind.START, "sibling member", status = null)
            val other = d.item("sibling member M", Role.QUEUE, type = "seat-task", parent = sibling.root)
            val siblingRun = seedRun(sibling.root, listOf(sibling.parent, other))
            d.suppressed(
                Kind.START,
                sibling.withRun(siblingRun),
                memberSeat("builder", siblingRun, other),
                "builder bound to a sibling member"
            )
            // control: the same builder token bound to the parent starts it
            val siblingControl = d.family(Kind.START, "sibling control")
            d.cascaded(
                Kind.START,
                siblingControl,
                memberSeat("builder", siblingControl.run!!, siblingControl.parent),
                "control: the builder bound to the parent"
            )

            // the canceller (declares cancel only): a cancel-origin terminal cascade is the complete trigger (D3), so it is suppressed
            val cancellerCancel = d.family(Kind.CANCEL, "canceller cancel")
            d.suppressed(
                Kind.CANCEL,
                cancellerCancel,
                memberSeat("canceller", cancellerCancel.run!!, cancellerCancel.parent),
                "canceller bound to the parent, cancel-origin cascade"
            )
            val cancellerComplete = d.family(Kind.COMPLETE, "canceller complete")
            d.suppressed(
                Kind.COMPLETE,
                cancellerComplete,
                memberSeat("canceller", cancellerComplete.run!!, cancellerComplete.parent),
                "canceller bound to the parent, complete cascade"
            )
        }

    // ---------------------------------------------------------------------------------------------
    // S8: the order inside a cascade step - ownership, then seat, then gate / dependency (AC5, D2)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 ownership is reported before the seat - a parent claimed by another is ownership-blocked, by the caller itself seat-blocked`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val foreign = d.family(Kind.START, "claimed by another")
            d.claim(foreign.parent, "agent-c")
            val own = d.family(Kind.START, "claimed by the caller")
            d.claim(own.parent, "agent-a")
            // fixture condition: without a seat-aware schema the same foreign claim is an ownership block
            val plain = d.family(Kind.START, "plain claimed by another", parentType = "plain-task")
            d.claim(plain.parent, "agent-d")
            val plainEvent =
                d
                    .advanceService(plain.child, "start", "agent-b", SeatCaller.NONE)
                    .success()
                    .cascadeEvents
                    .single()
            assertTrue(plainEvent.ownershipBlocked, "fixture: the foreign claim blocks a seat-less parent: $plainEvent")
            assertFalse(plainEvent.seatBlocked, "$plainEvent")

            val byOther =
                d
                    .advanceService(foreign.child, "start", "agent-b", SeatCaller.NONE)
                    .success()
                    .cascadeEvents
                    .single()
            assertEquals(foreign.parent.id, byOther.itemId)
            assertTrue(byOther.ownershipBlocked, "a foreign claim answers first: $byOther")
            assertFalse(byOther.seatBlocked, "$byOther")
            assertEquals(Role.QUEUE, d.role(foreign.parent))

            // control: the claim holder itself reaches the seat check
            val byHolder =
                d
                    .advanceService(own.child, "start", "agent-a", SeatCaller.NONE)
                    .success()
                    .cascadeEvents
                    .single()
            assertEquals(own.parent.id, byHolder.itemId)
            assertTrue(byHolder.seatBlocked, "the holder without a token meets the seat check: $byHolder")
            assertFalse(byHolder.ownershipBlocked, "$byHolder")
            assertEquals(Role.QUEUE, d.role(own.parent))
        }

    @Test
    fun `S8 the seat is reported before the note gate - token-less is seat-blocked, the coordinator is gate-blocked`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val tokenless = d.family(Kind.START, "gated token-less", parentType = "gated-seat-task")
            val coordinator = d.family(Kind.START, "gated coordinator", parentType = "gated-seat-task")

            // control: the required queue note is missing, so a caller the seat admits is stopped by the note gate
            val gate =
                d
                    .advanceService(coordinator.child, "start", null, coordinatorOf(coordinator.run!!))
                    .success()
                    .cascadeEvents
                    .single()
            assertTrue(gate.gateBlocked, "fixture: the missing note blocks the cascade: $gate")
            assertEquals(listOf("scope"), gate.gateMissingNotes.map { it.key })
            assertFalse(gate.seatBlocked, "$gate")

            val seat = d.suppressed(Kind.START, tokenless, SeatCaller.NONE, "token-less, parent missing its start notes")
            assertFalse(seat.gateBlocked, "the seat is reported, not the gate: $seat")
        }

    @Test
    fun `S8 the seat is reported before the dependency gate - token-less is seat-blocked, the coordinator is dependency-blocked`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val tokenless = d.family(Kind.START, "dependency token-less")
            val coordinator = d.family(Kind.START, "dependency coordinator")
            d.blocks(d.item("blocker one", Role.QUEUE), tokenless.parent)
            d.blocks(d.item("blocker two", Role.QUEUE), coordinator.parent)

            // control: the unmet blocker stops a caller the seat admits
            val dependency =
                d
                    .advanceService(
                        coordinator.child,
                        "start",
                        null,
                        coordinatorOf(coordinator.run!!)
                    ).success()
                    .cascadeEvents
                    .single()
            assertTrue(dependency.dependencyBlocked, "fixture: the blocker stops the cascade: $dependency")
            assertTrue(dependency.blockers.isNotEmpty(), "$dependency")
            assertFalse(dependency.seatBlocked, "$dependency")

            val seat = d.suppressed(Kind.START, tokenless, SeatCaller.NONE, "token-less, parent with an unmet blocker")
            assertFalse(seat.dependencyBlocked, "the seat is reported, not the dependency: $seat")
        }

    // ---------------------------------------------------------------------------------------------
    // S9: parents outside the seat scope cascade exactly as before (AC4)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 a parent in no run, a closed run, not a member, seat-less or schema-free cascades token-less, an open or paused run does not`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val foreignRoot = d.item("foreign root")
            val foreignAnchor = d.item("foreign anchor", Role.QUEUE, type = "seat-task", parent = foreignRoot)
            val foreignRun = seedRun(foreignRoot, listOf(foreignAnchor))

            // fixtures that must suppress: the open run and the paused run (a paused run still enforces)
            d.suppressed(Kind.START, d.family(Kind.START, "open run member"), SeatCaller.NONE, "control: member of an open run")
            d.suppressed(
                Kind.START,
                d.family(Kind.START, "paused run member", status = RunStatus.PAUSED),
                SeatCaller.NONE,
                "member of a paused run"
            )

            // fixtures that must cascade
            d.cascaded(Kind.START, d.family(Kind.START, "no run", status = null), SeatCaller.NONE, "parent in no run")
            d.cascaded(Kind.START, d.family(Kind.START, "closed run", status = RunStatus.CLOSED), SeatCaller.NONE, "member of a closed run")
            d.cascaded(Kind.START, d.family(Kind.START, "not a member", member = false), SeatCaller.NONE, "open run, parent not a member")
            d.cascaded(
                Kind.START,
                d.family(Kind.START, "seat-less member", parentType = "plain-task"),
                SeatCaller.NONE,
                "member with a seat-less schema"
            )
            d.cascaded(Kind.START, d.family(Kind.START, "schema-free member", parentType = null), SeatCaller.NONE, "member with no schema")

            // a token of another run is no better and no worse outside the scope
            val outside = d.family(Kind.START, "foreign token outside", status = null)
            d.cascaded(Kind.START, outside, memberSeat("builder", foreignRun, foreignAnchor), "foreign-run builder token, parent in no run")
        }

    // ---------------------------------------------------------------------------------------------
    // S10: a cascade that does not apply stays silent (D2)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S10 a complete of a non-last child produces no cascade entry, the last child produces the seat-blocked entry`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val fam = d.family(Kind.COMPLETE, "two children")
            val second = d.item("second child", Role.WORK, type = "plain-task", parent = fam.parent)

            val first = d.advanceService(fam.child, "complete", null, SeatCaller.NONE).success()

            assertEquals(emptyList(), first.cascadeEvents, "a parent with an open child is not a cascade candidate: ${first.cascadeEvents}")
            assertEquals(Role.WORK, d.role(fam.parent))
            // control: the same call on the last child reports the suppression
            val last =
                d
                    .advanceService(second, "complete", null, SeatCaller.NONE)
                    .success()
                    .cascadeEvents
                    .single()
            assertEquals(fam.parent.id, last.itemId)
            assertTrue(last.seatBlocked, "$last")
        }

    @Test
    fun `S10 a start of a child under a parent already in work produces no cascade entry`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val root = d.item("root")
            val parent = d.item("working member", Role.WORK, type = "seat-task", parent = root)
            val child = d.item("queued child", Role.QUEUE, type = "plain-task", parent = parent)
            seedRun(root, listOf(parent))

            val result = d.advanceService(child, "start", null, SeatCaller.NONE).success()

            assertEquals(Role.WORK, d.role(child))
            assertEquals(
                emptyList(),
                result.cascadeEvents,
                "a start cascade does not apply to a parent already in work: ${result.cascadeEvents}"
            )
            assertEquals(0, d.transitions(parent).size)
        }

    // ---------------------------------------------------------------------------------------------
    // S11: each step of a chain is checked (a non-member intermediate parent does not launder the climb)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 a start cascade stops at the non-member parent - the member grandparent above it is not evaluated`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val root = d.item("root")
            val grand = d.item("member grandparent", Role.QUEUE, type = "seat-task", parent = root)
            val parent = d.item("non-member parent", Role.QUEUE, type = "plain-task", parent = grand)
            val child = d.item("child", Role.QUEUE, type = "plain-task", parent = parent)
            seedRun(root, listOf(grand))

            val result = d.advanceService(child, "start", null, SeatCaller.NONE).success()

            // api-reference "start": it evaluates the immediate parent and does not climb further
            assertEquals(listOf(parent.id), result.cascadeEvents.map { it.itemId }, "${result.cascadeEvents}")
            assertTrue(result.cascadeEvents.single().applied, "${result.cascadeEvents}")
            assertEquals(Role.WORK, d.role(parent), "the non-member parent cascaded")
            assertEquals(Role.QUEUE, d.role(grand), "the member grandparent was not evaluated")
            assertEquals(0, d.transitions(grand).size)
        }

    @Test
    fun `S11 a complete climbs through a non-member parent, stops at the member grandparent, the coordinator climbs through`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val root = d.item("root")
            val grand = d.item("member grandparent", Role.WORK, type = "seat-task", parent = root)
            val parent = d.item("non-member parent", Role.WORK, type = "plain-task", parent = grand)
            val child = d.item("child", Role.WORK, type = "plain-task", parent = parent)
            seedRun(root, listOf(grand))
            val controlRoot = d.item("control root")
            val controlGrand = d.item("control member grandparent", Role.WORK, type = "seat-task", parent = controlRoot)
            val controlParent = d.item("control non-member parent", Role.WORK, type = "plain-task", parent = controlGrand)
            val controlChild = d.item("control child", Role.WORK, type = "plain-task", parent = controlParent)
            val controlRun = seedRun(controlRoot, listOf(controlGrand))

            val result = d.advanceService(child, "complete", null, SeatCaller.NONE).success()

            assertEquals(listOf(parent.id, grand.id), result.cascadeEvents.map { it.itemId }, "${result.cascadeEvents}")
            assertTrue(result.cascadeEvents[0].applied, "${result.cascadeEvents[0]}")
            assertTrue(result.cascadeEvents[1].seatBlocked && !result.cascadeEvents[1].applied, "${result.cascadeEvents[1]}")
            assertEquals(Role.TERMINAL, d.role(parent))
            assertEquals(Role.WORK, d.role(grand), "the member grandparent keeps its role")

            // control: the run's coordinator climbs through both
            d.advanceService(controlChild, "complete", null, coordinatorOf(controlRun)).success()
            assertEquals(Role.TERMINAL, d.role(controlParent))
            assertEquals(Role.TERMINAL, d.role(controlGrand), "the coordinator is admitted at the member grandparent")
        }

    // ---------------------------------------------------------------------------------------------
    // Probes and S13
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `probe the same suppression repeated for a second child changes nothing - the parent is still untouched and still reported`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val fam = d.family(Kind.START, "repeat")
            val second = d.item("second queued child", Role.QUEUE, type = "plain-task", parent = fam.parent)

            d.suppressed(Kind.START, fam, SeatCaller.NONE, "first start")
            val again =
                d
                    .advanceService(second, "start", null, SeatCaller.NONE)
                    .success()
                    .cascadeEvents
                    .single()

            assertEquals(fam.parent.id, again.itemId)
            assertTrue(again.seatBlocked && !again.applied, "$again")
            assertEquals(Role.QUEUE, d.role(fam.parent))
            assertEquals(0, d.transitions(fam.parent).size)
        }

    @Test
    fun `S13 the preview of the child and of the member parent carries no seat check`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val fam = d.family(Kind.START, "preview")

            suspend fun canAdvance(item: WorkItem): String? =
                d.rig
                    .callOk(ItemContextProbe(), "itemId" to JsonPrimitive(item.id.toString()))["data"]!!
                    .jsonObject["gateStatus"]!!
                    .jsonObject["canAdvance"]
                    ?.jsonPrimitive
                    ?.content

            assertEquals("true", canAdvance(fam.child), "the child preview is unchanged")
            assertEquals("true", canAdvance(fam.parent), "the member parent preview does not apply the SEAT gate")
            // control: the same advance of the member parent is a seat refusal for a token-less agent
            assertTrue(d.advanceService(fam.parent, "start", null, SeatCaller.NONE) is AdvanceOutcome.Failure)
            assertEquals(Role.QUEUE, d.role(fam.parent))
        }
}

/**
 * The same suppression on the wire, with REAL seat tokens over the production dispatcher: `advance` (cascade entry
 * `blocked: "seat"`), `items_write` `delete` and reparent-away (`cascade` entry `reason: "seat_forbidden"`), and `complete_tree`
 * (AC1, AC2, AC3). Oracles: task-scope AC1-AC3, D1, the api-reference cascade contract and the documented cascade entry shape
 * `{itemId, previousRole, targetRole, applied, blocked?}`; the `items_write` cascade entry `{itemId, from, to, applied, reason?}`.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class CascadeSeatBlockedWireTest {
    private val clock = SettableClock(Instant.parse("2026-10-10T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private fun world(block: suspend (SeatWorld) -> Unit) =
        runBlocking {
            val w = SeatWorld.create(db.db, dir, clock, CASCADE_WIRE_CONFIG)
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    private class WireFam(
        val root: WorkItem,
        val parent: WorkItem,
        val child: WorkItem,
        val run: Opened
    )

    /** A root, P (seated-task, member of an open run on that root) and P's only child C (plain-task). */
    private suspend fun SeatWorld.fam(
        label: String,
        parentRole: Role,
        childRole: Role
    ): WireFam {
        val r = rig.seed("root $label")
        val p = rig.seed("parent $label", parent = r, role = parentRole, tags = "seated-task")
        val c = rig.seed("child $label", parent = p, role = childRole, tags = "plain-task")
        return WireFam(r, p, c, open(members = listOf(p), rootItem = r))
    }

    private suspend fun SeatWorld.roleOf(item: WorkItem): Role = checkNotNull(rig.raw.workItemRepository().getById(item.id)).role!!

    private suspend fun SeatWorld.advanceOne(
        item: WorkItem,
        trigger: String,
        token: String? = null,
        by: ParityToken = orch,
        element: String = ""
    ): Reply = ops("advance", """{"op":"transition","itemId":"${item.id}","trigger":"$trigger"$element}""", token = token, by = by)

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

    private fun Reply.element(): JsonObject = results().firstOrNull() ?: error("the call returned no result element: $this")

    private fun Reply.cascades(): List<JsonObject> = element()["cascades"]?.jsonArray?.map { it.jsonObject }.orEmpty()

    private fun Reply.itemsWriteCascade(): List<JsonObject> = element()["cascade"]?.jsonArray?.map { it.jsonObject }.orEmpty()

    /** The cascade entry of an `items_write` element that names [parent] (an applied terminal cascade may climb on to the root too). */
    private fun Reply.cascadeOf(parent: WorkItem): JsonObject =
        itemsWriteCascade().singleOrNull { it["itemId"]?.jsonPrimitive?.content == parent.id.toString() }
            ?: error("no single cascade entry for the parent ${parent.id}: $this")

    /** The one cascade entry of an `items_write` element, failing with the whole reply when there is not exactly one. */
    private fun Reply.onlyCascade(): JsonObject {
        val entries = itemsWriteCascade()
        assertEquals(1, entries.size, "expected exactly one cascade entry: $this")
        return entries.single()
    }

    @Test
    fun `S1 AC1 a token-less start of a non-member child reports exactly the seat-blocked cascade and leaves the member parent in queue`() =
        world { w ->
            val blocked = w.fam("blocked", Role.QUEUE, Role.QUEUE)
            val control = w.fam("control", Role.QUEUE, Role.QUEUE)

            val reply = w.advanceOne(blocked.child, "start")

            assertTrue(reply.ok(), reply.toString())
            assertEquals("work", reply.find("newRole"), "the primary start committed: $reply")
            assertEquals(
                listOf(cascadeJson(blocked.parent, "queue", "work", applied = false, blocked = "seat")),
                reply.cascades(),
                "the cascade entry is exactly the documented shape with blocked: seat"
            )
            assertEquals(Role.WORK, w.roleOf(blocked.child))
            assertEquals(Role.QUEUE, w.roleOf(blocked.parent))

            // control: the same call under the run's coordinator token starts the member parent
            val viaCoordinator = w.advanceOne(control.child, "start", token = control.run.coordinatorToken)
            assertTrue(viaCoordinator.ok(), viaCoordinator.toString())
            val entry = viaCoordinator.cascades().single { it["itemId"]?.jsonPrimitive?.content == control.parent.id.toString() }
            assertEquals(cascadeJson(control.parent, "queue", "work", applied = true), entry)
            assertEquals(Role.WORK, w.roleOf(control.parent))
        }

    @Test
    fun `S1 AC3 a human credential and a builder token bound to the parent cascade into the member parent`() =
        world { w ->
            val human = w.fam("human", Role.QUEUE, Role.QUEUE)
            val bound = w.fam("bound", Role.QUEUE, Role.QUEUE)
            val builder = w.assignOk(bound.run, bound.parent, "builder").token

            val byHuman = w.advanceOne(human.child, "start", by = w.human)
            val byBuilder = w.advanceOne(bound.child, "start", token = builder)

            assertTrue(byHuman.ok(), byHuman.toString())
            assertTrue(byBuilder.ok(), byBuilder.toString())
            assertEquals(Role.WORK, w.roleOf(human.parent), "a human credential carrying no seat token is exempt")
            assertEquals(Role.WORK, w.roleOf(bound.parent), "the builder bound to the parent may send start to it")
        }

    @Test
    fun `S7 a builder token bound to the parent cannot complete it by cascade, a sibling token or a human holding one cannot start it`() =
        world { w ->
            val complete = w.fam("complete", Role.WORK, Role.WORK)
            val builderOfParent = w.assignOk(complete.run, complete.parent, "builder").token

            val builderComplete = w.advanceOne(complete.child, "complete", token = builderOfParent)

            assertTrue(builderComplete.ok(), builderComplete.toString())
            assertEquals(
                listOf(cascadeJson(complete.parent, "work", "terminal", applied = false, blocked = "seat")),
                builderComplete.cascades()
            )
            assertEquals(Role.WORK, w.roleOf(complete.parent))

            // a builder token bound to a sibling member of the same run
            val r = w.rig.seed("root sibling")
            val p = w.rig.seed("parent", parent = r, tags = "seated-task")
            val sibling = w.rig.seed("sibling member", parent = r, tags = "seated-task")
            val c1 = w.rig.seed("child one", parent = p, tags = "plain-task")
            val run = w.open(members = listOf(p, sibling), rootItem = r)
            val builderOfSibling = w.assignOk(run, sibling, "builder").token

            val bySibling = w.advanceOne(c1, "start", token = builderOfSibling)
            assertEquals(
                "seat",
                bySibling
                    .cascades()
                    .single()["blocked"]
                    ?.jsonPrimitive
                    ?.content,
                bySibling.toString()
            )
            assertEquals(Role.QUEUE, w.roleOf(p))

            // a HUMAN credential that presents a seat token is judged by the token, not exempted (the run is opened and assigned
            // by the human principal so the token is accepted for it)
            val hr = w.rig.seed("root human token")
            val hp = w.rig.seed("human token parent", parent = hr, tags = "seated-task")
            val hsibling = w.rig.seed("human token sibling", parent = hr, tags = "seated-task")
            val hc = w.rig.seed("human token child", parent = hp, tags = "plain-task")
            val humanRun = w.open(members = listOf(hp, hsibling), by = w.human, rootItem = hr)
            val humanSiblingToken = w.assignOk(humanRun, hsibling, "builder", by = w.human).token
            val humanWithToken = w.advanceOne(hc, "start", token = humanSiblingToken, by = w.human)
            assertEquals(
                "seat",
                humanWithToken
                    .cascades()
                    .single()["blocked"]
                    ?.jsonPrimitive
                    ?.content,
                humanWithToken.toString()
            )
            assertEquals(Role.QUEUE, w.roleOf(hp), "a human presenting a seat token is judged by the token")
        }

    @Test
    fun `AC2 items_write delete of the last open child reports the member parent with seat_forbidden, coordinator and human cascade`() =
        world { w ->
            val blocked = w.fam("delete blocked", Role.WORK, Role.WORK)
            val done = w.rig.seed("done kid", parent = blocked.parent, role = Role.TERMINAL, tags = "plain-task")

            val reply = w.ops("items_write", """{"op":"delete","id":"${blocked.child.id}"}""")

            assertTrue(reply.ok(), reply.toString())
            val cascade = reply.onlyCascade()
            assertEquals(blocked.parent.id.toString(), cascade["itemId"]?.jsonPrimitive?.content)
            assertEquals("work", cascade["from"]?.jsonPrimitive?.content)
            assertEquals("terminal", cascade["to"]?.jsonPrimitive?.content)
            assertEquals("false", cascade["applied"]?.jsonPrimitive?.content)
            assertEquals("seat_forbidden", cascade["reason"]?.jsonPrimitive?.content, reply.toString())
            assertEquals(Role.WORK, w.roleOf(blocked.parent))
            assertEquals(Role.TERMINAL, w.roleOf(done), "the sibling is untouched")

            // controls: the same delete under the coordinator token and under a human credential cascades the parent
            val viaCoordinator = w.fam("delete coordinator", Role.WORK, Role.WORK)
            w.rig.seed("done kid", parent = viaCoordinator.parent, role = Role.TERMINAL, tags = "plain-task")
            val coordinatorReply =
                w.ops("items_write", """{"op":"delete","id":"${viaCoordinator.child.id}"}""", token = viaCoordinator.run.coordinatorToken)
            val coordinatorCascade = coordinatorReply.cascadeOf(viaCoordinator.parent)
            assertEquals("true", coordinatorCascade["applied"]?.jsonPrimitive?.content, coordinatorReply.toString())
            assertFalse("reason" in coordinatorCascade, "an applied cascade carries no reason: $coordinatorCascade")
            assertEquals(Role.TERMINAL, w.roleOf(viaCoordinator.parent))

            val viaHuman = w.fam("delete human", Role.WORK, Role.WORK)
            w.rig.seed("done kid", parent = viaHuman.parent, role = Role.TERMINAL, tags = "plain-task")
            val humanReply = w.ops("items_write", """{"op":"delete","id":"${viaHuman.child.id}"}""", by = w.human)
            assertEquals("true", humanReply.cascadeOf(viaHuman.parent)["applied"]?.jsonPrimitive?.content, humanReply.toString())
            assertEquals(Role.TERMINAL, w.roleOf(viaHuman.parent))
        }

    @Test
    fun `AC2 items_write reparent-away of the last open child reports the member parent with reason seat_forbidden`() =
        world { w ->
            val blocked = w.fam("move blocked", Role.WORK, Role.WORK)
            w.rig.seed("done kid", parent = blocked.parent, role = Role.TERMINAL, tags = "plain-task")

            val reply = w.ops("items_write", """{"op":"update","id":"${blocked.child.id}","clear":["parentId"]}""")

            assertTrue(reply.ok(), reply.toString())
            val cascade = reply.onlyCascade()
            assertEquals(blocked.parent.id.toString(), cascade["itemId"]?.jsonPrimitive?.content)
            assertEquals("false", cascade["applied"]?.jsonPrimitive?.content)
            assertEquals("seat_forbidden", cascade["reason"]?.jsonPrimitive?.content, reply.toString())
            assertEquals(Role.WORK, w.roleOf(blocked.parent))

            // control: the same move under the coordinator token
            val viaCoordinator = w.fam("move coordinator", Role.WORK, Role.WORK)
            w.rig.seed("done kid", parent = viaCoordinator.parent, role = Role.TERMINAL, tags = "plain-task")
            val coordinatorReply =
                w.ops(
                    "items_write",
                    """{"op":"update","id":"${viaCoordinator.child.id}","clear":["parentId"]}""",
                    token = viaCoordinator.run.coordinatorToken
                )
            assertEquals(
                "true",
                coordinatorReply.cascadeOf(viaCoordinator.parent)["applied"]?.jsonPrimitive?.content,
                coordinatorReply.toString()
            )
            assertEquals(Role.TERMINAL, w.roleOf(viaCoordinator.parent))
        }

    @Test
    fun `AC4 items_write delete under a parent outside any run cascades for a token-less agent`() =
        world { w ->
            val root = w.rig.seed("root outside")
            val parent = w.rig.seed("seat-aware parent in no run", parent = root, role = Role.WORK, tags = "seated-task")
            w.rig.seed("done kid", parent = parent, role = Role.TERMINAL, tags = "plain-task")
            val open = w.rig.seed("open kid", parent = parent, role = Role.WORK, tags = "plain-task")

            val reply = w.ops("items_write", """{"op":"delete","id":"${open.id}"}""")

            assertEquals("true", reply.cascadeOf(parent)["applied"]?.jsonPrimitive?.content, reply.toString())
            assertEquals(Role.TERMINAL, w.roleOf(parent))
        }

    @Test
    fun `S12 complete_tree of a listed child reports the seat-blocked cascade in the child's entry`() =
        world { w ->
            val fam = w.fam("tree items", Role.WORK, Role.WORK)

            val reply = w.ops("items_write", """{"op":"complete_tree","itemIds":["${fam.child.id}"]}""")

            assertTrue(reply.ok(), reply.toString())
            val entry =
                reply
                    .results()
                    .first()["items"]!!
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it["itemId"]?.jsonPrimitive?.content == fam.child.id.toString() }
            assertEquals("applied", entry["outcome"]?.jsonPrimitive?.content, entry.toString())
            val cascade = entry["cascades"]!!.jsonArray.single().jsonObject
            assertEquals(fam.parent.id.toString(), cascade["itemId"]?.jsonPrimitive?.content)
            assertEquals("false", cascade["applied"]?.jsonPrimitive?.content)
            assertEquals("seat", cascade["blocked"]?.jsonPrimitive?.content)
            assertEquals(Role.TERMINAL, w.roleOf(fam.child), "the primary completion committed")
            assertEquals(Role.WORK, w.roleOf(fam.parent))
        }

    @Test
    fun `S12 complete_tree over the member parent completes the leaves, cannot terminate the parent token-less, the coordinator can`() =
        world { w ->
            val blocked = w.fam("tree root blocked", Role.WORK, Role.WORK)
            val blockedLeaf = w.rig.seed("second leaf", parent = blocked.parent, role = Role.WORK, tags = "plain-task")
            val control = w.fam("tree root control", Role.WORK, Role.WORK)
            val controlLeaf = w.rig.seed("second leaf", parent = control.parent, role = Role.WORK, tags = "plain-task")

            val reply = w.ops("items_write", """{"op":"complete_tree","rootId":"${blocked.parent.id}"}""")

            assertEquals(Role.TERMINAL, w.roleOf(blocked.child), "$reply")
            assertEquals(Role.TERMINAL, w.roleOf(blockedLeaf), "$reply")
            assertEquals(
                Role.WORK,
                w.roleOf(blocked.parent),
                "a token-less agent cannot terminate the member parent, directly or by cascade: $reply"
            )
            assertTrue("seat_forbidden" in reply.toString(), "the member parent itself is refused with seat_forbidden: $reply")

            val viaCoordinator =
                w.ops("items_write", """{"op":"complete_tree","rootId":"${control.parent.id}"}""", token = control.run.coordinatorToken)
            assertEquals(Role.TERMINAL, w.roleOf(control.child), "$viaCoordinator")
            assertEquals(Role.TERMINAL, w.roleOf(controlLeaf), "$viaCoordinator")
            assertEquals(Role.TERMINAL, w.roleOf(control.parent), "control: the coordinator terminates the parent: $viaCoordinator")
            assertFalse("seat_forbidden" in viaCoordinator.toString(), "$viaCoordinator")
        }

    @Test
    fun `probe a keyed replay of a transition whose cascade was suppressed returns the same suppression and starts nothing`() =
        world { w ->
            val fam = w.fam("replay", Role.QUEUE, Role.QUEUE)
            val element = ""","idempotencyKey":"cascade-seat-replay""""

            val first = w.advanceOne(fam.child, "start", element = element)
            val replay = w.advanceOne(fam.child, "start", element = element)

            assertTrue(first.ok(), first.toString())
            assertEquals(listOf(cascadeJson(fam.parent, "queue", "work", applied = false, blocked = "seat")), first.cascades())
            assertTrue(replay.ok(), replay.toString())
            assertEquals("true", replay.find("replayed"), "fixture: the second call is a replay: $replay")
            assertEquals(first.cascades(), replay.cascades(), "the stored result carries the same suppressed cascade")
            assertEquals(Role.QUEUE, w.roleOf(fam.parent))
            assertEquals(Role.WORK, w.roleOf(fam.child))
        }

    @Test
    fun `probe an atomic batch keeps the primary when a cascade is suppressed - a suppressed cascade never fails the call`() =
        world { w ->
            val fam = w.fam("atomic", Role.QUEUE, Role.QUEUE)

            val reply = w.ops("advance", """{"op":"transition","itemId":"${fam.child.id}","trigger":"start"}""", top = ""","atomic":true""")

            assertTrue(reply.ok(), reply.toString())
            assertEquals(Role.WORK, w.roleOf(fam.child), "the primary stays committed in an atomic batch")
            assertEquals(Role.QUEUE, w.roleOf(fam.parent))
            assertEquals(
                "seat",
                reply
                    .cascades()
                    .single()["blocked"]
                    ?.jsonPrimitive
                    ?.content,
                reply.toString()
            )
        }

    @Test
    fun `probe the run closed between two calls - the first start is suppressed, the second child's start cascades`() =
        world { w ->
            val fam = w.fam("closed between", Role.QUEUE, Role.QUEUE)
            val second = w.rig.seed("second child", parent = fam.parent, tags = "plain-task")

            val before = w.advanceOne(fam.child, "start")
            assertEquals(
                "seat",
                before
                    .cascades()
                    .single()["blocked"]
                    ?.jsonPrimitive
                    ?.content,
                before.toString()
            )
            assertEquals(Role.QUEUE, w.roleOf(fam.parent))

            assertTrue(w.close(fam.run, "cancelled", fam.run.coordinatorToken).ok(), "fixture: the run is closed")
            val after = w.advanceOne(second, "start")

            assertTrue(after.ok(), after.toString())
            assertEquals(Role.WORK, w.roleOf(fam.parent), "a closed run enforces nothing, so the cascade starts the former member")
        }
}

/**
 * The REST leg of AC1: `POST /api/v1/ops/advance` over the production REST app returns the same cascade entry as the MCP leg
 * (`blocked: "seat"`, `applied: false`), and a human credential on the same call shape cascades the parent. Oracle: task-scope AC1
 * ("on `advance` (MCP and REST)") and the documented cascade entry shape.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class CascadeSeatBlockedRestTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val caps = setOf(ApiCapability.READ, ApiCapability.ADVANCE)

    private fun drive(
        dir: Path,
        token: ParityToken,
        check: suspend (cascades: List<JsonObject>, parent: WorkItem, parentRole: Role) -> Unit
    ): Unit =
        testApplication {
            val rig = EventLogRig.build(db.db, dir, CASCADE_SEAT_YAML)
            val root = rig.seed("rest root")
            val parent = rig.seed("rest member parent", parent = root, tags = "seat-task")
            val child = rig.seed("rest child", parent = parent, tags = "plain-task")
            val provider = db.repositoryProvider()
            ParitySeeder(provider.workItemRepository(), provider.dependencyRepository(), provider, db.unitOfWork()).run(
                id = UUID.randomUUID(),
                rootId = root.id,
                items = listOf(parent.id),
                status = RunStatus.OPEN,
                openedBy = "api:orch",
                openedByKind = "service",
                coordinatorToken = null
            )
            application { installParityRestApp(rig, parityAuthConfig(token), ToolRegistry(listOf(advanceTool()))) }

            val response =
                client.post("/api/v1/ops/advance") {
                    header("Authorization", "Bearer ${token.raw}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"ops":[{"op":"transition","itemId":"${child.id}","trigger":"start"}]}""")
                }
            val element =
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["data"]!!
                    .jsonObject["results"]!!
                    .jsonArray[0]
                    .jsonObject
            assertEquals("true", element["ok"]?.jsonPrimitive?.content, element.toString())
            assertEquals("work", element["newRole"]?.jsonPrimitive?.content, "the primary start committed: $element")
            val cascades = element["cascades"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            val stored = checkNotNull(rig.raw.workItemRepository().getById(parent.id)).role!!
            check(cascades, parent, stored)
        }

    @Test
    fun `S1 REST a token-less agent's start of a non-member child reports the seat-blocked cascade and leaves the member parent in queue`(
        @TempDir dir: Path
    ) = drive(dir, ParityToken("cascade-rest-agent", caps)) { cascades, parent, parentRole ->
        assertEquals(
            listOf(
                buildJsonObject {
                    put("itemId", parent.id.toString())
                    put("previousRole", "queue")
                    put("targetRole", "work")
                    put("applied", false)
                    put("blocked", "seat")
                }
            ),
            cascades
        )
        assertEquals(Role.QUEUE, parentRole)
    }

    @Test
    fun `S1 REST control - a human credential on the same call shape starts the member parent`(
        @TempDir dir: Path
    ) = drive(dir, ParityToken("cascade-rest-human", caps, kind = PrincipalKind.HUMAN)) { cascades, parent, parentRole ->
        val entry = cascades.single { it["itemId"]?.jsonPrimitive?.content == parent.id.toString() }
        assertEquals("true", entry["applied"]?.jsonPrimitive?.content, entry.toString())
        assertFalse("blocked" in entry, entry.toString())
        assertEquals(Role.WORK, parentRole)
    }
}
