package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.test.EnvelopeConformance
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 2cd17c00) for the three lease admin routes on the envelope: scenarios S6, S7, S16
 * (DELETE half) and S20 (query-parameter half) of the frozen test-plan, over a real SQLite database, a real [ClaimService]
 * and the real [resourceLeaseRoutes], with a settable clock so every timestamp is exact.
 *
 * Oracles: task-scope TS5 and D7/D8 (GET leases is a page of `{resourceKey, holderItemId, acquiredByActorId? (ADMIN only),
 * acquiredAt, expiresAt, originalAcquiredAt}` ordered by (resourceKey, holderItemId) with `total` equal to the items returned;
 * history is newest-first with `limit` default 50 and valid range 1 to 200, its page `total` the full matching count with
 * `truncated` and `next` per the list-page contract (envelope amendment U22), a non-integer or out-of-range `limit` is
 * `invalid_request` on field `limit`, a bad `key` on `key`, an unparsable `at` on `at`; DELETE answers
 * `{resourceKey, releasedCount}` and a repeat is `not_found` of kind `lease`; a missing capability is `forbidden` with scope
 * `capability`; a missing token keeps the legacy 401), the envelope spec (ok/data/meta shape, list data, catalog statuses) and the
 * lease repository history contract (an interval closes as `released` or `force_released`, the latter recording the acting
 * principal). The structural shape of every body is also checked by [EnvelopeConformance].
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ResourceLeaseRoutesTest {
    private val start: Instant = Instant.parse("2026-10-10T12:00:00Z")
    private val clock = SettableClock(start)

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private val provider get() = sqlite.db.repositoryProvider()

    private fun claimService() = ClaimService(provider, sqlite.db.unitOfWork(clock))

    private fun withRoutes(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(claimService()) }) }
            block()
        }

    /** Seeds an item that holds an exclusive lease on [key] for [ttl] seconds, acquired by [actor]; returns the holder's id. */
    private fun lease(
        key: String,
        actor: String = "agent-99",
        ttl: Int = 300
    ): UUID =
        runBlocking {
            val holder = provider.workItemRepository().create(WorkItem(title = "holder of $key", depth = 0))
            val acquired = provider.resourceLeaseRepository().acquireAll(holder.id, actor, listOf(key to ttl))
            assertIs<LeaseAcquireResult.Success>(acquired, "fixture: lease on $key")
            holder.id
        }

    private fun release(holder: UUID) {
        runBlocking { provider.resourceLeaseRepository().releaseAllForItem(holder) }
    }

    private suspend fun HttpClient.getAs(
        path: String,
        token: String?
    ): HttpResponse =
        get(path) {
            if (token != null) header("Authorization", "Bearer $token")
        }

    private suspend fun HttpResponse.envelope(): JsonObject {
        val body = Json.parseToJsonElement(bodyAsText()).jsonObject
        EnvelopeConformance.assertConforms(body, "lease route body")
        return body
    }

    private fun JsonObject.items(): List<JsonObject> =
        getValue("data")
            .jsonObject
            .getValue("items")
            .jsonArray
            .map { it.jsonObject }

    private fun JsonObject.page(): JsonObject = getValue("data").jsonObject.getValue("page").jsonObject

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.errorDetail(): JsonObject =
        getValue("error")
            .jsonObject
            .getValue("detail")
            .jsonObject

    private fun JsonObject.errorCode(): String =
        getValue("error")
            .jsonObject
            .str("code")

    private fun JsonObject.fieldOfSingleViolation(): String =
        errorDetail()
            .getValue("fields")
            .jsonArray
            .single()
            .jsonObject
            .str("field")

    // ------------------------------------------------------------------ S6 GET /resources/leases

    @Test
    fun `S6 GET leases is a page of lease items ordered by resource key with identity fields only for an admin`() =
        withRoutes {
            val zeta = lease("zeta-lock", actor = "agent-zeta")
            val alpha = lease("alpha-lock", actor = "agent-alpha")
            val mid = lease("mid-lock", actor = "agent-mid")

            val admin = client.getAs("/api/v1/resources/leases", ADMIN_TOKEN)
            assertEquals(200, admin.status.value)
            val adminBody = admin.envelope()
            assertTrue(adminBody.str("ok").toBoolean())
            assertEquals(listOf("alpha-lock", "mid-lock", "zeta-lock"), adminBody.items().map { it.str("resourceKey") })
            assertEquals(listOf(alpha, mid, zeta).map { it.toString() }, adminBody.items().map { it.str("holderItemId") })
            assertEquals(listOf("agent-alpha", "agent-mid", "agent-zeta"), adminBody.items().map { it.str("acquiredByActorId") })
            val first = adminBody.items().first()
            assertEquals(start, Instant.parse(first.str("acquiredAt")))
            assertEquals(start.plusSeconds(300), Instant.parse(first.str("expiresAt")))
            assertEquals(start, Instant.parse(first.str("originalAcquiredAt")))
            assertEquals(
                setOf("resourceKey", "holderItemId", "acquiredByActorId", "acquiredAt", "expiresAt", "originalAcquiredAt"),
                first.keys
            )
            assertEquals(
                3,
                adminBody
                    .page()
                    .getValue("total")
                    .jsonPrimitive.content
                    .toInt(),
                "total equals the items returned"
            )
            assertEquals("false", adminBody.page().str("truncated"))
            assertFalse("next" in adminBody.page())

            val reader = client.getAs("/api/v1/resources/leases", TEST_TOKEN)
            assertEquals(200, reader.status.value)
            val readerBody = reader.envelope()
            assertEquals(listOf("alpha-lock", "mid-lock", "zeta-lock"), readerBody.items().map { it.str("resourceKey") })
            for (item in readerBody.items()) {
                assertEquals(setOf("resourceKey", "holderItemId", "acquiredAt", "expiresAt", "originalAcquiredAt"), item.keys)
            }
            assertFalse("agent-" in readerBody.toString(), "no actor identity reaches a non-admin caller")
        }

    @Test
    fun `S6 GET leases with nothing leased is an ok empty page`() =
        withRoutes {
            val response = client.getAs("/api/v1/resources/leases", TEST_TOKEN)

            assertEquals(200, response.status.value)
            val body = response.envelope()
            assertEquals(emptyList(), body.items())
            assertEquals(
                0,
                body
                    .page()
                    .getValue("total")
                    .jsonPrimitive.content
                    .toInt()
            )
        }

    @Test
    fun `S6 GET leases and history without a token keep the legacy 401`() =
        withRoutes {
            assertEquals(401, client.getAs("/api/v1/resources/leases", null).status.value)
            assertEquals(401, client.getAs("/api/v1/resources/leases/history", null).status.value)
            assertEquals(
                401,
                client
                    .delete("/api/v1/resources/leases/some-key")
                    .status.value
            )
        }

    // ------------------------------------------------------------------ S6 history

    @Test
    fun `S6 history is newest first, limit bounds the page and total is the full count with truncated and next`() =
        withRoutes {
            val older = lease("older-lock", actor = "agent-old")
            clock.advanceSeconds(60)
            val newer = lease("newer-lock", actor = "agent-new")

            val both = client.getAs("/api/v1/resources/leases/history", ADMIN_TOKEN)
            assertEquals(200, both.status.value)
            val bothBody = both.envelope()
            assertEquals(listOf(newer, older).map { it.toString() }, bothBody.items().map { it.str("holderItemId") })
            assertEquals(
                2,
                bothBody
                    .page()
                    .getValue("total")
                    .jsonPrimitive.content
                    .toInt()
            )

            val one = client.getAs("/api/v1/resources/leases/history?limit=1", ADMIN_TOKEN).envelope()
            assertEquals(listOf(newer.toString()), one.items().map { it.str("holderItemId") })
            assertEquals(
                2,
                one
                    .page()
                    .getValue("total")
                    .jsonPrimitive.content
                    .toInt(),
                "total is the full matching count, not the number of items returned"
            )
            assertEquals("true", one.page().str("truncated"))
            assertTrue("next" in one.page(), "a truncated page carries a next cursor")
        }

    @Test
    fun `S6 history default limit is 50`() =
        withRoutes {
            repeat(51) { lease("bulk-%02d".format(it)) }

            val defaulted = client.getAs("/api/v1/resources/leases/history", ADMIN_TOKEN).envelope()
            assertEquals(50, defaulted.items().size)

            val wide = client.getAs("/api/v1/resources/leases/history?limit=200", ADMIN_TOKEN).envelope()
            assertEquals(51, wide.items().size)
        }

    @Test
    fun `S6 history at filters to the holders at that instant and key filters to one resource`() =
        withRoutes {
            val a = lease("staging-db", ttl = 300) // held from start
            clock.advanceSeconds(100)
            release(a) // released at start + 100
            clock.advanceSeconds(100)
            val b = lease("staging-db", ttl = 300) // held from start + 200
            lease("other-lock")

            fun at(offset: Long) = start.plusSeconds(offset)

            val duringA = client.getAs("/api/v1/resources/leases/history?key=staging-db&at=${at(50)}", ADMIN_TOKEN).envelope()
            assertEquals(listOf(a.toString()), duringA.items().map { it.str("holderItemId") })

            val duringB = client.getAs("/api/v1/resources/leases/history?key=staging-db&at=${at(250)}", ADMIN_TOKEN).envelope()
            assertEquals(listOf(b.toString()), duringB.items().map { it.str("holderItemId") })

            val before = client.getAs("/api/v1/resources/leases/history?key=staging-db&at=${at(-10)}", ADMIN_TOKEN).envelope()
            assertEquals(emptyList(), before.items())

            val byKey = client.getAs("/api/v1/resources/leases/history?key=staging-db", ADMIN_TOKEN).envelope()
            assertEquals(setOf("staging-db"), byKey.items().map { it.str("resourceKey") }.toSet())
            assertEquals(listOf(b, a).map { it.toString() }, byKey.items().map { it.str("holderItemId") }, "newest first")
            val closed = byKey.items().last()
            assertEquals(start.plusSeconds(100), Instant.parse(closed.str("releasedAt")))
            assertEquals("released", closed.str("releaseReason"))
            assertFalse("releasedAt" in byKey.items().first(), "the open interval has no releasedAt")
        }

    @Test
    fun `S6 history shows actor fields to an admin only`() =
        withRoutes {
            lease("seen-lock", actor = "agent-77")

            val admin =
                client
                    .getAs("/api/v1/resources/leases/history", ADMIN_TOKEN)
                    .envelope()
                    .items()
                    .single()
            assertEquals("agent-77", admin.str("acquiredByActorId"))

            val reader = client.getAs("/api/v1/resources/leases/history", TEST_TOKEN)
            assertEquals(200, reader.status.value)
            val readerBody = reader.envelope()
            assertEquals("seen-lock", readerBody.items().single().str("resourceKey"))
            assertFalse("acquiredByActorId" in readerBody.items().single())
            assertFalse("agent-77" in readerBody.toString())
        }

    // ------------------------------------------------------------------ S20 query parameters

    @Test
    fun `S20 a bad history limit is invalid_request on the limit field and the bounds 1 and 200 are accepted`() =
        withRoutes {
            lease("limit-lock")
            for (bad in listOf("0", "201", "-1", "x", "1.5")) {
                val response = client.getAs("/api/v1/resources/leases/history?limit=$bad", ADMIN_TOKEN)
                assertEquals(400, response.status.value, "limit='$bad'")
                val body = response.envelope()
                assertEquals("invalid_request", body.errorCode(), "limit='$bad'")
                assertEquals("limit", body.fieldOfSingleViolation(), "limit='$bad'")
            }
            for (good in listOf("1", "200")) {
                assertEquals(200, client.getAs("/api/v1/resources/leases/history?limit=$good", ADMIN_TOKEN).status.value, "limit=$good")
            }
        }

    @Test
    fun `S20 a bad history key or at is invalid_request on that field`() =
        withRoutes {
            val badKeys = listOf("Invalid_Key!", "a".repeat(129), "-leading-dash", "Staging-DB", "has space")
            for (key in badKeys) {
                val response = client.getAs("/api/v1/resources/leases/history?key=${key.replace(" ", "%20")}", ADMIN_TOKEN)
                assertEquals(400, response.status.value, "key='$key'")
                assertEquals("key", response.envelope().fieldOfSingleViolation(), "key='$key'")
            }
            for (at in listOf("yesterday", "not-a-timestamp", "2026-13-45T00:00:00Z")) {
                val response = client.getAs("/api/v1/resources/leases/history?at=$at", ADMIN_TOKEN)
                assertEquals(400, response.status.value, "at='$at'")
                assertEquals("at", response.envelope().fieldOfSingleViolation(), "at='$at'")
            }
            assertEquals(200, client.getAs("/api/v1/resources/leases/history?key=${"a".repeat(128)}", ADMIN_TOKEN).status.value)
            assertEquals(200, client.getAs("/api/v1/resources/leases/history?key=a.b%2Fc-d_e", ADMIN_TOKEN).status.value)
        }

    // ------------------------------------------------------------------ S7 / S16 DELETE

    @Test
    fun `S7 DELETE releases the lease, answers resourceKey and releasedCount and records a forced lease released row`() =
        withRoutes {
            val holder = lease("force-me")
            lease("keep-me")
            val before = runBlocking { provider.eventStore().maxSeq() }

            val response = client.delete("/api/v1/resources/leases/force-me") { header("Authorization", "Bearer $ADMIN_TOKEN") }

            assertEquals(200, response.status.value)
            val body = response.envelope()
            assertTrue(body.str("ok").toBoolean())
            val data = body.getValue("data").jsonObject
            assertEquals("force-me", data.str("resourceKey"))
            assertEquals(
                1,
                data
                    .getValue("releasedCount")
                    .jsonPrimitive.content
                    .toInt()
            )
            assertEquals(setOf("resourceKey", "releasedCount"), data.keys)

            val remaining = runBlocking { provider.resourceLeaseRepository().findAllActive() }
            assertEquals(listOf("keep-me"), remaining.map { it.resourceKey }, "only the named key was released")
            val rows = runBlocking { provider.eventStore().readAfter(before, null, 1000) }.filter { it.type == "lease.released" }
            assertEquals(1, rows.size, "one lease.released row: $rows")
            assertTrue(
                rows
                    .single()
                    .payload()
                    .getValue("forced")
                    .jsonPrimitive.content
                    .toBoolean(),
                "the row is marked forced: ${rows.single().data}"
            )
            assertEquals("force-me", rows.single().str("key"))

            val reacquired =
                runBlocking {
                    val other = provider.workItemRepository().create(WorkItem(title = "next holder", depth = 0))
                    provider.resourceLeaseRepository().acquireAll(other.id, "agent-2", listOf("force-me" to 60))
                }
            assertIs<LeaseAcquireResult.Success>(reacquired, "the key is free after a force release")

            val history = client.getAs("/api/v1/resources/leases/history?key=force-me", ADMIN_TOKEN).envelope().items()
            val closed = history.single { it.str("holderItemId") == holder.toString() }
            assertEquals("force_released", closed.str("releaseReason"))
            assertTrue(closed.str("releasedByActorId").endsWith(ADMIN_TOKEN_ID), "the acting principal is recorded for an admin: $closed")
        }

    @Test
    fun `S7 a repeated DELETE or an unknown key is not_found of kind lease`() =
        withRoutes {
            lease("once-only")
            val first = client.delete("/api/v1/resources/leases/once-only") { header("Authorization", "Bearer $ADMIN_TOKEN") }
            assertEquals(200, first.status.value)

            for (key in listOf("once-only", "never-held")) {
                val response = client.delete("/api/v1/resources/leases/$key") { header("Authorization", "Bearer $ADMIN_TOKEN") }
                assertEquals(404, response.status.value, key)
                val body = response.envelope()
                assertEquals("not_found", body.errorCode())
                assertEquals("lease", body.errorDetail().str("kind"))
                assertEquals(key, body.errorDetail().str("id"))
            }
        }

    @Test
    fun `S16 DELETE with a read-only token is forbidden on the admin capability and releases nothing`() =
        withRoutes {
            lease("guarded")

            val response = client.delete("/api/v1/resources/leases/guarded") { header("Authorization", "Bearer $TEST_TOKEN") }

            assertEquals(403, response.status.value)
            val body = response.envelope()
            assertEquals("forbidden", body.errorCode())
            assertEquals("capability", body.errorDetail().str("scope"))
            assertEquals("admin", body.errorDetail().str("required"))
            assertEquals(1, runBlocking { provider.resourceLeaseRepository().findAllActive() }.size, "the lease is untouched")
        }

    @Test
    fun `S16 DELETE with a malformed key is invalid_request on the key field and releases nothing`() =
        withRoutes {
            lease("valid-key")
            for (key in listOf("Invalid_Key!", "a".repeat(129))) {
                val response = client.delete("/api/v1/resources/leases/$key") { header("Authorization", "Bearer $ADMIN_TOKEN") }
                assertEquals(400, response.status.value, "key of length ${key.length}")
                val body = response.envelope()
                assertEquals("invalid_request", body.errorCode())
                assertEquals("key", body.fieldOfSingleViolation())
            }
            assertEquals(1, runBlocking { provider.resourceLeaseRepository().findAllActive() }.size)
        }

    @Test
    fun `S16 the history and list routes are readable with the read capability alone`() =
        withRoutes {
            lease("readable")
            val listed = client.getAs("/api/v1/resources/leases", TEST_TOKEN)
            val history = client.getAs("/api/v1/resources/leases/history", TEST_TOKEN)
            assertEquals(200, listed.status.value)
            assertEquals(200, history.status.value)
            assertTrue(listed.envelope().str("ok").toBoolean())
            assertTrue(history.envelope().str("ok").toBoolean())
        }
}
