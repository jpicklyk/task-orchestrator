package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.compound.CompleteTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.P11FaultRig
import io.github.jpicklyk.mcptask.current.test.P11FaultSchemaService
import io.github.jpicklyk.mcptask.current.test.P11_FAULT_LEASE_KEY
import io.github.jpicklyk.mcptask.current.test.arr
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent P11 tests (item 919d379e, round r2), gap 2: scenario S9 with NON-POISONING faults, on advance_item and
 * complete_tree (the REST surface is AdvanceRestNonPoisoningFaultTest).
 *
 * The existing S9 faults are SQL triggers; a failed SQL statement poisons the unit, so even an advance that swallowed the
 * exception could not commit. These faults are plain RuntimeExceptions thrown by a delegating store wrapper (a TransitionStore
 * that throws on the 2nd create() -- the cascade's row -- and a LeaseStore whose releaseAllForItem throws on the WORK exit),
 * which leave the unit healthy: only an advance that actually fails and rolls back leaves everything unchanged.
 *
 * Oracle: AC1 / task-scope 1.1 step 7 ("Any store fault (primary or cascade apply, release) rolls the whole unit back ->
 * ApplyFailed: no partial advance") and the api-reference advance_item errorCode `apply_failed`. Every test ends with a
 * same-fixture control (fault disarmed) that applies, so the "unchanged" assertions would fail if the fault were swallowed.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceNonPoisoningFaultTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun advanceParams(item: WorkItem): JsonObject =
        buildJsonObject {
            put(
                "transitions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemId", item.id.toString())
                            put("trigger", "complete")
                        },
                    )
                },
            )
        }

    private fun treeParams(item: WorkItem): JsonObject =
        buildJsonObject {
            put("itemIds", buildJsonArray { add(JsonPrimitive(item.id.toString())) })
            put("trigger", JsonPrimitive("complete"))
        }

    private fun JsonElement.data(): JsonObject = (this as JsonObject)["data"] as JsonObject

    private fun JsonElement.entry(): JsonObject = data().arr("results")[0].jsonObject

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
    // advance_item
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 advance_item a plain fault on the cascade's transition row fails the whole advance and changes nothing`(): Unit =
        runBlocking {
            val rig = P11FaultRig(db.repositoryProvider())
            val ctx = ToolExecutionContext(rig.provider, P11FaultSchemaService(), unitOfWork = db.unitOfWork())
            val (parent, child) = rig.fixture("tx-mcp")
            val before = rig.snapshot(parent, child)
            assertEquals(listOf(P11_FAULT_LEASE_KEY), before.leases[child.id], "fixture: the child holds the lease")
            rig.transitionStore.arm()

            val r = AdvanceItemTool().execute(advanceParams(child), ctx).entry()

            assertEquals(false, r.flag("applied"), "$r")
            assertEquals("apply_failed", r.text("errorCode"), "$r")
            assertEquals(2, rig.transitionStore.createCalls, "the fault point (the cascade's row, the 2nd create) was reached")
            assertWholeAdvanceRolledBack(rig, before, parent, child)

            rig.transitionStore.disarm()
            val ok = AdvanceItemTool().execute(advanceParams(child), ctx).entry()
            assertEquals(true, ok.flag("applied"), "control: $ok")
            assertControlApplies(rig, parent, child)
        }

    @Test
    fun `S9 advance_item a plain fault releasing the lease on work exit fails the whole advance and changes nothing`(): Unit =
        runBlocking {
            val rig = P11FaultRig(db.repositoryProvider())
            val ctx = ToolExecutionContext(rig.provider, P11FaultSchemaService(), unitOfWork = db.unitOfWork())
            val (parent, child) = rig.fixture("lease-mcp")
            val before = rig.snapshot(parent, child)
            rig.leaseStore.arm()

            val r = AdvanceItemTool().execute(advanceParams(child), ctx).entry()

            assertEquals(false, r.flag("applied"), "a release fault now fails the advance: $r")
            assertEquals("apply_failed", r.text("errorCode"), "$r")
            assertTrue(rig.leaseStore.releaseCalls >= 1, "the fault point (the release on the WORK exit) was reached")
            assertWholeAdvanceRolledBack(rig, before, parent, child)

            rig.leaseStore.disarm()
            val ok = AdvanceItemTool().execute(advanceParams(child), ctx).entry()
            assertEquals(true, ok.flag("applied"), "control: $ok")
            assertControlApplies(rig, parent, child)
        }

    // ---------------------------------------------------------------------------------------------
    // complete_tree
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 complete_tree a plain fault on the cascade's transition row fails the item and changes nothing`(): Unit =
        runBlocking {
            val rig = P11FaultRig(db.repositoryProvider())
            val ctx = ToolExecutionContext(rig.provider, P11FaultSchemaService(), unitOfWork = db.unitOfWork())
            val (parent, child) = rig.fixture("tx-tree")
            val before = rig.snapshot(parent, child)
            rig.transitionStore.arm()

            val result = CompleteTreeTool().execute(treeParams(child), ctx)

            val entry = result.entry()
            assertEquals(false, entry.flag("applied"), "$result")
            // Observed + reported, not asserted: complete_tree answers an apply failure as skipped with a "Failed to apply transition" reason and no errorCode.
            assertEquals(true, entry.flag("skipped"), "$result")
            assertTrue(entry.text("skippedReason").orEmpty().startsWith("Failed to apply transition"), "$result")
            assertEquals(
                0,
                result
                    .data()["summary"]!!
                    .jsonObject
                    .text("completed")
                    ?.toInt(),
                "nothing completed: $result"
            )
            assertEquals(2, rig.transitionStore.createCalls, "the fault point (the cascade's row, the 2nd create) was reached")
            assertWholeAdvanceRolledBack(rig, before, parent, child)

            rig.transitionStore.disarm()
            val ok = CompleteTreeTool().execute(treeParams(child), ctx).entry()
            assertEquals(true, ok.flag("applied"), "control: $ok")
            assertControlApplies(rig, parent, child)
        }

    @Test
    fun `S9 complete_tree a plain fault releasing the lease on work exit fails the item and changes nothing`(): Unit =
        runBlocking {
            val rig = P11FaultRig(db.repositoryProvider())
            val ctx = ToolExecutionContext(rig.provider, P11FaultSchemaService(), unitOfWork = db.unitOfWork())
            val (parent, child) = rig.fixture("lease-tree")
            val before = rig.snapshot(parent, child)
            rig.leaseStore.arm()

            val result = CompleteTreeTool().execute(treeParams(child), ctx)

            val entry = result.entry()
            assertEquals(false, entry.flag("applied"), "$result")
            // Observed + reported, not asserted: complete_tree answers an apply failure as skipped with a "Failed to apply transition" reason and no errorCode.
            assertEquals(true, entry.flag("skipped"), "$result")
            assertTrue(entry.text("skippedReason").orEmpty().startsWith("Failed to apply transition"), "$result")
            assertTrue(rig.leaseStore.releaseCalls >= 1, "the fault point (the release on the WORK exit) was reached")
            assertWholeAdvanceRolledBack(rig, before, parent, child)

            rig.leaseStore.disarm()
            val ok = CompleteTreeTool().execute(treeParams(child), ctx).entry()
            assertEquals(true, ok.flag("applied"), "control: $ok")
            assertControlApplies(rig, parent, child)
        }
}
