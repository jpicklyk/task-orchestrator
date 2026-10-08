package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.Table

/**
 * Durable idempotency records. Mirrors `V19__Idempotency_Records.sql`.
 *
 * [createdAt] is a plain text column holding the canonical 23-character UTC form
 * (`yyyy-MM-dd HH:mm:ss.SSS`), formatted and parsed by `SqliteIdempotencyStore`; it deliberately does not
 * use an Exposed `timestamp` column type.
 */
object IdempotencyRecordsTable : Table("idempotency_records") {
    val principalId = text("principal_id")
    val operation = text("operation")
    val key = text("key")
    val fingerprint = text("fingerprint")
    val resultJson = text("result_json")
    val createdAt = text("created_at")

    override val primaryKey = PrimaryKey(principalId, operation, key)

    init {
        index("idx_idempotency_records_created_at", false, createdAt)
    }
}
