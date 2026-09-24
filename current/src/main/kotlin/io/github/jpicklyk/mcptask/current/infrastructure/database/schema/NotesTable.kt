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

    /** Always NULL since V17 — raw actor proofs (JWTs) are no longer persisted. Scrubbed for
     *  pre-V17 rows by the V17 migration. Never read back by the repository (defense in depth);
     *  see [actorProofSha256] / [actorProofClaims] for the persisted evidence. */
    val actorProof = text("actor_proof").nullable()

    /** V17: lowercase hex SHA-256 digest of the raw proof's UTF-8 bytes, when a proof was supplied. */
    val actorProofSha256 = text("actor_proof_sha256").nullable()

    /** V17: JSON-encoded [io.github.jpicklyk.mcptask.current.domain.model.ProofClaims], populated
     *  only for a VERIFIED proof. */
    val actorProofClaims = text("actor_proof_claims").nullable()
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
