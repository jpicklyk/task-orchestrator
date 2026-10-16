package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.Reply
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.SEAT_WORLD_CONFIG
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.SeatWorld
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f65fef01, R29) for scenario S14 (r16 review 3): the `report` selects `gates`, `contention`
 * and the anchor `rollup` read rows that the PRODUCTION code wrote. Every event below comes from a real tool call (a gate-blocked
 * transition, a refused claim, a lease conflict); no test appends to the event store, so a mismatch between what the producers write
 * and what the aggregates read cannot hide behind hand-made rows.
 *
 * Oracles (never read from a run), the report docs in `current/docs/api-reference.md` and the R11 / R16 metric definitions: `gates`
 * counts the `transition.rejected` events of the window per missing note key (a `gate_blocked` rejection counts once per missing key;
 * `blocks` is the number of rejections and `items` the number of distinct items); `contention` counts claim and lease events per
 * resource with a `kind` of `claim` or `lease` (`acquired`, `rejected`, and `contentionRate` = rejected / (acquired + rejected) to four
 * decimals); an anchor `rollup` counts the rejections of its subtree in `transitions.rejected`. The window is half-open
 * `[since, until)`, so the clock is advanced past the events before every read.
 *
 * Negative-assertion fixture conditions: each count is taken in a fresh world where exactly one producer ran, so it can only be that
 * producer's event; the claim and lease rows are told apart by `kind`, and the rate is the control that the refused call and the
 * granted call were both counted.
 *
 * Domain invariants satisfied by construction: items are children of their root and seeded through the rig; all state changes go
 * through the tools; the config declares the gated schema (a required queue note) and the leased schema (an exclusive resource trait)
 * the way the P11 advance fixtures do.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class ReportRealProducerTest {
    private val clock = SettableClock(Instant.parse("2026-10-15T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private val config =
        SEAT_WORLD_CONFIG +
            "  gated-task:\n" +
            "    notes:\n" +
            "      - key: spec\n        role: queue\n        required: true\n" +
            "  leased-task:\n" +
            "    default_traits: [real-exclusive]\n" +
            "    notes: []\n" +
            "traits:\n" +
            "  real-exclusive:\n" +
            "    resources:\n" +
            "      - key: real-db\n        mode: exclusive\n        ttlSeconds: 600\n" +
            "resources:\n" +
            "  real-db:\n" +
            "    description: real producer lease\n"

    private fun world(block: suspend (SeatWorld) -> Unit) =
        runBlocking {
            val w = SeatWorld.create(db.db, dir, clock, config)
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    private suspend fun SeatWorld.transition(
        itemId: String,
        trigger: String,
        by: io.github.jpicklyk.mcptask.current.parity.ParityToken = orch
    ): Reply = ops("advance", "{\"op\":\"transition\",\"itemId\":\"$itemId\",\"trigger\":\"$trigger\"}", by = by)

    private suspend fun SeatWorld.report(arguments: String): Reply {
        val reply = director("report", arguments, orch)
        assertTrue(reply.ok(), "report $arguments must answer: $reply")
        return reply
    }

    private fun Reply.rows(): List<JsonObject> = assertNotNull(data, "data in $this").getValue("items").jsonArray.map { it.jsonObject }

    private fun JsonObject.keyOf(member: String): String? =
        this["key"]
            ?.jsonObject
            ?.get(member)
            ?.jsonPrimitive
            ?.content

    private fun JsonObject.number(member: String): Long = getValue(member).jsonPrimitive.content.toLong()

    private fun windowFor(w: SeatWorld): String = "\"since\":\"2026-10-15T00:00:00Z\",\"until\":\"${w.clockNow()}\""

    private fun SeatWorld.clockNow(): Instant = clock.now()

    @Test
    fun `S14 a gate-blocked transition shows up in gates by note key and by error code, and in the anchor rollup rejected count`(): Unit =
        world { w ->
            val gated = w.rig.seed("Gated item", parent = w.root, tags = "gated-task")

            val blocked = w.transition(gated.id.toString(), "start")
            assertEquals("gate_blocked", blocked.code(), "fixture: the required spec note is missing: $blocked")
            clock.advanceSeconds(60)

            val byNote = w.report("""{"select":"gates"}""").rows()
            assertEquals(1, byNote.size, "one missing key, one group: $byNote")
            assertEquals("spec", byNote.single().keyOf("noteKey"))
            assertEquals(1L, byNote.single().number("blocks"), "one rejection")
            assertEquals(1L, byNote.single().number("items"), "of one item")

            val byCode = w.report("""{"select":"gates","groupBy":"errorCode"}""").rows()
            assertEquals(listOf("gate_blocked"), byCode.map { it.keyOf("errorCode") })
            assertEquals(1L, byCode.single().number("blocks"))

            val rollup = w.report("""{"select":"rollup","anchorId":"${w.root.id}",${windowFor(w)}}""")
            val transitions = assertNotNull(rollup.data, "rollup data").getValue("transitions").jsonObject
            assertEquals(1L, transitions.number("rejected"), "the anchor rollup counts the same rejection: $transitions")
        }

    @Test
    fun `S14 control - a transition that passes its gate adds no rejection to gates or to the rollup`(): Unit =
        world { w ->
            val free = w.rig.seed("Free item", parent = w.root, tags = "plain-task")

            assertTrue(w.transition(free.id.toString(), "start").ok(), "fixture: the start passes")
            clock.advanceSeconds(60)

            assertEquals(emptyList(), w.report("""{"select":"gates"}""").rows(), "no rejection, no group")
            val rollup = w.report("""{"select":"rollup","anchorId":"${w.root.id}",${windowFor(w)}}""")
            assertEquals(0L, assertNotNull(rollup.data).getValue("transitions").jsonObject.number("rejected"))
        }

    @Test
    fun `S14 a refused claim shows up in contention as one rejected claim next to the granted one`(): Unit =
        world { w ->
            assertTrue(w.claim(w.m1, null).ok(), "fixture: the orchestrator claims the item")
            val refused = w.claim(w.m1, null, by = w.other)
            assertEquals("claim_held", refused.code(), "fixture: a second principal is refused: $refused")
            clock.advanceSeconds(60)

            val rows = w.report("""{"select":"contention"}""").rows().filter { it.getValue("kind").jsonPrimitive.content == "claim" }

            assertEquals(1, rows.size, "one claimed resource: $rows")
            assertEquals(1L, rows.single().number("acquired"), "the granted claim")
            assertEquals(1L, rows.single().number("rejected"), "the refused claim")
            assertEquals(
                "0.5",
                rows
                    .single()
                    .getValue("contentionRate")
                    .jsonPrimitive.content,
                "1 / (1 + 1)"
            )
        }

    @Test
    fun `S14 a lease conflict shows up in contention as one rejected lease on the contended key`(): Unit =
        world { w ->
            val first = w.rig.seed("Leased one", parent = w.root, tags = "leased-task")
            val second = w.rig.seed("Leased two", parent = w.root, tags = "leased-task")
            assertTrue(w.transition(first.id.toString(), "start").ok(), "fixture: the first start takes the lease")
            val conflict = w.transition(second.id.toString(), "start", by = w.other)
            assertTrue(!conflict.ok(), "fixture: the second principal cannot take the same exclusive lease: $conflict")
            clock.advanceSeconds(60)

            val rows = w.report("""{"select":"contention"}""").rows().filter { it.getValue("kind").jsonPrimitive.content == "lease" }

            assertEquals(1, rows.size, "one contended resource: $rows")
            assertEquals("real-db", rows.single().keyOf("resource"))
            assertEquals(1L, rows.single().number("acquired"), "the granted lease")
            assertEquals(1L, rows.single().number("rejected"), "the refused one")
            assertEquals(
                "0.5",
                rows
                    .single()
                    .getValue("contentionRate")
                    .jsonPrimitive.content,
                "1 / (1 + 1)"
            )
        }
}
