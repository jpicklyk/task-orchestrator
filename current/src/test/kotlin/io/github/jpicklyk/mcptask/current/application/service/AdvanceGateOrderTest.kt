package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.rejections
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.transitioned
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.P11_LEASE_KEY
import io.github.jpicklyk.mcptask.current.test.failure
import io.github.jpicklyk.mcptask.current.test.rawExec
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.success
import io.github.jpicklyk.mcptask.current.test.testClaimService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent P11 tests (item 919d379e) for gate order, the CHANGE set and the legacy messages: S7, S8, S10.
 *
 * Oracles: task-scope 1.1 step 3 and DEFAULT_GATES order (OWNERSHIP, TABLE, WARRANT, HOLD, DEPENDENCY, NOTE, LEASE);
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
}
