package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.BackupOutcome
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.PreUpgradeBackup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item ff08e631 (P7): the pre-upgrade backup of a 3.x database.
 *
 * Oracles (frozen in the item's `test-plan` and `task-scope`): O1 plan S12 / task-scope (a); O2 task-scope D1-D5;
 * O3 the `EnvBoolean.parse` contract; O5 SQLite: `PRAGMA integrity_check` returns the single row `ok`.
 * Every scenario here is NEW-SURFACE. Scenario ids follow the test-plan: S1, S3, S4, S5, S7-S14 live here;
 * S6 is in `UpgradeBackupOutcomeTest` and S2 is appended to `GoldenV17UpgradeTest`.
 *
 * Fixtures: the checked-in golden V17 database ([GoldenV17.copyTo]) and [UpgradeHarness.copyAt] (a database migrated
 * to exactly a version, with a Flyway history table whose max successful version is that version).
 */
class PreUpgradeBackupTest {
    @TempDir
    lateinit var dir: File

    /** The injected clock: every `.bak` name in these tests derives from it (UTC). */
    private val fixedInstant: Instant = Instant.parse("2026-03-04T05:06:07Z")
    private val fixedStamp = "20260304T050607Z"

    /** A target version far above V17, as any 4.0 binary carries. */
    private val latestTarget = 37

    private fun golden(
        folder: File = dir,
        name: String = "golden.sqlite"
    ): File {
        folder.mkdirs()
        return GoldenV17.copyTo(File(folder, name))
    }

    private fun run(
        url: String,
        enabled: Boolean = true,
        target: Int = latestTarget,
        space: (File) -> Long = { Long.MAX_VALUE },
        at: Instant = fixedInstant
    ): BackupOutcome =
        PreUpgradeBackup.run(
            jdbcUrl = url,
            enabled = enabled,
            targetVersion = target,
            usableSpaceBytes = space,
            now = { at }
        )

    private fun bakNamePattern(dbName: String): Regex = Regex("^" + Regex.escape(dbName) + "\\.pre-4\\.0\\.\\d{8}T\\d{6}Z\\.bak$")

    /** Every `<dbName>.pre-4.0.<stamp>.bak` beside the database (the `-wal`/`-shm` side files never match). */
    private fun baks(
        dbFile: File,
        dbName: String = dbFile.name
    ): List<File> {
        val pattern = bakNamePattern(dbName)
        return (dbFile.parentFile.listFiles() ?: emptyArray()).filter { pattern.matches(it.name) }.sortedBy { it.name }
    }

    private fun <T> withConn(
        file: File,
        block: (Connection) -> T
    ): T = DriverManager.getConnection(UpgradeHarness.urlFor(file)).use(block)

    private fun dumpOf(file: File): UpgradeHarness.Dump = withConn(file) { UpgradeHarness.dump(it) }

    private fun integrity(file: File): List<String> =
        withConn(file) { c ->
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA integrity_check").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    /** Max successful migration version recorded in the file's Flyway history, null when there is no history table. */
    private fun historyMax(file: File): Int? =
        withConn(file) { c ->
            val hasHistory =
                c.createStatement().use { st ->
                    st
                        .executeQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'flyway_schema_history'")
                        .use { rs ->
                            rs.next()
                            rs.getInt(1) > 0
                        }
                }
            if (!hasHistory) {
                null
            } else {
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "SELECT max(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL"
                        ).use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                }
            }
        }

    /** Captures WARN records naming `UPGRADE_BACKUP`, from whichever logger emits them. */
    private fun upgradeBackupWarns(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender =
            ListAppender<ILoggingEvent>().also {
                it.start()
                root.addAppender(it)
            }
        try {
            block()
            return appender.list
                .filter { it.level == Level.WARN && it.formattedMessage.contains("UPGRADE_BACKUP") }
                .map { it.formattedMessage }
        } finally {
            root.detachAppender(appender)
        }
    }

    // ------------------------------------------------------------------
    // S1 -- happy: an eligible 3.x file is copied, verified and named from the injected UTC instant
    // ------------------------------------------------------------------

    @Test
    fun `S1 golden V17 with a later target is copied to a UTC-stamped bak whose content equals the source`() {
        val db = golden()
        val before = dumpOf(db)
        assertEquals(17, historyMax(db), "fixture: the golden is at V17")

        val outcome = run(UpgradeHarness.urlFor(db))

        assertTrue(outcome is BackupOutcome.Copied, "expected Copied, got $outcome")
        val siblings = baks(db)
        assertEquals(1, siblings.size, "exactly one bak beside the database, got ${siblings.map { it.name }}")
        val bak = siblings.single()
        assertEquals("golden.sqlite.pre-4.0.$fixedStamp.bak", bak.name, "the stamp is the injected instant in UTC")
        assertEquals(bak.canonicalFile, outcome.path.canonicalFile, "Copied reports the file it wrote")
        assertEquals(listOf("ok"), integrity(bak), "the copy passes PRAGMA integrity_check")
        assertEquals(17, historyMax(bak), "the copy's flyway history max equals the source's")
        assertEquals(before, dumpOf(bak), "every row of the copy equals the source")
        assertEquals(before, dumpOf(db), "the live database is untouched")
        assertEquals(17, historyMax(db))
    }

    // ------------------------------------------------------------------
    // S3 -- env: UPGRADE_BACKUP parses through EnvBoolean, default true
    // ------------------------------------------------------------------

    @Test
    fun `S3 UPGRADE_BACKUP defaults to true and accepts the EnvBoolean vocabulary`() {
        fun parsed(raw: String?): Boolean = AppConfig.fromEnv { name -> if (name == "UPGRADE_BACKUP") raw else null }.upgradeBackup

        assertTrue(parsed(null), "unset defaults to true")
        for (off in listOf("false", "0", "no", " FALSE ", "No")) {
            assertFalse(parsed(off), "'$off' disables the backup")
        }
        for (on in listOf("true", "1", "yes", "TRUE", " Yes ")) {
            assertTrue(parsed(on), "'$on' enables the backup")
        }
        assertTrue(parsed("maybe"), "an unrecognized value falls back to the default (true)")
        assertTrue(parsed(""), "the empty string is unrecognized, so it falls back to the default (true)")
    }

    @Test
    fun `S3 DatabaseManager honours the UPGRADE_BACKUP setting on an eligible file`() {
        val offDb = golden(File(dir, "off"))
        val offManager = DatabaseManager(appConfig = AppConfig.fromEnv { name -> if (name == "UPGRADE_BACKUP") "false" else null })
        try {
            assertTrue(offManager.initialize(UpgradeHarness.urlFor(offDb)))
            assertEquals(BackupOutcome.SkippedDisabled, offManager.runPreUpgradeBackup())
        } finally {
            offManager.shutdown()
        }
        assertTrue(baks(offDb).isEmpty(), "UPGRADE_BACKUP=false writes no bak")

        val onDb = golden(File(dir, "on"))
        val onManager = DatabaseManager(appConfig = AppConfig.fromEnv { null })
        try {
            assertTrue(onManager.initialize(UpgradeHarness.urlFor(onDb)))
            val outcome = onManager.runPreUpgradeBackup()
            assertTrue(outcome is BackupOutcome.Copied, "default config copies an eligible file, got $outcome")
        } finally {
            onManager.shutdown()
        }
        assertEquals(1, baks(onDb).size)
    }

    // ------------------------------------------------------------------
    // S4 -- failure: insufficient free space fails before any copy; equality proceeds
    // ------------------------------------------------------------------

    @Test
    fun `S4 free space one byte below the database size fails without writing a bak`() {
        val db = golden()
        val size = db.length()
        assertFalse(File(db.path + "-wal").exists(), "fixture: no wal file, so the required space is the file length")
        val before = dumpOf(db)

        val outcome = run(UpgradeHarness.urlFor(db), space = { size - 1 })

        assertTrue(outcome is BackupOutcome.Failed, "expected Failed, got $outcome")
        assertTrue(outcome.detail.contains("space", ignoreCase = true), "the detail names free space: ${outcome.detail}")
        assertTrue(baks(db).isEmpty(), "no bak is written when space is short")
        assertEquals(before, dumpOf(db), "the live database is untouched")
    }

    @Test
    fun `S4 free space exactly equal to the database size proceeds to a copy`() {
        val db = golden()
        val size = db.length()

        val outcome = run(UpgradeHarness.urlFor(db), space = { size })

        assertTrue(outcome is BackupOutcome.Copied, "equality proceeds, got $outcome")
        assertEquals(1, baks(db).size)
    }

    @Test
    fun `S4 the required space includes the wal file`() {
        val db = golden()
        val conn = DriverManager.getConnection(UpgradeHarness.urlFor(db))
        try {
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA wal_autocheckpoint=0")
                st.execute("CREATE TABLE wal_space_probe(v TEXT)")
                st.execute("INSERT INTO wal_space_probe(v) VALUES ('x')")
            }
            val wal = File(db.path + "-wal")
            assertTrue(wal.length() > 0, "fixture: the wal holds committed pages, so file+wal exceeds the file alone")
            val fileOnly = db.length()

            val short = run(UpgradeHarness.urlFor(db), space = { fileOnly })

            assertTrue(short is BackupOutcome.Failed, "free == file length is short once the wal counts, got $short")
            assertTrue(baks(db).isEmpty())

            val enough = run(UpgradeHarness.urlFor(db), space = { fileOnly + wal.length() + (1L shl 20) })
            assertTrue(enough is BackupOutcome.Copied, "ample space proceeds, got $enough")
        } finally {
            conn.close()
        }
    }

    // ------------------------------------------------------------------
    // S5 / S7 -- failure: never overwrite whatever is already at the bak path
    // ------------------------------------------------------------------

    @Test
    fun `S5 an empty directory at the exact bak path fails and is left alone`() {
        val db = golden()
        val bakPath = File(dir, "golden.sqlite.pre-4.0.$fixedStamp.bak")
        assertTrue(bakPath.mkdir(), "fixture: a directory squats on the bak path")

        val outcome = run(UpgradeHarness.urlFor(db))

        assertTrue(outcome is BackupOutcome.Failed, "expected Failed, got $outcome")
        assertTrue(outcome.detail.contains("already exists"), "the detail names the collision: ${outcome.detail}")
        assertTrue(bakPath.isDirectory, "the directory is untouched")
        assertEquals(0, bakPath.list()?.size, "and still empty")
    }

    @Test
    fun `S7 a regular file already at the bak path fails and its bytes are unchanged`() {
        val db = golden()
        val bakPath = File(dir, "golden.sqlite.pre-4.0.$fixedStamp.bak")
        val precious = "do not overwrite me".toByteArray()
        bakPath.writeBytes(precious)

        val outcome = run(UpgradeHarness.urlFor(db))

        assertTrue(outcome is BackupOutcome.Failed, "expected Failed, got $outcome")
        assertTrue(outcome.detail.contains("already exists"), "the detail names the collision: ${outcome.detail}")
        assertTrue(precious.contentEquals(bakPath.readBytes()), "the pre-existing file is byte-for-byte unchanged")
        assertEquals(1, baks(db).size, "no second bak appears")
    }

    // ------------------------------------------------------------------
    // S8 -- edge: a database with nothing to protect is skipped as fresh
    // ------------------------------------------------------------------

    @Test
    fun `S8 a missing path is a fresh database and writes no bak`() {
        val missing = File(dir, "missing.sqlite")
        assertFalse(missing.exists(), "fixture: the path does not exist")

        val outcome = run(UpgradeHarness.urlFor(missing))

        assertEquals(BackupOutcome.SkippedFreshDatabase, outcome)
        assertTrue(baks(missing).isEmpty())
    }

    @Test
    fun `S8 a zero-byte file is a fresh database and writes no bak`() {
        val empty = File(dir, "empty.sqlite")
        assertTrue(empty.createNewFile())
        assertEquals(0L, empty.length(), "fixture: zero bytes")

        val outcome = run(UpgradeHarness.urlFor(empty))

        assertEquals(BackupOutcome.SkippedFreshDatabase, outcome)
        assertTrue(baks(empty).isEmpty())
    }

    @Test
    fun `S8 a database file with no user tables is a fresh database and writes no bak`() {
        val hollow = File(dir, "hollow.sqlite")
        withConn(hollow) { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE scratch(a)")
                st.execute("DROP TABLE scratch")
            }
        }
        assertTrue(hollow.length() > 0, "fixture: a real sqlite file, not zero bytes")
        assertTrue(withConn(hollow) { UpgradeHarness.userTables(it) }.isEmpty(), "fixture: no user tables")

        val outcome = run(UpgradeHarness.urlFor(hollow))

        assertEquals(BackupOutcome.SkippedFreshDatabase, outcome)
        assertTrue(baks(hollow).isEmpty())
    }

    // ------------------------------------------------------------------
    // S9 -- edge: the stored-version / target-version boundary around 18
    // ------------------------------------------------------------------

    @Test
    fun `S9 a database already at V18 is skipped as already upgraded`() {
        val db = File(dir, "v18.sqlite")
        val url = UpgradeHarness.copyAt(18, db)
        assertEquals(18, historyMax(db))

        assertEquals(BackupOutcome.SkippedAlreadyUpgraded, run(url, target = latestTarget))
        assertEquals(BackupOutcome.SkippedAlreadyUpgraded, run(url, target = 18))
        assertTrue(baks(db).isEmpty())
    }

    @Test
    fun `S9 a V17 database whose target is 17 has no migration to protect against`() {
        val db = File(dir, "v17.sqlite")
        val url = UpgradeHarness.copyAt(17, db)
        assertEquals(17, historyMax(db))

        assertEquals(BackupOutcome.SkippedAlreadyUpgraded, run(url, target = 17))
        assertTrue(baks(db).isEmpty())
    }

    @Test
    fun `S9 a V17 database whose target is 18 is copied`() {
        val db = File(dir, "v17.sqlite")
        val url = UpgradeHarness.copyAt(17, db)

        val outcome = run(url, target = 18)

        assertTrue(outcome is BackupOutcome.Copied, "17 stored, 18 target is the smallest eligible pair, got $outcome")
        assertEquals(1, baks(db).size)
        assertEquals(17, historyMax(baks(db).single()))
    }

    @Test
    fun `S9 a database well above V18 is skipped as already upgraded`() {
        val db = File(dir, "v26.sqlite")
        val url = UpgradeHarness.copyAt(26, db)

        assertEquals(BackupOutcome.SkippedAlreadyUpgraded, run(url, target = latestTarget))
        assertTrue(baks(db).isEmpty())
    }

    // ------------------------------------------------------------------
    // S10 -- edge: disabled skips with exactly one WARN, and only when a copy would otherwise be taken
    // ------------------------------------------------------------------

    @Test
    fun `S10 an eligible file with the backup disabled is skipped with exactly one UPGRADE_BACKUP warning`() {
        val db = golden()
        val url = UpgradeHarness.urlFor(db)
        var outcome: BackupOutcome? = null

        val warns = upgradeBackupWarns { outcome = run(url, enabled = false) }

        assertEquals(BackupOutcome.SkippedDisabled, outcome)
        assertEquals(1, warns.size, "exactly one WARN naming UPGRADE_BACKUP, got $warns")
        assertTrue(baks(db).isEmpty(), "no bak when disabled")
    }

    @Test
    fun `S10 a disabled backup on a database that needs none logs no warning`() {
        val upgraded = File(dir, "v18.sqlite")
        val upgradedUrl = UpgradeHarness.copyAt(18, upgraded)
        val missingUrl = UpgradeHarness.urlFor(File(dir, "never-created.sqlite"))
        val outcomes = mutableListOf<BackupOutcome>()

        val warns =
            upgradeBackupWarns {
                outcomes += run(upgradedUrl, enabled = false)
                outcomes += run(missingUrl, enabled = false)
            }

        assertEquals(listOf(BackupOutcome.SkippedAlreadyUpgraded, BackupOutcome.SkippedFreshDatabase), outcomes)
        assertTrue(warns.isEmpty(), "no WARN when no copy would have been taken, got $warns")
    }

    // ------------------------------------------------------------------
    // S11 -- edge: a URL that is not a file-backed sqlite database
    // ------------------------------------------------------------------

    @Test
    fun `S11 an in-memory sqlite URL is not a file database`() {
        assertEquals(BackupOutcome.SkippedNotFileDb, run("jdbc:sqlite::memory:"))
    }

    @Test
    fun `S11 a URL of another driver is not a file database`() {
        assertEquals(BackupOutcome.SkippedNotFileDb, run("jdbc:postgresql://localhost:5432/tasks"))
    }

    // ------------------------------------------------------------------
    // S12 -- edge: committed pages still in the wal are in the copy
    // ------------------------------------------------------------------

    @Test
    fun `S12 a committed row that lives only in the wal of a still-open connection is in the copy`() {
        val db = golden()
        val conn = DriverManager.getConnection(UpgradeHarness.urlFor(db))
        try {
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA wal_autocheckpoint=0")
                st.execute("CREATE TABLE wal_only_probe(v TEXT)")
                st.execute("INSERT INTO wal_only_probe(v) VALUES ('committed-in-wal')")
            }
            assertTrue(File(db.path + "-wal").length() > 0, "fixture: the committed row is in the wal, not yet in the main file")

            val outcome = run(UpgradeHarness.urlFor(db))

            assertTrue(outcome is BackupOutcome.Copied, "expected Copied, got $outcome")
            val bak = baks(db).single()
            val rows = dumpOf(bak).getValue("wal_only_probe").values.map { it["v"] }
            assertEquals(listOf<Any?>("committed-in-wal"), rows, "the wal content reached the copy")
            assertEquals(listOf("ok"), integrity(bak))
        } finally {
            conn.close()
        }
    }

    // ------------------------------------------------------------------
    // S13 -- edge: a Direct-mode 3.x file (tables, no flyway_schema_history) counts as <= 17
    // ------------------------------------------------------------------

    @Test
    fun `S13 a V17-shaped database without a history table is copied`() {
        val db = File(dir, "direct.sqlite")
        val url = UpgradeHarness.copyAt(17, db)
        withConn(db) { c -> c.createStatement().use { it.execute("DROP TABLE flyway_schema_history") } }
        assertEquals(null, historyMax(db), "fixture: no history table")
        val before = dumpOf(db)

        val outcome = run(url)

        assertTrue(outcome is BackupOutcome.Copied, "a Direct-mode 3.x file is about to be baselined and migrated, got $outcome")
        val bak = baks(db).single()
        assertEquals(before, dumpOf(bak))
        assertEquals(listOf("ok"), integrity(bak))
    }

    // ------------------------------------------------------------------
    // S14 -- edge: repair runs and validate mode never copy
    // ------------------------------------------------------------------

    @Test
    fun `S14 FLYWAY_REPAIR on an eligible file writes no bak`() {
        val db = golden()
        val manager = DatabaseManager(appConfig = AppConfig.fromEnv { name -> if (name == "FLYWAY_REPAIR") "true" else null })
        try {
            assertTrue(manager.initialize(UpgradeHarness.urlFor(db)))
            val outcome = manager.runPreUpgradeBackup()
            assertEquals(BackupOutcome.SkippedSchemaMode, outcome)
        } finally {
            manager.shutdown()
        }
        assertTrue(baks(db).isEmpty(), "a repair run migrates nothing, so it copies nothing")
    }

    @Test
    fun `S14 SCHEMA_MODE validate on an eligible file writes no bak`() {
        val db = golden()
        val before = dumpOf(db)
        val manager = DatabaseManager(appConfig = AppConfig.fromEnv { name -> if (name == "SCHEMA_MODE") "validate" else null })
        try {
            assertTrue(manager.initialize(UpgradeHarness.urlFor(db)))
            // Amendment A1 oracle: SCHEMA_MODE=validate on an eligible file yields SkippedSchemaMode.
            assertEquals(BackupOutcome.SkippedSchemaMode, manager.runPreUpgradeBackup())
        } finally {
            manager.shutdown()
        }
        assertTrue(baks(db).isEmpty(), "validate mode migrates nothing, so it copies nothing")
        assertEquals(before, dumpOf(db))
    }

    // ------------------------------------------------------------------
    // Adversarial probes (test-author skill, section 6)
    // ------------------------------------------------------------------

    @Test
    fun `probe a database directory containing a space gets its bak beside it`() {
        val db = golden(File(dir, "dir with space"))

        val outcome = run(UpgradeHarness.urlFor(db))

        assertTrue(outcome is BackupOutcome.Copied, "got $outcome")
        assertEquals("golden.sqlite.pre-4.0.$fixedStamp.bak", baks(db).single().name)
        assertEquals(db.parentFile.canonicalFile, baks(db).single().parentFile.canonicalFile)
    }

    @Test
    fun `probe a database file name without an extension keeps its whole name as the bak prefix`() {
        val db = golden(name = "tasksdb")

        val outcome = run(UpgradeHarness.urlFor(db))

        assertTrue(outcome is BackupOutcome.Copied, "got $outcome")
        assertEquals("tasksdb.pre-4.0.$fixedStamp.bak", baks(db).single().name)
    }

    @Test
    fun `probe a URL with a journal_mode query still resolves the file and names the bak after the file only`() {
        val db = golden()

        val outcome = run(UpgradeHarness.urlFor(db) + "?journal_mode=WAL")

        assertTrue(outcome is BackupOutcome.Copied, "got $outcome")
        assertEquals("golden.sqlite.pre-4.0.$fixedStamp.bak", baks(db).single().name, "the query string is not part of the file name")
        assertEquals(listOf("ok"), integrity(baks(db).single()))
    }

    @Test
    fun `probe the stamp is zero-padded UTC and follows the injected instant`() {
        val db = golden()

        val outcome = run(UpgradeHarness.urlFor(db), at = Instant.parse("2027-01-02T03:04:05Z"))

        assertTrue(outcome is BackupOutcome.Copied, "got $outcome")
        assertEquals("golden.sqlite.pre-4.0.20270102T030405Z.bak", baks(db).single().name)
    }

    @Test
    fun `probe replay at later instants leaves two valid baks`() {
        val db = golden()
        val url = UpgradeHarness.urlFor(db)
        val before = dumpOf(db)

        val first = run(url, at = fixedInstant)
        val second = run(url, at = fixedInstant.plusSeconds(1))

        assertTrue(first is BackupOutcome.Copied, "got $first")
        assertTrue(second is BackupOutcome.Copied, "got $second")
        val siblings = baks(db)
        assertEquals(2, siblings.size, "got ${siblings.map { it.name }}")
        for (bak in siblings) {
            assertEquals(listOf("ok"), integrity(bak))
            assertEquals(before, dumpOf(bak))
        }
    }

    @Test
    fun `probe replay at the same instant fails the second run and keeps the first bak intact`() {
        val db = golden()
        val url = UpgradeHarness.urlFor(db)
        val before = dumpOf(db)

        val first = run(url)
        val second = run(url)

        assertTrue(first is BackupOutcome.Copied, "got $first")
        assertTrue(second is BackupOutcome.Failed, "a name collision must fail, never overwrite; got $second")
        assertTrue(second.detail.contains("already exists"), "the detail names the collision: ${second.detail}")
        val bak = baks(db).single()
        assertEquals(before, dumpOf(bak), "the first bak is still the verified copy")
    }
}
