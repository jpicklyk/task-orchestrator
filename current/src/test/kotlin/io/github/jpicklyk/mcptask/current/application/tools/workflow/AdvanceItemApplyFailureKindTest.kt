package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.VersionConflictException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import kotlin.test.assertEquals

/**
 * Independently authored for item `0c07190d` (error catalog adoption), review finding N3: the per-cause `errorKind` of
 * an `advance_item` `apply_failed` entry.
 *
 * Oracle: api-reference Error Envelope ("Kinds by cause"): an `advance_item` `apply_failed` takes its kind from its
 * cause -- a vanished row or a version conflict is `permanent`, a busy database `shedding`, anything else `transient`;
 * `errorCode` stays `apply_failed` in every case (advance_item codes are byte-identical to 3.x).
 *
 * Seam: [FaultingWorkItemRepository] wraps the real SQLite work-item store and fails `update()` for one item id, which
 * is the apply step of a primary transition (the same shape `AdvanceItemToolApplyFailureLeaseTest` forces on the
 * transition store). Four causes: a busy store fault, a store version conflict, a vanished row (update returns null),
 * and a plain fault. After each failure the item must still be in QUEUE.
 *
 * Red proof: the pre-P16 behaviour emitted no `errorKind` for apply_failed at all, and the kind mapping lives in the
 * apply-failure classification; making that classification return `transient` for every cause turns the busy,
 * version-conflict and vanished-row tests red while the generic one stays green.
 */
class AdvanceItemApplyFailureKindTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private class FaultingWorkItemRepository(
        private val delegate: WorkItemRepository,
        private val failFor: java.util.UUID,
        private val fault: (WorkItem) -> WorkItem?
    ) : WorkItemRepository by delegate {
        override suspend fun update(item: WorkItem): WorkItem? = if (item.id == failFor) fault(item) else delegate.update(item)
    }

    private class FaultingProvider(
        private val delegate: RepositoryProvider,
        private val items: WorkItemRepository
    ) : RepositoryProvider by delegate {
        override fun workItemRepository(): WorkItemRepository = items
    }

    private fun startParams(item: WorkItem): JsonObject =
        buildJsonObject {
            put(
                "transitions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemId", item.id.toString())
                            put("trigger", "start")
                        }
                    )
                }
            )
        }

    private fun assertApplyFailed(
        fault: (WorkItem) -> WorkItem?,
        expectedKind: String
    ) = runBlocking {
        val real = db.repositoryProvider()
        val item = real.workItemRepository().create(WorkItem(title = "apply failure kind", role = Role.QUEUE, depth = 0))
        val provider = FaultingProvider(real, FaultingWorkItemRepository(real.workItemRepository(), item.id, fault))
        val context = ToolExecutionContext(repositoryProvider = provider, unitOfWork = db.unitOfWork())

        val result = AdvanceItemTool().execute(startParams(item), context) as JsonObject
        val entry =
            result["data"]!!
                .jsonObject["results"]!!
                .jsonArray[0]
                .jsonObject

        assertEquals(false, entry["applied"]!!.jsonPrimitive.boolean, "actual: $entry")
        assertEquals("apply_failed", entry["errorCode"]!!.jsonPrimitive.content, "actual: $entry")
        assertEquals(expectedKind, entry["errorKind"]!!.jsonPrimitive.content, "actual: $entry")
        assertEquals(Role.QUEUE, real.workItemRepository().getById(item.id)!!.role, "a failed apply must not move the item")
    }

    @Test
    fun `N3 a busy store fault during apply is apply_failed with kind shedding`() =
        assertApplyFailed({ throw SQLiteException("busy during apply", SQLiteErrorCode.SQLITE_BUSY) }, "shedding")

    @Test
    fun `N3 a version conflict during apply is apply_failed with kind permanent`() =
        assertApplyFailed({ throw VersionConflictException(it.id, it.version, it.version + 1) }, "permanent")

    @Test
    fun `N3 a row that vanished during apply is apply_failed with kind permanent`() = assertApplyFailed({ null }, "permanent")

    @Test
    fun `N3 a generic fault during apply is apply_failed with kind transient`() =
        assertApplyFailed({ throw IllegalStateException("generic apply fault") }, "transient")
}
