package io.github.jpicklyk.mcptask.current.domain.event

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersionSource
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestSeverity
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.PinLayer
import io.github.jpicklyk.mcptask.current.domain.model.ProofClaims
import io.github.jpicklyk.mcptask.current.domain.model.RunBudget
import io.github.jpicklyk.mcptask.current.domain.model.RunInFlight
import io.github.jpicklyk.mcptask.current.domain.model.RunPauseReason
import io.github.jpicklyk.mcptask.current.domain.model.RunReleased
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatLink
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatOutcome
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
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

    /**
     * The item's config pin moved: [from] is the previous pin (null on a create), [to] the new one, [reason] which pin
     * point fired. Recorded only when a pin is written, never by the `pin-backfill` data step. Table-only: not
     * projected onto the SSE stream.
     */
    data class ItemPinned(
        override val entityId: UUID,
        override val rootId: UUID,
        val from: PinRef?,
        val to: PinRef,
        val reason: PinReason
    ) : DomainEvent() {
        override val type: String get() = ITEM_PINNED
        override val entityKind: String get() = KIND_ITEM

        override fun payload(): Map<String, Any?> = mapOf("from" to from?.payload(), "to" to to.payload(), "reason" to reason.wire)
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
        val verification: VerificationResult? = null,
        val summary: String? = null,
        val consumedCredentials: List<String> = emptyList(),
        /** The typed blocked reason (wire), set only on a transition into blocked. */
        val blockedReason: String? = null,
        /** The decision an waiting_decision block waits on, set only with that reason. */
        val decisionId: UUID? = null
    ) : DomainEvent() {
        override val type: String get() = ITEM_TRANSITIONED
        override val entityKind: String get() = KIND_ITEM
        override val entityActor: ActorClaim? get() = actor
        override val entityVerification: VerificationResult? get() = verification

        override fun payload(): Map<String, Any?> {
            val base =
                mapOf(
                    "trigger" to trigger,
                    "fromRole" to fromRole,
                    "toRole" to toRole,
                    "fromStatusLabel" to fromStatusLabel,
                    "toStatusLabel" to toStatusLabel,
                    "origin" to origin.wire,
                    "summary" to summary,
                    "consumedCredentials" to consumedCredentials,
                    "actorProofSha256" to verification?.proofSha256,
                    "actorProofClaims" to proofClaimsMap(verification?.proofClaims),
                    "verifier" to verification?.verifier,
                    "verificationReason" to verification?.reason
                )
            // The typed blocked reason rides only on a transition into blocked; every other payload is unchanged.
            if (toRole != "blocked" || blockedReason == null) return base
            val out = LinkedHashMap(base)
            out["blockedReason"] = blockedReason
            if (decisionId != null) out["decisionId"] = decisionId
            return out
        }

        /** Non-null claim fields as a nested map, or null when claims are null or all fields are null. */
        private fun proofClaimsMap(claims: ProofClaims?): Map<String, Any?>? {
            if (claims == null) return null
            val map = LinkedHashMap<String, Any?>()
            claims.iss?.let { map["iss"] = it }
            claims.sub?.let { map["sub"] = it }
            claims.aud?.let { map["aud"] = it }
            claims.jti?.let { map["jti"] = it }
            claims.iat?.let { map["iat"] = it }
            claims.exp?.let { map["exp"] = it }
            claims.kid?.let { map["kid"] = it }
            claims.alg?.let { map["alg"] = it }
            return map.takeIf { it.isNotEmpty() }
        }
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

    /**
     * A note changed owner: an ADMIN `takeover` ([from] is the previous owner's principal id) or the first 4.0 upsert of
     * an unowned pre-4.0 note ([from] is null and [legacyActor] is the note's pre-4.0 actor id, or null); [to] is the
     * new owner's principal id. Recorded in the same unit as the write, before the `note.upserted` row. Audit class.
     */
    data class NoteOwnershipTransferred(
        override val entityId: UUID,
        override val rootId: UUID,
        val itemId: UUID,
        val key: String,
        val from: String?,
        val to: String,
        val legacyActor: String? = null
    ) : DomainEvent() {
        override val type: String get() = NOTE_OWNERSHIP_TRANSFERRED
        override val entityKind: String get() = KIND_NOTE

        override fun payload(): Map<String, Any?> =
            if (from != null) {
                mapOf("itemId" to itemId, "key" to key, "from" to from, "to" to to)
            } else {
                mapOf("itemId" to itemId, "key" to key, "from" to null, "to" to to, "legacyActor" to legacyActor)
            }
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
     *
     * [reason] says how the lapse was found (R25, AR-84 residual 1): `expired` for every detector except a take-over,
     * `displaced` when a claim by a DIFFERENT holder pair took the item over the lapsed claim. A displaced row also
     * carries `displacedBy`, the new holder's seat assignment id (null for a principal-level holder).
     */
    data class ClaimExpired(
        override val entityId: UUID,
        override val rootId: UUID,
        val holder: String? = null,
        val expiresAt: Instant? = null,
        val reason: ClaimExpiryReason = ClaimExpiryReason.EXPIRED,
        val displacedBy: UUID? = null
    ) : DomainEvent() {
        override val type: String get() = CLAIM_EXPIRED
        override val entityKind: String get() = KIND_ITEM

        override fun payload(): Map<String, Any?> =
            buildMap {
                put("holder", holder)
                put("expiresAt", expiresAt)
                put("reason", reason.wire)
                if (reason == ClaimExpiryReason.DISPLACED) put("displacedBy", displacedBy)
            }
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

    // ---- versioned config (root = the project root item) ----

    /**
     * A config version was appended (a push that wrote a row, a rollback that wrote a row, or a reset). Never
     * recorded for an identical re-push, which writes nothing. [rootId] is the project root the version belongs to.
     */
    data class ConfigVersionCreated(
        override val rootId: UUID,
        val versionId: UUID,
        val scope: ConfigScope,
        val version: Int,
        val fingerprint: String,
        val source: ConfigVersionSource,
        val warningsCount: Int
    ) : DomainEvent() {
        override val type: String get() = CONFIG_VERSION_CREATED
        override val entityKind: String get() = KIND_CONFIG
        override val entityId: UUID get() = versionId

        override fun payload(): Map<String, Any?> =
            mapOf(
                "scope" to scope.wire,
                "rootId" to rootId,
                "version" to version,
                "fingerprint" to fingerprint,
                "source" to source.wire,
                "warningsCount" to warningsCount
            )
    }

    // ---- documents (entity = the document; root = its project root item) ----

    /** A document's first version was written ([version] is 1). */
    data class DocumentCreated(
        override val entityId: UUID,
        override val rootId: UUID,
        val kind: DocumentKind,
        val slug: String,
        val version: Int
    ) : DomainEvent() {
        override val type: String get() = DOCUMENT_CREATED
        override val entityKind: String get() = KIND_DOCUMENT

        override fun payload(): Map<String, Any?> = documentPayload(kind, slug, version)
    }

    /** A document gained a new version, or was unarchived ([version] is then unchanged). */
    data class DocumentUpdated(
        override val entityId: UUID,
        override val rootId: UUID,
        val kind: DocumentKind,
        val slug: String,
        val version: Int
    ) : DomainEvent() {
        override val type: String get() = DOCUMENT_UPDATED
        override val entityKind: String get() = KIND_DOCUMENT

        override fun payload(): Map<String, Any?> = documentPayload(kind, slug, version)
    }

    data class DocumentArchived(
        override val entityId: UUID,
        override val rootId: UUID,
        val kind: DocumentKind,
        val slug: String,
        val version: Int
    ) : DomainEvent() {
        override val type: String get() = DOCUMENT_ARCHIVED
        override val entityKind: String get() = KIND_DOCUMENT

        override fun payload(): Map<String, Any?> = documentPayload(kind, slug, version)
    }

    data class DocumentDeleted(
        override val entityId: UUID,
        override val rootId: UUID,
        val kind: DocumentKind,
        val slug: String,
        val version: Int
    ) : DomainEvent() {
        override val type: String get() = DOCUMENT_DELETED
        override val entityKind: String get() = KIND_DOCUMENT

        override fun payload(): Map<String, Any?> = documentPayload(kind, slug, version)
    }

    /** A plan document was adopted by [adoptedByItemId] (an `adopted_by` link was recorded). */
    data class DocumentAdopted(
        override val entityId: UUID,
        override val rootId: UUID,
        val slug: String,
        val adoptedByItemId: UUID
    ) : DomainEvent() {
        override val type: String get() = DOCUMENT_ADOPTED
        override val entityKind: String get() = KIND_DOCUMENT

        override fun payload(): Map<String, Any?> = mapOf("slug" to slug, "adoptedByItemId" to adoptedByItemId)
    }

    // ---- runs (entity = the run; root = the run's root item) ----

    /** A run was opened over [itemIds] (after de-duplication), with its pinned [budget] (null = unlimited). */
    data class RunOpened(
        override val entityId: UUID,
        override val rootId: UUID,
        val itemIds: List<UUID>,
        val budget: RunBudget? = null
    ) : DomainEvent() {
        override val type: String get() = RUN_OPENED
        override val entityKind: String get() = KIND_RUN

        override fun payload(): Map<String, Any?> = mapOf("itemIds" to itemIds, "budget" to budgetPayload(budget))
    }

    /** A run's membership, budget or hints changed: [added] / [removed] are the members that actually changed. */
    data class RunUpdated(
        override val entityId: UUID,
        override val rootId: UUID,
        val added: List<UUID>,
        val removed: List<UUID>,
        val budgetChanged: Boolean
    ) : DomainEvent() {
        override val type: String get() = RUN_UPDATED
        override val entityKind: String get() = KIND_RUN

        override fun payload(): Map<String, Any?> = mapOf("added" to added, "removed" to removed, "budgetChanged" to budgetChanged)
    }

    data class RunPaused(
        override val entityId: UUID,
        override val rootId: UUID,
        val reason: RunPauseReason,
        val decisionId: UUID? = null
    ) : DomainEvent() {
        override val type: String get() = RUN_PAUSED
        override val entityKind: String get() = KIND_RUN

        override fun payload(): Map<String, Any?> = mapOf("reason" to reason.wire, "decisionId" to decisionId)
    }

    data class RunResumed(
        override val entityId: UUID,
        override val rootId: UUID
    ) : DomainEvent() {
        override val type: String get() = RUN_RESUMED
        override val entityKind: String get() = KIND_RUN

        override fun payload(): Map<String, Any?> = emptyMap()
    }

    /**
     * A run closed with [reason]. [countsByRole] counts the members at close by role (`none` for a lifecycle-free
     * one); [inFlight] lists the members whose seat was still active; [released] is what the close cascade released;
     * [openDecisions] counts the run's decisions still open or acknowledged (a close withdraws none).
     */
    data class RunEnded(
        override val entityId: UUID,
        override val rootId: UUID,
        val reason: RunTerminalReason,
        val countsByRole: Map<String, Int>,
        val inFlight: List<RunInFlight>,
        val released: RunReleased,
        val openDecisions: Int = 0
    ) : DomainEvent() {
        override val type: String get() = RUN_ENDED
        override val entityKind: String get() = KIND_RUN

        override fun payload(): Map<String, Any?> =
            mapOf(
                "reason" to reason.wire,
                "countsByRole" to countsByRole,
                "inFlight" to inFlight.map { mapOf("itemId" to it.itemId, "role" to it.role, "seat" to it.seat) },
                "released" to mapOf("claims" to released.claims, "leases" to released.leases, "seats" to released.seats),
                "openDecisions" to openDecisions
            )
    }

    /** Named marks were set on a run. Defined here; produced by a later item. */
    data class RunMarked(
        override val entityId: UUID,
        override val rootId: UUID,
        val keys: List<String>
    ) : DomainEvent() {
        override val type: String get() = RUN_MARKED
        override val entityKind: String get() = KIND_RUN

        override fun payload(): Map<String, Any?> = mapOf("keys" to keys)
    }

    // ---- seats (entity = the assignment; root = the run's root item) ----

    /** A seat of member [itemId] of run [runId] was assigned (attempt [attempt]). The token is never part of an event. */
    data class SeatAssigned(
        override val entityId: UUID,
        override val rootId: UUID,
        val runId: UUID,
        val itemId: UUID,
        val seat: String,
        val attempt: Int,
        val hints: Map<String, String>
    ) : DomainEvent() {
        override val type: String get() = SEAT_ASSIGNED
        override val entityKind: String get() = KIND_SEAT

        override fun payload(): Map<String, Any?> =
            mapOf("runId" to runId, "itemId" to itemId, "seat" to seat, "attempt" to attempt, "hints" to hints)
    }

    /** A seat assignment ended by its own seat (`end_seat`) with [outcome], [summary] and [links]. */
    data class SeatEnded(
        override val entityId: UUID,
        override val rootId: UUID,
        val runId: UUID,
        val itemId: UUID,
        val seat: String,
        val attempt: Int,
        val outcome: RunSeatOutcome,
        val summary: String?,
        val links: List<RunSeatLink>
    ) : DomainEvent() {
        override val type: String get() = SEAT_ENDED
        override val entityKind: String get() = KIND_SEAT

        override fun payload(): Map<String, Any?> =
            mapOf(
                "runId" to runId,
                "itemId" to itemId,
                "seat" to seat,
                "attempt" to attempt,
                "outcome" to outcome.wire,
                "summary" to summary,
                "links" to links.map { mapOf("kind" to it.kind, "ref" to it.ref) }
            )
    }

    // ---- decisions (entity = the decision request; root = its root item) ----
    //
    // The payload names the class, severity, run, item and dedupe key; an answer adds the option, whether it came after
    // the expiry, and the answerer's kind, proof status and profile. Never a principal id, host, session id, answer
    // text or rationale: the row's own actor columns carry who acted.

    /** A decision request was raised (a re-raise that matched an existing request records nothing). */
    data class DecisionRaised(
        override val entityId: UUID,
        override val rootId: UUID,
        val decisionClass: String,
        val severity: DecisionRequestSeverity,
        val runId: UUID?,
        val itemId: UUID?,
        val dedupeKey: String
    ) : DomainEvent() {
        override val type: String get() = DECISION_RAISED
        override val entityKind: String get() = KIND_DECISION

        override fun payload(): Map<String, Any?> = decisionPayload(decisionClass, severity, runId, itemId, dedupeKey)
    }

    /** An open decision request was acknowledged (an acknowledge of an acknowledged one records nothing). */
    data class DecisionAcknowledged(
        override val entityId: UUID,
        override val rootId: UUID,
        val decisionClass: String,
        val severity: DecisionRequestSeverity,
        val runId: UUID?,
        val itemId: UUID?,
        val dedupeKey: String
    ) : DomainEvent() {
        override val type: String get() = DECISION_ACKNOWLEDGED
        override val entityKind: String get() = KIND_DECISION

        override fun payload(): Map<String, Any?> = decisionPayload(decisionClass, severity, runId, itemId, dedupeKey)
    }

    /**
     * A decision request was answered with [option] (null for a text-only freeform answer). [kind], [proofStatus] and
     * [profile] are the answerer's credential kind, proof status and served profile.
     */
    data class DecisionAnswered(
        override val entityId: UUID,
        override val rootId: UUID,
        val decisionClass: String,
        val severity: DecisionRequestSeverity,
        val runId: UUID?,
        val itemId: UUID?,
        val dedupeKey: String,
        val option: String?,
        val afterExpiry: Boolean,
        val kind: String,
        val proofStatus: String,
        val profile: String?
    ) : DomainEvent() {
        override val type: String get() = DECISION_ANSWERED
        override val entityKind: String get() = KIND_DECISION

        override fun payload(): Map<String, Any?> =
            decisionPayload(decisionClass, severity, runId, itemId, dedupeKey) +
                mapOf(
                    "option" to option,
                    "afterExpiry" to afterExpiry,
                    "kind" to kind,
                    "proofStatus" to proofStatus,
                    "profile" to profile
                )
    }

    /** A pending decision request was withdrawn by its raiser or the run's coordinator. */
    data class DecisionWithdrawn(
        override val entityId: UUID,
        override val rootId: UUID,
        val decisionClass: String,
        val severity: DecisionRequestSeverity,
        val runId: UUID?,
        val itemId: UUID?,
        val dedupeKey: String
    ) : DomainEvent() {
        override val type: String get() = DECISION_WITHDRAWN
        override val entityKind: String get() = KIND_DECISION

        override fun payload(): Map<String, Any?> = decisionPayload(decisionClass, severity, runId, itemId, dedupeKey)
    }

    companion object {
        const val KIND_ITEM = "item"
        const val KIND_NOTE = "note"
        const val KIND_DEPENDENCY = "dependency"
        const val KIND_CONFIG = "config"
        const val KIND_DOCUMENT = "document"
        const val KIND_RUN = "run"
        const val KIND_SEAT = "seat"
        const val KIND_DECISION = "decision"

        const val ITEM_CREATED = "item.created"
        const val ITEM_UPDATED = "item.updated"
        const val ITEM_REPARENTED = "item.reparented"
        const val ITEM_DELETED = "item.deleted"
        const val ITEM_PINNED = "item.pinned"
        const val ITEM_TRANSITIONED = "item.transitioned"
        const val TRANSITION_REJECTED = "transition.rejected"
        const val NOTE_UPSERTED = "note.upserted"
        const val NOTE_DELETED = "note.deleted"
        const val NOTE_OWNERSHIP_TRANSFERRED = "note.ownership_transferred"
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
        const val CONFIG_VERSION_CREATED = "config.version_created"
        const val DOCUMENT_CREATED = "document.created"
        const val DOCUMENT_UPDATED = "document.updated"
        const val DOCUMENT_ARCHIVED = "document.archived"
        const val DOCUMENT_DELETED = "document.deleted"
        const val DOCUMENT_ADOPTED = "document.adopted"
        const val RUN_OPENED = "run.opened"
        const val RUN_UPDATED = "run.updated"
        const val RUN_PAUSED = "run.paused"
        const val RUN_RESUMED = "run.resumed"
        const val RUN_ENDED = "run.ended"
        const val RUN_MARKED = "run.marked"
        const val SEAT_ASSIGNED = "seat.assigned"
        const val SEAT_ENDED = "seat.ended"
        const val DECISION_RAISED = "decision.raised"
        const val DECISION_ACKNOWLEDGED = "decision.acknowledged"
        const val DECISION_ANSWERED = "decision.answered"
        const val DECISION_WITHDRAWN = "decision.withdrawn"

        /** The run event types, in lifecycle order (all of them stream on the SSE projection). */
        val RUN_TYPES: List<String> = listOf(RUN_OPENED, RUN_UPDATED, RUN_PAUSED, RUN_RESUMED, RUN_ENDED, RUN_MARKED)

        /** The decision event types, in lifecycle order (all of them stream on the SSE projection; audit by prefix). */
        val DECISION_TYPES: List<String> = listOf(DECISION_RAISED, DECISION_ACKNOWLEDGED, DECISION_ANSWERED, DECISION_WITHDRAWN)

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

        private fun documentPayload(
            kind: DocumentKind,
            slug: String,
            version: Int
        ): Map<String, Any?> = mapOf("kind" to kind.toDbValue(), "slug" to slug, "version" to version)

        private fun decisionPayload(
            decisionClass: String,
            severity: DecisionRequestSeverity,
            runId: UUID?,
            itemId: UUID?,
            dedupeKey: String
        ): Map<String, Any?> =
            mapOf(
                "class" to decisionClass,
                "severity" to severity.wire,
                "runId" to runId,
                "itemId" to itemId,
                "dedupeKey" to dedupeKey
            )

        /** A budget as `{tokens, costMicroUsd, attemptsPerSeat, maxActiveSeats}` (unset fields null), or null. */
        private fun budgetPayload(budget: RunBudget?): Map<String, Any?>? =
            budget?.let {
                mapOf(
                    "tokens" to it.tokens,
                    "costMicroUsd" to it.costMicroUsd,
                    "attemptsPerSeat" to it.attemptsPerSeat,
                    "maxActiveSeats" to it.maxActiveSeats
                )
            }
    }
}

/**
 * One side of an `item.pinned` event: the two pinned config version ids and the pinned base schema key and layer
 * (all null for a schema-free pin with no config).
 */
data class PinRef(
    val serverVersionId: UUID?,
    val rootVersionId: UUID?,
    val key: String?,
    val layer: PinLayer?
) {
    /** The wire shape: `{server, root, key, layer}`. */
    fun payload(): Map<String, Any?> = mapOf("server" to serverVersionId, "root" to rootVersionId, "key" to key, "layer" to layer?.wire)
}

/** Which pin point wrote an `item.pinned` row. */
enum class PinReason(
    val wire: String
) {
    CREATE("create"),
    REOPEN("reopen"),
    TYPE_CHANGE("type_change"),
    TRAIT_CHANGE("trait_change"),
    REPARENT("reparent"),
    REPIN("repin")
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

/** Why a `claim.expired` row was written: found lapsed ([EXPIRED]) or taken over by another holder ([DISPLACED]). */
enum class ClaimExpiryReason(
    val wire: String
) {
    EXPIRED("expired"),
    DISPLACED("displaced")
}

enum class ClaimReleaseReason(
    val wire: String
) {
    RELEASED("released"),
    CLEARED("cleared"),
    SUPERSEDED("superseded"),

    /** Cleared by a run close's cascade (a member claim held by one of the run's seat assignees). */
    RUN_CLOSED("run_closed")
}
