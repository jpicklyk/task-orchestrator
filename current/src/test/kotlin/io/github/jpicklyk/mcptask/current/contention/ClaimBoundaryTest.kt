package io.github.jpicklyk.mcptask.current.contention

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.service.ok
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.P11_LEASE_KEY
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The exact claim and lease expiry boundary ABOVE the store (item 36c719db, closes AR-50): the ancestor-claim filter, the
 * advance ownership gate, the advance lease gate and get_context `isExpired`, each at E - 1 ms (still held) and E (expired).
 * The store-level boundary is pinned by P7ClaimClockStoreTest (S4) and P7LeaseBoundaryTest (S10).
 *
 * Oracle: a claim or lease is active while its expiry is strictly after the unit instant, so an instant equal to the expiry
 * is already expired (ClaimState.isActive). The clock is frozen at [WAL_T]; a claim of ttl 60 expires at E = T + 60 s, the
 * p11-db lease (ttl 600) at T + 600 s. No test sleeps: the clock is moved.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ClaimBoundaryTest {
    private val clock = SettableClock(WAL_T)

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private val claimExpiry: Instant = WAL_T.plusSeconds(CLAIM_TTL.toLong())
    private val leaseExpiry: Instant = WAL_T.plusSeconds(LEASE_TTL.toLong())

    private fun driver(dir: Path) = P11Driver(EventLogRig.build(sqlite.db, dir, P11_BASE_YAML, clock))

    private suspend fun P11Driver.claimFor(
        item: WorkItem,
        agent: String
    ) {
        assertIs<ClaimResult.Success>(
            rig.ctx.claimService
                .claim(item.id, agent, CLAIM_TTL)
                .ok()
        )
    }

    @Test
    fun `B1 the ancestor-claim filter keeps a child hidden until the parent claim expires at exactly E`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val parent = d.item("B1 parent", Role.QUEUE)
            val child = d.item("B1 child", Role.QUEUE, parent = parent)
            d.claimFor(parent, "agent-a")
            val claims = d.raw.claimStore()

            suspend fun claimableFor(agent: String?) = claims.findClaimable(Role.QUEUE, requestingAgentId = agent).map { it.id }

            clock.set(claimExpiry.minusMillis(1))
            assertFalse(child.id in claimableFor("agent-b"), "another agent's active ancestor claim hides the child at E - 1 ms")
            assertFalse(child.id in claimableFor(null), "with no requesting agent any active ancestor claim hides the child")
            assertTrue(child.id in claimableFor("agent-a"), "the ancestor holder still sees the child")

            clock.set(claimExpiry)
            assertTrue(child.id in claimableFor("agent-b"), "at exactly E the ancestor claim is expired")
            assertTrue(child.id in claimableFor(null))
            assertTrue(child.id in claimableFor("agent-a"))
        }

    @Test
    fun `B2 the advance ownership gate refuses another actor at E minus 1 ms and admits it at exactly E`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("B2", Role.QUEUE, type = "p11-optional")
            d.claimFor(item, "agent-a")

            clock.set(claimExpiry.minusMillis(1))
            val before = d.advance(item, "start", actor = "agent-b")
            assertEquals(false, before.flag("applied"), "$before")
            assertEquals("not_claim_holder", before.text("errorCode"), "$before")
            assertEquals(Role.QUEUE, d.role(item), "role unchanged by the refused start")

            clock.set(claimExpiry)
            val at = d.advance(item, "start", actor = "agent-b")
            assertEquals(true, at.flag("applied"), "an expired claim no longer gates: $at")
            assertEquals(Role.WORK, d.role(item))
        }

    @Test
    fun `B3 the advance lease gate rejects at E minus 1 ms with retryAfterMs 1 and admits at exactly E`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val holder = d.item("B3 holder", Role.QUEUE)
            d.holdLease(holder)
            val contender = d.item("B3 contender", Role.QUEUE, type = "p11-leased")

            clock.set(leaseExpiry.minusMillis(1))
            val before = d.advance(contender, "start")
            assertEquals("resource_unavailable", before.text("errorCode"), "$before")
            assertEquals("1", before.text("retryAfterMs"), "$before")
            assertEquals(Role.QUEUE, d.role(contender))

            clock.set(leaseExpiry)
            val at = d.advance(contender, "start")
            assertEquals(true, at.flag("applied"), "an expired lease no longer gates: $at")
            assertEquals(Role.WORK, d.role(contender))
            val intervals = d.raw.resourceLeaseRepository().findRecentIntervals(P11_LEASE_KEY, 10)
            val closed = intervals.single { it.holderItemId == holder.id }
            assertEquals("expired", closed.releaseReason)
            assertEquals(leaseExpiry, closed.releasedAt, "the holder interval closes at its own expiry")
            assertEquals(null, intervals.single { it.holderItemId == contender.id }.releasedAt, "the new holder interval is open")
        }

    @Test
    fun `B4 get_context reports isExpired false at E minus 1 ms and true at exactly E`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val item = d.item("B4", Role.QUEUE)
            d.claimFor(item, "agent-a")

            suspend fun isExpired(): Boolean? {
                val response = d.rig.callOk(GetContextTool(), "itemId" to JsonPrimitive(item.id.toString()))
                val detail = response["data"]!!.jsonObject["claimDetail"]!!.jsonObject
                return detail.flag("isExpired")
            }

            clock.set(claimExpiry.minusMillis(1))
            assertEquals(false, isExpired())
            clock.set(claimExpiry)
            assertEquals(true, isExpired())
        }

    private companion object {
        const val CLAIM_TTL = 60
        const val LEASE_TTL = 600
    }
}
