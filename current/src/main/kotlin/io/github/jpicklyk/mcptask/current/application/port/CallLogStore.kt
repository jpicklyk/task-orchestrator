package io.github.jpicklyk.mcptask.current.application.port

import java.time.Instant
import java.util.UUID

/**
 * One row of the `call_log` table (plan section 3.8): one MCP tool call or one REST request.
 *
 * Field names mirror the columns. [host] is the client application name (MCP clientInfo.name, or the REST X-Client-Host / User-Agent product);
 * [runId] and [seat] stay null until a run is stamped; [rootId] is the single root the call read or wrote (null when none or several). [targetIds],
 * [targetVersions] and [requestShape] are JSON text. Byte counts are UTF-8 bytes of the bodies the caller sent
 * and received; [responseTokensEst] is [TokenEstimate] of [responseBytes] and [tokenMethod] names the method.
 * [hitSignals] (JSON text) is, for a `related` call, the signals that found each returned hit, in page order;
 * [lifecycleFreeRejections] counts the rejections caused by a lifecycle-free item (null when none, never 0).
 */
data class CallLogRecord(
    val reqId: String,
    val at: Instant,
    val principalId: String? = null,
    val principalKind: String? = null,
    val proofStatus: String? = null,
    val host: String? = null,
    val sessionId: String? = null,
    val runId: UUID? = null,
    val seat: String? = null,
    val surface: String,
    val tool: String,
    val operation: String? = null,
    val targetIds: String? = null,
    val targetVersions: String? = null,
    val requestShape: String? = null,
    val outcome: String,
    val errorCode: String? = null,
    val attempts: Int = 1,
    val latencyMs: Long,
    val requestBytes: Long? = null,
    val responseBytes: Long? = null,
    val responseTokensEst: Long? = null,
    val tokenMethod: String? = null,
    val replayed: Boolean = false,
    val batchSize: Int? = null,
    val failedCount: Int? = null,
    val resultCount: Int? = null,
    val eligibleCount: Int? = null,
    val hitSignals: String? = null,
    val lifecycleFreeRejections: Int? = null,
    val rootId: UUID? = null
) {
    companion object {
        const val SURFACE_MCP = "mcp"
        const val SURFACE_REST = "rest"
        const val OUTCOME_OK = "ok"
        const val OUTCOME_ERROR = "error"
    }
}

/** The token estimate recorded per row: `ceil(utf8Bytes / 4)`, method name [METHOD]. */
object TokenEstimate {
    const val METHOD = "bytes/4"

    fun of(bytes: Long): Long = bytes / 4 + (if (bytes % 4 == 0L) 0L else 1L)
}

/**
 * Durable call log.
 *
 * [append] is a write: it runs inside a unit of work (the [CallLogSink] implementation opens one per batch). A
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
 * Where a transport hands a finished call's record. [submit] never blocks, never throws and never suspends: a
 * telemetry failure must not change the call's result.
 */
fun interface CallLogSink {
    fun submit(record: CallLogRecord)

    companion object {
        /** Discards every record. */
        val NONE: CallLogSink = CallLogSink { }
    }
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
