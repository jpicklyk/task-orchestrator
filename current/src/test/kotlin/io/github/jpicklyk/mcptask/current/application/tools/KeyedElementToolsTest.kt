package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.compound.CompleteTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.ClaimItemTool
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item d1cccd1a) over the real MCP tool entry points (`tool.execute(params,
 * context)`) and a real SQLite database: per-element idempotency of the keyed tools.
 *
 * Oracles: plan section 3.9 l.262-285 and the frozen task-scope note: a keyed element is recorded as
 * `"${requestId}:${index}"` under the trusted actor id and `mcp.<tool>[.<operation>]`; a replay returns the element's
 * stored result with `"replayed": true` (single-element tools: `data.replayed: true`); the same key with a changed
 * element is the per-element failure `idempotency_mismatch` and writes nothing; state and transient failures
 * (gate_blocked ...) are NOT recorded so a retry with the same key executes; unkeyed calls (no requestId, or no
 * trusted actor id) never create a record. The call shapes come from the pre-existing tool tests
 * (IdempotencyToolsTest at the base commit, AdvanceItemToolErrorCodeTest, ClaimItemToolRealStateTest,
 * ManageItemsToolTest).
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

    private val gateSchema: NoteSchemaService =
        object : NoteSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                if (tags.isNotEmpty()) {
                    listOf(
                        NoteSchemaEntry(
                            key = "acceptance-criteria",
                            role = Role.QUEUE,
                            required = true,
                            description = "Acceptance criteria for this task",
                            guidance = "List each criterion as a bullet point"
                        )
                    )
                } else {
                    null
                }
        }

    private fun context(
        schema: NoteSchemaService? = null,
        policy: DegradedModePolicy? = null
    ): ToolExecutionContext =
        when {
            schema != null ->
                ToolExecutionContext(
                    repositoryProvider = db.repositoryProvider(),
                    noteSchemaService = schema,
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

    private fun createParams(
        requestId: String?,
        vararg titles: String,
        actorId: String? = "agent-1"
    ): JsonObject =
        buildJsonObject {
            put("operation", "create")
            put("items", buildJsonArray { titles.forEach { t -> add(buildJsonObject { put("title", t) }) } })
            if (requestId != null) put("requestId", requestId)
            if (actorId != null) put("actor", actor(actorId))
        }

    private fun JsonObject.isReplayed(): Boolean = this["replayed"]?.jsonPrimitive?.booleanOrNull == true

    private fun elements(
        result: JsonElement,
        key: String
    ): List<JsonObject> = data(result)[key]!!.jsonArray.map { it.jsonObject }

    private suspend fun queueItem(
        title: String,
        tags: String? = null
    ): WorkItem = items.create(WorkItem(title = title, role = Role.QUEUE, depth = 0, tags = tags))

    private fun transition(
        itemId: UUID,
        trigger: String
    ) = buildJsonObject {
        put("itemId", itemId.toString())
        put("trigger", trigger)
        put("actor", actor())
    }

    private fun advanceParams(
        requestId: String?,
        vararg transitions: JsonObject
    ): JsonObject =
        buildJsonObject {
            put("transitions", buildJsonArray { transitions.forEach { add(it) } })
            if (requestId != null) put("requestId", requestId)
        }

    private suspend fun fillAcceptanceNote(
        itemId: UUID,
        context: ToolExecutionContext
    ) {
        ManageNotesTool().execute(
            obj(
                "operation" to JsonPrimitive("upsert"),
                "notes" to
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("itemId", itemId.toString())
                                put("key", "acceptance-criteria")
                                put("role", "queue")
                                put("body", "- the criteria")
                            }
                        )
                    }
            ),
            context
        )
    }

    // ---------------------------------------------------------------- S1

    @Test
    fun `S1 manage_items create with two elements replays each element and creates nothing the second time`(): Unit =
        runBlocking {
            val ctx = context()
            val key = UUID.randomUUID().toString()
            val params = createParams(key, "First", "Second")

            val first = ManageItemsTool().execute(params, ctx)
            val firstEls = elements(first, "items")
            assertEquals(2, firstEls.size)
            assertTrue(firstEls.none { it.isReplayed() }, "a first execution must not claim to be a replay: $first")
            assertEquals(2, records(), "one record per keyed element")
            assertEquals(2, itemRows())

            val second = ManageItemsTool().execute(params, ctx)
            val secondEls = elements(second, "items")
            assertEquals(2, secondEls.size)
            assertTrue(secondEls.all { it.isReplayed() }, "every element replays: $second")
            assertEquals(firstEls.map { it["id"] }, secondEls.map { it["id"] }, "the replay returns the same item ids")
            assertEquals(2, itemRows(), "the replay must not create items")
            assertEquals(2, records())
        }

    @Test
    fun `S1 probe - two identical elements in one call both execute because their keys carry different indexes`(): Unit =
        runBlocking {
            val ctx = context()
            val key = UUID.randomUUID().toString()
            val params = createParams(key, "Twin", "Twin")

            ManageItemsTool().execute(params, ctx)
            assertEquals(2, itemRows(), "both identical elements execute on the first call")
            assertEquals(2, records())

            val again = ManageItemsTool().execute(params, ctx)
            assertTrue(elements(again, "items").all { it.isReplayed() })
            assertEquals(2, itemRows())
        }

    @Test
    fun `S1 probe - the same requestId in upper case is the same key`(): Unit =
        runBlocking {
            val ctx = context()
            val lower = UUID.randomUUID().toString()
            ManageItemsTool().execute(createParams(lower, "Cased"), ctx)
            val replay = ManageItemsTool().execute(createParams(lower.uppercase(), "Cased"), ctx)
            assertTrue(elements(replay, "items").single().isReplayed(), "an upper-case requestId must replay: $replay")
            assertEquals(1, itemRows())
            assertEquals(1, records())
        }

    @Test
    fun `S1 probe - a different requestId executes fresh`(): Unit =
        runBlocking {
            val ctx = context()
            ManageItemsTool().execute(createParams(UUID.randomUUID().toString(), "One"), ctx)
            val second = ManageItemsTool().execute(createParams(UUID.randomUUID().toString(), "One"), ctx)
            assertFalse(elements(second, "items").single().isReplayed())
            assertEquals(2, itemRows())
            assertEquals(2, records())
        }

    // ---------------------------------------------------------------- S6

    @Test
    fun `S6 a changed second element is an idempotency_mismatch failure while the unchanged first element replays`(): Unit =
        runBlocking {
            val ctx = context()
            val key = UUID.randomUUID().toString()
            val first = ManageItemsTool().execute(createParams(key, "Alpha", "Beta"), ctx)
            val firstIds = elements(first, "items").map { it["id"] }

            val second = ManageItemsTool().execute(createParams(key, "Alpha", "Beta CHANGED"), ctx)

            val replayed = elements(second, "items")
            assertEquals(1, replayed.size, "only the unchanged element is returned as an item: $second")
            assertTrue(replayed.single().isReplayed())
            assertEquals(firstIds[0], replayed.single()["id"])
            val failures = data(second)["failures"]!!.jsonArray
            assertEquals(1, failures.size, "exactly the changed element fails: $second")
            assertTrue("idempotency_mismatch" in failures.single().toString(), "failure must carry the mismatch code: $failures")
            assertEquals(2, itemRows(), "a mismatch must not create the changed element")
            assertEquals(2, records(), "a mismatch must not write a record")
        }

    @Test
    fun `S6 manage_notes upsert with the same key and a different body is a mismatch and keeps the stored body`(): Unit =
        runBlocking {
            val ctx = context()
            val itemId = queueItem("Note host").id
            val key = UUID.randomUUID().toString()

            fun upsert(body: String) =
                obj(
                    "operation" to JsonPrimitive("upsert"),
                    "notes" to
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", itemId.toString())
                                    put("key", "approach")
                                    put("role", "work")
                                    put("body", body)
                                }
                            )
                        },
                    "requestId" to JsonPrimitive(key),
                    "actor" to actor()
                )

            ManageNotesTool().execute(upsert("First body"), ctx)
            assertEquals(1, records())

            val replay = ManageNotesTool().execute(upsert("First body"), ctx)
            assertTrue("\"replayed\":true" in replay.toString(), "an identical retry replays: $replay")

            val mismatch = ManageNotesTool().execute(upsert("DIFFERENT body"), ctx)
            assertTrue("idempotency_mismatch" in mismatch.toString(), "a changed body is a mismatch: $mismatch")
            val notes = ctx.noteRepository().findByItemId(itemId)
            assertEquals(1, notes.size)
            assertEquals("First body", notes.single().body, "the mismatched retry must not overwrite the note")
        }

    @Test
    fun `S6 create_work_tree replays an identical retry and rejects a changed one as a mismatch`(): Unit =
        runBlocking {
            val ctx = context()
            val key = UUID.randomUUID().toString()

            fun treeParams(title: String) =
                obj(
                    "root" to buildJsonObject { put("title", title) },
                    "requestId" to JsonPrimitive(key),
                    "actor" to actor()
                )

            val first = CreateWorkTreeTool().execute(treeParams("Tree"), ctx)
            val rootId = (data(first)["root"] as JsonObject)["id"]!!.jsonPrimitive.content

            val replay = CreateWorkTreeTool().execute(treeParams("Tree"), ctx)
            assertEquals(true, data(replay)["replayed"]?.jsonPrimitive?.booleanOrNull, "single-element tools report data.replayed: $replay")
            assertEquals(rootId, (data(replay)["root"] as JsonObject)["id"]!!.jsonPrimitive.content)
            assertEquals(1, itemRows())

            val mismatch = CreateWorkTreeTool().execute(treeParams("Different title"), ctx)
            assertTrue("idempotency_mismatch" in mismatch.toString(), "a changed tree is a mismatch: $mismatch")
            assertEquals(1, itemRows(), "a mismatch must not create a second tree")
        }

    @Test
    fun `S6 manage_dependencies create replays an identical retry and creates one dependency`(): Unit =
        runBlocking {
            val ctx = context()
            val from = queueItem("From").id
            val to = queueItem("To").id
            val key = UUID.randomUUID().toString()
            val params =
                obj(
                    "operation" to JsonPrimitive("create"),
                    "dependencies" to
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("fromItemId", from.toString())
                                    put("toItemId", to.toString())
                                }
                            )
                        },
                    "requestId" to JsonPrimitive(key),
                    "actor" to actor()
                )
            ManageDependenciesTool().execute(params, ctx)
            val replay = ManageDependenciesTool().execute(params, ctx)

            assertEquals(true, data(replay)["replayed"]?.jsonPrimitive?.booleanOrNull, "single-element tools report data.replayed: $replay")
            assertEquals(1, ctx.dependencyRepository().findByFromItemId(from).size)
            assertEquals(1, records())
        }

    // ---------------------------------------------------------------- S2 / S7

    @Test
    fun `S2 advance with one ok and one gate-blocked transition records only the ok one, and the retry executes the blocked one`(): Unit =
        runBlocking {
            val ctx = context(schema = gateSchema)
            val free = queueItem("Free item") // no tags: the schema declares no gate for it
            val gated = queueItem("Gated item", tags = "feature-task")
            val key = UUID.randomUUID().toString()
            val params = advanceParams(key, transition(free.id, "start"), transition(gated.id, "start"))

            val first = AdvanceItemTool().execute(params, ctx)
            val firstRes = elements(first, "results")
            assertEquals("true", firstRes[0]["applied"]!!.jsonPrimitive.content)
            assertEquals("false", firstRes[1]["applied"]!!.jsonPrimitive.content, "attempt 1 of the gated item must be blocked: $first")
            assertEquals(
                "gate_blocked",
                firstRes[1]["errorCode"]!!.jsonPrimitive.content,
                "blocked for the gate, not another reason: $first"
            )
            assertEquals(1, records(), "only the successful element is recorded")
            assertEquals(1, rawInt("SELECT count(*) FROM role_transitions"))

            fillAcceptanceNote(gated.id, ctx)

            val second = AdvanceItemTool().execute(params, ctx)
            val secondRes = elements(second, "results")
            assertTrue(secondRes[0].isReplayed(), "the recorded element replays: $second")
            assertEquals("true", secondRes[0]["applied"]!!.jsonPrimitive.content)
            assertFalse(secondRes[1].isReplayed(), "the unrecorded element executes: $second")
            assertEquals("true", secondRes[1]["applied"]!!.jsonPrimitive.content, "attempt 2 succeeds now the note exists: $second")
            assertEquals(2, rawInt("SELECT count(*) FROM role_transitions"), "the free item transitioned once, the gated item once")
            assertEquals(2, records())
            assertEquals(Role.WORK, items.getById(gated.id)!!.role)
        }

    @Test
    fun `S7 a gate-blocked advance is not recorded and writes no transition, then the same key executes once healthy`(): Unit =
        runBlocking {
            val ctx = context(schema = gateSchema)
            val gated = queueItem("Gated item", tags = "feature-task")
            val key = UUID.randomUUID().toString()
            val params = advanceParams(key, transition(gated.id, AdvanceTrigger.START))

            val blocked = AdvanceItemTool().execute(params, ctx)
            val blockedEl = elements(blocked, "results").single()
            assertEquals("false", blockedEl["applied"]!!.jsonPrimitive.content, "attempt 1 must fail: $blocked")
            assertEquals("gate_blocked", blockedEl["errorCode"]!!.jsonPrimitive.content, "attempt 1 must fail on the gate: $blocked")
            assertEquals(0, records())
            assertEquals(0, rawInt("SELECT count(*) FROM role_transitions"))
            assertEquals(Role.QUEUE, items.getById(gated.id)!!.role)

            fillAcceptanceNote(gated.id, ctx)
            val retry = AdvanceItemTool().execute(params, ctx)
            val el = elements(retry, "results").single()
            assertEquals("true", el["applied"]!!.jsonPrimitive.content, "the retry with the same key must execute: $retry")
            assertFalse(el.isReplayed())
            assertEquals(1, records())
        }

    // ---------------------------------------------------------------- S5

    @Test
    fun `S5 claim_item replays a claim under its key and releases under the same requestId execute separately`(): Unit =
        runBlocking {
            val ctx = context()
            val item = queueItem("Claimable")
            val key = UUID.randomUUID().toString()
            val claimParams =
                obj(
                    "claims" to buildJsonArray { add(buildJsonObject { put("itemId", item.id.toString()) }) },
                    "actor" to actor("agent-claimer"),
                    "requestId" to JsonPrimitive(key)
                )

            val first = ClaimItemTool().execute(claimParams, ctx)
            val firstEntry = elements(first, "claimResults").single()
            assertEquals("success", firstEntry["outcome"]!!.jsonPrimitive.content)

            val second = ClaimItemTool().execute(claimParams, ctx)
            val secondEntry = elements(second, "claimResults").single()
            assertTrue(secondEntry.isReplayed(), "the retried claim replays: $second")
            assertEquals(
                firstEntry,
                JsonObject(secondEntry.filterKeys { it != "replayed" }),
                "a replay returns the stored claim result unchanged"
            )
            assertEquals(1, records())

            val releaseParams =
                obj(
                    "releases" to buildJsonArray { add(buildJsonObject { put("itemId", item.id.toString()) }) },
                    "actor" to actor("agent-claimer"),
                    "requestId" to JsonPrimitive(key)
                )
            val release = ClaimItemTool().execute(releaseParams, ctx)
            assertFalse(elements(release, "releaseResults").single().isReplayed(), "a release is a different operation: $release")
            assertNull(items.getById(item.id)!!.claimedBy, "the release executed and cleared the claim")
            assertEquals(2, records(), "claim and release are recorded under separate operations")
        }

    // ---------------------------------------------------------------- S5 selector

    @Test
    fun `S5 a keyed selector claim replays the same itemId instead of claiming another item`(): Unit =
        runBlocking {
            val ctx = context()
            val a = queueItem("Selector first")
            val b = queueItem("Selector second")
            val key = UUID.randomUUID().toString()
            val params =
                obj(
                    "claims" to buildJsonArray { add(buildJsonObject { put("selector", buildJsonObject {}) }) },
                    "actor" to actor("agent-selector"),
                    "requestId" to JsonPrimitive(key)
                )

            val firstRes = elements(ClaimItemTool().execute(params, ctx), "claimResults").single()
            assertEquals("success", firstRes["outcome"]!!.jsonPrimitive.content)
            val claimedId = firstRes["itemId"]!!.jsonPrimitive.content
            assertEquals(1, records())

            val replay = elements(ClaimItemTool().execute(params, ctx), "claimResults").single()
            assertTrue(replay.isReplayed(), "the retried selector claim replays: $replay")
            assertEquals(claimedId, replay["itemId"]!!.jsonPrimitive.content, "the replay names the item claimed the first time")
            assertEquals(1, records(), "a replay writes no record")
            val claimedCount = listOf(a.id, b.id).count { items.getById(it)!!.claimedBy == "agent-selector" }
            assertEquals(1, claimedCount, "exactly one item is claimed: the replay must not claim a second one")
        }

    // ---------------------------------------------------------------- S8 tool level

    @Test
    fun `S8 a non-object element is a stored payload-validation failure that replays on retry with the same key`(): Unit =
        runBlocking {
            val ctx = context()
            val key = UUID.randomUUID().toString()

            fun badParams(element: JsonElement) =
                obj(
                    "operation" to JsonPrimitive("create"),
                    "items" to JsonArray(listOf(element)),
                    "requestId" to JsonPrimitive(key),
                    "actor" to actor()
                )

            val first = ManageItemsTool().execute(badParams(JsonPrimitive("not an object")), ctx)
            assertTrue("must be a JSON object" in first.toString(), "attempt 1 reports the payload failure: $first")
            assertEquals(1, records(), "a payload-validation failure IS recorded (every other failure is not)")
            assertEquals(0, itemRows())

            val retry = ManageItemsTool().execute(badParams(JsonPrimitive("not an object")), ctx)
            assertTrue("must be a JSON object" in retry.toString(), "the retry returns the same stored failure: $retry")
            assertFalse("idempotency_mismatch" in retry.toString(), "an identical retry is not a mismatch: $retry")
            assertEquals(1, records(), "the replay adds no record")
            assertEquals(0, itemRows())

            val changed = ManageItemsTool().execute(badParams(JsonPrimitive(5)), ctx)
            assertTrue("idempotency_mismatch" in changed.toString(), "a different bad element under the same key is a mismatch: $changed")
            assertEquals(1, records())
        }

    // ---------------------------------------------------------------- S10 tool level

    private val leaseSchema: WorkItemSchemaService =
        object : WorkItemSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                if (tags.isNotEmpty()) {
                    listOf(
                        NoteSchemaEntry(
                            key = "acceptance-criteria",
                            role = Role.QUEUE,
                            required = true,
                            description = "Acceptance criteria for this task",
                            guidance = "List each criterion as a bullet point"
                        )
                    )
                } else {
                    null
                }

            override fun getTraitResources(traitName: String): List<ResourceRequirement> =
                if (traitName == "needs-staging-db") {
                    listOf(ResourceRequirement("staging-db-credential", ResourceMode.EXCLUSIVE, 600))
                } else {
                    emptyList()
                }
        }

    @Test
    fun `S10 a keyed advance that fails its gate leaves no lease, no transition and no record, and the retry executes`(): Unit =
        runBlocking {
            val ctx =
                ToolExecutionContext(
                    repositoryProvider = db.repositoryProvider(),
                    noteSchemaService = leaseSchema,
                    unitOfWork = db.unitOfWork()
                )
            val item =
                items.create(
                    WorkItem(
                        title = "Leased and gated",
                        role = Role.QUEUE,
                        depth = 0,
                        tags = "feature-task",
                        properties = """{"traits":["needs-staging-db"]}"""
                    )
                )
            val leases = db.repositoryProvider().resourceLeaseRepository()
            val key = UUID.randomUUID().toString()
            val params = advanceParams(key, transition(item.id, "start"))

            val first = AdvanceItemTool().execute(params, ctx)
            val firstEl = elements(first, "results").single()
            assertEquals("false", firstEl["applied"]!!.jsonPrimitive.content, "attempt 1 must fail: $first")
            assertEquals("gate_blocked", firstEl["errorCode"]!!.jsonPrimitive.content, "attempt 1 must fail on the note gate: $first")
            assertTrue(leases.findActiveByKeys(listOf("staging-db-credential")).isEmpty(), "no lease may survive the failed attempt")
            assertEquals(0, rawInt("SELECT count(*) FROM role_transitions"), "no transition row may survive")
            assertEquals(0, records(), "the failed element must not be recorded as ok")
            assertEquals(Role.QUEUE, items.getById(item.id)!!.role)

            fillAcceptanceNote(item.id, ctx)
            val retry = AdvanceItemTool().execute(params, ctx)
            val retryEl = elements(retry, "results").single()
            assertEquals("true", retryEl["applied"]!!.jsonPrimitive.content, "the retry with the same key must execute: $retry")
            assertFalse(retryEl.isReplayed())
            assertEquals(1, records())
            assertEquals(1, leases.findActiveByKeys(listOf("staging-db-credential")).size, "the successful retry acquires the lease")
        }

    // ---------------------------------------------------------------- S11 / S15

    @Test
    fun `S11 a requestId without an actor executes every time and records nothing`(): Unit =
        runBlocking {
            val ctx = context()
            val key = UUID.randomUUID().toString()
            ManageItemsTool().execute(createParams(key, "No actor", actorId = null), ctx)
            ManageItemsTool().execute(createParams(key, "No actor", actorId = null), ctx)
            assertEquals(2, itemRows())
            assertEquals(0, records())
        }

    @Test
    fun `S11 no requestId executes every time and records nothing`(): Unit =
        runBlocking {
            val ctx = context()
            ManageItemsTool().execute(createParams(null, "Unkeyed"), ctx)
            ManageItemsTool().execute(createParams(null, "Unkeyed"), ctx)
            assertEquals(2, itemRows())
            assertEquals(0, records())
        }

    @Test
    fun `S11 an actor rejected by policy is never keyed - control with an accepting policy records`(): Unit =
        runBlocking {
            val key = UUID.randomUUID().toString()

            ManageItemsTool().execute(createParams(key, "Rejected"), context(policy = DegradedModePolicy.REJECT))
            assertEquals(0, records(), "a rejected unverified actor must not produce a record")

            ManageItemsTool().execute(createParams(key, "Accepted"), context(policy = DegradedModePolicy.ACCEPT_CACHED))
            assertEquals(1, records(), "control: the same call under an accepting policy is recorded")
        }

    @Test
    fun `S15 the same requestId under two actors executes twice with separate records`(): Unit =
        runBlocking {
            val ctx = context()
            val key = UUID.randomUUID().toString()
            ManageItemsTool().execute(createParams(key, "Same", actorId = "agent-a"), ctx)
            val other = ManageItemsTool().execute(createParams(key, "Same", actorId = "agent-b"), ctx)
            assertFalse(elements(other, "items").single().isReplayed())
            assertEquals(2, itemRows())
            assertEquals(2, records())
        }

    @Test
    fun `S15 the same requestId on two different tools executes both`(): Unit =
        runBlocking {
            val ctx = context()
            val key = UUID.randomUUID().toString()
            ManageItemsTool().execute(createParams(key, "Host"), ctx)
            val hostId =
                items
                    .findRootItems()
                    .items
                    .single()
                    .id

            val noteCall =
                obj(
                    "operation" to JsonPrimitive("upsert"),
                    "notes" to
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", hostId.toString())
                                    put("key", "approach")
                                    put("role", "work")
                                    put("body", "body")
                                }
                            )
                        },
                    "requestId" to JsonPrimitive(key),
                    "actor" to actor()
                )
            val result = ManageNotesTool().execute(noteCall, ctx)
            assertFalse("idempotency_mismatch" in result.toString(), "a different tool is a different operation: $result")
            assertEquals(1, ctx.noteRepository().findByItemId(hostId).size)
            assertEquals(2, records())
        }

    // ---------------------------------------------------------------- S17

    @Test
    fun `S17 complete_tree cancel is recorded and replays with data replayed true`(): Unit =
        runBlocking {
            val ctx = context()
            val item = queueItem("To cancel")
            val key = UUID.randomUUID().toString()
            val params =
                obj(
                    "itemIds" to JsonArray(listOf(JsonPrimitive(item.id.toString()))),
                    "trigger" to JsonPrimitive("cancel"),
                    "requestId" to JsonPrimitive(key),
                    "actor" to actor()
                )
            CompleteTreeTool().execute(params, ctx)
            assertEquals(Role.TERMINAL, items.getById(item.id)!!.role)
            assertEquals(1, records())

            val replay = CompleteTreeTool().execute(params, ctx)
            assertEquals(true, data(replay)["replayed"]?.jsonPrimitive?.booleanOrNull, "single-element tools report data.replayed: $replay")
            assertEquals(1, records())
        }

    @Test
    fun `S17 complete_tree with a gate failure is not recorded and the retry with the same key executes`(): Unit =
        runBlocking {
            val ctx = context(schema = gateSchema)
            val gated = queueItem("Gated tree item", tags = "feature-task")
            val key = UUID.randomUUID().toString()
            val params =
                obj(
                    "itemIds" to JsonArray(listOf(JsonPrimitive(gated.id.toString()))),
                    "trigger" to JsonPrimitive("complete"),
                    "requestId" to JsonPrimitive(key),
                    "actor" to actor()
                )

            CompleteTreeTool().execute(params, ctx)
            assertNotEquals(Role.TERMINAL, items.getById(gated.id)!!.role, "attempt 1 must be gate-blocked")
            assertEquals(0, records(), "a gate failure must not be recorded")

            fillAcceptanceNote(gated.id, ctx)
            val retry = CompleteTreeTool().execute(params, ctx)
            assertNotEquals(true, data(retry)["replayed"]?.jsonPrimitive?.booleanOrNull, "the retry executes, it does not replay: $retry")
            assertNotNull(items.getById(gated.id))
            assertEquals(Role.TERMINAL, items.getById(gated.id)!!.role)
            assertEquals(1, records())
        }

    // ---------------------------------------------------------------- probes

    @Test
    fun `probe an empty items array stores nothing, and the control call stores a record`(): Unit =
        runBlocking {
            val ctx = context()
            val key = UUID.randomUUID().toString()
            runCatching { ManageItemsTool().execute(createParams(key), ctx) }
            assertEquals(0, records(), "nothing was created, so nothing is recorded")
            assertEquals(0, itemRows())

            ManageItemsTool().execute(createParams(key, "Control"), ctx)
            assertEquals(1, records())
        }

    private object AdvanceTrigger {
        const val START = "start"
    }
}
