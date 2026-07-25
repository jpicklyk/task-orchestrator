package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLeaseInterval
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.ktor.client.request.header
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
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * REST enforcement-matrix tests for the resource-lease gate on `POST /items/{id}/advance`.
 *
 * Storage for leases is an in-memory fake, for the same reason [ResourceLeaseRoutesTest] uses one:
 * the SQLite-dialect repository (`datetime('now')`) does not run on the H2 database these route
 * tests use, and what is under test here is the route's enforcement/serialization behavior, not
 * lease storage semantics.
 */
private class LeaseGateFakeRepository : ResourceLeaseRepository {
    val leases = mutableListOf<ResourceLease>()
    val intervals = mutableListOf<ResourceLeaseInterval>()

    /** When non-null, every acquire is forced to report these keys contended. */
    var forceContended: List<String>? = null

    override suspend fun acquireAll(
        holderItemId: UUID,
        actorId: String?,
        requirements: List<Pair<String, Int>>,
    ): LeaseAcquireResult {
        forceContended?.let { return LeaseAcquireResult.Contended(it, retryAfterMs = 30_000) }
        val contended =
            requirements
                .map { it.first }
                .filter { key -> leases.any { it.resourceKey == key && it.holderItemId != holderItemId } }
        if (contended.isNotEmpty()) return LeaseAcquireResult.Contended(contended, retryAfterMs = 30_000)
        val now = Instant.now()
        val acquired =
            requirements.map { (key, ttl) ->
                val expiresAt = now.plusSeconds(ttl.toLong())
                val ownRowExisted = leases.any { it.resourceKey == key && it.holderItemId == holderItemId }
                if (ownRowExisted) {
                    val openIdx =
                        intervals.indexOfLast { it.resourceKey == key && it.holderItemId == holderItemId && it.releasedAt == null }
                    if (openIdx >= 0) {
                        intervals[openIdx] = intervals[openIdx].copy(expiresAt = expiresAt)
                    } else {
                        intervals +=
                            ResourceLeaseInterval(
                                resourceKey = key,
                                holderItemId = holderItemId,
                                acquiredByActorId = actorId,
                                acquiredAt = now,
                                expiresAt = expiresAt,
                            )
                    }
                } else {
                    intervals.replaceAll { iv ->
                        if (iv.resourceKey == key && iv.holderItemId != holderItemId && iv.releasedAt == null) {
                            iv.copy(releasedAt = iv.expiresAt, releaseReason = "expired")
                        } else {
                            iv
                        }
                    }
                    intervals +=
                        ResourceLeaseInterval(
                            resourceKey = key,
                            holderItemId = holderItemId,
                            acquiredByActorId = actorId,
                            acquiredAt = now,
                            expiresAt = expiresAt,
                        )
                }
                ResourceLease(
                    resourceKey = key,
                    holderItemId = holderItemId,
                    acquiredByActorId = actorId,
                    acquiredAt = now,
                    expiresAt = expiresAt,
                    originalAcquiredAt = now,
                )
            }
        leases += acquired
        return LeaseAcquireResult.Success(acquired)
    }

    override suspend fun releaseAllForItem(holderItemId: UUID): LeaseReleaseResult {
        val before = leases.size
        leases.removeAll { it.holderItemId == holderItemId }
        val now = Instant.now()
        intervals.replaceAll { iv ->
            if (iv.holderItemId == holderItemId && iv.releasedAt == null) {
                iv.copy(releasedAt = now, releaseReason = "released")
            } else {
                iv
            }
        }
        return LeaseReleaseResult.Success(before - leases.size)
    }

    override suspend fun forceReleaseByKey(
        resourceKey: String,
        actorId: String?,
    ): LeaseReleaseResult {
        val before = leases.size
        leases.removeAll { it.resourceKey == resourceKey }
        val now = Instant.now()
        intervals.replaceAll { iv ->
            if (iv.resourceKey == resourceKey && iv.releasedAt == null) {
                iv.copy(releasedAt = now, releaseReason = "force_released", releasedByActorId = actorId)
            } else {
                iv
            }
        }
        return LeaseReleaseResult.Success(before - leases.size)
    }

    override suspend fun findActiveByKeys(keys: List<String>): List<ResourceLease> = leases.filter { it.resourceKey in keys }

    override suspend fun findActiveForItem(holderItemId: UUID): List<ResourceLease> = leases.filter { it.holderItemId == holderItemId }

    override suspend fun findAllActive(): List<ResourceLease> = leases.toList()

    override suspend fun findHoldersAt(
        resourceKey: String?,
        at: Instant,
    ): List<ResourceLeaseInterval> =
        intervals
            .filter { iv ->
                (resourceKey == null || iv.resourceKey == resourceKey) &&
                    !at.isBefore(iv.acquiredAt) &&
                    at.isBefore(iv.releasedAt ?: iv.expiresAt)
            }.sortedByDescending { it.acquiredAt }

    override suspend fun findRecentIntervals(
        resourceKey: String?,
        limit: Int,
    ): List<ResourceLeaseInterval> =
        intervals
            .filter { resourceKey == null || it.resourceKey == resourceKey }
            .sortedByDescending { it.acquiredAt }
            .take(limit)
}

/** H2-backed provider with only [resourceLeaseRepository] swapped for the in-memory fake. */
private class LeaseOverridingProvider(
    private val delegate: RepositoryProvider,
    private val fake: ResourceLeaseRepository,
) : RepositoryProvider by delegate {
    override fun resourceLeaseRepository(): ResourceLeaseRepository = fake
}

/** Maps the `needs-staging-db` trait onto one exclusive resource with a 600s TTL. */
private class LeaseTraitSchemaService : WorkItemSchemaService {
    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null

    override fun getTraitResources(traitName: String): List<ResourceRequirement> =
        if (traitName == "needs-staging-db") {
            listOf(ResourceRequirement("staging-db-credential", ResourceMode.EXCLUSIVE, 600))
        } else {
            emptyList()
        }
}

private fun Application.configureLeaseTestApp(provider: RepositoryProvider) {
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
                LeaseTraitSchemaService(),
                statusLabelService = NoOpStatusLabelService,
            )
        }
    }
}

/** Creates a queued item carrying the resource-declaring trait. */
private fun DefaultRepositoryProvider.createTracedItem(title: String): WorkItem =
    runBlocking {
        workItemRepository()
            .create(
                WorkItem(
                    title = title,
                    role = Role.QUEUE,
                    depth = 0,
                    properties = """{"traits":["needs-staging-db"]}""",
                ),
            ).getOrNull()!!
    }

class AdvanceRouteResourceLeaseTest {
    @Test
    fun `contended resource returns 409 with Retry-After and the contended keys`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val fake = LeaseGateFakeRepository()
            val item = h2.createTracedItem("Needs staging DB")
            fake.forceContended = listOf("staging-db-credential")
            // Distinctive seeds so their absence in the serialized 409 body is unambiguous — mirrors
            // AdvanceItemToolLeaseBlockedTest's disclosure assertion for the MCP surface.
            val holderItemId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
            val holderActorId = "holder-actor-zzz999"
            val now = Instant.now()
            fake.leases +=
                ResourceLease(
                    resourceKey = "staging-db-credential",
                    holderItemId = holderItemId,
                    acquiredByActorId = holderActorId,
                    acquiredAt = now,
                    expiresAt = now.plusSeconds(600),
                    originalAcquiredAt = now,
                )
            application { configureLeaseTestApp(LeaseOverridingProvider(h2, fake)) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            // 30_000ms rounds UP to 30s — a client must never retry before the lease can expire.
            assertEquals("30", response.headers[HttpHeaders.RetryAfter])
            val body = response.bodyAsText()
            assertTrue(body.contains("resource_unavailable"), "body: $body")
            assertTrue(body.contains("staging-db-credential"), "body: $body")
            assertFalse(body.contains(holderItemId.toString()), "holder item id must never appear in a 409 body: $body")
            assertFalse(body.contains(holderActorId), "holder actor id must never appear in a 409 body: $body")

            // Hard negative: the item did NOT advance.
            val persisted = runBlocking { h2.workItemRepository().getById(item.id) }
            assertEquals(Role.QUEUE, (persisted as Result.Success).data.role)
        }

    @Test
    fun `an uncontended advance acquires the lease and succeeds`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val fake = LeaseGateFakeRepository()
            val item = h2.createTracedItem("Needs staging DB")
            application { configureLeaseTestApp(LeaseOverridingProvider(h2, fake)) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, fake.leases.size)
            assertEquals("staging-db-credential", fake.leases.single().resourceKey)
            assertEquals(item.id, fake.leases.single().holderItemId)
        }

    @Test
    fun `leaving the work phase releases the lease`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val fake = LeaseGateFakeRepository()
            val item = h2.createTracedItem("Needs staging DB")
            application { configureLeaseTestApp(LeaseOverridingProvider(h2, fake)) }

            val started =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(HttpStatusCode.OK, started.status)
            assertEquals(1, fake.leases.size)

            val completed =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"complete"}""")
                }
            assertEquals(HttpStatusCode.OK, completed.status)
            assertTrue(fake.leases.isEmpty(), "leases must be released on exit from the work phase")
        }

    // ──────────────────────────────────────────────
    // overrideResourceLeases — the ADMIN-only escape hatch
    // ──────────────────────────────────────────────

    @Test
    fun `overrideResourceLeases from a non-admin principal is rejected with 403`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val fake = LeaseGateFakeRepository()
            val item = h2.createTracedItem("Needs staging DB")
            fake.forceContended = listOf("staging-db-credential")
            application { configureLeaseTestApp(LeaseOverridingProvider(h2, fake)) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    // WRITE_TOKEN holds ADVANCE but NOT ADMIN.
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start","overrideResourceLeases":true}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("insufficient_capability"), response.bodyAsText())

            // The flag must never be silently ignored — the item stays put.
            val persisted = runBlocking { h2.workItemRepository().getById(item.id) }
            assertEquals(Role.QUEUE, (persisted as Result.Success).data.role)
        }

    @Test
    fun `overrideResourceLeases from an ADMIN principal proceeds past contention and is audited`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val fake = LeaseGateFakeRepository()
            val item = h2.createTracedItem("Needs staging DB")
            fake.forceContended = listOf("staging-db-credential")
            application { configureLeaseTestApp(LeaseOverridingProvider(h2, fake)) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start","overrideResourceLeases":true}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val persisted = runBlocking { h2.workItemRepository().getById(item.id) }
            assertEquals(Role.WORK, (persisted as Result.Success).data.role)
            // No lease was taken — the gate was skipped, not satisfied.
            assertTrue(fake.leases.isEmpty())

            // The durable audit row records the override alongside the WARN log line.
            val transitions =
                runBlocking { h2.roleTransitionRepository().findByItemId(item.id) }
                    .let { (it as Result.Success).data }
            val summary = transitions.firstNotNullOfOrNull { it.summary }
            assertNotNull(summary, "the override must be recorded on the transition audit row")
            assertTrue(summary.contains("resource leases overridden"), "summary was: $summary")
        }

    @Test
    fun `omitting overrideResourceLeases keeps the gate enforced for an ADMIN principal`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val fake = LeaseGateFakeRepository()
            val item = h2.createTracedItem("Needs staging DB")
            fake.forceContended = listOf("staging-db-credential")
            application { configureLeaseTestApp(LeaseOverridingProvider(h2, fake)) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            // Admin authority alone does NOT waive the resource gate — it takes the explicit flag.
            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `an item declaring no resources is unaffected by the gate`(): Unit =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val fake = LeaseGateFakeRepository()
            val item =
                runBlocking {
                    h2.workItemRepository().create(WorkItem(title = "Plain", depth = 0)).getOrNull()!!
                }
            fake.forceContended = listOf("staging-db-credential")
            application { configureLeaseTestApp(LeaseOverridingProvider(h2, fake)) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(response.bodyAsText().contains("resource_unavailable"))
            assertTrue(fake.leases.isEmpty())
        }
}
