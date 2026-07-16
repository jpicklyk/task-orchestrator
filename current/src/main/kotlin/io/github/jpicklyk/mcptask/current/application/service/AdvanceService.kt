package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
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
     * @property targetRole the role the transition would have moved to (for context)
     * @property missingNotes the structured required notes that are still unfilled
     */
    data class GateBlocked(
        val message: String,
        val targetRole: Role,
        val missingNotes: List<NoteSchemaEntry>
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
 */
data class AdvanceCascadeEvent(
    val itemId: java.util.UUID,
    val title: String,
    val previousRole: Role,
    val targetRole: Role,
    val applied: Boolean,
    val statusLabel: String? = null,
    val gateBlocked: Boolean = false,
    val gateMissingNotes: List<NoteSchemaEntry> = emptyList()
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
 */
class AdvanceService(
    private val workItemRepository: WorkItemRepository,
    private val roleTransitionRepository: RoleTransitionRepository,
    private val dependencyRepository: DependencyRepository,
    private val noteRepository: NoteRepository,
    private val statusLabelService: StatusLabelService,
    private val schemaResolver: suspend (WorkItem) -> WorkItemSchema?
) {
    private val handler = RoleTransitionHandler()
    private val cascadeDetector = CascadeDetector()

    companion object {
        private val logger = LoggerFactory.getLogger(AdvanceService::class.java)
        private const val MAX_CASCADES = 100
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
        enforceOwnership: Boolean
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
                roleChangedAt = dbNow
            )
        if (!applyResult.success || applyResult.item == null) {
            return AdvanceOutcome.Failure(
                AdvanceFailure.ApplyFailed(applyResult.error ?: "Failed to apply transition")
            )
        }
        val appliedItem = applyResult.item

        // 6. Cascade detection — each cascade apply is its OWN transaction boundary (inside
        //    applyTransition/cascadeTransition); detection and apply are intentionally split.
        val cascadeEvents = mutableListOf<AdvanceCascadeEvent>()
        when {
            targetRole == Role.TERMINAL -> detectAndApplyTerminalCascades(appliedItem, trigger, cascadeEvents)
            targetRole == Role.WORK -> {
                val startEvents = cascadeDetector.detectStartCascades(appliedItem, workItemRepository)
                applyCascadeEvents(startEvents, "Auto-cascaded from child start", cascadeEvents)
            }
        }
        if (trigger == "reopen" && targetRole == Role.QUEUE) {
            val reopenEvents = cascadeDetector.detectReopenCascades(appliedItem, workItemRepository, schemaResolver)
            applyCascadeEvents(reopenEvents, "Auto-cascaded from child reopen", cascadeEvents)
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
        return AdvanceFailure.GateBlocked(message, targetRole, missingEntries)
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
     */
    private suspend fun applyCascadeEvents(
        events: List<CascadeEvent>,
        reason: String,
        out: MutableList<AdvanceCascadeEvent>
    ) {
        for (event in events) {
            val parentItem =
                when (val parentResult = workItemRepository.getById(event.itemId)) {
                    is Result.Success -> parentResult.data
                    is Result.Error -> continue
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
