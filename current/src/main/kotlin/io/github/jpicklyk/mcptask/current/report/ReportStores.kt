package io.github.jpicklyk.mcptask.current.report

import io.github.jpicklyk.mcptask.current.platform.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.report.internal.sqlite.SqliteReportQueries

/** The report slice's store factory: callers outside the slice build the SQLite report queries here, not by naming their class. */
object ReportStores {
    /** The SQLite [ReportQueries] over [db]. */
    fun queries(db: DatabaseManager): ReportQueries = SqliteReportQueries(db)
}
