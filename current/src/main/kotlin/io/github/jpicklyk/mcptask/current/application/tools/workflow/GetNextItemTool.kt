package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

/** Non-terminal roles that `get_next_item` may query. */
private val VALID_ROLES = setOf("queue", "work", "review", "blocked")

/**
 * Read-only MCP tool that recommends the next WorkItem(s) to work on.
 *
 * Selection logic:
 * 1. Find all items in the requested role (default: QUEUE)
 * 2. Filter out items with active (non-expired) claims unless includeClaimed=true
 * 3. Filter out blocked items (unsatisfied BLOCKS / IS_BLOCKED_BY dependencies)
 * 4. Sort by priority (HIGH > MEDIUM > LOW), then complexity ascending (quick wins first)
 * 5. Return top `limit` recommendations
 *
 * Tiered claim disclosure: the response never exposes `claimedBy` or `claimedAt`.
 * When `includeClaimed=true`, only a boolean `isClaimed` field is added to each item.
 */
class GetNextItemTool : BaseToolDefinition() {
    override val name = "get_next_item"

    override val description =
        """
Recommends next work item(s) based on role, dependencies, priority, and complexity.

Finds items in the requested role (default: queue), filters out those blocked by
unsatisfied dependencies and those with active claims (unless includeClaimed=true),
and ranks by priority (high first) then complexity (low first = quick wins).

Parameters:
- role (optional string, default "queue"): Role to query — "queue", "work", "review", or "blocked"
- parentId (optional UUID): Scope to items under this parent
- limit (optional int, default 1, max 20): Number of recommendations
- includeDetails (optional boolean, default false): Include summary, tags, parentId
- includeAncestors (optional boolean, default false): when true, each recommended item includes an
  `ancestors` array ordered root-first (direct parent last). Root items (depth=0) get `"ancestors": []`.
- includeClaimed (optional boolean, default false): When false (default), items with an active
  (non-expired) claim are filtered out. When true, claimed items are included but only a boolean
  `isClaimed` field is exposed — the claiming agent's identity is never disclosed.
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
                        "role",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Role to query (default: \"queue\"). Valid values: \"queue\", \"work\", \"review\", \"blocked\""
                                )
                            )
                        }
                    )
                    put(
                        "parentId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Scope to items under this parent (UUID or hex prefix 4+ chars)")
                            )
                        }
                    )
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Number of recommendations (default: 1, max: 20)"))
                        }
                    )
                    put(
                        "includeDetails",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("Include summary, tags, parentId in response (default: false)"))
                        }
                    )
                    put(
                        "includeAncestors",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "When true, each recommended item includes an ancestors array ordered root-first (default: false)"
                                )
                            )
                        }
                    )
                    put(
                        "includeClaimed",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "When true, include items with active claims (shows isClaimed boolean only — identity never disclosed). Default: false."
                                )
                            )
                        }
                    )
                },
            required = emptyList()
        )

    override fun validateParams(params: JsonElement) {
        // Validate role parameter
        val roleStr = optionalString(params, "role")
        if (roleStr != null && roleStr.lowercase() !in VALID_ROLES) {
            throw ToolValidationException(
                "invalid_role: role must be one of ${VALID_ROLES.joinToString(", ")} — got: $roleStr"
            )
        }

        // All other parameters are optional — just validate types if present
        validateIdOrPrefix(params, "parentId", required = false)
        val limit = optionalInt(params, "limit")
        if (limit != null && (limit < 1 || limit > 20)) {
            throw ToolValidationException("limit must be between 1 and 20, got: $limit")
        }
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        // Parse parameters
        val roleStr = optionalString(params, "role") ?: "queue"
        val targetRole =
            Role.fromString(roleStr)
                ?: return errorResponse("invalid_role: unrecognized role '$roleStr'", ErrorCodes.VALIDATION_ERROR)

        val (parentId, parentIdError) = resolveItemId(params, "parentId", context, required = false)
        if (parentIdError != null) return parentIdError

        val limit = optionalInt(params, "limit") ?: 1
        val includeDetails = optionalBoolean(params, "includeDetails", defaultValue = false)
        val includeAncestors = optionalBoolean(params, "includeAncestors", false)
        val includeClaimed = optionalBoolean(params, "includeClaimed", false)

        val workItemRepo = context.workItemRepository()
        val dependencyRepo = context.dependencyRepository()

        // Step 1: Find items in the requested role, applying claim filter at the DB level
        val excludeActiveClaims = !includeClaimed
        val candidatesResult =
            workItemRepo.findForNextItem(
                role = targetRole,
                parentId = parentId,
                excludeActiveClaims = excludeActiveClaims,
                limit = 200
            )

        val candidates =
            when (candidatesResult) {
                is Result.Success -> candidatesResult.data
                is Result.Error -> return errorResponse(
                    candidatesResult.error.message,
                    ErrorCodes.DATABASE_ERROR
                )
            }

        // Step 2: Filter out blocked items (dependency-blocked, not role-BLOCKED).
        // isBlocked logic lives in NextItemRecommender; replicated inline here so Phase 3 can
        // cut execute() over to the recommender in a single focused change (no behaviour change
        // needed in Phase 2 — findForNextItem / in-memory sort path is preserved as-is).
        suspend fun isBlockedInline(item: WorkItem): Boolean {
            val incomingDeps = dependencyRepo.findByToItemId(item.id)
            for (dep in incomingDeps) {
                if (dep.type == DependencyType.BLOCKS) {
                    val threshold = dep.effectiveUnblockRole() ?: continue
                    val thresholdRole = Role.fromString(threshold) ?: continue
                    val blockerResult = workItemRepo.getById(dep.fromItemId)
                    val blocker =
                        when (blockerResult) {
                            is Result.Success -> blockerResult.data
                            is Result.Error -> continue
                        }
                    if (!Role.isAtOrBeyond(blocker.role, thresholdRole)) return true
                }
            }
            val outgoingDeps = dependencyRepo.findByFromItemId(item.id)
            for (dep in outgoingDeps) {
                if (dep.type == DependencyType.IS_BLOCKED_BY) {
                    val threshold = dep.effectiveUnblockRole() ?: continue
                    val thresholdRole = Role.fromString(threshold) ?: continue
                    val blockerResult = workItemRepo.getById(dep.toItemId)
                    val blocker =
                        when (blockerResult) {
                            is Result.Success -> blockerResult.data
                            is Result.Error -> continue
                        }
                    if (!Role.isAtOrBeyond(blocker.role, thresholdRole)) return true
                }
            }
            return false
        }

        val unblockedItems =
            candidates.filter { item ->
                !isBlockedInline(item)
            }

        // Step 3: Sort by priority (HIGH > MEDIUM > LOW), then complexity ascending
        val priorityOrder = mapOf(Priority.HIGH to 0, Priority.MEDIUM to 1, Priority.LOW to 2)
        val sorted =
            unblockedItems.sortedWith(
                compareBy<WorkItem> { priorityOrder[it.priority] ?: 99 }
                    .thenBy { it.complexity }
            )

        // Step 4: Take top `limit`
        val recommendations = sorted.take(limit)

        // Resolve ancestor chains once for all recommendations if requested
        val ancestorChains: Map<java.util.UUID, List<WorkItem>> =
            if (includeAncestors && recommendations.isNotEmpty()) {
                val allIds = recommendations.map { it.id }.toSet()
                when (val r = workItemRepo.findAncestorChains(allIds)) {
                    is Result.Success -> r.data
                    is Result.Error -> emptyMap()
                }
            } else {
                emptyMap()
            }

        // Fetch DB-side time once (only needed when includeClaimed=true so isClaimed is accurate).
        // Using DB clock avoids false positives/negatives when JVM and SQLite clocks skew.
        val dbNowForClaimed: java.time.Instant? =
            if (includeClaimed && recommendations.any { it.claimedBy != null }) workItemRepo.dbNow() else null

        // Build response
        val data =
            buildJsonObject {
                put(
                    "recommendations",
                    JsonArray(
                        recommendations.map { item ->
                            buildJsonObject {
                                put("itemId", JsonPrimitive(item.id.toString()))
                                put("title", JsonPrimitive(item.title))
                                put("role", JsonPrimitive(item.role.toJsonString()))
                                put("priority", JsonPrimitive(item.priority.toJsonString()))
                                put("complexity", JsonPrimitive(item.complexity))
                                if (includeDetails) {
                                    put("summary", JsonPrimitive(item.summary))
                                    item.tags?.let { put("tags", JsonPrimitive(it)) }
                                    item.parentId?.let { put("parentId", JsonPrimitive(it.toString())) }
                                }
                                // Tiered claim disclosure: expose only boolean isClaimed, never claimedBy/claimedAt.
                                // An item is "actively claimed" when claimedBy is set and claimExpiresAt is in the future.
                                // Use DB-side now so isClaimed reflects the DB clock.
                                if (includeClaimed) {
                                    val now = dbNowForClaimed ?: java.time.Instant.now()
                                    val activelyClaimedNow =
                                        item.claimedBy != null &&
                                            item.claimExpiresAt?.isAfter(now) == true
                                    put("isClaimed", JsonPrimitive(activelyClaimedNow))
                                }
                                if (includeAncestors) {
                                    put("ancestors", buildAncestorsArray(ancestorChains[item.id] ?: emptyList()))
                                }
                            }
                        }
                    )
                )
                put("total", JsonPrimitive(recommendations.size))
            }

        return successResponse(data)
    }

    override fun userSummary(
        params: JsonElement,
        result: JsonElement,
        isError: Boolean
    ): String {
        if (isError) return "No recommendations available"

        val data = (result as? JsonObject)?.get("data") as? JsonObject
        val total =
            data?.get("total")?.let {
                if (it is JsonPrimitive) it.intOrNull else null
            } ?: 0

        if (total == 0) return "No items ready to work on"

        val first =
            data?.get("recommendations")?.let {
                (it as? JsonArray)?.firstOrNull() as? JsonObject
            }
        val title =
            first?.get("title")?.let {
                if (it is JsonPrimitive && it.isString) it.content else null
            } ?: "unknown"

        return if (total == 1) "Next: $title" else "Next: $title (+${total - 1} more)"
    }
}
