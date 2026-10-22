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

    /**
     * Runs and their membership. Writes belong to `RunService`. The default throws, so a provider that serves runs
     * must override it.
     */
    fun runStore(): RunStore = throw UnsupportedOperationException("This RepositoryProvider has no run store")

    /**
     * Seat assignments (`run_seats`). Writes belong to `SeatService` and the run close. The default throws, so a provider
     * that serves seats must override it.
     */
    fun runSeatStore(): RunSeatStore = throw UnsupportedOperationException("This RepositoryProvider has no run seat store")

    /**
     * Decision requests (`decision_requests`). Writes belong to `DecisionService`. The default throws, so a provider that
     * serves decisions must override it.
     */
    fun decisionRequestStore(): DecisionRequestStore =
        throw UnsupportedOperationException("This RepositoryProvider has no decision request store")

    /**
     * The aggregate report queries (the `report` tool), reached as `stores.reportQueries()` inside the caller's read unit.
     * The default throws, so a provider that serves reports must override it.
     */
    fun reportQueries(): ReportQueries = throw UnsupportedOperationException("This RepositoryProvider has no report queries")

    /**
     * The `usage_daily` fold store (R13), used by the retention pruner's fold and the daily-basis report. The default throws,
     * so a provider that serves the fold must override it.
     */
    fun usageDailyStore(): UsageDailyStore = throw UnsupportedOperationException("This RepositoryProvider has no usage daily store")
}
