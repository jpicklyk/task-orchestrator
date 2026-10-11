package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.notes

import io.github.jpicklyk.mcptask.current.application.api.notes.NoteGetResult
import io.github.jpicklyk.mcptask.current.application.api.notes.NoteListEntry
import io.github.jpicklyk.mcptask.current.application.api.notes.NoteView
import io.github.jpicklyk.mcptask.current.application.api.notes.NoteWriteOutcome
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCodec
import io.github.jpicklyk.mcptask.current.application.service.NoteLengthWarning
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The wire shapes of `notes_read` and `notes_write` (nulls omitted, keys in the order the design lists them).
 *
 * A note's `actor` is `{id, kind, parent?}` and its `verification` `{status, verifier?, reason?}`. Proof evidence
 * (the proof hash and the verified claims) has no mapping here, so it can never reach a wire.
 */
internal object NoteJson {
    fun actor(actor: ActorClaim): JsonObject =
        buildJsonObject {
            put("id", actor.id)
            put("kind", actor.kind.toJsonString())
            actor.parent?.let { put("parent", it) }
        }

    fun verification(verification: VerificationResult): JsonObject =
        buildJsonObject {
            put("status", verification.status.toJsonString())
            verification.verifier?.let { put("verifier", it) }
            verification.reason?.let { put("reason", it) }
        }

    fun view(note: NoteView): JsonObject =
        buildJsonObject {
            put("id", note.id.toString())
            put("key", note.key)
            put("role", note.role)
            put("version", note.version)
            put("body", note.body)
            put("modifiedAt", note.modifiedAt.toString())
            note.actor?.let { put("actor", actor(it)) }
            note.verification?.let { put("verification", verification(it)) }
        }

    fun entry(entry: NoteListEntry): JsonObject =
        buildJsonObject {
            put("id", entry.shortId)
            put("key", entry.key)
            put("role", entry.role)
            put("version", entry.version)
            put("bodyLength", entry.bodyLength)
            put("modifiedAt", entry.modifiedAt.toString())
            entry.actor?.let { put("actor", actor(it)) }
            entry.verification?.let { put("verification", verification(it)) }
        }

    fun getResult(result: NoteGetResult): JsonElement =
        when (result) {
            is NoteGetResult.Found -> view(result.note)
            is NoteGetResult.Unchanged ->
                buildJsonObject {
                    put("unchanged", true)
                    put("version", result.version)
                }
        }

    fun warning(warning: NoteLengthWarning): JsonObject =
        buildJsonObject {
            put("key", warning.key)
            put("max", warning.maxLength)
            put("actual", warning.actualLength)
        }

    /** One `results[i]` of `notes_write` (the encoder adds `ok`). */
    fun element(outcome: NoteWriteOutcome): JsonObject =
        when (outcome) {
            is NoteWriteOutcome.Upserted ->
                buildJsonObject {
                    put("id", outcome.id.toString())
                    put("version", outcome.version)
                    if (outcome.created) put("created", true)
                    outcome.warning?.let { put("warning", warning(it)) }
                }
            is NoteWriteOutcome.Deleted -> buildJsonObject { put("id", outcome.id.toString()) }
        }
}

/** Stores a [NoteWriteOutcome] in an idempotency record and reads it back, field for field. */
internal object NoteWriteCodec : IdempotencyCodec<NoteWriteOutcome> {
    private const val UPSERTED = "upserted"
    private const val DELETED = "deleted"

    override fun encode(value: NoteWriteOutcome): JsonElement =
        when (value) {
            is NoteWriteOutcome.Upserted ->
                buildJsonObject {
                    put("kind", UPSERTED)
                    put("id", value.id.toString())
                    put("version", value.version)
                    put("created", value.created)
                    value.warning?.let { put("warning", NoteJson.warning(it)) }
                }
            is NoteWriteOutcome.Deleted ->
                buildJsonObject {
                    put("kind", DELETED)
                    put("id", value.id.toString())
                }
        }

    override fun decode(json: JsonElement): NoteWriteOutcome {
        val obj = json.jsonObject
        val id = UUID.fromString(obj.getValue("id").jsonPrimitive.content)
        return when (val kind = obj.getValue("kind").jsonPrimitive.content) {
            UPSERTED ->
                NoteWriteOutcome.Upserted(
                    id = id,
                    version = obj.getValue("version").jsonPrimitive.long,
                    created = obj.getValue("created").jsonPrimitive.boolean,
                    warning =
                        obj["warning"]?.jsonObject?.let {
                            NoteLengthWarning(
                                key = it.getValue("key").jsonPrimitive.content,
                                maxLength = it.getValue("max").jsonPrimitive.int,
                                actualLength = it.getValue("actual").jsonPrimitive.int
                            )
                        }
                )
            DELETED -> NoteWriteOutcome.Deleted(id)
            else -> throw IllegalArgumentException("Unknown stored notes_write result kind '$kind'")
        }
    }
}
