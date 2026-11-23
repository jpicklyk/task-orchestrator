package io.github.jpicklyk.mcptask.current.application.port

/** The stores the report slice reads. [RepositoryProvider] implements it. */
interface ReportStoreProvider {
    fun reportQueries(): ReportQueries
}
