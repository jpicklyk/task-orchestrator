package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import ch.qos.logback.classic.Level
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.BackupOutcome
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.SchemaStartupOutcome
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.FlywayDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.MigrationChain
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.at
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.captureLogs
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item 126ec583 (W7 review N3, P7 lineage): the `UPGRADE_BACKUP` copy and the
 * Flyway migration run under ONE acquisition of `<db>.migrate.lock` ([DatabaseManager.backupAndUpdateSchema]).
 *
 * Oracles (frozen in the item's `test-plan` and `task-scope`): O1 the carry-in acceptance (a second process cannot
 * migrate between the copy and the chain; a racing second boot waits, then sees V>=18); O2 the task-scope Design and
 * AC1-AC5 (backup failure -> [SchemaStartupOutcome.BackupFailed] with `updateSchema()` never reached and the lock
 * released; lock unavailable -> [SchemaStartupOutcome.SchemaUpdateFailed] with no backup and an ERROR log; lock
 * reentrancy confined to the active acquisition; an uninitialized manager never throws and takes no lock); O3 the
 * `fleet-deployment.md` "Migration lock" bullet and upgrade runbook.
 *
 * Every scenario here is NEW-SURFACE (it binds to `backupAndUpdateSchema` / `SchemaStartupOutcome` /
 * `migrationLockTimeoutMs`). S8 is the unmodified existing suites named in the plan; nothing here edits them.
 *
 * Fixtures: the checked-in golden V17 database ([GoldenV17.copyTo]) in a [TempDir]. An external lock holder is a
 * `RandomAccessFile(<db>.migrate.lock).channel.lock()` taken on the test thread; a same-JVM `tryLock` on the same file
 * from any other channel throws `OverlappingFileLockException`, which the lock under test treats as "held".
 * The migrated end state is derived from the migration folder ([MigrationChain]), not from the implementation.
 */
class UpgradeBackupLockTest {
    @TempDir
    lateinit var dir: File

    private val managers = mutableListOf<DatabaseManager>()

    @AfterEach
    fun tearDown() {
        managers.forEach { runCatching { it.shutdown() } }
        managers.clear()
    }

    // ---------------------------------------------------------------- helpers

    private fun golden(
        folder: File = dir,
        name: String = "golden.sqlite"
    ): File {
        folder.mkdirs()
        return GoldenV17.copyTo(File(folder, name))
    }

    private fun lockFileFor(db: File) = File(db.path + ".migrate.lock")

    private fun manager(
        timeoutMs: Long,
        env: Map<String, String> = emptyMap()
    ): DatabaseManager {
        val m = DatabaseManager(appConfig = AppConfig.fromEnv { key -> env[key] }, migrationLockTimeoutMs = timeoutMs)
        managers += m
        return m
    }

    private fun initialized(
        db: File,
        timeoutMs: Long,
        env: Map<String, String> = emptyMap()
    ): DatabaseManager {
        val m = manager(timeoutMs, env)
        assertTrue(m.initialize(UpgradeHarness.urlFor(db)), "fixture: initialize must accept ${db.name}")
        return m
    }

    /** Every `<db>.pre-4.0.<stamp>.bak` beside the database. */
    private fun baks(db: File): List<File> {
        val pattern = Regex("^" + Regex.escape(db.name) + "\\.pre-4\\.0\\.\\d{8}T\\d{6}Z\\.bak$")
        return (db.parentFile.listFiles() ?: emptyArray()).filter { pattern.matches(it.name) }.sortedBy { it.name }
    }

    private fun <T> withConn(
        file: File,
        block: (Connection) -> T
    ): T = DriverManager.getConnection(UpgradeHarness.urlFor(file)).use(block)

    /** Successful, versioned rows of the file's Flyway history, ascending. */
    private fun successVersions(file: File): List<Int> =
        withConn(file) { c ->
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "SELECT CAST(version AS INTEGER) FROM flyway_schema_history " +
                            "WHERE success = 1 AND version IS NOT NULL ORDER BY 1"
                    ).use { rs -> buildList { while (rs.next()) add(rs.getInt(1)) } }
            }
        }

    private fun historyMax(file: File): Int = successVersions(file).max()

    private fun integrity(file: File): List<String> =
        withConn(file) { c ->
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA integrity_check").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    private val latest: Int get() = MigrationChain.max()

    // ---------------------------------------------------------------- S1

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `S1 one manager on the golden database backs up then migrates under one lock acquisition and releases it`() {
        val db = golden()
        assertEquals(17, historyMax(db), "fixture: the golden is at V17")
        assertTrue(latest > 17, "fixture: this binary carries migrations after V17")
        val m = initialized(db, timeoutMs = 5000)

        val outcome = m.backupAndUpdateSchema()

        assertEquals(SchemaStartupOutcome.Ready, outcome, "a nested acquisition must not wait for itself (self-deadlock)")
        val bak = baks(db).single()
        assertEquals(listOf("ok"), integrity(bak), "the copy passes PRAGMA integrity_check")
        assertEquals(17, historyMax(bak), "the copy is the pre-migration state")
        assertEquals(latest, historyMax(db), "the live database is migrated to the latest version")
        assertTrue(lockFileFor(db).exists(), "the lock file is created and never deleted")
        RandomAccessFile(lockFileFor(db), "rw").use { raf ->
            val lock = raf.channel.tryLock()
            assertNotNull(lock, "the lock is released after backupAndUpdateSchema returns")
            lock.release()
        }
    }

    // ---------------------------------------------------------------- S2

    @Test
    @Timeout(180, unit = TimeUnit.SECONDS)
    fun `S2 while another holder owns the lock no bak is written and nothing migrates, then it proceeds after release`() {
        val db = golden()
        val m = initialized(db, timeoutMs = 120_000)
        val pool = Executors.newSingleThreadExecutor()
        RandomAccessFile(lockFileFor(db), "rw").use { raf ->
            val held = raf.channel.lock()
            try {
                val future = pool.submit<SchemaStartupOutcome> { m.backupAndUpdateSchema() }
                assertFailsWith<TimeoutException>("the call must wait while another holder owns the lock") {
                    future.get(1500, TimeUnit.MILLISECONDS)
                }
                assertFalse(future.isDone)
                assertTrue(baks(db).isEmpty(), "no bak may be written while another holder owns the lock: ${baks(db).map { it.name }}")
                assertEquals(17, historyMax(db), "nothing may migrate while the lock is held")

                held.release()
                assertEquals(SchemaStartupOutcome.Ready, future.get(60, TimeUnit.SECONDS), "once released the call completes")
            } finally {
                if (held.isValid) held.release()
                pool.shutdownNow()
            }
        }
        assertEquals(1, baks(db).size, "exactly one bak, taken after the release")
        assertEquals(17, historyMax(baks(db).single()))
        assertEquals(latest, historyMax(db))
    }

    // ---------------------------------------------------------------- S3

    @Test
    @Timeout(240, unit = TimeUnit.SECONDS)
    fun `S3 two managers racing on one golden file both end Ready with one bak and each migration applied once`() {
        val db = golden()
        val a = initialized(db, timeoutMs = 120_000)
        val b = initialized(db, timeoutMs = 120_000)
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        val results =
            try {
                listOf(a, b)
                    .map { m ->
                        pool.submit<SchemaStartupOutcome> {
                            barrier.await(10, TimeUnit.SECONDS)
                            m.backupAndUpdateSchema()
                        }
                    }.map { it.get(200, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        assertEquals(listOf(SchemaStartupOutcome.Ready, SchemaStartupOutcome.Ready), results, "both boots end Ready")
        assertEquals(1, baks(db).size, "the loser sees V>=18 and takes no second bak: ${baks(db).map { it.name }}")
        assertEquals(17, historyMax(baks(db).single()), "the single bak is the pre-migration state")
        val versions = successVersions(db)
        assertEquals(versions.distinct(), versions, "each migration applied exactly once")
        assertEquals(MigrationChain.count(), versions.size)
        assertEquals(latest, versions.max())
    }

    // ---------------------------------------------------------------- S4

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `S4 a failed backup is BackupFailed, never reaches updateSchema, and releases the lock`() {
        val db = golden()
        val m = spyk(DatabaseManager(appConfig = AppConfig.fromEnv { null }, migrationLockTimeoutMs = 5000))
        managers += m
        assertTrue(m.initialize(UpgradeHarness.urlFor(db)), "fixture: initialize must accept the golden database")
        every { m.runPreUpgradeBackup() } returns BackupOutcome.Failed("boom")

        val outcome = m.backupAndUpdateSchema()

        assertTrue(outcome is SchemaStartupOutcome.BackupFailed, "expected BackupFailed, got $outcome")
        assertTrue(outcome.detail.contains("boom"), "the backup's detail is carried: ${outcome.detail}")
        verify(exactly = 0) { m.updateSchema() }
        assertEquals(17, historyMax(db), "the schema is untouched after a failed backup")
        val pool = Executors.newSingleThreadExecutor()
        try {
            val acquired =
                pool
                    .submit<Boolean> {
                        RandomAccessFile(lockFileFor(db), "rw").use { raf ->
                            val lock = raf.channel.tryLock()
                            lock?.release()
                            lock != null
                        }
                    }.get(30, TimeUnit.SECONDS)
            assertTrue(acquired, "the lock is released after a failed backup (acquirable from another thread)")
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------------------------------------------------------------- S5

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `S5 an unavailable lock is SchemaUpdateFailed with no bak, no migration and an ERROR naming the lock file`() {
        val db = golden()
        val m = initialized(db, timeoutMs = 1000)
        RandomAccessFile(lockFileFor(db), "rw").use { raf ->
            val held = raf.channel.lock()
            try {
                var outcome: SchemaStartupOutcome? = null
                val errors = captureLogs { outcome = m.backupAndUpdateSchema() }.at(Level.ERROR)

                assertEquals(SchemaStartupOutcome.SchemaUpdateFailed, outcome)
                assertTrue(baks(db).isEmpty(), "no bak without the lock: ${baks(db).map { it.name }}")
                assertEquals(17, historyMax(db), "the file stays unmigrated")
                assertTrue(
                    errors.any { it.contains(lockFileFor(db).name) },
                    "an ERROR must name the lock file ${lockFileFor(db).name}: $errors"
                )
            } finally {
                if (held.isValid) held.release()
            }
        }
    }

    // ---------------------------------------------------------------- S6

    @Test
    @Timeout(180, unit = TimeUnit.SECONDS)
    fun `S6 reentrancy is confined to the active acquisition so a later updateSchema on the same thread still contends`() {
        val db = golden()
        val url = UpgradeHarness.urlFor(db)
        val m = initialized(db, timeoutMs = 120_000)
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertEquals(
                SchemaStartupOutcome.Ready,
                pool.submit<SchemaStartupOutcome> { m.backupAndUpdateSchema() }.get(100, TimeUnit.SECONDS)
            )
            RandomAccessFile(lockFileFor(db), "rw").use { raf ->
                val held = raf.channel.lock()
                try {
                    val future = pool.submit<Boolean> { FlywayDatabaseSchemaManager(url).updateSchema() }
                    assertFailsWith<TimeoutException>("a finished acquisition leaves no depth behind on the thread") {
                        future.get(1500, TimeUnit.MILLISECONDS)
                    }
                    assertFalse(future.isDone)
                    held.release()
                    assertTrue(future.get(60, TimeUnit.SECONDS), "once released the standalone updateSchema succeeds")
                } finally {
                    if (held.isValid) held.release()
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------------------------------------------------------------- S7

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `S7 a manager that was never initialized is SchemaUpdateFailed without throwing and creates no lock file`() {
        // The configured path points into the TempDir, so any fallback that resolved it would create
        // "<dir>/x.db.migrate.lock"; an uninitialized manager has no file-backed JDBC URL, so none may appear.
        val configured = File(dir, "x.db")
        val m = manager(timeoutMs = 1000, env = mapOf("DATABASE_PATH" to configured.absolutePath))

        val outcome = m.backupAndUpdateSchema()

        assertEquals(SchemaStartupOutcome.SchemaUpdateFailed, outcome)
        val locks = (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".migrate.lock") }
        assertTrue(locks.isEmpty(), "no lock file beside the configured path without a JDBC URL: ${locks.map { it.name }}")
    }

    // ---------------------------------------------------------------- probes

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `probe replay on one manager is Ready again with still one bak`() {
        val db = golden()
        val m = initialized(db, timeoutMs = 5000)

        assertEquals(SchemaStartupOutcome.Ready, m.backupAndUpdateSchema())
        assertEquals(SchemaStartupOutcome.Ready, m.backupAndUpdateSchema(), "the replay finds an already upgraded file")

        assertEquals(1, baks(db).size, "the replay takes no second bak")
        assertEquals(latest, historyMax(db))
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `probe a pre-existing zero byte lock file is reused and stays zero bytes`() {
        val db = golden()
        assertTrue(lockFileFor(db).createNewFile(), "fixture: a leftover lock file from an earlier boot")
        val m = initialized(db, timeoutMs = 5000)

        assertEquals(SchemaStartupOutcome.Ready, m.backupAndUpdateSchema())

        assertTrue(lockFileFor(db).exists(), "the lock file is never deleted")
        assertEquals(0L, lockFileFor(db).length(), "nothing is written to the lock file")
        assertEquals(1, baks(db).size)
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `probe a database directory containing a space works`() {
        val db = golden(File(dir, "dir with space"))
        val m = initialized(db, timeoutMs = 5000)

        assertEquals(SchemaStartupOutcome.Ready, m.backupAndUpdateSchema())

        assertEquals(1, baks(db).size)
        assertTrue(lockFileFor(db).exists())
        assertEquals(latest, historyMax(db))
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `probe UPGRADE_BACKUP=false is Ready, migrates, and writes no bak`() {
        val db = golden()
        val m = initialized(db, timeoutMs = 5000, env = mapOf("UPGRADE_BACKUP" to "false"))

        assertEquals(SchemaStartupOutcome.Ready, m.backupAndUpdateSchema())

        assertTrue(baks(db).isEmpty(), "the opt-out writes no bak")
        assertEquals(latest, historyMax(db))
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `probe FLYWAY_REPAIR writes no bak and does not throw`() {
        val db = golden()
        val m = initialized(db, timeoutMs = 5000, env = mapOf("FLYWAY_REPAIR" to "true"))

        m.backupAndUpdateSchema()

        assertTrue(baks(db).isEmpty(), "a repair run copies nothing")
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `probe a relative database url does not self-deadlock`() {
        val rel = File("build/tmp/upgrade-backup-lock-probe-${System.nanoTime()}")
        try {
            val db = golden(rel.absoluteFile)
            val relativeUrl = "jdbc:sqlite:" + File(rel, db.name).path.replace(File.separatorChar, '/')
            val m = manager(timeoutMs = 5000)
            assertTrue(m.initialize(relativeUrl), "fixture: initialize must accept a relative url")

            assertEquals(SchemaStartupOutcome.Ready, m.backupAndUpdateSchema())

            assertEquals(1, baks(db).size)
            assertEquals(latest, historyMax(db))
        } finally {
            managers.forEach { runCatching { it.shutdown() } }
            managers.clear()
            rel.deleteRecursively()
        }
    }
}
