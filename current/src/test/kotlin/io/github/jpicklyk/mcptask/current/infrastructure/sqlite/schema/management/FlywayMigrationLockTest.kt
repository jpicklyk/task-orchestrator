package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.dbFile
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.scalarInt
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.urlFor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Migration lock (AR-85 rec 2 / plan v4-phase1-core 3.10): the whole schema phase runs under an OS
 * file lock named `<dbfile>.migrate.lock`, so two processes (here: two managers on two threads,
 * which also covers the same-JVM overlap case) never migrate one file concurrently.
 */
class FlywayMigrationLockTest {
    @TempDir
    lateinit var dir: Path

    private fun lockFileFor(db: File) = File(db.path + ".migrate.lock")

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `S13 two concurrent managers on one fresh file both succeed, migrate once, and leave the lock file`() {
        val db = dbFile(dir)
        val url = urlFor(db)
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (1..2).map {
                    pool.submit<Boolean> {
                        barrier.await(10, TimeUnit.SECONDS)
                        FlywayDatabaseSchemaManager(url, repair = false).updateSchema()
                    }
                }
            val results = futures.map { it.get(90, TimeUnit.SECONDS) }
            assertEquals(listOf(true, true), results, "both callers must succeed under the lock")
        } finally {
            pool.shutdownNow()
        }

        assertEquals(
            21,
            scalarInt(url, "SELECT count(*) FROM flyway_schema_history WHERE success = 1"),
            "each migration applied exactly once"
        )
        assertEquals(21, scalarInt(url, "SELECT count(DISTINCT version) FROM flyway_schema_history"))
        assertTrue(lockFileFor(db).exists(), "the lock file <db>.migrate.lock must exist and is never deleted")
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `S14 updateSchema blocks while the lock file is held and completes once it is released`() {
        val db = dbFile(dir)
        val url = urlFor(db)
        val lock = lockFileFor(db)
        val pool = Executors.newSingleThreadExecutor()
        RandomAccessFile(lock, "rw").use { raf ->
            val held = raf.channel.lock()
            try {
                val future = pool.submit<Boolean> { FlywayDatabaseSchemaManager(url, repair = false).updateSchema() }
                assertFailsWith<TimeoutException>("updateSchema must wait while another holder owns the lock") {
                    future.get(1500, TimeUnit.MILLISECONDS)
                }
                assertFalse(future.isDone)
                assertEquals(
                    0,
                    if (db.exists()) SchemaTestSupport.userTableCount(url) else 0,
                    "nothing may be migrated while the lock is held"
                )
                held.release()
                assertTrue(future.get(60, TimeUnit.SECONDS), "once released the call must complete successfully")
            } finally {
                if (held.isValid) held.release()
                pool.shutdownNow()
            }
        }
        assertEquals(21, scalarInt(url, "SELECT count(*) FROM flyway_schema_history WHERE success = 1"))
    }

    @Test
    fun `lock timeout constant is a positive number of milliseconds`() {
        assertTrue(FlywayDatabaseSchemaManager.LOCK_TIMEOUT_MS > 0L)
    }

    @Test
    fun `probe in-memory database URL takes no lock and creates no lock file`() {
        val name = "lockprobe_${System.nanoTime()}"
        val url = "jdbc:sqlite:file:$name?mode=memory&cache=shared"
        java.sql.DriverManager.getConnection(url).use {
            assertTrue(FlywayDatabaseSchemaManager(url, repair = false).updateSchema())
            assertEquals(21, scalarInt(url, "SELECT count(*) FROM flyway_schema_history WHERE success = 1"))
        }
        assertFalse(File("$name.migrate.lock").exists(), "no lock file may be created for an in-memory URL (cwd)")
        assertFalse(File("file:$name.migrate.lock").exists())
    }

    @Test
    fun `probe lock file parent directories are created`() {
        val db =
            dir
                .resolve("a")
                .resolve("b")
                .resolve("nested.sqlite")
                .toFile()
        assertFalse(db.parentFile.exists(), "fixture: parent chain must not exist")
        FlywayDatabaseSchemaManager(urlFor(db), repair = false).updateSchema()
        assertTrue(lockFileFor(db).exists(), "lock file and its parent directories must be created")
    }

    @Test
    fun `probe sequential reuse after release re-acquires the same lock file`() {
        val db = dbFile(dir)
        val url = urlFor(db)
        assertTrue(FlywayDatabaseSchemaManager(url, repair = false).updateSchema())
        assertTrue(
            FlywayDatabaseSchemaManager(url, repair = false).updateSchema(),
            "same-JVM reentry must not throw OverlappingFileLockException"
        )
        assertTrue(lockFileFor(db).exists())
    }
}
