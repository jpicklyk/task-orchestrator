package io.github.jpicklyk.mcptask.current.domain.event

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import java.time.Instant
import java.util.UUID

/**
 * The typed domain-event catalog (plan section 3.7). One instance becomes one row of the `events` table.
 *
 * Every event names its [type] (the stable wire string), the entity it is about ([entityKind], [entityId]) and the
 * root it belongs to ([rootId], never null: a root item uses its own id). [payload] is the event-specific data
 * (primitives, strings, UUIDs and lists of them), stored as the row's JSON `data`. [entityActor] and
 * [entityVerification] are the actor and verification the entity itself carries (a note's or a transition's
 * claim); when absent the recorder falls back to the ambient event actor.
 *
 * `claim.expired` and `lease.expired` are produced by `ClaimService` (lazy detection plus the hourly sweep).
 */
sealed class DomainEvent {
    abstract val type: String
    abstract val entityKind: String
    abstract val entityId: UUID
    abstract val rootId: UUID

    /** The event-specific data; serialized as the row's JSON `data`. */
    abstract fun payload(): Map<String, Any?>

    open val entityActor: ActorClaim? get() = null
    open val entityVerification: VerificationResult? get() = null

    // ---- items ----

    data class ItemCreated(
        override val entityId: UUID,
        override val rootId: UUID,
        val parentId: UUID?
    ) : DomainEvent() {
        override val type: String get() = ITEM_CREATED
        override val entityKind: String get() = KIND_ITEM

        override fun payload(): Map<String, Any?> = mapOf("parentId" to parentId)
    }

    data class ItemUpdated(
        override val entityId: UUID,
        override val rootId: UUID,
        val changedFields: List<String>
    ) : DomainEvent() {
        override val type: String get() = ITEM_UPDATED
        override val entityKind: String get() = KIND_ITEM

        override fun payload(): Map<String, Any?> = mapOf("changedFields" to changedFields)
    }

    /** One of the two rows a reparent writes: [side] `left` under the old root, `entered` under the new root. */
    data class ItemReparented(
        override val entityId: UUID,
        override val rootId: UUID,
        val side: ReparentSide,
        val fromParentId: UUID?,
        val toParentId: UUID?
    ) : DomainEvent() {
        override val type: String get() = ITEM_REPARENTED
        override val entityKind: String get() = KIND_ITEM

        override fun payload(): Map<String, Any?> = mapOf("side" to side.wire, "fromParentId" to fromParentId, "toParentId" to toParentId)
    }

    data class ItemDeleted(
        override val entityId: UUID,
        override val rootId: UUID
    ) : DomainEvent() {
        override val type: String get() = ITEM_DELETED
        override val entityKind: String get() = KIND_ITEM

        override fun payload(): Map<String, Any?> = emptyMap()
    }

    // ---- transitions ----

    data class ItemTransitioned(
        override val entityId: UUID,
        override val rootId: UUID,
        val trigger: String,
        val fromRole: String,
        val toRole: String,
        val fromStatusLabel: String?,
        val toStatusLabel: String?,
        val origin: TransitionOrigin,
        val actor: ActorClaim? = null,
        val verification: VerificationResult? = null
    ) : DomainEvent() {
        override val type: String get() = ITEM_TRANSITIONED
        override val entityKind: String get() = KIND_ITEM
        override val entityActor: ActorClaim? get() = actor
        override val entityVerification: VerificationResult? get() = verification

        override fun payload(): Map<String, Any?> =
            mapOf(
                "trigger" to trigger,
                "fromRole" to fromRole,
                "toRole" to toRole,
                "fromStatusLabel" to fromStatusLabel,
                "toStatusLabel" to toStatusLabel,
                "origin" to origin.wire
            )
    }

    data class TransitionRejected(
        override val entityId: UUID,
        override val rootId: UUID,
        val trigger: String,
        val code: String,
        val missingKeys: List<String> = emptyList(),
        val blockerIds: List<UUID> = emptyList(),
        val actor: ActorClaim? = null,
        val verification: VerificationResult? = null
    ) : DomainEvent() {
        override val type: String get() = TRANSITION_REJECTED
        override val entityKind: String get() = KIND_ITEM
        override val entityActor: ActorClaim? get() = actor
        override val entityVerification: VerificationResult? get() = verification

        override fun payload(): Map<String, Any?> =
            mapOf("trigger" to trigger, "code" to code, "missingKeys" to missingKeys, "blockerIds" to blockerIds)
    }

    // ---- notes ----

    data class NoteUpserted(
        override val entityId: UUID,
        override val rootId: UUID,
        val itemId: UUID,
        val key: String,
        val role: String,
        val bodyLength: Int,
        val actor: ActorClaim? = null,
        val verification: VerificationResult? = null,
        /** The server-side path the caller supplied via `bodyFromFile` (never the file contents); null for an inline body. */
        val bodyFromFile: String? = null
    ) : DomainEvent() {
        override val type: String get() = NOTE_UPSERTED
        override val entityKind: String get() = KIND_NOTE
        override val entityActor: ActorClaim? get() = actor
        override val entityVerification: VerificationResult? get() = verification

        override fun payload(): Map<String, Any?> =
            mapOf("itemId" to itemId, "key" to key, "role" to role, "bodyLength" to bodyLength, "bodyFromFile" to bodyFromFile)
    }

    data class NoteDeleted(
        override val entityId: UUID,
        override val rootId: UUID,
        val itemId: UUID,
        val key: String,
        val role: String,
        val cause: DeleteCause
    ) : DomainEvent() {
        override val type: String get() = NOTE_DELETED
        override val entityKind: String get() = KIND_NOTE

        override fun payload(): Map<String, Any?> = mapOf("itemId" to itemId, "key" to key, "role" to role, "cause" to cause.wire)
    }

    // ---- dependencies ----

    data class DependencyAdded(
        override val entityId: UUID,
        override val rootId: UUID,
        val fromItemId: UUID,
        val toItemId: UUID,
        val depType: String,
        val unblockAt: String?
    ) : DomainEvent() {
        override val type: String get() = DEPENDENCY_ADDED
        override val entityKind: String get() = KIND_DEPENDENCY

        override fun payload(): Map<String, Any?> = dependencyPayload(fromItemId, toItemId, depType, unblockAt, null)
    }

    data class DependencyRemoved(
        override val entityId: UUID,
        override val rootId: UUID,
        val fromItemId: UUID,
        val toItemId: UUID,
        val depType: String,
        val unblockAt: String?,
        val cause: DeleteCause
    ) : DomainEvent() {
        override val type: String get() = DEPENDENCY_REMOVED
        override val entityKind: String get() = KIND_DEPENDENCY

        override fun payload(): Map<String, Any?> = dependencyPayload(fromItemId, toItemId, depType, unblockAt, cause)
    }

    // ---- claims (entity = the claimed item) ----

    data class ClaimAcquired(
        override val entityId: UUID,
        override val rootId: UUID,
        val holder: String,
        val ttlSeconds: Int
    ) : DomainEvent() {
        override val type: String get() = CLAIM_ACQUIRED
        override val entityKind: String get() = KIND_ITEM

        override fun payload(): Map<String, Any?> = mapOf("holder" to holder, "ttlSeconds" to ttlSeconds)
    }

    data class ClaimReleased(
        override val entityId: UUID,
        override val rootId: UUID,
        val reason: ClaimReleaseReason
    ) : DomainEvent() {
        override val type: String get() = CLAIM_RELEASED
        override val entityKind: String get() = KIND_ITEM

        override fun payload(): Map<String, Any?> = mapOf("reason" to reason.wire)
    }

    data class ClaimRejected(
        override val entityId: UUID,
        override val rootId: UUID,
        val retryAfterMs: Long?
    ) : DomainEvent() {
        override val type: String get() = CLAIM_REJECTED
        override val entityKind: String get() = KIND_ITEM

        override fun payload(): Map<String, Any?> = mapOf("retryAfterMs" to retryAfterMs)
    }

    /**
     * A claim whose TTL ran out ([expiresAt] <= the detecting unit's instant), held by [holder]. Recorded exactly once
     * per lapsed claim instance (item + holder + expiresAt) by `ClaimService`; the claim columns are left as they were.
     * Both fields default to null so a bare marker row stays constructible.
     */
    data class ClaimExpired(
        override val entityId: UUID,
        override val rootId: UUID,
        val holder: String? = null,
        val expiresAt: Instant? = null
    ) : DomainEvent() {
        override val type: String get() = CLAIM_EXPIRED
        override val entityKind: String get() = KIND_ITEM

        override fun payload(): Map<String, Any?> = mapOf("holder" to holder, "expiresAt" to expiresAt)
    }

    // ---- leases (entity = the holder item) ----

    data class LeaseAcquired(
        override val entityId: UUID,
        override val rootId: UUID,
        val key: String,
        val ttlSeconds: Long
    ) : DomainEvent() {
        override val type: String get() = LEASE_ACQUIRED
        override val entityKind: String get() = KIND_ITEM

        override fun payload(): Map<String, Any?> = mapOf("key" to key, "ttlSeconds" to ttlSeconds)
    }

    data class LeaseReleased(
        override val entityId: UUID,
        override val rootId: UUID,
        val key: String?,
        val count: Int,
        val forced: Boolean
    ) : DomainEvent() {
        override val type: String get() = LEASE_RELEASED
        override val entityKind: String get() = KIND_ITEM

        override fun payload(): Map<String, Any?> = mapOf("key" to key, "count" to count, "forced" to forced)
    }

    data class LeaseRejected(
        override val entityId: UUID,
        override val rootId: UUID,
        val contendedKeys: List<String>,
        val retryAfterMs: Long?
    ) : DomainEvent() {
        override val type: String get() = LEASE_REJECTED
        override val entityKind: String get() = KIND_ITEM

        override fun payload(): Map<String, Any?> = mapOf("contendedKeys" to contendedKeys, "retryAfterMs" to retryAfterMs)
    }

    /**
     * A lease whose TTL ran out ([expiresAt] <= the detecting unit's instant) on [key], held by the entity item.
     * Recorded exactly once per lapsed lease row by `ClaimService`, which removes the row; the one exception is the holder's own lapsed re-take, where the row is refreshed in place instead of removed.
     */
    data class LeaseExpired(
        override val entityId: UUID,
        override val rootId: UUID,
        val key: String,
        val expiresAt: Instant? = null
    ) : DomainEvent() {
        override val type: String get() = LEASE_EXPIRED
        override val entityKind: String get() = KIND_ITEM

        override fun payload(): Map<String, Any?> = mapOf("key" to key, "expiresAt" to expiresAt)
    }

    // ---- per-root config and plan documents (root = the project root item) ----

    data class ProjectConfigUpserted(
        override val rootId: UUID,
        val fingerprint: String
    ) : DomainEvent() {
        override val type: String get() = PROJECT_CONFIG_UPSERTED
        override val entityKind: String get() = KIND_PROJECT_CONFIG
        override val entityId: UUID get() = rootId

        override fun payload(): Map<String, Any?> = mapOf("fingerprint" to fingerprint)
    }

    data class ProjectConfigDeleted(
        override val rootId: UUID
    ) : DomainEvent() {
        override val type: String get() = PROJECT_CONFIG_DELETED
        override val entityKind: String get() = KIND_PROJECT_CONFIG
        override val entityId: UUID get() = rootId

        override fun payload(): Map<String, Any?> = emptyMap()
    }

    data class PlanDocumentStashed(
        override val entityId: UUID,
        override val rootId: UUID,
        val slug: String
    ) : DomainEvent() {
        override val type: String get() = PLAN_DOCUMENT_STASHED
        override val entityKind: String get() = KIND_PLAN_DOCUMENT

        override fun payload(): Map<String, Any?> = mapOf("slug" to slug)
    }

    data class PlanDocumentAdopted(
        override val entityId: UUID,
        override val rootId: UUID,
        val slug: String,
        val adoptedByItemId: UUID?
    ) : DomainEvent() {
        override val type: String get() = PLAN_DOCUMENT_ADOPTED
        override val entityKind: String get() = KIND_PLAN_DOCUMENT

        override fun payload(): Map<String, Any?> = mapOf("slug" to slug, "adoptedByItemId" to adoptedByItemId)
    }

    companion object {
        const val KIND_ITEM = "item"
        const val KIND_NOTE = "note"
        const val KIND_DEPENDENCY = "dependency"
        const val KIND_PROJECT_CONFIG = "project_config"
        const val KIND_PLAN_DOCUMENT = "plan_document"

        const val ITEM_CREATED = "item.created"
        const val ITEM_UPDATED = "item.updated"
        const val ITEM_REPARENTED = "item.reparented"
        const val ITEM_DELETED = "item.deleted"
        const val ITEM_TRANSITIONED = "item.transitioned"
        const val TRANSITION_REJECTED = "transition.rejected"
        const val NOTE_UPSERTED = "note.upserted"
        const val NOTE_DELETED = "note.deleted"
        const val DEPENDENCY_ADDED = "dependency.added"
        const val DEPENDENCY_REMOVED = "dependency.removed"
        const val CLAIM_ACQUIRED = "claim.acquired"
        const val CLAIM_RELEASED = "claim.released"
        const val CLAIM_REJECTED = "claim.rejected"
        const val CLAIM_EXPIRED = "claim.expired"
        const val LEASE_ACQUIRED = "lease.acquired"
        const val LEASE_RELEASED = "lease.released"
        const val LEASE_REJECTED = "lease.rejected"
        const val LEASE_EXPIRED = "lease.expired"
        const val PROJECT_CONFIG_UPSERTED = "project_config.upserted"
        const val PROJECT_CONFIG_DELETED = "project_config.deleted"
        const val PLAN_DOCUMENT_STASHED = "plan_document.stashed"
        const val PLAN_DOCUMENT_ADOPTED = "plan_document.adopted"

        /** `transition.rejected` codes (the error-catalog wire codes they mirror). */
        const val REJECTED_GATE_BLOCKED = "gate_blocked"
        const val REJECTED_DEPENDENCY_UNMET = "dependency_unmet"

        private fun dependencyPayload(
            fromItemId: UUID,
            toItemId: UUID,
            depType: String,
            unblockAt: String?,
            cause: DeleteCause?
        ): Map<String, Any?> =
            buildMap {
                put("fromItemId", fromItemId)
                put("toItemId", toItemId)
                put("depType", depType)
                put("unblockAt", unblockAt)
                if (cause != null) put("cause", cause.wire)
            }
    }
}

enum class ReparentSide(
    val wire: String
) {
    LEFT("left"),
    ENTERED("entered")
}

enum class TransitionOrigin(
    val wire: String
) {
    USER("user"),
    CASCADE("cascade")
}

enum class DeleteCause(
    val wire: String
) {
    EXPLICIT("explicit"),
    CASCADE("cascade")
}

enum class ClaimReleaseReason(
    val wire: String
) {
    RELEASED("released"),
    CLEARED("cleared"),
    SUPERSEDED("superseded")
}
