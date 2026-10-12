package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.application.api.PageRequest
import java.time.Instant
import java.util.UUID

/**
 * One row of the append-only `events` table (plan section 3.7).
 *
 * [seq] is assigned by [EventStore.append] (0 until then). [data] is the event's JSON payload. [reqId],
 * [host] and [sessionId] come from the ambient call telemetry (null outside a transport call); [runId] and [seat]
 * stay null until the phases that capture them (W4, W5);
 * the principal and proof columns are null for actorless writes.
 */
data class EventRecord(
    val seq: Long = 0,
    val id: UUID,
    val occurredAt: Instant,
    val rootId: UUID,
    val entityKind: String,
    val entityId: UUID,
    val type: String,
    val reqId: String? = null,
    val principalId: String? = null,
    val principalKind: String? = null,
    val proofStatus: String? = null,
    val host: String? = null,
    val sessionId: String? = null,
    val runId: UUID? = null,
    val seat: String? = null,
    val data: String
)

/** One page of [EventRecord]s read newest first, with the exact count of all rows matching the read. */
data class EventPage(
    val items: List<EventRecord>,
    val total: Int
)

/**
 * The append-only domain-event log.
 *
 * [append] is a write: it runs inside the caller's unit of work (production policy rejects outside-unit
 * writes), so the rows commit or roll back with the change they describe, and seq order is commit order
 * because one writer transaction runs at a time. There is no update or delete path. A database failure is
 * thrown, like every other store.
 */
interface EventStore {
    /** Appends [records] in order and returns them with their assigned [EventRecord.seq]. */
    suspend fun append(records: List<EventRecord>): List<EventRecord>

    /**
     * Rows with `seq > max(afterSeq, SEQ_FLOOR)` in ascending seq order, at most [limit]. A non-null [rootIds] keeps
     * only rows whose `root_id` is in the set (an empty set matches nothing).
     *
     * The floor clamp is part of the contract: rows backfilled from the retired `role_transitions` table sit BELOW
     * [SEQ_FLOOR] (seq 1..N) and are history, never stream events, so a live read never returns them.
     */
    suspend fun readAfter(
        afterSeq: Long,
        rootIds: Set<UUID>? = null,
        limit: Int = DEFAULT_PAGE
    ): List<EventRecord>

    /**
     * For each id of [entityIds], the row of [type] with the highest seq, if any. Ids with no such row are absent.
     * Used to deduplicate `claim.expired` rows (one per lapsed claim instance).
     */
    suspend fun latestOfType(
        type: String,
        entityIds: Set<UUID>
    ): Map<UUID, EventRecord>

    /**
     * The highest committed seq, never below [SEQ_FLOOR]: an empty table, or one holding only backfilled sub-floor
     * rows, answers [SEQ_FLOOR] (otherwise every stream resume would be `sync.lost`).
     */
    suspend fun maxSeq(): Long

    /**
     * The rows about [entityId] whose type is in [types], newest first (seq descending), one page of [page] with the
     * exact total. [since] and [until] bound `occurred_at` inclusively. History reads are NOT floored: backfilled
     * sub-floor rows are included.
     */
    suspend fun findByEntity(
        entityId: UUID,
        types: Set<String>,
        page: PageRequest,
        since: Instant? = null,
        until: Instant? = null
    ): EventPage

    /**
     * The rows whose type is in [types], newest first (seq descending), one page of [page] with the exact total
     * (counted in the same read unit). [since] and [until] bound `occurred_at` inclusively. [scope] restricts rows:
     * `rootIds` keeps rows whose `root_id` is in the set; `tagsInclude` keeps rows whose entity item carries an allowed
     * tag, so a row whose item is gone is invisible to a tag-scoped caller but visible to a root-scoped or
     * unrestricted one. Not floored (see [findByEntity]).
     */
    suspend fun findByTypes(
        types: Set<String>,
        since: Instant?,
        until: Instant?,
        scope: AccessScope,
        page: PageRequest
    ): EventPage

    companion object {
        /**
         * The value the migration seeds the seq counter with: every seq is above it, and every id the pre-table
         * in-memory ring buffer issued is at or below it.
         */
        const val SEQ_FLOOR: Long = 1_000_000_000_000L

        /** Default page size for [readAfter]. */
        const val DEFAULT_PAGE: Int = 500
    }
}
