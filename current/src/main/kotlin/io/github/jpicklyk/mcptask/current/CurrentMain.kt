package io.github.jpicklyk.mcptask.current

import io.github.jpicklyk.mcptask.current.application.BuildInfo
import io.github.jpicklyk.mcptask.current.infrastructure.config.JvmTimezone
import io.github.jpicklyk.mcptask.current.infrastructure.logging.LogFileAppenderInstaller
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.SignalHandler
import io.github.jpicklyk.mcptask.current.interfaces.mcp.CurrentMcpServer
import io.github.jpicklyk.mcptask.current.interfaces.mcp.Failed
import io.github.jpicklyk.mcptask.current.interfaces.mcp.RepairCompleted
import io.github.jpicklyk.mcptask.current.interfaces.mcp.StartupFailedException
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import org.slf4j.LoggerFactory

/**
 * Entry point for the Current (v3) MCP Task Orchestrator application.
 */
fun main() {
    // Suppress kotlin-logging-jvm's own startup banner ("kotlin-logging: initializing... active
    // logger factory: ...") BEFORE anything else runs. This is a transitive dependency of the MCP
    // SDK (io.github.oshai:kotlin-logging-jvm) that the SDK uses internally; when its `KotlinLogging`
    // singleton is first touched (the SDK's first `KotlinLogging.logger { }` call, anywhere in the
    // process), it prints that banner via `System.out.println` directly, BYPASSING SLF4J/logback
    // entirely -- corrupting the stdio transport's JSON-RPC stream. There is no JVM system property
    // for this specific setting (confirmed via javap against the resolved kotlin-logging-jvm 8.0.01
    // jar: only `kotlin-logging-to-jul`/`kotlin-logging-to-logback`/`kotlin-logging-to-direct`
    // properties exist, and they select the logging BACKEND, not the banner). The banner is gated
    // purely by the mutable `KotlinLoggingConfiguration.logStartupMessage` flag (default `true`,
    // set in that object's own <clinit>), so it must be flipped to `false` programmatically, before
    // the FIRST `KotlinLogging.logger { }` call anywhere in the JVM -- this is genuinely the
    // earliest point: nothing above this line touches the SDK or kotlin-logging.
    KotlinLoggingConfiguration.logStartupMessage = false

    // Opt-in file logging (LOG_FILE): attach before anything else logs, so as few startup lines
    // as possible are missed from the file. See LogFileAppenderInstaller's KDoc for why this is
    // programmatic rather than a logback.xml <if> conditional. No-op when LOG_FILE is unset.
    LogFileAppenderInstaller.installIfConfigured()

    val logger = LoggerFactory.getLogger("CurrentMain")
    val version = BuildInfo.version

    logger.info("Starting Current (v3) MCP Task Orchestrator v$version")

    // Log environment information for debugging
    logger.info("Java version: ${System.getProperty("java.version")}")
    logger.info("JVM name: ${System.getProperty("java.vm.name")}")
    logger.info("OS name: ${System.getProperty("os.name")}")

    // Enforce a UTC JVM default timezone for non-Docker launches (the Docker image pins this via
    // -Duser.timezone=UTC on the CMD). Must run before any Exposed/DB class caches the zone, so
    // this is the first thing main() does after logging startup info.
    JvmTimezone.enforceUtc(logger)

    try {
        // Create shutdown coordinator
        val coordinator = ShutdownCoordinator()

        // Install OS signal handlers (SIGTERM, SIGINT)
        SignalHandler.install(coordinator)

        // Register JVM shutdown hook as fallback
        Runtime.getRuntime().addShutdownHook(
            Thread {
                coordinator.initiateShutdown("JVM shutdown hook")
                coordinator.awaitCompletion(5000)
            }
        )

        // Create and run the MCP server (blocks until server closes)
        val mcpServer = CurrentMcpServer(version, coordinator)
        val outcome = mcpServer.run()
        if (outcome is Failed) {
            // Don't use exitProcess(1) here either — throw and let the catch below rethrow, so the
            // JVM's normal uncaught-exception exit (non-zero) applies without skipping shutdown hooks.
            throw StartupFailedException(outcome)
        }
        if (outcome is RepairCompleted) {
            logger.info("FLYWAY_REPAIR completed successfully; exiting without serving (exit 0).")
        }

        logger.info("Main function exiting normally")
    } catch (e: Exception) {
        logger.error("Error in Current (v3) MCP Task Orchestrator", e)
        // Don't use exitProcess(1) — it bypasses shutdown hooks.
        // Throwing from main() will cause the JVM to exit with code 1.
        throw e
    }
}
