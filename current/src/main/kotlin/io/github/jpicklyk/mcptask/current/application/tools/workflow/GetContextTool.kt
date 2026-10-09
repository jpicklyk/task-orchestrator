package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.config.withConfigSession
import io.github.jpicklyk.mcptask.current.application.port.unitNow
import io.github.jpicklyk.mcptask.current.application.service.GatePredicate
import io.github.jpicklyk.mcptask.current.application.service.blockedByWire
import io.github.jpicklyk.mcptask.current.application.service.buildDispatchBySeatFlatJson
import io.github.jpicklyk.mcptask.current.application.service.buildDispatchProfileJson
import io.github.jpicklyk.mcptask.current.application.service.buildExpectedNotesJson
import io.github.jpicklyk.mcptask.current.application.service.buildMissingBySeatJson
import io.github.jpicklyk.mcptask.current.application.service.buildSeatsJson
import io.github.jpicklyk.mcptask.current.application.service.computeMissingBySeat
import io.github.jpicklyk.mcptask.current.application.service.computePhaseNoteContext
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.legacyReadOrNull
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Decision
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.ClaimState
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import java.time.Instant

/**
 * Read-only MCP tool that provides rich context in three modes:
 *
 * - **Item mode** (`itemId` provided): Returns schema, existing notes, and gate status for a specific WorkItem.
 * - **Session resume** (`since` provided): Returns active items, recent role transitions, and stalled items since a timestamp.
 * - **Health check** (no params): Returns all active/blocked/stalled items for a dashboard-style overview.
 */
class GetContextTool : BaseToolDefinition() {
    /** Result entry from [findStalledItems]: an active item with missing required notes and optional guidance key. */
    private data class StalledItemEntry(
        val item: io.github.jpicklyk.mcptask.current.domain.model.WorkItem,
        val missingKeys: List<String>,
        val guidanceKey: String?,
        val skillPointer: String?
    )

    override val name = "get_context"

    override val description =
        """
Read-only context snapshot. Three modes:

**Item mode** — pass `mode: "item"` (or provide `itemId`): item role, note schema status, and the
canonical gate status (`canAdvance` + missing required notes). Includes full claim detail
(`claimedBy`, `claimedAt`, `claimExpiresAt`, `isExpired`) when claimed — the only mode exposing
claimedBy identity; use it to diagnose stalled/expired claims.

**Session resume** — pass `mode: "session-resume"` (or provide `since`): active items (role=work or
review), recent role transitions since the timestamp, and stalled items (active items with missing
required notes). No claim summary in this mode — use item mode or health-check for claim visibility.

**Health check** — pass `mode: "health-check"` (or omit all mode-selecting params): all active items,
blocked items, stalled items, and a claim-count summary (no identity).

When `mode` is omitted, it is inferred from which parameters are provided (`itemId` → item, `since` →
session-resume, neither → health-check); explicit `mode` takes precedence.

Call with no arguments to resume a session; call with `itemId` before any advance or dispatch decision.
        """.trimIndent()

    override val category = ToolCategory.WORKFLOW

    override val toolAnnotations =
        ToolAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )

    override val parameterSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put(
                        "mode",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Explicit mode: item, session-resume, or health-check. See tool description for " +
                                        "the inference rule used when omitted."
                                )
                            )
                            put(
                                "enum",
                                buildJsonArray {
                                    add(JsonPrimitive("item"))
                                    add(JsonPrimitive("session-resume"))
                                    add(JsonPrimitive("health-check"))
                                }
                            )
                        }
                    )
                    put(
                        "itemId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("UUID or hex prefix (4+ chars) of a WorkItem for item context mode"))
                        }
                    )
                    put(
                        "since",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 timestamp for session resume mode (e.g. 2024-01-01T00:00:00Z)"))
                        }
                    )
                    put(
                        "ancestorId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "UUID or hex prefix (4+ chars) of an item whose subtree (any depth, inclusive) " +
                                        "scopes health-check and session-resume results (active/blocked/stalled items " +
                                        "and the claim summary). Ignored in item mode. Omitted = unscoped. In " +
                                        "session-resume mode, `recentTransitions` is NOT scoped by this parameter " +
                                        "even when set — it always reflects transitions across the whole tree."
                                )
                            )
                        }
                    )
                    put(
                        "includeAncestors",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive("When true, each listed item includes an ancestors array ordered root-first (default: false)")
                            )
                        }
                    )
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put(
                                "description",
                                JsonPrimitive("Maximum number of role transitions to return in session-resume mode. Default 10, max 200.")
                            )
                        }
                    )
                },
            required = emptyList()
        )

    override fun validateParams(params: JsonElement) {
        val mode = optionalString(params, "mode")
        if (mode != null && mode !in listOf("item", "session-resume", "health-check")) {
            throw ToolValidationException("Invalid mode: $mode. Must be one of: item, session-resume, health-check")
        }
        // Validate itemId if present — accepts full UUID or short hex prefix
        validateIdOrPrefix(params, "itemId", required = false)
        validateIdOrPrefix(params, "ancestorId", required = false)
        // Validate since if present
        parseInstant(params, "since")
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement =
        withConfigSession {
            val (itemId, idError) = resolveItemId(params, "itemId", context, required = false)
            if (idError != null) return@withConfigSession idError
            val (ancestorId, ancestorIdError) = resolveItemId(params, "ancestorId", context, required = false)
            if (ancestorIdError != null) return@withConfigSession ancestorIdError
            val sinceInstant = parseInstant(params, "since")
            val includeAncestors = optionalBoolean(params, "includeAncestors", false)
            val transitionLimit =
                params.jsonObject["limit"]
                    ?.jsonPrimitive
                    ?.intOrNull
                    ?.coerceIn(1, 200) ?: 10

            val explicitMode = optionalString(params, "mode")

            when {
                explicitMode == "item" || (explicitMode == null && itemId != null) -> {
                    if (itemId == null) {
                        errorResponse("mode=item requires itemId parameter", ErrorCodes.VALIDATION_ERROR)
                    } else {
                        executeItemMode(itemId, context, includeAncestors)
                    }
                }
                explicitMode == "session-resume" || (explicitMode == null && sinceInstant != null) -> {
                    if (sinceInstant == null) {
                        errorResponse("mode=session-resume requires since parameter", ErrorCodes.VALIDATION_ERROR)
                    } else {
                        executeSessionResumeMode(sinceInstant, context, includeAncestors, transitionLimit, ancestorId)
                    }
                }
                else -> executeHealthCheckMode(context, includeAncestors, ancestorId)
            }
        }

    // ──────────────────────────────────────────────
    // Mode 1: Item context
    // ──────────────────────────────────────────────

    private suspend fun executeItemMode(
        itemId: java.util.UUID,
        context: ToolExecutionContext,
        includeAncestors: Boolean
    ): JsonElement {
        val itemResult = context.workItemRepository().getById(itemId)
        val item =
            itemResult ?: return errorResponse(
                "WorkItem not found: $itemId",
                ErrorCodes.RESOURCE_NOT_FOUND
            )

        val resolvedSchema = context.resolveSchema(item)

        // Dispatch routing profile for the item's CURRENT role, using the already-resolved schema
        // above (never re-resolves it) — see ToolExecutionContext.resolveDispatchProfile's KDoc.
        val dispatchProfile = context.resolveDispatchProfile(item, item.role, resolvedSchema)

        val notes =
            legacyReadOrNull { context.noteRepository().findByItemId(item.id) } ?: emptyList()
        val notesByKey = notes.associateBy { it.key }

        // Build schema list with exists/filled status
        val filledKeys = notes.filter { it.body.isNotBlank() }.map { it.key }.toSet()
        val seatAware = resolvedSchema?.isSeatAware() == true
        val schemaEntriesArray =
            buildExpectedNotesJson(
                schema = resolvedSchema?.notes,
                existingNoteKeys = notesByKey.keys,
                filledNoteKeys = filledKeys,
                seatAware = seatAware
            )

        // Gate status for current phase — uses shared computation
        val phaseContext = computePhaseNoteContext(item.role, resolvedSchema?.notes, notesByKey)
        val missingForPhase = phaseContext?.missingKeys ?: emptyList()
        val guidanceKey = phaseContext?.guidanceKey
        val skillPointer = phaseContext?.skillPointer
        // A1: missingBySeat is served only for a seat-aware schema AND only while the item is not
        // TERMINAL (task-scope §6) — a TERMINAL, seat-aware item would otherwise still get an empty
        // `{}` from computeMissingBySeat (missingForPhase is empty for TERMINAL regardless of
        // seat-awareness), so TERMINAL is excluded explicitly rather than relying on an empty list.
        val missingBySeat = if (item.role != Role.TERMINAL) computeMissingBySeat(resolvedSchema, missingForPhase) else null

        // A2: independence-attestation violations for the item's CURRENT phase — null when the
        // item is TERMINAL (mirrors missingBySeat), independence mode is OFF, or the resolved
        // schema declares no independent_of in any phase.
        val independencePolicy = context.resolveIndependencePolicy(item.rootId)
        val violations =
            if (item.role != Role.TERMINAL && resolvedSchema != null) {
                GatePredicate.violationsForStart(resolvedSchema, item.role, notes, independencePolicy)
            } else {
                null
            }
        // canAdvance is the advance's own policy evaluation of `start` (ownership excluded): table,
        // dependency, note/independence and lease gates, so it never disagrees with advance_item.
        val startDecision = context.transitionPreview().evaluate(item, Trigger.User.START)
        val canAdvance = startDecision is Decision.Allow

        // A1: current-phase seats + per-seat dispatch overrides (task-scope §6 "get_context item
        // mode"). Both omitted (never an empty array/object) when the resolved schema declares no
        // seats for the item's CURRENT role — byte-identical to pre-A1 for every seat-less schema.
        val currentPhaseSeats = resolvedSchema?.seatsForRole(item.role) ?: emptyList()
        val dispatchBySeatForPhase =
            if (currentPhaseSeats.isEmpty()) {
                emptyMap()
            } else {
                context.configResolver.resolveDispatchBySeat(item, resolvedSchema)[item.role] ?: emptyMap()
            }

        // Resolve ancestors if requested
        val ancestorsJson: JsonArray =
            if (includeAncestors) {
                val chains =
                    (legacyReadOrNull { context.workItemRepository().findAncestorChains(setOf(item.id)) } ?: emptyMap())
                buildAncestorsArray(chains[item.id] ?: emptyList())
            } else {
                JsonArray(emptyList())
            }

        // Resource lease disclosure inputs — declared resources (from traits) plus any leases this
        // item actively holds. Items that declare no resources skip the lease repository entirely
        // (the overwhelmingly common path pays zero lease queries); a lease held by an item whose
        // declarations were since removed from config is not surfaced here — TTL reclaims it.
        val declaredResources = context.resolveResourceRequirements(item)
        val declaredKeys = declaredResources.map { it.key }
        val heldLeaseByKey =
            if (declaredKeys.isNotEmpty()) {
                context.repositoryProvider
                    .resourceLeaseRepository()
                    .findActiveForItem(item.id)
                    .associateBy { it.resourceKey }
            } else {
                emptyMap()
            }
        val anyHolderByKey =
            if (declaredKeys.isNotEmpty()) {
                context.repositoryProvider
                    .resourceLeaseRepository()
                    .findActiveByKeys(declaredKeys)
                    .associateBy { it.resourceKey }
            } else {
                emptyMap()
            }

        val data =
            buildJsonObject {
                put("mode", JsonPrimitive("item"))
                put(
                    "item",
                    buildJsonObject {
                        put("id", JsonPrimitive(item.id.toString()))
                        put("title", JsonPrimitive(item.title))
                        put("role", JsonPrimitive(item.role.toJsonString()))
                        item.tags?.let { put("tags", JsonPrimitive(it)) }
                        put("depth", JsonPrimitive(item.depth))
                        if (includeAncestors) put("ancestors", ancestorsJson)
                    }
                )
                put("schema", schemaEntriesArray)
                put(
                    "gateStatus",
                    buildJsonObject {
                        val isTerminal = item.role == Role.TERMINAL
                        put("canAdvance", JsonPrimitive(canAdvance))
                        put("phase", JsonPrimitive(item.role.toJsonString()))
                        put("missing", JsonArray(missingForPhase.map { JsonPrimitive(it) }))
                        buildMissingBySeatJson(missingBySeat)?.let { put("missingBySeat", it) }
                        NoteSchemaJsonHelpers.buildViolationsArray(violations)?.let { put("violations", it) }
                        if (!canAdvance && !isTerminal) startDecision.blockedByWire()?.let { put("blockedBy", JsonPrimitive(it)) }
                    }
                )
                guidanceKey?.let { put("guidanceKey", JsonPrimitive(it)) }
                skillPointer?.let { put("skillPointer", JsonPrimitive(it)) }
                dispatchProfile?.let { put("dispatch", buildDispatchProfileJson(it)) }
                buildSeatsJson(currentPhaseSeats)?.let { put("seats", it) }
                buildDispatchBySeatFlatJson(dispatchBySeatForPhase)?.let { put("dispatchBySeat", it) }
                // Full claim detail — diagnostic tool, single-item, operators need identity to debug stalled work.
                // claimedBy is intentionally included here; it must NOT appear in query_items results.
                if (item.claimedBy != null) {
                    put(
                        "claimDetail",
                        buildJsonObject {
                            put("claimedBy", JsonPrimitive(item.claimedBy))
                            item.claimedAt?.let { put("claimedAt", JsonPrimitive(it.toString())) }
                            item.claimExpiresAt?.let { put("claimExpiresAt", JsonPrimitive(it.toString())) }
                            item.originalClaimedAt?.let { put("originalClaimedAt", JsonPrimitive(it.toString())) }
                            // Same predicate every claim decision uses: expired at or after the expiry instant.
                            val isExpired = !ClaimState.isActive(item, context.clock.unitNow())
                            put("isExpired", JsonPrimitive(isExpired))
                        }
                    )
                }
                // Resource lease disclosure — item mode is the sanctioned holder-identity diagnostic
                // for resource leases too, mirroring claimDetail above. Omitted entirely when the
                // item neither declares nor holds any resource (zero payload for the common case).
                if (declaredResources.isNotEmpty() || heldLeaseByKey.isNotEmpty()) {
                    val requirementByKey = declaredResources.associateBy { it.key }
                    val allKeys = (declaredKeys + heldLeaseByKey.keys).distinct()
                    put(
                        "resourceLeases",
                        JsonArray(
                            allKeys.map { key ->
                                val requirement = requirementByKey[key]
                                val ownLease = heldLeaseByKey[key]
                                // For declared keys, holder identity comes from the all-holders lookup
                                // above; for a held-but-no-longer-declared key (trait removed while the
                                // lease is still active), fall back to the item's own lease record.
                                val anyLease = anyHolderByKey[key] ?: ownLease
                                buildJsonObject {
                                    put("key", JsonPrimitive(key))
                                    put("mode", JsonPrimitive((requirement?.mode ?: ResourceMode.ADVISORY).name.lowercase()))
                                    put("held", JsonPrimitive(ownLease != null))
                                    anyLease?.let { lease ->
                                        put("holderItemId", JsonPrimitive(lease.holderItemId.toString()))
                                        lease.acquiredByActorId?.let { actorId -> put("acquiredByActorId", JsonPrimitive(actorId)) }
                                        put("expiresAt", JsonPrimitive(lease.expiresAt.toString()))
                                    }
                                }
                            }
                        )
                    )
                }
            }

        return successResponse(data)
    }

    // ──────────────────────────────────────────────
    // Mode 2: Session resume
    // ──────────────────────────────────────────────

    private suspend fun executeSessionResumeMode(
        since: java.time.Instant,
        context: ToolExecutionContext,
        includeAncestors: Boolean,
        transitionLimit: Int = 10,
        ancestorId: java.util.UUID? = null
    ): JsonElement {
        val workItemRepo = context.workItemRepository()
        val scopeIds = ancestorId?.let { setOf(it) }

        // Fetch work and review items in parallel, merge results
        val (workItems, reviewItems) =
            coroutineScope {
                val workDeferred =
                    async {
                        legacyReadOrNull { workItemRepo.findByRole(Role.WORK, limit = 200, rootIds = scopeIds) }
                            ?: emptyList()
                    }
                val reviewDeferred =
                    async {
                        legacyReadOrNull { workItemRepo.findByRole(Role.REVIEW, limit = 200, rootIds = scopeIds) }
                            ?: emptyList()
                    }

                Pair(
                    workDeferred.await(),
                    reviewDeferred.await()
                )
            }
        val activeItems = workItems + reviewItems

        // Recent transitions since the given timestamp — NOT scoped by ancestorId (see field
        // description on the `ancestorId` parameterSchema): scoping transitions would require
        // plumbing the resolved scope set through the transition-item lookup, which the task
        // scope note flagged as invasive; documented as a known limitation instead.
        val recentTransitions =
            legacyReadOrNull {
                context
                    .roleTransitionRepository()
                    .findSince(since, limit = transitionLimit)
            } ?: emptyList()

        // Resolve titles for the transition items (may include items no longer active, e.g. terminal).
        val transitionTitles: Map<java.util.UUID, String> =
            recentTransitions.map { it.itemId }.toSet().let { ids ->
                if (ids.isEmpty()) {
                    emptyMap()
                } else {
                    run {
                        val r = legacyRead({ return@run emptyMap<java.util.UUID, String>() }) { workItemRepo.findByIds(ids) }
                        r.associate { it.id to it.title }
                    }
                }
            }

        // Stalled items: active items with missing required notes
        val stalledItems = findStalledItems(activeItems, context)

        // Resolve ancestor chains once for all items if requested
        val ancestorChains: Map<java.util.UUID, List<io.github.jpicklyk.mcptask.current.domain.model.WorkItem>> =
            if (includeAncestors) {
                val allIds = (activeItems.map { it.id } + stalledItems.map { it.item.id }).toSet()
                if (allIds.isNotEmpty()) {
                    (legacyReadOrNull { workItemRepo.findAncestorChains(allIds) } ?: emptyMap())
                } else {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

        val data =
            buildJsonObject {
                put("mode", JsonPrimitive("session-resume"))
                put("since", JsonPrimitive(since.toString()))
                put(
                    "activeItems",
                    JsonArray(
                        activeItems.map { item ->
                            buildJsonObject {
                                put("id", JsonPrimitive(item.id.toString()))
                                put("title", JsonPrimitive(item.title))
                                put("role", JsonPrimitive(item.role.toJsonString()))
                                item.tags?.let { put("tags", JsonPrimitive(it)) }
                                if (includeAncestors) put("ancestors", buildAncestorsArray(ancestorChains[item.id] ?: emptyList()))
                            }
                        }
                    )
                )
                put(
                    "recentTransitions",
                    JsonArray(
                        recentTransitions.map { t ->
                            // Lean transition entries: {itemId, title, fromRole, toRole, at, actorId?}.
                            // Full actor/verification detail is available via query-side tools.
                            buildJsonObject {
                                put("itemId", JsonPrimitive(t.itemId.toString()))
                                transitionTitles[t.itemId]?.let { put("title", JsonPrimitive(it)) }
                                put("fromRole", JsonPrimitive(t.fromRole))
                                put("toRole", JsonPrimitive(t.toRole))
                                put("at", JsonPrimitive(t.transitionedAt.toString()))
                                t.actorClaim?.let { put("actorId", JsonPrimitive(it.id)) }
                            }
                        }
                    )
                )
                put(
                    "stalledItems",
                    JsonArray(
                        stalledItems.map { entry ->
                            buildJsonObject {
                                put("id", JsonPrimitive(entry.item.id.toString()))
                                put("title", JsonPrimitive(entry.item.title))
                                put("role", JsonPrimitive(entry.item.role.toJsonString()))
                                put("missingNotes", JsonArray(entry.missingKeys.map { JsonPrimitive(it) }))
                                // Reference-based: guidanceKey names the first missing note with guidance;
                                // resolve to full text via query_items operation "schema". Omitted when null.
                                entry.guidanceKey?.let { put("guidanceKey", JsonPrimitive(it)) }
                                entry.skillPointer?.let { put("skillPointer", JsonPrimitive(it)) }
                                if (includeAncestors) put("ancestors", buildAncestorsArray(ancestorChains[entry.item.id] ?: emptyList()))
                            }
                        }
                    )
                )
            }

        return successResponse(data)
    }

    // ──────────────────────────────────────────────
    // Mode 3: Health check
    // ──────────────────────────────────────────────

    private suspend fun executeHealthCheckMode(
        context: ToolExecutionContext,
        includeAncestors: Boolean,
        ancestorId: java.util.UUID? = null
    ): JsonElement {
        val workItemRepo = context.workItemRepository()
        val scopeIds = ancestorId?.let { setOf(it) }

        // Fetch work, review, and blocked items in parallel; also compute claim summary
        val workItems: List<io.github.jpicklyk.mcptask.current.domain.model.WorkItem>
        val reviewItems: List<io.github.jpicklyk.mcptask.current.domain.model.WorkItem>
        val blockedItems: List<io.github.jpicklyk.mcptask.current.domain.model.WorkItem>
        val claimCounts: io.github.jpicklyk.mcptask.current.application.port.ClaimStatusCounts?
        coroutineScope {
            val workDeferred =
                async {
                    legacyReadOrNull { workItemRepo.findByRole(Role.WORK, limit = 200, rootIds = scopeIds) }
                        ?: emptyList()
                }
            val reviewDeferred =
                async {
                    legacyReadOrNull { workItemRepo.findByRole(Role.REVIEW, limit = 200, rootIds = scopeIds) }
                        ?: emptyList()
                }
            val blockedDeferred =
                async {
                    legacyReadOrNull { workItemRepo.findByRole(Role.BLOCKED, limit = 200, rootIds = scopeIds) }
                        ?: emptyList()
                }
            val claimDeferred = async { legacyReadOrNull { workItemRepo.countByClaimStatus(parentId = null, rootIds = scopeIds) } }

            workItems = workDeferred.await()
            reviewItems = reviewDeferred.await()
            blockedItems = blockedDeferred.await()
            claimCounts = claimDeferred.await()
        }

        val activeItems = workItems + reviewItems
        val stalledItems = findStalledItems(activeItems, context)

        // Resolve ancestor chains once for all items if requested
        val ancestorChains: Map<java.util.UUID, List<io.github.jpicklyk.mcptask.current.domain.model.WorkItem>> =
            if (includeAncestors) {
                val allIds = (activeItems.map { it.id } + blockedItems.map { it.id } + stalledItems.map { it.item.id }).toSet()
                if (allIds.isNotEmpty()) {
                    (legacyReadOrNull { workItemRepo.findAncestorChains(allIds) } ?: emptyMap())
                } else {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

        val data =
            buildJsonObject {
                put("mode", JsonPrimitive("health-check"))
                put(
                    "activeItems",
                    JsonArray(
                        activeItems.map { item ->
                            buildJsonObject {
                                put("id", JsonPrimitive(item.id.toString()))
                                put("title", JsonPrimitive(item.title))
                                put("role", JsonPrimitive(item.role.toJsonString()))
                                item.tags?.let { put("tags", JsonPrimitive(it)) }
                                if (includeAncestors) put("ancestors", buildAncestorsArray(ancestorChains[item.id] ?: emptyList()))
                            }
                        }
                    )
                )
                put(
                    "blockedItems",
                    JsonArray(
                        blockedItems.map { item ->
                            buildJsonObject {
                                put("id", JsonPrimitive(item.id.toString()))
                                put("title", JsonPrimitive(item.title))
                                put("role", JsonPrimitive(item.role.toJsonString()))
                                if (includeAncestors) put("ancestors", buildAncestorsArray(ancestorChains[item.id] ?: emptyList()))
                            }
                        }
                    )
                )
                put(
                    "stalledItems",
                    JsonArray(
                        stalledItems.map { entry ->
                            buildJsonObject {
                                put("id", JsonPrimitive(entry.item.id.toString()))
                                put("title", JsonPrimitive(entry.item.title))
                                put("role", JsonPrimitive(entry.item.role.toJsonString()))
                                put("missingNotes", JsonArray(entry.missingKeys.map { JsonPrimitive(it) }))
                                // Reference-based: guidanceKey names the first missing note with guidance;
                                // resolve to full text via query_items operation "schema". Omitted when null.
                                entry.guidanceKey?.let { put("guidanceKey", JsonPrimitive(it)) }
                                entry.skillPointer?.let { put("skillPointer", JsonPrimitive(it)) }
                                if (includeAncestors) put("ancestors", buildAncestorsArray(ancestorChains[entry.item.id] ?: emptyList()))
                            }
                        }
                    )
                )
                // Claim summary: lightweight fleet health signal (counts only — no identity exposed).
                // active = live claims; expired = claims past TTL; omit unclaimed (too noisy for health-check).
                if (claimCounts != null) {
                    put(
                        "claimSummary",
                        buildJsonObject {
                            put("active", JsonPrimitive(claimCounts.active))
                            put("expired", JsonPrimitive(claimCounts.expired))
                        }
                    )
                }
            }

        return successResponse(data)
    }

    // ──────────────────────────────────────────────
    // Shared helpers
    // ──────────────────────────────────────────────

    /**
     * For each active item, determine which required notes for its current phase are missing.
     * Returns only items that have at least one missing required note.
     * Also computes guidanceKey per stalled item so health-check/session-resume
     * modes can include it without additional round-trips.
     */
    private suspend fun findStalledItems(
        items: List<io.github.jpicklyk.mcptask.current.domain.model.WorkItem>,
        context: ToolExecutionContext
    ): List<StalledItemEntry> {
        val noteRepo = context.noteRepository()
        val result = mutableListOf<StalledItemEntry>()

        // Pre-filter to items that have a matching schema (avoids batch-fetching notes for schema-less items)
        val schemaItems =
            items.mapNotNull { item ->
                val schema = context.resolveSchema(item)
                if (schema != null) Pair(item, schema) else null
            }
        if (schemaItems.isEmpty()) return emptyList()

        // Batch-fetch all notes for schema-eligible items (N+1 → 1 query)
        val itemIds = schemaItems.map { it.first.id }.toSet()
        val notesByItemId =
            legacyReadOrNull { noteRepo.findByItemIds(itemIds) } ?: return emptyList()

        // Check each item against its schema using shared computation
        for ((item, schema) in schemaItems) {
            val notesByKey = (notesByItemId[item.id] ?: emptyList()).associateBy { it.key }
            val phaseContext = computePhaseNoteContext(item.role, schema, notesByKey)

            if (phaseContext != null && phaseContext.missingKeys.isNotEmpty()) {
                result.add(StalledItemEntry(item, phaseContext.missingKeys, phaseContext.guidanceKey, phaseContext.skillPointer))
            }
        }

        return result
    }

    override fun userSummary(
        params: JsonElement,
        result: JsonElement,
        isError: Boolean
    ): String {
        if (isError) return "get_context failed"
        val data = (result as? JsonObject)?.get("data") as? JsonObject
        val mode = data?.get("mode")?.let { (it as? JsonPrimitive)?.content } ?: "unknown"
        return when (mode) {
            "item" -> {
                val canAdvance =
                    data
                        ?.get("gateStatus")
                        ?.jsonObject
                        ?.get("canAdvance")
                        ?.jsonPrimitive
                        ?.boolean ?: false
                if (canAdvance) "Item context: ready to advance" else "Item context: gate blocked"
            }
            "session-resume" -> {
                val active = data?.get("activeItems")?.jsonArray?.size ?: 0
                "Session resume: $active active item(s)"
            }
            "health-check" -> {
                val active = data?.get("activeItems")?.jsonArray?.size ?: 0
                val blocked = data?.get("blockedItems")?.jsonArray?.size ?: 0
                "Health check: $active active, $blocked blocked"
            }
            else -> "Context retrieved"
        }
    }
}
