import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.interfaces.mcp.McpServer
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * Entry point for the MCP Task Orchestrator application.
 */
@Suppress("unused")
fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("Main")
    // Use the version from the generated VersionInfo class
    val versionSuffix = if (VersionInfo.QUALIFIER.isNotEmpty()) {
        " (${VersionInfo.QUALIFIER})"
    } else {
        ""
    }

    logger.info("Starting MCP Task Orchestrator v${VersionInfo.VERSION}$versionSuffix\n")

    // Log environment information for debugging
    logger.info("Java version: ${System.getProperty("java.version")}")
    logger.info("JVM name: ${System.getProperty("java.vm.name")}")
    logger.info("OS name: ${System.getProperty("os.name")}")

    // Log environment variables (excluding sensitive ones)
    System.getenv().forEach { (key, value) ->
        if (!key.contains("key", ignoreCase = true) && !key.contains("password", ignoreCase = true)) {
            logger.debug("ENV: $key=$value")
        }
    }

    try {
        // Check if running in repair mode
        val isRepairMode = System.getenv("FLYWAY_REPAIR")?.toBoolean() ?: false

        // Initialize database
        initializeDatabase(logger)

        // Exit after repair if in repair mode
        if (isRepairMode) {
            logger.info("Repair mode complete, exiting...")
            exitProcess(0)
        }

        // Create and run the MCP server
        val mcpServer = McpServer(VersionInfo.VERSION)
        mcpServer.run()
    } catch (e: Exception) {
        logger.error("Error in MCP Task Orchestrator", e)
        exitProcess(1)
    }
}

/**
 * Initializes the database connection and schema.
 */
private fun initializeDatabase(logger: org.slf4j.Logger): DatabaseManager {
    // Get a database path from the environment or use default
    val databasePath = System.getenv("DATABASE_PATH") ?: "data/tasks.db"
    logger.info("Database path: $databasePath")

    // Initialize the database manager
    val databaseManager = DatabaseManager()

    if (!databaseManager.initialize(databasePath)) {
        logger.error("Failed to initialize database")
        exitProcess(1)
    }

    // Apply schema updates
    if (!databaseManager.updateSchema()) {
        logger.error("Failed to update database schema")
        exitProcess(1)
    }

    return databaseManager
}
