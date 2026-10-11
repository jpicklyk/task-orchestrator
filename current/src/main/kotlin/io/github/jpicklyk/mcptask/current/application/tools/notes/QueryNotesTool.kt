package io.github.jpicklyk.mcptask.current.application.tools.notes

import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

/**
 * Read-only MCP tool for querying Notes.
 *
 * Supports two operations:
 * - **get**: Retrieve a single Note by its UUID.
 * - **list**: List all notes for a WorkItem, with optional role filtering and body inclusion control.
 *
 * Full-text note search moved to the 4.0 `items_read` tool (`select: search`, `kinds: ["note"]`).
 */
class QueryNotesTool : BaseToolDefinition() {
    override val name = "query_notes"

    override val description =
        """
Read-only query operations for Notes.

Operations: get, list

**get** requires `noteId`.

**list** requires `itemId`. Optional `role`/`keys` filters.
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
                            put("description", JsonPrimitive("Operation: get, list"))
                            put("enum", JsonArray(listOf("get", "list").map { JsonPrimitive(it) }))
                        }
                    )
                    put(
                        "noteId",
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
                        "keys",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                            put("description", JsonPrimitive("Filter list results to notes with these keys (optional)"))
                        }
                    )
                    put(
                        "includeBody",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Include full note bodies (default: false). When false, each note includes bodyLength instead."
                                )
                            )
                        }
                    )
                },
            required = listOf("operation")
        )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")
        when (operation) {
            "get" -> {
                extractUUID(params, "noteId", required = true)
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
                val keysElement = (params as? JsonObject)?.get("keys")
                if (keysElement != null) {
                    val keysArray =
                        keysElement as? JsonArray
                            ?: throw ToolValidationException("keys must be an array of note-key strings")
                    keysArray.forEachIndexed { index, element ->
                        val prim = element as? JsonPrimitive
                        if (prim == null || !prim.isString || prim.content.isBlank()) {
                            throw ToolValidationException("keys[$index] must be a non-blank string")
                        }
                    }
                }
            }
            else -> throw ToolValidationException("Invalid operation: $operation. Must be get or list")
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
            op == "get" -> {
                val key = data?.get("key")?.let { (it as? JsonPrimitive)?.content } ?: "unknown"
                "Retrieved note: $key"
            }
            op == "list" -> {
                val total = data?.get("total")?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0
                "Listed $total note(s)"
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
        val id = requireUUID(params, "noteId")
        val noteRepo = context.noteRepository()

        val note =
            legacyRead({ return errorResponse("Failed to get note: $it", LegacyMcpCode.DATABASE_ERROR) }) { noteRepo.getById(id) }
                ?: return errorResponse(
                    "Note not found: $id",
                    LegacyMcpCode.RESOURCE_NOT_FOUND,
                    details = "Note not found with id: $id"
                )
        return successResponse(note.toJson())
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
        val includeBody = optionalBoolean(params, "includeBody", defaultValue = false)
        val keyFilter: Set<String>? =
            ((params as? JsonObject)?.get("keys") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
        val noteRepo = context.noteRepository()

        return run {
            val result =
                legacyRead({
                    return@run run {
                        errorResponse(
                            "Failed to list notes for item: $itemId",
                            LegacyMcpCode.DATABASE_ERROR,
                            details = it
                        )
                    }
                }) { noteRepo.findByItemId(itemId, role) }
            val notes =
                if (keyFilter != null) {
                    result.filter { it.key in keyFilter }
                } else {
                    result
                }
            val data =
                buildJsonObject {
                    // itemId echo omitted per note — the caller supplied it for this list.
                    put("notes", JsonArray(notes.map { it.toJson(includeBody = includeBody, includeItemId = false) }))
                    put("total", JsonPrimitive(notes.size))
                }
            successResponse(data)
        }
    }
}
