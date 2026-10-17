package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.management

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.StartupCompaction
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/**
 * The `<dbfile>.migrate.lock` OS file lock that serializes the schema phase across processes (AR-85), shared by
 * [FlywayDatabaseSchemaManager.updateSchema] and the pre-upgrade backup + schema update sequence in
 * `DatabaseManager.backupAndUpdateSchema`.
 *
 * Re-entrant for the thread that already holds the lock (keyed by the lock file's canonical path): the nested call runs its block
 * directly. Other threads and processes still contend; [OverlappingFileLockException] (this JVM already holds the lock) counts as held.
 */
internal object MigrationLock {
    private val logger = LoggerFactory.getLogger(MigrationLock::class.java)
    private const val LOCK_POLL_MS = 100L
    private const val LOCK_LOG_INTERVAL_MS = 10_000L

    /** Canonical lock-file paths the current thread holds. */
    private val held = ThreadLocal.withInitial { HashSet<String>() }

    /**
     * Runs [block] under the lock (no lock for in-memory or non-file URLs). Polls [lockAttempt]; any other [IOException] (for example
     * ENOLCK on an NFS mount without lockd) fails closed with a message naming the lock file. Logs INFO every 10 s while waiting and
     * gives up after [lockTimeoutMs]. The lock file is never deleted.
     */
    fun <T> withLock(
        jdbcUrl: String,
        lockTimeoutMs: Long = FlywayDatabaseSchemaManager.LOCK_TIMEOUT_MS,
        lockAttempt: (FileChannel) -> FileLock? = { it.tryLock() },
        block: () -> T
    ): T {
        val dbFile = StartupCompaction.resolveDbFile(jdbcUrl) ?: return block()
        val lockFile = File(dbFile.path + ".migrate.lock")
        lockFile.absoluteFile.parentFile?.mkdirs()
        val key = lockFile.canonicalPath
        val heldByThisThread = held.get()
        if (key in heldByThisThread) return block()

        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val start = System.nanoTime()
            var lastLog = start
            var lock: FileLock? = null
            while (lock == null) {
                lock =
                    try {
                        lockAttempt(channel)
                    } catch (e: OverlappingFileLockException) {
                        null
                    } catch (e: IOException) {
                        throw IllegalStateException(
                            "Could not acquire the migration lock ${lockFile.path}: ${e.message}. " +
                                "Startup fails closed: the database is not migrated without the lock. " +
                                "Check that the filesystem holding the database supports file locks.",
                            e
                        )
                    }
                if (lock != null) break
                val now = System.nanoTime()
                val waitedMs = (now - start) / 1_000_000
                if (waitedMs >= lockTimeoutMs) {
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
            heldByThisThread.add(key)
            try {
                return block()
            } finally {
                heldByThisThread.remove(key)
                try {
                    lock?.release()
                } catch (e: IOException) {
                    logger.warn("Could not release the migration lock ${lockFile.path}: ${e.message}")
                }
            }
        }
    }
}
