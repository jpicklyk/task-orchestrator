package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.SqliteUnitOfWork
import io.github.jpicklyk.mcptask.current.infrastructure.time.SystemClock
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.test.P11FaultRig
import io.github.jpicklyk.mcptask.current.test.P11_FAULT_LEASE_KEY
import io.github.jpicklyk.mcptask.current.test.advanceTool
import io.github.jpicklyk.mcptask.current.test.arr
import io.github.jpicklyk.mcptask.current.test.completeTreeOp
import io.github.jpicklyk.mcptask.current.test.errorCode
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.onlyResult
import io.github.jpicklyk.mcptask.current.test.p11FaultResolver
import io.github.jpicklyk.mcptask.current.test.results
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.strings
import io.github.jpicklyk.mcptask.current.test.text
import io.github.jpicklyk.mcptask.current.test.transitionOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent P11 tests (item 919d379e, round r2), gap 2: scenario S9 with NON-POISONING faults, on the `advance` tool's
 * `transition` op (Q32: formerly the removed 3.x advance_item) and
 * `items_write` `complete_tree` (the REST surface is AdvanceRestNonPoisoningFaultTest). Q21 (c820b3d0) retargets the
 * complete_tree half to the 4.0 op: its applied transitions share the element's unit, so an apply fault fails the whole
 * element (rolled back) instead of one skipped entry.
 *
 * The existing S9 faults are SQL triggers; a failed SQL statement poisons the unit, so even an advance that swallowed the
 * exception could not commit. These faults are plain RuntimeExceptions thrown by a delegating store wrapper (a TransitionStore
 * that throws on the 2nd create() -- the cascade's row -- and a LeaseStore whose releaseAllForItem throws on the WORK exit),
 * which leave the unit healthy: only an advance that actually fails and rolls back leaves everything unchanged.
 *
 * Oracle: AC1 / task-scope 1.1 step 7 ("Any store fault (primary or cascade apply, release) rolls the whole unit back ->
 * ApplyFailed: no partial advance") and the catalog binding of ApplyFailed, `internal` (the removed 3.x `apply_failed`). Every test ends with a
 * same-fixture control (fault disarmed) that applies, so the "unchanged" assertions would fail if the fault were swallowed.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceNonPoisoningFaultTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private suspend fun advance(
        ctx: ToolExecutionContext,
        item: WorkItem
    ): JsonObject = ctx.advanceTool(transitionOp(item.id, "complete")).onlyResult()

    private suspend fun completeTree(
        ctx: ToolExecutionContext,
        item: WorkItem
    ): JsonObject =
        ctx.itemsWrite(
            completeTreeOp("itemIds" to strings(item.id.toString()), "trigger" to JsonPrimitive("complete")),
            profile = ToolProfile.ORCHESTRATION
        )

    /** The first `items[]` entry of a successful `complete_tree` element. */
    private fun JsonObject.treeEntry(): JsonObject = okResult(0).arr("items")[0].jsonObject

    private suspend fun assertWholeAdvanceRolledBack(
        rig: P11FaultRig,
        before: io.github.jpicklyk.mcptask.current.test.P11FaultSnapshot,
        parent: WorkItem,
        child: WorkItem,
    ) {
        val after = rig.snapshot(parent, child)
        assertEquals(before, after, "role/label/previousRole/roleChangedAt, rows, leases and the event table are all unchanged")
        assertEquals(Role.WORK, after.items.single { it.id == child.id }.role, "the PRIMARY rolled back with the failure")
        assertEquals(Role.WORK, after.items.single { it.id == parent.id }.role)
        assertEquals(0, after.transitionRows[child.id], "no transition row for the primary")
        assertEquals(0, after.transitionRows[parent.id], "no transition row for the cascade")
        assertEquals(0, rig.eventCount("item.transitioned"), "no item.transitioned event survives")
        assertEquals(listOf(P11_FAULT_LEASE_KEY), after.leases[child.id], "the child's lease is unchanged (still held)")
    }

    private suspend fun assertControlApplies(
        rig: P11FaultRig,
        parent: WorkItem,
        child: WorkItem,
    ) {
        val after = rig.snapshot(parent, child)
        assertEquals(
            Role.TERMINAL,
            after.items.single { it.id == child.id }.role,
            "control: the same advance applies once the fault is gone"
        )
        assertEquals(Role.TERMINAL, after.items.single { it.id == parent.id }.role, "control: and cascades the parent")
        assertEquals(1, after.transitionRows[child.id])
        assertEquals(1, after.transitionRows[parent.id])
        assertEquals(2, rig.eventCount("item.transitioned"), "control: one event per applied transition")
        assertEquals(emptyList(), after.leases[child.id], "control: the lease is released on the WORK exit")
    }

    // ---------------------------------------------------------------------------------------------
    // advance transition
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 advance transition a plain fault on the cascade's transition row fails the whole advance and changes nothing`(): Unit =
        runBlocking {
            val rig = P11FaultRig(db.repositoryProvider())
            val ctx =
                ToolExecutionContext(
                    rig.provider,
                    configResolver = p11FaultResolver(),
                    unitOfWork = SqliteUnitOfWork(db.databaseManager, rig.provider, SystemClock)
                )
            val (parent, child) = rig.fixture("tx-mcp")
            val before = rig.snapshot(parent, child)
            assertEquals(listOf(P11_FAULT_LEASE_KEY), before.leases[child.id], "fixture: the child holds the lease")
            rig.transitionStore.arm()

            val r = advance(ctx, child)

            assertEquals(false, r.flag("ok"), "$r")
            assertEquals("internal", r.errorCode(), "$r")
            assertEquals(2, rig.transitionStore.createCalls, "the fault point (the cascade's row, the 2nd create) was reached")
            assertWholeAdvanceRolledBack(rig, before, parent, child)

            rig.transitionStore.disarm()
            val ok = advance(ctx, child)
            assertEquals(true, ok.flag("ok"), "control: $ok")
            assertControlApplies(rig, parent, child)
        }

    @Test
    fun `S9 advance transition a plain fault releasing the lease on work exit fails the whole advance and changes nothing`(): Unit =
        runBlocking {
            val rig = P11FaultRig(db.repositoryProvider())
            val ctx =
                ToolExecutionContext(
                    rig.provider,
                    configResolver = p11FaultResolver(),
                    unitOfWork = SqliteUnitOfWork(db.databaseManager, rig.provider, SystemClock)
                )
            val (parent, child) = rig.fixture("lease-mcp")
            val before = rig.snapshot(parent, child)
            rig.leaseStore.arm()

            val r = advance(ctx, child)

            assertEquals(false, r.flag("ok"), "a release fault now fails the advance: $r")
            assertEquals("internal", r.errorCode(), "$r")
            assertTrue(rig.leaseStore.releaseCalls >= 1, "the fault point (the release on the WORK exit) was reached")
            assertWholeAdvanceRolledBack(rig, before, parent, child)

            rig.leaseStore.disarm()
            val ok = advance(ctx, child)
            assertEquals(true, ok.flag("ok"), "control: $ok")
            assertControlApplies(rig, parent, child)
        }

    // ---------------------------------------------------------------------------------------------
    // complete_tree
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 complete_tree a plain fault on the cascade's transition row fails the element and changes nothing`(): Unit =
        runBlocking {
            val rig = P11FaultRig(db.repositoryProvider())
            val ctx =
                ToolExecutionContext(
                    rig.provider,
                    configResolver = p11FaultResolver(),
                    unitOfWork = SqliteUnitOfWork(db.databaseManager, rig.provider, SystemClock)
                )
            val (parent, child) = rig.fixture("tx-tree")
            val before = rig.snapshot(parent, child)
            rig.transitionStore.arm()

            val result = completeTree(ctx, child)

            // The element fails as a whole: an apply fault is not a per-item outcome (the unit holds every applied item).
            assertEquals(false, result.results()[0].flag("ok"), "$result")
            assertEquals(2, rig.transitionStore.createCalls, "the fault point (the cascade's row, the 2nd create) was reached")
            assertWholeAdvanceRolledBack(rig, before, parent, child)

            rig.transitionStore.disarm()
            val ok = completeTree(ctx, child).treeEntry()
            assertEquals("applied", ok.text("outcome"), "control: $ok")
            assertControlApplies(rig, parent, child)
        }

    @Test
    fun `S9 complete_tree a plain fault releasing the lease on work exit fails the element and changes nothing`(): Unit =
        runBlocking {
            val rig = P11FaultRig(db.repositoryProvider())
            val ctx =
                ToolExecutionContext(
                    rig.provider,
                    configResolver = p11FaultResolver(),
                    unitOfWork = SqliteUnitOfWork(db.databaseManager, rig.provider, SystemClock)
                )
            val (parent, child) = rig.fixture("lease-tree")
            val before = rig.snapshot(parent, child)
            rig.leaseStore.arm()

            val result = completeTree(ctx, child)

            assertEquals(false, result.results()[0].flag("ok"), "$result")
            assertTrue(rig.leaseStore.releaseCalls >= 1, "the fault point (the release on the WORK exit) was reached")
            assertWholeAdvanceRolledBack(rig, before, parent, child)

            rig.leaseStore.disarm()
            val ok = completeTree(ctx, child).treeEntry()
            assertEquals("applied", ok.text("outcome"), "control: $ok")
            assertControlApplies(rig, parent, child)
        }
}
