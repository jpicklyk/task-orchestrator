package io.github.jpicklyk.mcptask.current.infrastructure.database

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.VersionConflictException
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import org.sqlite.SQLiteException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Classifies persistence faults by walking the cause chain (ExposedSQLException -> SQLiteException
 * result code) and translates them into the error catalog once, at the unit-of-work boundary.
 *
 * Only a throwable that IS a [SQLException] (or a [PerRootConfigUnavailableException], translated by
 * its cause) is a persistence fault; every other exception (validation, marker, illegal-state) is not
 * translated and callers rethrow it unchanged. [DomainError.message] keeps the INNERMOST SQL
 * exception message, so the 3.x error text can still be reproduced from it.
 *
 * The one non-SQL row: a [VersionConflictException] (a store's lost optimistic-locking race) becomes
 * `version_conflict` with both versions in [ErrorDetail.VersionConflict].
 */
object PersistenceFaults {
    /** What kind of persistence fault a throwable is. */
    enum class Fault { BUSY, POOL_TIMEOUT, DUPLICATE, FOREIGN_KEY, READONLY, OTHER_SQL }

    /** Retry hint on `unavailable` after BUSY exhausted the unit deadline. */
    const val BUSY_RETRY_AFTER_MS = 1000L

    /** Retry hint on `unavailable` after a pool-checkout timeout. */
    const val POOL_RETRY_AFTER_MS = 2000L

    /** True when [t] or a cause is a SQLITE_BUSY or SQLITE_LOCKED result code (the whole unit may be retried). */
    fun isBusy(t: Throwable): Boolean = classify(t) == Fault.BUSY

    /** The fault class of [t], or null when [t] is not a persistence fault. */
    fun classify(t: Throwable): Fault? {
        if (t !is SQLException && !(t is PerRootConfigUnavailableException && chain(t).any { it is SQLException })) return null
        val causes = chain(t)
        if (causes.any { it is SQLTransientConnectionException }) return Fault.POOL_TIMEOUT
        val codes = causes.mapNotNull { (it as? SQLiteException)?.resultCode?.name }
        if (codes.any {
                it == "SQLITE_BUSY" ||
                    it.startsWith(
                        "SQLITE_BUSY_"
                    ) ||
                    it == "SQLITE_LOCKED" ||
                    it.startsWith("SQLITE_LOCKED_")
            }
        ) {
            return Fault.BUSY
        }
        if (codes.any { it.startsWith("SQLITE_READONLY") }) return Fault.READONLY
        val messages = causes.mapNotNull { it.message }
        if (codes.any { it == "SQLITE_CONSTRAINT_UNIQUE" || it == "SQLITE_CONSTRAINT_PRIMARYKEY" } ||
            messages.any { it.contains("UNIQUE constraint failed") || it.contains("PRIMARY KEY constraint failed") }
        ) {
            return Fault.DUPLICATE
        }
        if (codes.any { it == "SQLITE_CONSTRAINT_FOREIGNKEY" } || messages.any { it.contains("FOREIGN KEY constraint failed") }) {
            return Fault.FOREIGN_KEY
        }
        return Fault.OTHER_SQL
    }

    /**
     * Translates [t] to a catalog error, or null when [t] is not a persistence fault (rethrow it
     * unchanged). A BUSY fault that reaches this point has exhausted the unit deadline.
     */
    fun translate(t: Throwable): DomainError? {
        val conflict = chain(t).firstOrNull { it is VersionConflictException } as VersionConflictException?
        if (conflict != null) return versionConflict(conflict)
        val fault = classify(t) ?: return null
        val inner = innermostMessage(t)
        return when (fault) {
            Fault.BUSY ->
                unavailable("Database is busy: $inner", BUSY_RETRY_AFTER_MS)
            Fault.POOL_TIMEOUT ->
                unavailable("No database connection became available in time: $inner", POOL_RETRY_AFTER_MS)
            Fault.DUPLICATE ->
                DomainError(
                    code = ErrorCode.DUPLICATE,
                    message = "Duplicate record: $inner",
                    detail = ErrorDetail.Duplicate(kind = entityKindOf(inner), id = null, existingId = null),
                    fixArgs = mapOf("kind" to entityKindOf(inner).name.lowercase(), "existingId" to "that already holds this value")
                )
            Fault.FOREIGN_KEY ->
                DomainError(
                    code = ErrorCode.NOT_FOUND,
                    message = "Referenced record not found: $inner",
                    detail = ErrorDetail.NotFound(kind = EntityKind.ITEM, id = null),
                    fixArgs = mapOf("kind" to "item", "id" to "named in the request")
                )
            Fault.READONLY ->
                DomainError(ErrorCode.INTERNAL, "A write was routed to the read-only reader: $inner")
            Fault.OTHER_SQL ->
                DomainError(ErrorCode.INTERNAL, "Database error: $inner")
        }
    }

    private fun versionConflict(e: VersionConflictException): DomainError =
        DomainError(
            code = ErrorCode.VERSION_CONFLICT,
            message = VersionConflictException.MESSAGE,
            detail = ErrorDetail.VersionConflict(kind = EntityKind.ITEM, id = e.id.toString(), expected = e.expected, actual = e.actual),
            fixArgs = mapOf("kind" to "item", "id" to e.id.toString(), "actual" to e.actual.toString())
        )

    private fun unavailable(
        message: String,
        retryAfterMs: Long
    ): DomainError = DomainError(ErrorCode.UNAVAILABLE, message, ErrorDetail.Unavailable(retryAfterMs))

    /** The message of the deepest SQL exception in the chain (else the deepest throwable). */
    private fun innermostMessage(t: Throwable): String {
        val causes = chain(t)
        val innermost = causes.lastOrNull { it is SQLException } ?: causes.last()
        return innermost.message?.takeIf { it.isNotBlank() } ?: innermost.javaClass.simpleName
    }

    /** Best-effort entity family from `UNIQUE constraint failed: <table>.<column>` text. */
    private fun entityKindOf(message: String): EntityKind =
        when {
            message.contains("failed: notes.") -> EntityKind.NOTE
            message.contains("failed: dependencies.") -> EntityKind.DEPENDENCY
            message.contains("failed: plan_documents.") -> EntityKind.DOCUMENT
            else -> EntityKind.ITEM
        }

    private fun chain(t: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val out = ArrayList<Throwable>()
        var current: Throwable? = t
        while (current != null && seen.add(current) && out.size < MAX_CHAIN) {
            out.add(current)
            current = current.cause
        }
        return out
    }

    private const val MAX_CHAIN = 32
}
