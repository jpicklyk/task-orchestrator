package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.Table

/**
 * The per-day summary of `call_log`. Mirrors `V33__Usage_Daily.sql`; written and read through raw SQL by
 * `SqliteUsageDailyStore` and `SqliteReportQueries`, so this mapping only declares the schema.
 *
 * Every dimension is NOT NULL: an absent value is the sentinel `''` (TEXT dimensions) or the zero-length blob (`root_id`;
 * a real id is 16 bytes), so the composite primary key can drive an idempotent upsert. A consumer that joins [rootId] to
 * `work_items.id` must exclude the zero-length sentinel. [day] is the UTC date `yyyy-MM-dd`.
 */
object UsageDailyTable : Table("usage_daily") {
    val day = text("day")
    val rootId = binary("root_id")
    val tool = text("tool")
    val operation = text("operation")
    val surface = text("surface")
    val host = text("host")
    val principalKind = text("principal_kind")
    val outcome = text("outcome")
    val errorCode = text("error_code")
    val calls = long("calls")
    val errors = long("errors")
    val requestBytes = long("request_bytes")
    val responseBytes = long("response_bytes")
    val responseTokensEst = long("response_tokens_est")
    val latencyMsSum = long("latency_ms_sum")
    val latencyMsMax = long("latency_ms_max")
    val retries = long("retries")
    val replays = long("replays")

    override val primaryKey = PrimaryKey(day, rootId, tool, operation, surface, host, principalKind, outcome, errorCode)
}
