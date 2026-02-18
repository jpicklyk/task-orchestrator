package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

/**
 * Read-only MCP tool that recommends the next WorkItem(s) to work on.
 *
 * Selection logic:
 * 1. Find all items in QUEUE role (not yet started)
 * 2. Filter out blocked items (unsatisfied BLOCKS / IS_BLOCKED_BY dependencies)
 * 3. Sort by priority (HIGH > MEDIUM > LOW), then complexity ascending (quick wins first)
 * 4. Return top `limit` recommendations
 */
class GetNextItemTool : BaseToolDefinition() {

    override val name = "get_next_item"

    override val description = """
Recommends next work item(s) based on role, dependencies, priority, and complexity.

Finds QUEUE items, filters out those blocked by unsatisfied dependencies,
and ranks by priority (high first) then complexity (low first = quick wins).

Parameters:
- parentId (optional UUID): Scope to items under this parent
- limit (optional int, default 1, max 20): Number of recommendations
- includeDetails (optional boolean, default false): Include summary, tags, parentId
- includeAncestors (optional boolean, default false): when true, each recommended item includes an
  `ancestors` array ordered root-first (direct parent last). Root items (depth=0) get `"ancestors": []`.
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
            put("parentId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Scope recommendations to items under this parent (UUID)"))
            })
            put("limit", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Number of recommendations (default: 1, max: 20)"))
            })
            put("includeDetails", buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put("description", JsonPrimitive("Include summary, tags, parentId in response (default: false)"))
            })
            put("includeAncestors", buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put("description", JsonPrimitive("When true, each recommended item includes an ancestors array ordered root-first (default: false)"))
            })
        },
        required = emptyList()
    )

    override fun validateParams(params: JsonElement) {
        // All parameters are optional — just validate types if present
        extractUUID(params, "parentId", required = false)
        val limit = optionalInt(params, "limit")
        if (limit != null && (limit < 1 || limit > 20)) {
            throw ToolValidationException("limit must be between 1 and 20, got: $limit")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val parentId = extractUUID(params, "parentId", required = false)
        val limit = optionalInt(params, "limit") ?: 1
        val includeDetails = optionalBoolean(params, "includeDetails", defaultValue = false)
        val includeAncestors = params.jsonObject["includeAncestors"]?.jsonPrimitive?.booleanOrNull ?: false

        val workItemRepo = context.workItemRepository()
        val dependencyRepo = context.dependencyRepository()

        // Step 1: Find all QUEUE items (candidates)
        val candidatesResult = if (parentId != null) {
            workItemRepo.findByFilters(parentId = parentId, role = Role.QUEUE, limit = 200)
        } else {
            workItemRepo.findByRole(Role.QUEUE, limit = 200)
        }

        val candidates = when (candidatesResult) {
            is Result.Success -> candidatesResult.data
            is Result.Error -> return errorResponse(
                candidatesResult.error.message,
                ErrorCodes.DATABASE_ERROR
            )
        }

        // Step 2: Filter out blocked items
        val unblockedItems = candidates.filter { item ->
            !isBlocked(item, workItemRepo, dependencyRepo)
        }

        // Step 3: Sort by priority (HIGH > MEDIUM > LOW), then complexity ascending
        val priorityOrder = mapOf(Priority.HIGH to 0, Priority.MEDIUM to 1, Priority.LOW to 2)
        val sorted = unblockedItems.sortedWith(
            compareBy<WorkItem> { priorityOrder[it.priority] ?: 99 }
                .thenBy { it.complexity }
        )

        // Step 4: Take top `limit`
        val recommendations = sorted.take(limit)

        // Resolve ancestor chains once for all recommendations if requested
        val ancestorChains: Map<java.util.UUID, List<WorkItem>> = if (includeAncestors && recommendations.isNotEmpty()) {
            val allIds = recommendations.map { it.id }.toSet()
            when (val r = workItemRepo.findAncestorChains(allIds)) {
                is Result.Success -> r.data
                is Result.Error -> emptyMap()
            }
        } else emptyMap()

        // Build response
        val data = buildJsonObject {
            put("recommendations", JsonArray(recommendations.map { item ->
                buildJsonObject {
                    put("itemId", JsonPrimitive(item.id.toString()))
                    put("title", JsonPrimitive(item.title))
                    put("role", JsonPrimitive(item.role.name.lowercase()))
                    put("priority", JsonPrimitive(item.priority.name.lowercase()))
                    put("complexity", JsonPrimitive(item.complexity))
                    if (includeDetails) {
                        put("summary", JsonPrimitive(item.summary))
                        item.tags?.let { put("tags", JsonPrimitive(it)) }
                        item.parentId?.let { put("parentId", JsonPrimitive(it.toString())) }
                    }
                    if (includeAncestors) {
                        val ancestors = ancestorChains[item.id] ?: emptyList()
                        put("ancestors", JsonArray(ancestors.map { ancestor ->
                            buildJsonObject {
                                put("id", JsonPrimitive(ancestor.id.toString()))
                                put("title", JsonPrimitive(ancestor.title))
                                put("depth", JsonPrimitive(ancestor.depth))
                            }
                        }))
                    }
                }
            }))
            put("total", JsonPrimitive(recommendations.size))
        }

        return successResponse(data)
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return "No recommendations available"

        val data = (result as? JsonObject)?.get("data") as? JsonObject
        val total = data?.get("total")?.let {
            if (it is JsonPrimitive) it.intOrNull else null
        } ?: 0

        if (total == 0) return "No items ready to work on"

        val first = data?.get("recommendations")?.let {
            (it as? JsonArray)?.firstOrNull() as? JsonObject
        }
        val title = first?.get("title")?.let {
            if (it is JsonPrimitive && it.isString) it.content else null
        } ?: "unknown"

        return if (total == 1) "Next: $title" else "Next: $title (+${total - 1} more)"
    }

    // ──────────────────────────────────────────────
    // Blocking detection
    // ──────────────────────────────────────────────

    /**
     * Checks whether a WorkItem is blocked by any unsatisfied dependency.
     *
     * An item is blocked if:
     * - It has incoming BLOCKS deps (findByToItemId where type=BLOCKS) with unsatisfied blocker
     * - It has outgoing IS_BLOCKED_BY deps (findByFromItemId where type=IS_BLOCKED_BY) with unsatisfied blocker
     *
     * RELATES_TO dependencies are ignored (no blocking semantics).
     */
    private suspend fun isBlocked(
        item: WorkItem,
        workItemRepo: io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository,
        dependencyRepo: io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
    ): Boolean {
        // Check BLOCKS deps where this item is the target (dep.toItemId = item.id)
        val incomingDeps = dependencyRepo.findByToItemId(item.id)
        for (dep in incomingDeps) {
            if (dep.type == DependencyType.BLOCKS) {
                val threshold = dep.effectiveUnblockRole() ?: continue
                val thresholdRole = Role.fromString(threshold) ?: continue
                // Blocker is dep.fromItemId
                val blockerResult = workItemRepo.getById(dep.fromItemId)
                val blocker = when (blockerResult) {
                    is Result.Success -> blockerResult.data
                    is Result.Error -> continue // If we can't fetch, skip this dep
                }
                if (!Role.isAtOrBeyond(blocker.role, thresholdRole)) {
                    return true // Unsatisfied dependency
                }
            }
        }

        // Check IS_BLOCKED_BY deps where this item is the source (dep.fromItemId = item.id)
        val outgoingDeps = dependencyRepo.findByFromItemId(item.id)
        for (dep in outgoingDeps) {
            if (dep.type == DependencyType.IS_BLOCKED_BY) {
                val threshold = dep.effectiveUnblockRole() ?: continue
                val thresholdRole = Role.fromString(threshold) ?: continue
                // Blocker is dep.toItemId
                val blockerResult = workItemRepo.getById(dep.toItemId)
                val blocker = when (blockerResult) {
                    is Result.Success -> blockerResult.data
                    is Result.Error -> continue
                }
                if (!Role.isAtOrBeyond(blocker.role, thresholdRole)) {
                    return true
                }
            }
        }

        return false
    }
}
