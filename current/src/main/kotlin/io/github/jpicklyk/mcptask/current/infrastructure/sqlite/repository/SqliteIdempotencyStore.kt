package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.IdempotencyRecord
import io.github.jpicklyk.mcptask.current.application.port.IdempotencyStore
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.IdempotencyRecordsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * SQLite implementation of [IdempotencyStore] over [IdempotencyRecordsTable].
 *
 * `created_at` is stored as the canonical fixed-width UTC text `yyyy-MM-dd HH:mm:ss.SSS` (millisecond
 * truncation), so text order equals time order and the expiry comparison is a bound-parameter
 * comparison against the index.
 */
class SqliteIdempotencyStore(
    private val databaseManager: DatabaseManager
) : IdempotencyStore {
    override suspend fun find(
        principalId: String,
        operation: String,
        key: String
    ): IdempotencyRecord? =
        databaseManager.readTx {
            IdempotencyRecordsTable
                .selectAll()
                .where {
                    (IdempotencyRecordsTable.principalId eq principalId) and
                        (IdempotencyRecordsTable.operation eq operation) and
                        (IdempotencyRecordsTable.key eq key)
                }.singleOrNull()
                ?.let(::toRecord)
        }

    override suspend fun upsert(record: IdempotencyRecord) {
        databaseManager.writeTx("IdempotencyStore.upsert") {
            IdempotencyRecordsTable.upsert(
                keys =
                    arrayOf(
                        IdempotencyRecordsTable.principalId,
                        IdempotencyRecordsTable.operation,
                        IdempotencyRecordsTable.key
                    ),
                onUpdate = {
                    it[IdempotencyRecordsTable.fingerprint] = record.fingerprint
                    it[IdempotencyRecordsTable.resultJson] = record.resultJson
                    it[IdempotencyRecordsTable.createdAt] = format(record.createdAt)
                }
            ) {
                it[principalId] = record.principalId
                it[operation] = record.operation
                it[key] = record.key
                it[fingerprint] = record.fingerprint
                it[resultJson] = record.resultJson
                it[createdAt] = format(record.createdAt)
            }
        }
    }

    override suspend fun insertIfAbsent(record: IdempotencyRecord): Boolean =
        databaseManager.writeTx("IdempotencyStore.insertIfAbsent") {
            val stmt =
                IdempotencyRecordsTable.insertIgnore {
                    it[principalId] = record.principalId
                    it[operation] = record.operation
                    it[key] = record.key
                    it[fingerprint] = record.fingerprint
                    it[resultJson] = record.resultJson
                    it[createdAt] = format(record.createdAt)
                }
            stmt.insertedCount > 0
        }

    override suspend fun deleteExpired(cutoff: Instant): Int =
        databaseManager.writeTx("IdempotencyStore.deleteExpired") {
            IdempotencyRecordsTable.deleteWhere { createdAt lessEq format(cutoff) }
        }

    private fun toRecord(row: ResultRow): IdempotencyRecord =
        IdempotencyRecord(
            principalId = row[IdempotencyRecordsTable.principalId],
            operation = row[IdempotencyRecordsTable.operation],
            key = row[IdempotencyRecordsTable.key],
            fingerprint = row[IdempotencyRecordsTable.fingerprint],
            resultJson = row[IdempotencyRecordsTable.resultJson],
            createdAt = parse(row[IdempotencyRecordsTable.createdAt])
        )

    internal companion object {
        /** The canonical persisted timestamp form; replaced by the shared UTC helper when it lands. */
        private val FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

        fun format(instant: Instant): String = FORMATTER.format(instant.truncatedTo(ChronoUnit.MILLIS))

        fun parse(text: String): Instant = Instant.from(FORMATTER.parse(text))
    }
}
