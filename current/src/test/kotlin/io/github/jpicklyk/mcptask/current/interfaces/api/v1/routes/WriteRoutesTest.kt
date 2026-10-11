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
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.findByFilters
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.test.TestConfigResolver
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
// POST /items — create
// ─────────────────────────────────────────────────────────────────────────────

class ItemCreateRouteTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @Test
    fun `POST items creates item and returns 201 with persisted data`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"test-item","title":"New Task","priority":"high"}""")
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("New Task"), "Title should be in response: $body")
            assertTrue(body.contains("\"high\""), "Priority should be in response: $body")
            assertNotNull(response.headers[HttpHeaders.ETag], "ETag header should be present")

            // Hard assertion: query back and verify the item was actually persisted
            val itemId = extractId(body)
            assertNotNull(itemId, "Response should contain id: $body")
            val persisted = runBlocking { repo.workItemRepository().getById(UUID.fromString(itemId)) }
            assertNotNull(persisted, "Item should be persisted in DB")
            assertEquals("New Task", persisted.title)
        }

    @Test
    fun `POST items without WRITE_ITEMS capability returns 403`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $READ_ONLY_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"test-item","title":"Should Not Create"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)

            // Hard negative assertion: no item with that title should exist
            val items = runBlocking { repo.workItemRepository().findByFilters() }
            assertNotNull(items)
            assertTrue(
                items.items.none { it.title == "Should Not Create" },
                "Item should NOT be created when capability is missing"
            )
        }

    @Test
    fun `POST items with validation error returns 400`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"test-item","title":""}""") // blank title — validation error
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("validation_error") || body.contains("not blank"), "Should have error: $body")
        }

    @Test
    fun `POST items with parentId stamps rootId inherited from the parent`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val parent =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Root", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            // Root itself was created directly via the repository (not through this route), so
            // it has no rootId yet — the create route must fall back to the parent's own id.
            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"test-item","title":"Child","parentId":"${parent.id}"}""")
                }
            assertEquals(HttpStatusCode.Created, response.status)
            val childId = extractId(response.bodyAsText())
            assertNotNull(childId)

            val persistedChild = runBlocking { repo.workItemRepository().getById(UUID.fromString(childId)) }
            assertNotNull(persistedChild)
            assertEquals(parent.id, persistedChild.rootId, "Child must inherit the root's id")
        }

    @Test
    fun `POST items without parentId stamps rootId as its own id`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"test-item","title":"New Root"}""")
                }
            assertEquals(HttpStatusCode.Created, response.status)
            val itemId = extractId(response.bodyAsText())
            assertNotNull(itemId)

            val persisted = runBlocking { repo.workItemRepository().getById(UUID.fromString(itemId)) }
            assertNotNull(persisted)
            assertEquals(UUID.fromString(itemId), persisted.rootId, "A root-level item must be its own root")
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /items/{id} — merge patch
// ─────────────────────────────────────────────────────────────────────────────

class ItemPatchRouteTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @Test
    fun `PATCH items id updates title and returns 200 with new ETag`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Original", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val etag = "\"v1-${item.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Updated Title"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Updated Title"), "Title should be updated: $body")

            // Hard assertion: query back and verify persisted
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertNotNull(persisted)
            assertEquals("Updated Title", persisted.title)
        }

    @Test
    fun `PATCH items id without If-Match returns 400`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "No ETag", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Should Not Update"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)

            // Hard negative: title unchanged
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals("No ETag", persisted!!.title)
        }

    @Test
    fun `PATCH items id with stale ETag returns 412`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Stale ETag Test", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, "\"v1-0\"") // obviously wrong ETag
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Should Fail"}""")
                }
            assertEquals(HttpStatusCode.PreconditionFailed, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("etag_mismatch"), "Should indicate etag_mismatch: $body")
        }

    @Test
    fun `PATCH items id with disallowed field returns 400 field_not_patchable`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Protected Fields", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val etag = "\"v1-${item.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"id":"evil-id","title":"Also Updated"}""") // 'id' is rejected
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("field_not_patchable"), "Should indicate field_not_patchable: $body")

            // Hard assertion: item unchanged
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals("Protected Fields", persisted!!.title)
        }

    @Test
    fun `PATCH items id with wrong Content-Type returns 415 with Accept-Patch header`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Content Type Test", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val etag = "\"v1-${item.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Text.Plain) // wrong content type
                    setBody("title=Updated")
                }

            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
            val acceptPatch = response.headers["Accept-Patch"]
            assertNotNull(acceptPatch, "Accept-Patch header should be present")
            assertTrue(acceptPatch.contains("application/merge-patch+json"), "Accept-Patch should list merge-patch: $acceptPatch")
        }

    @Test
    fun `PATCH items null field in patch body removes field value`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(
                            WorkItem(title = "Has Description", description = "to remove", depth = 0)
                        )!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val etag = "\"v1-${item.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"description":null}""") // null = delete
                }

            assertEquals(HttpStatusCode.OK, response.status)
            // Hard assertion: description removed (RFC 7396 null = delete)
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertNull(
                persisted!!.description,
                "Description should be cleared to null by an explicit null patch"
            )
        }

    @Test
    fun `PATCH items absent field in patch body leaves field unchanged`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(
                            WorkItem(title = "Keep Description", description = "keep me", depth = 0)
                        )!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val etag = "\"v1-${item.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Only Title Changed"}""") // description absent
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals("Only Title Changed", persisted!!.title)
            assertEquals(
                "keep me",
                persisted!!.description,
                "Description should be unchanged when absent from patch"
            )
        }

    @Test
    fun `PATCH items invalid priority returns 400`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Priority Test", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val etag = "\"v1-${item.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"priority":"not-a-valid-priority"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `PATCH items parentId null moves item to root`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val (parent, child) =
                runBlocking {
                    val p = repo.workItemRepository().create(WorkItem(title = "Parent", depth = 0))!!
                    val c = repo.workItemRepository().create(WorkItem(title = "Child", parentId = p.id, depth = 1))!!
                    Pair(p, c)
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val etag = "\"v1-${child.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${child.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":null}""") // move to root
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // Hard assertion: depth should now be 0 (root)
            assertTrue(body.contains("\"depth\":0"), "Should be root after null parentId: $body")
        }

    @Test
    fun `PATCH items parentId change cascades depth to descendants`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            // root A(0) -> b(1) -> c(2), plus sibling target d(1) under root A.
            val (_, b, c, d) =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "Root A", depth = 0))!!
                    val b = repo.workItemRepository().create(WorkItem(title = "B", parentId = a.id, depth = 1))!!
                    val c = repo.workItemRepository().create(WorkItem(title = "C", parentId = b.id, depth = 2))!!
                    val d = repo.workItemRepository().create(WorkItem(title = "D", parentId = a.id, depth = 1))!!
                    listOf(a, b, c, d)
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val etag = "\"v1-${b.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${b.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${d.id}"}""") // B moves under D (depth 1 -> 2)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"depth\":2"), "B should be at depth 2 after moving under D: $body")

            // C must cascade from depth 2 to depth 3 even though this PATCH only targeted B.
            val persistedC = runBlocking { repo.workItemRepository().getById(c.id) }
            assertEquals(3, persistedC!!.depth, "C's depth must cascade with B's move")
        }

    @Test
    fun `PATCH items parentId change to a different root restamps rootId for item and descendants`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            // Root A -> B -> C, plus an independent Root D — both created directly via the
            // repository with an explicit rootId, mirroring what the create route would stamp.
            val (_, b, c, rootD) =
                runBlocking {
                    val a =
                        repo.workItemRepository().create(WorkItem(title = "Root A", depth = 0))!!
                    val aWithRoot =
                        repo.workItemRepository().update(a.copy(rootId = a.id))!!
                    val b =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "B", parentId = aWithRoot.id, depth = 1, rootId = aWithRoot.id))!!
                    val c =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "C", parentId = b.id, depth = 2, rootId = aWithRoot.id))!!
                    val d =
                        repo.workItemRepository().create(WorkItem(title = "Root D", depth = 0))!!
                    val dWithRoot =
                        repo.workItemRepository().update(d.copy(rootId = d.id))!!
                    listOf(aWithRoot, b, c, dWithRoot)
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            // Move B under Root D: same depth (1), but rootId must flip for B and cascade to C.
            val etag = "\"v1-${b.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${b.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${rootD.id}"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)

            val persistedB = runBlocking { repo.workItemRepository().getById(b.id) }
            val persistedC = runBlocking { repo.workItemRepository().getById(c.id) }
            assertNotNull(persistedB)
            assertNotNull(persistedC)
            assertEquals(rootD.id, persistedB.rootId, "B's rootId must flip to Root D after reparent")
            assertEquals(rootD.id, persistedC.rootId, "C must cascade to Root D even though depth didn't change")
        }

    @Test
    fun `PATCH items parentId null restamps rootId to the item's own id`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val (parent, child) =
                runBlocking {
                    val p = repo.workItemRepository().create(WorkItem(title = "Parent", depth = 0))!!
                    val pWithRoot = repo.workItemRepository().update(p.copy(rootId = p.id))!!
                    val c =
                        repo
                            .workItemRepository()
                            .create(WorkItem(title = "Child", parentId = pWithRoot.id, depth = 1, rootId = pWithRoot.id))!!
                    Pair(pWithRoot, c)
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val etag = "\"v1-${child.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${child.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":null}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)

            val persistedChild = runBlocking { repo.workItemRepository().getById(child.id) }
            assertNotNull(persistedChild)
            assertEquals(child.id, persistedChild.rootId, "Child must become its own root after moving to root level")
        }

    @Test
    fun `PATCH items with nested object in properties merges recursively`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(
                            WorkItem(title = "Props Test", properties = """{"key1":"val1","key2":"val2"}""", depth = 0)
                        )!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val etag = "\"v1-${item.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"properties":{"key2":"updated","key3":"new"}}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            val props = persisted!!.properties
            assertNotNull(props, "Properties should not be null")
            // key1 preserved from original, key2 updated, key3 added
            assertTrue(props.contains("key1"), "key1 should be preserved: $props")
            assertTrue(props.contains("updated"), "key2 should be updated: $props")
            assertTrue(props.contains("key3"), "key3 should be added: $props")
        }

    @Test
    fun `PATCH items post-merge domain validation failure returns 400`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Validation Test", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val etag = "\"v1-${item.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"complexity":99}""") // valid JSON but out of range 1-10
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains("validation_error") || body.contains("complexity"),
                "Should report validation error for out-of-range complexity: $body"
            )

            // Hard assertion: complexity unchanged in DB
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertNull(
                persisted!!.complexity,
                "Complexity should be unchanged after failed patch"
            )
        }

    @Test
    fun `PATCH items with role field returns 400 field_not_patchable`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Role Test", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val etag = "\"v1-${item.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"terminal"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("field_not_patchable"), "Should indicate field_not_patchable: $body")
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /items/{id}
// ─────────────────────────────────────────────────────────────────────────────

class ItemDeleteRouteTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @Test
    fun `DELETE items id deletes and returns 204`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "To Delete", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.delete("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.NoContent, response.status)

            // Hard assertion: item gone
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertNull(persisted, "Item should be deleted from DB")
        }

    @Test
    fun `DELETE items id without WRITE_ITEMS returns 403`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Protected", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.delete("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $READ_ONLY_TOKEN")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)

            // Hard negative: item still exists
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertNotNull(persisted, "Item should still exist")
        }

    @Test
    fun `DELETE items id with mismatched ETag returns 412`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "ETag Guard", depth = 0))!!
                }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.delete("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, "\"v1-0\"") // stale
                }
            assertEquals(HttpStatusCode.PreconditionFailed, response.status)

            // Hard negative: item still exists
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertNotNull(persisted, "Item should still exist after rejected delete")
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /items/{id}/advance
// ─────────────────────────────────────────────────────────────────────────────

class AdvanceRouteTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()
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
// Idempotency
// ─────────────────────────────────────────────────────────────────────────────

class IdempotencyTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @Test
    fun `same Idempotency-Key on POST items returns cached response and creates item exactly once`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val idempotencyKey = UUID.randomUUID().toString()
            val makeRequest: suspend () -> HttpResponse = {
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", idempotencyKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"test-item","title":"Idempotent Item"}""")
                }
            }

            val first = makeRequest()
            val second = makeRequest()

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Created, second.status)

            // Hard assertion: EXACTLY one item was created (not two)
            val items = runBlocking { repo.workItemRepository().findByFilters() }
            val matching = items.items.filter { it.title == "Idempotent Item" }
            assertEquals(1, matching.size, "Idempotent POST should create exactly one item, got ${matching.size}")
        }

    @Test
    fun `different Idempotency-Key on POST items re-executes and creates separate item`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            client.post("/api/v1/items") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType(ContentType.Application.Json)
                setBody("""{"type":"test-item","title":"Separate Item"}""")
            }
            client.post("/api/v1/items") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                header("Idempotency-Key", UUID.randomUUID().toString()) // different key
                contentType(ContentType.Application.Json)
                setBody("""{"type":"test-item","title":"Separate Item"}""")
            }

            // Hard assertion: two items created (different keys = different operations)
            val items = runBlocking { repo.workItemRepository().findByFilters() }
            val matching = items.items.filter { it.title == "Separate Item" }
            assertEquals(2, matching.size, "Different Idempotency-Keys should create 2 items, got ${matching.size}")
        }

    @Test
    fun `malformed Idempotency-Key returns 400`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", "not-a-uuid")
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"test-item","title":"Should Not Create"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// Scope enforcement
// ─────────────────────────────────────────────────────────────────────────────

class WriteScopeEnforcementTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @Test
    fun `PATCH item outside scope returns 403`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val scopeRootId = UUID.randomUUID()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Out Of Scope", depth = 0))!!
                }
            // Scoped auth config: only allows access to scopeRootId subtree (item is not there)
            val scopedAuthConfig = makeWriteAuthConfig(scopeRootIds = setOf(scopeRootId))
            application { configureWriteTestApp(repo, authConfig = scopedAuthConfig, unitOfWork = db.unitOfWork()) }

            val etag = "\"v1-${item.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Scoped Attack"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)

            // Hard assertion: title unchanged
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals("Out Of Scope", persisted!!.title)
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
