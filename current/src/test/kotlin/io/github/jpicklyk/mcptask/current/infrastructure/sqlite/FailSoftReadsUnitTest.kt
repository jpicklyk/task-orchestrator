package io.github.jpicklyk.mcptask.current.infrastructure.sqlite

import io.github.jpicklyk.mcptask.current.application.port.failSoftReads
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.readTx
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.writeTx
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Independent tests (blind test-author seat, item 85d49060) for the fail-soft read section of a write unit (`failSoftReads`),
 * scenarios S8 to S10 of the frozen test-plan, with its probes. The unit is the production `SqliteUnitOfWork` over a real SQLite
 * database; the reads and writes inside it are the production `readTx` / `writeTx` joined to that unit, and the probe table is the
 * shared `p5a_probe` of the unit tests.
 *
 * Oracles (never read from a run): the pre-existing "Poisoned units" contract of `UnitOfWork`: a fault thrown inside a unit, even
 * one a caller catches, is recorded and the outermost `write` never commits after it (an `Outcome.Ok` becomes a rollback and the
 * fault's translated `Outcome.Err`, a non-persistence fault is rethrown); and the frozen task-scope D2 rule: inside
 * `failSoftReads { ... }` a joined READ that fails with a fault that has no `SQLException` anywhere in its cause chain (and is not a
 * cancellation) is NOT recorded on the unit, so a caller that catches it can still commit; a SQL fault, a cancellation and every
 * fault of a joined WRITE keep poisoning; outside the section nothing changes.
 *
 * Arbitration (public evidence: the "Poisoned units" comment and `UnitRunnerTranslationTest` S9): a poisoned unit whose fault is not
 * a persistence fault rethrows that fault, so the poisoned legs with an IllegalArgumentException assert the thrown fault; the
 * legs whose fault is a SQL fault or wraps one (the translation of the result is the existing contract, not this item's) assert the
 * property under test, that the unit did NOT commit (the result is not `Outcome.Ok`) and no row survived.
 *
 * Negative-assertion fixture conditions: every "rolled back" assertion is paired with the S8 leg in which the same unit, the same
 * caught fault shape and the same rows DO commit when the fault is a decode-style one inside the section; every row count is taken
 * after the unit, from the table, with row 1 written BEFORE the fault so a rollback has something to remove.
 *
 * Domain invariants satisfied by construction: the probe table accepts any distinct integer id; rows are inserted through the joined
 * `writeTx` of the same unit.
 *
 * Forbidden-construct declaration: the helper `swallow` catches the fault inside the unit on purpose (the section's whole point is
 * that a caller catches a read fault and carries on); every assertion is outside the unit and outside that handler. No assumeTrue,
 * no @Disabled.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class FailSoftReadsUnitTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val db get() = sqlite.db
    private val dm get() = db.databaseManager

    private suspend fun insert(id: Int) {
        dm.writeTx("FailSoft.row.$id") { exec("INSERT INTO p5a_probe (id, v) VALUES ($id, $id)") }
    }

    /** Runs [block] and drops any exception it throws: the caller that catches a read fault and carries on. */
    private suspend fun swallow(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // the fault is deliberately dropped: the unit has already recorded (or not) the fault itself
        }
    }

    private suspend fun decodeFaultRead() {
        dm.readTx<Unit> { throw IllegalArgumentException("undecodable row") }
    }

    // ------------------------------------------------------------------ S8 a decode-style read fault in the section does not poison

    @Test
    fun `S8 a caught decode-style read fault inside failSoftReads does not stop the unit from committing`(): Unit =
        runBlocking {
            db.createProbeTables()
            var sectionValue = -1

            val result =
                db.uow().write("S8.failsoft") {
                    insert(1)
                    sectionValue =
                        failSoftReads {
                            swallow { decodeFaultRead() }
                            7
                        }
                    insert(2)
                    Outcome.Ok("committed")
                }

            assertEquals(Outcome.Ok("committed"), result, "the unit commits")
            assertEquals(7, sectionValue, "failSoftReads returns the value of its block")
            assertEquals(2, dm.countRows("p5a_probe"), "row 1 (before the section) and row 2 (after it) are both present")
            assertEquals(1, dm.probeValue(1))
            assertEquals(2, dm.probeValue(2))
        }

    @Test
    fun `S8 control the same caught read fault without failSoftReads poisons the unit and nothing commits`(): Unit =
        runBlocking {
            db.createProbeTables()

            val thrown =
                assertFailsWith<IllegalArgumentException> {
                    db.uow().write("S8.control") {
                        insert(1)
                        swallow { decodeFaultRead() }
                        insert(2)
                        Outcome.Ok("committed")
                    }
                }

            assertEquals("undecodable row", thrown.message, "the recorded non-persistence fault is rethrown")
            assertEquals(0, dm.countRows("p5a_probe"), "the unit rolled back, row 1 included")
        }

    // ------------------------------------------------------------------ S9 a SQL fault in the section still poisons

    @Test
    fun `S9 a SQL fault from a joined read inside failSoftReads still poisons the unit`(): Unit =
        runBlocking {
            db.createProbeTables()

            val result =
                runCatching {
                    db.uow().write("S9.sql") {
                        insert(1)
                        failSoftReads {
                            swallow { dm.readTx<Unit> { exec("SELECT * FROM table_that_does_not_exist_85d49060") { } } }
                        }
                        Outcome.Ok("committed")
                    }
                }

            assertFalse(result.getOrNull() is Outcome.Ok, "a unit that met a SQL fault never commits: $result")
            assertEquals(0, dm.countRows("p5a_probe"), "row 1 was rolled back")
        }

    @Test
    fun `S9 a fault that wraps a SQLException in its cause chain still poisons the unit`(): Unit =
        runBlocking {
            db.createProbeTables()

            val result =
                runCatching {
                    db.uow().write("S9.wrapped") {
                        insert(1)
                        failSoftReads {
                            swallow {
                                dm.readTx<Unit> { throw RuntimeException("wrapper", SQLException("injected persistence fault")) }
                            }
                        }
                        Outcome.Ok("committed")
                    }
                }

            assertFalse(result.getOrNull() is Outcome.Ok, "a SQLException anywhere in the cause chain keeps the poisoning: $result")
            assertEquals(0, dm.countRows("p5a_probe"), "row 1 was rolled back")
        }

    // ------------------------------------------------------------------ S10 a write fault in the section still poisons

    @Test
    fun `S10 a fault from a joined write inside failSoftReads still poisons the unit`(): Unit =
        runBlocking {
            db.createProbeTables()

            val thrown =
                assertFailsWith<IllegalArgumentException> {
                    db.uow().write("S10.write") {
                        insert(1)
                        failSoftReads {
                            swallow { dm.writeTx<Unit>("S10.fail") { throw IllegalArgumentException("write fault") } }
                        }
                        Outcome.Ok("committed")
                    }
                }

            assertEquals("write fault", thrown.message, "the recorded non-persistence fault is rethrown")
            assertEquals(0, dm.countRows("p5a_probe"), "row 1 was rolled back")
        }

    // ------------------------------------------------------------------ probes

    @Test
    fun `probe a plain read fault after the section in the same unit still poisons it`(): Unit =
        runBlocking {
            db.createProbeTables()

            val thrown =
                assertFailsWith<IllegalArgumentException> {
                    db.uow().write("Probe.after") {
                        insert(1)
                        failSoftReads { swallow { decodeFaultRead() } }
                        swallow { dm.readTx<Unit> { throw IllegalArgumentException("plain read fault") } }
                        Outcome.Ok("committed")
                    }
                }

            assertEquals("plain read fault", thrown.message, "it is the fault OUTSIDE the section that poisons, not the one inside")
            assertEquals(0, dm.countRows("p5a_probe"))
        }

    @Test
    fun `probe a plain read fault before the section in the same unit still poisons it`(): Unit =
        runBlocking {
            db.createProbeTables()

            val thrown =
                assertFailsWith<IllegalArgumentException> {
                    db.uow().write("Probe.before") {
                        insert(1)
                        swallow { dm.readTx<Unit> { throw IllegalArgumentException("early read fault") } }
                        failSoftReads { swallow { decodeFaultRead() } }
                        Outcome.Ok("committed")
                    }
                }

            assertEquals("early read fault", thrown.message, "entering the section does not clear a fault recorded before it")
            assertEquals(0, dm.countRows("p5a_probe"))
        }

    @Test
    fun `probe failSoftReads outside any unit leaves a fault unchanged`(): Unit =
        runBlocking {
            val thrown =
                assertFailsWith<IllegalArgumentException> {
                    failSoftReads { dm.readTx<Unit> { throw IllegalArgumentException("outside any unit") } }
                }

            assertEquals("outside any unit", thrown.message)
        }

    @Test
    fun `probe a cancellation from a read inside the section is rethrown and the unit does not commit`(): Unit =
        runBlocking {
            db.createProbeTables()

            val thrown =
                assertFailsWith<CancellationException> {
                    db.uow().write("Probe.cancel") {
                        insert(1)
                        failSoftReads { dm.readTx<Unit> { throw CancellationException("stop") } }
                        Outcome.Ok("committed")
                    }
                }

            assertEquals("stop", thrown.message)
            assertEquals(0, dm.countRows("p5a_probe"), "nothing committed")
        }
}
