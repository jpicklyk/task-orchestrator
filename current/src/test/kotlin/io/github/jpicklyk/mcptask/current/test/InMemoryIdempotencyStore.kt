package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.port.IdempotencyRecord
import io.github.jpicklyk.mcptask.current.application.port.IdempotencyStore
import java.time.Instant

/**
 * An in-memory [IdempotencyStore] for tests that run keyed tool calls over mocked repositories (the
 * default no-transaction unit of work has no database, so nothing rolls back: tests of rollback
 * semantics use a real SQLite unit of work instead).
 */
class InMemoryIdempotencyStore : IdempotencyStore {
    private val records = LinkedHashMap<Triple<String, String, String>, IdempotencyRecord>()

    val size: Int get() = synchronized(records) { records.size }

    fun all(): List<IdempotencyRecord> = synchronized(records) { records.values.toList() }

    override suspend fun find(
        principalId: String,
        operation: String,
        key: String
    ): IdempotencyRecord? = synchronized(records) { records[Triple(principalId, operation, key)] }

    override suspend fun upsert(record: IdempotencyRecord) {
        synchronized(records) { records[Triple(record.principalId, record.operation, record.key)] = record }
    }

    override suspend fun insertIfAbsent(record: IdempotencyRecord): Boolean =
        synchronized(records) {
            val id = Triple(record.principalId, record.operation, record.key)
            if (records.containsKey(id)) {
                false
            } else {
                records[id] = record
                true
            }
        }

    override suspend fun deleteExpired(cutoff: Instant): Int =
        synchronized(records) {
            val expired = records.filterValues { !it.createdAt.isAfter(cutoff) }.keys
            expired.forEach { records.remove(it) }
            expired.size
        }
}
