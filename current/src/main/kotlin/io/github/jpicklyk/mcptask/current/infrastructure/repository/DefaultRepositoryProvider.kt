package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager

/**
 * Default repository provider backed by SQLite database implementations.
 *
 * Uses lazy initialization so repository instances are only created when first accessed.
 *
 * @param databaseManager The database manager providing the connection.
 */
class DefaultRepositoryProvider(private val databaseManager: DatabaseManager) : RepositoryProvider {

    private val workItemRepo by lazy { SQLiteWorkItemRepository(databaseManager) }
    private val noteRepo by lazy { SQLiteNoteRepository(databaseManager) }
    private val dependencyRepo by lazy { SQLiteDependencyRepository(databaseManager) }
    private val roleTransitionRepo by lazy { SQLiteRoleTransitionRepository(databaseManager) }

    override fun workItemRepository(): WorkItemRepository = workItemRepo
    override fun noteRepository(): NoteRepository = noteRepo
    override fun dependencyRepository(): DependencyRepository = dependencyRepo
    override fun roleTransitionRepository(): RoleTransitionRepository = roleTransitionRepo
}
