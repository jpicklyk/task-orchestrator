package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ClaimStore
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.HierarchyStore
import io.github.jpicklyk.mcptask.current.application.port.ItemStore
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.time.SystemClock

/**
 * SQLite [WorkItemRepository]: a facade over the three stores. It holds no SQL; each narrow port is
 * delegated to its store ([SqliteItemStore], [SqliteHierarchyStore], [SqliteClaimStore]). Full-text search is the
 * separate [io.github.jpicklyk.mcptask.current.infrastructure.sqlite.knowledge.search.SqliteSearchEngine].
 * The stores share one [Clock]; callers that need a single store take it from
 * [io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider].
 */
class SQLiteWorkItemRepository private constructor(
    private val items: SqliteItemStore,
    private val hierarchy: SqliteHierarchyStore,
    private val claims: SqliteClaimStore
) : WorkItemRepository,
    ItemStore by items,
    HierarchyStore by hierarchy,
    ClaimStore by claims {
    constructor(databaseManager: DatabaseManager, clock: Clock = SystemClock) : this(
        SqliteItemStore(databaseManager, clock),
        SqliteHierarchyStore(databaseManager, clock),
        SqliteClaimStore(databaseManager, clock)
    )
}
