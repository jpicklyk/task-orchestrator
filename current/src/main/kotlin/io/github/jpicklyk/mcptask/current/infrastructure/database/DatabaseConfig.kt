package io.github.jpicklyk.mcptask.current.infrastructure.database

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig

/**
 * Thin, typed view over the database-related fields of [AppConfig].
 *
 * Environment reads are consolidated in [AppConfig.fromEnv]; this object is retained as a
 * backward-compatible accessor for code (and tests) that referenced `DatabaseConfig.*` directly.
 * Each property reads from a single process-wide [AppConfig] snapshot built once on first access.
 *
 * Prefer passing an [AppConfig] explicitly (e.g. into [DatabaseManager]) over reaching for this
 * object — it exists so existing call sites and unit tests keep compiling unchanged.
 */
object DatabaseConfig {
    /** Process-wide snapshot, read once from the environment on first access. */
    private val snapshot: AppConfig by lazy { AppConfig.fromEnv() }

    /** The database file path. Override with DATABASE_PATH. Default: "data/current-tasks.db". */
    val databasePath: String
        get() = snapshot.databasePath

    /** Whether to use Flyway for schema management. Override with USE_FLYWAY. Default: true. */
    val useFlyway: Boolean
        get() = snapshot.useFlyway

    /** Logging verbosity level. Override with LOG_LEVEL. Default: "INFO". */
    val logLevel: String
        get() = snapshot.logLevel

    /** Directory containing the .taskorchestrator/ folder. Override with AGENT_CONFIG_DIR. */
    val agentConfigDir: String?
        get() = snapshot.agentConfigDir

    /** Maximum number of pooled connections. Override with DATABASE_MAX_CONNECTIONS. Default: 10. */
    val maxConnections: Int
        get() = snapshot.databaseMaxConnections

    /** Whether to log SQL statements. Override with DATABASE_SHOW_SQL. Default: false. */
    val showSql: Boolean
        get() = snapshot.databaseShowSql

    /**
     * SQLite busy_timeout in milliseconds. Override with DATABASE_BUSY_TIMEOUT_MS.
     * Unset → 5000 ms; unparseable → 5000 ms; below 100 ms → 100 ms (sanity floor).
     */
    val busyTimeoutMs: Long
        get() = snapshot.databaseBusyTimeoutMs

    /**
     * Pure validation function — resolves a raw env-var string to a validated timeout value.
     * Exposed as `internal` for unit testing without env-var manipulation. Delegates to the
     * canonical implementation in [AppConfig].
     *
     * @param rawValue the raw string from the environment variable, or null if unset
     * @return validated timeout in milliseconds
     */
    internal fun resolveBusyTimeoutMs(rawValue: String?): Long = AppConfig.resolveBusyTimeoutMs(rawValue)

    internal const val DEFAULT_BUSY_TIMEOUT_MS = AppConfig.DEFAULT_BUSY_TIMEOUT_MS
    internal const val MIN_BUSY_TIMEOUT_MS = AppConfig.MIN_BUSY_TIMEOUT_MS
}
