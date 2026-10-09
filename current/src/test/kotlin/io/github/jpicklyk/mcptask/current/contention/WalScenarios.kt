package io.github.jpicklyk.mcptask.current.contention

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.port.ReleaseResult
import io.github.jpicklyk.mcptask.current.application.service.ok
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.rejections
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.transitioned
import io.github.jpicklyk.mcptask.current.test.P11_LEASE_KEY
import io.github.jpicklyk.mcptask.current.test.arr
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The race scenarios of item 36c719db (plan 3.12, 8). Each is written once and run by the in-process and the
 * cross-process classes. Expected values are derived by hand from the documented semantics, never from race output:
 * the clock is frozen at [WAL_T], so a claim of ttl N gives every loser a retryAfterMs of exactly N * 1000, and a
 * terminal item accepts only `reopen`, so a racing second `complete` is `invalid_transition`.
 */
internal object WalScenarios {
    private const val CLAIM_TTL = 900
    private const val CLAIM_RETRY_MS = 900_000L
    private const val LEASE_RETRY_MS = 600_000L

    private fun List<JsonObject>.applied() = filter { it.flag("applied") == true }

    private fun List<JsonObject>.rejected() = filter { it.flag("applied") != true }

    private fun JsonObject.missing(): List<String?> = arr("missingNotes").map { it.jsonObject.text("key") }

    // ---------------------------------------------------------------------------------------------
    // Advance
    // ---------------------------------------------------------------------------------------------

    /** A1: K racers `complete` the same WORK item. One applies; every other is `invalid_transition`, never `apply_failed`. */
    suspend fun a1CompleteRace(
        fx: WalFixture,
        racers: Int
    ) {
        val d = fx.d
        val item = d.item("A1 ${fx.next()}", Role.WORK, type = "p11-optional")
        val mark = fx.rig.maxSeq()

        val results = fx.go(racers) { i -> fx.driverFor(i).advance(item, "complete") }

        assertEquals(1, results.applied().size, "exactly one racer completes the item: $results")
        assertEquals("terminal", results.applied().single().text("newRole"), "$results")
        assertEquals(racers - 1, results.rejected().size, "$results")
        results.rejected().forEach {
            assertEquals("invalid_transition", it.text("errorCode"), "a loser is evaluated on the committed state: $it")
        }
        assertEquals(Role.TERMINAL, d.role(item))
        assertEquals(1, d.transitions(item).size, "one role_transitions row")
        val rows = fx.rig.rowsAfter(mark)
        assertEquals(1, rows.transitioned().size, "one item.transitioned event: $rows")
        assertEquals(0, rows.rejections().size, "a table rejection records no row: $rows")
    }

    /** A2: two racers `start` a QUEUE item whose only open gate is the work note. The loser is gate_blocked on the committed state. */
    suspend fun a2StartRace(fx: WalFixture) {
        val d = fx.d
        val item = d.item("A2 ${fx.next()}", Role.QUEUE, type = "p11-plain")
        d.note(item, "spec", "queue")
        val mark = fx.rig.maxSeq()

        val results = fx.go(2) { i -> fx.driverFor(i).advance(item, "start") }

        assertEquals(1, results.applied().size, "$results")
        assertEquals("work", results.applied().single().text("newRole"), "$results")
        val loser = results.rejected().single()
        assertEquals("gate_blocked", loser.text("errorCode"), "$loser")
        assertEquals(listOf("impl"), loser.missing(), "$loser")
        assertEquals(Role.WORK, d.role(item))
        val rejections = fx.rig.rowsAfter(mark).rejections()
        assertEquals(1, rejections.size, "exactly one rejection row: $rejections")
        assertEquals("transition.rejected", rejections.single().type)
        assertEquals(
            listOf("impl"),
            rejections
                .single()
                .payload()["missingKeys"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        )
    }

    /** S1: [children] siblings complete together. All apply; exactly one entry carries the parent cascade. */
    suspend fun s1SiblingCascade(
        fx: WalFixture,
        children: Int
    ) {
        val d = fx.d
        val parent = d.item("S1 parent ${fx.next()}", Role.WORK)
        val kids = (1..children).map { d.item("S1 kid $it", Role.WORK, type = "p11-optional", parent = parent) }

        val results = fx.go(children) { i -> fx.driverFor(i).advance(kids[i], "complete") }

        assertEquals(children, results.applied().size, "every sibling completes: $results")
        val carrying = results.filter { it.arr("cascadeEvents").isNotEmpty() }
        assertEquals(1, carrying.size, "exactly one entry reports the parent cascade: $results")
        val cascade =
            carrying
                .single()
                .arr("cascadeEvents")
                .single()
                .jsonObject
        assertEquals(parent.id.toString(), cascade.text("itemId"), "$cascade")
        assertEquals("work", cascade.text("previousRole"), "$cascade")
        assertEquals("terminal", cascade.text("targetRole"), "$cascade")
        assertEquals(true, cascade.flag("applied"), "$cascade")
        assertEquals(Role.TERMINAL, d.role(parent))
        assertEquals(1, d.transitions(parent).size, "the parent transitions exactly once")
    }

    /** S1b: as [s1SiblingCascade] with a grandparent whose only child is the parent: the winner reports two cascades. */
    suspend fun s1bGrandparentCascade(
        fx: WalFixture,
        children: Int
    ) {
        val d = fx.d
        val grand = d.item("S1b grandparent ${fx.next()}", Role.WORK)
        val parent = d.item("S1b parent", Role.WORK, parent = grand)
        val kids = (1..children).map { d.item("S1b kid $it", Role.WORK, type = "p11-optional", parent = parent) }

        val results = fx.go(children) { i -> fx.driverFor(i).advance(kids[i], "complete") }

        assertEquals(children, results.applied().size, "$results")
        val carrying = results.filter { it.arr("cascadeEvents").isNotEmpty() }
        assertEquals(1, carrying.size, "$results")
        val cascades = carrying.single().arr("cascadeEvents").map { it.jsonObject }
        assertEquals(
            listOf(parent.id.toString(), grand.id.toString()),
            cascades.map { it.text("itemId") },
            "parent first, then grandparent: $cascades"
        )
        assertTrue(cascades.all { it.flag("applied") == true && it.text("targetRole") == "terminal" }, "$cascades")
        assertEquals(1, d.transitions(parent).size)
        assertEquals(1, d.transitions(grand).size)
        assertEquals(Role.TERMINAL, d.role(grand))
    }

    /** L1: two QUEUE items needing the same exclusive resource `start` together. One takes the lease; the other is a transient reject. */
    suspend fun l1LeaseRace(fx: WalFixture) {
        val d = fx.d
        val items = (1..2).map { d.item("L1 $it ${fx.next()}", Role.QUEUE, type = "p11-leased") }
        val mark = fx.rig.maxSeq()

        val results = fx.go(2) { i -> fx.driverFor(i).advance(items[i], "start") }

        assertEquals(1, results.applied().size, "$results")
        val winnerIdx = results.indexOfFirst { it.flag("applied") == true }
        val loserIdx = 1 - winnerIdx
        assertEquals("work", results[winnerIdx].text("newRole"), "$results")
        val loser = results[loserIdx]
        assertEquals("resource_unavailable", loser.text("errorCode"), "$loser")
        assertEquals("transient", loser.text("errorKind"), "$loser")
        assertEquals(listOf(P11_LEASE_KEY), loser.arr("contendedResources").map { it.jsonPrimitive.content }, "$loser")
        assertEquals(LEASE_RETRY_MS.toString(), loser.text("retryAfterMs"), "$loser")
        assertEquals(Role.QUEUE, d.role(items[loserIdx]))
        assertEquals(0, d.transitions(items[loserIdx]).size)
        val held = d.raw.resourceLeaseRepository().findActiveByKeys(listOf(P11_LEASE_KEY))
        assertEquals(listOf(items[winnerIdx].id), held.map { it.holderItemId }, "the lease holder is the winner")
        assertEquals(1, fx.rig.rowsAfter(mark).count { it.type == "lease.rejected" }, "exactly one lease.rejected row")
        d.freeLease(items[winnerIdx])
    }

    /** L2: the holder completes (releasing its lease) while a contender starts. Either order; never two holders. */
    suspend fun l2HolderReleaseVersusContender(fx: WalFixture) {
        val d = fx.d
        val holder = d.item("L2 holder ${fx.next()}", Role.WORK, type = "p11-leased")
        d.holdLease(holder)
        val contender = d.item("L2 contender", Role.QUEUE, type = "p11-leased")

        val results =
            fx.go(2) { i ->
                if (i == 0) fx.driverFor(i).advance(holder, "complete") else fx.driverFor(i).advance(contender, "start")
            }

        assertEquals(true, results[0].flag("applied"), "the holder always completes: ${results[0]}")
        assertEquals(Role.TERMINAL, d.role(holder))
        val held = d.raw.resourceLeaseRepository().findActiveByKeys(listOf(P11_LEASE_KEY))
        val c = results[1]
        if (c.flag("applied") == true) {
            assertEquals(Role.WORK, d.role(contender))
            assertEquals(listOf(contender.id), held.map { it.holderItemId }, "the contender holds the lease alone")
        } else {
            assertEquals("resource_unavailable", c.text("errorCode"), "$c")
            assertEquals(LEASE_RETRY_MS.toString(), c.text("retryAfterMs"), "$c")
            assertEquals(Role.QUEUE, d.role(contender))
            assertTrue(held.isEmpty(), "the holder release left no lease rows: $held")
        }
        d.freeLease(contender)
        d.freeLease(holder)
    }

    // ---------------------------------------------------------------------------------------------
    // Claims and leases through ClaimService
    // ---------------------------------------------------------------------------------------------

    /** C1: [agents] agents claim one item. One Success; every loser is AlreadyClaimed with retryAfterMs = ttl * 1000. */
    suspend fun c1ClaimRace(
        fx: WalFixture,
        agents: Int
    ) {
        val d = fx.d
        val iter = fx.next()
        val item = d.item("C1 $iter", Role.QUEUE)
        val mark = fx.rig.maxSeq()

        val results =
            fx.go(agents) { i ->
                fx
                    .driverFor(i)
                    .rig.ctx.claimService
                    .claim(item.id, "agent-$iter-$i", CLAIM_TTL)
                    .ok()
            }

        val winnerIdx = results.indexOfFirst { it is ClaimResult.Success }
        assertEquals(1, results.count { it is ClaimResult.Success }, "$results")
        val won = results[winnerIdx] as ClaimResult.Success
        assertEquals(WAL_T, won.item.claimedAt)
        assertEquals(WAL_T.plusSeconds(CLAIM_TTL.toLong()), won.item.claimExpiresAt)
        results.filterIndexed { i, _ -> i != winnerIdx }.forEach {
            assertEquals(ClaimResult.AlreadyClaimed(item.id, CLAIM_RETRY_MS), it)
        }
        val rows = fx.rig.rowsAfter(mark)
        assertEquals(1, rows.count { it.type == "claim.acquired" }, "$rows")
        assertEquals(agents - 1, rows.count { it.type == "claim.rejected" }, "$rows")
        assertEquals("agent-$iter-$winnerIdx", d.reload(item).claimedBy)
        assertEquals("agent-$iter-$winnerIdx", rows.single { it.type == "claim.acquired" }.str("holder"))
    }

    /** C2: the holder releases twice at once. One Success, one NotClaimedByYou; all four claim columns end null. */
    suspend fun c2ReleaseRace(fx: WalFixture) {
        val d = fx.d
        val iter = fx.next()
        val item = d.item("C2 $iter", Role.QUEUE)
        val holder = "holder-$iter"
        assertIs<ClaimResult.Success>(
            fx.rig.ctx.claimService
                .claim(item.id, holder, CLAIM_TTL)
                .ok()
        )

        val results =
            fx.go(2) { i ->
                fx
                    .driverFor(i)
                    .rig.ctx.claimService
                    .release(item.id, holder)
                    .ok()
            }

        assertEquals(1, results.count { it is ReleaseResult.Success }, "$results")
        assertEquals(listOf(ReleaseResult.NotClaimedByYou(item.id)), results.filterIsInstance<ReleaseResult.NotClaimedByYou>(), "$results")
        val after = d.reload(item)
        assertNull(after.claimedBy)
        assertNull(after.claimedAt)
        assertNull(after.claimExpiresAt)
        assertNull(after.originalClaimedAt)
    }

    /** C3: A holds X; A claims Y while B claims X. Either order: A always moves to Y and releases X. */
    suspend fun c3SupersedeVersusClaim(fx: WalFixture) {
        val d = fx.d
        val iter = fx.next()
        val x = d.item("C3 x $iter", Role.QUEUE)
        val y = d.item("C3 y $iter", Role.QUEUE)
        val a = "a-$iter"
        val b = "b-$iter"
        assertIs<ClaimResult.Success>(
            fx.rig.ctx.claimService
                .claim(x.id, a, CLAIM_TTL)
                .ok()
        )

        val results =
            fx.go(2) { i ->
                val svc =
                    fx
                        .driverFor(i)
                        .rig.ctx.claimService
                if (i == 0) svc.claim(y.id, a, CLAIM_TTL).ok() else svc.claim(x.id, b, CLAIM_TTL).ok()
            }

        val ra = assertIs<ClaimResult.Success>(results[0])
        assertEquals(listOf(x.id), ra.releasedItemIds, "the move of A to Y releases X in either order")
        assertEquals(a, d.reload(y).claimedBy)
        when (val rb = results[1]) {
            is ClaimResult.Success -> assertEquals(b, d.reload(x).claimedBy, "B claimed X after A let it go")
            is ClaimResult.AlreadyClaimed -> {
                assertEquals(ClaimResult.AlreadyClaimed(x.id, CLAIM_RETRY_MS), rb)
                assertNull(d.reload(x).claimedBy, "B was refused, then A released X")
            }
            else -> error("unexpected result for B: $rb")
        }
    }

    /** C4: A claims an item while B advances it. Claim first: B is not_claim_holder. Advance first: B starts it. A always holds the claim. */
    suspend fun c4ClaimVersusAdvance(fx: WalFixture) {
        val d = fx.d
        val iter = fx.next()
        val item = d.item("C4 $iter", Role.QUEUE, type = "p11-optional")
        val a = "a-$iter"
        val b = "b-$iter"

        val results: List<Any> =
            fx.go(2) { i ->
                val driver = fx.driverFor(i)
                if (i == 0) {
                    driver.rig.ctx.claimService
                        .claim(item.id, a, CLAIM_TTL)
                        .ok() as Any
                } else {
                    driver.advance(item, "start", actor = b) as Any
                }
            }

        assertIs<ClaimResult.Success>(results[0])
        val advance = results[1] as JsonObject
        if (advance.flag("applied") == true) {
            assertEquals(Role.WORK, d.role(item), "advance first")
        } else {
            assertEquals("not_claim_holder", advance.text("errorCode"), "$advance")
            assertEquals(Role.QUEUE, d.role(item), "claim first")
        }
        assertEquals(a, d.reload(item).claimedBy)
    }

    /** K1: [holders] items acquire one lease key in units. One Success; each loser is Contended with retryAfterMs = ttl * 1000. */
    suspend fun k1LeaseAcquireRace(
        fx: WalFixture,
        holders: Int
    ) {
        val d = fx.d
        val iter = fx.next()
        val key = "k1-$iter"
        val items = (1..holders).map { d.item("K1 $it $iter", Role.QUEUE) }

        val results =
            fx.go(holders) { i ->
                fx
                    .driverFor(i)
                    .rig.ctx.claimService
                    .acquireLeases(items[i].id, "actor-$i", listOf(key to CLAIM_TTL))
                    .ok()
            }

        assertEquals(1, results.count { it is LeaseAcquireResult.Success }, "$results")
        val winnerIdx = results.indexOfFirst { it is LeaseAcquireResult.Success }
        results.filterIndexed { i, _ -> i != winnerIdx }.forEach {
            assertEquals(LeaseAcquireResult.Contended(listOf(key), CLAIM_RETRY_MS), it)
        }
        val held = d.raw.resourceLeaseRepository().findActiveByKeys(listOf(key))
        assertEquals(listOf(items[winnerIdx].id), held.map { it.holderItemId })
    }
}
