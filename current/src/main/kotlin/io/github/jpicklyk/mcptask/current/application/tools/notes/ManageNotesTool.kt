package io.github.jpicklyk.mcptask.current.application.tools.notes

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * MCP tool for upserting and deleting Notes.
 *
 * Supports two operations:
 * - **upsert**: Batch-upsert Notes from a `notes` array. Each note requires itemId, key, and role.
 *   The (itemId, key) pair is unique — upserting with an existing pair updates the note in place.
 * - **delete**: Delete notes by `ids` array, or by `itemId` (optionally scoped by `key`).
 */
class ManageNotesTool : BaseToolDefinition() {

    override val name = "manage_notes"

    override val description = """
Unified write operations for Notes (upsert, delete).

**Operations:**

**upsert** - Upsert notes from `notes` array.
- Each note: `{ itemId (required), key (required), role (required: "queue"|"work"|"review"), body? }`
- (itemId, key) is unique — existing notes with same pair are updated
- Validates that itemId references an existing WorkItem
- Response: `{ notes: [{id, itemId, key, role}], upserted: N, failed: N, failures: [{index, error}] }`

**delete** - Delete notes.
- By `ids` array: delete each note by UUID
- By `itemId` + optional `key`: delete all notes for item, or specific note by key
- Response: `{ deleted: N, failed: N, failures: [{id, error}] }`
""".trimIndent()

    override val category = ToolCategory.NOTE_MANAGEMENT

    override val toolAnnotations = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = true,
        idempotentHint = false,
        openWorldHint = false
    )

    override val parameterSchema = ToolSchema(
        properties = buildJsonObject {
            put("operation", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Operation: upsert, delete"))
                put("enum", JsonArray(listOf("upsert", "delete").map { JsonPrimitive(it) }))
            })
            put("notes", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("description", JsonPrimitive("Array of note objects for upsert"))
            })
            put("ids", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("description", JsonPrimitive("Array of note UUIDs for delete"))
            })
            put("itemId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("WorkItem UUID — delete all notes for this item"))
            })
            put("key", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Note key — with itemId, delete specific note"))
            })
        },
        required = listOf("operation")
    )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")
        when (operation) {
            "upsert" -> {
                val notes = optionalJsonArray(params, "notes")
                if (notes == null || notes.isEmpty()) {
                    throw ToolValidationException("Upsert operation requires a non-empty 'notes' array")
                }
            }
            "delete" -> {
                val ids = optionalJsonArray(params, "ids")
                val itemId = optionalString(params, "itemId")
                if ((ids == null || ids.isEmpty()) && itemId == null) {
                    throw ToolValidationException("Delete operation requires either 'ids' array or 'itemId'")
                }
            }
            else -> throw ToolValidationException("Invalid operation: $operation. Must be upsert or delete")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val operation = requireString(params, "operation")
        return when (operation) {
            "upsert" -> executeUpsert(params, context)
            "delete" -> executeDelete(params, context)
            else -> errorResponse("Invalid operation: $operation", ErrorCodes.VALIDATION_ERROR)
        }
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        val op = (params as? JsonObject)?.get("operation")?.let {
            (it as? JsonPrimitive)?.content
        } ?: "unknown"
        val data = (result as? JsonObject)?.get("data") as? JsonObject
        return when {
            isError -> "manage_notes($op) failed"
            op == "upsert" -> {
                val count = data?.get("upserted")?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0
                "Upserted $count note(s)"
            }
            op == "delete" -> {
                val count = data?.get("deleted")?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0
                "Deleted $count note(s)"
            }
            else -> super.userSummary(params, result, isError)
        }
    }

    // ──────────────────────────────────────────────
    // Upsert operation
    // ──────────────────────────────────────────────

    private suspend fun executeUpsert(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val notesArray = requireJsonArray(params, "notes")
        val noteRepo = context.noteRepository()
        val itemRepo = context.workItemRepository()

        val upsertedNotes = mutableListOf<JsonObject>()
        val failures = mutableListOf<JsonObject>()

        for ((index, element) in notesArray.withIndex()) {
            try {
                val noteObj = element as? JsonObject
                    ?: throw ToolValidationException("Note at index $index must be a JSON object")

                val itemIdStr = extractNoteString(noteObj, "itemId")
                    ?: throw ToolValidationException("Note at index $index: 'itemId' is required")
                val key = extractNoteString(noteObj, "key")
                    ?: throw ToolValidationException("Note at index $index: 'key' is required")
                val role = extractNoteString(noteObj, "role")
                    ?: throw ToolValidationException("Note at index $index: 'role' is required")
                val body = extractNoteString(noteObj, "body") ?: ""

                val itemId = try {
                    UUID.fromString(itemIdStr)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("Note at index $index: 'itemId' is not a valid UUID: $itemIdStr")
                }

                // Validate that the WorkItem exists
                when (itemRepo.getById(itemId)) {
                    is Result.Success -> { /* item exists */ }
                    is Result.Error -> throw ToolValidationException(
                        "Note at index $index: WorkItem '$itemIdStr' not found"
                    )
                }

                // Check for existing note with same (itemId, key) to preserve its ID
                val existingNote = when (val findResult = noteRepo.findByItemIdAndKey(itemId, key)) {
                    is Result.Success -> findResult.data
                    is Result.Error -> null
                }

                val note = Note(
                    id = existingNote?.id ?: UUID.randomUUID(),
                    itemId = itemId,
                    key = key,
                    role = role,
                    body = body
                )

                when (val result = noteRepo.upsert(note)) {
                    is Result.Success -> {
                        upsertedNotes.add(buildJsonObject {
                            put("id", JsonPrimitive(result.data.id.toString()))
                            put("itemId", JsonPrimitive(result.data.itemId.toString()))
                            put("key", JsonPrimitive(result.data.key))
                            put("role", JsonPrimitive(result.data.role))
                        })
                    }
                    is Result.Error -> {
                        failures.add(buildJsonObject {
                            put("index", JsonPrimitive(index))
                            put("error", JsonPrimitive(result.error.message))
                        })
                    }
                }
            } catch (e: ToolValidationException) {
                failures.add(buildJsonObject {
                    put("index", JsonPrimitive(index))
                    put("error", JsonPrimitive(e.message ?: "Validation failed"))
                })
            } catch (e: Exception) {
                failures.add(buildJsonObject {
                    put("index", JsonPrimitive(index))
                    put("error", JsonPrimitive(e.message ?: "Unexpected error"))
                })
            }
        }

        val data = buildJsonObject {
            put("notes", JsonArray(upsertedNotes))
            put("upserted", JsonPrimitive(upsertedNotes.size))
            put("failed", JsonPrimitive(failures.size))
            if (failures.isNotEmpty()) {
                put("failures", JsonArray(failures))
            }
        }

        return successResponse(data)
    }

    // ──────────────────────────────────────────────
    // Delete operation
    // ──────────────────────────────────────────────

    private suspend fun executeDelete(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val idsArray = optionalJsonArray(params, "ids")
        val itemIdStr = optionalString(params, "itemId")
        val key = optionalString(params, "key")
        val noteRepo = context.noteRepository()

        var deletedCount = 0
        val failures = mutableListOf<JsonObject>()

        // Delete by IDs array
        if (idsArray != null && idsArray.isNotEmpty()) {
            for (element in idsArray) {
                val idStr = (element as? JsonPrimitive)?.content
                if (idStr == null) {
                    failures.add(buildJsonObject {
                        put("id", JsonPrimitive("null"))
                        put("error", JsonPrimitive("Each ID must be a string"))
                    })
                    continue
                }

                val id = try {
                    UUID.fromString(idStr)
                } catch (_: IllegalArgumentException) {
                    failures.add(buildJsonObject {
                        put("id", JsonPrimitive(idStr))
                        put("error", JsonPrimitive("Invalid UUID format: $idStr"))
                    })
                    continue
                }

                when (val result = noteRepo.delete(id)) {
                    is Result.Success -> deletedCount++
                    is Result.Error -> {
                        failures.add(buildJsonObject {
                            put("id", JsonPrimitive(idStr))
                            put("error", JsonPrimitive(result.error.message))
                        })
                    }
                }
            }
        }

        // Delete by itemId (+ optional key)
        if (itemIdStr != null) {
            val itemId = try {
                UUID.fromString(itemIdStr)
            } catch (_: IllegalArgumentException) {
                return errorResponse(
                    "Parameter 'itemId' is not a valid UUID: $itemIdStr",
                    ErrorCodes.VALIDATION_ERROR
                )
            }

            if (key != null) {
                // Delete specific note by (itemId, key)
                when (val findResult = noteRepo.findByItemIdAndKey(itemId, key)) {
                    is Result.Success -> {
                        val note = findResult.data
                        if (note != null) {
                            when (val delResult = noteRepo.delete(note.id)) {
                                is Result.Success -> deletedCount++
                                is Result.Error -> {
                                    failures.add(buildJsonObject {
                                        put("id", JsonPrimitive(note.id.toString()))
                                        put("error", JsonPrimitive(delResult.error.message))
                                    })
                                }
                            }
                        }
                        // If note is null, nothing to delete — not an error
                    }
                    is Result.Error -> {
                        failures.add(buildJsonObject {
                            put("id", JsonPrimitive("$itemIdStr/$key"))
                            put("error", JsonPrimitive(findResult.error.message))
                        })
                    }
                }
            } else {
                // Delete all notes for itemId
                when (val result = noteRepo.deleteByItemId(itemId)) {
                    is Result.Success -> deletedCount += result.data
                    is Result.Error -> {
                        failures.add(buildJsonObject {
                            put("id", JsonPrimitive(itemIdStr))
                            put("error", JsonPrimitive(result.error.message))
                        })
                    }
                }
            }
        }

        val data = buildJsonObject {
            put("deleted", JsonPrimitive(deletedCount))
            put("failed", JsonPrimitive(failures.size))
            if (failures.isNotEmpty()) {
                put("failures", JsonArray(failures))
            }
        }

        return successResponse(data)
    }

    // ──────────────────────────────────────────────
    // JSON note field extraction helpers
    // ──────────────────────────────────────────────

    /**
     * Extracts a string field from a JsonObject. Returns null if absent, not a string, or blank.
     */
    private fun extractNoteString(obj: JsonObject, name: String): String? {
        val value = obj[name] as? JsonPrimitive ?: return null
        if (!value.isString) return null
        val content = value.content
        return if (content.isBlank()) null else content
    }
}
