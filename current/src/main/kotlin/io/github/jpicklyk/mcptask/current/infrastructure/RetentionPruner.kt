package io.github.jpicklyk.mcptask.current.infrastructure

import io.github.jpicklyk.mcptask.current.application.port.CallLogFoldWatermark
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.EventClasses
import io.github.jpicklyk.mcptask.current.infrastructure.config.RetentionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/** Rows one [RetentionPruner.pruneOnce] pass deleted, per table; [capped] when the pass stopped at its row cap. */
data class RetentionPass(
    val callLog: Int,
    val events: Int,
    val leaseHistory: Int,
    val capped: Boolean,
    val foldedDays: Int = 0
) {
    val total: Int get() = callLog + events + leaseHistory
}

/**
 * Deletes rows older than the `RETENTION_*` windows from `events`, `resource_lease_history` and `call_log`: one pass
 * at start, then every [interval]. Modeled on [IdempotencyPruner].
 *
 * A pass walks an ordered list of sources (each prunable event type, then closed lease intervals, then `call_log`)
 * in write units of at most [batchSize] rows IN TOTAL: a unit drains the current source and moves on to the next
 * one until its quota is spent, so the writer lock is released at least every [batchSize] deletions. A pass stops
 * after [passCap] rows (the next pass continues). Each unit takes its cutoffs from the unit instant (strict `<`).
 *
 * Invariants:
 *  - an event at `seq >= maxSeq - sseBufferSize` is never deleted, so a client inside the Last-Event-ID replay
 *    window never resumes into a gap; `maxSeq` is read inside the unit that deletes;
 *  - audit-class types follow [RetentionSettings.auditEventsDays] (0 = forever) and activity types
 *    [RetentionSettings.eventsDays]; a type the class map does not know is never deleted;
 *  - the latest `claim.expired` row per item survives (the claim-expiry sweep deduplicates against it);
 *  - only closed lease intervals (`released_at IS NOT NULL`) are deleted;
 *  - `call_log` rows are deleted only before the fold watermark ([folder] when given, else [foldWatermark]; nothing
 *    while it answers null), and a pass folds the completed days into `usage_daily` BEFORE it deletes anything. A fold
 *    failure aborts the pass (nothing is pruned: the safe direction).
 *
 * The pruner writes no events and runs no VACUUM. Each pass logs one INFO line. A failed pass is logged at WARN and
 * never fatal; the loop runs on its own scope, which [stop] cancels and joins.
 */
class RetentionPruner(
    private val unitOfWork: UnitOfWork,
    private val settings: RetentionSettings,
    private val sseBufferSize: Int,
    private val interval: Duration = Duration.ofHours(1),
    private val batchSize: Int = 5_000,
    private val passCap: Int = 200_000,
    private val foldWatermark: CallLogFoldWatermark = CallLogFoldWatermark.NONE,
    private val folder: UsageDailyFolder? = null
) {
    init {
        require(batchSize > 0) { "batchSize must be positive: $batchSize" }
        require(passCap > 0) { "passCap must be positive: $passCap" }
    }

    private val logger = LoggerFactory.getLogger(RetentionPruner::class.java)
    private val watermark: CallLogFoldWatermark get() = folder ?: foldWatermark

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    private sealed class Source {
        data class Events(
            val type: String,
            val days: Int
        ) : Source()

        data object LeaseHistory : Source()

        data object CallLog : Source()
    }

    private class Counts {
        var events = 0
        var leaseHistory = 0
        var callLog = 0
        val total: Int get() = events + leaseHistory + callLog
    }

    private class UnitResult(
        val nextSource: Int,
        val counts: Counts
    )

    /** Runs one pass and returns the rows it deleted. A failed write unit is thrown (rows of earlier units stay deleted). */
    suspend fun pruneOnce(): RetentionPass {
        val started = System.nanoTime()
        val foldedDays = folder?.foldOnce()?.days ?: 0
        val sources = sources()
        val totals = Counts()
        var next = 0
        var budget = passCap
        var units = 0
        while (next < sources.size && budget > 0) {
            val quota = minOf(batchSize, budget)
            val from = next
            val result =
                when (val outcome = unitOfWork.write("RetentionPruner.prune") { Outcome.Ok(pruneUnit(sources, from, quota)) }) {
                    is Outcome.Ok -> outcome.value
                    is Outcome.Err -> error(outcome.error.message)
                }
            units++
            totals.events += result.counts.events
            totals.leaseHistory += result.counts.leaseHistory
            totals.callLog += result.counts.callLog
            budget -= result.counts.total
            next = result.nextSource
        }
        val pass =
            RetentionPass(
                callLog = totals.callLog,
                events = totals.events,
                leaseHistory = totals.leaseHistory,
                capped = budget <= 0 && next < sources.size,
                foldedDays = foldedDays
            )
        logger.info(
            "Retention pass: events={} lease_history={} call_log={} folded_days={} capped={} units={} ({} ms)",
            pass.events,
            pass.leaseHistory,
            pass.callLog,
            pass.foldedDays,
            pass.capped,
            units,
            (System.nanoTime() - started) / 1_000_000
        )
        return pass
    }

    /**
     * One write unit: deletes at most [quota] rows, draining [sources] from index [from]. A source is exhausted (and
     * the unit moves to the next) when it returns fewer rows than asked for.
     */
    private suspend fun WriteScope.pruneUnit(
        sources: List<Source>,
        from: Int,
        quota: Int
    ): UnitResult {
        val counts = Counts()
        var index = from
        var left = quota
        var protectFrom: Long? = null
        while (index < sources.size && left > 0) {
            val deleted =
                when (val source = sources[index]) {
                    is Source.Events -> {
                        val cutoff = cutoff(now, source.days)
                        if (cutoff == null) {
                            0
                        } else {
                            val floor =
                                protectFrom ?: (stores.eventStore().maxSeq() - sseBufferSize.coerceAtLeast(0)).also { protectFrom = it }
                            stores.eventStore().prune(source.type, cutoff, floor, left).also { counts.events += it }
                        }
                    }
                    Source.LeaseHistory -> {
                        val cutoff = cutoff(now, settings.leaseHistoryDays)
                        if (cutoff ==
                            null
                        ) {
                            0
                        } else {
                            stores.resourceLeaseRepository().pruneClosed(cutoff, left).also { counts.leaseHistory += it }
                        }
                    }
                    Source.CallLog -> {
                        val folded = watermark.foldedThrough()
                        val cutoff = cutoff(now, settings.callLogDays)
                        if (folded == null || cutoff == null) {
                            0
                        } else {
                            stores.callLogStore().prune(minOf(cutoff, folded), left).also { counts.callLog += it }
                        }
                    }
                }
            if (deleted < left) index++
            left -= deleted
        }
        return UnitResult(index, counts)
    }

    /** The sources of one pass, in order; a table or class whose window is 0 (forever) is absent. */
    private fun sources(): List<Source> =
        buildList {
            if (settings.eventsDays > 0) EventClasses.ACTIVITY_TYPES.sorted().forEach { add(Source.Events(it, settings.eventsDays)) }
            if (settings.auditEventsDays > 0) EventClasses.AUDIT_TYPES.sorted().forEach { add(Source.Events(it, settings.auditEventsDays)) }
            if (settings.leaseHistoryDays > 0) add(Source.LeaseHistory)
            if (settings.callLogDays > 0) add(Source.CallLog)
        }

    /** Prunes once now (a failure only warns), then every [interval] in the background. */
    suspend fun start() {
        check(job == null) { "RetentionPruner is already started" }
        prune()
        job =
            scope.launch {
                while (true) {
                    delay(interval.toMillis())
                    prune()
                }
            }
    }

    /**
     * Like [start] but does not wait for the first pass: it runs in the background, so a large first pass never
     * delays startup or readiness. Used by the server; [stop] cancels it like the loop.
     */
    fun launch() {
        check(job == null) { "RetentionPruner is already started" }
        job =
            scope.launch {
                while (true) {
                    prune()
                    delay(interval.toMillis())
                }
            }
    }

    /** Cancels the background loop and waits up to five seconds for it to finish. */
    suspend fun stop() {
        val running = job ?: return
        running.cancel()
        withTimeoutOrNull(STOP_TIMEOUT_MS) { running.join() }
        scope.coroutineContext[Job]?.cancel()
        job = null
    }

    private suspend fun prune() {
        try {
            pruneOnce()
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("Retention pruning failed: {}", e.message)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** No stored timestamp is earlier than this; a cutoff before it deletes nothing (and is never formatted). */
        val EARLIEST_CUTOFF: Instant = Instant.parse("0001-01-01T00:00:00Z")

        /** `now - days` (strict `<` applies at the store), or null when the window reaches before [EARLIEST_CUTOFF]. */
        fun cutoff(
            now: Instant,
            days: Int
        ): Instant? {
            val cutoff = now.minus(Duration.ofDays(days.toLong()))
            return if (cutoff.isBefore(EARLIEST_CUTOFF)) null else cutoff
        }
    }
}
