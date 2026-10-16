package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService
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
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.closeInMemoryPair
import io.github.jpicklyk.mcptask.current.interfaces.mcp.inMemoryTestServerOptions
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.make
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.short
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours

/** `test-item` is an ordinary auto type, `page` is lifecycle-free. */
private const val REST_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    notes: []\n" +
        "  page:\n" +
        "    lifecycle: none\n" +
        "    notes: []\n"

/** The signal wire names in the documented order (api-reference `related`: edges, links, tags, text). */
private val REST_SIGNAL_ORDER = listOf("edges", "links", "tags", "text")

/**
 * Independent tests (test-author seat, item df10fafb) for the two new `call_log` columns on the REST surface, through the real
 * production topology (`installParityRestApp`: real composition, `installRestApiRoutes` with a call-log sink and the production
 * registry behind `POST /api/v1/ops/{tool}`) with real bearer principals: scenario S4 (REST and MCP agree) and the REST leg of
 * S6 to S10 of the frozen test-plan.
 *
 * Oracles (frozen at queue phase): the same as the MCP file (`McpCallLogFollowThroughTest`): `hit_signals` is recomputed from the
 * same response's hits and `explain.ranks` (ids, kind, ranks keys in the order edges, links, tags, text), NULL for an empty page
 * and for every other call; `lifecycle_free_rejections` counts the rejections that a lifecycle-free item caused, NULL otherwise
 * and never 0. Task-scope item 2: both transports pass the count, and a REST route that is not an `/ops` call is a read, so it
 * records neither column.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class RestCallLogHitSignalsTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private class Reply(
        val status: Int,
        val body: JsonObject,
        val reqId: String
    ) {
        val data: JsonObject get() = body.getValue("data").jsonObject
        val results: List<JsonObject> get() = data.getValue("results").jsonArray.map { it.jsonObject }
        val oks: List<Boolean> get() =
            results.map {
                it
                    .getValue("ok")
                    .jsonPrimitive.content
                    .toBoolean()
            }
        val isOk: Boolean get() =
            body
                .getValue("ok")
                .jsonPrimitive.content
                .toBoolean()
    }

    private class Env(
        val http: HttpClient,
        val rig: EventLogRig,
        val writer: CallLogWriter,
        val jdbcUrl: String
    ) {
        suspend fun ops(
            tool: String,
            body: String,
            token: String = WRITE_TOKEN
        ): Reply {
            val response =
                http.post("/api/v1/ops/$tool") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            val text = response.bodyAsText()
            return Reply(
                response.status.value,
                Json.parseToJsonElement(text).jsonObject,
                assertNotNull(response.headers["X-Req-Id"], "X-Req-Id on a recorded response")
            )
        }

        suspend fun rowOf(reqId: String): Map<String, Any?> {
            writer.flushNow()
            return CallLogRows.read(jdbcUrl).single { it["req_id"] == reqId }
        }
    }

    private fun restApp(block: suspend Env.() -> Unit) =
        testApplication {
            val rig = EventLogRig.build(db.db, dir, REST_YAML)
            val writer = CallLogWriter(rig.composition.unitOfWork, SqliteCallLogStore(db.databaseManager), flushInterval = 1.hours)
            application { installParityRestApp(rig, makeWriteAuthConfig(), ToolRegistry(buildToolSpecs()), callLog = writer) }
            Env(client, rig, writer, db.jdbcUrl).block()
        }

    private fun lifecycleFree(row: Map<String, Any?>): Long? = row["lifecycle_free_rejections"] as Long?

    private suspend fun EventLogRig.page(title: String): WorkItem =
        ctx.itemCommandService.create(ItemCreateCommand(parentId = null, title = title, type = "page")).orFail()

    private fun transition(
        itemId: Any,
        trigger: String
    ) = """{"op":"transition","itemId":"$itemId","trigger":"$trigger"}"""

    private fun claim(itemId: Any) = """{"op":"claim","itemId":"$itemId"}"""

    private fun edge(
        from: Any,
        to: Any,
        type: String? = null
    ): String {
        val typePart = if (type != null) ",\"type\":\"$type\"" else ""
        return """{"op":"create","from":"$from","to":"$to"$typePart}"""
    }

    // ------------------------------------------------------------------ related fixture

    private fun <T> Outcome<T>.value(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> fail("fixture: expected Ok but was Err: ${error.code} ${error.message}")
        }

    private class Fixture(
        val ref: WorkItem,
        val items: List<WorkItem>,
        val doc: Document
    ) {
        val itemByShort: Map<String, UUID> = items.associate { short(it.id) to it.id }
    }

    /** Four items (edge + text, edge + tag, edge only, tag only) and a document linking to the reference item. */
    private suspend fun Env.seedRelated(): Fixture {
        val p = rig.make("Hangar container")
        val ref = rig.make("Zorvath quasarium", p, tags = "orbit,beacon")
        val b = rig.make("Quasarium brigade", p)
        val a = rig.make("Kelmora", p, tags = "orbit")
        val c = rig.make("Wombrel", p)
        val d = rig.make("Ypsilon", p, tags = "beacon")
        for (counterpart in listOf(b, a, c)) {
            rig.raw
                .dependencyRepository()
                .create(Dependency(fromItemId = ref.id, toItemId = counterpart.id, type = DependencyType.RELATES_TO))
        }
        val doc =
            DocumentService(rig.raw, db.unitOfWork())
                .write(p.id, DocumentKind.PLAN, "plan-link", "An ordinary body without special words", title = "Titled plan")
                .value()
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
        return Fixture(ref, listOf(b, a, c, d), doc)
    }

    private data class Hit(
        val id: String,
        val kind: String,
        val signals: List<String>
    )

    private fun expectedHits(
        reply: Reply,
        fx: Fixture
    ): List<Hit> =
        reply.data.getValue("items").jsonArray.map { it.jsonObject }.map { hit ->
            val kind = hit.getValue("kind").jsonPrimitive.content
            val id =
                when (kind) {
                    "item" -> fx.itemByShort.getValue(hit.getValue("itemId").jsonPrimitive.content)
                    "document" -> fx.doc.id
                    else -> fail("unknown hit kind $kind")
                }
            val ranks =
                hit
                    .getValue("explain")
                    .jsonObject
                    .getValue("ranks")
                    .jsonObject.keys
            Hit(id.toString(), kind, REST_SIGNAL_ORDER.filter { it in ranks })
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

    private fun related(itemId: Any) = """{"select":"related","itemId":"$itemId","explain":true}"""

    // ------------------------------------------------------------------ S4

    @Test
    fun `S4 a REST related call records the page hit by hit and the MCP call over the same data records the same text`(): Unit =
        restApp {
            val fx = seedRelated()

            val rest = ops("items_read", related(fx.ref.id), token = ADMIN_TOKEN)

            assertEquals(200, rest.status, "${rest.body}")
            val expected = expectedHits(rest, fx)
            assertTrue(expected.size >= 5, "fixture: four items and a document are related: $expected")
            assertTrue(expected.any { it.signals.size >= 2 }, "fixture: a hit is found by several signals: $expected")
            assertEquals(REST_SIGNAL_ORDER.toSet(), expected.flatMap { it.signals }.toSet(), "fixture: every signal finds something")
            val restRow = rowOf(rest.reqId)
            assertEquals("rest", restRow["surface"])
            assertEquals(expected, hitSignalsOf(restRow), "page order, canonical ids, kinds and rank signals")

            val serverInfo = Implementation(name = "df10-rest-server", version = "1.0.0")
            val server = Server(serverInfo = serverInfo, options = inMemoryTestServerOptions())
            McpOperationAdapter(buildToolDispatcher(rig.ctx), callLog = writer)
                .register(server, ToolRegistry(buildToolSpecs()), ToolProfile.ORCHESTRATION)
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            val mcp =
                Client(
                    clientInfo = Implementation(name = "df10-rest-client", version = "1.0.0"),
                    options = ClientOptions(capabilities = ClientCapabilities())
                )
            server.createSession(serverTransport)
            mcp.connect(clientTransport)
            try {
                val result =
                    mcp.callTool(
                        buildCallToolRequest {
                            name = "items_read"
                            arguments(
                                JsonObject(
                                    mapOf(
                                        "select" to JsonPrimitive("related"),
                                        "itemId" to JsonPrimitive(fx.ref.id.toString()),
                                        "explain" to JsonPrimitive(true)
                                    )
                                )
                            )
                        }
                    )
                val mcpReqId =
                    assertNotNull(
                        result.meta
                            ?.get("reqId")
                            ?.jsonPrimitive
                            ?.content
                    )
                val mcpRow = rowOf(mcpReqId)
                assertEquals("mcp", mcpRow["surface"])
                assertEquals(restRow["hit_signals"], mcpRow["hit_signals"], "both transports record the same text for the same call")
            } finally {
                closeInMemoryPair(mcp, server)
            }
        }

    @Test
    fun `S4 a REST related call with an empty page, a REST get and a resource GET leave hit_signals NULL`(): Unit =
        restApp {
            val fx = seedRelated()
            val control = ops("items_read", related(fx.ref.id), token = ADMIN_TOKEN)
            assertNotNull(rowOf(control.reqId)["hit_signals"], "control: the related call on the same data records hits")
            val lonely = rig.make("Lonely")

            val empty = ops("items_read", related(lonely.id), token = ADMIN_TOKEN)
            val get = ops("items_read", """{"select":"get","itemId":"${fx.ref.id}"}""", token = ADMIN_TOKEN)
            val resource =
                http.get("/api/v1/items/${fx.ref.id}") { header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN") }

            assertEquals(
                0,
                empty.data
                    .getValue("items")
                    .jsonArray.size,
                "fixture: nothing is related to the lonely item"
            )
            assertNull(rowOf(empty.reqId)["hit_signals"], "an empty page is NULL")
            assertNull(rowOf(get.reqId)["hit_signals"])
            val resourceRow = rowOf(assertNotNull(resource.headers["X-Req-Id"]))
            assertNull(resourceRow["hit_signals"])
            assertNull(lifecycleFree(resourceRow), "a resource GET is a read: no lifecycle-free count either")
        }

    // ------------------------------------------------------------------ S6 .. S10 over REST

    @Test
    fun `S6 a REST start on a lifecycle-free item records one rejection and a start on a plain item records none`(): Unit =
        restApp {
            val page = rig.page("Page")
            val plain = rig.make("Plain")

            val refused = ops("advance", """{"ops":[${transition(page.id, "start")}]}""")
            val control = ops("advance", """{"ops":[${transition(plain.id, "start")}]}""")

            assertTrue(!refused.isOk, "fixture: the page cannot start")
            assertTrue(control.isOk, "control: an ordinary item starts: ${control.body}")
            val row = rowOf(refused.reqId)
            assertEquals(1L, lifecycleFree(row))
            assertNull(lifecycleFree(rowOf(control.reqId)), "an ok call is NULL, never 0")
        }

    @Test
    fun `S7 a REST non-atomic batch counts lifecycle-free rejections once each and skips role-bearing ones and successes`(): Unit =
        restApp {
            val page = rig.page("Page")
            val plain = rig.make("Plain")
            val done = rig.make("Done", role = Role.TERMINAL)

            val elements =
                listOf(transition(page.id, "start"), claim(page.id), transition(plain.id, "start"), transition(done.id, "complete"))

            val reply = ops("advance", """{"ops":[${elements.joinToString(",")}]}""")

            assertEquals(listOf(false, false, true, false), reply.oks, "fixture: three of four fail")
            val roleBearing = reply.results[3].getValue("error").jsonObject
            assertEquals("invalid_transition", roleBearing.getValue("code").jsonPrimitive.content, "fixture: a role-bearing rejection")
            assertEquals(2L, lifecycleFree(rowOf(reply.reqId)))
        }

    @Test
    fun `S8 a REST atomic batch whose lifecycle-free element fails records one rejection`(): Unit =
        restApp {
            val page = rig.page("Page")
            val plain = rig.make("Plain")

            val elements = listOf(transition(plain.id, "start"), transition(page.id, "start"))

            val reply = ops("advance", """{"atomic":true,"ops":[${elements.joinToString(",")}]}""")

            assertTrue(!reply.isOk, "fixture: the atomic batch fails")
            assertEquals(1L, lifecycleFree(rowOf(reply.reqId)))
        }

    @Test
    fun `S9 a REST BLOCKS dependency with a lifecycle-free endpoint records one rejection and RELATES_TO records none`(): Unit =
        restApp {
            val plain = rig.make("Plain")
            val page = rig.page("Page")

            val refused = ops("deps_write", """{"ops":[${edge(plain.id, page.id)}]}""")
            val allowed = ops("deps_write", """{"ops":[${edge(plain.id, page.id, "relates_to")}]}""")

            assertTrue(!refused.isOk, "fixture: a BLOCKS edge into a page is refused")
            assertTrue(allowed.isOk, "control: RELATES_TO to the same page is stored: ${allowed.body}")
            val row = rowOf(refused.reqId)
            assertEquals(1L, lifecycleFree(row))
            assertNull(lifecycleFree(rowOf(allowed.reqId)))
        }

    @Test
    fun `S10 a REST not-found rejection records NULL, never 0`(): Unit =
        restApp {
            val reply = ops("advance", """{"ops":[${transition(UUID.randomUUID(), "start")}]}""")

            assertTrue(!reply.isOk, "fixture: the unknown item fails")
            assertNull(lifecycleFree(rowOf(reply.reqId)))
        }
}
