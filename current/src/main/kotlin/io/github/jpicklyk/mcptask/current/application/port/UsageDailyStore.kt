package io.github.jpicklyk.mcptask.current.application.port

import java.time.Instant
import java.time.LocalDate

/** How far `call_log` has been folded into `usage_daily`: the last folded UTC day and when it was folded. */
data class UsageFoldWatermark(
    val foldedThrough: LocalDate,
    val foldedAt: Instant
)

/**
 * The `usage_daily` fold (R13): the watermark, the day bounds the fold needs, and the one write that folds a day. All
 * methods join the ambient unit; [foldDay] is a write.
 */
interface UsageDailyStore {
    /** The fold watermark, or null before the first fold. */
    suspend fun watermark(): UsageFoldWatermark?

    /** The UTC date of the oldest `call_log` row, or null when the table is empty. */
    suspend fun earliestCallLogDay(): LocalDate?

    /** The oldest day held in `usage_daily`, or null when it is empty. */
    suspend fun earliestFoldedDay(): LocalDate?

    /**
     * Folds the `call_log` rows of UTC [day] into `usage_daily` (replacing the day's summary rows, never adding to them)
     * and sets the watermark to [day], both in the caller's unit. Returns the number of groups the day holds.
     */
    suspend fun foldDay(
        day: LocalDate,
        foldedAt: Instant
    ): Int
}
