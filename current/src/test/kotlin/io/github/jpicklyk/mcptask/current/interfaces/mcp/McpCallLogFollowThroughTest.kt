package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.Link
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.make
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.short
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.sampleCallLogRecord
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testReqId
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours

/** `test-item` is an ordinary auto type, `page` is lifecycle-free, `feature-task` is gated by one required queue note. */
private const val FOLLOW_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    notes: []\n" +
        "  page:\n" +
        "    lifecycle: none\n" +
        "    notes: []\n" +
        "  feature-task:\n" +
        "    notes:\n" +
        "      - key: spec\n" +
        "        role: queue\n" +
        "        required: true\n"

/** The signal wire names in the documented order (api-reference `related`: edges, links, tags, text). */
private val SIGNAL_ORDER = listOf("edges", "links", "tags", "text")

private const val FIXED_ID = "0f8fad5b-d9cb-469f-a165-70867728950e"

/**
 * Independent tests (test-author seat, item df10fafb) for the two new `call_log` columns on the MCP surface, driven through the
 * real [McpOperationAdapter] over the in-memory client/server pair, the production tool registry and the real composition:
 * scenarios S1 to S3, S5 to S10 (MCP leg) and S17 of the frozen test-plan, with their probes.
 *
 * Oracles (frozen at queue phase). `hit_signals` (task-scope item 1): a JSON array in page order of `{id, kind, signals}`, with
 * `id` the canonical UUID of the hit, `kind` item or document and `signals` the keys of the same response's `explain.ranks` in
 * the order edges, links, tags, text; NULL for an empty page and for every other call; no title or text. The expected value is
 * recomputed here from the response the client received, never from the code. `lifecycle_free_rejections` (task-scope item 1,
 * config-reference "Lifecycle-free items", api-reference claim and BLOCKS-endpoint rules): the number of rejections in the
 * response that a lifecycle-free item caused (`invalid_transition` with `fromRole` none, or the `invalid_request` refusing a
 * BLOCKS endpoint), NULL otherwise and never 0. Expected counts are derived from the fixture (which elements touch the page).
 *
 * Negative / absence assertions and the condition that would fail them without the new capture: every NULL on a related row
 * would be non-null if the call recorded hits on a call that is not a related page; every NULL on a lifecycle row would be a
 * number if role-bearing rejections or successes were counted (the S7 batch holds a role-bearing `invalid_transition` and an ok
 * element for exactly that reason).
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class McpCallLogFollowThroughTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private lateinit var rig: EventLogRig
    private lateinit var writer: CallLogWriter
    private lateinit var client: Client
    private val pairs = mutableListOf<Pair<Client, Server>>()

    private suspend fun start(yaml: String = FOLLOW_YAML) {
        rig = EventLogRig.build(db.db, dir, yaml)
        writer = CallLogWriter(rig.composition.unitOfWork, SqliteCallLogStore(db.databaseManager), flushInterval = 1.hours)
        val server = Server(serverInfo = Implementation(name = "df10-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        McpOperationAdapter(buildToolDispatcher(rig.ctx), callLog = writer)
            .register(server, ToolRegistry(buildToolSpecs()), ToolProfile.ORCHESTRATION)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        client =
            Client(
                clientInfo = Implementation(name = "df10-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities())
            )
        server.createSession(serverTransport)
        client.connect(clientTransport)
        pairs += client to server
    }

    @AfterEach
    fun tearDown(): Unit = runBlocking { pairs.forEach { closeInMemoryPair(it.first, it.second) } }

    // ------------------------------------------------------------------ helpers

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

    private fun CallToolResult.body(): JsonObject = checkNotNull(structuredContent) { "no structuredContent: $content" }

    private fun CallToolResult.data(): JsonObject = body().getValue("data").jsonObject

    private fun CallToolResult.results(): List<JsonObject> = data().getValue("results").jsonArray.map { it.jsonObject }

    private fun CallToolResult.oks(): List<Boolean> =
        results().map {
            it
                .getValue("ok")
                .jsonPrimitive.content
                .toBoolean()
        }

    private val actor: JsonObject =
        buildJsonObject {
            put("id", "agent-df10")
            put("kind", "subagent")
        }

    private fun transition(
        itemId: Any,
        trigger: String,
        key: String? = null
    ): JsonObject =
        buildJsonObject {
            put("op", "transition")
            put("itemId", itemId.toString())
            put("trigger", trigger)
            if (key != null) put("idempotencyKey", key)
        }

    private fun claimOp(itemId: Any): JsonObject =
        buildJsonObject {
            put("op", "claim")
            put("itemId", itemId.toString())
        }

    private suspend fun advance(
        vararg ops: JsonObject,
        atomic: Boolean = false
    ): CallToolResult {
        val args = mutableListOf<Pair<String, JsonElement>>("ops" to JsonArray(ops.toList()), "actor" to actor)
        if (atomic) args += "atomic" to JsonPrimitive(true)
        return call("advance", *args.toTypedArray())
    }

    private suspend fun EventLogRig.page(
        title: String,
        parent: WorkItem? = null
    ): WorkItem = ctx.itemCommandService.create(ItemCreateCommand(parentId = parent?.id, title = title, type = "page")).orFail()

    private fun lifecycleFree(row: Map<String, Any?>): Long? = row["lifecycle_free_rejections"] as Long?

    private fun createEdge(
        from: Any,
        to: Any,
        type: String? = null
    ): JsonObject =
        buildJsonObject {
            put("op", "create")
            put("from", from.toString())
            put("to", to.toString())
            if (type != null) put("type", type)
        }

    // ------------------------------------------------------------------ related fixture

    private fun <T> Outcome<T>.value(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> fail("fixture: expected Ok but was Err: ${error.code} ${error.message}")
        }

    private suspend fun EventLogRig.doc(
        owner: WorkItem,
        slug: String,
        title: String
    ): Document =
        DocumentService(raw, db.unitOfWork())
            .write(owner.id, DocumentKind.PLAN, slug, "An ordinary body without special words", title = title)
            .value()

    private suspend fun EventLogRig.relate(
        from: WorkItem,
        to: WorkItem
    ) {
        raw.dependencyRepository().create(Dependency(fromItemId = from.id, toItemId = to.id, type = DependencyType.RELATES_TO))
    }

    private class Fixture(
        val ref: WorkItem,
        val items: List<WorkItem>,
        val doc: Document,
        val docTitle: String
    ) {
        val itemByShort: Map<String, UUID> = items.associate { short(it.id) to it.id }
        val titles: List<String> = items.map { it.title } + docTitle
    }

    /**
     * Counterparts of the reference item: B (edge, shares text), A (edge, shares the tag orbit), C (edge only), D (shares the tag
     * beacon only) and one document linking to it. Every signal therefore finds at least one hit and some hits are found by several.
     */
    private suspend fun seedRelated(): Fixture {
        val p = rig.make("Hangar container")
        val ref = rig.make("Zorvath quasarium", p, tags = "orbit,beacon")
        val b = rig.make("Quasarium brigade", p)
        val a = rig.make("Kelmora", p, tags = "orbit")
        val c = rig.make("Wombrel", p)
        val d = rig.make("Ypsilon", p, tags = "beacon")
        for (counterpart in listOf(b, a, c)) rig.relate(ref, counterpart)
        val docTitle = "Titled plan"
        val doc = rig.doc(p, "plan-link", docTitle)
        rig.raw.linkStore().insert(
            Link(
                UUID.randomUUID(),
                p.id,
                EntityRef(RefKind.DOCUMENT, doc.id),
                EntityRef(RefKind.ITEM, ref.id),
                null,
                LinkRelation.REFERENCES,
                null,
                Instant.now()
            )
        )
        return Fixture(ref, listOf(b, a, c, d), doc, docTitle)
    }

    private data class Hit(
        val id: String,
        val kind: String,
        val signals: List<String>
    )

    /** The page of a `related` answer as the oracle sees it: id, kind and the explain.ranks keys in the documented order. */
    private fun expectedHits(
        result: CallToolResult,
        fx: Fixture
    ): List<Hit> =
        result.data().getValue("items").jsonArray.map { it.jsonObject }.map { hit ->
            val kind = hit.getValue("kind").jsonPrimitive.content
            val id =
                when (kind) {
                    "item" -> fx.itemByShort.getValue(hit.getValue("itemId").jsonPrimitive.content)
                    "document" -> {
                        assertEquals("plan-link", hit.getValue("slug").jsonPrimitive.content, "the only document of the fixture")
                        fx.doc.id
                    }
                    else -> fail("unknown hit kind $kind")
                }
            val ranks =
                hit
                    .getValue("explain")
                    .jsonObject
                    .getValue("ranks")
                    .jsonObject.keys
            Hit(id.toString(), kind, SIGNAL_ORDER.filter { it in ranks })
        }

    private fun hitSignalsOf(row: Map<String, Any?>): List<Hit> =
        Json
            .parseToJsonElement(row["hit_signals"] as String)
            .jsonArray
            .map { it.jsonObject }
            .map { e ->
                Hit(
                    e.getValue("id").jsonPrimitive.content,
                    e.getValue("kind").jsonPrimitive.content,
                    e.getValue("signals").jsonArray.map { it.jsonPrimitive.content }
                )
            }

    private suspend fun related(
        itemId: Any,
        limit: Int? = null,
        cursor: String? = null
    ): CallToolResult {
        val args =
            mutableListOf<Pair<String, JsonElement>>(
                "select" to JsonPrimitive("related"),
                "itemId" to JsonPrimitive(itemId.toString()),
                "explain" to JsonPrimitive(true)
            )
        if (limit != null) args += "limit" to JsonPrimitive(limit)
        if (cursor != null) args += "cursor" to JsonPrimitive(cursor)
        return call("items_read", *args.toTypedArray())
    }

    // ------------------------------------------------------------------ S1 .. S5: hit_signals

    @Test
    fun `S1 a related row lists the returned page hit by hit with the signals of its own explain ranks`(): Unit =
        runBlocking {
            start()
            val fx = seedRelated()

            val result = related(fx.ref.id)

            assertTrue(result.isError != true, "related must succeed: ${result.content}")
            val expected = expectedHits(result, fx)
            assertTrue(expected.size >= 5, "fixture: four items and a document are related: $expected")
            assertTrue(expected.any { it.signals.size >= 2 }, "fixture: at least one hit is found by several signals: $expected")
            assertEquals(SIGNAL_ORDER.toSet(), expected.flatMap { it.signals }.toSet(), "fixture: every signal finds something")
            assertTrue(expected.any { it.kind == "document" } && expected.any { it.kind == "item" }, "fixture: both kinds appear")

            val row = rowOf(result)

            assertEquals(expected, hitSignalsOf(row), "page order, canonical ids, kinds and rank signals")
            assertEquals(expected.size, hitSignalsOf(row).map { it.id }.toSet().size, "a hit found by several signals is listed once")
            val shape = Json.parseToJsonElement(row["request_shape"] as String).jsonObject
            assertEquals(
                SIGNAL_ORDER.associateWith { s -> expected.count { s in it.signals } },
                shape.getValue("relatedSignals").jsonObject.mapValues {
                    it.value.jsonPrimitive.content
                        .toInt()
                },
                "S3b: request_shape.relatedSignals still counts the same page per signal"
            )
        }

    @Test
    fun `S1 a hit id is written in the canonical form a later call in the session records as its target`(): Unit =
        runBlocking {
            start()
            val fx = seedRelated()
            val followed = fx.items[1]
            val first = related(fx.ref.id)

            val get = call("items_read", "select" to JsonPrimitive("get"), "itemId" to JsonPrimitive(followed.id.toString()))

            val relatedRow = rowOf(first)
            val getRow = rowOf(get)
            assertTrue(followed.id.toString() in hitSignalsOf(relatedRow).map { it.id }, "the followed item is one of the related hits")
            assertEquals(relatedRow["session_id"], getRow["session_id"], "same session: part of the follow-through join key")
            assertTrue((relatedRow["at"] as String) <= (getRow["at"] as String), "the follow-up call is not earlier")
            val recorded =
                Json.parseToJsonElement(getRow["target_versions"] as String).jsonObject.keys +
                    Json.parseToJsonElement(getRow["target_ids"] as String).jsonArray.map { it.jsonPrimitive.content }
            assertTrue(followed.id.toString() in recorded, "the later call records the same id text: $recorded")
        }

    @Test
    fun `S2 the second page records only the hits of that page`(): Unit =
        runBlocking {
            start()
            val fx = seedRelated()
            val page1 = related(fx.ref.id, limit = 2)
            val cursor =
                page1
                    .data()
                    .getValue("page")
                    .jsonObject["next"]
                    ?.jsonPrimitive
                    ?.content
            val next = assertNotNull(cursor, "fixture: more than two hits")

            val page2 = related(fx.ref.id, limit = 2, cursor = next)

            val expected1 = expectedHits(page1, fx)
            val expected2 = expectedHits(page2, fx)
            assertEquals(2, expected1.size, "fixture: a page of two")
            val firstIds = expected1.map { it.id }
            assertTrue(expected2.isNotEmpty() && expected2.none { it.id in firstIds }, "fixture: page two holds other hits")
            assertEquals(expected1, hitSignalsOf(rowOf(page1)))
            assertEquals(expected2, hitSignalsOf(rowOf(page2)), "only the hits of page two")
        }

    @Test
    fun `S3 a related call with an empty page leaves hit_signals NULL, not an empty array`(): Unit =
        runBlocking {
            start()
            val p = rig.make("Hangar container")
            val lonely = rig.make("Zorvath", p)

            val result = related(lonely.id)

            assertTrue(result.isError != true, "${result.content}")
            assertEquals(
                0,
                result
                    .data()
                    .getValue("items")
                    .jsonArray.size,
                "fixture: nothing is related"
            )
            val row = rowOf(result)
            assertNull(row["hit_signals"], "NULL for an empty page: ${row["hit_signals"]}")
        }

    @Test
    fun `S3 get search and list calls leave hit_signals NULL while the related call on the same data records hits`(): Unit =
        runBlocking {
            start()
            val fx = seedRelated()
            val control = rowOf(related(fx.ref.id))
            assertNotNull(control["hit_signals"], "control: the related call on the same data records hits")

            val get = call("items_read", "select" to JsonPrimitive("get"), "itemId" to JsonPrimitive(fx.ref.id.toString()))
            val search = call("items_read", "select" to JsonPrimitive("search"), "query" to JsonPrimitive("quasarium"))
            val list = call("items_read", "select" to JsonPrimitive("list"))

            for ((name, result) in listOf("get" to get, "search" to search, "list" to list)) {
                assertTrue(result.isError != true, "$name must succeed: ${result.content}")
                assertNull(rowOf(result)["hit_signals"], "$name records no hit signals")
            }
        }

    @Test
    fun `S3 a related call that fails leaves hit_signals NULL`(): Unit =
        runBlocking {
            start()
            val fx = seedRelated()

            val missing = related(UUID.randomUUID())
            val invalid = related(fx.ref.id, limit = 51)

            assertEquals(true, missing.isError, "fixture: an unknown item fails")
            assertEquals(true, invalid.isError, "fixture: limit 51 fails validation")
            assertNull(rowOf(missing)["hit_signals"])
            assertNull(rowOf(invalid)["hit_signals"])
        }

    @Test
    fun `S5 hit_signals carries ids and signal names only, never a title, a slug or any text`(): Unit =
        runBlocking {
            start()
            val fx = seedRelated()

            val result = related(fx.ref.id)

            val raw = assertNotNull(rowOf(result)["hit_signals"] as String?, "fixture: the related call recorded hits")
            val responseText = result.body().toString()
            for (text in fx.titles + "plan-link") {
                assertTrue(text in responseText, "fixture: '$text' is visible to the caller in the response, so it could leak")
                assertTrue(text !in raw, "'$text' must not appear in hit_signals: $raw")
            }
            for (element in Json.parseToJsonElement(raw).jsonArray) {
                assertEquals(setOf("id", "kind", "signals"), element.jsonObject.keys)
            }
        }

    @Test
    fun `S1 probe - a page of the maximum size of fifty records exactly the returned hits`(): Unit =
        runBlocking {
            start()
            val p = rig.make("Hangar container")
            val ref = rig.make("Zorvath", p)
            val counterparts = (1..52).map { rig.make("Counterpart $it", p) }
            for (c in counterparts) rig.relate(ref, c)
            val byShort = counterparts.associate { short(it.id) to it.id.toString() }

            val result = related(ref.id, limit = 50)

            val items =
                result
                    .data()
                    .getValue("items")
                    .jsonArray
                    .map { it.jsonObject }
            assertEquals(50, items.size, "fixture: the maximum page is full")
            assertEquals(
                "true",
                result
                    .data()
                    .getValue("page")
                    .jsonObject["capped"]
                    ?.jsonPrimitive
                    ?.content,
                "fixture: more existed"
            )
            val hits = hitSignalsOf(rowOf(result))
            assertEquals(items.map { byShort.getValue(it.getValue("itemId").jsonPrimitive.content) }, hits.map { it.id })
            assertTrue(hits.all { it.kind == "item" && it.signals == listOf("edges") })
        }

    // ------------------------------------------------------------------ S6 .. S10: lifecycle_free_rejections

    @Test
    fun `S6 a start on a lifecycle-free item records one rejection and a start on a plain item records none`(): Unit =
        runBlocking {
            start()
            val page = rig.page("Page")
            val plain = rig.make("Plain")

            val refused = advance(transition(page.id, "start"))
            val control = advance(transition(plain.id, "start"))

            assertEquals(true, refused.isError, "fixture: the page cannot start")
            assertNotEquals(true, control.isError, "control: an ordinary item starts: ${control.content}")
            val row = rowOf(refused)
            assertEquals(1L, lifecycleFree(row))
            assertNull(lifecycleFree(rowOf(control)), "an ok call is NULL, never 0")
        }

    @Test
    fun `S6 a claim on a lifecycle-free item records one rejection`(): Unit =
        runBlocking {
            start()
            val page = rig.page("Page")

            val refused = advance(claimOp(page.id))

            assertEquals(true, refused.isError, "fixture: the page cannot be claimed")
            assertEquals(1L, lifecycleFree(rowOf(refused)))
        }

    @Test
    fun `S7 a non-atomic batch counts lifecycle-free rejections once each and skips role-bearing rejections and successes`(): Unit =
        runBlocking {
            start()
            val page = rig.page("Page")
            val plain = rig.make("Plain")
            val done = rig.make("Done", role = Role.TERMINAL)

            val result =
                advance(
                    transition(page.id, "start"),
                    claimOp(page.id),
                    transition(plain.id, "start"),
                    transition(done.id, "complete")
                )

            assertEquals(listOf(false, false, true, false), result.oks(), "fixture: three of four fail")
            val roleBearing = result.results()[3].getValue("error").jsonObject
            assertEquals("invalid_transition", roleBearing.getValue("code").jsonPrimitive.content, "fixture: a role-bearing rejection")
            assertNotEquals(
                "none",
                roleBearing
                    .getValue("detail")
                    .jsonObject
                    .getValue("fromRole")
                    .jsonPrimitive.content
            )
            val row = rowOf(result)
            assertEquals(2L, lifecycleFree(row), "the transition and the claim on the page; not the terminal item, not the ok start")
            assertEquals(3L, row["failed_count"])
            assertEquals(4L, row["batch_size"])
        }

    @Test
    fun `S8 an atomic batch whose lifecycle-free element fails records one rejection`(): Unit =
        runBlocking {
            start()
            val page = rig.page("Page")
            val plain = rig.make("Plain")

            val result = advance(transition(plain.id, "start"), transition(page.id, "start"), atomic = true)

            assertEquals(true, result.isError, "fixture: the atomic batch fails")
            val row = rowOf(result)
            assertEquals(1L, lifecycleFree(row))
            assertEquals("invalid_transition", row["error_code"], "an atomic failure answers the failing element's own code")
        }

    @Test
    fun `S8 probe - an atomic batch that fails on a role-bearing rejection records NULL`(): Unit =
        runBlocking {
            start()
            val plain = rig.make("Plain")
            val done = rig.make("Done", role = Role.TERMINAL)

            val result = advance(transition(plain.id, "start"), transition(done.id, "complete"), atomic = true)

            assertEquals(true, result.isError, "fixture: the atomic batch fails")
            assertNull(lifecycleFree(rowOf(result)))
        }

    @Test
    fun `S9 a BLOCKS dependency with a lifecycle-free endpoint records one rejection and RELATES_TO records none`(): Unit =
        runBlocking {
            start()
            val plain = rig.make("Plain")
            val page = rig.page("Page")

            val refused = call("deps_write", "ops" to buildJsonArray { add(createEdge(plain.id, page.id)) })
            val allowed = call("deps_write", "ops" to buildJsonArray { add(createEdge(plain.id, page.id, "relates_to")) })

            assertEquals(true, refused.isError, "fixture: a BLOCKS edge into a page is refused")
            assertNotEquals(true, allowed.isError, "control: RELATES_TO to the same page is stored: ${allowed.content}")
            val row = rowOf(refused)
            assertEquals(1L, lifecycleFree(row))
            assertNull(lifecycleFree(rowOf(allowed)))
        }

    @Test
    fun `S9 a non-atomic dependency batch counts each refused endpoint`(): Unit =
        runBlocking {
            start()
            val plain = rig.make("Plain")
            val other = rig.make("Other")
            val page = rig.page("Page")

            val result =
                call(
                    "deps_write",
                    "ops" to
                        buildJsonArray {
                            add(createEdge(plain.id, page.id))
                            add(createEdge(page.id, plain.id))
                            add(createEdge(plain.id, other.id))
                        }
                )

            assertEquals(listOf(false, false, true), result.oks(), "fixture: both page edges fail, the ordinary edge is stored")
            val row = rowOf(result)
            assertEquals(2L, lifecycleFree(row))
            assertEquals(2L, row["failed_count"])
        }

    @Test
    fun `S10 ok, gate-blocked and not-found calls record NULL, never 0`(): Unit =
        runBlocking {
            start()
            val plain = rig.make("Plain")
            val gated = rig.make("Gated", type = "feature-task")

            val ok = advance(transition(plain.id, "start"))
            val blocked = advance(transition(gated.id, "start"))
            val missing = advance(transition(UUID.randomUUID(), "start"))

            assertNotEquals(true, ok.isError, "fixture: ${ok.content}")
            assertEquals(true, blocked.isError, "fixture: the gated item cannot start without its note")
            assertEquals(true, missing.isError, "fixture: the unknown item fails")
            for ((name, result) in listOf("ok" to ok, "gate-blocked" to blocked, "not-found" to missing)) {
                assertNull(lifecycleFree(rowOf(result)), "$name call: NULL")
            }
            assertNotEquals("invalid_transition", rowOf(blocked)["error_code"], "fixture: the gate refusal is not a none-role rejection")
        }

    @Test
    fun `S10 probe - a replay of an ok keyed transition records NULL`(): Unit =
        runBlocking {
            start()
            val plain = rig.make("Plain")

            val first = advance(transition(plain.id, "start", key = "df10-k1"))
            val replay = advance(transition(plain.id, "start", key = "df10-k1"))

            assertNotEquals(true, first.isError, "${first.content}")
            assertNotEquals(true, replay.isError, "fixture: the replay answers the stored result: ${replay.content}")
            assertNull(lifecycleFree(rowOf(first)))
            val row = rowOf(replay)
            assertEquals(1L, row["replayed"], "fixture: the second call is a replay")
            assertNull(lifecycleFree(row))
        }

    // ------------------------------------------------------------------ S17: storage round trip

    @Test
    fun `S17 SqliteCallLogStore writes and reads back both new fields and NULL when absent`(): Unit =
        runBlocking {
            val signals = """[{"id":"$FIXED_ID","kind":"document","signals":["links","text"]}]"""
            val both = sampleCallLogRecord(testReqId(1), tool = "items_read").copy(hitSignals = signals, lifecycleFreeRejections = 5)
            val onlyHits = sampleCallLogRecord(testReqId(2), tool = "items_read").copy(hitSignals = signals)
            val onlyCount = sampleCallLogRecord(testReqId(3), tool = "advance").copy(lifecycleFreeRejections = 1)
            val neither = sampleCallLogRecord(testReqId(4))
            val store = SqliteCallLogStore(db.databaseManager)

            val batch = listOf(both, onlyHits, onlyCount, neither)
            val inserted =
                when (val outcome = db.unitOfWork().write("df10.append") { Outcome.Ok(store.append(batch)) }) {
                    is Outcome.Ok -> outcome.value
                    is Outcome.Err -> fail("unit failed: ${outcome.error.message}")
                }

            assertEquals(4, inserted)
            val rows = CallLogRows.read(db.jdbcUrl).associateBy { it["req_id"] }
            assertEquals(signals, rows.getValue(testReqId(1))["hit_signals"])
            assertEquals(5L, rows.getValue(testReqId(1))["lifecycle_free_rejections"])
            assertEquals(signals, rows.getValue(testReqId(2))["hit_signals"])
            assertNull(rows.getValue(testReqId(2))["lifecycle_free_rejections"])
            assertNull(rows.getValue(testReqId(3))["hit_signals"])
            assertEquals(1L, rows.getValue(testReqId(3))["lifecycle_free_rejections"])
            assertNull(rows.getValue(testReqId(4))["hit_signals"])
            assertNull(rows.getValue(testReqId(4))["lifecycle_free_rejections"])
            assertEquals(CallLogRecord.OUTCOME_OK, rows.getValue(testReqId(4))["outcome"], "control: the rest of the row is stored")
        }
}
