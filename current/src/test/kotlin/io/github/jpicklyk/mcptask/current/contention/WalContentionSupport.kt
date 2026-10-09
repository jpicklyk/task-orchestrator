package io.github.jpicklyk.mcptask.current.contention

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.OutsideUnitPolicy
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The frozen instant every contention fixture runs at, deliberately far from wall-clock time. */
internal val WAL_T: Instant = Instant.parse("2001-02-03T04:05:06.789Z")

/** Real-thread race mechanics: every racer is parked on one start gate, none sleeps, any exception is a failure. */
internal object WalRace {
    private const val READY_TIMEOUT_SECONDS = 30L
    private const val RESULT_TIMEOUT_SECONDS = 60L

    /** Runs [body] for indexes 0 until [n] on [n] real threads released together; results come back in index order. */
    fun <T> race(
        n: Int,
        body: suspend (Int) -> T
    ): List<T> {
        val pool = Executors.newFixedThreadPool(n)
        try {
            val ready = CountDownLatch(n)
            val start = CountDownLatch(1)
            val futures =
                (0 until n).map { i ->
                    pool.submit<T> {
                        ready.countDown()
                        start.await()
                        runBlocking { body(i) }
                    }
                }
            check(ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "racers did not reach the start gate" }
            start.countDown()
            // A racer's exception surfaces here as ExecutionException: no exception is tolerated.
            return futures.map { it.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** The same operations one after another, in index order: the oracle run. */
    fun <T> sequential(
        n: Int,
        body: suspend (Int) -> T
    ): List<T> = runBlocking { (0 until n).map { body(it) } }
}

/**
 * A file-backed WAL database through the production [DatabaseManager], with one production composition per manager
 * (all sharing one frozen [clock] and one config), so a race exercises the real tools and services. With
 * [twoManagers] a second [DatabaseManager] on the same file stands in for a second server process: its own writer
 * mutex and pool, so contention is real SQLite-level locking.
 */
internal class WalFixture private constructor(
    val clock: SettableClock,
    val db: SqliteTestDatabase,
    val drivers: List<P11Driver>,
    private val closeables: List<AutoCloseable>,
    private val concurrent: Boolean
) : AutoCloseable {
    private val counter = AtomicInteger()

    /** The driver on the first (primary) manager. */
    val d: P11Driver get() = drivers[0]

    val rig: EventLogRig get() = d.rig

    /** A number unique within this fixture, for item titles and agent ids. */
    fun next(): Int = counter.incrementAndGet()

    /** The driver racer [i] uses: alternates between the managers when there are two. */
    fun driverFor(i: Int): P11Driver = drivers[i % drivers.size]

    /** Races [body] on [n] threads, or runs it sequentially on the oracle fixture. */
    fun <T> go(
        n: Int,
        body: suspend (Int) -> T
    ): List<T> = if (concurrent) WalRace.race(n, body) else WalRace.sequential(n, body)

    override fun close() {
        closeables.asReversed().forEach { runCatching { it.close() } }
        db.close()
    }

    companion object {
        fun open(
            concurrent: Boolean,
            twoManagers: Boolean
        ): WalFixture {
            val clock = SettableClock(WAL_T)
            val db = SqliteTestDatabase.open(clock = clock)
            val closeables = mutableListOf<AutoCloseable>()
            try {
                val drivers = mutableListOf<P11Driver>()
                val dir = tempDir(closeables)
                drivers += P11Driver(EventLogRig.build(db, dir, P11_BASE_YAML, clock))
                if (twoManagers) {
                    val other =
                        DatabaseManager(appConfig = AppConfig.fromEnv { null }, outsideUnitPolicy = OutsideUnitPolicy.IMPLICIT).also {
                            check(it.initialize(db.jdbcUrl)) { "second manager failed to open ${db.jdbcUrl}" }
                        }
                    closeables += AutoCloseable { other.shutdown() }
                    drivers += P11Driver(EventLogRig.build(db, tempDir(closeables), P11_BASE_YAML, clock, other))
                }
                return WalFixture(clock, db, drivers, closeables, concurrent)
            } catch (e: Throwable) {
                closeables.asReversed().forEach { runCatching { it.close() } }
                runCatching { db.close() }
                throw e
            }
        }

        private fun tempDir(closeables: MutableList<AutoCloseable>): Path {
            val dir = Files.createTempDirectory("wal-config-")
            closeables += AutoCloseable { dir.toFile().deleteRecursively() }
            return dir
        }
    }
}

/**
 * Runs [scenario] once sequentially on an oracle fixture (the hand-derived expectations must hold with no race at all),
 * then [repeats] times on a racing fixture with fresh items per iteration.
 */
internal fun runScenario(
    repeats: Int,
    twoManagers: Boolean = false,
    scenario: suspend (WalFixture) -> Unit
) {
    WalFixture.open(concurrent = false, twoManagers = twoManagers).use { oracle -> runBlocking { scenario(oracle) } }
    WalFixture.open(concurrent = true, twoManagers = twoManagers).use { fx -> repeat(repeats) { runBlocking { scenario(fx) } } }
}
