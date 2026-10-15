package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.config.RunsConfig
import io.github.jpicklyk.mcptask.current.application.port.ReadScope
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.run.RunCloseHook
import io.github.jpicklyk.mcptask.current.application.run.RunEndedSnapshot
import io.github.jpicklyk.mcptask.current.application.run.RunOpenCommand
import io.github.jpicklyk.mcptask.current.application.run.RunService
import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import io.github.jpicklyk.mcptask.current.application.service.ok
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f65fef01, R29) for scenario S12 (R28 N4): a failure of the rollup computation at close
 * time must not block the close.
 *
 * Oracles (never read from a run), the frozen task-scope D10 and the seam contract of `RunRollupSnapshot` ("a throw rolls the close
 * back"): the production snapshot (`ReportRunRollupSnapshot.over`) catches IllegalArgumentException, IllegalStateException and
 * SerializationException raised by the computation, logs a warning and answers null, so the close succeeds, `run.ended` carries no
 * `rollup` and a later read of the closed run computes the rollup live (R28 AC9); cancellation and every other failure, a persistence
 * fault above all (the transaction state is unknown), still propagate and roll the close back (the run stays open, no `run.ended`).
 *
 * The failure is injected through a unit of work whose reads throw the chosen exception, handed to the production snapshot, so it is
 * raised by the computation itself. (The finding's own fixture, a non-coordinator seat row with no item, cannot be driven through the
 * production close: the domain refuses to load such an active row in the close cascade before the snapshot runs.)
 *
 * Negative-assertion fixture conditions: every "close is rolled back" assertion is paired with the same close succeeding with a
 * healthy snapshot on a fresh run (so the rollback is the failure's doing), and every "no rollup" assertion with a healthy close that
 * stores one.
 *
 * Domain invariants satisfied by construction: runs are opened through the service over a root created through the repository.
 *
 * Forbidden-construct declaration: the persistence and cancellation legs catch the failure of the close call itself with `runCatching`;
 * every assertion is outside the handler.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class RunRollupSnapshotFailSoftTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private class FaultyReads(
        private val real: UnitOfWork,
        private val fault: () -> Throwable
    ) : UnitOfWork by real {
        override suspend fun <T> read(block: suspend ReadScope.() -> T): T = throw fault()
    }

    private object NoopHook : RunCloseHook {
        override suspend fun onClose(
            run: Run,
            snapshot: RunEndedSnapshot
        ) = Unit
    }

    private inner class Rig(
        fault: (() -> Throwable)?
    ) {
        val provider get() = sqlite.db.repositoryProvider()
        val uow = sqlite.db.unitOfWork(clock)
        val service =
            RunService(
                provider,
                uow,
                ClaimService(provider, uow),
                runsConfig = { RunsConfig() },
                onClose = NoopHook,
                rollupSnapshot = ReportRunRollupSnapshot.over(provider, if (fault == null) uow else FaultyReads(uow, fault), clock)
            )

        suspend fun openRun(): Run {
            val root = provider.workItemRepository().create(WorkItem(title = "root", depth = 0))
            return service.open(RunOpenCommand(rootId = root.id)).ok()
        }

        suspend fun maxSeq(): Long = provider.eventStore().maxSeq()

        suspend fun ended(
            runId: UUID,
            mark: Long
        ) = provider.eventStore().readAfter(mark, null, 100_000).filter { it.type == "run.ended" && it.entityId == runId }
    }

    private suspend fun assertHealthyCloseStoresARollup() {
        val healthy = Rig(null)
        val run = healthy.openRun()
        val mark = healthy.maxSeq()
        healthy.service.close(run.id, RunTerminalReason.COMPLETED).ok()
        val ended = healthy.ended(run.id, mark).single().payload()
        assertTrue("rollup" in ended, "control: a healthy close stores the rollup: $ended")
    }

    // ------------------------------------------------------------------ the three failures the snapshot absorbs

    private fun absorbed(
        label: String,
        fault: () -> Throwable
    ) = runBlocking {
        val rig = Rig(fault)
        val run = rig.openRun()
        val mark = rig.maxSeq()

        rig.service.close(run.id, RunTerminalReason.COMPLETED).ok()

        assertEquals(
            RunStatus.CLOSED,
            rig.provider
                .runStore()
                .getById(run.id)
                ?.status,
            "$label: the close succeeded"
        )
        val ended = rig.ended(run.id, mark).single().payload()
        assertFalse("rollup" in ended, "$label: run.ended carries no rollup: $ended")
        assertEquals("completed", ended.getValue("reason").toString().trim('"'), "$label: and still carries its reason")
        assertHealthyCloseStoresARollup()

        val f = ReportFixture(sqlite.db, clock)
        val live =
            f.service().rollup(
                f.ctx(),
                ReportRollupRequest(
                    runId = IdRef(run.id.toString())
                )
            )
        val report =
            assertNotNull(
                (live as? Outcome.Ok)?.value,
                "$label: a later read computes live: $live"
            )
        assertEquals(RunStatus.CLOSED, report.run?.status, "$label: the live rollup shows the closed run")
    }

    @Test
    fun `S12 an IllegalStateException from the rollup computation does not block the close and a later read is live`() =
        absorbed("IllegalStateException") { IllegalStateException("injected: bad seat row") }

    @Test
    fun `S12 an IllegalArgumentException from the rollup computation does not block the close`() =
        absorbed("IllegalArgumentException") { IllegalArgumentException("injected: bad argument") }

    @Test
    fun `S12 a SerializationException from the rollup computation does not block the close`() =
        absorbed("SerializationException") { SerializationException("injected: bad snapshot") }

    // ------------------------------------------------------------------ the failures that still roll the close back

    private fun propagates(
        label: String,
        fault: () -> Throwable
    ) = runBlocking {
        val rig = Rig(fault)
        val run = rig.openRun()
        val mark = rig.maxSeq()

        val outcome = runCatching { rig.service.close(run.id, RunTerminalReason.COMPLETED) }

        assertFalse(outcome.getOrNull() is Outcome.Ok, "$label: the close does not succeed: $outcome")
        assertEquals(
            RunStatus.OPEN,
            rig.provider
                .runStore()
                .getById(run.id)
                ?.status,
            "$label: the close was rolled back, the run is still open"
        )
        assertEquals(emptyList(), rig.ended(run.id, mark), "$label: no run.ended row survives")
        assertHealthyCloseStoresARollup()
    }

    @Test
    fun `S12 a SQLException from the rollup computation rolls the close back`() =
        propagates("SQLException") { SQLException("injected: persistence fault") }

    @Test
    fun `S12 a cancellation from the rollup computation rolls the close back instead of being absorbed`() =
        propagates("CancellationException") { CancellationException("injected: cancelled") }

    @Test
    fun `S12 a failure of any other kind rolls the close back`() =
        propagates("RuntimeException") { RuntimeException("injected: unexpected") }
}
