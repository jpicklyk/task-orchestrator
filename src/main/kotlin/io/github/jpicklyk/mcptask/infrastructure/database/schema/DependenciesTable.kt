package io.github.jpicklyk.mcptask.infrastructure.database.schema

import io.github.jpicklyk.mcptask.domain.model.DependencyType
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Exposed table definition for Dependencies.
 */
object DependenciesTable : UUIDTable("dependencies") {
    val fromTaskId = uuid("from_task_id")
    val toTaskId = uuid("to_task_id")
    val type = enumerationByName("type", 20, DependencyType::class)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex("unique_dependency", fromTaskId, toTaskId, type)
        foreignKey(fromTaskId to TaskTable.id)
        foreignKey(toTaskId to TaskTable.id)

        // Performance indexes for directional dependency lookups
        index(isUnique = false, fromTaskId)
        index(isUnique = false, toTaskId)
    }
}
