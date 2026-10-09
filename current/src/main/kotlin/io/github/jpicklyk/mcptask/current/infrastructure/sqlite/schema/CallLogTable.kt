package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.Table

/**
 * The call log. Mirrors `V21__Call_Log.sql`.
 *
 * [reqId] is the 8-character text primary key. [at] is persisted through [UtcTimestampColumnType] as the canonical
 * 23-character UTC text. [replayed] is stored as INTEGER 0/1. The table has no foreign key.
 */
object CallLogTable : Table("call_log") {
    val reqId = text("req_id")
    val at = utcTimestampText("at")
    val principalId = text("principal_id").nullable()
    val principalKind = text("principal_kind").nullable()
    val proofStatus = text("proof_status").nullable()
    val host = text("host").nullable()
    val sessionId = text("session_id").nullable()
    val runId = javaUuidSqlite("run_id").nullable()
    val seat = text("seat").nullable()
    val surface = text("surface")
    val tool = text("tool")
    val operation = text("operation").nullable()
    val targetIds = text("target_ids").nullable()
    val targetVersions = text("target_versions").nullable()
    val requestShape = text("request_shape").nullable()
    val outcome = text("outcome")
    val errorCode = text("error_code").nullable()
    val attempts = integer("attempts")
    val latencyMs = long("latency_ms")
    val requestBytes = long("request_bytes").nullable()
    val responseBytes = long("response_bytes").nullable()
    val responseTokensEst = long("response_tokens_est").nullable()
    val tokenMethod = text("token_method").nullable()
    val replayed = boolSqlite("replayed")
    val batchSize = integer("batch_size").nullable()
    val failedCount = integer("failed_count").nullable()
    val resultCount = integer("result_count").nullable()
    val eligibleCount = integer("eligible_count").nullable()

    override val primaryKey = PrimaryKey(reqId)

    init {
        index("idx_call_log_at", false, at)
        index("idx_call_log_tool_at", false, tool, at)
    }
}
