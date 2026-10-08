package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.IdempotencyStore
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentStore
import io.github.jpicklyk.mcptask.current.application.port.ProjectConfigStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.TransitionStore
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.service.SQLiteWorkTreeService
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
    private val roleTransitionRepo by lazy { SQLiteRoleTransitionRepository(databaseManager) }
    private val projectConfigRepo by lazy { SQLiteProjectConfigRepository(databaseManager) }
    private val planDocumentRepo by lazy { SQLitePlanDocumentRepository(databaseManager) }
    private val resourceLeaseRepo by lazy { SQLiteResourceLeaseRepository(databaseManager, clock) }
    private val idempotencyStoreInstance by lazy { SqliteIdempotencyStore(databaseManager) }
    private val workTreeExecutorInstance by lazy { SQLiteWorkTreeService(databaseManager, workItemRepo, noteRepo, planDocumentRepo) }

    override fun workItemRepository(): WorkItemRepository = workItemRepo

    override fun noteRepository(): NoteStore = noteRepo

    override fun dependencyRepository(): DependencyStore = dependencyRepo

    override fun roleTransitionRepository(): TransitionStore = roleTransitionRepo

    override fun projectConfigRepository(): ProjectConfigStore = projectConfigRepo

    override fun planDocumentRepository(): PlanDocumentStore = planDocumentRepo

    override fun resourceLeaseRepository(): LeaseStore = resourceLeaseRepo

    override fun workTreeExecutor(): WorkTreeExecutor = workTreeExecutorInstance

    override fun idempotencyStore(): IdempotencyStore = idempotencyStoreInstance
}
