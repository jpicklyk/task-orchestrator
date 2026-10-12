package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.TestConfigResolver
import io.github.jpicklyk.mcptask.current.test.actorArg
import io.github.jpicklyk.mcptask.current.test.advanceTool
import io.github.jpicklyk.mcptask.current.test.claimOp
import io.github.jpicklyk.mcptask.current.test.claimSelectOp
import io.github.jpicklyk.mcptask.current.test.errorCode
import io.github.jpicklyk.mcptask.current.test.putNote
import io.github.jpicklyk.mcptask.current.test.releaseOp
import io.github.jpicklyk.mcptask.current.test.results
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.transitionOp
import io.github.jpicklyk.mcptask.current.test.upsertNoteVia
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item d1cccd1a) over the real tool entry points and a real SQLite database:
 * per-element idempotency of the keyed tools. Q32 port: the 3.x `requestId` of advance_item / claim_item (recorded as
 * `"${requestId}:${index}"`) is gone; the `advance` tool keys each element with its own `idempotencyKey`, so the
 * scenarios below key every element and resend the identical call.
 *
 * Oracles: plan section 3.9 l.262-285 and the frozen task-scope note: a keyed element is recorded as
 * `"${requestId}:${index}"` under the trusted actor id and `mcp.<tool>[.<operation>]`; a replay returns the element's
 * stored result with `"replayed": true` (single-element tools: `data.replayed: true`); the same key with a changed
 * element is the per-element failure `idempotency_mismatch` and writes nothing; state and transient failures
 * (gate_blocked ...) are NOT recorded so a retry with the same key executes; unkeyed calls (no requestId, or no
 * trusted actor id) never create a record. The call shapes come from the pre-existing tool tests
 * (IdempotencyToolsTest at the base commit, AdvanceItemToolErrorCodeTest, ClaimItemToolRealStateTest,
 * and the removed manage_items tests).
 *
 * Record counts are read with raw JDBC from idempotency_records, so they are independent of the service API.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class KeyedElementToolsTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val db get() = sqlite.db
    private val items get() = db.repositoryProvider().workItemRepository()

    private val gateEntries: List<NoteSchemaEntry> =
        listOf(
            NoteSchemaEntry(
                key = "acceptance-criteria",
                role = Role.QUEUE,
                required = true,
                description = "Acceptance criteria for this task",
                guidance = "List each criterion as a bullet point"
            )
        )

    private val gateSchema: EffectiveConfigResolver
        get() = TestConfigResolver.forSchemas(mapOf("feature-task" to gateEntries))

    private fun context(
        schema: EffectiveConfigResolver? = null,
        policy: DegradedModePolicy? = null
    ): ToolExecutionContext =
        when {
            schema != null ->
                ToolExecutionContext(
                    repositoryProvider = db.repositoryProvider(),
                    configResolver = schema,
                    unitOfWork = db.unitOfWork()
                )
            policy != null ->
                ToolExecutionContext(
                    repositoryProvider = db.repositoryProvider(),
                    degradedModePolicy = policy,
                    unitOfWork = db.unitOfWork()
                )
            else -> ToolExecutionContext(repositoryProvider = db.repositoryProvider(), unitOfWork = db.unitOfWork())
        }

    private fun actor(id: String = "agent-1") =
        buildJsonObject {
            put("id", id)
            put("kind", "subagent")
        }

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun rawInt(sql: String): Int =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun records(): Int = db.idempotencyRecordCount()

    private fun itemRows(): Int = rawInt("SELECT count(*) FROM work_items")

    private fun data(result: JsonElement): JsonObject = (result as JsonObject)["data"] as JsonObject

    private fun JsonObject.isReplayed(): Boolean = this["replayed"]?.jsonPrimitive?.booleanOrNull == true

    private fun elements(
        result: JsonElement,
        key: String
    ): List<JsonObject> = data(result)[key]!!.jsonArray.map { it.jsonObject }

    private suspend fun queueItem(
        title: String,
        tags: String? = null
    ): WorkItem =
        items.create(WorkItem(title = title, role = Role.QUEUE, depth = 0, tags = tags, type = tags?.substringBefore(",")?.trim()))

    private fun transition(
        itemId: UUID,
        trigger: String,
        key: String
    ) = transitionOp(itemId, trigger, "idempotencyKey" to JsonPrimitive(key))

    private fun keyed(key: String) = "idempotencyKey" to JsonPrimitive(key)

    private suspend fun fillAcceptanceNote(
        itemId: UUID,
        context: ToolExecutionContext
    ) {
        context.putNote(itemId, "acceptance-criteria", "queue", "- the criteria")
    }

    // ---------------------------------------------------------------- S1

    // ---------------------------------------------------------------- S6

    @Test
    fun `S6 notes_write upsert with the same key and a different body is a mismatch and keeps the stored body`(): Unit =
        runBlocking {
            val ctx = context()
            val itemId = queueItem("Note host").id
            val key = UUID.randomUUID().toString()

            suspend fun upsert(body: String) =
                ctx.upsertNoteVia(itemId, "approach", "work", body, actor = actorArg("agent-1"), idempotencyKey = key)

            upsert("First body")
            assertEquals(1, records())

            val replay = upsert("First body")
            assertTrue("\"replayed\":true" in replay.toString(), "an identical retry replays: $replay")

            val mismatch = upsert("DIFFERENT body")
            assertTrue("idempotency_mismatch" in mismatch.toString(), "a changed body is a mismatch: $mismatch")
            val notes = ctx.noteRepository().findByItemId(itemId)
            assertEquals(1, notes.size)
            assertEquals("First body", notes.single().body, "the mismatched retry must not overwrite the note")
        }

    // ---------------------------------------------------------------- S2 / S7

    @Test
    fun `S2 advance with one ok and one gate-blocked transition records only the ok one, and the retry executes the blocked one`(): Unit =
        runBlocking {
            val ctx = context(schema = gateSchema)
            val free = queueItem("Free item") // no tags: the schema declares no gate for it
            val gated = queueItem("Gated item", tags = "feature-task")
            val key = UUID.randomUUID().toString()

            suspend fun call() =
                ctx.advanceTool(transition(free.id, "start", "$key:0"), transition(gated.id, "start", "$key:1"), actor = actor())

            val first = call()
            val firstRes = first.results()
            assertEquals("true", firstRes[0]["ok"]!!.jsonPrimitive.content)
            assertEquals("false", firstRes[1]["ok"]!!.jsonPrimitive.content, "attempt 1 of the gated item must be blocked: $first")
            assertEquals("gate_blocked", firstRes[1].errorCode(), "blocked for the gate, not another reason: $first")
            assertEquals(1, records(), "only the successful element is recorded")
            assertEquals(1, rawInt("SELECT count(*) FROM events WHERE type = 'item.transitioned'"))

            fillAcceptanceNote(gated.id, ctx)

            val second = call()
            val secondRes = second.results()
            assertTrue(secondRes[0].isReplayed(), "the recorded element replays: $second")
            assertEquals("true", secondRes[0]["ok"]!!.jsonPrimitive.content)
            assertFalse(secondRes[1].isReplayed(), "the unrecorded element executes: $second")
            assertEquals("true", secondRes[1]["ok"]!!.jsonPrimitive.content, "attempt 2 succeeds now the note exists: $second")
            assertEquals(
                2,
                rawInt("SELECT count(*) FROM events WHERE type = 'item.transitioned'"),
                "the free item transitioned once, the gated item once"
            )
            assertEquals(2, records())
            assertEquals(Role.WORK, items.getById(gated.id)!!.role)
        }

    @Test
    fun `S7 a gate-blocked advance is not recorded and writes no transition, then the same key executes once healthy`(): Unit =
        runBlocking {
            val ctx = context(schema = gateSchema)
            val gated = queueItem("Gated item", tags = "feature-task")
            val key = UUID.randomUUID().toString()

            suspend fun call() = ctx.advanceTool(transition(gated.id, AdvanceTrigger.START, key), actor = actor())

            val blocked = call()
            val blockedEl = blocked.results().single()
            assertEquals("false", blockedEl["ok"]!!.jsonPrimitive.content, "attempt 1 must fail: $blocked")
            assertEquals("gate_blocked", blockedEl.errorCode(), "attempt 1 must fail on the gate: $blocked")
            assertEquals(0, records())
            assertEquals(0, rawInt("SELECT count(*) FROM events WHERE type = 'item.transitioned'"))
            assertEquals(Role.QUEUE, items.getById(gated.id)!!.role)

            fillAcceptanceNote(gated.id, ctx)
            val retry = call()
            val el = retry.results().single()
            assertEquals("true", el["ok"]!!.jsonPrimitive.content, "the retry with the same key must execute: $retry")
            assertFalse(el.isReplayed())
            assertEquals(1, records())
        }

    // ---------------------------------------------------------------- S5

    @Test
    fun `S5 a keyed claim replays under its key and a release under its own key executes separately`(): Unit =
        runBlocking {
            val ctx = context()
            val item = queueItem("Claimable")
            val key = UUID.randomUUID().toString()

            suspend fun claim() = ctx.advanceTool(claimOp(item.id, keyed(key)), actor = actor("agent-claimer")).results().single()

            val firstEntry = claim()
            assertEquals("true", firstEntry["ok"]!!.jsonPrimitive.content, "$firstEntry")

            val secondEntry = claim()
            assertTrue(secondEntry.isReplayed(), "the retried claim replays: $secondEntry")
            assertEquals(
                firstEntry,
                JsonObject(secondEntry.filterKeys { it != "replayed" }),
                "a replay returns the stored claim result unchanged"
            )
            assertEquals(1, records())

            // A 4.0 key names one element: the release is a different element, so it carries its own key.
            val release =
                ctx.advanceTool(releaseOp(item.id).let { JsonObject(it + keyed("$key-release")) }, actor = actor("agent-claimer"))
            assertFalse(release.results().single().isReplayed(), "a release is a different element: $release")
            assertNull(items.getById(item.id)!!.claimedBy, "the release executed and cleared the claim")
            assertEquals(2, records(), "claim and release are recorded separately")
        }

    // ---------------------------------------------------------------- S5 selector

    @Test
    fun `S5 a keyed selector claim replays the same itemId instead of claiming another item`(): Unit =
        runBlocking {
            val ctx = context()
            val a = queueItem("Selector first")
            val b = queueItem("Selector second")
            val key = UUID.randomUUID().toString()

            suspend fun claim() =
                ctx.advanceTool(claimSelectOp(buildJsonObject {}, keyed(key)), actor = actor("agent-selector")).results().single()

            val firstRes = claim()
            assertEquals("true", firstRes["ok"]!!.jsonPrimitive.content, "$firstRes")
            val claimedId = firstRes["itemId"]!!.jsonPrimitive.content
            assertEquals(1, records())

            val replay = claim()
            assertTrue(replay.isReplayed(), "the retried selector claim replays: $replay")
            assertEquals(claimedId, replay["itemId"]!!.jsonPrimitive.content, "the replay names the item claimed the first time")
            assertEquals(1, records(), "a replay writes no record")
            val claimedCount = listOf(a.id, b.id).count { items.getById(it)!!.claimedBy == "agent-selector" }
            assertEquals(1, claimedCount, "exactly one item is claimed: the replay must not claim a second one")
        }

    // ---------------------------------------------------------------- S8 tool level

    // ---------------------------------------------------------------- S10 tool level

    private val leaseSchema: EffectiveConfigResolver
        get() =
            TestConfigResolver.resolver(
                TestConfigResolver.document(mapOf("feature-task" to gateEntries)).copy(
                    traitResources =
                        mapOf("needs-staging-db" to listOf(ResourceRequirement("staging-db-credential", ResourceMode.EXCLUSIVE, 600))),
                ),
            )

    @Test
    fun `S10 a keyed advance that fails its gate leaves no lease, no transition and no record, and the retry executes`(): Unit =
        runBlocking {
            val ctx =
                ToolExecutionContext(
                    repositoryProvider = db.repositoryProvider(),
                    configResolver = leaseSchema,
                    unitOfWork = db.unitOfWork()
                )
            val item =
                items.create(
                    WorkItem(
                        title = "Leased and gated",
                        role = Role.QUEUE,
                        depth = 0,
                        tags = "feature-task",
                        type = "feature-task",
                        traits = listOf("needs-staging-db")
                    )
                )
            val leases = db.repositoryProvider().resourceLeaseRepository()
            val key = UUID.randomUUID().toString()

            suspend fun call() = ctx.advanceTool(transition(item.id, "start", key), actor = actor())

            val first = call()
            val firstEl = first.results().single()
            assertEquals("false", firstEl["ok"]!!.jsonPrimitive.content, "attempt 1 must fail: $first")
            assertEquals("gate_blocked", firstEl.errorCode(), "attempt 1 must fail on the note gate: $first")
            assertTrue(leases.findActiveByKeys(listOf("staging-db-credential")).isEmpty(), "no lease may survive the failed attempt")
            assertEquals(0, rawInt("SELECT count(*) FROM events WHERE type = 'item.transitioned'"), "no transition row may survive")
            assertEquals(0, records(), "the failed element must not be recorded as ok")
            assertEquals(Role.QUEUE, items.getById(item.id)!!.role)

            fillAcceptanceNote(item.id, ctx)
            val retry = call()
            val retryEl = retry.results().single()
            assertEquals("true", retryEl["ok"]!!.jsonPrimitive.content, "the retry with the same key must execute: $retry")
            assertFalse(retryEl.isReplayed())
            assertEquals(1, records())
            assertEquals(1, leases.findActiveByKeys(listOf("staging-db-credential")).size, "the successful retry acquires the lease")
        }

    // ---------------------------------------------------------------- S11 / S15

    // ---------------------------------------------------------------- S17

    // ---------------------------------------------------------------- probes

    private object AdvanceTrigger {
        const val START = "start"
    }
}
