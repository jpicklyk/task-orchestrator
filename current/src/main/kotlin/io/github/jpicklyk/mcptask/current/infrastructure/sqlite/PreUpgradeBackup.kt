package io.github.jpicklyk.mcptask.current.infrastructure.sqlite

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Outcome of one [PreUpgradeBackup.run] call. Only [Failed] stops startup. */
sealed interface BackupOutcome {
    /** The JDBC URL is not an on-disk SQLite file (in-memory or another driver); nothing was opened. */
    data object SkippedNotFileDb : BackupOutcome

    /** The file is missing, empty, or holds no user tables: a brand-new database has nothing to protect. */
    data object SkippedFreshDatabase : BackupOutcome

    /** The stored schema version is already >= [PreUpgradeBackup.FIRST_UNPROTECTED_VERSION], or the binary would not cross it. */
    data object SkippedAlreadyUpgraded : BackupOutcome

    /** `UPGRADE_BACKUP=false` and a copy would otherwise have been taken (one WARN was logged). */
    data object SkippedDisabled : BackupOutcome

    /** The binary's latest classpath migration could not be determined, so there is no upgrade to protect. */
    data object SkippedNoMigration : BackupOutcome

    /** `FLYWAY_REPAIR` or `SCHEMA_MODE=validate`: neither migrates, so neither copies. */
    data object SkippedSchemaMode : BackupOutcome

    /** The verified backup was written to [path]. */
    data class Copied(
        val path: File
    ) : BackupOutcome

    /** The backup could not be taken or verified; [detail] is the startup failure message (no row content). */
    data class Failed(
        val detail: String
    ) : BackupOutcome
}

/**
 * Pre-upgrade safety copy of a 3.x database file, taken before Flyway applies the first 4.0 migration.
 *
 * Eligible only when the stored `flyway_schema_history` max successful version is `<= 17` (a Direct-mode 3.x file with
 * user tables and no history table counts as `<= 17`) and the binary's latest classpath migration is `>= 18`.
 * Check order: not a file URL, fresh database, already upgraded, disabled, free space, checkpoint, copy, verify.
 *
 * The copy goes to `<db>.pre-4.0.<yyyyMMddTHHmmssZ>.bak` beside the database (UTC, [now]) and is created with
 * CREATE_NEW: an existing path is a [BackupOutcome.Failed], never an overwrite. A copy that fails verification
 * (`PRAGMA integrity_check` = `ok` and the same max `flyway_schema_history` version as the source) is deleted.
 * [run] never throws.
 */
internal object PreUpgradeBackup {
    /** The first schema version a pre-4.0 file does not yet have; stored versions below it are backed up. */
    const val FIRST_UNPROTECTED_VERSION = 18

    private val logger = LoggerFactory.getLogger(PreUpgradeBackup::class.java)
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun run(
        jdbcUrl: String,
        enabled: Boolean,
        targetVersion: Int,
        busyTimeoutMs: Long = 5000L,
        usableSpaceBytes: (File) -> Long = { it.usableSpace },
        now: () -> Instant = { Instant.now() },
    ): BackupOutcome {
        val dbFile = StartupCompaction.resolveDbFile(jdbcUrl) ?: return BackupOutcome.SkippedNotFileDb
        return try {
            runBackup(jdbcUrl, dbFile, enabled, targetVersion, busyTimeoutMs, usableSpaceBytes, now)
        } catch (e: Exception) {
            fail("${e.message}")
        }
    }

    private fun runBackup(
        jdbcUrl: String,
        dbFile: File,
        enabled: Boolean,
        targetVersion: Int,
        busyTimeoutMs: Long,
        usableSpaceBytes: (File) -> Long,
        now: () -> Instant,
    ): BackupOutcome {
        if (!dbFile.isFile || dbFile.length() == 0L) return BackupOutcome.SkippedFreshDatabase
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { it.execute("PRAGMA busy_timeout = $busyTimeoutMs") }

            if (!hasUserTables(connection)) return BackupOutcome.SkippedFreshDatabase
            val stored = maxSuccessfulVersion(connection)
            if ((stored != null && stored >= FIRST_UNPROTECTED_VERSION) || targetVersion < FIRST_UNPROTECTED_VERSION) {
                return BackupOutcome.SkippedAlreadyUpgraded
            }
            if (!enabled) {
                logger.warn(
                    "UPGRADE_BACKUP=false: skipping the pre-upgrade backup of ${dbFile.path}; " +
                        "the schema is about to be migrated from ${stored ?: "unversioned"} to $targetVersion",
                )
                return BackupOutcome.SkippedDisabled
            }

            val walFile = File(dbFile.parentFile, "${dbFile.name}-wal")
            val needed = dbFile.length() + (if (walFile.exists()) walFile.length() else 0L)
            val parentDir = dbFile.absoluteFile.parentFile ?: File(".")
            val free = usableSpaceBytes(parentDir)
            if (free < needed) {
                return fail("insufficient free space in ${parentDir.path} (need >= $needed bytes, have $free bytes)")
            }

            if (checkpointBusy(connection)) {
                return fail("WAL checkpoint reported busy (a concurrent reader holds un-checkpointed WAL frames)")
            }

            val target = File(parentDir, "${dbFile.name}.pre-4.0.${STAMP.format(now())}.bak")
            try {
                Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                    Files.copy(dbFile.toPath(), out)
                }
            } catch (e: FileAlreadyExistsException) {
                return fail("backup path already exists, refusing to overwrite: ${target.path}")
            } catch (e: Exception) {
                runCatching { target.delete() }
                return fail("copy to ${target.path} failed: ${e.message}")
            }

            val problem = verify(target, stored, busyTimeoutMs)
            if (problem != null) {
                runCatching { target.delete() }
                return fail("verification of ${target.path} failed: $problem")
            }
            logger.info("Pre-upgrade backup written: ${target.path} (schema version ${stored ?: "unversioned"})")
            return BackupOutcome.Copied(target)
        }
    }

    private fun fail(reason: String): BackupOutcome.Failed {
        val detail = "Pre-upgrade backup failed: $reason. Set UPGRADE_BACKUP=false to skip the backup and migrate without one."
        logger.error(detail)
        return BackupOutcome.Failed(detail)
    }

    private fun verify(
        copy: File,
        sourceVersion: Int?,
        busyTimeoutMs: Long,
    ): String? =
        try {
            DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { c ->
                c.autoCommit = true
                c.createStatement().use { it.execute("PRAGMA busy_timeout = $busyTimeoutMs") }
                val integrity =
                    c.createStatement().use { s ->
                        s.executeQuery("PRAGMA integrity_check").use { rs -> if (rs.next()) rs.getString(1) else null }
                    }
                val copied = maxSuccessfulVersion(c)
                when {
                    integrity != "ok" -> "integrity_check returned '$integrity'"
                    copied != sourceVersion -> "schema version $copied does not match the source's $sourceVersion"
                    else -> null
                }
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }

    private fun hasUserTables(c: Connection): Boolean =
        c.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { rs ->
                rs.next() && rs.getInt(1) > 0
            }
        }

    /** Max successful `flyway_schema_history` version, or null when the table is absent or has no versioned row. */
    private fun maxSuccessfulVersion(c: Connection): Int? {
        val hasHistory =
            c.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'flyway_schema_history'").use { rs ->
                    rs.next() && rs.getInt(1) > 0
                }
            }
        if (!hasHistory) return null
        return c.createStatement().use { s ->
            s
                .executeQuery(
                    "SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL"
                ).use { rs ->
                    if (rs.next()) rs.getInt(1).takeUnless { rs.wasNull() } else null
                }
        }
    }

    private fun checkpointBusy(c: Connection): Boolean =
        c.createStatement().use { s ->
            s.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { rs -> rs.next() && rs.getInt(1) != 0 }
        }
}
