package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Independent test-author coverage for item `1a5ccf06` (frozen `test-plan` note, queue phase):
 * `PATCH /items/{id}` re-parent's descendant-cycle guard must (a) detect a cycle by walking the
 * REAL ancestor chain rather than a hop count bounded by the proposed parent's stored `depth`
 * (which is a denormalized, unvalidated column — [WorkItem.validate] only enforces `depth >= 0`
 * and `depth >= 1` for a child, never that it matches the item's true distance from its root),
 * and (b) fail CLOSED (500 `db_error`, nothing written) rather than open when the ancestor lookup
 * itself errors.
 *
 * Bug behaviour this suite must fail against (pre-fix `ItemWriteRoutes.kt` new-parent branch):
 * (a) the manual walk bounds itself to `parentData.depth + 1` hops read off the proposed parent's
 * own (possibly stale) row, so a descendant whose stored `depth` understates its true distance
 * from the root lets the walk exit before it ever reaches the item being moved — the cycle is
 * silently admitted. (b) a `Result.Error` from a lookup mid-walk is folded into `ancestorId = null`
 * — "reached a root, no cycle" — so a transient repository failure also results in an admitted,
 * silently cyclic write. Both defects hand a cyclic parent chain to the same-transaction
 * `recomputeDescendantDepths` -> `findDescendants` cascade, whose BFS/CTE has no visited set and
 * spins forever. A test that merely checks "not 200" would pass whether or not the fix exists, so
 * every scenario below asserts the exact status AND exact `error` code together, and every
 * scenario that could actually produce a cycle if the guard fails open stubs `findDescendants` to
 * an immediate [Result.Error] — see MANDATORY SAFETY STUB below — so a pre-fix run fails red
 * instead of hanging.
 *
 * ORACLES (frozen in the `test-plan` note before this file existed):
 * - O1 `current/docs/api-rest.md` PATCH /items/{id} (post-#299): self-parent -> 400
 *   `validation_error` "An item cannot be its own parent"; descendant-as-parent -> 400
 *   `validation_error` "Cannot re-parent an item under its own descendant"; check order is
 *   not_found -> scope_forbidden -> validation_error, nothing written until every check clears.
 * - O2 `current/docs/api-rest.md` error table: `db_error` = 500 (this file's own repository-
 *   failure mapping, `ItemWriteRoutes.kt` ~656/676); 409 is reserved for `version_conflict`, not
 *   used here.
 * - O3 `AuthorizationPlugin.kt` (~222-231): a `findAncestorChains` [Result.Error] surfaced during
 *   a hierarchy decision resolves to the DENY outcome — the fail-closed precedent this fix follows.
 * - O4 diagnosis note: stored `depth` is denormalized and never validated against the real parent
 *   chain, so it must never be trusted as a bound on how far a cycle-detection walk needs to go.
 *
 * SIGNATURES (all pre-existing; none new): `itemWriteRoutes` takes the [RepositoryProvider]
 * INTERFACE (not a concrete provider type), so no new test seam is required.
 * `WorkItemRepository.findAncestorChains(Set<UUID>): Result<Map<UUID, List<WorkItem>>>`,
 * `getById(UUID): Result<WorkItem>`, `findDescendants(UUID): Result<List<WorkItem>>`,
 * `update(WorkItem): Result<WorkItem>`.
 *
 * SEAM (test-only, named in `test-plan`): a private `WorkItemRepository by delegate` wrapper
 * injected through a private `RepositoryProvider by delegate` provider — the pattern already
 * used in this package at `ItemPatchConflictMappingTest.kt` (~76-115). `ApiTestHelper.kt` is not
 * edited; only its existing `buildH2RepositoryProvider`/`makeWriteAuthConfig`/`WRITE_TOKEN`
 * helpers are reused.
 *
 * MANDATORY SAFETY STUB: every scenario below that constructs a REAL ancestor/descendant
 * relationship between the patched item and the proposed new parent (S3, S4, S5, S8-direct-child
 * probe) stubs `findDescendants` on the wrapper to return an immediate [Result.Error]. If the
 * guard under test ever fails open, the route would otherwise persist the cyclic parent and then
 * cascade into the real (unguarded) `findDescendants` BFS/CTE, which never returns — this stub is
 * what keeps a red pre-fix run a *fast, observable* failure instead of a hang.
 *
 * BLINDNESS: authored from the item's `test-plan` and `diagnosis` notes (both queue-phase,
 * explicitly permitted by the `test-author` skill's blindness rule), the public signatures above,
 * and existing test conventions in this package (`ItemPatchConflictMappingTest.kt`,
 * `PatchReparentScopeTest.kt`, `WriteRoutesTest.kt`, `ApiTestHelper.kt`). Disclosure: an initial
 * `query_notes` `list` call was made without a `keys` filter and incidentally returned this item's
 * `implementation-notes` and `session-tracking` bodies alongside `diagnosis`/`test-plan`, which the
 * skill's blindness rule (§4) says a test author must not read. No scenario, oracle, or assertion
 * value in this file was drawn from that content — every oracle above cites `test-plan` S1-S9/O1-O4
 * or `diagnosis`, both of which independently and permissibly state the fix approach (queue-phase
 * content is frozen before implementation and is explicitly readable). Recorded here and in
 * `test-manifest` per skill §4/§8 for audit; it did not change how this suite was designed.
 */
class PatchReparentCycleGuardTest {
    /**
     * Wraps a real [WorkItemRepository], optionally substituting scripted results for `getById`,
     * `findAncestorChains`, `findDescendants` and/or `update`. Each `on*` lambda may return `null`
     * to fall through to the real [delegate] for that specific call. Counts `update()` and
     * `findAncestorChains()` invocations so tests can assert the repository seam was (or, for
     * short-circuited requests, was NOT) actually reached.
     */
    private class ScriptedWorkItemRepository(
        private val delegate: WorkItemRepository,
        private val onGetById: ((UUID) -> Result<WorkItem>?)? = null,
        private val onFindAncestorChains: ((Set<UUID>) -> Result<Map<UUID, List<WorkItem>>>?)? = null,
        private val onFindDescendants: ((UUID) -> Result<List<WorkItem>>?)? = null,
        private val onUpdate: ((WorkItem) -> Result<WorkItem>)? = null,
    ) : WorkItemRepository by delegate {
        var updateCallCount: Int = 0
            private set
        var findAncestorChainsCallCount: Int = 0
            private set

        override suspend fun getById(id: UUID): Result<WorkItem> = onGetById?.invoke(id) ?: delegate.getById(id)

        override suspend fun findAncestorChains(itemIds: Set<UUID>): Result<Map<UUID, List<WorkItem>>> {
            findAncestorChainsCallCount++
            return onFindAncestorChains?.invoke(itemIds) ?: delegate.findAncestorChains(itemIds)
        }

        override suspend fun findDescendants(id: UUID): Result<List<WorkItem>> =
            onFindDescendants?.invoke(id) ?: delegate.findDescendants(id)

        override suspend fun update(item: WorkItem): Result<WorkItem> {
            updateCallCount++
            return onUpdate?.invoke(item) ?: delegate.update(item)
        }
    }

    /**
     * [RepositoryProvider] delegate that substitutes [workItemRepo] for `workItemRepository()`
     * while forwarding every other accessor to [delegate] unchanged.
     */
    private class WorkItemRepoOverrideProvider(
        private val delegate: RepositoryProvider,
        private val workItemRepo: WorkItemRepository,
    ) : RepositoryProvider by delegate {
        override fun workItemRepository(): WorkItemRepository = workItemRepo
    }

    /**
     * Minimal Ktor app wiring for item write routes, parameterized on the [RepositoryProvider]
     * INTERFACE (rather than a concrete provider type) so a [WorkItemRepoOverrideProvider] can be
     * injected. Mirrors [ItemPatchConflictMappingTest.configureConflictTestApp] and
     * [configureWriteTestApp]'s auth/route wiring conventions.
     */
    private fun Application.configureReparentTestApp(
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

    /** Creates a root item and stamps its own `rootId` onto itself (existing test convention). */
    private suspend fun makeRoot(
        repo: DefaultRepositoryProvider,
        title: String,
    ): WorkItem {
        val created = repo.workItemRepository().create(WorkItem(title = title, depth = 0)).getOrNull()!!
        return repo.workItemRepository().update(created.copy(rootId = created.id)).getOrNull()!!
    }

    /**
     * Creates a child of [parent]. [depthOverride], when given, persists a `depth` different from
     * `parent.depth + 1` — the stale-depth fixture the guard must not trust (O4).
     */
    private suspend fun makeChild(
        repo: DefaultRepositoryProvider,
        parent: WorkItem,
        title: String,
        depthOverride: Int? = null,
    ): WorkItem {
        val rootId = parent.rootId ?: parent.id
        val depth = depthOverride ?: (parent.depth + 1)
        return repo
            .workItemRepository()
            .create(WorkItem(parentId = parent.id, rootId = rootId, depth = depth, title = title))
            .getOrNull()!!
    }

    // ─────────────────────────────────────────────────────────────────────────
    // S1 — happy path: legitimate re-parent among siblings still succeeds
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S1 legitimate reparent among siblings returns 200 with recomputed depth and unchanged rootId`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (root, x, p) =
                runBlocking {
                    val r = makeRoot(repo, "Root S1")
                    val xItem = makeChild(repo, r, "X S1")
                    val pItem = makeChild(repo, r, "P S1")
                    Triple(r, xItem, pItem)
                }
            application { configureReparentTestApp(repo) }

            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${p.id}"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status, "A legitimate reparent must succeed: ${response.bodyAsText()}")
            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(p.id, persisted.data.parentId, "X must be re-parented to P")
            assertEquals(2, persisted.data.depth, "X's depth must be recomputed from P.depth + 1")
            assertEquals(root.id, persisted.data.rootId, "rootId must remain R (P is under the same root)")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S2 — self-parent still rejected (untouched identity check, kept as a guard rail)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S2 self reparent returns 400 validation_error with the exact self-parent message and never calls update`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val x = runBlocking { makeRoot(repo, "X S2") }
            val scripted = ScriptedWorkItemRepository(repo.workItemRepository())
            application { configureReparentTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${x.id}"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("validation_error", json["error"]?.jsonPrimitive?.content)
            assertEquals(
                "An item cannot be its own parent",
                json["message"]?.jsonPrimitive?.content,
                "O1: the self-parent message must be exact: $json",
            )
            assertEquals(0, scripted.updateCallCount, "A rejected self-parent must never reach update()")
            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertNull((persisted as Result.Success).data.parentId, "X must remain a root")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S3 — THE BUG: a stale (understated) stored depth must not shorten the cycle walk
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S3 reparenting under a descendant with an understated stale depth is still rejected 400`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (x, d) =
                runBlocking {
                    val xItem = makeRoot(repo, "X S3")
                    val a = makeChild(repo, xItem, "A S3")
                    val b = makeChild(repo, a, "B S3")
                    // True depth of D is 3 (X=0, A=1, B=2, D=3); stored depth understates it to 1,
                    // exactly the off-by-one shape from the diagnosis reproduction (hopsRemaining =
                    // depth+1 = 2, but the real walk D->B->A->X needs 3 hops to reach X).
                    val d = makeChild(repo, b, "D S3", depthOverride = 1)
                    Pair(xItem, d)
                }
            // MANDATORY SAFETY STUB: if the guard fails open here, the route would persist a cyclic
            // parent and cascade into the real findDescendants BFS, which never returns.
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onFindDescendants = {
                        Result.Error(RepositoryError.DatabaseError("must not be reached - guard should reject before any cascade"))
                    },
                )
            application { configureReparentTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${d.id}"}""")
                }

            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "A stale/understated stored depth on the proposed parent must not defeat the cycle guard: ${response.bodyAsText()}",
            )
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("validation_error", json["error"]?.jsonPrimitive?.content)
            assertEquals(
                "Cannot re-parent an item under its own descendant",
                json["message"]?.jsonPrimitive?.content,
                "O1: the descendant-cycle message must be exact: $json",
            )
            assertEquals(0, scripted.updateCallCount, "A rejected cyclic reparent must never reach update()")
            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertNull(persisted.data.parentId, "X's parentId must be unchanged")
            assertEquals(0, persisted.data.depth, "X's depth must be unchanged")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S4 — deep HONEST chain: detection is not bounded by chain length either
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S4 reparenting under a deep honestly-depthed descendant is still rejected 400`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (x, d) =
                runBlocking {
                    val xItem = makeRoot(repo, "X S4")
                    val a = makeChild(repo, xItem, "A S4")
                    val b = makeChild(repo, a, "B S4")
                    val c = makeChild(repo, b, "C S4")
                    val d = makeChild(repo, c, "D S4") // honest depth = 4, no override
                    Pair(xItem, d)
                }
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onFindDescendants = {
                        Result.Error(RepositoryError.DatabaseError("must not be reached - guard should reject before any cascade"))
                    },
                )
            application { configureReparentTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${d.id}"}""")
                }

            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "A deep honest descendant chain must still be detected: ${response.bodyAsText()}"
            )
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("validation_error", json["error"]?.jsonPrimitive?.content)
            assertEquals("Cannot re-parent an item under its own descendant", json["message"]?.jsonPrimitive?.content)
            assertEquals(0, scripted.updateCallCount)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S5 — THE BUG: a repository error during the ancestor lookup must fail CLOSED (500), not open
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S5 a repository error during ancestor lookup returns 500 db_error and writes nothing`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (x, b, d) =
                runBlocking {
                    val xItem = makeRoot(repo, "X S5")
                    val a = makeChild(repo, xItem, "A S5")
                    val b = makeChild(repo, a, "B S5")
                    val d = makeChild(repo, b, "D S5")
                    Triple(xItem, b, d)
                }
            // Blind to which surface the fix actually calls (test-plan S5): stub BOTH the
            // getById-on-a-mid-chain-ancestor surface AND the findAncestorChains-on-the-proposed-
            // parent surface. MANDATORY SAFETY STUB: findDescendants also errors immediately,
            // because if the guard fails open on this error it would persist a real cycle (B/D are
            // real descendants of X here) and cascade into the unguarded BFS.
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onGetById = { id ->
                        if (id == b.id) Result.Error(RepositoryError.DatabaseError("ancestor lookup failed")) else null
                    },
                    onFindAncestorChains = { ids ->
                        if (d.id in ids) Result.Error(RepositoryError.DatabaseError("ancestor chain lookup failed")) else null
                    },
                    onFindDescendants = {
                        Result.Error(RepositoryError.DatabaseError("must not be reached - guard should fail closed before any cascade"))
                    },
                )
            application { configureReparentTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${d.id}"}""")
                }

            assertEquals(
                HttpStatusCode.InternalServerError,
                response.status,
                "A repository error during the ancestor lookup must fail closed (500), not open (200/400): ${response.bodyAsText()}",
            )
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("db_error", json["error"]?.jsonPrimitive?.content)
            assertEquals(0, scripted.updateCallCount, "A failed-closed lookup error must never reach update()")

            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertNull(persisted.data.parentId, "X must be unchanged in the DB after the 500")
            assertEquals(0, persisted.data.depth)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S6 — isolation: a title-only patch under the SAME erroring wrapper is unaffected
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S6 title-only patch under the same erroring ancestor-lookup wrapper still returns 200`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (x, b, d) =
                runBlocking {
                    val xItem = makeRoot(repo, "X S6")
                    val a = makeChild(repo, xItem, "A S6")
                    val b = makeChild(repo, a, "B S6")
                    val d = makeChild(repo, b, "D S6")
                    Triple(xItem, b, d)
                }
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onGetById = { id -> if (id == b.id) Result.Error(RepositoryError.DatabaseError("ancestor lookup failed")) else null },
                    onFindAncestorChains = { ids ->
                        if (d.id in ids) Result.Error(RepositoryError.DatabaseError("ancestor chain lookup failed")) else null
                    },
                )
            application { configureReparentTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Retitled S6"}""") // parentId absent -> no ancestor lookup at all
                }

            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "A patch that never touches parentId must not be affected by armed ancestor-lookup error stubs: ${response.bodyAsText()}",
            )
            assertEquals(1, scripted.updateCallCount)
            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertEquals("Retitled S6", (persisted as Result.Success).data.title)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S7 — edge: parentId:null (move to root) under the same erroring wrapper still succeeds
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S7 parentId null under an erroring ancestor-lookup wrapper still moves the item to root`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val x =
                runBlocking {
                    val root = makeRoot(repo, "Root S7")
                    makeChild(repo, root, "X S7")
                }
            // Arm ONLY the ancestor-lookup surface: parentId:null must never even reach an
            // ancestor lookup, since there is no proposed new parent to walk from (RFC 7396 s2 /
            // O1 established semantics). getById is deliberately left un-stubbed (falls through to
            // the real delegate) because the route's own existence check on the PATCH target (X
            // itself) runs before the parentId:null branch and must succeed for this scenario to
            // be observable at all. findDescendants is also deliberately left un-stubbed: moving a
            // child to root IS a real parent change, so the route legitimately cascades into
            // recomputeDescendantDepths -> findDescendants for X's (here empty) descendant set in
            // the same transaction as the write (api-rest.md PATCH section: parent-change writes
            // and the descendant cascade succeed or fail together) - erroring it would make this
            // success-path scenario 500 for a reason unrelated to what it's testing. The
            // test-plan's "every cycle scenario stubs findDescendants" rule applies to the
            // rejection scenarios (S3/S4/S5/S8/probes), not to this legitimate-move success path.
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onFindAncestorChains = { Result.Error(RepositoryError.DatabaseError("must not be reached")) },
                )
            application { configureReparentTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":null}""")
                }

            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "parentId:null must move to root regardless of armed lookup stubs: ${response.bodyAsText()}"
            )
            val persisted = runBlocking { repo.workItemRepository().getById(x.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertNull(persisted.data.parentId)
            assertEquals(0, persisted.data.depth)
            assertEquals(x.id, persisted.data.rootId, "Item must become its own root")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S8 — ordering: an unknown proposed parent stays on the existing not_found path,
    // reached BEFORE any ancestor-chain walk
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S8 reparenting under an unknown parent id returns 400 not_found before any ancestor-chain walk`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val x = runBlocking { makeRoot(repo, "X S8") }
            // If the not_found check did not run first, this unconditional error would surface as
            // a 500 instead of the expected 400 not_found, proving the ordering.
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onFindAncestorChains = {
                        Result.Error(
                            RepositoryError.DatabaseError("must not be reached - not_found must short-circuit first")
                        )
                    },
                    onFindDescendants = { Result.Error(RepositoryError.DatabaseError("must not be reached")) },
                )
            application { configureReparentTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            val missingParentId = UUID.randomUUID()
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"$missingParentId"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, "An unknown parent must be 400, not 500: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("not_found", json["error"]?.jsonPrimitive?.content)
            assertEquals(0, scripted.findAncestorChainsCallCount, "The ancestor-chain walk must never run for an unknown parent")
            assertEquals(0, scripted.updateCallCount)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S9 — Idempotency-Key replay of the 500 from S5 returns the cached body, does not re-run
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S9 replaying the same Idempotency-Key on a 500 db_error returns the cached body and does not re-run the lookup`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (x, b, d) =
                runBlocking {
                    val xItem = makeRoot(repo, "X S9")
                    val a = makeChild(repo, xItem, "A S9")
                    val b = makeChild(repo, a, "B S9")
                    val d = makeChild(repo, b, "D S9")
                    Triple(xItem, b, d)
                }
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onGetById = { id -> if (id == b.id) Result.Error(RepositoryError.DatabaseError("ancestor lookup failed")) else null },
                    onFindAncestorChains = { ids ->
                        if (d.id in ids) Result.Error(RepositoryError.DatabaseError("ancestor chain lookup failed")) else null
                    },
                    onFindDescendants = { Result.Error(RepositoryError.DatabaseError("must not be reached")) },
                )
            val cache = IdempotencyCache()
            application { configureReparentTestApp(WorkItemRepoOverrideProvider(repo, scripted), idempotencyCache = cache) }

            val idempotencyKey = UUID.randomUUID().toString()
            val makeRequest: suspend () -> HttpResponse = {
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", idempotencyKey)
                    header(HttpHeaders.IfMatch, etagFor(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${d.id}"}""")
                }
            }

            val first = makeRequest()
            assertEquals(HttpStatusCode.InternalServerError, first.status)
            val firstBody = first.bodyAsText()
            val callsAfterFirst = scripted.findAncestorChainsCallCount

            val second = makeRequest()
            assertEquals(first.status, second.status, "A replay with the same Idempotency-Key must return the same cached status")
            assertEquals(firstBody, second.bodyAsText(), "A replay with the same Idempotency-Key must return the cached body verbatim")
            assertEquals(
                callsAfterFirst,
                scripted.findAncestorChainsCallCount,
                "A cached replay must NOT re-run the ancestor-chain lookup",
            )
            assertEquals(0, scripted.updateCallCount)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Adversarial probes
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `probe reparenting under the immediate direct child (1 hop) is rejected, including a mixed-case UUID string`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (x, c) =
                runBlocking {
                    val xItem = makeRoot(repo, "X Probe1Hop")
                    val c = makeChild(repo, xItem, "C Probe1Hop")
                    Pair(xItem, c)
                }
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onFindDescendants = { Result.Error(RepositoryError.DatabaseError("must not be reached")) },
                )
            application { configureReparentTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            // Mixed-case UUID string for the same id C — java.util.UUID.fromString is
            // case-insensitive, so this must be detected exactly like the lowercase form.
            val mixedCaseId = c.id.toString().uppercase()
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"$mixedCaseId"}""")
                }

            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "The shallowest possible cycle (direct child) must still be rejected: ${response.bodyAsText()}"
            )
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("validation_error", json["error"]?.jsonPrimitive?.content)
            assertEquals("Cannot re-parent an item under its own descendant", json["message"]?.jsonPrimitive?.content)
            assertEquals(0, scripted.updateCallCount)
        }

    @Test
    fun `probe depth understated by exactly one hop is the sharpest form of the stale-depth bug`(): Unit =
        testApplication {
            // This is the same construction as S3 (stored depth=1 against a true depth of 3, i.e.
            // the pre-fix hopsRemaining=depth+1=2 walk falls exactly one hop short of the 3 hops
            // (D->B->A->X) needed to see X) — recorded here as its own named probe per the
            // adversarial-probe catalog rather than duplicated as a second assertion block.
            val repo = buildH2RepositoryProvider()
            val (x, d) =
                runBlocking {
                    val xItem = makeRoot(repo, "X ProbeOffByOne")
                    val a = makeChild(repo, xItem, "A ProbeOffByOne")
                    val b = makeChild(repo, a, "B ProbeOffByOne")
                    val d = makeChild(repo, b, "D ProbeOffByOne", depthOverride = 1)
                    Pair(xItem, d)
                }
            val scripted =
                ScriptedWorkItemRepository(
                    repo.workItemRepository(),
                    onFindDescendants = { Result.Error(RepositoryError.DatabaseError("must not be reached")) },
                )
            application { configureReparentTestApp(WorkItemRepoOverrideProvider(repo, scripted)) }

            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${d.id}"}""")
                }

            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "An off-by-one-hop stale depth must not defeat the guard: ${response.bodyAsText()}"
            )
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("validation_error", json["error"]?.jsonPrimitive?.content)
            assertEquals(0, scripted.updateCallCount)
        }

    // Probe catalog, recorded per skill §6 (every probe attempted, including no-finding/N-A ones):
    // - boundary/suffix (depth off-by-one): covered above ("probe depth understated by exactly one hop") and by S3.
    // - alternate separators / encoded / UNC forms: N/A - the only path-like input here is a UUID, not a
    //   filesystem or URI path; there is no separator-bearing surface to probe.
    // - mixed case: covered above ("probe reparenting under the immediate direct child...").
    // - empty vs absent vs null: covered by S6 (absent parentId key) and S7 (explicit parentId:null).
    // - duplicates / ordering: N/A - parentId is a single scalar field, not a collection; there is no
    //   duplicate-entry or processing-order surface to probe.
    // - replay / idempotency: covered by S9 (Idempotency-Key replay of the 500 db_error path).
    // - getById/lookup error on the PARENT itself (existing not_found path, no write): covered by S8.
    // - getById/lookup error on the ITEM itself (the patched item's own id not found): NOT covered here.
    //   The expected status for a nonexistent PATCH target id is not specified by this item's
    //   test-plan or diagnosis note (both scope the fix to the NEW-parent ancestor lookup, not the
    //   item-lookup path, which this item does not touch) and is not derivable from O1-O4 without
    //   guessing. Per skill §8 this is left as an open arbitration item rather than asserted from
    //   assumption - see test-manifest.
}
