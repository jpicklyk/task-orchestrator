package io.github.jpicklyk.mcptask.current.application.tools.notes

import io.github.jpicklyk.mcptask.current.application.service.computePhaseNoteContext
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.security.PathContainment
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * MCP tool for upserting and deleting Notes.
 *
 * Supports two operations:
 * - **upsert**: Batch-upsert Notes from a `notes` array. Each note requires itemId, key, and role.
 *   The (itemId, key) pair is unique — upserting with an existing pair updates the note in place.
 * - **delete**: Delete notes by `ids` array, or by `itemId` (optionally scoped by `key`).
 *
 * @param agentConfigBaseDir The trusted root that `bodyFromFile` paths are resolved strictly
 *   relative to (see [PathContainment]). Defaults to the same `AGENT_CONFIG_DIR` → `user.dir`
 *   resolution used elsewhere in the codebase (e.g. [io.github.jpicklyk.mcptask.current.infrastructure.config.YamlWorkItemSchemaService]).
 *   Overridable for tests.
 */
class ManageNotesTool(
    private val agentConfigBaseDir: Path =
        Paths.get(AppConfig.resolveConfigBaseDir(System.getenv("AGENT_CONFIG_DIR")))
) : BaseToolDefinition(),
    ActorAware {
    override val name = "manage_notes"

    override val description =
        """
Unified write operations for Notes (upsert, delete).

**upsert** — upsert notes from the `notes` array (see its schema description for the per-note shape).
`(itemId, key)` is unique — an existing pair is updated in place; `itemId` must reference an existing
WorkItem. If the matched note schema declares `maxLength` for a note's key, the resolved body is
checked after resolution: `note_limits.mode: warn` (default) accepts the note and adds a `warning`
field naming the limit and actual size; `mode: reject` fails that note with `code: NOTE_BODY_TOO_LONG`.

**delete** — delete by `ids` array, or by `itemId` (optionally scoped by `key`).
        """.trimIndent()

    override val category = ToolCategory.NOTE_MANAGEMENT

    override val toolAnnotations =
        ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = false,
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
                            put("description", JsonPrimitive("Operation: upsert, delete"))
                            put("enum", JsonArray(listOf("upsert", "delete").map { JsonPrimitive(it) }))
                        }
                    )
                    put(
                        "notes",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Array of note objects for upsert. Each: " +
                                        "{ itemId (required), key (required), role (required: queue|work|review), " +
                                        "body? (inline text), " +
                                        "bodyFromFile? (server-side path; mutually exclusive with body — providing both " +
                                        "fails that note; resolved strictly relative to the agent config root, or the " +
                                        "server's cwd; rejects absolute paths, '..', and symlink escapes; file must " +
                                        "exist, <=65536 bytes; CRLF normalized to LF), " +
                                        "actor? ({ id (required), kind (required: orchestrator|subagent|user|external), " +
                                        "parent?, proof? } — who wrote the note; last-writer-wins on re-upsert) }"
                                )
                            )
                        }
                    )
                    put(
                        "ids",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("description", JsonPrimitive("Array of note UUIDs for delete"))
                        }
                    )
                    put(
                        "itemId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("WorkItem UUID or hex prefix (4+ chars) — delete all notes for this item")
                            )
                        }
                    )
                    put(
                        "key",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Note key — with itemId, delete specific note"))
                        }
                    )
                    put(
                        "requestId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Client-generated UUID for idempotency (10 min cache, keyed by actor+requestId). " +
                                        "Requires actor."
                                )
                            )
                        }
                    )
                    put(
                        "actor",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Top-level actor: { id (required), " +
                                        "kind (required: orchestrator|subagent|user|external), parent?, proof? }"
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
                if ((ids != null && ids.isNotEmpty()) && itemId != null) {
                    throw ToolValidationException("Provide either 'ids' or 'itemId' for delete, not both")
                }
            }
            else -> throw ToolValidationException("Invalid operation: $operation. Must be upsert or delete")
        }
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val operation = requireString(params, "operation")
        val requestIdStr = optionalString(params, "requestId")
        val requestId =
            requestIdStr?.let {
                try {
                    UUID.fromString(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

        // Resolve trusted actor identity from the top-level actor for the idempotency key.
        // Must be done BEFORE the cache lookup so the cache is keyed on the verified identity,
        // not the self-reported actor.id (bug 3a fix).
        val actorObj = (params as? JsonObject)?.get("actor") as? JsonObject
        val trustedActorId: String? =
            if (actorObj != null) {
                val actorResult = parseActorClaim(actorObj, context)
                when (actorResult) {
                    is ActorParseResult.Success -> {
                        when (
                            val r =
                                ActorAware.resolveTrustedActorId(
                                    actorResult.claim,
                                    actorResult.verification,
                                    context.degradedModePolicy
                                )
                        ) {
                            is PolicyResolution.Trusted -> r.trustedId
                            is PolicyResolution.Rejected -> null
                        }
                    }
                    else -> null
                }
            } else {
                null
            }

        // Atomic getOrCompute: check-compute-store under a single lock to prevent TOCTOU races.
        // kotlinx.coroutines.runBlocking bridges the suspend execution into the lock-held lambda.
        // This is safe because the operation logic only accesses DB repositories and never
        // re-acquires the IdempotencyCache lock.
        if (requestId != null && trustedActorId != null) {
            return context.idempotencyCache.getOrCompute(trustedActorId, requestId) {
                runBlocking {
                    when (operation) {
                        "upsert" -> executeUpsert(params, context)
                        "delete" -> executeDelete(params, context)
                        else -> errorResponse("Invalid operation: $operation", ErrorCodes.VALIDATION_ERROR)
                    }
                }
            }
        }

        return when (operation) {
            "upsert" -> executeUpsert(params, context)
            "delete" -> executeDelete(params, context)
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

    private suspend fun executeUpsert(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val notesArray = requireJsonArray(params, "notes")
        val noteRepo = context.noteRepository()
        val itemRepo = context.workItemRepository()

        val upsertedNotes = mutableListOf<JsonObject>()
        val failures = mutableListOf<JsonObject>()
        // Cache validated items to avoid redundant DB lookups in itemContext computation
        val validatedItems = mutableMapOf<UUID, io.github.jpicklyk.mcptask.current.domain.model.WorkItem>()

        for ((index, element) in notesArray.withIndex()) {
            try {
                val noteObj =
                    element as? JsonObject
                        ?: throw ToolValidationException("Note at index $index must be a JSON object")

                val itemIdStr =
                    extractNoteString(noteObj, "itemId")
                        ?: throw ToolValidationException("Note at index $index: 'itemId' is required")
                val key =
                    extractNoteString(noteObj, "key")
                        ?: throw ToolValidationException("Note at index $index: 'key' is required")
                val role =
                    extractNoteString(noteObj, "role")
                        ?: throw ToolValidationException("Note at index $index: 'role' is required")

                // Resolve body: `body` (inline) and `bodyFromFile` (server-side path) are
                // mutually exclusive. bodyFromFile is read and validated eagerly here so the
                // remaining validation (schema maxLength, etc.) sees the final resolved text.
                val bodyInline = extractNoteString(noteObj, "body")
                val bodyFromFilePath = extractNoteString(noteObj, "bodyFromFile")
                if (bodyInline != null && bodyFromFilePath != null) {
                    throw ToolValidationException(
                        "Note at index $index: 'body' and 'bodyFromFile' are mutually exclusive — provide only one"
                    )
                }
                val body: String =
                    if (bodyFromFilePath != null) {
                        readBodyFromFile(bodyFromFilePath, index)
                    } else {
                        bodyInline ?: ""
                    }

                // Extract optional actor claim
                val actorResult = parseActorClaim(noteObj["actor"] as? JsonObject, context)
                val actorClaim =
                    when (actorResult) {
                        is ActorParseResult.Success -> actorResult.claim
                        is ActorParseResult.Absent -> null
                        is ActorParseResult.Invalid -> {
                            failures.add(
                                buildJsonObject {
                                    put("index", JsonPrimitive(index))
                                    put("error", JsonPrimitive("Note at index $index: ${actorResult.error}"))
                                }
                            )
                            continue
                        }
                    }
                val verification =
                    when (actorResult) {
                        is ActorParseResult.Success -> actorResult.verification
                        else -> null
                    }

                val (resolvedItemId, itemIdErr) = resolveIdString(itemIdStr, context)
                if (itemIdErr != null || resolvedItemId == null) {
                    throw ToolValidationException("Note at index $index: could not resolve 'itemId': $itemIdStr")
                }
                val itemId = resolvedItemId

                // Validate that the WorkItem exists (cache for itemContext reuse)
                if (itemId !in validatedItems) {
                    when (val r = itemRepo.getById(itemId)) {
                        is Result.Success -> validatedItems[itemId] = r.data
                        is Result.Error -> throw ToolValidationException(
                            "Note at index $index: WorkItem '$itemIdStr' not found"
                        )
                    }
                }

                // Enforce the configured note-body length limit (schema maxLength), evaluated
                // AFTER body resolution so it applies uniformly to inline `body` and
                // file-sourced `bodyFromFile` content alike.
                var lengthWarning: String? = null
                val maxLength =
                    context
                        .resolveSchema(validatedItems.getValue(itemId))
                        ?.notes
                        ?.firstOrNull { it.key == key }
                        ?.maxLength
                if (maxLength != null && body.length > maxLength) {
                    val detail = "body length ${body.length} exceeds maxLength $maxLength for key '$key'"
                    if (context.noteSchemaService().getNoteLimitsMode() == "reject") {
                        failures.add(
                            buildJsonObject {
                                put("index", JsonPrimitive(index))
                                put("error", JsonPrimitive("Note at index $index: $detail"))
                                put("code", JsonPrimitive("NOTE_BODY_TOO_LONG"))
                                put("key", JsonPrimitive(key))
                                put("maxLength", JsonPrimitive(maxLength))
                                put("actualLength", JsonPrimitive(body.length))
                            }
                        )
                        continue
                    } else {
                        lengthWarning = detail
                    }
                }

                // Check for existing note with same (itemId, key) to preserve its ID
                val existingNote =
                    when (val findResult = noteRepo.findByItemIdAndKey(itemId, key)) {
                        is Result.Success -> findResult.data
                        is Result.Error -> null
                    }

                val note =
                    Note(
                        id = existingNote?.id ?: UUID.randomUUID(),
                        itemId = itemId,
                        key = key,
                        role = role,
                        body = body,
                        actorClaim = actorClaim,
                        verification = verification
                    )

                when (val result = noteRepo.upsert(note)) {
                    is Result.Success -> {
                        upsertedNotes.add(
                            buildJsonObject {
                                put("id", JsonPrimitive(result.data.id.toString()))
                                put("itemId", JsonPrimitive(result.data.itemId.toString()))
                                put("key", JsonPrimitive(result.data.key))
                                put("role", JsonPrimitive(result.data.role))
                                actorClaim?.let { put("actor", it.toJson()) }
                                verification?.toJsonOrOmit()?.let { put("verification", it) }
                                lengthWarning?.let { put("warning", JsonPrimitive(it)) }
                            }
                        )
                    }
                    is Result.Error -> {
                        failures.add(
                            buildJsonObject {
                                put("index", JsonPrimitive(index))
                                put("error", JsonPrimitive(result.error.message))
                            }
                        )
                    }
                }
            } catch (e: ToolValidationException) {
                failures.add(
                    buildJsonObject {
                        put("index", JsonPrimitive(index))
                        put("error", JsonPrimitive(e.message ?: "Validation failed"))
                    }
                )
            } catch (e: Exception) {
                failures.add(
                    buildJsonObject {
                        put("index", JsonPrimitive(index))
                        put("error", JsonPrimitive(e.message ?: "Unexpected error"))
                    }
                )
            }
        }

        // Compute itemContext for each unique itemId that had at least one successful upsert
        val successItemIds =
            upsertedNotes
                .mapNotNull { note ->
                    note["itemId"]?.let { (it as? JsonPrimitive)?.content }
                }.toSet()

        val itemContextMap =
            buildJsonObject {
                for (itemIdStr in successItemIds) {
                    val itemId = UUID.fromString(itemIdStr)
                    val item = validatedItems[itemId] ?: continue

                    val resolvedSchema = context.resolveSchema(item)
                    val allNotes =
                        when (val nr = noteRepo.findByItemId(itemId)) {
                            is Result.Success -> nr.data
                            is Result.Error -> emptyList()
                        }
                    val notesByKey = allNotes.associateBy { it.key }

                    val phaseContext = computePhaseNoteContext(item.role, resolvedSchema?.notes, notesByKey)

                    put(
                        itemIdStr,
                        buildJsonObject {
                            if (phaseContext != null) {
                                if (phaseContext.guidancePointer != null) {
                                    put("guidancePointer", JsonPrimitive(phaseContext.guidancePointer))
                                } else {
                                    put("guidancePointer", JsonNull)
                                }
                                phaseContext.skillPointer?.let { put("skillPointer", JsonPrimitive(it)) }
                                put(
                                    "noteProgress",
                                    buildJsonObject {
                                        put("filled", JsonPrimitive(phaseContext.filled))
                                        put("remaining", JsonPrimitive(phaseContext.remaining))
                                        put("total", JsonPrimitive(phaseContext.total))
                                    }
                                )
                            } else {
                                put("guidancePointer", JsonNull)
                                put("noteProgress", JsonNull)
                            }
                        }
                    )
                }
            }

        val data =
            buildJsonObject {
                put("notes", JsonArray(upsertedNotes))
                put("upserted", JsonPrimitive(upsertedNotes.size))
                put("failed", JsonPrimitive(failures.size))
                if (failures.isNotEmpty()) {
                    put("failures", JsonArray(failures))
                }
                put("itemContext", itemContextMap)
            }

        return successResponse(data)
    }

    // ──────────────────────────────────────────────
    // Delete operation
    // ──────────────────────────────────────────────

    private suspend fun executeDelete(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val idsArray = optionalJsonArray(params, "ids")
        val itemIdStr = optionalString(params, "itemId")
        val key = optionalString(params, "key")
        val noteRepo = context.noteRepository()

        var deletedCount = 0
        var notFoundCount = 0
        val failures = mutableListOf<JsonObject>()

        // Delete by IDs array
        if (idsArray != null && idsArray.isNotEmpty()) {
            for (element in idsArray) {
                val idStr = (element as? JsonPrimitive)?.content
                if (idStr == null) {
                    failures.add(
                        buildJsonObject {
                            put("id", JsonPrimitive("null"))
                            put("error", JsonPrimitive("Each ID must be a string"))
                        }
                    )
                    continue
                }

                val id =
                    try {
                        UUID.fromString(idStr)
                    } catch (_: IllegalArgumentException) {
                        failures.add(
                            buildJsonObject {
                                put("id", JsonPrimitive(idStr))
                                put("error", JsonPrimitive("Invalid UUID format: $idStr"))
                            }
                        )
                        continue
                    }

                when (val result = noteRepo.delete(id)) {
                    is Result.Success ->
                        if (result.data) {
                            deletedCount++
                        } else {
                            failures.add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(idStr))
                                    put("error", JsonPrimitive("Note '$idStr' not found"))
                                }
                            )
                        }
                    is Result.Error -> {
                        failures.add(
                            buildJsonObject {
                                put("id", JsonPrimitive(idStr))
                                put("error", JsonPrimitive(result.error.message))
                            }
                        )
                    }
                }
            }
        } else if (itemIdStr != null) {
            // Delete by itemId (+ optional key) — only when ids array was NOT provided (mutual exclusion, defense-in-depth)
            val (resolvedItemId, itemIdErr) = resolveIdString(itemIdStr, context)
            if (itemIdErr != null) return itemIdErr
            val itemId = resolvedItemId!!

            if (key != null) {
                // Delete specific note by (itemId, key)
                when (val findResult = noteRepo.findByItemIdAndKey(itemId, key)) {
                    is Result.Success -> {
                        val note = findResult.data
                        if (note != null) {
                            when (val delResult = noteRepo.delete(note.id)) {
                                is Result.Success -> deletedCount++
                                is Result.Error -> {
                                    failures.add(
                                        buildJsonObject {
                                            put("id", JsonPrimitive(note.id.toString()))
                                            put("error", JsonPrimitive(delResult.error.message))
                                        }
                                    )
                                }
                            }
                        } else {
                            // Key did not exist — not an error, but tracked so callers can distinguish
                            notFoundCount++
                        }
                    }
                    is Result.Error -> {
                        failures.add(
                            buildJsonObject {
                                put("id", JsonPrimitive("$itemIdStr/$key"))
                                put("error", JsonPrimitive(findResult.error.message))
                            }
                        )
                    }
                }
            } else {
                // Delete all notes for itemId
                when (val result = noteRepo.deleteByItemId(itemId)) {
                    is Result.Success -> deletedCount += result.data
                    is Result.Error -> {
                        failures.add(
                            buildJsonObject {
                                put("id", JsonPrimitive(itemIdStr))
                                put("error", JsonPrimitive(result.error.message))
                            }
                        )
                    }
                }
            }
        }

        val data =
            buildJsonObject {
                put("deleted", JsonPrimitive(deletedCount))
                put("notFound", JsonPrimitive(notFoundCount))
                put("failed", JsonPrimitive(failures.size))
                if (failures.isNotEmpty()) {
                    put("failures", JsonArray(failures))
                }
            }

        return successResponse(data)
    }

    // ──────────────────────────────────────────────
    // bodyFromFile resolution
    // ──────────────────────────────────────────────

    /**
     * Reads and returns the note body from [relativePath], resolved strictly relative to
     * [agentConfigBaseDir] via [PathContainment].
     *
     * Throws [ToolValidationException] (caught by the per-note try/catch in [executeUpsert] and
     * turned into a per-note failure) when the path is rejected, the file is missing, or the
     * file exceeds [MAX_BODY_FILE_BYTES].
     */
    private fun readBodyFromFile(
        relativePath: String,
        index: Int
    ): String {
        when (val result = PathContainment.resolveWithinBase(agentConfigBaseDir, relativePath)) {
            is PathContainment.Result.Rejected ->
                throw ToolValidationException("Note at index $index: bodyFromFile rejected — ${result.reason}")
            is PathContainment.Result.Allowed -> {
                val file = result.realPath.toFile()
                val size = file.length()
                if (size > MAX_BODY_FILE_BYTES) {
                    throw ToolValidationException(
                        "Note at index $index: bodyFromFile '$relativePath' is $size bytes, " +
                            "exceeds the $MAX_BODY_FILE_BYTES byte cap"
                    )
                }
                val raw = file.readText(Charsets.UTF_8)
                // Normalize CRLF line endings to LF: note bodies are stored and compared as
                // LF-only, and files authored or edited on Windows commonly contain CRLF.
                return raw.replace("\r\n", "\n")
            }
        }
    }

    // ──────────────────────────────────────────────
    // JSON note field extraction helpers
    // ──────────────────────────────────────────────

    /**
     * Extracts a string field from a JsonObject. Returns null if absent, not a string, or blank.
     */
    private fun extractNoteString(
        obj: JsonObject,
        name: String
    ): String? {
        val value = obj[name] as? JsonPrimitive ?: return null
        if (!value.isString) return null
        val content = value.content
        return if (content.isBlank()) null else content
    }

    companion object {
        /** Maximum size, in bytes, of a file readable via `bodyFromFile`. */
        private const val MAX_BODY_FILE_BYTES = 65536
    }
}
