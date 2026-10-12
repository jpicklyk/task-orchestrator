package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.service.ActorVerificationScope
import io.github.jpicklyk.mcptask.current.application.service.NoteUpsertCommand
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Note helpers for tests that need notes but are not about the note tools (contract-change sweep for Q11, which
 * replaced `manage_notes` and the note `get`/`list` of `query_notes` with `notes_write` / `notes_read`).
 *
 * - [putNote] is the FIXTURE writer: it goes straight through `NoteCommandService` (the one owner of the note write
 *   policy), optionally with an actor claim and verification, and records the same event rows a real write does.
 * - [callTool] / [upsertNoteVia] / [getNoteVia] run the production `notes_write` / `notes_read` through the real
 *   dispatcher, for tests whose subject is what a tool call does (actor lifting and verification, idempotency, events).
 */
suspend fun ToolExecutionContext.putNote(
    itemId: UUID,
    key: String,
    role: String = "work",
    body: String = "",
    actor: ActorClaim? = null,
    verification: VerificationResult? = null,
    bodyFromFile: String? = null,
    expectedVersion: Long? = null
): Note =
    when (
        val written =
            noteCommandService.upsert(
                NoteUpsertCommand(itemId, key, role, body, actor, verification, bodyFromFile, expectedVersion)
            )
    ) {
        is Outcome.Ok -> written.value.note
        is Outcome.Err -> throw AssertionError("putNote($key) rejected: ${written.error.code.wire} ${written.error.message}")
    }

/** One 4.0 tool call through the real [buildToolDispatcher] at MCP surface; returns the envelope body. */
suspend fun ToolExecutionContext.callTool(
    tool: String,
    arguments: JsonObject,
    access: CallAccess = CallAccess.UNRESTRICTED
): JsonObject {
    val spec = checkNotNull(ToolRegistry(buildToolSpecs())[tool]) { "no 4.0 tool named $tool" }
    val dispatched =
        withContext(ActorVerificationScope()) {
            buildToolDispatcher(this@callTool).dispatch(spec, ToolProfile.ORCHESTRATION, arguments, "testreq0", "mcp", access)
        }
    return dispatched.envelope.body
}

/** A top-level `actor` argument. */
fun actorArg(
    id: String,
    kind: String = "subagent",
    proof: String? = null,
    parent: String? = null
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("kind", kind)
        proof?.let { put("proof", it) }
        parent?.let { put("parent", it) }
    }

/** `notes_write` with one `upsert` element; [actor] is the top-level actor, [extra] more element fields. */
suspend fun ToolExecutionContext.upsertNoteVia(
    itemId: UUID,
    key: String,
    role: String = "work",
    body: String? = "",
    actor: JsonObject? = null,
    idempotencyKey: String? = null,
    extra: Map<String, JsonElement> = emptyMap()
): JsonObject =
    callTool(
        "notes_write",
        buildJsonObject {
            actor?.let { put("actor", it) }
            put(
                "ops",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("op", "upsert")
                            put("itemId", itemId.toString())
                            put("key", key)
                            put("role", role)
                            body?.let { put("body", it) }
                            idempotencyKey?.let { put("idempotencyKey", it) }
                            for ((name, value) in extra) put(name, value)
                        }
                    )
                )
            )
        }
    )

/** `notes_read get` of one note; the answer is the envelope body. */
suspend fun ToolExecutionContext.getNoteVia(
    itemId: UUID,
    key: String,
    access: CallAccess = CallAccess.UNRESTRICTED
): JsonObject =
    callTool(
        "notes_read",
        buildJsonObject {
            put("select", "get")
            put("itemId", itemId.toString())
            put("key", key)
        },
        access
    )

/** `notes_read list` of an item's notes; the answer is the envelope body. */
suspend fun ToolExecutionContext.listNotesVia(
    itemId: UUID,
    access: CallAccess = CallAccess.UNRESTRICTED
): JsonObject =
    callTool(
        "notes_read",
        buildJsonObject {
            put("select", "list")
            put("itemId", itemId.toString())
        },
        access
    )

/** True when the envelope [body] is `ok`. */
fun envelopeOk(body: JsonObject): Boolean = (body["ok"] as? JsonPrimitive)?.content == "true"

/**
 * Call-site shim for tests written against the 3.x `manage_notes` upsert shape
 * (`{operation: "upsert", notes: [{itemId, key, role, body, actor?}], requestId?}`): runs the same notes through
 * `notes_write`. The first note's `actor` becomes the call's top-level `actor` (the 4.0 shape has no per-note actor)
 * and a 3.x `requestId` becomes each element's `idempotencyKey`. The answer is the `notes_write` envelope.
 */
suspend fun notesWriteFromLegacyParams(
    params: JsonElement,
    context: ToolExecutionContext
): JsonObject {
    val legacy = params as JsonObject
    val notes = (legacy["notes"] as JsonArray).map { it as JsonObject }
    val requestId = (legacy["requestId"] as? JsonPrimitive)?.content
    val actor = (legacy["actor"] as? JsonObject) ?: notes.firstNotNullOfOrNull { it["actor"] as? JsonObject }
    return context.callTool(
        "notes_write",
        buildJsonObject {
            actor?.let { put("actor", it) }
            put(
                "ops",
                JsonArray(
                    notes.map { note ->
                        buildJsonObject {
                            put("op", "upsert")
                            for ((name, value) in note) if (name != "actor") put(name, value)
                            requestId?.let { put("idempotencyKey", it) }
                        }
                    }
                )
            )
        }
    )
}
