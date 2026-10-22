package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.short
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 57d4dfc0, R26) for the typed blocked reason: `advance` `transition` with `reason` and
 * `decisionId` on `block` and `hold`, the stored columns, the `item.transitioned` payload, the reads that report the reason
 * (`items_read` `get`, `context`, `blocked`, `overview`; `runs_read` `frontier`) and `runs_write` `raise` with `block`. Every call goes
 * through the real MCP adapter and the production dispatcher over the real composition and SQLite, on the seat test world of
 * `RunsSeatOpsTest`.
 *
 * Scenarios S1 to S18 of the frozen test-plan (S19 lives in `BlockedReasonDomainTest`, S20 in `SeedV37`) with its probe list.
 * Surface labels: every scenario is NEW-SURFACE except S15 (EXISTING-SURFACE: it reads a row written by the old schema).
 *
 * Oracles (never read from a run): the W5 brief 3.6 and 3.7 as the frozen task-scope D1 to D12 state them. D1: the four reasons, the
 * default `manual`, `NULL` on a blocked row reads as `manual`. D2: the stored reason and ref exist only while the item is blocked, a ref
 * goes with `awaiting_decision` only. D3: the request rules in order, all `invalid_request` with a field pointer: (a) `reason` or
 * `decisionId` with a trigger other than block or hold is refused on `/reason` and/or `/decisionId`; (b) a `decisionId` with an effective
 * reason other than `awaiting_decision` is refused on `/decisionId`; (c) `awaiting_decision` without a `decisionId` is refused on
 * `/decisionId`; (d) the decision must exist, belong to this item (a run-scoped or root-scoped decision does not) and be pending (open
 * or acknowledged). The rules run before the transition policy. D5: block and hold store the effective reason and the ref; every other
 * target clears both. D6: the `item.transitioned` payload of a transition into blocked adds `blockedReason` (and `decisionId` when set)
 * and no other transition's payload gains either key. D7: `raise{block: true}` blocks the item as `awaiting_decision` in the same unit,
 * a refused block refuses the raise, a replay does not block twice, an already-answered decision does not block, `blocked` is in the
 * result only when the request set `block: true`, and an answer never resumes the item (plan D1). D8: reads report `blockedReason` /
 * `decisionId` on get and context, `reason` / `decisionId` / `resumable` on blocked entries (`resumable` on awaiting_decision entries
 * only, true when the decision is answered, withdrawn or gone), `blockedByReason` beside `childCounts` (zero counts omitted, member
 * omitted when empty, NULL counted as manual) and the reason of a blocked member in the frontier `waiting` list.
 *
 * Self-resolved from public evidence (task-scope text, `current/docs/api-rest.md` and the existing items, seat, decision and frontier
 * tests, not from the implementation): blocked entries are located as the objects that carry a `reason` and the item's short id, so
 * the nesting of the entry is not decided by a test; `get` and `context` members are looked up by name anywhere in the result; the
 * scoped overview carries `blockedByReason` at the top level of the data and the global and anchored overviews on each entry; the
 * frontier `waiting` entries carry `id`, `reason`, `blockers` and `decisionId`; `raise` returns the decision id as `decisionId` or
 * `id`, and `existing` as in `DecisionToolsTest`; the event payload members are `blockedReason` and `decisionId`.
 *
 * Negative-assertion fixture conditions: every refusal is paired with the same call that succeeds when the single input it turns on
 * is changed (the missing `decisionId`, the pending decision, the other trigger), and asserts that the role and both stored columns of
 * the item are unchanged and that no `item.transitioned` row was written.
 *
 * Domain invariants satisfied by construction: items are children of their root and seeded through the rig; decisions are raised
 * through the tool; blocked rows are produced by the transitions under test, and the one legacy row is the pre-V37 shape (role
 * blocked, previous role set, both new columns NULL) written with SQL.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions. The probes that name a malformed
 * `decisionId` assert only that the call is refused and the item unchanged (no oracle names the error code of those inputs).
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class BlockedReasonTest {
    private val clock = SettableClock(Instant.parse("2026-10-14T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private companion object {
        const val CONFIG = """
work_item_schemas:
  seated-task:
    notes: []
  plain-task:
    notes: []
decision_classes:
  signoff:
    answerers: human-only
"""
        val WIRE = setOf("awaiting_decision", "dependency", "resource", "manual")
    }

    private val caps = setOf(ApiCapability.RUNS, ApiCapability.READ, ApiCapability.DECIDE, ApiCapability.ADVANCE, ApiCapability.WRITE_ITEMS)
    private val bkOrch = ParityToken("bk-orch", caps)
    private val bkHuman =
        ParityToken("bk-human", setOf(ApiCapability.RUNS, ApiCapability.READ, ApiCapability.DECIDE), kind = PrincipalKind.HUMAN)

    private fun world(block: suspend (SeatWorld) -> Unit) =
        runBlocking {
            val w = SeatWorld.create(db.db, dir, clock, CONFIG)
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    // ------------------------------------------------------------------------------------------------------------ calls

    private fun field(
        name: String,
        value: String
    ) = ",\"$name\":\"$value\""

    private suspend fun SeatWorld.step(
        item: WorkItem,
        trigger: String,
        extra: String = "",
        by: ParityToken = bkOrch
    ): Reply = ops("advance", "{\"op\":\"transition\",\"itemId\":\"${item.id}\",\"trigger\":\"$trigger\"$extra}", by = by)

    private suspend fun SeatWorld.startOk(item: WorkItem) {
        val reply = step(item, "start")
        assertTrue(reply.ok(), "start must succeed: $reply")
    }

    private fun raiseElement(
        key: String,
        extra: String
    ) = "{\"op\":\"raise\",\"class\":\"question\",\"question\":\"Which one?\"," +
        "\"options\":[{\"key\":\"a\",\"label\":\"A\"},{\"key\":\"b\",\"label\":\"B\"}],\"dedupeKey\":\"$key\"$extra}"

    private suspend fun SeatWorld.raise(
        key: String,
        extra: String
    ): Reply = ops("runs_write", raiseElement(key, extra), by = bkOrch)

    private fun decisionIdOf(reply: Reply): String = assertNotNull(reply.find("decisionId") ?: reply.find("id"), "a decision id in $reply")

    /** Raises a decision about [item] and returns its full id. */
    private suspend fun SeatWorld.decisionOn(
        item: WorkItem,
        key: String
    ): String {
        val reply = raise(key, field("itemId", item.id.toString()))
        assertTrue(reply.ok(), "raise must succeed: $reply")
        return decisionIdOf(reply)
    }

    private suspend fun SeatWorld.answer(id: String): Reply =
        director("runs_write", "{\"ops\":[{\"op\":\"answer\",\"decisionId\":\"$id\",\"option\":\"a\"}]}", bkHuman)

    private suspend fun SeatWorld.ack(id: String): Reply =
        director("runs_write", "{\"ops\":[{\"op\":\"ack\",\"decisionId\":\"$id\"}]}", bkHuman)

    private suspend fun SeatWorld.withdraw(id: String): Reply =
        director("runs_write", "{\"ops\":[{\"op\":\"withdraw\",\"decisionId\":\"$id\"}]}", bkOrch)

    private suspend fun SeatWorld.getItem(item: WorkItem): Reply =
        call("items_read", "{\"select\":\"get\",\"itemId\":\"${item.id}\"}", by = bkOrch)

    private suspend fun SeatWorld.contextOf(item: WorkItem): Reply =
        call("items_read", "{\"select\":\"context\",\"itemId\":\"${item.id}\"}", by = bkOrch)

    private suspend fun SeatWorld.overview(args: String): Reply = call("items_read", "{\"select\":\"overview\"$args}", by = bkOrch)

    private fun objectsWith(
        within: JsonElement?,
        key: String
    ): List<JsonObject> =
        when (within) {
            is JsonObject -> (if (key in within) listOf(within) else emptyList()) + within.values.flatMap { objectsWith(it, key) }
            is JsonArray -> within.flatMap { objectsWith(it, key) }
            else -> emptyList()
        }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

    /** The `blocked` select entry of [item]: the object that carries a wire `reason` and the item's short id. */
    private suspend fun SeatWorld.blockedEntry(item: WorkItem): JsonObject {
        val reply = call("items_read", "{\"select\":\"blocked\",\"limit\":200}", by = bkOrch)
        assertTrue(reply.ok(), "blocked must succeed: $reply")
        val sid = short(item.id)
        val matches = objectsWith(reply.data, "reason").filter { it.text("reason") in WIRE && sid in it.toString() }
        return assertNotNull(matches.singleOrNull(), "exactly one blocked entry carries a reason for $sid: $reply")
    }

    // ----------------------------------------------------------------------------------------------------- state reads

    private fun SeatWorld.reasonOf(item: WorkItem): String? = itemRow(item)["blocked_reason"] as String?

    private fun SeatWorld.refOf(item: WorkItem): UUID? = itemRow(item)["blocked_ref"]?.let { uuidOf(it) }

    private fun SeatWorld.roleOf(item: WorkItem): String = itemRow(item)["role"] as String

    private fun SeatWorld.assertState(
        item: WorkItem,
        role: String,
        reason: String?,
        ref: UUID?,
        label: String
    ) {
        assertEquals(role, roleOf(item), "$label: role")
        assertEquals(reason, reasonOf(item), "$label: blocked_reason")
        assertEquals(ref, refOf(item), "$label: blocked_ref")
    }

    private fun SeatWorld.transitionPayloads(): List<JsonObject> =
        rows("SELECT data FROM events WHERE type = 'item.transitioned' ORDER BY seq").map {
            Json.parseToJsonElement(it["data"] as String).jsonObject
        }

    private fun SeatWorld.blockPayloads(): List<JsonObject> = transitionPayloads().filter { "blockedReason" in it }

    private fun SeatWorld.eventCount(type: String): Int = rows("SELECT seq FROM events WHERE type = ?", type).size

    private fun SeatWorld.decisionCount(): Int = rows("SELECT id FROM decision_requests").size

    private fun SeatWorld.decisionStatus(id: String): String =
        rows("SELECT status FROM decision_requests WHERE id = ?", UUID.fromString(id)).single()["status"] as String

    private fun assertInvalid(
        reply: Reply,
        pointer: String,
        label: String
    ) {
        assertEquals("invalid_request", reply.code(), "$label: $reply")
        assertTrue(reply.pointers().any { it.endsWith(pointer) }, "$label: expected $pointer in ${reply.pointers()}: $reply")
    }

    /** Writes the pre-V37 shape of a blocked row: role blocked, a previous role, both new columns NULL. */
    private fun SeatWorld.makeLegacyBlocked(item: WorkItem) {
        exec(
            "UPDATE work_items SET role = 'blocked', previous_role = 'queue', blocked_reason = NULL, blocked_ref = NULL WHERE id = ?",
            item.id
        )
    }

    // -------------------------------------------------------------------------------------------------------- S1 and S2

    @Test
    fun `S1 block on a work item with no reason stores manual, reads as manual and records it on the event`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)
            val before = w.transitionPayloads().size

            val reply = w.step(item, "block")

            assertTrue(reply.ok(), "$reply")
            w.assertState(item, "blocked", "manual", null, "after a block with no reason")
            val got = w.getItem(item)
            assertEquals("manual", got.find("blockedReason"), "get reports the default reason: $got")
            assertNull(got.findElement("decisionId"), "a manual block has no decision id: $got")
            val payloads = w.transitionPayloads()
            assertEquals(before + 1, payloads.size, "exactly one transition row was written")
            assertEquals(
                "manual",
                payloads.last().text("blockedReason"),
                "the block event carries the effective reason: ${payloads.last()}"
            )
            assertFalse("decisionId" in payloads.last(), "and no decision id: ${payloads.last()}")
            for (earlier in payloads.dropLast(1)) {
                assertFalse(
                    "blockedReason" in earlier || "decisionId" in earlier,
                    "a transition that is not into blocked is unchanged: $earlier"
                )
            }
        }

    @Test
    fun `S2 hold takes the same reasons as block and each stored reason is reported on the blocked entry`(): Unit =
        world { w ->
            val held = w.m2
            val blocked = w.m3
            w.startOk(held)
            w.startOk(blocked)

            assertTrue(w.step(held, "hold", field("reason", "resource")).ok())
            assertTrue(w.step(blocked, "block", field("reason", "dependency")).ok())

            assertEquals("resource", w.reasonOf(held))
            assertEquals("dependency", w.reasonOf(blocked))
            val heldEntry = w.blockedEntry(held)
            assertEquals("resource", heldEntry.text("reason"))
            assertFalse("resumable" in heldEntry, "resumable is for awaiting_decision entries only: $heldEntry")
            assertFalse("decisionId" in heldEntry, "$heldEntry")
            assertEquals("dependency", w.blockedEntry(blocked).text("reason"))
        }

    @Test
    fun `S2 hold with no reason is manual, the same as block with no reason`(): Unit =
        world { w ->
            w.startOk(w.m2)
            assertTrue(w.step(w.m2, "hold").ok())
            assertEquals("manual", w.reasonOf(w.m2), "hold defaults to manual")
            assertEquals("manual", w.blockedEntry(w.m2).text("reason"))
            assertEquals("manual", w.getItem(w.m2).find("blockedReason"))
        }

    // -------------------------------------------------------------------------------------------------------- S3 and S4

    @Test
    fun `S3 an awaiting_decision block by decision prefix stores the full id and reads it everywhere`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)
            val id = w.decisionOn(item, "k1")

            val reply = w.step(item, "block", field("reason", "awaiting_decision") + field("decisionId", id.substring(0, 8)))

            assertTrue(reply.ok(), "$reply")
            w.assertState(item, "blocked", "awaiting_decision", UUID.fromString(id), "after an awaiting_decision block")
            val got = w.getItem(item)
            assertEquals("awaiting_decision", got.find("blockedReason"), "$got")
            assertEquals(id, got.find("decisionId"), "get reports the full decision id: $got")
            val ctx = w.contextOf(item)
            assertEquals("awaiting_decision", ctx.find("blockedReason"), "context item mode: $ctx")
            assertEquals(id, ctx.find("decisionId"), "context item mode: $ctx")
            val entry = w.blockedEntry(item)
            assertEquals("awaiting_decision", entry.text("reason"))
            assertEquals(id, entry.text("decisionId"))
            assertEquals("false", entry.text("resumable"), "the decision is pending: $entry")
            val event = w.blockPayloads().single()
            assertEquals("awaiting_decision", event.text("blockedReason"))
            assertEquals(id, event.text("decisionId"), "the event carries the full decision id: $event")
        }

    @Test
    fun `S3 the health view of context reports the reason and decision of a blocked item and no resumable`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)
            val id = w.decisionOn(item, "k1")
            assertTrue(w.step(item, "block", field("reason", "awaiting_decision") + field("decisionId", id)).ok())

            val health = w.call("items_read", "{\"select\":\"context\",\"limit\":200}", by = bkOrch)

            assertTrue(health.ok(), "$health")
            val sid = short(item.id)
            val entry =
                assertNotNull(
                    objectsWith(health.data, "reason").singleOrNull { it.text("reason") in WIRE && sid in it.toString() },
                    "the health view lists the blocked item with its reason: $health"
                )
            assertEquals("awaiting_decision", entry.text("reason"))
            assertEquals(id, entry.text("decisionId"))
            assertFalse("resumable" in entry, "the health view carries row values only: $entry")
        }

    @Test
    fun `S4 an answer records only, the item stays blocked and becomes resumable, and resume clears both columns`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)
            val id = w.decisionOn(item, "k1")
            assertTrue(w.step(item, "block", field("reason", "awaiting_decision") + field("decisionId", id)).ok())
            val transitions = w.transitionPayloads().size

            val answered = w.answer(id)

            assertTrue(answered.ok(), "$answered")
            assertEquals("answered", w.decisionStatus(id))
            w.assertState(item, "blocked", "awaiting_decision", UUID.fromString(id), "an answer does not resume the item")
            assertEquals(transitions, w.transitionPayloads().size, "and writes no transition")
            val entry = w.blockedEntry(item)
            assertEquals("true", entry.text("resumable"), "an answered decision makes the entry resumable: $entry")
            assertEquals(id, entry.text("decisionId"), "the entry keeps the decision id: $entry")

            val resumed = w.step(item, "resume")

            assertTrue(resumed.ok(), "$resumed")
            w.assertState(item, "work", null, null, "after resume")
            val got = w.getItem(item)
            assertNull(got.findElement("blockedReason"), "a resumed item reports no reason: $got")
            assertNull(got.findElement("decisionId"), "and no decision id: $got")
            val last = w.transitionPayloads().last()
            assertFalse("blockedReason" in last || "decisionId" in last, "the resume row is unchanged in shape: $last")
        }

    @Test
    fun `S4 a withdrawn decision also makes the entry resumable and does not resume the item`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)
            val id = w.decisionOn(item, "k1")
            assertTrue(w.step(item, "block", field("reason", "awaiting_decision") + field("decisionId", id)).ok())
            assertEquals("false", w.blockedEntry(item).text("resumable"), "control: pending")

            assertTrue(w.withdraw(id).ok())

            assertEquals("withdrawn", w.decisionStatus(id))
            assertEquals("blocked", w.roleOf(item), "a withdrawal does not resume the item")
            assertEquals("true", w.blockedEntry(item).text("resumable"))
        }

    // ---------------------------------------------------------------------------------------------------------------- S5

    @Test
    fun `S5 raise with block on a work item raises one decision and blocks the item in the same call`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)
            val mark = w.rows("SELECT coalesce(max(seq), 0) AS m FROM events").single()["m"] as Long

            val reply = w.raise("k1", field("itemId", item.id.toString()) + ",\"block\":true")

            assertTrue(reply.ok(), "$reply")
            assertEquals("true", reply.find("blocked"), "the result says the item was blocked: $reply")
            val id = decisionIdOf(reply)
            w.assertState(item, "blocked", "awaiting_decision", UUID.fromString(id), "after raise with block")
            val types = w.rows("SELECT type FROM events WHERE seq > ? ORDER BY seq", mark).map { it["type"] as String }
            assertEquals(1, types.count { it == "decision.raised" }, "one decision.raised: $types")
            assertEquals(1, types.count { it == "item.transitioned" }, "one item.transitioned: $types")
            val event = w.blockPayloads().single()
            assertEquals("awaiting_decision", event.text("blockedReason"))
            assertEquals(id, event.text("decisionId"))
            assertEquals("false", w.blockedEntry(item).text("resumable"))
        }

    @Test
    fun `S5 a raise without block, or with block false, leaves the item alone and reports no blocked member`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)

            val plain = w.raise("k1", field("itemId", item.id.toString()))
            val off = w.raise("k2", field("itemId", item.id.toString()) + ",\"block\":false")

            assertTrue(plain.ok() && off.ok(), "$plain $off")
            assertNull(plain.findElement("blocked"), "no block requested, no blocked member: $plain")
            assertNull(off.findElement("blocked"), "block false is not a block request: $off")
            w.assertState(item, "work", null, null, "after raises that did not ask to block")
            assertEquals(2, w.decisionCount())
            assertEquals(0, w.blockPayloads().size, "no transition into blocked was written")
        }

    // -------------------------------------------------------------------------------------------------------------- S6

    @Test
    fun `S6 overview counts blocked children by reason on every entry, scoped, global and anchored`(): Unit =
        world { w ->
            val parent = w.rig.seed("Blocky root")
            val c1 = w.rig.seed("manual child", parent = parent, tags = "plain-task")
            val c2 = w.rig.seed("awaiting child", parent = parent, tags = "plain-task")
            val c3 = w.rig.seed("legacy child", parent = parent, tags = "plain-task")
            val c4 = w.rig.seed("working child", parent = parent, tags = "plain-task")
            val g1 = w.rig.seed("grandchild", parent = c4, tags = "plain-task")
            for (item in listOf(c1, c2, c4)) w.startOk(item)
            w.startOk(g1)
            assertTrue(w.step(c1, "block").ok())
            val id = w.decisionOn(c2, "k1")
            assertTrue(w.step(c2, "block", field("reason", "awaiting_decision") + field("decisionId", id)).ok())
            w.makeLegacyBlocked(c3)
            assertTrue(w.step(g1, "hold", field("reason", "resource")).ok())

            fun JsonObject.byReason(): Map<String, Int>? =
                (this["blockedByReason"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.int }

            val scoped = w.overview(",\"itemId\":\"${parent.id}\"")
            assertTrue(scoped.ok(), "$scoped")
            assertEquals(
                mapOf("awaiting_decision" to 1, "manual" to 2),
                scoped.data!!.byReason(),
                "direct children only, the NULL row counted as manual, zero counts omitted: $scoped"
            )

            val global = w.overview("")
            assertTrue(global.ok(), "$global")
            val entries = global.data!!["items"]!!.jsonArray.map { it.jsonObject }
            val blocky = entries.single { it.text("title") == "Blocky root" }
            assertEquals(mapOf("awaiting_decision" to 1, "manual" to 2), blocky.byReason(), "global entry: $blocky")
            val clean = entries.single { it.text("title") == "Other root" }
            assertFalse("blockedByReason" in clean, "a root with nothing blocked below omits the member: $clean")

            val anchored = w.overview(",\"anchorId\":\"${parent.id}\"")
            assertTrue(anchored.ok(), "$anchored")
            val rolled = anchored.data!!["items"]!!.jsonArray.map { it.jsonObject }
            assertEquals(
                mapOf("resource" to 1),
                rolled.single { it.text("title") == "working child" }.byReason(),
                "an anchored entry rolls up its full subtree: the grandchild's resource block"
            )
        }

    // -------------------------------------------------------------------------------------------------------------- S7

    @Test
    fun `S7 the frontier waiting list reports the typed reason and the decision id of a blocked member`(): Unit =
        world { w ->
            val run = w.open(members = listOf(w.m2, w.m3), by = bkOrch)
            val id = w.decisionOn(w.m2, "k1")
            assertTrue(w.step(w.m2, "block", field("reason", "awaiting_decision") + field("decisionId", id)).ok())
            assertTrue(w.step(w.m3, "block").ok())

            val frontier = w.select("frontier", ",\"runId\":\"${run.runId}\"", by = bkOrch)

            assertTrue(frontier.ok(), "$frontier")
            val waiting = frontier.data!!["waiting"]!!.jsonArray.map { it.jsonObject }
            val awaiting = waiting.single { it.text("id") == short(w.m2.id) }
            assertEquals("awaiting_decision", awaiting.text("reason"), "$awaiting")
            assertEquals(id, awaiting.text("decisionId"), "the full decision id: $awaiting")
            assertEquals(0, (awaiting["blockers"] as? JsonArray)?.size ?: 0, "a blocked member has no blockers: $awaiting")
            val manual = waiting.single { it.text("id") == short(w.m3.id) }
            assertEquals("manual", manual.text("reason"), "$manual")
            assertFalse("decisionId" in manual, "$manual")
        }

    // ---------------------------------------------------------------------------------------------------------- S8 to S12

    @Test
    fun `S8 reason or decisionId with a trigger other than block or hold is invalid_request on the field and changes nothing`(): Unit =
        world { w ->
            val item = w.m2
            val id = w.decisionOn(item, "k1")

            val start = w.step(item, "start", field("reason", "manual"))
            assertInvalid(start, "/reason", "reason on start")
            w.assertState(item, "queue", null, null, "after a refused start")
            val cancel = w.step(item, "cancel", field("reason", "resource"))
            assertInvalid(cancel, "/reason", "reason on cancel")
            w.assertState(item, "queue", null, null, "after a refused cancel")
            val both = w.step(item, "start", field("reason", "awaiting_decision") + field("decisionId", id))
            assertInvalid(both, "/reason", "both fields on start, the reason")
            assertInvalid(both, "/decisionId", "both fields on start, the decision")
            assertEquals(0, w.transitionPayloads().size, "no transition row for any refused call")
            assertTrue(w.step(item, "start").ok(), "control: the same start without the field succeeds")
        }

    @Test
    fun `S8 resume with a reason or a decisionId is refused and the item keeps its block`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)
            val id = w.decisionOn(item, "k1")
            assertTrue(w.step(item, "block", field("reason", "awaiting_decision") + field("decisionId", id)).ok())
            val ref = UUID.fromString(id)

            assertInvalid(w.step(item, "resume", field("reason", "manual")), "/reason", "reason on resume")
            w.assertState(item, "blocked", "awaiting_decision", ref, "after a refused resume with a reason")
            assertInvalid(w.step(item, "resume", field("decisionId", id)), "/decisionId", "decisionId on resume")
            w.assertState(item, "blocked", "awaiting_decision", ref, "after a refused resume with a decision id")
            assertTrue(w.step(item, "resume").ok(), "control: a bare resume succeeds")
            w.assertState(item, "work", null, null, "after the bare resume")
        }

    @Test
    fun `S9 a decisionId with no reason or with reason manual is invalid_request on decisionId`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)
            val id = w.decisionOn(item, "k1")

            assertInvalid(w.step(item, "block", field("decisionId", id)), "/decisionId", "decisionId with no reason")
            assertInvalid(
                w.step(item, "block", field("reason", "manual") + field("decisionId", id)),
                "/decisionId",
                "decisionId with manual"
            )
            assertInvalid(
                w.step(item, "hold", field("reason", "dependency") + field("decisionId", id)),
                "/decisionId",
                "decisionId with dependency"
            )
            assertInvalid(
                w.step(item, "hold", field("reason", "resource") + field("decisionId", id)),
                "/decisionId",
                "decisionId with resource"
            )
            w.assertState(item, "work", null, null, "after the refusals")
            assertTrue(
                w.step(item, "block", field("reason", "awaiting_decision") + field("decisionId", id)).ok(),
                "control: the same decision with awaiting_decision succeeds"
            )
        }

    @Test
    fun `S9 the request rules run before the transition policy`(): Unit =
        world { w ->
            val item = w.m2
            val id = w.decisionOn(item, "k1")
            assertTrue(w.step(item, "cancel").ok(), "the item is terminal")

            val refused = w.step(item, "block", field("reason", "manual") + field("decisionId", id))

            assertInvalid(refused, "/decisionId", "the field rule is reported, not the transition error")
            val policy = w.step(item, "block", field("reason", "manual"))
            assertEquals(
                "invalid_transition",
                policy.code(),
                "control: a well-formed block of a terminal item is the policy error: $policy"
            )
        }

    @Test
    fun `S10 awaiting_decision with no decisionId is invalid_request on decisionId`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)

            assertInvalid(w.step(item, "block", field("reason", "awaiting_decision")), "/decisionId", "awaiting_decision, block")
            assertInvalid(w.step(item, "hold", field("reason", "awaiting_decision")), "/decisionId", "awaiting_decision, hold")
            w.assertState(item, "work", null, null, "after the refusals")
        }

    @Test
    fun `S11 a decision about another item, a run or a root is refused as invalid_request on decisionId`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)
            val other = w.decisionOn(w.m3, "other-item")
            val run = w.open(members = listOf(w.m2, w.m3), by = bkOrch)
            val runScoped = decisionIdOf(w.raise("run-scoped", field("runId", run.runId)).also { assertTrue(it.ok(), "$it") })
            val rootScoped = decisionIdOf(w.raise("root-scoped", field("rootId", w.root.id.toString())).also { assertTrue(it.ok(), "$it") })

            for ((label, id) in listOf("another item's" to other, "a run-scoped" to runScoped, "a root-scoped" to rootScoped)) {
                val reply = w.step(item, "block", field("reason", "awaiting_decision") + field("decisionId", id))
                assertInvalid(reply, "/decisionId", "$label decision")
                w.assertState(item, "work", null, null, "after the refusal of $label decision")
            }
            val own = w.decisionOn(item, "own")
            assertTrue(
                w.step(item, "block", field("reason", "awaiting_decision") + field("decisionId", own)).ok(),
                "control: this item's own decision is accepted"
            )
        }

    @Test
    fun `S12 an answered or a withdrawn decision cannot be waited on`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)
            val answered = w.decisionOn(item, "answered")
            val withdrawn = w.decisionOn(item, "withdrawn")
            assertTrue(w.answer(answered).ok())
            assertTrue(w.withdraw(withdrawn).ok())

            for ((label, id) in listOf("answered" to answered, "withdrawn" to withdrawn)) {
                val reply = w.step(item, "block", field("reason", "awaiting_decision") + field("decisionId", id))
                assertInvalid(reply, "/decisionId", "$label decision")
                w.assertState(item, "work", null, null, "after the refusal of the $label decision")
            }
            val pending = w.decisionOn(item, "pending")
            assertTrue(
                w.step(item, "block", field("reason", "awaiting_decision") + field("decisionId", pending)).ok(),
                "control: a pending decision is accepted"
            )
        }

    @Test
    fun `S12 a decision that does not exist is invalid_request on decisionId`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)

            val reply =
                w.step(
                    item,
                    "block",
                    field("reason", "awaiting_decision") + field("decisionId", "0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f")
                )

            assertInvalid(reply, "/decisionId", "a well-formed id that names no decision")
            w.assertState(item, "work", null, null, "after the refusal")
        }

    // ------------------------------------------------------------------------------------------------------------- S13

    @Test
    fun `S13 raise with block on a blocked or terminal item is refused and raises nothing`(): Unit =
        world { w ->
            val blocked = w.m2
            val finished = w.m3
            w.startOk(blocked)
            assertTrue(w.step(blocked, "block").ok())
            assertTrue(w.step(finished, "cancel").ok())
            val transitions = w.transitionPayloads().size

            for (item in listOf(blocked, finished)) {
                val reply = w.raise("k-${item.id}", field("itemId", item.id.toString()) + ",\"block\":true")
                assertEquals("invalid_transition", reply.code(), "block on a ${w.roleOf(item)} item: $reply")
            }

            assertEquals(0, w.decisionCount(), "the refused block rolled the raise back: no decision row")
            assertEquals(0, w.eventCount("decision.raised"), "and no decision.raised event")
            assertEquals(transitions, w.transitionPayloads().size, "and no transition row")
            w.assertState(blocked, "blocked", "manual", null, "the blocked item is untouched")

            val control = w.rig.seed("control item", parent = w.root, tags = "plain-task")
            w.startOk(control)
            val ok = w.raise("control", field("itemId", control.id.toString()) + ",\"block\":true")
            assertTrue(ok.ok(), "control: the same raise with block on a work item succeeds: $ok")
            assertEquals("blocked", w.roleOf(control))
        }

    // ------------------------------------------------------------------------------------------------------------- S14

    @Test
    fun `S14 raise with block and no item is invalid_request on block`(): Unit =
        world { w ->
            val reply = w.raise("k1", field("rootId", w.root.id.toString()) + ",\"block\":true")

            assertInvalid(reply, "/block", "block needs an item")
            assertEquals(0, w.decisionCount(), "nothing was raised")
            assertTrue(w.raise("k2", field("rootId", w.root.id.toString())).ok(), "control: the same raise without block succeeds")
        }

    // ------------------------------------------------------------------------------------------------------------- S15

    @Test
    fun `S15 a legacy blocked row with NULL reads as manual everywhere and stays NULL`(): Unit =
        world { w ->
            val item = w.m2
            w.makeLegacyBlocked(item)
            val run = w.open(members = listOf(w.m2, w.m3), by = bkOrch)

            val got = w.getItem(item)
            assertEquals("manual", got.find("blockedReason"), "get: $got")
            assertNull(got.findElement("decisionId"), "get: $got")
            val entry = w.blockedEntry(item)
            assertEquals("manual", entry.text("reason"), "blocked: $entry")
            assertFalse("resumable" in entry, "$entry")
            val scoped = w.overview(",\"itemId\":\"${w.root.id}\"")
            assertEquals(
                mapOf("manual" to 1),
                (scoped.data!!["blockedByReason"] as JsonObject).mapValues { it.value.jsonPrimitive.int },
                "overview counts the NULL row as manual: $scoped"
            )
            val frontier = w.select("frontier", ",\"runId\":\"${run.runId}\"", by = bkOrch)
            val waiting =
                frontier.data!!["waiting"]!!
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.text("id") == short(item.id) }
            assertEquals("manual", waiting.text("reason"), "frontier: $waiting")
            w.assertState(item, "blocked", null, null, "reads never rewrite the legacy row")

            assertTrue(w.step(item, "resume").ok(), "control: a legacy blocked row can be resumed")
            w.assertState(item, "queue", null, null, "after resume")
        }

    // ------------------------------------------------------------------------------------------------------------- S16

    @Test
    fun `S16 cancelling an awaiting_decision item clears both columns`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)
            val id = w.decisionOn(item, "k1")
            assertTrue(w.step(item, "block", field("reason", "awaiting_decision") + field("decisionId", id)).ok())

            val cancelled = w.step(item, "cancel")

            assertTrue(cancelled.ok(), "$cancelled")
            w.assertState(item, "terminal", null, null, "after cancel out of blocked")
            val got = w.getItem(item)
            assertNull(got.findElement("blockedReason"), "$got")
            assertNull(got.findElement("decisionId"), "$got")
        }

    @Test
    fun `S16 a cascade never moves a blocked parent, so it keeps its reason and decision`(): Unit =
        world { w ->
            // Control: in this fixture, completing the only child of a working parent completes the parent.
            val liveParent = w.rig.seed("live cascade parent", parent = w.root, role = Role.WORK, tags = "plain-task")
            val liveChild = w.rig.seed("live cascade child", parent = liveParent, role = Role.WORK, tags = "plain-task")
            assertTrue(w.step(liveChild, "complete").ok())
            assertEquals("terminal", w.roleOf(liveParent), "control: the cascade is live for a working parent")

            val parent = w.rig.seed("cascade parent", parent = w.root, role = Role.WORK, tags = "plain-task")
            val child = w.rig.seed("cascade child", parent = parent, role = Role.WORK, tags = "plain-task")
            val id = w.decisionOn(parent, "k1")
            assertTrue(w.step(parent, "block", field("reason", "awaiting_decision") + field("decisionId", id)).ok())
            val ref = UUID.fromString(id)
            w.assertState(parent, "blocked", "awaiting_decision", ref, "control: the parent is blocked before the cascade")

            val completed = w.step(child, "complete")

            assertTrue(completed.ok(), "$completed")
            assertEquals("role", completed.find("blocked"), "the cascade is suppressed because the parent is blocked: $completed")
            assertEquals("false", completed.find("applied"), "$completed")
            w.assertState(
                parent,
                "blocked",
                "awaiting_decision",
                ref,
                "a suppressed cascade leaves the blocked parent and its columns alone"
            )
        }

    @Test
    fun `S16 completing a tree with cancel takes a blocked item to terminal and clears both columns`(): Unit =
        world { w ->
            val parent = w.rig.seed("tree parent", parent = w.root, role = Role.WORK, tags = "plain-task")
            val child = w.rig.seed("tree child", parent = parent, role = Role.WORK, tags = "plain-task")
            val id = w.decisionOn(child, "k1")
            assertTrue(w.step(child, "block", field("reason", "awaiting_decision") + field("decisionId", id)).ok())
            w.assertState(
                child,
                "blocked",
                "awaiting_decision",
                UUID.fromString(id),
                "control: the child is blocked before the tree is cancelled"
            )

            val reply = w.ops("items_write", "{\"op\":\"complete_tree\",\"rootId\":\"${parent.id}\",\"trigger\":\"cancel\"}", by = bkOrch)

            assertTrue(reply.ok(), "$reply")
            w.assertState(child, "terminal", null, null, "after complete_tree cancelled the blocked child; reply: $reply")
            assertEquals("terminal", w.roleOf(parent))
        }

    // ------------------------------------------------------------------------------------------------------------- S17

    @Test
    fun `S17 a replayed raise with block does not block twice, and after an answer it returns the answer and blocks nothing`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)
            val extra = field("itemId", item.id.toString()) + ",\"block\":true"

            val first = w.raise("k1", extra)
            assertTrue(first.ok(), "$first")
            assertEquals("true", first.find("blocked"))
            assertNotEquals("true", first.find("existing"), "a first raise is new")
            val id = decisionIdOf(first)
            assertEquals(1, w.blockPayloads().size)

            val replay = w.raise("k1", extra)

            assertTrue(replay.ok(), "$replay")
            assertEquals("true", replay.find("existing"), "the replay says so: $replay")
            assertEquals("true", replay.find("blocked"), "the item is blocked on this decision: $replay")
            assertEquals(1, w.blockPayloads().size, "no second transition")
            w.assertState(item, "blocked", "awaiting_decision", UUID.fromString(id), "after the replay")

            assertTrue(w.answer(id).ok())
            val afterAnswer = w.raise("k1", extra)

            assertTrue(afterAnswer.ok(), "$afterAnswer")
            assertEquals("true", afterAnswer.find("existing"), "$afterAnswer")
            assertEquals("false", afterAnswer.find("blocked"), "an answered decision blocks nothing: $afterAnswer")
            assertEquals(1, w.blockPayloads().size, "still one transition into blocked")
            w.assertState(item, "blocked", "awaiting_decision", UUID.fromString(id), "the item is untouched by the raise after the answer")
        }

    // ------------------------------------------------------------------------------------------------------------- S18

    @Test
    fun `S18 an acknowledged decision is accepted and still pending`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)
            val id = w.decisionOn(item, "k1")
            assertTrue(w.ack(id).ok())
            assertEquals("acknowledged", w.decisionStatus(id))

            val reply = w.step(item, "block", field("reason", "awaiting_decision") + field("decisionId", id))

            assertTrue(reply.ok(), "$reply")
            w.assertState(item, "blocked", "awaiting_decision", UUID.fromString(id), "after the block")
            assertEquals("false", w.blockedEntry(item).text("resumable"), "an acknowledged decision still awaits its answer")
        }

    // ---------------------------------------------------------------------------------------------------------- probes

    @Test
    fun `probe the reason words are case and separator exact`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)

            for (bad in listOf("Manual", "MANUAL", "awaiting-decision", "awaitingDecision", "", " manual", "blocked")) {
                val reply = w.step(item, "block", field("reason", bad))
                assertFalse(reply.ok(), "reason '$bad' must be refused: $reply")
                w.assertState(item, "work", null, null, "after reason '$bad'")
            }
            assertTrue(w.step(item, "block", field("reason", "manual")).ok(), "control: the exact word is accepted")
        }

    @Test
    fun `probe a malformed, short or foreign decisionId is refused and the item is unchanged`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)
            val id = w.decisionOn(item, "k1")
            val awaiting = field("reason", "awaiting_decision")

            for (bad in listOf("", id.substring(0, 7), item.id.toString(), "not-a-uuid")) {
                val reply = w.step(item, "block", awaiting + field("decisionId", bad))
                assertFalse(reply.ok(), "decisionId '$bad' must be refused: $reply")
                w.assertState(item, "work", null, null, "after decisionId '$bad'")
            }
            assertTrue(w.step(item, "block", awaiting + field("decisionId", id)).ok(), "control: the real id is accepted")
        }

    @Test
    fun `probe another root's decision by prefix under a restricted scope is forbidden`(): Unit =
        world { w ->
            w.startOk(w.m2)
            val foreign = w.decisionOn(w.e, "foreign")
            val scoped = ParityToken("bk-scoped", caps, rootIds = setOf(w.root.id))

            val reply =
                w.ops(
                    "advance",
                    "{\"op\":\"transition\",\"itemId\":\"${w.m2.id}\",\"trigger\":\"block\"" +
                        field("reason", "awaiting_decision") + field("decisionId", foreign.substring(0, 8)) + "}",
                    by = scoped
                )

            assertEquals("forbidden", reply.code(), "$reply")
            w.assertState(w.m2, "work", null, null, "after the refusal")
        }

    @Test
    fun `probe a replayed block with the same idempotency key writes one transition`(): Unit =
        world { w ->
            val item = w.m2
            w.startOk(item)
            val extra = field("reason", "resource") + field("idempotencyKey", "block-once")

            val first = w.step(item, "block", extra)
            val second = w.step(item, "block", extra)

            assertTrue(first.ok() && second.ok(), "$first $second")
            assertEquals(1, w.blockPayloads().size, "the replay did not write a second transition")
            w.assertState(item, "blocked", "resource", null, "after the replay")
        }
}
