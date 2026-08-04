package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import org.slf4j.LoggerFactory

/**
 * Reason an advance attempt failed before (or instead of) applying the transition.
 *
 * Each variant carries the structured data a caller needs to build its own error envelope
 * (MCP JSON or REST DTO) — no JSON is produced by [AdvanceService].
 */
sealed class AdvanceFailure {
    /** The caller does not hold the active claim on an actively-claimed item. */
    data class OwnershipRejected(
        val message: String
    ) : AdvanceFailure()

    /** The configured [DegradedModePolicy] rejected the actor's verification status. */
    data class PolicyRejected(
        val reason: String
    ) : AdvanceFailure()

    /** The trigger could not be resolved to a target role from the item's current role. */
    data class ResolutionFailed(
        val message: String
    ) : AdvanceFailure()

    /** A blocking dependency prevents the forward transition. */
    data class ValidationFailed(
        val message: String,
        val blockers: List<BlockerInfo>
    ) : AdvanceFailure()

    /**
     * A required-note gate blocked the transition (start or complete).
     *
     * @property message human-readable summary including the missing keys
     * @property previousRole the item's role at the time the transition was attempted (the phase
     *   whose gate rejected it)
     * @property targetRole the role the transition would have moved to (for context)
     * @property missingNotes the structured required notes that are still unfilled
     */
    data class GateBlocked(
        val message: String,
        val previousRole: Role,
        val targetRole: Role,
        val missingNotes: List<NoteSchemaEntry>
    ) : AdvanceFailure()

    /**
     * A required EXCLUSIVE resource lease could not be acquired for a transition entering
     * [Role.WORK] — another work item currently holds at least one of the item's declared
     * resources (or the lease store hit a transient write conflict).
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
     * @property retryAfterMs backoff hint in milliseconds (soonest lease expiry), or null when the
     *   store could not compute one.
     */
    data class ResourceLeaseUnavailable(
        val message: String,
        val targetRole: Role,
        val contendedResources: List<String>,
        val retryAfterMs: Long?
    ) : AdvanceFailure()

    /** The persistence step failed (DB error during apply). */
    data class ApplyFailed(
        val message: String
    ) : AdvanceFailure()
}

/**
 * A single cascade transition detected and applied as a side effect of the primary advance.
 *
 * Covers terminal cascades (child completion auto-completing a parent), start cascades
 * (first child starting auto-advancing a queued parent), and reopen cascades. The structured
 * form lets the tool and route layers build their own JSON/DTO shapes.
 *
 * @property gateBlocked true when a terminal cascade was suppressed because the parent had
 *   unfilled required notes; in that case [applied] is false and [gateMissingNotes] is populated.
 * @property gateMissingNotes structured required notes missing on the parent (only when [gateBlocked]).
 * @property resourceBlocked true when a START cascade into [Role.WORK] was suppressed because the
 *   parent's EXCLUSIVE resource leases are held by another item; [applied] is false and
 *   [contendedResources] is populated. Mirrors the [gateBlocked] suppression shape. The CHILD's own
 *   advance still succeeded — only the parent's auto-start was skipped.
 * @property contendedResources contended resource keys on the parent (only when [resourceBlocked]);
 *   never carries holder identity.
 */
data class AdvanceCascadeEvent(
    val itemId: java.util.UUID,
    val title: String,
    val previousRole: Role,
    val targetRole: Role,
    val applied: Boolean,
    val statusLabel: String? = null,
    val gateBlocked: Boolean = false,
    val gateMissingNotes: List<NoteSchemaEntry> = emptyList(),
    val resourceBlocked: Boolean = false,
    val contendedResources: List<String> = emptyList()
)

/** A downstream item that became fully unblocked as a result of the primary advance. */
data class AdvanceUnblockedItem(
    val itemId: java.util.UUID,
    val title: String
)

/**
 * Structured result of a successful advance.
 *
 * Carries everything the caller needs to build its response without re-querying: the role
 * change, the applied item, the resolved schema + target role (for expectedNotes /
 * guidancePointer / noteProgress), and the detected cascade + unblock side effects.
 *
 * Contains NO JSON — the MCP tool maps this to its JSON response shape and the REST route maps
 * it to [io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.AdvanceResponseDto].
 */
data class AdvanceResult(
    val itemId: java.util.UUID,
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
    /** Cascade transitions applied (or gate-blocked) up the ancestor chain. */
    val cascadeEvents: List<AdvanceCascadeEvent>,
    /** Downstream items that became fully unblocked. */
    val unblockedItems: List<AdvanceUnblockedItem>,
    /** Resolved (trait-merged) schema for the item, or null in schema-free mode. */
    val resolvedSchema: WorkItemSchema?
)

/**
 * The unified advance pipeline shared by the MCP `advance_item` tool and the REST
 * `POST /items/{id}/advance` route.
 *
 * Owns the full sequence that previously lived inline in `AdvanceItemTool.executeTransitions`:
 *
 * 1. **Ownership pre-check** — gated by [enforceOwnership]. MCP passes `true` (claim ownership is
 *    enforced); REST passes `false` (operators bypass claim ownership but their actor is still
 *    recorded for audit).
 * 2. **Resolve** — trigger + current role → target role (review-phase-aware).
 * 3. **Validate** — dependency-constraint check.
 * 4. **Gate check** — required-note enforcement for start/complete via [GatePredicate].
 * 4.5 **Resource-lease gate** — for transitions ENTERING [Role.WORK] only, acquires the item's
 *    declared EXCLUSIVE resource leases (see [resourceRequirementsResolver]). Short-circuits with
 *    ZERO lease-store access when the item declares no resources, so non-adopters pay nothing.
 * 5. **Apply** — persist the role change + audit row atomically (single inner transaction).
 * 6. **Cascade detection** — terminal / start / reopen cascades, each applied in its OWN
 *    transaction boundary (the cascade applies are never wrapped in one outer transaction).
 * 7. **Unblock detection** — downstream items whose blocking deps are now satisfied.
 *
 * Returns a structured [AdvanceResult] on success, or a structured [AdvanceFailure] on any
 * rejection. It produces NO JSON. The repository `update()` event-decorator mechanism (which emits
 * `ITEM_ADVANCED` on role change) is unchanged — this service never publishes events explicitly.
 *
 * The handler's [RoleTransitionHandler.cascadeTransition] is `internal`; this service lives in the
 * same `application/service` module, so it can drive cascades through that internal entry point
 * without exposing a public path to cascade transitions.
 *
 * @property workItemRepository repository for item reads + persistence.
 * @property roleTransitionRepository audit-trail repository.
 * @property dependencyRepository dependency-graph repository.
 * @property noteRepository note repository (for gate checks).
 * @property statusLabelService resolves config-driven status labels per trigger.
 * @property schemaResolver resolves the trait-merged [WorkItemSchema] for an item (or null).
 *   Provided by the caller so the service does not depend on `ToolExecutionContext`.
 * @property resourceLeaseRepository lease store used by the step-4.5 resource gate and by the
 *   WORK-exit release paths. Null (the default) means no lease wiring is available — the resource
 *   gate then logs an error and proceeds if an item somehow declares resources, which cannot happen
 *   with the default [resourceRequirementsResolver].
 * @property resourceRequirementsResolver resolves an item's declared resource requirements
 *   (trait-merged, schema-free-safe — see `ToolExecutionContext.resolveResourceRequirements`).
 *   Injected in the same style as [schemaResolver]; defaults to "no requirements", which keeps the
 *   whole resource path dormant for callers that do not wire it.
 * @property resourceRegistryResolver resolves the effective `resources:` registry for a root, used
 *   for TTL defaults and for the credentialRefs membership check. Defaults to an empty registry.
 * @property resourceLeasesEnforced deployment kill switch, read from the `RESOURCE_LEASES_ENFORCED`
 *   environment variable at CONSTRUCTION time (see [resourceLeasesEnforcedFromEnv]) — never deep in
 *   the pipeline. When false, step 4.5 and the start-cascade acquisition are skipped entirely;
 *   releases still run, because releasing a lease is always safe.
 */
class AdvanceService(
    private val workItemRepository: WorkItemRepository,
    private val roleTransitionRepository: RoleTransitionRepository,
    private val dependencyRepository: DependencyRepository,
    private val noteRepository: NoteRepository,
    private val statusLabelService: StatusLabelService,
    private val schemaResolver: suspend (WorkItem) -> WorkItemSchema?,
    private val resourceLeaseRepository: ResourceLeaseRepository? = null,
    private val resourceRequirementsResolver: suspend (WorkItem) -> List<ResourceRequirement> = { emptyList() },
    private val resourceRegistryResolver: suspend (java.util.UUID?) -> Map<String, ResourceDefinition> = { emptyMap() },
    private val resourceLeasesEnforced: Boolean = true
) {
    private val handler = RoleTransitionHandler()
    private val cascadeDetector = CascadeDetector()

    companion object {
        private val logger = LoggerFactory.getLogger(AdvanceService::class.java)
        private const val MAX_CASCADES = 100

        /** Environment variable name for the deployment-wide resource-lease kill switch. */
        const val RESOURCE_LEASES_ENFORCED_ENV = "RESOURCE_LEASES_ENFORCED"

        /** Fallback lease TTL when neither the requirement nor the registry specifies one. */
        const val DEFAULT_RESOURCE_TTL_SECONDS = 3600

        /** Inclusive lower bound applied to a resolved lease TTL. */
        const val MIN_RESOURCE_TTL_SECONDS = 1

        /** Inclusive upper bound (24h) applied to a resolved lease TTL. */
        const val MAX_RESOURCE_TTL_SECONDS = 86400

        /**
         * Backoff hint surfaced when the lease store returns
         * [LeaseAcquireResult.DBError] — its KDoc documents that a lost cross-transaction race can
         * surface as a DB error rather than a [LeaseAcquireResult.Contended], with no computable
         * expiry, so callers treat it as transient with a fixed default.
         */
        const val LEASE_DB_ERROR_RETRY_AFTER_MS = 1000L

        /**
         * Reads the [RESOURCE_LEASES_ENFORCED_ENV] kill switch. Enforcement is ON by default; only
         * the literal string "false" (case-insensitive) disables it, matching the
         * `API_WARN_ON_CLAIMED_ADVANCE` / `API_REDACT_*` convention in `AppConfig`.
         *
         * Call this at AdvanceService CONSTRUCTION sites and pass the result in — the pipeline
         * itself never touches the environment.
         */
        fun resourceLeasesEnforcedFromEnv(env: (String) -> String? = System::getenv): Boolean =
            env(RESOURCE_LEASES_ENFORCED_ENV)?.lowercase() != "false"
    }

    /**
     * Run the full advance pipeline for a single item + trigger.
     *
     * @param item the freshly-read [WorkItem] to transition.
     * @param trigger the canonical user trigger string (e.g. "start", "complete").
     * @param summary optional human-readable transition summary.
     * @param actorClaim optional actor attribution recorded on the transition.
     * @param verification optional verification result attached to the actor claim.
     * @param degradedModePolicy the deployment's degraded-mode policy.
     * @param enforceOwnership when true, the claim-ownership pre-check runs (MCP); when false it is
     *   skipped entirely (REST) — the transition proceeds regardless of claim state.
     * @param credentialRefs optional audit list of opaque credential/secret labels consumed by this
     *   transition (never raw secret material). Defaults to empty — additive, no behavior change
     *   when omitted. Caller (MCP tool / REST route) is responsible for FORMAT validation before
     *   calling [advance]; this service adds a SET-MEMBERSHIP check (and the derived resource keys)
     *   only for items that actually declare resources — see step 4.5.
     * @param enforceResourceLeases when true (the default), the step-4.5 resource-lease gate and
     *   the start-cascade lease acquisition run. **Deliberately INDEPENDENT of [enforceOwnership].**
     *   The REST route passes `enforceOwnership = false` but `enforceResourceLeases = true`: claim
     *   ownership is an *agent's* bookkeeping that an operator may legitimately override, whereas a
     *   resource lease protects a shared EXTERNAL resource (a credential, a staging environment)
     *   whose contention an operator cannot make safe by asserting authority. Only an explicit
     *   ADMIN-only `overrideResourceLeases` request flag lowers this to false on the REST path; MCP
     *   always passes true. The deployment-wide [resourceLeasesEnforced] kill switch is ANDed with
     *   this parameter — either one being false disables the gate.
     * @return [AdvanceOutcome.Success] with a structured [AdvanceResult], or
     *   [AdvanceOutcome.Failure] with a structured [AdvanceFailure].
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
        val previousRole = item.role
        val itemSchema = schemaResolver(item)

        // Use DB-side time so freshness is evaluated on the DB clock, not the JVM clock.
        val dbNow = workItemRepository.dbNow()

        // 1. Ownership pre-check (only when enforced).
        if (enforceOwnership) {
            when (
                val ownershipResult =
                    handler.checkOwnershipForTransition(item, actorClaim, verification, degradedModePolicy, dbNow)
            ) {
                is OwnershipCheckResult.Allowed -> {} // proceed
                is OwnershipCheckResult.Rejected ->
                    return AdvanceOutcome.Failure(AdvanceFailure.OwnershipRejected(ownershipResult.error))
                is OwnershipCheckResult.PolicyRejected ->
                    return AdvanceOutcome.Failure(AdvanceFailure.PolicyRejected(ownershipResult.reason))
            }
        }

        // 2. Resolve — schema-driven review-phase detection.
        val hasReviewPhase = itemSchema?.hasReviewPhase() ?: false
        val resolution = handler.resolveTransition(item, trigger, hasReviewPhase)
        if (!resolution.success || resolution.targetRole == null) {
            return AdvanceOutcome.Failure(
                AdvanceFailure.ResolutionFailed(resolution.error ?: "Failed to resolve transition")
            )
        }
        val targetRole = resolution.targetRole
        // "start" ordinarily maps to the in-progress label, but resolveStart() returns
        // targetRole=TERMINAL when the schema has no review phase (WORK->TERMINAL) or the item was
        // already in REVIEW (REVIEW->TERMINAL) — the item is actually completing, not merely
        // starting work. Look the label up under "complete" in that case so start-to-terminal and
        // complete-to-terminal converge on the same terminal label instead of stamping the
        // work-phase label ("in-progress") on a terminal item (bug 100da214). "cancel" already
        // carries its own hardcoded resolution.statusLabel ("cancelled"), which takes precedence
        // below regardless of this lookup, so it is deliberately excluded from the remap.
        val labelLookupTrigger = if (trigger == "start" && targetRole == Role.TERMINAL) "complete" else trigger
        val configLabel = statusLabelService.resolveLabel(labelLookupTrigger)

        // 3. Validate dependency constraints.
        val validation = handler.validateTransition(item, targetRole, dependencyRepository, workItemRepository)
        if (!validation.valid) {
            return AdvanceOutcome.Failure(
                AdvanceFailure.ValidationFailed(
                    validation.error ?: "Transition validation failed",
                    validation.blockers
                )
            )
        }

        // 4. Gate check — required notes for start / complete.
        if (itemSchema != null && (trigger == "start" || trigger == "complete")) {
            val gateFailure = checkGate(item, itemSchema, trigger, targetRole)
            if (gateFailure != null) return AdvanceOutcome.Failure(gateFailure)
        }

        // 4.5 Resource-lease gate — ONLY for transitions entering WORK.
        //
        // targetRole (not the trigger) is the correct discriminator: it covers "start" QUEUE->WORK
        // AND "resume" BLOCKED->WORK, which the note gate above deliberately does not (its
        // trigger == "start" || "complete" condition is left untouched).
        val leaseGateActive = enforceResourceLeases && resourceLeasesEnforced
        val effectiveCredentialRefs =
            if (targetRole == Role.WORK && leaseGateActive) {
                when (val gate = runResourceLeaseGate(item, targetRole, actorClaim, credentialRefs)) {
                    is ResourceGateOutcome.Rejected -> return AdvanceOutcome.Failure(gate.failure)
                    is ResourceGateOutcome.Proceed -> gate.credentialRefs
                }
            } else {
                credentialRefs
            }

        // 5. Apply — routes through applyTransition (atomic role change + audit row).
        // roleChangedAt is sourced from the DB clock for consistency with range-filter queries.
        val effectiveLabel = resolution.statusLabel ?: configLabel
        val applyResult =
            handler.applyTransition(
                item,
                targetRole,
                trigger,
                summary,
                effectiveLabel,
                workItemRepository,
                roleTransitionRepository,
                actorClaim = actorClaim,
                verification = verification,
                roleChangedAt = dbNow,
                consumedCredentials = effectiveCredentialRefs
            )
        if (!applyResult.success || applyResult.item == null) {
            return AdvanceOutcome.Failure(
                AdvanceFailure.ApplyFailed(applyResult.error ?: "Failed to apply transition")
            )
        }
        val appliedItem = applyResult.item

        // 5.5 Release on EVERY exit from WORK — one condition covers complete, cancel, block, hold,
        // and reopen-out-of-work. Releasing is unconditional on the kill switch: a lease acquired
        // while enforcement was on must still be released after it is turned off.
        if (previousRole == Role.WORK && targetRole != Role.WORK) {
            releaseLeases(item.id, "work-exit ($trigger)")
        }

        // 6. Cascade detection — each cascade apply is its OWN transaction boundary (inside
        //    applyTransition/cascadeTransition); detection and apply are intentionally split.
        val cascadeEvents = mutableListOf<AdvanceCascadeEvent>()
        when {
            targetRole == Role.TERMINAL -> detectAndApplyTerminalCascades(appliedItem, trigger, cascadeEvents)
            targetRole == Role.WORK -> {
                val startEvents = cascadeDetector.detectStartCascades(appliedItem, workItemRepository)
                applyCascadeEvents(startEvents, "Auto-cascaded from child start", cascadeEvents, leaseGateActive)
            }
        }
        if (trigger == "reopen" && targetRole == Role.QUEUE) {
            val reopenEvents = cascadeDetector.detectReopenCascades(appliedItem, workItemRepository, schemaResolver)
            applyCascadeEvents(reopenEvents, "Auto-cascaded from child reopen", cascadeEvents, leaseGateActive)
        }

        // 7. Unblock detection.
        val unblocked =
            cascadeDetector
                .findUnblockedItems(appliedItem, dependencyRepository, workItemRepository)
                .map { AdvanceUnblockedItem(it.itemId, it.title) }

        return AdvanceOutcome.Success(
            AdvanceResult(
                itemId = item.id,
                previousRole = previousRole,
                newRole = targetRole,
                trigger = trigger,
                applied = true,
                appliedItem = appliedItem,
                statusLabel = appliedItem.statusLabel,
                summary = summary,
                actorClaim = actorClaim,
                verification = verification,
                cascadeEvents = cascadeEvents,
                unblockedItems = unblocked,
                resolvedSchema = itemSchema
            )
        )
    }

    /**
     * Gate check for the primary transition. Returns a [AdvanceFailure.GateBlocked] when required
     * notes are missing, or null when the gate passes (or there are no required notes to enforce).
     */
    private suspend fun checkGate(
        item: WorkItem,
        schema: WorkItemSchema,
        trigger: String,
        targetRole: Role
    ): AdvanceFailure.GateBlocked? {
        val existingNotes =
            when (val notesResult = noteRepository.findByItemId(item.id)) {
                is Result.Success -> notesResult.data
                is Result.Error -> emptyList()
            }
        val filledKeys = GatePredicate.filledNoteKeys(existingNotes)

        val missingEntries =
            when (trigger) {
                "start" -> GatePredicate.missingForStart(schema, item.role, filledKeys)
                "complete" -> GatePredicate.missingForComplete(schema, filledKeys)
                else -> emptyList()
            }

        if (missingEntries.isEmpty()) return null

        val missingKeys = missingEntries.joinToString { it.key }
        val message =
            if (trigger == "start") {
                "Gate check failed: required notes not filled for ${item.role.name.lowercase()} phase: $missingKeys"
            } else {
                "Gate check failed: required notes not filled: $missingKeys"
            }
        return AdvanceFailure.GateBlocked(message, item.role, targetRole, missingEntries)
    }

    /** Result of the step-4.5 resource gate: proceed (with derived refs) or reject the advance. */
    private sealed class ResourceGateOutcome {
        /** Leases acquired (or none needed); [credentialRefs] is the final audit list to persist. */
        data class Proceed(
            val credentialRefs: List<String>
        ) : ResourceGateOutcome()

        data class Rejected(
            val failure: AdvanceFailure
        ) : ResourceGateOutcome()
    }

    /**
     * Step 4.5 — the resource-lease gate for a transition entering [Role.WORK].
     *
     * Order of operations:
     * 1. Resolve the item's declared requirements. **If there are none, return immediately with the
     *    caller's refs untouched — zero lease-repository access, zero registry resolution.** This is
     *    the common path for every item that does not use resources, and it must stay free.
     * 2. Resolve the registry (TTL defaults + the known-key set).
     * 3. Tighten `credentialRefs` validation: reject any caller-supplied ref outside
     *    `declared keys ∪ registry keys`. Only reachable when the item declares at least one
     *    resource — step (1) short-circuits before this regardless of registry state, so
     *    non-adopters keep rung-1 behavior (format validation only).
     * 4. Acquire all EXCLUSIVE keys in one all-or-nothing call. ADVISORY keys are never locked.
     * 5. Derive the final `consumedCredentials`: derived keys (exclusive, then advisory) first, then
     *    any caller-supplied extras, deduped.
     *
     * The acquire IS the snapshot of the item's requirements for its whole work phase — requirements
     * are never re-resolved mid-work. A `resume` out of BLOCKED re-enters WORK and therefore
     * re-resolves and re-acquires against the config as it stands at resume time, which is the
     * intended behavior (the item lost its leases when it exited WORK into BLOCKED).
     */
    private suspend fun runResourceLeaseGate(
        item: WorkItem,
        targetRole: Role,
        actorClaim: ActorClaim?,
        credentialRefs: List<String>
    ): ResourceGateOutcome {
        // (1) Short-circuit: no declared resources → nothing to lock, nothing to derive.
        val requirements = resourceRequirementsResolver(item)
        if (requirements.isEmpty()) return ResourceGateOutcome.Proceed(credentialRefs)

        // (2) Registry: TTL defaults and the set of server-known keys.
        val registry = resourceRegistryResolver(item.rootId)

        // (3) Membership tightening — declared keys plus every registered key.
        val knownKeys = requirements.mapTo(mutableSetOf()) { it.key } + registry.keys
        val unknownRef = credentialRefs.firstOrNull { it !in knownKeys }
        if (unknownRef != null) {
            return ResourceGateOutcome.Rejected(
                AdvanceFailure.ValidationFailed(
                    "credentialRefs entry '$unknownRef' is not a resource declared by this item or " +
                        "registered in the server's resources: registry. Known keys: " +
                        knownKeys.sorted().joinToString(),
                    emptyList()
                )
            )
        }

        val exclusiveKeys = requirements.filter { it.mode == ResourceMode.EXCLUSIVE }.map { it.key }
        val advisoryKeys = requirements.filter { it.mode == ResourceMode.ADVISORY }.map { it.key }

        // (4) Acquire the EXCLUSIVE set. Actor id is audit metadata ONLY — exclusivity is keyed on
        // the holder ITEM in the repository, so a shared or self-reported actor id cannot widen or
        // narrow the guarantee.
        if (exclusiveKeys.isNotEmpty()) {
            val leaseRepo = resourceLeaseRepository
            if (leaseRepo == null) {
                logger.error(
                    "Item {} declares {} exclusive resource(s) {} but no ResourceLeaseRepository is wired " +
                        "into AdvanceService — the resource gate is being SKIPPED. This is a wiring bug at " +
                        "the AdvanceService construction site, not a runtime condition.",
                    item.id,
                    exclusiveKeys.size,
                    exclusiveKeys
                )
            } else {
                val leaseRequests =
                    requirements
                        .filter { it.mode == ResourceMode.EXCLUSIVE }
                        .map { it.key to resolveTtlSeconds(it, registry) }
                when (val acquire = leaseRepo.acquireAll(item.id, actorClaim?.id, leaseRequests)) {
                    is LeaseAcquireResult.Success -> {} // proceed
                    is LeaseAcquireResult.Contended ->
                        return ResourceGateOutcome.Rejected(
                            AdvanceFailure.ResourceLeaseUnavailable(
                                message =
                                    "Cannot enter work phase: resource(s) currently held by another work item: " +
                                        acquire.contendedKeys.joinToString(),
                                targetRole = targetRole,
                                contendedResources = acquire.contendedKeys,
                                retryAfterMs = acquire.retryAfterMs
                            )
                        )
                    is LeaseAcquireResult.DBError -> {
                        logger.warn(
                            "Resource lease acquire failed for item {} on keys {}: {}",
                            item.id,
                            exclusiveKeys,
                            acquire.cause.message
                        )
                        return ResourceGateOutcome.Rejected(
                            AdvanceFailure.ResourceLeaseUnavailable(
                                message =
                                    "Cannot enter work phase: transient contention in the resource lease store " +
                                        "for resource(s): " + exclusiveKeys.joinToString() + ". Retry shortly.",
                                targetRole = targetRole,
                                contendedResources = exclusiveKeys,
                                retryAfterMs = LEASE_DB_ERROR_RETRY_AFTER_MS
                            )
                        )
                    }
                }
            }
        }

        // (5) Derivation: derived keys first (exclusive, then advisory), caller extras appended.
        // Deduped, order-preserving. The MAX_ENTRIES cap in CredentialRefValidation constrains
        // CALLER input only — server-derived keys are trusted and are never truncated.
        return ResourceGateOutcome.Proceed((exclusiveKeys + advisoryKeys + credentialRefs).distinct())
    }

    /**
     * Resolves a lease TTL: the requirement's own override wins, then the registry entry's
     * `defaultTtlSeconds`, then [DEFAULT_RESOURCE_TTL_SECONDS]. The result is clamped to
     * [MIN_RESOURCE_TTL_SECONDS]..[MAX_RESOURCE_TTL_SECONDS] so a hostile or stale config value can
     * neither produce a non-positive TTL (which the lease store rejects) nor pin a resource for
     * longer than a day.
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

    /**
     * Releases every lease held by [itemId], logging and CONTINUING on failure.
     *
     * A release failure must never fail a transition that already persisted: the role change is
     * committed by this point, and the lease TTL is the backstop that frees the resource anyway.
     * [reason] is log context naming the release path (work exit vs. cascade).
     */
    private suspend fun releaseLeases(
        itemId: java.util.UUID,
        reason: String
    ) {
        val leaseRepo = resourceLeaseRepository ?: return
        when (val release = leaseRepo.releaseAllForItem(itemId)) {
            is LeaseReleaseResult.Success ->
                if (release.releasedCount > 0) {
                    logger.debug("Released {} resource lease(s) for item {} on {}", release.releasedCount, itemId, reason)
                }
            is LeaseReleaseResult.DBError ->
                logger.warn(
                    "Failed to release resource leases for item {} on {}: {}. The transition is NOT failed; " +
                        "the lease TTL remains the backstop.",
                    itemId,
                    reason,
                    release.cause.message
                )
        }
    }

    /**
     * Iterative detect-apply loop for terminal cascades up the ancestor chain.
     *
     * Mirrors the canonical pattern: detect from the current source with fresh DB state, apply the
     * first (immediate-parent) event in its own transaction, then re-detect from the cascaded
     * parent. A terminal cascade is gate-checked against the parent's required notes (all phases)
     * UNLESS the originating trigger was "cancel" (cancel cascades bypass the work-phase gate).
     */
    private suspend fun detectAndApplyTerminalCascades(
        source: WorkItem,
        trigger: String,
        out: MutableList<AdvanceCascadeEvent>
    ) {
        val isCancelCascade = trigger == "cancel"
        var cascadeSource: WorkItem = source
        var depth = 0
        while (depth < MAX_CASCADES) {
            val events = cascadeDetector.detectCascades(cascadeSource, workItemRepository, schemaResolver)
            if (events.isEmpty()) break

            // Only the immediate parent cascade (first event) is reliable; deeper events may read
            // stale DB state prior to this cascade's apply.
            val event = events.first()

            val parentItem =
                when (val parentResult = workItemRepository.getById(event.itemId)) {
                    is Result.Success -> parentResult.data
                    is Result.Error -> break
                }

            // Gate check: cascade-to-TERMINAL requires all required notes (like "complete").
            if (event.targetRole == Role.TERMINAL && !isCancelCascade) {
                val parentSchema = schemaResolver(parentItem)
                if (parentSchema != null) {
                    val parentNotes =
                        when (val nr = noteRepository.findByItemId(parentItem.id)) {
                            is Result.Success -> nr.data
                            is Result.Error -> emptyList()
                        }
                    val filledKeys = GatePredicate.filledNoteKeys(parentNotes)
                    val missingEntries = GatePredicate.missingForComplete(parentSchema, filledKeys)
                    if (missingEntries.isNotEmpty()) {
                        out.add(
                            AdvanceCascadeEvent(
                                itemId = event.itemId,
                                title = parentItem.title,
                                previousRole = event.currentRole,
                                targetRole = event.targetRole,
                                applied = false,
                                gateBlocked = true,
                                gateMissingNotes = missingEntries
                            )
                        )
                        break // Stop cascading up the tree.
                    }
                }
            }

            val cascadeApply =
                handler.cascadeTransition(
                    parentItem,
                    event.targetRole,
                    "Auto-cascaded from child completion",
                    workItemRepository,
                    roleTransitionRepository,
                    statusLabel = statusLabelService.resolveLabel("cascade")
                )

            out.add(
                AdvanceCascadeEvent(
                    itemId = event.itemId,
                    title = parentItem.title,
                    previousRole = event.currentRole,
                    targetRole = event.targetRole,
                    applied = cascadeApply.success,
                    statusLabel = cascadeApply.item?.statusLabel
                )
            )

            // A cascaded ancestor that was itself in WORK has now left WORK — release its leases on
            // the same log-and-continue policy as the primary work-exit path.
            if (cascadeApply.success && event.currentRole == Role.WORK && event.targetRole != Role.WORK) {
                releaseLeases(event.itemId, "terminal-cascade work-exit")
            }

            if (!cascadeApply.success || cascadeApply.item == null) break

            // Continue up the tree: re-detect from the newly-cascaded parent.
            cascadeSource = cascadeApply.item
            depth++
        }
        if (depth >= MAX_CASCADES) {
            logger.warn(
                "Cascade safety net hit: terminal cascade reached maxCascades={} levels starting " +
                    "from itemId={}. Remaining cascades (if any) are silently truncated. Investigate " +
                    "ancestor chain for unexpected length or cycles.",
                MAX_CASCADES,
                source.id
            )
        }
    }

    /**
     * Apply a list of pre-detected cascade events (start cascade, reopen cascade). Each apply runs
     * in its own transaction via [RoleTransitionHandler.cascadeTransition].
     *
     * Resource handling mirrors the primary transition, keyed on the cascade's target role. Both
     * cascade kinds applied here — start and reopen — target [Role.WORK] (a reopen cascade re-enters
     * WORK from a TERMINAL parent; it does not leave WORK), so only one path is ever reachable:
     * - **Into WORK** (start or reopen cascades): the parent's own EXCLUSIVE leases are acquired
     *   BEFORE the cascade is applied. On contention the cascade event is SUPPRESSED — recorded with
     *   `applied = false`, `resourceBlocked = true` and the contended keys, exactly mirroring the
     *   `gateBlocked` suppression shape used for terminal cascades. The child's own advance, which
     *   already succeeded, is unaffected: a parent that cannot take the resource simply stays queued.
     *
     * The release call below is defensive and currently unreachable — no event this method applies
     * ever has `targetRole != WORK` — but it is kept in case a future cascade kind routed through
     * this method does exit WORK; releasing an item with no leases is always a safe no-op.
     *
     * @param leaseGateActive false when either the caller passed `enforceResourceLeases = false` or
     *   the deployment kill switch is off; acquisition is skipped, releases still run.
     */
    private suspend fun applyCascadeEvents(
        events: List<CascadeEvent>,
        reason: String,
        out: MutableList<AdvanceCascadeEvent>,
        leaseGateActive: Boolean
    ) {
        for (event in events) {
            val parentItem =
                when (val parentResult = workItemRepository.getById(event.itemId)) {
                    is Result.Success -> parentResult.data
                    is Result.Error -> continue
                }

            // Resource gate for a cascade INTO work: suppress this cascade on contention.
            if (event.targetRole == Role.WORK && leaseGateActive) {
                val contended = acquireForCascadeIntoWork(parentItem)
                if (contended.isNotEmpty()) {
                    out.add(
                        AdvanceCascadeEvent(
                            itemId = event.itemId,
                            title = parentItem.title,
                            previousRole = event.currentRole,
                            targetRole = event.targetRole,
                            applied = false,
                            resourceBlocked = true,
                            contendedResources = contended
                        )
                    )
                    continue
                }
            }

            val cascadeApply =
                handler.cascadeTransition(
                    parentItem,
                    event.targetRole,
                    reason,
                    workItemRepository,
                    roleTransitionRepository,
                    statusLabel = statusLabelService.resolveLabel("cascade")
                )

            out.add(
                AdvanceCascadeEvent(
                    itemId = event.itemId,
                    title = parentItem.title,
                    previousRole = event.currentRole,
                    targetRole = event.targetRole,
                    applied = cascadeApply.success,
                    statusLabel = cascadeApply.item?.statusLabel
                )
            )

            // Defensive and currently unreachable: every event this method applies targets WORK (see
            // the KDoc above), so `event.targetRole != Role.WORK` is never true here. Kept as a
            // safety net for a future cascade kind that DOES exit WORK.
            if (cascadeApply.success && event.currentRole == Role.WORK && event.targetRole != Role.WORK) {
                releaseLeases(event.itemId, "cascade work-exit")
            }
        }
    }

    /**
     * Attempts to acquire [parentItem]'s EXCLUSIVE leases ahead of a cascade into [Role.WORK].
     *
     * @return the contended keys (empty when the acquire succeeded, when the parent declares no
     *   exclusive resources, or when no lease repository is wired) — a non-empty result means the
     *   caller must suppress the cascade.
     */
    private suspend fun acquireForCascadeIntoWork(parentItem: WorkItem): List<String> {
        val requirements = resourceRequirementsResolver(parentItem)
        if (requirements.isEmpty()) return emptyList()

        val exclusive = requirements.filter { it.mode == ResourceMode.EXCLUSIVE }
        if (exclusive.isEmpty()) return emptyList()

        val leaseRepo = resourceLeaseRepository ?: return emptyList()
        val registry = resourceRegistryResolver(parentItem.rootId)
        val leaseRequests = exclusive.map { it.key to resolveTtlSeconds(it, registry) }

        // Cascades have no actor by construction (see RoleTransitionHandler.cascadeTransition),
        // so the lease's audit actor is null here.
        return when (val acquire = leaseRepo.acquireAll(parentItem.id, null, leaseRequests)) {
            is LeaseAcquireResult.Success -> emptyList()
            is LeaseAcquireResult.Contended -> {
                logger.info(
                    "Start cascade into work suppressed for item {}: resource(s) {} held by another item",
                    parentItem.id,
                    acquire.contendedKeys
                )
                acquire.contendedKeys
            }
            is LeaseAcquireResult.DBError -> {
                logger.warn(
                    "Start cascade into work suppressed for item {}: lease store error on keys {}: {}",
                    parentItem.id,
                    exclusive.map { it.key },
                    acquire.cause.message
                )
                exclusive.map { it.key }
            }
        }
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
