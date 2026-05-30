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
 *   oldest event is dropped and a [ApiEventType.SYNC_LOST] sentinel is queued.
 *
 * @param bufferSize Number of recent events retained for `Last-Event-ID` replay (default 1000).
 * @param connectionQueueSize Per-connection bounded queue capacity (default 256).
 */
class ApiEventBus(
    private val bufferSize: Int = System.getenv("API_SSE_BUFFER_SIZE")?.toIntOrNull() ?: 1000,
    private val connectionQueueSize: Int = 256,
) {
    private val logger = LoggerFactory.getLogger(ApiEventBus::class.java)

    /** Monotonically increasing global event counter — independent of /mcp EventStore. */
    private val idCounter = AtomicLong(0L)

    /** Ring buffer of recent events for Last-Event-ID replay. Protected by synchronized access. */
    private val ringBuffer = ArrayDeque<ApiEvent>(bufferSize)

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
        // Add to ring buffer
        synchronized(ringBuffer) {
            if (ringBuffer.size >= bufferSize) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(event)
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
                val syncLost = buildEvent(ApiEventType.SYNC_LOST)
                sub.channel.trySend(syncLost)
                // Try once more for the actual event
                sub.channel.trySend(event)
            }
        }
    }

    /**
     * Build a new [ApiEvent] with the next monotonic ID.
     */
    fun buildEvent(
        eventType: String,
        itemId: UUID? = null,
        modifiedAt: Instant? = null,
        newRole: String? = null,
    ): ApiEvent =
        ApiEvent(
            id = idCounter.incrementAndGet(),
            event = eventType,
            itemId = itemId?.toString(),
            modifiedAt = modifiedAt?.toString(),
            newRole = newRole,
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
     * @param subscriberId Stable identifier for this connection (used for cleanup).
     * @param rootIds Root UUIDs to subscribe to. Empty = subscribe to all events.
     * @param lastEventId If non-null, replay all buffered events with id > [lastEventId] before
     *   streaming live events.
     */
    fun subscribe(
        subscriberId: String,
        rootIds: Set<UUID>,
        lastEventId: Long? = null,
    ): Flow<ApiEvent> {
        val channel = Channel<ApiEvent>(capacity = connectionQueueSize)
        val sub = Subscriber(id = subscriberId, rootIds = rootIds, channel = channel)
        subscribers[subscriberId] = sub

        return flow {
            try {
                // Replay buffered events from lastEventId forward, THEN stream live
                if (lastEventId != null) {
                    // Replay all buffered events after the last-seen ID.
                    // Ring-buffer entries do not carry per-publisher root metadata,
                    // so replay is unfiltered by root — clients should treat replayed
                    // events as potentially broader than their live subscription filter.
                    val buffered =
                        synchronized(ringBuffer) {
                            ringBuffer.filter { it.id > lastEventId }
                        }
                    for (evt in buffered) {
                        emit(evt)
                    }
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
     */
    fun ringBufferSnapshot(): List<ApiEvent> =
        synchronized(ringBuffer) {
            ringBuffer.toList()
        }
}
