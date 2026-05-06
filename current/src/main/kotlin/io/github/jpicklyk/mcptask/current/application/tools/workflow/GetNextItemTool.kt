package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.time.Instant

/** Non-terminal roles that `get_next_item` may query. */
private val VALID_ROLES = setOf("queue", "work", "review", "blocked")

/**
 * Read-only MCP tool that recommends the next WorkItem(s) to work on.
 *
 * Selection logic:
 * 1. Find all items in the requested role (default: QUEUE) matching optional filters.
 * 2. Filter out items with active (non-expired) claims unless includeClaimed=true.
 * 3. Filter out dependency-blocked items.
 * 4. Order by `orderBy` (default: priority then complexity ascending = quick wins first).
 * 5. Return top `limit` recommendations.
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
and ranks by priority (high first) then complexity (low first = quick wins) by default.

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

Filter parameters (all optional, any-match semantics where applicable):
- tags (string, comma-separated): Return only items whose tags contain any of the given values
- priority (string: high|medium|low): Return only items with this exact priority
- type (string): Return only items of this type
- complexityMax (integer 1..10): Return only items with complexity <= this value
- createdAfter (string, ISO 8601): Return only items created after this timestamp
- createdBefore (string, ISO 8601): Return only items created before this timestamp
- roleChangedAfter (string, ISO 8601): Return only items whose role last changed after this timestamp
- roleChangedBefore (string, ISO 8601): Return only items whose role last changed before this timestamp
- orderBy (string: priority|oldest|newest, default "priority"): Ordering strategy —
  "priority" = HIGH first then complexity ascending (quick wins),
  "oldest" = createdAt ascending (FIFO / queue drain),
  "newest" = createdAt descending (recency-biased)
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
                    put(
                        "tags",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Comma-separated tag values; any-match semantics (item must have at least one)")
                            )
                        }
                    )
                    put(
                        "priority",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Filter by priority: high, medium, or low"))
                        }
                    )
                    put(
                        "type",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Filter by item type (exact match)"))
                        }
                    )
                    put(
                        "complexityMax",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Maximum complexity (1..10 inclusive); items above this value are excluded"))
                        }
                    )
                    put(
                        "createdAfter",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 timestamp — return only items created after this point"))
                        }
                    )
                    put(
                        "createdBefore",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 timestamp — return only items created before this point"))
                        }
                    )
                    put(
                        "roleChangedAfter",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("ISO 8601 timestamp — return only items whose role last changed after this point")
                            )
                        }
                    )
                    put(
                        "roleChangedBefore",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("ISO 8601 timestamp — return only items whose role last changed before this point")
                            )
                        }
                    )
                    put(
                        "orderBy",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Ordering strategy: \"priority\" (default, HIGH first then complexity ASC), " +
                                        "\"oldest\" (createdAt ASC), \"newest\" (createdAt DESC)"
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

        validateIdOrPrefix(params, "parentId", required = false)
        val limit = optionalInt(params, "limit")
        if (limit != null && (limit < 1 || limit > 20)) {
            throw ToolValidationException("limit must be between 1 and 20, got: $limit")
        }

        // New filter parameters — validate only when present
        val priorityStr = optionalString(params, "priority")
        if (priorityStr != null && Priority.fromString(priorityStr) == null) {
            throw ToolValidationException(
                "validation_error: priority must be one of high, medium, low — got: $priorityStr"
            )
        }

        val complexityMax = optionalInt(params, "complexityMax")
        if (complexityMax != null && (complexityMax < 1 || complexityMax > 10)) {
            throw ToolValidationException(
                "validation_error: complexityMax must be between 1 and 10 — got: $complexityMax"
            )
        }

        // Read the raw JsonPrimitive for tags so we can detect blank-string explicitly.
        // optionalString() collapses blank strings to null, which would silently bypass this check.
        val tagsRaw = (params as? JsonObject)?.get("tags") as? JsonPrimitive
        if (tagsRaw != null && tagsRaw.isString && tagsRaw.content.isBlank()) {
            throw ToolValidationException("validation_error: tags must be a non-blank string when provided")
        }

        // ISO 8601 timestamp validations — round-trip via Instant.parse
        for (field in listOf("createdAfter", "createdBefore", "roleChangedAfter", "roleChangedBefore")) {
            val raw = optionalString(params, field)
            if (raw != null) {
                try {
                    Instant.parse(raw)
                } catch (_: Exception) {
                    throw ToolValidationException(
                        "validation_error: $field must be a valid ISO 8601 timestamp — got: $raw"
                    )
                }
            }
        }

        val orderByStr = optionalString(params, "orderBy")
        if (orderByStr != null && NextItemOrder.fromString(orderByStr) == null) {
            throw ToolValidationException(
                "validation_error: orderBy must be one of priority, oldest, newest — got: $orderByStr"
            )
        }
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        // Parse legacy parameters
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

        // Parse new filter parameters
        val tags = optionalString(params, "tags")
        val parsedPriority = optionalString(params, "priority")?.let { Priority.fromString(it) }
        val type = optionalString(params, "type")
        val complexityMax = optionalInt(params, "complexityMax")
        val parsedCreatedAfter = optionalString(params, "createdAfter")?.let { Instant.parse(it) }
        val parsedCreatedBefore = optionalString(params, "createdBefore")?.let { Instant.parse(it) }
        val parsedRoleChangedAfter = optionalString(params, "roleChangedAfter")?.let { Instant.parse(it) }
        val parsedRoleChangedBefore = optionalString(params, "roleChangedBefore")?.let { Instant.parse(it) }
        val parsedOrderBy =
            NextItemOrder.fromString(optionalString(params, "orderBy"))
                ?: NextItemOrder.PRIORITY_THEN_COMPLEXITY

        // Build criteria and delegate to NextItemRecommender (standard path: active claims excluded)
        val criteria =
            NextItemRecommender.Criteria(
                role = targetRole,
                parentId = parentId,
                tags = tags?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() },
                priority = parsedPriority,
                type = type,
                complexityMax = complexityMax,
                createdAfter = parsedCreatedAfter,
                createdBefore = parsedCreatedBefore,
                roleChangedAfter = parsedRoleChangedAfter,
                roleChangedBefore = parsedRoleChangedBefore,
                orderBy = parsedOrderBy,
            )

        val workItemRepo = context.workItemRepository()

        // includeClaimed=false (default): recommender handles everything — active-claim exclusion
        // + dependency blocking + ordering. includeClaimed=true: fetch with claims included via
        // findForNextItem(excludeActiveClaims=false), then apply new filters in-memory and run
        // the same dependency-blocking walk. The new filters do NOT change the includeClaimed
        // disclosure contract — claimedBy/claimedAt are never exposed in either path.
        val recommendations: List<WorkItem> =
            if (!includeClaimed) {
                when (val result = context.nextItemRecommender.recommend(criteria, limit)) {
                    is Result.Success -> result.data
                    is Result.Error -> return errorResponse(result.error.message, ErrorCodes.DATABASE_ERROR)
                }
            } else {
                val dependencyRepo = context.dependencyRepository()
                val candidatesResult =
                    workItemRepo.findForNextItem(
                        role = targetRole,
                        parentId = parentId,
                        excludeActiveClaims = false,
                        limit = NextItemRecommender.OVER_FETCH_LIMIT
                    )

                val candidates =
                    when (candidatesResult) {
                        is Result.Success -> candidatesResult.data
                        is Result.Error -> return errorResponse(candidatesResult.error.message, ErrorCodes.DATABASE_ERROR)
                    }

                // Apply new filters in-memory for the includeClaimed=true path.
                // Tag matching mirrors the DB-side semantics in SQLiteWorkItemRepository.buildTagFilter:
                // exact-token match against comma-separated tags (NOT substring match).
                // Uses isNotBlank to reject whitespace-only tokens (consistent with ClaimItemTool's selector path).
                val filtered =
                    candidates.filter { item ->
                        (
                            criteria.tags == null ||
                                item.tags?.let { t ->
                                    val itemTokens =
                                        t.split(",").map { it.trim() }.filter { it.isNotBlank() }
                                    criteria.tags.any { tag -> tag in itemTokens }
                                } == true
                        ) &&
                            (criteria.priority == null || item.priority == criteria.priority) &&
                            (criteria.type == null || item.type == criteria.type) &&
                            (criteria.complexityMax == null || (item.complexity != null && item.complexity <= criteria.complexityMax)) &&
                            (criteria.createdAfter == null || !item.createdAt.isBefore(criteria.createdAfter)) &&
                            (criteria.createdBefore == null || !item.createdAt.isAfter(criteria.createdBefore)) &&
                            (criteria.roleChangedAfter == null || !item.roleChangedAt.isBefore(criteria.roleChangedAfter)) &&
                            (criteria.roleChangedBefore == null || !item.roleChangedAt.isAfter(criteria.roleChangedBefore))
                    }

                // Apply ordering
                val sorted =
                    when (parsedOrderBy) {
                        NextItemOrder.PRIORITY_THEN_COMPLEXITY ->
                            filtered.sortedWith(
                                compareBy<WorkItem> { PRIORITY_ORDER[it.priority] ?: 99 }.thenBy { it.complexity }
                            )
                        NextItemOrder.OLDEST_FIRST -> filtered.sortedBy { it.createdAt }
                        NextItemOrder.NEWEST_FIRST -> filtered.sortedByDescending { it.createdAt }
                    }

                // Filter dependency-blocked items — reuse the recommender's internal isBlocked
                // so this path and the default path can never diverge on dependency-walk semantics.
                // Early-exit walk: stop once we have `limit` unblocked items (Sequences cannot
                // call suspending isBlocked, so a manual loop with break is the simplest form).
                val unblocked = mutableListOf<WorkItem>()
                for (item in sorted) {
                    if (!context.nextItemRecommender.isBlocked(item)) {
                        unblocked.add(item)
                        if (unblocked.size >= limit) break
                    }
                }
                unblocked
            }

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
        val dbNowForClaimed: Instant? =
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
                                    val now = dbNowForClaimed ?: Instant.now()
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

    private companion object {
        /** In-memory priority ordering for the includeClaimed=true path (mirrors the DB CASE expression). */
        val PRIORITY_ORDER = mapOf(Priority.HIGH to 0, Priority.MEDIUM to 1, Priority.LOW to 2)
    }
}
