package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.lifecycle.SeatCaller
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.Reply
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.SeatWorld
import io.github.jpicklyk.mcptask.current.parity.ParitySeeder
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.rejections
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.transitioned
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.P11_LEASE_KEY
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.failure
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.rawExec
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.success
import io.github.jpicklyk.mcptask.current.test.testClaimService
import io.github.jpicklyk.mcptask.current.test.tools.ItemContextProbe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The seat-aware and seat-less schemas the R23 SEAT-gate tests run under (seats, one declared `transitions`, one declared empty list). */
private const val SEAT_GATE_YAML = """
work_item_schemas:
  seat-task:
    seats:
      - { name: planner, phase: queue, enters: true }
      - { name: builder, phase: work, enters: true }
      - { name: checker, phase: work, transitions: [complete] }
      - { name: orchestrator, phase: work }
      - { name: silent, phase: work, transitions: [] }
    notes:
      - key: build-notes
        role: work
        required: false
        seat: builder
  plain-task:
    notes: []
"""

/**
 * Independent P11 tests (item 919d379e) for gate order, the CHANGE set and the legacy messages: S7, S8, S10.
 *
 * Oracles: task-scope 1.1 step 3 and DEFAULT_GATES order (OWNERSHIP, SEAT, TABLE, WARRANT, HOLD, DEPENDENCY, NOTE, LEASE);
 * AC2/AC3 (rejection rows: exactly one `transition.rejected` gate_blocked with missingKeys / dependency_unmet with
 * blockerIds, or one `lease.rejected`, none for invalid_transition or not_claim_holder); task-scope 3(b)-(e)
 * (resume dependency-gated, cancel never gated, start/reopen cascades dependency-gated, unreadable blocker "unknown");
 * 1.4 (legacy message strings verbatim); api-rest.md for the gate and lease messages.
 *
 * Q32 port: the oracle is the AdvanceService outcome (the failure variant, its message and data), so every advance drives
 * the shared service directly (P11Driver.advanceService) instead of the removed 3.x advance_item driver.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceGateOrderTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun driver(dir: Path) = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))

    // ---------------------------------------------------------------------------------------------
    // S7 -- the gate order on one item, with one rejection row per rejection
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S7 start is rejected by ownership, then dependency, then notes, then lease, then applied`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("ordered", Role.QUEUE, type = "p11-order")
            val blocker = d.item("blocker", Role.QUEUE)
            val holder = d.item("lease holder", Role.QUEUE)
            d.blocks(blocker, item)
            d.holdLease(holder)
            d.claim(item, "agent-a")
            val mark = d.rig.maxSeq()

            // 1. ownership first: every other gate is also failing, yet the claim holder check answers.
            val ownership = d.advanceService(item, "start", actor = "agent-b").failure()
            assertIs<AdvanceFailure.OwnershipRejected>(ownership, "$ownership")
            assertEquals(
                0,
                d.rig
                    .rowsAfter(mark)
                    .rejections()
                    .size,
                "not_claim_holder writes no rejection row"
            )

            // 2. dependency next.
            val dependency = assertIs<AdvanceFailure.ValidationFailed>(d.advanceService(item, "start", actor = "agent-a").failure())
            assertEquals(blocker.id, dependency.blockers[0].fromItemId, "$dependency")
            val afterDependency = d.rig.rowsAfter(mark).rejections()
            assertEquals(1, afterDependency.size, "exactly one row for the dependency rejection: $afterDependency")
            assertEquals("transition.rejected", afterDependency.single().type)
            assertEquals("dependency_unmet", afterDependency.single().str("code"))
            assertEquals(
                listOf(blocker.id.toString()),
                afterDependency
                    .single()
                    .payload()["blockerIds"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            )

            // 3. notes next.
            d.setRole(blocker, Role.TERMINAL)
            val notes = assertIs<AdvanceFailure.GateBlocked>(d.advanceService(item, "start", actor = "agent-a").failure())
            assertEquals(listOf("spec"), notes.missingNotes.map { it.key }, "$notes")
            val afterNotes = d.rig.rowsAfter(mark).rejections()
            assertEquals(2, afterNotes.size, "one more row, not a repeat of the first: $afterNotes")
            assertEquals("gate_blocked", afterNotes.last().str("code"))
            assertEquals(
                listOf("spec"),
                afterNotes
                    .last()
                    .payload()["missingKeys"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            )

            // 4. lease last.
            d.note(item, "spec", "queue")
            val lease = assertIs<AdvanceFailure.ResourceLeaseUnavailable>(d.advanceService(item, "start", actor = "agent-a").failure())
            assertEquals(listOf(P11_LEASE_KEY), lease.contendedResources, "$lease")
            assertTrue((lease.retryAfterMs ?: 0) >= 1, "retryAfterMs is at least 1: $lease")
            val afterLease = d.rig.rowsAfter(mark).rejections()
            assertEquals(3, afterLease.size, "$afterLease")
            assertEquals("lease.rejected", afterLease.last().type)
            assertEquals(
                listOf(P11_LEASE_KEY),
                afterLease
                    .last()
                    .payload()["contendedKeys"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            )
            assertEquals(Role.QUEUE, d.role(item), "nothing applied by any rejection")
            assertEquals(0, d.transitions(item).size)

            // 5. all gates open: applied, lease taken, no further rejection row.
            d.freeLease(holder)
            val applied = d.advanceService(item, "start", actor = "agent-a").success()
            assertEquals(Role.WORK, applied.newRole, "$applied")
            assertEquals(Role.WORK, d.role(item))
            assertEquals(
                1,
                d.raw
                    .resourceLeaseRepository()
                    .findActiveForItem(item.id)
                    .size,
                "the lease is taken in the same advance"
            )
            assertEquals(
                3,
                d.rig
                    .rowsAfter(mark)
                    .rejections()
                    .size,
                "a successful advance records no rejection row"
            )
            assertEquals(
                1,
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .size
            )
        }

    @Test
    fun `S7 ownership is checked before the transition table`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("blocked and claimed", Role.BLOCKED, previousRole = Role.WORK)
            d.claim(item, "agent-a")
            val mark = d.rig.maxSeq()

            val byOther = d.advanceService(item, "start", actor = "agent-b").failure()
            val byHolder = d.advanceService(item, "start", actor = "agent-a").failure()

            assertIs<AdvanceFailure.OwnershipRejected>(byOther, "ownership answers before the table: $byOther")
            assertIs<AdvanceFailure.ResolutionFailed>(byHolder, "the holder reaches the table: $byHolder")
            assertEquals(
                0,
                d.rig
                    .rowsAfter(mark)
                    .rejections()
                    .size,
                "neither writes a rejection row"
            )
        }

    @Test
    fun `S7 an invalid transition writes no rejection row`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val done = d.item("done", Role.TERMINAL)
            val mark = d.rig.maxSeq()

            val r = d.advanceService(done, "start").failure()

            assertIs<AdvanceFailure.ResolutionFailed>(r, "$r")
            assertEquals(emptyList(), d.rig.rowsAfter(mark), "an invalid transition leaves no event rows at all")
        }

    // ---------------------------------------------------------------------------------------------
    // S8 -- the CHANGE set
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 C4 resume into work or review is dependency-gated, resume into queue is not`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            for (previous in listOf(Role.WORK, Role.REVIEW)) {
                val target = d.item("blocked-$previous", Role.BLOCKED, previousRole = previous)
                val unmet = d.item("unmet-$previous", Role.QUEUE)
                d.blocks(unmet, target)

                val r = d.advanceService(target, "resume").failure()

                assertIs<AdvanceFailure.ValidationFailed>(r, "$previous: $r")
                assertTrue(r.blockers.isNotEmpty(), "$previous: a dependency rejection: $r")
                assertEquals(Role.BLOCKED, d.role(target), "$previous: stays blocked")

                val free = d.item("free-$previous", Role.BLOCKED, previousRole = previous)
                d.advanceService(free, "resume").success()
                assertEquals(previous, d.role(free))
            }
            val toQueue = d.item("blocked-queue", Role.BLOCKED, previousRole = Role.QUEUE)
            d.blocks(d.item("unmet-queue", Role.QUEUE), toQueue)
            val resumedToQueue = d.advanceService(toQueue, "resume")
            assertIs<AdvanceOutcome.Success>(resumedToQueue, "resume that does not enter work or review is not gated: $resumedToQueue")
            assertEquals(Role.QUEUE, d.role(toQueue))
        }

    @Test
    fun `S8 C5 cancel from queue work review and blocked ignores unmet dependencies`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            for (role in listOf(Role.QUEUE, Role.WORK, Role.REVIEW, Role.BLOCKED)) {
                val item = d.item("cancel-$role", role, previousRole = if (role == Role.BLOCKED) Role.WORK else null)
                d.blocks(d.item("unmet-$role", Role.QUEUE), item)

                val r = d.advanceService(item, "cancel")

                assertIs<AdvanceOutcome.Success>(r, "$role: cancel is never dependency-gated: $r")
                assertEquals(Role.TERMINAL, d.role(item), "$role")
            }
        }

    @Test
    fun `S8 C6 a start cascade is suppressed by an unmet dependency on the parent while the child still starts`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val parent = d.item("parent", Role.QUEUE)
            val unmet = d.item("unmet", Role.QUEUE)
            d.blocks(unmet, parent)
            val child = d.item("child", Role.QUEUE, parent = parent)
            val mark = d.rig.maxSeq()

            val r = d.advanceService(child, "start").success()

            assertEquals(Role.WORK, d.role(child), "the child's own transition is unaffected: $r")
            val cascade = r.cascadeEvents.single()
            assertEquals(false, cascade.applied, "$cascade")
            assertEquals(true, cascade.dependencyBlocked, "$cascade")
            assertEquals(unmet.id, cascade.blockers[0].fromItemId)
            assertEquals(Role.QUEUE, d.role(parent), "the parent did not start")
            assertEquals(0, d.transitions(parent).size)
            assertEquals(
                1,
                d.rig
                    .rowsAfter(mark)
                    .transitioned()
                    .size,
                "a suppressed cascade records no item.transitioned row"
            )
        }

    @Test
    fun `S8 C7 a reopen cascade is suppressed by an unmet dependency on the parent while the child still reopens`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val parent = d.item("parent", Role.TERMINAL)
            val unmet = d.item("unmet", Role.QUEUE)
            d.blocks(unmet, parent)
            val child = d.item("child", Role.TERMINAL, parent = parent)

            val r = d.advanceService(child, "reopen").success()

            assertEquals(Role.QUEUE, d.role(child), "$r")
            val cascade = r.cascadeEvents.single()
            assertEquals(false, cascade.applied, "$cascade")
            assertEquals(true, cascade.dependencyBlocked, "$cascade")
            assertEquals(Role.TERMINAL, d.role(parent), "the parent stays terminal")
        }

    @Test
    fun `S8 C9 an unreadable blocker is reported with currentRole unknown and still blocks the start`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val target = d.item("target", Role.QUEUE)
            val ghost = d.item("ghost blocker", Role.QUEUE)
            d.blocks(ghost, target)
            // Delete the blocker row while leaving the edge in place: foreign keys are per connection, off here.
            rawExec(d.jdbcUrl, "PRAGMA foreign_keys=OFF", "DELETE FROM work_items WHERE title = 'ghost blocker'")
            assertEquals(null, d.raw.workItemRepository().getById(ghost.id), "fixture: the blocker row is really gone")

            val r =
                assertIs<AdvanceFailure.ValidationFailed>(
                    d.advanceService(target, "start").failure(),
                    "an unreadable blocker is unsatisfied (fail closed)"
                )

            assertEquals(null, r.blockers[0].currentRole, "an unreadable blocker has no current role (the wire's `unknown`): $r")
        }

    // ---------------------------------------------------------------------------------------------
    // S10 -- legacy messages verbatim
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S10 invalid transitions keep their legacy messages`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val cases =
                listOf(
                    Triple(Role.BLOCKED to Role.WORK, "start", "Cannot start: item is blocked. Use 'resume' trigger first"),
                    Triple(Role.TERMINAL to null, "start", "Cannot start: item is already terminal"),
                    Triple(Role.TERMINAL to null, "complete", "Cannot complete: item is already terminal"),
                    Triple(Role.BLOCKED to Role.WORK, "complete", "Cannot complete: item is blocked. Use 'resume' trigger first"),
                    Triple(Role.BLOCKED to Role.WORK, "block", "Cannot block: item is already blocked"),
                    Triple(Role.TERMINAL to null, "block", "Cannot block: item is already terminal"),
                    Triple(Role.QUEUE to null, "resume", "Cannot resume: item is not blocked (current role:"),
                    Triple(Role.BLOCKED to null, "resume", "Cannot resume: item is blocked but has no previousRole to restore"),
                    Triple(Role.TERMINAL to null, "cancel", "Cannot cancel: item is already terminal"),
                    Triple(Role.QUEUE to null, "reopen", "Cannot reopen: item is not terminal (current role:"),
                )
            for ((state, trigger, message) in cases) {
                val item = d.item("$trigger-${state.first}-${state.second}", state.first, previousRole = state.second)

                val r = assertIs<AdvanceFailure.ResolutionFailed>(d.advanceService(item, trigger).failure(), "$trigger from ${state.first}")

                assertTrue(r.message.contains(message), "$trigger from ${state.first}: expected '$message' in $r")
            }
        }

    @Test
    fun `S10 the dependency, gate and lease messages keep their legacy wording`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val two = d.item("two blockers", Role.QUEUE)
            d.blocks(d.item("b1", Role.QUEUE), two)
            d.blocks(d.item("b2", Role.QUEUE), two)
            val twoResult = assertIs<AdvanceFailure.ValidationFailed>(d.advanceService(two, "start").failure())
            assertTrue(twoResult.message.contains("2 blocking dependency(ies) not yet satisfied"), "$twoResult")

            val gated = d.item("gated", Role.QUEUE, type = "p11-gated")
            val gate = assertIs<AdvanceFailure.GateBlocked>(d.advanceService(gated, "start").failure())
            assertTrue(gate.message.contains("Gate check failed: required notes not filled for queue phase: spec"), "$gate")

            val leased = d.item("leased", Role.QUEUE, type = "p11-leased")
            d.holdLease(d.item("holder", Role.QUEUE))
            val lease = assertIs<AdvanceFailure.ResourceLeaseUnavailable>(d.advanceService(leased, "start").failure())
            assertTrue(
                lease.message.contains("Cannot enter work phase: resource(s) currently held by another work item: $P11_LEASE_KEY"),
                "$lease",
            )
        }

    @Test
    fun `S10 an unknown trigger string on the service lists the valid triggers`(): Unit =
        runBlocking {
            val provider = db.repositoryProvider()
            val item =
                provider.workItemRepository().create(
                    io.github.jpicklyk.mcptask.current.domain.model
                        .WorkItem(title = "t", role = Role.QUEUE)
                )
            val service =
                AdvanceService(
                    workItemRepository = provider.workItemRepository(),
                    dependencyRepository = provider.dependencyRepository(),
                    noteRepository = provider.noteRepository(),
                    schemaResolver = { null },
                    unitOfWork = db.unitOfWork(),
                    claimService = testClaimService(provider.workItemRepository(), null, db.unitOfWork())
                )

            val outcome = service.advance(item, "bogus", null, null, null)

            val failure = assertIs<AdvanceOutcome.Failure>(outcome).failure
            val resolution = assertIs<AdvanceFailure.ResolutionFailed>(failure)
            assertEquals(
                "Unknown trigger: 'bogus'. Valid triggers: start, complete, block, hold, resume, cancel, reopen",
                resolution.message
            )
        }

    // ---------------------------------------------------------------------------------------------
    // R23 (item 9536cf09) -- the SEAT gate through the shared AdvanceService, over the production composition and a real
    // SQLite database. Oracles: frozen task-scope "What to build" 3-4 and decisions D1-D6, the plan 4.1 R23 table
    // (OWNERSHIP, SEAT, TABLE, WARRANT, HOLD, DEPENDENCY, NOTE, LEASE). The caller facts are supplied the way the interface
    // layer would build them from a token (SeatCaller); the run state is real (ParitySeeder.run over the run store).
    // ---------------------------------------------------------------------------------------------

    private fun seatDriver(dir: Path) = P11Driver(EventLogRig.build(db.db, dir, SEAT_GATE_YAML))

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

    /** Drives [trigger] with [caller], asserts a SEAT refusal that applied nothing and wrote no event or rejection row. */
    private suspend fun P11Driver.refused(
        item: WorkItem,
        trigger: String,
        caller: SeatCaller,
        label: String,
        actor: String? = null
    ): ErrorDetail.SeatForbidden {
        val before = reload(item)
        val transitionsBefore = transitions(item).size
        val mark = rig.maxSeq()

        val failure = advanceService(item, trigger, actor, caller).failure()

        val seat = assertIs<AdvanceFailure.SeatForbidden>(failure, "$label: $failure")
        assertEquals(ErrorCode.SEAT_FORBIDDEN, seat.error.code, label)
        val after = reload(item)
        assertEquals(before.role, after.role, "$label: role unchanged")
        assertEquals(before.previousRole, after.previousRole, "$label: previous role unchanged")
        assertEquals(before.statusLabel, after.statusLabel, "$label: label unchanged")
        assertEquals(transitionsBefore, transitions(item).size, "$label: no transition row")
        assertEquals(emptyList(), rig.rowsAfter(mark), "$label: no event row and no rejection row")
        return assertIs<ErrorDetail.SeatForbidden>(seat.error.detail, label)
    }

    private suspend fun P11Driver.applied(
        item: WorkItem,
        trigger: String,
        caller: SeatCaller,
        expected: Role,
        label: String
    ) {
        val result = advanceService(item, trigger, null, caller).success()
        assertEquals(expected, result.newRole, "$label: $result")
        assertEquals(expected, role(item), "$label: stored role")
    }

    @Test
    fun `R23 S2 a seat token starts into the phase it enters, runs the default block and hold and completes only when declared`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val root = d.item("root")
            val toStart = d.item("to start", Role.QUEUE, type = "seat-task", parent = root)
            val toBlock = d.item("to block", Role.WORK, type = "seat-task", parent = root)
            val toHold = d.item("to hold", Role.WORK, type = "seat-task", parent = root)
            val toComplete = d.item("to complete", Role.WORK, type = "seat-task", parent = root)
            val run = seedRun(root, listOf(toStart, toBlock, toHold, toComplete))

            d.applied(toStart, "start", memberSeat("builder", run, toStart), Role.WORK, "builder start queue to work")
            d.applied(toBlock, "block", memberSeat("builder", run, toBlock), Role.BLOCKED, "builder block (default)")
            d.applied(toHold, "hold", memberSeat("builder", run, toHold), Role.BLOCKED, "builder hold (default)")
            d.applied(toComplete, "complete", memberSeat("checker", run, toComplete), Role.TERMINAL, "checker complete (declared)")
        }

    @Test
    fun `R23 S2 a seat token never completes or cancels unless it declares it, never starts into a phase it does not enter`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val root = d.item("root")
            val work = d.item("in work", Role.WORK, type = "seat-task", parent = root)
            val queued = d.item("queued", Role.QUEUE, type = "seat-task", parent = root)
            val blocked = d.item("blocked", Role.BLOCKED, type = "seat-task", parent = root, previousRole = Role.WORK)
            val done = d.item("done", Role.TERMINAL, type = "seat-task", parent = root)
            val run = seedRun(root, listOf(work, queued, blocked, done))

            d.refused(work, "complete", memberSeat("builder", run, work), "builder complete")
            d.refused(work, "cancel", memberSeat("builder", run, work), "builder cancel")
            d.refused(work, "cancel", memberSeat("checker", run, work), "checker cancel (only complete is declared)")
            d.refused(work, "block", memberSeat("checker", run, work), "checker block (a declared list replaces the default)")
            d.refused(work, "hold", memberSeat("silent", run, work), "a declared empty list allows nothing")
            d.refused(queued, "start", memberSeat("planner", run, queued), "planner start (it enters queue, not work)")
            d.refused(queued, "start", memberSeat("orchestrator", run, queued), "a seat that enters nothing starts nothing")
            d.refused(blocked, "resume", memberSeat("builder", run, blocked), "builder resume")
            d.refused(done, "reopen", memberSeat("builder", run, done), "builder reopen")

            // controls: the coordinator of the run does each of them, so every refusal above is the seat rule
            d.applied(work, "complete", coordinatorOf(run), Role.TERMINAL, "coordinator complete")
            d.applied(queued, "cancel", coordinatorOf(run), Role.TERMINAL, "coordinator cancel")
            d.applied(blocked, "resume", coordinatorOf(run), Role.WORK, "coordinator resume")
            d.applied(done, "reopen", coordinatorOf(run), Role.QUEUE, "coordinator reopen")
        }

    @Test
    fun `R23 S3 the coordinator token of the run starts, completes, cancels and reopens`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val root = d.item("root")
            val a = d.item("a", Role.QUEUE, type = "seat-task", parent = root)
            val b = d.item("b", Role.WORK, type = "seat-task", parent = root)
            val c = d.item("c", Role.QUEUE, type = "seat-task", parent = root)
            val e = d.item("e", Role.TERMINAL, type = "seat-task", parent = root)
            val run = seedRun(root, listOf(a, b, c, e))

            d.applied(a, "start", coordinatorOf(run), Role.WORK, "coordinator start")
            d.applied(b, "complete", coordinatorOf(run), Role.TERMINAL, "coordinator complete")
            d.applied(c, "cancel", coordinatorOf(run), Role.TERMINAL, "coordinator cancel")
            d.applied(e, "reopen", coordinatorOf(run), Role.QUEUE, "coordinator reopen")
        }

    @Test
    fun `R23 S4 items outside an open run, in a closed run or with a seat-less schema behave exactly as before`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val root = d.item("root")
            val otherRoot = d.item("other root")
            val anchor = d.item("anchor of the foreign run", Role.QUEUE, type = "seat-task", parent = otherRoot)
            val foreignRun = seedRun(otherRoot, listOf(anchor))
            val foreign = { item: WorkItem -> memberSeat("builder", foreignRun, item) }

            // the open run whose seat-aware member REFUSES a token-less agent: the fixture condition for every pass below
            val control = d.item("control member", Role.QUEUE, type = "seat-task", parent = root)
            val plainMember = d.item("plain member", Role.QUEUE, type = "plain-task", parent = root)
            val plainMember2 = d.item("plain member 2", Role.QUEUE, type = "plain-task", parent = root)
            val freeMember = d.item("schema-free member", Role.QUEUE, parent = root)
            val freeMember2 = d.item("schema-free member 2", Role.QUEUE, parent = root)
            val outside = d.item("outside", Role.QUEUE, type = "seat-task", parent = root)
            val outside2 = d.item("outside 2", Role.QUEUE, type = "seat-task", parent = root)
            val closedRoot = d.item("closed run root")
            val inClosed = d.item("in a closed run", Role.QUEUE, type = "seat-task", parent = closedRoot)
            val inClosed2 = d.item("in a closed run 2", Role.QUEUE, type = "seat-task", parent = closedRoot)
            seedRun(root, listOf(control, plainMember, plainMember2, freeMember, freeMember2))
            seedRun(closedRoot, listOf(inClosed, inClosed2), RunStatus.CLOSED)

            d.refused(control, "start", SeatCaller.NONE, "control: the seat-aware member of the open run")

            // token-less agent and a token of a different run: both pass where the SEAT gate is out of scope
            d.applied(plainMember, "start", SeatCaller.NONE, Role.WORK, "seat-less schema, token-less")
            d.applied(plainMember2, "start", foreign(plainMember2), Role.WORK, "seat-less schema, foreign token")
            d.applied(freeMember, "start", SeatCaller.NONE, Role.WORK, "schema-free member, token-less")
            d.applied(freeMember2, "start", foreign(freeMember2), Role.WORK, "schema-free member, foreign token")
            d.applied(outside, "start", SeatCaller.NONE, Role.WORK, "outside any run, token-less")
            d.applied(outside2, "start", foreign(outside2), Role.WORK, "outside any run, foreign token")
            d.applied(inClosed, "start", SeatCaller.NONE, Role.WORK, "member of a closed run, token-less")
            d.applied(inClosed2, "start", foreign(inClosed2), Role.WORK, "member of a closed run, foreign token")
        }

    @Test
    fun `R23 S4 previews and cascades ignore seats - a preview predicts the advance and a cascade into a member parent applies`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val root = d.item("root")
            val memberParent = d.item("member parent", Role.QUEUE, type = "seat-task", parent = root)
            val refusedParent = d.item("refused parent", Role.QUEUE, type = "seat-task", parent = root)
            val childOfMember = d.item("child of the member", Role.QUEUE, type = "plain-task", parent = memberParent)
            seedRun(root, listOf(memberParent, refusedParent))

            // the preview (items_read context) carries no caller, so it is not a refusal even where the advance would be
            val preview =
                d.rig
                    .callOk(ItemContextProbe(), "itemId" to JsonPrimitive(refusedParent.id.toString()))["data"]!!
                    .jsonObject["gateStatus"]!!
                    .jsonObject
            assertEquals(true, preview.flag("canAdvance"), "the preview does not apply the SEAT gate: $preview")
            d.refused(refusedParent, "start", SeatCaller.NONE, "control: the same item refuses the token-less advance")

            // the start cascade into the member parent applies (cascades pass no caller)
            d.advanceService(childOfMember, "start").success()
            assertEquals(Role.WORK, d.role(childOfMember))
            assertEquals(Role.WORK, d.role(memberParent), "the cascade started the member parent")
        }

    @Test
    fun `R23 S5 a human credential is exempt inside an open run`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val root = d.item("root")
            val toStart = d.item("to start", Role.QUEUE, type = "seat-task", parent = root)
            val toComplete = d.item("to complete", Role.WORK, type = "seat-task", parent = root)
            val agentTarget = d.item("agent target", Role.QUEUE, type = "seat-task", parent = root)
            seedRun(root, listOf(toStart, toComplete, agentTarget))

            d.refused(agentTarget, "start", SeatCaller.NONE, "control: a token-less agent on the same kind of item")
            d.applied(toStart, "start", humanCaller, Role.WORK, "human start")
            d.applied(toComplete, "complete", humanCaller, Role.TERMINAL, "human complete")
        }

    @Test
    fun `R23 S7 the refusal names the item, the presented seat, the action and the allowed seats`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val root = d.item("root")
            val mine = d.item("mine", Role.QUEUE, type = "seat-task", parent = root)
            val other = d.item("another member", Role.QUEUE, type = "seat-task", parent = root)
            val working = d.item("working", Role.WORK, type = "seat-task", parent = root)
            val run = seedRun(root, listOf(mine, other, working))
            val foreignRun = seedRun(d.item("other root"), emptyList())

            assertEquals(
                ErrorDetail.SeatForbidden(other.id, "builder", "start", listOf("coordinator", "builder")),
                d.refused(other, "start", memberSeat("builder", run, mine), "builder token used on another member"),
            )
            assertEquals(
                ErrorDetail.SeatForbidden(mine.id, "planner", "start", listOf("coordinator", "builder")),
                d.refused(mine, "start", memberSeat("planner", run, mine), "planner start into work"),
            )
            assertEquals(
                ErrorDetail.SeatForbidden(working.id, "builder", "complete", listOf("coordinator", "checker")),
                d.refused(working, "complete", memberSeat("builder", run, working), "builder complete"),
            )
            assertEquals(
                ErrorDetail.SeatForbidden(working.id, "builder", "cancel", listOf("coordinator")),
                d.refused(working, "cancel", memberSeat("builder", run, working), "builder cancel"),
            )
            assertEquals(
                ErrorDetail.SeatForbidden(mine.id, "coordinator", "start", listOf("coordinator", "builder")),
                d.refused(mine, "start", coordinatorOf(foreignRun), "the coordinator token of another run"),
            )
            assertEquals(
                ErrorDetail.SeatForbidden(mine.id, "builder", "start", listOf("coordinator", "builder")),
                d.refused(mine, "start", memberSeat("builder", foreignRun, mine), "a builder token of another run"),
            )

            // the same refusal replayed is the same refusal and still writes nothing
            val first = d.refused(mine, "start", memberSeat("planner", run, mine), "first")
            val replay = d.refused(mine, "start", memberSeat("planner", run, mine), "replay")
            assertEquals(first, replay)
        }

    @Test
    fun `R23 S8 a token-less agent is refused with a null domain seat on a member of an open run`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val root = d.item("root")
            val member = d.item("member", Role.QUEUE, type = "seat-task", parent = root)
            seedRun(root, listOf(member))

            val detail = d.refused(member, "start", SeatCaller.NONE, "token-less start")

            assertEquals(ErrorDetail.SeatForbidden(member.id, null, "start", listOf("coordinator", "builder")), detail)
            assertEquals(null, detail.seat, "the absent seat is null, not an empty string")
        }

    @Test
    fun `R23 S9 gate order - ownership before seat, seat before the table and the dependency gate`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val root = d.item("root")
            val claimed = d.item("claimed", Role.QUEUE, type = "seat-task", parent = root)
            val terminal = d.item("terminal", Role.TERMINAL, type = "seat-task", parent = root)
            val unmet = d.item("unmet", Role.QUEUE, type = "seat-task", parent = root)
            val blocker = d.item("blocker", Role.QUEUE, parent = root)
            d.blocks(blocker, unmet)
            d.claim(claimed, "agent-a")
            val run = seedRun(root, listOf(claimed, terminal, unmet))

            // a foreign claim wins over the seat refusal; the seat refusal returns once the claim holder asks
            assertIs<AdvanceFailure.OwnershipRejected>(
                d.advanceService(claimed, "start", actor = "agent-b", seatCaller = SeatCaller.NONE).failure(),
                "foreign claim and seat violation: ownership first",
            )
            assertIs<AdvanceFailure.OwnershipRejected>(
                d.advanceService(claimed, "start", actor = "agent-b", seatCaller = memberSeat("builder", run, claimed)).failure(),
                "foreign claim with a valid seat: ownership",
            )
            d.refused(claimed, "start", SeatCaller.NONE, "the claim holder without a token", actor = "agent-a")
            assertEquals(
                Role.WORK,
                d.advanceService(claimed, "start", actor = "agent-a", seatCaller = memberSeat("builder", run, claimed)).success().newRole,
                "control: the claim holder with the builder token",
            )

            // a seat violation on a terminal item answers before invalid_transition
            d.refused(terminal, "start", SeatCaller.NONE, "seat violation on a terminal item")
            assertIs<AdvanceFailure.ResolutionFailed>(
                d.advanceService(terminal, "start", seatCaller = coordinatorOf(run)).failure(),
                "control: the coordinator reaches the table",
            )

            // a seat violation answers before the dependency gate; with a valid seat the dependency gate answers
            d.refused(unmet, "start", SeatCaller.NONE, "seat violation on an item with an unmet dependency")
            val dependency = d.advanceService(unmet, "start", seatCaller = memberSeat("builder", run, unmet)).failure()
            assertIs<AdvanceFailure.ValidationFailed>(dependency, "valid seat, unmet dependency: $dependency")
        }

    @Test
    fun `R23 S12 a paused run enforces seats and a closed run does not`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val openRoot = d.item("open run root")
            val pausedRoot = d.item("paused run root")
            val closedRoot = d.item("closed run root")
            val inOpen = d.item("in an open run", Role.QUEUE, type = "seat-task", parent = openRoot)
            val inPaused = d.item("in a paused run", Role.QUEUE, type = "seat-task", parent = pausedRoot)
            val inClosed = d.item("in a closed run", Role.QUEUE, type = "seat-task", parent = closedRoot)
            seedRun(openRoot, listOf(inOpen))
            val paused = seedRun(pausedRoot, listOf(inPaused), RunStatus.PAUSED)
            seedRun(closedRoot, listOf(inClosed), RunStatus.CLOSED)

            d.refused(inOpen, "start", SeatCaller.NONE, "open run")
            d.refused(inPaused, "start", SeatCaller.NONE, "paused run (D1: a paused run still has live assignments)")
            d.applied(inPaused, "start", memberSeat("builder", paused, inPaused), Role.WORK, "paused run, the builder token")
            d.applied(inClosed, "start", SeatCaller.NONE, Role.WORK, "closed run: the member is outside any open run")
        }

    @Test
    fun `R23 S13 a token whose seat the pinned schema does not declare is refused`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = seatDriver(dir)
            val root = d.item("root")
            val member = d.item("member", Role.QUEUE, type = "seat-task", parent = root)
            val run = seedRun(root, listOf(member))

            assertEquals(
                "ghost",
                d.refused(member, "start", memberSeat("ghost", run, member), "an undeclared seat name").seat,
            )
            assertEquals(
                "Builder",
                d.refused(member, "start", memberSeat("Builder", run, member), "the seat name is case sensitive").seat,
            )
            d.applied(member, "start", memberSeat("builder", run, member), Role.WORK, "control: the declared seat")
        }
}

/** The seated and seat-less schemas the token-path tests run under (the item types SeatWorld seeds: seated-task, plain-task). */
private const val SEAT_ENFORCEMENT_WORLD_CONFIG = """
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
      - key: check-notes
        role: work
        required: false
        seat: checker
      - key: orch-notes
        role: work
        required: false
        seat: orchestrator
      - key: shared-notes
        role: work
        required: false
        seat: builder
        shared: true
  plain-task:
    notes: []
"""

/**
 * R23 (item 9536cf09) end to end with REAL seat tokens: the production dispatcher over a real SQLite database, one MCP client
 * and server pair, runs opened and seats assigned through the tools themselves. Oracles: frozen task-scope "What to build" 3-5
 * and decisions D1, D4-D10, AC2, AC4 and the plan 4.1 R23 table; error codes and the call_log columns `error_code`, `seat`, `host`
 * from the envelope spec and the call_log schema in the API reference. Every refusal is paired with the same call that succeeds
 * when the single input it turns on is changed (the right token, the owner, the item's own run).
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class SeatEnforcementTokenPathTest {
    private val clock = SettableClock(Instant.parse("2026-10-10T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private fun world(block: suspend (SeatWorld) -> Unit) =
        runBlocking {
            val w = SeatWorld.create(db.db, dir, clock, SEAT_ENFORCEMENT_WORLD_CONFIG)
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    private fun transition(
        item: WorkItem,
        trigger: String
    ) = """{"op":"transition","itemId":"${item.id}","trigger":"$trigger"}"""

    private suspend fun SeatWorld.advance(
        item: WorkItem,
        trigger: String,
        token: String?,
        by: ParityToken = orch
    ): Reply = ops("advance", transition(item, trigger), token = token, by = by)

    private suspend fun SeatWorld.member(title: String): WorkItem = rig.seed(title, parent = root, tags = "seated-task")

    private suspend fun SeatWorld.roleOf(item: WorkItem): Role = checkNotNull(rig.raw.workItemRepository().getById(item.id)).role!!

    private fun Reply.seatDetail(): JsonObject = checkNotNull(error()?.get("detail")?.jsonObject) { "no error detail: $this" }

    @Test
    fun `R23 S2 S3 S5 S7 S8 real tokens - a seat acts on its own item, the coordinator on any, a human on any, the rest are refused`() =
        world { w ->
            val own = w.m1
            val other = w.member("another member")
            val viaCoordinator = w.member("coordinator target")
            val viaHuman = w.member("human target")
            val run = w.open(members = listOf(own, other, viaCoordinator, viaHuman))
            val builder = w.assignOk(run, own, "builder").token
            val checker = w.assignOk(run, other, "checker").token

            // the builder token starts its own item into work
            val started = w.advance(own, "start", builder)
            assertTrue(started.ok(), started.toString())
            assertEquals("work", started.find("newRole"), started.toString())

            // the same token on another member: seat_forbidden naming itself, the action and who could do it; nothing applied
            val foreign = w.advance(other, "start", builder)
            assertEquals("seat_forbidden", foreign.code(), foreign.toString())
            val detail = foreign.seatDetail()
            assertEquals(other.id.toString(), detail["itemId"]?.jsonPrimitive?.content)
            assertEquals("builder", detail["seat"]?.jsonPrimitive?.content)
            assertEquals("start", detail["action"]?.jsonPrimitive?.content)
            assertEquals(listOf("coordinator", "builder"), detail["allowedSeats"]?.jsonArray?.map { it.jsonPrimitive.content })
            assertEquals(Role.QUEUE, w.roleOf(other), "nothing applied")

            // a token-less agent: the seat member is omitted
            val tokenless = w.advance(other, "start", null)
            assertEquals("seat_forbidden", tokenless.code(), tokenless.toString())
            assertFalse(
                "seat" in tokenless.seatDetail().keys,
                "seat is omitted without a token, never JSON null: ${tokenless.seatDetail()}"
            )
            assertEquals(Role.QUEUE, w.roleOf(other))

            // the checker token: start is not declared for it, complete is
            assertEquals("seat_forbidden", w.advance(other, "start", checker).code(), "checker does not enter work")
            val completed = w.advance(other, "complete", checker)
            assertTrue(completed.ok(), completed.toString())
            assertEquals("terminal", completed.find("newRole"))

            // the builder token never completes or cancels its own item
            assertEquals("seat_forbidden", w.advance(own, "complete", builder).code())
            assertEquals("seat_forbidden", w.advance(own, "cancel", builder).code())
            assertEquals(Role.WORK, w.roleOf(own))

            // the coordinator token and a human credential act on any member
            val byCoordinator = w.advance(viaCoordinator, "start", run.coordinatorToken)
            assertTrue(byCoordinator.ok(), byCoordinator.toString())
            val byHuman = w.advance(viaHuman, "start", null, by = w.human)
            assertTrue(byHuman.ok(), "a human credential with no token is exempt: $byHuman")
        }

    @Test
    fun `R23 S12 pause still enforces, end_seat kills the token but not the enforcement, close lifts it`() =
        world { w ->
            val a = w.m1
            val b = w.member("second member")
            val run = w.open(members = listOf(a, b))
            val builderA = w.assignOk(run, a, "builder")

            // pause is a human-only director op
            val paused = w.director("runs_write", """{"ops":[{"op":"pause","runId":"${run.runId}"}]}""", w.human)
            assertTrue(paused.ok(), paused.toString())
            assertEquals("seat_forbidden", w.advance(b, "start", null).code(), "a paused run still enforces")
            assertTrue(w.advance(a, "start", builderA.token).ok(), "the builder token works while the run is paused")

            assertTrue(w.endSeat(builderA.token).ok(), "fixture: the seat ended")
            assertEquals("unauthenticated", w.advance(a, "block", builderA.token).wholeCode(), "the ended token is dead")
            assertEquals("seat_forbidden", w.advance(a, "block", null).code(), "token-less is still refused after end_seat")

            assertTrue(w.close(run, "cancelled", run.coordinatorToken).ok(), "fixture: the run is closed")
            val afterClose = w.advance(b, "start", null)
            assertTrue(afterClose.ok(), "a closed run does not enforce: $afterClose")
        }

    @Test
    fun `R23 S14 every seat refusal is a call_log row with its error code, seat and host`() =
        world { w ->
            val mine = w.m1
            val other = w.member("other member")
            val run = w.open(members = listOf(mine, other))
            val builder = w.assignOk(run, mine, "builder").token

            // An atomic call answers the failing element's own code (McpCallLogFollowThroughTest: an atomic failure logs the
            // element's code), so the row of an atomic refusal carries `seat_forbidden` / `note_owned_by_other` itself.
            val atomic = ""","atomic":true"""
            val tokenless = w.ops("advance", transition(other, "start"), top = atomic)
            val wrongItem = w.ops("advance", transition(other, "start"), token = builder, top = atomic)
            val ownedByOther =
                w.ops(
                    "notes_write",
                    """{"op":"upsert","itemId":"${mine.id}","key":"check-notes","role":"work","body":"text"}""",
                    token = builder,
                    top = atomic
                )
            assertEquals("seat_forbidden", tokenless.wholeCode(), tokenless.toString())
            assertEquals("seat_forbidden", wrongItem.wholeCode(), wrongItem.toString())
            assertEquals("note_owned_by_other", ownedByOther.wholeCode(), ownedByOther.toString())

            val tokenlessRow = w.callLogRowOf(tokenless)
            assertEquals("seat_forbidden", tokenlessRow["error_code"])
            assertNull(tokenlessRow["seat"], "no token, no seat")
            assertEquals("seat-client", tokenlessRow["host"])
            val wrongItemRow = w.callLogRowOf(wrongItem)
            assertEquals("seat_forbidden", wrongItemRow["error_code"])
            assertEquals("builder", wrongItemRow["seat"])
            assertEquals("seat-client", wrongItemRow["host"])
            val noteRow = w.callLogRowOf(ownedByOther)
            assertEquals("note_owned_by_other", noteRow["error_code"])
            assertEquals("builder", noteRow["seat"])

            // a non-atomic refusal is a row too, with the same seat and host (its error_code is the batch's own code)
            val batched = w.advance(other, "start", builder)
            assertEquals("seat_forbidden", batched.code(), batched.toString())
            val batchedRow = w.callLogRowOf(batched)
            assertEquals("builder", batchedRow["seat"])
            assertEquals("seat-client", batchedRow["host"])
            assertNotNull(batchedRow["error_code"], "a refused call is logged with an error code")

            // control: the accepted call under the same token is a row with no error
            val ok = w.advance(mine, "start", builder)
            assertTrue(ok.ok(), ok.toString())
            assertNull(w.callLogRowOf(ok)["error_code"])
        }

    @Test
    fun `R23 S6 S10 rule 2 with real tokens - own and unowned keys pass, another seat key is note_owned_by_other on a first write`() =
        world { w ->
            val item = w.m1
            val outside = w.d
            val run = w.open(members = listOf(item))
            val builder = w.assignOk(run, item, "builder").token
            val coordinator = run.coordinatorToken

            // the builder: its own key, a key no seat owns, a shared key
            assertTrue(w.noteUpsert(item, "build-notes", builder).ok(), "builder writes its own key")
            assertTrue(w.noteUpsert(item, "scratch", builder).ok(), "builder writes an unowned key")
            assertTrue(w.noteUpsert(item, "shared-notes", builder).ok(), "a shared key")

            // the builder on the checker's key: refused on a FIRST write, naming the owner seat, nothing stored
            val first = w.noteUpsert(item, "check-notes", builder)
            assertEquals("note_owned_by_other", first.code(), first.toString())
            assertEquals("checker", first.find("ownerSeat", first.error()), first.toString())
            assertNull(w.noteRow(item, "check-notes"), "nothing was written")

            // the coordinator: the orchestrator's key and an unowned key, never another seat's
            assertTrue(w.noteUpsert(item, "orch-notes", coordinator).ok(), "the coordinator writes the orchestrator's key")
            assertTrue(w.noteUpsert(item, "coordinator-scratch", coordinator).ok(), "the coordinator writes an unowned key")
            val coordinatorOnSeat = w.noteUpsert(item, "build-notes", coordinator)
            assertEquals("note_owned_by_other", coordinatorOnSeat.code(), coordinatorOnSeat.toString())
            assertEquals("builder", coordinatorOnSeat.find("ownerSeat", coordinatorOnSeat.error()))

            // a token-less agent, upsert and delete, on a note that exists unowned (rule 3 alone would allow it)
            w.legacyNote(item, "check-notes")
            val tokenlessUpsert = w.noteUpsert(item, "check-notes", null)
            assertEquals("note_owned_by_other", tokenlessUpsert.code(), tokenlessUpsert.toString())
            assertEquals("checker", tokenlessUpsert.find("ownerSeat", tokenlessUpsert.error()))
            val tokenlessDelete = w.ops("notes_write", """{"op":"delete","itemId":"${item.id}","key":"check-notes"}""", token = null)
            assertEquals("note_owned_by_other", tokenlessDelete.code(), tokenlessDelete.toString())
            assertEquals("3.x body", w.noteRow(item, "check-notes")?.get("body"), "the unowned note is intact")

            // an ADMIN takeover without a token hits rule 2 first and never reaches the transfer
            val takeover = w.noteUpsert(item, "check-notes", null, extra = ""","takeover":true""", by = w.admin)
            assertEquals("note_owned_by_other", takeover.code(), takeover.toString())
            assertEquals("3.x body", w.noteRow(item, "check-notes")?.get("body"))

            // humans are exempt from rule 2 (rule 3 sees the unowned note and allows it)
            assertTrue(w.noteUpsert(item, "check-notes", null, body = "by a human", by = w.human).ok(), "human writes a seat key")

            // outside the run rule 2 is out of scope
            assertTrue(w.noteUpsert(outside, "build-notes", null).ok(), "an item outside any open run: phase 2 behaviour")
        }

    @Test
    fun `R23 probe an atomic batch with a seat refusal rolls back the permitted element, a plain batch keeps it`() =
        world { w ->
            val own = w.m1
            val other = w.member("another member")
            val plainOwn = w.member("second own item")
            val run = w.open(members = listOf(own, other, plainOwn))
            val builder = w.assignOk(run, own, "builder").token
            val builder2 = w.assignOk(run, plainOwn, "builder").token

            val atomic =
                w.ops(
                    "advance",
                    transition(own, "start"),
                    transition(other, "start"),
                    token = builder,
                    top = ""","atomic":true"""
                )
            assertEquals("seat_forbidden", atomic.wholeCode(), atomic.toString())
            assertEquals(Role.QUEUE, w.roleOf(own), "the permitted first element was rolled back")
            assertEquals(Role.QUEUE, w.roleOf(other))

            // control: the same two elements without atomic: the first applies, the second is refused on its own
            val plain = w.ops("advance", transition(plainOwn, "start"), transition(other, "start"), token = builder2)
            assertEquals("partial_failure", plain.wholeCode(), plain.toString())
            assertEquals(Role.WORK, w.roleOf(plainOwn), "the permitted element applied")
            assertEquals(Role.QUEUE, w.roleOf(other), "the refused element did not")
            assertEquals("seat_forbidden", plain.code(1), plain.toString())
        }

    @Test
    fun `R23 probe a superseded attempt's token is dead and the new attempt's token acts`() =
        world { w ->
            val own = w.m1
            val run = w.open(members = listOf(own))
            val first = w.assignOk(run, own, "builder")
            val second = w.assignOk(run, own, "builder")
            assertEquals(1, first.attempt)
            assertEquals(2, second.attempt, "fixture: the re-assign superseded attempt 1")

            assertEquals("unauthenticated", w.advance(own, "start", first.token).wholeCode(), "the superseded token is dead")
            assertEquals(Role.QUEUE, w.roleOf(own), "nothing applied")
            val started = w.advance(own, "start", second.token)
            assertTrue(started.ok(), started.toString())
        }

    // ---------------------------------------------------------------------------------------------
    // Round 2, orchestrator Ruling 1 with real tokens (the seat rule is decisive when it admits) and Ruling 2 (the idempotency
    // store never holds a seat token). The 43-character token alphabet is base64url without padding.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `R23 R1b R1c R1e with real tokens - an admitted write changes attribution and never the owner, and adopts an unowned note`() =
        world { w ->
            val item = w.m1
            val run = w.open(members = listOf(item))
            val builder = w.assignOk(run, item, "builder").token
            val coordinator = run.coordinatorToken

            // R1b: a human overwrites a builder-owned key in the open run
            assertTrue(w.noteUpsert(item, "build-notes", builder, body = "by builder").ok())
            val owned = assertNotNull(w.noteRow(item, "build-notes"))
            assertEquals(w.orchId, owned["owner_id"], "fixture: the builder principal owns the key")
            assertEquals("builder", owned["owner_seat"], "fixture: the owner seat is builder")
            val human = w.noteUpsert(item, "build-notes", null, body = "by a human", by = w.human)
            assertTrue(human.ok(), human.toString())
            val afterHuman = assertNotNull(w.noteRow(item, "build-notes"))
            assertEquals("by a human", afterHuman["body"])
            assertEquals(2L, afterHuman["version"])
            assertEquals(w.orchId, afterHuman["owner_id"], "the human exemption never moves the owner")
            assertEquals("builder", afterHuman["owner_seat"], "nor the owner seat")
            assertEquals("api:seat-human", afterHuman["actor_id"], "attribution is the human")
            assertNull(afterHuman["seat"], "a token-less human attributes no seat")

            // R1c: the coordinator overwrites an orchestrator-seat key that a human created
            assertTrue(w.noteUpsert(item, "orch-notes", null, body = "human first", by = w.human).ok())
            assertEquals("api:seat-human", assertNotNull(w.noteRow(item, "orch-notes"))["owner_id"], "fixture: another principal owns it")
            val viaCoordinator = w.noteUpsert(item, "orch-notes", coordinator, body = "by the coordinator")
            assertTrue(viaCoordinator.ok(), viaCoordinator.toString())
            val afterCoordinator = assertNotNull(w.noteRow(item, "orch-notes"))
            assertEquals("by the coordinator", afterCoordinator["body"])
            assertEquals("api:seat-human", afterCoordinator["owner_id"], "the coordinator admission never moves the owner")
            assertNull(afterCoordinator["owner_seat"])
            assertEquals(w.orchId, afterCoordinator["actor_id"], "attribution changes to the coordinator principal")
            assertEquals("coordinator", afterCoordinator["seat"])
            // control: the coordinator is not admitted on the builder's key
            assertEquals("note_owned_by_other", w.noteUpsert(item, "build-notes", coordinator).code())

            // R1e: an admitted write on an unowned legacy note adopts it
            w.legacyNote(item, "check-notes")
            assertNull(assertNotNull(w.noteRow(item, "check-notes"))["owner_id"], "fixture: unowned")
            assertTrue(w.noteUpsert(item, "check-notes", null, body = "adopted by a human", by = w.human).ok())
            val adopted = assertNotNull(w.noteRow(item, "check-notes"))
            assertEquals("api:seat-human", adopted["owner_id"], "the admitted human adopts the unowned note")
            assertNull(adopted["owner_seat"])
            w.exec("DELETE FROM notes WHERE work_item_id = ? AND key = ?", item.id, "build-notes")
            w.legacyNote(item, "build-notes")
            assertTrue(w.noteUpsert(item, "build-notes", builder, body = "adopted by builder").ok())
            val adoptedBySeat = assertNotNull(w.noteRow(item, "build-notes"))
            assertEquals(w.orchId, adoptedBySeat["owner_id"])
            assertEquals("builder", adoptedBySeat["owner_seat"], "the owner pair is the caller pair")
        }

    @Test
    fun `R23 R1d R1f with real tokens - after the run closes, or on an item in no run, the same writes are note_owned_by_other`() =
        world { w ->
            val item = w.m1
            val outside = w.d
            val run = w.open(members = listOf(item))
            val builder = w.assignOk(run, item, "builder").token
            assertTrue(w.noteUpsert(item, "build-notes", builder, body = "by builder").ok())
            assertTrue(w.noteUpsert(outside, "build-notes", null, body = "outside, by the orchestrator principal").ok())

            // control: inside the open run the human write is admitted
            assertTrue(w.noteUpsert(item, "build-notes", null, body = "human, open run", by = w.human).ok())

            // R1f: a refusal beats an admin takeover (the owner stays)
            val takeover = w.noteUpsert(item, "build-notes", null, body = "taken", extra = ""","takeover":true""", by = w.admin)
            assertEquals("note_owned_by_other", takeover.code(), takeover.toString())
            assertEquals("human, open run", w.noteRow(item, "build-notes")?.get("body"))
            assertEquals(w.orchId, w.noteRow(item, "build-notes")?.get("owner_id"), "the owner stays after the refused takeover")

            // R1d: outside any run the human is a non-owner (rule 3)
            val onOutside = w.noteUpsert(outside, "build-notes", null, body = "human, no run", by = w.human)
            assertEquals("note_owned_by_other", onOutside.code(), onOutside.toString())
            assertEquals("outside, by the orchestrator principal", w.noteRow(outside, "build-notes")?.get("body"))

            // R1d: after the run closes the same human write on the same key is refused
            val closed = w.close(run, "cancelled", run.coordinatorToken)
            assertTrue(closed.ok(), closed.toString())
            val afterClose = w.noteUpsert(item, "build-notes", null, body = "human, closed run", by = w.human)
            assertEquals("note_owned_by_other", afterClose.code(), afterClose.toString())
            assertEquals("human, open run", w.noteRow(item, "build-notes")?.get("body"), "nothing was written after the close")
        }

    private val tokenShape = Regex("^[A-Za-z0-9_-]{43}$")

    private fun stringValues(element: JsonElement): List<String> =
        when (element) {
            is JsonObject -> element.values.flatMap { stringValues(it) }
            is JsonArray -> element.flatMap { stringValues(it) }
            is JsonPrimitive -> if (element.isString) listOf(element.content) else emptyList()
        }

    private fun SeatWorld.idempotencyStrings(): List<String> =
        rows("SELECT result_json FROM idempotency_records").flatMap { stringValues(Json.parseToJsonElement(it["result_json"] as String)) }

    @Test
    fun `R23 Ruling 2 a keyed open replay omits the coordinator token and says it was redacted - the store never holds a token`() =
        world { w ->
            val element = """{"op":"open","rootId":"${w.root.id}","items":[${w.ids(w.m1)}],"idempotencyKey":"open-key-r23"}"""

            val first = w.ops("runs_write", element)
            assertTrue(first.ok(), first.toString())
            val token = assertNotNull(first.find("coordinatorToken"), "the first call returns the token: $first")
            assertTrue(tokenShape.matches(token), "fixture: the issued token has the 43-character shape")
            assertNull(first.find("tokenRedacted"), "the first call has no tokenRedacted key: $first")

            val replay = w.ops("runs_write", element)
            assertTrue(replay.ok(), replay.toString())
            assertEquals("true", replay.find("replayed"), "fixture: the second call is a replay: $replay")
            assertEquals(first.find("runId"), replay.find("runId"), "the stored result is returned")
            assertNull(replay.find("coordinatorToken"), "a replay carries no coordinator token: $replay")
            assertEquals("true", replay.find("tokenRedacted"), "a replay says the token was redacted: $replay")
            assertFalse(token in replay.toString(), "the token string appears nowhere in the replay")

            val stored = w.rows("SELECT result_json FROM idempotency_records")
            assertTrue(stored.any { (it["result_json"] as String).contains(first.find("runId")!!) }, "fixture: the open's record is stored")
            assertFalse(stored.any { token in (it["result_json"] as String) }, "the idempotency table never holds the token")
            assertEquals(emptyList(), w.idempotencyStrings().filter { tokenShape.matches(it) }, "no 43-character token string is stored")
        }

    @Test
    fun `R23 Ruling 2 a keyed assign replay omits the seat token and says it was redacted - the store never holds a token`() =
        world { w ->
            val run = w.open(members = listOf(w.m1))

            val first = w.assign(run, w.m1, "builder", key = "assign-key-r23")
            assertTrue(first.reply.ok(), first.reply.toString())
            val token = assertNotNull(first.token, "the first call returns the seat token: ${first.reply}")
            assertTrue(tokenShape.matches(token), "fixture: the issued token has the 43-character shape")
            assertNull(first.reply.find("tokenRedacted"), "the first call has no tokenRedacted key: ${first.reply}")

            val replay = w.assign(run, w.m1, "builder", key = "assign-key-r23")
            assertTrue(replay.reply.ok(), replay.reply.toString())
            assertEquals("true", replay.reply.find("replayed"), "fixture: the second call is a replay: ${replay.reply}")
            assertEquals(first.assignmentId, replay.assignmentId, "the stored result is returned")
            assertNull(replay.token, "a replay carries no seat token: ${replay.reply}")
            assertEquals("true", replay.reply.find("tokenRedacted"), "a replay says the token was redacted: ${replay.reply}")
            assertFalse(token in replay.reply.toString(), "the token string appears nowhere in the replay")

            val stored = w.rows("SELECT result_json FROM idempotency_records")
            assertTrue(
                stored.any { (it["result_json"] as String).contains(first.assignmentId!!) },
                "fixture: the assign's record is stored"
            )
            assertFalse(stored.any { token in (it["result_json"] as String) }, "the idempotency table never holds the seat token")
            assertFalse(stored.any { run.coordinatorToken in (it["result_json"] as String) }, "nor the coordinator token")
            assertEquals(emptyList(), w.idempotencyStrings().filter { tokenShape.matches(it) }, "no 43-character token string is stored")

            // recovery: a re-assign supersedes and issues a fresh token (the replay does not)
            val fresh = w.assignOk(run, w.m1, "builder")
            assertNotNull(fresh.token)
            assertTrue(fresh.token != token, "a new assign issues a different token")
        }
}
