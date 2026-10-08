package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.EventRecorder
import io.github.jpicklyk.mcptask.current.application.service.withEventActor
import io.github.jpicklyk.mcptask.current.domain.event.DeleteCause
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.event.ReparentSide
import io.github.jpicklyk.mcptask.current.domain.event.TransitionOrigin
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
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
 * synchronously, at call time, not when the returned [Flow] starts collecting. [DeferredEventPublisher.publishOnCommit]
 * publishes synchronously, before it returns, when no transaction is open; inside a transaction it
 * publishes at `afterCommit` (or discards on rollback) — and either way this happens before the
 * `inTransaction` call returns (see [DeferredEventPublisher]'s KDoc). So by the time a write under
 * test has returned, every event it produced has already reached the subscriber's channel. There is
 * nothing left to wait for.
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
 * stays synchronous, per [DeferredEventPublisher]'s contract. If a future change made
 * `publishOnCommit` schedule work asynchronously instead, this helper would UNDER-count rather than
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
 * Wires the production decorator over [delegate] the way `ServerComposition` does with the API on: one
 * [EventRecorder] over [delegate]'s event store whose commit listener ([DeferredEventPublisher]) feeds [bus], and
 * [bus] projecting that same store. Construction only: tests keep calling `EventPublishingRepositoryProvider(delegate,
 * bus)` as they did when the decorator published to the bus directly.
 */
@Suppress("ktlint:standard:function-naming")
internal fun EventPublishingRepositoryProvider(
    delegate: RepositoryProvider,
    bus: ApiEventBus,
): EventPublishingRepositoryProvider {
    // Resolved on first use, so a mocked delegate that never records needs no event-store stub.
    val store = LazyEventStore { delegate.eventStore() }
    if (bus.source == null) bus.source = store
    return EventPublishingRepositoryProvider(delegate, EventRecorder(store, listener = DeferredEventPublisher(bus)))
}

/**
 * The production projection wiring without the decorator: [bus] projects [store], and [emit] records one row
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

    val recorder = EventRecorder(store, listener = DeferredEventPublisher(bus))

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
        val rec = if (at == null) recorder else EventRecorder(store, Clock { at }, DeferredEventPublisher(bus))
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

    override suspend fun maxSeq(): Long = store.maxSeq()
}
