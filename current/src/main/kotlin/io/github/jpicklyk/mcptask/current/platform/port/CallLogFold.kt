package io.github.jpicklyk.mcptask.current.platform.port

import java.time.Instant
import java.time.LocalDate

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

/** What one [CallLogFold.foldOnce] pass did: [days] folded, [groups] summary rows they hold, the watermark after it, and whether it stopped at the day cap. */
data class FoldPass(
    val days: Int,
    val groups: Int,
    val foldedThrough: LocalDate?,
    val capped: Boolean
)

/** A fold write unit failed on [day]; [days] earlier days of the pass were folded. [cause] is the original failure. */
class UsageFoldException(
    val day: LocalDate,
    val days: Int,
    cause: Throwable
) : RuntimeException("usage fold failed on UTC day $day", cause)

/**
 * A [CallLogFoldWatermark] that also folds: one [foldOnce] pass folds the completed UTC days of `call_log` into
 * `usage_daily`, and [foldedThrough] answers how far that has got. The retention pruner folds before it deletes.
 */
interface CallLogFold : CallLogFoldWatermark {
    /** Folds the pending completed days, one write unit each. A failed unit is thrown as [UsageFoldException] (earlier days stay folded). */
    suspend fun foldOnce(): FoldPass
}
