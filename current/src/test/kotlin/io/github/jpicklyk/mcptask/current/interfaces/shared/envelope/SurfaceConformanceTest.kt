package io.github.jpicklyk.mcptask.current.interfaces.shared.envelope

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.bootstrap.buildProfileServer
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.bootstrap.installMcpStreamableHttp
import io.github.jpicklyk.mcptask.current.bootstrap.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.routeTemplate
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
import io.github.jpicklyk.mcptask.current.interfaces.mcp.HOST_REQUIRED
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.visibleNames
import io.github.jpicklyk.mcptask.current.test.EnvelopeConformance
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.pluginOrNull
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Q32 (task-scope E): every JSON body the 4.0 surface writes is the response envelope (envelope spec sections 2 and 6, oracle
 * [EnvelopeConformance]), with the error code's catalog status, except the six [EXEMPTIONS] listed (each with its reason).
 *
 * 1. Tool bodies: every tool in every profile that shows it answers `describe: true` and an unknown parameter with a conforming
 *    envelope, through the dispatcher both transports share and through `POST /api/v1/ops/{tool}` (scenario S7). The
 *    ProductionParity catalog's bodies are checked where the catalog runs: both legs of every parity runner go through
 *    `callMcp` and `parityRestPost`, which assert [EnvelopeConformance] on every body (S6).
 * 2. REST routes: the production route tree (bootstrap's [installMcpStreamableHttp] then [installRestApiRoutes], the real
 *    wiring) is walked, and every `/api/v1` and `/.well-known` endpoint must have probes in [PROBES] or be an exempt route;
 *    each probe (no token, missing capability, happy, unknown query parameter, bad Host) must conform (S13, S8-S11, S14).
 *    `/mcp` is a JSON-RPC transport, not a REST route: only its Host rejection is an envelope, probed in S10.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class SurfaceConformanceTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    /** One reasoned exemption from the envelope rule. */
    data class Exemption(
        val what: String,
        val reason: String
    )

    /** How a route is probed: the concrete request path, the token that lacks its capability, and which probes apply. */
    data class Probe(
        val route: String,
        val path: String,
        val lacking: Set<ApiCapability>?,
        val happy: Boolean = true,
        val unknownQuery: Boolean = true,
        val body: String? = null
    )

    private val itemId: UUID = UUID.fromString("5a5a5a5a-1111-4111-8111-111111111111")

    private val admin = token("conf-admin", setOf(ApiCapability.ADMIN))
    private val reader = token("conf-reader", setOf(ApiCapability.READ))
    private val notesWriter = token("conf-notes-writer", setOf(ApiCapability.WRITE_NOTES))

    private fun token(
        id: String,
        caps: Set<ApiCapability>
    ) = "raw-$id" to
        Principal(
            id = id,
            scope = AccessScope.principal(rootIds = null, tagsInclude = emptySet()),
            capabilities = caps,
            kind = PrincipalKind.SERVICE,
            proofStatus = ProofStatus.ISSUED
        )

    private val tokens = listOf(admin, reader, notesWriter)

    private val probes: List<Probe> =
        listOf(
            Probe("GET /api/v1/info", "/api/v1/info", lacking = null),
            Probe("GET /api/v1/items/{id}", "/api/v1/items/$itemId", lacking = setOf(ApiCapability.WRITE_NOTES)),
            Probe("GET /api/v1/items/{id}/children", "/api/v1/items/$itemId/children", lacking = setOf(ApiCapability.WRITE_NOTES)),
            Probe("GET /api/v1/items/{id}/notes", "/api/v1/items/$itemId/notes", lacking = setOf(ApiCapability.WRITE_NOTES)),
            Probe("GET /api/v1/items/{id}/notes/{key}", "/api/v1/items/$itemId/notes/spec", lacking = setOf(ApiCapability.WRITE_NOTES)),
            // The 200 body is the raw JSON Schema (exempt); its 401 / 403 / Host rejection are envelopes.
            Probe(
                "GET /api/v1/config/schema",
                "/api/v1/config/schema",
                lacking = setOf(ApiCapability.WRITE_NOTES),
                happy = false,
                unknownQuery = false
            ),
            Probe("GET /api/v1/resources/leases", "/api/v1/resources/leases", lacking = setOf(ApiCapability.WRITE_NOTES)),
            Probe("GET /api/v1/resources/leases/history", "/api/v1/resources/leases/history", lacking = setOf(ApiCapability.WRITE_NOTES)),
            Probe("DELETE /api/v1/resources/leases/{key}", "/api/v1/resources/leases/no-such-key", lacking = setOf(ApiCapability.READ)),
            Probe(
                "POST /api/v1/ops/{tool}",
                "/api/v1/ops/items_read",
                lacking = setOf(ApiCapability.WRITE_NOTES),
                body = """{"select":"list"}"""
            ),
            Probe(
                "POST /api/v1/ops/director/{tool}",
                "/api/v1/ops/director/items_read",
                lacking = setOf(ApiCapability.WRITE_NOTES),
                body = """{"select":"list"}"""
            ),
            // The stream body is exempt and a happy or unknown-parameter call would open it; the pre-flight answers are probed.
            Probe("GET /api/v1/events", "/api/v1/events", lacking = setOf(ApiCapability.WRITE_NOTES), happy = false, unknownQuery = false),
        )

    // ------------------------------------------------------------------ wiring

    private fun authConfig() =
        ApiAuthConfig.Bearer(
            tokens =
                tokens.associate { (raw, p) ->
                    HashBytes(sha256(raw)) to
                        BearerTokenStore.TokenEntry(p, expiresAt = null)
                }
        )

    private fun appConfig(): AppConfig = AppConfig.fromEnv(env = mapOf("MCP_ALLOWED_HOSTS" to "secret-allowed.example")::get)

    private fun Application.productionApp(rig: EventLogRig) {
        val registry = ToolRegistry(buildToolSpecs())
        val dispatcher = buildToolDispatcher(rig.composition.toolContext)
        val server = buildProfileServer(ToolProfile.ORCHESTRATION, "conformance", "1.0.0", registry, McpOperationAdapter(dispatcher))
        val config = appConfig()
        installMcpStreamableHttp(server, config, mcpAuth = McpAuthMode.Local)
        val auth = authConfig()
        installRestApiRoutes(
            apiConfig = auth,
            eventBus =
                ApiEventBus(
                    source =
                        rig.composition.toolContext.repositoryProvider
                            .eventStore()
                ),
            effectiveProvider = rig.composition.toolContext.repositoryProvider,
            apiTokenEntries = auth.tokens,
            allowQueryToken = false,
            serverName = "conformance",
            serverVersion = "test",
            serverConfig = rig.composition.serverConfig,
            toolContext = rig.composition.toolContext,
            appConfig = config,
            toolRegistry = registry,
            toolDispatcher = dispatcher
        )
    }

    private fun seededRig(): EventLogRig {
        val rig = EventLogRig.build(db.db, dir)
        runBlocking { rig.raw.workItemRepository().create(WorkItem(id = itemId, title = "Conformance item")) }
        return rig
    }

    // ------------------------------------------------------------------ the checks

    /**
     * The body of [response] for [route] conforms, unless an [EXEMPTIONS] entry covers it: a 304 or an exempt route is
     * skipped; an error body's status is its code's catalog status, an ok body's is 200.
     */
    private suspend fun assertConformingResponse(
        route: String,
        label: String,
        response: HttpResponse
    ) {
        val status = response.status.value
        if (status == NOT_MODIFIED) return
        val text = response.bodyAsText()
        val body = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        assertTrue(body != null, "$label ($route): a JSON object body, got $status: $text")
        EnvelopeConformance.assertConforms(body, "$label ($route)")
        val code = (body["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.content
        val expected = if (code == null) 200 else ErrorCode.entries.single { it.wire == code }.httpStatus
        assertEquals(expected, status, "$label ($route): the status is the envelope's: $text")
        assertTrue(response.headers["X-Req-Id"] != null, "$label ($route): X-Req-Id is set")
        assertEquals(
            response.headers["X-Req-Id"],
            body["meta"]!!.jsonObject["reqId"]?.jsonPrimitive?.content,
            "$label ($route): X-Req-Id equals meta.reqId"
        )
    }

    private suspend fun ApplicationTestBuilder.send(
        probe: Probe,
        token: String?,
        query: String = "",
        host: String? = null
    ): HttpResponse {
        val method = HttpMethod.parse(probe.route.substringBefore(' '))
        return client.request(probe.path + query) {
            this.method = method
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            if (host != null) header(HttpHeaders.Host, host)
            if (probe.body != null) {
                contentType(ContentType.Application.Json)
                setBody(probe.body)
            }
        }
    }

    /** Every endpoint under `/api/v1` and `/.well-known`, as `METHOD template`. */
    private fun restEndpoints(root: Route): Set<String> {
        val found = sortedSetOf<String>()

        fun walk(route: Route) {
            val selector = route.selector
            val template = routeTemplate(route)
            val rest = template.startsWith("/api/v1") || template.startsWith("/.well-known")
            if (rest && selector is HttpMethodRouteSelector) found += "${selector.method.value} $template"
            // Ktor's `sse { }` handles on the path node itself (no method selector); the stream is a GET.
            if (rest && selector !is HttpMethodRouteSelector && route.children.toList().isEmpty()) found += "GET $template"
            route.children.forEach { walk(it) }
        }
        walk(root)
        return found
    }

    // ------------------------------------------------------------------ S13 the route guard (pure)

    @Test
    fun `S13 the exemption list is exactly the six reasoned entries`() {
        assertEquals(6, EXEMPTIONS.size)
        assertTrue(EXEMPTIONS.all { it.reason.isNotBlank() }, "every exemption carries a reason")
        assertEquals(
            listOf(
                "GET /api/v1/health",
                "GET /.well-known/mcp-task-orchestrator.json",
                "GET /api/v1/events stream body",
                "GET /api/v1/config/schema 200 body",
                "any 304",
                "unmatched-route 404/405"
            ),
            EXEMPTIONS.map { it.what }
        )
    }

    @Test
    fun `S13 an unprobed route on a fixture app fails the guard and a reasonless exemption is rejected`() {
        val fixture = setOf("GET /api/v1/info", "GET /api/v1/fixture-only", "GET /api/v1/health")
        assertEquals(setOf("GET /api/v1/fixture-only"), uncovered(fixture, probes, EXEMPTIONS))
        assertFailsWith<IllegalArgumentException> { uncovered(fixture, probes, EXEMPTIONS + Exemption("GET /api/v1/fixture-only", " ")) }
        assertEquals(emptySet(), uncovered(fixture, probes, EXEMPTIONS + Exemption("GET /api/v1/fixture-only", "fixture")))
    }

    @Test
    fun `S13 every production REST endpoint is probed or exempt and no probe names a missing route`() =
        testApplication {
            val rig = seededRig()
            var root: Route? = null
            application {
                productionApp(rig)
                root = pluginOrNull(RoutingRoot)
            }
            client.get("/api/v1/health")
            val endpoints = restEndpoints(checkNotNull(root) { "routing is installed" })
            assertTrue("POST /api/v1/ops/{tool}" in endpoints, "control: the walk sees the ops route: $endpoints")
            assertEquals(emptySet(), uncovered(endpoints, probes, EXEMPTIONS), "every endpoint needs probes or a reasoned exemption")
            assertEquals(emptySet(), probes.map { it.route }.toSet() - endpoints, "every probe names a production endpoint: $endpoints")
        }

    // ------------------------------------------------------------------ S8-S11, S14 route probes

    @Test
    fun `S8 S9 S10 S14 every probed route answers envelopes for no token, missing capability, happy, unknown query and bad Host`() =
        testApplication {
            val rig = seededRig()
            application { productionApp(rig) }

            for (probe in probes) {
                // S8: no token is 401 unauthenticated, and the RFC 6750 challenge is kept.
                val noToken = send(probe, token = null)
                assertEquals(401, noToken.status.value, "${probe.route}: no token")
                assertConformingResponse(probe.route, "no token", noToken)
                assertTrue(
                    noToken.headers[HttpHeaders.WWWAuthenticate]?.startsWith("Bearer") == true,
                    "${probe.route}: WWW-Authenticate kept"
                )

                // S9: a token without the route's capability is 403 forbidden (capability) on routes that gate one.
                if (probe.lacking != null) {
                    val raw = tokens.single { it.second.capabilities == probe.lacking }.first
                    val lacking = send(probe, token = raw)
                    assertConformingResponse(probe.route, "missing capability", lacking)
                    assertEquals(403, lacking.status.value, "${probe.route}: missing capability")
                    val detail =
                        Json
                            .parseToJsonElement(lacking.bodyAsText())
                            .jsonObject["error"]!!
                            .jsonObject["detail"]!!
                            .jsonObject
                    assertEquals("capability", detail["scope"]?.jsonPrimitive?.content, "${probe.route}: ${lacking.bodyAsText()}")
                }

                if (probe.happy) assertConformingResponse(probe.route, "happy", send(probe, token = admin.first))
                if (probe.unknownQuery) {
                    assertConformingResponse(
                        probe.route,
                        "unknown query",
                        send(probe, token = admin.first, query = "?bogusParam=1")
                    )
                }

                // S10: a bad Host is 403 forbidden {scope: host}; neither the allowlist nor the rejected value is echoed.
                val badHost = send(probe, token = admin.first, host = "evil-rebind.example")
                assertConformingResponse(probe.route, "bad host", badHost)
                assertHostRejection(probe.route, badHost)
            }
        }

    private suspend fun assertHostRejection(
        label: String,
        response: HttpResponse
    ) {
        val text = response.bodyAsText()
        assertEquals(403, response.status.value, "$label: $text")
        val detail =
            Json
                .parseToJsonElement(text)
                .jsonObject["error"]!!
                .jsonObject["detail"]!!
                .jsonObject
        assertEquals("host", detail["scope"]?.jsonPrimitive?.content, "$label: $text")
        assertEquals(HOST_REQUIRED, detail["required"]?.jsonPrimitive?.content, "$label: $text")
        assertFalse("secret-allowed.example" in text, "$label: the allowlist is never echoed: $text")
        assertFalse("evil-rebind.example" in text, "$label: the rejected Host is never echoed: $text")
    }

    @Test
    fun `S10 a bad Host on mcp and well-known is the same forbidden host envelope`() =
        testApplication {
            val rig = seededRig()
            application { productionApp(rig) }

            val mcp =
                client.post("/mcp") {
                    header(HttpHeaders.Host, "evil-rebind.example")
                    contentType(ContentType.Application.Json)
                    setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
                }
            assertHostRejection("/mcp", mcp)
            EnvelopeConformance.assertConforms(Json.parseToJsonElement(mcp.bodyAsText()), "/mcp host rejection")

            val wellKnown = client.get("/.well-known/mcp-task-orchestrator.json") { header(HttpHeaders.Host, "evil-rebind.example") }
            assertHostRejection("well-known", wellKnown)
        }

    @Test
    fun `S11 info is an ok envelope whose data is the service info`() =
        testApplication {
            val rig = seededRig()
            application { productionApp(rig) }

            val response = client.get("/api/v1/info") { header(HttpHeaders.Authorization, "Bearer ${reader.first}") }
            assertConformingResponse("GET /api/v1/info", "info", response)
            val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonObject
            assertEquals("conformance", data["serverName"]?.jsonPrimitive?.content)
            assertEquals(
                JsonPrimitive("read"),
                Json.parseToJsonElement(data["capabilities"].toString()).let {
                    (it as kotlinx.serialization.json.JsonArray).single()
                }
            )
        }

    @Test
    fun `S14 config schema 200 is the raw schema and its 401 is an envelope`() =
        testApplication {
            val rig = seededRig()
            application { productionApp(rig) }

            val ok = client.get("/api/v1/config/schema") { header(HttpHeaders.Authorization, "Bearer ${reader.first}") }
            assertEquals(200, ok.status.value)
            val schema = Json.parseToJsonElement(ok.bodyAsText()).jsonObject
            assertTrue("\$schema" in schema && "ok" !in schema, "the exempt 200 is the schema itself: ${schema.keys}")

            val unauthenticated = client.get("/api/v1/config/schema")
            assertConformingResponse("GET /api/v1/config/schema", "no token", unauthenticated)
        }

    @Test
    fun `S12 events pre-flight - a mix of valid and malformed roots is 400 and a malformed one alone is 400`() =
        testApplication {
            val rig = seededRig()
            application { productionApp(rig) }

            for (query in listOf("?root=$itemId&root=not-a-uuid", "?root=not-a-uuid")) {
                val response = client.get("/api/v1/events$query") { header(HttpHeaders.Authorization, "Bearer ${reader.first}") }
                assertEquals(400, response.status.value, "$query: ${response.bodyAsText()}")
                val body = Json.parseToJsonElement(response.bodyAsText())
                EnvelopeConformance.assertConforms(body, "events $query")
                assertEquals(
                    "invalid_request",
                    body.jsonObject["error"]!!
                        .jsonObject["code"]
                        ?.jsonPrimitive
                        ?.content
                )
            }
        }

    // ------------------------------------------------------------------ S7 tool bodies

    @Test
    fun `S7 every tool in every profile answers describe and an unknown parameter with a conforming envelope`(): Unit =
        runBlocking {
            val rig = seededRig()
            val registry = ToolRegistry(buildToolSpecs())
            val dispatcher = buildToolDispatcher(rig.composition.toolContext)
            var checked = 0
            for (spec in registry.tools) {
                for (profile in ToolProfile.entries) {
                    if (visibleNames(spec, profile).isEmpty()) continue
                    val describe =
                        dispatcher.dispatch(
                            spec,
                            profile,
                            JsonObject(mapOf("describe" to JsonPrimitive(true))),
                            "conf-desc",
                            CallLogRecord.SURFACE_MCP,
                            CallAccess.LOCAL
                        )
                    EnvelopeConformance.assertConforms(describe.envelope.body, "${spec.name}/${profile.wire} describe")
                    assertEquals(200, describe.envelope.httpStatus, "${spec.name}/${profile.wire} describe")

                    val unknown =
                        dispatcher.dispatch(
                            spec,
                            profile,
                            JsonObject(mapOf("bogusParameter" to JsonPrimitive(1))),
                            "conf-unknown",
                            CallLogRecord.SURFACE_MCP,
                            CallAccess.LOCAL
                        )
                    EnvelopeConformance.assertConforms(unknown.envelope.body, "${spec.name}/${profile.wire} unknown parameter")
                    assertFalse(
                        unknown.envelope.body["ok"]!!
                            .jsonPrimitive.content
                            .toBoolean(),
                        "${spec.name}/${profile.wire}: rejected"
                    )
                    checked++
                }
            }
            assertTrue(checked >= registry.tools.size, "every tool was checked in at least one profile ($checked)")
        }

    @Test
    fun `S7 the same two calls over REST ops conform for every orchestration tool`() =
        testApplication {
            val rig = seededRig()
            application { productionApp(rig) }
            for (spec in ToolRegistry(buildToolSpecs()).tools) {
                if (visibleNames(spec, ToolProfile.ORCHESTRATION).isEmpty()) continue
                for ((label, body) in listOf("describe" to """{"describe":true}""", "unknown" to """{"bogusParameter":1}""")) {
                    val response =
                        client.post("/api/v1/ops/${spec.name}") {
                            header(HttpHeaders.Authorization, "Bearer ${admin.first}")
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                    assertConformingResponse("POST /api/v1/ops/{tool}", "${spec.name} $label", response)
                }
            }
        }

    // ------------------------------------------------------------------ helpers

    companion object {
        private const val NOT_MODIFIED = 304

        /** The envelope rule's exemptions: exactly these six, each with its reason (task-scope E). */
        val EXEMPTIONS: List<Exemption> =
            listOf(
                Exemption("GET /api/v1/health", "the unauthenticated probe DTO for load balancers and the Docker HEALTHCHECK"),
                Exemption(
                    "GET /.well-known/mcp-task-orchestrator.json",
                    "the discovery document, a published fixed shape fetched before any session"
                ),
                Exemption(
                    "GET /api/v1/events stream body",
                    "the SSE stream is text/event-stream, not a JSON body; its pre-flight answers are envelopes"
                ),
                Exemption(
                    "GET /api/v1/config/schema 200 body",
                    "the raw JSON Schema is a \$schema target for editors (Q16 D9); its 401/403 are envelopes"
                ),
                Exemption("any 304", "Not Modified carries no body"),
                Exemption("unmatched-route 404/405", "Ktor's own routing answer for a request no route matched; no route ran"),
            )

        /**
         * The endpoints of [endpoints] neither probed nor named by an exemption (route-level exemptions name the endpoint
         * exactly). An exemption with a blank reason is rejected.
         */
        fun uncovered(
            endpoints: Set<String>,
            probes: List<Probe>,
            exemptions: List<Exemption>
        ): Set<String> {
            exemptions.forEach { require(it.reason.isNotBlank()) { "exemption '${it.what}' needs a reason" } }
            val exemptRoutes = exemptions.map { it.what }.toSet()
            return endpoints - probes.map { it.route }.toSet() - exemptRoutes
        }
    }
}
