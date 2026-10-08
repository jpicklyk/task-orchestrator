package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor

/**
 * Provides access to all repository implementations.
 * Used for dependency injection across the application layer.
 */
interface RepositoryProvider {
    fun workItemRepository(): WorkItemRepository

    /**
     * The narrow work-item stores. Defaults to the composite [workItemRepository], which implements
     * all four, so a provider that only supplies the composite (mocks, the event-publishing decorator)
     * needs no change and events still flow through whatever decorates the composite.
     */
    fun itemStore(): ItemStore = workItemRepository()

    fun hierarchyStore(): HierarchyStore = workItemRepository()

    fun claimStore(): ClaimStore = workItemRepository()

    fun searchIndex(): SearchIndex = workItemRepository()

    fun noteRepository(): NoteStore

    fun dependencyRepository(): DependencyStore

    fun roleTransitionRepository(): TransitionStore

    fun projectConfigRepository(): ProjectConfigStore

    fun planDocumentRepository(): PlanDocumentStore

    fun resourceLeaseRepository(): LeaseStore

    fun workTreeExecutor(): WorkTreeExecutor

    fun idempotencyStore(): IdempotencyStore
}
