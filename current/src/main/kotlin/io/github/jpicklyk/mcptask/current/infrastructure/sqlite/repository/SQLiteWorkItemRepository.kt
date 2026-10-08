package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ClaimStore
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.HierarchyStore
import io.github.jpicklyk.mcptask.current.application.port.ItemStore
import io.github.jpicklyk.mcptask.current.application.port.SearchIndex
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.time.SystemClock

/**
 * SQLite [WorkItemRepository]: a facade over the four stores. It holds no SQL; each narrow port is
 * delegated to its store ([SqliteItemStore], [SqliteHierarchyStore], [SqliteClaimStore], [SqliteSearchIndex]).
 * The stores share one [Clock]; callers that need a single store take it from
 * [io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider].
 */
class SQLiteWorkItemRepository private constructor(
    private val items: SqliteItemStore,
    private val hierarchy: SqliteHierarchyStore,
    private val claims: SqliteClaimStore,
    private val search: SqliteSearchIndex
) : WorkItemRepository,
    ItemStore by items,
    HierarchyStore by hierarchy,
    ClaimStore by claims,
    SearchIndex by search {
    constructor(databaseManager: DatabaseManager, clock: Clock = SystemClock) : this(
        SqliteItemStore(databaseManager, clock),
        SqliteHierarchyStore(databaseManager),
        SqliteClaimStore(databaseManager, clock),
        SqliteSearchIndex(databaseManager)
    )

    /** Inserts one row inside the CALLER's open transaction (the work-tree service writes whole trees in one). */
    internal fun insertRow(item: WorkItem): WorkItem = items.insertRow(item)
}
