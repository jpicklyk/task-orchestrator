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

    fun configStore(): ConfigStore

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

    /**
     * The call log, for the retention pruner (appends go through the batched call-log writer, not a unit's stores).
     * The default throws, so a provider that serves retention must override it.
     */
    fun callLogStore(): CallLogStore = throw UnsupportedOperationException("This RepositoryProvider has no call log store")
}
