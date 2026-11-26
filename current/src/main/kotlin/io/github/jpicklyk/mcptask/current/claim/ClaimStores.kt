package io.github.jpicklyk.mcptask.current.claim

import io.github.jpicklyk.mcptask.current.claim.internal.sqlite.SqliteClaimStore
import io.github.jpicklyk.mcptask.current.platform.port.Clock
import io.github.jpicklyk.mcptask.current.platform.sqlite.DatabaseManager

/** The claim slice's store factory: callers outside the slice build the SQLite claim store here, not by naming its class. */
object ClaimStores {
    /** The SQLite [ClaimStore] over [db], reading time from [clock]. */
    fun sqlite(
        db: DatabaseManager,
        clock: Clock
    ): ClaimStore = SqliteClaimStore(db, clock)
}
