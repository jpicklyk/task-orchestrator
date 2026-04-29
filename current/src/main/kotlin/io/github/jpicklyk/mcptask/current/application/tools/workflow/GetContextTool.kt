package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.buildExpectedNotesJson
import io.github.jpicklyk.mcptask.current.application.service.computePhaseNoteContext
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.repository.Result
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
    /** Result entry from [findStalledItems]: an active item with missing required notes and optional guidance. */
    private data class StalledItemEntry(
        val item: io.github.jpicklyk.mcptask.current.domain.model.WorkItem,
        val missingKeys: List<String>,
        val guidancePointer: String?,
        val skillPointer: String?
    )

    override val name = "get_context"

    override val description =
        """
Read-only context snapshot. Three modes:

**Item mode** — `itemId` (UUID):
Returns the item's current role, note schema for its tags, existing notes with filled/exists status,
gate status (canAdvance + missing required notes for current phase), and `noteProgress`
(`{filled, remaining, total}` counts of required notes for the current role; null for terminal or schema-free items).
Full claim detail when item is claimed: `claimedBy`, `claimedAt`, `claimExpiresAt` (UTC), `isExpired` (boolean).
Use this mode to diagnose stalled/expired claims — this is the only mode that exposes claimedBy identity.

**Session resume** — `since` (ISO 8601 timestamp):
Returns active items (role=work or review), recent role transitions since the timestamp
(including actor/verification when present), and stalled items (active items with missing required notes).
No claim summary in this mode — use item mode or health-check mode for claim visibility.

**Health check** — no parameters:
Returns all active items (work/review), blocked items, stalled items, and
`claimSummary: { active: N, expired: N }` — lightweight fleet health signal (counts only, no identity).

Parameters:
- itemId (optional UUID): triggers item context mode
- since (optional ISO 8601 string): triggers session resume mode
- includeAncestors (optional boolean, default false): when true, each listed item includes an
  `ancestors` array ordered root-first (direct parent last). Root items (depth=0) get `"ancestors": []`.
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
                                JsonPrimitive("Maximum number of role transitions to return in session-resume mode. Default 50, max 200.")
                            )
                        }
                    )
                },
            required = emptyList()
        )

    override fun validateParams(params: JsonElement) {
        // Validate itemId if present — accepts full UUID or short hex prefix
        validateIdOrPrefix(params, "itemId", required = false)
        // Validate since if present
        parseInstant(params, "since")
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (itemId, idError) = resolveItemId(params, "itemId", context, required = false)
        if (idError != null) return idError
        val sinceInstant = parseInstant(params, "since")
        val includeAncestors = optionalBoolean(params, "includeAncestors", false)
        val transitionLimit =
            params.jsonObject["limit"]
                ?.jsonPrimitive
                ?.intOrNull
                ?.coerceIn(1, 200) ?: 50

        return when {
            itemId != null -> executeItemMode(itemId, context, includeAncestors)
            sinceInstant != null -> executeSessionResumeMode(sinceInstant, context, includeAncestors, transitionLimit)
            else -> executeHealthCheckMode(context, includeAncestors)
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
            when (itemResult) {
                is Result.Success -> itemResult.data
                is Result.Error -> return errorResponse(
                    "WorkItem not found: $itemId",
                    ErrorCodes.RESOURCE_NOT_FOUND
                )
            }

        val resolvedSchema = context.resolveSchema(item)

        val notesResult = context.noteRepository().findByItemId(item.id)
        val notes =
            when (notesResult) {
                is Result.Success -> notesResult.data
                is Result.Error -> emptyList()
            }
        val notesByKey = notes.associateBy { it.key }

        // Build schema list with exists/filled status
        val filledKeys = notes.filter { it.body.isNotBlank() }.map { it.key }.toSet()
        val schemaEntriesArray =
            buildExpectedNotesJson(
                schema = resolvedSchema?.notes,
                existingNoteKeys = notesByKey.keys,
                filledNoteKeys = filledKeys
            )

        // Gate status for current phase — uses shared computation
        val phaseContext = computePhaseNoteContext(item.role, resolvedSchema?.notes, notesByKey)
        val missingForPhase = phaseContext?.missingKeys ?: emptyList()
        val guidancePointer = phaseContext?.guidancePointer
        val skillPointer = phaseContext?.skillPointer

        // Resolve ancestors if requested
        val ancestorsJson: JsonArray =
            if (includeAncestors) {
                val chains =
                    when (val r = context.workItemRepository().findAncestorChains(setOf(item.id))) {
                        is Result.Success -> r.data
                        is Result.Error -> emptyMap()
                    }
                buildAncestorsArray(chains[item.id] ?: emptyList())
            } else {
                JsonArray(emptyList())
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
                        // Terminal items can never advance; schema-free items always can; schema items need all notes filled
                        val isTerminal = item.role == Role.TERMINAL
                        put("canAdvance", JsonPrimitive(!isTerminal && missingForPhase.isEmpty()))
                        put("phase", JsonPrimitive(item.role.toJsonString()))
                        put("missing", JsonArray(missingForPhase.map { JsonPrimitive(it) }))
                    }
                )
                if (guidancePointer != null) {
                    put("guidancePointer", JsonPrimitive(guidancePointer))
                } else {
                    put("guidancePointer", JsonNull)
                }
                skillPointer?.let { put("skillPointer", JsonPrimitive(it)) }
                if (phaseContext != null) {
                    put(
                        "noteProgress",
                        buildJsonObject {
                            put("filled", JsonPrimitive(phaseContext.filled))
                            put("remaining", JsonPrimitive(phaseContext.remaining))
                            put("total", JsonPrimitive(phaseContext.total))
                        }
                    )
                }
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
                            // Use DB-side time so isExpired reflects the DB clock, not the JVM clock.
                            val dbNowInstant = context.workItemRepository().dbNow()
                            val isExpired = item.claimExpiresAt != null && !item.claimExpiresAt.isAfter(dbNowInstant)
                            put("isExpired", JsonPrimitive(isExpired))
                        }
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
        transitionLimit: Int = 50
    ): JsonElement {
        val workItemRepo = context.workItemRepository()

        // Fetch work and review items in parallel, merge results
        val (workItems, reviewItems) =
            coroutineScope {
                val workDeferred = async { workItemRepo.findByRole(Role.WORK, limit = 200) }
                val reviewDeferred = async { workItemRepo.findByRole(Role.REVIEW, limit = 200) }

                Pair(
                    workDeferred.await().getOrElse(emptyList()),
                    reviewDeferred.await().getOrElse(emptyList())
                )
            }
        val activeItems = workItems + reviewItems

        // Recent transitions since the given timestamp
        val recentTransitions =
            context
                .roleTransitionRepository()
                .findSince(since, limit = transitionLimit)
                .getOrElse(emptyList())

        // Stalled items: active items with missing required notes
        val stalledItems = findStalledItems(activeItems, context)

        // Resolve ancestor chains once for all items if requested
        val ancestorChains: Map<java.util.UUID, List<io.github.jpicklyk.mcptask.current.domain.model.WorkItem>> =
            if (includeAncestors) {
                val allIds = (activeItems.map { it.id } + stalledItems.map { it.item.id }).toSet()
                if (allIds.isNotEmpty()) {
                    when (val r = workItemRepo.findAncestorChains(allIds)) {
                        is Result.Success -> r.data
                        is Result.Error -> emptyMap()
                    }
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
                            buildJsonObject {
                                put("itemId", JsonPrimitive(t.itemId.toString()))
                                put("fromRole", JsonPrimitive(t.fromRole))
                                put("toRole", JsonPrimitive(t.toRole))
                                put("trigger", JsonPrimitive(t.trigger))
                                put("at", JsonPrimitive(t.transitionedAt.toString()))
                                t.actorClaim?.let { put("actor", it.toJson()) }
                                t.verification?.let { put("verification", it.toJson()) }
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
                                // Bug 3 fix: include guidancePointer so callers don't need additional item-mode calls
                                if (entry.guidancePointer != null) {
                                    put("guidancePointer", JsonPrimitive(entry.guidancePointer))
                                } else {
                                    put("guidancePointer", JsonNull)
                                }
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
        includeAncestors: Boolean
    ): JsonElement {
        val workItemRepo = context.workItemRepository()

        // Fetch work, review, and blocked items in parallel; also compute claim summary
        val workItems: List<io.github.jpicklyk.mcptask.current.domain.model.WorkItem>
        val reviewItems: List<io.github.jpicklyk.mcptask.current.domain.model.WorkItem>
        val blockedItems: List<io.github.jpicklyk.mcptask.current.domain.model.WorkItem>
        val claimCounts: io.github.jpicklyk.mcptask.current.domain.repository.ClaimStatusCounts?
        coroutineScope {
            val workDeferred = async { workItemRepo.findByRole(Role.WORK, limit = 200) }
            val reviewDeferred = async { workItemRepo.findByRole(Role.REVIEW, limit = 200) }
            val blockedDeferred = async { workItemRepo.findByRole(Role.BLOCKED, limit = 200) }
            val claimDeferred = async { workItemRepo.countByClaimStatus(parentId = null) }

            workItems = workDeferred.await().getOrElse(emptyList())
            reviewItems = reviewDeferred.await().getOrElse(emptyList())
            blockedItems = blockedDeferred.await().getOrElse(emptyList())
            claimCounts = (claimDeferred.await() as? Result.Success)?.data
        }

        val activeItems = workItems + reviewItems
        val stalledItems = findStalledItems(activeItems, context)

        // Resolve ancestor chains once for all items if requested
        val ancestorChains: Map<java.util.UUID, List<io.github.jpicklyk.mcptask.current.domain.model.WorkItem>> =
            if (includeAncestors) {
                val allIds = (activeItems.map { it.id } + blockedItems.map { it.id } + stalledItems.map { it.item.id }).toSet()
                if (allIds.isNotEmpty()) {
                    when (val r = workItemRepo.findAncestorChains(allIds)) {
                        is Result.Success -> r.data
                        is Result.Error -> emptyMap()
                    }
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
                                // Bug 3 fix: include guidancePointer so callers don't need additional item-mode calls
                                if (entry.guidancePointer != null) {
                                    put("guidancePointer", JsonPrimitive(entry.guidancePointer))
                                } else {
                                    put("guidancePointer", JsonNull)
                                }
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
     * Bug 3 fix: also computes guidancePointer per stalled item so health-check/session-resume
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
            when (val r = noteRepo.findByItemIds(itemIds)) {
                is Result.Success -> r.data
                is Result.Error -> return emptyList()
            }

        // Check each item against its schema using shared computation
        for ((item, schema) in schemaItems) {
            val notesByKey = (notesByItemId[item.id] ?: emptyList()).associateBy { it.key }
            val phaseContext = computePhaseNoteContext(item.role, schema, notesByKey)

            if (phaseContext != null && phaseContext.missingKeys.isNotEmpty()) {
                result.add(StalledItemEntry(item, phaseContext.missingKeys, phaseContext.guidancePointer, phaseContext.skillPointer))
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
