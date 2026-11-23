package io.github.jpicklyk.mcptask.current.application.port

/** The stores the report slice reads: the run set and the telemetry fold. [RepositoryProvider] implements it. */
interface ReportStoreProvider :
    RunStoreProvider,
    TelemetryStoreProvider {
    fun reportQueries(): ReportQueries
}
