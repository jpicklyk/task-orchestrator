package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.StatusLabelService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.test.TestConfigResolver
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.upsert
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Read-only-capability token alias — reuses [TEST_TOKEN] from [ApiTestHelper] (READ only).
 * Used to assert that write routes reject principals lacking the required write capability.
 */
private const val READ_ONLY_TOKEN = TEST_TOKEN

/**
 * Configures a test Ktor application with Phase 5 write routes registered.
 *
 * Reuses the shared [makeWriteAuthConfig] / [ApiBearerAuth] setup from [ApiTestHelper] —
 * NO hand-rolled auth config. The WRITE_TOKEN principal has all write capabilities; the
 * TEST_TOKEN (READ_ONLY_TOKEN) principal has READ only.
 *
 * @param serverConfig the server config document the routes resolve schemas against (via
 *   [TestConfigResolver]). Defaults to [ConfigDocument.EMPTY] (schema-free → no review phase).
 * @param statusLabelService config-driven status label resolution used by the advance route.
 *   Defaults to [NoOpStatusLabelService] (deterministic hardcoded defaults, no file I/O) — tests
 *   asserting REST/MCP status-label parity (bug 80e48e55) rely on this default.
 */
fun Application.configureWriteTestApp(
    repo: DefaultRepositoryProvider,
    degradedModePolicy: DegradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
    authConfig: ApiAuthConfig.Bearer = makeWriteAuthConfig(),
    serverConfig: ConfigDocument = ConfigDocument.EMPTY,
    statusLabelService: StatusLabelService = NoOpStatusLabelService,
    unitOfWork: UnitOfWork
) {
    install(ContentNegotiation) { json(McpJson) }
    install(SSE)
    routing {
        route("/api/v1") {
            install(ApiBearerAuth) {
                this.authConfig = authConfig
                tokenEntries =
                    authConfig.tokens.mapValues { (_, p) ->
                        BearerTokenStore.TokenEntry(p, expiresAt = null)
                    }
            }
            // READ routes too (for asserting persisted state)
            itemRoutes(repo)
            noteRoutes(repo)
            dependencyRoutes(repo)
            // WRITE routes under test
            val perRoot = PerRootConfigService(repo.configStore())
            val toolContext =
                ToolExecutionContext(
                    repo,
                    statusLabelService = statusLabelService,
                    perRootConfigService = perRoot,
                    configResolver = TestConfigResolver.resolver(serverConfig, perRoot),
                    unitOfWork = unitOfWork
                )
            itemWriteRoutes(
                repo,
                degradedModePolicy,
                IdempotencyService(unitOfWork),
                toolContext.advanceServiceFactory(),
                unitOfWork,
            )
            dependencyWriteRoutes(repo, degradedModePolicy, IdempotencyService(unitOfWork), unitOfWork)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /items/{id}/advance
// ─────────────────────────────────────────────────────────────────────────────

class AdvanceRouteTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @Test
    fun `POST items id advance starts item and returns 200 with new role`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Advance Me", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("work") || body.contains("terminal"), "Should have new role: $body")
            assertFalse(body.contains("claimedBy"), "Response must NOT disclose claimedBy: $body")

            // Hard assertion: role transition persisted — query back
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertTrue(
                (persisted!!)
                    .role.name
                    .lowercase() != "queue",
                "Item role should have advanced from queue"
            )
        }

    @Test
    fun `POST items id advance stamps a config-driven statusLabel (bug 80e48e55 REST-MCP parity)`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Label Parity", depth = 0))!!
                }
            application { configureWriteTestApp(repo, statusLabelService = NoOpStatusLabelService, unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains("\"statusLabel\":\"in-progress\""),
                "REST advance must stamp a config-driven statusLabel; previously used NoOpStatusLabelService " +
                    "unconditionally and applied no label at all: $body",
            )

            // Hard assertion: the persisted item carries the stamped label, not just the response body.
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals(
                "in-progress",
                persisted!!.statusLabel,
                "Persisted item must carry the REST-stamped statusLabel",
            )
        }

    @Test
    fun `POST items id advance start on WORK with no review phase stamps the terminal label (bug 100da214 + 80e48e55)`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            // 'flat-type' has no REVIEW-phase note -> hasReviewPhase() false -> start from WORK
            // resolves straight to TERMINAL. Before the fix this stamped "in-progress" (bug
            // 100da214) via a NoOpStatusLabelService that REST never even consulted (bug 80e48e55).
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(WorkItem(title = "No Review Label", type = "flat-type", role = Role.WORK, depth = 0))!!
                }
            application {
                configureWriteTestApp(
                    repo,
                    serverConfig = reviewPhaseSchemaService(),
                    statusLabelService = NoOpStatusLabelService,
                    unitOfWork = db.unitOfWork()
                )
            }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            val data = persisted
            assertEquals("terminal", data!!.role.name.lowercase())
            assertEquals(
                "done",
                data!!.statusLabel,
                "REST start->TERMINAL must stamp the terminal label, not 'in-progress' or null",
            )
        }

    @Test
    fun `POST items id advance creates role_transitions row with API actor`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Audit Test", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            client.post("/api/v1/items/${item.id}/advance") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Application.Json)
                setBody("""{"trigger":"start"}""")
            }

            // Hard assertion: query the role_transitions table to verify actor was persisted
            val transitionsResult =
                runBlocking {
                    repo.roleTransitionRepository().findByItemId(item.id)
                }
            assertNotNull(transitionsResult, "Role transition query should succeed")
            val transitions = transitionsResult
            assertTrue(transitions.isNotEmpty(), "Role transition row should be created")
            // findByItemId returns rows ordered by transitionedAt DESC — the most recent is first.
            val transition = transitions.first()
            assertNotNull(transition.actorClaim, "Actor claim should be persisted on transition")
            assertEquals(
                "api:$WRITE_TOKEN_ID",
                transition.actorClaim?.id,
                "Actor id should be 'api:<tokenId>'"
            )
            assertEquals(
                "external",
                transition.actorClaim?.kind?.toJsonString(),
                "Actor kind should be 'external'"
            )
        }

    @Test
    fun `POST items id advance without ADVANCE capability returns 403`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Protected Advance", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $READ_ONLY_TOKEN") // no ADVANCE capability
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)

            // Hard assertion: item role unchanged
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals(
                "queue",
                (persisted!!)
                    .role.name
                    .lowercase()
            )
        }

    @Test
    fun `POST items id advance with invalid trigger returns 400`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Bad Trigger", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"cascade"}""") // not a user trigger
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST advance on item CLAIMED by another MCP agent SUCCEEDS and records API actor`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            // Create the item ALREADY claimed by a fleet MCP agent (different identity than the API).
            // Construct the claim fields directly instead of calling repo.claim() — claim() uses
            // SQLite-specific SQL with its own coverage, whereas create() persists
            // claim fields on SQLite (see SQLiteWorkItemClaimFieldsTest). Capture `now` ONCE so the
            // WorkItem.validate() invariant originalClaimedAt <= claimedAt holds.
            val item =
                runBlocking {
                    val now =
                        java.time.Instant
                            .now()
                            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                    repo
                        .workItemRepository()
                        .create(
                            WorkItem(
                                title = "Claimed By Agent",
                                depth = 0,
                                claimedBy = "fleet-agent-7",
                                claimedAt = now,
                                claimExpiresAt = now.plusSeconds(900),
                                originalClaimedAt = now,
                            ),
                        )!!
                }
            // Sanity: the item is actively claimed by a different agent before the API advance.
            assertEquals("fleet-agent-7", item.claimedBy)
            assertNotNull(item.claimExpiresAt)

            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            // 1. The advance MUST succeed despite the foreign claim (API bypasses ownership).
            assertEquals(HttpStatusCode.OK, response.status, "API advance of a claimed item must succeed")
            val body = response.bodyAsText()

            // 4. Response body MUST NOT disclose claimedBy (tiered-disclosure principle).
            assertFalse(body.contains("claimedBy"), "Response must NOT disclose claimedBy: $body")
            assertFalse(body.contains("fleet-agent-7"), "Response must NOT leak the claim holder id: $body")

            // 1b. Hard assertion: the role actually advanced from queue.
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            val persistedItem = persisted
            assertTrue(persistedItem!!.role.name.lowercase() != "queue", "Item should have advanced from queue")
            // The claim holder is unchanged (API does not touch claim fields).
            assertEquals("fleet-agent-7", persistedItem!!.claimedBy, "API advance must not alter claimedBy")

            // 2. The role_transitions row records the API actor (audit must not be lost).
            val transitionsResult = runBlocking { repo.roleTransitionRepository().findByItemId(item.id) }
            val transitions = transitionsResult
            assertTrue(transitions.isNotEmpty(), "A role_transitions row must be recorded")
            val transition = transitions.first()
            assertEquals("api:$WRITE_TOKEN_ID", transition.actorClaim?.id, "Transition must record the API actor id")
            assertEquals("external", transition.actorClaim?.kind?.toJsonString(), "Transition actor kind must be external")
        }

    @Test
    fun `POST advance on gate-incomplete item is REJECTED 422 instead of silently advancing`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            // Item typed 'gated-type' with a REQUIRED queue-phase note that is NOT filled.
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(WorkItem(title = "Gated", type = "gated-type", role = Role.QUEUE, depth = 0))!!
                }
            application { configureWriteTestApp(repo, serverConfig = gatedSchemaService(), unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            // The REST advance now enforces note gates — gate-incomplete start is rejected.
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("gate_blocked"), "Should report gate_blocked error: $body")
            assertTrue(body.contains("spec"), "Should name the missing required note key: $body")

            // Hard assertion: the item did NOT advance (still in queue).
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals(
                "queue",
                (persisted!!)
                    .role.name
                    .lowercase(),
                "Gate-blocked item must NOT have advanced",
            )
        }

    @Test
    fun `POST advance on gate-complete item SUCCEEDS and includes parity fields`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    val i =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "Gated OK", type = "gated-type", role = Role.QUEUE, depth = 0))!!
                    // Fill the required queue note so the gate passes.
                    repo.noteRepository().upsert(Note(itemId = i.id, key = "spec", role = "queue", body = "done"))
                    i
                }
            application { configureWriteTestApp(repo, serverConfig = gatedSchemaService(), unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // Additive parity fields present in the response envelope.
            assertTrue(body.contains("cascadeEvents"), "Response should include cascadeEvents: $body")
            assertTrue(body.contains("unblockedItems"), "Response should include unblockedItems: $body")
            assertTrue(body.contains("expectedNotes"), "Response should include expectedNotes: $body")

            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals(
                "work",
                (persisted!!)
                    .role.name
                    .lowercase()
            )
        }
}

/**
 * Schema service for gate-enforcement tests: type `gated-type` has a REQUIRED queue-phase note
 * (`spec`). The REST advance route resolves this via type-first lookup and enforces the gate.
 */
private fun gatedSchemaService(): ConfigDocument =
    run {
        val schema =
            WorkItemSchema(
                type = "gated-type",
                notes =
                    listOf(
                        NoteSchemaEntry("spec", Role.QUEUE, required = true, description = "spec"),
                        NoteSchemaEntry("impl", Role.WORK, required = false, description = "impl"),
                    ),
            )
        TestConfigResolver.documentOf(mapOf("gated-type" to schema))
    }

// ─────────────────────────────────────────────────────────────────────────────
// Advance — schema review-phase resolution
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Minimal in-memory schema service for advance review-phase tests.
 *
 * Two TYPED schemas (resolved by type):
 * - `review-type` → has a REVIEW-phase note (hasReviewPhase = true)
 * - `flat-type`   → only QUEUE/WORK notes (hasReviewPhase = false)
 */
private fun reviewPhaseSchemaService(): ConfigDocument =
    run {
        val schemas =
            mapOf(
                "review-type" to
                    WorkItemSchema(
                        type = "review-type",
                        notes =
                            listOf(
                                NoteSchemaEntry("scope", Role.QUEUE, required = false, description = "scope"),
                                NoteSchemaEntry("impl", Role.WORK, required = false, description = "impl"),
                                NoteSchemaEntry("review", Role.REVIEW, required = false, description = "review"),
                            ),
                    ),
                "flat-type" to
                    WorkItemSchema(
                        type = "flat-type",
                        notes =
                            listOf(
                                NoteSchemaEntry("scope", Role.QUEUE, required = false, description = "scope"),
                                NoteSchemaEntry("impl", Role.WORK, required = false, description = "impl"),
                            ),
                    ),
            )
        TestConfigResolver.documentOf(schemas)
    }

class AdvanceReviewPhaseTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @Test
    fun `advance from WORK on item whose schema HAS review phase lands in REVIEW not terminal`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            // Item typed 'review-type' (has REVIEW phase), starting in WORK.
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(
                            WorkItem(title = "Has Review", type = "review-type", role = Role.WORK, depth = 0),
                        )!!
                }
            application { configureWriteTestApp(repo, serverConfig = reviewPhaseSchemaService(), unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            // Hard assertion: persisted role is REVIEW (NOT terminal).
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals(
                "review",
                (persisted!!)
                    .role.name
                    .lowercase(),
                "WORK→start with a review schema must land in REVIEW, not terminal",
            )
        }

    @Test
    fun `advance from WORK on item whose schema has NO review phase goes to terminal`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            // Item typed 'flat-type' (no REVIEW phase), starting in WORK.
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(
                            WorkItem(title = "No Review", type = "flat-type", role = Role.WORK, depth = 0),
                        )!!
                }
            application { configureWriteTestApp(repo, serverConfig = reviewPhaseSchemaService(), unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            // Hard assertion: persisted role is TERMINAL (review phase skipped).
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals(
                "terminal",
                (persisted!!)
                    .role.name
                    .lowercase(),
                "WORK→start with no review schema must advance straight to terminal",
            )
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /dependencies + DELETE /dependencies/{id}
// ─────────────────────────────────────────────────────────────────────────────

class DependencyWriteRouteTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @Test
    fun `POST dependencies creates edge and returns 201`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val (from, to) =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "From", depth = 0))!!
                    val b = repo.workItemRepository().create(WorkItem(title = "To", depth = 0))!!
                    Pair(a, b)
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${from.id}","toItemId":"${to.id}","type":"blocks"}""")
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(from.id.toString()), "fromItemId should be in response: $body")
            assertTrue(body.contains(to.id.toString()), "toItemId should be in response: $body")

            // Hard assertion: dependency persisted
            val deps =
                runBlocking {
                    withContext(Dispatchers.IO) {
                        repo.dependencyRepository().findByItemId(from.id)
                    }
                }
            assertTrue(deps.isNotEmpty(), "Dependency should be persisted in DB")
        }

    @Test
    fun `POST dependencies with same fromItemId and toItemId returns 400`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Self Dep", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${item.id}","toItemId":"${item.id}","type":"blocks"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST dependencies cycle detection returns 400 cycle_detected`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val (a, b) =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "A", depth = 0))!!
                    val b = repo.workItemRepository().create(WorkItem(title = "B", depth = 0))!!
                    // Create A → B
                    repo.dependencyRepository().create(Dependency(fromItemId = a.id, toItemId = b.id, type = DependencyType.BLOCKS))
                    Pair(a, b)
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            // Try to create B → A (would create cycle)
            val response =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${b.id}","toItemId":"${a.id}","type":"blocks"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("cycle_detected"), "Should indicate cycle: $body")
        }

    @Test
    fun `DELETE dependencies id deletes edge and returns 204`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val (from, to) =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "From Del", depth = 0))!!
                    val b = repo.workItemRepository().create(WorkItem(title = "To Del", depth = 0))!!
                    Pair(a, b)
                }
            val dep =
                runBlocking {
                    withContext(Dispatchers.IO) {
                        repo.dependencyRepository().create(
                            Dependency(fromItemId = from.id, toItemId = to.id, type = DependencyType.BLOCKS)
                        )
                    }
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.delete("/api/v1/dependencies/${dep.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.NoContent, response.status)

            // Hard assertion: dep gone
            val remaining =
                runBlocking {
                    withContext(Dispatchers.IO) {
                        repo.dependencyRepository().findById(dep.id)
                    }
                }
            assertTrue(remaining == null, "Dependency should be deleted from DB")
        }

    @Test
    fun `DELETE dependencies enforces scope on BOTH endpoints not just from`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            // 'from' is a root inside the caller's scope; 'to' is a separate root OUTSIDE it.
            val (from, to) =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "In Scope From", depth = 0))!!
                    val b = repo.workItemRepository().create(WorkItem(title = "Out Of Scope To", depth = 0))!!
                    Pair(a, b)
                }
            val dep =
                runBlocking {
                    withContext(Dispatchers.IO) {
                        repo.dependencyRepository().create(
                            Dependency(fromItemId = from.id, toItemId = to.id, type = DependencyType.BLOCKS),
                        )
                    }
                }
            // Principal scoped to ONLY the 'from' root — authority over the from side, not the to side.
            application {
                configureWriteTestApp(repo, authConfig = makeWriteAuthConfig(scopeRootIds = setOf(from.id)), unitOfWork = db.unitOfWork())
            }

            val response =
                client.delete("/api/v1/dependencies/${dep.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            // Forbidden: the 'to' endpoint is outside the caller's scope (regression guard for the
            // prior bug where DELETE only checked fromItemId).
            assertEquals(HttpStatusCode.Forbidden, response.status)

            // Hard negative assertion: the edge must NOT have been deleted.
            val remaining =
                runBlocking {
                    withContext(Dispatchers.IO) { repo.dependencyRepository().findById(dep.id) }
                }
            assertNotNull(remaining, "Dependency must NOT be deleted when toItemId is outside the caller's scope")
        }

    @Test
    fun `DELETE dependencies store fault returns 500 db_error with fixed text and no SQL text`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val (from, to) =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "From Fault", depth = 0))!!
                    val b = repo.workItemRepository().create(WorkItem(title = "To Fault", depth = 0))!!
                    Pair(a, b)
                }
            val dep =
                runBlocking {
                    withContext(Dispatchers.IO) {
                        repo.dependencyRepository().create(
                            Dependency(fromItemId = from.id, toItemId = to.id, type = DependencyType.BLOCKS),
                        )
                    }
                }
            // A real SQL fault on the delete itself (the edge lookup before it still succeeds).
            DriverManager.getConnection(db.jdbcUrl).use { conn ->
                conn.createStatement().use {
                    it.execute(
                        "CREATE TRIGGER dep_delete_fault BEFORE DELETE ON dependencies " +
                            "BEGIN SELECT RAISE(ABORT, 'injected-dep-delete-fault'); END"
                    )
                }
            }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.delete("/api/v1/dependencies/${dep.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"db_error\""), "legacy write-fault code expected: $body")
            assertTrue(body.contains("Failed to delete dependency"), "fixed route text expected: $body")
            assertFalse(body.contains("injected-dep-delete-fault"), "SQL text must not reach the body: $body")
            assertFalse(body.contains("\"internal\""), "the catalog code must not reach the body: $body")

            val remaining =
                runBlocking {
                    withContext(Dispatchers.IO) { repo.dependencyRepository().findById(dep.id) }
                }
            assertNotNull(remaining, "the faulted delete must roll back")
        }

    @Test
    fun `POST dependencies without MANAGE_DEPENDENCIES returns 403`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val (from, to) =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "From Forbidden", depth = 0))!!
                    val b = repo.workItemRepository().create(WorkItem(title = "To Forbidden", depth = 0))!!
                    Pair(a, b)
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $READ_ONLY_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${from.id}","toItemId":"${to.id}","type":"blocks"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Extracts the first UUID-looking value for "id" from a JSON body string. */
private fun extractId(body: String): String? {
    val regex = Regex(""""id"\s*:\s*"([0-9a-f\-]{36})"""")
    return regex.find(body)?.groupValues?.get(1)
}
