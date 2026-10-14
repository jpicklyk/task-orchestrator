package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * V35: one row per seat assignment. Query mapping only; Flyway (`V35__Run_Seats.sql`) is the only schema path (its two
 * partial unique indexes and its CHECKs live there).
 *
 * [itemId] is null only for the run-scoped `coordinator` row. [status] and [outcome] hold the wire strings of the seat
 * enums; [links] and [hints] are JSON text. [tokenHash] is the lowercase hex SHA-256 of the seat token.
 */
object RunSeatsTable : Table("run_seats") {
    val id = javaUuidSqlite("id")
    val runId = javaUuidSqlite("run_id")
    val itemId = javaUuidSqlite("item_id").nullable()
    val seat = text("seat")
    val attempt = integer("attempt")
    val principalId = text("principal_id")
    val tokenHash = text("token_hash")
    val status = text("status")
    val outcome = text("outcome").nullable()
    val summary = text("summary").nullable()
    val links = text("links").nullable()
    val hints = text("hints").nullable()
    val assignedAt = utcTimestampText("assigned_at")
    val endedAt = utcTimestampText("ended_at").nullable()
    val lastSeenAt = utcTimestampText("last_seen_at").nullable()
    val usageTokens = long("usage_tokens").default(0)
    val usageCostMicro = long("usage_cost_micro").default(0)

    override val primaryKey = PrimaryKey(id)

    init {
        foreignKey(runId to RunsTable.id, onDelete = ReferenceOption.CASCADE)
        foreignKey(itemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        index("idx_run_seats_run_status", false, runId, status)
        index("idx_run_seats_item", false, itemId)
    }
}
