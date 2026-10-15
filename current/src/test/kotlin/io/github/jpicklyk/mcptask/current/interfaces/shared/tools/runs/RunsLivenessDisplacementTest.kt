package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.ExpirySweeper
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item fca8ce36, R25 round 2) for the two rulings of the orchestrator-decisions note.
 *
 * Ruling A (carry-in-r22-review NB4): `last_seen_at` is refreshed only by token-carrying writes (ops) and `advance heartbeat`; a
 * `describe: true` call never touches it, on any tool and with any token, because describe is read-only like selects.
 * Tests A1 (seat token) and A2 (coordinator token). The "op with the same token updates it" control runs at a fixed clock 30 s after
 * the last touch, outside the 5 s skip window of D9.
 *
 * Ruling B (carry-in-ar84-displacement, AR-84 residual 1): `claim` over a LAPSED claim of a DIFFERENT holder pair (principal,
 * assignment) records the existing `claim.expired` event once, before the new claim's own events, with `reason: "displaced"` and
 * `displacedBy` (the new holder's assignmentId or null), unless a sweep already recorded it (then `reason: "expired"`, and a sweep row
 * has no `displacedBy` key). The claim result entry gains `displaced: {assignmentId?, expiredAt}` plus `principal` only for a
 * principalsVisible caller (ADMIN outside a seat token, public docs api-reference.md "Claims"). A claim over an unclaimed item or over
 * the caller's own lapsed claim reports nothing. Tests B1 to B6 (names carry the id).
 *
 * Oracles: the ruling text and the declarations supplement (payload keys holder, expiresAt, reason, displacedBy; wire keys
 * assignmentId, expiredAt, principal). Expected instants are read from the fixture's own claim_expires_at column before the claim
 * lapses, never from a run of the code under test.
 *
 * Negative-assertion fixture conditions: every absence ("no displaced", "no principal", "no second claim.expired") is paired in the
 * same test or its twin with a fixture whose only changed input makes the thing appear (a foreign lapsed holder, an ADMIN caller
 * outside a seat token, no prior sweep); every "lapsed" claim is asserted lapsed (claim_expires_at <= now) before the claim under test.
 *
 * Domain invariants satisfied by construction: runs and seats are produced through the tools; claims through the claim op; the clock
 * only moves forward.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no catch around assertions, no disjunctive assertion.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class RunsLivenessDisplacementTest {
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

    private fun runRow(
        w: SeatWorld,
        run: Opened
    ): Map<String, Any?> = w.rows("SELECT * FROM runs WHERE id = ?", UUID.fromString(run.runId)).single()

    private fun seenAt(
        w: SeatWorld,
        assignmentId: String
    ): Instant = w.stamp(w.seatById(assignmentId)["last_seen_at"])

    // ------------------------------------------------------------------ Ruling A

    @Test
    fun `A1 describe with a valid seat token leaves last_seen_at unchanged on every tool, an op with the same token updates it`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            val assignment = assertNotNull(a.assignmentId)
            clock.advanceSeconds(20)
            val t0 = clock.now()
            assertTrue(w.claim(w.m1, a.token).ok())
            assertEquals(t0, seenAt(w, assignment), "fixture: the op touched the seat at t0")

            clock.advanceSeconds(30)
            for (tool in listOf("advance", "runs_read", "runs_write")) {
                val reply = w.call(tool, """{"seatToken":"${a.token}","describe":true}""")
                assertFalse(reply.isError, "describe under a valid token must answer on $tool: $reply")
                assertNull(reply.wholeCode(), "describe on $tool is not an error: $reply")
                assertEquals(
                    t0,
                    seenAt(w, assignment),
                    "describe on $tool is read-only: last_seen_at stays t0 (30 s old, outside the skip window)"
                )
            }

            assertTrue(w.heartbeat(w.m1, a.token).ok())
            assertEquals(t0.plusSeconds(30), seenAt(w, assignment), "control: an op with the same token touches at the same clock")
        }

    @Test
    fun `A2 describe with the coordinator token touches neither the coordinator row nor the run's coordinator_seen_at, an op does`() =
        world { w ->
            val run = w.open()
            clock.advanceSeconds(40)
            val t0 = clock.now()
            assertTrue(w.assign(run, w.m1, "builder").reply.ok())
            val coordinator = { w.seatsOfRun(run.runId).single { it["seat"] == "coordinator" } }
            assertEquals(t0, w.stamp(coordinator()["last_seen_at"]), "fixture: the op touched the coordinator at t0")
            assertEquals(t0, w.stamp(runRow(w, run)["coordinator_seen_at"]), "fixture: and the run's sign of life")

            clock.advanceSeconds(30)
            for (tool in listOf("runs_write", "runs_read", "advance")) {
                val reply = w.call(tool, """{"seatToken":"${run.coordinatorToken}","describe":true}""")
                assertFalse(reply.isError, "describe under the coordinator token must answer on $tool: $reply")
                assertNull(reply.wholeCode(), "describe on $tool is not an error: $reply")
                assertEquals(t0, w.stamp(coordinator()["last_seen_at"]), "describe on $tool leaves the coordinator row alone")
                assertEquals(t0, w.stamp(runRow(w, run)["coordinator_seen_at"]), "describe on $tool leaves coordinator_seen_at alone")
            }

            assertTrue(w.assign(run, w.m2, "implementer").reply.ok())
            assertEquals(t0.plusSeconds(30), w.stamp(coordinator()["last_seen_at"]), "control: an op under the token touches the row")
            assertEquals(t0.plusSeconds(30), w.stamp(runRow(w, run)["coordinator_seen_at"]), "and the run")
        }

    // ------------------------------------------------------------------ Ruling B helpers

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private suspend fun SeatWorld.claimRows(item: WorkItem): List<EventRecord> =
        rig.rows().filter { it.entityId == item.id && it.type.startsWith("claim.") }

    private suspend fun SeatWorld.expiredRows(item: WorkItem): List<EventRecord> = claimRows(item).filter { it.type == "claim.expired" }

    private fun displacedOf(reply: Reply): JsonObject? = reply.findElement("displaced") as? JsonObject

    /** Moves the clock past the 900 s default claim ttl and proves the claim on [item] is lapsed (fixture precondition). */
    private fun SeatWorld.lapse(item: WorkItem): Instant {
        val expiry = stamp(itemRow(item)["claim_expires_at"])
        clock.advanceSeconds(901)
        assertTrue(expiry <= clock.now(), "fixture: the claim on ${item.title} is lapsed")
        return expiry
    }

    // ------------------------------------------------------------------ B1 displacement over a foreign lapsed claim

    @Test
    fun `B1 a claim over a foreign lapsed seat claim records one claim expired displaced with a null displacedBy and reports displaced`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            assertTrue(w.claim(w.m1, a.token).ok())
            val expiry = w.lapse(w.m1)
            val mark = w.rig.maxSeq()

            val reply = w.claim(w.m1, null, by = w.other)

            assertTrue(reply.ok(), "the take-over succeeds: $reply")
            assertEquals("api:seat-other", w.itemRow(w.m1)["claimed_by"], "control: the item really changed hands")
            val displaced = assertNotNull(displacedOf(reply), "the displacement is reported: $reply")
            assertEquals(a.assignmentId, displaced.text("assignmentId"), "the displaced seat assignment")
            assertEquals(expiry, Instant.parse(assertNotNull(displaced.text("expiredAt"))), "when the displaced claim expired")
            assertFalse("principal" in displaced.keys, "the caller is not principalsVisible: $displaced")

            val rows = w.rig.rowsAfter(mark).filter { it.entityId == w.m1.id && it.type.startsWith("claim.") }
            assertEquals(
                listOf("claim.expired", "claim.acquired"),
                rows.map { it.type },
                "the expiry is recorded before the new claim's own event"
            )
            val expired = rows.first().payload()
            assertEquals("displaced", expired.text("reason"))
            assertEquals(w.orchId, expired.text("holder"), "the displaced holder")
            assertEquals(expiry, Instant.parse(assertNotNull(expired.text("expiresAt"))))
            assertTrue("displacedBy" in expired.keys, "the key is present even when the new holder has no assignment: $expired")
            assertEquals(JsonNull, expired.getValue("displacedBy"), "and its value is JSON null, not a missing key")
        }

    @Test
    fun `B1 displacedBy is the new holder's assignment id under a seat token, a principal-level displaced holder has no assignmentId`() =
        world { w ->
            assertTrue(w.claim(w.m3, null).ok())
            val expiry = w.lapse(w.m3)
            val run = w.open(by = w.other)
            val seat = w.assignOk(run, w.m3, "planner", by = w.other)
            val mark = w.rig.maxSeq()

            val reply = w.claim(w.m3, seat.token, by = w.other)

            assertTrue(reply.ok(), "$reply")
            val displaced = assertNotNull(displacedOf(reply), "$reply")
            assertFalse("assignmentId" in displaced.keys, "a principal-level holder has no assignment to report: $displaced")
            assertEquals(expiry, Instant.parse(assertNotNull(displaced.text("expiredAt"))))
            val rows = w.rig.rowsAfter(mark).filter { it.entityId == w.m3.id && it.type == "claim.expired" }
            assertEquals(1, rows.size, "exactly one claim.expired: $rows")
            assertEquals("displaced", rows.single().payload().text("reason"))
            assertEquals(seat.assignmentId, rows.single().payload().text("displacedBy"), "the new holder's assignment id")
        }

    // ------------------------------------------------------------------ B2 sweep then claim: no double record

    @Test
    fun `B2 an expiry sweep followed by a claim leaves exactly one claim expired for the lapsed claim`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            assertTrue(w.claim(w.m1, a.token).ok())
            w.lapse(w.m1)

            val sweep = ExpirySweeper(ClaimService(w.rig.raw, w.rig.composition.unitOfWork)).sweepOnce()
            assertEquals(1, sweep.claimsExpired, "fixture: the sweep recorded the lapsed claim")
            assertEquals(1, w.expiredRows(w.m1).size, "fixture: one row after the sweep")

            val reply = w.claim(w.m1, null, by = w.other)

            assertTrue(reply.ok(), "$reply")
            assertEquals("api:seat-other", w.itemRow(w.m1)["claimed_by"], "control: the claim took the item")
            val expired = w.expiredRows(w.m1)
            assertEquals(1, expired.size, "no double claim.expired for that lapsed claim: $expired")
            assertEquals("expired", expired.single().payload().text("reason"), "the sweep's row stays the one on record")
        }

    // ------------------------------------------------------------------ B3 principal visibility

    @Test
    fun `B3 displaced principal is the displaced holder for a principalsVisible caller, an ADMIN outside a seat token`() =
        world { w ->
            assertTrue(w.claim(w.m3, null).ok())
            w.lapse(w.m3)

            val reply = w.claim(w.m3, null, by = w.admin)

            assertTrue(reply.ok(), "$reply")
            val displaced = assertNotNull(displacedOf(reply), "$reply")
            assertEquals(w.orchId, displaced.text("principal"), "the displaced holder's principal id")
        }

    @Test
    fun `B3 displaced principal is absent for a caller without admin, while displaced itself is reported`() =
        world { w ->
            assertTrue(w.claim(w.m3, null).ok())
            w.lapse(w.m3)

            val reply = w.claim(w.m3, null, by = w.other)

            assertTrue(reply.ok(), "$reply")
            val displaced = assertNotNull(displacedOf(reply), "control: the displacement is reported: $reply")
            assertNotNull(displaced.text("expiredAt"))
            assertFalse("principal" in displaced.keys, "no principal for a non-admin caller: $displaced")
            assertFalse(w.orchId in reply.toString(), "the displaced principal appears nowhere in the answer: $reply")
        }

    @Test
    fun `B3 displaced principal is absent for an ADMIN under a seat token`() =
        world { w ->
            assertTrue(w.claim(w.m3, null).ok())
            w.lapse(w.m3)
            val run = w.open(by = w.admin)
            val seat = w.assignOk(run, w.m3, "planner", by = w.admin)

            val reply = w.claim(w.m3, seat.token, by = w.admin)

            assertTrue(reply.ok(), "$reply")
            val displaced = assertNotNull(displacedOf(reply), "control: the displacement is reported: $reply")
            assertFalse("principal" in displaced.keys, "an ADMIN under a seat token is not principalsVisible: $displaced")
            assertFalse(w.orchId in reply.toString(), "the displaced principal appears nowhere in the answer: $reply")
        }

    // ------------------------------------------------------------------ B4 the caller's own lapsed claim

    @Test
    fun `B4 re-claiming the caller's own lapsed principal-level claim reports no displaced and no displaced reason`() =
        world { w ->
            assertTrue(w.claim(w.m3, null).ok())
            w.lapse(w.m3)
            val mark = w.rig.maxSeq()

            val reply = w.claim(w.m3, null)

            assertTrue(reply.ok(), "$reply")
            assertNull(displacedOf(reply), "own lapsed claim is not a displacement: $reply")
            assertFalse("displaced" in reply.allKeys(), "$reply")
            val rows = w.rig.rowsAfter(mark).filter { it.entityId == w.m3.id && it.type == "claim.expired" }
            assertTrue(rows.none { it.payload().text("reason") == "displaced" }, "no displaced reason: $rows")
        }

    @Test
    fun `B4 re-claiming the same seat's own lapsed claim reports no displaced and no displaced reason`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            assertTrue(w.claim(w.m1, a.token).ok())
            w.lapse(w.m1)
            val mark = w.rig.maxSeq()

            val reply = w.claim(w.m1, a.token)

            assertTrue(reply.ok(), "$reply")
            assertNull(displacedOf(reply), "the same (principal, assignment) pair is not displaced: $reply")
            assertFalse("displaced" in reply.allKeys(), "$reply")
            val rows = w.rig.rowsAfter(mark).filter { it.entityId == w.m1.id && it.type == "claim.expired" }
            assertTrue(rows.none { it.payload().text("reason") == "displaced" }, "no displaced reason: $rows")
        }

    // ------------------------------------------------------------------ B5 unclaimed

    @Test
    fun `B5 a claim over an unclaimed item reports nothing and records no claim expired`() =
        world { w ->
            assertNull(w.itemRow(w.m3)["claimed_by"], "fixture: the item is unclaimed")
            val mark = w.rig.maxSeq()

            val reply = w.claim(w.m3, null, by = w.admin)

            assertTrue(reply.ok(), "$reply")
            assertEquals("api:seat-admin", w.itemRow(w.m3)["claimed_by"], "control: the claim took effect")
            assertNull(displacedOf(reply), "$reply")
            assertFalse("displaced" in reply.allKeys(), "$reply")
            val rows = w.rig.rowsAfter(mark).filter { it.entityId == w.m3.id }
            assertEquals(listOf("claim.acquired"), rows.map { it.type }, "only the acquisition is recorded: $rows")
        }

    // ------------------------------------------------------------------ B6 sweep rows

    @Test
    fun `B6 a sweep-recorded claim expired has reason expired and no displacedBy key`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            assertTrue(w.claim(w.m1, a.token).ok())
            val expiry = w.lapse(w.m1)

            val sweep = ExpirySweeper(ClaimService(w.rig.raw, w.rig.composition.unitOfWork)).sweepOnce()

            assertEquals(1, sweep.claimsExpired)
            val row = w.expiredRows(w.m1).single()
            assertEquals("expired", row.payload().text("reason"))
            assertFalse("displacedBy" in row.payload().keys, "a sweep row has no displacedBy key: ${row.payload()}")
            assertEquals(expiry, Instant.parse(assertNotNull(row.payload().text("expiresAt"))))
        }
}
