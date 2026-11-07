package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.run.RunOpenCommand
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.patchOf
import io.github.jpicklyk.mcptask.current.test.deleteOp
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.strings
import io.github.jpicklyk.mcptask.current.test.updateOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item db826b24) for refusing the re-parenting of a depth-0 root that has an
 * open or paused run.
 *
 * Oracles (frozen planning phase, no implementation read): the item's `diagnosis` acceptance criteria AC1-AC3 and its
 * fix precedence, the item's `test-plan` scenarios S1-S12, and the `RunService.open` / delete-refusal contract that a
 * run's rootId must stay a depth-0 item. The refusal is `invalid_request` with one violation on field `itemId`
 * (received = the root id) whose reason names the open runs; nothing is written.
 */
class RootReparentOpenRunsTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun rigOf(dir: Path) = EventLogRig.build(db.db, dir)

    private suspend fun EventLogRig.get(item: WorkItem): WorkItem =
        assertNotNull(raw.workItemRepository().getById(item.id), "item ${item.title} vanished")

    private suspend fun EventLogRig.openRun(
        root: WorkItem,
        vararg members: WorkItem
    ): Run = ctx.runService.open(RunOpenCommand(rootId = root.id, items = members.map { it.id })).orFail()

    private suspend fun EventLogRig.runOf(run: Run): Run = assertNotNull(raw.runStore().getById(run.id), "run vanished")

    private fun move(
        item: WorkItem,
        under: WorkItem
    ): JsonObject = updateOp(item.id, "parentId" to JsonPrimitive(under.id.toString()))

    private fun JsonObject.violations(index: Int = 0): List<JsonObject> =
        errorAt(index)["detail"]!!
            .jsonObject["fields"]!!
            .jsonArray
            .map { it.jsonObject }

    private fun JsonObject.code(index: Int = 0): String = errorAt(index)["code"]!!.jsonPrimitive.content

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun DomainError.fields() = assertIs<ErrorDetail.InvalidRequest>(detail, "detail of $this").fields

    private suspend fun assertUntouched(
        rig: EventLogRig,
        root: WorkItem,
        before: WorkItem,
        mark: Long
    ) {
        val now = rig.get(root)
        assertNull(now.parentId, "the root keeps no parent")
        assertEquals(0, now.depth)
        assertEquals(root.id, now.rootId)
        assertEquals(before.version, now.version, "the root version is unchanged")
        val rows = rig.rowsAfter(mark).filter { it.type == "item.reparented" || it.type == "item.pinned" }
        assertTrue(rows.isEmpty(), "a refused move writes no reparent or pin rows: $rows")
    }

    // ------------------------------------------------------------------ failure

    @Test
    fun `S1 a root with an open run is refused on a move under another root and nothing is written`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val r = rig.seed("R")
            val a = rig.seed("A")
            val k = rig.seed("K", parent = r, role = Role.WORK)
            val run = rig.openRun(r, k)
            val before = rig.get(r)
            val kBefore = rig.get(k)
            val mark = rig.maxSeq()

            val body = rig.itemsWrite(move(r, a))

            assertEquals("invalid_request", body.code())
            val violation = body.violations().single()
            assertEquals("itemId", violation.str("field"))
            assertTrue(violation.str("reason").contains("open run"), "the reason names the open runs: $violation")
            assertEquals(r.id.toString(), violation.str("received"))
            assertUntouched(rig, r, before, mark)
            val kAfter = rig.get(k)
            assertEquals(kBefore.rootId, kAfter.rootId)
            assertEquals(kBefore.version, kAfter.version)
            val stored = rig.runOf(run)
            assertEquals(RunStatus.OPEN, stored.status)
            assertEquals(r.id, stored.rootId)
        }

    @Test
    fun `S2 a root with a paused run is refused the same way and the run stays paused`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val r = rig.seed("R")
            val a = rig.seed("A")
            val k = rig.seed("K", parent = r, role = Role.WORK)
            val run = rig.openRun(r, k)
            rig.ctx.runService
                .pause(run.id)
                .orFail()
            val before = rig.get(r)
            val mark = rig.maxSeq()

            val body = rig.itemsWrite(move(r, a))

            assertEquals("invalid_request", body.code())
            val violation = body.violations().single()
            assertEquals("itemId", violation.str("field"))
            assertEquals(r.id.toString(), violation.str("received"))
            assertUntouched(rig, r, before, mark)
            val stored = rig.runOf(run)
            assertEquals(RunStatus.PAUSED, stored.status)
            assertEquals(r.id, stored.rootId)
        }

    @Test
    fun `S3 two open runs on the root are both counted in the reason`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val r = rig.seed("R")
            val a = rig.seed("A")
            val k1 = rig.seed("K1", parent = r, role = Role.WORK)
            val k2 = rig.seed("K2", parent = r, role = Role.WORK)
            rig.openRun(r, k1)
            rig.openRun(r, k2)

            val body = rig.itemsWrite(move(r, a))

            assertEquals("invalid_request", body.code())
            val reason = body.violations().single().str("reason")
            assertTrue(reason.contains("2"), "the reason names 2 open runs: $reason")
        }

    @Test
    fun `S4 a delete of the same root is still invalid_request on itemId alongside the refused move`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val r = rig.seed("R")
            val a = rig.seed("A")
            val k = rig.seed("K", parent = r, role = Role.WORK)
            rig.openRun(r, k)

            val moved = rig.itemsWrite(move(r, a))
            val deleted = rig.itemsWrite(deleteOp(r.id, recursive = true))

            assertEquals("invalid_request", moved.code())
            assertEquals("invalid_request", deleted.code())
            assertEquals("itemId", deleted.violations().single().str("field"))
            assertNotNull(rig.raw.workItemRepository().getById(r.id), "the root survives the refused delete")
        }

    @Test
    fun `S5 earlier refusals keep their codes on a root with an open run`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val r = rig.seed("R")
            val a = rig.seed("A")
            val k = rig.seed("K", parent = r, role = Role.WORK)
            rig.openRun(r, k)

            val unknownParent = rig.itemsWrite(updateOp(r.id, "parentId" to JsonPrimitive(UUID.randomUUID().toString())))
            val underOwnChild = rig.itemsWrite(move(r, k))
            val stale = rig.itemsWrite(updateOp(r.id, "parentId" to JsonPrimitive(a.id.toString()), "expectedVersion" to JsonPrimitive(99)))

            assertEquals("not_found", unknownParent.code())
            assertEquals("cycle_detected", underOwnChild.code())
            assertEquals("version_conflict", stale.code(), "a stale version beats the open-run refusal")
        }

    @Test
    fun `S6 a non-atomic batch refuses only the guarded root and an atomic batch writes nothing`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val r = rig.seed("R")
            val a = rig.seed("A")
            val free = rig.seed("F")
            val k = rig.seed("K", parent = r, role = Role.WORK)
            rig.openRun(r, k)

            val partial = rig.itemsWrite(move(r, a), move(free, a))

            assertEquals("invalid_request", partial.code(0))
            partial.okResult(1)
            assertEquals(a.id, rig.get(free).parentId, "the run-free root moved")
            assertNull(rig.get(r).parentId, "the guarded root did not")

            val free2 = rig.seed("F2")
            val atomic = rig.itemsWrite(move(r, a), move(free2, a), atomic = true)

            assertTrue(!atomic["ok"]!!.jsonPrimitive.boolean, "an atomic batch with a refused element fails: $atomic")
            assertNull(rig.get(r).parentId)
            assertNull(rig.get(free2).parentId, "nothing is written by an atomic batch with a refused element")
            assertEquals(0, rig.get(free2).depth)
        }

    // ------------------------------------------------------------------ happy

    @Test
    fun `S7 once the run is closed the same move succeeds and the subtree is re-rooted`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val r = rig.seed("R")
            val a = rig.seed("A")
            val k = rig.seed("K", parent = r, role = Role.WORK)
            val run = rig.openRun(r, k)
            assertEquals("invalid_request", rig.itemsWrite(move(r, a)).code())

            rig.ctx.runService
                .close(run.id, RunTerminalReason.CANCELLED)
                .orFail()
            val body = rig.itemsWrite(move(r, a))

            body.okResult()
            val moved = rig.get(r)
            assertEquals(a.id, moved.parentId)
            assertEquals(1, moved.depth)
            assertEquals(a.id, moved.rootId)
            assertEquals(a.id, rig.get(k).rootId, "the child follows the root")
        }

    @Test
    fun `S8 a root that never had a run moves under another root`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val r = rig.seed("R")
            val a = rig.seed("A")

            rig.itemsWrite(move(r, a)).okResult()

            val moved = rig.get(r)
            assertEquals(a.id, moved.parentId)
            assertEquals(a.id, moved.rootId)
        }

    // ------------------------------------------------------------------ edge

    @Test
    fun `S9 a root with an open run still takes non-parent patches and a clear of an absent parent`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val r = rig.seed("R")
            val k = rig.seed("K", parent = r, role = Role.WORK)
            rig.openRun(r, k)

            rig.itemsWrite(updateOp(r.id, "title" to JsonPrimitive("R renamed"))).okResult()
            rig.itemsWrite(updateOp(r.id, "clear" to strings("parentId"))).okResult()

            val now = rig.get(r)
            assertEquals("R renamed", now.title)
            assertNull(now.parentId)
            assertEquals(0, now.depth)
        }

    @Test
    fun `S10 an open run on the target root does not stop a run-free root from moving under it`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val r = rig.seed("R")
            val a = rig.seed("A")
            val ak = rig.seed("AK", parent = a, role = Role.WORK)
            rig.openRun(a, ak)

            rig.itemsWrite(move(r, a)).okResult()

            assertEquals(a.id, rig.get(r).parentId)
        }

    @Test
    fun `S11 a member moved under another child of the same root is not refused`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val r = rig.seed("R")
            val k = rig.seed("K", parent = r, role = Role.WORK)
            val other = rig.seed("other", parent = r)
            rig.openRun(r, k)

            rig.itemsWrite(move(k, other)).okResult()

            val moved = rig.get(k)
            assertEquals(other.id, moved.parentId)
            assertEquals(r.id, moved.rootId)
        }

    @Test
    fun `S12 the service patch refuses the same move with the same error before any write`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val r = rig.seed("R")
            val a = rig.seed("A")
            val k = rig.seed("K", parent = r, role = Role.WORK)
            rig.openRun(r, k)
            val before = rig.get(r)
            val mark = rig.maxSeq()

            val outcome = rig.ctx.itemCommandService.patch(patchOf(before, parent = ParentChange.MoveUnder(a.id)), caller = null)

            val error = assertIs<Outcome.Err>(outcome).error
            assertEquals(ErrorCode.INVALID_REQUEST, error.code)
            assertTrue(error.message.contains("re-parenting"), "the domain message mentions re-parenting: ${error.message}")
            val violation = error.fields().single()
            assertEquals("itemId", violation.field)
            assertEquals(r.id.toString(), violation.received)
            assertUntouched(rig, r, before, mark)
        }

    // ------------------------------------------------------------------ probes

    @Test
    fun `probe 8-hex prefix ids for the root and the parent reach the same refusal`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val r = rig.seed("R")
            val a = rig.seed("A")
            val k = rig.seed("K", parent = r, role = Role.WORK)
            rig.openRun(r, k)
            val op =
                JsonObject(
                    mapOf(
                        "op" to JsonPrimitive("update"),
                        "id" to JsonPrimitive(r.id.toString().take(8)),
                        "parentId" to JsonPrimitive(a.id.toString().take(8))
                    )
                )

            val body = rig.itemsWrite(op)

            assertEquals("invalid_request", body.code())
            assertNull(rig.get(r).parentId)
        }

    @Test
    fun `probe replaying the refused write leaves the root untouched`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = rigOf(dir)
            val r = rig.seed("R")
            val a = rig.seed("A")
            val k = rig.seed("K", parent = r, role = Role.WORK)
            rig.openRun(r, k)
            val before = rig.get(r)
            val mark = rig.maxSeq()

            val first = rig.itemsWrite(move(r, a))
            val second = rig.itemsWrite(move(r, a))

            assertEquals("invalid_request", first.code())
            assertEquals("invalid_request", second.code())
            assertUntouched(rig, r, before, mark)
        }
}
