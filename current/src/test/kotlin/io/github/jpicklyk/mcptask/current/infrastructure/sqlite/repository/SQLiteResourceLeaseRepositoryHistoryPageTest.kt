package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.PageRequest
import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.ByteBuffer
import java.sql.DriverManager
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Independent tests (test-author seat, item a74bc60a) for the page contract of the two lease-history reads on the store,
 * `findRecentIntervals` and `findHoldersAt`, plus `ClaimService.leaseHistory` over them: scenario S4 and the store-level
 * halves of S5 to S7 of the frozen test-plan.
 *
 * Oracles (never the implementation): the pre-existing `ListPage` KDoc (U22: the total is the full count of ALL matching rows
 * on every page, whatever offset and limit the page asks for); w6-brief 3.4 / task-scope (order `(acquiredAt DESC, id ASC)`,
 * the page is an offset and a limit over that order, the default page is `PageRequest()` = limit 50); the api-rest.md held-at
 * rule `acquiredAt <= at < coalesce(releasedAt, expiresAt)`; and the V16 DDL (history rows carry no foreign key, ids are 16
 * big-endian bytes, so BLOB order equals canonical lowercase UUID string order).
 */
class SQLiteResourceLeaseRepositoryHistoryPageTest {
    private val t0: Instant = Instant.parse("2026-10-10T12:00:00Z")
    private val clock = SettableClock(t0)

    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod(clock = clock)

    private val provider get() = sqliteDb.repositoryProvider()
    private val leases get() = provider.resourceLeaseRepository()

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
        DriverManager.getConnection(sqliteDb.jdbcUrl).use { conn ->
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

    private suspend fun newHolder(title: String): UUID = provider.workItemRepository().create(WorkItem(title = title, depth = 0)).id

    /**
     * Real lease traffic on [key]: three holders in turn, each acquiring 10 s after the previous one released; returns the
     * holders oldest first. History then holds three intervals acquired at t0, t0+10 and t0+20.
     */
    private suspend fun threeRealHolders(key: String): List<UUID> {
        val holders = listOf("h1", "h2", "h3").map { newHolder(it) }
        holders.forEachIndexed { i, h ->
            assertIs<LeaseAcquireResult.Success>(leases.acquireAll(h, "actor-$i", listOf(key to 300)), "fixture: acquire $key #$i")
            clock.advanceSeconds(5)
            assertIs<LeaseReleaseResult.Success>(leases.releaseAllForItem(h), "fixture: release $key #$i")
            clock.advanceSeconds(5)
        }
        return holders
    }

    // ---------------------------------------------------------------- S4 findRecentIntervals

    @Test
    fun `S4 findRecentIntervals pages rows 2 and 3 newest first with the total of all rows of the key`(): Unit =
        runBlocking {
            val (h1, h2, h3) = threeRealHolders("k")
            val other = newHolder("other")
            assertIs<LeaseAcquireResult.Success>(leases.acquireAll(other, "actor-o", listOf("other-key" to 300)))

            val page = leases.findRecentIntervals("k", PageRequest(limit = 2, offset = 1))

            assertEquals(listOf(h2, h1), page.items.map { it.holderItemId }, "rows 2..3 of the newest-first order")
            assertEquals(3, page.total, "total counts all k intervals, not the page and not other keys")

            val first = leases.findRecentIntervals("k", PageRequest(limit = 2, offset = 0))
            assertEquals(listOf(h3, h2), first.items.map { it.holderItemId })
            assertEquals(3, first.total)

            val all = leases.findRecentIntervals(null, PageRequest(limit = 200))
            assertEquals(4, all.total, "no key counts every key")
            assertEquals(4, all.items.size)
            assertEquals(other, all.items.first().holderItemId, "the last acquired interval is newest")
        }

    @Test
    fun `S4 findRecentIntervals past the end is empty and keeps the total`(): Unit =
        runBlocking {
            threeRealHolders("k")

            val page = leases.findRecentIntervals("k", PageRequest(limit = 5, offset = 3))

            assertEquals(emptyList(), page.items)
            assertEquals(3, page.total)
        }

    @Test
    fun `S7 findRecentIntervals on an empty table is empty with total 0`(): Unit =
        runBlocking {
            val page = leases.findRecentIntervals(null)

            assertEquals(emptyList(), page.items)
            assertEquals(0, page.total)
        }

    @Test
    fun `S4 findRecentIntervals default page is limit 50 and still reports the full total`(): Unit =
        runBlocking {
            seed((1..51).map { n -> Row(UUID(0L, n.toLong()), holder(n.toLong()), "bulk", t0.plusSeconds(n.toLong())) })

            val page = leases.findRecentIntervals("bulk")

            assertEquals(50, page.items.size)
            assertEquals(51, page.total)
            assertEquals(holder(51), page.items.first().holderItemId)
        }

    // ---------------------------------------------------------------- S4 findHoldersAt

    private fun seedHeldAt(): Instant {
        val at = t0.plusSeconds(1000)
        seed(
            listOf(
                Row(UUID(0L, 1), holder(1), "k", at.minusSeconds(300)),
                Row(UUID(0L, 2), holder(2), "k", at.minusSeconds(200)),
                Row(UUID(0L, 3), holder(3), "k", at.minusSeconds(100)),
                Row(UUID(0L, 4), holder(4), "k", at.minusSeconds(500), releasedAt = at.minusSeconds(50)),
                Row(UUID(0L, 5), holder(5), "k", at.plusSeconds(10)),
                Row(UUID(0L, 6), holder(6), "other", at.minusSeconds(150))
            )
        )
        return at
    }

    @Test
    fun `S4 findHoldersAt pages the held rows newest first with a total that ignores the page and the unheld rows`(): Unit =
        runBlocking {
            val at = seedHeldAt()

            val first = leases.findHoldersAt("k", at, PageRequest(limit = 2, offset = 0))
            assertEquals(listOf(holder(3), holder(2)), first.items.map { it.holderItemId })
            assertEquals(3, first.total)

            val second = leases.findHoldersAt("k", at, PageRequest(limit = 2, offset = 1))
            assertEquals(listOf(holder(2), holder(1)), second.items.map { it.holderItemId })
            assertEquals(3, second.total)

            val last = leases.findHoldersAt("k", at, PageRequest(limit = 2, offset = 2))
            assertEquals(listOf(holder(1)), last.items.map { it.holderItemId })
            assertEquals(3, last.total)

            val beyond = leases.findHoldersAt("k", at, PageRequest(limit = 2, offset = 3))
            assertEquals(emptyList(), beyond.items)
            assertEquals(3, beyond.total)
        }

    @Test
    fun `S4 findHoldersAt without a key counts the held rows of every key`(): Unit =
        runBlocking {
            val at = seedHeldAt()

            val page = leases.findHoldersAt(null, at, PageRequest(limit = 2))

            assertEquals(listOf(holder(3), holder(6)), page.items.map { it.holderItemId })
            assertEquals(4, page.total)
        }

    // ---------------------------------------------------------------- S5 tie order

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
            ).map(UUID::fromString) // inserted scrambled: neither ascending nor descending
        val tied = tieIds.mapIndexed { i, id -> Row(id, holder(100L + i), "tie", t0.plusSeconds(500)) }
        val older = Row(UUID.fromString("00000000-0000-0000-0000-000000000000"), holder(999), "tie", t0.plusSeconds(100))
        seed(tied + older)
        return tied.sortedBy { it.id.toString() }.map { it.holder } + older.holder
    }

    @Test
    fun `S5 equal acquiredAt orders by id ascending and offset pages partition the rows exactly`(): Unit =
        runBlocking {
            val expected = seedTies()

            val pages = (0 until 9 step 4).map { offset -> leases.findRecentIntervals("tie", PageRequest(limit = 4, offset = offset)) }

            assertEquals(listOf(4, 4, 1), pages.map { it.items.size })
            assertEquals(listOf(9, 9, 9), pages.map { it.total })
            assertEquals(expected, pages.flatMap { it.items }.map { it.holderItemId })
            assertEquals(expected, leases.findRecentIntervals("tie", PageRequest(limit = 200)).items.map { it.holderItemId })
        }

    @Test
    fun `S5 findHoldersAt applies the same tie order and exact partition`(): Unit =
        runBlocking {
            val expected = seedTies()
            val at = t0.plusSeconds(600)

            val pages = (0 until 9 step 4).map { offset -> leases.findHoldersAt("tie", at, PageRequest(limit = 4, offset = offset)) }

            assertEquals(listOf(9, 9, 9), pages.map { it.total })
            assertEquals(expected, pages.flatMap { it.items }.map { it.holderItemId })
        }

    // ---------------------------------------------------------------- ClaimService.leaseHistory

    @Test
    fun `S4 ClaimService leaseHistory returns the store page for the recent and the held-at forms`(): Unit =
        runBlocking {
            val at = seedHeldAt()
            val service = ClaimService(provider, sqliteDb.unitOfWork(clock))

            val recent = service.leaseHistory("k", null, PageRequest(limit = 2, offset = 1))
            val held = service.leaseHistory("k", at, PageRequest(limit = 2, offset = 1))

            assertEquals(listOf(holder(3), holder(2)), recent.items.map { it.holderItemId }, "recent page: rows 2..3 of k newest first")
            assertEquals(5, recent.total, "recent form counts every k interval")
            assertEquals(listOf(holder(2), holder(1)), held.items.map { it.holderItemId })
            assertEquals(3, held.total, "held-at form counts only intervals held at `at`")
        }
}
