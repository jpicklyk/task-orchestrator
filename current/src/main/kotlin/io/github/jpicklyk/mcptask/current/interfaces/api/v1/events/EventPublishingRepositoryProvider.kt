package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeInput
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeResult
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.PlanDocumentRepository
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.ReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A transparent [RepositoryProvider] decorator that publishes [ApiEvent]s to [eventBus]
 * AFTER each successful write.
 *
 * ## Additive / Default-Off design (non-negotiable)
 *
 * When the API is disabled, [CurrentMcpServer] returns the UNDECORATED [RepositoryProvider] —
 * this decorator is never constructed. MCP-only deployments get zero behavior change and
 * zero overhead. When the API is enabled, the server wraps the provider here.
 *
 * The decorator MUST NOT change any return value or signature — it only adds a post-write side
 * effect after the delegate returns success.
 *
 * ## Root-ancestor caching
 *
 * To fan out events to the correct root-topic subscribers without a per-event DB query, this
 * decorator maintains an in-memory `itemId → Set<UUID>` ancestor cache. The cache is populated
 * lazily on first access via [resolveRoots] and is invalidated on parentId change (reparent).
 *
 * When a reparent is detected (old parentId ≠ new parentId), `scope.left` events are emitted
 * to old-root subscribers and `scope.entered` events are emitted to new-root subscribers.
 */
class EventPublishingRepositoryProvider(
    private val delegate: RepositoryProvider,
    private val eventBus: ApiEventBus,
) : RepositoryProvider {
    private val logger = LoggerFactory.getLogger(EventPublishingRepositoryProvider::class.java)

    /**
     * Routes every publish in this decorator so that events raised inside an open transaction are
     * held until it commits, and discarded if it rolls back. See [DeferredEventPublisher].
     */
    private val deferredPublisher = DeferredEventPublisher(eventBus)

    // -------------------------------------------------------------------------
    // Root-ancestor cache
    // -------------------------------------------------------------------------

    /** Cache: item UUID → its root UUID set (the root items in its ancestor chain). */
    private val rootCache = ConcurrentHashMap<UUID, Set<UUID>>()

    /**
     * Resolve the root UUIDs for [itemId] — the set of IDs with depth=0 in the ancestor chain.
     * For root items (depth=0) this is {itemId} itself.
     *
     * Uses the cache; falls back to a live [WorkItemRepository.findAncestorChains] query.
     * On query failure, returns an empty set — which [publishScoped] marks UNRESOLVED, so the
     * event reaches only unrestricted subscribers rather than being broadcast to every one.
     */
    private suspend fun resolveRoots(itemId: UUID): Set<UUID> {
        // Performance guard: when no SSE clients are connected, skip the ancestor-chain DB query.
        // Returning emptySet() marks the event UNRESOLVED in publishScoped — with zero subscribers
        // there is nothing to fan out to anyway, and the event is still added to the ring buffer
        // for a future client's Last-Event-ID replay, where the unresolved flag keeps it out of a
        // root-scoped resume. This keeps MCP-tool writes cheap when the API is enabled but no
        // dashboard is watching (e.g. stdio mode, or http with no live connections).
        if (eventBus.subscriberCount() == 0) return emptySet()

        rootCache[itemId]?.let { return it }

        return try {
            val chains = delegate.workItemRepository().findAncestorChains(setOf(itemId))
            val chain = chains.getOrNull()?.get(itemId) ?: emptyList()
            // Ancestor chain is ordered root-first. The root is the first item (depth=0),
            // or the item itself if it has no ancestors (is itself a root).
            val roots =
                if (chain.isEmpty()) {
                    setOf(itemId) // it IS the root
                } else {
                    setOf(chain.first().id)
                }
            rootCache[itemId] = roots
            roots
        } catch (e: Exception) {
            logger.warn("Failed to resolve roots for item {}: {}", itemId, e.message)
            emptySet()
        }
    }

    /**
     * Enqueue an event of [eventType] with [roots] as its affected-root set, marking it UNRESOLVED
     * when [roots] is empty.
     *
     * Every publish site in this decorator goes through here. No site in this class ever intends a
     * bus-level broadcast — an empty [roots] here always means "we could not work out which roots
     * this event belongs to", which is precisely the case [ApiEventBus.publish]'s `rootsResolved`
     * flag exists to distinguish. Two producers of an empty set feed this:
     *
     * 1. [resolveRoots]'s no-subscriber performance guard (the high-volume one: every write made
     *    while nobody is connected is buffered for a future `Last-Event-ID` replay), and
     * 2. [resolveRoots]'s ancestor-chain query failure.
     *
     * A cold root cache is no longer among them: every publish site here resolves roots through
     * the suspend [resolveRoots], which falls back to an ancestor-chain query on a cache miss.
     *
     * Marking these unresolved keeps them out of root-scoped subscribers' streams and replays.
     * Unrestricted subscribers still receive them.
     *
     * ## Why a descriptor rather than a built event
     *
     * Delivery is routed through [DeferredEventPublisher]: with no transaction open the event is
     * built and published synchronously here (unchanged behaviour for every standalone write),
     * and inside an open transaction it is held until that transaction COMMITS — so a rolled-back
     * `inTransaction` block publishes nothing. Root resolution stays here, at enqueue time, while
     * the pre-update ancestor chain is still visible; only the build and the publish move. The id
     * is therefore stamped at flush time, in commit order, which is what keeps the `Last-Event-ID`
     * replay contract intact. See [PendingApiEvent].
     */
    private fun publishScoped(
        eventType: String,
        itemId: UUID,
        modifiedAt: Instant?,
        roots: Set<UUID>,
        newRole: String? = null,
    ) {
        deferredPublisher.publishOnCommit(
            PendingApiEvent(
                eventType = eventType,
                itemId = itemId,
                modifiedAt = modifiedAt,
                newRole = newRole,
                affectedRoots = roots,
            ),
        )
    }

    /** Invalidate the cache for [itemId] and all its known descendants (on delete or reparent). */
    private fun invalidateCache(itemId: UUID) {
        rootCache.remove(itemId)
        // Invalidate any item that had this item in its ancestor chain (all descendants).
        // Simple approach: remove any entries where itemId appears in their chain.
        // Since we can't easily enumerate descendants here, we take the conservative approach
        // of clearing the entire cache on structural changes (reparent/delete).
        // This is safe because the cache is just a performance optimisation; it gets repopulated lazily.
    }

    private fun clearCache() {
        rootCache.clear()
    }

    // -------------------------------------------------------------------------
    // WorkItem repository decorator
    // -------------------------------------------------------------------------

    private inner class EventPublishingWorkItemRepository(
        private val inner: WorkItemRepository,
    ) : WorkItemRepository by inner {
        override suspend fun create(item: WorkItem): Result<WorkItem> {
            val result = inner.create(item)
            if (result is Result.Success) {
                val roots = resolveRoots(result.data.id)
                publishScoped(
                    ApiEventType.ITEM_CREATED,
                    itemId = result.data.id,
                    modifiedAt = result.data.createdAt,
                    roots = roots,
                )
            }
            return result
        }

        override suspend fun update(item: WorkItem): Result<WorkItem> {
            // Performance guard: only read the pre-update row (for reparent detection) when an SSE
            // client is connected. With no subscribers this extra getById is pure overhead — we
            // still buffer a plain item.updated event below (roots resolve to emptySet()).
            val hasSubscribers = eventBus.subscriberCount() > 0
            val oldItem = if (hasSubscribers) (inner.getById(item.id) as? Result.Success)?.data else null
            // Capture the OLD root set BEFORE the update — while the DB still reflects the old parent
            // chain. After inner.update(), resolveRoots(item.id) returns the NEW roots for this same
            // itemId, so scope.left must use this pre-update snapshot (else it fires to the new root
            // and the old-root subscriber never gets scope.left). Reparent path only; cheap otherwise.
            val oldRoots = if (hasSubscribers && oldItem != null) resolveRoots(item.id) else emptySet()
            val result = inner.update(item)
            if (result is Result.Success) {
                val updated = result.data
                val oldParentId = oldItem?.parentId
                val newParentId = updated.parentId

                // Classify the update for the event model (all require the pre-update row, so only
                // when subscribers are present):
                //   - role change   → item.advanced (semantic phase transition; carries newRole)
                //   - parent change  → scope.left / scope.entered (reparent across roots)
                //   - otherwise      → item.updated
                if (hasSubscribers && oldItem != null && oldItem.role != updated.role) {
                    // Advance — distinct from item.updated; carries the new role for dashboards.
                    // Captures BOTH the MCP path (AdvanceItemTool → update) and the REST /advance route.
                    publishAdvance(updated.id, updated.role, updated.modifiedAt)
                } else if (hasSubscribers && oldItem != null && oldParentId != newParentId) {
                    // Reparent — emit scope.left for the OLD roots (pre-update snapshot), rebuild, then enter.
                    for (oldRoot in oldRoots) {
                        publishScoped(
                            ApiEventType.SCOPE_LEFT,
                            itemId = updated.id,
                            modifiedAt = updated.modifiedAt,
                            roots = setOf(oldRoot),
                        )
                    }
                    // Invalidate and recompute — reparent changes the whole subtree ancestry
                    clearCache()
                    val newRoots = resolveRoots(updated.id)
                    for (newRoot in newRoots) {
                        publishScoped(
                            ApiEventType.SCOPE_ENTERED,
                            itemId = updated.id,
                            modifiedAt = updated.modifiedAt,
                            roots = setOf(newRoot),
                        )
                    }
                } else {
                    // Normal update — roots unchanged. Resolve from current state so the buffered
                    // item.updated event is correctly scoped even when no subscriber is connected.
                    publishScoped(
                        ApiEventType.ITEM_UPDATED,
                        itemId = updated.id,
                        modifiedAt = updated.modifiedAt,
                        roots = resolveRoots(updated.id),
                    )
                }
            }
            return result
        }

        override suspend fun delete(id: UUID): Result<Boolean> {
            val roots = resolveRoots(id)
            val result = inner.delete(id)
            if (result is Result.Success && result.data) {
                invalidateCache(id)
                publishScoped(
                    ApiEventType.ITEM_DELETED,
                    itemId = id,
                    modifiedAt = Instant.now(),
                    roots = roots,
                )
            }
            return result
        }

        override suspend fun deleteAll(ids: Set<UUID>): Result<Int> {
            // Unconditional pre-read (to learn which ids actually exist, and their pre-delete
            // roots) — replay needs the event even when no SSE client is connected right now: a
            // later Last-Event-ID resume must still see these deletes (as UNRESOLVED entries, via
            // resolveRoots' own no-subscriber guard), matching item delete()/note deleteByItemId's
            // existing behaviour. The 4 guarded delete paths in this file are now uniform.
            val preDeleteItems = (inner.findByIds(ids) as? Result.Success)?.data
            val rootsByItemId =
                if (preDeleteItems != null) {
                    preDeleteItems.associate { it.id to resolveRoots(it.id) }
                } else {
                    emptyMap()
                }
            val result = inner.deleteAll(ids)
            if (result is Result.Success && result.data > 0 && preDeleteItems != null) {
                clearCache()
                for (item in preDeleteItems) {
                    publishScoped(
                        ApiEventType.ITEM_DELETED,
                        itemId = item.id,
                        modifiedAt = Instant.now(),
                        roots = rootsByItemId[item.id] ?: emptySet(),
                    )
                }
            }
            return result
        }

        override suspend fun claim(
            itemId: UUID,
            agentId: String,
            ttlSeconds: Int,
        ): ClaimResult {
            val result = inner.claim(itemId, agentId, ttlSeconds)
            if (result is ClaimResult.Success) {
                publishScoped(
                    ApiEventType.ITEM_UPDATED,
                    itemId = result.item.id,
                    modifiedAt = result.item.modifiedAt,
                    roots = resolveRoots(result.item.id),
                )
                // Every OTHER item this agent held was auto-released as part of this claim
                // (step 2 of the atomic claim SQL) — each one is a data change dashboards must see.
                for (releasedId in result.releasedItemIds) {
                    publishScoped(
                        ApiEventType.ITEM_UPDATED,
                        itemId = releasedId,
                        modifiedAt = Instant.now(),
                        roots = resolveRoots(releasedId),
                    )
                }
            }
            return result
        }

        override suspend fun release(
            itemId: UUID,
            agentId: String,
        ): ReleaseResult {
            val result = inner.release(itemId, agentId)
            if (result is ReleaseResult.Success) {
                publishScoped(
                    ApiEventType.ITEM_UPDATED,
                    itemId = result.item.id,
                    modifiedAt = result.item.modifiedAt,
                    roots = resolveRoots(result.item.id),
                )
            }
            return result
        }
    }

    // -------------------------------------------------------------------------
    // Note repository decorator
    // -------------------------------------------------------------------------

    private inner class EventPublishingNoteRepository(
        private val inner: NoteRepository,
    ) : NoteRepository by inner {
        override suspend fun upsert(note: Note): Result<Note> {
            val result = inner.upsert(note)
            if (result is Result.Success) {
                val roots = resolveRoots(note.itemId)
                publishScoped(
                    ApiEventType.NOTE_UPSERTED,
                    itemId = note.itemId,
                    modifiedAt = result.data.modifiedAt,
                    roots = roots,
                )
            }
            return result
        }

        override suspend fun delete(id: UUID): Result<Boolean> {
            // Unconditional pre-read of the note (to learn its itemId for the event payload) —
            // replay needs the event even at 0 subscribers; see deleteAll()'s comment above.
            val note = (inner.getById(id) as? Result.Success)?.data
            val result = inner.delete(id)
            if (result is Result.Success && result.data && note != null) {
                val roots = resolveRoots(note.itemId)
                publishScoped(
                    ApiEventType.NOTE_DELETED,
                    itemId = note.itemId,
                    modifiedAt = Instant.now(),
                    roots = roots,
                )
            }
            return result
        }

        override suspend fun deleteByItemId(itemId: UUID): Result<Int> {
            val result = inner.deleteByItemId(itemId)
            // The itemId is already known from the parameter (unlike single-note delete(id), no
            // pre-read is needed to learn it) — one note.deleted event when at least one note was
            // removed. N identical events (one per deleted note) would add nothing since the
            // payload carries no noteId.
            if (result is Result.Success && result.data > 0) {
                publishScoped(
                    ApiEventType.NOTE_DELETED,
                    itemId = itemId,
                    modifiedAt = Instant.now(),
                    roots = resolveRoots(itemId),
                )
            }
            return result
        }
    }

    // -------------------------------------------------------------------------
    // Dependency repository decorator
    // -------------------------------------------------------------------------

    private inner class EventPublishingDependencyRepository(
        private val inner: DependencyRepository,
    ) : DependencyRepository by inner {
        override suspend fun create(dependency: Dependency): Dependency {
            val result = inner.create(dependency)
            // Resolve the from-item's roots through the suspend resolver, which falls back to an
            // ancestor-chain query when the cache misses. A dependency written against an item
            // this server session has never otherwise touched therefore still reaches its
            // root-scoped subscribers, instead of being withheld as UNRESOLVED.
            val roots = resolveRoots(dependency.fromItemId)
            publishScoped(
                ApiEventType.DEPENDENCY_ADDED,
                itemId = dependency.fromItemId,
                modifiedAt = dependency.createdAt,
                roots = roots,
            )
            return result
        }

        override suspend fun delete(id: UUID): Boolean {
            // Unconditional pre-read of the dependency (for the event payload) — replay needs the
            // event even at 0 subscribers; see WorkItemRepository.deleteAll()'s comment above.
            val dep = inner.findById(id)
            val result = inner.delete(id)
            if (result && dep != null) {
                // Same resolution as create(): DB-backed, so a cold cache no longer withholds
                // dependency.removed from root-scoped subscribers.
                val roots = resolveRoots(dep.fromItemId)
                publishScoped(
                    ApiEventType.DEPENDENCY_REMOVED,
                    itemId = dep.fromItemId,
                    modifiedAt = Instant.now(),
                    roots = roots,
                )
            }
            return result
        }

        override suspend fun createBatch(dependencies: List<Dependency>): List<Dependency> {
            val result = inner.createBatch(dependencies)
            // Per RETURNED dep only — createBatch throws on an in-batch validation failure (e.g. a
            // duplicate), so a thrown exception here means this loop never runs and zero events
            // are published for the failed call.
            for (dep in result) {
                val roots = resolveRoots(dep.fromItemId)
                publishScoped(
                    ApiEventType.DEPENDENCY_ADDED,
                    itemId = dep.fromItemId,
                    modifiedAt = dep.createdAt,
                    roots = roots,
                )
            }
            return result
        }

        override suspend fun deleteByItemId(itemId: UUID): Int {
            // Unconditional pre-read of the edges (for their itemIds/fromItemId payload) — replay
            // needs the event even at 0 subscribers; see WorkItemRepository.deleteAll()'s comment
            // above.
            val existingEdges = inner.findByItemId(itemId)
            val count = inner.deleteByItemId(itemId)
            if (count > 0) {
                // Per pre-read edge (both directions — findByItemId matches fromItemId OR
                // toItemId), itemId = the edge's fromItemId, matching create()'s convention.
                for (edge in existingEdges) {
                    val roots = resolveRoots(edge.fromItemId)
                    publishScoped(
                        ApiEventType.DEPENDENCY_REMOVED,
                        itemId = edge.fromItemId,
                        modifiedAt = Instant.now(),
                        roots = roots,
                    )
                }
            }
            return count
        }
    }

    // -------------------------------------------------------------------------
    // WorkTreeExecutor decorator
    // -------------------------------------------------------------------------

    /**
     * Post-commit publish from the returned [WorkTreeResult] — see the class KDoc's
     * "WorkTreeExecutor decision" note. [SQLiteWorkTreeService] takes concrete SQLite types and
     * calls `internal` row-insert helpers directly, so decorating its constituent repositories is
     * not possible; instead this wraps the executor itself and publishes from what it returns.
     *
     * `CreateWorkTreeTool` calls `execute` inside `context.inTransaction`, so this runs inside that
     * same open transaction — every [publishScoped] call here is buffered by [deferredPublisher]
     * until the OUTER transaction commits, and discarded if it rolls back (including a rollback
     * triggered by [inner]'s own `execute` throwing, in which case this loop never runs at all).
     */
    private inner class EventPublishingWorkTreeExecutor(
        private val inner: WorkTreeExecutor,
    ) : WorkTreeExecutor {
        override suspend fun execute(input: WorkTreeInput): WorkTreeResult {
            val result = inner.execute(input)

            // Items are root-first (WorkTreeInput's contract) and exclude the attach-mode
            // pre-existing root (SQLiteWorkTreeService only appends newly-inserted items to
            // createdItems) — so no spurious item.created for an item this call merely attached to.
            for (item in result.items) {
                publishScoped(
                    ApiEventType.ITEM_CREATED,
                    itemId = item.id,
                    modifiedAt = item.createdAt,
                    roots = resolveRoots(item.id),
                )
            }
            for (dep in result.deps) {
                publishScoped(
                    ApiEventType.DEPENDENCY_ADDED,
                    itemId = dep.fromItemId,
                    modifiedAt = dep.createdAt,
                    roots = resolveRoots(dep.fromItemId),
                )
            }
            for (note in result.notes) {
                publishScoped(
                    ApiEventType.NOTE_UPSERTED,
                    itemId = note.itemId,
                    modifiedAt = note.modifiedAt,
                    roots = resolveRoots(note.itemId),
                )
            }

            return result
        }
    }

    // -------------------------------------------------------------------------
    // RepositoryProvider interface — delegate everything, wrap the three repos
    // -------------------------------------------------------------------------

    private val wrappedWorkItemRepo by lazy {
        EventPublishingWorkItemRepository(delegate.workItemRepository())
    }
    private val wrappedNoteRepo by lazy {
        EventPublishingNoteRepository(delegate.noteRepository())
    }
    private val wrappedDependencyRepo by lazy {
        EventPublishingDependencyRepository(delegate.dependencyRepository())
    }
    private val wrappedWorkTreeExecutor by lazy {
        EventPublishingWorkTreeExecutor(delegate.workTreeExecutor())
    }

    override fun workItemRepository(): WorkItemRepository = wrappedWorkItemRepo

    override fun noteRepository(): NoteRepository = wrappedNoteRepo

    override fun dependencyRepository(): DependencyRepository = wrappedDependencyRepo

    override fun roleTransitionRepository(): RoleTransitionRepository = delegate.roleTransitionRepository()

    override fun projectConfigRepository(): ProjectConfigRepository = delegate.projectConfigRepository()

    override fun planDocumentRepository(): PlanDocumentRepository = delegate.planDocumentRepository()

    // No event publishing for resource leases — pure pass-through decorator.
    override fun resourceLeaseRepository(): ResourceLeaseRepository = delegate.resourceLeaseRepository()

    override fun database() = delegate.database()

    override fun workTreeExecutor(): WorkTreeExecutor = wrappedWorkTreeExecutor

    // -------------------------------------------------------------------------
    // Role-transition hook (called by RoleTransitionHandler.applyTransition callers)
    // -------------------------------------------------------------------------

    /**
     * Called by code that wraps [RoleTransitionHandler.applyTransition] to emit [ApiEventType.ITEM_ADVANCED].
     *
     * The REST [TransitionRoutes] and any future callers should invoke this after a successful
     * transition. The [AdvanceItemTool] (MCP path) uses [workItemRepository.update] which is
     * already decorated — but `item.advanced` is distinct from `item.updated` because it carries
     * the [newRole] payload and semantically means "phase advanced", not "data patched".
     *
     * It is safe to call this even if no SSE subscribers are connected — [ApiEventBus.publish]
     * is a no-op when the subscriber map is empty.
     */
    suspend fun publishAdvance(
        itemId: UUID,
        newRole: Role,
        modifiedAt: Instant = Instant.now(),
    ) {
        val roots = resolveRoots(itemId)
        publishScoped(
            ApiEventType.ITEM_ADVANCED,
            itemId = itemId,
            modifiedAt = modifiedAt,
            roots = roots,
            newRole = newRole.name.lowercase(),
        )
    }
}
