package io.github.jpicklyk.mcptask.current.telemetry.internal.app

import io.github.jpicklyk.mcptask.current.platform.calltelemetry.CallLogRecord
import java.time.Instant
import java.util.UUID

/**
 * Durable call log.
 *
 * [append] is a write: it runs inside a unit of work (the `CallLogSink` implementation opens one per batch). A
 * duplicate `req_id` is ignored PER ROW and never fails the batch. A database failure is thrown, like every other
 * store.
 */
interface CallLogStore {
    /** Inserts [records] and returns how many rows were actually inserted (duplicates excluded). */
    suspend fun append(records: List<CallLogRecord>): Int

    /**
     * Like [append], but also reports rows refused for a reason other than a primary-key (`req_id`) conflict.
     * Defaults to [append] with no rejections.
     */
    suspend fun appendCounted(records: List<CallLogRecord>): CallLogAppendResult {
        val inserted = append(records)
        // Every row stored only when append reports all of them; otherwise the per-row outcome is unknown.
        return CallLogAppendResult(inserted, insertedTokensByRun = if (inserted == records.size) tokensByRun(records) else emptyMap())
    }

    /**
     * Retention: deletes at most [batch] rows with `at < cutoff`, oldest first, and returns how many it deleted. A
     * write: it runs inside the caller's unit. The caller bounds [cutoff] by the `usage_daily` fold watermark. The
     * default throws; a store that serves retention overrides it.
     */
    suspend fun prune(
        cutoff: Instant,
        batch: Int
    ): Int = throw UnsupportedOperationException("This CallLogStore does not support retention pruning")
}

/**
 * Outcome of [CallLogStore.appendCounted]: rows inserted, and rows refused for a reason other than a duplicate `req_id`.
 * [insertedTokensByRun] is the positive `responseTokensEst` of the rows actually inserted, summed per non-null `runId`.
 */
data class CallLogAppendResult(
    val inserted: Int,
    val rejected: Int = 0,
    val insertedTokensByRun: Map<UUID, Long> = emptyMap()
)

/** Sums the positive `responseTokensEst` of [records] per non-null `runId`, in first-seen order. */
internal fun tokensByRun(records: List<CallLogRecord>): Map<UUID, Long> {
    val byRun = LinkedHashMap<UUID, Long>()
    for (record in records) {
        val runId = record.runId ?: continue
        val tokens = record.responseTokensEst ?: continue
        if (tokens > 0) byRun[runId] = (byRun[runId] ?: 0L) + tokens
    }
    return byRun
}
