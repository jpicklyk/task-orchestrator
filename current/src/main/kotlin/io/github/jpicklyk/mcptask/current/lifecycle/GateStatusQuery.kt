package io.github.jpicklyk.mcptask.current.lifecycle

import io.github.jpicklyk.mcptask.current.lifecycle.internal.app.LeaseInput
import io.github.jpicklyk.mcptask.current.lifecycle.internal.app.OwnershipInput
import io.github.jpicklyk.mcptask.current.lifecycle.internal.app.TransitionSnapshotLoader
import io.github.jpicklyk.mcptask.current.lifecycle.internal.domain.GatePredicate
import io.github.jpicklyk.mcptask.current.lifecycle.internal.domain.PhaseNoteContext
import io.github.jpicklyk.mcptask.current.lifecycle.internal.domain.computeMissingBySeat
import io.github.jpicklyk.mcptask.current.lifecycle.internal.domain.computePhaseNoteContext
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.Trigger
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.model.note.Note
import io.github.jpicklyk.mcptask.current.model.schema.IndependenceViolation
import io.github.jpicklyk.mcptask.current.model.schema.WorkItemSchema
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork

/**
 * The wire name of the gate that rejected this decision (`"table"`, `"dependency"`, `"note"`, `"lease"`;
 * `"ownership"` / `"hold"` cannot occur for a preview); null for an [Decision.Allow] or [Decision.NotApplicable].
 * Surfaced as `gateStatus.blockedBy`.
 */
fun Decision.blockedByWire(): String? = (this as? Decision.Reject)?.gate?.name?.lowercase()

/**
 * The gate state of an item for its START transition, all read in the SAME read unit: the [decision] a start
 * would get, the item's resolved [schema], its [notes] with the [filledKeys] of the gate's own definition, the
 * current-[phase] note context, and the independence-attestation [violations] of the current phase (null when
 * none apply or the item is terminal). `items_read` `select=context` serves `canAdvance`, `blockedBy`, `missing`,
 * `missingBySeat` and `violations` from this, so they never disagree because of a write landing between two reads.
 * [missing] is the current phase's unfilled required keys whatever gate rejected the start.
 */
class GateStatus(
    val decision: Decision,
    val schema: WorkItemSchema?,
    val notes: List<Note>,
    val filledKeys: Set<String>,
    val phase: PhaseNoteContext?,
    val missingBySeat: Map<String, List<String>>?,
    val violations: List<IndependenceViolation>?
) {
    val missing: List<String> get() = phase?.missingKeys.orEmpty()
}

/**
 * The one read-only evaluation of an item's gate state: the same [TransitionSnapshotLoader] and [TransitionPolicy]
 * the advance pipeline uses, evaluated in a read unit with nothing applied. `canAdvance` and `next` of
 * `items_read` `select=context` are `inspect(item).decision is Decision.Allow`, so a preview never disagrees with
 * the advance it predicts on the same state.
 *
 * Claim ownership is not part of a preview (no caller identity): it models [OwnershipInput.NONE]
 * (decision D9), so on an item another principal holds it can predict an advance that the OWNERSHIP gate (and the
 * write fence, `FenceGuard`) then refuses for that caller. Resource leases are evaluated when the deployment kill
 * switch ([leasesEnforced], read per call) is on.
 *
 * Config is read inside the read unit, like the advance it predicts: a unit has no last-known-good
 * fallback, so a per-root config read fault propagates as a `PerRootConfigUnavailableException`.
 */
class GateStatusQuery(
    private val unitOfWork: UnitOfWork<LifecycleStoreProvider>,
    private val loader: TransitionSnapshotLoader,
    private val leasesEnforced: () -> Boolean = { true },
    private val policy: TransitionPolicy = TransitionPolicy()
) {
    /**
     * The [GateStatus] of [item] for START. Notes the snapshot did not need (no schema, or no table target) are
     * read in the same unit too.
     */
    suspend fun inspect(item: WorkItem): GateStatus {
        val trigger = Trigger.User.START
        val leases = LeaseInput(leasesEnforced())
        return unitOfWork.read {
            val loaded = loader.loadDetailed(this, item, trigger, OwnershipInput.NONE, leases)
            val decision = policy.evaluate(loaded.snapshot, trigger)
            val loadedNotes = loaded.notes
            val notes = loadedNotes ?: loader.notesOf(item.id)
            val violations =
                if (loadedNotes != null) {
                    loaded.snapshot.independence?.currentPhase
                } else {
                    loaded.schema?.let { loader.currentPhaseViolations(it, item, notes) }
                }
            val filledKeys = GatePredicate.filledNoteKeys(notes)
            val schema = loaded.schema
            // A lifecycle-free item (null role) has no phase; a terminal one has no phase context either.
            val phase = computePhaseNoteContext(item.role, schema?.notes, notes.associateBy { it.key })
            val terminal = item.role == Role.TERMINAL
            GateStatus(
                decision = decision,
                schema = schema,
                notes = notes,
                filledKeys = filledKeys,
                phase = phase,
                missingBySeat = if (terminal) null else computeMissingBySeat(schema, phase?.missingKeys.orEmpty()),
                violations = if (terminal) null else violations
            )
        }
    }
}
