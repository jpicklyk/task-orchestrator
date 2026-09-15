package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process pub/sub event bus for API SSE real-time events.
 *
 * ## Design
 *
 * - **Independent event-ID namespace:** The monotonic [idCounter] is separate from the `/mcp`
 *   endpoint's `EventStore`. Clients MUST NOT mix `Last-Event-ID` values across `/mcp` and
 *   `/api/v1/events`.
 * - **Per-root topic registry:** Subscribers attach to a set of root UUIDs. On publish, the bus
 *   fans out to every subscriber whose root set intersects the event's affected-root set.
 * - **Root-ancestor cache:** The bus maintains a lazy `itemId → Set<UUID>` map of root ancestors.
 *   The decorator that calls [publish] supplies the pre-computed root set (resolved from the
 *   repository provider at decorator construction time). The cache is invalidated on reparent.
 * - **Ring buffer:** The last [bufferSize] events are retained for `Last-Event-ID` replay.
 * - **Backpressure:** Each subscriber has a bounded per-connection [Channel]. When full, the
 *   oldest event is dropped and a [ApiEventType.SYNC_LOST] sentinel
 *   ([SyncLostReason.QUEUE_OVERFLOW]) is queued.
 * - **Replay-gap sentinel:** A resuming subscriber whose `Last-Event-ID` is no longer replayable
 *   receives a [ApiEventType.SYNC_LOST] event as the FIRST emission of its flow, before any
 *   replayed or live event. See [replayGapSentinel] for the gap predicate and the id contract.
 *
 * @param bufferSize Number of recent events retained for `Last-Event-ID` replay (default 1000).
 *   Values below 1 (e.g. `API_SSE_BUFFER_SIZE=0`) are coerced to 1 — a zero-capacity deque would
 *   make the first [publish] call `removeFirst()` on an empty buffer.
 * @param connectionQueueSize Per-connection bounded queue capacity (default 256).
 */
class ApiEventBus(
    private val bufferSize: Int = System.getenv("API_SSE_BUFFER_SIZE")?.toIntOrNull() ?: 1000,
    private val connectionQueueSize: Int = 256,
) {
    private val logger = LoggerFactory.getLogger(ApiEventBus::class.java)

    /** Monotonically increasing global event counter — independent of /mcp EventStore. */
    private val idCounter = AtomicLong(0L)

    /**
     * Internal wrapper that annotates a buffered event with the root UUIDs affected by it.
     * This allows the replay path to apply the same root-scope filter that the live publish() path uses.
     */
    private data class RingBufferEntry(
        val event: ApiEvent,
        /** Root UUIDs affected by this event; empty = bus-level broadcast (sync.lost, auth.expired). */
        val affectedRoots: Set<UUID>,
    )

    /**
     * Effective retention capacity. [bufferSize] is coerced to at least 1: a non-positive value
     * (`API_SSE_BUFFER_SIZE=0`, or a negative override) would otherwise make [publish] call
     * `removeFirst()` on an empty deque and throw `NoSuchElementException` on the very first event.
     */
    private val retainedBufferSize: Int = bufferSize.coerceAtLeast(1)

    /** Ring buffer of recent events for Last-Event-ID replay. Protected by synchronized access. */
    private val ringBuffer = ArrayDeque<RingBufferEntry>(retainedBufferSize)

    /** Active subscribers: subscriber-id → Subscriber. */
    private val subscribers = ConcurrentHashMap<String, Subscriber>()

    private data class Subscriber(
        val id: String,
        /** Root UUIDs this subscriber is interested in. Empty = interested in ALL roots. */
        val rootIds: Set<UUID>,
        val channel: Channel<ApiEvent>,
    )

    // -------------------------------------------------------------------------
    // Publish
    // -------------------------------------------------------------------------

    /**
     * Publish [event] to all subscribers whose root filter intersects [affectedRoots].
     *
     * This method is non-suspend and safe to call from the repository decorator (which runs
     * inside coroutine context but needs fire-and-forget delivery to subscribers).
     *
     * @param event The event to publish (must already have a valid [ApiEvent.id]).
     * @param affectedRoots The root UUIDs affected by this event (item's ancestor chain root set).
     *   Pass [emptySet] only for bus-level events ([ApiEventType.SYNC_LOST], [ApiEventType.AUTH_EXPIRED])
     *   that should not be filtered by root.
     */
    fun publish(
        event: ApiEvent,
        affectedRoots: Set<UUID> = emptySet(),
    ) {
        // Add to ring buffer — store alongside affectedRoots so replay can apply scope filtering
        synchronized(ringBuffer) {
            if (ringBuffer.isNotEmpty() && ringBuffer.size >= retainedBufferSize) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(RingBufferEntry(event, affectedRoots))
        }

        // Fan out to subscribers
        for (sub in subscribers.values) {
            val interested =
                sub.rootIds.isEmpty() ||
                    // no filter → all events
                    affectedRoots.isEmpty() ||
                    // bus-level event → all subscribers
                    sub.rootIds.intersect(affectedRoots).isNotEmpty()

            if (!interested) continue

            val sent = sub.channel.trySend(event)
            if (sent.isFailure) {
                // Queue full — drop oldest by draining one and sending sync.lost + event
                logger.debug("Subscriber {} queue full, dropping oldest event", sub.id)
                sub.channel.tryReceive() // drain one slot
                val syncLost = buildEvent(ApiEventType.SYNC_LOST, reason = SyncLostReason.QUEUE_OVERFLOW)
                sub.channel.trySend(syncLost)
                // Try once more for the actual event
                sub.channel.trySend(event)
            }
        }
    }

    /**
     * Build a new [ApiEvent] with the next monotonic ID.
     *
     * @param reason Cause code for [ApiEventType.SYNC_LOST] events ([SyncLostReason]); null for
     *   every other event type.
     */
    fun buildEvent(
        eventType: String,
        itemId: UUID? = null,
        modifiedAt: Instant? = null,
        newRole: String? = null,
        reason: String? = null,
    ): ApiEvent =
        ApiEvent(
            id = idCounter.incrementAndGet(),
            event = eventType,
            itemId = itemId?.toString(),
            modifiedAt = modifiedAt?.toString(),
            newRole = newRole,
            reason = reason,
        )

    // -------------------------------------------------------------------------
    // Subscribe
    // -------------------------------------------------------------------------

    /**
     * Subscribe to events for the given [rootIds].
     *
     * Returns a [Flow] that emits events until the subscriber is removed (via [unsubscribe]).
     * The caller must call [unsubscribe] in a `finally` block when the SSE connection closes.
     *
     * If the resume cursor is no longer replayable, a [ApiEventType.SYNC_LOST] sentinel is emitted
     * FIRST — before any replayed or live event — so the client learns about the gap instead of
     * seeing a stream that merely looks contiguous. See [replayGapSentinel].
     *
     * @param subscriberId Stable identifier for this connection (used for cleanup).
     * @param rootIds Root UUIDs to subscribe to. Empty = subscribe to all events.
     * @param lastEventId If non-null, replay all buffered events with id > [lastEventId] before
     *   streaming live events.
     * @param resumeRequested Whether the client actually asked to resume. Defaults to
     *   `lastEventId != null`. Transports pass `true` with a null [lastEventId] when a resume
     *   cursor was present but unparsable (e.g. `Last-Event-ID: abc`), so the gap is reported as
     *   [SyncLostReason.UNKNOWN_EVENT_ID] rather than silently treated as a fresh connection.
     */
    fun subscribe(
        subscriberId: String,
        rootIds: Set<UUID>,
        lastEventId: Long? = null,
        resumeRequested: Boolean = lastEventId != null,
    ): Flow<ApiEvent> {
        val channel = Channel<ApiEvent>(capacity = connectionQueueSize)
        val sub = Subscriber(id = subscriberId, rootIds = rootIds, channel = channel)
        subscribers[subscriberId] = sub

        return flow {
            try {
                // Gap detection and the replay snapshot are taken under ONE lock acquisition
                // (the monitor is reentrant, so replayGapSentinel's own synchronized block
                // nests safely) so a concurrent publish cannot evict between the two and turn a
                // detected-gap-free resume into a silent loss.
                val (sentinel, buffered) =
                    synchronized(ringBuffer) {
                        val gap = replayGapSentinel(lastEventId, resumeRequested)
                        // An unparsable cursor has no numeric value to replay from; resume at the
                        // sentinel's id, which by construction replays the whole retained buffer.
                        val replayFrom = lastEventId ?: gap?.id?.takeIf { resumeRequested }
                        val entries =
                            if (replayFrom == null) {
                                emptyList()
                            } else {
                                // Apply the same root-scope filter as the live publish() fan-out so
                                // that a subscriber scoped to a subset of roots does not receive
                                // buffered events for roots outside its scope.
                                ringBuffer.filter { entry ->
                                    entry.event.id > replayFrom &&
                                        (
                                            sub.rootIds.isEmpty() ||
                                                // subscriber has no root filter
                                                entry.affectedRoots.isEmpty() ||
                                                // bus-level event (sync.lost, auth.expired)
                                                sub.rootIds.intersect(entry.affectedRoots).isNotEmpty()
                                        )
                                }
                            }
                        gap to entries
                    }

                // The sentinel precedes replay: the client must know the stream has a hole before
                // it starts applying the events on the far side of it.
                if (sentinel != null) {
                    logger.debug(
                        "Subscriber {} resumed with unreplayable Last-Event-ID {} (reason={})",
                        subscriberId,
                        lastEventId,
                        sentinel.reason,
                    )
                    emit(sentinel)
                }

                for (entry in buffered) {
                    emit(entry.event)
                }

                // Stream live events from the channel
                for (evt in channel) {
                    emit(evt)
                }
            } finally {
                unsubscribe(subscriberId)
            }
        }
    }

    /**
     * Returns the [ApiEventType.SYNC_LOST] sentinel a resuming subscriber must be sent before its
     * replay, or null when the resume cursor is fully replayable (or no resume was requested).
     *
     * ## Gap predicate
     * - [resumeRequested] false → null. A fresh connection has no history to miss.
     * - [lastEventId] null while [resumeRequested] is true (unparsable cursor) →
     *   [SyncLostReason.UNKNOWN_EVENT_ID].
     * - [lastEventId] greater than the current high-water mark → [SyncLostReason.UNKNOWN_EVENT_ID].
     *   This is the server-restart case: [idCounter] restarts at 0, so a pre-restart cursor would
     *   otherwise match nothing and go silent until live ids climbed past it.
     * - [lastEventId] older than `oldestRetained.id - 1` (or, with an empty buffer, below the
     *   high-water mark) → [SyncLostReason.BUFFER_EVICTED].
     *
     * ## Global buffer, deliberately
     * Detection reads the GLOBAL ring buffer, not the caller's root-scoped view. A root-scoped
     * subscriber whose own events all survived can therefore receive a sentinel because some
     * OTHER root's events were evicted — a false positive that costs the client one unnecessary
     * re-fetch. The scoped alternative would produce false NEGATIVES (silent loss), which is the
     * bug this exists to prevent. The asymmetry is intentional.
     *
     * ## Id contract
     * `sentinel.id = oldestRetained.id - 1`, or the current high-water mark when the buffer is
     * empty — always BELOW every event that follows it on the connection. SSE clients set their
     * reconnect cursor from each `id:` field, so a sentinel numbered from [idCounter] would let a
     * disconnect right after it skip the entire replayed tail. Reconnecting at the sentinel's own
     * id yields no sentinel plus the full retained replay, so the client reconverges.
     *
     * The sentinel is never published: it does not enter the ring buffer, does not consume an id
     * from [idCounter], and reaches no other subscriber.
     */
    internal fun replayGapSentinel(
        lastEventId: Long?,
        resumeRequested: Boolean,
    ): ApiEvent? {
        if (!resumeRequested) return null
        return synchronized(ringBuffer) {
            val oldestRetainedId = ringBuffer.firstOrNull()?.event?.id
            val highWater = idCounter.get()
            val sentinelId = oldestRetainedId?.minus(1) ?: highWater
            val reason =
                when {
                    lastEventId == null -> SyncLostReason.UNKNOWN_EVENT_ID
                    lastEventId > highWater -> SyncLostReason.UNKNOWN_EVENT_ID
                    oldestRetainedId != null && lastEventId < oldestRetainedId - 1 ->
                        SyncLostReason.BUFFER_EVICTED
                    oldestRetainedId == null && lastEventId < highWater -> SyncLostReason.BUFFER_EVICTED
                    else -> null
                } ?: return@synchronized null
            ApiEvent(
                id = sentinelId,
                event = ApiEventType.SYNC_LOST,
                reason = reason,
            )
        }
    }

    /**
     * Remove the subscriber with [subscriberId] and close its channel.
     * Safe to call multiple times (idempotent).
     */
    fun unsubscribe(subscriberId: String) {
        val sub = subscribers.remove(subscriberId)
        sub?.channel?.close()
    }

    /**
     * Returns the current number of active subscribers (for testing/monitoring).
     */
    fun subscriberCount(): Int = subscribers.size

    /**
     * Returns a snapshot of the ring buffer (for testing/replay verification).
     * Returns [ApiEvent] objects only — affectedRoots metadata is internal.
     */
    fun ringBufferSnapshot(): List<ApiEvent> =
        synchronized(ringBuffer) {
            ringBuffer.map { it.event }
        }
}
