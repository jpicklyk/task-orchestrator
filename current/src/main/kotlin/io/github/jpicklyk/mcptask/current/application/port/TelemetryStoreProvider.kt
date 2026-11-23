package io.github.jpicklyk.mcptask.current.application.port

/** The stores the telemetry slice reads and writes. [RepositoryProvider] implements it. */
interface TelemetryStoreProvider {
    fun usageDailyStore(): UsageDailyStore
}
