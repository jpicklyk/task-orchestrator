package io.github.jpicklyk.mcptask.current.application.tools.notes

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

/**
 * Read-only MCP tool for querying Notes.
 *
 * Supports two operations:
 * - **get**: Retrieve a single Note by its UUID.
 * - **list**: List all notes for a WorkItem, with optional role filtering and body inclusion control.
 */
class QueryNotesTool : BaseToolDefinition() {

    override val name = "query_notes"

    override val description = """
Read-only query operations for Notes (get, list).

**Operations:**

**get** - Get a single Note by ID.
- Required: `id` (UUID)
- Response: full Note JSON

**list** - List notes for a WorkItem.
- Required: `itemId` (UUID)
- Optional: `role` — filter by role: "queue", "work", "review"
- Optional: `includeBody` (boolean, default true) — set false to omit body for token efficiency
- Response: `{ notes: [...], total: N }`
""".trimIndent()

    override val category = ToolCategory.NOTE_MANAGEMENT

    override val toolAnnotations = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false
    )

    override val parameterSchema = ToolSchema(
        properties = buildJsonObject {
            put("operation", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Operation: get, list"))
                put("enum", JsonArray(listOf("get", "list").map { JsonPrimitive(it) }))
            })
            put("id", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Note UUID (required for get)"))
            })
            put("itemId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("WorkItem UUID (required for list)"))
            })
            put("role", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Filter by role: queue, work, review"))
            })
            put("includeBody", buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put("description", JsonPrimitive("Include note body (default: true)"))
            })
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
                extractUUID(params, "itemId", required = true)
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
            else -> throw ToolValidationException("Invalid operation: $operation. Must be get or list")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val operation = requireString(params, "operation")
        return when (operation) {
            "get" -> executeGet(params, context)
            "list" -> executeList(params, context)
            else -> errorResponse("Invalid operation: $operation", ErrorCodes.VALIDATION_ERROR)
        }
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        val op = (params as? JsonObject)?.get("operation")?.let {
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

    private suspend fun executeGet(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val id = extractUUID(params, "id", required = true)!!
        val noteRepo = context.noteRepository()

        return when (val result = noteRepo.getById(id)) {
            is Result.Success -> {
                successResponse(noteToJson(result.data))
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

    private suspend fun executeList(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val itemId = extractUUID(params, "itemId", required = true)!!
        val role = optionalString(params, "role")
        val includeBody = optionalBoolean(params, "includeBody", defaultValue = true)
        val noteRepo = context.noteRepository()

        return when (val result = noteRepo.findByItemId(itemId, role)) {
            is Result.Success -> {
                val notes = result.data
                val data = buildJsonObject {
                    put("notes", JsonArray(notes.map { noteToJson(it, includeBody) }))
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
    // JSON serialization helper
    // ──────────────────────────────────────────────

    private fun noteToJson(note: Note, includeBody: Boolean = true): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(note.id.toString()))
        put("itemId", JsonPrimitive(note.itemId.toString()))
        put("key", JsonPrimitive(note.key))
        put("role", JsonPrimitive(note.role))
        if (includeBody) put("body", JsonPrimitive(note.body))
        put("createdAt", JsonPrimitive(note.createdAt.toString()))
        put("modifiedAt", JsonPrimitive(note.modifiedAt.toString()))
    }
}
