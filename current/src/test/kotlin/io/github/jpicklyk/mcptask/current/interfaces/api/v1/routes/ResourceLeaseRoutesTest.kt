package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLeaseInterval
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
import kotlin.test.assertFalse
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

    /**
     * Mirrors (a simplified version of) `SQLiteResourceLeaseRepository`'s interval bookkeeping,
     * for the `/resources/leases/history` route tests.
     */
    val intervals = mutableListOf<ResourceLeaseInterval>()

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

class ResourceLeaseHistoryRouteTest {
    @Test
    fun `GET resources leases history with READ token returns 200 without actor fields`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            fake.seedLease(actorId = "agent-77")
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val response =
                client.get("/api/v1/resources/leases/history") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("db-migration-lock"))
            assertTrue(
                !body.contains("agent-77") && !body.contains("\"acquiredByActorId\""),
                "non-admin caller must not see acquiredByActorId, got: $body",
            )
        }

    @Test
    fun `GET resources leases history with ADMIN token returns 200 including actor fields`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            fake.seedLease(actorId = "agent-77")
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val response =
                client.get("/api/v1/resources/leases/history") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"acquiredByActorId\":\"agent-77\""), "admin caller must see acquiredByActorId, got: $body")
        }

    @Test
    fun `GET resources leases history without any token returns 401`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val response = client.get("/api/v1/resources/leases/history")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET resources leases history with an invalid at value returns 400`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val response =
                client.get("/api/v1/resources/leases/history?at=not-a-timestamp") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET resources leases history with an invalid key returns 400`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val response =
                client.get("/api/v1/resources/leases/history?key=Invalid_Key!") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET resources leases history with an at filter returns only intervals held at that instant`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            val now = Instant.now()
            val holderA = UUID.randomUUID()
            val holderB = UUID.randomUUID()
            // A: held from now-3600s to now-1800s (closed, released).
            fake.intervals +=
                ResourceLeaseInterval(
                    resourceKey = "staging-db",
                    holderItemId = holderA,
                    acquiredAt = now.minusSeconds(3600),
                    expiresAt = now.minusSeconds(1200),
                    releasedAt = now.minusSeconds(1800),
                    releaseReason = "released",
                )
            // B: held from now-900s onward, still open.
            fake.intervals +=
                ResourceLeaseInterval(
                    resourceKey = "staging-db",
                    holderItemId = holderB,
                    acquiredAt = now.minusSeconds(900),
                    expiresAt = now.plusSeconds(900),
                )
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val at = now.minusSeconds(2400) // inside A's held window, before B even acquired
            val response =
                client.get("/api/v1/resources/leases/history?at=$at") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(holderA.toString()), "holder A should be reported held at $at, body: $body")
            assertFalse(body.contains(holderB.toString()), "holder B must not be reported held at $at, body: $body")
        }

    @Test
    fun `GET resources leases history without at returns recent intervals newest-first`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            val now = Instant.now()
            val older = UUID.randomUUID()
            val newer = UUID.randomUUID()
            fake.intervals +=
                ResourceLeaseInterval(
                    resourceKey = "staging-db",
                    holderItemId = older,
                    acquiredAt = now.minusSeconds(600),
                    expiresAt = now.plusSeconds(600),
                )
            fake.intervals +=
                ResourceLeaseInterval(
                    resourceKey = "staging-db",
                    holderItemId = newer,
                    acquiredAt = now.minusSeconds(60),
                    expiresAt = now.plusSeconds(600),
                )
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val response =
                client.get("/api/v1/resources/leases/history?limit=1") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(newer.toString()), "the single most-recent interval must be returned, body: $body")
            assertFalse(body.contains(older.toString()), "limit=1 must exclude the older interval, body: $body")
        }

    @Test
    fun `GET resources leases history clamps limit=0 up to the minimum of 1`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            val now = Instant.now()
            val older = UUID.randomUUID()
            val newer = UUID.randomUUID()
            fake.intervals +=
                ResourceLeaseInterval(
                    resourceKey = "staging-db",
                    holderItemId = older,
                    acquiredAt = now.minusSeconds(600),
                    expiresAt = now.plusSeconds(600),
                )
            fake.intervals +=
                ResourceLeaseInterval(
                    resourceKey = "staging-db",
                    holderItemId = newer,
                    acquiredAt = now.minusSeconds(60),
                    expiresAt = now.plusSeconds(600),
                )
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val response =
                client.get("/api/v1/resources/leases/history?limit=0") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(newer.toString()))
            assertFalse(body.contains(older.toString()), "limit=0 must clamp to 1, not 0/unlimited, body: $body")
        }

    @Test
    fun `GET resources leases history accepts a limit far above the max without erroring`(): Unit =
        io.ktor.server.testing.testApplication {
            val fake = FakeResourceLeaseRepository()
            fake.seedLease()
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(leaseTestProvider(fake)) }) }

            val response =
                client.get("/api/v1/resources/leases/history?limit=99999") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
}
