package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management

import ch.qos.logback.classic.Level
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.at
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.captureLogs
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.dbFile
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.scalarInt
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.urlFor
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management.SchemaTestSupport.userTableCount
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The migration lock across two real OS processes (carry-in P2a Obs 8, item 36c719db C2): the second process is a JVM
 * launched from this test's runtime classpath running [MigrationLockChildProcess], so the `<dbfile>.migrate.lock`
 * file lock is contended between processes, not inside one JVM (where [java.nio.channels.OverlappingFileLockException]
 * stands in for it, see [FlywayMigrationLockTest]). Every wait is bounded and every child is destroyed in `finally`.
 * Serial: it starts JVMs and holds OS locks.
 */
@Tag("serial")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class FlywayMigrationLockCrossProcessTest {
    @TempDir
    lateinit var dir: Path

    private fun lockFileFor(db: File) = File(db.path + ".migrate.lock")

    private fun historyCount(url: String) = scalarInt(url, "SELECT count(*) FROM flyway_schema_history WHERE success = 1")

    @Test
    fun `C2 this JVM and a second process race startup migration - exactly one migrates, the other validates`() {
        val db = dbFile(dir)
        val url = urlFor(db)
        ChildJvm.start(dir, "migrate", url).use { child ->
            child.await("READY", STARTUP_SECONDS)
            val pool = Executors.newSingleThreadExecutor()
            try {
                child.send("GO")
                val ours =
                    pool.submit<Outcome> {
                        var ok = false
                        val logs = captureLogs { ok = FlywayDatabaseSchemaManager(url, repair = false).updateSchema() }
                        Outcome(ok, MigrationLockChildProcess.appliedCount(logs))
                    }
                val theirs = Outcome.parse(child.await("OUTCOME", RESULT_SECONDS))
                val mine = ours.get(RESULT_SECONDS, TimeUnit.SECONDS)
                assertTrue(mine.ok && theirs.ok, "both startups succeed: this=$mine child=$theirs")
                assertEquals(
                    listOf(0, MIGRATIONS),
                    listOf(mine.applied, theirs.applied).sorted(),
                    "exactly one process applies every migration, the other applies none: this=$mine child=$theirs"
                )
            } finally {
                pool.shutdownNow()
            }
        }
        assertEquals(MIGRATIONS, historyCount(url), "each migration applied exactly once")
        assertTrue(lockFileFor(db).exists())
    }

    @Test
    fun `C2 a second process waits while another process holds the lock, then validates the schema it migrated`() {
        val db = dbFile(dir)
        val url = urlFor(db)
        RandomAccessFile(lockFileFor(db), "rw").use { raf ->
            val held = raf.channel.lock()
            try {
                ChildJvm.start(dir, "migrate", url).use { child ->
                    child.await("READY", STARTUP_SECONDS)
                    child.send("GO")
                    assertNull(child.poll("OUTCOME", HELD_WINDOW_MS), "the child must wait while this process holds the lock")
                    assertEquals(0, if (db.exists()) userTableCount(url) else 0, "the waiting child migrated nothing")

                    // This process migrates under its lock, as the first process to boot would.
                    FlywayDatabaseSchemaManager(url, repair = false).flywayConfiguration().load().migrate()
                    held.release()

                    val theirs = Outcome.parse(child.await("OUTCOME", RESULT_SECONDS))
                    assertEquals(Outcome(true, 0), theirs, "after the lock is released the child validates and applies nothing")
                }
            } finally {
                if (held.isValid) held.release()
            }
        }
        assertEquals(MIGRATIONS, historyCount(url))
    }

    @Test
    fun `C2 lock timeout - startup fails with the timeout message naming the lock file while another process holds it`() {
        val db = dbFile(dir)
        val url = urlFor(db)
        val lockFile = lockFileFor(db)
        ChildJvm.start(dir, "hold", lockFile.path).use { child ->
            child.await("LOCKED", STARTUP_SECONDS)
            var ok = true
            val errors =
                captureLogs {
                    ok = FlywayDatabaseSchemaManager(url, repair = false, lockTimeoutMs = SHORT_TIMEOUT_MS).updateSchema()
                }.at(Level.ERROR)
            assertFalse(ok, "startup must fail once the lock timeout elapses")
            assertTrue(
                errors.any {
                    it.contains("Timed out after") &&
                        it.contains("waiting for the migration lock ${lockFile.path}") &&
                        it.contains("another process is migrating this database")
                },
                "the ERROR must carry the timeout message naming the lock file: $errors"
            )
            assertEquals(0, if (db.exists()) userTableCount(url) else 0, "nothing may be migrated without the lock")
            child.send("RELEASE")
            child.await("RELEASED", RESULT_SECONDS)
        }
        assertTrue(FlywayDatabaseSchemaManager(url, repair = false).updateSchema(), "control: succeeds once the lock is free")
    }

    private data class Outcome(
        val ok: Boolean,
        val applied: Int
    ) {
        companion object {
            private val LINE = Regex("OUTCOME ok=(true|false) applied=(-?\\d+)")

            fun parse(line: String): Outcome {
                val m = LINE.matchEntire(line.trim()) ?: fail("malformed child outcome: $line")
                return Outcome(m.groupValues[1].toBoolean(), m.groupValues[2].toInt())
            }
        }
    }

    /** A child JVM on this test's runtime classpath, driven line by line over stdin/stdout. */
    private class ChildJvm private constructor(
        private val process: Process,
        private val stderr: File
    ) : AutoCloseable {
        private val lines = LinkedBlockingQueue<String>()

        init {
            Thread({
                runCatching { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }
            }, "migration-lock-child-stdout").apply {
                isDaemon = true
                start()
            }
        }

        fun send(line: String) {
            process.outputStream.write((line + "\n").toByteArray())
            process.outputStream.flush()
        }

        /** The next stdout line starting with [prefix] within [millis], or null. Other lines are skipped. */
        fun poll(
            prefix: String,
            millis: Long
        ): String? {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
            while (true) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return null
                val line = lines.poll(left, TimeUnit.NANOSECONDS) ?: return null
                if (line.startsWith(prefix)) return line
            }
        }

        fun await(
            prefix: String,
            seconds: Long
        ): String =
            poll(prefix, TimeUnit.SECONDS.toMillis(seconds))
                ?: fail("child printed no $prefix within ${seconds}s (alive=${process.isAlive}); stderr tail: ${stderrTail()}")

        private fun stderrTail(): String = runCatching { stderr.readText().takeLast(4000) }.getOrDefault("<unreadable>")

        override fun close() {
            runCatching { process.outputStream.close() }
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
        }

        companion object {
            fun start(
                dir: Path,
                vararg args: String
            ): ChildJvm {
                val classpath = System.getProperty("java.class.path")
                check(!classpath.isNullOrBlank()) { "java.class.path is empty" }
                // An argument file keeps a long Windows classpath clear of the command-line length limit.
                val tag = System.nanoTime()
                val argFile = dir.resolve("child-$tag.args")
                Files.writeString(argFile, "-cp\n\"" + classpath.replace('\\', '/') + "\"\n")
                val exe = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
                val java = Paths.get(System.getProperty("java.home"), "bin", exe).toString()
                val stderr = dir.resolve("child-$tag.err").toFile()
                val process =
                    ProcessBuilder(
                        listOf(java, "-Duser.timezone=UTC", "-Xmx256m", "@$argFile", MigrationLockChildProcess::class.java.name) +
                            args
                    ).redirectError(stderr)
                        .start()
                return ChildJvm(process, stderr)
            }
        }
    }

    private companion object {
        const val MIGRATIONS = 22
        const val STARTUP_SECONDS = 60L
        const val RESULT_SECONDS = 120L
        const val HELD_WINDOW_MS = 2_000L
        const val SHORT_TIMEOUT_MS = 1_500L
    }
}
