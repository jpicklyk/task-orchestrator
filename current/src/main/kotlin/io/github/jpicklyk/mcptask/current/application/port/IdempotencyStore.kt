package io.github.jpicklyk.mcptask.current.application.port

import java.time.Instant

/**
 * One stored idempotent result: who asked ([principalId]), what ([operation]), under which
 * client key ([key]), a digest of the request ([fingerprint]), the encoded outcome
 * ([resultJson]) and when it was stored ([createdAt]).
 */
data class IdempotencyRecord(
    val principalId: String,
    val operation: String,
    val key: String,
    val fingerprint: String,
    val resultJson: String,
    val createdAt: Instant
)

/**
 * Durable idempotency records, keyed `(principalId, operation, key)`.
 *
 * Every call runs inside a unit of work (production policy rejects outside-unit writes); a database
 * failure is thrown, like every other store. TTL is the caller decision: [find] returns the row
 * as stored, expired or not.
 */
interface IdempotencyStore {
    suspend fun find(
        principalId: String,
        operation: String,
        key: String
    ): IdempotencyRecord?

    /** Inserts [record], or replaces the row with the same primary key (an expired record being reused). */
    suspend fun upsert(record: IdempotencyRecord)

    /** Inserts [record] unless the primary key exists; true when a row was inserted. */
    suspend fun insertIfAbsent(record: IdempotencyRecord): Boolean

    /** Deletes every record with `createdAt <= cutoff`; returns the number of rows removed. */
    suspend fun deleteExpired(cutoff: Instant): Int
}
