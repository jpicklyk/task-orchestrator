package io.github.jpicklyk.mcptask.current.infrastructure

import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import io.github.jpicklyk.mcptask.current.application.service.ExpirySweep
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Reports expired claims and removes lapsed resource leases: once at [start], then every [interval]. The backstop for
 * expiry that no advance, claim or lease call happened to notice (read-only tools never write).
 *
 * Each pass is ONE write unit ([ClaimService.sweepExpired]), so it runs under the production outside-unit policy and
 * takes its time from the unit. A failed pass is logged at WARN and never fatal. Its rows carry no actor. The loop runs
 * on its own scope, which [stop] cancels and joins, so nothing runs after shutdown.
 */
class ExpirySweeper(
    private val claimService: ClaimService,
    private val interval: Duration = Duration.ofHours(1)
) {
    private val logger = LoggerFactory.getLogger(ExpirySweeper::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    /** Runs one pass and returns what it found; a fault is thrown. */
    suspend fun sweepOnce(): ExpirySweep =
        when (val outcome = claimService.sweepExpired()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error(outcome.error.message)
        }

    /** Sweeps once now (a failure only warns), then hourly in the background. */
    suspend fun start() {
        check(job == null) { "ExpirySweeper is already started" }
        sweep()
        job =
            scope.launch {
                while (true) {
                    delay(interval.toMillis())
                    sweep()
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

    private suspend fun sweep() {
        try {
            val found = sweepOnce()
            if (found.claimsExpired > 0 || found.leasesExpired > 0) {
                logger.info(
                    "Expiry sweep: {} claim(s) reported expired, {} lapsed lease(s) removed",
                    found.claimsExpired,
                    found.leasesExpired
                )
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("Expiry sweep failed: {}", e.message)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
