package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for `GET /api/v1/items/{id}/gate` ([itemGateRoutes]).
 *
 * Independently authored against the frozen `test-plan` note on item `a6468f6c` — scenarios S1-S16
 * and probes P1-P7 below map 1:1 to that note's ids; oracles are cited there (AR = api-reference.md
 * gateStatus contract, TS = task-scope acceptance criteria, SIB = sibling `GET /items/{id}`
 * id-handling, CF = per-root config-format doc, GC = get_context item-mode parity).
 *
 * Fixture schema type `gt`: required WORK `w1` (guidance "g1", skill "s1"), optional WORK `wo`,
 * required QUEUE `q1`. Trait `tr` adds a required WORK `t1`. Type `probe-guidance` carries two
 * required WORK notes, `p-first` (no guidance) and `p-second` (has guidance), for P2/P3.
 */
class ItemGateRouteTest {
    // ─── S1/S2 — happy path, single required WORK note ─────────────────────

    @Test
    fun `S1 GET items id gate returns missing w1 with guidance and skill when WORK item has no notes`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val svc = GateFixtureSchemaService()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Gate S1", type = "gt", role = Role.WORK, depth = 0)).getOrNull()!!
                }
            application { configureGateApp(repo, svc) }

            val response =
                client.get("/api/v1/items/${item.id}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = parseGate(response.bodyAsText())

            val gateStatus = json["gateStatus"]!!.jsonObject
            // assertAll: the S3 parity check (second executable) must run and report independently
            // of the scenario assertions (first executable) — neither may mask the other.
            assertAll(
                {
                    assertEquals(item.id.toString(), json["itemId"]!!.jsonPrimitive.content)
                    assertEquals("Gate S1", json["title"]!!.jsonPrimitive.content)
                    assertEquals("work", json["role"]!!.jsonPrimitive.content)

                    assertFalse(gateStatus["canAdvance"]!!.jsonPrimitive.boolean)
                    assertEquals("work", gateStatus["phase"]!!.jsonPrimitive.content)
                    assertEquals(listOf("w1"), gateStatus["missing"]!!.jsonArray.map { it.jsonPrimitive.content })

                    assertEquals("w1", json["guidanceKey"]!!.jsonPrimitive.content)
                    assertEquals("s1", json["skillPointer"]!!.jsonPrimitive.content)
                },
                { assertParityWithGetContext(repo, svc, item.id, json) }, // S3
            )
        }

    @Test
    fun `S2 GET items id gate returns canAdvance true and omits guidanceKey and skillPointer when w1 is filled`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val svc = GateFixtureSchemaService()
            val item =
                runBlocking {
                    val i =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "Gate S2", type = "gt", role = Role.WORK, depth = 0))
                            .getOrNull()!!
                    repo.noteRepository().upsert(Note(itemId = i.id, key = "w1", role = "work", body = "done"))
                    i
                }
            application { configureGateApp(repo, svc) }

            val response =
                client.get("/api/v1/items/${item.id}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = parseGate(response.bodyAsText())

            val gateStatus = json["gateStatus"]!!.jsonObject
            assertAll(
                {
                    assertTrue(gateStatus["canAdvance"]!!.jsonPrimitive.boolean)
                    assertEquals(0, gateStatus["missing"]!!.jsonArray.size)

                    // P7: omitted fields must be ABSENT keys, not JSON null.
                    assertFalse(json.containsKey("guidanceKey"), "guidanceKey must be omitted, not present-as-null: $json")
                    assertFalse(json.containsKey("skillPointer"), "skillPointer must be omitted, not present-as-null: $json")
                },
                { assertParityWithGetContext(repo, svc, item.id, json) }, // S3
            )
        }

    // ─── S4 — terminal ───────────────────────────────────────────────────────

    @Test
    fun `S4 GET items id gate on a TERMINAL item returns phase terminal canAdvance false and empty missing`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val svc = GateFixtureSchemaService()
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(WorkItem(title = "Gate S4", type = "gt", role = Role.TERMINAL, depth = 0))
                        .getOrNull()!!
                }
            application { configureGateApp(repo, svc) }

            val response =
                client.get("/api/v1/items/${item.id}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = parseGate(response.bodyAsText())

            val gateStatus = json["gateStatus"]!!.jsonObject
            assertAll(
                {
                    assertEquals("terminal", json["role"]!!.jsonPrimitive.content)
                    assertEquals("terminal", gateStatus["phase"]!!.jsonPrimitive.content)
                    assertFalse(gateStatus["canAdvance"]!!.jsonPrimitive.boolean)
                    assertEquals(0, gateStatus["missing"]!!.jsonArray.size)
                },
                { assertParityWithGetContext(repo, svc, item.id, json) }, // S3
            )
        }

    // ─── S5 — schema-free ──────────────────────────────────────────────────

    @Test
    fun `S5 GET items id gate on a schema-free type returns canAdvance true with no guidanceKey`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val svc = GateFixtureSchemaService()
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(WorkItem(title = "Gate S5", type = "free", role = Role.WORK, depth = 0))
                        .getOrNull()!!
                }
            application { configureGateApp(repo, svc) }

            val response =
                client.get("/api/v1/items/${item.id}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = parseGate(response.bodyAsText())

            val gateStatus = json["gateStatus"]!!.jsonObject
            assertAll(
                {
                    assertTrue(gateStatus["canAdvance"]!!.jsonPrimitive.boolean)
                    assertEquals(0, gateStatus["missing"]!!.jsonArray.size)
                    assertFalse(json.containsKey("guidanceKey"))
                    assertFalse(json.containsKey("skillPointer"))
                },
                { assertParityWithGetContext(repo, svc, item.id, json) }, // S3
            )
        }

    // ─── S6 — blank body counts as missing ────────────────────────────────────

    @Test
    fun `S6 GET items id gate treats a blank w1 body as missing`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val svc = GateFixtureSchemaService()
            val item =
                runBlocking {
                    val i =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "Gate S6", type = "gt", role = Role.WORK, depth = 0))
                            .getOrNull()!!
                    repo.noteRepository().upsert(Note(itemId = i.id, key = "w1", role = "work", body = "   "))
                    i
                }
            application { configureGateApp(repo, svc) }

            val response =
                client.get("/api/v1/items/${item.id}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = parseGate(response.bodyAsText())

            val gateStatus = json["gateStatus"]!!.jsonObject
            assertAll(
                {
                    assertFalse(gateStatus["canAdvance"]!!.jsonPrimitive.boolean)
                    assertEquals(listOf("w1"), gateStatus["missing"]!!.jsonArray.map { it.jsonPrimitive.content })
                },
                { assertParityWithGetContext(repo, svc, item.id, json) }, // S3
            )
        }

    // ─── S7 — other-phase / optional notes do not affect WORK missing ────────

    @Test
    fun `S7 GET items id gate ignores queue-phase and optional work-phase notes when computing WORK missing`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val svc = GateFixtureSchemaService()
            val item =
                runBlocking {
                    val i =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "Gate S7", type = "gt", role = Role.WORK, depth = 0))
                            .getOrNull()!!
                    repo.noteRepository().upsert(Note(itemId = i.id, key = "w1", role = "work", body = "done"))
                    // q1 (queue, required) and wo (work, optional) are deliberately left absent.
                    i
                }
            application { configureGateApp(repo, svc) }

            val response =
                client.get("/api/v1/items/${item.id}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = parseGate(response.bodyAsText())

            val gateStatus = json["gateStatus"]!!.jsonObject
            assertTrue(gateStatus["canAdvance"]!!.jsonPrimitive.boolean)
            assertEquals(0, gateStatus["missing"]!!.jsonArray.size)
            // Not in the S3 parity list — no get_context cross-check for S7.
        }

    // ─── S8 — per-item trait via properties JSON ──────────────────────────────

    @Test
    fun `S8 GET items id gate merges a per-item trait note from properties JSON into missing`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val svc = GateFixtureSchemaService()
            val item =
                runBlocking {
                    val i =
                        repo
                            .workItemRepository()
                            .create(
                                WorkItem(
                                    title = "Gate S8",
                                    type = "gt",
                                    role = Role.WORK,
                                    depth = 0,
                                    properties = """{"traits": ["tr"]}""",
                                ),
                            ).getOrNull()!!
                    repo.noteRepository().upsert(Note(itemId = i.id, key = "w1", role = "work", body = "done"))
                    i
                }
            application { configureGateApp(repo, svc) }

            val response =
                client.get("/api/v1/items/${item.id}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = parseGate(response.bodyAsText())

            val gateStatus = json["gateStatus"]!!.jsonObject
            assertAll(
                {
                    assertFalse(gateStatus["canAdvance"]!!.jsonPrimitive.boolean)
                    assertEquals(listOf("t1"), gateStatus["missing"]!!.jsonArray.map { it.jsonPrimitive.content })
                },
                { assertParityWithGetContext(repo, svc, item.id, json) }, // S3
            )
        }

    // ─── S9 — per-root pushed schema replaces the global schema for the type ──

    @Test
    fun `S9 GET items id gate uses per-root pushed schema instead of the global schema for the type`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val svc = GateFixtureSchemaService()
            val (root, item) =
                runBlocking {
                    val r =
                        repo.workItemRepository().create(WorkItem(title = "Gate S9 Root", depth = 0)).getOrNull()!!
                    val i =
                        repo
                            .workItemRepository()
                            .create(
                                WorkItem(
                                    title = "Gate S9 Child",
                                    type = "gt",
                                    role = Role.WORK,
                                    parentId = r.id,
                                    rootId = r.id,
                                    depth = 1,
                                ),
                            ).getOrNull()!!
                    val yaml =
                        """
                        work_item_schemas:
                          gt:
                            notes:
                              - key: pr1
                                role: work
                                required: true
                                description: "Per-root required note"
                        """.trimIndent()
                    repo.projectConfigRepository().upsert(r.id, yaml).getOrNull()
                        ?: error("fixture: per-root config upsert failed")
                    r to i
                }
            application { configureGateApp(repo, svc) }

            val response =
                client.get("/api/v1/items/${item.id}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = parseGate(response.bodyAsText())

            val gateStatus = json["gateStatus"]!!.jsonObject
            assertAll(
                {
                    assertFalse(gateStatus["canAdvance"]!!.jsonPrimitive.boolean)
                    assertEquals(
                        listOf("pr1"),
                        gateStatus["missing"]!!.jsonArray.map { it.jsonPrimitive.content },
                        "per-root schema for type 'gt' must replace the global 'gt' schema (w1 must not appear)",
                    )
                    assertTrue(root.id == item.rootId, "sanity: fixture item must carry the root's id as rootId")
                },
                { assertParityWithGetContext(repo, svc, item.id, json) }, // S3
            )
        }

    // ─── S10/S11 — id-handling failures (mirrors sibling GET /items/{id}) ────

    @Test
    fun `S10 GET items id gate returns 404 not_found for a random UUID`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureGateApp(repo) }

            val response =
                client.get("/api/v1/items/${UUID.randomUUID()}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("not_found"))
        }

    @Test
    fun `S11a GET items id gate returns 400 bad_request for a malformed id`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureGateApp(repo) }

            val response =
                client.get("/api/v1/items/not-a-uuid/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("bad_request"))
        }

    @Test
    fun `S11b GET items id gate returns 400 bad_request for an 8-hex prefix of a real item id (no hex-prefix resolution)`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Gate S11b", depth = 0)).getOrNull()!!
                }
            application { configureGateApp(repo) }

            val hexPrefix =
                item.id
                    .toString()
                    .replace("-", "")
                    .substring(0, 8)
            val response =
                client.get("/api/v1/items/$hexPrefix/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("bad_request"))
        }

    // ─── S12/S13/S14 — auth / scope / capability ──────────────────────────────

    @Test
    fun `S12 GET items id gate returns 401 without an Authorization header`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Gate S12", depth = 0)).getOrNull()!!
                }
            application { configureGateApp(repo) }

            val response = client.get("/api/v1/items/${item.id}/gate")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `S13 GET items id gate returns 403 scope_forbidden for a token scoped to a different root`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val outsideScopeItem =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Gate S13", depth = 0)).getOrNull()!!
                }
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(UUID.randomUUID()))
            application { configureGateApp(repo, authConfig = authConfig) }

            val response =
                client.get("/api/v1/items/${outsideScopeItem.id}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("scope_forbidden"))
        }

    @Test
    fun `S14 GET items id gate returns 403 insufficient_scope for a principal missing READ capability`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Gate S14", depth = 0)).getOrNull()!!
                }
            val notesOnlyToken = "notes-only-token-for-gate-test"
            val principal =
                ApiPrincipal(
                    tokenId = "notes-only-principal",
                    scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                    capabilities = setOf(ApiCapability.WRITE_NOTES),
                    authMode = ApiAuthMode.BEARER,
                )
            val authConfig = ApiAuthConfig.Bearer(tokens = mapOf(HashBytes(sha256(notesOnlyToken)) to principal))
            application { configureGateApp(repo, authConfig = authConfig) }

            val response =
                client.get("/api/v1/items/${item.id}/gate") {
                    header("Authorization", "Bearer $notesOnlyToken")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("insufficient_scope"))
        }

    // ─── S15 — production wiring registration ─────────────────────────────────

    @Test
    fun `S15 installRestApiRoutes wires the gate route so it is reachable in production topology`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val svc = GateFixtureSchemaService()
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(WorkItem(title = "Gate S15", type = "gt", role = Role.WORK, depth = 0))
                        .getOrNull()!!
                }
            val authConfig = makeTestAuthConfig()
            val tokenEntries = authConfig.tokens.mapValues { (_, p) -> BearerTokenStore.TokenEntry(p, expiresAt = null) }
            application {
                // Production topology mirrors McpRestAuthBypassTest.kt:49-101 (installMcpStreamableHttp
                // THEN installRestApiRoutes). installMcpStreamableHttp is what installs ContentNegotiation
                // in production; without it, a matched route with no negotiated format serializer replies
                // 406, not 404 — installRestApiRoutes alone does NOT install it. Building a full MCP Server
                // here is unnecessary: this is the exact ContentNegotiation wiring installMcpStreamableHttp
                // performs, installed directly.
                install(ContentNegotiation) { json(McpJson) }
                installRestApiRoutes(
                    apiConfig = authConfig,
                    eventBus = null,
                    effectiveProvider = repo,
                    apiTokenEntries = tokenEntries,
                    allowQueryToken = false,
                    serverName = "gate-wiring-test",
                    serverVersion = "1.0.0",
                    actorAuthEnabled = false,
                    noteSchemaService = svc,
                    toolContext =
                        ToolExecutionContext(
                            repo,
                            svc,
                            perRootConfigService = PerRootConfigService(repo.projectConfigRepository()),
                        ),
                    degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
                    idempotencyCache = IdempotencyCache(),
                )
            }

            val response =
                client.get("/api/v1/items/${item.id}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "itemGateRoutes must be registered in installRestApiRoutes's production wiring",
            )
            val json = parseGate(response.bodyAsText())
            assertEquals(
                listOf("w1"),
                json["gateStatus"]!!.jsonObject["missing"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }

    // ─── S16 — read-only: no side effects ─────────────────────────────────────

    @Test
    fun `S16 GET items id gate does not mutate item role modifiedAt or notes`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val svc = GateFixtureSchemaService()
            val item =
                runBlocking {
                    val i =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "Gate S16", type = "gt", role = Role.WORK, depth = 0))
                            .getOrNull()!!
                    repo.noteRepository().upsert(Note(itemId = i.id, key = "w1", role = "work", body = "done"))
                    i
                }
            application { configureGateApp(repo, svc) }

            val before = runBlocking { repo.workItemRepository().getById(item.id).getOrNull()!! }
            val notesBefore = runBlocking { repo.noteRepository().findByItemId(item.id).getOrNull()!! }

            val response =
                client.get("/api/v1/items/${item.id}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)

            val after = runBlocking { repo.workItemRepository().getById(item.id).getOrNull()!! }
            val notesAfter = runBlocking { repo.noteRepository().findByItemId(item.id).getOrNull()!! }

            assertEquals(before.role, after.role)
            assertEquals(before.modifiedAt, after.modifiedAt, "GET /gate must not touch modifiedAt")
            assertEquals(notesBefore.size, notesAfter.size)
            assertEquals(notesBefore.map { it.id }.toSet(), notesAfter.map { it.id }.toSet())
        }

    // ─── P1 — If-None-Match is never honored (no ETag semantics, AC7) ─────────

    @Test
    fun `P1 GET items id gate ignores If-None-Match and never returns 304`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val svc = GateFixtureSchemaService()
            val item =
                runBlocking {
                    val i =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "Gate P1", type = "gt", role = Role.WORK, depth = 0))
                            .getOrNull()!!
                    repo.noteRepository().upsert(Note(itemId = i.id, key = "w1", role = "work", body = "done"))
                    i
                }
            application { configureGateApp(repo, svc, includeItemRoute = true) }

            // Read the REAL ETag from the sibling route's response HEADER (not the JSON body — a
            // body field, if any, is a separate value and must not be used as a stand-in). The
            // If-None-Match value sent below must equal this byte-for-byte, or the probe cannot
            // prove anything: a route that DID honor If-None-Match would still return 200 for a
            // non-matching value, which is indistinguishable from correctly ignoring the header.
            val itemResponse =
                client.get("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            val etag = itemResponse.headers[HttpHeaders.ETag]
            assertTrue(!etag.isNullOrBlank(), "sibling GET /items/{id} must return a non-blank ETag header")
            assertTrue(etag!!.startsWith("\"") && etag.endsWith("\""), "ETag header must be quoted: $etag")

            val response =
                client.get("/api/v1/items/${item.id}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                    header(HttpHeaders.IfNoneMatch, etag)
                }
            // bodyAsText() is suspend — read it in the coroutine body, not inside an assertAll
            // executable (a plain, non-suspend functional interface).
            val json = parseGate(response.bodyAsText())
            assertAll(
                {
                    assertEquals(
                        HttpStatusCode.OK,
                        response.status,
                        "gate route must never honor If-None-Match (AC7: no ETag handling)",
                    )
                },
                {
                    // Fresh gate content (not a cached/304 stand-in): w1 was filled, so canAdvance
                    // must be true and missing empty — proving the response is a real computed body.
                    val gateStatus = json["gateStatus"]!!.jsonObject
                    assertTrue(gateStatus["canAdvance"]!!.jsonPrimitive.boolean, "expected fresh gate content: canAdvance true")
                    assertEquals(0, gateStatus["missing"]!!.jsonArray.size, "expected fresh gate content: missing empty")
                },
            )
        }

    // ─── P2/P3 — two missing notes: schema order + guidance parity ───────────

    @Test
    fun `P2 P3 GET items id gate lists two missing required notes in schema order with guidance parity`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val svc = GateFixtureSchemaService()
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(WorkItem(title = "Gate P2P3", type = "probe-guidance", role = Role.WORK, depth = 0))
                        .getOrNull()!!
                }
            application { configureGateApp(repo, svc) }

            val response =
                client.get("/api/v1/items/${item.id}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = parseGate(response.bodyAsText())

            assertAll(
                // P3: schema order preserved (p-first before p-second).
                {
                    assertEquals(
                        listOf("p-first", "p-second"),
                        json["gateStatus"]!!.jsonObject["missing"]!!.jsonArray.map { it.jsonPrimitive.content },
                    )
                },
                // P2: guidanceKey/skillPointer behavior is parity-only (AR:1471 ambiguous which of the
                // two missing entries "counts" when only the second has guidance) — assert against
                // get_context's own answer for the same fixture rather than a hardcoded value.
                { assertParityWithGetContext(repo, svc, item.id, json) },
            )
        }

    // ─── P4 — uppercase UUID accepted ─────────────────────────────────────────

    @Test
    fun `P4 GET items id gate accepts an uppercase UUID`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Gate P4", depth = 0)).getOrNull()!!
                }
            application { configureGateApp(repo) }

            val response =
                client.get("/api/v1/items/${item.id.toString().uppercase()}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    // ─── P5 — BLOCKED item parity ──────────────────────────────────────────────

    @Test
    fun `P5 GET items id gate on a BLOCKED item matches get_context parity`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val svc = GateFixtureSchemaService()
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(WorkItem(title = "Gate P5", type = "gt", role = Role.BLOCKED, depth = 0))
                        .getOrNull()!!
                }
            application { configureGateApp(repo, svc) }

            val response =
                client.get("/api/v1/items/${item.id}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = parseGate(response.bodyAsText())
            assertEquals("blocked", json["gateStatus"]!!.jsonObject["phase"]!!.jsonPrimitive.content)

            assertParityWithGetContext(repo, svc, item.id, json)
        }

    // ─── P6 — encoded traversal-style id must not 500 ─────────────────────────

    @Test
    fun `P6 GET items id gate returns a 4xx not 500 for a percent-encoded traversal id`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureGateApp(repo) }

            val response =
                client.get("/api/v1/items/%2e%2e/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertTrue(
                response.status.value in 400..499,
                "Expected a 4xx response for a path-traversal-style id, got ${response.status}",
            )
        }

    // ─── Shared helpers ────────────────────────────────────────────────────────

    private fun parseGate(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    /** get_context item-mode data for the SAME repo/schema/item, for S3 parity assertions. */
    private fun getContextGateData(
        repo: DefaultRepositoryProvider,
        schemaService: WorkItemSchemaService,
        itemId: UUID,
    ): JsonObject {
        val context =
            ToolExecutionContext(repo, schemaService, perRootConfigService = PerRootConfigService(repo.projectConfigRepository()))
        val params = JsonObject(mapOf("itemId" to JsonPrimitive(itemId.toString())))
        val result = runBlocking { GetContextTool().execute(params, context) }
        return (result as JsonObject)["data"] as JsonObject
    }

    /** S3: route's gateStatus/guidanceKey/skillPointer must equal get_context item mode's, field-for-field. */
    private fun assertParityWithGetContext(
        repo: DefaultRepositoryProvider,
        schemaService: WorkItemSchemaService,
        itemId: UUID,
        routeJson: JsonObject,
    ) {
        val gcData = getContextGateData(repo, schemaService, itemId)
        assertEquals(gcData["gateStatus"], routeJson["gateStatus"], "route gateStatus must equal get_context item-mode gateStatus")
        assertEquals(gcData["guidanceKey"], routeJson["guidanceKey"], "route guidanceKey must equal get_context guidanceKey")
        assertEquals(gcData["skillPointer"], routeJson["skillPointer"], "route skillPointer must equal get_context skillPointer")
    }
}

/**
 * Configures a test Ktor application with only [itemGateRoutes] registered (plus, optionally, the
 * sibling [itemRoutes] for the P1 ETag probe, which needs a real item ETag to prove the gate route
 * ignores it).
 */
private fun Application.configureGateApp(
    repo: DefaultRepositoryProvider,
    schemaService: WorkItemSchemaService = GateFixtureSchemaService(),
    authConfig: ApiAuthConfig = makeTestAuthConfig(),
    includeItemRoute: Boolean = false,
) {
    configureTestApp(authConfig) {
        if (includeItemRoute) itemRoutes(repo)
        itemGateRoutes(
            repo,
            ToolExecutionContext(
                repo,
                schemaService,
                perRootConfigService = PerRootConfigService(repo.projectConfigRepository()),
            ).configResolver,
        )
    }
}

/**
 * Fixture schema service for the gate route tests.
 *
 * Type `gt`: required WORK `w1` (guidance "g1", skill "s1"), optional WORK `wo`, required QUEUE
 * `q1`. Trait `tr` -> required WORK `t1` (per-item trait merge, S8). Type `probe-guidance`: two
 * required WORK notes, `p-first` (no guidance) then `p-second` (has guidance), for P2/P3. Any
 * other type (e.g. `free`) resolves schema-free (both lookups return null).
 */
private class GateFixtureSchemaService : WorkItemSchemaService {
    private val gt =
        WorkItemSchema(
            type = "gt",
            notes =
                listOf(
                    NoteSchemaEntry(key = "w1", role = Role.WORK, required = true, description = "w1", guidance = "g1", skill = "s1"),
                    NoteSchemaEntry(key = "wo", role = Role.WORK, required = false, description = "wo"),
                    NoteSchemaEntry(key = "q1", role = Role.QUEUE, required = true, description = "q1"),
                ),
        )

    private val probeGuidance =
        WorkItemSchema(
            type = "probe-guidance",
            notes =
                listOf(
                    NoteSchemaEntry(key = "p-first", role = Role.WORK, required = true, description = "first, no guidance"),
                    NoteSchemaEntry(
                        key = "p-second",
                        role = Role.WORK,
                        required = true,
                        description = "second",
                        guidance = "has guidance",
                    ),
                ),
        )

    private val t1 = NoteSchemaEntry(key = "t1", role = Role.WORK, required = true, description = "t1")

    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
        when {
            "gt" in tags -> gt.notes
            "probe-guidance" in tags -> probeGuidance.notes
            else -> null
        }

    override fun getSchemaForType(type: String?): WorkItemSchema? =
        when (type) {
            "gt" -> gt
            "probe-guidance" -> probeGuidance
            else -> null
        }

    override fun getTraitNotes(traitName: String): List<NoteSchemaEntry>? = if (traitName == "tr") listOf(t1) else null
}
