package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import kotlinx.serialization.Serializable

/**
 * A single real-time event emitted over the SSE stream at `GET /api/v1/events`.
 *
 * **Event-ID namespace:** This ID counter is INDEPENDENT from the `/mcp` SSE channel managed by
 * `mcpStreamableHttp`'s `EventStore`. Clients MUST NOT reuse `Last-Event-ID` values across the
 * two channels — the two namespaces have no relation.
 *
 * **Payload contract:** Minimal by design. Dashboards should re-fetch the full item details
 * via the read API when they need field-level data. The payload keeps the bus lightweight.
 *
 * @param id Monotonically-increasing event sequence number, scoped to this [ApiEventBus] instance.
 * @param event The event type string (e.g. `item.created`, `item.advanced`, `auth.expired`).
 * @param itemId The primary work-item UUID affected, or null for bus-level events (`sync.lost`,
 *   `auth.expired`).
 * @param modifiedAt ISO-8601 string of when the write occurred, or null for bus-level events.
 * @param newRole For `item.advanced`, the new role string (`queue`, `work`, `review`, `terminal`,
 *   `blocked`). Null for all other event types.
 * @param reason Machine-readable cause code. Currently set only on [ApiEventType.SYNC_LOST]
 *   events — one of [SyncLostReason]. Null (and, because the SSE encoder runs with
 *   `explicitNulls = false`, absent from the JSON) for every other event type.
 */
@Serializable
data class ApiEvent(
    val id: Long,
    val event: String,
    val itemId: String? = null,
    val modifiedAt: String? = null,
    val newRole: String? = null,
    val reason: String? = null,
)

/**
 * Cause codes carried in [ApiEvent.reason] on a [ApiEventType.SYNC_LOST] event.
 *
 * - [QUEUE_OVERFLOW] — the client fell behind and its per-connection queue overflowed; events
 *   were dropped mid-stream.
 * - [BUFFER_EVICTED] — the client resumed with a `Last-Event-ID` older than the oldest event
 *   still retained in the bus ring buffer; the events in between can no longer be replayed.
 * - [UNKNOWN_EVENT_ID] — the client resumed with a `Last-Event-ID` this bus never issued: either
 *   greater than the current high-water mark (typically a cursor from before a server restart,
 *   since the counter restarts at 0) or unparsable as a number.
 *
 * In all three cases the client must re-fetch full state via the read API; the gap is not
 * recoverable from the stream.
 */
object SyncLostReason {
    const val QUEUE_OVERFLOW = "queue_overflow"
    const val BUFFER_EVICTED = "buffer_evicted"
    const val UNKNOWN_EVENT_ID = "unknown_event_id"
}

/**
 * All event-type string constants emitted by [ApiEventBus].
 *
 * - `ITEM_CREATED`, `ITEM_UPDATED`, `ITEM_DELETED` — work-item CRUD via [WorkItemRepository].
 * - `NOTE_UPSERTED`, `NOTE_DELETED` — note writes via [NoteRepository].
 * - `DEPENDENCY_ADDED`, `DEPENDENCY_REMOVED` — dependency changes via [DependencyRepository].
 * - `ITEM_ADVANCED` — role transition via [RoleTransitionHandler]; payload carries [ApiEvent.newRole].
 * - `SCOPE_ENTERED` — item moved INTO this root's subtree (reparent or creation).
 * - `SCOPE_LEFT` — item moved OUT OF this root's subtree (reparent).
 * - `SYNC_LOST` — the client has an unrecoverable gap: its per-connection queue overflowed, or it
 *   resumed with a `Last-Event-ID` that was evicted from / never issued by the ring buffer. The
 *   cause is carried in [ApiEvent.reason] ([SyncLostReason]). Client should re-fetch full state.
 * - `AUTH_EXPIRED` — the connection's bearer token (or JWT) has expired. Client must reconnect
 *   with a fresh credential.
 *
 * [CONTROL_EVENTS] names the subset that carries stream-control meaning rather than a data change.
 * Consumers applying an event-type filter (the `?types=` query parameter on `GET /api/v1/events`)
 * MUST NOT filter these out — a client that suppressed `sync.lost` would silently keep operating
 * on incomplete state.
 */
object ApiEventType {
    const val ITEM_CREATED = "item.created"
    const val ITEM_UPDATED = "item.updated"
    const val ITEM_DELETED = "item.deleted"
    const val NOTE_UPSERTED = "note.upserted"
    const val NOTE_DELETED = "note.deleted"
    const val DEPENDENCY_ADDED = "dependency.added"
    const val DEPENDENCY_REMOVED = "dependency.removed"
    const val ITEM_ADVANCED = "item.advanced"
    const val SCOPE_ENTERED = "scope.entered"
    const val SCOPE_LEFT = "scope.left"
    const val SYNC_LOST = "sync.lost"
    const val AUTH_EXPIRED = "auth.expired"

    /**
     * Stream-control event types, exempt from the `?types=` filter on `GET /api/v1/events`.
     *
     * These are not data-change notifications: suppressing them would leave a client believing it
     * has a contiguous stream when it does not ([SYNC_LOST]) or still authenticated when it is not
     * ([AUTH_EXPIRED]).
     */
    val CONTROL_EVENTS: Set<String> = setOf(SYNC_LOST, AUTH_EXPIRED)
}
