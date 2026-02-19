package io.github.jpicklyk.mcptask.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.Table

object RoleTransitionsTable : Table("role_transitions") {
    val id = text("id")
    val entityId = text("entity_id")
    val entityType = text("entity_type")
    val fromRole = text("from_role")
    val toRole = text("to_role")
    val fromStatus = text("from_status")
    val toStatus = text("to_status")
    val transitionedAt = text("transitioned_at")
    val trigger = text("trigger").nullable()
    val summary = text("summary").nullable()

    override val primaryKey = PrimaryKey(id)
}
