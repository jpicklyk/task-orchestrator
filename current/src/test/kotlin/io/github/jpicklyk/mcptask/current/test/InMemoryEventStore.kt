package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.port.IdempotencyStore
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentStore
import io.github.jpicklyk.mcptask.current.application.port.ProjectConfigStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.TransitionStore
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import java.util.UUID

/**
 * An in-memory [EventStore] for tests over mocked repositories (no database, so nothing rolls back: tests of
 * rollback semantics use a real SQLite unit of work instead). Seqs start above [EventStore.SEQ_FLOOR].
 */
class InMemoryEventStore : EventStore {
    private val rows = ArrayList<EventRecord>()

    fun all(): List<EventRecord> = synchronized(rows) { rows.toList() }

    override suspend fun append(records: List<EventRecord>): List<EventRecord> =
        synchronized(rows) {
            records.map { record ->
                val next = (rows.lastOrNull()?.seq ?: EventStore.SEQ_FLOOR) + 1
                record.copy(seq = next).also { rows.add(it) }
            }
        }

    override suspend fun readAfter(
        afterSeq: Long,
        rootIds: Set<UUID>?,
        limit: Int
    ): List<EventRecord> =
        synchronized(rows) {
            rows.filter { it.seq > afterSeq && (rootIds == null || it.rootId in rootIds) }.take(limit)
        }

    override suspend fun latestOfType(
        type: String,
        entityIds: Set<UUID>
    ): Map<UUID, EventRecord> =
        synchronized(rows) {
            rows.filter { it.type == type && it.entityId in entityIds }.groupBy { it.entityId }.mapValues { (_, v) -> v.maxBy { it.seq } }
        }

    override suspend fun maxSeq(): Long = synchronized(rows) { rows.lastOrNull()?.seq ?: EventStore.SEQ_FLOOR }
}

/**
 * A real [ClaimService] over the given stores and an event log ([InMemoryEventStore] unless supplied), for tests that
 * build an AdvanceService from individual stores. Only the item, lease and event stores are reachable.
 */
fun testClaimService(
    items: WorkItemRepository,
    leases: LeaseStore?,
    unitOfWork: UnitOfWork,
    events: EventStore = InMemoryEventStore()
): ClaimService = ClaimService(StoresOnlyProvider(items, leases, events), unitOfWork)

private class StoresOnlyProvider(
    private val items: WorkItemRepository,
    private val leases: LeaseStore?,
    private val events: EventStore
) : RepositoryProvider {
    override fun workItemRepository(): WorkItemRepository = items

    override fun resourceLeaseRepository(): LeaseStore = leases ?: error("No lease store was supplied")

    override fun eventStore(): EventStore = events

    override fun noteRepository(): NoteStore = unsupported()

    override fun dependencyRepository(): DependencyStore = unsupported()

    override fun roleTransitionRepository(): TransitionStore = unsupported()

    override fun projectConfigRepository(): ProjectConfigStore = unsupported()

    override fun planDocumentRepository(): PlanDocumentStore = unsupported()

    override fun idempotencyStore(): IdempotencyStore = unsupported()

    private fun unsupported(): Nothing = error("Not available to the test ClaimService")
}
