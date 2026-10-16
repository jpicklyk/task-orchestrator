package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item fca8ce36, R25) for the `frontier` select of `runs_read` and for the liveness fields the run
 * view gained (`stalledSeats`, the single stalled rule of `coordinatorStalled`), over the MCP orchestration and director profiles. Every
 * call goes through the real MCP adapter and the production dispatcher over the real composition and SQLite, on the seat test world of
 * `RunsSeatOpsTest` (a fixed, settable clock). The REST leg lives in `RunsFrontierRestTest`, the statement count in
 * `RunFrontierQueryCountTest`, the pure predicate in `RunLivenessTest`.
 *
 * Scenarios S1 to S12 and S15 of the frozen test-plan with its probe list. Oracles (never read from a run): the W5 brief 3.4 / 3.8 and the
 * frozen task-scope of R25, Build 1 to 4 and decisions D1 to D11: stalled is `now - lastSeen > stallAfterSeconds`, strictly and exactly,
 * with the run pinned threshold and a seat last seen at `lastSeenAt ?: assignedAt`; `ready` is a visible member that is not terminal and
 * not blocked, has no active member-seat assignment, no unsatisfied blocking edge (a BLOCKS edge is satisfied when the blocker role is at or
 * beyond its `unblockAt`, default terminal; BLOCKED never satisfies) and no foreign live claim (`claimExpiresAt > now` and a holder pair that
 * is not the (principal, assignment) pair of an active assignment of the run, the coordinator included); always empty on a closed run and
 * computed normally on a paused one; `inFlight` lists the active member seats in assignment order; `waiting` gives a blocked member the
 * reason `manual` and a member with an unsatisfied blocking edge the reason `dependency` plus the sorted short ids of the unsatisfied
 * blockers visible to the caller; item ids are 8-hex short ids, the run id is the full UUID; no bodies, tokens, principals or assignment ids;
 * absent members are omitted, never null; the arrays are always present; members and blockers outside the caller scope are left out while an
 * invisible blocker still keeps its item waiting. Literal values are written from those clauses.
 *
 * Self-resolved from public evidence (the task-scope text, `current/docs/api-reference.md` and the existing seat tests, not from the
 * implementation): the orchestration `select` envelope carries the frontier as `data`; the wire role words are the lower-case role names; the
 * `open` argument `stallAfterSeconds`; the `close` reason `cancelled`; the claim, heartbeat and end_seat flows of `SeatWorld`.
 *
 * Arbitration (test-plan section 8): the order of `ready` and `waiting` is the membership order, but the tie-break among members added in the
 * same instant is not stated, so those two arrays are compared as sets of ids (their entries field by field); the order of `inFlight`
 * (assignedAt then id) is asserted with distinct assignment instants and item ids that run opposite to them.
 *
 * Negative-assertion fixture conditions: every "not ready" is paired with the same member becoming ready when the single input it turns on
 * changes (the seat ends, the claim expires, the blocker finishes, the caller scope widens); every "not stalled" has a twin one millisecond
 * later that is stalled; every omitted key is paired with a fixture that would carry it.
 *
 * Domain invariants satisfied by construction: items are children of the world root (or of a root the test seeds) and seeded through the rig;
 * runs, seats, claims and heartbeats are made through the tools themselves, so the stored state is whatever the service produced; roles
 * and dependency edges are written through the stores/SQL only for fixtures the tools cannot reach (a terminal or blocked member, an edge).
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class RunsFrontierTest {
    private val clock = SettableClock(Instant.parse("2026-10-10T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private fun world(block: suspend (SeatWorld) -> Unit) =
        runBlocking {
            val w = SeatWorld.create(db.db, dir, clock)
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    // ------------------------------------------------------------------ fixture helpers

    private fun short(item: WorkItem): String = item.id.toString().substring(0, 8)

    private fun stall(seconds: Int) = ""","stallAfterSeconds":$seconds"""

    private suspend fun SeatWorld.item(
        title: String,
        role: Role = Role.QUEUE,
        tags: String? = "plain-task",
        id: UUID = UUID.randomUUID(),
        parent: WorkItem? = null
    ): WorkItem = rig.seed(title, parent = parent ?: root, role = role, tags = tags, id = id)

    /** An edge in which [blocker] blocks [blocked]; the IS_BLOCKED_BY form is written from the blocked end, as the store stores it. */
    private suspend fun SeatWorld.edge(
        blocker: WorkItem,
        blocked: WorkItem,
        unblockAt: String? = null,
        type: DependencyType = DependencyType.BLOCKS
    ) {
        val (from, to) = if (type == DependencyType.IS_BLOCKED_BY) blocked to blocker else blocker to blocked
        rig.raw.dependencyRepository().create(Dependency(fromItemId = from.id, toItemId = to.id, type = type, unblockAt = unblockAt))
    }

    private fun SeatWorld.setRole(
        item: WorkItem,
        role: String
    ) = exec("UPDATE work_items SET role = ? WHERE id = ?", role, item.id)

    private suspend fun SeatWorld.frontier(
        run: Opened,
        by: ParityToken = orch,
        token: String? = null
    ): JsonObject {
        val reply = select("frontier", ""","runId":"${run.runId}"""", token = token, by = by)
        assertTrue(reply.ok(), "frontier must answer: $reply")
        return assertNotNull(reply.data, "frontier data: $reply")
    }

    private suspend fun SeatWorld.getRun(run: Opened): JsonObject {
        val reply = select("get", ""","runId":"${run.runId}"""")
        assertTrue(reply.ok(), "get must answer: $reply")
        return assertNotNull(reply.data, "get data: $reply")
    }

    private fun JsonObject.list(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.num(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.ids(key: String): List<String> = list(key).map { it.text("id") }

    private fun JsonObject.counts(): Map<String, Int> = getValue("counts").jsonObject.mapValues { it.value.jsonPrimitive.int }

    private fun JsonObject.waitingBy(id: String): JsonObject = list("waiting").single { it.text("id") == id }

    private fun JsonObject.blockers(id: String): List<String> =
        waitingBy(id).getValue("blockers").jsonArray.map { it.jsonPrimitive.content }

    private fun assertSameIds(
        expected: Collection<WorkItem>,
        actual: List<String>,
        label: String
    ) {
        assertEquals(expected.map(::short).sorted(), actual.sorted(), label)
    }

    private fun allKeys(e: JsonElement): Set<String> =
        when (e) {
            is JsonObject -> e.keys + e.values.flatMap { allKeys(it) }
            is JsonArray -> e.flatMap { allKeys(it) }.toSet()
            else -> emptySet()
        }

    private fun containsNull(e: JsonElement): Boolean =
        when (e) {
            is JsonObject -> e.values.any { containsNull(it) }
            is JsonArray -> e.any { containsNull(it) }
            is JsonNull -> true
            else -> false
        }

    private fun objectsWithKey(
        e: JsonElement,
        key: String
    ): List<JsonObject> =
        when (e) {
            is JsonObject -> (if (key in e) listOf(e) else emptyList()) + e.values.flatMap { objectsWithKey(it, key) }
            is JsonArray -> e.flatMap { objectsWithKey(it, key) }
            else -> emptyList()
        }

    private fun runRow(
        w: SeatWorld,
        run: Opened
    ): Map<String, Any?> = w.rows("SELECT * FROM runs WHERE id = ?", UUID.fromString(run.runId)).single()

    /** Rows with their blob columns as lists, so two reads of the same row compare equal. */
    private fun stable(rows: List<Map<String, Any?>>): List<Map<String, Any?>> =
        rows.map { row -> row.mapValues { (_, v) -> if (v is ByteArray) v.toList() else v } }

    private class Mixed(
        val run: Opened,
        val a: WorkItem,
        val b: WorkItem,
        val c: WorkItem,
        val e: WorkItem,
        val f: WorkItem,
        val x: WorkItem,
        val seat: Assigned
    )

    /** A queue, B working with an active implementer seat, C queued behind X (outside the run), E terminal, F blocked. */
    private suspend fun mixed(
        w: SeatWorld,
        stallAfter: Int = 120,
        more: List<WorkItem> = emptyList()
    ): Mixed {
        val x = w.item("X outside blocker", tags = "free-task")
        val a = w.item("A queue")
        val b = w.item("B working", Role.WORK)
        val c = w.item("C behind X")
        val e = w.item("E done", Role.TERMINAL)
        val f = w.item("F held", Role.BLOCKED)
        w.edge(x, c)
        val run = w.open(listOf(a, b, c, e, f) + more, extra = stall(stallAfter))
        val seat = w.assignOk(run, b, "implementer")
        return Mixed(run, a, b, c, e, f, x, seat)
    }
    // ------------------------------------------------------------------ S1 the mixed run

    @Test
    fun `S1 a mixed run reports counts, ready, in flight and waiting exactly`(): Unit =
        world { w ->
            val m = mixed(w)
            val data = w.frontier(m.run)

            assertEquals(m.run.runId, data.text("runId"))
            assertEquals("open", data.text("status"))
            assertFalse(data.flag("stalled"), "the coordinator just wrote")
            assertEquals(mapOf("queue" to 2, "work" to 1, "review" to 0, "blocked" to 1, "terminal" to 1), data.counts())

            assertEquals(listOf(short(m.a)), data.ids("ready"), "only A is ready")
            val ready = data.list("ready").single()
            assertEquals("A queue", ready.text("title"))
            assertEquals("queue", ready.text("role"))
            assertEquals("plain-task", ready.text("type"))

            val inFlight = data.list("inFlight").single()
            assertEquals(short(m.b), inFlight.text("id"))
            assertEquals("work", inFlight.text("role"))
            assertEquals("implementer", inFlight.text("seat"))
            assertEquals(1, inFlight.num("attempt"))
            assertFalse(inFlight.flag("stalled"))

            assertEquals(setOf(short(m.c), short(m.f)), data.ids("waiting").toSet())
            assertEquals(2, data.list("waiting").size)
            assertEquals("dependency", data.waitingBy(short(m.c)).text("reason"))
            assertEquals(listOf(short(m.x)), data.blockers(short(m.c)))
            assertEquals("manual", data.waitingBy(short(m.f)).text("reason"))
        }

    // ------------------------------------------------------------------ S2 a blocker reaches its threshold

    @Test
    fun `S2 a member becomes ready when its blocker reaches terminal and its waiting entry goes`(): Unit =
        world { w ->
            val m = mixed(w)
            assertEquals(setOf(short(m.c), short(m.f)), w.frontier(m.run).ids("waiting").toSet(), "control: C waits on X")

            w.setRole(m.x, "terminal")
            val after = w.frontier(m.run)
            assertSameIds(listOf(m.a, m.c), after.ids("ready"), "A and C are ready")
            assertEquals(listOf(short(m.f)), after.ids("waiting"), "only the blocked member still waits")
            assertEquals("manual", after.waitingBy(short(m.f)).text("reason"))
        }

    @Test
    fun `S2 the unblockAt threshold decides, and a BLOCKED blocker never satisfies any threshold`(): Unit =
        world { w ->
            val y = w.item("Y blocker", tags = "free-task")
            val g = w.item("G behind Y")
            w.edge(y, g, unblockAt = "work")
            val run = w.open(listOf(g), extra = stall(120))

            val below = w.frontier(run)
            assertEquals(listOf(short(g)), below.ids("waiting"), "Y is queue, below the work threshold")
            assertTrue(below.ids("ready").isEmpty())

            w.setRole(y, "work")
            val atThreshold = w.frontier(run)
            assertEquals(listOf(short(g)), atThreshold.ids("ready"), "Y reached work: satisfied")
            assertTrue(atThreshold.ids("waiting").isEmpty())

            w.setRole(y, "blocked")
            val blocked = w.frontier(run)
            assertEquals(listOf(short(g)), blocked.ids("waiting"), "a BLOCKED blocker is never at or beyond a threshold")
            assertTrue(blocked.ids("ready").isEmpty())

            w.setRole(y, "terminal")
            assertEquals(listOf(short(g)), w.frontier(run).ids("ready"), "terminal satisfies the work threshold")
        }

    // ------------------------------------------------------------------ S3 the run view agrees

    @Test
    @Suppress("ktlint:standard:max-line-length")
    fun `S3 frontier stalled equals the run view coordinatorStalled, and stalledSeats counts the stalled in-flight member seats only`(): Unit =
        world { w ->
            val b1 = w.item("B1")
            val b2 = w.item("B2")
            val b3 = w.item("B3")
            val run = w.open(listOf(b1, b2, b3), extra = stall(120))
            val a1 = w.assignOk(run, b1, "implementer")
            w.assignOk(run, b2, "implementer")
            assertTrue(w.claim(b1, a1.token).ok(), "the seat of B1 touches")
            clock.advanceSeconds(121)
            assertTrue(w.heartbeat(b1, a1.token).ok(), "B1 touches again, 121 s later")

            val front = w.frontier(run)
            val view = w.getRun(run)
            assertTrue(front.flag("stalled"), "the coordinator was last seen 121 s ago")
            assertEquals(front.flag("stalled"), view.flag("coordinatorStalled"))
            val stalledNow = front.list("inFlight").filter { it.flag("stalled") }
            assertEquals(listOf(short(b2)), stalledNow.map { it.text("id") }, "B2 never touched, assigned 121 s ago; B1 just touched")
            assertEquals(1, view.num("stalledSeats"), "the stalled coordinator row is not counted")
            assertEquals(2, view.num("activeSeats"), "activeSeats counts the member seats only")
            assertEquals(front.list("inFlight").size, view.num("activeSeats"))

            // a coordinator write brings it back, and a new seat is fresh
            w.assignOk(run, b3, "implementer")
            val front2 = w.frontier(run)
            val view2 = w.getRun(run)
            assertFalse(front2.flag("stalled"))
            assertEquals(front2.flag("stalled"), view2.flag("coordinatorStalled"))
            assertEquals(3, front2.list("inFlight").size)
            assertEquals(front2.list("inFlight").count { it.flag("stalled") }, view2.num("stalledSeats"))
            assertEquals(1, view2.num("stalledSeats"))
        }

    @Test
    fun `S3 stalledSeats is on the run view of get, list and the runs_write results`(): Unit =
        world { w ->
            val b1 = w.item("B1")
            val run = w.open(listOf(b1), extra = stall(120))
            assertEquals("0", run.reply.find("stalledSeats"), "open returns the run view with stalledSeats: ${run.reply}")
            w.assignOk(run, b1, "implementer")
            clock.advanceSeconds(121)

            val list = w.select("list")
            assertTrue(list.ok(), list.toString())
            val listed = objectsWithKey(assertNotNull(list.data), "stalledSeats").single { it.text("runId") == run.runId }
            assertEquals(1, listed.num("stalledSeats"), "list shows the stalled seat")
            assertEquals(1, w.getRun(run).num("stalledSeats"), "get shows it")

            val closed = w.close(run, "cancelled", run.coordinatorToken)
            assertTrue(closed.ok(), closed.toString())
            assertNotNull(closed.find("stalledSeats"), "the close result carries the run view with stalledSeats: $closed")
        }

    // ------------------------------------------------------------------ S4 active seats

    @Test
    fun `S4 a member with an active seat is in flight and not ready, and is ready once the seat ends`(): Unit =
        world { w ->
            val q = w.item("Q planned")
            val other = w.item("Other")
            val run = w.open(listOf(q, other), extra = stall(120))
            assertSameIds(listOf(q, other), w.frontier(run).ids("ready"), "control: both ready before the seat")

            val seat = w.assignOk(run, q, "planner")
            val during = w.frontier(run)
            assertEquals(listOf(short(other)), during.ids("ready"))
            assertEquals(listOf(short(q)), during.ids("inFlight"))
            assertEquals("planner", during.list("inFlight").single().text("seat"))
            assertEquals(2, during.counts().getValue("queue"), "an in-flight member is still counted")
            assertTrue(during.ids("waiting").isEmpty(), "an in-flight member has no waiting reason")

            val ended = w.endSeat(seat.token)
            assertTrue(ended.ok(), ended.toString())
            val after = w.frontier(run)
            assertSameIds(listOf(q, other), after.ids("ready"), "Q is ready again")
            assertTrue(after.list("inFlight").isEmpty())
        }

    @Test
    fun `S4 probe two active seats on one item are two in-flight entries and the item is ready only after both end`(): Unit =
        world { w ->
            val item = w.item("Seated", tags = "seated-task")
            val run = w.open(listOf(item), extra = stall(120))
            val builder = w.assignOk(run, item, "builder")
            val checker = w.assignOk(run, item, "checker")

            val both = w.frontier(run)
            assertEquals(2, both.list("inFlight").size)
            assertEquals(setOf("builder", "checker"), both.list("inFlight").map { it.text("seat") }.toSet())
            assertTrue(both.ids("ready").isEmpty())

            assertTrue(w.endSeat(builder.token).ok())
            val one = w.frontier(run)
            assertEquals(listOf("checker"), one.list("inFlight").map { it.text("seat") })
            assertTrue(one.ids("ready").isEmpty(), "one seat is still active")

            assertTrue(w.endSeat(checker.token).ok())
            assertEquals(listOf(short(item)), w.frontier(run).ids("ready"))
        }

    @Test
    fun `S4 in-flight entries are in assignment order, not item id order`(): Unit =
        world { w ->
            val first = w.item("assigned first", id = UUID.fromString("ffffffff-0000-4000-8000-000000000001"))
            val second = w.item("assigned second", id = UUID.fromString("00000001-0000-4000-8000-000000000002"))
            val run = w.open(listOf(first, second), extra = stall(120))
            w.assignOk(run, first, "implementer")
            clock.advanceSeconds(1)
            w.assignOk(run, second, "implementer")

            assertEquals(listOf(short(first), short(second)), w.frontier(run).ids("inFlight"))
        }
    // ------------------------------------------------------------------ S5 claims

    @Test
    fun `S5 a live claim held outside the run keeps a member out of ready, an expired one and the coordinator one do not`(): Unit =
        world { w ->
            val outsider = w.item("claimed by an outsider")
            val coordinator = w.item("claimed under the coordinator token")
            val selfNoToken = w.item("claimed by the coordinator principal without a token")
            val free = w.item("unclaimed")
            val run = w.open(listOf(outsider, coordinator, selfNoToken, free), extra = stall(900))

            assertTrue(w.claim(outsider, null, by = w.other).ok(), "an outsider principal claims")
            val coordClaim = w.claim(coordinator, run.coordinatorToken)
            assertTrue(coordClaim.ok(), "the coordinator token claims: $coordClaim")
            assertTrue(w.claim(selfNoToken, null).ok(), "the opener principal claims with no token")
            val expiry = w.stamp(w.itemRow(outsider)["claim_expires_at"])
            assertTrue(expiry.isAfter(clock.now()), "fixture: the claim is live")

            val live = w.frontier(run)
            assertSameIds(listOf(coordinator, free), live.ids("ready"), "an outsider claim and a token-less principal claim are foreign")
            assertTrue(live.ids("waiting").isEmpty(), "a claimed member has no waiting reason")
            assertEquals(4, live.counts().getValue("queue"), "a claimed member is still counted")

            clock.set(expiry.minusMillis(1))
            assertSameIds(listOf(coordinator, free), w.frontier(run).ids("ready"), "1 ms before the expiry the claims are live")

            clock.set(expiry)
            assertSameIds(
                listOf(outsider, coordinator, selfNoToken, free),
                w.frontier(run).ids("ready"),
                "claimExpiresAt == now is expired"
            )
        }

    // ------------------------------------------------------------------ S6 blockers

    @Test
    fun `S6 waiting lists only the unsatisfied blocking blockers, as short ids in sorted order`(): Unit =
        world { w ->
            val g = w.item("G blocked many ways")
            val unsatisfiedB = w.item("blocker b", tags = "free-task", id = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000001"))
            val unsatisfiedA = w.item("blocker a", tags = "free-task", id = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000003"))
            val aliasNine = w.item("blocker nine", tags = "free-task", id = UUID.fromString("99999999-0000-4000-8000-000000000005"))
            val satisfiedTerminal =
                w.item(
                    "done blocker",
                    Role.TERMINAL,
                    "free-task",
                    UUID.fromString("cccccccc-0000-4000-8000-000000000002")
                )
            val satisfiedAtWork = w.item("working blocker", Role.WORK, "free-task", UUID.fromString("eeeeeeee-0000-4000-8000-000000000006"))
            val relates = w.item("related only", tags = "free-task", id = UUID.fromString("dddddddd-0000-4000-8000-000000000004"))
            w.edge(unsatisfiedB, g)
            w.edge(unsatisfiedA, g)
            w.edge(aliasNine, g, type = DependencyType.IS_BLOCKED_BY)
            w.edge(satisfiedTerminal, g)
            w.edge(satisfiedAtWork, g, unblockAt = "work")
            w.edge(relates, g, type = DependencyType.RELATES_TO)
            val run = w.open(listOf(g), extra = stall(120))

            val data = w.frontier(run)
            assertEquals(listOf(short(g)), data.ids("waiting"))
            assertEquals(
                listOf("99999999", "aaaaaaaa", "bbbbbbbb"),
                data.blockers(short(g)),
                "the unsatisfied BLOCKS and IS_BLOCKED_BY blockers, sorted; not the satisfied ones, not the RELATES_TO one"
            )
            assertTrue(data.ids("ready").isEmpty())
        }

    @Test
    fun `S6 a single unsatisfied blocker among satisfied ones is the only one listed`(): Unit =
        world { w ->
            val g = w.item("G")
            val open = w.item("open blocker", tags = "free-task")
            val done = w.item("done blocker", Role.TERMINAL, "free-task")
            w.edge(open, g)
            w.edge(done, g)
            val run = w.open(listOf(g), extra = stall(120))

            assertEquals(listOf(short(open)), w.frontier(run).blockers(short(g)))
        }

    // ------------------------------------------------------------------ S7 scope

    @Test
    fun `S7 a run on another root is forbidden to a root-scoped token, who still reads its own run`(): Unit =
        world { w ->
            val own = w.item("own member")
            val foreignMember = w.item("foreign member", parent = w.root2)
            val ownRun = w.open(listOf(own), extra = stall(120))
            val foreignRun = w.open(listOf(foreignMember), rootItem = w.root2, extra = stall(120))
            w.assignOk(foreignRun, foreignMember, "implementer")

            val refused = w.select("frontier", ""","runId":"${foreignRun.runId}"""", by = w.rootScoped)
            assertEquals("forbidden", refused.code(), refused.toString())
            assertFalse(short(foreignMember) in refused.toString(), "a refusal names no member of the run: $refused")
            assertFalse("inFlight" in refused.allKeys() || "ready" in refused.allKeys(), "and carries no frontier: $refused")

            assertTrue(
                w.select("frontier", ""","runId":"${foreignRun.runId}"""", by = w.orch).ok(),
                "control: the unscoped caller reads it"
            )
            val mine = w.frontier(ownRun, by = w.rootScoped)
            assertEquals(listOf(short(own)), mine.ids("ready"))
            assertFalse(short(foreignMember) in mine.toString(), "no member or seat of the other run appears")
        }

    @Test
    fun `S7 a blocker outside the scope keeps its item waiting but is left out of blockers`(): Unit =
        world { w ->
            val m = w.item("M behind an invisible blocker")
            val n = w.item("N behind both")
            val visible = w.item("visible blocker", tags = "free-task")
            w.edge(w.e, m)
            w.edge(w.e, n)
            w.edge(visible, n)
            val run = w.open(listOf(m, n), extra = stall(120))

            val full = w.frontier(run)
            assertEquals(listOf(short(w.e)), full.blockers(short(m)), "control: unrestricted sees the blocker of R2")
            assertEquals(listOf(short(visible), short(w.e)).sorted(), full.blockers(short(n)))

            val scoped = w.frontier(run, by = w.rootScoped)
            assertEquals(setOf(short(m), short(n)), scoped.ids("waiting").toSet(), "blocked-ness is decided on the true rows")
            assertTrue(scoped.ids("ready").isEmpty())
            assertEquals("dependency", scoped.waitingBy(short(m)).text("reason"))
            assertTrue(scoped.blockers(short(m)).isEmpty(), "the invisible blocker is not named")
            assertEquals(listOf(short(visible)), scoped.blockers(short(n)))
            assertFalse(short(w.e) in scoped.toString(), "the id of the R2 item appears nowhere in the scoped answer")
        }

    @Test
    fun `S7 another run members and seats never appear in the frontier`(): Unit =
        world { w ->
            val mine = w.item("mine")
            val theirs = w.item("theirs")
            val runA = w.open(listOf(mine), extra = stall(120))
            val runB = w.open(listOf(theirs), extra = stall(120))
            w.assignOk(runB, theirs, "implementer")

            val a = w.frontier(runA)
            assertEquals(listOf(short(mine)), a.ids("ready"))
            assertTrue(a.list("inFlight").isEmpty(), "the seat of the other run is not in flight here")
            assertEquals(1, a.counts().values.sum(), "one member")
            assertFalse(short(theirs) in a.toString())
            val b = w.frontier(runB)
            assertEquals(listOf(short(theirs)), b.ids("inFlight"))
            assertFalse(short(mine) in b.toString())
        }

    @Test
    fun `S7 a tag-scoped token sees only the members with its tag, counted and listed`(): Unit =
        world { w ->
            val taggedRoot = w.rig.seed("Tagged root", tags = "plain-task")
            val ta = w.rig.seed("visible member", parent = taggedRoot, tags = "plain-task")
            val tb = w.rig.seed("hidden member", parent = taggedRoot, tags = "seated-task")
            val run = w.open(listOf(ta, tb), rootItem = taggedRoot, extra = stall(120))
            val tagToken = ParityToken("seat-tag", setOf(ApiCapability.RUNS, ApiCapability.READ), tagsInclude = setOf("plain-task"))

            val all = w.frontier(run)
            assertSameIds(listOf(ta, tb), all.ids("ready"), "control: unrestricted sees both")
            assertEquals(2, all.counts().values.sum())

            val scoped = w.frontier(run, by = tagToken)
            assertEquals(listOf(short(ta)), scoped.ids("ready"), "the member without the tag is filtered, not a refusal")
            assertEquals(1, scoped.counts().values.sum(), "counts cover the visible members")
            assertFalse(short(tb) in scoped.toString())
        }
    // ------------------------------------------------------------------ S8 and S9 seat liveness

    @Test
    fun `S8 a seat is stalled strictly after the pinned threshold of its own run - 120 and 900 differ from the 600 default`(): Unit =
        world { w ->
            val i120 = w.item("in the 120 run")
            val i900 = w.item("in the 900 run")
            val r120 = w.open(listOf(i120), extra = stall(120))
            val r900 = w.open(listOf(i900), extra = stall(900))
            val s120 = w.assignOk(r120, i120, "implementer")
            val s900 = w.assignOk(r900, i900, "implementer")
            assertTrue(w.claim(i120, s120.token).ok())
            assertTrue(w.claim(i900, s900.token).ok())
            val t = clock.now()
            assertEquals(t, w.stamp(w.seatById(assertNotNull(s120.assignmentId))["last_seen_at"]), "fixture: the seat was last seen at t")

            fun stalledOf(data: JsonObject) = data.list("inFlight").single().flag("stalled")

            clock.set(t.plusSeconds(120))
            val atBoundary = w.frontier(r120)
            assertFalse(stalledOf(atBoundary), "exactly 120 s since last seen")
            assertEquals(t, Instant.parse(atBoundary.list("inFlight").single().text("lastSeenAt")), "lastSeenAt is reported")
            clock.set(t.plusSeconds(120).plusMillis(1))
            assertTrue(stalledOf(w.frontier(r120)), "120 s and 1 ms")

            clock.set(t.plusSeconds(700))
            assertTrue(stalledOf(w.frontier(r120)), "the 120 run: 700 s")
            assertFalse(stalledOf(w.frontier(r900)), "the 900 run: 700 s is below its own threshold although above the default 600")
            clock.set(t.plusSeconds(900).plusMillis(1))
            assertTrue(stalledOf(w.frontier(r900)), "the 900 run: 900 s and 1 ms")
        }

    @Test
    fun `S9 a seat never seen omits lastSeenAt and is stalled from its assignment instant`(): Unit =
        world { w ->
            val item = w.item("never touched")
            val run = w.open(listOf(item), extra = stall(120))
            val assigned = w.assignOk(run, item, "implementer")
            assertEquals(null, w.seatById(assertNotNull(assigned.assignmentId))["last_seen_at"], "fixture: never touched")
            val t = clock.now()

            clock.set(t.plusSeconds(120))
            val atBoundary = w.frontier(run).list("inFlight").single()
            assertFalse("lastSeenAt" in atBoundary, "an absent lastSeenAt is omitted, not null: $atBoundary")
            assertFalse(atBoundary.flag("stalled"), "assigned exactly 120 s ago")
            clock.set(t.plusSeconds(121))
            assertTrue(
                w
                    .frontier(run)
                    .list("inFlight")
                    .single()
                    .flag("stalled"),
                "assigned 121 s ago"
            )

            // control: the first touch brings lastSeenAt into the entry and clears the stall
            assertTrue(w.claim(item, assigned.token).ok())
            val touched = w.frontier(run).list("inFlight").single()
            assertEquals(clock.now(), Instant.parse(touched.text("lastSeenAt")))
            assertFalse(touched.flag("stalled"))
        }

    // ------------------------------------------------------------------ S8a the coordinator

    @Test
    fun `S8a the coordinator is stalled strictly after the run threshold, on the frontier and on get alike`(): Unit =
        world { w ->
            val b1 = w.item("B1")
            val b2 = w.item("B2")
            val run = w.open(listOf(b1, b2), extra = stall(120))
            w.assignOk(run, b1, "implementer")
            val t = clock.now()
            assertEquals(t, w.stamp(runRow(w, run)["coordinator_seen_at"]), "fixture: the coordinator wrote at t")

            suspend fun both(): Pair<Boolean, Boolean> = w.frontier(run).flag("stalled") to w.getRun(run).flag("coordinatorStalled")

            clock.set(t.plusSeconds(120))
            assertEquals(false to false, both(), "exactly 120 s")
            clock.set(t.plusSeconds(120).plusMillis(1))
            assertEquals(true to true, both(), "120 s and 1 ms")

            assertTrue(w.assignOk(run, b2, "implementer").reply.ok(), "a coordinator write")
            assertEquals(false to false, both(), "seen again")
        }

    @Test
    fun `S8a a select under the coordinator token touches nothing`(): Unit =
        world { w ->
            val b1 = w.item("B1")
            val run = w.open(listOf(b1), extra = stall(120))
            w.assignOk(run, b1, "implementer")
            val seenAt = runRow(w, run)["coordinator_seen_at"]
            clock.advanceSeconds(60)

            w.frontier(run, token = run.coordinatorToken)
            assertEquals(seenAt, runRow(w, run)["coordinator_seen_at"], "a read is not a sign of life")
        }

    // ------------------------------------------------------------------ S10 to S12 shapes

    @Test
    fun `S10 a closed run has no ready members, no seats and is not stalled, yet counts its members`(): Unit =
        world { w ->
            val m = mixed(w)
            assertEquals(listOf(short(m.a)), w.frontier(m.run).ids("ready"), "control: the open run has a ready member")

            val closed = w.close(m.run, "cancelled", m.run.coordinatorToken)
            assertTrue(closed.ok(), closed.toString())
            clock.advanceSeconds(100_000)

            val data = w.frontier(m.run)
            assertEquals("closed", data.text("status"))
            assertFalse(data.flag("stalled"), "a closed run is never stalled")
            assertTrue(data.list("ready").isEmpty(), "always empty on a closed run")
            assertTrue(data.list("inFlight").isEmpty(), "the close ended every seat")
            assertEquals(mapOf("queue" to 2, "work" to 1, "review" to 0, "blocked" to 1, "terminal" to 1), data.counts())
            assertFalse(w.getRun(m.run).flag("coordinatorStalled"), "and the run view agrees")
        }

    @Test
    fun `S10 a paused run computes its frontier normally`(): Unit =
        world { w ->
            val m = mixed(w)
            val before = w.frontier(m.run)
            val paused = w.director("runs_write", """{"ops":[{"op":"pause","runId":"${m.run.runId}"}]}""", w.human)
            assertTrue(paused.ok(), paused.toString())

            val after = w.frontier(m.run)
            assertEquals("paused", after.text("status"))
            assertEquals(before.ids("ready"), after.ids("ready"))
            assertEquals(listOf(short(m.a)), after.ids("ready"))
            assertEquals(before.ids("inFlight"), after.ids("inFlight"))
            assertEquals(before.ids("waiting").toSet(), after.ids("waiting").toSet())
        }

    @Test
    fun `S11 a run with no members answers zero counts and empty arrays that are present`(): Unit =
        world { w ->
            val run = w.open(emptyList(), extra = stall(120))
            val data = w.frontier(run)

            assertEquals(mapOf("queue" to 0, "work" to 0, "review" to 0, "blocked" to 0, "terminal" to 0), data.counts())
            for (key in listOf("ready", "inFlight", "waiting")) {
                assertTrue(data.getValue(key).jsonArray.isEmpty(), "$key is present and empty: $data")
            }
            assertFalse(data.flag("stalled"))
            assertEquals(run.runId, data.text("runId"))
        }

    @Test
    fun `S12 the wire shape - short item ids, the full run id, only the stated keys, no null, no secrets`(): Unit =
        world { w ->
            val untyped = w.item("untyped", tags = null)
            val claimed = w.item("claimed by an outsider")
            val m = mixed(w, more = listOf(untyped, claimed))
            assertTrue(w.claim(claimed, null, by = w.other).ok(), "fixture: a live outsider claim")
            val data = w.frontier(m.run)

            assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(data.text("runId")))
            assertEquals(m.run.runId, data.text("runId"))
            assertEquals(setOf("runId", "status", "stalled", "counts", "ready", "inFlight", "waiting"), data.keys)
            assertEquals(setOf("queue", "work", "review", "blocked", "terminal"), data.getValue("counts").jsonObject.keys)
            assertTrue(
                data.ids("ready").isNotEmpty() && data.ids("inFlight").isNotEmpty() && data.ids("waiting").isNotEmpty(),
                "fixture: every array has an entry"
            )

            val shortId = Regex("^[0-9a-f]{8}$")
            for (key in listOf("ready", "inFlight", "waiting")) {
                for (entry in data.list(key)) assertTrue(shortId.matches(entry.text("id")), "$key id is 8-hex: $entry")
            }
            for (entry in data.list("waiting")) {
                entry["blockers"]?.jsonArray?.forEach {
                    assertTrue(
                        shortId.matches(it.jsonPrimitive.content),
                        "blocker id is 8-hex: $entry"
                    )
                }
            }
            for (entry in data.list(
                "ready"
            )) {
                assertTrue(entry.keys.all { it in setOf("id", "title", "role", "type") }, "ready keys: $entry")
            }
            for (entry in data.list("inFlight")) {
                assertTrue(
                    entry.keys.all { it in setOf("id", "role", "seat", "attempt", "lastSeenAt", "stalled") },
                    "inFlight keys: $entry"
                )
            }
            for (entry in data.list(
                "waiting"
            )) {
                assertTrue(entry.keys.all { it in setOf("id", "reason", "blockers") }, "waiting keys: $entry")
            }

            assertFalse(containsNull(data), "absent members are omitted, never null: $data")
            val keys = allKeys(data)
            for (forbidden in listOf(
                "summary",
                "body",
                "token",
                "seatToken",
                "coordinatorToken",
                "tokenHash",
                "principal",
                "principalId",
                "assignmentId",
                "claimedBy"
            )) {
                assertFalse(forbidden in keys, "the key $forbidden must not appear: $keys")
            }
            val text = data.toString()
            assertFalse("api:seat" in text, "no principal: $text")
            assertFalse(m.run.coordinatorToken in text, "no coordinator token")
            assertFalse(assertNotNull(m.seat.token) in text, "no seat token")
            assertFalse(assertNotNull(m.seat.assignmentId) in text, "no assignment id")
            assertFalse(m.a.id.toString() in text, "item ids are short")
        }

    @Test
    fun `S12 an item with no type has no type member in its ready entry, and one with a type has it`(): Unit =
        world { w ->
            val untyped = w.item("untyped", tags = null)
            val typed = w.item("typed")
            val run = w.open(listOf(untyped, typed), extra = stall(120))
            val ready = w.frontier(run).list("ready").associateBy { it.text("id") }

            assertFalse("type" in ready.getValue(short(untyped)), "no type: omitted, not null: ${ready.getValue(short(untyped))}")
            assertEquals("plain-task", ready.getValue(short(typed)).text("type"))
        }

    // ------------------------------------------------------------------ probes and S15

    @Test
    fun `S12 probe the frontier is a pure read - twice identical, no row written, no event`(): Unit =
        world { w ->
            val m = mixed(w)
            val seatsBefore = stable(w.seatsOfRun(m.run.runId))
            val runBefore = stable(listOf(runRow(w, m.run)))
            val eventsBefore = w.rig.maxSeq()

            val first = w.frontier(m.run)
            val second = w.frontier(m.run)

            assertEquals(first, second)
            assertEquals(seatsBefore, stable(w.seatsOfRun(m.run.runId)))
            assertEquals(runBefore, stable(listOf(runRow(w, m.run))))
            assertEquals(eventsBefore, w.rig.maxSeq())
        }

    @Test
    fun `S15 the orchestration and the director profile answer with the same frontier, and a bad reference is refused`(): Unit =
        world { w ->
            val m = mixed(w)
            val viaOrchestration = w.frontier(m.run)
            val director = w.director("runs_read", """{"select":"frontier","runId":"${m.run.runId}"}""", w.orch)
            assertTrue(director.ok(), "the director profile serves frontier: $director")
            assertEquals(viaOrchestration, director.data)

            val prefix = m.run.runId.substring(0, 8)
            val byPrefix = w.select("frontier", ""","runId":"$prefix"""")
            assertTrue(byPrefix.ok(), byPrefix.toString())
            assertEquals(m.run.runId, assertNotNull(byPrefix.data).text("runId"), "an 8-hex prefix resolves to the full run id")

            val unknown = w.select("frontier", ""","runId":"deadbeef"""")
            assertEquals("not_found", unknown.code(), unknown.toString())
            val missing = w.select("frontier")
            assertEquals("invalid_request", missing.code(), missing.toString())
            val directorUnknown = w.director("runs_read", """{"select":"frontier","runId":"deadbeef"}""", w.orch)
            assertEquals("not_found", directorUnknown.code(), directorUnknown.toString())
        }

    @Test
    fun `S15 a caller without the read capability is refused, a READ-only caller is served`(): Unit =
        world { w ->
            val m = mixed(w)
            val noRead = ParityToken("seat-noread", setOf(ApiCapability.RUNS))
            val refused = w.select("frontier", ""","runId":"${m.run.runId}"""", by = noRead)
            assertTrue(refused.code() != null, "no read capability: refused: $refused")
            assertTrue("ready" !in refused.allKeys(), "and no frontier is returned: $refused")
            val control = w.frontier(m.run, by = w.readOnly)
            assertEquals(listOf(short(m.a)), control.ids("ready"), "control: a READ-only token reads it")
        }
}
