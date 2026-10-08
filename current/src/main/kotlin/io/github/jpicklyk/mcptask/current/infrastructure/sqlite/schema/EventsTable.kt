package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.Table

/**
 * The append-only domain-event log. Mirrors `V20__Events.sql`.
 *
 * [seq] is `INTEGER PRIMARY KEY AUTOINCREMENT` (seeded above 1e12 by the migration), so it is never reused
 * and its order is commit order. [occurredAt] is persisted through [UtcTimestampColumnType] as the canonical
 * 23-character UTC text. The table has no foreign key: a row outlives the entity it describes.
 */
object EventsTable : Table("events") {
    val seq = long("seq").autoIncrement()
    val id = javaUuidSqlite("id")
    val occurredAt = utcTimestampText("occurred_at")
    val rootItemId = javaUuidSqlite("root_id")
    val entityKind = text("entity_kind")
    val entityId = javaUuidSqlite("entity_id")
    val type = text("type")
    val reqId = text("req_id").nullable()
    val principalId = text("principal_id").nullable()
    val principalKind = text("principal_kind").nullable()
    val proofStatus = text("proof_status").nullable()
    val host = text("host").nullable()
    val sessionId = text("session_id").nullable()
    val runId = javaUuidSqlite("run_id").nullable()
    val seat = text("seat").nullable()
    val data = text("data")

    override val primaryKey = PrimaryKey(seq)

    init {
        index("idx_events_root_seq", false, rootItemId, seq)
        index("idx_events_entity_seq", false, entityId, seq)
        index("idx_events_type_occurred", false, type, occurredAt)
        index("idx_events_req_id", false, reqId)
    }
}
