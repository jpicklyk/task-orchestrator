package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.event.internal.wire.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.model.event.DomainEvent
import io.github.jpicklyk.mcptask.current.model.event.EventClass
import io.github.jpicklyk.mcptask.current.model.event.EventClasses
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `runs_write reissue` (item 7c71283a): a director-profile or human caller with no seat token gets a new run token for an open or
 * paused run; the old one is refused from then on. Oracles: the item's task-scope AC1-AC8 and decisions D1-D8 (the caller rule of
 * pause and resume; the new row bound to the superseded row's principal; `supersededSeenAt` is the old row's `last_seen_at`; the
 * old holder's claims released; one audit `run.orchestrator_reissued` event that never carries a token). Every refusal is paired
 * with the call that succeeds and asserts the rows and events it would have written are unchanged.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class RunsReissueOpTest {
    private val clock = SettableClock(Instant.parse("2026-11-24T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private val token43 = Regex("^[A-Za-z0-9_-]{43}$")

    private fun world(block: suspend (SeatWorld) -> Unit) =
        runBlocking {
            val w = SeatWorld.create(db.db, dir, clock)
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    private fun reissueOp(
        run: Opened,
        key: String? = null
    ): String = """{"op":"reissue","runId":"${run.runId}"${if (key != null) ""","idempotencyKey":"$key"""" else ""}}"""

    private suspend fun SeatWorld.reissue(
        run: Opened,
        by: ParityToken = orch,
        token: String? = null,
        key: String? = null
    ): Reply {
        val top = if (token != null) """"seatToken":"$token",""" else ""
        return director("runs_write", """{$top"ops":[${reissueOp(run, key)}]}""", by)
    }

    private suspend fun SeatWorld.update(
        run: Opened,
        token: String,
        by: ParityToken = orch
    ): Reply = ops("runs_write", """{"op":"update","runId":"${run.runId}","hints":{"k":"v"}}""", token = token, by = by)

    private fun runRow(
        w: SeatWorld,
        run: Opened
    ): Map<String, Any?> = w.rows("SELECT * FROM runs WHERE id = ?", UUID.fromString(run.runId)).single()

    private fun coordinatorRows(
        w: SeatWorld,
        run: Opened
    ) = w.seatsOfRun(run.runId).filter { it["seat"] == "coordinator" }

    private suspend fun reissuedEvents(w: SeatWorld) = w.rig.rows().filter { it.type == DomainEvent.RUN_ORCHESTRATOR_REISSUED }

    // ------------------------------------------------------------------ S1

    @Test
    fun `S1 a director reissue returns the run and a new token under both keys - the old token dies, the new one closes`(): Unit =
        world { w ->
            val run = w.open()
            assertTrue(w.update(run, run.coordinatorToken).ok(), "fixture: T1 updates")

            val reply = w.reissue(run)

            assertTrue(reply.ok(), reply.toString())
            assertEquals(run.runId, reply.find("runId"), "the result is the run view")
            val t2 = assertNotNull(reply.find("orchestratorToken"), reply.toString())
            assertTrue(token43.matches(t2), t2)
            assertEquals(t2, reply.find("coordinatorToken"), "the deprecated alias carries the same token")
            assertNotEquals(run.coordinatorToken, t2)
            assertEquals("unauthenticated", w.update(run, run.coordinatorToken).wholeCode(), "the old token is refused")
            assertTrue(w.update(run, t2).ok(), "the new token updates")
            assertTrue(w.close(run, "cancelled", t2).ok(), "the new token closes")
        }

    // ------------------------------------------------------------------ S2

    @Test
    fun `S2 a member or run seat token is forbidden on the director profile, even for a human, and nothing is written`(): Unit =
        world { w ->
            // a token is bound to the principal it was issued to, so each caller presents tokens of a run it opened
            val run = w.open(members = listOf(w.m1))
            val humanRun = w.open(members = listOf(w.m2), by = w.human)
            val member = w.assignOk(run, w.m1, "builder")
            val humanMember = w.assignOk(humanRun, w.m2, "implementer", by = w.human)
            val cases =
                listOf(
                    Triple(run, w.orch, assertNotNull(member.token)),
                    Triple(run, w.orch, run.coordinatorToken),
                    Triple(humanRun, w.human, assertNotNull(humanMember.token)),
                    Triple(humanRun, w.human, humanRun.coordinatorToken)
                )
            val shape = { r: Opened ->
                w.seatsOfRun(r.runId).map { listOf(w.uuidOf(it["id"]), it["status"], it["outcome"], it["attempt"]) }
            }
            val seatsBefore = listOf(run, humanRun).map(shape)
            val eventsBefore = reissuedEvents(w).size

            for ((target, by, token) in cases) {
                val refused = w.reissue(target, by = by, token = token)
                assertEquals("forbidden", refused.code(), "${by.id}: $refused")
                assertEquals("principal_kind", refused.find("scope", refused.error()))
                assertEquals("director,human", refused.find("required", refused.error()))
            }
            assertEquals(seatsBefore, listOf(run, humanRun).map(shape), "no seat row changed")
            assertEquals(eventsBefore, reissuedEvents(w).size, "no reissue was recorded")

            assertTrue(w.reissue(humanRun, by = w.human).ok(), "control: the same human without a token reissues")
            assertTrue(w.reissue(run).ok(), "control: the same caller without a token reissues on the director profile")
        }

    // ------------------------------------------------------------------ S3

    @Test
    fun `S3 an out-of-scope run is forbidden on the root, a closed run is run_closed, a paused run is reissued and stays paused`(): Unit =
        world { w ->
            val elsewhere = w.open(members = listOf(w.e), rootItem = w.root2)
            val outOfScope = w.reissue(elsewhere, by = w.rootScoped)
            assertEquals("forbidden", outOfScope.code(), outOfScope.toString())
            assertNotEquals("principal_kind", outOfScope.find("scope", outOfScope.error()), "the scope check comes first")
            assertEquals(1, coordinatorRows(w, elsewhere).size, "nothing was reissued")

            val closed = w.open()
            assertTrue(w.close(closed, "cancelled", closed.coordinatorToken).ok())
            assertEquals("run_closed", w.reissue(closed).code())
            assertEquals(1, coordinatorRows(w, closed).size, "a closed run gets no new row")

            val paused = w.open(members = listOf(w.m2))
            assertTrue(w.director("runs_write", """{"ops":[{"op":"pause","runId":"${paused.runId}"}]}""", w.human).ok())
            val reissued = w.reissue(paused)
            assertTrue(reissued.ok(), reissued.toString())
            assertEquals("paused", reissued.find("status"), "the view shows the run still paused")
            assertEquals("paused", runRow(w, paused)["status"])
        }

    // ------------------------------------------------------------------ S4

    @Test
    fun `S4 the old row ends superseded and the new row is attempt 2 bound to the opener, also when a human reissued`(): Unit =
        world { w ->
            val run = w.open()

            val reply = w.reissue(run, by = w.human)

            val t2 = assertNotNull(reply.find("orchestratorToken"), reply.toString())
            val rows = coordinatorRows(w, run)
            assertEquals(2, rows.size)
            val (old, new) = rows
            assertEquals("ended", old["status"])
            assertEquals("superseded", old["outcome"])
            assertEquals("active", new["status"])
            assertEquals(2L, new["attempt"])
            assertEquals(w.orchId, new["principal_id"], "bound to the superseded row's principal, not the human")
            val listing = w.seats(run, ""","ended":true""")
            assertTrue("superseded" in listing.toString(), "the ended listing shows the superseded row: $listing")

            assertTrue(w.update(run, t2, by = w.orch).ok(), "the opener presents the new token")
            assertEquals("unauthenticated", w.update(run, t2, by = w.human).wholeCode(), "the human cannot present it")
        }

    // ------------------------------------------------------------------ S5 and S8

    @Test
    fun `S5 one audit event per reissue, supersededSeenAt absent before the old token was seen and present after, no token in it`(): Unit =
        world { w ->
            val run = w.open()

            val first = w.reissue(run)
            assertTrue(first.ok(), first.toString())
            assertNull(first.findElement("supersededSeenAt"), "never seen: omitted from the result: $first")
            val t2 = assertNotNull(first.find("orchestratorToken"))
            val events1 = reissuedEvents(w)
            assertEquals(1, events1.size)
            val e1 = events1.single()
            assertEquals(UUID.fromString(run.runId), e1.entityId)
            assertEquals(DomainEvent.KIND_RUN, e1.entityKind)
            assertEquals(
                2,
                e1
                    .payload()
                    .getValue("attempt")
                    .jsonPrimitive.content
                    .toInt()
            )
            assertTrue(e1.payload()["supersededSeenAt"].let { it == null || it == JsonNull }, e1.data)
            assertEquals(EventClass.AUDIT, EventClasses.of(e1.type))

            clock.advanceSeconds(10)
            val seenAt = clock.now()
            assertTrue(w.update(run, t2).ok(), "T2 is seen")
            clock.advanceSeconds(10)
            val oldRowId = coordinatorRows(w, run).single { it["status"] == "active" }["id"]

            val second = w.reissue(run)

            assertEquals(seenAt.toString(), second.find("supersededSeenAt"), second.toString())
            val e2 = reissuedEvents(w).also { assertEquals(2, it.size) }.last()
            assertEquals(
                seenAt,
                Instant.parse(
                    e2
                        .payload()
                        .getValue("supersededSeenAt")
                        .jsonPrimitive.content
                )
            )
            assertEquals(
                w.uuidOf(oldRowId).toString(),
                e2
                    .payload()
                    .getValue("supersededAssignmentId")
                    .jsonPrimitive.content
            )
            val t3 = assertNotNull(second.find("orchestratorToken"))
            for (secret in listOf(run.coordinatorToken, t2, t3, w.sha256Hex(t2), w.sha256Hex(t3))) {
                assertTrue(secret !in e1.data && secret !in e2.data, "no token or hash in an event")
            }

            val projected = assertNotNull(ApiEventBus.project(e2))
            assertEquals("run.orchestrator_reissued", projected.event)
            assertEquals(run.runId, projected.runId)
            assertNull(projected.itemId)
            assertEquals(w.root.id.toString(), projected.rootId)
        }

    // ------------------------------------------------------------------ S6

    @Test
    fun `S6 a claim held under the old run token is released by the reissue`(): Unit =
        world { w ->
            val run = w.open()
            assertTrue(w.claim(w.m1, run.coordinatorToken).ok(), "fixture: the run token holds M1")
            assertNotNull(w.itemRow(w.m1)["claimed_by"])

            assertTrue(w.reissue(run).ok())

            assertNull(w.itemRow(w.m1)["claimed_by"], "the old holder's claim is released")
            assertNull(w.itemRow(w.m1)["claim_assignment_id"])
        }

    // ------------------------------------------------------------------ S7

    @Test
    fun `S7 a keyed replay returns the stored result without the token and writes no second row or event`(): Unit =
        world { w ->
            val run = w.open()
            val first = w.reissue(run, key = "reissue-1")
            assertNotNull(first.find("orchestratorToken"), first.toString())
            val rows = w.seatsOfRun(run.runId).size
            val events = reissuedEvents(w).size

            val replay = w.reissue(run, key = "reissue-1")

            assertTrue(replay.ok(), replay.toString())
            assertEquals("true", replay.find("tokenRedacted"), replay.toString())
            assertNull(replay.findElement("orchestratorToken"), replay.toString())
            assertNull(replay.findElement("coordinatorToken"), replay.toString())
            assertEquals(rows, w.seatsOfRun(run.runId).size)
            assertEquals(events, reissuedEvents(w).size)
        }
}
