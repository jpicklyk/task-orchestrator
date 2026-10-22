package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.EventPage
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.port.PageRequest
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.EventsTable
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.UtcTimestampColumnType
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of [EventStore] over [EventsTable].
 *
 * [append] inserts one row at a time so each row gets its AUTOINCREMENT seq back; inside a unit the rows of
 * one unit are therefore contiguous (one writer transaction at a time). Root-scoped reads use the
 * `(root_id, seq)` index; unscoped reads walk the primary key.
 *
 * Backfilled rows (seq 1..N, below [EventStore.SEQ_FLOOR]) are history: [readAfter] and [maxSeq] are floored so the
 * stream never sees them, while [findByEntity] and [findByTypes] read them.
 *
 * [prune] is the retention delete path: it selects candidate seqs through `idx_events_type_occurred` (a range scan
 * that never walks kept rows) and deletes them by primary key.
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
        val from = maxOf(afterSeq, EventStore.SEQ_FLOOR)
        return databaseManager.readTx {
            EventsTable
                .selectAll()
                .where {
                    if (rootIds == null) {
                        EventsTable.seq greater from
                    } else {
                        (EventsTable.seq greater from) and (EventsTable.rootItemId inList rootIds)
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
        databaseManager
            .readTx {
                val maxExpr = EventsTable.seq.max()
                EventsTable.select(maxExpr).singleOrNull()?.get(maxExpr)
            }.let { maxOf(it ?: EventStore.SEQ_FLOOR, EventStore.SEQ_FLOOR) }

    override suspend fun findByEntity(
        entityId: UUID,
        types: Set<String>,
        page: PageRequest,
        since: Instant?,
        until: Instant?
    ): EventPage {
        if (types.isEmpty()) return EventPage(emptyList(), 0)
        return databaseManager.readTx {
            var condition: Op<Boolean> = (EventsTable.entityId eq entityId) and (EventsTable.type inList types)
            since?.let { condition = condition and (EventsTable.occurredAt greaterEq it) }
            until?.let { condition = condition and (EventsTable.occurredAt lessEq it) }
            val total =
                EventsTable
                    .selectAll()
                    .where { condition }
                    .count()
                    .toInt()
            val items =
                EventsTable
                    .selectAll()
                    .where { condition }
                    .orderBy(EventsTable.seq to SortOrder.DESC)
                    .limit(page.limit)
                    .offset(page.offset.toLong())
                    .map(::toRecord)
            EventPage(items, total)
        }
    }

    override suspend fun findByTypes(
        types: Set<String>,
        since: Instant?,
        until: Instant?,
        scope: AccessScope,
        page: PageRequest
    ): EventPage {
        if (types.isEmpty() || scope.rootIds?.isEmpty() == true) return EventPage(emptyList(), 0)
        return databaseManager.readTx {
            val conditions = ArrayList<Op<Boolean>>()
            conditions.add(EventsTable.type inList types)
            since?.let { conditions.add(EventsTable.occurredAt greaterEq it) }
            until?.let { conditions.add(EventsTable.occurredAt lessEq it) }
            scope.rootIds?.let { conditions.add(EventsTable.rootItemId inList it) }
            val tagScoped = scope.tagsInclude.isNotEmpty()
            if (tagScoped) conditions.add(TagAllowlistOp(WorkItemsTable.tags, scope.tagsInclude))
            val combined = conditions.reduce { acc, op -> acc and op }
            // A tag-scoped read joins the item row (an event whose item is gone has no tags and is invisible); the other
            // scopes read the events alone, so a deleted item's history stays visible to them.
            val source =
                if (tagScoped) {
                    EventsTable.join(WorkItemsTable, JoinType.INNER, EventsTable.entityId, WorkItemsTable.id)
                } else {
                    EventsTable
                }
            val total =
                source
                    .select(EventsTable.seq)
                    .where { combined }
                    .count()
                    .toInt()
            val items =
                source
                    .select(EventsTable.columns)
                    .where { combined }
                    .orderBy(EventsTable.seq to SortOrder.DESC)
                    .limit(page.limit)
                    .offset(page.offset.toLong())
                    .map(::toRecord)
            EventPage(items, total)
        }
    }

    override suspend fun prune(
        type: String,
        cutoff: Instant,
        protectFrom: Long,
        batch: Int
    ): Int {
        if (batch <= 0) return 0
        return databaseManager.writeTx("EventStore.prune") {
            val guarded = type == DomainEvent.CLAIM_EXPIRED
            val sql =
                "SELECT seq FROM events INDEXED BY idx_events_type_occurred " +
                    "WHERE type = ? AND occurred_at < ? AND seq < ?" +
                    (if (guarded) LATEST_PER_ENTITY_GUARD else "") +
                    " ORDER BY occurred_at LIMIT ?"
            val args =
                buildList<Pair<IColumnType<*>, Any?>> {
                    add(TextColumnType() to type)
                    // A FRESH column type, never EventsTable.occurredAt.columnType: exec(sql, args) sets nullable=true
                    // on each arg's column type, which would mutate the shared table definition (SchemaParityTest P-col).
                    add(UtcTimestampColumnType("TEXT") to cutoff)
                    add(LongColumnType() to protectFrom)
                    if (guarded) add(TextColumnType() to type)
                    add(IntegerColumnType() to batch)
                }
            val seqs =
                exec(sql, args) { rs ->
                    val found = ArrayList<Long>()
                    while (rs.next()) found.add(rs.getLong(1))
                    found
                } ?: emptyList()
            seqs.chunked(SQL_IN_CHUNK_SIZE).sumOf { chunk -> EventsTable.deleteWhere { EventsTable.seq inList chunk } }
        }
    }

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

    private companion object {
        /** Keeps each entity's newest row of the pruned type (applied to `claim.expired` only). */
        const val LATEST_PER_ENTITY_GUARD =
            " AND seq < (SELECT MAX(l.seq) FROM events l WHERE l.type = ? AND l.entity_id = events.entity_id)"
    }
}
