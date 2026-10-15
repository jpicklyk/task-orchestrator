package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.failSoftReads
import io.github.jpicklyk.mcptask.current.application.run.RunRollupSnapshot
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.UUID

/**
 * The production [RunRollupSnapshot] (R28): the report service's live run rollup in its stored form
 * ([RollupSnapshotCodec]), read inside the close unit. Built once, in `ServerComposition`, and handed to
 * `ToolExecutionContext`, which must not import this package (application/report imports application/api).
 *
 * Fail-soft: a computation fault that is about the DATA (an [IllegalArgumentException], [IllegalStateException] or
 * [SerializationException]) while computing or encoding the rollup is caught and the close proceeds without a stored
 * rollup (WARN logged); a later read computes the rollup live. The snapshot's reads run in a [failSoftReads]
 * section, so a row that fails to decode inside a store read does not poison the close unit and the close still
 * commits. A persistence fault (an [SQLException] anywhere in the cause
 * chain), a cancellation and any other exception still propagate and roll the close back: the transaction state is
 * unknown after them. The [RunRollupSnapshot] contract ("a throw rolls the close back") is unchanged.
 */
object ReportRunRollupSnapshot {
    private val logger = LoggerFactory.getLogger(ReportRunRollupSnapshot::class.java)

    /**
     * The snapshot over [unitOfWork] and [clock]. A provider that serves no report queries (its `reportQueries()` throws
     * [UnsupportedOperationException], as the interface default does) stores none.
     */
    fun over(
        repositoryProvider: RepositoryProvider,
        unitOfWork: UnitOfWork,
        clock: Clock
    ): RunRollupSnapshot =
        RunRollupSnapshot { runId ->
            val served =
                try {
                    repositoryProvider.reportQueries()
                    true
                } catch (e: UnsupportedOperationException) {
                    false
                }
            if (served) computed(unitOfWork, clock, runId) else null
        }

    private suspend fun computed(
        unitOfWork: UnitOfWork,
        clock: Clock,
        runId: UUID
    ): Map<String, Any?>? =
        try {
            failSoftReads { ReportService(unitOfWork, clock).runSnapshot(runId) }?.let { RollupSnapshotCodec.encode(it) }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            val dataFault = e is IllegalArgumentException || e is IllegalStateException || e is SerializationException
            if (!dataFault || generateSequence<Throwable>(e) { it.cause }.any { it is SQLException }) throw e
            logger.warn("Run {} closes without a rollup snapshot: {}", runId, e.toString())
            null
        }
}
