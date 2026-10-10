package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.Table

/**
 * The ledger of applied once data steps. Mirrors `V23__Data_Steps.sql`.
 *
 * [name] is the text primary key (the step name). [appliedAt] is persisted through [UtcTimestampColumnType] as the
 * canonical 23-character UTC text. The table has no foreign key.
 */
object DataStepsTable : Table("data_steps") {
    val name = text("name")
    val appliedAt = utcTimestampText("applied_at")
    val rowsAffected = integer("rows_affected")
    val binaryVersion = text("binary_version")

    override val primaryKey = PrimaryKey(name)
}
