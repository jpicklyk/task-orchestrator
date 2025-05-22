package io.github.jpicklyk.mcptask.infrastructure.database

/**
 * Configuration settings for the database connection.
 */
object DatabaseConfig {
    /**
     * The database file path.
     * This can be overridden with the DATABASE_PATH environment variable.
     */
    val databasePath: String
        get() = System.getenv("DATABASE_PATH") ?: "data/tasks.db"
    
    /**
     * The maximum number of connections in the pool.
     * This can be overridden with the DATABASE_MAX_CONNECTIONS environment variable.
     */
    val maxConnections: Int
        get() = System.getenv("DATABASE_MAX_CONNECTIONS")?.toIntOrNull() ?: 10
    
    /**
     * The database type (currently only SQLite is supported).
     */
    const val databaseType: String = "sqlite"
    
    /**
     * Whether to show SQL statements in the logs.
     * This can be enabled with the DATABASE_SHOW_SQL environment variable.
     */
    val showSql: Boolean
        get() = System.getenv("DATABASE_SHOW_SQL")?.toBoolean() ?: false
}