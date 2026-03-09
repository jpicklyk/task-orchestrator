package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction

/**
 * Wraps a suspended database transaction with standardized error handling.
 *
 * The [block] executes inside [newSuspendedTransaction] and may return any [Result].
 * Any exception thrown by [block] (including from domain validation) is caught and
 * converted to [Result.Error] with a [RepositoryError.DatabaseError].
 *
 * @param errorMessage prefix for the error message if an exception is thrown
 * @param block the transaction body, which must return a [Result]
 */
suspend fun <T> DatabaseManager.suspendedTransaction(
    errorMessage: String,
    block: suspend () -> Result<T>
): Result<T> =
    try {
        newSuspendedTransaction(db = getDatabase()) {
            block()
        }
    } catch (e: Exception) {
        Result.Error(RepositoryError.DatabaseError("$errorMessage: ${e.message}", e))
    }
