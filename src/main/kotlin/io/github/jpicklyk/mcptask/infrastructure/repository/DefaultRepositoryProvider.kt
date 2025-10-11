package io.github.jpicklyk.mcptask.infrastructure.repository

import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.*

/**
 * Default implementation of RepositoryProvider that provides access to SQLite-based repositories.
 */
class DefaultRepositoryProvider(private val databaseManager: DatabaseManager) : RepositoryProvider {
    private val projectRepository: SQLiteProjectRepository by lazy {
        SQLiteProjectRepository(databaseManager)
    }

    private val taskRepository: SQLiteTaskRepository by lazy {
        SQLiteTaskRepository(databaseManager)
    }

    private val featureRepository: SQLiteFeatureRepository by lazy {
        SQLiteFeatureRepository(databaseManager)
    }

    private val sectionRepository: SQLiteSectionRepository by lazy {
        SQLiteSectionRepository(databaseManager)
    }

    private val templateRepository: TemplateRepository by lazy {
        // Wrap the SQLite repository with caching for performance
        CachedTemplateRepository(
            SQLiteTemplateRepository(sectionRepository)
        )
    }

    private val dependencyRepository: SQLiteDependencyRepository by lazy {
        SQLiteDependencyRepository(databaseManager)
    }

    override fun projectRepository(): ProjectRepository = projectRepository
    
    override fun taskRepository(): TaskRepository = taskRepository

    override fun featureRepository(): FeatureRepository = featureRepository

    override fun sectionRepository(): SectionRepository = sectionRepository

    override fun templateRepository(): TemplateRepository = templateRepository

    override fun dependencyRepository(): DependencyRepository = dependencyRepository
}