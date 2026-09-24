package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ChildPlacement
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent test authorship for item `3da296d8` (needs-test-author): REST `POST /items` /
 * `PATCH /items/{id}` reparent-in-transaction coverage — S4, S5, S10 of the frozen `test-plan`
 * note (queue phase, read before this file existed), plus an Idempotency-Key replay probe.
 *
 * Oracles:
 * - O1 `child.depth == parent.depth + 1`, `child.rootId == (parent.rootId ?: parent.id)`, parent
 *   read AS PERSISTED at assert time.
 * - O3 `current/docs/api-rest.md` §10: `400 not_found` — "parentId not found" for POST; PATCH's
 *   equivalent (same section, and precedent `PatchReparentCycleGuardTest` S8) is a 400 body whose
 *   `error` field is exactly `"not_found"`. Parent-not-found error shapes are UNCHANGED by this fix
 *   (diagnosis: "NotFound inside the txn maps to each path's existing parent-not-found error").
 *
 * SEAM: same [MutateOnFirstTransactionRepository] technique as
 * `ManageItemsParentPlacementInTxnTest` (see that file's class KDoc for why `resolveChildPlacement`
 * must be re-implemented rather than left to `by delegate` forwarding), injected via a
 * [RepositoryProvider]-typed local test app (`configureParentPlacementTestApp`, mirroring
 * `PatchReparentCycleGuardTest.configureReparentTestApp` in this same package — `ApiTestHelper.kt`
 * and `WriteRoutesTest.kt` are not edited; only their existing `buildH2RepositoryProvider` /
 * `makeWriteAuthConfig` / `WRITE_TOKEN` helpers are reused, since `WriteRoutesTest`'s own
 * `configureWriteTestApp` is typed to the concrete `DefaultRepositoryProvider` and cannot accept a
 * wrapped provider).
 *
 * BLINDNESS: authored from `diagnosis`/`test-plan` (queue-phase, frozen, `keys`-filtered
 * `query_notes`), the verbatim declarations block supplied in the dispatch prompt, and existing
 * conventions in this package (`PatchReparentCycleGuardTest.kt`, `WriteRoutesTest.kt`,
 * `ApiTestHelper.kt`). No `src/main` file, diff, or commit was read.
 */
class ItemWriteRoutesParentPlacementInTxnTest {
    /** See `ManageItemsParentPlacementInTxnTest.MutateOnFirstTransactionRepository` KDoc. */
    private class MutateOnFirstTransactionRepository(
        private val delegate: WorkItemRepository,
        private val mutate: suspend (WorkItemRepository) -> Unit
    ) : WorkItemRepository by delegate {
        private var hasFired = false

        override suspend fun inTransaction(block: suspend () -> Unit) {
            delegate.inTransaction {
                if (!hasFired) {
                    hasFired = true
                    mutate(delegate)
                }
                block()
            }
        }

        override suspend fun resolveChildPlacement(parentId: UUID): Result<ChildPlacement> =
            when (val parent = getById(parentId)) {
                is Result.Success ->
                    Result.Success(
                        ChildPlacement(
                            parentId = parent.data.id,
                            depth = parent.data.depth + 1,
                            rootId =
                                parent.data.rootId ?: parent.data.id
                        )
                    )
                is Result.Error -> Result.Error(parent.error)
            }
    }

    private class WorkItemRepoOverrideProvider(
        private val delegate: RepositoryProvider,
        private val workItemRepo: WorkItemRepository
    ) : RepositoryProvider by delegate {
        override fun workItemRepository(): WorkItemRepository = workItemRepo
    }

    /**
     * Minimal Ktor app wiring for item write routes, parameterized on the [RepositoryProvider]
     * INTERFACE so a wrapped provider can be injected — mirrors
     * [PatchReparentCycleGuardTest.configureReparentTestApp]'s auth/route wiring conventions.
     */
    private fun Application.configureParentPlacementTestApp(
        repositoryProvider: RepositoryProvider,
        idempotencyCache: IdempotencyCache = IdempotencyCache(),
        authConfig: ApiAuthConfig.Bearer = makeWriteAuthConfig()
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
                    statusLabelService = NoOpStatusLabelService
                )
            }
        }
    }

    private fun etagFor(item: WorkItem): String = "\"v1-${item.modifiedAt.toEpochMilli()}\""

    private suspend fun stampSelfRoot(
        repo: DefaultRepositoryProvider,
        item: WorkItem
    ): WorkItem = (repo.workItemRepository().update(item.copy(rootId = item.id)) as Result.Success).data

    /** R (root, self-rooted) -> A (depth1) -> P (depth2); Q is a second, unrelated self-rooted root. */
    private suspend fun threeLevelTreeWithAlternateRoot(repo: DefaultRepositoryProvider): List<WorkItem> {
        val root = stampSelfRoot(repo, (repo.workItemRepository().create(WorkItem(title = "R", depth = 0)) as Result.Success).data)
        val a =
            (
                repo.workItemRepository().create(
                    WorkItem(title = "A", parentId = root.id, depth = 1, rootId = root.id)
                ) as Result.Success
            ).data
        val p =
            (
                repo.workItemRepository().create(
                    WorkItem(title = "P", parentId = a.id, depth = 2, rootId = root.id)
                ) as Result.Success
            ).data
        val q = stampSelfRoot(repo, (repo.workItemRepository().create(WorkItem(title = "Q", depth = 0)) as Result.Success).data)
        return listOf(root, a, p, q)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // S4 — POST /items with parentId=P, P concurrently reparented inside the write transaction
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S4 POST items under P reflects P's placement as of the write transaction, not a pre-transaction snapshot`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (_, _, p, q) = runBlocking { threeLevelTreeWithAlternateRoot(repo) }
            val wrapped =
                MutateOnFirstTransactionRepository(repo.workItemRepository()) { d ->
                    d.update(p.copy(parentId = q.id, depth = 1, rootId = q.id))
                }
            application { configureParentPlacementTestApp(WorkItemRepoOverrideProvider(repo, wrapped)) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Child of P S4","parentId":"${p.id}"}""")
                }

            assertEquals(HttpStatusCode.Created, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2, json["depth"]?.jsonPrimitive?.int, "O1: must be P's LIVE depth (1) + 1, not the pre-transaction depth (2) + 1")

            // O1 is a property of the PERSISTED row (test-plan: "P as PERSISTED at assert time");
            // ItemDto does not carry a rootId field (arbitration, 3da296d8), so rootId is read back
            // through the UNDERLYING (unwrapped) repository the test app was built with.
            val childId = UUID.fromString(json["id"]!!.jsonPrimitive.content)
            val persisted = runBlocking { repo.workItemRepository().getById(childId) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(2, persisted.data.depth)
            assertEquals(q.id, persisted.data.rootId, "O1: must be P's LIVE rootId (Q)")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S5 — PATCH /items/{id} reparenting X under P, P concurrently reparented inside the txn
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S5 PATCH reparenting X under P reflects P's placement as of the write transaction, not a pre-transaction snapshot`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (_, _, p, q) = runBlocking { threeLevelTreeWithAlternateRoot(repo) }
            val x =
                runBlocking {
                    stampSelfRoot(
                        repo,
                        (repo.workItemRepository().create(WorkItem(title = "X S5", depth = 0)) as Result.Success).data
                    )
                }
            val wrapped =
                MutateOnFirstTransactionRepository(repo.workItemRepository()) { d ->
                    d.update(p.copy(parentId = q.id, depth = 1, rootId = q.id))
                }
            application { configureParentPlacementTestApp(WorkItemRepoOverrideProvider(repo, wrapped)) }

            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${p.id}"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2, json["depth"]?.jsonPrimitive?.int, "O1: X must land at P's LIVE depth (1) + 1")

            // ItemDto does not carry a rootId field (arbitration, 3da296d8) — read the persisted
            // row through the underlying repository instead.
            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(2, persisted.data.depth)
            assertEquals(q.id, persisted.data.rootId, "O1: X must inherit P's LIVE rootId (Q)")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S10 — POST /items under P, P concurrently DELETED inside the write transaction (O3)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S10 POST items under P whose parent is deleted inside the write transaction returns 400 not_found without an orphan row`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root =
                runBlocking {
                    stampSelfRoot(
                        repo,
                        (repo.workItemRepository().create(WorkItem(title = "R S10", depth = 0)) as Result.Success).data
                    )
                }
            val p =
                runBlocking {
                    (
                        repo.workItemRepository().create(
                            WorkItem(title = "P S10 (leaf, will be deleted)", parentId = root.id, depth = 1, rootId = root.id)
                        ) as Result.Success
                    ).data
                }
            val wrapped = MutateOnFirstTransactionRepository(repo.workItemRepository()) { d -> d.delete(p.id) }
            application { configureParentPlacementTestApp(WorkItemRepoOverrideProvider(repo, wrapped)) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Orphan Child S10","parentId":"${p.id}"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, "O3: actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("not_found", json["error"]?.jsonPrimitive?.content, "actual: $json")

            val all = runBlocking { repo.workItemRepository().findByFilters() }
            assertTrue(all is Result.Success)
            val orphan = (all as Result.Success).data.items.filter { it.title == "Orphan Child S10" }
            assertTrue(orphan.isEmpty(), "O3: a parent deleted inside the write transaction must leave NO orphan row: $orphan")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Adversarial probe: Idempotency-Key replay of the S10 400 does not re-run and re-delete
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `probe replaying the same Idempotency-Key on the S10 400 not_found returns the cached body and does not re-run`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root =
                runBlocking {
                    stampSelfRoot(
                        repo,
                        (repo.workItemRepository().create(WorkItem(title = "R Probe", depth = 0)) as Result.Success).data
                    )
                }
            val p =
                runBlocking {
                    (
                        repo.workItemRepository().create(
                            WorkItem(title = "P Probe (leaf, will be deleted)", parentId = root.id, depth = 1, rootId = root.id)
                        ) as Result.Success
                    ).data
                }
            val wrapped = MutateOnFirstTransactionRepository(repo.workItemRepository()) { d -> d.delete(p.id) }
            val cache = IdempotencyCache()
            application { configureParentPlacementTestApp(WorkItemRepoOverrideProvider(repo, wrapped), idempotencyCache = cache) }

            val idempotencyKey = UUID.randomUUID().toString()
            val makeRequest: suspend () -> HttpResponse = {
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", idempotencyKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Orphan Child Probe","parentId":"${p.id}"}""")
                }
            }

            val first = makeRequest()
            assertEquals(HttpStatusCode.BadRequest, first.status)
            val firstBody = first.bodyAsText()

            val second = makeRequest()
            assertEquals(first.status, second.status)
            assertEquals(firstBody, second.bodyAsText(), "a replay with the same Idempotency-Key must return the cached body verbatim")

            val all = runBlocking { repo.workItemRepository().findByFilters() }
            assertTrue(all is Result.Success)
            assertTrue((all as Result.Success).data.items.none { it.title == "Orphan Child Probe" })
        }

    // Probe catalog, recorded per skill §6 (every probe attempted, including N/A ones):
    // - boundary/suffix, alternate separators, encoded/UNC forms, mixed case: N/A - parentId is a
    //   UUID identifying an existing row, not a path/string surface.
    // - empty vs absent vs null: not re-authored here — pre-existing `WriteRoutesTest` coverage
    //   ("POST items without parentId stamps rootId as its own id") already exercises the no-parent
    //   path, which never calls resolveChildPlacement and is unaffected by this fix.
    // - duplicates/ordering: N/A - a single scalar parentId field per request, not a collection.
    // - replay/idempotency: covered above ("probe replaying the same Idempotency-Key...").
}
