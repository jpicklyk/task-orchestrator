package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Decision
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionPolicy
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem

/**
 * The wire name of the gate that rejected this decision (`"table"`, `"dependency"`, `"note"`, `"lease"`;
 * `"ownership"` / `"hold"` cannot occur for a preview); null for an [Decision.Allow] or [Decision.NotApplicable].
 * Surfaced as `gateStatus.blockedBy`.
 */
fun Decision.blockedByWire(): String? = (this as? Decision.Reject)?.gate?.name?.lowercase()

/**
 * Read-only previews of a user transition: the same [TransitionSnapshotLoader] and [TransitionPolicy]
 * the advance pipeline uses, evaluated in a read unit with nothing applied. `get_context`'s and
 * `GET /items/{id}/gate`'s `canAdvance`, and `get_next_status`'s recommendation, are
 * `evaluate(item, START) is Decision.Allow`, so a preview never disagrees with the advance it predicts
 * on the same state.
 *
 * Claim ownership is not part of a preview (no caller identity): [OwnershipInput.NONE]. Resource leases
 * are evaluated when the deployment kill switch ([leasesEnforced], read per call) is on.
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
        // Config first, outside the read unit, so a failed per-root read keeps its last-known-good fallback.
        val config = loader.resolveConfig(item, trigger, leases)
        return unitOfWork.read {
            policy.evaluate(loader.loadDetailed(this, item, trigger, OwnershipInput.NONE, leases, config).snapshot, trigger)
        }
    }
}
