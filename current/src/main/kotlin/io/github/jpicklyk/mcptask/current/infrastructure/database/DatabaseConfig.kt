package io.github.jpicklyk.mcptask.current.infrastructure.database

/**
 * Configuration settings for the Current (v3) database connection.
 * Reads from environment variables with sensible defaults.
 */
object DatabaseConfig {
    /**
     * The database file path.
     * Override with the DATABASE_PATH environment variable.
     * Default: "data/current-tasks.db" (separate from v2's "data/tasks.db")
     */
    val databasePath: String
        get() = System.getenv("DATABASE_PATH") ?: "data/current-tasks.db"

    /**
     * Whether to use Flyway for database schema management.
     * Override with the USE_FLYWAY environment variable.
     * Default: true (production-ready by default)
     */
    val useFlyway: Boolean
        get() = System.getenv("USE_FLYWAY")?.toBoolean() ?: true

    /**
     * Logging verbosity level.
     * Override with the LOG_LEVEL environment variable.
     */
    val logLevel: String
        get() = System.getenv("LOG_LEVEL") ?: "INFO"

    /**
     * Directory containing the .taskorchestrator/ configuration folder.
     * Override with the AGENT_CONFIG_DIR environment variable.
     * Defaults to null (falls back to System.getProperty("user.dir")).
     */
    val agentConfigDir: String?
        get() = System.getenv("AGENT_CONFIG_DIR")

    /**
     * Maximum number of connections in the pool.
     * Override with the DATABASE_MAX_CONNECTIONS environment variable.
     */
    val maxConnections: Int
        get() = System.getenv("DATABASE_MAX_CONNECTIONS")?.toIntOrNull() ?: 10

    /**
     * Whether to show SQL statements in the logs.
     * Override with the DATABASE_SHOW_SQL environment variable.
     */
    val showSql: Boolean
        get() = System.getenv("DATABASE_SHOW_SQL")?.toBoolean() ?: false
}
