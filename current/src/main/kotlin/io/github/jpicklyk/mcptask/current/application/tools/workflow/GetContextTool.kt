package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.computePhaseNoteContext
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

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
        val guidancePointer: String?
    )

    override val name = "get_context"

    override val description =
        """
Read-only context snapshot. Three modes:

**Item mode** — `itemId` (UUID):
Returns the item's current role, note schema for its tags, existing notes with filled/exists status,
gate status (canAdvance + missing required notes for current phase), and `noteProgress`
(`{filled, remaining, total}` counts of required notes for the current role; null for terminal or schema-free items).

**Session resume** — `since` (ISO 8601 timestamp):
Returns active items (role=work or review), recent role transitions since the timestamp,
and stalled items (active items with missing required notes).

**Health check** — no parameters:
Returns all active items (work/review), blocked items, and stalled items.

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
                            put("description", JsonPrimitive("UUID of a WorkItem for item context mode"))
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
        // Validate itemId if present
        extractUUID(params, "itemId", required = false)
        // Validate since if present
        parseInstant(params, "since")
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val itemId = extractUUID(params, "itemId", required = false)
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

        val itemTags = item.tagList()
        val schema = context.noteSchemaService().getSchemaForTags(itemTags)

        val notesResult = context.noteRepository().findByItemId(item.id)
        val notes =
            when (notesResult) {
                is Result.Success -> notesResult.data
                is Result.Error -> emptyList()
            }
        val notesByKey = notes.associateBy { it.key }

        // Build schema list with exists/filled status
        val schemaEntries =
            schema?.map { entry ->
                val note = notesByKey[entry.key]
                buildJsonObject {
                    put("key", JsonPrimitive(entry.key))
                    put("role", JsonPrimitive(entry.role.toJsonString()))
                    put("required", JsonPrimitive(entry.required))
                    put("description", JsonPrimitive(entry.description))
                    entry.guidance?.let { put("guidance", JsonPrimitive(it)) }
                    put("exists", JsonPrimitive(note != null))
                    put("filled", JsonPrimitive(note != null && note.body.isNotBlank()))
                }
            } ?: emptyList()

        // Gate status for current phase — uses shared computation
        val phaseContext = computePhaseNoteContext(item.role, schema, notesByKey)
        val missingForPhase = phaseContext?.missingKeys ?: emptyList()
        val guidancePointer = phaseContext?.guidancePointer

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
                put("schema", JsonArray(schemaEntries))
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

        // Fetch work, review, and blocked items in parallel
        val (workItems, reviewItems, blockedItems) =
            coroutineScope {
                val workDeferred = async { workItemRepo.findByRole(Role.WORK, limit = 200) }
                val reviewDeferred = async { workItemRepo.findByRole(Role.REVIEW, limit = 200) }
                val blockedDeferred = async { workItemRepo.findByRole(Role.BLOCKED, limit = 200) }

                Triple(
                    workDeferred.await().getOrElse(emptyList()),
                    reviewDeferred.await().getOrElse(emptyList()),
                    blockedDeferred.await().getOrElse(emptyList())
                )
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
                                if (includeAncestors) put("ancestors", buildAncestorsArray(ancestorChains[entry.item.id] ?: emptyList()))
                            }
                        }
                    )
                )
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
        val schemaService = context.noteSchemaService()
        val noteRepo = context.noteRepository()
        val result = mutableListOf<StalledItemEntry>()

        // Pre-filter to items that have a matching schema (avoids batch-fetching notes for schema-less items)
        val schemaItems =
            items.mapNotNull { item ->
                val tags = item.tagList()
                val schema = schemaService.getSchemaForTags(tags)
                if (schema != null) Triple(item, tags, schema) else null
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
        for ((item, _, schema) in schemaItems) {
            val notesByKey = (notesByItemId[item.id] ?: emptyList()).associateBy { it.key }
            val phaseContext = computePhaseNoteContext(item.role, schema, notesByKey)

            if (phaseContext != null && phaseContext.missingKeys.isNotEmpty()) {
                result.add(StalledItemEntry(item, phaseContext.missingKeys, phaseContext.guidancePointer))
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
