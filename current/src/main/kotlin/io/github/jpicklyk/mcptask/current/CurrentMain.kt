package io.github.jpicklyk.mcptask.current

import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.SignalHandler
import io.github.jpicklyk.mcptask.current.interfaces.mcp.CurrentMcpServer
import org.slf4j.LoggerFactory

/**
 * Entry point for the Current (v3) MCP Task Orchestrator application.
 */
fun main() {
    val logger = LoggerFactory.getLogger("CurrentMain")
    val version = "0.1.0-alpha-01"

    logger.info("Starting Current (v3) MCP Task Orchestrator v$version")

    // Log environment information for debugging
    logger.info("Java version: ${System.getProperty("java.version")}")
    logger.info("JVM name: ${System.getProperty("java.vm.name")}")
    logger.info("OS name: ${System.getProperty("os.name")}")

    try {
        // Create shutdown coordinator
        val coordinator = ShutdownCoordinator()

        // Install OS signal handlers (SIGTERM, SIGINT)
        SignalHandler.install(coordinator)

        // Register JVM shutdown hook as fallback
        Runtime.getRuntime().addShutdownHook(Thread {
            coordinator.initiateShutdown("JVM shutdown hook")
            coordinator.awaitCompletion(5000)
        })

        // Create and run the MCP server (blocks until server closes)
        val mcpServer = CurrentMcpServer(version, coordinator)
        mcpServer.run()

        logger.info("Main function exiting normally")
    } catch (e: Exception) {
        logger.error("Error in Current (v3) MCP Task Orchestrator", e)
        // Don't use exitProcess(1) — it bypasses shutdown hooks.
        // Throwing from main() will cause the JVM to exit with code 1.
        throw e
    }
}
