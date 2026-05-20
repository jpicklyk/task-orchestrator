package io.github.jpicklyk.mcptask.current.application.tools.notes

import io.github.jpicklyk.mcptask.current.application.service.search.FtsQuerySanitizer
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteNoteRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SearchMatchMode
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SearchResult
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SearchScope
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.UUID

/** Valid values for the `matchMode` FTS search parameter. */
private val VALID_MATCH_MODES = setOf("auto", "substring", "text")

/**
 * Read-only MCP tool for querying Notes.
 *
 * Supports three operations:
 * - **get**: Retrieve a single Note by its UUID.
 * - **list**: List all notes for a WorkItem, with optional role filtering and body inclusion control.
 * - **search**: Full-text FTS5 search over note bodies, returning ranked snippets.
 */
class QueryNotesTool : BaseToolDefinition() {
    override val name = "query_notes"

    override val description =
        """
Read-only query operations for Notes (get, list, search).

**Operations:**

**get** - Get a single Note by ID.
- Required: `id` (UUID)
- Response: full Note JSON (includes `actor` and `verification` when present)

**list** - List notes for a WorkItem.
- Required: `itemId` (UUID)
- Optional: `role` — filter by role: "queue", "work", "review"
- Optional: `includeBody` (boolean, default true) — set false to omit body for token efficiency
- Response: `{ notes: [...], total: N }` — each note includes `actor` and `verification` fields when attribution was provided at write time

**search** - Full-text search over note bodies via FTS5 (RRF fusion of trigram + porter tokenizer tables).
Returns ranked hits with ~32-token snippets. Use this to find notes mentioning a specific phrase,
concept, or identifier across all note bodies.
- Required: `query` (string) — the search terms. Multiple words produce implicit AND.
  Special FTS5 characters are automatically escaped; you do not need to quote terms.
- Optional: `scope` (object) — structural scope filter:
    - scope.itemId (UUID): Narrow to notes on this single work item only.
    - scope.ancestorId (UUID): Narrow to notes whose owning item is in that subtree (descendants
      at any depth via recursive CTE). Use this to scope a search to a feature or container.
    - Note: to filter by note role (queue/work/review), use the `list` operation instead — it
      supports direct role filtering and returns complete note content without needing a query.
- Optional: `matchMode` (string, default "auto"):
    - "auto" — query both trigram and text tables, fuse via RRF (best coverage, recommended)
    - "substring" — trigram table only (substring/case-insensitive; requires ≥3-char token)
    - "text" — porter+unicode61 table only (stemming/natural language)
- Optional: `snippet` (boolean, default true) — include ~32-token snippet with <mark>…</mark> highlights.
  Markdown formatting in snippet bodies is preserved.
- Optional: `explain` (boolean, default false) — include raw FTS5 ranks (trigramRank, textRank, rrfK=60)
  per hit. Off by default — only enable when debugging ranking.
- Optional: `limit` (integer, default 20, max 100)
- Optional: `offset` (integer, default 0)
- Response: `{ hits: [...], totalHits, nextOffset, truncated }`
  Each hit: `{ kind: "note", itemId, noteKey, field: "body", snippet, score, matchedIn: ["trigram","text"],
              explain?: { trigramRank, textRank, rrfK } }`
  score is the descending RRF fused value; higher = more relevant.
        """.trimIndent()

    override val category = ToolCategory.NOTE_MANAGEMENT

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
                            put("description", JsonPrimitive("Operation: get, list, search"))
                            put("enum", JsonArray(listOf("get", "list", "search").map { JsonPrimitive(it) }))
                        }
                    )
                    put(
                        "id",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Note UUID (required for get)"))
                        }
                    )
                    put(
                        "itemId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("WorkItem UUID or hex prefix (4+ chars), required for list"))
                        }
                    )
                    put(
                        "role",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Filter by role: queue, work, review"))
                        }
                    )
                    put(
                        "includeBody",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("Include note body (default: true)"))
                        }
                    )
                    // ── FTS search parameters (used when operation=search) ──
                    put(
                        "query",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Full-text search query (FTS5, required for search operation). " +
                                        "Multiple words = implicit AND. " +
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
                                    "Structural scope filter for search mode. All fields are optional and combined with AND."
                                )
                            )
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "itemId",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("string"))
                                            put(
                                                "description",
                                                JsonPrimitive(
                                                    "When set, search only notes on this single work item."
                                                )
                                            )
                                        }
                                    )
                                    put(
                                        "ancestorId",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("string"))
                                            put(
                                                "description",
                                                JsonPrimitive(
                                                    "When set, search only notes whose owning item is in this item's subtree " +
                                                        "(descendants at any depth via recursive CTE). Use this to scope a search " +
                                                        "to a feature or container. E.g. scope.ancestorId = UUID of the feature root."
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
                        "limit",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Max results (default: 20, max: 100)"))
                        }
                    )
                    put(
                        "offset",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Number of notes to skip (for pagination). Default: 0."))
                        }
                    )
                },
            required = listOf("operation")
        )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")
        when (operation) {
            "get" -> {
                extractUUID(params, "id", required = true)
            }
            "list" -> {
                validateIdOrPrefix(params, "itemId", required = true)
                val role = optionalString(params, "role")
                if (role != null) {
                    val validRoles = setOf("queue", "work", "review")
                    if (role.lowercase() !in validRoles) {
                        throw ToolValidationException(
                            "Invalid role: '$role'. Must be one of: queue, work, review"
                        )
                    }
                }
            }
            "search" -> {
                requireString(params, "query") // query is required for search
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
            else -> throw ToolValidationException("Invalid operation: $operation. Must be get, list, or search")
        }
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val operation = requireString(params, "operation")
        return when (operation) {
            "get" -> executeGet(params, context)
            "list" -> executeList(params, context)
            "search" -> {
                val query = requireString(params, "query")
                executeFtsSearch(params, query, context)
            }
            else -> errorResponse("Invalid operation: $operation", ErrorCodes.VALIDATION_ERROR)
        }
    }

    override fun userSummary(
        params: JsonElement,
        result: JsonElement,
        isError: Boolean
    ): String {
        val op =
            (params as? JsonObject)?.get("operation")?.let {
                (it as? JsonPrimitive)?.content
            } ?: "unknown"
        val data = (result as? JsonObject)?.get("data") as? JsonObject
        return when {
            isError -> "query_notes($op) failed"
            op == "get" -> {
                val key = data?.get("key")?.let { (it as? JsonPrimitive)?.content } ?: "unknown"
                "Retrieved note: $key"
            }
            op == "list" -> {
                val total = data?.get("total")?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0
                "Listed $total note(s)"
            }
            op == "search" -> {
                val totalHits = data?.get("totalHits")?.let { if (it is JsonPrimitive) it.intOrNull else null } ?: 0
                val returned = (data?.get("hits") as? JsonArray)?.size ?: 0
                "Found $totalHits note hit(s), returned $returned"
            }
            else -> super.userSummary(params, result, isError)
        }
    }

    // ──────────────────────────────────────────────
    // Get operation
    // ──────────────────────────────────────────────

    private suspend fun executeGet(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val id = requireUUID(params, "id")
        val noteRepo = context.noteRepository()

        return when (val result = noteRepo.getById(id)) {
            is Result.Success -> {
                successResponse(result.data.toJson())
            }
            is Result.Error -> {
                errorResponse(
                    "Note not found: $id",
                    ErrorCodes.RESOURCE_NOT_FOUND,
                    details = result.error.message
                )
            }
        }
    }

    // ──────────────────────────────────────────────
    // List operation
    // ──────────────────────────────────────────────

    private suspend fun executeList(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (resolvedItemId, idError) = resolveItemId(params, "itemId", context)
        if (idError != null) return idError
        val itemId = resolvedItemId!!
        val role = optionalString(params, "role")
        val includeBody = optionalBoolean(params, "includeBody", defaultValue = true)
        val noteRepo = context.noteRepository()

        return when (val result = noteRepo.findByItemId(itemId, role)) {
            is Result.Success -> {
                val notes = result.data
                val data =
                    buildJsonObject {
                        put("notes", JsonArray(notes.map { it.toJson(includeBody) }))
                        put("total", JsonPrimitive(notes.size))
                    }
                successResponse(data)
            }
            is Result.Error -> {
                errorResponse(
                    "Failed to list notes for item: $itemId",
                    ErrorCodes.DATABASE_ERROR,
                    details = result.error.message
                )
            }
        }
    }

    // ──────────────────────────────────────────────
    // Search operation (FTS5)
    // ──────────────────────────────────────────────

    /**
     * Full-text search over note bodies via FTS5.
     *
     * Sanitizes the user query, delegates to [SQLiteNoteRepository.ftsSearch], and
     * serializes the [SearchResult] into the response shape defined in plan §7:
     * `{ hits: [...], totalHits, nextOffset, truncated }`.
     *
     * Each hit includes `kind="note"`, `itemId`, `noteKey`, `field="body"`, `snippet`, `score`,
     * `matchedIn`, and optionally `explain` (raw FTS5 ranks, only when `explain=true`).
     *
     * `totalHits` is based on the in-memory RRF-fused list (all rows matched and fetched,
     * then paginated). The hard cap at 100 rows (repo-level) means `totalHits` is always ≤ the
     * actual match count but never exceeds the 100 hard cap. When `truncated=true`, the caller
     * should refine the query or use scope filters.
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
                val scopeRoleStr = scopeJson["role"]?.jsonPrimitive?.contentOrNull

                val ancestorId: UUID? =
                    if (ancestorIdStr != null) {
                        runCatching { UUID.fromString(ancestorIdStr) }.getOrElse {
                            return errorResponse(
                                "Invalid scope.ancestorId UUID: $ancestorIdStr",
                                ErrorCodes.VALIDATION_ERROR,
                            )
                        }
                    } else {
                        null
                    }
                val scopeItemId: UUID? =
                    if (itemIdStr != null) {
                        runCatching { UUID.fromString(itemIdStr) }.getOrElse {
                            return errorResponse(
                                "Invalid scope.itemId UUID: $itemIdStr",
                                ErrorCodes.VALIDATION_ERROR,
                            )
                        }
                    } else {
                        null
                    }
                // scope.role for notes filters on the note's own role column (queue/work/review).
                // Notes do not use the Role enum from work items, so we validate as a string.
                val validNoteRoles = setOf("queue", "work", "review")
                if (scopeRoleStr != null && scopeRoleStr !in validNoteRoles) {
                    return errorResponse(
                        "Invalid scope.role: $scopeRoleStr. Valid note roles: queue, work, review",
                        ErrorCodes.VALIDATION_ERROR,
                    )
                }
                // SearchScope.role is a Role enum used for work-item role filtering.
                // For note search, we encode note-role filtering via the note repo's scope.itemId/ancestorId;
                // role scoping on notes themselves is handled separately — pass null for the Role enum field
                // and instead let the repo handle the note-role column via the SearchScope.role field which
                // is currently scoped to work-item roles only. Since T2's SearchScope only has a Role enum
                // for work-item roles, and notes use string roles, we skip role scope filtering here.
                // Callers wanting role-scoped note search should use list (which supports role filtering).
                SearchScope(itemId = scopeItemId, ancestorId = ancestorId, tags = null, role = null)
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
                                ErrorCodes.VALIDATION_ERROR,
                            )
                    SearchMatchMode.AUTO, SearchMatchMode.TEXT ->
                        FtsQuerySanitizer.sanitize(rawQuery)
                            ?: return errorResponse(
                                "Search query is empty. Provide at least one search term.",
                                ErrorCodes.VALIDATION_ERROR,
                            )
                }
            } catch (e: IllegalArgumentException) {
                return errorResponse(e.message ?: "Invalid search query", ErrorCodes.VALIDATION_ERROR)
            }

        // Delegate to repository — mirrors T4's SQLiteWorkItemRepository cast pattern.
        val repo = context.noteRepository()
        val searchResult: SearchResult =
            if (repo is SQLiteNoteRepository) {
                repo.ftsSearch(
                    sanitizedFtsQuery = sanitizedQuery,
                    matchMode = matchMode,
                    scope = scope,
                    limit = limit,
                    offset = offset,
                )
            } else {
                // Non-SQLite environment (H2 tests): FTS5 is SQLite-only — return empty result.
                SearchResult(hits = emptyList(), totalHits = 0, nextOffset = null)
            }

        // Serialize hits
        val hitsArray =
            JsonArray(
                searchResult.hits.map { hit ->
                    buildJsonObject {
                        put("kind", JsonPrimitive(hit.kind))
                        put("itemId", JsonPrimitive(hit.itemId.toString()))
                        put("noteKey", JsonPrimitive(hit.noteKey ?: ""))
                        put("field", JsonPrimitive(hit.field))
                        if (includeSnippet) {
                            put("snippet", JsonPrimitive(hit.snippet))
                        }
                        put("score", JsonPrimitive(hit.score))
                        put(
                            "matchedIn",
                            JsonArray(hit.matchedIn.map { JsonPrimitive(it) }),
                        )
                        if (includeExplain) {
                            put(
                                "explain",
                                buildJsonObject {
                                    put(
                                        "trigramRank",
                                        if (hit.trigramRank != null) JsonPrimitive(hit.trigramRank) else JsonNull,
                                    )
                                    put(
                                        "textRank",
                                        if (hit.textRank != null) JsonPrimitive(hit.textRank) else JsonNull,
                                    )
                                    put("rrfK", JsonPrimitive(60))
                                },
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
                    if (searchResult.nextOffset != null) JsonPrimitive(searchResult.nextOffset) else JsonNull,
                )
                put("truncated", JsonPrimitive(searchResult.truncated))
            }

        return successResponse(data)
    }
}
