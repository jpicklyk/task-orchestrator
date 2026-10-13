package io.github.jpicklyk.mcptask.current.application.port

import java.time.Instant

/**
 * How far the `call_log` has been folded into `usage_daily` (R13).
 *
 * The retention pruner deletes a `call_log` row only when it is older than BOTH the `RETENTION_CALL_LOG_DAYS` cutoff
 * and [foldedThrough], so no row is lost before its day has been summarized. A null answer means nothing has been
 * folded and no `call_log` row is pruned.
 */
fun interface CallLogFoldWatermark {
    /** The instant before which every `call_log` row has been folded, or null when nothing has been folded. */
    suspend fun foldedThrough(): Instant?

    companion object {
        /** Nothing is folded (until R13 supplies the fold): `call_log` is never pruned. */
        val NONE: CallLogFoldWatermark = CallLogFoldWatermark { null }
    }
}
