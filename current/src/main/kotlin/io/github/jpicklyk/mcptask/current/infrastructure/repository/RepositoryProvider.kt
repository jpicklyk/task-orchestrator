package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository

/**
 * Provides access to all repository implementations.
 * Used for dependency injection across the application layer.
 */
interface RepositoryProvider {
    fun workItemRepository(): WorkItemRepository
    fun noteRepository(): NoteRepository
    fun dependencyRepository(): DependencyRepository
    fun roleTransitionRepository(): RoleTransitionRepository
    fun database(): org.jetbrains.exposed.v1.jdbc.Database?
    fun workTreeExecutor(): WorkTreeExecutor
}
