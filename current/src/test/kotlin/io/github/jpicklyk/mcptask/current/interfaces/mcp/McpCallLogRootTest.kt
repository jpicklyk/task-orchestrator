package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.make
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.sampleCallLogRecord
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testReqId
import io.github.jpicklyk.mcptask.current.test.upsertNoteVia
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours

/**
 * Independent tests (test-author seat, item 6a52466e, R2) for the root, fields and multiRoot capture of `call_log` on the MCP
 * surface, driven through the real [McpOperationAdapter] over the in-memory client/server pair, the production tool registry and
 * the real composition (events included). Scenarios S1 to S6, S8, S10 and S12 of the frozen test-plan, with their probes.
 *
 * Oracles (frozen at queue phase): task-scope points 2, 4, 5 and 6 and AC2 to AC5. `call_log.root_id` is the single root a call
 * read (target-version reads: items_read get and context, notes_read get and list) or wrote (the roots of its events), NULL when
 * none, and NULL with `request_shape.multiRoot: true` when two or more distinct roots were touched (one root touched many times
 * is that root, no key). A child item's root is its depth-0 ancestor (the fixtures derive it from the parent, the way EventLogRig
 * seeds). `request_shape.fields` is the sorted distinct array of the accepted `fields` names of an `items_read` select, absent when
 * the param is absent or empty or the call is refused for an unknown name; the allowed names per select come from the public
 * current/docs/api-reference.md (get: createdAt, modifiedAt, roleChangedAt, ancestors; list: those plus summary; schema: guidance;
 * context: ancestors; next and blocked: summary, ancestors). `target_ids` carries `from` and `to` (task-scope point 6, AC4).
 *
 * Negative / absence assertions and the condition that would fail them without the new capture: a NULL root on a schema-by-type
 * read is paired with a get in the same test that has a root (so a missing column fails the control); an absent `fields` key is
 * paired with a call that has it; an absent `multiRoot` key on a one-root batch is paired with a two-root batch; a replayed write
 * would show `multiRoot` if the replay re-reported roots.
 *
 * Not asserted (the oracle does not fix it): the root of a depth-0 item itself, the root of a replayed write, what a client-sent
 * `fields` or `multiRoot` key becomes when the server has no value.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class McpCallLogRootTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private lateinit var rig: EventLogRig
    private lateinit var writer: CallLogWriter
    private lateinit var client: Client
    private lateinit var server: Server

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            rig = EventLogRig.build(db.db, dir, EventLogRig.GATED_CONFIG)
            writer = CallLogWriter(rig.composition.unitOfWork, SqliteCallLogStore(db.databaseManager), flushInterval = 1.hours)
            server = Server(serverInfo = Implementation(name = "r2-server", version = "1.0.0"), options = inMemoryTestServerOptions())
            McpOperationAdapter(buildToolDispatcher(rig.ctx), callLog = writer)
                .register(server, ToolRegistry(buildToolSpecs()), ToolProfile.ORCHESTRATION)
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            client =
                Client(
                    clientInfo = Implementation(name = "r2-client", version = "1.0.0"),
                    options = ClientOptions(capabilities = ClientCapabilities())
                )
            server.createSession(serverTransport)
            client.connect(clientTransport)
        }

    @AfterEach
    fun tearDown(): Unit = runBlocking { closeInMemoryPair(client, server) }

    // ------------------------------------------------------------------ helpers

    private fun js(value: String): JsonElement = JsonPrimitive(value)

    private suspend fun call(
        tool: String,
        vararg args: Pair<String, JsonElement>
    ): CallToolResult =
        client.callTool(
            buildCallToolRequest {
                name = tool
                arguments(JsonObject(mapOf(*args)))
            }
        )

    private fun reqIdOf(result: CallToolResult): String =
        assertNotNull(
            result.meta
                ?.get("reqId")
                ?.jsonPrimitive
                ?.content,
            "_meta.reqId"
        )

    private suspend fun rowOf(result: CallToolResult): Map<String, Any?> {
        writer.flushNow()
        val reqId = reqIdOf(result)
        return CallLogRows.read(db.jdbcUrl).single { it["req_id"] == reqId }
    }

    private fun rootOf(row: Map<String, Any?>): UUID? =
        (row["root_id"] as ByteArray?)?.let {
            assertEquals(16, it.size, "root_id is a 16-byte UUID blob")
            ByteBuffer.wrap(it).let { buf -> UUID(buf.long, buf.long) }
        }

    private fun shapeOf(row: Map<String, Any?>): JsonObject =
        Json
            .parseToJsonElement(
                assertNotNull(row["request_shape"] as String?, "request_shape must be non-null on a 4.0 MCP call")
            ).jsonObject

    private fun fieldsOf(row: Map<String, Any?>): List<String>? = shapeOf(row)["fields"]?.jsonArray?.map { it.jsonPrimitive.content }

    private fun multiRootOf(row: Map<String, Any?>): Boolean? = shapeOf(row)["multiRoot"]?.jsonPrimitive?.booleanOrNull

    private fun get(
        id: UUID,
        vararg extra: Pair<String, JsonElement>
    ): List<Pair<String, JsonElement>> = listOf("select" to js("get"), "itemId" to js(id.toString())) + extra

    private suspend fun itemsRead(vararg args: Pair<String, JsonElement>): CallToolResult = call("items_read", *args)

    private fun updateOp(
        id: UUID,
        title: String
    ): JsonObject =
        buildJsonObject {
            put("op", "update")
            put("id", id.toString())
            put("title", title)
        }

    private suspend fun write(vararg ops: JsonObject): CallToolResult = call("items_write", "ops" to JsonArray(ops.toList()))

    private fun transition(
        itemId: UUID,
        trigger: String,
        key: String? = null
    ): JsonObject =
        buildJsonObject {
            put("op", "transition")
            put("itemId", itemId.toString())
            put("trigger", trigger)
            if (key != null) put("idempotencyKey", key)
        }

    private suspend fun advance(vararg ops: JsonObject): CallToolResult = call("advance", "ops" to JsonArray(ops.toList()))

    /** Two roots, each with two children: the fixture that makes "which root" decidable. */
    private class Forest(
        val rootA: WorkItem,
        val a1: WorkItem,
        val a2: WorkItem,
        val rootB: WorkItem,
        val b1: WorkItem,
        val rootC: WorkItem,
        val c1: WorkItem
    )

    private suspend fun forest(): Forest {
        val rootA = rig.make("Root A")
        val rootB = rig.make("Root B")
        val rootC = rig.make("Root C")
        return Forest(
            rootA = rootA,
            a1 = rig.make("A child one", rootA),
            a2 = rig.make("A child two", rootA),
            rootB = rootB,
            b1 = rig.make("B child one", rootB),
            rootC = rootC,
            c1 = rig.make("C child one", rootC)
        )
    }

    // ------------------------------------------------------------------ S1 .. S2: reads record the root

    @Test
    fun `S1 an items_read get of a child records the root of that child and no multiRoot key`(): Unit =
        runBlocking {
            val f = forest()

            val result = itemsRead(*get(f.a1.id).toTypedArray())

            assertTrue(result.isError != true, "${result.content}")
            val row = rowOf(result)
            assertEquals(f.rootA.id, rootOf(row))
            assertNull(multiRootOf(row), "one root: no multiRoot key: ${row["request_shape"]}")
        }

    @Test
    fun `S1 gets of children under different roots record each own root`(): Unit =
        runBlocking {
            val f = forest()

            val a = rowOf(itemsRead(*get(f.a1.id).toTypedArray()))
            val b = rowOf(itemsRead(*get(f.b1.id).toTypedArray()))

            assertEquals(f.rootA.id, rootOf(a))
            assertEquals(f.rootB.id, rootOf(b))
            assertNotEquals(rootOf(a), rootOf(b), "control: the fixture has two distinct roots")
        }

    @Test
    fun `S1 an items_read context of a child records the root of that child`(): Unit =
        runBlocking {
            val f = forest()

            val result = itemsRead("select" to js("context"), "itemId" to js(f.b1.id.toString()))

            assertTrue(result.isError != true, "${result.content}")
            assertEquals(f.rootB.id, rootOf(rowOf(result)))
        }

    @Test
    fun `S1 a list read records no root`(): Unit =
        runBlocking {
            val f = forest()
            val control = rowOf(itemsRead(*get(f.a1.id).toTypedArray()))
            assertEquals(f.rootA.id, rootOf(control), "control: a get on the same data has a root")

            val list = itemsRead("select" to js("list"))

            assertTrue(list.isError != true, "${list.content}")
            assertNull(rootOf(rowOf(list)), "list records no target version, so no root")
        }

    @Test
    fun `S2 notes_read get and list of an item record the root of the owning item`(): Unit =
        runBlocking {
            val f = forest()
            rig.ctx.upsertNoteVia(f.b1.id, "spec", body = "a note body")

            val got = call("notes_read", "select" to js("get"), "itemId" to js(f.b1.id.toString()), "key" to js("spec"))
            val listed = call("notes_read", "select" to js("list"), "itemId" to js(f.b1.id.toString()))

            assertTrue(got.isError != true, "${got.content}")
            assertTrue(listed.isError != true, "${listed.content}")
            assertEquals(f.rootB.id, rootOf(rowOf(got)), "notes_read get")
            assertEquals(f.rootB.id, rootOf(rowOf(listed)), "notes_read list")
        }

    // ------------------------------------------------------------------ S3 .. S5: writes record the root through their events

    @Test
    fun `S3 an items_write update of a child records the root of the event`(): Unit =
        runBlocking {
            val f = forest()

            val result = write(updateOp(f.a1.id, "renamed"))

            assertTrue(result.isError != true, "${result.content}")
            val row = rowOf(result)
            assertEquals(f.rootA.id, rootOf(row))
            assertNull(multiRootOf(row))
        }

    @Test
    fun `S4 a batch touching items in two roots records no root and multiRoot true`(): Unit =
        runBlocking {
            val f = forest()

            val result = write(updateOp(f.a1.id, "renamed A"), updateOp(f.b1.id, "renamed B"))

            assertTrue(result.isError != true, "fixture: both updates apply: ${result.content}")
            val row = rowOf(result)
            assertNull(rootOf(row), "two distinct roots: no single root")
            assertEquals(true, multiRootOf(row), "${row["request_shape"]}")
        }

    @Test
    fun `S4 probe - three roots in one batch is still multiRoot and the order of the roots does not matter`(): Unit =
        runBlocking {
            val f = forest()

            val abc = rowOf(write(updateOp(f.a1.id, "a"), updateOp(f.b1.id, "b"), updateOp(f.c1.id, "c")))
            val aba = rowOf(write(updateOp(f.a1.id, "a2"), updateOp(f.b1.id, "b2"), updateOp(f.a2.id, "a3")))

            assertNull(rootOf(abc))
            assertEquals(true, multiRootOf(abc))
            assertNull(rootOf(aba), "root A seen again after root B does not restore a single root")
            assertEquals(true, multiRootOf(aba))
        }

    @Test
    fun `S5 a batch touching two items under the same root records that root and no multiRoot key`(): Unit =
        runBlocking {
            val f = forest()

            val result = write(updateOp(f.a1.id, "one"), updateOp(f.a2.id, "two"))

            assertTrue(result.isError != true, "${result.content}")
            val row = rowOf(result)
            assertEquals(f.rootA.id, rootOf(row))
            assertNull(multiRootOf(row), "${row["request_shape"]}")
        }

    @Test
    fun `S5 probe - an advance transition records the root of the transitioned item`(): Unit =
        runBlocking {
            val f = forest()

            val result = advance(transition(f.b1.id, "start"))

            assertTrue(result.isError != true, "${result.content}")
            assertEquals(f.rootB.id, rootOf(rowOf(result)))
        }

    @Test
    fun `S5 probe - a replay of a keyed single-root write is never multiRoot`(): Unit =
        runBlocking {
            val f = forest()

            val first = advance(transition(f.a1.id, "start", key = "r2-k1"))
            val replay = advance(transition(f.a1.id, "start", key = "r2-k1"))

            assertTrue(first.isError != true, "${first.content}")
            assertTrue(replay.isError != true, "fixture: the replay answers the stored result: ${replay.content}")
            val firstRow = rowOf(first)
            assertEquals(f.rootA.id, rootOf(firstRow))
            val replayRow = rowOf(replay)
            assertEquals(1L, replayRow["replayed"], "fixture: the second call is a replay")
            assertNull(multiRootOf(replayRow), "a replay never reports two roots: ${replayRow["request_shape"]}")
        }

    // ------------------------------------------------------------------ S6: no item, no root

    @Test
    fun `S6 a schema read by type only records no root and no multiRoot key`(): Unit =
        runBlocking {
            val f = forest()
            assertEquals(f.rootA.id, rootOf(rowOf(itemsRead(*get(f.a1.id).toTypedArray()))), "control: a get has a root")

            val result = itemsRead("select" to js("schema"), "type" to js("test-item"))

            assertTrue(result.isError != true, "${result.content}")
            val row = rowOf(result)
            assertNull(rootOf(row))
            assertNull(multiRootOf(row))
        }

    // ------------------------------------------------------------------ S8: follow-through through the widened target keys

    @Test
    fun `S8 a related hit followed by a deps_write create naming it records both ids as targets of the later call`(): Unit =
        runBlocking {
            val container = rig.make("Hangar")
            val ref = rig.make("Zorvath quasarium", container, tags = "orbit")
            val hit = rig.make("Kelmora", container, tags = "orbit")

            val related =
                itemsRead("select" to js("related"), "itemId" to js(ref.id.toString()), "explain" to JsonPrimitive(true))
            assertTrue(related.isError != true, "${related.content}")
            val relatedRow = rowOf(related)
            val hitIds =
                Json
                    .parseToJsonElement(assertNotNull(relatedRow["hit_signals"] as String?, "fixture: related recorded its hits"))
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("id")
                            .jsonPrimitive.content
                    }
            assertTrue(hit.id.toString() in hitIds, "fixture: the tag sibling is a related hit: $hitIds")

            val create =
                call(
                    "deps_write",
                    "ops" to
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("op", "create")
                                    put("from", ref.id.toString())
                                    put("to", hit.id.toString())
                                }
                            )
                        }
                )
            assertTrue(create.isError != true, "fixture: the edge is stored: ${create.content}")

            val createRow = rowOf(create)
            val targets = Json.parseToJsonElement(createRow["target_ids"] as String).jsonArray.map { it.jsonPrimitive.content }
            assertEquals(setOf(ref.id.toString(), hit.id.toString()), targets.toSet(), "from and to are recorded as targets")
            assertEquals(relatedRow["session_id"], createRow["session_id"], "same session: part of the follow-through join key")
            assertTrue(
                hit.id.toString() in hitIds && hit.id.toString() in targets,
                "the hit of the earlier call is a target of the later one"
            )
            assertEquals(container.id, rootOf(createRow), "the edge write reports the root of its events")
        }

    // ------------------------------------------------------------------ S10: request_shape.fields

    @Test
    fun `S10 a get with fields records the sorted distinct names`(): Unit =
        runBlocking {
            val f = forest()

            val result =
                itemsRead(
                    *get(
                        f.a1.id,
                        "fields" to buildJsonArray { listOf("createdAt", "ancestors", "createdAt").forEach { add(JsonPrimitive(it)) } }
                    ).toTypedArray()
                )

            assertTrue(result.isError != true, "fixture: duplicates are accepted: ${result.content}")
            assertEquals(listOf("ancestors", "createdAt"), fieldsOf(rowOf(result)))
        }

    @Test
    fun `S10 an absent fields param and an empty fields array record no fields key while a populated one does`(): Unit =
        runBlocking {
            val f = forest()

            val absent = rowOf(itemsRead(*get(f.a1.id).toTypedArray()))
            val emptyResult = itemsRead(*get(f.a1.id, "fields" to JsonArray(emptyList())).toTypedArray())
            val populated = rowOf(itemsRead(*get(f.a1.id, "fields" to buildJsonArray { add(JsonPrimitive("ancestors")) }).toTypedArray()))

            assertTrue(emptyResult.isError != true, "fixture: an empty fields array is accepted: ${emptyResult.content}")
            assertNull(fieldsOf(absent), "absent: ${absent["request_shape"]}")
            assertNull(fieldsOf(rowOf(emptyResult)), "empty: no key")
            assertEquals(listOf("ancestors"), fieldsOf(populated), "control: the populated call records the key")
        }

    @Test
    fun `S10 probe - a fields name of the wrong case is refused and records no fields key`(): Unit =
        runBlocking {
            val f = forest()
            val control = rowOf(itemsRead(*get(f.a1.id, "fields" to buildJsonArray { add(JsonPrimitive("ancestors")) }).toTypedArray()))
            assertEquals(listOf("ancestors"), fieldsOf(control), "control: the right case is recorded")

            val result = itemsRead(*get(f.a1.id, "fields" to buildJsonArray { add(JsonPrimitive("Ancestors")) }).toTypedArray())

            assertEquals(true, result.isError, "fixture: an unknown name is invalid_request")
            assertNull(fieldsOf(rowOf(result)), "a refused call records no fields")
        }

    @Test
    fun `S10 probe - one unknown name next to a valid one refuses the call and records no fields key`(): Unit =
        runBlocking {
            val f = forest()

            val result =
                itemsRead(
                    *get(
                        f.a1.id,
                        "fields" to buildJsonArray { listOf("ancestors", "bogus").forEach { add(JsonPrimitive(it)) } }
                    ).toTypedArray()
                )

            assertEquals(true, result.isError, "fixture: the unknown name refuses the whole call")
            val row = rowOf(result)
            assertEquals("invalid_request", row["error_code"])
            assertNull(fieldsOf(row), "fields are recorded only after the names are accepted")
        }

    @Test
    fun `S10 every items_read select records its accepted fields`(): Unit =
        runBlocking {
            val f = forest()

            fun names(vararg n: String): Pair<String, JsonElement> = "fields" to buildJsonArray { n.forEach { add(JsonPrimitive(it)) } }
            val cases: List<Triple<String, List<Pair<String, JsonElement>>, List<String>>> =
                listOf(
                    Triple("get", get(f.a1.id, names("modifiedAt", "roleChangedAt")), listOf("modifiedAt", "roleChangedAt")),
                    Triple("list", listOf("select" to js("list"), names("summary", "createdAt")), listOf("createdAt", "summary")),
                    Triple("schema", listOf("select" to js("schema"), "type" to js("test-item"), names("guidance")), listOf("guidance")),
                    Triple(
                        "context",
                        listOf("select" to js("context"), "itemId" to js(f.a1.id.toString()), names("ancestors")),
                        listOf("ancestors")
                    ),
                    Triple("next", listOf("select" to js("next"), names("summary", "ancestors")), listOf("ancestors", "summary")),
                    Triple("blocked", listOf("select" to js("blocked"), names("ancestors")), listOf("ancestors"))
                )
            for ((select, args, expected) in cases) {
                val result = itemsRead(*args.toTypedArray())
                assertTrue(result.isError != true, "$select must succeed: ${result.content}")
                assertEquals(expected, fieldsOf(rowOf(result)), "select $select")
            }
        }

    @Test
    fun `S10 fields sit next to the client flags and the profile in one shape`(): Unit =
        runBlocking {
            forest()

            // The 16-key cap itself (twenty flags) is asserted on the pure function in CallLogFieldsRootKeysTest: the items_read
            // selects accept too few boolean flags to reach it end to end, and unknown parameters refuse the call.
            val result =
                itemsRead(
                    "select" to js("next"),
                    "fields" to buildJsonArray { add(JsonPrimitive("summary")) },
                    "includeClaimed" to JsonPrimitive(true)
                )

            assertTrue(result.isError != true, "${result.content}")
            val row = rowOf(result)
            assertEquals(listOf("summary"), fieldsOf(row), "${row["request_shape"]}")
            assertEquals("orchestration", shapeOf(row)["profile"]?.jsonPrimitive?.content, "control: the profile is recorded too")
            assertEquals(true, shapeOf(row)["includeClaimed"]?.jsonPrimitive?.booleanOrNull, "control: the client flag is recorded too")
        }

    // ------------------------------------------------------------------ S12: storage round trip of the new column

    @Test
    fun `S12 SqliteCallLogStore writes and reads back root_id and NULL when absent`(): Unit =
        runBlocking {
            val root = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
            val withRoot = sampleCallLogRecord(testReqId(1), tool = "items_read").copy(rootId = root)
            val without = sampleCallLogRecord(testReqId(2), tool = "items_read")
            val store = SqliteCallLogStore(db.databaseManager)

            val inserted =
                when (val outcome = db.unitOfWork().write("r2.append") { Outcome.Ok(store.append(listOf(withRoot, without))) }) {
                    is Outcome.Ok -> outcome.value
                    is Outcome.Err -> fail("unit failed: ${outcome.error.message}")
                }

            assertEquals(2, inserted)
            val rows = CallLogRows.read(db.jdbcUrl).associateBy { it["req_id"] }
            val bytes = assertNotNull(rows.getValue(testReqId(1))["root_id"] as ByteArray?, "root_id is stored")
            assertEquals(
                root,
                ByteBuffer.wrap(bytes).let { UUID(it.long, it.long) },
                "the 16 bytes are the UUID, most significant half first (the encoding of events.root_id)"
            )
            assertNull(rows.getValue(testReqId(2))["root_id"], "a record without a root reads NULL")
            assertEquals(CallLogRecord.OUTCOME_OK, rows.getValue(testReqId(2))["outcome"], "control: the rest of the row is stored")
        }

    @Test
    fun `S12 probe - the root_id column does not disturb a record that also carries run and seat`(): Unit =
        runBlocking {
            val root = UUID.fromString("11111111-2222-4333-8444-555555555555")
            val run = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa")
            val full = sampleCallLogRecord(testReqId(3), tool = "advance").copy(rootId = root, runId = run, seat = "implementer")
            val store = SqliteCallLogStore(db.databaseManager)

            when (val outcome = db.unitOfWork().write("r2.append2") { Outcome.Ok(store.append(listOf(full))) }) {
                is Outcome.Ok -> assertEquals(1, outcome.value)
                is Outcome.Err -> fail("unit failed: ${outcome.error.message}")
            }

            val row = CallLogRows.read(db.jdbcUrl).single { it["req_id"] == testReqId(3) }
            assertEquals(root, rootOf(row))
            assertEquals(run, (row["run_id"] as ByteArray).let { ByteBuffer.wrap(it).let { b -> UUID(b.long, b.long) } })
            assertEquals("implementer", row["seat"])
        }
}
