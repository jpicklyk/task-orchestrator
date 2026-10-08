package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import java.util.UUID

/**
 * An in-memory [EventStore] for tests over mocked repositories (no database, so nothing rolls back: tests of
 * rollback semantics use a real SQLite unit of work instead). Seqs start above [EventStore.SEQ_FLOOR].
 */
class InMemoryEventStore : EventStore {
    private val rows = ArrayList<EventRecord>()

    fun all(): List<EventRecord> = synchronized(rows) { rows.toList() }

    override suspend fun append(records: List<EventRecord>): List<EventRecord> =
        synchronized(rows) {
            records.map { record ->
                val next = (rows.lastOrNull()?.seq ?: EventStore.SEQ_FLOOR) + 1
                record.copy(seq = next).also { rows.add(it) }
            }
        }

    override suspend fun readAfter(
        afterSeq: Long,
        rootIds: Set<UUID>?,
        limit: Int
    ): List<EventRecord> =
        synchronized(rows) {
            rows.filter { it.seq > afterSeq && (rootIds == null || it.rootId in rootIds) }.take(limit)
        }

    override suspend fun maxSeq(): Long = synchronized(rows) { rows.lastOrNull()?.seq ?: EventStore.SEQ_FLOOR }
}
