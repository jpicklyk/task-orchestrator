package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * V27: the typed traits of a work item, one row per (item, position). Query mapping only; Flyway
 * (`V27__Item_Traits.sql`) is the only schema path.
 */
object ItemTraitsTable : Table("item_traits") {
    val itemId: Column<java.util.UUID> = javaUuidSqlite("item_id")
    val position: Column<Int> = integer("position")
    val name: Column<String> = text("name")

    override val primaryKey = PrimaryKey(itemId, position)

    init {
        foreignKey(itemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
    }
}
