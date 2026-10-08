package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.application.port.ActiveUnit
import io.github.jpicklyk.mcptask.current.application.port.UnitElement
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.coroutines.coroutineContext

/*
 * The transaction helpers. Together with UnitRunner these are the ONLY places that may open an Exposed
 * transaction (guarded by TransactionOpenerTest). Every repository method goes through one of them:
 *
 *  - Inside an ambient unit (a UnitElement in the coroutine context) the helper JOINS it: the same
 *    JdbcTransaction, no second connection, no retry, no commit. A unit read sees its own writes
 *    because inside a write unit even readTx uses the writer database. A throw out of a joined
 *    transaction is recorded on the unit (a poisoned unit never commits) and rethrown.
 *  - Outside a unit, readTx runs an implicit read on the reader pool and writeTx an outside-unit
 *    write, which is counted and then handled by the OutsideUnitPolicy: IMPLICIT runs an implicit
 *    write unit (writer Mutex, BUSY retry, last exception rethrown), FAIL throws OutsideUnitWriteException.
 */

/**
 * Runs [block] in a read transaction: joins the ambient unit when there is one, otherwise runs an
 * implicit read on the reader pool.
 */
suspend fun <T> DatabaseManager.readTx(block: suspend JdbcTransaction.() -> T): T {
    val unit = coroutineContext[UnitElement]?.unit
    return if (unit != null) {
        joined(unit) { suspendTransaction(db = if (unit.writable) writer() else reader()) { block() } }
    } else {
        units.implicitRead(block)
    }
}

/**
 * Runs [block] in a write transaction labelled [op] (`<Repo>.<method>`): joins the ambient write
 * unit when there is one, otherwise runs an implicit write unit that is counted by
 * `UnitRunner.outsideUnitWrites`.
 *
 * @throws IllegalStateException when the ambient unit is a read unit
 */
suspend fun <T> DatabaseManager.writeTx(
    op: String,
    block: suspend JdbcTransaction.() -> T
): T {
    val unit = coroutineContext[UnitElement]?.unit
    return if (unit != null) {
        check(unit.writable) { "write '$op' attempted inside a read unit" }
        joined(unit) { suspendTransaction(db = writer()) { block() } }
    } else {
        units.implicitWrite(op, block)
    }
}

/**
 * Runs a transaction JOINED to [unit]. A throw out of it means Exposed already rolled the shared connection
 * back, so the fault is recorded on the unit (poisoning it: the runner never commits it) and rethrown.
 */
private inline fun <T> joined(
    unit: ActiveUnit,
    tx: () -> T
): T =
    try {
        tx()
    } catch (e: Throwable) {
        e.rethrowIfCancellation()
        unit.recordFault(e)
        throw e
    }

/**
 * Blocking read for the few non-suspend callers. Joins the innermost open transaction of any
 * database on this thread, otherwise opens a reader transaction.
 */
fun <T> DatabaseManager.readTxBlocking(block: JdbcTransaction.() -> T): T {
    val database = TransactionManager.currentOrNull()?.db ?: reader()
    return transaction(db = database) { block() }
}
