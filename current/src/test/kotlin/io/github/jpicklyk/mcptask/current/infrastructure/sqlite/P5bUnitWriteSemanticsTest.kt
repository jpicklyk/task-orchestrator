package io.github.jpicklyk.mcptask.current.infrastructure.sqlite

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.writeTx
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent P5b tests (item c01d2e90), authored blind against the frozen test-plan / task-scope / carry-in.
 *
 * Scenarios: S1 and S1b (outside-unit write under OutsideUnitPolicy.FAIL), S15a / S15b / S15e (poisoned unit,
 * carry-in F8 and O1), probes P2 (store write in afterCommit under FAIL), P3 (nested Err without a thrown fault),
 * P4 (= S15e) and P5 (write in a read unit).
 *
 * Oracles: S1 - task-scope section 6 ("FAIL counts then throws before drive() (no Mutex, no checkout)"); S15 - carry-in
 * F8 and the P5a-review O1 text ("a store fault mid-unit followed by a later write must leave NEITHER write
 * committed"); P3 - carry-in TP2. Every negative assertion has a control that DOES commit (vacuity check).
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class P5bUnitWriteSemanticsTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val db get() = sqlite.db
    private val dm get() = db.databaseManager
    private val managers = mutableListOf<DatabaseManager>()

    @AfterEach
    fun tearDown() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    /** A second production manager over the same file, with the production default policy (FAIL) unless told otherwise. */
    private fun manager(policy: OutsideUnitPolicy = OutsideUnitPolicy.FAIL): DatabaseManager =
        DatabaseManager(appConfig = AppConfig.fromEnv { null }, outsideUnitPolicy = policy).also {
            assertTrue(it.initialize(db.jdbcUrl), "manager must initialize against the fixture file")
            managers += it
        }

    private fun rawExec(sql: String) {
        DriverManager.getConnection(db.jdbcUrl).use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private fun rawCount(table: String): Int =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM $table").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun uowOver(manager: DatabaseManager) = SqliteUnitOfWork(manager, DefaultRepositoryProvider(manager), Clock { Instant.now() })

    // ---------------------------------------------------------------- S1
    @Test
    fun `S1 FAIL policy refuses an outside-unit write, leaves zero rows and counts the op`(): Unit =
        runBlocking {
            rawExec("CREATE TABLE s1_probe (id INTEGER PRIMARY KEY)")
            val failing = manager(OutsideUnitPolicy.FAIL)
            assertEquals(OutsideUnitPolicy.FAIL, failing.units.outsideUnitPolicy)
            assertEquals(0L, failing.units.outsideUnitWrites["S1.write"] ?: 0L)

            val e =
                assertFailsWith<OutsideUnitWriteException> {
                    failing.writeTx("S1.write") { exec("INSERT INTO s1_probe (id) VALUES (1)") }
                }

            assertEquals("S1.write", e.op)
            assertEquals(0, rawCount("s1_probe"), "a refused write must not reach the table")
            assertEquals(1L, failing.units.outsideUnitWrites["S1.write"], "the refused attempt is counted under its op")
        }

    @Test
    fun `S1 control - the IMPLICIT policy runs the same outside-unit write, so the FAIL zero-row result is not vacuous`(): Unit =
        runBlocking {
            rawExec("CREATE TABLE s1_probe (id INTEGER PRIMARY KEY)")
            val lenient = manager(OutsideUnitPolicy.IMPLICIT)
            lenient.writeTx("S1.write") { exec("INSERT INTO s1_probe (id) VALUES (1)") }
            assertEquals(1, rawCount("s1_probe"))
            assertEquals(1L, lenient.units.outsideUnitWrites["S1.write"])
        }

    @Test
    fun `S1 a write inside a unit is allowed under FAIL and is not counted as outside`(): Unit =
        runBlocking {
            rawExec("CREATE TABLE s1_probe (id INTEGER PRIMARY KEY)")
            val failing = manager(OutsideUnitPolicy.FAIL)
            val result =
                uowOver(failing).write("S1.inUnit") {
                    failing.writeTx("S1.inUnit.store") { exec("INSERT INTO s1_probe (id) VALUES (1)") }
                    Outcome.Ok("done")
                }
            assertEquals(Outcome.Ok("done"), result)
            assertEquals(1, rawCount("s1_probe"))
            assertEquals(emptyMap(), failing.units.outsideUnitWrites.filterValues { it != 0L })
        }

    @Test
    @Tag("serial")
    fun `S1b FAIL fails within 500 ms even while another connection holds the writer lock`(): Unit =
        runBlocking {
            rawExec("CREATE TABLE s1_probe (id INTEGER PRIMARY KEY)")
            val failing = manager(OutsideUnitPolicy.FAIL)
            RawWriterLock(db.jdbcUrl, 1_500).start().use {
                val t0 = System.nanoTime()
                assertFailsWith<OutsideUnitWriteException> {
                    failing.writeTx("S1b.write") { exec("INSERT INTO s1_probe (id) VALUES (1)") }
                }
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                assertTrue(elapsedMs < 500, "FAIL must throw before waiting for the writer (took $elapsedMs ms)")
            }
            assertEquals(0, rawCount("s1_probe"))
        }

    // ---------------------------------------------------------------- S15
    private fun installFaultTrigger() =
        rawExec(
            "CREATE TRIGGER s15_fault BEFORE INSERT ON p5a_probe WHEN NEW.id = 99 BEGIN SELECT RAISE(ABORT, 'inj'); END"
        )

    @Test
    fun `S15a a thrown joined store fault propagates - Err from the fault, neither write committed, afterRollback not afterCommit`(): Unit =
        runBlocking {
            db.createProbeTables()
            installFaultTrigger()
            val rolledBack = CopyOnWriteArrayList<DomainError?>()
            val committed = AtomicInteger()

            val result =
                db.uow().write<Unit>("S15a") {
                    afterCommit { committed.incrementAndGet() }
                    afterRollback { rolledBack += it }
                    dm.writeTx("S15a.A") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                    dm.writeTx("S15a.fault") { exec("INSERT INTO p5a_probe (id, v) VALUES (99, 9)") }
                    dm.writeTx("S15a.B") { exec("INSERT INTO p5a_probe (id, v) VALUES (2, 2)") }
                    Outcome.Ok(Unit)
                }

            val err = assertIs<Outcome.Err>(result, "a faulted unit must not return Ok: $result")
            assertTrue("inj" in err.error.message, "the error keeps the innermost SQL text: ${err.error.message}")
            assertEquals(0, dm.countRows("p5a_probe"), "neither write A nor write B may be committed")
            assertEquals(0, committed.get(), "afterCommit must not fire")
            assertEquals(1, rolledBack.size, "afterRollback fires exactly once")
            assertNotNull(rolledBack[0], "the rollback hook receives the translated fault")
        }

    @Test
    fun `S15b a fault caught inside the block and followed by another write and Ok is turned into Err - neither committed`(): Unit =
        runBlocking {
            db.createProbeTables()
            installFaultTrigger()
            val rolledBack = CopyOnWriteArrayList<DomainError?>()
            val committed = AtomicInteger()

            val result =
                db.uow().write<Unit>("S15b") {
                    afterCommit { committed.incrementAndGet() }
                    afterRollback { rolledBack += it }
                    dm.writeTx("S15b.A") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                    try {
                        dm.writeTx("S15b.fault") { exec("INSERT INTO p5a_probe (id, v) VALUES (99, 9)") }
                    } catch (_: Exception) {
                        // swallowed on purpose: the unit must still refuse to commit the later write
                    }
                    dm.writeTx("S15b.B") { exec("INSERT INTO p5a_probe (id, v) VALUES (2, 2)") }
                    Outcome.Ok(Unit)
                }

            val err = assertIs<Outcome.Err>(result, "a poisoned unit must not return Ok: $result")
            assertTrue("inj" in err.error.message, "the recorded fault is the translated error: ${err.error.message}")
            assertEquals(0, dm.countRows("p5a_probe"), "neither A nor B may be committed (B would be a partial write)")
            assertEquals(0, committed.get())
            assertEquals(1, rolledBack.size)
        }

    @Test
    fun `S15 control - the same block without the fault commits both writes and fires afterCommit`(): Unit =
        runBlocking {
            db.createProbeTables()
            installFaultTrigger()
            val committed = AtomicInteger()
            val result =
                db.uow().write<Unit>("S15.control") {
                    afterCommit { committed.incrementAndGet() }
                    dm.writeTx("S15c.A") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                    dm.writeTx("S15c.B") { exec("INSERT INTO p5a_probe (id, v) VALUES (2, 2)") }
                    Outcome.Ok(Unit)
                }
            assertEquals(Outcome.Ok(Unit), result)
            assertEquals(2, dm.countRows("p5a_probe"))
            assertEquals(1, committed.get())
        }

    @Test
    @Tag("serial")
    fun `S15e P4 a BUSY fault on attempt 1 does not poison attempt 2, which commits`(): Unit =
        runBlocking {
            db.createProbeTables()
            val attempts = AtomicInteger()
            val committed = CopyOnWriteArrayList<Int>()
            val result =
                db.uow().write<Int>("S15e") {
                    val attempt = attempts.incrementAndGet()
                    afterCommit { committed += attempt }
                    dm.writeTx("S15e.A") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, $attempt)") }
                    if (attempt == 1) {
                        dm.writeTx("S15e.busy") { throw SQLiteException("simulated busy", SQLiteErrorCode.SQLITE_BUSY) }
                    }
                    dm.writeTx("S15e.B") { exec("INSERT INTO p5a_probe (id, v) VALUES (2, $attempt)") }
                    Outcome.Ok(attempt)
                }

            assertEquals(Outcome.Ok(2), result, "the second attempt must commit")
            assertEquals(2, attempts.get())
            assertEquals(2, dm.countRows("p5a_probe"), "only attempt 2's rows exist (attempt 1 rolled back)")
            assertEquals(2, dm.probeValue(1), "row A carries attempt 2's value")
            assertEquals(2, dm.probeValue(2))
            assertEquals(listOf(2), committed.toList(), "only the surviving attempt's afterCommit fires")
        }

    // ---------------------------------------------------------------- probes
    @Test
    fun `P2 a store write inside afterCommit under FAIL is refused and logged - the unit outcome and rows are unchanged`(): Unit =
        runBlocking {
            rawExec("CREATE TABLE s1_probe (id INTEGER PRIMARY KEY)")
            val failing = manager(OutsideUnitPolicy.FAIL)
            val result =
                uowOver(failing).write("P2.unit") {
                    failing.writeTx("P2.row") { exec("INSERT INTO s1_probe (id) VALUES (1)") }
                    afterCommit { failing.writeTx("P2.hook") { exec("INSERT INTO s1_probe (id) VALUES (2)") } }
                    Outcome.Ok("committed")
                }
            assertEquals(Outcome.Ok("committed"), result, "a failing hook never changes the outcome")
            assertEquals(1, rawCount("s1_probe"), "the unit's row is committed, the hook's row was refused")
            assertEquals(1L, failing.units.outsideUnitWrites["P2.hook"], "the hook's outside-unit write was counted")
        }

    @Test
    fun `P3 a nested Err with no thrown fault, ignored by an outer Ok, commits both writes - the documented design`(): Unit =
        runBlocking {
            db.createProbeTables()
            val uow = db.uow()
            val result =
                uow.write("P3.outer") {
                    dm.writeTx("P3.row1") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                    val inner =
                        uow.write<Unit>("P3.inner") {
                            dm.writeTx("P3.row2") { exec("INSERT INTO p5a_probe (id, v) VALUES (2, 2)") }
                            Outcome.Err(err("inner domain error"))
                        }
                    assertIs<Outcome.Err>(inner)
                    Outcome.Ok("outer decides")
                }
            assertEquals(Outcome.Ok("outer decides"), result)
            assertEquals(2, dm.countRows("p5a_probe"), "carry-in TP2: the outermost caller decides, so both writes commit")
        }

    @Test
    fun `P5 a write opened inside a read unit fails with IllegalStateException and nothing is written`(): Unit =
        runBlocking {
            db.createProbeTables()
            val uow = db.uow()
            assertFailsWith<IllegalStateException> {
                uow.read { uow.write("P5.write") { Outcome.Ok(Unit) } }
            }
            assertFailsWith<IllegalStateException> {
                uow.read { dm.writeTx("P5.store") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") } }
            }
            assertEquals(0, dm.countRows("p5a_probe"))
        }
}
