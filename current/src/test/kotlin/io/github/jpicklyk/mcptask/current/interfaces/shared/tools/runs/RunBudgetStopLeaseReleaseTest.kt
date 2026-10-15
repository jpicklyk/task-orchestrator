package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import io.github.jpicklyk.mcptask.current.application.service.ok
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
 * Independent test (test-author seat, item f65fef01, R29) for scenario S9 (r27 N7, a test-only gap): the director's `stop` answer of a
 * run-budget decision closes the run `budget_exhausted` with the full close cascade, and that cascade releases the resource lease a
 * member in WORK holds and reports it in `run.ended` `released.leases`.
 *
 * Oracle (never read from a run): the close cascade of R17 as `RunServiceTest` states it (a WORK member's lease is released by a
 * close and counted in `released.leases`; a QUEUE member's lease and a non-member's lease are not) applied to the budget stop, whose
 * option text promises the same close ("stop" closes the run budget_exhausted, task-scope D6 / R27 S6).
 *
 * Negative-assertion fixture conditions: the lease of the member is asserted held before the stop (so "gone" is a change), and a lease
 * held by a WORK item that is NOT a member of the run must survive the same stop (so a release that sweeps every lease would fail).
 *
 * Domain invariants satisfied by construction: items are children of the root and seeded through the rig; leases are taken through the
 * production `ClaimService`; the run, the seat, the pause and the stop all go through the tools.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class RunBudgetStopLeaseReleaseTest {
    private val clock = SettableClock(Instant.parse("2026-10-15T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private val director =
        ParityToken("lease-dir", setOf(ApiCapability.RUNS, ApiCapability.READ, ApiCapability.DECIDE), kind = PrincipalKind.HUMAN)

    private fun world(block: suspend (SeatWorld) -> Unit) =
        runBlocking {
            val w = SeatWorld.create(db.db, dir, clock)
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    @Test
    fun `S9 the budget stop of a run releases the lease of a WORK member, reports it in run ended and leaves a non-member's lease alone`() =
        world { w ->
            val leased = w.rig.seed("Leased WORK member", parent = w.root, role = Role.WORK, tags = "plain-task")
            val outsider = w.rig.seed("Leased WORK outsider", parent = w.root, role = Role.WORK, tags = "plain-task")
            val claims = ClaimService(w.rig.raw, w.rig.composition.unitOfWork)
            claims.acquireLeases(leased.id, "actor-member", listOf("res-member" to 600)).ok()
            claims.acquireLeases(outsider.id, "actor-outsider", listOf("res-outsider" to 600)).ok()
            val leases = w.rig.raw.resourceLeaseRepository()
            assertEquals(1, leases.findActiveForItem(leased.id).size, "fixture: the member holds its lease")
            assertEquals(1, leases.findActiveForItem(outsider.id).size, "fixture: the outsider holds its lease")

            val run = w.open(members = listOf(w.m1, leased), extra = ",\"budget\":{\"tokens\":100}")
            val seat = w.assignOk(run, w.m1, "builder")
            assertTrue(w.claim(w.m1, seat.token).ok(), "the seat claims its item")
            val beat = w.heartbeat(w.m1, seat.token, usage = """{"tokens":101}""")
            val decisionId =
                assertNotNull(
                    (beat.findElement("paused") as? JsonObject)?.get("decisionId")?.jsonPrimitive?.content,
                    "101 over a limit of 100 pauses the run on a decision: $beat"
                )
            assertEquals(1, leases.findActiveForItem(leased.id).size, "still held while the run is only paused")

            val stopped =
                w.director("runs_write", """{"ops":[{"op":"answer","decisionId":"$decisionId","option":"stop"}]}""", director)

            assertTrue(stopped.ok(), "the director's stop must succeed: $stopped")
            assertEquals("closed", w.select("get", ""","runId":"${run.runId}"""").find("status"))
            assertEquals("budget_exhausted", w.select("get", ""","runId":"${run.runId}"""").find("terminalReason"))
            assertEquals(0, leases.findActiveForItem(leased.id).size, "the stop released the WORK member's lease")
            assertEquals(1, leases.findActiveForItem(outsider.id).size, "a lease of a non-member is not touched")
            val ended =
                w.rig
                    .rows()
                    .single { it.type == "run.ended" }
                    .payload()
            assertEquals("budget_exhausted", ended.getValue("reason").jsonPrimitive.content)
            assertEquals(
                1,
                ended
                    .getValue("released")
                    .jsonObject
                    .getValue("leases")
                    .jsonPrimitive.content
                    .toInt(),
                "run.ended reports one released lease: $ended"
            )
            assertTrue(w.rig.rows().count { it.type == "lease.released" } == 1, "and exactly one lease.released event was recorded")
        }
}
