package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor

/**
 * Provides access to all repository implementations.
 * Used for dependency injection across the application layer.
 */
interface RepositoryProvider {
    fun workItemRepository(): WorkItemRepository

    fun noteRepository(): NoteStore

    fun dependencyRepository(): DependencyStore

    fun roleTransitionRepository(): TransitionStore

    fun projectConfigRepository(): ProjectConfigStore

    fun planDocumentRepository(): PlanDocumentStore

    fun resourceLeaseRepository(): LeaseStore

    fun workTreeExecutor(): WorkTreeExecutor
}
