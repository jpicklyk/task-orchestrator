package io.github.jpicklyk.mcptask.current.telemetry

/** The stores the telemetry slice reads and writes. [io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider] implements it. */
interface TelemetryStoreProvider {
    fun usageDailyStore(): UsageDailyStore
}
