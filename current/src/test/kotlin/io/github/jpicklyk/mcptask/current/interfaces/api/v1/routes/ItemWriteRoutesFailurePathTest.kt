package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.port.ChildPlacement
import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.TransitionStore
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLeaseInterval
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.findByFilters
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.test.CountingUnitOfWork
import io.github.jpicklyk.mcptask.current.test.TestConfigResolver
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent test authorship for item `af37c467` (needs-test-author, stream IW): REST
 * `POST /items/{id}/advance` / `PATCH /items/{id}` failure-path coverage — S1, S4, S5, S6, S7, S8,
 * S10, S12 of the frozen `test-plan` note (queue phase, read before this file existed), plus
 * adversarial probes.
 *
 * This wave is CHARACTERIZATION-FIRST (dispatch contract, `plans/decompose-complexity-hotspots.md`):
 * these tests drive the CURRENT, pre-refactor `ItemWriteRoutes` handlers and must be green on base
 * `dd26e9e2`. They are the safety net for an upcoming behavior-preserving decomposition of the
 * advance-route handler and `respondAdvanceFailure`.
 *
 * Oracles: `current/docs/api-rest.md` §10 (advance error-code table, gate/contention response
 * shapes, PATCH descendant-cascade `500 db_error` mapping), `current/docs/api-reference.md`
 * (`missingNotes` guidance/skill "omitted when unset"), and `current/docs/workflow-guide.md`
 * (resource-lease same-call release on apply failure, start-cascade QUEUE->WORK).
 *
 * SEAMS (own file, SQLite via `SqliteTestDatabase.repositoryProvider()`):
 * - [FailingRoleTransitionRepository] wraps the real `roleTransitionRepository()`, failing
 *   `create()` only for a chosen item id — this is how the apply step (`workItemRepository.update`
 *   then `roleTransitionRepository.create` inside one transaction) is forced to fail for S1/S4 and
 *   their replay probe, per the dispatch declarations' "Behavioral seams" note.
 * - [SimpleLeaseFakeRepository] is an in-memory [LeaseStore] fake (mirrors the
 *   established pattern in `AdvanceRouteResourceLeaseTest.LeaseGateFakeRepository`, reimplemented
 *   here per the test-author "own file" rule), used for S1/S4 (uncontended acquire + release-on-
 *   apply-failure) and S8 (forced contention).
 * - [MutateOnFirstTransactionRepository] (same technique as
 *   `ItemWriteRoutesParentPlacementInTxnTest`) deletes a parent as the first thing inside the real
 *   write transaction, for S10 and its mixed-case-UUID probe.
 * - [UpdateFailsForIdRepository] fails `update()` for one target id, forcing the PATCH
 *   descendant-depth-cascade to roll back for S12 (declarations: "failing `update()` for D's id
 *   triggers S11/S12").
 * - [GateSchemaService] supplies two required queue-phase notes (one with guidance+skill, one with
 *   neither) for S7. [TraitSchemaService] maps `needs-staging-db` to one exclusive resource for
 *   S1/S4/S8 and their probes.
 *
 * BLINDNESS: authored from `task-scope`/`test-plan` (queue-phase, frozen, `keys`-filtered
 * `query_notes`), the verbatim declarations block supplied in the dispatch prompt, and existing
 * conventions in this package (`ItemWriteRoutesParentPlacementInTxnTest.kt`,
 * `AdvanceRouteResourceLeaseTest.kt`, `ApiTestHelper.kt`). No `src/main` file, diff, or commit was
 * read.
 */
class ItemWriteRoutesFailurePathTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    // ─────────────────────────────────────────────────────────────────────────
    // Test-only seams
    // ─────────────────────────────────────────────────────────────────────────

    /** Wraps a real [TransitionStore]; fails `create()` only for [failFor]'s transitions. */
    private class FailingRoleTransitionRepository(
        private val delegate: TransitionStore,
        private val failFor: UUID
    ) : TransitionStore by delegate {
        override suspend fun create(transition: RoleTransition): RoleTransition =
            if (transition.itemId == failFor) {
                throw IllegalStateException("simulated apply failure for $failFor")
            } else {
                delegate.create(transition)
            }
    }

    private class RoleTransitionOverrideProvider(
        private val delegate: RepositoryProvider,
        private val roleTransitionRepo: TransitionStore
    ) : RepositoryProvider by delegate {
        override fun roleTransitionRepository(): TransitionStore = roleTransitionRepo
    }

    /** Wraps a real [WorkItemRepository]; fails `update()` only for [failFor]'s writes. */
    private class UpdateFailsForIdRepository(
        private val delegate: WorkItemRepository,
        private val failFor: UUID
    ) : WorkItemRepository by delegate {
        override suspend fun update(item: WorkItem): WorkItem? =
            if (item.id == failFor) {
                throw IllegalStateException("simulated descendant cascade failure for $failFor")
            } else {
                delegate.update(item)
            }

        /** Since P15 the descendant cascade is one restamp statement: it fails when it would reach [failFor]. */
        override suspend fun restampSubtree(
            itemId: UUID,
            depthDelta: Int,
            newRootId: UUID
        ): Int =
            if (failFor in delegate.descendantIds(itemId)) {
                throw IllegalStateException("simulated descendant cascade failure for $failFor")
            } else {
                delegate.restampSubtree(itemId, depthDelta, newRootId)
            }
    }

    /** See `ItemWriteRoutesParentPlacementInTxnTest.MutateOnFirstTransactionRepository` KDoc. */
    private class MutateOnFirstTransactionRepository(
        private val delegate: WorkItemRepository,
        private val mutate: suspend (WorkItemRepository) -> Unit
    ) : WorkItemRepository by delegate {
        private var hasFired = false

        /** Fires [mutate] once, from the [CountingUnitOfWork] hook at the first top-level unit's open. */
        suspend fun fireOnce() {
            if (!hasFired) {
                hasFired = true
                mutate(delegate)
            }
        }

        override suspend fun resolveChildPlacement(parentId: UUID): ChildPlacement? =
            getById(parentId)?.let { parent ->
                ChildPlacement(
                    parentId = parent.id,
                    depth = parent.depth + 1,
                    rootId = parent.rootId ?: parent.id
                )
            }
    }

    private class WorkItemRepoOverrideProvider(
        private val delegate: RepositoryProvider,
        private val workItemRepo: WorkItemRepository
    ) : RepositoryProvider by delegate {
        override fun workItemRepository(): WorkItemRepository = workItemRepo
    }

    /** In-memory [LeaseStore] fake; own-file reimplementation per test-author rule 8. */
    private class SimpleLeaseFakeRepository : LeaseStore {
        val leases = mutableListOf<ResourceLease>()

        /** When non-null, every acquire is forced to report these keys contended. */
        var forceContended: List<String>? = null
        var forceRetryAfterMs: Long = 30_000

        override suspend fun acquireAll(
            holderItemId: UUID,
            actorId: String?,
            requirements: List<Pair<String, Int>>
        ): LeaseAcquireResult {
            forceContended?.let { return LeaseAcquireResult.Contended(it, retryAfterMs = forceRetryAfterMs) }
            val now = Instant.now()
            val acquired =
                requirements.map { (key, ttl) ->
                    ResourceLease(
                        resourceKey = key,
                        holderItemId = holderItemId,
                        acquiredByActorId = actorId,
                        acquiredAt = now,
                        expiresAt = now.plusSeconds(ttl.toLong()),
                        originalAcquiredAt = now,
                        version = 0
                    )
                }
            leases += acquired
            return LeaseAcquireResult.Success(acquired)
        }

        override suspend fun releaseAllForItem(holderItemId: UUID): LeaseReleaseResult {
            val before = leases.size
            leases.removeAll { it.holderItemId == holderItemId }
            return LeaseReleaseResult.Success(before - leases.size)
        }

        override suspend fun forceReleaseByKey(
            resourceKey: String,
            actorId: String?
        ): LeaseReleaseResult {
            val before = leases.size
            leases.removeAll { it.resourceKey == resourceKey }
            return LeaseReleaseResult.Success(before - leases.size)
        }

        override suspend fun findLapsed(
            keys: List<String>?,
            holderItemIds: Set<UUID>?
        ): List<ResourceLease> = emptyList()

        override suspend fun deleteLapsed(leaseIds: Set<UUID>): Int = 0

        override suspend fun findActiveByKeys(keys: List<String>): List<ResourceLease> = leases.filter { it.resourceKey in keys }

        override suspend fun findActiveForItem(holderItemId: UUID): List<ResourceLease> = leases.filter { it.holderItemId == holderItemId }

        override suspend fun findAllActive(): List<ResourceLease> = leases.toList()

        override suspend fun findHoldersAt(
            resourceKey: String?,
            at: Instant,
            limit: Int
        ): List<ResourceLeaseInterval> = emptyList()

        override suspend fun findRecentIntervals(
            resourceKey: String?,
            limit: Int
        ): List<ResourceLeaseInterval> = emptyList()
    }

    private class LeaseOverrideProvider(
        private val delegate: RepositoryProvider,
        private val leaseRepo: LeaseStore
    ) : RepositoryProvider by delegate {
        override fun resourceLeaseRepository(): LeaseStore = leaseRepo
    }

    /** Maps `needs-staging-db` onto one exclusive resource with a 600s TTL. */
    private fun traitSchemaService(): ConfigDocument =
        ConfigDocument(
            workItemSchemas = emptyMap(),
            traits = emptyMap(),
            traitResources = mapOf("needs-staging-db" to listOf(ResourceRequirement("staging-db-credential", ResourceMode.EXCLUSIVE, 600))),
        )

    /** Supplies two required queue-phase notes for type `s7-typed`: one with guidance+skill, one with neither. */
    private fun gateSchemaService(): ConfigDocument =
        TestConfigResolver.document(
            mapOf(
                "s7-typed" to
                    listOf(
                        NoteSchemaEntry(key = "n1", role = Role.QUEUE, required = true, description = "N1", guidance = "g1", skill = "sk1"),
                        NoteSchemaEntry(key = "n2", role = Role.QUEUE, required = true, description = "N2")
                    )
            )
        )

    private fun Application.configureFailurePathTestApp(
        repositoryProvider: RepositoryProvider,
        schemaService: ConfigDocument = ConfigDocument.EMPTY,
        authConfig: ApiAuthConfig.Bearer = makeWriteAuthConfig()
    ) {
        // Fires a MutateOnFirstTransactionRepository's write at the first top-level unit's open.
        val uow =
            CountingUnitOfWork(
                db.unitOfWork()
            ) { (repositoryProvider.workItemRepository() as? MutateOnFirstTransactionRepository)?.fireOnce() }
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
                    IdempotencyService(uow),
                    PerRootConfigService(repositoryProvider.configStore()).let { perRoot ->
                        ToolExecutionContext(
                            repositoryProvider,
                            statusLabelService = NoOpStatusLabelService,
                            perRootConfigService = perRoot,
                            configResolver = TestConfigResolver.resolver(schemaService, perRoot),
                            unitOfWork = uow
                        ).advanceServiceFactory()
                    },
                    uow,
                )
            }
        }
    }

    private fun etagFor(item: WorkItem): String = "\"v1-${item.modifiedAt.toEpochMilli()}\""

    private suspend fun stampSelfRoot(
        repo: DefaultRepositoryProvider,
        item: WorkItem
    ): WorkItem = (repo.workItemRepository().update(item.copy(rootId = item.id))!!)

    // ─────────────────────────────────────────────────────────────────────────
    // S1 — start apply failure: 422 transition_failed, role unchanged, lease released
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // S4 — cascade apply failure: 200, cascadeEvents[0] applied=false with error, parent lease released
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // S5 — dependency blocker: 422 transition_blocked with blockers
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // S6 — terminal item: 422 transition_failed
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // S7 — required-note gate: 422 gate_blocked with missingNotes (guidance/skill omitted when unset)
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // S8 — resource contention: 409, Retry-After rounded up, contendedResources/retryAfterMs
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // S10 — PATCH reparent whose target parent is deleted inside the write transaction: 400 not_found
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S10 REST PATCH reparenting X under P whose parent is deleted inside the write transaction returns 400 not_found`(): Unit =
        testApplication {
            val sqlite = db.repositoryProvider()
            val root =
                runBlocking {
                    stampSelfRoot(sqlite, (sqlite.workItemRepository().create(WorkItem(title = "R S10", depth = 0))!!))
                }
            val p =
                runBlocking {
                    (
                        sqlite.workItemRepository().create(
                            WorkItem(title = "P S10 (leaf, will be deleted)", parentId = root.id, depth = 1, rootId = root.id)
                        )!!
                    )
                }
            val x =
                runBlocking {
                    stampSelfRoot(sqlite, (sqlite.workItemRepository().create(WorkItem(title = "X S10", depth = 0))!!))
                }
            val wrapped = MutateOnFirstTransactionRepository(sqlite.workItemRepository()) { d -> d.delete(p.id) }
            application { configureFailurePathTestApp(WorkItemRepoOverrideProvider(sqlite, wrapped)) }

            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${p.id}"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("not_found", json["error"]?.jsonPrimitive?.content, "actual: $json")

            val persisted = runBlocking { sqlite.workItemRepository().getById(x.id) }
            assertNotNull(persisted)
            assertEquals(null, persisted.parentId, "X must be left unchanged")
            assertEquals(0, persisted.depth, "X must be left unchanged")
        }

    @Test
    fun `probe S10 a mixed-case UUID for the deleted parent still resolves and returns 400 not_found`(): Unit =
        testApplication {
            val sqlite = db.repositoryProvider()
            val root =
                runBlocking {
                    stampSelfRoot(
                        sqlite,
                        (sqlite.workItemRepository().create(WorkItem(title = "R S10 probe", depth = 0))!!)
                    )
                }
            val p =
                runBlocking {
                    (
                        sqlite.workItemRepository().create(
                            WorkItem(title = "P S10 probe (leaf, will be deleted)", parentId = root.id, depth = 1, rootId = root.id)
                        )!!
                    )
                }
            val x =
                runBlocking {
                    stampSelfRoot(
                        sqlite,
                        (sqlite.workItemRepository().create(WorkItem(title = "X S10 probe", depth = 0))!!)
                    )
                }
            val wrapped = MutateOnFirstTransactionRepository(sqlite.workItemRepository()) { d -> d.delete(p.id) }
            application { configureFailurePathTestApp(WorkItemRepoOverrideProvider(sqlite, wrapped)) }

            val mixedCaseId = p.id.toString().uppercase()
            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"$mixedCaseId"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("not_found", json["error"]?.jsonPrimitive?.content, "actual: $json")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S12 — PATCH descendant-cascade update failure: 500 db_error, nothing mutated
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S12 REST PATCH moving X to a different root fails closed with 500 db_error when the descendant cascade update fails`(): Unit =
        testApplication {
            val sqlite = db.repositoryProvider()
            val r =
                runBlocking {
                    stampSelfRoot(sqlite, (sqlite.workItemRepository().create(WorkItem(title = "R S12", depth = 0))!!))
                }
            val x =
                runBlocking {
                    (
                        sqlite.workItemRepository().create(
                            WorkItem(title = "X S12", parentId = r.id, depth = 1, rootId = r.id)
                        )!!
                    )
                }
            val d =
                runBlocking {
                    (
                        sqlite.workItemRepository().create(
                            WorkItem(title = "D S12 (child of X)", parentId = x.id, depth = 2, rootId = r.id)
                        )!!
                    )
                }
            val q =
                runBlocking {
                    stampSelfRoot(sqlite, (sqlite.workItemRepository().create(WorkItem(title = "Q S12", depth = 0))!!))
                }
            val wrapped = UpdateFailsForIdRepository(sqlite.workItemRepository(), failFor = d.id)
            application { configureFailurePathTestApp(WorkItemRepoOverrideProvider(sqlite, wrapped)) }

            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etagFor(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${q.id}"}""")
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("db_error", json["error"]?.jsonPrimitive?.content, "actual: $json")
            assertEquals("Failed to update item", json["message"]?.jsonPrimitive?.content, "actual: $json")

            val persistedX = runBlocking { sqlite.workItemRepository().getById(x.id) }
            assertNotNull(persistedX)
            assertEquals(r.id, persistedX.parentId, "X must be left unchanged")
            assertEquals(r.id, persistedX.rootId, "X must be left unchanged")
            val persistedD = runBlocking { sqlite.workItemRepository().getById(d.id) }
            assertNotNull(persistedD)
            assertEquals(2, persistedD.depth, "D must be left unchanged")
            assertEquals(r.id, persistedD.rootId, "D must be left unchanged")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // F7/F8/F9/F10/F11/F12/F14 — review follow-up: advance-route request-parse failure paths
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun createQueueItem(
        sqlite: DefaultRepositoryProvider,
        title: String
    ): WorkItem = (sqlite.workItemRepository().create(WorkItem(title = title, role = Role.QUEUE, depth = 0))!!)

    // ─────────────────────────────────────────────────────────────────────────
    // F13 — review follow-up: create-route Content-Type guard
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `F13 create with a non-JSON Content-Type returns 415 unsupported_media_type and nothing persists`(): Unit =
        testApplication {
            val sqlite = db.repositoryProvider()
            application { configureFailurePathTestApp(sqlite) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("""{"type":"test-item","title":"F13 Should Not Persist"}""")
                }

            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("unsupported_media_type", json["error"]?.jsonPrimitive?.content, "actual: $json")
            assertEquals("Use Content-Type: application/json", json["message"]?.jsonPrimitive?.content, "actual: $json")

            val persisted = runBlocking { sqlite.workItemRepository().findByFilters(limit = 500) }
            val titles = persisted.items.map { it.title }
            assertTrue("F13 Should Not Persist" !in titles, "nothing must persist: $titles")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // F14 — review follow-up: guard precedence on advance (Content-Type / trigger parse both run
    // BEFORE the overrideResourceLeases capability check, even though the caller sets the flag)
    // ─────────────────────────────────────────────────────────────────────────

    // Probe catalog, recorded per skill §6 (every probe attempted, including N/A ones):
    // - boundary/suffix: N/A - the surfaces here are error-code/JSON-shape mappings, not a
    //   size-bounded value.
    // - alternate separators / encoded / UNC forms: N/A - parentId is a UUID identifying an
    //   existing row, not a path/string surface.
    // - mixed case: covered above ("probe S10 a mixed-case UUID...").
    // - empty vs absent vs null: N/A for this file's scenarios (all target an existing item id).
    // - duplicates/ordering: N/A - single-item REST calls, not a batch surface (covered on the
    //   MCP surface by AdvanceItemToolApplyFailureLeaseTest's batch probe).
    // - replay/idempotency: covered above ("probe replaying the S1 apply failure twice...").
    // - S8 retryAfterMs rounding boundaries (1ms, 0ms): covered above.
    // - S7 absent vs present guidance/skill: covered within the S7 test itself (n1 carries both,
    //   n2 carries neither) — not re-authored as a separate probe.
}
