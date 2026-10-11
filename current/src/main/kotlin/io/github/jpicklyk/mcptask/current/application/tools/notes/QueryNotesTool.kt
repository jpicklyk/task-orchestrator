package io.github.jpicklyk.mcptask.current.application.tools.notes

import io.github.jpicklyk.mcptask.current.application.knowledge.search.Ranker
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchRequest
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchService
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchValidationException
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.MAX_FTS_RESULTS
import io.github.jpicklyk.mcptask.current.application.port.SearchMatchMode
import io.github.jpicklyk.mcptask.current.application.port.SearchResult
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.UUID

/** Valid values for the `matchMode` FTS search parameter. */
private val VALID_MATCH_MODES = setOf("auto", "substring", "text")

/** `scope` fields `query_items` accepts but note search does not; rejected rather than ignored. */
private val UNSUPPORTED_SEARCH_SCOPE_FIELDS = listOf("role", "tags")

/**
 * Read-only MCP tool for searching Notes.
 *
 * Supports one operation, **search**: full-text FTS5 search over note bodies, returning ranked snippets. Reading a
 * note by key and listing an item's notes are `notes_read` (`get`, `list`); this tool disappears with the rest of the
 * 3.x surface when search moves to `items_read` (Q19).
 */
class QueryNotesTool : BaseToolDefinition() {
    override val name = "query_notes"

    override val description =
        """
Read-only search over Notes.

Operations: search

**search** requires `query`; full-text search over note bodies. To read a note by key or list an item's
notes, use `notes_read`.
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
                            put("description", JsonPrimitive("Operation: search"))
                            put("enum", JsonArray(listOf("search").map { JsonPrimitive(it) }))
                        }
                    )
                    // ── FTS search parameters ──
                    put(
                        "query",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Search terms (required for search). Multiple words = implicit AND; special " +
                                        "characters are auto-escaped — pass plain terms. Control characters are rejected."
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
                                    "Structural scope filter for search mode. All fields are optional and combined with AND. " +
                                        "scope.role/scope.tags are rejected (VALIDATION_ERROR); use notes_read list for role."
                                )
                            )
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "itemId",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("string"))
                                            put("description", JsonPrimitive("Limit to notes on this single work item."))
                                        }
                                    )
                                    put(
                                        "ancestorId",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("string"))
                                            put(
                                                "description",
                                                JsonPrimitive(
                                                    "Limit to notes whose owning item is in this item's subtree (any depth) " +
                                                        "— scope a search to a feature or container."
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
                                    "Search strategy: auto (default, broadest coverage), substring (case-insensitive " +
                                        "exact match, token 3+ chars), text (stemmed natural-language match)."
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
                                    "When true (default), each hit includes a ~32-token snippet with <mark> highlights; " +
                                        "markdown is preserved."
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
                                    "When true, each hit includes an explain object with trigramRank, textRank, and " +
                                        "rrfK. Off by default — only for debugging."
                                )
                            )
                        }
                    )
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Max results (default 20; over 100 rejected)"))
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
                if (limitVal != null && limitVal > MAX_FTS_RESULTS) {
                    throw ToolValidationException(SearchService.limitTooLargeMessage(limitVal))
                }
                val offsetVal = optionalInt(params, "offset")
                if (offsetVal != null && offsetVal < 0) {
                    throw ToolValidationException("offset must be non-negative")
                }
                // Note search has no role or tag scope: reject them instead of silently ignoring them. An explicit
                // JSON null is the same as an absent field.
                val scopeJson = (params as? JsonObject)?.get("scope") as? JsonObject
                for (field in UNSUPPORTED_SEARCH_SCOPE_FIELDS) {
                    val value = scopeJson?.get(field)
                    if (value != null && value !is JsonNull) {
                        throw ToolValidationException(
                            "scope.$field is not supported by query_notes search. Supported scope fields: ancestorId, " +
                                "itemId. To filter notes by role, use notes_read list with role."
                        )
                    }
                }
            }
            else -> throw ToolValidationException("Invalid operation: $operation. Must be search")
        }
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val operation = requireString(params, "operation")
        return when (operation) {
            "search" -> {
                val query = requireString(params, "query")
                executeFtsSearch(params, query, context)
            }
            else -> errorResponse("Invalid operation: $operation", LegacyMcpCode.VALIDATION_ERROR)
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
            op == "search" -> {
                val totalHits = data?.get("totalHits")?.let { if (it is JsonPrimitive) it.intOrNull else null } ?: 0
                val returned = (data?.get("hits") as? JsonArray)?.size ?: 0
                "Found $totalHits note hit(s), returned $returned"
            }
            else -> super.userSummary(params, result, isError)
        }
    }

    // ──────────────────────────────────────────────
    // Search operation (FTS5)
    // ──────────────────────────────────────────────

    /**
     * Full-text search over note bodies via FTS5.
     *
     * Parses the params, delegates to the shared
     * [io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchService] (note corpus, unrestricted
     * access), and serializes the [SearchResult] into the response shape defined in plan §7:
     * `{ hits: [...], totalHits, nextOffset, truncated }`.
     *
     * Each hit includes `kind="note"`, `itemId`, `title` (the owning item's, when it could be read),
     * `noteKey`, `field="body"`, `snippet`, `score`,
     * `matchedIn`, and optionally `explain` (raw FTS5 ranks, only when `explain=true`).
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
        // Range-checked in validateParams (1..MAX_FTS_RESULTS); the service re-checks.
        val limit = optionalInt(params, "limit") ?: SearchRequest.DEFAULT_LIMIT
        val offset = (optionalInt(params, "offset") ?: 0).coerceAtLeast(0)

        // Parse optional scope object
        val scopeJson = (params as? JsonObject)?.get("scope") as? JsonObject
        val scope: NoteSearchScope? =
            if (scopeJson != null) {
                val ancestorIdStr = scopeJson["ancestorId"]?.jsonPrimitive?.contentOrNull
                val itemIdStr = scopeJson["itemId"]?.jsonPrimitive?.contentOrNull

                val ancestorId: UUID? =
                    if (ancestorIdStr != null) {
                        runCatchingNonCancellation { UUID.fromString(ancestorIdStr) }.getOrElse {
                            return errorResponse(
                                "Invalid scope.ancestorId UUID: $ancestorIdStr",
                                LegacyMcpCode.VALIDATION_ERROR,
                            )
                        }
                    } else {
                        null
                    }
                val scopeItemId: UUID? =
                    if (itemIdStr != null) {
                        runCatchingNonCancellation { UUID.fromString(itemIdStr) }.getOrElse {
                            return errorResponse(
                                "Invalid scope.itemId UUID: $itemIdStr",
                                LegacyMcpCode.VALIDATION_ERROR,
                            )
                        }
                    } else {
                        null
                    }
                NoteSearchScope(itemId = scopeItemId, ancestorId = ancestorId)
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

        // MCP carries no principal scope yet: the one explicit unrestricted() call site.
        val request =
            SearchRequest(
                query = rawQuery,
                corpus = Corpus.NOTE,
                matchMode = matchMode,
                itemId = scope?.itemId,
                ancestorId = scope?.ancestorId,
                limit = limit,
                offset = offset,
                access = AccessScope.unrestricted(),
            )
        val searchResult: SearchResult =
            try {
                context.searchService.search(request)
            } catch (e: SearchValidationException) {
                return errorResponse(e.message ?: "Invalid search query", LegacyMcpCode.VALIDATION_ERROR)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                return errorResponse(
                    "FTS5 note search failed: ${e.message}",
                    LegacyMcpCode.INTERNAL_ERROR
                )
            }

        // Serialize hits
        val hitsArray =
            JsonArray(
                searchResult.hits.map { hit ->
                    buildJsonObject {
                        put("kind", JsonPrimitive(hit.kind))
                        put("itemId", JsonPrimitive(hit.itemId.toString()))
                        hit.title?.let { put("title", JsonPrimitive(it)) }
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
                                    put("rrfK", JsonPrimitive(Ranker.K))
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

/** The parsed `scope` object of a `query_notes` search. */
private data class NoteSearchScope(
    val itemId: UUID?,
    val ancestorId: UUID?,
)
