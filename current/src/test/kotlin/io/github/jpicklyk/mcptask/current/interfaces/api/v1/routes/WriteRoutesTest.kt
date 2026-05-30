package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Bearer token for a principal with ALL write capabilities. */
const val WRITE_TOKEN = "write-all-token-xyz456"
const val WRITE_TOKEN_ID = "test-write-principal"

/** Bearer token for a principal with only READ capability (no write). */
const val READ_ONLY_TOKEN = TEST_TOKEN
const val READ_ONLY_TOKEN_ID = TEST_TOKEN_ID

/**
 * Builds a test auth config that includes:
 * - WRITE_TOKEN → all capabilities
 * - TEST_TOKEN (READ_ONLY_TOKEN) → READ only
 */
fun makeWriteTestAuthConfig(scopeRootIds: Set<UUID>? = null): ApiAuthConfig.Bearer {
    val writePrincipal =
        ApiPrincipal(
            tokenId = WRITE_TOKEN_ID,
            scope = ApiScope(rootIds = scopeRootIds, tagsInclude = emptySet()),
            capabilities =
                setOf(
                    ApiCapability.READ,
                    ApiCapability.WRITE_ITEMS,
                    ApiCapability.WRITE_NOTES,
                    ApiCapability.ADVANCE,
                    ApiCapability.MANAGE_DEPENDENCIES,
                ),
            authMode = ApiAuthMode.BEARER,
        )
    val readOnlyPrincipal =
        ApiPrincipal(
            tokenId = READ_ONLY_TOKEN_ID,
            scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
            capabilities = setOf(ApiCapability.READ),
            authMode = ApiAuthMode.BEARER,
        )
    return ApiAuthConfig.Bearer(
        tokens =
            mapOf(
                HashBytes(sha256(WRITE_TOKEN)) to writePrincipal,
                HashBytes(sha256(READ_ONLY_TOKEN)) to readOnlyPrincipal,
            ),
    )
}

/**
 * Configures a test Ktor application with write routes registered.
 */
fun Application.configureWriteTestApp(
    repo: DefaultRepositoryProvider,
    idempotencyCache: IdempotencyCache = IdempotencyCache(),
    degradedModePolicy: DegradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
    authConfig: ApiAuthConfig.Bearer = makeWriteTestAuthConfig(),
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
            itemWriteRoutes(repo, degradedModePolicy, idempotencyCache)
            noteWriteRoutes(repo, degradedModePolicy, idempotencyCache)
            dependencyWriteRoutes(repo, degradedModePolicy)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /items — create
// ─────────────────────────────────────────────────────────────────────────────

class ItemCreateRouteTest {
    @Test
    fun `POST items creates item and returns 201 with persisted data`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureWriteTestApp(repo) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"New Task","priority":"high"}""")
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
            assertTrue(persisted is Result.Success, "Item should be persisted in DB")
            assertEquals("New Task", (persisted as Result.Success).data.title)
        }

    @Test
    fun `POST items without WRITE_ITEMS capability returns 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureWriteTestApp(repo) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $READ_ONLY_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Should Not Create"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)

            // Hard negative assertion: no item with that title should exist
            val items = runBlocking { repo.workItemRepository().findByFilters() }
            assertTrue(items is Result.Success)
            assertTrue(
                (items as Result.Success).data.none { it.title == "Should Not Create" },
                "Item should NOT be created when capability is missing"
            )
        }

    @Test
    fun `POST items with validation error returns 400`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureWriteTestApp(repo) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":""}""") // blank title — validation error
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("validation_error") || body.contains("not blank"), "Should have error: $body")
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /items/{id} — merge patch
// ─────────────────────────────────────────────────────────────────────────────

class ItemPatchRouteTest {
    @Test
    fun `PATCH items id updates title and returns 200 with new ETag`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Original", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

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
            assertTrue(persisted is Result.Success)
            assertEquals("Updated Title", (persisted as Result.Success).data.title)
        }

    @Test
    fun `PATCH items id without If-Match returns 400`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "No ETag", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Should Not Update"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)

            // Hard negative: title unchanged
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals("No ETag", (persisted as Result.Success).data.title)
        }

    @Test
    fun `PATCH items id with stale ETag returns 412`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Stale ETag Test", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

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
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Protected Fields", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

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
            assertEquals("Protected Fields", (persisted as Result.Success).data.title)
        }

    @Test
    fun `PATCH items id with wrong Content-Type returns 415 with Accept-Patch header`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Content Type Test", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

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
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(
                            WorkItem(title = "Has Description", description = "to remove", depth = 0)
                        ).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

            val etag = "\"v1-${item.modifiedAt.toEpochMilli()}\""
            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"description":null}""") // null = delete
                }

            assertEquals(HttpStatusCode.OK, response.status)
            // Hard assertion: description removed
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertFalse(
                (persisted as Result.Success).data.description != null &&
                    (persisted as Result.Success).data.description!!.isNotBlank(),
                "Description should be cleared"
            )
        }

    @Test
    fun `PATCH items absent field in patch body leaves field unchanged`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(
                            WorkItem(title = "Keep Description", description = "keep me", depth = 0)
                        ).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

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
            assertEquals("Only Title Changed", (persisted as Result.Success).data.title)
            assertEquals(
                "keep me",
                (persisted as Result.Success).data.description,
                "Description should be unchanged when absent from patch"
            )
        }

    @Test
    fun `PATCH items invalid priority returns 400`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Priority Test", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

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
            val repo = buildH2RepositoryProvider()
            val (parent, child) =
                runBlocking {
                    val p = repo.workItemRepository().create(WorkItem(title = "Parent", depth = 0)).getOrNull()!!
                    val c = repo.workItemRepository().create(WorkItem(title = "Child", parentId = p.id, depth = 1)).getOrNull()!!
                    Pair(p, c)
                }
            application { configureWriteTestApp(repo) }

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
    fun `PATCH items with nested object in properties merges recursively`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(
                            WorkItem(title = "Props Test", properties = """{"key1":"val1","key2":"val2"}""", depth = 0)
                        ).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

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
            val props = (persisted as Result.Success).data.properties
            assertNotNull(props, "Properties should not be null")
            // key1 preserved from original, key2 updated, key3 added
            assertTrue(props.contains("key1"), "key1 should be preserved: $props")
            assertTrue(props.contains("updated"), "key2 should be updated: $props")
            assertTrue(props.contains("key3"), "key3 should be added: $props")
        }

    @Test
    fun `PATCH items post-merge domain validation failure returns 400`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Validation Test", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

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
                (persisted as Result.Success).data.complexity,
                "Complexity should be unchanged after failed patch"
            )
        }

    @Test
    fun `PATCH items with role field returns 400 field_not_patchable`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Role Test", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

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
    @Test
    fun `DELETE items id deletes and returns 204`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "To Delete", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

            val response =
                client.delete("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.NoContent, response.status)

            // Hard assertion: item gone
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertTrue(persisted is Result.Error, "Item should be deleted from DB")
        }

    @Test
    fun `DELETE items id without WRITE_ITEMS returns 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Protected", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

            val response =
                client.delete("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $READ_ONLY_TOKEN")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)

            // Hard negative: item still exists
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertTrue(persisted is Result.Success, "Item should still exist")
        }

    @Test
    fun `DELETE items id with mismatched ETag returns 412`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "ETag Guard", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

            val response =
                client.delete("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, "\"v1-0\"") // stale
                }
            assertEquals(HttpStatusCode.PreconditionFailed, response.status)

            // Hard negative: item still exists
            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertTrue(persisted is Result.Success, "Item should still exist after rejected delete")
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /items/{id}/advance
// ─────────────────────────────────────────────────────────────────────────────

class AdvanceRouteTest {
    @Test
    fun `POST items id advance starts item and returns 200 with new role`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Advance Me", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

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
                (persisted as Result.Success)
                    .data.role.name
                    .lowercase() != "queue",
                "Item role should have advanced from queue"
            )
        }

    @Test
    fun `POST items id advance creates role_transitions row with API actor`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Audit Test", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

            client.post("/api/v1/items/${item.id}/advance") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Application.Json)
                setBody("""{"trigger":"start"}""")
            }

            // Hard assertion: query the role_transitions table to verify actor was persisted
            val transitions =
                runBlocking {
                    repo.roleTransitionRepository().findByItemId(item.id)
                }
            assertTrue(transitions.isNotEmpty(), "Role transition row should be created")
            val transition = transitions.last()
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
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Protected Advance", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

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
                (persisted as Result.Success)
                    .data.role.name
                    .lowercase()
            )
        }

    @Test
    fun `POST items id advance with invalid trigger returns 400`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Bad Trigger", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"cascade"}""") // not a user trigger
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST items id advance response body does not contain claimedBy`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Claimed Item Test", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            if (response.status == HttpStatusCode.OK) {
                val body = response.bodyAsText()
                assertFalse(
                    body.contains("claimedBy"),
                    "Response MUST NOT disclose claimedBy (tiered-disclosure): $body"
                )
            }
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// PUT /items/{id}/notes/{key} — note upsert
// ─────────────────────────────────────────────────────────────────────────────

class NoteWriteRouteTest {
    @Test
    fun `PUT note creates new note and persists with api actor`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Note Target", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

            val response =
                client.put("/api/v1/items/${item.id}/notes/impl-note") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"work","body":"Some implementation detail"}""")
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("impl-note"), "Note key should be in response: $body")
            assertTrue(body.contains("work"), "Note role should be in response: $body")
            assertNotNull(response.headers[HttpHeaders.ETag], "ETag should be present")

            // Hard assertion: query back actor attribution
            val noteResult = runBlocking { repo.noteRepository().findByItemIdAndKey(item.id, "impl-note") }
            assertTrue(noteResult is Result.Success)
            val note = (noteResult as Result.Success).data
            assertNotNull(note, "Note should be persisted")
            assertNotNull(note!!.actorClaim, "Actor claim should be persisted on note")
            assertEquals(
                "api:$WRITE_TOKEN_ID",
                note.actorClaim?.id,
                "Actor id must be 'api:<tokenId>'"
            )
            assertEquals(
                "external",
                note.actorClaim?.kind?.toJsonString(),
                "Actor kind must be 'external'"
            )
        }

    @Test
    fun `PUT note updates existing note`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Update Note Item", depth = 0)).getOrNull()!!
                }
            // Pre-create note
            runBlocking {
                repo.noteRepository().upsert(Note(itemId = item.id, key = "update-me", role = "work", body = "original"))
            }
            application { configureWriteTestApp(repo) }

            // First read to get ETag
            val readResponse =
                client.get("/api/v1/items/${item.id}/notes/update-me") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            val readBody = readResponse.bodyAsText()
            val noteEtag = readResponse.headers[HttpHeaders.ETag]
            assertNotNull(noteEtag, "Should get ETag from read: $readBody")

            val response =
                client.put("/api/v1/items/${item.id}/notes/update-me") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, noteEtag!!)
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"work","body":"updated content"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)

            // Hard assertion: body updated in DB
            val noteResult = runBlocking { repo.noteRepository().findByItemIdAndKey(item.id, "update-me") }
            assertEquals("updated content", ((noteResult as Result.Success).data)!!.body)
        }

    @Test
    fun `PUT note with stale ETag on update returns 412`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Note ETag", depth = 0)).getOrNull()!!
                }
            runBlocking {
                repo.noteRepository().upsert(Note(itemId = item.id, key = "stale-test", role = "work", body = "original"))
            }
            application { configureWriteTestApp(repo) }

            val response =
                client.put("/api/v1/items/${item.id}/notes/stale-test") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, "\"v1-0\"") // stale
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"work","body":"should fail"}""")
                }

            assertEquals(HttpStatusCode.PreconditionFailed, response.status)

            // Hard negative: body unchanged
            val noteResult = runBlocking { repo.noteRepository().findByItemIdAndKey(item.id, "stale-test") }
            assertEquals("original", ((noteResult as Result.Success).data)!!.body)
        }

    @Test
    fun `PUT note without WRITE_NOTES capability returns 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Notes Forbidden", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

            val response =
                client.put("/api/v1/items/${item.id}/notes/new-note") {
                    header("Authorization", "Bearer $READ_ONLY_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"work","body":"should not create"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)

            // Hard negative: note not created
            val noteResult = runBlocking { repo.noteRepository().findByItemIdAndKey(item.id, "new-note") }
            assertTrue((noteResult as Result.Success).data == null, "Note should NOT be created")
        }

    @Test
    fun `DELETE note deletes it and returns 204`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Delete Note Item", depth = 0)).getOrNull()!!
                }
            runBlocking {
                repo.noteRepository().upsert(Note(itemId = item.id, key = "delete-me", role = "work", body = "bye"))
            }
            application { configureWriteTestApp(repo) }

            val response =
                client.delete("/api/v1/items/${item.id}/notes/delete-me") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.NoContent, response.status)

            // Hard assertion: note gone
            val noteResult = runBlocking { repo.noteRepository().findByItemIdAndKey(item.id, "delete-me") }
            assertTrue((noteResult as Result.Success).data == null, "Note should be deleted")
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /dependencies + DELETE /dependencies/{id}
// ─────────────────────────────────────────────────────────────────────────────

class DependencyWriteRouteTest {
    @Test
    fun `POST dependencies creates edge and returns 201`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (from, to) =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "From", depth = 0)).getOrNull()!!
                    val b = repo.workItemRepository().create(WorkItem(title = "To", depth = 0)).getOrNull()!!
                    Pair(a, b)
                }
            application { configureWriteTestApp(repo) }

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
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        repo.dependencyRepository().findByItemId(from.id)
                    }
                }
            assertTrue(deps.isNotEmpty(), "Dependency should be persisted in DB")
        }

    @Test
    fun `POST dependencies with same fromItemId and toItemId returns 400`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Self Dep", depth = 0)).getOrNull()!!
                }
            application { configureWriteTestApp(repo) }

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
            val repo = buildH2RepositoryProvider()
            val (a, b) =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "A", depth = 0)).getOrNull()!!
                    val b = repo.workItemRepository().create(WorkItem(title = "B", depth = 0)).getOrNull()!!
                    // Create A → B
                    repo.dependencyRepository().create(Dependency(fromItemId = a.id, toItemId = b.id, type = DependencyType.BLOCKS))
                    Pair(a, b)
                }
            application { configureWriteTestApp(repo) }

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
            val repo = buildH2RepositoryProvider()
            val (from, to) =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "From Del", depth = 0)).getOrNull()!!
                    val b = repo.workItemRepository().create(WorkItem(title = "To Del", depth = 0)).getOrNull()!!
                    Pair(a, b)
                }
            val dep =
                runBlocking {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        repo.dependencyRepository().create(
                            Dependency(fromItemId = from.id, toItemId = to.id, type = DependencyType.BLOCKS)
                        )
                    }
                }
            application { configureWriteTestApp(repo) }

            val response =
                client.delete("/api/v1/dependencies/${dep.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.NoContent, response.status)

            // Hard assertion: dep gone
            val remaining =
                runBlocking {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        repo.dependencyRepository().findById(dep.id)
                    }
                }
            assertTrue(remaining == null, "Dependency should be deleted from DB")
        }

    @Test
    fun `POST dependencies without MANAGE_DEPENDENCIES returns 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (from, to) =
                runBlocking {
                    val a = repo.workItemRepository().create(WorkItem(title = "From Forbidden", depth = 0)).getOrNull()!!
                    val b = repo.workItemRepository().create(WorkItem(title = "To Forbidden", depth = 0)).getOrNull()!!
                    Pair(a, b)
                }
            application { configureWriteTestApp(repo) }

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
    @Test
    fun `same Idempotency-Key on POST items returns cached response and creates item exactly once`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val cache = IdempotencyCache()
            application { configureWriteTestApp(repo, idempotencyCache = cache) }

            val idempotencyKey = UUID.randomUUID().toString()
            val makeRequest: suspend () -> io.ktor.client.statement.HttpResponse = {
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", idempotencyKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Idempotent Item"}""")
                }
            }

            val first = makeRequest()
            val second = makeRequest()

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Created, second.status)

            // Hard assertion: EXACTLY one item was created (not two)
            val items = runBlocking { repo.workItemRepository().findByFilters() }
            val matching = (items as Result.Success).data.filter { it.title == "Idempotent Item" }
            assertEquals(1, matching.size, "Idempotent POST should create exactly one item, got ${matching.size}")
        }

    @Test
    fun `different Idempotency-Key on POST items re-executes and creates separate item`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val cache = IdempotencyCache()
            application { configureWriteTestApp(repo, idempotencyCache = cache) }

            client.post("/api/v1/items") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType(ContentType.Application.Json)
                setBody("""{"title":"Separate Item"}""")
            }
            client.post("/api/v1/items") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                header("Idempotency-Key", UUID.randomUUID().toString()) // different key
                contentType(ContentType.Application.Json)
                setBody("""{"title":"Separate Item"}""")
            }

            // Hard assertion: two items created (different keys = different operations)
            val items = runBlocking { repo.workItemRepository().findByFilters() }
            val matching = (items as Result.Success).data.filter { it.title == "Separate Item" }
            assertEquals(2, matching.size, "Different Idempotency-Keys should create 2 items, got ${matching.size}")
        }

    @Test
    fun `malformed Idempotency-Key returns 400`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureWriteTestApp(repo) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", "not-a-uuid")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Should Not Create"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// Scope enforcement
// ─────────────────────────────────────────────────────────────────────────────

class WriteScopeEnforcementTest {
    @Test
    fun `PATCH item outside scope returns 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val scopeRootId = UUID.randomUUID()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Out Of Scope", depth = 0)).getOrNull()!!
                }
            // Scoped auth config: only allows access to scopeRootId subtree (item is not there)
            val scopedAuthConfig = makeWriteTestAuthConfig(scopeRootIds = setOf(scopeRootId))
            application { configureWriteTestApp(repo, authConfig = scopedAuthConfig) }

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
            assertEquals("Out Of Scope", (persisted as Result.Success).data.title)
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
