package io.github.jpicklyk.mcptask.current.run

import io.github.jpicklyk.mcptask.current.platform.port.Clock
import io.github.jpicklyk.mcptask.current.platform.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.run.internal.sqlite.SqliteDecisionRequestStore
import io.github.jpicklyk.mcptask.current.run.internal.sqlite.SqliteRunSeatStore
import io.github.jpicklyk.mcptask.current.run.internal.sqlite.SqliteRunStore

/** The run slice's store factory: callers outside the slice build the SQLite run stores here, not by naming their classes. */
object RunStores {
    /** The SQLite [RunStore] over [db]. */
    fun runs(db: DatabaseManager): RunStore = SqliteRunStore(db)

    /** The SQLite [RunSeatStore] over [db], reading time from [clock]. */
    fun seats(
        db: DatabaseManager,
        clock: Clock
    ): RunSeatStore = SqliteRunSeatStore(db, clock)

    /** The SQLite [DecisionRequestStore] over [db]. */
    fun decisions(db: DatabaseManager): DecisionRequestStore = SqliteDecisionRequestStore(db)
}
