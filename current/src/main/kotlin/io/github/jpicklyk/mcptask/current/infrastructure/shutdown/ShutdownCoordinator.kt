package io.github.jpicklyk.mcptask.current.infrastructure.shutdown

import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe shutdown coordinator with exactly-once execution semantics.
 *
 * Multiple shutdown triggers (SIGTERM, SIGINT, stdin EOF, JVM shutdown hook)
 * converge here. The first caller to [initiateShutdown] runs the cleanup
 * sequence; subsequent callers are no-ops.
 *
 * [addCleanupAction] and [initiateShutdown] are safe to call concurrently from
 * any thread. A single [lock] guards both the registration list and the
 * [shutdownStarted] flag, so a registration can never race the drain's
 * iterator: [addCleanupAction] either lands in the snapshot [initiateShutdown]
 * drains, or — if the drain has already started — runs the action immediately
 * on the registering thread, wrapped in the same per-action log/catch shape as
 * the in-sequence path (see [initiateShutdown]). Every call to
 * [addCleanupAction] that returns normally has therefore had its action run
 * exactly once. Actions are never rejected: every production call site
 * registers the release of a resource it just constructed, and running late
 * releases immediately preserves that intent instead of leaking the resource.
 *
 * Known limitation: a shutdown signal arriving mid-startup does not abort
 * startup. A cleanup action registered after the drain has already run (e.g.
 * "stop HTTP server") executes immediately, which can stop a resource that a
 * still-running startup sequence starts moments later. This is inherent to
 * signalling during startup, not a defect of this class — callers that need
 * to bail out of startup early should poll [isShutdownInitiated].
 */
class ShutdownCoordinator {
    private val logger = LoggerFactory.getLogger(ShutdownCoordinator::class.java)
    private val shutdownInitiated = AtomicBoolean(false)
    private val shutdownComplete = CountDownLatch(1)
    private val lock = Any()

    // Guarded by `lock`.
    private val cleanupActions = mutableListOf<Pair<String, () -> Unit>>()

    // Guarded by `lock`.
    private var shutdownStarted = false

    /**
     * Register a named cleanup action. Actions execute in registration order
     * during shutdown. May be called at any time, including concurrently with
     * or after [initiateShutdown]: if the drain has already started, the
     * action runs immediately on the calling thread instead of being enqueued
     * — it is never silently dropped.
     */
    fun addCleanupAction(
        name: String,
        action: () -> Unit
    ) {
        val runImmediately =
            synchronized(lock) {
                if (shutdownStarted) {
                    true
                } else {
                    cleanupActions.add(name to action)
                    false
                }
            }

        if (runImmediately) {
            runCleanupAction(name, action)
        }
    }

    /**
     * Initiate the shutdown sequence. Thread-safe — only the first caller
     * executes cleanup; subsequent calls return immediately.
     *
     * @param reason Human-readable reason for shutdown (for logging)
     */
    fun initiateShutdown(reason: String) {
        if (!shutdownInitiated.compareAndSet(false, true)) {
            logger.info("Shutdown already in progress, ignoring duplicate trigger: $reason")
            return
        }

        logger.info("Shutdown initiated: $reason")

        val actionsSnapshot =
            synchronized(lock) {
                shutdownStarted = true
                cleanupActions.toList()
            }

        try {
            for ((name, action) in actionsSnapshot) {
                runCleanupAction(name, action)
            }
            logger.info("Shutdown sequence complete")
        } finally {
            shutdownComplete.countDown()
        }
    }

    private fun runCleanupAction(
        name: String,
        action: () -> Unit
    ) {
        try {
            logger.info("Running cleanup: $name")
            action()
            logger.info("Cleanup complete: $name")
        } catch (e: Exception) {
            logger.error("Cleanup failed: $name", e)
        }
    }

    /**
     * Block until shutdown completes or timeout expires.
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if shutdown completed within timeout
     */
    fun awaitCompletion(timeoutMs: Long = 5000): Boolean = shutdownComplete.await(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Check whether shutdown has been initiated.
     */
    fun isShutdownInitiated(): Boolean = shutdownInitiated.get()
}
