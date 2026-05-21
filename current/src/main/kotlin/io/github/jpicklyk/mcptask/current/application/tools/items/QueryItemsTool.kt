package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.service.search.FtsQuerySanitizer
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SearchMatchMode
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SearchScope
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

/** Valid values for the `claimStatus` filter parameter (search/list operation). */
private val VALID_CLAIM_STATUSES = setOf("claimed", "unclaimed", "expired")

/** Valid values for the `matchMode` FTS search parameter. */
private val VALID_MATCH_MODES = setOf("auto", "substring", "text")

/**
 * Read-only MCP tool for querying WorkItems.
 *
 * Supports three operations:
 * - **get**: Fetch a single WorkItem by ID (full JSON)
 * - **search**: Full-text FTS5 search returning ranked snippets; also supports list-style
 *   filter-based lookup when `query` is omitted.
 * - **overview**: Hierarchical summary view (scoped to an item, or global root items)
 *
 * The old LIKE-based `query` parameter has been removed. All text search goes through the
 * FTS5-backed `search` operation which uses the `query` field of the `search` operation.
 *
 * Tiered claim disclosure:
 * - **search** (list mode): `claimStatus` filter supported; `isClaimed: Boolean` added to each result when filter is used.
 *   `claimedBy` identity is NEVER included — use `get_context(itemId)` for full claim details.
 * - **overview**: `claimSummary: { active, expired, unclaimed }` added per root item (counts only).
 */
class QueryItemsTool : BaseToolDefinition() {
    override val name = "query_items"

    override val description =
        """
Unified read operations for work items.

Operations: get, search, overview

**get** - Retrieve a single item by ID or short prefix
- Required: itemId (UUID or hex prefix, minimum 4 characters)
- Full 36-char UUID: exact match (existing behavior)
- Short hex prefix (4-35 chars): resolves to unique item; errors if ambiguous or not found
- Returns full item JSON
- includeAncestors (boolean, default false): When true, each item includes an `ancestors` array showing the full path from root to direct parent

**search** - Two modes depending on whether `query` is provided:

  *FTS mode* (when `query` is set): Full-text search via FTS5 (RRF fusion of trigram + porter tokenizer tables).
  Returns ranked hits with ~32-token snippets. Use this to find items mentioning a concept,
  phrase, or identifier across all titles and summaries.
  - Required: query (string) — the search terms. Multiple words produce implicit AND.
    Special FTS5 characters are automatically escaped; you do not need to quote terms.
  - Optional: scope (object) — structural scope filter:
      - scope.ancestorId (UUID): Limit search to items in this item's subtree (descendants at any depth).
        Use this to scope a search to a feature or container. E.g. scope={ancestorId: "uuid-of-feature"}.
      - scope.itemId (UUID): Search only this single item's content.
      - scope.tags (string[]): Only include items that have at least one of these tags.
      - scope.role (string): Only include items in this role (queue/work/review/terminal/blocked).
  - Optional: matchMode (string, default "auto"):
      - "auto" — query both trigram and text tables, fuse via RRF (best coverage, recommended)
      - "substring" — trigram table only (substring/case-insensitive; requires ≥3-char token)
      - "text" — porter+unicode61 table only (stemming/natural language)
  - Optional: snippet (boolean, default true) — include ~32-token snippet with <mark>…</mark> highlights
  - Optional: explain (boolean, default false) — include raw FTS5 ranks (trigramRank, textRank, rrfK) per hit
  - Optional: limit (integer, default 20, max 100)
  - Optional: offset (integer, default 0)
  - Response: { hits: [...], totalHits, nextOffset, truncated }
    Each hit: { kind: "item", itemId, field ("title"|"summary"), snippet, score, matchedIn: ["trigram","text"],
                explain?: { trigramRank, textRank, rrfK } }
    score is the descending RRF fused value; higher = more relevant.
    Markdown formatting in snippets is preserved as-is.

  *List mode* (when `query` is omitted): Filter items by structured criteria.
  - Optional: parentId, depth, role, priority, tags, createdAfter/Before, modifiedAfter/Before,
              roleChangedAfter/Before, sortBy, sortOrder, limit, offset, claimStatus, includeAncestors
  - claimStatus: "claimed" (active live claim), "unclaimed" (never claimed), "expired" (TTL elapsed).
    When provided, a boolean `isClaimed` field is added to each result.
  - Returns minimal fields: id, parentId, title, role, priority, depth, tags
  - Note: claimedBy identity is NEVER included in search results (use get_context(itemId) for full details)
  - Wrapped in { items: [...], total: N, returned: N, limit: N, offset: N }
  - total is the full count of all matching rows (regardless of limit/offset)

**overview** - Hierarchical summary view
- With itemId: Item metadata + child counts by role + direct children list
- Without itemId: Global overview of all root items with per-root child counts and claimSummary { active, expired, unclaimed }
- claimSummary counts are per root-item's subtree (or global when no itemId)
- Default limit: 20 root items
- includeChildren (boolean, default false): When true (global overview only), each root item includes a `children` array of its direct child items
        """.trimIndent()

    override val category = ToolCategory.ITEM_MANAGEMENT

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
                        "operation",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Operation: get, search, overview"))
                            put("enum", JsonArray(listOf("get", "search", "overview").map { JsonPrimitive(it) }))
                        }
                    )
                    put(
                        "itemId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Item UUID or hex prefix (minimum 4 characters) for get operation; UUID for scoped overview")
                            )
                        }
                    )
                    put(
                        "parentId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Filter by parent ID (UUID or hex prefix 4+ chars)"))
                        }
                    )
                    put(
                        "depth",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Filter by depth level"))
                        }
                    )
                    put(
                        "role",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Filter by role: queue, work, review, blocked, terminal"))
                        }
                    )
                    put(
                        "priority",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Filter by priority: high, medium, low"))
                        }
                    )
                    put(
                        "tags",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Comma-separated tags filter (OR logic) — used in list mode only"))
                        }
                    )
                    // ── FTS search parameters (used when operation=search and query is set) ──
                    put(
                        "query",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Full-text search query (FTS5). When present, triggers FTS mode: returns ranked hits " +
                                        "with snippets from titles and summaries. Multiple words = implicit AND. " +
                                        "FTS5 special characters are automatically escaped — pass plain terms."
                                )
                            )
                        }
                    )
                    put(
                        "scope",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Structural scope filter for FTS mode. All fields are optional and combined with AND."
                                )
                            )
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "ancestorId",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("string"))
                                            put(
                                                "description",
                                                JsonPrimitive(
                                                    "When set, search only matches items within this item's subtree " +
                                                        "(descendants at any depth via recursive CTE). Use this to scope a search " +
                                                        "to a feature or container. E.g. scope.ancestorId = UUID of the feature root."
                                                )
                                            )
                                        }
                                    )
                                    put(
                                        "itemId",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("string"))
                                            put(
                                                "description",
                                                JsonPrimitive(
                                                    "When set, search only this single item's content (title + summary)."
                                                )
                                            )
                                        }
                                    )
                                    put(
                                        "tags",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("array"))
                                            put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                                            put(
                                                "description",
                                                JsonPrimitive(
                                                    "OR-match: only include items that have at least one of these tags."
                                                )
                                            )
                                        }
                                    )
                                    put(
                                        "role",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("string"))
                                            put(
                                                "description",
                                                JsonPrimitive(
                                                    "Only include items in this role: queue, work, review, terminal, blocked."
                                                )
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                    put(
                        "matchMode",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "FTS table selection: \"auto\" (default — both trigram + text tables fused via RRF, " +
                                        "best coverage), \"substring\" (trigram only, requires ≥3-char tokens), " +
                                        "\"text\" (porter+unicode61 only, stemming/natural language)."
                                )
                            )
                            put("enum", JsonArray(listOf("auto", "substring", "text").map { JsonPrimitive(it) }))
                        }
                    )
                    put(
                        "snippet",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "When true (default), each hit includes a ~32-token snippet with <mark>…</mark> " +
                                        "highlights. Markdown formatting in snippet bodies is preserved."
                                )
                            )
                        }
                    )
                    put(
                        "explain",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "When true, each hit includes an `explain` object with raw FTS5 component scores " +
                                        "(trigramRank, textRank, rrfK=60). Off by default — only enable when debugging ranking."
                                )
                            )
                        }
                    )
                    put(
                        "createdAfter",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 timestamp filter"))
                        }
                    )
                    put(
                        "createdBefore",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 timestamp filter"))
                        }
                    )
                    put(
                        "modifiedAfter",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 timestamp filter"))
                        }
                    )
                    put(
                        "modifiedBefore",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 timestamp filter"))
                        }
                    )
                    put(
                        "roleChangedAfter",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 timestamp — items whose role changed after this time"))
                        }
                    )
                    put(
                        "roleChangedBefore",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 timestamp — items whose role changed before this time"))
                        }
                    )
                    put(
                        "sortBy",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Sort field: title, priority, complexity, createdAt, modifiedAt"))
                        }
                    )
                    put(
                        "sortOrder",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Sort order: asc, desc (default: desc)"))
                        }
                    )
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Max results (default: 50)"))
                        }
                    )
                    put(
                        "offset",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Number of items to skip (for pagination). Use with limit. Default: 0."))
                        }
                    )
                    put(
                        "includeAncestors",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "When true, each item in search/get results includes an `ancestors` array (get and search operations only)"
                                )
                            )
                        }
                    )
                    put(
                        "includeChildren",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "When true, each root item in global overview includes a `children` array of direct child items (overview operation, global mode only)"
                                )
                            )
                        }
                    )
                    put(
                        "type",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Filter by type identifier (exact match)"))
                        }
                    )
                    put(
                        "claimStatus",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Filter by claim state (search operation only): \"claimed\" (active live claim), \"unclaimed\" (claimed_by IS NULL), or \"expired\" (claim placed but TTL elapsed). When used, each result includes a boolean isClaimed field. claimedBy identity is NEVER exposed in search results."
                                )
                            )
                            put("enum", JsonArray(listOf("claimed", "unclaimed", "expired").map { JsonPrimitive(it) }))
                        }
                    )
                },
            required = listOf("operation")
        )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")
        when (operation) {
            "get" -> validateIdOrPrefix(params, "itemId")
            "search" -> {
                val claimStatus = optionalString(params, "claimStatus")
                if (claimStatus != null && claimStatus !in VALID_CLAIM_STATUSES) {
                    throw ToolValidationException(
                        "Invalid claimStatus: \"$claimStatus\". Valid values: ${VALID_CLAIM_STATUSES.joinToString(", ") { "\"$it\"" }}"
                    )
                }
                val matchMode = optionalString(params, "matchMode")
                if (matchMode != null && matchMode.lowercase() !in VALID_MATCH_MODES) {
                    throw ToolValidationException(
                        "Invalid matchMode: \"$matchMode\". Valid values: ${VALID_MATCH_MODES.joinToString(", ") { "\"$it\"" }}"
                    )
                }
                val limitVal = optionalInt(params, "limit")
                if (limitVal != null && limitVal < 1) {
                    throw ToolValidationException("limit must be at least 1")
                }
                val offsetVal = optionalInt(params, "offset")
                if (offsetVal != null && offsetVal < 0) {
                    throw ToolValidationException("offset must be non-negative")
                }
            }
            "overview" -> { /* all parameters are optional */ }
            else -> throw ToolValidationException("Invalid operation: $operation. Must be one of: get, search, overview")
        }
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val operation = requireString(params, "operation")
        return when (operation) {
            "get" -> executeGet(params, context)
            "search" -> {
                // Route to FTS search when `query` is present; otherwise use list-filter mode.
                val query = optionalString(params, "query")
                if (query != null) {
                    executeFtsSearch(params, query, context)
                } else {
                    executeSearch(params, context)
                }
            }
            "overview" -> executeOverview(params, context)
            else ->
                errorResponse(
                    "Invalid operation: $operation",
                    ErrorCodes.VALIDATION_ERROR
                )
        }
    }

    override fun userSummary(
        params: JsonElement,
        result: JsonElement,
        isError: Boolean
    ): String {
        if (isError) {
            val msg =
                (result as? JsonObject)
                    ?.get("error")
                    ?.let { (it as? JsonObject)?.get("message") }
                    ?.let { (it as? JsonPrimitive)?.content }
            return if (msg != null) "Query failed: $msg" else "Query failed"
        }

        val paramsObj = params as? JsonObject
        val operation =
            paramsObj?.get("operation")?.let {
                if (it is JsonPrimitive && it.isString) it.content else null
            } ?: return "Query completed"

        val data = (result as? JsonObject)?.get("data") as? JsonObject

        return when (operation) {
            "get" -> {
                val title =
                    data?.get("title")?.let {
                        if (it is JsonPrimitive && it.isString) it.content else null
                    } ?: "unknown"
                val id =
                    data?.get("id")?.let {
                        if (it is JsonPrimitive && it.isString) it.content.take(8) else null
                    } ?: "?"
                "Item: $title ($id)"
            }
            "search" -> {
                // FTS mode response has `hits`; list mode has `total`.
                val hits = data?.get("hits")
                if (hits != null) {
                    val totalHits = data.get("totalHits")?.let { if (it is JsonPrimitive) it.intOrNull else null } ?: 0
                    val returned = (hits as? JsonArray)?.size ?: 0
                    "Found $totalHits hit(s), returned $returned"
                } else {
                    val total = data?.get("total")?.let { if (it is JsonPrimitive) it.intOrNull else null } ?: 0
                    "Found $total item(s)"
                }
            }
            "overview" -> {
                val title =
                    data?.get("item")?.let { itemObj ->
                        (itemObj as? JsonObject)?.get("title")?.let {
                            if (it is JsonPrimitive && it.isString) it.content else null
                        }
                    }
                if (title != null) {
                    val children =
                        data?.get("children")?.let {
                            (it as? JsonArray)?.size
                        } ?: 0
                    "Overview: $title -- $children children"
                } else {
                    val total =
                        data?.get("total")?.let {
                            if (it is JsonPrimitive) it.intOrNull else null
                        } ?: 0
                    "Overview: root items -- $total items"
                }
            }
            else -> "Query completed"
        }
    }

    // ──────────────────────────────────────────────
    // Operation: get
    // ──────────────────────────────────────────────

    private suspend fun executeGet(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (resolvedId, idError) = resolveItemId(params, "itemId", context)
        if (idError != null) return idError
        val itemId = resolvedId!!
        val includeAncestors = optionalBoolean(params, "includeAncestors", false)

        val item =
            when (val result = context.workItemRepository().getById(itemId)) {
                is Result.Success -> result.data
                is Result.Error -> return errorResponse(
                    "WorkItem not found",
                    ErrorCodes.RESOURCE_NOT_FOUND,
                    additionalData =
                        buildJsonObject {
                            put("requestedId", JsonPrimitive(itemId.toString()))
                        }
                )
            }

        val itemJson = item.toFullJson()

        return if (includeAncestors) {
            val chains = context.workItemRepository().findAncestorChains(setOf(item.id))
            val ancestors = (chains as? Result.Success)?.data?.get(item.id) ?: emptyList()
            val enriched =
                buildJsonObject {
                    itemJson.forEach { (k, v) -> put(k, v) }
                    put("ancestors", buildAncestorsArray(ancestors))
                }
            successResponse(enriched)
        } else {
            successResponse(itemJson)
        }
    }

    // ──────────────────────────────────────────────
    // Operation: search (FTS mode — `query` is set)
    // ──────────────────────────────────────────────

    /**
     * Full-text search over work-item titles and summaries via FTS5.
     *
     * Sanitizes the user query, delegates to [SQLiteWorkItemRepository.ftsSearch], and
     * serializes the [SearchResult] into the response shape defined in plan §7:
     * `{ hits: [...], totalHits, nextOffset, truncated }`.
     *
     * Each hit includes `kind`, `itemId`, `field`, `snippet`, `score`, `matchedIn`, and
     * optionally `explain` (raw FTS5 ranks, only when `explain=true`).
     *
     * `totalHits` is based on the in-memory RRF-fused list (all rows matched and fetched,
     * then paginated). The repo fetches `effectiveLimit + offset + 1` rows per FTS table,
     * so for very large result sets the true DB total may exceed `totalHits`. This trade-off
     * is documented here and acceptable for the 25k-token response cap: the hard cap at 100
     * rows (repo-level) means `totalHits` is always ≤ the actual match count but never
     * exceeds the 100 hard cap. When `truncated=true`, the caller should refine the query.
     *
     * @param rawQuery The user-supplied (unsanitized) search string from the `query` param.
     */
    private suspend fun executeFtsSearch(
        params: JsonElement,
        rawQuery: String,
        context: ToolExecutionContext
    ): JsonElement {
        val matchModeStr = optionalString(params, "matchMode")?.lowercase() ?: "auto"
        val includeSnippet = optionalBoolean(params, "snippet", true)
        val includeExplain = optionalBoolean(params, "explain", false)
        val limit = (optionalInt(params, "limit") ?: 20).coerceIn(1, 100)
        val offset = (optionalInt(params, "offset") ?: 0).coerceAtLeast(0)

        // Parse optional scope object
        val scopeJson = (params as? JsonObject)?.get("scope") as? JsonObject
        val scope: SearchScope? =
            if (scopeJson != null) {
                val ancestorIdStr = scopeJson["ancestorId"]?.jsonPrimitive?.contentOrNull
                val itemIdStr = scopeJson["itemId"]?.jsonPrimitive?.contentOrNull
                val scopeTagsArr = scopeJson["tags"]?.jsonArray
                val scopeRoleStr = scopeJson["role"]?.jsonPrimitive?.contentOrNull

                val ancestorId: UUID? =
                    if (ancestorIdStr != null) {
                        runCatching { UUID.fromString(ancestorIdStr) }.getOrElse {
                            return errorResponse("Invalid scope.ancestorId UUID: $ancestorIdStr", ErrorCodes.VALIDATION_ERROR)
                        }
                    } else {
                        null
                    }
                val scopeItemId: UUID? =
                    if (itemIdStr != null) {
                        runCatching { UUID.fromString(itemIdStr) }.getOrElse {
                            return errorResponse("Invalid scope.itemId UUID: $itemIdStr", ErrorCodes.VALIDATION_ERROR)
                        }
                    } else {
                        null
                    }
                val scopeTags = scopeTagsArr?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { it.isNotEmpty() }
                val scopeRole =
                    if (scopeRoleStr != null) {
                        Role.fromString(scopeRoleStr)
                            ?: return errorResponse(
                                "Invalid scope.role: $scopeRoleStr. Valid roles: ${Role.VALID_NAMES.joinToString()}",
                                ErrorCodes.VALIDATION_ERROR
                            )
                    } else {
                        null
                    }
                SearchScope(itemId = scopeItemId, ancestorId = ancestorId, tags = scopeTags, role = scopeRole)
            } else {
                null
            }

        // Map string → enum
        val matchMode =
            when (matchModeStr) {
                "substring" -> SearchMatchMode.SUBSTRING
                "text" -> SearchMatchMode.TEXT
                else -> SearchMatchMode.AUTO
            }

        // Sanitize the query, with trigram-specific validation when relevant.
        val sanitizedQuery: String =
            try {
                when (matchMode) {
                    SearchMatchMode.SUBSTRING ->
                        FtsQuerySanitizer.sanitizeForTrigram(rawQuery)
                            ?: return errorResponse(
                                "Search query is empty. Provide at least one search term.",
                                ErrorCodes.VALIDATION_ERROR
                            )
                    SearchMatchMode.AUTO, SearchMatchMode.TEXT ->
                        FtsQuerySanitizer.sanitize(rawQuery)
                            ?: return errorResponse(
                                "Search query is empty. Provide at least one search term.",
                                ErrorCodes.VALIDATION_ERROR
                            )
                }
            } catch (e: IllegalArgumentException) {
                return errorResponse(e.message ?: "Invalid search query", ErrorCodes.VALIDATION_ERROR)
            }

        // Delegate to repository
        val repo = context.workItemRepository()
        val searchResult =
            if (repo is SQLiteWorkItemRepository) {
                try {
                    repo.ftsSearch(
                        sanitizedFtsQuery = sanitizedQuery,
                        matchMode = matchMode,
                        scope = scope,
                        limit = limit,
                        offset = offset,
                    )
                } catch (e: Exception) {
                    return errorResponse(
                        "FTS5 search failed: ${e.message}",
                        ErrorCodes.INTERNAL_ERROR
                    )
                }
            } else {
                // Non-SQLite environment (H2 tests): return empty result
                io.github.jpicklyk.mcptask.current.infrastructure.repository.SearchResult(
                    hits = emptyList(),
                    totalHits = 0,
                    nextOffset = null,
                )
            }

        // Serialize hits
        val hitsArray =
            JsonArray(
                searchResult.hits.map { hit ->
                    buildJsonObject {
                        put("kind", JsonPrimitive(hit.kind))
                        put("itemId", JsonPrimitive(hit.itemId.toString()))
                        put("field", JsonPrimitive(hit.field))
                        if (includeSnippet) {
                            put("snippet", JsonPrimitive(hit.snippet))
                        }
                        put("score", JsonPrimitive(hit.score))
                        put(
                            "matchedIn",
                            JsonArray(hit.matchedIn.map { JsonPrimitive(it) })
                        )
                        if (includeExplain) {
                            put(
                                "explain",
                                buildJsonObject {
                                    put("trigramRank", if (hit.trigramRank != null) JsonPrimitive(hit.trigramRank) else JsonNull)
                                    put("textRank", if (hit.textRank != null) JsonPrimitive(hit.textRank) else JsonNull)
                                    put("rrfK", JsonPrimitive(60))
                                }
                            )
                        }
                    }
                }
            )

        val data =
            buildJsonObject {
                put("hits", hitsArray)
                put("totalHits", JsonPrimitive(searchResult.totalHits))
                put(
                    "nextOffset",
                    if (searchResult.nextOffset != null) JsonPrimitive(searchResult.nextOffset) else JsonNull
                )
                put("truncated", JsonPrimitive(searchResult.truncated))
            }

        return successResponse(data)
    }

    // ──────────────────────────────────────────────
    // Operation: search (list mode — `query` is absent)
    // ──────────────────────────────────────────────

    private suspend fun executeSearch(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (parentId, parentIdError) = resolveItemId(params, "parentId", context, required = false)
        if (parentIdError != null) return parentIdError
        val depth = optionalInt(params, "depth")
        val roleStr = optionalString(params, "role")
        val priorityStr = optionalString(params, "priority")
        val tagsStr = optionalString(params, "tags")
        // NOTE: The old LIKE-based `query` filter is removed. FTS search is via executeFtsSearch.
        val createdAfter = parseInstant(params, "createdAfter")
        val createdBefore = parseInstant(params, "createdBefore")
        val modifiedAfter = parseInstant(params, "modifiedAfter")
        val modifiedBefore = parseInstant(params, "modifiedBefore")
        val roleChangedAfter = parseInstant(params, "roleChangedAfter")
        val roleChangedBefore = parseInstant(params, "roleChangedBefore")
        val typeFilter = optionalString(params, "type")
        val sortBy = optionalString(params, "sortBy")
        val sortOrder = optionalString(params, "sortOrder")
        // Cap list-mode limit at 100 to match FTS-mode behavior and bound payload size.
        val limit = (optionalInt(params, "limit") ?: 50).coerceIn(1, 100)
        val offset = (optionalInt(params, "offset") ?: 0).coerceAtLeast(0)
        val includeAncestors = optionalBoolean(params, "includeAncestors", false)
        // claimStatus filter — validated in validateParams; safe to use directly here
        val claimStatusFilter = optionalString(params, "claimStatus")

        // Validate time ranges — reject inverted ranges early
        if (createdAfter != null && createdBefore != null && createdAfter > createdBefore) {
            return errorResponse("createdAfter must be before createdBefore", ErrorCodes.VALIDATION_ERROR)
        }
        if (modifiedAfter != null && modifiedBefore != null && modifiedAfter > modifiedBefore) {
            return errorResponse("modifiedAfter must be before modifiedBefore", ErrorCodes.VALIDATION_ERROR)
        }
        if (roleChangedAfter != null && roleChangedBefore != null && roleChangedAfter > roleChangedBefore) {
            return errorResponse("roleChangedAfter must be before roleChangedBefore", ErrorCodes.VALIDATION_ERROR)
        }

        // Parse role
        val role =
            roleStr?.let {
                Role.fromString(it)
                    ?: return errorResponse(
                        "Invalid role: $it. Valid roles: ${Role.VALID_NAMES.joinToString()}",
                        ErrorCodes.VALIDATION_ERROR
                    )
            }

        // Parse priority
        val priority =
            priorityStr?.let {
                Priority.fromString(it)
                    ?: return errorResponse("Invalid priority: $it. Valid values: high, medium, low", ErrorCodes.VALIDATION_ERROR)
            }

        // Parse tags
        val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

        // Get full match count (no limit/offset) for accurate pagination metadata
        val totalCount =
            when (
                val countResult =
                    context.workItemRepository().countByFilters(
                        parentId = parentId,
                        depth = depth,
                        role = role,
                        priority = priority,
                        tags = tags,
                        query = null, // LIKE-based query removed; FTS goes through executeFtsSearch
                        createdAfter = createdAfter,
                        createdBefore = createdBefore,
                        modifiedAfter = modifiedAfter,
                        modifiedBefore = modifiedBefore,
                        roleChangedAfter = roleChangedAfter,
                        roleChangedBefore = roleChangedBefore,
                        type = typeFilter,
                        claimStatus = claimStatusFilter
                    )
            ) {
                is Result.Success -> countResult.data
                is Result.Error -> return errorResponse(countResult.error.message, ErrorCodes.DATABASE_ERROR)
            }

        return when (
            val result =
                context.workItemRepository().findByFilters(
                    parentId = parentId,
                    depth = depth,
                    role = role,
                    priority = priority,
                    tags = tags,
                    query = null, // LIKE-based query removed; FTS goes through executeFtsSearch
                    createdAfter = createdAfter,
                    createdBefore = createdBefore,
                    modifiedAfter = modifiedAfter,
                    modifiedBefore = modifiedBefore,
                    roleChangedAfter = roleChangedAfter,
                    roleChangedBefore = roleChangedBefore,
                    sortBy = sortBy,
                    sortOrder = sortOrder,
                    limit = limit,
                    offset = offset,
                    type = typeFilter,
                    claimStatus = claimStatusFilter
                )
        ) {
            is Result.Success -> {
                val items = result.data

                val chains: Map<java.util.UUID, List<WorkItem>> =
                    if (includeAncestors && items.isNotEmpty()) {
                        val chainResult = context.workItemRepository().findAncestorChains(items.map { it.id }.toSet())
                        (chainResult as? Result.Success)?.data ?: emptyMap()
                    } else {
                        emptyMap()
                    }

                val data =
                    buildJsonObject {
                        put(
                            "items",
                            JsonArray(
                                items.map { item ->
                                    buildSearchResultItem(item, claimStatusFilter, includeAncestors, chains)
                                }
                            )
                        )
                        put("total", JsonPrimitive(totalCount))
                        put("returned", JsonPrimitive(items.size))
                        put("limit", JsonPrimitive(limit))
                        put("offset", JsonPrimitive(offset))
                    }
                successResponse(data)
            }
            is Result.Error ->
                errorResponse(
                    result.error.message,
                    ErrorCodes.DATABASE_ERROR
                )
        }
    }

    /**
     * Build a single search-result item JSON object.
     *
     * **Tiered claim disclosure:** `claimedBy` is NEVER included in search results (even if the item
     * has a claim). When a `claimStatus` filter was applied, a boolean `isClaimed` field is added to
     * indicate whether the item currently has an active (non-expired) claim. This mirrors the pattern
     * used by [GetNextItemTool.includeClaimed].
     */
    private fun buildSearchResultItem(
        item: WorkItem,
        claimStatusFilter: String?,
        includeAncestors: Boolean,
        chains: Map<java.util.UUID, List<WorkItem>>
    ): JsonObject {
        val minimalJson = item.toMinimalJson()
        return buildJsonObject {
            minimalJson.forEach { (k, v) -> put(k, v) }
            // Tiered disclosure: add isClaimed boolean when claimStatus filter was used.
            // NEVER expose claimedBy identity here — that belongs only in get_context(itemId).
            if (claimStatusFilter != null) {
                val activelyClaimedNow =
                    item.claimedBy != null &&
                        item.claimExpiresAt?.isAfter(Instant.now()) == true
                put("isClaimed", JsonPrimitive(activelyClaimedNow))
            }
            if (includeAncestors) {
                val ancestors = chains[item.id] ?: emptyList()
                put("ancestors", buildAncestorsArray(ancestors))
            }
        }
    }

    // ──────────────────────────────────────────────
    // Operation: overview
    // ──────────────────────────────────────────────

    private suspend fun executeOverview(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (itemId, itemIdError) = resolveItemId(params, "itemId", context, required = false)
        if (itemIdError != null) return itemIdError

        return if (itemId != null) {
            executeScopedOverview(itemId, context)
        } else {
            executeGlobalOverview(params, context)
        }
    }

    private suspend fun executeScopedOverview(
        itemId: java.util.UUID,
        context: ToolExecutionContext
    ): JsonElement {
        // Fetch the item itself
        val item =
            when (val result = context.workItemRepository().getById(itemId)) {
                is Result.Success -> result.data
                is Result.Error -> return errorResponse(
                    result.error.message,
                    ErrorCodes.RESOURCE_NOT_FOUND
                )
            }

        // Count children by role
        val childCounts =
            when (val result = context.workItemRepository().countChildrenByRole(itemId)) {
                is Result.Success -> result.data
                is Result.Error -> return errorResponse(
                    result.error.message,
                    ErrorCodes.DATABASE_ERROR
                )
            }

        // Fetch direct children
        val children =
            when (val result = context.workItemRepository().findChildren(itemId)) {
                is Result.Success -> result.data
                is Result.Error -> return errorResponse(
                    result.error.message,
                    ErrorCodes.DATABASE_ERROR
                )
            }

        val data =
            buildJsonObject {
                put("item", item.toFullJson())
                put("childCounts", roleCountToJson(childCounts))
                put("children", JsonArray(children.map { enrichChildJson(it, context) }))
            }

        return successResponse(data)
    }

    private suspend fun executeGlobalOverview(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val limit = optionalInt(params, "limit") ?: 20
        val includeChildren = params.jsonObject["includeChildren"]?.jsonPrimitive?.booleanOrNull ?: false

        // Fetch root items
        val rootItems =
            when (val result = context.workItemRepository().findRootItems(limit)) {
                is Result.Success -> result.data
                is Result.Error -> return errorResponse(
                    result.error.message,
                    ErrorCodes.DATABASE_ERROR
                )
            }

        // For each root item, get child counts by role, claim summary, and optionally children
        val itemsWithCounts =
            rootItems.map { item ->
                val childCounts =
                    when (val result = context.workItemRepository().countChildrenByRole(item.id)) {
                        is Result.Success -> result.data
                        is Result.Error -> emptyMap()
                    }

                // Claim summary: scoped to this root item's direct children.
                // (For a true subtree count, countByClaimStatus with parentId gives direct children only.)
                val claimCounts =
                    when (val result = context.workItemRepository().countByClaimStatus(parentId = item.id)) {
                        is Result.Success -> result.data
                        is Result.Error -> null
                    }

                buildJsonObject {
                    item.toMinimalJson().forEach { (k, v) -> put(k, v) }
                    val rootTraits = PropertiesHelper.extractTraits(item.properties)
                    if (rootTraits.isNotEmpty()) {
                        put("traits", JsonArray(rootTraits.map { JsonPrimitive(it) }))
                    }
                    put("childCounts", roleCountToJson(childCounts))
                    // claimSummary: counts for children of this root item (omit if query failed)
                    if (claimCounts != null) {
                        put(
                            "claimSummary",
                            buildJsonObject {
                                put("active", JsonPrimitive(claimCounts.active))
                                put("expired", JsonPrimitive(claimCounts.expired))
                                put("unclaimed", JsonPrimitive(claimCounts.unclaimed))
                            }
                        )
                    }
                    if (includeChildren) {
                        val children =
                            when (val result = context.workItemRepository().findChildren(item.id)) {
                                is Result.Success -> result.data
                                is Result.Error -> emptyList()
                            }
                        put("children", JsonArray(children.map { enrichChildJson(it, context) }))
                    }
                }
            }

        val data =
            buildJsonObject {
                put("items", JsonArray(itemsWithCounts))
                put("total", JsonPrimitive(rootItems.size))
            }

        return successResponse(data)
    }

    /**
     * Enriches a child WorkItem with childCounts and traits for overview responses.
     * Shared by both scoped and global overview to keep child rendering consistent.
     */
    private suspend fun enrichChildJson(
        child: WorkItem,
        context: ToolExecutionContext
    ): JsonObject {
        val grandchildCounts =
            when (val result = context.workItemRepository().countChildrenByRole(child.id)) {
                is Result.Success -> result.data
                is Result.Error -> emptyMap()
            }
        val childTraits = PropertiesHelper.extractTraits(child.properties)
        return buildJsonObject {
            child.toMinimalJson().forEach { (k, v) -> put(k, v) }
            put("childCounts", roleCountToJson(grandchildCounts))
            if (childTraits.isNotEmpty()) {
                put("traits", JsonArray(childTraits.map { JsonPrimitive(it) }))
            }
        }
    }
}
