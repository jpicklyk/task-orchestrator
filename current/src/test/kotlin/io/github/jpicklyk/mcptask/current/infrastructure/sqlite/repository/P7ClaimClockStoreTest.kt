package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.application.port.ReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.SelectorMatchCounts
import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.AdvanceService
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.ClaimStatus
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7 (item beeef6f7): claim SQL bound to the injected clock, the single claim predicate across every surface,
 * claim columns out of update(), and claims that do not bump the item version.
 *
 * Fixture: a real SQLite database whose stores are bound to a [SettableClock] parked at T = 2001-02-03 04:05:06.789Z,
 * deliberately far from wall-clock time so an implementation that still asks the database or the JVM for "now"
 * produces a visibly different value. Stored text is read back with raw SQL on a separate connection.
 *
 * Oracles (frozen test-plan): plan 3.12 l.350-363, AR-43/45/49/50 recommendations, task-scope D1 (canonical UTC text
 * `yyyy-MM-dd HH:mm:ss.SSS`), D5 (an instant equal to the expiry is expired), D6/D7 (update() never writes claim
 * columns; claims never bump version), AlreadyClaimed.retryAfterMs = expiresAt - now (always >= 1).
 * Labels: S1, S3-surfaces (S4), S6 NEW-SURFACE-or-existing as marked per test; S7 and S9 EXISTING-SURFACE.
 */
class P7ClaimClockStoreTest {
    private val t: Instant = Instant.parse("2001-02-03T04:05:06.789Z")
    private val clock = SettableClock(t)

    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod(clock = clock)

    private val provider get() = sqliteDb.repositoryProvider()
    private val items get() = provider.itemStore()
    private val claims get() = provider.claimStore()
    private val jdbc get() = sqliteDb.jdbcUrl

    private suspend fun newItem(
        title: String = "Item",
        role: Role = Role.QUEUE
    ): WorkItem = items.create(WorkItem(title = title, role = role))

    private data class RawClaim(
        val claimedBy: String?,
        val claimedAt: String?,
        val expiresAt: String?,
        val originalClaimedAt: String?,
        val version: Long
    )

    private fun raw(id: UUID): RawClaim =
        P7Raw
            .query(
                jdbc,
                "SELECT claimed_by, claimed_at, claim_expires_at, original_claimed_at, version FROM work_items WHERE id = ?",
                id
            ) { RawClaim(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getLong(5)) }
            .single()

    // ---- S1: claim stamps come from the bound clock, in canonical UTC text ------------------------------------

    @Test
    fun `S1 a claim at T with ttl 60 stores canonical UTC text derived from the bound clock`(): Unit =
        runBlocking {
            val item = newItem()

            val result = claims.claim(item.id, "agent-a", 60)

            val success = assertIs<ClaimResult.Success>(result)
            val row = raw(item.id)
            assertEquals("agent-a", row.claimedBy)
            assertEquals("2001-02-03 04:05:06.789", row.claimedAt, "claimed_at must be the bound clock's instant")
            assertEquals("2001-02-03 04:06:06.789", row.expiresAt, "claim_expires_at must be T + 60s")
            assertEquals("2001-02-03 04:05:06.789", row.originalClaimedAt)
            assertEquals(t, success.item.claimedAt)
            assertEquals(t.plusSeconds(60), success.item.claimExpiresAt)
            assertEquals(t, success.item.originalClaimedAt)
        }

    @Test
    fun `S1 probe a same-agent refresh keeps originalClaimedAt and moves claimedAt and expiry with the clock`(): Unit =
        runBlocking {
            val item = newItem()
            assertIs<ClaimResult.Success>(claims.claim(item.id, "agent-a", 60))

            clock.advanceSeconds(10)
            val refreshed = claims.claim(item.id, "agent-a", 60)

            assertIs<ClaimResult.Success>(refreshed)
            val row = raw(item.id)
            assertEquals("2001-02-03 04:05:16.789", row.claimedAt)
            assertEquals("2001-02-03 04:06:16.789", row.expiresAt)
            assertEquals("2001-02-03 04:05:06.789", row.originalClaimedAt, "the first claim instant must be preserved")
        }

    @Test
    fun `S1 probe replaying the identical claim at the same instant is idempotent`(): Unit =
        runBlocking {
            val item = newItem()
            assertIs<ClaimResult.Success>(claims.claim(item.id, "agent-a", 60))
            val first = raw(item.id)

            assertIs<ClaimResult.Success>(claims.claim(item.id, "agent-a", 60))

            assertEquals(first, raw(item.id))
        }

    // ---- S4: every claim surface agrees on the E-1ms / E boundary --------------------------------------------

    @Test
    fun `S4 at E minus 1ms the claim is active on every surface and a competing claim is refused with retryAfter 1`(): Unit =
        runBlocking {
            val claimed = newItem("Claimed")
            val free = newItem("Free")
            assertIs<ClaimResult.Success>(claims.claim(claimed.id, "agent-a", 60))
            val e = t.plusSeconds(60)
            clock.set(e.minusMillis(1))

            val refused = claims.claim(claimed.id, "agent-b", 60)
            val alreadyClaimed = assertIs<ClaimResult.AlreadyClaimed>(refused)
            assertEquals(claimed.id, alreadyClaimed.itemId)
            assertEquals(1L, alreadyClaimed.retryAfterMs, "retryAfterMs is expiresAt - now = 1ms")

            assertEquals(
                setOf(free.id),
                claims.findClaimable(Role.QUEUE, requestingAgentId = "agent-b").map { it.id }.toSet(),
                "findClaimable must exclude the actively claimed item"
            )
            assertEquals(
                setOf(free.id),
                claims.findForNextItem(Role.QUEUE, excludeActiveClaims = true).map { it.id }.toSet(),
                "findForNextItem must exclude the actively claimed item"
            )
            assertEquals(ClaimStatusCounts(active = 1, expired = 0, unclaimed = 1), claims.countByClaimStatus())
            assertEquals(
                SelectorMatchCounts(matched = 2, activelyClaimed = 1),
                claims.countSelectorMatches(Role.QUEUE)
            )
            assertEquals(listOf(claimed.id), items.findByFilters(claimStatus = ClaimStatus.CLAIMED).items.map { it.id })
            assertEquals(emptyList(), items.findByFilters(claimStatus = ClaimStatus.EXPIRED).items.map { it.id })
            assertEquals(listOf(free.id), items.findByFilters(claimStatus = ClaimStatus.UNCLAIMED).items.map { it.id })
            assertEquals(1, items.countByFilters(claimStatus = ClaimStatus.CLAIMED))
            assertEquals(0, items.countByFilters(claimStatus = ClaimStatus.EXPIRED))
        }

    @Test
    fun `S4 at E exactly the claim is expired on every surface and a competing claim succeeds`(): Unit =
        runBlocking {
            val claimed = newItem("Claimed")
            val free = newItem("Free")
            assertIs<ClaimResult.Success>(claims.claim(claimed.id, "agent-a", 60))
            val e = t.plusSeconds(60)
            clock.set(e)

            assertEquals(
                setOf(claimed.id, free.id),
                claims.findClaimable(Role.QUEUE, requestingAgentId = "agent-b").map { it.id }.toSet(),
                "an instant equal to the expiry is expired, so the item is claimable again"
            )
            assertEquals(
                setOf(claimed.id, free.id),
                claims.findForNextItem(Role.QUEUE, excludeActiveClaims = true).map { it.id }.toSet()
            )
            assertEquals(ClaimStatusCounts(active = 0, expired = 1, unclaimed = 1), claims.countByClaimStatus())
            assertEquals(
                SelectorMatchCounts(matched = 2, activelyClaimed = 0),
                claims.countSelectorMatches(Role.QUEUE)
            )
            assertEquals(emptyList(), items.findByFilters(claimStatus = ClaimStatus.CLAIMED).items.map { it.id })
            assertEquals(listOf(claimed.id), items.findByFilters(claimStatus = ClaimStatus.EXPIRED).items.map { it.id })
            assertEquals(listOf(free.id), items.findByFilters(claimStatus = ClaimStatus.UNCLAIMED).items.map { it.id })
            assertEquals(0, items.countByFilters(claimStatus = ClaimStatus.CLAIMED))
            assertEquals(1, items.countByFilters(claimStatus = ClaimStatus.EXPIRED))

            val taken = claims.claim(claimed.id, "agent-b", 60)
            val success = assertIs<ClaimResult.Success>(taken)
            assertEquals("agent-b", success.item.claimedBy)
            val row = raw(claimed.id)
            assertEquals("agent-b", row.claimedBy)
            assertEquals(P7Raw.canon(e), row.claimedAt)
            assertEquals(P7Raw.canon(e.plusSeconds(60)), row.expiresAt)
        }

    @Test
    fun `S4 one millisecond after the expiry a competing claim succeeds`(): Unit =
        runBlocking {
            val claimed = newItem("Claimed")
            assertIs<ClaimResult.Success>(claims.claim(claimed.id, "agent-a", 60))
            clock.set(t.plusSeconds(60).plusMillis(1))

            val taken = claims.claim(claimed.id, "agent-b", 60)

            assertIs<ClaimResult.Success>(taken)
            assertEquals("agent-b", raw(claimed.id).claimedBy)
        }

    // ---- S6: claim operations do not bump version; update() never touches claim columns ----------------------

    @Test
    fun `S6 claim, refresh, release and auto-release leave the item version unchanged`(): Unit =
        runBlocking {
            val a = newItem("A")
            val b = newItem("B")
            val v0a = raw(a.id).version
            val v0b = raw(b.id).version

            assertIs<ClaimResult.Success>(claims.claim(a.id, "agent-x", 900))
            assertEquals(v0a, raw(a.id).version, "claim must not bump version")

            clock.advanceSeconds(1)
            assertIs<ClaimResult.Success>(claims.claim(a.id, "agent-x", 900))
            assertEquals(v0a, raw(a.id).version, "same-agent refresh must not bump version")

            // Claiming B as the same agent auto-releases A.
            assertIs<ClaimResult.Success>(claims.claim(b.id, "agent-x", 900))
            assertNull(raw(a.id).claimedBy, "A must have been auto-released")
            assertEquals(v0a, raw(a.id).version, "auto-release must not bump version")
            assertEquals(v0b, raw(b.id).version)

            assertIs<ReleaseResult.Success>(claims.release(b.id, "agent-x"))
            assertNull(raw(b.id).claimedBy)
            assertEquals(v0b, raw(b.id).version, "release must not bump version")
        }

    @Test
    fun `S6 update of a snapshot taken before the claim succeeds and keeps the claim`(): Unit =
        runBlocking {
            val item = newItem("Before claim")
            val snapshot = assertNotNull(items.getById(item.id))
            assertIs<ClaimResult.Success>(claims.claim(item.id, "agent-a", 600))

            val updated = items.update(snapshot.copy(title = "Renamed"))

            assertNotNull(updated, "the claim must not have bumped version, so the stale-by-claim snapshot still applies")
            assertEquals("Renamed", assertNotNull(items.getById(item.id)).title)
            val row = raw(item.id)
            assertEquals("agent-a", row.claimedBy, "update() must not clear the claim")
            assertEquals(P7Raw.canon(t), row.claimedAt)
            assertEquals(P7Raw.canon(t.plusSeconds(600)), row.expiresAt)
            assertEquals(P7Raw.canon(t), row.originalClaimedAt)
        }

    @Test
    fun `S6 update ignores claim fields carried on the incoming item`(): Unit =
        runBlocking {
            val item = newItem("Claimed then edited")
            assertIs<ClaimResult.Success>(claims.claim(item.id, "agent-a", 600))
            val claimedSnapshot = assertNotNull(items.getById(item.id))

            val forged =
                claimedSnapshot.copy(
                    title = "Edited",
                    claimedBy = "intruder",
                    claimExpiresAt = t.plusSeconds(99_999)
                )
            assertNotNull(items.update(forged))

            val row = raw(item.id)
            assertEquals("agent-a", row.claimedBy, "claimed_by is owned by the claim store, not by update()")
            assertEquals(P7Raw.canon(t.plusSeconds(600)), row.expiresAt)
            assertEquals("Edited", assertNotNull(items.getById(item.id)).title)
        }

    @Test
    fun `S6 clear removes the claim without bumping version`(): Unit =
        runBlocking {
            val item = newItem()
            val v0 = raw(item.id).version
            assertIs<ClaimResult.Success>(claims.claim(item.id, "agent-a", 600))

            assertTrue(claims.clear(item.id), "clear of a claimed item reports that it cleared")

            val row = raw(item.id)
            assertNull(row.claimedBy)
            assertNull(row.claimedAt)
            assertNull(row.expiresAt)
            assertNull(row.originalClaimedAt)
            assertEquals(v0, row.version)
        }

    // ---- S7: advancing to a terminal role clears all four claim columns (EXISTING-SURFACE) -------------------

    @Test
    fun `S7 a real claim followed by a terminal advance leaves all four claim columns null`(): Unit =
        runBlocking {
            val item = newItem("To finish", Role.WORK)
            assertIs<ClaimResult.Success>(claims.claim(item.id, "agent-a", 600))
            val claimed = assertNotNull(items.getById(item.id))
            val service =
                AdvanceService(
                    workItemRepository = provider.workItemRepository(),
                    roleTransitionRepository = provider.roleTransitionRepository(),
                    dependencyRepository = provider.dependencyRepository(),
                    noteRepository = provider.noteRepository(),
                    schemaResolver = { null },
                    unitOfWork = sqliteDb.unitOfWork()
                )

            val outcome =
                service.advance(
                    claimed,
                    "complete",
                    null,
                    ActorClaim(id = "agent-a", kind = ActorKind.SUBAGENT),
                    VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop"),
                    DegradedModePolicy.ACCEPT_CACHED,
                    true
                )

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.TERMINAL, success.result.newRole)
            val row = raw(item.id)
            assertNull(row.claimedBy)
            assertNull(row.claimedAt)
            assertNull(row.expiresAt)
            assertNull(row.originalClaimedAt)
        }

    // ---- S9: failure results -------------------------------------------------------------------------------

    @Test
    fun `S9 releasing twice reports NotClaimedByYou and a non-holder cannot release`(): Unit =
        runBlocking {
            val item = newItem()
            assertIs<ClaimResult.Success>(claims.claim(item.id, "agent-a", 600))

            assertIs<ReleaseResult.NotClaimedByYou>(claims.release(item.id, "agent-b"))
            assertEquals("agent-a", raw(item.id).claimedBy, "a refused release must leave the claim intact")

            assertIs<ReleaseResult.Success>(claims.release(item.id, "agent-a"))
            assertIs<ReleaseResult.NotClaimedByYou>(claims.release(item.id, "agent-a"))
        }

    @Test
    fun `S9 claiming a terminal item or a missing item is refused with the matching result`(): Unit =
        runBlocking {
            val terminal = newItem("Done", Role.TERMINAL)
            val terminalResult = claims.claim(terminal.id, "agent-a", 600)
            assertEquals(terminal.id, assertIs<ClaimResult.TerminalItem>(terminalResult).itemId)
            assertNull(raw(terminal.id).claimedBy)

            val missing = UUID.randomUUID()
            assertEquals(missing, assertIs<ClaimResult.NotFound>(claims.claim(missing, "agent-a", 600)).itemId)
            assertEquals(missing, assertIs<ReleaseResult.NotFound>(claims.release(missing, "agent-a")).itemId)
        }
}
