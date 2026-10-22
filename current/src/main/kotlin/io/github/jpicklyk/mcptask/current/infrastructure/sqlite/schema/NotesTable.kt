package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema

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
    val createdAt = utcTimestamp("created_at")
    val modifiedAt = utcTimestamp("modified_at")
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

    /** V28: 1 on create, +1 on every successful upsert of an existing note (written by the note store). */
    val version = long("version").default(1L)

    /** V34: the owning principal id; NULL = unowned (every pre-4.0 note). */
    val ownerId = text("owner_id").nullable()

    /** V35: attribution, the seat of the token that made the latest write; NULL without a token (every pre-4.0 note). */
    val seat = text("seat").nullable()

    /** V35: the seat half of the owner pair (written only with [ownerId]); NULL = a principal-level owner. */
    val ownerSeat = text("owner_seat").nullable()

    init {
        foreignKey(itemId to WorkItemsTable.id, onDelete = ReferenceOption.CASCADE)
        uniqueIndex(itemId, key)
        index(isUnique = false, itemId)
        index(isUnique = false, role)
    }
}
