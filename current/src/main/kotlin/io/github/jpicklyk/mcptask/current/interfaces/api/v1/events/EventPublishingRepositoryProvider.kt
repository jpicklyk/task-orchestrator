package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.EventSink
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.port.IdempotencyStore
import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentAdoptOutcome
import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentStashOutcome
import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentStore
import io.github.jpicklyk.mcptask.current.application.port.ProjectConfigStore
import io.github.jpicklyk.mcptask.current.application.port.ReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.TransitionStore
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeInput
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeResult
import io.github.jpicklyk.mcptask.current.application.service.eventRootOf
import io.github.jpicklyk.mcptask.current.domain.event.ClaimReleaseReason
import io.github.jpicklyk.mcptask.current.domain.event.DeleteCause
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.event.ReparentSide
import io.github.jpicklyk.mcptask.current.domain.event.TransitionOrigin
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.GuardedUpsertOutcome
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.ProjectConfig
import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.time.Duration
import java.util.UUID

/**
 * A transparent [RepositoryProvider] decorator that records a typed [DomainEvent] row for every mutating store
 * call, through [recorder], in the caller's own unit of work (plan section 3.7). The rows commit or roll back
 * with the change; the SSE stream is a projection of them (see [ApiEventBus]).
 *
 * ## Installed always
 *
 * The server wraps its provider here whether or not the REST API is enabled: the event log is the audit record,
 * not an API feature. Only the SSE projection (the recorder's commit listener) depends on the API.
 *
 * The decorator never changes a return value or signature. It records after the delegate returns; a store write
 * that throws records nothing (and rolls its unit back).
 *
 * ## Coverage
 *
 * Every mutating method of the work-item, note, dependency, transition, lease, project-config and plan-document
 * stores, plus the work-tree executor, is overridden (guarded by `EventPublishingDecoratorGuardTest`). The
 * [IdempotencyStore] is an explicit pass-through: its rows are request bookkeeping, and replays belong to the
 * per-call log (P10). The [EventStore] is never decorated.
 *
 * ## Roots
 *
 * Every row carries a root: the item's denormalized `rootId`, else its ancestor chain's root, else its own id
 * ([eventRootOf]). A reparent writes two `item.reparented` rows (side `left` under the old root, `entered` under
 * the new one), so `root_id` stays single-valued. A role change made through [WorkItemRepository.update] records
 * nothing: the transition row written in the same unit ([TransitionStore.create]) is authoritative.
 *
 * ## Deletes
 *
 * An item delete pre-reads the item's notes and dependency edges and records one `note.deleted` /
 * `dependency.removed` row (cause `cascade`) per row the foreign-key cascade removes, before the `item.deleted`
 * row. Edges shared by two deleted items are recorded once.
 */
class EventPublishingRepositoryProvider(
    private val delegate: RepositoryProvider,
    private val recorder: EventSink,
) : RepositoryProvider {
    private val hierarchy by lazy { delegate.workItemRepository() }

    private suspend fun record(events: List<DomainEvent>) {
        if (events.isNotEmpty()) recorder.record(events)
    }

    private suspend fun record(event: DomainEvent) = record(listOf(event))

    /** The root of the item with [itemId] as it is NOW (pre-read before a delete, post-read after a write). */
    private suspend fun rootOfItem(itemId: UUID): UUID {
        val item = hierarchy.getById(itemId) ?: return eventRootOf(itemId, hierarchy)
        return eventRootOf(item, hierarchy)
    }

    private suspend fun rootOf(item: WorkItem): UUID = eventRootOf(item, hierarchy)

    // -------------------------------------------------------------------------
    // WorkItem repository decorator
    // -------------------------------------------------------------------------

    private inner class EventPublishingWorkItemRepository(
        private val inner: WorkItemRepository,
    ) : WorkItemRepository by inner {
        override suspend fun create(item: WorkItem): WorkItem {
            val result = inner.create(item)
            record(DomainEvent.ItemCreated(result.id, rootOf(result), result.parentId))
            return result
        }

        override suspend fun update(item: WorkItem): WorkItem? {
            val old = inner.getById(item.id)
            val oldRoot = old?.let { rootOf(it) }
            val result = inner.update(item) ?: return null
            if (old == null) {
                record(DomainEvent.ItemUpdated(result.id, rootOf(result), changedFields(null, result)))
                return result
            }
            when {
                // The transition row (TransitionStore.create, same unit) is authoritative for a role change.
                old.role != result.role -> Unit
                old.parentId != result.parentId ->
                    record(
                        listOf(
                            DomainEvent.ItemReparented(result.id, oldRoot ?: result.id, ReparentSide.LEFT, old.parentId, result.parentId),
                            DomainEvent.ItemReparented(result.id, rootOf(result), ReparentSide.ENTERED, old.parentId, result.parentId),
                        ),
                    )
                else -> {
                    val changed = changedFields(old, result)
                    if (changed.isNotEmpty()) record(DomainEvent.ItemUpdated(result.id, rootOf(result), changed))
                }
            }
            return result
        }

        override suspend fun delete(id: UUID): Boolean {
            val item = inner.getById(id)
            val pending = if (item != null) cascadeRows(listOf(item)) else emptyList()
            val result = inner.delete(id)
            if (result && item != null) record(pending)
            return result
        }

        override suspend fun deleteAll(ids: Set<UUID>): Int {
            if (ids.isEmpty()) return inner.deleteAll(ids)
            val items = inner.findByIds(ids)
            val pending = cascadeRows(items)
            val result = inner.deleteAll(ids)
            if (result > 0) record(pending)
            return result
        }

        override suspend fun claim(
            itemId: UUID,
            agentId: String,
            ttlSeconds: Int,
        ): ClaimResult {
            val result = inner.claim(itemId, agentId, ttlSeconds)
            when (result) {
                is ClaimResult.Success -> {
                    val events =
                        mutableListOf<DomainEvent>(DomainEvent.ClaimAcquired(result.item.id, rootOf(result.item), agentId, ttlSeconds))
                    // Every OTHER item this agent held was auto-released as part of this claim.
                    for (releasedId in result.releasedItemIds) {
                        events += DomainEvent.ClaimReleased(releasedId, rootOfItem(releasedId), ClaimReleaseReason.SUPERSEDED)
                    }
                    record(events)
                }
                // A rejection row must survive the caller's unit (a keyed claim rolls back on it): see recordRejection.
                is ClaimResult.AlreadyClaimed ->
                    recorder.recordRejection(DomainEvent.ClaimRejected(itemId, rootOfItem(itemId), result.retryAfterMs))
                else -> Unit
            }
            return result
        }

        override suspend fun release(
            itemId: UUID,
            agentId: String,
        ): ReleaseResult {
            val result = inner.release(itemId, agentId)
            if (result is ReleaseResult.Success) {
                record(DomainEvent.ClaimReleased(result.item.id, rootOf(result.item), ClaimReleaseReason.RELEASED))
            }
            return result
        }

        override suspend fun clear(itemId: UUID): Boolean {
            // clear() reports true for any existing row (every terminal transition calls it), so a row is recorded
            // only when a claim was actually held.
            val held = inner.getById(itemId)?.claimedBy != null
            val result = inner.clear(itemId)
            if (result && held) record(DomainEvent.ClaimReleased(itemId, rootOfItem(itemId), ClaimReleaseReason.CLEARED))
            return result
        }
    }

    /**
     * The rows deleting [items] produces, built BEFORE the delete (the rows they describe are about to vanish):
     * one `note.deleted` and one `dependency.removed` (cause `cascade`) per row the foreign-key cascade removes,
     * then one `item.deleted` per item. All carry the item's pre-delete root; an edge is recorded once, under the
     * first deleted item that references it.
     */
    private suspend fun cascadeRows(items: List<WorkItem>): List<DomainEvent> {
        if (items.isEmpty()) return emptyList()
        val ids = items.map { it.id }.toSet()
        val roots = items.associate { it.id to rootOf(it) }
        val notesByItem = delegate.noteRepository().findRefsByItemIds(ids)
        val edgesByItem = delegate.dependencyRepository().findByItemIds(ids)
        val seenEdges = HashSet<UUID>()
        val events = mutableListOf<DomainEvent>()
        for (item in items) {
            val root = roots.getValue(item.id)
            for (note in notesByItem[item.id].orEmpty()) {
                events += DomainEvent.NoteDeleted(note.id, root, note.itemId, note.key, note.role, DeleteCause.CASCADE)
            }
            for (edge in edgesByItem[item.id].orEmpty()) {
                if (seenEdges.add(edge.id)) events += dependencyRemoved(edge, root, DeleteCause.CASCADE)
            }
        }
        for (item in items) events += DomainEvent.ItemDeleted(item.id, roots.getValue(item.id))
        return events
    }

    // -------------------------------------------------------------------------
    // Note repository decorator
    // -------------------------------------------------------------------------

    private inner class EventPublishingNoteRepository(
        private val inner: NoteStore,
    ) : NoteStore by inner {
        override suspend fun upsert(note: Note): Note {
            val result = inner.upsert(note)
            record(noteUpserted(result, rootOfItem(result.itemId)))
            return result
        }

        override suspend fun delete(id: UUID): Boolean {
            val note = inner.getById(id)
            val result = inner.delete(id)
            if (result && note != null) {
                record(DomainEvent.NoteDeleted(note.id, rootOfItem(note.itemId), note.itemId, note.key, note.role, DeleteCause.EXPLICIT))
            }
            return result
        }

        override suspend fun deleteByItemId(itemId: UUID): Int {
            val notes = inner.findByItemId(itemId)
            val count = inner.deleteByItemId(itemId)
            if (count > 0 && notes.isNotEmpty()) {
                val root = rootOfItem(itemId)
                record(notes.map { DomainEvent.NoteDeleted(it.id, root, it.itemId, it.key, it.role, DeleteCause.EXPLICIT) })
            }
            return count
        }
    }

    private fun noteUpserted(
        note: Note,
        root: UUID,
    ): DomainEvent =
        DomainEvent.NoteUpserted(
            entityId = note.id,
            rootId = root,
            itemId = note.itemId,
            key = note.key,
            role = note.role,
            bodyLength = note.body.length,
            actor = note.actorClaim,
            verification = note.verification,
        )

    // -------------------------------------------------------------------------
    // Dependency repository decorator
    // -------------------------------------------------------------------------

    private inner class EventPublishingDependencyRepository(
        private val inner: DependencyStore,
    ) : DependencyStore by inner {
        override suspend fun create(dependency: Dependency): Dependency {
            val result = inner.create(dependency)
            record(dependencyAdded(result, rootOfItem(result.fromItemId)))
            return result
        }

        override suspend fun delete(id: UUID): Boolean {
            val dep = inner.findById(id)
            val result = inner.delete(id)
            if (result && dep != null) record(dependencyRemoved(dep, rootOfItem(dep.fromItemId), DeleteCause.EXPLICIT))
            return result
        }

        override suspend fun createBatch(dependencies: List<Dependency>): List<Dependency> {
            val result = inner.createBatch(dependencies)
            record(result.map { dependencyAdded(it, rootOfItem(it.fromItemId)) })
            return result
        }

        override suspend fun deleteByItemId(itemId: UUID): Int {
            val edges = inner.findByItemId(itemId)
            val count = inner.deleteByItemId(itemId)
            if (count > 0) record(edges.map { dependencyRemoved(it, rootOfItem(it.fromItemId), DeleteCause.EXPLICIT) })
            return count
        }
    }

    private fun dependencyAdded(
        dep: Dependency,
        root: UUID,
    ): DomainEvent = DomainEvent.DependencyAdded(dep.id, root, dep.fromItemId, dep.toItemId, dep.type.name.lowercase(), dep.unblockAt)

    private fun dependencyRemoved(
        dep: Dependency,
        root: UUID,
        cause: DeleteCause,
    ): DomainEvent =
        DomainEvent.DependencyRemoved(dep.id, root, dep.fromItemId, dep.toItemId, dep.type.name.lowercase(), dep.unblockAt, cause)

    // -------------------------------------------------------------------------
    // Transition store decorator
    // -------------------------------------------------------------------------

    private inner class EventPublishingTransitionStore(
        private val inner: TransitionStore,
    ) : TransitionStore by inner {
        override suspend fun create(transition: RoleTransition): RoleTransition {
            val result = inner.create(transition)
            record(
                DomainEvent.ItemTransitioned(
                    entityId = result.itemId,
                    rootId = rootOfItem(result.itemId),
                    trigger = result.trigger,
                    fromRole = result.fromRole,
                    toRole = result.toRole,
                    fromStatusLabel = result.fromStatusLabel,
                    toStatusLabel = result.toStatusLabel,
                    origin = if (result.trigger == "cascade") TransitionOrigin.CASCADE else TransitionOrigin.USER,
                    actor = result.actorClaim,
                    verification = result.verification,
                ),
            )
            return result
        }
    }

    // -------------------------------------------------------------------------
    // Lease store decorator (entity = the holder item)
    // -------------------------------------------------------------------------

    private inner class EventPublishingLeaseStore(
        private val inner: LeaseStore,
    ) : LeaseStore by inner {
        override suspend fun acquireAll(
            holderItemId: UUID,
            actorId: String?,
            requirements: List<Pair<String, Int>>,
        ): LeaseAcquireResult {
            val result = inner.acquireAll(holderItemId, actorId, requirements)
            when (result) {
                is LeaseAcquireResult.Success ->
                    if (result.leases.isNotEmpty()) {
                        val root = rootOfItem(holderItemId)
                        record(
                            result.leases.map {
                                DomainEvent.LeaseAcquired(
                                    holderItemId,
                                    root,
                                    it.resourceKey,
                                    Duration.between(it.acquiredAt, it.expiresAt).seconds
                                )
                            },
                        )
                    }
                is LeaseAcquireResult.Contended ->
                    recorder.recordRejection(
                        DomainEvent.LeaseRejected(holderItemId, rootOfItem(holderItemId), result.contendedKeys, result.retryAfterMs),
                    )
            }
            return result
        }

        override suspend fun releaseAllForItem(holderItemId: UUID): LeaseReleaseResult {
            val active = inner.findActiveForItem(holderItemId)
            val result = inner.releaseAllForItem(holderItemId)
            recordReleases(holderItemId, active.map { it.resourceKey }, result)
            return result
        }

        /**
         * Set-based, like the store it wraps: ONE pre-read of the active leases (filtered to [holderItemIds]) and ONE
         * bulk release, then one `lease.released` row per active (holder, key) found. Lapsed rows the bulk release
         * also removes are expiries, not releases, and record nothing (expiry rows are P14's).
         */
        override suspend fun releaseAllForItems(holderItemIds: Set<UUID>): LeaseReleaseResult {
            if (holderItemIds.isEmpty()) return inner.releaseAllForItems(holderItemIds)
            val active = inner.findAllActive().filter { it.holderItemId in holderItemIds }
            val result = inner.releaseAllForItems(holderItemIds)
            val released = (result as? LeaseReleaseResult.Success)?.releasedCount ?: 0
            if (released > 0 && active.isNotEmpty()) {
                val events = mutableListOf<DomainEvent>()
                for ((holderItemId, leases) in active.groupBy { it.holderItemId }) {
                    val root = rootOfItem(holderItemId)
                    for (lease in leases) events += DomainEvent.LeaseReleased(holderItemId, root, lease.resourceKey, 1, forced = false)
                }
                record(events)
            }
            return result
        }

        override suspend fun forceReleaseByKey(
            resourceKey: String,
            actorId: String?,
        ): LeaseReleaseResult {
            val holders = inner.findActiveByKeys(listOf(resourceKey)).map { it.holderItemId }.distinct()
            val result = inner.forceReleaseByKey(resourceKey, actorId)
            val released = (result as? LeaseReleaseResult.Success)?.releasedCount ?: 0
            if (released > 0 && holders.isNotEmpty()) {
                record(holders.map { DomainEvent.LeaseReleased(it, rootOfItem(it), resourceKey, 1, forced = true) })
            }
            return result
        }

        private suspend fun recordReleases(
            holderItemId: UUID,
            keys: List<String>,
            result: LeaseReleaseResult,
        ) {
            val released = (result as? LeaseReleaseResult.Success)?.releasedCount ?: 0
            if (released <= 0) return
            val root = rootOfItem(holderItemId)
            if (keys.isEmpty()) {
                record(DomainEvent.LeaseReleased(holderItemId, root, null, released, forced = false))
            } else {
                record(keys.map { DomainEvent.LeaseReleased(holderItemId, root, it, 1, forced = false) })
            }
        }
    }

    // -------------------------------------------------------------------------
    // Project-config and plan-document decorators (root = the project root item)
    // -------------------------------------------------------------------------

    private inner class EventPublishingProjectConfigStore(
        private val inner: ProjectConfigStore,
    ) : ProjectConfigStore by inner {
        override suspend fun upsert(
            rootItemId: UUID,
            configYaml: String,
        ): ProjectConfig {
            val result = inner.upsert(rootItemId, configYaml)
            record(DomainEvent.ProjectConfigUpserted(rootItemId, result.fingerprint))
            return result
        }

        override suspend fun upsertGuarded(
            rootItemId: UUID,
            configYaml: String,
            expectedFingerprint: String?,
            rejectSuperseded: Boolean,
        ): GuardedUpsertOutcome {
            val result = inner.upsertGuarded(rootItemId, configYaml, expectedFingerprint, rejectSuperseded)
            if (result is GuardedUpsertOutcome.Applied) {
                record(DomainEvent.ProjectConfigUpserted(rootItemId, result.config.fingerprint))
            }
            return result
        }

        override suspend fun delete(rootItemId: UUID): Boolean {
            val result = inner.delete(rootItemId)
            if (result) record(DomainEvent.ProjectConfigDeleted(rootItemId))
            return result
        }
    }

    private inner class EventPublishingPlanDocumentStore(
        private val inner: PlanDocumentStore,
    ) : PlanDocumentStore by inner {
        override suspend fun stash(
            rootItemId: UUID,
            slug: String,
            body: String,
        ): PlanDocumentStashOutcome {
            val result = inner.stash(rootItemId, slug, body)
            if (result is PlanDocumentStashOutcome.Stored) {
                record(DomainEvent.PlanDocumentStashed(result.document.id, rootItemId, slug))
            }
            return result
        }

        override suspend fun markAdopted(
            rootItemId: UUID,
            slug: String,
            adoptedByItemId: UUID,
        ): PlanDocumentAdoptOutcome {
            val result = inner.markAdopted(rootItemId, slug, adoptedByItemId)
            if (result is PlanDocumentAdoptOutcome.Adopted) {
                record(DomainEvent.PlanDocumentAdopted(result.document.id, rootItemId, slug, adoptedByItemId))
            }
            return result
        }
    }

    // -------------------------------------------------------------------------
    // WorkTreeExecutor decorator
    // -------------------------------------------------------------------------

    /**
     * Records from the returned [WorkTreeResult]: the SQLite work-tree service inserts through internal row
     * helpers, so its constituent stores cannot be decorated. `create_work_tree` runs `execute` inside its write
     * unit, so these rows join that unit. A docRef adoption is recorded from the document as stored afterwards.
     */
    private inner class EventPublishingWorkTreeExecutor(
        private val inner: WorkTreeExecutor,
    ) : WorkTreeExecutor {
        override suspend fun execute(input: WorkTreeInput): WorkTreeResult {
            val result = inner.execute(input)
            val events = mutableListOf<DomainEvent>()
            // Items are root-first and exclude an attach-mode pre-existing root, so no spurious item.created.
            for (item in result.items) events += DomainEvent.ItemCreated(item.id, rootOf(item), item.parentId)
            for (dep in result.deps) events += dependencyAdded(dep, rootOfItem(dep.fromItemId))
            for (note in result.notes) events += noteUpserted(note, rootOfItem(note.itemId))
            val docRef = input.docRef
            if (docRef != null) {
                val doc = delegate.planDocumentRepository().get(docRef.rootItemId, docRef.slug)
                if (doc != null) events += DomainEvent.PlanDocumentAdopted(doc.id, docRef.rootItemId, docRef.slug, doc.adoptedByItemId)
            }
            record(events)
            return result
        }
    }

    // -------------------------------------------------------------------------
    // RepositoryProvider interface
    // -------------------------------------------------------------------------

    private val wrappedWorkItemRepo by lazy { EventPublishingWorkItemRepository(delegate.workItemRepository()) }
    private val wrappedNoteRepo by lazy { EventPublishingNoteRepository(delegate.noteRepository()) }
    private val wrappedDependencyRepo by lazy { EventPublishingDependencyRepository(delegate.dependencyRepository()) }
    private val wrappedTransitionStore by lazy { EventPublishingTransitionStore(delegate.roleTransitionRepository()) }
    private val wrappedLeaseStore by lazy { EventPublishingLeaseStore(delegate.resourceLeaseRepository()) }
    private val wrappedProjectConfigStore by lazy { EventPublishingProjectConfigStore(delegate.projectConfigRepository()) }
    private val wrappedPlanDocumentStore by lazy { EventPublishingPlanDocumentStore(delegate.planDocumentRepository()) }
    private val wrappedWorkTreeExecutor by lazy { EventPublishingWorkTreeExecutor(delegate.workTreeExecutor()) }

    override fun workItemRepository(): WorkItemRepository = wrappedWorkItemRepo

    override fun noteRepository(): NoteStore = wrappedNoteRepo

    override fun dependencyRepository(): DependencyStore = wrappedDependencyRepo

    override fun roleTransitionRepository(): TransitionStore = wrappedTransitionStore

    override fun projectConfigRepository(): ProjectConfigStore = wrappedProjectConfigStore

    override fun planDocumentRepository(): PlanDocumentStore = wrappedPlanDocumentStore

    override fun resourceLeaseRepository(): LeaseStore = wrappedLeaseStore

    override fun workTreeExecutor(): WorkTreeExecutor = wrappedWorkTreeExecutor

    /** Explicit pass-through: request bookkeeping, not a domain change (replays belong to the P10 call log). */
    override fun idempotencyStore(): IdempotencyStore = delegate.idempotencyStore()

    /** Never decorated: its appends are what this decorator records. */
    override fun eventStore(): EventStore = delegate.eventStore()

    companion object {
        /** The item fields whose change an `item.updated` row reports (bookkeeping fields excluded). */
        internal fun changedFields(
            old: WorkItem?,
            new: WorkItem,
        ): List<String> {
            if (old == null) return listOf("*")
            return buildList {
                if (old.title != new.title) add("title")
                if (old.description != new.description) add("description")
                if (old.summary != new.summary) add("summary")
                if (old.statusLabel != new.statusLabel) add("statusLabel")
                if (old.previousRole != new.previousRole) add("previousRole")
                if (old.priority != new.priority) add("priority")
                if (old.complexity != new.complexity) add("complexity")
                if (old.requiresVerification != new.requiresVerification) add("requiresVerification")
                if (old.rootId != new.rootId) add("rootId")
                if (old.depth != new.depth) add("depth")
                if (old.metadata != new.metadata) add("metadata")
                if (old.tags != new.tags) add("tags")
                if (old.type != new.type) add("type")
                if (old.properties != new.properties) add("properties")
                if (old.claimedBy != new.claimedBy) add("claimedBy")
                if (old.claimExpiresAt != new.claimExpiresAt) add("claimExpiresAt")
            }
        }
    }
}
