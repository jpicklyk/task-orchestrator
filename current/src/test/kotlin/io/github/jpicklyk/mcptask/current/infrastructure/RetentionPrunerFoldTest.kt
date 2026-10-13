package io.github.jpicklyk.mcptask.current.infrastructure

import ch.qos.logback.classic.Level
import io.github.jpicklyk.mcptask.current.application.port.CallLogFoldWatermark
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.config.RetentionSettings
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.CallRow
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.UsageFoldKit
import io.github.jpicklyk.mcptask.current.test.LogCapture
import io.github.jpicklyk.mcptask.current.test.RetentionRows
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testReqId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 515ec0a4, R13) for [RetentionPruner] wired with a [UsageDailyFolder]: scenario S7 of
 * the frozen test-plan (NEW-SURFACE), through the production SQLite database and production units of work.
 *
 * Oracles (frozen task-scope D3, D5, AC4 and AC6, R3 invariant 5): with a folder wired a pass first folds every completed UTC day
 * (a day is completed once `now - 1h` is past its end), then deletes `call_log` rows only strictly below
 * `min(now - callLogDays, F)` where `F` is the folded day plus one day at midnight; the folder is the pass's watermark and
 * overrides a `foldWatermark`; `RetentionPass.foldedDays` counts the days folded by the pass and the pass's INFO line carries
 * `folded_days=`; a fold failure throws out of the pass and nothing is pruned. Rows are raw SQL rows at explicit instants and
 * every expected survivor is an input this test chose.
 *
 * Negative-assertion conditions: each "survives" assertion has a row that is older than the plain cutoff (or otherwise deletable
 * by R3 alone) and survives only because its day is not folded; the failure test seeds a row that the permissive
 * `foldWatermark` would delete.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions. The failure test uses
 * `runCatching` only to capture the thrown pass and then asserts `isFailure`.
 *
 * Domain invariants satisfied by construction: [RetentionSettings] takes day counts only; raw rows keep the call_log CHECKs.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class RetentionPrunerFoldTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val jdbc get() = sqlite.jdbcUrl
    private val noon: Instant = Instant.parse("2026-10-13T12:00:00.000Z")

    private fun at(iso: String) = UsageFoldKit.at(iso)

    private fun uow(now: Instant): UnitOfWork = sqlite.unitOfWork(Clock { now })

    private fun settings(callLog: Int = 1) =
        RetentionSettings(callLogDays = callLog, eventsDays = 0, auditEventsDays = 0, leaseHistoryDays = 0)

    private fun pruner(
        now: Instant = noon,
        callLog: Int = 1,
        maxDays: Int = 400,
        watermark: CallLogFoldWatermark = CallLogFoldWatermark.NONE,
        folderUnit: UnitOfWork = uow(now)
    ) = RetentionPruner(
        unitOfWork = uow(now),
        settings = settings(callLog),
        sseBufferSize = 0,
        interval = Duration.ofHours(1),
        batchSize = 5_000,
        passCap = 200_000,
        foldWatermark = watermark,
        folder = UsageDailyFolder(unitOfWork = folderUnit, maxDaysPerPass = maxDays)
    )

    private fun callIds(vararg ns: Int) = ns.map { testReqId(it) }

    private fun remaining() = RetentionRows.callLogIds(jdbc)

    private fun foldedDays() = UsageFoldKit.readDaily(jdbc).map { it.day }

    private fun watermark(through: Instant?) =
        object : CallLogFoldWatermark {
            override suspend fun foldedThrough(): Instant? = through
        }

    // ------------------------------------------------------------------ S7 fold first, then delete below min(cutoff, F)

    @Test
    fun `S7 a pass folds the completed days first and then deletes only the rows below the cutoff`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(
                jdbc,
                CallRow(1, at("2026-10-01T12:00:00Z"), "old_tool", latencyMs = 11),
                CallRow(2, at("2026-10-12T13:00:00Z"), "kept_tool", latencyMs = 22),
                CallRow(3, at("2026-10-13T09:00:00Z"), "today_tool", latencyMs = 33)
            )

            val pass = pruner().pruneOnce()

            assertEquals(12, pass.foldedDays, "2026-10-01 to 2026-10-12 inclusive")
            assertEquals(1, pass.callLog, "only the 1st is below min(cutoff = 10-12T12:00Z, F = 10-13T00:00Z)")
            assertEquals(callIds(2, 3), remaining())
            val daily = UsageFoldKit.readDaily(jdbc)
            assertEquals(
                listOf(
                    UsageFoldKit.dailyRow("2026-10-01", null, "old_tool", calls = 1, latencySum = 11, latencyMax = 11),
                    UsageFoldKit.dailyRow("2026-10-12", null, "kept_tool", calls = 1, latencySum = 22, latencyMax = 22)
                ),
                daily,
                "the deleted row was folded before it was deleted; today's row is not folded"
            )
            assertEquals("2026-10-12", UsageFoldKit.readFold(jdbc).single().foldedThrough)
        }

    @Test
    fun `S7 a second pass folds no day and deletes nothing more`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(
                jdbc,
                CallRow(1, at("2026-10-01T12:00:00Z"), "old_tool"),
                CallRow(2, at("2026-10-12T13:00:00Z"), "kept_tool"),
                CallRow(3, at("2026-10-13T09:00:00Z"), "today_tool")
            )
            val p = pruner()
            p.pruneOnce()

            val second = p.pruneOnce()

            assertEquals(0, second.foldedDays)
            assertEquals(0, second.callLog)
            assertEquals(callIds(2, 3), remaining())
        }

    @Test
    fun `S7 the INFO line of a pass carries folded_days`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(
                jdbc,
                CallRow(1, at("2026-10-01T12:00:00Z"), "old_tool"),
                CallRow(2, at("2026-10-13T09:00:00Z"), "today_tool")
            )
            val capture = LogCapture().attach()
            try {
                pruner().pruneOnce()

                val infos = capture.events.filter { it.level == Level.INFO && it.logger.endsWith("RetentionPruner") }
                assertEquals(1, infos.size, "one INFO line per pass: ${infos.map { it.message }}")
                assertTrue("folded_days=12" in infos.single().message, infos.single().message)
            } finally {
                capture.detach()
            }
        }

    /**
     * now 10-13T00:30Z: the plain cutoff is 10-12T00:30Z, but with the 1h grace only the 11th is foldable, so F = 10-12T00:00Z is
     * BELOW the cutoff. The 00:15 row is older than the cutoff and unfolded: it must survive.
     */
    private suspend fun gapScenario(permissive: Boolean) {
        val early = at("2026-10-13T00:30:00Z")
        UsageFoldKit.insertCalls(
            jdbc,
            CallRow(1, at("2026-10-11T12:00:00Z"), "folded_tool", latencyMs = 5),
            CallRow(2, at("2026-10-12T00:15:00Z"), "gap_tool", latencyMs = 6),
            CallRow(3, at("2026-10-12T10:00:00Z"), "recent_tool", latencyMs = 7)
        )
        val farFuture = watermark(Instant.parse("2099-01-01T00:00:00Z"))

        val pass = pruner(early, watermark = if (permissive) farFuture else CallLogFoldWatermark.NONE).pruneOnce()

        assertEquals(1, pass.foldedDays, "only the 11th is completed")
        assertEquals(1, pass.callLog)
        assertEquals(callIds(2, 3), remaining(), "the 00:15 row is older than the cutoff but its day is not folded")
        assertEquals(listOf("2026-10-11"), foldedDays())
    }

    @Test
    fun `S7 a row between the folded day and the plain cutoff survives`(): Unit = runBlocking { gapScenario(permissive = false) }

    @Test
    fun `S7 the folder overrides a permissive foldWatermark, the row between the folded day and the cutoff still survives`(): Unit =
        runBlocking { gapScenario(permissive = true) }

    @Test
    fun `S7 with maxDaysPerPass of one the pruner deletes a row only once its own day has been folded`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(
                jdbc,
                CallRow(1, at("2026-10-01T12:00:00Z"), "d01"),
                CallRow(2, at("2026-10-05T12:00:00Z"), "d05"),
                CallRow(3, at("2026-10-12T12:00:00Z"), "d12")
            )
            val p = pruner(maxDays = 1)

            val first = p.pruneOnce()
            assertEquals(1, first.foldedDays)
            assertEquals(1, first.callLog)
            assertEquals(callIds(2, 3), remaining(), "the 5th is older than the cutoff but only the 1st is folded")

            // passes 2 to 4 fold the 2nd, 3rd and 4th: the 5th (12:00) is still at or above F
            for (pass in 2..4) {
                val next = p.pruneOnce()
                assertEquals(1, next.foldedDays, "pass $pass")
                assertEquals(0, next.callLog, "pass $pass")
                assertEquals(callIds(2, 3), remaining(), "pass $pass")
            }

            val fifth = p.pruneOnce()
            assertEquals(1, fifth.foldedDays, "pass 5 folds the 5th")
            assertEquals(1, fifth.callLog, "now the 5th is below F = 10-06T00:00Z")
            assertEquals(callIds(3), remaining())
            assertTrue("2026-10-05" in foldedDays(), "the 5th was folded before its row was deleted: ${foldedDays()}")
        }

    // ------------------------------------------------------------------ S7 a fold failure prunes nothing

    /** Delegates to the real unit of work but fails every write, counting the attempts. */
    private class FailingWrites(
        private val real: UnitOfWork,
        val attempts: AtomicInteger
    ) : UnitOfWork by real {
        override suspend fun <T> write(
            op: String,
            block: suspend WriteScope.() -> Outcome<T>
        ): Outcome<T> {
            attempts.incrementAndGet()
            error("simulated store fault")
        }
    }

    @Test
    fun `S7 a fold failure throws out of the pass and no call_log row is deleted`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(
                jdbc,
                CallRow(1, at("2026-10-01T12:00:00Z"), "old_tool"),
                CallRow(2, at("2026-10-13T09:00:00Z"), "today_tool")
            )
            val attempts = AtomicInteger(0)
            val failing = FailingWrites(uow(noon), attempts)

            // the permissive foldWatermark would let R3 delete the 1st; only the failing folder stands in the way
            val outcome =
                runCatching { pruner(watermark = watermark(Instant.parse("2099-01-01T00:00:00Z")), folderUnit = failing).pruneOnce() }

            assertTrue(outcome.isFailure, "a fold failure must surface from the pass")
            assertTrue(attempts.get() >= 1, "control: the folder really tried to write")
            assertEquals(callIds(1, 2), remaining(), "nothing is pruned when the fold failed")
            assertEquals(emptyList(), UsageFoldKit.readDaily(jdbc))
        }
}
