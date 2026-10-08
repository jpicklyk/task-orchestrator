package io.github.jpicklyk.mcptask.current.infrastructure.sqlite

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.readTx
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.writeTx
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shared helpers for the P5a unit-of-work tests. Everything runs against a file-backed [SqliteTestDatabase]. */
internal fun SqliteTestDatabase.uow(clock: Clock = Clock { Instant.now() }): SqliteUnitOfWork =
    SqliteUnitOfWork(databaseManager, repositoryProvider(), clock)

/** Creates the probe tables used by the unit tests (outside any unit; an implicit write). */
internal fun SqliteTestDatabase.createProbeTables() =
    runBlocking {
        databaseManager.writeTx("Test.ddl.probe") {
            exec("CREATE TABLE p5a_probe (id INTEGER PRIMARY KEY, v INTEGER NOT NULL DEFAULT 0)")
        }
        databaseManager.writeTx("Test.ddl.parent") { exec("CREATE TABLE p5a_parent (id INTEGER PRIMARY KEY)") }
        databaseManager.writeTx("Test.ddl.child") {
            exec("CREATE TABLE p5a_child (id INTEGER PRIMARY KEY, pid INTEGER NOT NULL REFERENCES p5a_parent(id))")
        }
    }

internal suspend fun DatabaseManager.countRows(table: String): Int =
    readTx {
        var n = -1
        exec("SELECT count(*) FROM $table") { rs -> if (rs.next()) n = rs.getInt(1) }
        n
    }

internal suspend fun DatabaseManager.probeValue(id: Int): Int? =
    readTx {
        var v: Int? = null
        exec("SELECT v FROM p5a_probe WHERE id = $id") { rs -> if (rs.next()) v = rs.getInt(1) }
        v
    }

internal fun err(message: String): DomainError = DomainError(ErrorCode.INTERNAL, message)

/**
 * A raw JDBC connection (outside the pools) that holds the SQLite write lock (`BEGIN IMMEDIATE`) for [holdMs]
 * after [start] returns, then rolls back and closes. [close] joins the holder thread, so the fixture can close.
 */
internal class RawWriterLock(
    private val jdbcUrl: String,
    private val holdMs: Long
) : AutoCloseable {
    private val locked = CountDownLatch(1)
    private val thread =
        Thread {
            DriverManager.getConnection(jdbcUrl).use { c ->
                c.createStatement().use { st ->
                    st.execute("PRAGMA busy_timeout=0")
                    st.execute("BEGIN IMMEDIATE")
                    locked.countDown()
                    try {
                        Thread.sleep(holdMs)
                    } finally {
                        st.execute("ROLLBACK")
                    }
                }
            }
        }.also { it.isDaemon = true }

    fun start(): RawWriterLock {
        thread.start()
        check(locked.await(10, TimeUnit.SECONDS)) { "raw writer lock was not acquired" }
        return this
    }

    override fun close() {
        thread.join(20_000)
    }
}
