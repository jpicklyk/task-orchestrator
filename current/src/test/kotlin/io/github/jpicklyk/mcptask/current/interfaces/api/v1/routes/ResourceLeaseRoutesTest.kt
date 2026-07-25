package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the REST `GET /api/v1/resources/leases` and `DELETE /api/v1/resources/leases/{key}`
 * routes registered by [resourceLeaseRoutes]. Reuses [TEST_TOKEN] (read-only) / [ADMIN_TOKEN] from
 * [ApiTestHelper] — same conventions as [PlanDocumentRoutesTest] / [ProjectConfigRoutesTest].
 *
 * Storage is an in-memory [FakeResourceLeaseRepository] rather than the SQLite-backed
 * implementation: the real repository's raw SQL is SQLite-dialect (`datetime('now')`) and does not
 * run on the H2 database these route tests use, and route tests assert auth/serialization
 * behavior, not storage semantics (those are covered by `SQLiteResourceLeaseRepositoryTest`).
 */
private class FakeResourceLeaseRepository : ResourceLeaseRepository {
    val leases = mutableListOf<ResourceLease>()

    override suspend fun acquireAll(
        holderItemId: UUID,
        actorId: String?,
        requirements: List<Pair<String, Int>>,
    ): LeaseAcquireResult {
        val contended =
            requirements
                .map { it.first }
                .filter { key -> leases.any { it.resourceKey == key && it.holderItemId != holderItemId } }
        if (contended.isNotEmpty()) return LeaseAcquireResult.Contended(contended, retryAfterMs = 1000)
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

    override suspend fun forceReleaseByKey(resourceKey: String): LeaseReleaseResult {
        val before = leases.size
        leases.removeAll { it.resourceKey == resourceKey }
        return LeaseReleaseResult.Success(before - leases.size)
    }

    override suspend fun findActiveByKeys(keys: List<String>): List<ResourceLease> = leases.filter { it.resourceKey in keys }

    override suspend fun findActiveForItem(holderItemId: UUID): List<ResourceLease> = leases.filter { it.holderItemId == holderItemId }

    override suspend fun findAllActive(): List<ResourceLease> = leases.toList()
}

private fun leaseTestProvider(fake: FakeResourceLeaseRepository): RepositoryProvider {
    val provider = mockk<RepositoryProvider>()
    every { provider.resourceLeaseRepository() } returns fake
    return provider
}

private fun FakeResourceLeaseRepository.seedLease(
    resourceKey: String = "db-migration-lock",
    actorId: String? = "agent-99",
    holderItemId: UUID = UUID.randomUUID(),
): UUID {
    val result = runBlocking { acquireAll(holderItemId, actorId, listOf(resourceKey to 300)) }
    check(result is LeaseAcquireResult.Success) { "Failed to seed lease: $result" }
    return holderItemId
}

class ResourceLeaseGetRouteTest {
    @Test
    fun `GET resources leases with READ token returns 200 without acquiredByActorId`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            fake.seedLease(actorId = "agent-99")
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val response =
                client.get("/api/v1/resources/leases") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("db-migration-lock"))
            assertTrue(
                !body.contains("agent-99") && !body.contains("\"acquiredByActorId\""),
                "non-admin caller must not see acquiredByActorId, got: $body",
            )
        }

    @Test
    fun `GET resources leases with ADMIN token returns 200 including acquiredByActorId`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            fake.seedLease(actorId = "agent-99")
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val response =
                client.get("/api/v1/resources/leases") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"acquiredByActorId\":\"agent-99\""), "admin caller must see acquiredByActorId, got: $body")
        }

    @Test
    fun `GET resources leases without any token returns 401`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val response = client.get("/api/v1/resources/leases")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}

class ResourceLeaseDeleteRouteTest {
    @Test
    fun `DELETE resources leases key with READ-only token returns 403`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            fake.seedLease()
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val response =
                client.delete("/api/v1/resources/leases/db-migration-lock") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            val remaining = runBlocking { fake.findActiveByKeys(listOf("db-migration-lock")) }
            assertTrue(remaining.isNotEmpty(), "READ-only caller must not be able to force-release a lease")
        }

    @Test
    fun `DELETE resources leases key with ADMIN token on an active lease returns 200 and releases it`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            fake.seedLease()
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val response =
                client.delete("/api/v1/resources/leases/db-migration-lock") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"resourceKey\":\"db-migration-lock\""))
            assertTrue(body.contains("\"releasedCount\":1"))

            val remaining = runBlocking { fake.findActiveByKeys(listOf("db-migration-lock")) }
            assertTrue(remaining.isEmpty(), "lease must be gone after force-release")

            // Verify re-acquire-ability: the key must now be free for a fresh acquire by a new holder.
            val reacquired =
                runBlocking {
                    fake.acquireAll(UUID.randomUUID(), "agent-2", listOf("db-migration-lock" to 60))
                }
            assertTrue(reacquired is LeaseAcquireResult.Success, "key must be immediately re-acquirable after force-release")
        }

    @Test
    fun `DELETE resources leases key for an unknown key returns 404`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val response =
                client.delete("/api/v1/resources/leases/no-such-key") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `DELETE resources leases key with an invalid key format returns 400`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val response =
                client.delete("/api/v1/resources/leases/Invalid_Key!") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `DELETE resources leases key exceeding max length returns 400`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val tooLong = "a".repeat(129)
            val response =
                client.delete("/api/v1/resources/leases/$tooLong") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}
