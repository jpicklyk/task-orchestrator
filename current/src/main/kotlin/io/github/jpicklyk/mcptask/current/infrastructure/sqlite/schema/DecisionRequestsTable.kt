package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * V36: one row per decision request. Query mapping only; Flyway (`V36__Decision_Requests.sql`) is the only schema path
 * (its partial unique index on `(root_id, scope_key, dedupe_key)` and its CHECKs live there).
 *
 * [runId] has no foreign key on purpose (see the migration). [decisionClass], [answerers], [severity], [status] and
 * [onExpiry] hold wire strings; [options] and [links] are JSON text parsed in Kotlin.
 */
object DecisionRequestsTable : Table("decision_requests") {
    val id = javaUuidSqlite("id")
    val rootId = javaUuidSqlite("root_id")
    val runId = javaUuidSqlite("run_id").nullable()
    val itemId = javaUuidSqlite("item_id").nullable()
    val decisionClass = text("class")
    val answerers = text("answerers")
    val severity = text("severity")
    val status = text("status")
    val dedupeKey = text("dedupe_key")
    val scopeKey = text("scope_key")
    val question = text("question")
    val options = text("options")
    val recommended = text("recommended").nullable()
    val links = text("links").nullable()
    val context = text("context").nullable()
    val raisedById = text("raised_by_id")
    val raisedByKind = text("raised_by_kind")
    val raisedBySeat = text("raised_by_seat").nullable()
    val raisedAt = utcTimestampText("raised_at")
    val expiresAt = utcTimestampText("expires_at").nullable()
    val onExpiry = text("on_expiry")
    val ackById = text("ack_by_id").nullable()
    val ackByKind = text("ack_by_kind").nullable()
    val ackAt = utcTimestampText("ack_at").nullable()
    val answerOption = text("answer_option").nullable()
    val answerText = text("answer_text").nullable()
    val answerRationale = text("answer_rationale").nullable()
    val answeredById = text("answered_by_id").nullable()
    val answeredByKind = text("answered_by_kind").nullable()
    val answeredByProof = text("answered_by_proof").nullable()
    val answeredHost = text("answered_host").nullable()
    val answeredSessionId = text("answered_session_id").nullable()
    val answeredRunId = javaUuidSqlite("answered_run_id").nullable()
    val answeredSeat = text("answered_seat").nullable()
    val answeredProfile = text("answered_profile").nullable()
    val answeredAt = utcTimestampText("answered_at").nullable()
    val afterExpiry = boolSqlite("after_expiry").default(false)
    val version = long("version").default(1)

    override val primaryKey = PrimaryKey(id)

    init {
        foreignKey(rootId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        foreignKey(itemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        index("idx_decision_requests_run_status", false, runId, status)
        index("idx_decision_requests_item", false, itemId)
        index("idx_decision_requests_root_status_severity", false, rootId, status, severity)
    }
}
