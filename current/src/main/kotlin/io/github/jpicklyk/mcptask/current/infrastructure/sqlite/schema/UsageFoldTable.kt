package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.Table

/**
 * The fold watermark: a single row (`singleton = 1`) holding the last UTC day folded into `usage_daily` ([foldedThrough],
 * `yyyy-MM-dd`) and when that happened. Mirrors `V32__Usage_Daily.sql`. [foldedAt] is persisted through
 * [UtcTimestampColumnType] as the canonical 23-character UTC text.
 */
object UsageFoldTable : Table("usage_fold") {
    val singleton = integer("singleton")
    val foldedThrough = text("folded_through")
    val foldedAt = utcTimestampText("folded_at")

    override val primaryKey = PrimaryKey(singleton)
}
