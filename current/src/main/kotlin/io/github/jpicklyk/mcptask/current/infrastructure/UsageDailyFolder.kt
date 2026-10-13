package io.github.jpicklyk.mcptask.current.infrastructure

import io.github.jpicklyk.mcptask.current.application.port.CallLogFoldWatermark
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** What one [UsageDailyFolder.foldOnce] pass did: [days] folded, [groups] summary rows they hold, the watermark after it, and whether it stopped at the day cap. */
data class FoldPass(
    val days: Int,
    val groups: Int,
    val foldedThrough: LocalDate?,
    val capped: Boolean
)

/**
 * Folds completed UTC days of `call_log` into `usage_daily` (R13) and answers the fold watermark to the retention pruner.
 *
 * A pass folds the days after the watermark (or from the oldest `call_log` day when there is none) up to the last
 * foldable day: [lastFoldableDay] of the pass instant, so today (and yesterday during the first [grace] after midnight UTC,
 * which absorbs the batched call-log writer's lag) is never folded. Each day is one write unit (the writer lock is held
 * for one day's rows at most), advancing the watermark over empty days too; a pass folds at most [maxDaysPerPass] days and
 * the next one continues. A day below the watermark is never re-folded: its raw rows may already be pruned.
 */
class UsageDailyFolder(
    private val unitOfWork: UnitOfWork,
    private val grace: Duration = Duration.ofHours(1),
    private val maxDaysPerPass: Int = 400
) : CallLogFoldWatermark {
    init {
        require(maxDaysPerPass > 0) { "maxDaysPerPass must be positive: $maxDaysPerPass" }
        require(!grace.isNegative) { "grace must not be negative: $grace" }
    }

    private class Plan(
        val now: Instant,
        val next: LocalDate?,
        val last: LocalDate
    )

    /** Folds the pending completed days, one write unit each. A failed unit is thrown (earlier days stay folded). */
    suspend fun foldOnce(): FoldPass {
        val plan =
            unitOfWork.read {
                val store = stores.usageDailyStore()
                val watermark = store.watermark()
                val next = watermark?.foldedThrough?.plusDays(1) ?: store.earliestCallLogDay()
                Plan(now, next, lastFoldableDay(now, grace))
            }
        val start = plan.next
        if (start == null || start.isAfter(plan.last)) return FoldPass(0, 0, currentWatermark(), false)
        val lastThisPass = minOf(plan.last, start.plusDays(maxDaysPerPass - 1L))
        var days = 0
        var groups = 0
        var day: LocalDate = start
        while (!day.isAfter(lastThisPass)) {
            val folding = day
            val result =
                unitOfWork.write("UsageDailyFolder.fold") {
                    val store = stores.usageDailyStore()
                    val watermark = store.watermark()
                    // Re-checked in the unit: a day at or below the watermark is never folded again.
                    if (watermark != null && !watermark.foldedThrough.isBefore(folding)) {
                        Outcome.Ok(-1)
                    } else {
                        Outcome.Ok(store.foldDay(folding, now))
                    }
                }
            when (result) {
                is Outcome.Ok -> if (result.value >= 0) groups += result.value
                is Outcome.Err -> error(result.error.message)
            }
            days++
            day = day.plusDays(1)
        }
        return FoldPass(days, groups, currentWatermark(), capped = lastThisPass.isBefore(plan.last))
    }

    /** The instant before which every `call_log` row is folded: the day after the watermark at 00:00Z, or null before the first fold. */
    override suspend fun foldedThrough(): Instant? = currentWatermark()?.plusDays(1)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()

    private suspend fun currentWatermark(): LocalDate? = unitOfWork.read { stores.usageDailyStore().watermark()?.foldedThrough }

    companion object {
        /** The last UTC day that is complete at [now]: the UTC date of `now - grace`, minus one day. */
        fun lastFoldableDay(
            now: Instant,
            grace: Duration
        ): LocalDate =
            now
                .minus(grace)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .minusDays(1)
    }
}
