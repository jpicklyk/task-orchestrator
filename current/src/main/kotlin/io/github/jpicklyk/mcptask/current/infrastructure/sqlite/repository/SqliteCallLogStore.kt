package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.CallLogAppendResult
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.CallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.CallLogTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * SQLite implementation of [CallLogStore] over [CallLogTable].
 *
 * [append] inserts each row with `INSERT OR IGNORE`, so a duplicate `req_id` (40-bit keys can collide) drops only
 * that row and never fails or poisons the batch. It joins the ambient unit like [SqliteEventStore].
 */
class SqliteCallLogStore(
    private val databaseManager: DatabaseManager
) : CallLogStore {
    override suspend fun append(records: List<CallLogRecord>): Int = appendCounted(records).inserted

    override suspend fun appendCounted(records: List<CallLogRecord>): CallLogAppendResult {
        if (records.isEmpty()) return CallLogAppendResult(0)
        return databaseManager.writeTx("CallLogStore.append") {
            var inserted = 0
            var rejected = 0
            for (record in records) {
                val stmt =
                    CallLogTable.insertIgnore {
                        it[reqId] = record.reqId
                        it[at] = record.at
                        it[principalId] = record.principalId
                        it[principalKind] = record.principalKind
                        it[proofStatus] = record.proofStatus
                        it[host] = record.host
                        it[sessionId] = record.sessionId
                        it[runId] = record.runId
                        it[seat] = record.seat
                        it[surface] = record.surface
                        it[tool] = record.tool
                        it[operation] = record.operation
                        it[targetIds] = record.targetIds
                        it[targetVersions] = record.targetVersions
                        it[requestShape] = record.requestShape
                        it[outcome] = record.outcome
                        it[errorCode] = record.errorCode
                        it[attempts] = record.attempts
                        it[latencyMs] = record.latencyMs
                        it[requestBytes] = record.requestBytes
                        it[responseBytes] = record.responseBytes
                        it[responseTokensEst] = record.responseTokensEst
                        it[tokenMethod] = record.tokenMethod
                        it[replayed] = record.replayed
                        it[batchSize] = record.batchSize
                        it[failedCount] = record.failedCount
                        it[resultCount] = record.resultCount
                        it[eligibleCount] = record.eligibleCount
                    }
                val count = stmt.insertedCount
                inserted += count
                // INSERT OR IGNORE also skips CHECK / NOT NULL violations: only a req_id conflict is a duplicate.
                if (count == 0 && CallLogTable.selectAll().where { CallLogTable.reqId eq record.reqId }.empty()) rejected++
            }
            CallLogAppendResult(inserted, rejected)
        }
    }
}
