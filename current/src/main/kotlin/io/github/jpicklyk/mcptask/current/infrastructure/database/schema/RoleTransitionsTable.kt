package io.github.jpicklyk.mcptask.current.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

object RoleTransitionsTable : UUIDTable("role_transitions") {
    val itemId = uuid("item_id")
    val fromRole = varchar("from_role", 20)
    val toRole = varchar("to_role", 20)
    val fromStatusLabel = text("from_status_label").nullable()
    val toStatusLabel = text("to_status_label").nullable()
    val trigger = varchar("trigger", 50)
    val summary = text("summary").nullable()
    val transitionedAt = timestamp("transitioned_at")
    val actorId = text("actor_id").nullable()
    val actorKind = text("actor_kind").nullable()
    val actorParent = text("actor_parent").nullable()
    val actorProof = text("actor_proof").nullable()
    val verificationStatus = text("verification_status").nullable()
    val verificationVerifier = text("verification_verifier").nullable()
    val verificationReason = text("verification_reason").nullable()

    init {
        foreignKey(itemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        index(isUnique = false, itemId)
        index(isUnique = false, transitionedAt)
    }
}
