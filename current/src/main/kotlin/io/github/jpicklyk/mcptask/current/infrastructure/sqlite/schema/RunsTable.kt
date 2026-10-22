package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * V32: one row per run. Query mapping only; Flyway (`V32__Runs.sql`) is the only schema path.
 *
 * [status], [pauseReason] and [terminalReason] hold the wire strings of the run enums; [budget], [hints] and [marks]
 * are JSON text. [scopeId] and [pauseDecisionId] have no foreign key on purpose (see the migration).
 */
object RunsTable : Table("runs") {
    val id = javaUuidSqlite("id")
    val rootId = javaUuidSqlite("root_id")
    val scopeId = javaUuidSqlite("scope_id").nullable()
    val label = text("label").nullable()
    val title = text("title").nullable()
    val status = text("status")
    val pauseReason = text("pause_reason").nullable()
    val pauseDecisionId = javaUuidSqlite("pause_decision_id").nullable()
    val terminalReason = text("terminal_reason").nullable()
    val openedBy = text("opened_by").nullable()
    val openedByKind = text("opened_by_kind").nullable()
    val openedAt = utcTimestampText("opened_at")
    val closedBy = text("closed_by").nullable()
    val closedAt = utcTimestampText("closed_at").nullable()
    val coordinatorSeenAt = utcTimestampText("coordinator_seen_at").nullable()
    val stallAfterSeconds = integer("stall_after_s")
    val budget = text("budget").nullable()
    val reportedTokens = long("reported_tokens").default(0)
    val reportedCostMicro = long("reported_cost_micro").default(0)
    val observedTokens = long("observed_tokens").default(0)
    val hints = text("hints").nullable()
    val marks = text("marks").nullable()
    val version = long("version").default(1)

    override val primaryKey = PrimaryKey(id)

    init {
        foreignKey(rootId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        index("idx_runs_root_status", false, rootId, status)
    }
}
