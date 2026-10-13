package io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops

import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiAuthMode
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiPrincipal
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiScope
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.findByFilters
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.configName
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.grants
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.ADMIN_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.WRITE_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.WRITE_TOKEN_ID
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.makeWriteAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.parity.ParityFixtureTools
import io.github.jpicklyk.mcptask.current.parity.ParitySeeder
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Independent tests (test-author seat, item f67e1762) for `POST /api/v1/ops/{tool}` and the shared capability predicate,
 * scenarios S1, S3, S4, S6 to S11, S15 and S16 of the frozen test-plan (NEW-SURFACE, except S16), all through the production
 * REST topology ([installParityRestApp]: real composition, ContentNegotiation, `installRestApiRoutes`).
 *
 * Oracles: task-scope items 4 to 7 and 10, decisions D1 (the bearer is the principal, a body `actor` is ignored, REST
 * idempotency is namespaced per token), D5 (unknown tool and header faults are `invalid_request`, no 415), D6 (the ORCHESTRATION
 * superset is served), the envelope spec (statuses 200, 207, 400, 401, 403, 413; `forbidden` detail is
 * `{scope, required}`; per-element results keep their position), the frozen route order (principal, Content-Type,
 * Idempotency-Key header, bounded read, JSON object, tool lookup, dispatch), RFC 3986 section 2.3 (a percent-encoded unreserved
 * character equals the character) and RFC 7231 section 3.1.1.1 (media type names are case-insensitive). Expected values are
 * literals written from those clauses.
 *
 * Harness: [FixtureTools] plus [ParityFixtureTools] behind the real routes; each "not executed / not stored" assertion is paired
 * with a control through the same call shape that DOES execute (the handler's `seen` list and the database prove it).
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class OpsRoutesTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val fx = FixtureTools()
    private val pf = ParityFixtureTools()
    private val registry: ToolRegistry by lazy { ToolRegistry(fx.registry.tools + pf.registry.tools) }

    private val maxBody = 1_048_576

    private fun withApp(
        auth: ApiAuthConfig.Bearer = makeWriteAuthConfig(),
        block: suspend ApplicationTestBuilder.(EventLogRig) -> Unit
    ) = testApplication {
        val rig = EventLogRig.build(db.db, dir)
        application { installParityRestApp(rig, auth, registry) }
        block(rig)
    }

    private fun tokenConfig(
        raw: String,
        id: String,
        capabilities: Set<ApiCapability>
    ): ApiAuthConfig.Bearer {
        val base = makeWriteAuthConfig()
        val principal = ApiPrincipal(id, ApiScope(rootIds = null, tagsInclude = emptySet()), capabilities, ApiAuthMode.BEARER)
        return ApiAuthConfig.Bearer(tokens = base.tokens + (HashBytes(sha256(raw)) to principal))
    }

    private suspend fun HttpClient.ops(
        tool: String,
        body: String,
        token: String? = WRITE_TOKEN,
        ct: ContentType? = ContentType.Application.Json,
        extra: List<Pair<String, String>> = emptyList(),
        path: String = "/api/v1/ops/$tool"
    ): HttpResponse =
        post(path) {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            if (ct != null) contentType(ct)
            extra.forEach { (k, v) -> header(k, v) }
            setBody(rawBody(body))
        }

    /** A byte body with NO content type of its own (a plain ByteArray body would be sent as application/octet-stream). */
    private fun rawBody(text: String): OutgoingContent =
        object : OutgoingContent.ByteArrayContent() {
            private val data = text.toByteArray(Charsets.UTF_8)

            override val contentType: ContentType? = null

            override val contentLength: Long = data.size.toLong()

            override fun bytes(): ByteArray = data
        }

    private suspend fun HttpResponse.envelope(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.error(): JsonObject = getValue("error").jsonObject

    private fun JsonObject.code(): String = error().getValue("code").jsonPrimitive.content

    private fun JsonObject.detailOf(): JsonObject = getValue("detail").jsonObject

    private fun JsonObject.results(): JsonArray = getValue("data").jsonObject.getValue("results").jsonArray

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.firstField(): JsonObject =
        error()
            .detailOf()
            .getValue("fields")
            .jsonArray
            .first()
            .jsonObject

    private fun JsonObject.elementError(i: Int): JsonObject = results()[i].jsonObject.getValue("error").jsonObject

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun create(
        title: String,
        extra: String = ""
    ) = """{"op":"create","title":"$title"$extra}"""

    private fun ops(vararg elements: String) = """{"ops":[${elements.joinToString(",")}]}"""

    private suspend fun EventLogRig.titles(): List<String> =
        raw
            .workItemRepository()
            .findByFilters()
            .items
            .map { it.title }
            .sorted()

    private fun assertForbidden(
        element: JsonObject,
        scope: String,
        required: String
    ) {
        assertEquals("forbidden", element.str("code"))
        assertEquals(scope, element.detailOf().str("scope"))
        assertEquals(required, element.detailOf().str("required"))
    }
    // ------------------------------------------------------------------ S1 happy

    @Test
    fun `S1 an admin select returns the ok envelope with status 200 and X-Req-Id equal to meta reqId`() =
        withApp { _ ->
            val response = client.ops("fx_read", """{"select":"get","id":"${FixtureTools.GET_OK}"}""", ADMIN_TOKEN)
            assertEquals(200, response.status.value)
            val reqId = assertNotNull(response.headers["X-Req-Id"], "X-Req-Id")
            assertTrue(ReqId.isValid(reqId), reqId)
            assertEquals("""{"ok":true,"data":"got:aaaaaaaa:1","meta":{"reqId":"$reqId"}}""", response.bodyAsText())
            assertTrue(response.contentType()?.match(ContentType.Application.Json) == true, "JSON content type: ${response.contentType()}")
            assertEquals(1, fx.seen.size)
            assertEquals("rest", fx.seen.single().surface)
        }

    @Test
    fun `S1 ops run and the orchestration-only tools are served (the superset profile)`() =
        withApp { rig ->
            val peek = client.ops("fx_peek", """{"select":"peek","what":"x"}""", TEST_TOKEN)
            assertEquals(200, peek.status.value)
            assertEquals("peek:x", peek.envelope().str("data"))

            val reset = client.ops("fx_admin", ops("""{"op":"reset","reason":"r"}"""))
            assertEquals(200, reset.status.value)
            assertEquals(
                "reset:r",
                reset
                    .envelope()
                    .results()[0]
                    .jsonObject
                    .str("id")
            )

            val purge = client.ops("fx_write", ops("""{"op":"purge","id":"aaaaaaaa"}"""))
            assertEquals(
                "purged:aaaaaaaa",
                purge
                    .envelope()
                    .results()[0]
                    .jsonObject
                    .str("id")
            )
            assertEquals(emptyList(), rig.titles(), "no work item was created by these calls")
        }

    // ------------------------------------------------------------------ S3 call_log

    @Test
    fun `S3 the call log records the registry tool name, operation, batch counts and the bearer principal`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = CallLogWriter(rig.composition.unitOfWork, SqliteCallLogStore(db.databaseManager), flushInterval = 1.hours)
            application { installParityRestApp(rig, makeWriteAuthConfig(), registry, writer) }

            client.ops("fx_read", """{"select":"get","id":"${FixtureTools.GET_OK}"}""")
            client.ops("fx_read", """{"select":"list"}""")
            client.ops("fx_write", ops(create("A"), create(FixtureTools.FAIL_TITLE), create("C")))
            client.ops("fx_write", ops(create("D"), """{"op":"rename","id":"aaaaaaaa"}"""))
            writer.flushNow()

            val rows = CallLogRows.read(db.jdbcUrl)
            assertEquals(4, rows.size, "one row per /ops call: $rows")
            assertTrue(rows.none { (it["tool"] as String).startsWith("POST") }, "tool is the registry name, not the route: $rows")
            assertTrue(rows.all { it["surface"] == "rest" }, rows.toString())
            assertTrue(rows.all { it["principal_id"] == "api:$WRITE_TOKEN_ID" }, rows.toString())
            assertTrue(rows.all { it["principal_kind"] == "external" }, rows.toString())

            val get = rows.single { it["tool"] == "fx_read" && it["operation"] == "get" }
            assertNull(get["batch_size"])

            val list = rows.single { it["tool"] == "fx_read" && it["operation"] == "list" }
            assertEquals(2L, list["result_count"], "data.items holds two entries")

            val partial = rows.single { it["tool"] == "fx_write" && it["operation"] == "create" }
            assertEquals(3L, partial["batch_size"])
            assertEquals(1L, partial["failed_count"])

            val mixed = rows.single { it["tool"] == "fx_write" && it["operation"] == "mixed" }
            assertEquals(2L, mixed["batch_size"])
        }

    // ------------------------------------------------------------------ S4 shared predicate

    @Test
    fun `S4 ADMIN grants every capability, any other capability grants only itself, nothing grants nothing`() {
        for (required in ApiCapability.entries) {
            assertTrue(setOf(ApiCapability.ADMIN).grants(required), "ADMIN grants $required")
            assertTrue(setOf(required).grants(required), "$required grants itself")
            assertFalse(emptySet<ApiCapability>().grants(required), "the empty set grants nothing ($required)")
            for (held in ApiCapability.entries.filter { it != required && it != ApiCapability.ADMIN }) {
                assertFalse(setOf(held).grants(required), "$held does not grant $required")
            }
        }
        assertTrue(setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS).grants(ApiCapability.WRITE_ITEMS))
        assertFalse(setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS).grants(ApiCapability.ADMIN))
        assertFalse(setOf(ApiCapability.WRITE_ITEMS).grants(ApiCapability.READ), "a write capability does not imply read")
    }

    @Test
    fun `S4 probe the canonical kebab-case spelling of every capability`() {
        val expected =
            mapOf(
                ApiCapability.READ to "read",
                ApiCapability.WRITE_NOTES to "write-notes",
                ApiCapability.WRITE_ITEMS to "write-items",
                ApiCapability.ADVANCE to "advance",
                ApiCapability.MANAGE_DEPENDENCIES to "manage-dependencies",
                ApiCapability.WRITE_CONFIG to "write-config",
                ApiCapability.ADMIN to "admin"
            )
        assertEquals(ApiCapability.entries.toSet(), expected.keys, "fixture: every capability has an expected spelling")
        for ((capability, spelling) in expected) assertEquals(spelling, capability.configName())
    }

    // ------------------------------------------------------------------ S6 write op without the capability

    @Test
    fun `S6 a read-only token on a write op gets a 207 with a forbidden element, nothing runs and nothing is stored`() =
        withApp { rig ->
            val response = client.ops("fx_write", ops(create("A")), TEST_TOKEN)
            assertEquals(207, response.status.value)
            val body = response.envelope()
            assertEquals("partial_failure", body.code())
            assertForbidden(body.elementError(0), "capability", "write-items")
            assertEquals(
                """{"ok":0,"failed":1}""",
                body
                    .getValue("data")
                    .jsonObject
                    .getValue("summary")
                    .toString()
            )
            assertEquals(emptyList(), rig.titles())
            assertTrue(fx.seen.isEmpty(), "the op never executed")

            val control = client.ops("fx_write", ops(create("A")), WRITE_TOKEN)
            assertEquals(200, control.status.value)
            assertEquals(listOf("A"), rig.titles(), "control: a write-items token runs the same body")
            assertEquals(1, fx.seen.size)
        }

    @Test
    fun `S6 atomic with a forbidden element is a 403 forbidden whose error index is 0`() =
        withApp { rig ->
            val response = client.ops("fx_write", """{"ops":[${create("A")}],"atomic":true}""", TEST_TOKEN)
            assertEquals(403, response.status.value)
            val body = response.envelope()
            assertEquals("forbidden", body.code())
            assertEquals("0", body.error().str("index"))
            assertForbidden(body.error(), "capability", "write-items")
            assertFalse("data" in body, "a whole-call failure has no per-element data")
            assertEquals(emptyList(), rig.titles())

            val control = client.ops("fx_write", """{"ops":[${create("A")}],"atomic":true}""", WRITE_TOKEN)
            assertEquals(200, control.status.value)
            assertEquals(listOf("A"), rig.titles())
        }

    // ------------------------------------------------------------------ S7 select without read

    @Test
    fun `S7 a token without read on a select gets 403 forbidden capability read and the handler does not run`() {
        val auth = tokenConfig("no-read-token", "no-read", setOf(ApiCapability.WRITE_ITEMS))
        withApp(auth) { _ ->
            val response = client.ops("fx_read", """{"select":"get","id":"${FixtureTools.GET_OK}"}""", "no-read-token")
            assertEquals(403, response.status.value)
            val body = response.envelope()
            assertForbidden(body.error(), "capability", "read")
            assertFalse("data" in body)
            assertTrue(fx.seen.isEmpty(), "the handler never ran")

            val control = client.ops("fx_read", """{"select":"get","id":"${FixtureTools.GET_OK}"}""", TEST_TOKEN)
            assertEquals(200, control.status.value)
            assertEquals(1, fx.seen.size, "control: a read token runs the same select")
        }
    }

    @Test
    fun `S7 describe needs no capability`() {
        val auth = tokenConfig("no-read-token", "no-read", setOf(ApiCapability.WRITE_ITEMS))
        withApp(auth) { _ ->
            val response = client.ops("fx_read", """{"describe":true}""", "no-read-token")
            assertEquals(200, response.status.value)
            assertTrue(response.envelope().str("ok").toBoolean())
            assertTrue(fx.seen.isEmpty(), "describe runs no handler")
        }
    }

    // ------------------------------------------------------------------ S8 forbidden wins over invalid

    @Test
    fun `S8 a forbidden op with invalid fields is forbidden, not invalid_request or unknown_parameter`() =
        withApp { rig ->
            val typeFault = client.ops("fx_write", ops("""{"op":"create","title":7}"""), TEST_TOKEN).envelope()
            assertForbidden(typeFault.elementError(0), "capability", "write-items")
            val unknownField = client.ops("fx_write", ops(create("A", ""","foo":1""")), TEST_TOKEN).envelope()
            assertForbidden(unknownField.elementError(0), "capability", "write-items")

            val control = client.ops("fx_write", ops("""{"op":"create","title":7}"""), WRITE_TOKEN).envelope()
            assertEquals(
                "invalid_request",
                control.elementError(0).str("code"),
                "control: with the capability the same fault is invalid_request"
            )
            assertEquals(
                "/ops/0/title",
                control
                    .elementError(0)
                    .detailOf()
                    .getValue("fields")
                    .jsonArray[0]
                    .jsonObject
                    .str("field")
            )
            assertEquals(emptyList(), rig.titles())
            assertTrue(fx.seen.isEmpty())
        }
    // ------------------------------------------------------------------ S9 per element

    private val x: UUID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val y: UUID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

    private fun renameEl(
        id: UUID,
        title: String,
        key: String? = null
    ) = """{"op":"rename","id":"$id","title":"$title"${key?.let { ""","idempotencyKey":"$it"""" }.orEmpty()}}"""

    private fun retagEl(
        id: UUID,
        tags: String,
        key: String? = null
    ) = """{"op":"retag","id":"$id","tags":"$tags"${key?.let { ""","idempotencyKey":"$it"""" }.orEmpty()}}"""

    private suspend fun EventLogRig.seedXY() {
        val seeder = ParitySeeder(raw.workItemRepository())
        seeder.item(x, "X original", tags = "orig-x")
        seeder.item(y, "Y original", tags = "orig-y")
    }

    private val itemsOnly get() = tokenConfig("items-only-token", "items-only", setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS))

    @Test
    fun `S9 an allowed element then a forbidden one gives ok plus forbidden and only the first effect is stored`() =
        withApp(itemsOnly) { rig ->
            rig.seedXY()
            val response = client.ops(ParityFixtureTools.EDIT, ops(renameEl(x, "X renamed"), retagEl(y, "hijacked")), "items-only-token")
            assertEquals(207, response.status.value, response.bodyAsText())
            val body = response.envelope()
            assertEquals("true", body.results()[0].jsonObject.str("ok"))
            assertEquals("false", body.results()[1].jsonObject.str("ok"))
            assertForbidden(body.elementError(1), "capability", "write-notes")
            assertEquals(
                """{"ok":1,"failed":1}""",
                body
                    .getValue("data")
                    .jsonObject
                    .getValue("summary")
                    .toString()
            )

            val items = rig.raw.workItemRepository()
            assertEquals("X renamed", items.getById(x)!!.title, "the allowed element's effect is stored")
            assertEquals("orig-y", items.getById(y)!!.tags, "the forbidden element's effect is not stored")
        }

    @Test
    fun `S9 a forbidden element first keeps its position and the allowed one after it still runs`() =
        withApp(itemsOnly) { rig ->
            rig.seedXY()
            val body =
                client.ops(ParityFixtureTools.EDIT, ops(retagEl(y, "hijacked"), renameEl(x, "X renamed")), "items-only-token").envelope()
            assertForbidden(body.elementError(0), "capability", "write-notes")
            assertEquals("true", body.results()[1].jsonObject.str("ok"))
            val items = rig.raw.workItemRepository()
            assertEquals("X renamed", items.getById(x)!!.title)
            assertEquals("orig-y", items.getById(y)!!.tags)
        }

    @Test
    fun `S9 a forbidden element with an idempotency key stores no record while the allowed keyed element does`() =
        withApp(itemsOnly) { rig ->
            rig.seedXY()
            val body =
                client
                    .ops(
                        ParityFixtureTools.EDIT,
                        ops(renameEl(x, "X renamed", key = "k-allowed"), retagEl(y, "hijacked", key = "k-forbidden")),
                        "items-only-token"
                    ).envelope()
            assertEquals("true", body.results()[0].jsonObject.str("ok"))
            assertForbidden(body.elementError(1), "capability", "write-notes")
            assertEquals(1, db.idempotencyRecordCount(), "only the executed keyed element is recorded")
        }

    // ------------------------------------------------------------------ S10 body actor ignored

    @Test
    fun `S10 a body actor on an ops call is ignored and the principal is the bearer, external`() =
        withApp { rig ->
            val body = """{"actor":{"id":"orchestrator","kind":"orchestrator"},"ops":[${create("A")}]}"""
            val response = client.ops("fx_write", body)
            assertEquals(200, response.status.value, response.bodyAsText())
            val principal = assertNotNull(fx.seen.single().principal)
            assertEquals("api:$WRITE_TOKEN_ID", principal.claim.id)
            assertEquals(ActorKind.EXTERNAL, principal.claim.kind)

            // control: the same body through the dispatcher's default (MCP) path lifts the body actor
            val dispatcher = buildToolDispatcher(rig.composition.toolContext)
            dispatcher.dispatch(fx.writeTool, ToolProfile.ORCHESTRATION, obj(body), "k7f3q9ab", "mcp")
            assertEquals("orchestrator", assertNotNull(fx.seen.last().principal).claim.id)
        }

    @Test
    fun `S10 a body actor on a select, a spoofed user kind and a non-object actor are all ignored`() =
        withApp { _ ->
            val spoof = client.ops("fx_read", """{"actor":{"id":"spoof","kind":"user"},"select":"get","id":"${FixtureTools.GET_OK}"}""")
            assertEquals(200, spoof.status.value, spoof.bodyAsText())
            val nonObject = client.ops("fx_read", """{"actor":5,"select":"get","id":"${FixtureTools.GET_OK}"}""")
            assertEquals(200, nonObject.status.value, nonObject.bodyAsText())
            assertEquals(listOf("api:$WRITE_TOKEN_ID", "api:$WRITE_TOKEN_ID"), fx.seen.map { it.principal?.claim?.id })
        }

    // ------------------------------------------------------------------ S11 per-token idempotency

    @Test
    fun `S11 the same idempotency key and payload from two tokens is not replayed across tokens, but is within one`() =
        withApp { rig ->
            val body = ops(create("R", ""","idempotencyKey":"key-1""""))

            val first = client.ops("fx_write", body, WRITE_TOKEN).envelope()
            assertNull(first.results()[0].jsonObject["replayed"])
            val otherToken = client.ops("fx_write", body, ADMIN_TOKEN)
            assertEquals(200, otherToken.status.value)
            assertNull(otherToken.envelope().results()[0].jsonObject["replayed"], "another token's key namespace: executed, not replayed")
            assertEquals(listOf("R", "R"), rig.titles(), "executed once per token")

            val again = client.ops("fx_write", body, WRITE_TOKEN).envelope()
            assertEquals("true", again.results()[0].jsonObject.str("replayed"), "control: the same token replays")
            assertEquals(listOf("R", "R"), rig.titles(), "the replay did not execute")
            assertEquals(2, fx.seen.size)
        }

    // ------------------------------------------------------------------ S15 400 / 413

    @Test
    fun `S15 an unknown tool is invalid_request on field tool and names the visible tools`() =
        withApp { _ ->
            val response = client.ops("nope", """{"select":"get"}""")
            assertEquals(400, response.status.value)
            val body = response.envelope()
            assertEquals("invalid_request", body.code())
            assertEquals("tool", body.firstField().str("field"))
            val reason = body.firstField().str("reason")
            assertTrue(reason.startsWith("must be one of:"), reason)
            for (name in registry.visible(ToolProfile.ORCHESTRATION).map { it.name }) assertTrue(name in reason, "$name in: $reason")
            assertTrue(fx.seen.isEmpty())
        }

    @Test
    fun `S15 probe tool name casing and percent-encoding`() =
        withApp { _ ->
            val upper = client.ops("Fx_Read", """{"select":"get","id":"${FixtureTools.GET_OK}"}""")
            assertEquals(400, upper.status.value, "tool names are case-sensitive")
            assertEquals("tool", upper.envelope().firstField().str("field"))
            assertTrue(fx.seen.isEmpty())

            val encoded = client.ops("fx%5Fread", """{"select":"get","id":"${FixtureTools.GET_OK}"}""")
            assertEquals(200, encoded.status.value, "%5F is the unreserved underscore (RFC 3986 2.3): ${encoded.bodyAsText()}")
            assertEquals(1, fx.seen.size)
        }

    @Test
    fun `S15 Content-Type must be absent or application json with parameters`() =
        withApp { _ ->
            val body = """{"select":"get","id":"${FixtureTools.GET_OK}"}"""
            for (bad in listOf(
                ContentType.Text.Plain,
                ContentType.parse("application/json-patch+json"),
                ContentType.parse("application/jsonx")
            )) {
                val response = client.ops("fx_read", body, ct = bad)
                assertEquals(400, response.status.value, "$bad")
                val envelope = response.envelope()
                assertEquals("invalid_request", envelope.code(), "$bad")
                assertEquals("Content-Type", envelope.firstField().str("field"), "$bad")
            }
            assertTrue(fx.seen.isEmpty(), "none of the rejected calls ran")

            for (good in listOf(ContentType.parse("application/json; charset=utf-8"), ContentType.parse("application/JSON"), null)) {
                val accepted = client.ops("fx_read", body, ct = good)
                assertEquals(200, accepted.status.value, "control: $good is accepted: ${accepted.bodyAsText()}")
            }
            assertEquals(3, fx.seen.size)
        }

    @Test
    fun `S15 an Idempotency-Key header is invalid_request on that header, in any case`() =
        withApp { _ ->
            val body = """{"select":"get","id":"${FixtureTools.GET_OK}"}"""
            for (name in listOf("Idempotency-Key", "idempotency-key")) {
                val response = client.ops("fx_read", body, extra = listOf(name to "abc"))
                assertEquals(400, response.status.value, name)
                val envelope = response.envelope()
                assertEquals("invalid_request", envelope.code())
                assertEquals("Idempotency-Key", envelope.firstField().str("field"))
            }
            assertTrue(fx.seen.isEmpty())
            assertEquals(200, client.ops("fx_read", body).status.value, "control: without the header it runs")
        }

    @Test
    fun `S15 a body that is not a JSON object is invalid_request on field dollar`() =
        withApp { _ ->
            for (bad in listOf("[1]", "{", "", "\"text\"", "null", "7")) {
                val response = client.ops("fx_read", bad)
                assertEquals(400, response.status.value, "body <$bad>")
                val envelope = response.envelope()
                assertEquals("invalid_request", envelope.code(), "body <$bad>")
                assertEquals("$", envelope.firstField().str("field"), "body <$bad>")
            }
            val empty = client.ops("fx_write", """{"ops":[]}""")
            assertEquals(400, empty.status.value)
            assertEquals("/ops", empty.envelope().firstField().str("field"), "control: an object body reaches the dispatcher")
            assertTrue(fx.seen.isEmpty())
        }

    @Test
    fun `S15 a body of exactly 1 MiB is accepted and more is 413 payload_too_large with the declared length`() =
        withApp { _ ->
            val core = """{"select":"get","id":"${FixtureTools.GET_OK}"}"""

            fun padded(total: Int) = core + " ".repeat(total - core.length)

            val exact = client.ops("fx_read", padded(maxBody))
            assertEquals(200, exact.status.value, "exactly the limit: ${exact.bodyAsText().take(200)}")

            for (extra in listOf(1, 1000)) {
                val over = client.ops("fx_read", padded(maxBody + extra))
                assertEquals(413, over.status.value, "limit + $extra")
                val body = over.envelope()
                assertEquals("payload_too_large", body.code())
                assertEquals("$maxBody", body.error().detailOf().str("max"))
                assertEquals("${maxBody + extra}", body.error().detailOf().str("actual"), "actual is the declared length")
            }
            assertEquals(1, fx.seen.size, "only the exact-limit call ran")
        }

    @Test
    fun `S15 a chunked body over the limit is 413 payload_too_large with actual max plus one`() =
        withApp { _ ->
            val core = """{"select":"get","id":"${FixtureTools.GET_OK}"}"""
            val bytes = (core + " ".repeat(maxBody + 1000 - core.length)).toByteArray(Charsets.UTF_8)
            val chunked =
                object : OutgoingContent.ReadChannelContent() {
                    override val contentType: ContentType = ContentType.Application.Json

                    override fun readFrom(): ByteReadChannel = ByteReadChannel(bytes)
                }
            val response =
                client.post("/api/v1/ops/fx_read") {
                    header(HttpHeaders.Authorization, "Bearer $WRITE_TOKEN")
                    setBody(chunked)
                }
            assertEquals(413, response.status.value, response.bodyAsText().take(200))
            val body = response.envelope()
            assertEquals("payload_too_large", body.code())
            assertEquals("$maxBody", body.error().detailOf().str("max"))
            assertEquals("${maxBody + 1}", body.error().detailOf().str("actual"), "bytes read = max + 1")
            assertTrue(fx.seen.isEmpty())
        }

    @Test
    fun `S15 the checks run in the frozen order`() =
        withApp { _ ->
            val ok = """{"select":"get","id":"${FixtureTools.GET_OK}"}"""
            // principal before Content-Type: with a token the same request is 400, without one 401
            assertEquals(401, client.ops("fx_read", ok, token = null, ct = ContentType.Text.Plain).status.value)
            assertEquals(400, client.ops("fx_read", ok, ct = ContentType.Text.Plain).status.value)

            // Content-Type before the Idempotency-Key header
            val both = client.ops("fx_read", ok, ct = ContentType.Text.Plain, extra = listOf("Idempotency-Key" to "k")).envelope()
            assertEquals("Content-Type", both.firstField().str("field"))

            // Idempotency-Key header before the body is read or parsed
            val keyThenBody = client.ops("fx_read", "{", extra = listOf("Idempotency-Key" to "k")).envelope()
            assertEquals("Idempotency-Key", keyThenBody.firstField().str("field"))
            val huge = " ".repeat(maxBody + 10)
            val keyThenSize = client.ops("fx_read", huge, extra = listOf("Idempotency-Key" to "k"))
            assertEquals(400, keyThenSize.status.value)
            assertEquals("Idempotency-Key", keyThenSize.envelope().firstField().str("field"))

            // the body is parsed before the tool is looked up
            val bodyThenTool = client.ops("nope", "[]").envelope()
            assertEquals("$", bodyThenTool.firstField().str("field"))
            assertTrue(fx.seen.isEmpty())
        }

    // ------------------------------------------------------------------ S16 authentication

    @Test
    fun `S16 no token and a wrong token are 401 and nothing runs`() =
        withApp { _ ->
            val body = """{"select":"get","id":"${FixtureTools.GET_OK}"}"""
            assertEquals(401, client.ops("fx_read", body, token = null).status.value)
            assertEquals(401, client.ops("fx_read", body, token = "not-a-real-token").status.value)
            assertTrue(fx.seen.isEmpty())
            assertEquals(200, client.ops("fx_read", body, token = TEST_TOKEN).status.value, "control: a valid token runs")
            assertEquals(1, fx.seen.size)
        }

    @Test
    fun `the reqId equals the X-Req-Id header on an error response too`() =
        withApp { _ ->
            val response = client.ops("fx_read", """{"select":"get","id":"${FixtureTools.GET_MISSING}"}""")
            assertEquals(404, response.status.value)
            val reqId = assertNotNull(response.headers["X-Req-Id"])
            val body = response.envelope()
            assertEquals("not_found", body.code())
            assertEquals(reqId, body.getValue("meta").jsonObject.str("reqId"))
            assertTrue(ReqId.isValid(reqId))
        }
}
