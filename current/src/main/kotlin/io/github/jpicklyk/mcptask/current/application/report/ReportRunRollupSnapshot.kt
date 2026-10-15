package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.run.RunRollupSnapshot

/**
 * The production [RunRollupSnapshot] (R28): the report service's live run rollup in its stored form
 * ([RollupSnapshotCodec]), read inside the close unit. Built once, in `ServerComposition`, and handed to
 * `ToolExecutionContext`, which must not import this package (application/report imports application/api).
 */
object ReportRunRollupSnapshot {
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
            if (served) ReportService(unitOfWork, clock).runSnapshot(runId)?.let { RollupSnapshotCodec.encode(it) } else null
        }
}
