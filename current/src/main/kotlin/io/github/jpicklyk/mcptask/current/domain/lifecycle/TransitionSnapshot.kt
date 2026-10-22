package io.github.jpicklyk.mcptask.current.domain.lifecycle

import io.github.jpicklyk.mcptask.current.domain.graph.BlockingEdge
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceMode
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceViolation
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.util.UUID

/**
 * The item being transitioned. [role] is null for a lifecycle-free (NONE) item. [previousRole] is the
 * role saved when it was blocked.
 */
data class ItemFacts(
    val id: UUID,
    val role: Role?,
    val previousRole: Role? = null,
    val parentId: UUID? = null
)

/** One REQUIRED note of the resolved schema, in schema order. */
data class RequiredNote(
    val key: String,
    val role: Role,
    val seat: String? = null
)

/**
 * Independence (A2) findings, precomputed by the service. [currentPhase] covers declaring notes of
 * the item's current role, [allPhases] every phase; each is null when the predicate declared nothing.
 */
data class IndependenceFacts(
    val mode: IndependenceMode,
    val currentPhase: List<IndependenceViolation>?,
    val allPhases: List<IndependenceViolation>?
)

/** Child counts, for the complete-cascade warrant. */
data class ChildFacts(
    val total: Int,
    val terminal: Int
) {
    companion object {
        val NONE: ChildFacts = ChildFacts(0, 0)
    }
}

/** One edge blocking the item, with its blocker's current role (null = unreadable, fail-closed). */
data class BlockerState(
    val edge: BlockingEdge,
    val blockerRole: Role?
)

/**
 * Claim ownership. ([activeHolder], [activeHolderAssignment]) is the holder pair of a live (unexpired) claim, already
 * filtered by the service; ([callerId], [callerAssignment]) is the acting holder pair (the assignment is the seat
 * assignment of the call's seat token, null without one). The caller holds the claim only when BOTH members match.
 */
data class OwnershipFacts(
    val enforced: Boolean,
    val activeHolder: String?,
    val callerId: String?,
    val activeHolderAssignment: UUID? = null,
    val callerAssignment: UUID? = null
) {
    companion object {
        val NONE: OwnershipFacts = OwnershipFacts(enforced = false, activeHolder = null, callerId = null)
    }
}

/**
 * Resource leases. [exclusiveKeys] are the keys the item declares; [heldElsewhere] the subset held
 * by another item right now; [retryAfterMs] the earliest expiry among those, when known. [heldModes] is the
 * declared requirement mode of each [heldElsewhere] key, as the loader read it from the item's requirements.
 */
data class LeaseFacts(
    val enforced: Boolean,
    val exclusiveKeys: List<String>,
    val heldElsewhere: List<String>,
    val retryAfterMs: Long?,
    val heldModes: Map<String, ResourceMode> = emptyMap()
) {
    companion object {
        val NONE: LeaseFacts = LeaseFacts(enforced = false, exclusiveKeys = emptyList(), heldElsewhere = emptyList(), retryAfterMs = null)
    }
}

/**
 * Everything [TransitionPolicy] reads, loaded by the service inside its unit of work.
 *
 * @property requiredNotes required notes of the resolved schema; null = schema-free (no note gate).
 * @property filledKeys keys of notes with a non-blank body.
 * @property independence null when independence is OFF or no note declares it.
 * @property blockers edges whose blocked side is this item.
 * @property seat seat-enforcement facts ([SeatFacts.NONE] outside an open run or without a caller).
 */
data class TransitionSnapshot(
    val item: ItemFacts,
    val schema: SchemaFacts = SchemaFacts.SCHEMA_FREE,
    val requiredNotes: List<RequiredNote>? = null,
    val filledKeys: Set<String> = emptySet(),
    val independence: IndependenceFacts? = null,
    val children: ChildFacts = ChildFacts.NONE,
    val blockers: List<BlockerState> = emptyList(),
    val ownership: OwnershipFacts = OwnershipFacts.NONE,
    val lease: LeaseFacts = LeaseFacts.NONE,
    val seat: SeatFacts = SeatFacts.NONE
) {
    companion object {
        /** The snapshot for a [Trigger.Structural.Create] of a new item [newId] under [parent]. */
        fun forCreate(
            newId: UUID,
            parent: ParentFacts?
        ): TransitionSnapshot = TransitionSnapshot(item = ItemFacts(id = newId, role = Role.QUEUE, parentId = parent?.id))
    }
}
