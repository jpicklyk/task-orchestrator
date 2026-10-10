package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.application.port.ReadScope
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.TransitionStore
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.port.unitNow
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.tools.ActorAware
import io.github.jpicklyk.mcptask.current.application.tools.PolicyResolution
import io.github.jpicklyk.mcptask.current.application.tools.toJsonString
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.error.ResourceRef
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.event.TransitionOrigin
import io.github.jpicklyk.mcptask.current.domain.graph.BlockerEvaluator
import io.github.jpicklyk.mcptask.current.domain.graph.UnsatisfiedBlocker
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Decision
import io.github.jpicklyk.mcptask.current.domain.lifecycle.FollowUp
import io.github.jpicklyk.mcptask.current.domain.lifecycle.GateId
import io.github.jpicklyk.mcptask.current.domain.lifecycle.RejectContext
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionPolicy
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionSnapshot
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionTable
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.IndependencePolicy
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceViolation
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.infrastructure.config.EnvBoolean
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Reason an advance attempt failed before (or instead of) applying the transition.
 *
 * Each variant carries the structured data a caller needs to build its own error envelope
 * (MCP JSON or REST DTO) — no JSON is produced by [AdvanceService].
 */
sealed class AdvanceFailure {
    /**
     * The catalog error behind this failure: the policy rejection's own ([Decision.Reject.error]) or one
     * built here for failures raised outside the policy (unknown trigger, degraded-mode policy, credential
     * refs, a missing row, an apply-time lease contention, a store fault). Always present: the MCP and REST
     * mappers read the failure's `kind` from it. The 3.x wire code and message stay each variant's own.
     */
    abstract val error: DomainError

    /** The caller does not hold the active claim on an actively-claimed item. */
    data class OwnershipRejected(
        val message: String,
        override val error: DomainError
    ) : AdvanceFailure()

    /** The configured [DegradedModePolicy] rejected the actor's verification status. */
    data class PolicyRejected(
        val reason: String,
        override val error: DomainError
    ) : AdvanceFailure()

    /** The trigger could not be resolved to a target role from the item's current role. */
    data class ResolutionFailed(
        val message: String,
        override val error: DomainError
    ) : AdvanceFailure()

    /** A blocking dependency prevents the forward transition (or a `credentialRefs` rule failed, with no blockers). */
    data class ValidationFailed(
        val message: String,
        val blockers: List<BlockerInfo>,
        override val error: DomainError
    ) : AdvanceFailure()

    /**
     * A required-note gate blocked the transition (start or complete).
     *
     * @property message human-readable summary including the missing keys
     * @property previousRole the item's role at the time the transition was attempted (the phase
     *   whose gate rejected it)
     * @property targetRole the role the transition would have moved to (for context)
     * @property missingNotes the structured required notes that are still unfilled
     * @property missingBySeat [missingNotes]' keys bucketed by owning seat (A1;
     *   [io.github.jpicklyk.mcptask.current.application.service.computeMissingBySeat]) — null for a
     *   seat-less schema, so a seat-less config's gate-failure payload stays byte-identical (A1
     *   task-scope AC1).
     * @property violations A2 independence-attestation findings — null iff independence mode is
     *   OFF or the schema declares no `independent_of` in any phase; otherwise a (possibly empty)
     *   list, populated even when the block was purely a missing-notes block (warn mode too).
     */
    data class GateBlocked(
        val message: String,
        val previousRole: Role,
        val targetRole: Role,
        val missingNotes: List<NoteSchemaEntry>,
        val missingBySeat: Map<String, List<String>>? = null,
        val violations: List<IndependenceViolation>? = null,
        override val error: DomainError
    ) : AdvanceFailure()

    /**
     * A required EXCLUSIVE resource lease is held by another work item, for a transition entering
     * [Role.WORK].
     *
     * Deliberately a SIBLING of [GateBlocked], not a variant of it: a gate block is a permanent
     * rejection the caller fixes by writing notes, whereas this is a TRANSIENT rejection the caller
     * fixes by waiting. Callers map it to a retryable error shape (MCP `resource_unavailable` with
     * `errorKind=transient`, REST 409 + `Retry-After`).
     *
     * **No holder identity is carried here.** Learning which item or actor holds a contended
     * resource is an operator-only capability exposed through `GET /api/v1/resources/leases`
     * (ADMIN-gated) — never through an advance rejection, which any agent can trigger by probing.
     *
     * @property message human-readable summary naming the contended resource keys.
     * @property targetRole the role the transition would have moved to (always [Role.WORK]).
     * @property contendedResources every contended resource key, not just the first.
     * @property retryAfterMs backoff hint in milliseconds (soonest lease expiry), or null when it
     *   could not be computed.
     */
    data class ResourceLeaseUnavailable(
        val message: String,
        val targetRole: Role,
        val contendedResources: List<String>,
        val retryAfterMs: Long?,
        override val error: DomainError
    ) : AdvanceFailure()

    /** The persistence step failed (a store fault anywhere in the advance unit; nothing was applied). */
    data class ApplyFailed(
        val message: String,
        override val error: DomainError
    ) : AdvanceFailure()
}

/**
 * One unmet blocking dependency of a transition.
 *
 * @property itemId the item whose transition is blocked.
 * @property fromItemId the blocker.
 * @property currentRole the blocker's current role; null when the blocker row could not be read
 *   (reported on the wire as `"unknown"`).
 * @property requiredRole the role (lowercase) the blocker must reach.
 */
data class BlockerInfo(
    val itemId: UUID,
    val fromItemId: UUID,
    val currentRole: Role?,
    val requiredRole: String
) {
    companion object {
        /** The wire value for an unreadable blocker's role. */
        const val UNKNOWN_ROLE: String = "unknown"
    }
}

/**
 * A single cascade transition evaluated as a side effect of the primary advance, inside the same
 * unit of work.
 *
 * Covers terminal cascades (child completion auto-completing a parent), start cascades (first
 * child starting auto-advancing a queued parent), and reopen cascades. The structured form lets the
 * tool and route layers build their own JSON/DTO shapes.
 *
 * @property gateBlocked true when a cascade was suppressed because the parent had unfilled required
 *   notes (or a blocking independence finding); in that case [applied] is false and
 *   [gateMissingNotes] is populated. Raised by TERMINAL cascades (all required notes, all phases,
 *   except a cancel-origin chain) and by START cascades (the parent's CURRENT-phase required notes
 *   only). REOPEN cascades never raise it.
 * @property gateMissingNotes structured required notes missing on the parent (only when [gateBlocked]).
 * @property resourceBlocked true when a START or REOPEN cascade into [Role.WORK] was suppressed
 *   because the parent's EXCLUSIVE resource leases are held by another item; [applied] is false and
 *   [contendedResources] is populated. The CHILD's own advance still succeeded.
 * @property contendedResources contended resource keys on the parent (only when [resourceBlocked]);
 *   never carries holder identity.
 * @property roleBlocked true when a TERMINAL cascade was suppressed because the parent is in
 *   [Role.BLOCKED] (an explicit hold); [applied] is false. Applies to cancel-originated cascades too.
 * @property dependencyBlocked true when a cascade was suppressed because the parent has an unmet
 *   blocking dependency (the same check a direct advance on the parent runs); [applied] is false
 *   and [blockers] is populated.
 * @property blockers the unmet blocking dependencies on the parent (only when [dependencyBlocked]).
 * @property error reserved; never populated. A cascade apply fault now fails (and rolls back) the
 *   whole advance instead of being reported per cascade. Kept for DTO compatibility.
 * @property violations A2 independence-attestation findings for the cascaded parent — null iff the
 *   cascade's note gate did not run (cancel-origin or reopen cascade, schema-free parent), independence
 *   mode is OFF, or the parent's schema declares no `independent_of`; otherwise a (possibly empty) list.
 */
data class AdvanceCascadeEvent(
    val itemId: UUID,
    val title: String,
    val previousRole: Role,
    val targetRole: Role,
    val applied: Boolean,
    val statusLabel: String? = null,
    val gateBlocked: Boolean = false,
    val gateMissingNotes: List<NoteSchemaEntry> = emptyList(),
    val resourceBlocked: Boolean = false,
    val contendedResources: List<String> = emptyList(),
    val roleBlocked: Boolean = false,
    val dependencyBlocked: Boolean = false,
    val blockers: List<BlockerInfo> = emptyList(),
    val error: String? = null,
    val violations: List<IndependenceViolation>? = null
)

/** A downstream item that became fully unblocked as a result of the primary advance. */
data class AdvanceUnblockedItem(
    val itemId: UUID,
    val title: String
)

/**
 * Structured result of a successful advance.
 *
 * Carries everything the caller needs to build its response without re-querying: the role
 * change, the applied item, the resolved schema + target role (for expectedNotes /
 * guidancePointer / noteProgress), and the cascade + unblock side effects.
 *
 * Contains NO JSON — the MCP tool maps this to its JSON response shape and the REST route maps
 * it to [io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.AdvanceResponseDto].
 */
data class AdvanceResult(
    val itemId: UUID,
    val previousRole: Role,
    val newRole: Role,
    val trigger: String,
    val applied: Boolean,
    /** The persisted item after the transition (carries the final statusLabel). */
    val appliedItem: WorkItem,
    /** Effective status label applied to the item, or null. */
    val statusLabel: String?,
    /** Optional human-readable summary recorded on the transition. */
    val summary: String?,
    /** Actor attribution recorded on the transition, or null. */
    val actorClaim: ActorClaim?,
    /** Verification attached to the actor claim, or null. */
    val verification: VerificationResult?,
    /** Cascade transitions applied (or suppressed) up the ancestor chain. */
    val cascadeEvents: List<AdvanceCascadeEvent>,
    /** Downstream items that became fully unblocked. */
    val unblockedItems: List<AdvanceUnblockedItem>,
    /** Resolved (trait-merged) schema for the item, or null in schema-free mode. */
    val resolvedSchema: WorkItemSchema?,
    /**
     * A2 independence-attestation findings for the PRIMARY transition — null iff the note gate did not
     * run for the trigger, independence mode is OFF, or the schema declares no `independent_of` in any
     * phase; otherwise a (possibly empty) list, populated in warn mode too.
     */
    val violations: List<IndependenceViolation>? = null
)

/**
 * The unified advance pipeline shared by the MCP `advance_item` tool, `complete_tree`, and the REST
 * `POST /items/{id}/advance` route.
 *
 * One advance is ONE write unit (`AdvanceService.advance`), which joins the ambient unit of an
 * idempotency-keyed element and is otherwise the only unit:
 *
 * 1. **Pre-policy** — trigger parse; the item is re-read by id inside the unit (no decision on a
 *    stale copy); the degraded-mode policy resolves the caller's trusted id (MCP only).
 * 2. **Snapshot** — [TransitionSnapshotLoader] loads the facts the policy reads.
 * 3. **Policy** — [TransitionPolicy.evaluate]: OWNERSHIP, TABLE, WARRANT, HOLD, DEPENDENCY, NOTE, LEASE
 *    in that order. A rejection records its `transition.rejected` / `lease.rejected` row through
 *    [io.github.jpicklyk.mcptask.current.application.port.EventSink.recordRejection] (it survives the
 *    rollback) and returns the mapped [AdvanceFailure].
 * 4. **Apply** — exclusive leases, the item row, the `role_transitions` row, the `item.transitioned`
 *    event, and the lease release on a WORK exit.
 * 5. **Cascades** — every [Decision.Allow.followUps] parent is evaluated by the same policy with a
 *    cascade trigger and applied in the same unit; a rejected cascade is reported as a suppressed
 *    [AdvanceCascadeEvent]; an inapplicable one (lifecycle, warrant) is silent.
 * 6. **Unblock** — downstream items whose blockers are now all satisfied.
 *
 * Any store fault anywhere rolls the whole unit back and returns [AdvanceFailure.ApplyFailed]: no
 * partial advance. A [PerRootConfigUnavailableException] on the primary propagates (the caller maps it
 * to `config_unavailable`); on a cascade parent it skips that cascade with a WARN and the primary still
 * commits (D7).
 *
 * @property labelFor the status label for a (trigger, target role) pair; production binds
 *   [io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver.labelFor] to the
 *   item's root. Defaults to the built-in labels ([NoOpStatusLabelService]).
 * @property resourceLeasesEnforced deployment kill switch, read from the `RESOURCE_LEASES_ENFORCED`
 *   environment variable at CONSTRUCTION time (see [resourceLeasesEnforcedFromEnv]). When false the
 *   lease gate and acquisition are skipped; releases still run.
 * @property clock the time source for ownership checks and `roleChangedAt`; the ambient unit instant
 *   wins inside a unit.
 */
class AdvanceService(
    private val workItemRepository: WorkItemRepository,
    private val roleTransitionRepository: TransitionStore,
    private val dependencyRepository: DependencyStore,
    private val noteRepository: NoteStore,
    private val labelFor: suspend (Trigger, Role) -> String? = DEFAULT_LABEL_FOR,
    private val schemaResolver: suspend (WorkItem) -> WorkItemSchema?,
    /** The transaction boundary: one advance is one unit. */
    private val unitOfWork: UnitOfWork,
    private val resourceLeaseRepository: LeaseStore? = null,
    private val resourceRequirementsResolver: suspend (WorkItem) -> List<ResourceRequirement> = { emptyList() },
    private val resourceRegistryResolver: suspend (UUID?) -> Map<String, ResourceDefinition> = { emptyMap() },
    private val resourceLeasesEnforced: Boolean = true,
    private val independencePolicyResolver: suspend (WorkItem) -> IndependencePolicy = { IndependencePolicy.DEFAULT },
    private val clock: Clock = Clock.SYSTEM,
    private val policy: TransitionPolicy = TransitionPolicy(),
    /** The owner of claim and lease writes and their events: always the shared instance wired by [AdvanceServiceFactory]. */
    claimService: ClaimService
) {
    private val claims: ClaimService = claimService

    private val loader =
        TransitionSnapshotLoader(
            workItemRepository,
            dependencyRepository,
            noteRepository,
            resourceLeaseRepository,
            schemaResolver,
            resourceRequirementsResolver,
            independencePolicyResolver
        )

    companion object {
        private val logger = LoggerFactory.getLogger(AdvanceService::class.java)

        /** Safety net on the number of cascades one advance applies. */
        private const val MAX_CASCADES = 100

        private const val UNIT_OP = "AdvanceService.advance"

        /** Environment variable name for the deployment-wide resource-lease kill switch. */
        const val RESOURCE_LEASES_ENFORCED_ENV = "RESOURCE_LEASES_ENFORCED"

        /** Fallback lease TTL when neither the requirement nor the registry specifies one. */
        const val DEFAULT_RESOURCE_TTL_SECONDS = 3600

        /** Inclusive lower bound applied to a resolved lease TTL. */
        const val MIN_RESOURCE_TTL_SECONDS = 1

        /** Inclusive upper bound (24h) applied to a resolved lease TTL. */
        const val MAX_RESOURCE_TTL_SECONDS = 86400

        /** The built-in status labels ([NoOpStatusLabelService]) under the shared key rule ([statusLabelKey]). */
        val DEFAULT_LABEL_FOR: suspend (Trigger, Role) -> String? =
            { trigger, target -> NoOpStatusLabelService.resolveLabel(statusLabelKey(trigger, target)) }

        /**
         * Reads the [RESOURCE_LEASES_ENFORCED_ENV] kill switch. Enforcement is ON by default;
         * parsed via the shared [io.github.jpicklyk.mcptask.current.infrastructure.config.EnvBoolean]
         * vocabulary (`true/1/yes` vs `false/0/no`, case-insensitive, trimmed).
         *
         * Call this at AdvanceService CONSTRUCTION sites and pass the result in — the pipeline
         * itself never touches the environment.
         */
        fun resourceLeasesEnforcedFromEnv(env: (String) -> String? = System::getenv): Boolean =
            EnvBoolean.parse(
                RESOURCE_LEASES_ENFORCED_ENV,
                env(RESOURCE_LEASES_ENFORCED_ENV),
                default = true,
            )
    }

    /**
     * Run the full advance pipeline for a single item + trigger, as one unit of work.
     *
     * @param item the [WorkItem] to transition; only its id is trusted (the row is re-read in the unit).
     * @param trigger the user trigger string (e.g. "start", "complete"; case-insensitive).
     * @param summary optional human-readable transition summary.
     * @param actorClaim optional actor attribution recorded on the transition.
     * @param verification optional verification result attached to the actor claim.
     * @param degradedModePolicy the deployment's degraded-mode policy.
     * @param enforceOwnership when true, the claim-ownership gate runs (MCP); when false it is skipped
     *   (REST) — the actor is still recorded for audit.
     * @param credentialRefs optional audit list of opaque credential labels consumed by this
     *   transition (never raw secret material). For an item that declares resources and enters WORK
     *   with the lease gate active, every ref must be a declared or registered resource key.
     * @param enforceResourceLeases when true (the default), the resource-lease gate and acquisition run.
     *   **Deliberately INDEPENDENT of [enforceOwnership]**: the REST route passes
     *   `enforceOwnership = false` but `enforceResourceLeases = true`; only an explicit ADMIN-only
     *   `overrideResourceLeases` request flag lowers it. ANDed with [resourceLeasesEnforced].
     */
    suspend fun advance(
        item: WorkItem,
        trigger: String,
        summary: String?,
        actorClaim: ActorClaim?,
        verification: VerificationResult?,
        degradedModePolicy: DegradedModePolicy,
        enforceOwnership: Boolean,
        credentialRefs: List<String> = emptyList(),
        enforceResourceLeases: Boolean = true
    ): AdvanceOutcome {
        val userTrigger =
            Trigger.User.parse(trigger)
                ?: return AdvanceOutcome.Failure(
                    AdvanceFailure.ResolutionFailed(
                        "Unknown trigger: '$trigger'. Valid triggers: ${Trigger.User.entries.joinToString { it.wire }}",
                        AdvanceErrors.invalidField("trigger", "unknown trigger", trigger, "Unknown trigger: '$trigger'")
                    )
                )

        // Degraded-mode policy (MCP only): resolve the caller's trusted id, or reject outright.
        var callerId: String? = null
        if (enforceOwnership && actorClaim != null && verification != null) {
            when (val resolution = ActorAware.resolveTrustedActorId(actorClaim, verification, degradedModePolicy)) {
                is PolicyResolution.Rejected -> return AdvanceOutcome.Failure(
                    AdvanceFailure.PolicyRejected(resolution.reason, AdvanceErrors.unauthenticated(resolution.reason))
                )
                is PolicyResolution.Trusted -> callerId = resolution.trustedId
            }
        }

        val leaseGateActive = enforceResourceLeases && resourceLeasesEnforced
        // Config (schema, independence policy, requirements, registry, labels) is read INSIDE the unit, against
        // the re-read row, so no gate decides on config read before the writer lock (AR-15). The unit's config
        // session has no last-known-good fallback: a per-root read fault fails the advance closed
        // (config_unavailable), keyed or unkeyed alike.
        val request =
            PrimaryRequest(
                itemId = item.id,
                trigger = userTrigger,
                summary = summary,
                actorClaim = actorClaim,
                verification = verification,
                ownership = OwnershipInput(enforceOwnership, callerId),
                leaseGateActive = leaseGateActive,
                credentialRefs = credentialRefs
            )
        return runUnit { primary(request) }
    }

    /**
     * Evaluates and applies [followUps] (parent cascades a structural edit produced: a reparent or a delete
     * re-evaluates the item's OLD parent) exactly like the cascades of an advance: the same policy with the
     * cascade trigger, the same note gate (a rejection is a suppressed [AdvanceCascadeEvent], never an error),
     * the same chaining through each applied cascade's own follow-ups, and a per-root config fault on a parent
     * skipping that cascade with a WARN.
     *
     * JOINS the ambient unit (the caller's structural write), so the cascade's role change, its transition row
     * and its `item.transitioned` (origin `cascade`) event commit or roll back with that write. A store fault
     * propagates and rolls the joined unit back.
     */
    suspend fun cascadeInUnit(followUps: List<FollowUp>): List<AdvanceCascadeEvent> {
        if (followUps.isEmpty()) return emptyList()
        var events: List<AdvanceCascadeEvent> = emptyList()
        val outcome =
            unitOfWork.write(UNIT_OP) {
                val now = clock.unitNow()
                events = runCascades(viewAt(this, now), followUps, resourceLeasesEnforced, now)
                Outcome.Ok(Unit)
            }
        if (outcome is Outcome.Err) throw IllegalStateException(applyFaultMessage(outcome.error))
        return events
    }

    private data class PrimaryRequest(
        val itemId: UUID,
        val trigger: Trigger.User,
        val summary: String?,
        val actorClaim: ActorClaim?,
        val verification: VerificationResult?,
        val ownership: OwnershipInput,
        val leaseGateActive: Boolean,
        val credentialRefs: List<String>
    )

    /** The value of a claim-service call; a fault aborts the advance (the unit rolls back, `apply_failed`). */
    private fun <T> Outcome<T>.orAbort(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> throw ApplyAbort(applyFaultMessage(error), error)
        }

    /** An apply step aborted by a missing row: rolls the unit back and becomes [AdvanceFailure.ApplyFailed]. */
    private class ApplyAbort(
        message: String,
        val error: DomainError
    ) : RuntimeException(message)

    /**
     * Runs [block] as the advance's single write unit. A [AdvanceOutcome.Failure] rolls the unit back
     * (its rejection row survives through `recordRejection`); a store fault, a poisoned unit or an
     * [ApplyAbort] becomes [AdvanceFailure.ApplyFailed]. A per-root config fault is rethrown for the
     * caller's `config_unavailable` mapping; cancellation is rethrown.
     *
     * The config fault is caught INSIDE the unit block, the unit is rolled back with an [Outcome.Err],
     * and the exception is rethrown only after the unit returns: thrown out of the block, the outermost
     * unit runner would translate it by its SQL cause into a store fault (`apply_failed`) instead.
     */
    private suspend fun runUnit(block: suspend WriteScope.() -> AdvanceOutcome): AdvanceOutcome {
        var rejected: AdvanceOutcome.Failure? = null
        var configFault: PerRootConfigUnavailableException? = null
        val outcome =
            try {
                unitOfWork.write(UNIT_OP) {
                    rejected = null
                    configFault = null
                    val result =
                        try {
                            block()
                        } catch (e: PerRootConfigUnavailableException) {
                            configFault = e
                            return@write Outcome.Err(DomainError(ErrorCode.INTERNAL, "Unit '$UNIT_OP' rolled back: ${e.message}"))
                        }
                    when (result) {
                        is AdvanceOutcome.Success -> Outcome.Ok(result)
                        is AdvanceOutcome.Failure -> {
                            rejected = result
                            Outcome.Err(result.failure.error)
                        }
                    }
                }
            } catch (e: PerRootConfigUnavailableException) {
                throw e
            } catch (e: ApplyAbort) {
                return AdvanceOutcome.Failure(AdvanceFailure.ApplyFailed(e.message ?: "Failed to apply transition", e.error))
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                val fault = LegacyFaults.fault(e)
                return AdvanceOutcome.Failure(AdvanceFailure.ApplyFailed(applyFaultMessage(fault), fault))
            }
        configFault?.let { throw it }
        return when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err ->
                rejected
                    ?: AdvanceOutcome.Failure(AdvanceFailure.ApplyFailed(applyFaultMessage(outcome.error), outcome.error))
        }
    }

    private fun applyFaultMessage(error: DomainError): String = "Failed to apply transition: ${LegacyFaults.message(error)}"

    /** A view of [scope] whose `now` is [at] (the service clock's unit instant). */
    private fun viewAt(
        scope: ReadScope,
        at: Instant
    ): ReadScope =
        object : ReadScope {
            override val stores: RepositoryProvider get() = scope.stores
            override val now: Instant = at
        }

    private suspend fun WriteScope.primary(request: PrimaryRequest): AdvanceOutcome {
        val now = clock.unitNow()
        val view = viewAt(this, now)
        val trigger = request.trigger
        val item =
            workItemRepository.getById(request.itemId)
                ?: return AdvanceOutcome.Failure(
                    AdvanceFailure.ApplyFailed(
                        "Failed to update item: WorkItem not found with id: ${request.itemId}",
                        AdvanceErrors.itemNotFound(request.itemId)
                    )
                )

        // An advance that evaluates a lapsed claim reports it (deduped); a rejected advance rolls the row back and
        // the hourly sweep backstops it.
        if (item.claimedBy != null) claims.detectExpiry(item).orAbort()

        val loaded = loader.loadDetailed(view, item, trigger, request.ownership, LeaseInput(request.leaseGateActive))
        val snapshot = loaded.snapshot
        val decision = policy.evaluate(snapshot, trigger)
        val target = (decision as? Decision.Allow)?.target ?: resolvedTarget(snapshot, trigger)

        // credentialRefs membership + derivation: only for an item that declares resources and enters
        // WORK with the lease gate active. Checked after the dependency and note gates and before the
        // lease gate, as before.
        var consumedCredentials = request.credentialRefs
        var registry: Map<String, ResourceDefinition>? = null
        val leaseStage = decision is Decision.Allow || (decision is Decision.Reject && decision.gate == GateId.LEASE)
        if (request.leaseGateActive && target == Role.WORK && loaded.requirements.isNotEmpty() && leaseStage) {
            val resolvedRegistry = resourceRegistryResolver(item.rootId)
            registry = resolvedRegistry
            val knownKeys = loaded.requirements.mapTo(mutableSetOf()) { it.key } + resolvedRegistry.keys
            val unknownRef = request.credentialRefs.firstOrNull { it !in knownKeys }
            if (unknownRef != null) {
                return AdvanceOutcome.Failure(
                    AdvanceFailure.ValidationFailed(
                        "credentialRefs entry '$unknownRef' is not a resource declared by this item or " +
                            "registered in the server's resources: registry. Known keys: " +
                            knownKeys.sorted().joinToString(),
                        emptyList(),
                        AdvanceErrors.invalidField(
                            "credentialRefs",
                            "not a declared or registered resource",
                            unknownRef,
                            "credentialRefs entry '$unknownRef' is not a declared or registered resource"
                        )
                    )
                )
            }
            val exclusive = loaded.requirements.filter { it.mode == ResourceMode.EXCLUSIVE }.map { it.key }
            val advisory = loaded.requirements.filter { it.mode == ResourceMode.ADVISORY }.map { it.key }
            consumedCredentials = (exclusive + advisory + request.credentialRefs).distinct()
        }

        val allow =
            when (decision) {
                is Decision.Allow -> decision
                is Decision.Reject -> return AdvanceOutcome.Failure(rejectPrimary(item, trigger, decision, loaded, target, request))
                // Not produced for a user trigger; treated as an invalid transition defensively.
                is Decision.NotApplicable ->
                    return AdvanceOutcome.Failure(
                        AdvanceFailure.ResolutionFailed(
                            legacyTableMessage(item, trigger, null),
                            AdvanceErrors.notApplicable(item, trigger)
                        )
                    )
            }

        val label = labelFor(trigger, allow.target)
        val applied =
            when (
                val result =
                    applyDecision(
                        item = item,
                        decision = allow,
                        trigger = trigger,
                        summary = request.summary,
                        label = label,
                        actorClaim = request.actorClaim,
                        verification = request.verification,
                        consumedCredentials = consumedCredentials,
                        requirements = loaded.requirements,
                        registry = registry,
                        now = now
                    )
            ) {
                is ApplyResult.Applied -> result.item
                is ApplyResult.Contended ->
                    return AdvanceOutcome.Failure(
                        AdvanceFailure.ResourceLeaseUnavailable(
                            message = leaseMessage(result.keys),
                            targetRole = Role.WORK,
                            contendedResources = result.keys,
                            retryAfterMs = result.retryAfterMs,
                            error = AdvanceErrors.contended(item.id, result.keys, result.retryAfterMs)
                        )
                    )
            }

        val cascadeEvents = runCascades(view, allow.followUps, request.leaseGateActive, now)
        val unblocked = findUnblocked(item.id)

        return AdvanceOutcome.Success(
            AdvanceResult(
                itemId = item.id,
                previousRole = item.role,
                newRole = allow.target,
                trigger = trigger.wire,
                applied = true,
                appliedItem = applied,
                statusLabel = applied.statusLabel,
                summary = request.summary,
                actorClaim = request.actorClaim,
                verification = request.verification,
                cascadeEvents = cascadeEvents,
                unblockedItems = unblocked,
                resolvedSchema = loaded.schema,
                violations = allow.violations
            )
        )
    }

    /** The table target for [trigger] from the snapshot item, or null when the table does not resolve one. */
    private fun resolvedTarget(
        snapshot: TransitionSnapshot,
        trigger: Trigger
    ): Role? =
        (
            TransitionTable.resolve(
                snapshot.item.role,
                trigger,
                snapshot.schema,
                snapshot.item.previousRole
            ) as? TransitionTable.Resolution.To
        )?.role

    /**
     * Maps a primary [Decision.Reject] to its [AdvanceFailure] (legacy 3.x messages) and records the
     * rejection row for a note, dependency or lease rejection, inside the unit (it survives the rollback).
     */
    private suspend fun WriteScope.rejectPrimary(
        item: WorkItem,
        trigger: Trigger.User,
        decision: Decision.Reject,
        loaded: LoadedSnapshot,
        target: Role?,
        request: PrimaryRequest
    ): AdvanceFailure =
        when (decision.gate) {
            GateId.OWNERSHIP -> {
                val message =
                    if (request.ownership.callerId == null) {
                        "Item is claimed by another agent and cannot be transitioned without providing " +
                            "actor credentials. Claim expires at ${item.claimExpiresAt}."
                    } else {
                        "Ownership check failed: this item is claimed by a different agent. " +
                            "Claim expires at ${item.claimExpiresAt}. " +
                            "Release the claim or wait for it to expire before transitioning."
                    }
                AdvanceFailure.OwnershipRejected(message, decision.error)
            }
            GateId.TABLE, GateId.HOLD ->
                AdvanceFailure.ResolutionFailed(legacyTableMessage(item, trigger, decision), decision.error)
            GateId.DEPENDENCY -> {
                val blockers = blockerInfos(item.id, (decision.context as? RejectContext.Dependency)?.unsatisfied.orEmpty())
                events.recordRejection(
                    DomainEvent.TransitionRejected(
                        entityId = item.id,
                        rootId = eventRoot(item),
                        trigger = trigger.wire,
                        code = DomainEvent.REJECTED_DEPENDENCY_UNMET,
                        blockerIds = blockers.map { it.fromItemId }.distinct(),
                        actor = request.actorClaim,
                        verification = request.verification
                    )
                )
                AdvanceFailure.ValidationFailed("${blockers.size} blocking dependency(ies) not yet satisfied", blockers, decision.error)
            }
            GateId.NOTE -> {
                val context = decision.context as? RejectContext.Notes
                val missingKeys = context?.missing.orEmpty().map { it.key }
                val missingEntries = schemaEntries(loaded.schema, missingKeys)
                val violations = context?.violations
                events.recordRejection(
                    DomainEvent.TransitionRejected(
                        entityId = item.id,
                        rootId = eventRoot(item),
                        trigger = trigger.wire,
                        code = DomainEvent.REJECTED_GATE_BLOCKED,
                        missingKeys = missingKeys,
                        actor = request.actorClaim,
                        verification = request.verification
                    )
                )
                val message =
                    if (missingEntries.isNotEmpty()) {
                        val keys = missingEntries.joinToString { it.key }
                        if (trigger == Trigger.User.START) {
                            "Gate check failed: required notes not filled for ${item.role.name.lowercase()} phase: $keys"
                        } else {
                            "Gate check failed: required notes not filled: $keys"
                        }
                    } else {
                        "Gate check failed: independence violations: " +
                            violations.orEmpty().joinToString { "${it.key} (${it.constraint.toJsonString()})" }
                    }
                AdvanceFailure.GateBlocked(
                    message = message,
                    previousRole = item.role,
                    targetRole = target ?: item.role,
                    missingNotes = missingEntries,
                    missingBySeat = computeMissingBySeat(loaded.schema, missingKeys),
                    violations = violations,
                    error = decision.error
                )
            }
            GateId.LEASE -> {
                val context = decision.context as? RejectContext.Lease
                val keys = context?.contended.orEmpty()
                events.recordRejection(DomainEvent.LeaseRejected(item.id, eventRoot(item), keys, context?.retryAfterMs))
                AdvanceFailure.ResourceLeaseUnavailable(
                    message = leaseMessage(keys),
                    targetRole = target ?: Role.WORK,
                    contendedResources = keys,
                    retryAfterMs = context?.retryAfterMs,
                    error = decision.error
                )
            }
        }

    private fun leaseMessage(keys: List<String>): String =
        "Cannot enter work phase: resource(s) currently held by another work item: " + keys.joinToString()

    /** The 3.x resolution message for a table rejection of [trigger] on [item]. */
    private fun legacyTableMessage(
        item: WorkItem,
        trigger: Trigger.User,
        decision: Decision.Reject?
    ): String {
        val role = item.role
        val roleWire = role.name.lowercase()
        val legacy =
            when (trigger) {
                Trigger.User.START ->
                    when (role) {
                        Role.TERMINAL -> "Cannot start: item is already terminal"
                        Role.BLOCKED -> "Cannot start: item is blocked. Use 'resume' trigger first"
                        else -> null
                    }
                Trigger.User.COMPLETE ->
                    when (role) {
                        Role.TERMINAL -> "Cannot complete: item is already terminal"
                        Role.BLOCKED -> "Cannot complete: item is blocked. Use 'resume' trigger first"
                        else -> null
                    }
                Trigger.User.BLOCK, Trigger.User.HOLD ->
                    when (role) {
                        Role.BLOCKED -> "Cannot block: item is already blocked"
                        Role.TERMINAL -> "Cannot block: item is already terminal"
                        else -> null
                    }
                Trigger.User.RESUME ->
                    when {
                        role != Role.BLOCKED -> "Cannot resume: item is not blocked (current role: $roleWire)"
                        item.previousRole == null -> "Cannot resume: item is blocked but has no previousRole to restore"
                        else ->
                            "Cannot resume: item is blocked but its previousRole " +
                                "(${item.previousRole.name.lowercase()}) cannot be restored"
                    }
                Trigger.User.CANCEL -> if (role == Role.TERMINAL) "Cannot cancel: item is already terminal" else null
                Trigger.User.REOPEN -> if (role != Role.TERMINAL) "Cannot reopen: item is not terminal (current role: $roleWire)" else null
            }
        return legacy ?: decision?.error?.message ?: "Failed to resolve transition"
    }

    /** Required schema entries for [keys], in [keys] order (first entry per key). */
    private fun schemaEntries(
        schema: WorkItemSchema?,
        keys: List<String>
    ): List<NoteSchemaEntry> {
        if (schema == null) return emptyList()
        return keys.mapNotNull { key ->
            schema.notes.firstOrNull { it.key == key && it.required }
                ?: schema.notes.firstOrNull { it.key == key }
        }
    }

    private fun blockerInfos(
        itemId: UUID,
        unsatisfied: List<UnsatisfiedBlocker>
    ): List<BlockerInfo> =
        unsatisfied.map {
            BlockerInfo(
                itemId = itemId,
                fromItemId = it.blockerId,
                currentRole = it.role,
                requiredRole = it.threshold.name.lowercase()
            )
        }

    /** The root an event row for [item] belongs to; the item's own id when the chain cannot be read. */
    private suspend fun eventRoot(item: WorkItem): UUID =
        item.rootId ?: try {
            eventRootOf(item.id, workItemRepository)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            item.id
        }

    private sealed interface ApplyResult {
        data class Applied(
            val item: WorkItem
        ) : ApplyResult

        /** Lease acquisition found a contended key (impossible under the writer lock; mapped to a lease rejection). */
        data class Contended(
            val keys: List<String>,
            val retryAfterMs: Long?
        ) : ApplyResult
    }

    /**
     * Applies an allowed transition inside the current unit: exclusive leases (entry into WORK), the
     * item row (previousRole / statusLabel / claim rules), the `role_transitions` row, the
     * `item.transitioned` event, and the lease release on a WORK exit. A missing row aborts the unit.
     */
    private suspend fun WriteScope.applyDecision(
        item: WorkItem,
        decision: Decision.Allow,
        trigger: Trigger,
        summary: String?,
        label: String?,
        actorClaim: ActorClaim?,
        verification: VerificationResult?,
        consumedCredentials: List<String>,
        requirements: List<ResourceRequirement>,
        registry: Map<String, ResourceDefinition>?,
        now: Instant
    ): ApplyResult {
        val leaseRepo = resourceLeaseRepository
        if (decision.acquireLeases.isNotEmpty() && leaseRepo != null) {
            val resolvedRegistry = registry ?: resourceRegistryResolver(item.rootId)
            val requests =
                requirements
                    .filter { it.mode == ResourceMode.EXCLUSIVE && it.key in decision.acquireLeases }
                    .distinctBy { it.key }
                    .map { it.key to resolveTtlSeconds(it, resolvedRegistry) }
            // Actor id is audit metadata only: exclusivity is keyed on the holder ITEM.
            when (val acquired = claims.acquireLeases(item.id, actorClaim?.id, requests).orAbort()) {
                is LeaseAcquireResult.Success -> {}
                is LeaseAcquireResult.Contended -> return ApplyResult.Contended(acquired.contendedKeys, acquired.retryAfterMs)
            }
        }

        val previousRole = item.role
        val targetRole = decision.target
        val touchesTerminal = targetRole == Role.TERMINAL || previousRole == Role.TERMINAL
        // Entering TERMINAL clears the claim unconditionally in the unit; leaving a terminal item that
        // still holds a claim (pre-fix rows) heals it.
        val releasingClaim = touchesTerminal && item.claimedBy != null
        val clearsClaim = targetRole == Role.TERMINAL || releasingClaim
        if (releasingClaim) {
            logger.info(
                "Releasing claim on transition to terminal state: itemId={}, trigger={}, previousHolder={}",
                item.id,
                trigger.wire,
                item.claimedBy
            )
        }

        val updatedItem =
            item.update { current ->
                current.copy(
                    role = targetRole,
                    previousRole =
                        when {
                            targetRole == Role.BLOCKED -> previousRole
                            previousRole == Role.BLOCKED -> null
                            else -> current.previousRole
                        },
                    statusLabel =
                        when {
                            label != null -> label
                            targetRole == Role.BLOCKED -> current.statusLabel
                            else -> null
                        },
                    roleChangedAt = now,
                    claimedBy = if (touchesTerminal) null else current.claimedBy,
                    claimedAt = if (touchesTerminal) null else current.claimedAt,
                    claimExpiresAt = if (touchesTerminal) null else current.claimExpiresAt,
                    originalClaimedAt = if (touchesTerminal) null else current.originalClaimedAt
                )
            }

        val updated =
            workItemRepository.update(updatedItem)
                ?: throw ApplyAbort(
                    "Failed to update item: WorkItem not found with id: ${item.id}",
                    AdvanceErrors.itemNotFound(item.id)
                )
        // update() never writes the claim columns: release the claim explicitly, in this unit.
        if (clearsClaim) claims.clearClaim(item.id).orAbort()

        val transition =
            RoleTransition(
                itemId = item.id,
                fromRole = previousRole.name.lowercase(),
                toRole = targetRole.name.lowercase(),
                fromStatusLabel = item.statusLabel,
                toStatusLabel = label,
                trigger = trigger.wire,
                summary = summary,
                actorClaim = actorClaim,
                verification = verification,
                consumedCredentials = consumedCredentials
            )
        roleTransitionRepository.create(transition)
        events.record(
            DomainEvent.ItemTransitioned(
                entityId = item.id,
                rootId = eventRoot(updated),
                trigger = transition.trigger,
                fromRole = transition.fromRole,
                toRole = transition.toRole,
                fromStatusLabel = transition.fromStatusLabel,
                toStatusLabel = transition.toStatusLabel,
                origin = if (trigger is Trigger.Cascade) TransitionOrigin.CASCADE else TransitionOrigin.USER,
                actor = actorClaim,
                verification = verification
            )
        )

        // Release on EVERY exit from WORK, in this unit, regardless of the kill switch (a lease acquired
        // while enforcement was on must still be released after it is turned off). A fault fails the advance.
        if (previousRole == Role.WORK && targetRole != Role.WORK && leaseRepo != null) {
            claims.releaseLeases(setOf(item.id)).orAbort()
        }
        return ApplyResult.Applied(updated)
    }

    /**
     * Resolves a lease TTL: the requirement's own override wins, then the registry entry's
     * `defaultTtlSeconds`, then [DEFAULT_RESOURCE_TTL_SECONDS], clamped to
     * [MIN_RESOURCE_TTL_SECONDS]..[MAX_RESOURCE_TTL_SECONDS].
     */
    private fun resolveTtlSeconds(
        requirement: ResourceRequirement,
        registry: Map<String, ResourceDefinition>
    ): Int {
        val raw =
            requirement.ttlSeconds
                ?: registry[requirement.key]?.defaultTtlSeconds
                ?: DEFAULT_RESOURCE_TTL_SECONDS
        return raw.coerceIn(MIN_RESOURCE_TTL_SECONDS, MAX_RESOURCE_TTL_SECONDS)
    }

    /** One cascade's evaluation: the event to report (null = nothing to report) and, when applied, its follow-ups. */
    private data class CascadeStep(
        val event: AdvanceCascadeEvent?,
        val followUps: List<FollowUp>
    )

    /**
     * Evaluates [followUps] (and the follow-ups of every applied cascade) in the current unit,
     * iteratively, up to [MAX_CASCADES] applied cascades.
     */
    private suspend fun WriteScope.runCascades(
        view: ReadScope,
        followUps: List<FollowUp>,
        leaseGateActive: Boolean,
        now: Instant
    ): List<AdvanceCascadeEvent> {
        val out = mutableListOf<AdvanceCascadeEvent>()
        val pending = ArrayDeque(followUps)
        var applied = 0
        while (pending.isNotEmpty()) {
            if (applied >= MAX_CASCADES) {
                logger.warn(
                    "Cascade safety net hit: {} cascades applied in one advance; remaining cascades are " +
                        "truncated. Investigate the ancestor chain for unexpected length or cycles.",
                    MAX_CASCADES
                )
                break
            }
            val followUp = pending.removeFirst()
            val step =
                try {
                    cascadeStep(view, followUp, leaseGateActive, now)
                } catch (e: PerRootConfigUnavailableException) {
                    // D7: a parent's config fault skips that cascade only; the primary still commits.
                    logger.warn(
                        "Per-root config unavailable evaluating a cascade on item {}; cascade not applied, " +
                            "item left unchanged: {}",
                        followUp.parentId,
                        e.message
                    )
                    null
                } ?: continue
            step.event?.let { out += it }
            if (step.event?.applied == true) {
                applied++
                pending.addAll(step.followUps)
            }
        }
        return out
    }

    private suspend fun WriteScope.cascadeStep(
        view: ReadScope,
        followUp: FollowUp,
        leaseGateActive: Boolean,
        now: Instant
    ): CascadeStep? {
        val parent = workItemRepository.getById(followUp.parentId) ?: return null
        val (trigger, reason) =
            when (followUp) {
                is FollowUp.TerminalCascade -> Trigger.Cascade.Complete(followUp.cancelOrigin) to "Auto-cascaded from child completion"
                is FollowUp.StartCascade -> Trigger.Cascade.Start to "Auto-cascaded from child start"
                is FollowUp.ReopenCascade -> Trigger.Cascade.Reopen to "Auto-cascaded from child reopen"
            }
        val loaded = loader.loadDetailed(view, parent, trigger, OwnershipInput.NONE, LeaseInput(leaseGateActive))
        return when (val decision = policy.evaluate(loaded.snapshot, trigger)) {
            is Decision.NotApplicable -> null
            is Decision.Reject -> CascadeStep(suppressedEvent(parent, trigger, decision, loaded), emptyList())
            is Decision.Allow -> {
                val label = labelFor(trigger, decision.target)
                when (
                    val result =
                        applyDecision(
                            item = parent,
                            decision = decision,
                            trigger = trigger,
                            summary = reason,
                            label = label,
                            actorClaim = null,
                            verification = null,
                            consumedCredentials = emptyList(),
                            requirements = loaded.requirements,
                            registry = null,
                            now = now
                        )
                ) {
                    is ApplyResult.Contended ->
                        CascadeStep(
                            AdvanceCascadeEvent(
                                itemId = parent.id,
                                title = parent.title,
                                previousRole = parent.role,
                                targetRole = decision.target,
                                applied = false,
                                resourceBlocked = true,
                                contendedResources = result.keys
                            ),
                            emptyList()
                        )
                    is ApplyResult.Applied ->
                        CascadeStep(
                            AdvanceCascadeEvent(
                                itemId = parent.id,
                                title = parent.title,
                                previousRole = parent.role,
                                targetRole = decision.target,
                                applied = true,
                                statusLabel = result.item.statusLabel,
                                violations = decision.violations
                            ),
                            decision.followUps
                        )
                }
            }
        }
    }

    /** The suppressed-cascade event for a rejected cascade; null for a gate cascades cannot fail. */
    private fun suppressedEvent(
        parent: WorkItem,
        trigger: Trigger.Cascade,
        decision: Decision.Reject,
        loaded: LoadedSnapshot
    ): AdvanceCascadeEvent? {
        val target = resolvedTarget(loaded.snapshot, trigger) ?: return null
        val base =
            AdvanceCascadeEvent(
                itemId = parent.id,
                title = parent.title,
                previousRole = parent.role,
                targetRole = target,
                applied = false
            )
        return when (decision.gate) {
            GateId.HOLD -> base.copy(roleBlocked = true)
            GateId.DEPENDENCY ->
                base.copy(
                    dependencyBlocked = true,
                    blockers = blockerInfos(parent.id, (decision.context as? RejectContext.Dependency)?.unsatisfied.orEmpty())
                )
            GateId.NOTE -> {
                val context = decision.context as? RejectContext.Notes
                base.copy(
                    gateBlocked = true,
                    gateMissingNotes = schemaEntries(loaded.schema, context?.missing.orEmpty().map { it.key }),
                    violations = context?.violations
                )
            }
            GateId.LEASE ->
                base.copy(
                    resourceBlocked = true,
                    contendedResources = (decision.context as? RejectContext.Lease)?.contended.orEmpty()
                )
            GateId.OWNERSHIP, GateId.TABLE -> null
        }
    }

    /** Items blocked by [itemId] whose every blocking dependency is now satisfied (fail-closed on unreadable blockers). */
    private suspend fun findUnblocked(itemId: UUID): List<AdvanceUnblockedItem> {
        val targets = loader.edgesBlockedBy(itemId).map { it.blocked }.distinct()
        if (targets.isEmpty()) return emptyList()
        val edgesByTarget = targets.associateWith { loader.blockingEdgesOf(it) }
        val roles =
            loader.rolesOf(
                edgesByTarget.values
                    .flatten()
                    .map { it.blocker }
                    .toSet()
            )
        val unblockedIds =
            targets.filter { target ->
                BlockerEvaluator.unsatisfied(setOf(target), edgesByTarget.getValue(target), roles)[target].isNullOrEmpty()
            }
        if (unblockedIds.isEmpty()) return emptyList()
        val titles = workItemRepository.findByIds(unblockedIds.toSet()).associate { it.id to it.title }
        return unblockedIds.mapNotNull { id -> titles[id]?.let { AdvanceUnblockedItem(id, it) } }
    }
}

/**
 * Outcome of [AdvanceService.advance]: either a structured [AdvanceResult] or a structured
 * [AdvanceFailure]. Modeled as a sealed type so callers exhaustively handle both paths.
 */
sealed class AdvanceOutcome {
    data class Success(
        val result: AdvanceResult
    ) : AdvanceOutcome()

    data class Failure(
        val failure: AdvanceFailure
    ) : AdvanceOutcome()
}

/**
 * Catalog errors for the [AdvanceFailure]s raised outside the transition policy, so every failure carries a
 * [DomainError] (the mappers read `kind` from it). Messages are descriptive only: each variant keeps its own
 * 3.x wire message.
 */
internal object AdvanceErrors {
    fun invalidField(
        field: String,
        reason: String,
        received: String?,
        message: String
    ): DomainError =
        DomainError(
            code = ErrorCode.INVALID_REQUEST,
            message = message,
            detail = ErrorDetail.InvalidRequest(listOf(FieldViolation(field, reason, received)))
        )

    fun unauthenticated(reason: String): DomainError =
        DomainError(ErrorCode.UNAUTHENTICATED, reason.ifBlank { "Actor verification rejected by the degraded-mode policy" })

    fun itemNotFound(id: UUID): DomainError =
        DomainError(
            code = ErrorCode.NOT_FOUND,
            message = "WorkItem not found with id: $id",
            detail = ErrorDetail.NotFound(EntityKind.ITEM, id.toString()),
            fixArgs = mapOf("kind" to "item", "id" to id.toString())
        )

    fun notApplicable(
        item: WorkItem,
        trigger: Trigger.User
    ): DomainError =
        DomainError(
            code = ErrorCode.INVALID_TRANSITION,
            message = "Trigger '${trigger.wire}' is not applicable to item ${item.id} in role ${item.role.toJsonString()}",
            detail = ErrorDetail.InvalidTransition(item.id, item.role.toJsonString(), trigger.wire, emptyList()),
            fixArgs = mapOf("itemId" to item.id.toString(), "trigger" to trigger.wire, "fromRole" to item.role.toJsonString())
        )

    fun contended(
        itemId: UUID,
        keys: List<String>,
        retryAfterMs: Long?
    ): DomainError =
        DomainError(
            code = ErrorCode.RESOURCE_UNAVAILABLE,
            message = "Resource(s) currently held by another work item: ${keys.joinToString()}",
            detail =
                ErrorDetail.ResourceUnavailable(
                    itemId,
                    keys.ifEmpty { listOf("unknown") }.map { ResourceRef(it, "exclusive") },
                    retryAfterMs
                ),
            fixArgs = mapOf("itemId" to itemId.toString())
        )
}
