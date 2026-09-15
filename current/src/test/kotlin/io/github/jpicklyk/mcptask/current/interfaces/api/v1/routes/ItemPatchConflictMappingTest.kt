package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item `20ccc9fb` (frozen `test-plan` note,
 * queue phase): a repository-level optimistic-lock [RepositoryError.ConflictError] returned
 * from `WorkItemRepository.update()` during `PATCH /items/{id}` must map to `409 Conflict` with
 * `ErrorDto.error == "version_conflict"` — NOT the generic `500 db_error` mapping that every other
 * [RepositoryError] subtype still gets. An `If-Match` precondition failure is a SEPARATE, unchanged
 * code path (`412 etag_mismatch`) — the two must never collide.
 *
 * Oracles (frozen before this test file existed, per the `test-plan` note):
 * - `current/docs/api-rest.md` §4 (ETag concurrency: 412 is reserved for `If-Match` mismatches),
 *   §5 (idempotency: a replay returns the cached status+body verbatim, without re-executing),
 *   §6 (error code table: 412 `etag_mismatch`, 400 `precondition_required`, 500 `db_error`),
 *   §8 (`ErrorDto` shape: `{error, message, details?}`), §10 (`PATCH /items/{id}` contract).
 * - The `test-plan` note's own DECISION section: a repository conflict happens when `If-Match`
 *   MATCHED (no precondition was violated), so it is a distinct 409 family member, not a 412.
 *
 * Bug behaviour this suite must fail against: before the fix, `ItemWriteRoutes.kt`'s PATCH handler
 * maps every non-conflict-aware [RepositoryError] branch (including [RepositoryError.ConflictError])
 * to `500 db_error`. A test that can't tell `500 db_error` apart from `409 version_conflict` would
 * pass whether or not the fix exists — every scenario below asserts the exact status AND the exact
 * `error` code together to rule that out.
 *
 * Test seam (named in `test-plan`, not invented here): [ScriptedWorkItemRepository] wraps a real
 * H2-backed [WorkItemRepository] and, when scripted, deterministically returns a
 * [RepositoryError.ConflictError] (or another scripted [RepositoryError]) from `update()`/`delete()`
 * instead of ever touching row-version data — this is what makes the ConflictError reachable
 * without racing concurrent requests.
 */
class ItemPatchConflictMappingTest {
    /**
     * Wraps a real [WorkItemRepository], optionally substituting a scripted result for `update()`
     * and/or `delete()`. Counts invocations of both so tests can assert the repository seam was
     * (or, for short-circuited requests, was NOT) actually reached.
     */
    private class ScriptedWorkItemRepository(
        private val delegate: WorkItemRepository,
        private val onUpdate: ((WorkItem) -> Result<WorkItem>)? = null,
        private val onDelete: ((UUID) -> Result<Boolean>)? = null,
    ) : WorkItemRepository by delegate {
        var updateCallCount: Int = 0
            private set
        var deleteCallCount: Int = 0
            private set

        override suspend fun update(item: WorkItem): Result<WorkItem> {
            updateCallCount++
            return onUpdate?.invoke(item) ?: delegate.update(item)
        }

        override suspend fun delete(id: UUID): Result<Boolean> {
            deleteCallCount++
            return onDelete?.invoke(id) ?: delegate.delete(id)
        }
    }

    /**
     * [RepositoryProvider] delegate that substitutes [workItemRepository] with a caller-supplied
     * instance while forwarding every other repository accessor to [delegate] unchanged.
     */
    private class WorkItemRepoOverrideProvider(
        private val delegate: RepositoryProvider,
        private val workItemRepo: WorkItemRepository,
    ) : RepositoryProvider by delegate {
        override fun workItemRepository(): WorkItemRepository = workItemRepo
    }

    /**
     * Minimal Ktor app wiring for item write routes, parameterized on the [RepositoryProvider]
     * interface (rather than a concrete provider type) so a [WorkItemRepoOverrideProvider] can be
     * injected. Mirrors [configureWriteTestApp]'s auth/route wiring conventions.
     */
    private fun Application.configureConflictTestApp(
        repositoryProvider: RepositoryProvider,
        idempotencyCache: IdempotencyCache = IdempotencyCache(),
        authConfig: ApiAuthConfig.Bearer = makeWriteAuthConfig(),
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
                itemWriteRoutes(
                    repositoryProvider,
                    DegradedModePolicy.ACCEPT_CACHED,
                    idempotencyCache,
                    NoOpNoteSchemaService,
                    statusLabelService = NoOpStatusLabelService,
                )
            }
        }
    }

    private fun etagFor(item: WorkItem): String = "\"v1-${item.modifiedAt.toEpochMilli()}\""

    // ─────────────────────────────────────────────────────────────────────────
    // S1 — Happy path unchanged
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S1 PATCH with matching If-Match and valid merge patch returns 200 with fresh ETag`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Original", depth = 0)).getOrNull()!!
                }
            application { configureConflictTestApp(repo) }

            val originalEtag = etagFor(item)
            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, originalEtag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Updated Title"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Updated Title"), "Field should be applied: $body")
            val newEtag = response.headers[HttpHeaders.ETag]
            assertNotNull(newEtag, "ETag header should be present")
            assertFalse(newEtag == originalEtag, "ETag must change after a successful update")

            val persisted = runBlocking { repo.workItemRepository().getById(item.id) }
            assertEquals("Updated Title", (persisted as Result.Success).data.title)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S2 — the bug: ConflictError must map to 409 version_conflict, not 500
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S2 update() returning ConflictError while If-Match matched returns 409 version_conflict`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Conflict Target", depth = 0)).getOrNull()!!
                }
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onUpdate = { Result.Error(RepositoryError.ConflictError("row version moved underneath the request")) },
                )
            application { configureConflictTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(item))
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Should Conflict"}""")
                }

            assertEquals(
                HttpStatusCode.Conflict,
                response.status,
                "A repository ConflictError with a matched If-Match must be 409, not 500: ${response.bodyAsText()}",
            )
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(
                "version_conflict",
                json["error"]?.jsonPrimitive?.content,
                "ErrorDto.error must be version_conflict: $json",
            )
            assertEquals(1, scripted.updateCallCount, "update() must have been reached exactly once")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S3 — non-conflict repository errors are unaffected
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S3 update() returning DatabaseError stays 500 db_error`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "DB Error Target", depth = 0)).getOrNull()!!
                }
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onUpdate = { Result.Error(RepositoryError.DatabaseError("query failed")) },
                )
            application { configureConflictTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(item))
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Should 500"}""")
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("db_error", json["error"]?.jsonPrimitive?.content)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S4 / S5 — If-Match precondition failures are unchanged and never reach update()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S4 stale If-Match returns 412 etag_mismatch and never calls update()`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Stale ETag Target", depth = 0)).getOrNull()!!
                }
            // Scripted to ALWAYS conflict if reached — proves 412 comes from the precondition
            // check itself, not from a ConflictError that happens to also map to something else.
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onUpdate = { Result.Error(RepositoryError.ConflictError("must not be reached")) },
                )
            application { configureConflictTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, "\"v1-0\"") // obviously stale
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Should Not Apply"}""")
                }

            assertEquals(HttpStatusCode.PreconditionFailed, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("etag_mismatch", json["error"]?.jsonPrimitive?.content)
            assertEquals(0, scripted.updateCallCount, "A 412 precondition failure must short-circuit before update()")
        }

    @Test
    fun `S5 missing If-Match returns 400 precondition_required and never calls update()`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "No ETag Target", depth = 0)).getOrNull()!!
                }
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onUpdate = { Result.Error(RepositoryError.ConflictError("must not be reached")) },
                )
            application { configureConflictTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Should Not Apply"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("precondition_required", json["error"]?.jsonPrimitive?.content)
            assertEquals(0, scripted.updateCallCount, "A 400 missing-precondition must short-circuit before update()")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S6 — exact ErrorDto shape, no repository exception text echoed
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S6 409 body is exactly error and message with no repository exception text echoed`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Shape Target", depth = 0)).getOrNull()!!
                }
            // A deliberately internal-looking repository message that must NOT leak verbatim.
            val internalMarker = "SQLITE_BUSY_ROWLOCKED offset=0xdeadbeef txn=42"
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onUpdate = { Result.Error(RepositoryError.ConflictError(internalMarker)) },
                )
            application { configureConflictTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(item))
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Shape Check"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            val rawBody = response.bodyAsText()
            val json = Json.parseToJsonElement(rawBody).jsonObject
            assertEquals(
                setOf("error", "message"),
                json.keys,
                "409 body must be exactly {error,message} with no extra/leaked fields: $rawBody",
            )
            assertEquals("version_conflict", json["error"]?.jsonPrimitive?.content)
            val message = json["message"]?.jsonPrimitive?.content
            assertNotNull(message, "message must be present")
            assertTrue(message.isNotBlank(), "message must not be blank")
            assertFalse(
                rawBody.contains(internalMarker),
                "The server-authored message must not echo the raw repository exception text: $rawBody",
            )
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S7 — Idempotency-Key replay of a 409 returns the cached response verbatim
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S7 replaying the same Idempotency-Key on a 409 returns the cached body and does not re-run update()`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Idempotent Conflict Target", depth = 0)).getOrNull()!!
                }
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onUpdate = { Result.Error(RepositoryError.ConflictError("row version moved underneath the request")) },
                )
            val cache = IdempotencyCache()
            application { configureConflictTestApp(WorkItemRepoOverrideProvider(repo, scripted), idempotencyCache = cache) }

            val idempotencyKey = UUID.randomUUID().toString()
            val etag = etagFor(item)
            val makeRequest: suspend () -> io.ktor.client.statement.HttpResponse = {
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", idempotencyKey)
                    header(HttpHeaders.IfMatch, etag)
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Replayed Conflict"}""")
                }
            }

            val first = makeRequest()
            assertEquals(HttpStatusCode.Conflict, first.status)
            val firstBody = first.bodyAsText()
            assertEquals(1, scripted.updateCallCount, "update() should have run exactly once on the first request")

            val second = makeRequest()
            assertEquals(
                first.status,
                second.status,
                "A replay with the same Idempotency-Key must return the same cached status",
            )
            assertEquals(
                firstBody,
                second.bodyAsText(),
                "A replay with the same Idempotency-Key must return the cached body verbatim",
            )
            assertEquals(
                1,
                scripted.updateCallCount,
                "A cached replay must NOT re-execute update() against the repository",
            )
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Adversarial probes
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `probe scope check precedes conflict mapping - scoped-out token gets 403 before update() runs`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    // No explicit rootId: the item is its own root (see ItemRoutesTest scope
                    // convention: a root-level item's effective scope root is its own id).
                    repo.workItemRepository().create(WorkItem(title = "Scoped Out Target", depth = 0)).getOrNull()!!
                }
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onUpdate = { Result.Error(RepositoryError.ConflictError("must not be reached")) },
                )
            // WRITE_TOKEN scoped to an unrelated random root — item is outside scope.
            val authConfig = makeWriteAuthConfig(scopeRootIds = setOf(UUID.randomUUID()))
            application {
                configureConflictTestApp(WorkItemRepoOverrideProvider(repo, scripted), authConfig = authConfig)
            }

            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(item))
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Should Not Apply"}""")
                }

            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "A scope rejection must win over any repository-level conflict: ${response.bodyAsText()}",
            )
            assertEquals(0, scripted.updateCallCount, "update() must not be reached for an out-of-scope item")
        }

    @Test
    fun `probe empty json patch body still reaches update() and maps ConflictError to 409`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Empty Patch Target", depth = 0)).getOrNull()!!
                }
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onUpdate = { Result.Error(RepositoryError.ConflictError("row version moved underneath the request")) },
                )
            application { configureConflictTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(item))
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("version_conflict", json["error"]?.jsonPrimitive?.content)
            assertEquals(1, scripted.updateCallCount, "an empty {} merge patch must still reach update()")
        }

    @Test
    fun `probe empty and whitespace-only If-Match are present-but-non-matching - 412 not 400`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Blank ETag Target", depth = 0)).getOrNull()!!
                }
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onUpdate = { Result.Error(RepositoryError.ConflictError("must not be reached")) },
                )
            application { configureConflictTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            // per api-rest.md sec.4/sec.10: only an ABSENT If-Match header is precondition_required
            // (400); any header VALUE that is present but does not match the current ETag —
            // including an empty or whitespace-only value — is a mismatch (412), not "missing".
            val emptyResponse =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, "")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Should Not Apply"}""")
                }
            assertEquals(
                HttpStatusCode.PreconditionFailed,
                emptyResponse.status,
                "An empty (but present) If-Match must be treated as a mismatch, not a missing header: " +
                    emptyResponse.bodyAsText(),
            )

            val whitespaceResponse =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, "   ")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Should Not Apply"}""")
                }
            assertEquals(
                HttpStatusCode.PreconditionFailed,
                whitespaceResponse.status,
                "A whitespace-only If-Match must be treated as a mismatch, not a missing header: " +
                    whitespaceResponse.bodyAsText(),
            )
            assertEquals(0, scripted.updateCallCount, "Neither blank variant should ever reach update()")
        }

    @Test
    fun `probe unquoted If-Match value (matching digits, missing quotes) is a mismatch`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Unquoted ETag Target", depth = 0)).getOrNull()!!
                }
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onUpdate = { Result.Error(RepositoryError.ConflictError("must not be reached")) },
                )
            application { configureConflictTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            // The documented ETag format is quoted ("v1-<millis>"); a value that carries the same
            // digits but omits the surrounding quotes does not string-equal the current ETag.
            val unquoted = "v1-${item.modifiedAt.toEpochMilli()}"
            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, unquoted)
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Should Not Apply"}""")
                }
            assertEquals(
                HttpStatusCode.PreconditionFailed,
                response.status,
                "An unquoted If-Match value must not be treated as matching the quoted ETag: ${response.bodyAsText()}",
            )
            assertEquals(0, scripted.updateCallCount)
        }

    @Test
    fun `probe DELETE conflict stays 500 db_error - the fix is PATCH-only`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Delete Conflict Target", depth = 0)).getOrNull()!!
                }
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onDelete = { Result.Error(RepositoryError.ConflictError("delete-path conflict, out of scope for this fix")) },
                )
            application { configureConflictTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            val response =
                client.delete("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(
                HttpStatusCode.InternalServerError,
                response.status,
                "DELETE's ConflictError mapping is explicitly out of scope (test-plan S8) and must stay 500: " +
                    response.bodyAsText(),
            )
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("db_error", json["error"]?.jsonPrimitive?.content)
            assertEquals(1, scripted.deleteCallCount)
        }
}
