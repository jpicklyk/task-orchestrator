package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import io.github.jpicklyk.mcptask.current.test.EnvelopeConformance
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.sql.DriverManager
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item a74bc60a) for the page contract of `GET /api/v1/resources/leases/history`:
 * scenarios S1 to S3 and S5 to S10 of the frozen test-plan, over a real SQLite database, a real [ClaimService] and the real
 * [resourceLeaseRoutes]. Rows are seeded straight into `resource_lease_history` (a table with no foreign key, pinned by
 * migration V16) so ids, instants and overlapping holders are exact and deterministic.
 *
 * Oracles (never the implementation):
 * - [U22] the pre-existing `ListPage` KDoc: `total` is the full count on EVERY page, never counted from the page's own offset;
 *   `truncated` is true exactly when `offset + items.size < total`; `next` is an opaque cursor present exactly when `truncated`.
 * - [P] w6-brief 3.4 / task-scope: history order is `(acquiredAt DESC, id ASC)` and the route accepts a `cursor`; `limit` default
 *   50, range 1..200; the held-at rule `acquiredAt <= at < coalesce(releasedAt, expiresAt)` from api-rest.md.
 * - [C] the pre-existing `CursorCodec` KDoc: the cursor is base64 of the exact bytes `{"offset":N}` (standard or URL-safe
 *   alphabet, padding optional); anything else, including an empty string, is `invalid_cursor` (HTTP 400).
 * - [B] interval ids are stored as 16 big-endian bytes (msb then lsb), so BLOB comparison equals canonical lowercase UUID
 *   string order; the expected tie order below is computed with `sortedBy { it.toString() }`, never read back.
 * - Field faults (`invalid_request`) are answered before a malformed or empty `cursor` (`invalid_cursor`) (route KDoc).
 *
 * Every expected cursor is recomputed here with `java.util.Base64`; the structural shape of every body is also checked by
 * [EnvelopeConformance].
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ResourceLeaseHistoryPageTest {
    private val t0: Instant = Instant.parse("2026-10-10T12:00:00Z")
    private val clock = SettableClock(t0)

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private fun claimService() = ClaimService(sqlite.db.repositoryProvider(), sqlite.db.unitOfWork(clock))

    private fun withRoutes(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { configureTestApp(routeBlock = { resourceLeaseRoutes(claimService()) }) }
            block()
        }

    // ---------------------------------------------------------------- fixtures

    /** One history row. [holder] is unique per row so a response item can be mapped back to its row. */
    private data class Row(
        val id: UUID,
        val holder: UUID,
        val key: String,
        val acquiredAt: Instant,
        val expiresAt: Instant = acquiredAt.plusSeconds(3600),
        val releasedAt: Instant? = null
    )

    private val canonical: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    private fun bytes(id: UUID): ByteArray =
        ByteBuffer
            .allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()

    private fun seed(rows: List<Row>) {
        DriverManager.getConnection(sqlite.jdbcUrl).use { conn ->
            conn.autoCommit = false
            conn
                .prepareStatement(
                    "INSERT INTO resource_lease_history " +
                        "(id, resource_key, holder_item_id, acquired_at, expires_at, released_at, release_reason) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    for (r in rows) {
                        ps.setBytes(1, bytes(r.id))
                        ps.setString(2, r.key)
                        ps.setBytes(3, bytes(r.holder))
                        ps.setString(4, canonical.format(r.acquiredAt))
                        ps.setString(5, canonical.format(r.expiresAt))
                        if (r.releasedAt == null) {
                            ps.setNull(6, Types.VARCHAR)
                            ps.setNull(7, Types.VARCHAR)
                        } else {
                            ps.setString(6, canonical.format(r.releasedAt))
                            ps.setString(7, "released")
                        }
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            conn.commit()
        }
    }

    private fun holder(n: Long) = UUID(1L, n)

    private fun b64(offset: Int): String =
        Base64.getEncoder().withoutPadding().encodeToString("""{"offset":$offset}""".toByteArray(Charsets.UTF_8))

    private fun b64Of(json: String): String = Base64.getEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun q(raw: String): String = URLEncoder.encode(raw, "UTF-8")

    private suspend fun HttpClient.getAs(
        path: String,
        token: String? = ADMIN_TOKEN
    ): HttpResponse =
        get(path) {
            if (token != null) header("Authorization", "Bearer $token")
        }

    private suspend fun HttpResponse.envelope(): JsonObject {
        val body = Json.parseToJsonElement(bodyAsText()).jsonObject
        // a cursor request is a continuation: `total` stays the full count while later pages hold fewer items than it
        EnvelopeConformance.assertConforms(body, "lease history page body", continuation = request.url.parameters.contains("cursor"))
        return body
    }

    private fun JsonObject.items(): List<JsonObject> =
        getValue("data")
            .jsonObject
            .getValue("items")
            .jsonArray
            .map { it.jsonObject }

    private fun JsonObject.holders(): List<UUID> = items().map { UUID.fromString(it.getValue("holderItemId").jsonPrimitive.content) }

    private fun JsonObject.page(): JsonObject = getValue("data").jsonObject.getValue("page").jsonObject

    private fun JsonObject.total(): Int =
        page()
            .getValue("total")
            .jsonPrimitive.content
            .toInt()

    private fun JsonObject.truncated(): Boolean =
        page()
            .getValue("truncated")
            .jsonPrimitive.content
            .toBoolean()

    private fun JsonObject.next(): String? = page()["next"]?.jsonPrimitive?.content

    private fun JsonObject.errorCode(): String =
        getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content

    private fun JsonObject.singleViolationField(): String =
        getValue("error")
            .jsonObject
            .getValue("detail")
            .jsonObject
            .getValue("fields")
            .jsonArray
            .single()
            .jsonObject
            .getValue("field")
            .jsonPrimitive.content

    /** Follows `next` from the first page until it disappears; fails if it never does. Returns every page body. */
    private suspend fun ApplicationTestBuilder.walk(
        base: String,
        limit: Int
    ): List<JsonObject> {
        val pages = mutableListOf<JsonObject>()
        var cursor: String? = null
        do {
            val url = "$base${if ('?' in base) "&" else "?"}limit=$limit" + (cursor?.let { "&cursor=${q(it)}" } ?: "")
            val body = client.getAs(url).envelope()
            pages += body
            cursor = body.next()
        } while (cursor != null && pages.size < 100)
        assertNull(cursor, "the cursor chain must terminate")
        return pages
    }

    /** Three open intervals on [key], acquired 10 s apart starting at t0, ids and holders numbered by age (1 = oldest). */
    private fun seedThree(key: String = "k-lock"): List<Row> {
        val rows = (1..3).map { n -> Row(UUID(0L, n.toLong()), holder(n.toLong()), key, t0.plusSeconds(10L * n)) }
        seed(rows)
        return rows
    }

    // ---------------------------------------------------------------- S1 / S2 happy

    @Test
    fun `S1 limit 1 over three intervals answers the newest, the full total, truncated true and a next cursor`(): Unit =
        withRoutes {
            seedThree()

            val response = client.getAs("/api/v1/resources/leases/history?limit=1")

            assertEquals(200, response.status.value)
            val body = response.envelope()
            assertEquals(listOf(holder(3)), body.holders(), "newest interval first")
            assertEquals(3, body.total(), "total is the full matching count, not the page size")
            assertTrue(body.truncated())
            assertEquals(b64(1), body.next(), "next is the cursor of offset + items.size")
        }

    @Test
    fun `S2 a cursor walk of limit 2 yields every interval once newest first and total 3 on every page`(): Unit =
        withRoutes {
            seedThree()

            val first = client.getAs("/api/v1/resources/leases/history?limit=2").envelope()
            assertEquals(listOf(holder(3), holder(2)), first.holders())
            assertEquals(3, first.total())
            assertTrue(first.truncated())
            assertEquals(b64(2), first.next())

            val second = client.getAs("/api/v1/resources/leases/history?limit=2&cursor=${q(first.next()!!)}").envelope()
            assertEquals(listOf(holder(1)), second.holders())
            assertEquals(3, second.total(), "total is not counted from the page's own offset")
            assertFalse(second.truncated())
            assertNull(second.next(), "no next on the last page")
            assertFalse("next" in second.page())
        }

    @Test
    fun `S2 the default limit is 50 and the 51st interval is reached through next`(): Unit =
        withRoutes {
            seed((1..51).map { n -> Row(UUID(0L, n.toLong()), holder(n.toLong()), "bulk", t0.plusSeconds(n.toLong())) })

            val first = client.getAs("/api/v1/resources/leases/history").envelope()

            assertEquals(50, first.items().size)
            assertEquals(51, first.total())
            assertTrue(first.truncated())
            assertEquals(b64(50), first.next())
            val second = client.getAs("/api/v1/resources/leases/history?cursor=${q(first.next()!!)}").envelope()
            assertEquals(listOf(holder(1)), second.holders(), "the oldest interval is the 51st")
            assertEquals(51, second.total())
            assertFalse(second.truncated())
        }

    // ---------------------------------------------------------------- S3 key and at filters

    private fun seedHeldAt(
        key: String,
        other: String
    ): Instant {
        val at = t0.plusSeconds(1000)
        seed(
            listOf(
                // held at `at` on key: acquired before, still open
                Row(UUID(0L, 1), holder(1), key, at.minusSeconds(300)),
                Row(UUID(0L, 2), holder(2), key, at.minusSeconds(200)),
                Row(UUID(0L, 3), holder(3), key, at.minusSeconds(100)),
                // on key but NOT held at `at`: released before it
                Row(UUID(0L, 4), holder(4), key, at.minusSeconds(500), releasedAt = at.minusSeconds(50)),
                // on key but NOT held at `at`: acquired after it
                Row(UUID(0L, 5), holder(5), key, at.plusSeconds(10)),
                // held at `at` on another key
                Row(UUID(0L, 6), holder(6), other, at.minusSeconds(150))
            )
        )
        return at
    }

    @Test
    fun `S3 key and at with limit 2 answers two of the three holders, total 3 counting only held rows of that key`(): Unit =
        withRoutes {
            val at = seedHeldAt("k-lock", "other-lock")

            val first = client.getAs("/api/v1/resources/leases/history?key=k-lock&at=$at&limit=2").envelope()

            assertEquals(listOf(holder(3), holder(2)), first.holders())
            assertEquals(3, first.total(), "total counts the three held intervals of k-lock, not the released, future or other-key ones")
            assertTrue(first.truncated())
            assertEquals(b64(2), first.next())

            val second = client.getAs("/api/v1/resources/leases/history?key=k-lock&at=$at&limit=2&cursor=${q(first.next()!!)}").envelope()
            assertEquals(listOf(holder(1)), second.holders())
            assertEquals(3, second.total())
            assertFalse(second.truncated())
        }

    @Test
    fun `S3 at without key counts the held rows of every key`(): Unit =
        withRoutes {
            val at = seedHeldAt("k-lock", "other-lock")

            val body = client.getAs("/api/v1/resources/leases/history?at=$at&limit=2").envelope()

            assertEquals(listOf(holder(3), holder(6)), body.holders(), "newest acquiredAt first across keys")
            assertEquals(4, body.total(), "three on k-lock plus one on other-lock")
            assertTrue(body.truncated())
        }

    @Test
    fun `S3 key without at counts every interval of that key, open or closed`(): Unit =
        withRoutes {
            seedHeldAt("k-lock", "other-lock")

            val body = client.getAs("/api/v1/resources/leases/history?key=k-lock&limit=1").envelope()

            assertEquals(5, body.total(), "five intervals on k-lock regardless of at")
            assertEquals(listOf(holder(5)), body.holders(), "the interval acquired after the others is newest")
            assertTrue(body.truncated())
        }

    // ---------------------------------------------------------------- S5 tie order

    /** Eight intervals acquired at the SAME instant with ids that straddle the sign bit, plus one older interval. */
    private fun seedTies(): List<UUID> {
        val tieIds =
            listOf(
                "ffff0000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000009",
                "80000000-0000-0000-0000-000000000002",
                "7fffffff-ffff-ffff-ffff-ffffffffffff",
                "00000000-0000-0000-0000-000000000003",
                "c0000000-0000-0000-0000-000000000004",
                "00000000-0000-0000-0000-00000000000a",
                "ffff0000-0000-0000-0000-000000000000"
            ).map(UUID::fromString) // inserted in this scrambled order, which is neither ascending nor descending
        val tied = tieIds.mapIndexed { i, id -> Row(id, holder(100L + i), "tie-lock", t0.plusSeconds(500)) }
        val older = Row(UUID.fromString("00000000-0000-0000-0000-000000000000"), holder(999), "tie-lock", t0.plusSeconds(100))
        seed(tied + older)
        return tied.sortedBy { it.id.toString() }.map { it.holder } + older.holder
    }

    @Test
    fun `S5 equal acquiredAt breaks ties by id ascending and the walk visits each interval exactly once`(): Unit =
        withRoutes {
            val expected = seedTies()

            val pages = walk("/api/v1/resources/leases/history", limit = 4)

            assertEquals(listOf(4, 4, 1), pages.map { it.items().size })
            assertEquals(expected, pages.flatMap { it.holders() }, "ids ascending within the tie, then the older interval")
            assertEquals(expected.size, pages.flatMap { it.holders() }.toSet().size, "each interval exactly once")
            assertEquals(listOf(9, 9, 9), pages.map { it.total() })
            assertEquals(listOf(true, true, false), pages.map { it.truncated() })
        }

    @Test
    fun `S5 a repeated request returns the same order`(): Unit =
        withRoutes {
            val expected = seedTies()

            val a = client.getAs("/api/v1/resources/leases/history?limit=200").envelope().holders()
            val b = client.getAs("/api/v1/resources/leases/history?limit=200").envelope().holders()

            assertEquals(expected, a)
            assertEquals(a, b)
        }

    @Test
    fun `S5 the tie order holds under a key filter and an at filter too`(): Unit =
        withRoutes {
            val expected = seedTies()
            val at = t0.plusSeconds(600)

            val keyed = walk("/api/v1/resources/leases/history?key=tie-lock", limit = 3).flatMap { it.holders() }
            val held = walk("/api/v1/resources/leases/history?key=tie-lock&at=$at", limit = 3).flatMap { it.holders() }

            assertEquals(expected, keyed)
            assertEquals(expected, held, "every interval is open at `at`, so the same order applies")
        }

    // ---------------------------------------------------------------- S6 / S7 / S8 edges

    @Test
    fun `S6 a cursor past the end is an ok empty page that keeps the total`(): Unit =
        withRoutes {
            seedThree()

            val response = client.getAs("/api/v1/resources/leases/history?cursor=${q(b64(10))}")

            assertEquals(200, response.status.value)
            val body = response.envelope()
            assertEquals(emptyList(), body.items())
            assertEquals(3, body.total())
            assertFalse(body.truncated())
            assertNull(body.next())
        }

    @Test
    fun `S6 a cursor exactly at the total is an empty last page`(): Unit =
        withRoutes {
            seedThree()

            val body = client.getAs("/api/v1/resources/leases/history?cursor=${q(b64(3))}").envelope()

            assertEquals(emptyList(), body.items())
            assertEquals(3, body.total())
            assertFalse(body.truncated())
        }

    @Test
    fun `S7 an empty table is an ok empty page with total 0`(): Unit =
        withRoutes {
            val response = client.getAs("/api/v1/resources/leases/history")

            assertEquals(200, response.status.value)
            val body = response.envelope()
            assertEquals(emptyList(), body.items())
            assertEquals(0, body.total())
            assertFalse(body.truncated())
            assertNull(body.next())
        }

    @Test
    fun `S8 201 intervals with limit 200 are truncated with total 201 and the last one is on the next page`(): Unit =
        withRoutes {
            seed((1..201).map { n -> Row(UUID(0L, n.toLong()), holder(n.toLong()), "bulk", t0.plusSeconds(n.toLong())) })

            val first = client.getAs("/api/v1/resources/leases/history?limit=200").envelope()

            assertEquals(200, first.items().size)
            assertEquals(201, first.total())
            assertTrue(first.truncated())
            assertEquals(b64(200), first.next())
            assertEquals(holder(201), first.holders().first())
            assertEquals(holder(2), first.holders().last())

            val second = client.getAs("/api/v1/resources/leases/history?limit=200&cursor=${q(first.next()!!)}").envelope()
            assertEquals(listOf(holder(1)), second.holders())
            assertEquals(201, second.total())
            assertFalse(second.truncated())
            assertNull(second.next())
        }

    // ---------------------------------------------------------------- S9 / S10 failures

    @Test
    fun `S9 an undecodable cursor is 400 invalid_cursor`(): Unit =
        withRoutes {
            seedThree()
            val bad =
                mapOf(
                    "garbage" to q("!!!not base64!!!"),
                    "empty" to "",
                    "negative offset" to q(b64Of("""{"offset":-1}""")),
                    "extra member" to q(b64Of("""{"offset":1,"x":2}""")),
                    "string offset" to q(b64Of("""{"offset":"1"}""")),
                    "fractional offset" to q(b64Of("""{"offset":1.5}""")),
                    "beyond Int" to q(b64Of("""{"offset":2147483648}""")),
                    "not an object" to q(b64Of("[1]"))
                )
            for ((label, cursor) in bad) {
                val response = client.getAs("/api/v1/resources/leases/history?cursor=$cursor")
                assertEquals(400, response.status.value, "cursor case '$label'")
                assertEquals("invalid_cursor", response.envelope().errorCode(), "cursor case '$label'")
            }
        }

    @Test
    fun `S9 probe an absent cursor is offset 0 while an empty one is invalid_cursor`(): Unit =
        withRoutes {
            seedThree()

            val absent = client.getAs("/api/v1/resources/leases/history?limit=1")
            val empty = client.getAs("/api/v1/resources/leases/history?limit=1&cursor=")

            assertEquals(200, absent.status.value)
            assertEquals(listOf(holder(3)), absent.envelope().holders())
            assertEquals(400, empty.status.value)
            assertEquals("invalid_cursor", empty.envelope().errorCode())
        }

    @Test
    fun `S10 a bad limit is answered as invalid_request on limit before a bad cursor`(): Unit =
        withRoutes {
            seedThree()

            val response = client.getAs("/api/v1/resources/leases/history?limit=0&cursor=${q("!!!")}")

            assertEquals(400, response.status.value)
            val body = response.envelope()
            assertEquals("invalid_request", body.errorCode())
            assertEquals("limit", body.singleViolationField())
        }

    @Test
    fun `S10 a bad key or at is answered as invalid_request on that field before a bad cursor`(): Unit =
        withRoutes {
            seedThree()

            val badKey = client.getAs("/api/v1/resources/leases/history?key=Invalid_Key!&cursor=${q("!!!")}")
            assertEquals(400, badKey.status.value)
            val badKeyBody = badKey.envelope()
            assertEquals("invalid_request", badKeyBody.errorCode())
            assertEquals("key", badKeyBody.singleViolationField())

            val badAt = client.getAs("/api/v1/resources/leases/history?at=yesterday&cursor=${q("!!!")}")
            assertEquals(400, badAt.status.value)
            val badAtBody = badAt.envelope()
            assertEquals("invalid_request", badAtBody.errorCode())
            assertEquals("at", badAtBody.singleViolationField())
        }

    // ---------------------------------------------------------------- probes

    @Test
    fun `probe padded, URL-safe and unpadded encodings of the same cursor select the same page`(): Unit =
        withRoutes {
            seed((1..12).map { n -> Row(UUID(0L, n.toLong()), holder(n.toLong()), "bulk", t0.plusSeconds(n.toLong())) })
            val raw = """{"offset":10}""".toByteArray(Charsets.UTF_8) // 13 bytes, so the padded forms end in '=='
            val encodings =
                mapOf(
                    "standard unpadded" to Base64.getEncoder().withoutPadding().encodeToString(raw),
                    "standard padded" to Base64.getEncoder().encodeToString(raw),
                    "url-safe unpadded" to Base64.getUrlEncoder().withoutPadding().encodeToString(raw),
                    "url-safe padded" to Base64.getUrlEncoder().encodeToString(raw)
                )
            assertTrue(encodings.getValue("standard padded").endsWith("=="), "fixture: the padded form really is padded")

            for ((label, cursor) in encodings) {
                val response = client.getAs("/api/v1/resources/leases/history?limit=5&cursor=${q(cursor)}")
                assertEquals(200, response.status.value, label)
                val body = response.envelope()
                assertEquals(listOf(holder(2), holder(1)), body.holders(), label)
                assertEquals(12, body.total(), label)
                assertFalse(body.truncated(), label)
            }
        }

    @Test
    fun `probe a cursor from one key applies as a plain offset to another key without error`(): Unit =
        withRoutes {
            seed(
                (1..3).map { n -> Row(UUID(0L, n.toLong()), holder(n.toLong()), "key-a", t0.plusSeconds(n.toLong())) } +
                    (4..6).map { n -> Row(UUID(0L, n.toLong()), holder(n.toLong()), "key-b", t0.plusSeconds(n.toLong())) }
            )
            val fromA = client.getAs("/api/v1/resources/leases/history?key=key-a&limit=2").envelope()
            assertEquals(b64(2), fromA.next())

            val onB = client.getAs("/api/v1/resources/leases/history?key=key-b&limit=2&cursor=${q(fromA.next()!!)}")

            assertEquals(200, onB.status.value, "the cursor is offset-only, not bound to its query")
            val body = onB.envelope()
            assertEquals(listOf(holder(4)), body.holders(), "offset 2 into key-b newest first is its oldest interval")
            assertEquals(3, body.total())
        }

    @Test
    fun `probe a non-admin reader sees the same total, truncated and next`(): Unit =
        withRoutes {
            seedThree()

            val body = client.getAs("/api/v1/resources/leases/history?limit=1", TEST_TOKEN).envelope()

            assertEquals(3, body.total())
            assertTrue(body.truncated())
            assertEquals(b64(1), body.next())
            assertEquals(listOf(holder(3)), body.holders())
        }

    @Test
    fun `probe duplicate cursor parameters still answer a conforming envelope whose ok flag agrees with the status`(): Unit =
        withRoutes {
            seedThree()

            val response = client.getAs("/api/v1/resources/leases/history?limit=1&cursor=${q(b64(1))}&cursor=${q(b64(2))}")

            val body = response.envelope()
            assertEquals(
                response.status.value == 200,
                body
                    .getValue("ok")
                    .jsonPrimitive.content
                    .toBoolean()
            )
        }
}
