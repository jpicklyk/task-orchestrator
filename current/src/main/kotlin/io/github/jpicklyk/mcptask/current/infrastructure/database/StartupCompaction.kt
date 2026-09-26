package io.github.jpicklyk.mcptask.current.infrastructure.database

import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Outcome of a single [StartupCompaction.runOnce] call.
 *
 * - [COMPACTED] - the database was vacuumed, its FTS5 shadow tables rebuilt, and
 *   `PRAGMA user_version` was advanced to [StartupCompaction.COMPACTED_USER_VERSION].
 * - [ALREADY_COMPACTED] - `PRAGMA user_version` was already `>= 1`; nothing was done.
 * - [SKIPPED_NOT_FILE_DB] - the JDBC URL does not resolve to an on-disk SQLite file (an
 *   in-memory database, or a non-SQLite driver such as H2 used by tests); no connection was
 *   opened.
 * - [SKIPPED_INSUFFICIENT_DISK] - the free-disk precheck failed (fewer than 2x the current
 *   database + WAL size available); compaction was not attempted.
 * - [FAILED] - an exception was thrown at any step; `user_version` is left at `0` so the next
 *   boot retries.
 */
internal enum class CompactionOutcome {
    COMPACTED,
    ALREADY_COMPACTED,
    SKIPPED_NOT_FILE_DB,
    SKIPPED_INSUFFICIENT_DISK,
    FAILED,
}

/**
 * One-time startup compaction run after the V17 actor-proof scrub migration.
 *
 * V17 (`V17__Store_Actor_Proof_Evidence.sql`) stopped writing raw actor-proof evidence into
 * long-lived columns, but SQLite does not overwrite freed pages by default: rows deleted or
 * updated before that migration can leave stale copies of the old, sensitive column values
 * sitting in unused pages of the `.db` file (and in the WAL) until something reclaims them.
 * [runOnce] reclaims that space exactly once per database file:
 *
 * 1. Resolve the on-disk file behind [jdbcUrl]; if this is not a file-backed SQLite database
 *    (in-memory, or a non-SQLite JDBC URL used by tests), skip immediately without opening a
 *    connection.
 * 2. Open one raw, autocommit `DriverManager` connection (VACUUM cannot run inside a
 *    transaction or with other open statements on the same connection - see sqlite.org's
 *    `lang_vacuum` docs) and set `PRAGMA busy_timeout` on it.
 * 3. Read `PRAGMA user_version`; a value `>= 1` means this file was already compacted by a
 *    prior boot, so return immediately without touching anything else.
 * 4. Precheck free disk space: VACUUM needs headroom for a full temporary copy of the database
 *    (up to ~2x its current size, matching `dbFile` + `-wal`); skip rather than risk running
 *    out of disk mid-VACUUM.
 * 5. Checkpoint the WAL (`TRUNCATE`), `VACUUM`, rebuild every FTS5 shadow table in
 *    [FTS_TABLES] (VACUUM may renumber the rowids of tables without an explicit
 *    `INTEGER PRIMARY KEY` - including these external-content FTS5 tables - so a rebuild is a
 *    correctness requirement, not just hygiene) and verify each with an `integrity-check`,
 *    write `PRAGMA user_version = [COMPACTED_USER_VERSION]` as the last step so the gate is set
 *    iff every prior step succeeded, then checkpoint the WAL again.
 *
 * [runOnce] never throws: any exception at any step is caught, logged (message only - never row
 * content), and reported as [CompactionOutcome.FAILED]. Because the `user_version` write only
 * happens after every other step succeeds, a failed run leaves the gate at `0` so the very next
 * boot retries automatically.
 */
internal object StartupCompaction {
    /** `PRAGMA user_version` value written once compaction has fully succeeded. */
    const val COMPACTED_USER_VERSION = 1

    /**
     * The four FTS5 shadow tables created by V7 (`work_items_fts_trigram`, `work_items_fts_text`,
     * `notes_fts_trigram`, `notes_fts_text`). All four are external-content tables keyed on the
     * base tables' implicit rowid, so every one of them must be rebuilt after VACUUM potentially
     * renumbers those rowids.
     */
    val FTS_TABLES: List<String> =
        listOf(
            "work_items_fts_trigram",
            "work_items_fts_text",
            "notes_fts_trigram",
            "notes_fts_text",
        )

    private val logger = LoggerFactory.getLogger(StartupCompaction::class.java)

    /**
     * Runs the one-time compaction against [jdbcUrl], never throwing.
     *
     * @param jdbcUrl the JDBC URL DatabaseManager connected with. Only `jdbc:sqlite:` URLs that
     *   resolve to an on-disk file are eligible; in-memory SQLite URLs and non-SQLite URLs
     *   (e.g. `jdbc:h2:mem:...` used by tests) are skipped.
     * @param busyTimeoutMs `PRAGMA busy_timeout` set on the raw compaction connection, so a
     *   concurrent writer causes a bounded wait-then-fail instead of an indefinite block.
     * @param usableSpaceBytes injectable free-disk-space probe for the precheck, defaulting to
     *   [File.getUsableSpace]; overridable in tests to exercise [CompactionOutcome.SKIPPED_INSUFFICIENT_DISK]
     *   deterministically.
     */
    fun runOnce(
        jdbcUrl: String,
        busyTimeoutMs: Long = 5000L,
        usableSpaceBytes: (File) -> Long = { it.usableSpace },
    ): CompactionOutcome {
        val dbFile =
            try {
                resolveDbFile(jdbcUrl)
            } catch (e: Exception) {
                logger.warn("Startup compaction: failed to resolve database file from JDBC URL: ${e.message}")
                return CompactionOutcome.FAILED
            }

        if (dbFile == null) {
            logger.debug("Startup compaction: JDBC URL is not a file-backed SQLite database; skipping")
            return CompactionOutcome.SKIPPED_NOT_FILE_DB
        }

        return try {
            runCompaction(dbFile, busyTimeoutMs, usableSpaceBytes)
        } catch (e: Exception) {
            logger.warn("Startup compaction failed: ${e.message}")
            CompactionOutcome.FAILED
        }
    }

    private fun runCompaction(
        dbFile: File,
        busyTimeoutMs: Long,
        usableSpaceBytes: (File) -> Long,
    ): CompactionOutcome {
        Class.forName("org.sqlite.JDBC")
        // Open by the resolved file, not the caller's URL: a `?query` suffix is not part of the
        // filename for a plain (non-`file:`) sqlite-jdbc URL and would fail with SQLITE_CANTOPEN.
        DriverManager.getConnection("jdbc:sqlite:${dbFile.path}").use { connection: Connection ->
            connection.autoCommit = true

            connection.createStatement().use { stmt -> stmt.execute("PRAGMA busy_timeout = $busyTimeoutMs") }

            val currentUserVersion =
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA user_version").use { rs ->
                        if (rs.next()) rs.getInt(1) else 0
                    }
                }
            if (currentUserVersion >= COMPACTED_USER_VERSION) {
                return CompactionOutcome.ALREADY_COMPACTED
            }

            val walFile = File(dbFile.parentFile, "${dbFile.name}-wal")
            val currentSizeBytes = dbFile.length() + (if (walFile.exists()) walFile.length() else 0L)
            val parentDir = dbFile.parentFile ?: File(".")
            val freeBytes = usableSpaceBytes(parentDir)
            if (freeBytes < 2 * currentSizeBytes) {
                logger.warn(
                    "Startup compaction skipped: insufficient free disk space " +
                        "(need >= ${2 * currentSizeBytes} bytes, have $freeBytes bytes)",
                )
                return CompactionOutcome.SKIPPED_INSUFFICIENT_DISK
            }

            val startNanos = System.nanoTime()

            connection.createStatement().use { stmt -> stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            connection.createStatement().use { stmt -> stmt.execute("VACUUM") }

            for (table in FTS_TABLES) {
                connection.createStatement().use { stmt ->
                    stmt.execute("INSERT INTO $table($table) VALUES('rebuild')")
                }
                connection.createStatement().use { stmt ->
                    stmt.execute("INSERT INTO $table($table) VALUES('integrity-check')")
                }
            }

            connection.createStatement().use { stmt -> stmt.execute("PRAGMA user_version = $COMPACTED_USER_VERSION") }
            connection.createStatement().use { stmt -> stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)") }

            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            val afterSizeBytes = dbFile.length() + (if (walFile.exists()) walFile.length() else 0L)
            logger.info(
                "Startup compaction complete: $currentSizeBytes bytes -> $afterSizeBytes bytes in ${elapsedMs}ms",
            )

            return CompactionOutcome.COMPACTED
        }
    }

    /**
     * Resolves the on-disk [File] behind a `jdbc:sqlite:` URL, or `null` when the URL is not a
     * file-backed SQLite database (a different driver entirely, an in-memory database, or a
     * URL with no resolvable path).
     */
    private fun resolveDbFile(jdbcUrl: String): File? {
        if (!jdbcUrl.startsWith("jdbc:sqlite:")) return null

        var rest = jdbcUrl.removePrefix("jdbc:sqlite:")
        if (rest.startsWith("file:")) rest = rest.removePrefix("file:")

        // Strip a trailing `?query` (e.g. `?mode=memory`, `?cache=shared`) but inspect it first.
        val queryIndex = rest.indexOf('?')
        val query = if (queryIndex >= 0) rest.substring(queryIndex + 1) else null
        val path = if (queryIndex >= 0) rest.substring(0, queryIndex) else rest

        if (path.isBlank()) return null
        if (path == ":memory:") return null
        if (query != null && query.split('&').any { it.equals("mode=memory", ignoreCase = true) }) return null

        return File(path)
    }
}
