package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.service.EventRecorder
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.ClaimItemTool
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.SqliteUnitOfWork
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P8 review finding 1: a `*.rejected` row must survive the unit it was recorded in. An idempotency-keyed element
 * (MCP `requestId` + an actor) runs in ONE unit that rolls back when the element fails, i.e. on the very rejection
 * the row records. Each path here records exactly one row per rejected attempt; a retry with the same key re-executes
 * (gate, claim and lease rejections are state failures, never stored as idempotency records) and records exactly one
 * more. A control whose unit COMMITS (an unkeyed advance; a store-level claim in a committing unit) also records
 * exactly one: no duplicate from the rollback fallback.
 *
 * Wiring mirrors production: the event-recording decorator over a real SQLite database and a [SqliteUnitOfWork]
 * sharing the decorator's recorder.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class KeyedRejectionRowsTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val raw get() = sqlite.repositoryProvider()

    private val schema: WorkItemSchemaService =
        object : WorkItemSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                if (tags.isNotEmpty()) {
                    listOf(NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, description = "spec"))
                } else {
                    null
                }

            override fun getTraitResources(traitName: String): List<ResourceRequirement> =
                if (traitName == "needs-db") listOf(ResourceRequirement("db-credential", ResourceMode.EXCLUSIVE, 600)) else emptyList()
        }

    private fun context(): ToolExecutionContext {
        val recorder = EventRecorder(raw.eventStore(), sqlite.db.clock)
        val decorated = EventPublishingRepositoryProvider(raw, recorder)
        val unitOfWork = SqliteUnitOfWork(sqlite.databaseManager, decorated, sqlite.db.clock, recorder)
        return ToolExecutionContext(repositoryProvider = decorated, noteSchemaService = schema, unitOfWork = unitOfWork)
    }

    private suspend fun rows(type: String) = raw.eventStore().readAfter(0L, null, 10_000).filter { it.type == type }

    private fun actor(id: String) =
        buildJsonObject {
            put("id", id)
            put("kind", "subagent")
        }

    private fun advance(
        itemId: UUID,
        requestId: String?,
    ): JsonObject =
        buildJsonObject {
            put(
                "transitions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemId", itemId.toString())
                            put("trigger", "start")
                            put("actor", actor("agent-1"))
                        },
                    )
                },
            )
            if (requestId != null) put("requestId", requestId)
        }

    private fun claim(
        itemId: UUID,
        requestId: String?,
    ): JsonObject =
        buildJsonObject {
            put("claims", buildJsonArray { add(buildJsonObject { put("itemId", itemId.toString()) }) })
            put("actor", actor("agent-2"))
            if (requestId != null) put("requestId", requestId)
        }

    private fun element(
        result: JsonElement,
        key: String,
    ): JsonObject =
        (result as JsonObject)["data"]!!
            .jsonObject[key]!!
            .jsonArray
            .single()
            .jsonObject

    @Test
    fun `keyed advance_item that is gate-blocked records exactly one transition rejected row per attempt`(): Unit =
        runBlocking {
            val ctx = context()
            val item = raw.workItemRepository().create(WorkItem(title = "Gated", role = Role.QUEUE, depth = 0, tags = "feature-task"))
            val key = UUID.randomUUID().toString()

            val first = AdvanceItemTool().execute(advance(item.id, key), ctx)
            assertEquals("gate_blocked", element(first, "results")["errorCode"]!!.jsonPrimitive.content, "must be gate-blocked: $first")
            val afterFirst = rows(DomainEvent.TRANSITION_REJECTED)
            assertEquals(1, afterFirst.size, "the keyed element rolled back; its rejection row must survive: $afterFirst")
            assertEquals(item.id, afterFirst.single().entityId)

            AdvanceItemTool().execute(advance(item.id, key), ctx)
            assertEquals(2, rows(DomainEvent.TRANSITION_REJECTED).size, "the retry with the same key re-executes and records one more")

            AdvanceItemTool().execute(advance(item.id, null), ctx)
            assertEquals(3, rows(DomainEvent.TRANSITION_REJECTED).size, "an unkeyed rejection records exactly one row")
        }

    @Test
    fun `keyed claim_item on a claimed item records exactly one claim rejected row per attempt`(): Unit =
        runBlocking {
            val ctx = context()
            val item = raw.workItemRepository().create(WorkItem(title = "Held", role = Role.QUEUE, depth = 0))
            raw.workItemRepository().claim(item.id, "agent-1", 900)
            val key = UUID.randomUUID().toString()

            val first = ClaimItemTool().execute(claim(item.id, key), ctx)
            assertEquals("agent-1", raw.workItemRepository().getById(item.id)!!.claimedBy, "the claim must be rejected: $first")
            assertEquals(1, rows(DomainEvent.CLAIM_REJECTED).size, "the keyed element rolled back; its rejection row must survive")

            ClaimItemTool().execute(claim(item.id, key), ctx)
            assertEquals(2, rows(DomainEvent.CLAIM_REJECTED).size, "the retry with the same key re-executes and records one more")

            // claim_item always keys its elements, so the committing path is driven at ClaimService: a unit that COMMITS
            // after the rejection keeps its own row and the rollback fallback never fires (no duplicate).
            val committed =
                ctx.unitOfWork.write("test.claim") {
                    Outcome.Ok(ctx.claimService.claim(item.id, "agent-3", 900).getOrNull()!!)
                }
            assertTrue(
                committed is Outcome.Ok && committed.value is ClaimResult.AlreadyClaimed,
                "the store claim must be rejected: $committed"
            )
            assertEquals(3, rows(DomainEvent.CLAIM_REJECTED).size, "a rejection in a committed unit records exactly one row")
        }

    @Test
    fun `keyed advance_item that loses a lease records exactly one lease rejected row per attempt`(): Unit =
        runBlocking {
            val ctx = context()
            val holder = raw.workItemRepository().create(WorkItem(title = "Holder", role = Role.QUEUE, depth = 0))
            raw.resourceLeaseRepository().acquireAll(holder.id, "agent-0", listOf("db-credential" to 600))
            val item =
                raw.workItemRepository().create(
                    WorkItem(title = "Wants the lease", role = Role.QUEUE, depth = 0, properties = """{"traits":["needs-db"]}"""),
                )
            val key = UUID.randomUUID().toString()

            val first = AdvanceItemTool().execute(advance(item.id, key), ctx)
            assertEquals(
                "resource_unavailable",
                element(first, "results")["errorCode"]!!.jsonPrimitive.content,
                "must lose the lease: $first",
            )
            val afterFirst = rows(DomainEvent.LEASE_REJECTED)
            assertEquals(1, afterFirst.size, "the keyed element rolled back; its rejection row must survive: $afterFirst")
            assertEquals(item.id, afterFirst.single().entityId)
            assertEquals(0, rows(DomainEvent.TRANSITION_REJECTED).size, "lease contention is not a transition rejection")

            AdvanceItemTool().execute(advance(item.id, key), ctx)
            assertEquals(2, rows(DomainEvent.LEASE_REJECTED).size, "the retry with the same key re-executes and records one more")

            AdvanceItemTool().execute(advance(item.id, null), ctx)
            assertEquals(3, rows(DomainEvent.LEASE_REJECTED).size, "an unkeyed rejection records exactly one row")
            assertEquals(Role.QUEUE, raw.workItemRepository().getById(item.id)!!.role)
        }
}
