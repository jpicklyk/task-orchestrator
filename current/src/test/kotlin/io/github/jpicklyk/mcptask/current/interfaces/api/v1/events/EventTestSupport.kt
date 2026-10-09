package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.EventRecorder
import io.github.jpicklyk.mcptask.current.application.service.ItemPatchCommand
import io.github.jpicklyk.mcptask.current.application.service.ParentChange
import io.github.jpicklyk.mcptask.current.application.service.withEventActor
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DeleteCause
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.event.ReparentSide
import io.github.jpicklyk.mcptask.current.domain.event.TransitionOrigin
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.SqliteUnitOfWork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.UUID

/**
 * Deterministic replacement for the fixed-`delay` / bounded-window event-collection pattern used
 * across the event test suites (item 646b12a6, O5) — item.
 *
 * ## Why a fixed wait is unnecessary
 *
 * [ApiEventBus.subscribe] registers the subscriber and its bounded [kotlinx.coroutines.channels.Channel]
 * synchronously, at call time, not when the returned [Flow] starts collecting. The recorder signals
 * [ApiEventBus.committed] synchronously, before it returns, when no unit is open; inside a unit it
 * signals at `afterCommit` (or never, on rollback) — and either way this happens before the unit's
 * `write` call returns. So by the time a write under test has returned, every event it produced has
 * already reached the subscriber's channel. There is nothing left to wait for.
 *
 * ## Usage
 *
 * `subscribe` → perform the write(s) under test → [drainDelivered] → assert on the returned list.
 * [drainDelivered] unsubscribes first (closing the channel) and then drains whatever is already
 * queued — [kotlinx.coroutines.channels.Channel.close] still delivers previously buffered elements,
 * so nothing already sent is lost, and nothing MORE can arrive after the channel is closed. The
 * [withTimeout] is a hang guard only, never a wait window: a correctly-implemented publish path
 * returns from `toList()` immediately once the channel drains and closes.
 *
 * ## Risk if publishing ever becomes asynchronous
 *
 * This helper (and the exact-count assertions built on it) are correct only while publishing
 * stays synchronous. If a future change made the commit signal schedule work asynchronously
 * instead, this helper would UNDER-count rather than
 * hang — it would drain and close before the async publish completed. The exact-count assertions in
 * the tests that use this helper are what catches that: a missing event fails the count assertion
 * rather than passing silently.
 */
internal suspend fun ApiEventBus.drainDelivered(
    subscriberId: String,
    flow: Flow<ApiEvent>,
): List<ApiEvent> {
    unsubscribe(subscriberId)
    return withTimeout(5_000) { flow.toList() }
}

/**
 * The full `ServerComposition` wiring with the API on, over [delegate]: ONE [EventRecorder] over [delegate]'s event
 * store whose commit listener is [bus], and a [SqliteUnitOfWork] over [databaseManager] that hands its scopes that
 * recorder. Every write service records its rows through `WriteScope.events`, so they reach [bus] only through this
 * shared recorder. Returns [delegate] (no store is decorated) and the unit of work.
 */
internal fun eventWiredUnit(
    databaseManager: DatabaseManager,
    delegate: RepositoryProvider,
    bus: ApiEventBus,
    clock: Clock = Clock.SYSTEM,
): Pair<RepositoryProvider, UnitOfWork> {
    val store = LazyEventStore { delegate.eventStore() }
    if (bus.source == null) bus.source = store
    val recorder = EventRecorder(store, clock, bus)
    return delegate to SqliteUnitOfWork(databaseManager, delegate, clock, recorder)
}

/**
 * A [ToolExecutionContext] over [delegate] whose unit of work is [eventWiredUnit]'s: the write services it exposes
 * (`itemCommandService`, `noteCommandService`, `dependencyCommandService`, `claimService`) record their rows through
 * the one recorder that feeds [bus]. Tests that drove a decorated store directly drive these services instead.
 */
internal fun eventWiredContext(
    databaseManager: DatabaseManager,
    delegate: RepositoryProvider,
    bus: ApiEventBus,
    clock: Clock = Clock.SYSTEM,
): ToolExecutionContext {
    val (provider, unitOfWork) = eventWiredUnit(databaseManager, delegate, bus, clock)
    return ToolExecutionContext(provider, clock = clock, unitOfWork = unitOfWork)
}

/** A patch of [item] keeping every field but the ones given: [parent] (default keep) and [title]. */
internal fun patchOf(
    item: WorkItem,
    parent: ParentChange = ParentChange.Keep,
    title: String = item.title,
): ItemPatchCommand =
    ItemPatchCommand(
        itemId = item.id,
        expectedVersion = null,
        parent = parent,
        title = title,
        description = item.description,
        summary = item.summary,
        statusLabel = item.statusLabel,
        priority = item.priority,
        complexity = item.complexity,
        requiresVerification = item.requiresVerification,
        metadata = item.metadata,
        tags = item.tags,
        type = item.type,
        properties = item.properties,
    )

/** The value of a write-service [Outcome], failing the test on a rejection. */
internal fun <T> Outcome<T>.orFail(): T =
    when (this) {
        is Outcome.Ok -> value
        is Outcome.Err -> throw AssertionError("write rejected: ${error.code} ${error.message}")
    }

/**
 * The production projection wiring: [bus] projects [store], and [emit] records one row
 * through an [EventRecorder] whose commit listener feeds [bus], exactly as a committed write would. Use it where a
 * test used to inject events with `bus.publish(bus.buildEvent(...))`: a row is both streamed live and replayable.
 */
internal class EventFeed(
    val store: EventStore,
    val bus: ApiEventBus = ApiEventBus(source = store),
) {
    init {
        if (bus.source == null) bus.source = store
    }

    val recorder = EventRecorder(store, listener = bus)

    /**
     * Records one row that projects as [type] (a 3.x [ApiEventType] name) for [itemId] under [rootId], and returns
     * its projection.
     */
    suspend fun emit(
        type: String = ApiEventType.ITEM_UPDATED,
        itemId: UUID = UUID.randomUUID(),
        rootId: UUID = itemId,
        actor: ActorClaim? = null,
        at: Instant? = null,
    ): ApiEvent {
        val event: DomainEvent =
            when (type) {
                ApiEventType.ITEM_CREATED -> DomainEvent.ItemCreated(itemId, rootId, null)
                ApiEventType.ITEM_UPDATED -> DomainEvent.ItemUpdated(itemId, rootId, listOf("title"))
                ApiEventType.ITEM_DELETED -> DomainEvent.ItemDeleted(itemId, rootId)
                ApiEventType.ITEM_ADVANCED ->
                    DomainEvent.ItemTransitioned(itemId, rootId, "start", "queue", "work", null, null, TransitionOrigin.USER)
                ApiEventType.NOTE_UPSERTED -> DomainEvent.NoteUpserted(UUID.randomUUID(), rootId, itemId, "k", "queue", 1)
                ApiEventType.NOTE_DELETED ->
                    DomainEvent.NoteDeleted(UUID.randomUUID(), rootId, itemId, "k", "queue", DeleteCause.EXPLICIT)
                ApiEventType.DEPENDENCY_ADDED ->
                    DomainEvent.DependencyAdded(UUID.randomUUID(), rootId, itemId, UUID.randomUUID(), "blocks", null)
                ApiEventType.DEPENDENCY_REMOVED ->
                    DomainEvent.DependencyRemoved(
                        UUID.randomUUID(),
                        rootId,
                        itemId,
                        UUID.randomUUID(),
                        "blocks",
                        null,
                        DeleteCause.EXPLICIT
                    )
                ApiEventType.SCOPE_LEFT -> DomainEvent.ItemReparented(itemId, rootId, ReparentSide.LEFT, null, null)
                ApiEventType.SCOPE_ENTERED -> DomainEvent.ItemReparented(itemId, rootId, ReparentSide.ENTERED, null, null)
                else -> error("EventFeed.emit: $type is not a projected data event type")
            }
        val rec = if (at == null) recorder else EventRecorder(store, Clock { at }, bus)
        val record = withEventActor(actor) { rec.record(listOf(event)).single() }
        return ApiEventBus.project(record) ?: error("row of $type did not project")
    }
}

/**
 * Records one row that projects as [type] for [itemId] under [rootId] into this bus's source, streams it to live
 * subscribers and returns its projection: the table-backed replacement for `publish(buildEvent(...))`.
 */
internal suspend fun ApiEventBus.emit(
    type: String,
    itemId: UUID = UUID.randomUUID(),
    rootId: UUID = itemId,
    actor: ActorClaim? = null,
    at: Instant? = null,
): ApiEvent = EventFeed(source ?: error("ApiEventBus.emit needs a bus with a source"), this).emit(type, itemId, rootId, actor, at)

/**
 * A `Last-Event-ID` that replays the whole log of a fresh database: the seq floor, below every seq (P8 F2). Tests
 * that used `"0"` to mean "replay everything" use this instead; `"0"` is now an old ring-buffer id
 * (sync.lost unknown_event_id).
 */
internal val FROM_START: String = EventStore.SEQ_FLOOR.toString()

/** An [EventStore] resolved on first use. */
internal class LazyEventStore(
    resolve: () -> EventStore,
) : EventStore {
    private val store by lazy(resolve)

    override suspend fun append(records: List<EventRecord>): List<EventRecord> = store.append(records)

    override suspend fun readAfter(
        afterSeq: Long,
        rootIds: Set<UUID>?,
        limit: Int,
    ): List<EventRecord> = store.readAfter(afterSeq, rootIds, limit)

    override suspend fun latestOfType(
        type: String,
        entityIds: Set<UUID>,
    ): Map<UUID, EventRecord> = store.latestOfType(type, entityIds)

    override suspend fun maxSeq(): Long = store.maxSeq()
}
