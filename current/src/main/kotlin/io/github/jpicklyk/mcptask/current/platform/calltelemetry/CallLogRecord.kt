package io.github.jpicklyk.mcptask.current.platform.calltelemetry

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
 * [principalKind] is the credential's PrincipalKind wire value (human, agent or service); null when the call had no principal.
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
