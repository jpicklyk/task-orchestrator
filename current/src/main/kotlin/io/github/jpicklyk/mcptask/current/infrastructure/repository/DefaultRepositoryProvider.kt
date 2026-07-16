package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.PlanDocumentRepository
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.service.SQLiteWorkTreeService

/**
 * Default repository provider backed by SQLite database implementations.
 *
 * Uses lazy initialization so repository instances are only created when first accessed.
 *
 * @param databaseManager The database manager providing the connection.
 */
class DefaultRepositoryProvider(
    private val databaseManager: DatabaseManager
) : RepositoryProvider {
    private val workItemRepo by lazy { SQLiteWorkItemRepository(databaseManager) }
    private val noteRepo by lazy { SQLiteNoteRepository(databaseManager) }
    private val dependencyRepo by lazy { SQLiteDependencyRepository(databaseManager) }
    private val roleTransitionRepo by lazy { SQLiteRoleTransitionRepository(databaseManager) }
    private val projectConfigRepo by lazy { SQLiteProjectConfigRepository(databaseManager) }
    private val planDocumentRepo by lazy { SQLitePlanDocumentRepository(databaseManager) }
    private val workTreeExecutorInstance by lazy { SQLiteWorkTreeService(databaseManager, workItemRepo, noteRepo, planDocumentRepo) }

    override fun workItemRepository(): WorkItemRepository = workItemRepo

    override fun noteRepository(): NoteRepository = noteRepo

    override fun dependencyRepository(): DependencyRepository = dependencyRepo

    override fun roleTransitionRepository(): RoleTransitionRepository = roleTransitionRepo

    override fun projectConfigRepository(): ProjectConfigRepository = projectConfigRepo

    override fun planDocumentRepository(): PlanDocumentRepository = planDocumentRepo

    override fun database(): org.jetbrains.exposed.v1.jdbc.Database? = databaseManager.getDatabase()

    override fun workTreeExecutor(): WorkTreeExecutor = workTreeExecutorInstance
}
