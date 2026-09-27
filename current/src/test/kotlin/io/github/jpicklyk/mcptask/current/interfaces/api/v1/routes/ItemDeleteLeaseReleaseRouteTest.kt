package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.test.SQLiteRepositoryTestBase
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wraps a real [ResourceLeaseRepository], failing [releaseAllForItem] with
 * [LeaseReleaseResult.DBError] for exactly one holder id; every other member delegates unchanged.
 * Mirrors [io.github.jpicklyk.mcptask.current.application.tools.items.DeleteItemLeaseReleaseTest]'s
 * seam (itself mirroring `DeleteItemHandlerAtomicityTest`'s FailOnId pattern), applied here to the
 * REST delete path.
 */
private class FailOnIdResourceLeaseRepository(
    private val delegate: ResourceLeaseRepository,
    private val failingHolderId: UUID,
) : ResourceLeaseRepository by delegate {
    override suspend fun releaseAllForItem(holderItemId: UUID): LeaseReleaseResult =
        if (holderItemId == failingHolderId) {
            LeaseReleaseResult.DBError(RuntimeException("Simulated lease release failure for $holderItemId"))
        } else {
            delegate.releaseAllForItem(holderItemId)
        }
}

/** Wraps a real [RepositoryProvider], substituting [failingLeaseRepo] for [resourceLeaseRepository]. */
private class FailOnIdRepositoryProvider(
    private val delegate: RepositoryProvider,
    private val failingLeaseRepo: ResourceLeaseRepository,
) : RepositoryProvider by delegate {
    override fun resourceLeaseRepository(): ResourceLeaseRepository = failingLeaseRepo
}

/**
 * A write-route app wired directly against `itemWriteRoutes` (not [configureWriteTestApp], which
 * is pinned to [io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider])
 * so a [RepositoryProvider] wrapper — [FailOnIdRepositoryProvider] — can be installed for S8.
 * Mirrors [AdvanceRouteResourceLeaseTest]'s `configureLeaseTestApp`.
 */
private fun Application.configureDeleteLeaseTestApp(provider: RepositoryProvider) {
    install(ContentNegotiation) { json(McpJson) }
    install(SSE)
    val authConfig = makeWriteAuthConfig()
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
                provider,
                DegradedModePolicy.ACCEPT_CACHED,
                IdempotencyCache(),
                ToolExecutionContext(
                    provider,
                    NoOpNoteSchemaService,
                    statusLabelService = NoOpStatusLabelService,
                    perRootConfigService = PerRootConfigService(provider.projectConfigRepository()),
                ).advanceServiceFactory(),
            )
        }
    }
}

/**
 * Independent test authorship for item 2cef6ca4 (needs-test-author) — REST delete surface only
 * (S6, S8, plus review fix-up B1). The MCP surface (S4/S5/S7/S9/S10/S11/B1a/B1b + probes) lives in
 * [io.github.jpicklyk.mcptask.current.application.tools.items.DeleteItemLeaseReleaseTest].
 *
 * Oracles (frozen in test-plan note b9109cc0 / diagnosis note ca116121, before implementation was
 * read):
 *  [V16] `resource_lease_history`: every interval is closed exactly once, on release.
 *  [DX] diagnosis: the REST delete route wraps release+delete in one transaction; a release
 *       DBError takes the existing 500 `db_error` path and the row is kept — no other change to
 *       the route's status codes or body shape from this item.
 *
 * Runs on real SQLite ([SQLiteRepositoryTestBase]) because the lease repository's SQL uses
 * `datetime()`, which the H2 database the other route tests in this package use does not support —
 * the harness the test-plan names for these scenarios (mirroring why
 * [AdvanceRouteResourceLeaseTest] uses an in-memory fake instead: here real lease-close semantics
 * ARE what is under test, so a fake would not do).
 */
class ItemDeleteLeaseReleaseRouteTest : SQLiteRepositoryTestBase() {
    @Test
    fun `S6 REST DELETE of a leased leaf returns 204 and closes its lease interval as released`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val item =
                runBlocking {
                    repositoryProvider.workItemRepository().create(WorkItem(title = "Leased Leaf", depth = 0)).getOrNull()!!
                }
            val leaseRepo = repositoryProvider.resourceLeaseRepository()
            assertIs<LeaseAcquireResult.Success>(
                runBlocking { leaseRepo.acquireAll(item.id, "agent-a", listOf("k-s6" to 900)) },
            )

            val response =
                client.delete("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            val persisted = runBlocking { repositoryProvider.workItemRepository().getById(item.id) }
            assertTrue(persisted is Result.Error, "item must be gone")

            val interval = runBlocking { leaseRepo.findRecentIntervals("k-s6", 10) }.single()
            assertEquals("released", interval.releaseReason)
            assertNotNull(interval.releasedAt)
        }

    @Test
    fun `S8 REST DELETE with a lease-release DBError returns 500 db_error and keeps the item`(): Unit =
        testApplication {
            val item =
                runBlocking {
                    repositoryProvider.workItemRepository().create(WorkItem(title = "Will Survive", depth = 0)).getOrNull()!!
                }
            val leaseRepo = repositoryProvider.resourceLeaseRepository()
            assertIs<LeaseAcquireResult.Success>(
                runBlocking { leaseRepo.acquireAll(item.id, "agent-a", listOf("k-s8" to 900)) },
            )
            val failing = FailOnIdResourceLeaseRepository(leaseRepo, item.id)
            application { configureDeleteLeaseTestApp(FailOnIdRepositoryProvider(repositoryProvider, failing)) }

            val response =
                client.delete("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status, "body: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("db_error", json["error"]?.jsonPrimitive?.content)

            val persisted = runBlocking { repositoryProvider.workItemRepository().getById(item.id) }
            assertTrue(persisted is Result.Success, "item must survive a release DBError")

            val interval = leaseRepo.findRecentIntervals("k-s8", 10).single()
            assertNull(interval.releasedAt, "the lease must remain open — the release failed before the delete")
        }

    /**
     * Review fix-up B1, corrected per fc8f3748 arbitration case 2 (2026-09-25): fc8f3748's
     * HasChildren pre-check now runs BEFORE any lease release on the REST route, so the child-FK
     * 500 this test originally reproduced is unreachable through the route — a parent with
     * children and no `?recursive=true` is refused with a structured 409 before the delete (and
     * any lease release) is ever attempted. Oracle: fc8f3748 diagnosis F1 / test-plan — "REST
     * DELETE of an item with children and no `?recursive=true` -> structured 409 `has_children`
     * (child count in details), nothing deleted". The lease-rollback-on-FK-failure scenario this
     * test originally named is exercised, via a failing-repository seam rather than the
     * unreachable FK path, in
     * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.ItemDeleteRecursiveSqliteRouteTest].
     */
    @Test
    fun `B1 REST DELETE of a leased parent without recursive is refused with 409 has_children and touches no lease`(): Unit =
        testApplication {
            application { configureWriteTestApp(repositoryProvider) }
            val parent =
                runBlocking {
                    repositoryProvider.workItemRepository().create(WorkItem(title = "Leased Parent", depth = 0)).getOrNull()!!
                }
            val child =
                runBlocking {
                    repositoryProvider
                        .workItemRepository()
                        .create(WorkItem(title = "Child", parentId = parent.id, depth = 1))
                        .getOrNull()!!
                }
            val leaseRepo = repositoryProvider.resourceLeaseRepository()
            assertIs<LeaseAcquireResult.Success>(
                runBlocking { leaseRepo.acquireAll(parent.id, "agent-a", listOf("k-b1-rest" to 900)) },
            )

            val response =
                client.delete("/api/v1/items/${parent.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.Conflict, response.status, "body: ${response.bodyAsText()}")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("has_children", json["error"]?.jsonPrimitive?.content)
            assertEquals(
                1,
                json["details"]
                    ?.jsonObject
                    ?.get("childCount")
                    ?.jsonPrimitive
                    ?.content
                    ?.toInt()
            )

            val persistedParent = runBlocking { repositoryProvider.workItemRepository().getById(parent.id) }
            assertTrue(persistedParent is Result.Success, "the parent must remain — nothing was deleted")
            val persistedChild = runBlocking { repositoryProvider.workItemRepository().getById(child.id) }
            assertTrue(persistedChild is Result.Success, "the child must remain untouched")

            val interval = leaseRepo.findRecentIntervals("k-b1-rest", 10).single()
            assertNull(interval.releaseReason, "the has_children refusal must precede any lease release")
            assertNull(interval.releasedAt, "the has_children refusal must precede any lease release")
            assertEquals(1, leaseRepo.findActiveForItem(parent.id).size, "the parent's lease must still be ACTIVE")
        }
}
