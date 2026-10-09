package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.EventsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/**
 * SQLite implementation of [EventStore] over [EventsTable].
 *
 * [append] inserts one row at a time so each row gets its AUTOINCREMENT seq back; inside a unit the rows of
 * one unit are therefore contiguous (one writer transaction at a time). Root-scoped reads use the
 * `(root_id, seq)` index; unscoped reads walk the primary key.
 */
class SqliteEventStore(
    private val databaseManager: DatabaseManager
) : EventStore {
    override suspend fun append(records: List<EventRecord>): List<EventRecord> {
        if (records.isEmpty()) return emptyList()
        return databaseManager.writeTx("EventStore.append") {
            records.map { record ->
                val seq =
                    EventsTable.insert {
                        it[id] = record.id
                        it[occurredAt] = record.occurredAt
                        it[rootItemId] = record.rootId
                        it[entityKind] = record.entityKind
                        it[entityId] = record.entityId
                        it[type] = record.type
                        it[reqId] = record.reqId
                        it[principalId] = record.principalId
                        it[principalKind] = record.principalKind
                        it[proofStatus] = record.proofStatus
                        it[host] = record.host
                        it[sessionId] = record.sessionId
                        it[runId] = record.runId
                        it[seat] = record.seat
                        it[data] = record.data
                    } get EventsTable.seq
                record.copy(seq = seq)
            }
        }
    }

    override suspend fun readAfter(
        afterSeq: Long,
        rootIds: Set<UUID>?,
        limit: Int
    ): List<EventRecord> {
        if (rootIds != null && rootIds.isEmpty()) return emptyList()
        return databaseManager.readTx {
            EventsTable
                .selectAll()
                .where {
                    if (rootIds == null) {
                        EventsTable.seq greater afterSeq
                    } else {
                        (EventsTable.seq greater afterSeq) and (EventsTable.rootItemId inList rootIds)
                    }
                }.orderBy(EventsTable.seq to SortOrder.ASC)
                .limit(limit)
                .map(::toRecord)
        }
    }

    override suspend fun latestOfType(
        type: String,
        entityIds: Set<UUID>
    ): Map<UUID, EventRecord> {
        if (entityIds.isEmpty()) return emptyMap()
        return databaseManager.readTx {
            val latest = HashMap<UUID, EventRecord>()
            val maxSeq = EventsTable.seq.max()
            for (chunk in entityIds.chunked(SQL_IN_CHUNK_SIZE - 1)) {
                // Select only each entity's newest seq, then load just those rows, rather than every row of the type.
                val newestSeqs =
                    EventsTable
                        .select(EventsTable.entityId, maxSeq)
                        .where { (EventsTable.type eq type) and (EventsTable.entityId inList chunk) }
                        .groupBy(EventsTable.entityId)
                        .map { it[maxSeq] }
                        .filterNotNull()
                if (newestSeqs.isEmpty()) continue
                EventsTable
                    .selectAll()
                    .where { EventsTable.seq inList newestSeqs }
                    .forEach { row -> toRecord(row).let { latest[it.entityId] = it } }
            }
            latest
        }
    }

    override suspend fun maxSeq(): Long =
        databaseManager.readTx {
            val maxExpr = EventsTable.seq.max()
            EventsTable.select(maxExpr).singleOrNull()?.get(maxExpr)
        } ?: EventStore.SEQ_FLOOR

    private fun toRecord(row: ResultRow): EventRecord =
        EventRecord(
            seq = row[EventsTable.seq],
            id = row[EventsTable.id],
            occurredAt = row[EventsTable.occurredAt],
            rootId = row[EventsTable.rootItemId],
            entityKind = row[EventsTable.entityKind],
            entityId = row[EventsTable.entityId],
            type = row[EventsTable.type],
            reqId = row[EventsTable.reqId],
            principalId = row[EventsTable.principalId],
            principalKind = row[EventsTable.principalKind],
            proofStatus = row[EventsTable.proofStatus],
            host = row[EventsTable.host],
            sessionId = row[EventsTable.sessionId],
            runId = row[EventsTable.runId],
            seat = row[EventsTable.seat],
            data = row[EventsTable.data]
        )
}
