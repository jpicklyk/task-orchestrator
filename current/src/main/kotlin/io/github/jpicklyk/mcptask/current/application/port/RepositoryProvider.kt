package io.github.jpicklyk.mcptask.current.application.port

/**
 * Provides access to all repository implementations.
 * Used for dependency injection across the application layer.
 */
interface RepositoryProvider {
    fun workItemRepository(): WorkItemRepository

    /**
     * The narrow work-item stores. Defaults to the composite [workItemRepository], which implements
     * all four, so a provider that only supplies the composite (mocks) needs no change.
     */
    fun itemStore(): ItemStore = workItemRepository()

    fun hierarchyStore(): HierarchyStore = workItemRepository()

    fun claimStore(): ClaimStore = workItemRepository()

    fun searchIndex(): SearchIndex = workItemRepository()

    fun noteRepository(): NoteStore

    fun dependencyRepository(): DependencyStore

    fun roleTransitionRepository(): TransitionStore

    fun projectConfigRepository(): ProjectConfigStore

    /**
     * The 3.x plan-document view, backed by [documentStore] and [linkStore] since 4.0 (a `rule/<key>` slug is the
     * RULE-kind document `<key>`; adoption is an `adopted_by` link).
     */
    fun planDocumentRepository(): PlanDocumentStore

    /** Versioned documents and their history. Writes belong to the knowledge document service. */
    fun documentStore(): DocumentStore

    /** Polymorphic links (adoption provenance and, later, references). */
    fun linkStore(): LinkStore

    fun resourceLeaseRepository(): LeaseStore

    fun idempotencyStore(): IdempotencyStore

    /**
     * The append-only domain-event log (`events`). Write services append to it through the unit's
     * [WriteScope.events] sink, never directly.
     */
    fun eventStore(): EventStore
}
