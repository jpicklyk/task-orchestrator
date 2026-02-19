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
 */
class ShutdownCoordinator {
    private val logger = LoggerFactory.getLogger(ShutdownCoordinator::class.java)
    private val shutdownInitiated = AtomicBoolean(false)
    private val shutdownComplete = CountDownLatch(1)
    private val cleanupActions = mutableListOf<Pair<String, () -> Unit>>()

    /**
     * Register a named cleanup action. Actions execute in registration order
     * during shutdown. Must be called before [initiateShutdown].
     */
    fun addCleanupAction(name: String, action: () -> Unit) {
        cleanupActions.add(name to action)
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

        try {
            for ((name, action) in cleanupActions) {
                try {
                    logger.info("Running cleanup: $name")
                    action()
                    logger.info("Cleanup complete: $name")
                } catch (e: Exception) {
                    logger.error("Cleanup failed: $name", e)
                }
            }
            logger.info("Shutdown sequence complete")
        } finally {
            shutdownComplete.countDown()
        }
    }

    /**
     * Block until shutdown completes or timeout expires.
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if shutdown completed within timeout
     */
    fun awaitCompletion(timeoutMs: Long = 5000): Boolean {
        return shutdownComplete.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Check whether shutdown has been initiated.
     */
    fun isShutdownInitiated(): Boolean = shutdownInitiated.get()
}
