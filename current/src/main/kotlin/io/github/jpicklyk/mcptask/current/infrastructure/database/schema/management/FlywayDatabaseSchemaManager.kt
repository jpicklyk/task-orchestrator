package io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management

import io.github.jpicklyk.mcptask.current.infrastructure.config.EnvBoolean
import io.github.jpicklyk.mcptask.current.infrastructure.database.StartupCompaction
import io.github.jpicklyk.mcptask.current.infrastructure.database.StartupIntegrity
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.flywaydb.core.api.output.MigrateResult
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * The schema manager: Flyway versioned migrations are the only schema path.
 *
 * Migrations are loaded from `classpath:db/migration/sqlite` only. The parent location must NOT also
 * be configured: Flyway scans recursively and would record scripts as `sqlite/V...`, which would not
 * match the bare names in existing histories. The migration files must stay byte-identical once
 * released: Flyway's checksum is a CRC32 over every line, comments included, so even a comment edit
 * (for example the V9 header) fails validation on every existing database.
 *
 * The whole schema phase (Direct-mode check/baseline, migrate/validate/repair, inventory, FTS check)
 * runs under an OS file lock `<dbfile>.migrate.lock` so concurrent boots serialize; Flyway has no
 * SQLite migration lock of its own (AR-85).
 *
 * @param jdbcUrl The JDBC URL for the database connection.
 * @param repair Whether to run Flyway repair instead of migrate. Sourced from the FLYWAY_REPAIR
 *   env var via [io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig]; defaults to
 *   reading the env directly (via the shared [EnvBoolean] vocabulary) so standalone construction
 *   keeps the prior behavior.
 * @param busyTimeoutMs busy_timeout for Flyway's own connection and the integrity connection.
 * @param schemaMode [SchemaMode.MIGRATE] (default) or [SchemaMode.VALIDATE].
 */
class FlywayDatabaseSchemaManager(
    private val jdbcUrl: String,
    private val repair: Boolean = EnvBoolean.parse("FLYWAY_REPAIR", System.getenv("FLYWAY_REPAIR"), default = false),
    private val busyTimeoutMs: Long = 5000L,
    private val schemaMode: SchemaMode = SchemaMode.MIGRATE
) : DatabaseSchemaManager {
    private val logger = LoggerFactory.getLogger(FlywayDatabaseSchemaManager::class.java)

    /**
     * The single Flyway configuration used by migrate, validate and repair.
     *
     * - `cleanDisabled` is true so a stray `flyway.clean()` can never wipe a production database.
     * - `baselineOnMigrate` stays at Flyway's default (false): a non-empty schema without history is
     *   handled by [handleDirectModeDatabase] before Flyway runs, and baselining at 0 could never be
     *   correct because V1 is not idempotent.
     * - `ignoreMigrationPatterns("*:missing")` replaces Flyway's default `*:future`, so a database
     *   whose history is AHEAD of this binary fails validation (fail closed, AR-37) while an applied
     *   version with no local file below the latest is still tolerated.
     * - The DataSource is Flyway's own connection (see [flywayDataSource]).
     */
    internal fun flywayConfiguration(
        url: String = jdbcUrl,
        target: String? = null
    ): FluentConfiguration {
        val config =
            Flyway
                .configure()
                .dataSource(flywayDataSource(url))
                .locations(MIGRATION_LOCATION)
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .ignoreMigrationPatterns("*:missing")
        if (target != null) config.target(target)
        return config
    }

    /**
     * Flyway's dedicated SQLite DataSource (the application connection is never reused).
     *
     * - `busy_timeout` = [busyTimeoutMs] (otherwise the driver default of 3000 ms applies, AR-86).
     * - `foreign_keys` is explicitly OFF. Migrations recreate tables (V7 drops and recreates
     *   `work_items`); with foreign keys ON the DROP would cascade-delete child rows. The application
     *   connection keeps `foreign_keys = ON`; only Flyway's connection runs with it off.
     *
     * Note on the V9 header comment: it cannot be edited (checksum), so the correction about how
     * root_id and foreign keys are handled lives here and in the migration-review skill.
     */
    private fun flywayDataSource(url: String): SQLiteDataSource =
        SQLiteDataSource(
            SQLiteConfig().apply {
                enforceForeignKeys(false)
                setBusyTimeout(busyTimeoutMs.toInt())
            }
        ).also { it.url = url }

    override fun updateSchema(): Boolean {
        return try {
            withMigrationLock {
                if (!applySchemaPhase()) return@withMigrationLock false
                if (!repair) runIntegrity()
                true
            }
        } catch (e: FlywayException) {
            logger.error("Flyway migration failed: ${e.message}", e)
            false
        } catch (e: Exception) {
            logger.error("Database migration failed: ${e.message}", e)
            false
        }
    }

    /** Direct-mode handling, then repair / validate / migrate. Returns false after logging on refusal. */
    private fun applySchemaPhase(): Boolean {
        if (!handleDirectModeDatabase()) return false

        if (repair) {
            logger.info("FLYWAY_REPAIR=true detected, running repair...")
            return repair()
        }

        if (schemaMode == SchemaMode.VALIDATE) return validateOnly()

        logger.info("Starting Flyway database migration...")
        logger.info("Using database URL for Flyway: $jdbcUrl")

        val result = migrateWithBusyRetry()
        logger.info("Successfully applied ${result.migrationsExecuted} migration(s)")
        logger.info("Current schema version: ${result.targetSchemaVersion ?: "unknown"}")
        return true
    }

    private fun migrateWithBusyRetry(): MigrateResult {
        var attempt = 1
        while (true) {
            try {
                return flywayConfiguration().load().migrate()
            } catch (e: Exception) {
                if (attempt >= BUSY_RETRIES || !isBusy(e)) throw e
                logger.warn("Flyway migrate hit SQLITE_BUSY (attempt $attempt of $BUSY_RETRIES); retrying: ${e.message}")
                Thread.sleep(BUSY_BACKOFF_MS * attempt)
                attempt++
            }
        }
    }

    /** True when SQLITE_BUSY appears anywhere in the cause chain. */
    private fun isBusy(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 20) {
            if (t is SQLException && (t.errorCode and 0xFF) == 5) return true
            if (t.message?.contains("SQLITE_BUSY") == true) return true
            t = t.cause
            depth++
        }
        return false
    }

    /** SCHEMA_MODE=validate: no migrate, no baseline, nothing created. Pending or future versions fail. */
    private fun validateOnly(): Boolean {
        logger.info("SCHEMA_MODE=validate: validating schema without migrating")
        val flyway = flywayConfiguration().load()
        val info = flyway.info()
        if (info.applied().isEmpty()) {
            logger.error(
                "SCHEMA_MODE=validate: database has no applied migrations (empty or unmigrated). " +
                    "Run once with SCHEMA_MODE=migrate (the default) to create or upgrade the schema."
            )
            return false
        }
        flyway.validate()
        val pending = info.pending()
        if (pending.isNotEmpty()) {
            logger.error(
                "SCHEMA_MODE=validate: ${pending.size} pending migration(s) (" +
                    pending.joinToString(", ") { it.version.toString() } +
                    "). Run once with SCHEMA_MODE=migrate to apply them."
            )
            return false
        }
        logger.info("Schema validated: current version ${info.current()?.version}")
        return true
    }

    /**
     * Handles a database that holds user tables but no `flyway_schema_history` (a former Direct-mode
     * database). It is baselined at 17 ONLY when its sqlite_master matches a Flyway `target(17)`
     * reference exactly; otherwise the database is left unmodified and refused with the differing
     * objects and the remedy. In validate and repair modes it is always refused. Returns false on refusal.
     */
    private fun handleDirectModeDatabase(): Boolean {
        val needsHandling =
            DriverManager.getConnection(jdbcUrl).use { conn ->
                val names = userTableNames(conn)
                names.any { it != HISTORY_TABLE } && !names.contains(HISTORY_TABLE)
            }
        if (!needsHandling) return true

        if (repair) {
            logger.error("$DIRECT_MODE_REFUSAL_PREFIX FLYWAY_REPAIR does not apply to it. $DIRECT_MODE_REMEDY")
            return false
        }
        if (schemaMode == SchemaMode.VALIDATE) {
            logger.error("$DIRECT_MODE_REFUSAL_PREFIX SCHEMA_MODE=validate never baselines. $DIRECT_MODE_REMEDY")
            return false
        }

        val differences = diffAgainstReference()
        if (differences.isNotEmpty()) {
            logger.error(
                "$DIRECT_MODE_REFUSAL_PREFIX Its schema differs from the V17 shape in: " +
                    differences.joinToString(", ") + ". " + DIRECT_MODE_REMEDY
            )
            return false
        }

        logger.warn("Database has no flyway_schema_history but matches the V17 schema exactly; baselining at version 17.")
        flywayConfiguration()
            .baselineVersion("17")
            .baselineDescription("Baseline of a Flyway-shaped database that lost its history")
            .load()
            .baseline()
        return true
    }

    private fun userTableNames(conn: Connection): List<String> {
        val names = mutableListOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { rs ->
                while (rs.next()) names += rs.getString(1)
            }
        }
        return names
    }

    /** Object keys (type:name) whose (type,name,tbl_name,sql) row differs from, or is absent in, the V17 reference. */
    private fun diffAgainstReference(): List<String> {
        val refName = "flyway_ref_" + UUID.randomUUID().toString().replace("-", "")
        val refUrl = "jdbc:sqlite:file:$refName?mode=memory&cache=shared"
        // Hold one connection open so the shared-cache in-memory reference outlives Flyway's connections.
        DriverManager.getConnection(refUrl).use { holder ->
            flywayConfiguration(url = refUrl, target = "17").load().migrate()
            val reference = schemaRows(holder)
            val actual = DriverManager.getConnection(jdbcUrl).use { schemaRows(it) }
            val keys = (reference.keys + actual.keys).sorted()
            return keys.filter { reference[it] != actual[it] }
        }
    }

    private fun schemaRows(conn: Connection): Map<String, List<String?>> {
        val rows = linkedMapOf<String, List<String?>>()
        val sql =
            "SELECT type, name, tbl_name, sql FROM sqlite_master " +
                "WHERE name NOT LIKE 'sqlite_%' AND tbl_name != '$HISTORY_TABLE' ORDER BY type, name"
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    rows[rs.getString(1) + ":" + rs.getString(2)] =
                        listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
                }
            }
        }
        return rows
    }

    /** Runs the inventory, FTS integrity check and drift reports on one short-lived connection. */
    private fun runIntegrity() {
        val config = SQLiteConfig().apply { setBusyTimeout(busyTimeoutMs.toInt()) }
        DriverManager.getConnection(jdbcUrl, config.toProperties()).use { StartupIntegrity.run(it) }
    }

    /**
     * Repairs the Flyway schema history table by updating checksums
     * to match current migration files. Useful when migration files
     * have been modified after being applied.
     */
    private fun repair(): Boolean =
        try {
            logger.info("Starting Flyway repair...")

            flywayConfiguration().load().repair()
            logger.info("Flyway repair completed successfully")

            true
        } catch (e: FlywayException) {
            logger.error("Flyway repair failed: ${e.message}", e)
            false
        }

    /**
     * Runs [block] under the `<dbfile>.migrate.lock` OS file lock (no lock for in-memory URLs).
     * Polls `tryLock`; [OverlappingFileLockException] (this JVM already holds it) counts as held.
     * Logs INFO every 10 s while waiting and gives up after [LOCK_TIMEOUT_MS]. The lock file is
     * never deleted.
     */
    private fun <T> withMigrationLock(block: () -> T): T {
        val dbFile = StartupCompaction.resolveDbFile(jdbcUrl) ?: return block()
        val lockFile = File(dbFile.path + ".migrate.lock")
        lockFile.absoluteFile.parentFile?.mkdirs()

        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val start = System.nanoTime()
            var lastLog = start
            var lock: FileLock? = null
            while (lock == null) {
                lock =
                    try {
                        channel.tryLock()
                    } catch (e: OverlappingFileLockException) {
                        null
                    }
                if (lock != null) break
                val now = System.nanoTime()
                val waitedMs = (now - start) / 1_000_000
                if (waitedMs >= LOCK_TIMEOUT_MS) {
                    throw IllegalStateException(
                        "Timed out after ${waitedMs / 1000}s waiting for the migration lock ${lockFile.path}; " +
                            "another process is migrating this database. The lock file is never deleted; " +
                            "stop the other process and retry."
                    )
                }
                if ((now - lastLog) / 1_000_000 >= LOCK_LOG_INTERVAL_MS) {
                    logger.info("Waiting for migration lock ${lockFile.path} (${waitedMs / 1000}s elapsed)")
                    lastLog = now
                }
                Thread.sleep(LOCK_POLL_MS)
            }
            try {
                return block()
            } finally {
                runCatching { lock?.release() }
            }
        }
    }

    companion object {
        /** The only configured migration location. */
        const val MIGRATION_LOCATION = "classpath:db/migration/sqlite"
        internal const val HISTORY_TABLE = "flyway_schema_history"
        internal const val LOCK_TIMEOUT_MS = 300_000L
        private const val LOCK_POLL_MS = 100L
        private const val LOCK_LOG_INTERVAL_MS = 10_000L
        private const val BUSY_RETRIES = 3
        private const val BUSY_BACKOFF_MS = 500L

        private const val DIRECT_MODE_REFUSAL_PREFIX =
            "Database contains tables but no flyway_schema_history (created by the removed Direct mode, USE_FLYWAY=false)."
        private const val DIRECT_MODE_REMEDY =
            "The database was not modified. Point DATABASE_PATH at a new file, or restore a Flyway-mode backup. " +
                "Only a Flyway-shaped database that lost its history can be baselined automatically."
    }
}
