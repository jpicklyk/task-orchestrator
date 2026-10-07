package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.application.port.UnitElement
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
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
 *    because inside a write unit even readTx uses the writer database.
 *  - Outside a unit, readTx runs an implicit read on the reader pool and writeTx an implicit write
 *    unit (writer Mutex, BUSY retry, counted as an outside-unit write, last exception rethrown).
 */

/**
 * Runs [block] in a read transaction: joins the ambient unit when there is one, otherwise runs an
 * implicit read on the reader pool.
 */
suspend fun <T> DatabaseManager.readTx(block: suspend JdbcTransaction.() -> T): T {
    val unit = coroutineContext[UnitElement]?.unit
    return if (unit != null) {
        suspendTransaction(db = if (unit.writable) writer() else reader()) { block() }
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
        suspendTransaction(db = writer()) { block() }
    } else {
        units.implicitWrite(op, block)
    }
}

/**
 * Blocking read for the few non-suspend callers. Joins the innermost open transaction of any
 * database on this thread, otherwise opens a reader transaction.
 */
fun <T> DatabaseManager.readTxBlocking(block: JdbcTransaction.() -> T): T {
    val database = TransactionManager.currentOrNull()?.db ?: reader()
    return transaction(db = database) { block() }
}

/**
 * Legacy bridge for the `Result`-returning stores (until P5b): [readTx] with any non-cancellation
 * exception mapped to [Result.Error] carrying a [RepositoryError.DatabaseError].
 */
suspend fun <T> DatabaseManager.readResult(
    errorMessage: String,
    block: suspend JdbcTransaction.() -> Result<T>
): Result<T> =
    try {
        readTx(block)
    } catch (e: Exception) {
        e.rethrowIfCancellation()
        Result.Error(RepositoryError.DatabaseError("$errorMessage: ${e.message}", e))
    }

/** Legacy bridge for the `Result`-returning stores (until P5b): [writeTx] with the same mapping as [readResult]. */
suspend fun <T> DatabaseManager.writeResult(
    op: String,
    errorMessage: String,
    block: suspend JdbcTransaction.() -> Result<T>
): Result<T> =
    try {
        writeTx(op, block)
    } catch (e: Exception) {
        e.rethrowIfCancellation()
        Result.Error(RepositoryError.DatabaseError("$errorMessage: ${e.message}", e))
    }
