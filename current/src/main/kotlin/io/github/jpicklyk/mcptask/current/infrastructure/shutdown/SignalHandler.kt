package io.github.jpicklyk.mcptask.current.infrastructure.shutdown

import org.slf4j.LoggerFactory

/**
 * Installs OS signal handlers (SIGTERM, SIGINT) that delegate to [ShutdownCoordinator].
 *
 * Uses [sun.misc.Signal] which is deprecated but has no replacement on JVM 21+.
 * The `@Suppress("DEPRECATION")` annotation documents intentional use.
 */
@Suppress("DEPRECATION")
object SignalHandler {
    private val logger = LoggerFactory.getLogger(SignalHandler::class.java)

    /**
     * Install SIGTERM and SIGINT handlers that trigger graceful shutdown.
     */
    fun install(coordinator: ShutdownCoordinator) {
        installSignal("TERM", coordinator)
        installSignal("INT", coordinator)
    }

    private fun installSignal(signalName: String, coordinator: ShutdownCoordinator) {
        try {
            sun.misc.Signal.handle(sun.misc.Signal(signalName)) {
                coordinator.initiateShutdown("Received SIG$signalName")
            }
            logger.info("Installed SIG$signalName handler")
        } catch (e: IllegalArgumentException) {
            // Signal not available on this platform (e.g., SIGTERM on Windows)
            logger.warn("Could not install SIG$signalName handler: ${e.message}")
        }
    }
}
