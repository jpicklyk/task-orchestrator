package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.run.RunStoreProvider
import io.github.jpicklyk.mcptask.current.telemetry.TelemetryStoreProvider

/** The stores the report slice reads: the run set and the telemetry fold. [RepositoryProvider] implements it. */
interface ReportStoreProvider :
    RunStoreProvider,
    TelemetryStoreProvider {
    fun reportQueries(): ReportQueries
}
