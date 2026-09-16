package io.github.jpicklyk.mcptask.current.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import java.util.UUID

object NotesTable : IdTable<UUID>("notes") {
    override val id: Column<EntityID<UUID>> = javaUuidSqlite("id").autoGenerate().entityId()
    override val primaryKey = PrimaryKey(id)

    val itemId = javaUuidSqlite("work_item_id")
    val key = varchar("key", 200)
    val role = varchar("role", 20)
    val body = text("body").default("")
    val createdAt = timestampSqlite("created_at")
    val modifiedAt = timestampSqlite("modified_at")
    val actorId = text("actor_id").nullable()
    val actorKind = text("actor_kind").nullable()
    val actorParent = text("actor_parent").nullable()
    val actorProof = text("actor_proof").nullable()
    val verificationStatus = text("verification_status").nullable()
    val verificationVerifier = text("verification_verifier").nullable()
    val verificationReason = text("verification_reason").nullable()

    init {
        foreignKey(itemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        uniqueIndex(itemId, key)
        index(isUnique = false, itemId)
        index(isUnique = false, role)
    }
}
