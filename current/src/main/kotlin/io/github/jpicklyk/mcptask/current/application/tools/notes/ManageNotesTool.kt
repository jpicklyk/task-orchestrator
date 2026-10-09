package io.github.jpicklyk.mcptask.current.application.tools.notes

import io.github.jpicklyk.mcptask.current.application.service.NoteCommandService
import io.github.jpicklyk.mcptask.current.application.service.NoteUpsertCommand
import io.github.jpicklyk.mcptask.current.application.service.computePhaseNoteContext
import io.github.jpicklyk.mcptask.current.application.service.withEventActor
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.legacyReadOrNull
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.security.PathContainment
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
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
                                    "Client-generated UUID; each element runs once per 24h (keyed by actor+requestId); " +
                                        "requires actor; malformed values rejected."
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
        validateRequestIdParam(params)
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
        // Defence-in-depth: unreachable via MCP (validateParams already ran validateRequestIdParam),
        // but guards a direct in-process call to execute() that skipped validateParams.
        validateRequestIdParam(params)
        val requestIdStr = optionalString(params, "requestId")
        val requestId = requestIdStr?.let { UUID.fromString(it.trim()) }

        // Resolve trusted actor identity from the top-level actor for the idempotency key.
        // Must be done BEFORE the cache lookup so the cache is keyed on the verified identity,
        // not the self-reported actor.id (bug 3a fix).
        val actorObj = (params as? JsonObject)?.get("actor") as? JsonObject
        val parsedActor = if (actorObj != null) parseActorClaim(actorObj, context) else null
        val eventActor = (parsedActor as? ActorParseResult.Success)?.claim
        val trustedActorId: String? =
            if (parsedActor != null) {
                val actorResult = parsedActor
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

        // A keyed call (requestId plus a trusted principal) is keyed PER ELEMENT of the operation array: each
        // element runs, and is recorded, in its own unit. Unkeyed calls never touch the idempotency service.
        val keyed =
            if (requestId != null && trustedActorId != null) {
                KeyedCall(
                    context.idempotency,
                    trustedActorId,
                    requestId,
                    KeyedCall.op(name, operation),
                    KeyedCall.sharedOf(params, "notes", "ids")
                )
            } else {
                null
            }

        return withEventActor(eventActor) {
            when (operation) {
                "upsert" -> executeUpsert(params, context, keyed)
                "delete" ->
                    if (keyed != null && optionalJsonArray(params, "ids").isNullOrEmpty()) {
                        // Delete by itemId (+ key) is one atomic call: element 0.
                        keyed.whole(
                            KeyedCall.withoutKeyFields(params),
                            recordable = { KeyedCall.hasPositive(it, "deleted") && !KeyedCall.hasPositive(it, "failed") }
                        ) { executeDelete(params, context, null) }
                    } else {
                        executeDelete(params, context, keyed)
                    }
                else -> errorResponse("Invalid operation: $operation", ErrorCodes.VALIDATION_ERROR)
            }
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
        context: ToolExecutionContext,
        keyed: KeyedCall?
    ): JsonElement {
        val notesArray = requireJsonArray(params, "notes")
        val noteRepo = context.noteRepository()
        val itemRepo = context.workItemRepository()

        val upsertedNotes = mutableListOf<JsonObject>()
        val failures = mutableListOf<JsonObject>()

        for ((index, element) in notesArray.withIndex()) {
            try {
                val outcome =
                    runElement(keyed, index, element, onError = { noteFailure(index, it) }) body@{
                        val noteObj = element as? JsonObject
                        if (noteObj == null) {
                            val message = "Note at index $index must be a JSON object"
                            return@body ElementResult.Invalid(noteFailure(index, message), message)
                        }

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
                                is ActorParseResult.Invalid ->
                                    return@body ElementResult.Failed(noteFailure(index, "Note at index $index: ${actorResult.error}"))
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

                        // The write policy (role normalization, byte cap, CRLF, schema-role, maxLength and
                        // note_limits) and the item lookup live in NoteCommandService, shared with REST and
                        // create_work_tree; this maps its outcome back to the manage_notes wire shapes.
                        when (
                            val written =
                                context.noteCommandService.upsert(
                                    NoteUpsertCommand(itemId, key, role, body, actorClaim, verification, bodyFromFilePath)
                                )
                        ) {
                            is Outcome.Err -> upsertFailure(written.error, index, itemIdStr, key)
                            is Outcome.Ok -> {
                                val result = written.value.note
                                ElementResult.Done(
                                    buildJsonObject {
                                        put("id", JsonPrimitive(result.id.toString()))
                                        put("itemId", JsonPrimitive(result.itemId.toString()))
                                        put("key", JsonPrimitive(result.key))
                                        put("role", JsonPrimitive(result.role))
                                        actorClaim?.let { put("actor", it.toJson()) }
                                        verification?.toJsonOrOmit()?.let { put("verification", it) }
                                        written.value.warning?.let { put("warning", JsonPrimitive(it.message())) }
                                    }
                                )
                            }
                        }
                    }
                when (outcome) {
                    is ElementOutcome.Succeeded -> upsertedNotes.add(outcome.fragment)
                    is ElementOutcome.Failed -> failures.add(outcome.failure)
                }
            } catch (e: ToolValidationException) {
                failures.add(
                    buildJsonObject {
                        put("index", JsonPrimitive(index))
                        put("error", JsonPrimitive(e.message ?: "Validation failed"))
                    }
                )
            } catch (e: PerRootConfigUnavailableException) {
                // D10: a per-root config read failure resolving THIS note's schema (maxLength) or
                // note-limits mode fails only this note — mirroring the per-transition transient
                // outcome AdvanceItemTool emits for the same exception (D5). Nothing is stored for
                // this note; the other notes in the batch proceed.
                failures.add(
                    buildJsonObject {
                        put("index", JsonPrimitive(index))
                        put("error", JsonPrimitive(e.message))
                        put("errorKind", JsonPrimitive("transient"))
                        put("errorCode", JsonPrimitive(PerRootConfigUnavailableException.CODE))
                    }
                )
            } catch (e: Exception) {
                e.rethrowIfCancellation()
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
                    val item =
                        legacyReadOrNull { itemRepo.getById(itemId) }
                            ?: continue

                    // The notes for this item are ALREADY PERSISTED at this point (the per-index
                    // upsert loop above already ran) — per D7, a per-root config read failure
                    // resolving this response-only `itemContext` decoration must never be reported
                    // as a failure of the already-committed upsert(s). The entry for this itemId is
                    // simply omitted and a WARN is logged.
                    // resolveSchema legitimately returns null (no matching schema) as a normal
                    // outcome, distinct from the config-unavailable case below — the call is boxed
                    // in a non-null Result so `?: continue` only fires on the exception, not on an
                    // ordinary null schema.
                    val resolvedSchema =
                        (
                            omitOnConfigUnavailable(logger, "itemContext", itemId) {
                                kotlin.Result.success(context.resolveSchema(item))
                            } ?: continue
                        ).getOrThrow()
                    val allNotes =
                        (legacyReadOrNull { noteRepo.findByItemId(itemId) } ?: emptyList())
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

    private fun noteFailure(
        index: Int,
        message: String
    ): JsonObject =
        buildJsonObject {
            put("index", JsonPrimitive(index))
            put("error", JsonPrimitive(message))
        }

    private fun noteFailure(
        index: Int,
        error: DomainError
    ): JsonObject = KeyedCall.defaultFailure(index, error)

    /**
     * Maps a [NoteCommandService] write failure to this tool's per-note failure shapes. Payload-only
     * failures (byte cap, invalid role) are [ElementResult.Invalid] and may be recorded for idempotency;
     * config-dependent ones (schema-role, `maxLength`) and store faults are [ElementResult.Failed], never recorded.
     */
    private fun upsertFailure(
        error: DomainError,
        index: Int,
        itemIdStr: String,
        key: String
    ): ElementResult {
        val message = "Note at index $index: ${error.message}"
        return when (val detail = error.detail) {
            is ErrorDetail.NoteTooLong ->
                ElementResult.Failed(
                    buildJsonObject {
                        put("index", JsonPrimitive(index))
                        put("error", JsonPrimitive(message))
                        put("code", JsonPrimitive("NOTE_BODY_TOO_LONG"))
                        put("key", JsonPrimitive(key))
                        put("maxLength", JsonPrimitive(detail.max))
                        put("actualLength", JsonPrimitive(detail.actual))
                    }
                )
            is ErrorDetail.PayloadTooLarge ->
                ElementResult.Invalid(
                    buildJsonObject {
                        put("index", JsonPrimitive(index))
                        put("error", JsonPrimitive(message))
                        put("code", JsonPrimitive("NOTE_BODY_TOO_LARGE"))
                        put("key", JsonPrimitive(key))
                        put("maxBytes", JsonPrimitive(detail.max))
                        put("actualBytes", JsonPrimitive(detail.actual))
                    },
                    message
                )
            is ErrorDetail.InvalidRequest -> ElementResult.Invalid(noteFailure(index, message), message)
            is ErrorDetail.NotFound ->
                ElementResult.Failed(noteFailure(index, "Note at index $index: WorkItem '$itemIdStr' not found"))
            is ErrorDetail.SchemaViolation -> ElementResult.Failed(noteFailure(index, message))
            else -> ElementResult.Failed(noteFailure(index, LegacyFaults.message(error)))
        }
    }

    private fun deleteFailure(
        id: String,
        message: String
    ): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("error", JsonPrimitive(message))
        }

    private suspend fun executeDelete(
        params: JsonElement,
        context: ToolExecutionContext,
        keyed: KeyedCall?
    ): JsonElement {
        val idsArray = optionalJsonArray(params, "ids")
        val itemIdStr = optionalString(params, "itemId")
        val key = optionalString(params, "key")
        var deletedCount = 0
        var notFoundCount = 0
        val failures = mutableListOf<JsonObject>()

        // Delete by IDs array
        if (idsArray != null && idsArray.isNotEmpty()) {
            for ((index, element) in idsArray.withIndex()) {
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

                // A delete is recorded only once it committed; a missing note or a fault rolls the element
                // back and is reported unrecorded, so a retry with the same key runs it again.
                val outcome =
                    runElement(keyed, index, element, onError = { deleteFailure(idStr, it.message) }) {
                        when (val deleted = context.noteCommandService.deleteById(id)) {
                            is Outcome.Err -> ElementResult.Failed(deleteFailure(idStr, LegacyFaults.message(deleted.error)))
                            is Outcome.Ok ->
                                if (deleted.value) {
                                    ElementResult.Done(buildJsonObject { put("id", JsonPrimitive(idStr)) })
                                } else {
                                    ElementResult.Failed(deleteFailure(idStr, "Note '$idStr' not found"))
                                }
                        }
                    }
                when (outcome) {
                    is ElementOutcome.Succeeded -> deletedCount++
                    is ElementOutcome.Failed -> failures.add(outcome.failure)
                }
            }
        } else if (itemIdStr != null) {
            // Delete by itemId (+ optional key) — only when ids array was NOT provided (mutual exclusion, defense-in-depth)
            val (resolvedItemId, itemIdErr) = resolveIdString(itemIdStr, context)
            if (itemIdErr != null) return itemIdErr
            val itemId = resolvedItemId!!

            if (key != null) {
                // Delete specific note by (itemId, key): the lookup and the delete share ONE write unit.
                when (val deleted = context.noteCommandService.deleteByKey(itemId, key)) {
                    is Outcome.Ok ->
                        if (deleted.value != null) {
                            deletedCount++
                        } else {
                            // Key did not exist — not an error, but tracked so callers can distinguish
                            notFoundCount++
                        }
                    is Outcome.Err -> {
                        failures.add(
                            buildJsonObject {
                                put("id", JsonPrimitive("$itemIdStr/$key"))
                                put("error", JsonPrimitive(LegacyFaults.message(deleted.error)))
                            }
                        )
                    }
                }
            } else {
                // Delete all notes for itemId
                when (val deleted = context.noteCommandService.deleteAllForItem(itemId)) {
                    is Outcome.Ok -> deletedCount += deleted.value
                    is Outcome.Err ->
                        failures.add(
                            buildJsonObject {
                                put("id", JsonPrimitive(itemIdStr))
                                put("error", JsonPrimitive(LegacyFaults.message(deleted.error)))
                            }
                        )
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
                // CRLF -> LF normalization is NoteCommandService's job (every write path shares it).
                return file.readText(Charsets.UTF_8)
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
        private const val MAX_BODY_FILE_BYTES = NoteCommandService.MAX_NOTE_BODY_BYTES
    }
}
