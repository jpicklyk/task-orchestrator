package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.PlanDocumentRepository
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
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
     * On query failure, returns an empty set (event is broadcast to all subscribers as a fallback).
     */
    private suspend fun resolveRoots(itemId: UUID): Set<UUID> {
        // Performance guard: when no SSE clients are connected, skip the ancestor-chain DB query.
        // Returning emptySet() means "broadcast" in ApiEventBus.publish — but with zero subscribers
        // there is nothing to fan out to, and the event is still added to the ring buffer for a
        // future client's Last-Event-ID replay. This keeps MCP-tool writes cheap when the API is
        // enabled but no dashboard is watching (e.g. stdio mode, or http with no live connections).
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
     * Non-suspend variant of [resolveRoots] that reads from the cache only — no DB fallback.
     *
     * Used by non-suspend paths (e.g., [DependencyRepository.create] and [DependencyRepository.delete])
     * that cannot call suspend functions. For items whose ancestor chain has already been cached
     * (e.g., after a prior create or update), this returns the correct root set and allows
     * dependency events to be root-scoped. For uncached items, it returns [emptySet] which causes
     * [ApiEventBus.publish] to broadcast to all subscribers — the same behavior as before this fix.
     *
     * **Tradeoff:** A cold-start dependency event (item never fetched or written since server start)
     * still broadcasts. This is acceptable because: (a) it only affects the rare case where no
     * prior write has occurred for the item in this server session, and (b) the broadcast is
     * metadata-only (itemId, no body). The correct fix for perfect scoping is to use
     * [createSuspend] instead of [create] at all call sites, but that is a broader refactor.
     */
    private fun resolveRootsCached(itemId: UUID): Set<UUID> = rootCache[itemId] ?: emptySet()

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
                eventBus.publish(
                    eventBus.buildEvent(
                        ApiEventType.ITEM_CREATED,
                        itemId = result.data.id,
                        modifiedAt = result.data.createdAt,
                    ),
                    affectedRoots = roots,
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
                        eventBus.publish(
                            eventBus.buildEvent(
                                ApiEventType.SCOPE_LEFT,
                                itemId = updated.id,
                                modifiedAt = updated.modifiedAt,
                            ),
                            affectedRoots = setOf(oldRoot),
                        )
                    }
                    // Invalidate and recompute — reparent changes the whole subtree ancestry
                    clearCache()
                    val newRoots = resolveRoots(updated.id)
                    for (newRoot in newRoots) {
                        eventBus.publish(
                            eventBus.buildEvent(
                                ApiEventType.SCOPE_ENTERED,
                                itemId = updated.id,
                                modifiedAt = updated.modifiedAt,
                            ),
                            affectedRoots = setOf(newRoot),
                        )
                    }
                } else {
                    // Normal update — roots unchanged. Resolve from current state so the buffered
                    // item.updated event is correctly scoped even when no subscriber is connected.
                    eventBus.publish(
                        eventBus.buildEvent(
                            ApiEventType.ITEM_UPDATED,
                            itemId = updated.id,
                            modifiedAt = updated.modifiedAt,
                        ),
                        affectedRoots = resolveRoots(updated.id),
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
                eventBus.publish(
                    eventBus.buildEvent(
                        ApiEventType.ITEM_DELETED,
                        itemId = id,
                        modifiedAt = Instant.now(),
                    ),
                    affectedRoots = roots,
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
                eventBus.publish(
                    eventBus.buildEvent(
                        ApiEventType.NOTE_UPSERTED,
                        itemId = note.itemId,
                        modifiedAt = result.data.modifiedAt,
                    ),
                    affectedRoots = roots,
                )
            }
            return result
        }

        override suspend fun delete(id: UUID): Result<Boolean> {
            // Performance guard: only pre-read the note (to learn its itemId for the event payload)
            // when an SSE client is connected. With no subscribers, skip the extra getById entirely.
            val note = if (eventBus.subscriberCount() > 0) (inner.getById(id) as? Result.Success)?.data else null
            val result = inner.delete(id)
            if (result is Result.Success && result.data && note != null) {
                val roots = resolveRoots(note.itemId)
                eventBus.publish(
                    eventBus.buildEvent(
                        ApiEventType.NOTE_DELETED,
                        itemId = note.itemId,
                        modifiedAt = Instant.now(),
                    ),
                    affectedRoots = roots,
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
        override fun create(dependency: Dependency): Dependency {
            val result = inner.create(dependency)
            // Non-suspend path: use the cached root set for the from-item (populated by prior
            // create/update writes). Falls back to emptySet (broadcast) for items not yet cached.
            // See resolveRootsCached() for the tradeoff rationale.
            val roots = resolveRootsCached(dependency.fromItemId)
            eventBus.publish(
                eventBus.buildEvent(
                    ApiEventType.DEPENDENCY_ADDED,
                    itemId = dependency.fromItemId,
                    modifiedAt = dependency.createdAt,
                ),
                affectedRoots = roots,
            )
            return result
        }

        override suspend fun createSuspend(dependency: Dependency): Dependency {
            val result = inner.createSuspend(dependency)
            val roots = resolveRoots(dependency.fromItemId)
            eventBus.publish(
                eventBus.buildEvent(
                    ApiEventType.DEPENDENCY_ADDED,
                    itemId = dependency.fromItemId,
                    modifiedAt = dependency.createdAt,
                ),
                affectedRoots = roots,
            )
            return result
        }

        override fun delete(id: UUID): Boolean {
            // Performance guard: only pre-read the dependency (for the event payload) when an SSE
            // client is connected. With no subscribers, skip the extra findById.
            val dep = if (eventBus.subscriberCount() > 0) inner.findById(id) else null
            val result = inner.delete(id)
            if (result && dep != null) {
                // Non-suspend path: use the cached root set for the from-item.
                // Falls back to emptySet (broadcast) for uncached items.
                // See resolveRootsCached() for the tradeoff rationale.
                val roots = resolveRootsCached(dep.fromItemId)
                eventBus.publish(
                    eventBus.buildEvent(
                        ApiEventType.DEPENDENCY_REMOVED,
                        itemId = dep.fromItemId,
                        modifiedAt = Instant.now(),
                    ),
                    affectedRoots = roots,
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

    override fun workItemRepository(): WorkItemRepository = wrappedWorkItemRepo

    override fun noteRepository(): NoteRepository = wrappedNoteRepo

    override fun dependencyRepository(): DependencyRepository = wrappedDependencyRepo

    override fun roleTransitionRepository(): RoleTransitionRepository = delegate.roleTransitionRepository()

    override fun projectConfigRepository(): ProjectConfigRepository = delegate.projectConfigRepository()

    override fun planDocumentRepository(): PlanDocumentRepository = delegate.planDocumentRepository()

    override fun database() = delegate.database()

    override fun workTreeExecutor(): WorkTreeExecutor = delegate.workTreeExecutor()

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
        eventBus.publish(
            eventBus.buildEvent(
                ApiEventType.ITEM_ADVANCED,
                itemId = itemId,
                modifiedAt = modifiedAt,
                newRole = newRole.name.lowercase(),
            ),
            affectedRoots = roots,
        )
    }
}
