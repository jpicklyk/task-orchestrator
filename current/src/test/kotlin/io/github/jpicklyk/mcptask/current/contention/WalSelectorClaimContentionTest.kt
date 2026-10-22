package io.github.jpicklyk.mcptask.current.contention

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.advanceTool
import io.github.jpicklyk.mcptask.current.test.claimOp
import io.github.jpicklyk.mcptask.current.test.claimSelectOp
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.onlyResult
import io.github.jpicklyk.mcptask.current.test.p11Actor
import io.github.jpicklyk.mcptask.current.test.text
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Item 3ee700d8 (closes the AR-81 residue): `advance` op `claim` with `select` over a file-backed WAL database with the
 * workers split across TWO `DatabaseManager`s (the repo second-process stand-in, see [WalCrossProcessContentionTest]).
 * The documented contract (api-reference.md, "Claims" and the `claim` select filters) is that the selector and the claim are
 * one serialized find-and-claim: N concurrent workers get N distinct claims while unclaimed eligible matches remain, and
 * a worker is told `claim_held` only when no unclaimed eligible match is left. Every expected value below is derived by hand
 * from that contract (frozen clock, distinct worker ids, a fresh parent per iteration), never from race output. Each
 * scenario runs as the two sequential oracle passes first and then as a real race.
 */
@Tag("serial")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class WalSelectorClaimContentionTest {
    @Test
    fun `S1 eight workers on two managers select among eight children - eight distinct claims and no claim_held`(): Unit =
        runScenario(REPEATS, twoManagers = true) { fx ->
            val round = fx.round(workers = 8, children = 8)

            round.assertEveryWorkerClaimedADistinctChild()
            assertEquals(round.children.map { it.id.toString() }.toSet(), round.reportedIds().toSet(), "the claims cover the child set")
        }

    @Test
    fun `S2 six workers select among ten children - six distinct claims and four children stay unclaimed`(): Unit =
        runScenario(REPEATS, twoManagers = true) { fx ->
            val round = fx.round(workers = 6, children = 10)

            round.assertEveryWorkerClaimedADistinctChild()
            assertEquals(4, round.claimedByNobody(), "exactly the surplus children stay unclaimed")
        }

    @Test
    fun `S3 six workers all targeting the same oldest candidate still claim six distinct children`(): Unit =
        runScenario(REPEATS, twoManagers = true) { fx ->
            val round = fx.round(workers = 6, children = 6, orderBy = "oldest")

            round.assertEveryWorkerClaimedADistinctChild()
            assertEquals(round.children.map { it.id.toString() }.toSet(), round.reportedIds().toSet(), "the claims cover the child set")
        }

    @Test
    fun `S4 eight workers select among three children - three claims and five claim_held with retryAfterMs 30000`(): Unit =
        runScenario(REPEATS, twoManagers = true) { fx ->
            val round = fx.round(workers = 8, children = 3)

            round.assertScarcity(claims = 3)
        }

    @Test
    fun `S5 a child already claimed by someone else is skipped and never stolen`(): Unit =
        runScenario(REPEATS, twoManagers = true) { fx ->
            val iter = fx.next()
            val parent = fx.d.item("S5 parent $iter", Role.WORK)
            val held = fx.d.createChild("S5 held $iter", parent, Priority.HIGH)
            val medium = (1..4).map { fx.d.createChild("S5 medium $it $iter", parent, Priority.MEDIUM) }
            val squatter = "squatter-$iter"
            val seizure =
                fx.d.rig
                    .advanceTool(claimOp(held.id), actor = p11Actor(squatter))
                    .onlyResult()
            assertEquals(true, seizure.flag("ok"), "the squatter claims the high-priority child first: $seizure")
            assertEquals(true, seizure.flag("claimed"), "$seizure")

            val round = Round(fx, iter, parent, medium, fx.selectRace(parent, iter, workers = 4, orderBy = null))

            round.assertEveryWorkerClaimedADistinctChild()
            assertEquals(medium.map { it.id.toString() }.toSet(), round.reportedIds().toSet(), "the claims are exactly the medium children")
            assertEquals(squatter, fx.d.reload(held).claimedBy, "the squatter still holds the high-priority child")
            assertTrue(held.id.toString() !in round.reportedIds(), "no worker reports the child someone else holds: ${round.results}")
        }

    @Test
    fun `S6 four workers select under a childless parent - every answer is ok and claimed false with no reason`(): Unit =
        runScenario(REPEATS, twoManagers = true) { fx ->
            val round = fx.round(workers = 4, children = 0)

            round.results.forEach {
                assertEquals(true, it.flag("ok"), "no error element: $it")
                assertEquals(false, it.flag("claimed"), "nothing matched, nothing claimed: $it")
                assertNull(it["reason"], "nothing matched at all carries no reason: $it")
                assertNull(it["retryAfterMs"], "$it")
                assertNull(it["excluded"], "$it")
            }
        }

    @Test
    fun `P1 four workers select among three children - the boundary N equals M plus one yields one claim_held`(): Unit =
        runScenario(REPEATS, twoManagers = true) { fx ->
            val round = fx.round(workers = 4, children = 3)

            round.assertScarcity(claims = 3)
        }

    @Test
    fun `P2 one actor selecting on both managers at once ends up holding exactly one child`(): Unit =
        runScenario(REPEATS, twoManagers = true) { fx ->
            val iter = fx.next()
            val parent = fx.d.item("P2 parent $iter", Role.WORK)
            val children = (1..3).map { fx.d.createChild("P2 child $it $iter", parent, Priority.MEDIUM) }
            val actor = "same-$iter"

            val results =
                fx.go(2) { i ->
                    fx
                        .driverFor(i)
                        .rig
                        .advanceTool(claimSelectOp(selectUnder(parent)), actor = p11Actor(actor))
                        .onlyResult()
                }

            results.forEach { assertEquals(true, it.flag("ok"), "both calls are answered ok: $results") }
            assertEquals(1, children.count { fx.d.reload(it).claimedBy == actor }, "one claim per actor: $results")
        }

    @Test
    fun `P3 each worker repeating its selector call after the race still holds exactly one child`(): Unit =
        runScenario(REPEATS, twoManagers = true) { fx ->
            val round = fx.round(workers = 4, children = 6)
            round.assertEveryWorkerClaimedADistinctChild()

            // Which item a repeat ends on is ambiguous in the documented contract (refresh versus best unclaimed match):
            // only the one-claim-per-actor invariant is asserted.
            val repeats =
                fx.go(4) { i ->
                    fx
                        .driverFor(i)
                        .rig
                        .advanceTool(claimSelectOp(selectUnder(round.parent)), actor = p11Actor(round.holder(i)))
                        .onlyResult()
                }

            repeats.forEach {
                assertEquals(true, it.flag("ok"), "a repeat is answered ok: $repeats")
                assertEquals(true, it.flag("claimed"), "unclaimed children remain, so a repeat claims: $repeats")
            }
            for (i in 0 until 4) {
                assertEquals(
                    1,
                    round.children.count { fx.d.reload(it).claimedBy == round.holder(i) },
                    "worker $i holds one child: $repeats"
                )
            }
        }

    private class Round(
        val fx: WalFixture,
        val iter: Int,
        val parent: WorkItem,
        val children: List<WorkItem>,
        val results: List<JsonObject>
    ) {
        fun holder(i: Int): String = holderId(iter, i)

        fun reportedIds(): List<String> = results.mapNotNull { it.text("itemId") }

        /** AC1: every worker got an ok, claimed, selected element for a distinct child it now holds. */
        suspend fun assertEveryWorkerClaimedADistinctChild() {
            results.forEachIndexed { i, r ->
                assertEquals(true, r.flag("ok"), "worker $i: no error element: $results")
                assertEquals(true, r.flag("claimed"), "worker $i claimed: $results")
                assertEquals(true, r.flag("selected"), "worker $i claim came from the selector: $results")
            }
            val ids = reportedIds()
            assertEquals(results.size, ids.size, "every element names its item: $results")
            assertEquals(results.size, ids.toSet().size, "the claimed items are pairwise distinct: $results")
            results.forEachIndexed { i, r ->
                val child = children.single { it.id.toString() == r.text("itemId") }
                assertEquals(holder(i), fx.d.reload(child).claimedBy, "worker $i holds the child it reported: $results")
            }
        }

        suspend fun claimedByNobody(): Int = children.count { fx.d.reload(it).claimedBy == null }

        /** AC2: [claims] workers claim distinct children; every other worker is told claim_held with retryAfterMs 30000. */
        suspend fun assertScarcity(claims: Int) {
            val winners = results.filter { it.flag("claimed") == true }
            val losers = results.filter { it.flag("claimed") != true }
            assertEquals(claims, winners.size, "exactly one claim per child: $results")
            assertEquals(results.size - claims, losers.size, "$results")
            assertEquals(claims, winners.mapNotNull { it.text("itemId") }.toSet().size, "the claims are distinct: $results")
            winners.forEach {
                assertEquals(true, it.flag("ok"), "$results")
                assertEquals(true, it.flag("selected"), "$results")
            }
            losers.forEach {
                assertEquals(true, it.flag("ok"), "a loser is an ok answer, not an error: $results")
                assertEquals(false, it.flag("claimed"), "$it")
                assertEquals("claim_held", it.text("reason"), "$it")
                assertEquals(30_000L, it.retryAfterMs(), "the selector retry hint is 30000 ms, not the claim TTL: $it")
                assertEquals(claims, it.excluded("claimed"), "every child is claimed: $it")
                assertEquals(0, it.excluded("ancestorClaimed"), "$it")
                assertEquals(0, it.excluded("dependencyBlocked"), "$it")
                assertEquals(0, it.excluded("outOfScope"), "$it")
            }
            results.forEachIndexed { i, r ->
                if (r.flag("claimed") == true) {
                    val child = children.single { it.id.toString() == r.text("itemId") }
                    assertEquals(holder(i), fx.d.reload(child).claimedBy, "worker $i holds the child it reported: $results")
                }
            }
            assertEquals(0, claimedByNobody(), "every child is held")
        }
    }

    private companion object {
        const val REPEATS = 8
        const val CHILD_TYPE = "test-item"

        fun holderId(
            iter: Int,
            i: Int
        ) = "sel-$iter-$i"

        fun selectUnder(
            parent: WorkItem,
            orderBy: String? = null
        ): JsonObject =
            buildJsonObject {
                put("parentId", parent.id.toString())
                orderBy?.let { put("orderBy", it) }
            }

        fun JsonObject.retryAfterMs(): Long? =
            this["retryAfterMs"]
                ?.takeIf { it !is JsonNull }
                ?.jsonPrimitive
                ?.content
                ?.toLong()

        fun JsonObject.excluded(key: String): Int =
            this["excluded"]!!
                .jsonObject[key]!!
                .jsonPrimitive.content
                .toInt()

        /** A fresh parent with [children] medium-priority children, then [workers] selector calls racing on it. */
        suspend fun WalFixture.round(
            workers: Int,
            children: Int,
            orderBy: String? = null
        ): Round {
            val iter = next()
            val parent = d.item("selector parent $iter", Role.WORK)
            val kids = (1..children).map { d.createChild("selector child $it $iter", parent, Priority.MEDIUM) }
            return Round(this, iter, parent, kids, selectRace(parent, iter, workers, orderBy))
        }

        /** [workers] distinct actors, alternating between the managers, each making one selector claim under [parent]. */
        fun WalFixture.selectRace(
            parent: WorkItem,
            iter: Int,
            workers: Int,
            orderBy: String?
        ): List<JsonObject> =
            go(workers) { i ->
                driverFor(i).rig.advanceTool(claimSelectOp(selectUnder(parent, orderBy)), actor = p11Actor(holderId(iter, i))).onlyResult()
            }

        /** A QUEUE child of [parent] with an explicit [priority] (the driver item helper takes none). */
        suspend fun P11Driver.createChild(
            title: String,
            parent: WorkItem,
            priority: Priority
        ): WorkItem =
            raw.workItemRepository().create(
                WorkItem(
                    title = title,
                    role = Role.QUEUE,
                    type = CHILD_TYPE,
                    tags = CHILD_TYPE,
                    parentId = parent.id,
                    depth = parent.depth + 1,
                    rootId = parent.rootId ?: parent.id,
                    priority = priority
                )
            )
    }
}
