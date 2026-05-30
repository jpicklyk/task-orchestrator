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
 */
@Serializable
data class ApiEvent(
    val id: Long,
    val event: String,
    val itemId: String? = null,
    val modifiedAt: String? = null,
    val newRole: String? = null,
)

/**
 * All event-type string constants emitted by [ApiEventBus].
 *
 * - `ITEM_CREATED`, `ITEM_UPDATED`, `ITEM_DELETED` — work-item CRUD via [WorkItemRepository].
 * - `NOTE_UPSERTED`, `NOTE_DELETED` — note writes via [NoteRepository].
 * - `DEPENDENCY_ADDED`, `DEPENDENCY_REMOVED` — dependency changes via [DependencyRepository].
 * - `ITEM_ADVANCED` — role transition via [RoleTransitionHandler]; payload carries [ApiEvent.newRole].
 * - `SCOPE_ENTERED` — item moved INTO this root's subtree (reparent or creation).
 * - `SCOPE_LEFT` — item moved OUT OF this root's subtree (reparent).
 * - `SYNC_LOST` — client fell behind; its per-connection queue overflowed. Client should
 *   re-fetch full state.
 * - `AUTH_EXPIRED` — the connection's bearer token (or JWT) has expired. Client must reconnect
 *   with a fresh credential.
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
}
