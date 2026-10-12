package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.DocumentStore
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.port.IdempotencyStore
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.LinkStore
import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.SearchIndex
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.knowledge.document.SqliteDocumentStore
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.knowledge.document.SqliteLinkStore
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.knowledge.search.SqliteSearchEngine
import io.github.jpicklyk.mcptask.current.infrastructure.time.SystemClock

/**
 * Default repository provider backed by SQLite database implementations.
 *
 * Uses lazy initialization so repository instances are only created when first accessed.
 *
 * @param databaseManager The database manager providing the connection.
 * @param clock The one time source bound into the claim and lease stores (the ambient unit instant wins inside a unit).
 */
class DefaultRepositoryProvider(
    private val databaseManager: DatabaseManager,
    private val clock: Clock = SystemClock
) : RepositoryProvider {
    private val workItemRepo by lazy { SQLiteWorkItemRepository(databaseManager, clock) }
    private val noteRepo by lazy { SQLiteNoteRepository(databaseManager) }
    private val dependencyRepo by lazy { SQLiteDependencyRepository(databaseManager) }
    private val configStore by lazy { SqliteConfigStore(databaseManager) }
    private val documentStoreInstance by lazy { SqliteDocumentStore(databaseManager) }
    private val linkStoreInstance by lazy { SqliteLinkStore(databaseManager) }
    private val planDocumentRepo by lazy { DocumentBackedPlanDocumentStore(databaseManager, documentStoreInstance, linkStoreInstance) }
    private val resourceLeaseRepo by lazy { SQLiteResourceLeaseRepository(databaseManager, clock) }
    private val idempotencyStoreInstance by lazy { SqliteIdempotencyStore(databaseManager) }
    private val eventStoreInstance by lazy { SqliteEventStore(databaseManager) }
    private val searchEngine by lazy { SqliteSearchEngine(databaseManager) }

    override fun workItemRepository(): WorkItemRepository = workItemRepo

    override fun searchIndex(): SearchIndex = searchEngine

    override fun noteRepository(): NoteStore = noteRepo

    override fun dependencyRepository(): DependencyStore = dependencyRepo

    override fun configStore(): ConfigStore = configStore

    override fun planDocumentRepository(): PlanDocumentStore = planDocumentRepo

    override fun documentStore(): DocumentStore = documentStoreInstance

    override fun linkStore(): LinkStore = linkStoreInstance

    override fun resourceLeaseRepository(): LeaseStore = resourceLeaseRepo

    override fun idempotencyStore(): IdempotencyStore = idempotencyStoreInstance

    override fun eventStore(): EventStore = eventStoreInstance
}
