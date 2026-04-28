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

    /**
     * SQLite busy_timeout in milliseconds — how long SQLite waits for a write lock before
     * returning SQLITE_BUSY. Increase this in high-contention fleet deployments.
     *
     * Recommended range: 5000–30000 ms.
     * Default: 5000 ms (5 seconds) — appropriate for single-process use and small fleets.
     * Beyond 30 000 ms you are medicating a capacity problem rather than solving it;
     * right-size the fleet or reduce concurrency instead.
     *
     * Override with the DATABASE_BUSY_TIMEOUT_MS environment variable.
     * - If the variable is set but not parseable as a Long, the default (5000) is used.
     * - If the parsed value is below 100 ms, 100 ms is used (sanity floor).
     */
    val busyTimeoutMs: Long
        get() = resolveBusyTimeoutMs(System.getenv("DATABASE_BUSY_TIMEOUT_MS"))

    /**
     * Pure validation function — resolves a raw env-var string to a validated timeout value.
     * Exposed as `internal` for unit testing without env-var manipulation.
     *
     * @param rawValue the raw string from the environment variable, or null if unset
     * @return validated timeout in milliseconds
     */
    internal fun resolveBusyTimeoutMs(rawValue: String?): Long {
        rawValue ?: return DEFAULT_BUSY_TIMEOUT_MS
        val parsed = rawValue.toLongOrNull() ?: return DEFAULT_BUSY_TIMEOUT_MS
        return if (parsed < MIN_BUSY_TIMEOUT_MS) MIN_BUSY_TIMEOUT_MS else parsed
    }

    internal const val DEFAULT_BUSY_TIMEOUT_MS = 5000L
    internal const val MIN_BUSY_TIMEOUT_MS = 100L
}
