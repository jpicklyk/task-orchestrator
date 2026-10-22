package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f65fef01, R29) for the scope hardening of two reads and one write:
 * the existence signal of a decision outside the caller's scope on `advance` `transition` `block` (r26 N6, scenarios S1 and S2) and the
 * scoped counts of the run view and of the frontier `inFlight` list (r25 NB2 and NB1, scenarios S4 and S5). Every call goes through the
 * real MCP adapter and the production dispatcher over the real composition and SQLite, on the seat test world of `RunsSeatOpsTest`.
 *
 * Surface labels (from the frozen test-plan): S1 and S4 are EXISTING-SURFACE bug fixes (a plain revert of the fix is behavioural red);
 * S2 and S5 are EXISTING-SURFACE guards of behaviour that must not change.
 *
 * Oracles (never read from a run), the frozen task-scope decisions and the public docs:
 * - D1 (r26 N6): for a RESTRICTED caller (root or tag scope) a full-UUID `decisionId` whose decision is outside the caller's scope
 *   must answer exactly what a nonexistent UUID answers, `invalid_request` on `/decisionId` with the reason "names no decision"; an
 *   unrestricted caller, and a restricted one naming an in-scope decision of another item, still get "belongs to another item"; a hex
 *   prefix of an out-of-scope decision stays `forbidden`; the request-rule order (trigger rule, then reason rule) is unchanged.
 * - D3 (r25 NB2): under a restricted scope the run view's `members`, `activeSeats` and `stalledSeats` count only the members the caller
 *   may see; a root scope that covers the run's root equals the unrestricted counts; the frontier already scopes (`counts`, `inFlight`).
 *   Stalled is `now - lastSeen > stallAfterSeconds`, strictly, with a seat last seen at `lastSeenAt ?: assignedAt` (R25 liveness).
 *
 * Negative-assertion fixture conditions: every absence is paired with the unrestricted twin over the same fixture that returns the
 * value ("names no decision" next to "belongs to another item"; the counts 2 and 1 next to 3 and 2), and the stalled member is made
 * stalled by the clock (121 s against a 120 s threshold) so the count that drops out is a real, non-zero one.
 *
 * Self-resolved from public evidence (existing seat, decision and frontier tests, `current/docs/api-reference.md`, not the
 * implementation): the wire keys `members`, `activeSeats`, `stalledSeats` of the run view (get and list), the frontier `inFlight[].id`
 * (an 8-hex short id), the field-violation shape `{field, reason, received}` under the error, and the `question` decision class.
 *
 * Domain invariants satisfied by construction: items are children of their root and seeded through the rig; runs, seats and decisions
 * are made through the tools themselves; the clock only moves forward.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class RunsHardeningScopeTest {
    private val clock = SettableClock(Instant.parse("2026-10-15T12:00:00Z"))

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

    private val caps =
        setOf(ApiCapability.RUNS, ApiCapability.READ, ApiCapability.ADVANCE, ApiCapability.WRITE_NOTES, ApiCapability.WRITE_ITEMS)

    // ------------------------------------------------------------------ helpers

    private fun field(
        name: String,
        value: String
    ) = ",\"$name\":\"$value\""

    private suspend fun SeatWorld.step(
        item: WorkItem,
        trigger: String,
        extra: String = "",
        by: ParityToken = orch
    ): Reply = ops("advance", "{\"op\":\"transition\",\"itemId\":\"${item.id}\",\"trigger\":\"$trigger\"$extra}", by = by)

    private suspend fun SeatWorld.startOk(item: WorkItem) {
        val reply = step(item, "start")
        assertTrue(reply.ok(), "start must succeed: $reply")
    }

    /** Raises a pending `question` decision about [item] as the unrestricted orchestrator and returns its full id. */
    private suspend fun SeatWorld.decisionOn(
        item: WorkItem,
        key: String
    ): String {
        val reply =
            ops(
                "runs_write",
                "{\"op\":\"raise\",\"class\":\"question\",\"question\":\"Which one?\"," +
                    "\"options\":[{\"key\":\"a\",\"label\":\"A\"},{\"key\":\"b\",\"label\":\"B\"}]," +
                    "\"dedupeKey\":\"$key\",\"itemId\":\"${item.id}\"}"
            )
        assertTrue(reply.ok(), "raise must succeed: $reply")
        return assertNotNull(reply.find("decisionId") ?: reply.find("id"), "a decision id in $reply")
    }

    private fun awaiting(decisionId: String) = field("reason", "awaiting_decision") + field("decisionId", decisionId)

    private fun violations(within: JsonElement?): List<Pair<String, String>> =
        when (within) {
            is JsonObject ->
                (
                    if ("field" in within && "reason" in within) {
                        listOf(within.getValue("field").jsonPrimitive.content to within.getValue("reason").jsonPrimitive.content)
                    } else {
                        emptyList()
                    }
                ) + within.values.flatMap { violations(it) }
            is JsonArray -> within.flatMap { violations(it) }
            else -> emptyList()
        }

    private fun Reply.violationReasons(pointerEnd: String): List<String> =
        violations(error())
            .filter {
                it.first.endsWith(pointerEnd)
            }.map { it.second }

    /** The error object of [reply] as text with [id] masked, so two refusals that differ only in the id they echo compare equal. */
    private fun Reply.shape(id: String): String = (error() ?: error("no error in $this")).toString().replace(id, "<id>", ignoreCase = true)

    private fun SeatWorld.assertUnblocked(
        item: WorkItem,
        label: String
    ) {
        assertEquals("work", itemRow(item)["role"], "$label: role")
        assertNull(itemRow(item)["blocked_reason"], "$label: blocked_reason")
        assertNull(itemRow(item)["blocked_ref"], "$label: blocked_ref")
    }

    // ------------------------------------------------------------------ S1 an out-of-scope decision reads as nonexistent

    @Test
    fun `S1 a root-scoped caller naming a pending decision of another root gets the answer of a nonexistent decision`(): Unit =
        world { w ->
            w.startOk(w.m2)
            val foreign = w.decisionOn(w.e, "foreign")
            val unused = "0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f"

            val hidden = w.step(w.m2, "block", awaiting(foreign), by = w.rootScoped)
            val missing = w.step(w.m2, "block", awaiting(unused), by = w.rootScoped)

            assertEquals("invalid_request", missing.code(), "control: a UUID that names nothing: $missing")
            assertEquals(listOf("names no decision"), missing.violationReasons("/decisionId"), "control reason: $missing")
            assertEquals("invalid_request", hidden.code(), "a decision outside the scope: $hidden")
            assertEquals(
                listOf("names no decision"),
                hidden.violationReasons("/decisionId"),
                "the out-of-scope decision must not read as 'belongs to another item': $hidden"
            )
            assertEquals(missing.shape(unused), hidden.shape(foreign), "the two refusals have one shape (code, message, field, reason)")
            w.assertUnblocked(w.m2, "after the refusals")

            // control: the same caller blocking on its own in-scope decision succeeds, so the refusals above are about the decision only
            val own = w.decisionOn(w.m2, "own")
            assertTrue(w.step(w.m2, "block", awaiting(own), by = w.rootScoped).ok(), "own decision accepted")
            assertEquals("awaiting_decision", w.itemRow(w.m2)["blocked_reason"])
        }

    @Test
    @Suppress("ktlint:standard:max-line-length")
    fun `S1 probe the upper-case spelling of the same out-of-scope UUID is also a nonexistent decision, and the refusal changes nothing`(): Unit =
        world { w ->
            w.startOk(w.m2)
            val foreign = w.decisionOn(w.e, "foreign")

            val upper = w.step(w.m2, "block", awaiting(foreign.uppercase()), by = w.rootScoped)

            assertEquals("invalid_request", upper.code(), "$upper")
            assertEquals(listOf("names no decision"), upper.violationReasons("/decisionId"), "$upper")
            w.assertUnblocked(w.m2, "after the upper-case refusal")
            assertEquals(1, w.rows("SELECT id FROM decision_requests").size, "no decision was created or changed by the refused calls")
        }

    // ------------------------------------------------------------------ S2 everything else about the check stays as it was

    @Test
    @Suppress("ktlint:standard:max-line-length")
    fun `S2 an unrestricted caller and a restricted one naming an in-scope decision of another item still read belongs to another item`(): Unit =
        world { w ->
            w.startOk(w.m2)
            val foreign = w.decisionOn(w.e, "foreign")
            val sibling = w.decisionOn(w.m3, "sibling")

            val unrestricted = w.step(w.m2, "block", awaiting(foreign))
            val scopedInScope = w.step(w.m2, "block", awaiting(sibling), by = w.rootScoped)

            assertEquals("invalid_request", unrestricted.code(), "$unrestricted")
            assertTrue(
                unrestricted.violationReasons("/decisionId").any { "belongs to another item" in it },
                "unrestricted names the existing decision of another root: $unrestricted"
            )
            assertEquals("invalid_request", scopedInScope.code(), "$scopedInScope")
            assertTrue(
                scopedInScope.violationReasons("/decisionId").any { "belongs to another item" in it },
                "an in-scope decision of another item keeps its message: $scopedInScope"
            )
            w.assertUnblocked(w.m2, "after the refusals")
        }

    @Test
    fun `S2 a hex prefix of an out-of-scope decision stays forbidden for a root-scoped caller but not for an unrestricted one`(): Unit =
        world { w ->
            w.startOk(w.m2)
            val foreign = w.decisionOn(w.e, "foreign")
            val prefix = foreign.substring(0, 8)

            val scoped = w.step(w.m2, "block", awaiting(prefix), by = w.rootScoped)
            val unrestricted = w.step(w.m2, "block", awaiting(prefix))

            assertEquals("forbidden", scoped.code(), "an out-of-scope prefix is forbidden: $scoped")
            assertEquals("invalid_request", unrestricted.code(), "control: unrestricted resolves it and refuses on the item: $unrestricted")
            assertTrue(unrestricted.violationReasons("/decisionId").any { "belongs to another item" in it }, "$unrestricted")
            w.assertUnblocked(w.m2, "after the refusals")
        }

    @Test
    fun `S2 the request-rule order is unchanged for a restricted caller naming an out-of-scope decision`(): Unit =
        world { w ->
            w.startOk(w.m2)
            val foreign = w.decisionOn(w.e, "foreign")

            val wrongTrigger = w.step(w.m3, "start", field("decisionId", foreign), by = w.rootScoped)
            val wrongReason = w.step(w.m2, "block", field("reason", "manual") + field("decisionId", foreign), by = w.rootScoped)
            val noReason = w.step(w.m2, "block", field("decisionId", foreign), by = w.rootScoped)

            assertEquals("invalid_request", wrongTrigger.code(), "$wrongTrigger")
            assertEquals(listOf("allowed only with trigger block or hold"), wrongTrigger.violationReasons("/decisionId"), "$wrongTrigger")
            assertEquals("invalid_request", wrongReason.code(), "$wrongReason")
            assertEquals(listOf("allowed only with reason awaiting_decision"), wrongReason.violationReasons("/decisionId"), "$wrongReason")
            assertEquals(listOf("allowed only with reason awaiting_decision"), noReason.violationReasons("/decisionId"), "$noReason")
            assertEquals("queue", w.itemRow(w.m3)["role"], "the refused start left the item queued")
            w.assertUnblocked(w.m2, "after the refusals")
        }

    // ------------------------------------------------------------------ S4 and S5 scoped counts

    private class Scoped(
        val run: Opened,
        val a: WorkItem,
        val b: WorkItem,
        val c: WorkItem
    )

    /**
     * A run on a tagged root with members A and B (tag plain-task) and C (tag seated-task, so a plain-task tag filter hides it).
     * C's builder seat is assigned first and goes 121 s without a touch against a 120 s threshold (stalled); A's implementer seat is
     * assigned after the wait (fresh); B has no seat. Unrestricted: members 3, activeSeats 2, stalledSeats 1.
     */
    private suspend fun scopedRun(w: SeatWorld): Scoped {
        val root = w.rig.seed("Tagged root", tags = "plain-task")
        val a = w.rig.seed("A visible with a fresh seat", parent = root, tags = "plain-task")
        val b = w.rig.seed("B visible without a seat", parent = root, tags = "plain-task")
        val c = w.rig.seed("C hidden with a stalled seat", parent = root, tags = "seated-task")
        val run = w.open(members = listOf(a, b, c), rootItem = root, extra = ",\"stallAfterSeconds\":120")
        w.assignOk(run, c, "builder")
        clock.advanceSeconds(121)
        w.assignOk(run, a, "implementer")
        return Scoped(run, a, b, c)
    }

    private fun tagToken() = ParityToken("scope-tag", setOf(ApiCapability.RUNS, ApiCapability.READ), tagsInclude = setOf("plain-task"))

    private fun rootToken(
        w: SeatWorld,
        run: Scoped
    ): ParityToken {
        val rootId = w.itemRow(run.a)["root_id"]
        return ParityToken("scope-root", setOf(ApiCapability.RUNS, ApiCapability.READ), rootIds = setOf(w.uuidOf(rootId)))
    }

    private fun JsonObject.num(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.counts(): Triple<Int, Int, Int> = Triple(num("members"), num("activeSeats"), num("stalledSeats"))

    private suspend fun SeatWorld.view(
        run: Opened,
        by: ParityToken
    ): JsonObject {
        val reply = select("get", ",\"runId\":\"${run.runId}\"", by = by)
        assertTrue(reply.ok(), "get must answer: $reply")
        return assertNotNull(reply.data, "get data: $reply")
    }

    private fun objectsWithKey(
        within: JsonElement?,
        key: String
    ): List<JsonObject> =
        when (within) {
            is JsonObject -> (if (key in within) listOf(within) else emptyList()) + within.values.flatMap { objectsWithKey(it, key) }
            is JsonArray -> within.flatMap { objectsWithKey(it, key) }
            else -> emptyList()
        }

    private suspend fun SeatWorld.listed(
        run: Opened,
        by: ParityToken
    ): JsonObject {
        val reply = select("list", by = by)
        assertTrue(reply.ok(), "list must answer: $reply")
        return objectsWithKey(reply.data, "stalledSeats").single { it.getValue("runId").jsonPrimitive.content == run.runId }
    }

    @Test
    @Suppress("ktlint:standard:max-line-length")
    fun `S4 a tag-scoped caller counts only the members it may see in get and in list, an unrestricted and a root-scoped one count all`(): Unit =
        world { w ->
            val s = scopedRun(w)

            assertEquals(Triple(3, 2, 1), w.view(s.run, w.orch).counts(), "unrestricted: A, B, C; seats A and C; C stalled")
            assertEquals(Triple(3, 2, 1), w.view(s.run, rootToken(w, s)).counts(), "a root scope that covers the run's root sees all")
            assertEquals(
                Triple(2, 1, 0),
                w.view(s.run, tagToken()).counts(),
                "tag plain-task: A and B; A's seat; C's stalled seat is hidden"
            )

            assertEquals(Triple(3, 2, 1), w.listed(s.run, w.orch).counts(), "list, unrestricted")
            assertEquals(Triple(3, 2, 1), w.listed(s.run, rootToken(w, s)).counts(), "list, root scope")
            assertEquals(Triple(2, 1, 0), w.listed(s.run, tagToken()).counts(), "list, tag scope")
        }

    @Test
    fun `S4 probe a tag-scoped caller whose tag matches the root but no member counts zero where unrestricted counts all`(): Unit =
        world { w ->
            val root = w.rig.seed("Tagged root", tags = "plain-task")
            val x = w.rig.seed("X other tag", parent = root, tags = "seated-task")
            val y = w.rig.seed("Y other tag", parent = root, tags = "free-task")
            val run = w.open(members = listOf(x, y), rootItem = root, extra = ",\"stallAfterSeconds\":120")
            w.assignOk(run, x, "builder")

            assertEquals(Triple(2, 1, 0), w.view(run, w.orch).counts(), "control: unrestricted sees both members and the seat")
            assertEquals(Triple(0, 0, 0), w.view(run, tagToken()).counts(), "no member carries the caller's tag")
        }

    @Test
    fun `S5 the frontier in-flight list is scoped the same way - the tag-scoped caller sees A only, the others see A and C`(): Unit =
        world { w ->
            val s = scopedRun(w)

            suspend fun inFlight(by: ParityToken): List<String> {
                val reply = w.select("frontier", ",\"runId\":\"${s.run.runId}\"", by = by)
                assertTrue(reply.ok(), "frontier must answer: $reply")
                return assertNotNull(reply.data, "frontier data").getValue("inFlight").jsonArray.map {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                }
            }

            fun short(item: WorkItem) = item.id.toString().substring(0, 8)

            assertEquals(setOf(short(s.a), short(s.c)), inFlight(w.orch).toSet(), "unrestricted: both seats")
            assertEquals(setOf(short(s.a), short(s.c)), inFlight(rootToken(w, s)).toSet(), "root scope: both seats")
            assertEquals(listOf(short(s.a)), inFlight(tagToken()), "tag scope: only A")
            assertFalse(
                short(s.c) in w.select("frontier", ",\"runId\":\"${s.run.runId}\"", by = tagToken()).toString(),
                "C is named nowhere"
            )
        }
}
