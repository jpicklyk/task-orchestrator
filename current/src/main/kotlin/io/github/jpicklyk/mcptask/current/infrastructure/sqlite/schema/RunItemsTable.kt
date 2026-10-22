package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * V32: run membership, one row per (run, member item). Query mapping only; Flyway (`V32__Runs.sql`) is the only
 * schema path. Both keys cascade: a run delete drops its rows, and so does an item delete.
 */
object RunItemsTable : Table("run_items") {
    val runId = javaUuidSqlite("run_id")
    val itemId = javaUuidSqlite("item_id")
    val addedAt = utcTimestampText("added_at")

    override val primaryKey = PrimaryKey(runId, itemId)

    init {
        foreignKey(runId to RunsTable.id, onDelete = ReferenceOption.CASCADE)
        foreignKey(itemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        index("idx_run_items_item", false, itemId)
    }
}
