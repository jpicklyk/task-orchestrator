package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.api.PageRequest
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.DocumentStore
import io.github.jpicklyk.mcptask.current.application.port.EventPage
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.port.IdempotencyStore
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.LinkStore
import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import java.time.Instant
import java.util.UUID

/**
 * An in-memory [EventStore] for tests over mocked repositories (no database, so nothing rolls back: tests of
 * rollback semantics use a real SQLite unit of work instead). Seqs start above [EventStore.SEQ_FLOOR]; [seed] places
 * rows at an explicit seq (a backfilled row sits below the floor). Mirrors the SQLite store's floor: [maxSeq] and
 * [readAfter] never go below [EventStore.SEQ_FLOOR]. [itemTags] answers an entity's tag text for a tag-scoped
 * [findByTypes] (null = the item is gone or untagged, so invisible to a tag-scoped caller).
 */
class InMemoryEventStore(
    private val itemTags: (UUID) -> String? = { null }
) : EventStore {
    private val rows = ArrayList<EventRecord>()

    fun all(): List<EventRecord> = synchronized(rows) { rows.toList() }

    /** Adds [records] at the seq they carry (history rows below the floor), keeping the list in seq order. */
    fun seed(records: List<EventRecord>) {
        synchronized(rows) {
            rows.addAll(records)
            rows.sortBy { it.seq }
        }
    }

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
            val from = maxOf(afterSeq, EventStore.SEQ_FLOOR)
            rows.filter { it.seq > from && (rootIds == null || it.rootId in rootIds) }.take(limit)
        }

    override suspend fun latestOfType(
        type: String,
        entityIds: Set<UUID>
    ): Map<UUID, EventRecord> =
        synchronized(rows) {
            rows.filter { it.type == type && it.entityId in entityIds }.groupBy { it.entityId }.mapValues { (_, v) -> v.maxBy { it.seq } }
        }

    override suspend fun maxSeq(): Long = synchronized(rows) { maxOf(rows.lastOrNull()?.seq ?: EventStore.SEQ_FLOOR, EventStore.SEQ_FLOOR) }

    override suspend fun findByEntity(
        entityId: UUID,
        types: Set<String>,
        page: PageRequest,
        since: Instant?,
        until: Instant?
    ): EventPage =
        synchronized(rows) {
            val hits =
                rows
                    .filter { it.entityId == entityId && it.type in types }
                    .filter { since == null || !it.occurredAt.isBefore(since) }
                    .filter { until == null || !it.occurredAt.isAfter(until) }
                    .sortedByDescending { it.seq }
            EventPage(hits.drop(page.offset).take(page.limit), hits.size)
        }

    override suspend fun findByTypes(
        types: Set<String>,
        since: Instant?,
        until: Instant?,
        scope: AccessScope,
        page: PageRequest
    ): EventPage =
        synchronized(rows) {
            val hits =
                rows
                    .filter { it.type in types }
                    .filter { since == null || !it.occurredAt.isBefore(since) }
                    .filter { until == null || !it.occurredAt.isAfter(until) }
                    .filter { scope.rootIds == null || it.rootId in scope.rootIds!! }
                    .filter { row ->
                        scope.tagsInclude.isEmpty() ||
                            itemTags(row.entityId)
                                ?.split(',')
                                ?.map { it.trim().lowercase() }
                                ?.any { tag -> scope.tagsInclude.any { it.lowercase() == tag } } == true
                    }.sortedByDescending { it.seq }
            EventPage(hits.drop(page.offset).take(page.limit), hits.size)
        }
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

    override fun configStore(): ConfigStore = unsupported()

    override fun planDocumentRepository(): PlanDocumentStore = unsupported()

    override fun documentStore(): DocumentStore = unsupported()

    override fun linkStore(): LinkStore = unsupported()

    override fun idempotencyStore(): IdempotencyStore = unsupported()

    private fun unsupported(): Nothing = error("Not available to the test ClaimService")
}
