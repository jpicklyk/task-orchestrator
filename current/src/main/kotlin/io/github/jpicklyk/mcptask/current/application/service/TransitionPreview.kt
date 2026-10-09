package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Decision
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionPolicy
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceViolation
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema

/**
 * The wire name of the gate that rejected this decision (`"table"`, `"dependency"`, `"note"`, `"lease"`;
 * `"ownership"` / `"hold"` cannot occur for a preview); null for an [Decision.Allow] or [Decision.NotApplicable].
 * Surfaced as `gateStatus.blockedBy`.
 */
fun Decision.blockedByWire(): String? = (this as? Decision.Reject)?.gate?.name?.lowercase()

/**
 * A preview [decision] together with the gate facts read in the SAME read unit: the item's resolved
 * [schema], its [notes], and the independence-attestation [violations] for its current phase
 * (`GatePredicate.violationsForStart`; null when none apply). `get_context` and `GET /items/{id}/gate`
 * derive `missing`, `missingBySeat` and `violations` from these, so they never disagree with
 * `canAdvance` / `blockedBy` because of a write landing between two reads.
 */
class PreviewView(
    val decision: Decision,
    val schema: WorkItemSchema?,
    val notes: List<Note>,
    val violations: List<IndependenceViolation>?
)

/**
 * Read-only previews of a user transition: the same [TransitionSnapshotLoader] and [TransitionPolicy]
 * the advance pipeline uses, evaluated in a read unit with nothing applied. `get_context`'s and
 * `GET /items/{id}/gate`'s `canAdvance`, and `get_next_status`'s recommendation, are
 * `evaluate(item, START) is Decision.Allow`, so a preview never disagrees with the advance it predicts
 * on the same state.
 *
 * Claim ownership is not part of a preview (no caller identity): [OwnershipInput.NONE]. Resource leases
 * are evaluated when the deployment kill switch ([leasesEnforced], read per call) is on.
 *
 * Config is read inside the read unit, like the advance it predicts: a unit has no last-known-good
 * fallback, so a per-root config read fault propagates as a `PerRootConfigUnavailableException`.
 */
class TransitionPreview(
    private val unitOfWork: UnitOfWork,
    private val loader: TransitionSnapshotLoader,
    private val leasesEnforced: () -> Boolean = { true },
    private val policy: TransitionPolicy = TransitionPolicy()
) {
    /** The decision an advance of [item] via [trigger] would get right now (ownership excluded). */
    suspend fun evaluate(
        item: WorkItem,
        trigger: Trigger.User
    ): Decision {
        val leases = LeaseInput(leasesEnforced())
        return unitOfWork.read {
            policy.evaluate(loader.loadDetailed(this, item, trigger, OwnershipInput.NONE, leases).snapshot, trigger)
        }
    }

    /**
     * [evaluate] plus the schema, notes and current-phase independence violations, all read in the same
     * read unit as the decision. Notes the snapshot did not need (no schema, or no table target) are read
     * in that unit too.
     */
    suspend fun inspect(
        item: WorkItem,
        trigger: Trigger.User
    ): PreviewView {
        val leases = LeaseInput(leasesEnforced())
        return unitOfWork.read {
            val loaded = loader.loadDetailed(this, item, trigger, OwnershipInput.NONE, leases)
            val decision = policy.evaluate(loaded.snapshot, trigger)
            val loadedNotes = loaded.notes
            if (loadedNotes != null) {
                PreviewView(decision, loaded.schema, loadedNotes, loaded.snapshot.independence?.currentPhase)
            } else {
                val notes = loader.notesOf(item.id)
                PreviewView(decision, loaded.schema, notes, loaded.schema?.let { loader.currentPhaseViolations(it, item, notes) })
            }
        }
    }
}
