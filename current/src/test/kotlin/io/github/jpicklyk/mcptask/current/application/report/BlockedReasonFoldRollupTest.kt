package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Independent tests (test-author seat, item f65fef01, R29) for scenario S3, the report half: the blocked reasons of a run rollup read
 * through ONE shared fold. A BLOCKED member whose stored reason is NULL, a `blocked` event that carries no `blockedReason`, and a
 * `blockedReason` that is not one of the four wire spellings all read `manual`; a known spelling keeps its own key.
 *
 * Oracles (task-scope D2 and the V37 / R25 rules the run rollup documents): `byReason` counts the current BLOCKED members by effective
 * reason (NULL reads manual); `dwellMs` is the time spent blocked per effective reason over the run's window, a block starts at its
 * `blocked` event and ends at the member's next event; an event reason that is not a wire spelling now reads `manual` (before this item
 * the dwell query passed an unknown string through as its own key). Every number is a hand sum written next to its rows.
 *
 * Fixture (clock fixed at the rollup kit's NOW, 12:00 on 2026-10-13; run Q opened at 02:00, open, so the window runs to NOW):
 * - F1 BLOCKED, stored reason NULL (legacy), no events.
 * - F4 BLOCKED, stored reason `manual`, no events.
 * - F2 work; unstamped events: blocked with reason `bogus` at 08:00, work at 09:00 (one hour).
 * - F3 work; unstamped events: blocked with NO reason at 10:00, work at 10:30 (half an hour).
 * - F5 work; unstamped events: blocked `dependency` at 03:00, work at 03:20 (twenty minutes).
 * Hand sums: byReason {manual 2}; dwellMs manual 3,600,000 + 1,800,000 = 5,400,000 and dependency 1,200,000, and no other key.
 *
 * Negative-assertion fixture conditions: the `bogus` event WOULD produce its own key `bogus` without the fold (the old behaviour of the
 * dwell query), and the dependency block is the control that a known spelling is not collapsed.
 *
 * Domain invariants satisfied by construction: see [RunKit] and [RollupRaw].
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class BlockedReasonFoldRollupTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val root = RunKit.R
    private val run = RunKit.id("f6a00001")
    private val f1 = RunKit.id("f6a10000", 1)
    private val f2 = RunKit.id("f6a10000", 2)
    private val f3 = RunKit.id("f6a10000", 3)
    private val f4 = RunKit.id("f6a10000", 4)
    private val f5 = RunKit.id("f6a10000", 5)

    private fun t(hhmm: String): Instant = Instant.parse("2026-10-13T$hhmm:00Z")

    private suspend fun seed(f: ReportFixture) {
        RunKit.roots(f)
        RollupRaw.write(f.database.jdbcUrl) {
            item(f1, root, "blocked", 1, root)
            item(f2, root, "work", 1, root)
            item(f3, root, "work", 1, root)
            item(f4, root, "blocked", 1, root)
            item(f5, root, "work", 1, root)
            blocked(f4, "manual")
            moved(t("08:00"), f2, root, "blocked", "bogus")
            moved(t("09:00"), f2, root, "work")
            moved(t("10:00"), f3, root, "blocked")
            moved(t("10:30"), f3, root, "work")
            moved(t("03:00"), f5, root, "blocked", "dependency")
            moved(t("03:20"), f5, root, "work")
        }
        RunKit.run(f, run, members = listOf(f1, f2, f3, f4, f5), openedAt = t("02:00"))
    }

    private suspend fun rollup(f: ReportFixture): RollupReport =
        when (val outcome = f.service().rollup(f.ctx(), ReportRollupRequest(runId = IdRef(run.toString())))) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected an ok rollup but got ${outcome.error}")
        }

    @Test
    fun `S3 a NULL stored reason counts manual in byReason and a block event with no or an unknown reason dwells as manual`(): Unit =
        runBlocking {
            val f = ReportFixture(db.db, clock)
            seed(f)

            val blocked = rollup(f).blocked

            assertEquals(2L, blocked.total, "F1 and F4 are the blocked members")
            assertEquals(mapOf("manual" to 2), blocked.byReason, "the NULL row reads manual next to the stored manual one")
            assertEquals(
                mapOf("manual" to 5_400_000L, "dependency" to 1_200_000L),
                blocked.dwellMs,
                "F2 bogus 1 h and F3 no reason 30 min are manual; F5 keeps its dependency key"
            )
            assertFalse(blocked.dwellMs.orEmpty().containsKey("bogus"), "an unknown spelling is never its own key")
        }

    @Test
    fun `S3 control - the same rows without the unknown spelling give the same keys, so only the fold decides`(): Unit =
        runBlocking {
            val f = ReportFixture(db.db, clock)
            seed(f)
            RollupRaw.write(f.database.jdbcUrl) {
                sql("DELETE FROM events WHERE entity_id = ?", f2)
            }

            val blocked = rollup(f).blocked

            assertEquals(mapOf("manual" to 1_800_000L, "dependency" to 1_200_000L), blocked.dwellMs, "F2 removed: only F3 and F5 dwell")
            assertEquals(mapOf("manual" to 2), blocked.byReason)
        }
}
