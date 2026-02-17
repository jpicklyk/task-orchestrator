package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

/**
 * Read-only MCP tool that provides rich context in three modes:
 *
 * - **Item mode** (`itemId` provided): Returns schema, existing notes, and gate status for a specific WorkItem.
 * - **Session resume** (`since` provided): Returns active items, recent role transitions, and stalled items since a timestamp.
 * - **Health check** (no params): Returns all active/blocked/stalled items for a dashboard-style overview.
 */
class GetContextTool : BaseToolDefinition() {

    override val name = "get_context"

    override val description = """
Read-only context snapshot. Three modes:

**Item mode** — `itemId` (UUID):
Returns the item's current role, note schema for its tags, existing notes with filled/exists status,
and gate status (canAdvance + missing required notes for current phase).

**Session resume** — `since` (ISO 8601 timestamp):
Returns active items (role=work or review), recent role transitions since the timestamp,
and stalled items (active items with missing required notes).

**Health check** — no parameters:
Returns all active items (work/review), blocked items, and stalled items.

Parameters:
- itemId (optional UUID): triggers item context mode
- since (optional ISO 8601 string): triggers session resume mode
    """.trimIndent()

    override val category = ToolCategory.WORKFLOW

    override val toolAnnotations = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false
    )

    override val parameterSchema = ToolSchema(
        properties = buildJsonObject {
            put("itemId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("UUID of a WorkItem for item context mode"))
            })
            put("since", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("ISO 8601 timestamp for session resume mode (e.g. 2024-01-01T00:00:00Z)"))
            })
        },
        required = emptyList()
    )

    override fun validateParams(params: JsonElement) {
        // Validate itemId if present
        extractUUID(params, "itemId", required = false)
        // Validate since if present
        parseInstant(params, "since")
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val itemId = extractUUID(params, "itemId", required = false)
        val sinceInstant = parseInstant(params, "since")

        return when {
            itemId != null -> executeItemMode(itemId, context)
            sinceInstant != null -> executeSessionResumeMode(sinceInstant, context)
            else -> executeHealthCheckMode(context)
        }
    }

    // ──────────────────────────────────────────────
    // Mode 1: Item context
    // ──────────────────────────────────────────────

    private suspend fun executeItemMode(
        itemId: java.util.UUID,
        context: ToolExecutionContext
    ): JsonElement {
        val itemResult = context.workItemRepository().getById(itemId)
        val item = when (itemResult) {
            is Result.Success -> itemResult.data
            is Result.Error -> return errorResponse(
                "WorkItem not found: $itemId",
                ErrorCodes.RESOURCE_NOT_FOUND
            )
        }

        val itemTags = item.tagList()
        val schema = context.noteSchemaService().getSchemaForTags(itemTags)

        val notesResult = context.noteRepository().findByItemId(item.id)
        val notes = when (notesResult) {
            is Result.Success -> notesResult.data
            is Result.Error -> emptyList()
        }
        val notesByKey = notes.associateBy { it.key }
        val currentRoleStr = item.role.name.lowercase()

        // Build schema list with exists/filled status
        val schemaEntries = schema?.map { entry ->
            val note = notesByKey[entry.key]
            buildJsonObject {
                put("key", JsonPrimitive(entry.key))
                put("role", JsonPrimitive(entry.role))
                put("required", JsonPrimitive(entry.required))
                put("description", JsonPrimitive(entry.description))
                entry.guidance?.let { put("guidance", JsonPrimitive(it)) }
                put("exists", JsonPrimitive(note != null))
                put("filled", JsonPrimitive(note != null && note.body.isNotBlank()))
            }
        } ?: emptyList()

        // Gate status for current phase
        val currentPhaseRequired = schema?.filter { it.role == currentRoleStr && it.required } ?: emptyList()
        val missingForPhase = currentPhaseRequired.filter {
            val note = notesByKey[it.key]
            note == null || note.body.isBlank()
        }.map { it.key }

        val guidancePointer = schema?.firstOrNull()?.guidance

        val data = buildJsonObject {
            put("mode", JsonPrimitive("item"))
            put("item", buildJsonObject {
                put("id", JsonPrimitive(item.id.toString()))
                put("title", JsonPrimitive(item.title))
                put("role", JsonPrimitive(item.role.name.lowercase()))
                item.tags?.let { put("tags", JsonPrimitive(it)) }
                put("depth", JsonPrimitive(item.depth))
            })
            put("schema", JsonArray(schemaEntries))
            put("gateStatus", buildJsonObject {
                put("canAdvance", JsonPrimitive(missingForPhase.isEmpty()))
                put("phase", JsonPrimitive(currentRoleStr))
                put("missing", JsonArray(missingForPhase.map { JsonPrimitive(it) }))
            })
            if (guidancePointer != null) {
                put("guidancePointer", JsonPrimitive(guidancePointer))
            } else {
                put("guidancePointer", JsonNull)
            }
        }

        return successResponse(data)
    }

    // ──────────────────────────────────────────────
    // Mode 2: Session resume
    // ──────────────────────────────────────────────

    private suspend fun executeSessionResumeMode(
        since: java.time.Instant,
        context: ToolExecutionContext
    ): JsonElement {
        val workItemRepo = context.workItemRepository()

        // Fetch work and review items (two calls, merge results)
        val workItems = when (val r = workItemRepo.findByRole(Role.WORK, limit = 200)) {
            is Result.Success -> r.data
            is Result.Error -> emptyList()
        }
        val reviewItems = when (val r = workItemRepo.findByRole(Role.REVIEW, limit = 200)) {
            is Result.Success -> r.data
            is Result.Error -> emptyList()
        }
        val activeItems = (workItems + reviewItems)

        // Recent transitions since the given timestamp
        val recentTransitions = when (val r = context.roleTransitionRepository().findSince(since, limit = 50)) {
            is Result.Success -> r.data
            is Result.Error -> emptyList()
        }

        // Stalled items: active items with missing required notes
        val stalledItems = findStalledItems(activeItems, context)

        val data = buildJsonObject {
            put("mode", JsonPrimitive("session-resume"))
            put("since", JsonPrimitive(since.toString()))
            put("activeItems", JsonArray(activeItems.map { item ->
                buildJsonObject {
                    put("id", JsonPrimitive(item.id.toString()))
                    put("title", JsonPrimitive(item.title))
                    put("role", JsonPrimitive(item.role.name.lowercase()))
                    item.tags?.let { put("tags", JsonPrimitive(it)) }
                }
            }))
            put("recentTransitions", JsonArray(recentTransitions.map { t ->
                buildJsonObject {
                    put("itemId", JsonPrimitive(t.itemId.toString()))
                    put("fromRole", JsonPrimitive(t.fromRole))
                    put("toRole", JsonPrimitive(t.toRole))
                    put("trigger", JsonPrimitive(t.trigger))
                    put("at", JsonPrimitive(t.transitionedAt.toString()))
                }
            }))
            put("stalledItems", JsonArray(stalledItems.map { (item, missing) ->
                buildJsonObject {
                    put("id", JsonPrimitive(item.id.toString()))
                    put("title", JsonPrimitive(item.title))
                    put("role", JsonPrimitive(item.role.name.lowercase()))
                    put("missingNotes", JsonArray(missing.map { JsonPrimitive(it) }))
                }
            }))
        }

        return successResponse(data)
    }

    // ──────────────────────────────────────────────
    // Mode 3: Health check
    // ──────────────────────────────────────────────

    private suspend fun executeHealthCheckMode(context: ToolExecutionContext): JsonElement {
        val workItemRepo = context.workItemRepository()

        val workItems = when (val r = workItemRepo.findByRole(Role.WORK, limit = 200)) {
            is Result.Success -> r.data
            is Result.Error -> emptyList()
        }
        val reviewItems = when (val r = workItemRepo.findByRole(Role.REVIEW, limit = 200)) {
            is Result.Success -> r.data
            is Result.Error -> emptyList()
        }
        val blockedItems = when (val r = workItemRepo.findByRole(Role.BLOCKED, limit = 200)) {
            is Result.Success -> r.data
            is Result.Error -> emptyList()
        }

        val activeItems = (workItems + reviewItems)
        val stalledItems = findStalledItems(activeItems, context)

        val data = buildJsonObject {
            put("mode", JsonPrimitive("health-check"))
            put("activeItems", JsonArray(activeItems.map { item ->
                buildJsonObject {
                    put("id", JsonPrimitive(item.id.toString()))
                    put("title", JsonPrimitive(item.title))
                    put("role", JsonPrimitive(item.role.name.lowercase()))
                    item.tags?.let { put("tags", JsonPrimitive(it)) }
                }
            }))
            put("blockedItems", JsonArray(blockedItems.map { item ->
                buildJsonObject {
                    put("id", JsonPrimitive(item.id.toString()))
                    put("title", JsonPrimitive(item.title))
                    put("role", JsonPrimitive(item.role.name.lowercase()))
                }
            }))
            put("stalledItems", JsonArray(stalledItems.map { (item, missing) ->
                buildJsonObject {
                    put("id", JsonPrimitive(item.id.toString()))
                    put("title", JsonPrimitive(item.title))
                    put("role", JsonPrimitive(item.role.name.lowercase()))
                    put("missingNotes", JsonArray(missing.map { JsonPrimitive(it) }))
                }
            }))
        }

        return successResponse(data)
    }

    // ──────────────────────────────────────────────
    // Shared helpers
    // ──────────────────────────────────────────────

    /**
     * For each active item, determine which required notes for its current phase are missing.
     * Returns only items that have at least one missing required note.
     */
    private suspend fun findStalledItems(
        items: List<io.github.jpicklyk.mcptask.current.domain.model.WorkItem>,
        context: ToolExecutionContext
    ): List<Pair<io.github.jpicklyk.mcptask.current.domain.model.WorkItem, List<String>>> {
        val schemaService = context.noteSchemaService()
        val noteRepo = context.noteRepository()
        val result = mutableListOf<Pair<io.github.jpicklyk.mcptask.current.domain.model.WorkItem, List<String>>>()

        for (item in items) {
            val tags = item.tagList()
            val schema = schemaService.getSchemaForTags(tags) ?: continue
            val currentRoleStr = item.role.name.lowercase()

            val notes = when (val r = noteRepo.findByItemId(item.id)) {
                is Result.Success -> r.data
                is Result.Error -> continue
            }
            val notesByKey = notes.associateBy { it.key }

            val missing = schema
                .filter { it.role == currentRoleStr && it.required }
                .filter { entry ->
                    val note = notesByKey[entry.key]
                    note == null || note.body.isBlank()
                }
                .map { it.key }

            if (missing.isNotEmpty()) {
                result.add(item to missing)
            }
        }

        return result
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return "get_context failed"
        val data = (result as? JsonObject)?.get("data") as? JsonObject
        val mode = data?.get("mode")?.let { (it as? JsonPrimitive)?.content } ?: "unknown"
        return when (mode) {
            "item" -> {
                val canAdvance = data?.get("gateStatus")?.jsonObject?.get("canAdvance")?.jsonPrimitive?.boolean ?: false
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
