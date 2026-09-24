package io.github.jpicklyk.mcptask.current.infrastructure.database.schema

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import java.util.UUID

object RoleTransitionsTable : IdTable<UUID>("role_transitions") {
    override val id: Column<EntityID<UUID>> = javaUuidSqlite("id").autoGenerate().entityId()
    override val primaryKey = PrimaryKey(id)

    val itemId = javaUuidSqlite("item_id")
    val fromRole = varchar("from_role", 20)
    val toRole = varchar("to_role", 20)
    val fromStatusLabel = text("from_status_label").nullable()
    val toStatusLabel = text("to_status_label").nullable()
    val trigger = varchar("trigger", 50)
    val summary = text("summary").nullable()
    val transitionedAt = timestampSqlite("transitioned_at")
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

    /** JSON array of opaque credential/secret labels consumed by this transition (V14); null when omitted. */
    val consumedCredentials = text("consumed_credentials").nullable()

    init {
        foreignKey(itemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        index(isUnique = false, itemId)
        index(isUnique = false, transitionedAt)
    }
}
