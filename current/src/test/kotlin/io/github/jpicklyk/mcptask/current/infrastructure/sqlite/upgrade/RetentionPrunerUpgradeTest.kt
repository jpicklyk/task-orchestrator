package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.infrastructure.RetentionPruner
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.RetentionSettings
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.SqliteUnitOfWork
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.test.RetentionRows
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent test (test-author seat, item 0da29ea1, plan section 10 case "pruner runs over an upgraded DB"): the checked-in
 * golden V17 database is upgraded through the production startup path (initialize, updateSchema) and the retention pruner then
 * runs over it. Oracle (frozen task-scope invariants 2 and 4): the rows V29 backfilled below the seq floor are audit and
 * survive a pass with `auditEventsDays = 0`; of the golden's lease intervals every open one survives and a closed one survives
 * exactly when `released_at >= now - leaseHistoryDays`; an ancient activity row added above the floor is the control that
 * proves the pass really deletes on this database. Expected counts are read from the upgraded file with plain SQL BEFORE the
 * pass, never from the pruner.
 *
 * Forbidden-construct declaration: none.
 */
class RetentionPrunerUpgradeTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `pruner runs over an upgraded database, keeps backfilled audit rows and open leases, and deletes what it should`(): Unit =
        runBlocking {
            val floor = EventStore.SEQ_FLOOR
            val now = Instant.now()
            val golden = GoldenV17.copyTo(File(dir, "retention.sqlite"))
            val url = UpgradeHarness.urlFor(golden)
            val manager = DatabaseManager(appConfig = AppConfig.fromEnv { null })
            try {
                assertTrue(manager.initialize(url), "initialize must accept the golden database")
                assertTrue(manager.updateSchema(), "production updateSchema must upgrade the golden database")

                val subFloorBefore = RetentionRows.scalar(url, "SELECT count(*) FROM events WHERE seq < $floor")
                assertTrue(subFloorBefore > 0, "the upgrade must have backfilled events below the floor, else this test proves nothing")
                val openLeases = RetentionRows.scalar(url, "SELECT count(*) FROM resource_lease_history WHERE released_at IS NULL")
                val leaseCutoff = RetentionRows.stamp(now.minus(Duration.ofDays(1)))
                val keepClosed =
                    RetentionRows.scalar(
                        url,
                        "SELECT count(*) FROM resource_lease_history WHERE released_at IS NOT NULL AND released_at >= '$leaseCutoff'"
                    )

                RetentionRows.insertEvent(url, floor + 1, DomainEvent.NOTE_UPSERTED, now.minus(Duration.ofDays(400)))
                RetentionRows.insertEvent(url, floor + 2, DomainEvent.NOTE_UPSERTED, now.minusSeconds(3600))

                val clock = Clock { now }
                val provider = DefaultRepositoryProvider(manager, clock)
                val unitOfWork = SqliteUnitOfWork(manager, provider, clock)
                val pass =
                    RetentionPruner(
                        unitOfWork = unitOfWork,
                        settings = RetentionSettings(callLogDays = 1, eventsDays = 1, auditEventsDays = 0, leaseHistoryDays = 1),
                        sseBufferSize = 0
                    ).pruneOnce()

                assertEquals(1, pass.events, "exactly the ancient activity control row is deleted from the events table")
                assertEquals(
                    subFloorBefore,
                    RetentionRows.scalar(url, "SELECT count(*) FROM events WHERE seq < $floor"),
                    "every backfilled sub-floor row is audit and must survive"
                )
                assertEquals(listOf(floor + 2), RetentionRows.eventSeqs(url).filter { it >= floor })
                assertEquals(
                    openLeases + keepClosed,
                    RetentionRows.scalar(url, "SELECT count(*) FROM resource_lease_history"),
                    "open intervals and closed intervals within the window survive; older closed intervals are pruned"
                )
                assertEquals(
                    openLeases,
                    RetentionRows.scalar(url, "SELECT count(*) FROM resource_lease_history WHERE released_at IS NULL"),
                    "no open interval is ever pruned"
                )
            } finally {
                manager.shutdown()
            }
        }
}
