package io.github.jpicklyk.mcptask.current.application.port

/**
 * Provides access to all repository implementations.
 * Used for dependency injection across the application layer.
 */
interface RepositoryProvider {
    fun workItemRepository(): WorkItemRepository

    /**
     * The narrow work-item stores. Defaults to the composite [workItemRepository], which implements
     * all three, so a provider that only supplies the composite (mocks) needs no change.
     */
    fun itemStore(): ItemStore = workItemRepository()

    fun hierarchyStore(): HierarchyStore = workItemRepository()

    fun claimStore(): ClaimStore = workItemRepository()

    /**
     * Full-text candidate retrieval over every searchable corpus. Not part of the work-item composite;
     * the default throws, so a provider that serves search must override it.
     */
    fun searchIndex(): SearchIndex = throw UnsupportedOperationException("This RepositoryProvider has no search index")

    fun noteRepository(): NoteStore

    fun dependencyRepository(): DependencyStore

    fun roleTransitionRepository(): TransitionStore

    fun projectConfigRepository(): ProjectConfigStore

    fun planDocumentRepository(): PlanDocumentStore

    fun resourceLeaseRepository(): LeaseStore

    fun idempotencyStore(): IdempotencyStore

    /**
     * The append-only domain-event log (`events`). Write services append to it through the unit's
     * [WriteScope.events] sink, never directly.
     */
    fun eventStore(): EventStore
}
