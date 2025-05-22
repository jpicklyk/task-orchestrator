import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.interfaces.mcp.McpServer
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private const val applicationVersion = "0.3.15"

/**
 * Entry point for the MCP Task Orchestrator application.
 */
fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("Main")
    logger.info("Starting MCP Task Orchestrator v$applicationVersion")

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
        // Initialize database
        initializeDatabase(logger)

        
        // Create and run the MCP server
        val mcpServer = McpServer(applicationVersion)
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
