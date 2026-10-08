package io.github.jpicklyk.mcptask.current.application.port

import java.time.Instant
import java.util.UUID

/**
 * One row of the append-only `events` table (plan section 3.7).
 *
 * [seq] is assigned by [EventStore.append] (0 until then). [data] is the event's JSON payload. [reqId],
 * [host], [sessionId], [runId] and [seat] stay null until the phases that capture them (P10, W4, W5);
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
     * Rows with `seq > afterSeq` in ascending seq order, at most [limit]. A non-null [rootIds] keeps only rows
     * whose `root_id` is in the set (an empty set matches nothing).
     */
    suspend fun readAfter(
        afterSeq: Long,
        rootIds: Set<UUID>? = null,
        limit: Int = DEFAULT_PAGE
    ): List<EventRecord>

    /** The highest committed seq, or [SEQ_FLOOR] when the table is empty. */
    suspend fun maxSeq(): Long

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
