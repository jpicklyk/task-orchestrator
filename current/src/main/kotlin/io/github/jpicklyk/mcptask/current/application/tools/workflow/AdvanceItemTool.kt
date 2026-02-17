package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.CascadeDetector
import io.github.jpicklyk.mcptask.current.application.service.RoleTransitionHandler
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Trigger-based role transitions for WorkItems with validation, cascade detection,
 * and unblock reporting.
 *
 * Supports batch transitions via the `transitions` array parameter. Each transition
 * is processed independently: failures on one do not block others.
 *
 * Valid triggers: start, complete, block, hold, resume, cancel.
 *
 * NoteSchemaService integration:
 * - If the item's tags match a schema, gate enforcement applies:
 *   - "start": required notes for the CURRENT role must be filled before advancing
 *   - "complete": all required notes across all phases must be filled
 * - hasReviewPhase: if the schema has no "review" entries, start from WORK skips REVIEW
 * - expectedNotes: the success result includes schema entries for the new role
 */
class AdvanceItemTool : BaseToolDefinition() {

    override val name = "advance_item"

    override val description = """
Trigger-based role transitions for WorkItems with validation, cascade detection, and unblock reporting.

**Parameters:**
- `transitions` (required array): Each element: `{ itemId (required UUID), trigger (required string), summary? (optional string) }`
- Valid triggers: start, complete, block, hold, resume, cancel

**Trigger effects:**
- start: QUEUE->WORK, WORK->REVIEW (or TERMINAL if no review phase in schema), REVIEW->TERMINAL
- complete: any non-TERMINAL/BLOCKED -> TERMINAL
- block/hold: any non-TERMINAL/BLOCKED -> BLOCKED (saves previousRole)
- resume: BLOCKED -> previousRole
- cancel: any non-TERMINAL -> TERMINAL (statusLabel = "cancelled")

**Gate enforcement (when tags match a note schema):**
- start: required notes for the current phase must be filled before advancing
- complete: all required notes across all phases must be filled

**Response:**
```json
{
  "results": [
    {
      "itemId": "uuid",
      "previousRole": "queue",
      "newRole": "work",
      "trigger": "start",
      "applied": true,
      "cascadeEvents": [],
      "unblockedItems": [],
      "expectedNotes": [
        { "key": "acceptance-criteria", "role": "work", "required": true, "description": "...", "exists": false }
      ]
    }
  ],
  "summary": { "total": N, "succeeded": N, "failed": N },
  "allUnblockedItems": [{ "itemId": "uuid", "title": "..." }]
}
```
    """.trimIndent()

    override val category = ToolCategory.WORKFLOW

    override val toolAnnotations = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = false,
        idempotentHint = false,
        openWorldHint = false
    )

    override val parameterSchema = ToolSchema(
        properties = buildJsonObject {
            put("transitions", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("description", JsonPrimitive("Array of transition objects: { itemId, trigger, summary? }"))
            })
        },
        required = listOf("transitions")
    )

    override fun validateParams(params: JsonElement) {
        val transitions = requireJsonArray(params, "transitions")
        if (transitions.isEmpty()) {
            throw ToolValidationException("transitions array must not be empty")
        }
        for ((index, element) in transitions.withIndex()) {
            val obj = element as? JsonObject
                ?: throw ToolValidationException("transitions[$index] must be a JSON object")
            val itemIdPrim = obj["itemId"] as? JsonPrimitive
                ?: throw ToolValidationException("transitions[$index] missing required field: itemId")
            if (!itemIdPrim.isString || itemIdPrim.content.isBlank()) {
                throw ToolValidationException("transitions[$index].itemId must be a non-empty string")
            }
            try {
                UUID.fromString(itemIdPrim.content)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("transitions[$index].itemId must be a valid UUID")
            }
            val triggerPrim = obj["trigger"] as? JsonPrimitive
                ?: throw ToolValidationException("transitions[$index] missing required field: trigger")
            if (!triggerPrim.isString || triggerPrim.content.isBlank()) {
                throw ToolValidationException("transitions[$index].trigger must be a non-empty string")
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val transitions = requireJsonArray(params, "transitions")

        val handler = RoleTransitionHandler()
        val cascadeDetector = CascadeDetector()
        val noteSchemaService = context.noteSchemaService()

        val resultsList = mutableListOf<JsonObject>()
        val allUnblockedItems = mutableListOf<JsonObject>()
        var successCount = 0
        var failCount = 0

        for (element in transitions) {
            val obj = element as JsonObject
            val itemId = UUID.fromString((obj["itemId"] as JsonPrimitive).content)
            val trigger = (obj["trigger"] as JsonPrimitive).content.lowercase()
            val summary = (obj["summary"] as? JsonPrimitive)?.let {
                if (it.isString && it.content.isNotBlank()) it.content else null
            }

            // Fetch the WorkItem
            val itemResult = context.workItemRepository().getById(itemId)
            val item = when (itemResult) {
                is Result.Success -> itemResult.data
                is Result.Error -> {
                    failCount++
                    resultsList.add(buildErrorResult(itemId, trigger, "WorkItem not found: $itemId"))
                    continue
                }
            }

            val previousRole = item.role

            // Parse item tags for schema lookup
            val itemTags = item.tagList()

            // Phase 1: Resolve — schema-driven review phase detection
            val hasReviewPhase = noteSchemaService.hasReviewPhase(itemTags)
            val resolution = handler.resolveTransition(item, trigger, hasReviewPhase)
            if (!resolution.success || resolution.targetRole == null) {
                failCount++
                resultsList.add(buildErrorResult(itemId, trigger, resolution.error ?: "Failed to resolve transition"))
                continue
            }

            val targetRole = resolution.targetRole

            // Phase 2: Validate dependency constraints
            val validation = handler.validateTransition(
                item, targetRole,
                context.dependencyRepository(),
                context.workItemRepository()
            )
            if (!validation.valid) {
                failCount++
                val blockersJson = if (validation.blockers.isNotEmpty()) {
                    JsonArray(validation.blockers.map { blocker ->
                        buildJsonObject {
                            put("fromItemId", JsonPrimitive(blocker.fromItemId.toString()))
                            put("currentRole", JsonPrimitive(blocker.currentRole.name.lowercase()))
                            put("requiredRole", JsonPrimitive(blocker.requiredRole))
                        }
                    })
                } else null
                resultsList.add(
                    buildErrorResult(
                        itemId, trigger,
                        validation.error ?: "Transition validation failed",
                        blockersJson
                    )
                )
                continue
            }

            // Gate check: required notes for the CURRENT role must exist before advancing (start trigger)
            if (trigger == "start") {
                val schema = noteSchemaService.getSchemaForTags(itemTags)
                if (schema != null) {
                    val currentRoleStr = item.role.name.lowercase()
                    val requiredForCurrentPhase = schema.filter { it.role == currentRoleStr && it.required }
                    if (requiredForCurrentPhase.isNotEmpty()) {
                        val notesResult = context.noteRepository().findByItemId(item.id)
                        val existingNotes = when (notesResult) {
                            is Result.Success -> notesResult.data
                            is Result.Error -> emptyList()
                        }
                        val filledKeys = existingNotes.filter { it.body.isNotBlank() }.map { it.key }.toSet()
                        val missingKeys = requiredForCurrentPhase.filter { it.key !in filledKeys }.map { it.key }
                        if (missingKeys.isNotEmpty()) {
                            failCount++
                            resultsList.add(buildErrorResult(itemId, trigger,
                                "Gate check failed: required notes not filled for ${currentRoleStr} phase: ${missingKeys.joinToString()}"))
                            continue
                        }
                    }
                }
            }

            // Gate check: all required notes across all phases must be filled (complete trigger)
            if (trigger == "complete") {
                val schema = noteSchemaService.getSchemaForTags(itemTags)
                if (schema != null) {
                    val allRequired = schema.filter { it.required }
                    if (allRequired.isNotEmpty()) {
                        val notesResult = context.noteRepository().findByItemId(item.id)
                        val existingNotes = when (notesResult) {
                            is Result.Success -> notesResult.data
                            is Result.Error -> emptyList()
                        }
                        val filledKeys = existingNotes.filter { it.body.isNotBlank() }.map { it.key }.toSet()
                        val missingKeys = allRequired.filter { it.key !in filledKeys }.map { it.key }
                        if (missingKeys.isNotEmpty()) {
                            failCount++
                            resultsList.add(buildErrorResult(itemId, trigger,
                                "Gate check failed: required notes not filled: ${missingKeys.joinToString()}"))
                            continue
                        }
                    }
                }
            }

            // Phase 3: Apply
            val applyResult = handler.applyTransition(
                item, targetRole, trigger, summary, resolution.statusLabel,
                context.workItemRepository(),
                context.roleTransitionRepository()
            )
            if (!applyResult.success || applyResult.item == null) {
                failCount++
                resultsList.add(buildErrorResult(itemId, trigger, applyResult.error ?: "Failed to apply transition"))
                continue
            }

            successCount++

            // Phase 4: Cascade detection (only when reaching TERMINAL)
            // Uses iterative detect-apply pattern: after applying each cascade,
            // re-detect from the cascaded parent with fresh DB state.
            // Bounded by CascadeDetector.MAX_DEPTH to prevent runaway recursion.
            val cascadeJsonList = mutableListOf<JsonObject>()
            if (targetRole == Role.TERMINAL) {
                var cascadeSource = applyResult.item!!
                var depth = 0
                while (depth < CascadeDetector.MAX_DEPTH) {
                    val events = cascadeDetector.detectCascades(cascadeSource, context.workItemRepository())
                    if (events.isEmpty()) break

                    // Only the immediate parent cascade (first event) is reliable;
                    // deeper events may read stale DB state prior to this cascade's apply.
                    val event = events.first()

                    val parentResult = context.workItemRepository().getById(event.itemId)
                    val parentItem = when (parentResult) {
                        is Result.Success -> parentResult.data
                        is Result.Error -> break
                    }

                    val cascadeApply = handler.applyTransition(
                        parentItem, event.targetRole, "cascade",
                        "Auto-cascaded from child completion", null,
                        context.workItemRepository(),
                        context.roleTransitionRepository()
                    )

                    cascadeJsonList.add(buildJsonObject {
                        put("itemId", JsonPrimitive(event.itemId.toString()))
                        put("previousRole", JsonPrimitive(event.currentRole.name.lowercase()))
                        put("targetRole", JsonPrimitive(event.targetRole.name.lowercase()))
                        put("applied", JsonPrimitive(cascadeApply.success))
                    })

                    if (!cascadeApply.success || cascadeApply.item == null) break

                    // Continue up the tree: re-detect from the newly-cascaded parent
                    cascadeSource = cascadeApply.item
                    depth++
                }
            }

            // Phase 5: Unblock detection
            val unblockedJsonList = mutableListOf<JsonObject>()
            val unblockedItems = cascadeDetector.findUnblockedItems(
                applyResult.item,
                context.dependencyRepository(),
                context.workItemRepository()
            )
            for (unblocked in unblockedItems) {
                val unblockedJson = buildJsonObject {
                    put("itemId", JsonPrimitive(unblocked.itemId.toString()))
                    put("title", JsonPrimitive(unblocked.title))
                }
                unblockedJsonList.add(unblockedJson)
                allUnblockedItems.add(unblockedJson)
            }

            // Build expectedNotes: schema entries matching the new role
            val expectedNotesJson: JsonArray = run {
                val schema = noteSchemaService.getSchemaForTags(itemTags)
                if (schema == null) {
                    JsonArray(emptyList())
                } else {
                    val newRoleStr = targetRole.name.lowercase()
                    val notesResult = context.noteRepository().findByItemId(item.id)
                    val existingNotes = when (notesResult) {
                        is Result.Success -> notesResult.data
                        is Result.Error -> emptyList()
                    }
                    val existingKeys = existingNotes.map { it.key }.toSet()
                    val forNewRole = schema.filter { it.role == newRoleStr }
                    JsonArray(forNewRole.map { entry ->
                        buildJsonObject {
                            put("key", JsonPrimitive(entry.key))
                            put("role", JsonPrimitive(entry.role))
                            put("required", JsonPrimitive(entry.required))
                            put("description", JsonPrimitive(entry.description))
                            entry.guidance?.let { put("guidance", JsonPrimitive(it)) }
                            put("exists", JsonPrimitive(entry.key in existingKeys))
                        }
                    })
                }
            }

            // Build success result
            resultsList.add(buildJsonObject {
                put("itemId", JsonPrimitive(itemId.toString()))
                put("previousRole", JsonPrimitive(previousRole.name.lowercase()))
                put("newRole", JsonPrimitive(targetRole.name.lowercase()))
                put("trigger", JsonPrimitive(trigger))
                put("applied", JsonPrimitive(true))
                if (summary != null) put("summary", JsonPrimitive(summary))
                put("cascadeEvents", JsonArray(cascadeJsonList))
                put("unblockedItems", JsonArray(unblockedJsonList))
                put("expectedNotes", expectedNotesJson)
            })
        }

        val totalCount = successCount + failCount
        val data = buildJsonObject {
            put("results", JsonArray(resultsList))
            put("summary", buildJsonObject {
                put("total", JsonPrimitive(totalCount))
                put("succeeded", JsonPrimitive(successCount))
                put("failed", JsonPrimitive(failCount))
            })
            put("allUnblockedItems", JsonArray(allUnblockedItems))
        }

        return successResponse(data)
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return "advance_item failed"
        val data = (result as? JsonObject)?.get("data") as? JsonObject
        val summary = data?.get("summary") as? JsonObject
        val total = summary?.get("total")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val succeeded = summary?.get("succeeded")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val failed = summary?.get("failed")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        return if (failed == 0) "Transitioned $succeeded item(s)" else "Transitioned $succeeded/$total (${failed} failed)"
    }

    private fun buildErrorResult(
        itemId: UUID,
        trigger: String,
        error: String,
        blockers: JsonArray? = null
    ): JsonObject {
        return buildJsonObject {
            put("itemId", JsonPrimitive(itemId.toString()))
            put("trigger", JsonPrimitive(trigger))
            put("applied", JsonPrimitive(false))
            put("error", JsonPrimitive(error))
            if (blockers != null) {
                put("blockers", blockers)
            }
        }
    }
}
