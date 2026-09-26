package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLeaseInterval
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ChildPlacement
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
 * SEAMS (own file, H2 via [ApiTestHelper.buildH2RepositoryProvider]):
 * - [FailingRoleTransitionRepository] wraps the real `roleTransitionRepository()`, failing
 *   `create()` only for a chosen item id — this is how the apply step (`workItemRepository.update`
 *   then `roleTransitionRepository.create` inside one transaction) is forced to fail for S1/S4 and
 *   their replay probe, per the dispatch declarations' "Behavioral seams" note.
 * - [SimpleLeaseFakeRepository] is an in-memory [ResourceLeaseRepository] fake (mirrors the
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
    // ─────────────────────────────────────────────────────────────────────────
    // Test-only seams
    // ─────────────────────────────────────────────────────────────────────────

    /** Wraps a real [RoleTransitionRepository]; fails `create()` only for [failFor]'s transitions. */
    private class FailingRoleTransitionRepository(
        private val delegate: RoleTransitionRepository,
        private val failFor: UUID
    ) : RoleTransitionRepository by delegate {
        override suspend fun create(transition: RoleTransition): Result<RoleTransition> =
            if (transition.itemId == failFor) {
                Result.Error(RepositoryError.DatabaseError("simulated apply failure for $failFor"))
            } else {
                delegate.create(transition)
            }
    }

    private class RoleTransitionOverrideProvider(
        private val delegate: RepositoryProvider,
        private val roleTransitionRepo: RoleTransitionRepository
    ) : RepositoryProvider by delegate {
        override fun roleTransitionRepository(): RoleTransitionRepository = roleTransitionRepo
    }

    /** Wraps a real [WorkItemRepository]; fails `update()` only for [failFor]'s writes. */
    private class UpdateFailsForIdRepository(
        private val delegate: WorkItemRepository,
        private val failFor: UUID
    ) : WorkItemRepository by delegate {
        override suspend fun update(item: WorkItem): Result<WorkItem> =
            if (item.id == failFor) {
                Result.Error(RepositoryError.ConflictError("simulated descendant cascade failure for $failFor"))
            } else {
                delegate.update(item)
            }
    }

    /** See `ItemWriteRoutesParentPlacementInTxnTest.MutateOnFirstTransactionRepository` KDoc. */
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
                            rootId = parent.data.rootId ?: parent.data.id
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

    /** In-memory [ResourceLeaseRepository] fake; own-file reimplementation per test-author rule 8. */
    private class SimpleLeaseFakeRepository : ResourceLeaseRepository {
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

        override suspend fun findActiveByKeys(keys: List<String>): List<ResourceLease> = leases.filter { it.resourceKey in keys }

        override suspend fun findActiveForItem(holderItemId: UUID): List<ResourceLease> = leases.filter { it.holderItemId == holderItemId }

        override suspend fun findAllActive(): List<ResourceLease> = leases.toList()

        override suspend fun findHoldersAt(
            resourceKey: String?,
            at: Instant
        ): List<ResourceLeaseInterval> = emptyList()

        override suspend fun findRecentIntervals(
            resourceKey: String?,
            limit: Int
        ): List<ResourceLeaseInterval> = emptyList()
    }

    private class LeaseOverrideProvider(
        private val delegate: RepositoryProvider,
        private val leaseRepo: ResourceLeaseRepository
    ) : RepositoryProvider by delegate {
        override fun resourceLeaseRepository(): ResourceLeaseRepository = leaseRepo
    }

    /** Maps `needs-staging-db` onto one exclusive resource with a 600s TTL. */
    private class TraitSchemaService : WorkItemSchemaService {
        override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null

        override fun getTraitResources(traitName: String): List<ResourceRequirement> =
            if (traitName == "needs-staging-db") {
                listOf(ResourceRequirement("staging-db-credential", ResourceMode.EXCLUSIVE, 600))
            } else {
                emptyList()
            }
    }

    /** Supplies two required queue-phase notes for tag `s7-typed`: one with guidance+skill, one with neither. */
    private class GateSchemaService : WorkItemSchemaService {
        override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
            if (tags.contains("s7-typed")) {
                listOf(
                    NoteSchemaEntry(key = "n1", role = Role.QUEUE, required = true, description = "N1", guidance = "g1", skill = "sk1"),
                    NoteSchemaEntry(key = "n2", role = Role.QUEUE, required = true, description = "N2")
                )
            } else {
                null
            }
    }

    private fun Application.configureFailurePathTestApp(
        repositoryProvider: RepositoryProvider,
        schemaService: WorkItemSchemaService = NoOpNoteSchemaService,
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
                    ToolExecutionContext(
                        repositoryProvider,
                        schemaService,
                        statusLabelService = NoOpStatusLabelService,
                        perRootConfigService = PerRootConfigService(repositoryProvider.projectConfigRepository()),
                    ).advanceServiceFactory(),
                )
            }
        }
    }

    private fun etagFor(item: WorkItem): String = "\"v1-${item.modifiedAt.toEpochMilli()}\""

    private suspend fun stampSelfRoot(
        repo: DefaultRepositoryProvider,
        item: WorkItem
    ): WorkItem = (repo.workItemRepository().update(item.copy(rootId = item.id)) as Result.Success).data

    // ─────────────────────────────────────────────────────────────────────────
    // S1 — start apply failure: 422 transition_failed, role unchanged, lease released
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S1 REST start apply failure returns 422 transition_failed with same-call lease release`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item =
                (
                    h2.workItemRepository().create(
                        WorkItem(title = "Traited S1", role = Role.QUEUE, depth = 0, properties = """{"traits":["needs-staging-db"]}""")
                    ) as Result.Success
                ).data
            val failingRoleTx = FailingRoleTransitionRepository(h2.roleTransitionRepository(), failFor = item.id)
            val leaseFake = SimpleLeaseFakeRepository()
            val provider = LeaseOverrideProvider(RoleTransitionOverrideProvider(h2, failingRoleTx), leaseFake)
            application { configureFailurePathTestApp(provider, schemaService = TraitSchemaService()) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("transition_failed", json["error"]?.jsonPrimitive?.content, "actual: $json")

            val persisted = runBlocking { h2.workItemRepository().getById(item.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(Role.QUEUE, persisted.data.role)
            assertTrue(leaseFake.findActiveForItem(item.id).isEmpty(), "the same-call fresh lease must be released on apply failure")
        }

    @Test
    fun `probe replaying the S1 apply failure twice both return 422 with zero leases each time`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item =
                (
                    h2.workItemRepository().create(
                        WorkItem(
                            title = "Traited S1 replay",
                            role = Role.QUEUE,
                            depth = 0,
                            properties = """{"traits":["needs-staging-db"]}"""
                        )
                    ) as Result.Success
                ).data
            val failingRoleTx = FailingRoleTransitionRepository(h2.roleTransitionRepository(), failFor = item.id)
            val leaseFake = SimpleLeaseFakeRepository()
            val provider = LeaseOverrideProvider(RoleTransitionOverrideProvider(h2, failingRoleTx), leaseFake)
            application { configureFailurePathTestApp(provider, schemaService = TraitSchemaService()) }

            repeat(2) {
                val response =
                    client.post("/api/v1/items/${item.id}/advance") {
                        header("Authorization", "Bearer $WRITE_TOKEN")
                        contentType(ContentType.Application.Json)
                        setBody("""{"trigger":"start"}""")
                    }
                assertEquals(HttpStatusCode.UnprocessableEntity, response.status, "actual: ${response.bodyAsText()}")
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("transition_failed", json["error"]?.jsonPrimitive?.content, "actual: $json")
                assertTrue(leaseFake.findActiveForItem(item.id).isEmpty(), "replay ${it + 1}: leases must be empty")
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S4 — cascade apply failure: 200, cascadeEvents[0] applied=false with error, parent lease released
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S4 REST start with cascade apply failure reports cascadeEvents applied false with error`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val p =
                (
                    h2.workItemRepository().create(
                        WorkItem(title = "P S4", role = Role.QUEUE, depth = 0, properties = """{"traits":["needs-staging-db"]}""")
                    ) as Result.Success
                ).data
            val pStamped = (h2.workItemRepository().update(p.copy(rootId = p.id)) as Result.Success).data
            val c =
                (
                    h2.workItemRepository().create(
                        WorkItem(title = "C S4", role = Role.QUEUE, parentId = pStamped.id, depth = 1, rootId = pStamped.id)
                    ) as Result.Success
                ).data
            val failingRoleTx = FailingRoleTransitionRepository(h2.roleTransitionRepository(), failFor = pStamped.id)
            val leaseFake = SimpleLeaseFakeRepository()
            val provider = LeaseOverrideProvider(RoleTransitionOverrideProvider(h2, failingRoleTx), leaseFake)
            application { configureFailurePathTestApp(provider, schemaService = TraitSchemaService()) }

            val response =
                client.post("/api/v1/items/${c.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val cascadeEvents = json["cascadeEvents"]?.jsonArray
            assertTrue(cascadeEvents != null && cascadeEvents.isNotEmpty(), "actual: $json")
            val cascade = cascadeEvents!![0].jsonObject
            assertEquals(pStamped.id.toString(), cascade["itemId"]?.jsonPrimitive?.content, "actual: $cascade")
            assertEquals(false, cascade["applied"]?.jsonPrimitive?.boolean, "actual: $cascade")
            assertTrue(cascade["error"]?.jsonPrimitive?.content?.isNotBlank() == true, "actual: $cascade")
            assertEquals(
                false,
                cascade["gateBlocked"]?.jsonPrimitive?.boolean,
                "REST cascadeEvents must carry gateBlocked=false explicitly (not merely absent): $cascade"
            )

            val persistedP = runBlocking { h2.workItemRepository().getById(pStamped.id) }
            assertIs<Result.Success<WorkItem>>(persistedP)
            assertEquals(Role.QUEUE, persistedP.data.role)
            assertTrue(
                leaseFake.findActiveForItem(pStamped.id).isEmpty(),
                "the cascade's own fresh lease must be released on apply failure"
            )
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S5 — dependency blocker: 422 transition_blocked with blockers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S5 REST start blocked by an unsatisfied dependency returns 422 transition_blocked with blockers`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val b = (h2.workItemRepository().create(WorkItem(title = "B S5", role = Role.QUEUE, depth = 0)) as Result.Success).data
            val t = (h2.workItemRepository().create(WorkItem(title = "T S5", role = Role.QUEUE, depth = 0)) as Result.Success).data
            runBlocking { h2.dependencyRepository().create(Dependency(fromItemId = b.id, toItemId = t.id, type = DependencyType.BLOCKS)) }
            application { configureFailurePathTestApp(h2) }

            val response =
                client.post("/api/v1/items/${t.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("transition_blocked", json["error"]?.jsonPrimitive?.content, "actual: $json")
            val blockers = json["details"]!!.jsonObject["blockers"]!!.jsonArray
            assertEquals(1, blockers.size, "actual: $blockers")
            val blocker = blockers[0].jsonObject
            assertEquals(b.id.toString(), blocker["fromItemId"]?.jsonPrimitive?.content, "actual: $blocker")
            assertEquals("queue", blocker["currentRole"]?.jsonPrimitive?.content, "actual: $blocker")
            assertEquals("terminal", blocker["requiredRole"]?.jsonPrimitive?.content, "actual: $blocker")

            val persisted = runBlocking { h2.workItemRepository().getById(t.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(Role.QUEUE, persisted.data.role)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S6 — terminal item: 422 transition_failed
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S6 REST start on a terminal item returns 422 transition_failed`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item =
                (
                    h2.workItemRepository().create(
                        WorkItem(title = "Terminal S6", role = Role.TERMINAL, depth = 0)
                    ) as Result.Success
                ).data
            application { configureFailurePathTestApp(h2) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("transition_failed", json["error"]?.jsonPrimitive?.content, "actual: $json")
            assertTrue(json["message"]?.jsonPrimitive?.content?.isNotBlank() == true, "actual: $json")

            val persisted = runBlocking { h2.workItemRepository().getById(item.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(Role.TERMINAL, persisted.data.role)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S7 — required-note gate: 422 gate_blocked with missingNotes (guidance/skill omitted when unset)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S7 REST start blocked by an unfilled required-note gate returns 422 gate_blocked with missingNotes`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item =
                (
                    h2.workItemRepository().create(
                        WorkItem(title = "Typed S7", role = Role.QUEUE, depth = 0, tags = "s7-typed")
                    ) as Result.Success
                ).data
            application { configureFailurePathTestApp(h2, schemaService = GateSchemaService()) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("gate_blocked", json["error"]?.jsonPrimitive?.content, "actual: $json")
            val details = json["details"]!!.jsonObject
            assertEquals("work", details["targetRole"]?.jsonPrimitive?.content, "actual: $details")
            val missingNotes = details["missingNotes"]!!.jsonArray.map { it.jsonObject }
            assertEquals(
                setOf("n1", "n2"),
                missingNotes.map { it["key"]!!.jsonPrimitive.content }.toSet(),
                "actual: $missingNotes"
            )
            val n1 = missingNotes.first { it["key"]!!.jsonPrimitive.content == "n1" }
            assertEquals("g1", n1["guidance"]?.jsonPrimitive?.content, "actual: $n1")
            assertEquals("sk1", n1["skill"]?.jsonPrimitive?.content, "actual: $n1")
            val n2 = missingNotes.first { it["key"]!!.jsonPrimitive.content == "n2" }
            assertEquals(null, n2["guidance"], "n2 must omit guidance entirely when unset: $n2")
            assertEquals(null, n2["skill"], "n2 must omit skill entirely when unset: $n2")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S8 — resource contention: 409, Retry-After rounded up, contendedResources/retryAfterMs
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S8 REST start blocked by resource contention returns 409 with Retry-After rounded up`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item =
                (
                    h2.workItemRepository().create(
                        WorkItem(title = "Traited S8", role = Role.QUEUE, depth = 0, properties = """{"traits":["needs-staging-db"]}""")
                    ) as Result.Success
                ).data
            val leaseFake = SimpleLeaseFakeRepository()
            leaseFake.forceContended = listOf("staging-db-credential")
            leaseFake.forceRetryAfterMs = 1500
            val provider = LeaseOverrideProvider(h2, leaseFake)
            application { configureFailurePathTestApp(provider, schemaService = TraitSchemaService()) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status, "actual: ${response.bodyAsText()}")
            assertEquals("2", response.headers[HttpHeaders.RetryAfter], "1500ms must round UP to 2s")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("resource_unavailable", json["error"]?.jsonPrimitive?.content, "actual: $json")
            val details = json["details"]!!.jsonObject
            assertEquals("work", details["targetRole"]?.jsonPrimitive?.content, "actual: $details")
            assertEquals(
                listOf("staging-db-credential"),
                details["contendedResources"]!!.jsonArray.map { it.jsonPrimitive.content },
                "actual: $details"
            )
            assertEquals(1500L, details["retryAfterMs"]!!.jsonPrimitive.content.toLong(), "actual: $details")
        }

    @Test
    fun `probe S8 retryAfterMs of 1ms rounds up to Retry-After header of 1`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item =
                (
                    h2.workItemRepository().create(
                        WorkItem(
                            title = "Traited S8 probe 1ms",
                            role = Role.QUEUE,
                            depth = 0,
                            properties = """{"traits":["needs-staging-db"]}"""
                        )
                    ) as Result.Success
                ).data
            val leaseFake = SimpleLeaseFakeRepository()
            leaseFake.forceContended = listOf("staging-db-credential")
            leaseFake.forceRetryAfterMs = 1
            val provider = LeaseOverrideProvider(h2, leaseFake)
            application { configureFailurePathTestApp(provider, schemaService = TraitSchemaService()) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status, "actual: ${response.bodyAsText()}")
            assertEquals("1", response.headers[HttpHeaders.RetryAfter], "1ms must round UP to a floor of 1s")
        }

    @Test
    fun `probe S8 retryAfterMs of 0ms floors to Retry-After header of 1`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item =
                (
                    h2.workItemRepository().create(
                        WorkItem(
                            title = "Traited S8 probe 0ms",
                            role = Role.QUEUE,
                            depth = 0,
                            properties = """{"traits":["needs-staging-db"]}"""
                        )
                    ) as Result.Success
                ).data
            val leaseFake = SimpleLeaseFakeRepository()
            leaseFake.forceContended = listOf("staging-db-credential")
            leaseFake.forceRetryAfterMs = 0
            val provider = LeaseOverrideProvider(h2, leaseFake)
            application { configureFailurePathTestApp(provider, schemaService = TraitSchemaService()) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status, "actual: ${response.bodyAsText()}")
            assertEquals("1", response.headers[HttpHeaders.RetryAfter], "0ms must floor to 1s, never 0")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // S10 — PATCH reparent whose target parent is deleted inside the write transaction: 400 not_found
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `S10 REST PATCH reparenting X under P whose parent is deleted inside the write transaction returns 400 not_found`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val root =
                runBlocking {
                    stampSelfRoot(h2, (h2.workItemRepository().create(WorkItem(title = "R S10", depth = 0)) as Result.Success).data)
                }
            val p =
                runBlocking {
                    (
                        h2.workItemRepository().create(
                            WorkItem(title = "P S10 (leaf, will be deleted)", parentId = root.id, depth = 1, rootId = root.id)
                        ) as Result.Success
                    ).data
                }
            val x =
                runBlocking {
                    stampSelfRoot(h2, (h2.workItemRepository().create(WorkItem(title = "X S10", depth = 0)) as Result.Success).data)
                }
            val wrapped = MutateOnFirstTransactionRepository(h2.workItemRepository()) { d -> d.delete(p.id) }
            application { configureFailurePathTestApp(WorkItemRepoOverrideProvider(h2, wrapped)) }

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

            val persisted = runBlocking { h2.workItemRepository().getById(x.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(null, persisted.data.parentId, "X must be left unchanged")
            assertEquals(0, persisted.data.depth, "X must be left unchanged")
        }

    @Test
    fun `probe S10 a mixed-case UUID for the deleted parent still resolves and returns 400 not_found`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val root =
                runBlocking {
                    stampSelfRoot(h2, (h2.workItemRepository().create(WorkItem(title = "R S10 probe", depth = 0)) as Result.Success).data)
                }
            val p =
                runBlocking {
                    (
                        h2.workItemRepository().create(
                            WorkItem(title = "P S10 probe (leaf, will be deleted)", parentId = root.id, depth = 1, rootId = root.id)
                        ) as Result.Success
                    ).data
                }
            val x =
                runBlocking {
                    stampSelfRoot(h2, (h2.workItemRepository().create(WorkItem(title = "X S10 probe", depth = 0)) as Result.Success).data)
                }
            val wrapped = MutateOnFirstTransactionRepository(h2.workItemRepository()) { d -> d.delete(p.id) }
            application { configureFailurePathTestApp(WorkItemRepoOverrideProvider(h2, wrapped)) }

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
            val h2 = buildH2RepositoryProvider()
            val r =
                runBlocking {
                    stampSelfRoot(h2, (h2.workItemRepository().create(WorkItem(title = "R S12", depth = 0)) as Result.Success).data)
                }
            val x =
                runBlocking {
                    (
                        h2.workItemRepository().create(
                            WorkItem(title = "X S12", parentId = r.id, depth = 1, rootId = r.id)
                        ) as Result.Success
                    ).data
                }
            val d =
                runBlocking {
                    (
                        h2.workItemRepository().create(
                            WorkItem(title = "D S12 (child of X)", parentId = x.id, depth = 2, rootId = r.id)
                        ) as Result.Success
                    ).data
                }
            val q =
                runBlocking {
                    stampSelfRoot(h2, (h2.workItemRepository().create(WorkItem(title = "Q S12", depth = 0)) as Result.Success).data)
                }
            val wrapped = UpdateFailsForIdRepository(h2.workItemRepository(), failFor = d.id)
            application { configureFailurePathTestApp(WorkItemRepoOverrideProvider(h2, wrapped)) }

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

            val persistedX = runBlocking { h2.workItemRepository().getById(x.id) }
            assertIs<Result.Success<WorkItem>>(persistedX)
            assertEquals(r.id, persistedX.data.parentId, "X must be left unchanged")
            assertEquals(r.id, persistedX.data.rootId, "X must be left unchanged")
            val persistedD = runBlocking { h2.workItemRepository().getById(d.id) }
            assertIs<Result.Success<WorkItem>>(persistedD)
            assertEquals(2, persistedD.data.depth, "D must be left unchanged")
            assertEquals(r.id, persistedD.data.rootId, "D must be left unchanged")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // F7/F8/F9/F10/F11/F12/F14 — review follow-up: advance-route request-parse failure paths
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun createQueueItem(
        h2: DefaultRepositoryProvider,
        title: String
    ): WorkItem = (h2.workItemRepository().create(WorkItem(title = title, role = Role.QUEUE, depth = 0)) as Result.Success).data

    @Test
    fun `F7 advance with a non-JSON Content-Type returns 415 unsupported_media_type before the body is read`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item = runBlocking { createQueueItem(h2, "F7 Item") }
            application { configureFailurePathTestApp(h2) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("unsupported_media_type", json["error"]?.jsonPrimitive?.content, "actual: $json")
            assertEquals("Use Content-Type: application/json", json["message"]?.jsonPrimitive?.content, "actual: $json")
        }

    @Test
    fun `F8 advance with malformed JSON returns 400 validation_error`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item = runBlocking { createQueueItem(h2, "F8 Item") }
            application { configureFailurePathTestApp(h2) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{not json""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("validation_error", json["error"]?.jsonPrimitive?.content, "actual: $json")
        }

    @Test
    fun `F9 advance with an unrecognized trigger returns 400 validation_error naming the trigger`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item = runBlocking { createQueueItem(h2, "F9 Item") }
            application { configureFailurePathTestApp(h2) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"bogus"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("validation_error", json["error"]?.jsonPrimitive?.content, "actual: $json")
            val message = json["message"]?.jsonPrimitive?.content ?: ""
            assertTrue(message.startsWith("Invalid trigger 'bogus'. Valid: "), "actual: $message")
        }

    @Test
    fun `F10 advance with more than 8 credentialRefs is rejected with the exact count message`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item = runBlocking { createQueueItem(h2, "F10 Item") }
            application { configureFailurePathTestApp(h2) }

            val refs = (1..9).joinToString(",") { "\"a$it\"" }
            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start","credentialRefs":[$refs]}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("validation_error", json["error"]?.jsonPrimitive?.content, "actual: $json")
            assertEquals(
                "credentialRefs must not contain more than 8 entries (found 9)",
                json["message"]?.jsonPrimitive?.content,
                "actual: $json"
            )
        }

    @Test
    fun `F11 advance with a credentialRefs entry violating the pattern is rejected with the exact message`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item = runBlocking { createQueueItem(h2, "F11 Item") }
            application { configureFailurePathTestApp(h2) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start","credentialRefs":["Bad!"]}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("validation_error", json["error"]?.jsonPrimitive?.content, "actual: $json")
            assertEquals(
                "credentialRefs[0] 'Bad!' does not match required pattern ^[a-z0-9][a-z0-9\\-_./]*$",
                json["message"]?.jsonPrimitive?.content,
                "actual: $json"
            )
        }

    @Test
    fun `F12 advance with an empty credentialRefs entry is rejected with the exact length message`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item = runBlocking { createQueueItem(h2, "F12 Item") }
            application { configureFailurePathTestApp(h2) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start","credentialRefs":[""]}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("validation_error", json["error"]?.jsonPrimitive?.content, "actual: $json")
            assertEquals(
                "credentialRefs[0] must be 1-128 characters (found length 0)",
                json["message"]?.jsonPrimitive?.content,
                "actual: $json"
            )
        }

    // ─────────────────────────────────────────────────────────────────────────
    // F13 — review follow-up: create-route Content-Type guard
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `F13 create with a non-JSON Content-Type returns 415 unsupported_media_type and nothing persists`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            application { configureFailurePathTestApp(h2) }

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("""{"title":"F13 Should Not Persist"}""")
                }

            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("unsupported_media_type", json["error"]?.jsonPrimitive?.content, "actual: $json")
            assertEquals("Use Content-Type: application/json", json["message"]?.jsonPrimitive?.content, "actual: $json")

            val persisted = runBlocking { h2.workItemRepository().findByFilters(limit = 500) }
            val titles = (persisted as Result.Success).data.items.map { it.title }
            assertTrue("F13 Should Not Persist" !in titles, "nothing must persist: $titles")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // F14 — review follow-up: guard precedence on advance (Content-Type / trigger parse both run
    // BEFORE the overrideResourceLeases capability check, even though the caller sets the flag)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `F14a wrong Content-Type with overrideResourceLeases set returns 415, not 403`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item = runBlocking { createQueueItem(h2, "F14a Item") }
            application { configureFailurePathTestApp(h2) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("""{"trigger":"start","overrideResourceLeases":true}""")
                }

            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("unsupported_media_type", json["error"]?.jsonPrimitive?.content, "actual: $json")
        }

    @Test
    fun `F14b an unrecognized trigger with overrideResourceLeases set returns 400, not 403`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item = runBlocking { createQueueItem(h2, "F14b Item") }
            application { configureFailurePathTestApp(h2) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"bogus","overrideResourceLeases":true}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("validation_error", json["error"]?.jsonPrimitive?.content, "actual: $json")
        }

    @Test
    fun `F14c a valid trigger with overrideResourceLeases set from a non-admin returns 403 once parsing succeeds`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item = runBlocking { createQueueItem(h2, "F14c Item") }
            application { configureFailurePathTestApp(h2) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start","overrideResourceLeases":true}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status, "actual: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("insufficient_capability", json["error"]?.jsonPrimitive?.content, "actual: $json")

            val persisted = runBlocking { h2.workItemRepository().getById(item.id) }
            assertIs<Result.Success<WorkItem>>(persisted)
            assertEquals(Role.QUEUE, persisted.data.role, "the flag must never be silently ignored")
        }

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
