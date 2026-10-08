package io.github.jpicklyk.mcptask.current.infrastructure.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.FlywayDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.SchemaMode
import io.github.jpicklyk.mcptask.current.infrastructure.repository.readTxBlocking
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.File
import java.sql.Connection

/**
 * Manages database connections and schema management for the Current (v3) MCP Task Orchestrator.
 *
 * Two Exposed [Database]s sit over HikariCP pools (AR-16): [writer] has ONE connection that begins
 * every transaction IMMEDIATE and is serialized in-process by [units]; [reader] has
 * `DATABASE_MAX_CONNECTIONS` connections, begins DEFERRED and runs with `PRAGMA query_only = 1`.
 * Transactions are opened only by [units] and the helpers in `TransactionHelper.kt`.
 *
 * @param customDatabase Optional pre-configured database connection (useful for testing). When set,
 *   [writer] and [reader] are both this database and no pools are created.
 * @param appConfig Typed env snapshot supplying the busy_timeout value, FLYWAY_REPAIR and the raw env resolver. Defaults to
 *   a fresh [AppConfig.fromEnv] snapshot so existing no-arg construction (and tests) keep the prior
 *   env-driven behavior unchanged.
 * @param outsideUnitPolicy what a store write outside any unit of work does (always counted first).
 *   Stays [OutsideUnitPolicy.IMPLICIT] until every write site runs inside a unit; then it becomes FAIL.
 */
class DatabaseManager(
    private val customDatabase: Database? = null,
    private val appConfig: AppConfig = AppConfig.fromEnv(),
    outsideUnitPolicy: OutsideUnitPolicy = OutsideUnitPolicy.IMPLICIT
) {
    private val logger = LoggerFactory.getLogger(DatabaseManager::class.java)
    private var writerDb: Database? = customDatabase
    private var readerDb: Database? = customDatabase
    private var writerPool: HikariDataSource? = null
    private var readerPool: HikariDataSource? = null
    private var jdbcUrl: String? = null
    private lateinit var schemaManager: DatabaseSchemaManager

    /** Runs units of work: the writer Mutex, BUSY retry, boundary translation and the outside-unit write counter. */
    val units: UnitRunner = UnitRunner(this, outsideUnitPolicy)

    /**
     * Initializes the database connection pools.
     *
     * @param databasePath The path to the SQLite database file or JDBC URL
     * @return True if initialization was successful, false otherwise
     */
    fun initialize(databasePath: String): Boolean {
        // If a custom database is already provided, no need to initialize
        if (customDatabase != null) {
            logger.info("Using custom database connection")
            jdbcUrl = customDatabase.url
            return true
        }

        try {
            logger.info("Initializing database at: $databasePath")

            if (databasePath.contains("mode=memory") || databasePath.contains(":memory:")) {
                logger.error(
                    "In-memory SQLite databases are not supported: the writer and reader pools would open two " +
                        "different databases. Use a file-backed database. Refusing: $databasePath"
                )
                return false
            }

            // Determine if this is a JDBC URL or file path
            val url =
                if (databasePath.startsWith("jdbc:")) {
                    logger.info("Using provided JDBC URL: $databasePath")
                    databasePath
                } else {
                    // For file-based database, ensure parent directories exist
                    File(databasePath).parentFile?.mkdirs()
                    logger.info("Using file-based SQLite database at: $databasePath")
                    "jdbc:sqlite:$databasePath"
                }

            // DATABASE_BUSY_TIMEOUT_MS (validated and logged here) governs Flyway and startup compaction only;
            // the request-path pools use a fixed short busy_timeout (see POOL_BUSY_TIMEOUT_MS).
            val busyTimeoutMs = resolveBusyTimeout()

            val readerSize = appConfig.databaseMaxConnections.coerceIn(1, MAX_READER_CONNECTIONS)
            val writerSource = buildPool(WRITER_POOL_NAME, url, writer = true, maxSize = 1)
            writerPool = writerSource
            val readerSource = buildPool(READER_POOL_NAME, url, writer = false, maxSize = readerSize)
            readerPool = readerSource

            // The isolation level is per Database. defaultMaxAttempts = 1: Exposed would otherwise retry any
            // SQLException up to 3 times and re-run the block without resetting unit hooks or the ConfigSession;
            // UnitRunner owns retry.
            val config =
                DatabaseConfig {
                    defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
                    defaultMaxAttempts = 1
                }
            writerDb = Database.connect(datasource = writerSource, databaseConfig = config)
            readerDb = Database.connect(datasource = readerSource, databaseConfig = config)
            jdbcUrl = url

            logger.info(
                "Database pools established (writer: 1 connection, IMMEDIATE; reader: $readerSize connections, " +
                    "DEFERRED, query_only; pool busy_timeout $POOL_BUSY_TIMEOUT_MS ms; " +
                    "Flyway/compaction busy_timeout $busyTimeoutMs ms)"
            )
            return true
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.error("Failed to initialize database: ${e.message}", e)
            closePools()
            return false
        }
    }

    private fun buildPool(
        name: String,
        url: String,
        writer: Boolean,
        maxSize: Int
    ): HikariDataSource {
        val sqliteConfig =
            SQLiteConfig().apply {
                enforceForeignKeys(true)
                setJournalMode(SQLiteConfig.JournalMode.WAL)
                busyTimeout = POOL_BUSY_TIMEOUT_MS
                // IMMEDIATE acquires the writer lock at BEGIN, so a read-then-write transaction cannot fail with
                // SQLITE_BUSY_SNAPSHOT mid-flight. The JDBC URL string is left untouched (Flyway and compaction parse it).
                if (writer) setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
            }
        val dataSource = SQLiteDataSource(sqliteConfig).also { it.url = url }
        val hikari =
            HikariConfig().apply {
                this.dataSource = dataSource
                poolName = name
                maximumPoolSize = maxSize
                minimumIdle = maxSize
                connectionTimeout = CHECKOUT_TIMEOUT_MS
                maxLifetime = 0
                idleTimeout = 0
                leakDetectionThreshold = LEAK_DETECTION_MS
                // Not JDBC readOnly: sqlite-jdbc throws from setReadOnly once a statement has run on the connection.
                if (!writer) connectionInitSql = "PRAGMA query_only = 1"
            }
        return HikariDataSource(hikari)
    }

    /**
     * Applies any needed schema updates using the configured schema manager.
     *
     * @return True if schema was updated successfully, false otherwise
     */
    fun updateSchema(): Boolean {
        try {
            logger.info("Updating database schema...")

            // Ensure database is initialized
            writer()

            // Create schema manager if not already created
            if (!::schemaManager.isInitialized) {
                if (appConfig.envResolver("USE_FLYWAY") != null) {
                    logger.warn(
                        "USE_FLYWAY is ignored: Flyway is the only schema path and Direct mode has been removed. " +
                            "Remove USE_FLYWAY from the environment."
                    )
                }
                val schemaMode =
                    try {
                        SchemaMode.parse(appConfig.envResolver("SCHEMA_MODE"))
                    } catch (e: IllegalArgumentException) {
                        logger.error("${e.message}. Failing startup.")
                        return false
                    }
                val url = this.jdbcUrl
                if (url == null) {
                    logger.error("Cannot update schema: no JDBC URL available")
                    return false
                }
                logger.info("Creating Flyway schema manager (SCHEMA_MODE=${schemaMode.name.lowercase()})")
                schemaManager =
                    FlywayDatabaseSchemaManager(url, appConfig.flywayRepair, appConfig.databaseBusyTimeoutMs, schemaMode)
            }

            val result = schemaManager.updateSchema()

            if (result) {
                logger.info("Database schema updated successfully")
                checkParentCycleIntegrity()
                runStartupCompactionIfEligible()
            } else {
                logger.error("Failed to update database schema")
            }

            return result
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.error("Error updating database schema: ${e.message}", e)
            return false
        }
    }

    /**
     * Runs lightweight SQL queries to detect self-loop and 2-hop mutual cycles in the
     * work_items parent hierarchy. Logs a WARNING for each corrupt item found but does NOT
     * fail startup or delete data — remediation is a data decision, not an infra decision.
     *
     * Called once after schema migrations complete.
     */
    private fun checkParentCycleIntegrity() {
        try {
            readTxBlocking {
                exec("SELECT id FROM work_items WHERE id = parent_id") { rs ->
                    var selfLoopCount = 0
                    while (rs.next()) {
                        val id = rs.getString("id")
                        logger.warn("Data integrity issue: work item '$id' is its own parent (self-loop)")
                        selfLoopCount++
                    }
                    if (selfLoopCount > 0) {
                        logger.warn("Found $selfLoopCount self-loop(s) in work_items. These items have parent_id = id.")
                    }
                }

                exec(
                    """
                    SELECT a.id AS a_id, b.id AS b_id
                    FROM work_items a
                    JOIN work_items b ON a.parent_id = b.id AND b.parent_id = a.id
                    WHERE a.id < b.id
                    """.trimIndent()
                ) { rs ->
                    var mutualCycleCount = 0
                    while (rs.next()) {
                        val aId = rs.getString("a_id")
                        val bId = rs.getString("b_id")
                        logger.warn("Data integrity issue: mutual parent cycle between work items '$aId' and '$bId'")
                        mutualCycleCount++
                    }
                    if (mutualCycleCount > 0) {
                        logger.warn("Found $mutualCycleCount mutual 2-hop cycle(s) in work_items.")
                    }
                }
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("Could not run parent-cycle integrity check: ${e.message}")
        }
    }

    /**
     * Runs the one-time post-V17 [StartupCompaction] when eligible: not a `FLYWAY_REPAIR` run (which exits before serving), and
     * [AppConfig.dbCompactOnUpgrade] not disabled. Wrapped in [runCatching] as an extra safety
     * net on top of [StartupCompaction.runOnce] never throwing on its own — compaction must
     * never fail startup or change [updateSchema]'s return value.
     */
    private fun runStartupCompactionIfEligible() {
        if (appConfig.flywayRepair) return
        if (!appConfig.dbCompactOnUpgrade) return

        val url = jdbcUrl ?: return
        runCatchingNonCancellation {
            StartupCompaction.runOnce(url, appConfig.databaseBusyTimeoutMs)
        }.onSuccess { outcome ->
            when (outcome) {
                CompactionOutcome.COMPACTED -> logger.info("Startup compaction outcome: $outcome")
                CompactionOutcome.FAILED, CompactionOutcome.SKIPPED_INSUFFICIENT_DISK ->
                    logger.warn("Startup compaction outcome: $outcome")
                else -> logger.debug("Startup compaction outcome: $outcome")
            }
        }.onFailure { e ->
            logger.warn("Startup compaction threw unexpectedly: ${e.message}")
        }
    }

    /**
     * Resolves the busy_timeout value from [AppConfig.databaseBusyTimeoutMs] with logging.
     *
     * Validation rules (enforced in [AppConfig]):
     *   - Unset → default 5000 ms
     *   - Unparseable → default 5000 ms (warns here)
     *   - Below 100 ms → floor 100 ms (warns here)
     */
    private fun resolveBusyTimeout(): Long {
        val rawEnv = appConfig.databaseBusyTimeoutRaw
        val resolved = appConfig.databaseBusyTimeoutMs
        if (rawEnv != null) {
            val parsed = rawEnv.toLongOrNull()
            when {
                parsed == null ->
                    logger.warn(
                        "DATABASE_BUSY_TIMEOUT_MS='$rawEnv' is not a valid Long; " +
                            "using default 5000 ms"
                    )
                parsed < 100L ->
                    logger.warn(
                        "DATABASE_BUSY_TIMEOUT_MS=$parsed ms is below the 100 ms sanity floor; " +
                            "using 100 ms"
                    )
                else ->
                    logger.info("DATABASE_BUSY_TIMEOUT_MS set to $resolved ms")
            }
        } else {
            logger.info("DATABASE_BUSY_TIMEOUT_MS not set; using default $resolved ms")
        }
        return resolved
    }

    /**
     * The writer database: one connection, IMMEDIATE transactions, serialized by [units].
     *
     * @throws IllegalStateException if the database has not been initialized
     */
    fun writer(): Database = writerDb ?: throw IllegalStateException("Database has not been initialized")

    /**
     * The reader database: a pool of `query_only` DEFERRED connections.
     *
     * @throws IllegalStateException if the database has not been initialized
     */
    fun reader(): Database = readerDb ?: throw IllegalStateException("Database has not been initialized")

    /** Transitional alias for [writer]; callers should pick [writer] or [reader] (or, better, `readTx`/`writeTx`). */
    @Deprecated("Use writer() or reader(); open transactions through readTx/writeTx or a unit of work", ReplaceWith("writer()"))
    fun getDatabase(): Database = writer()

    /**
     * Shuts down the database connections and closes both pools.
     */
    fun shutdown() {
        try {
            logger.info("Shutting down database connection...")

            val dbs = listOfNotNull(writerDb, readerDb).distinct()
            for (db in dbs) {
                TransactionManager.closeAndUnregister(db)
            }
            if (dbs.isNotEmpty()) logger.info("Database connection closed and unregistered")
            closePools()
            writerDb = null
            readerDb = null
            jdbcUrl = null

            logger.info("Database shutdown complete")
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.error("Error shutting down database", e)
        }
    }

    /**
     * Soft-evicts the writer or reader pool's connections: idle ones close now, in-use ones when returned.
     * No-op for a pre-built database, which has no pools.
     */
    internal fun evictConnections(writer: Boolean) {
        val pool = if (writer) writerPool else readerPool
        try {
            pool?.hikariPoolMXBean?.softEvictConnections()
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("Could not evict ${if (writer) "writer" else "reader"} connections: ${e.message}")
        }
    }

    private fun closePools() {
        for (pool in listOfNotNull(writerPool, readerPool)) {
            try {
                pool.close()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                logger.warn("Error closing pool ${pool.poolName}: ${e.message}")
            }
        }
        writerPool = null
        readerPool = null
    }

    private companion object {
        const val WRITER_POOL_NAME = "to-writer"
        const val READER_POOL_NAME = "to-reader"
        const val MAX_READER_CONNECTIONS = 64

        /** Request-path busy_timeout: only other PROCESSES contend for the writer lock, so a short wait suffices. */
        const val POOL_BUSY_TIMEOUT_MS = 1000

        /** Pool checkout deadline; a miss becomes the `unavailable` error. */
        const val CHECKOUT_TIMEOUT_MS = 2000L

        const val LEAK_DETECTION_MS = 15000L
    }
}
