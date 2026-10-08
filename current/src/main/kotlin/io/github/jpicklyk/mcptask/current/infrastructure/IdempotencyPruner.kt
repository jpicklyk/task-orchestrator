package io.github.jpicklyk.mcptask.current.infrastructure

import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
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
 * Deletes idempotency records older than the replay window: once at [start], then every [interval].
 *
 * Each pass is ONE write unit (`deleteExpired(scope.now - ttl)`), so it runs under the production
 * outside-unit policy and takes its time from the unit, like every other idempotency call. A failed
 * pass is logged at WARN and never fatal. The loop runs on its own scope, which [stop] cancels and
 * joins, so nothing runs after shutdown.
 */
class IdempotencyPruner(
    private val unitOfWork: UnitOfWork,
    private val interval: Duration = Duration.ofHours(1),
    private val ttl: Duration = IdempotencyService.DEFAULT_TTL
) {
    private val logger = LoggerFactory.getLogger(IdempotencyPruner::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    /** Runs one pass and returns the number of records deleted. */
    suspend fun pruneOnce(): Int =
        when (
            val outcome =
                unitOfWork.write(
                    "IdempotencyPruner.prune"
                ) { Outcome.Ok(stores.idempotencyStore().deleteExpired(now.minus(ttl))) }
        ) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error(outcome.error.message)
        }

    /** Prunes once now (a failure only warns), then hourly in the background. */
    suspend fun start() {
        check(job == null) { "IdempotencyPruner is already started" }
        prune()
        job =
            scope.launch {
                while (true) {
                    delay(interval.toMillis())
                    prune()
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
            val deleted = pruneOnce()
            if (deleted > 0) logger.info("Pruned {} expired idempotency record(s)", deleted)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("Idempotency record pruning failed: {}", e.message)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
