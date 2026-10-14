package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.infrastructure.RetentionPruner
import io.github.jpicklyk.mcptask.current.infrastructure.UsageDailyFolder
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.RetentionSettings
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.SqliteUnitOfWork
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.CallRow
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.FoldRow
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.UsageFoldKit
import io.github.jpicklyk.mcptask.current.test.RetentionRows
import io.github.jpicklyk.mcptask.current.test.testReqId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent test (test-author seat, item 515ec0a4, scenario S13 of the frozen test-plan, NEW-SURFACE): the checked-in golden V17
 * database is upgraded through the production startup path (initialize, updateSchema), raw `call_log` rows are added for three
 * old days and two recent ones, the usage fold then runs and the retention pruner runs over the upgraded file.
 *
 * Oracles (frozen task-scope D3, D5, AC1, AC4 to AC6; migration-assessment section 3): the upgrade creates both new tables empty;
 * with a unit instant of 2026-10-13T12:00Z the completed days are every UTC day up to 2026-10-12, so the fold covers the days from
 * the oldest call (2026-07-01) through 2026-10-12 and the watermark ends at the 12th; the pruner with `callLogDays = 1` has the
 * cutoff 2026-10-12T12:00Z and deletes exactly the rows below `min(cutoff, F = 2026-10-13T00:00Z)`, i.e. the three old rows, and
 * keeps the 20:00 row of the 12th and the 01:00 row of the 13th. Expected counts and sums are inputs this test chose (hand sums of
 * the seeded rows), the day span is computed from the calendar, never read from the fold.
 *
 * Forbidden-construct declaration: none.
 */
class UsageFoldUpgradeTest {
    @TempDir
    lateinit var dir: File

    private fun count(
        url: String,
        table: String
    ) = RetentionRows.scalar(url, "SELECT count(*) FROM $table")

    @Test
    fun `fold then prune over an upgraded golden database folds the old days and deletes only their rows`(): Unit =
        runBlocking {
            val now = Instant.parse("2026-10-13T12:00:00Z")
            val golden = GoldenV17.copyTo(File(dir, "usage-fold.sqlite"))
            val url = UpgradeHarness.urlFor(golden)
            val manager = DatabaseManager(appConfig = AppConfig.fromEnv { null })
            try {
                assertTrue(manager.initialize(url), "initialize must accept the golden database")
                assertTrue(manager.updateSchema(), "production updateSchema must upgrade the golden database")
                assertEquals(0L, count(url, "usage_daily"), "the upgrade creates usage_daily empty")
                assertEquals(0L, count(url, "usage_fold"), "the upgrade writes no watermark")
                assertEquals(emptyList(), RetentionRows.callLogIds(url), "fixture: the upgraded call_log starts empty")

                UsageFoldKit.insertCalls(
                    url,
                    CallRow(1, UsageFoldKit.at("2026-07-01T10:00:00Z"), "old_a", latencyMs = 10),
                    CallRow(2, UsageFoldKit.at("2026-07-02T10:00:00Z"), "old_a", latencyMs = 20),
                    CallRow(3, UsageFoldKit.at("2026-07-04T10:00:00Z"), "old_b", latencyMs = 30),
                    CallRow(4, UsageFoldKit.at("2026-10-12T20:00:00Z"), "recent", latencyMs = 40),
                    CallRow(5, UsageFoldKit.at("2026-10-13T01:00:00Z"), "today", latencyMs = 50)
                )
                val clock = Clock { now }
                val provider = DefaultRepositoryProvider(manager, clock)
                val unitOfWork = SqliteUnitOfWork(manager, provider, clock)
                val folder = UsageDailyFolder(unitOfWork)

                val pass = folder.foldOnce()

                val expectedDays = ChronoUnit.DAYS.between(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-10-12")) + 1
                assertEquals(expectedDays.toInt(), pass.days, "2026-07-01 to 2026-10-12 inclusive")
                assertEquals(4, pass.groups, "one group on each of the 1st, 2nd and 4th of July and on the 12th of October")
                assertEquals(LocalDate.parse("2026-10-12"), pass.foldedThrough)
                assertEquals(false, pass.capped)
                assertEquals(
                    listOf(
                        UsageFoldKit.dailyRow("2026-07-01", null, "old_a", calls = 1, latencySum = 10, latencyMax = 10),
                        UsageFoldKit.dailyRow("2026-07-02", null, "old_a", calls = 1, latencySum = 20, latencyMax = 20),
                        UsageFoldKit.dailyRow("2026-07-04", null, "old_b", calls = 1, latencySum = 30, latencyMax = 30),
                        UsageFoldKit.dailyRow("2026-10-12", null, "recent", calls = 1, latencySum = 40, latencyMax = 40)
                    ),
                    UsageFoldKit.readDaily(url)
                )
                assertEquals(listOf(FoldRow(1, "2026-10-12", "2026-10-13 12:00:00.000")), UsageFoldKit.readFold(url))
                assertEquals(5L, count(url, "call_log"), "the fold deletes nothing")

                val prune =
                    RetentionPruner(
                        unitOfWork = unitOfWork,
                        settings = RetentionSettings(callLogDays = 1, eventsDays = 0, auditEventsDays = 0, leaseHistoryDays = 0),
                        sseBufferSize = 0,
                        folder = folder
                    ).pruneOnce()

                assertEquals(3, prune.callLog, "the three July rows are below min(cutoff, F)")
                assertEquals(0, prune.foldedDays, "everything completed was folded by the explicit pass")
                assertEquals(listOf(testReqId(4), testReqId(5)), RetentionRows.callLogIds(url))
                assertEquals(4L, count(url, "usage_daily"), "pruning raw rows does not touch the summaries")
            } finally {
                manager.shutdown()
            }
        }
}
