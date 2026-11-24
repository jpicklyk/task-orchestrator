package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.claim.LeaseStore
import io.github.jpicklyk.mcptask.current.dependency.DependencyStore
import io.github.jpicklyk.mcptask.current.knowledge.DocumentStore
import io.github.jpicklyk.mcptask.current.knowledge.LinkStore
import io.github.jpicklyk.mcptask.current.knowledge.PlanDocumentStore
import io.github.jpicklyk.mcptask.current.platform.port.EventStore
import io.github.jpicklyk.mcptask.current.platform.port.IdempotencyStore
import io.github.jpicklyk.mcptask.current.platform.sqlite.SqliteUnitOfWork
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase

/**
 * A provider over [base]'s stores that overrides only the members [RepositoryProvider] leaves abstract. Every other
 * accessor keeps its interface default, so this provider has no run store, run seat store, decision request store,
 * search index, call log store, report queries or usage daily store (each throws [UnsupportedOperationException]), and
 * the adapters over them (`runReads()`, `openRunCount()`, `decisionReads()`, `pruneCallLog`) throw the same way.
 */
internal class NoRunStoresProvider(
    private val base: RepositoryProvider
) : RepositoryProvider {
    override fun workItemRepository(): WorkItemRepository = base.workItemRepository()

    override fun noteRepository(): NoteStore = base.noteRepository()

    override fun dependencyRepository(): DependencyStore = base.dependencyRepository()

    override fun configStore(): ConfigStore = base.configStore()

    override fun planDocumentRepository(): PlanDocumentStore = base.planDocumentRepository()

    override fun documentStore(): DocumentStore = base.documentStore()

    override fun linkStore(): LinkStore = base.linkStore()

    override fun resourceLeaseRepository(): LeaseStore = base.resourceLeaseRepository()

    override fun idempotencyStore(): IdempotencyStore = base.idempotencyStore()

    override fun eventStore(): EventStore = base.eventStore()
}

/** A tool context over [db] whose provider, and the provider of every unit it opens, is a [NoRunStoresProvider]. */
internal fun noRunStoresContext(db: SqliteTestDatabase): ToolExecutionContext {
    val provider = NoRunStoresProvider(db.repositoryProvider())
    return ToolExecutionContext(provider, unitOfWork = SqliteUnitOfWork(db.databaseManager, provider, db.clock))
}
