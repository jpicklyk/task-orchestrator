package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.notes

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.notes.NoteDeleteRequest
import io.github.jpicklyk.mcptask.current.application.api.notes.NoteHandlers
import io.github.jpicklyk.mcptask.current.application.api.notes.NoteUpsertRequest
import io.github.jpicklyk.mcptask.current.application.api.notes.NoteWriteOutcome
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
import kotlinx.serialization.serializer

/** `notes_write upsert`: create or replace the note at (item, key). */
internal val noteUpsertOp =
    OpSpec<NoteUpsertRequest, NoteWriteOutcome>(
        name = "upsert",
        doc =
            "Create the note at (item, key) or replace it in place; its version goes up by one on every write. " +
                "Give exactly one of body and bodyFromFile. A body over the schema's maxLength for the key is accepted " +
                "with a warning, or rejected, depending on the server's note limits mode. A key the current schema requires but " +
                "the item's pinned schema lacks is schema_pinned_conflict: repin the item first.",
        profiles = knowledge(),
        destructive = false,
        capability = ApiCapability.WRITE_NOTES,
        request = serializer<NoteUpsertRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.VERSION_CONFLICT,
                ErrorCode.PAYLOAD_TOO_LARGE,
                ErrorCode.NOTE_TOO_LONG,
                ErrorCode.SCHEMA_VIOLATION,
                ErrorCode.SCHEMA_PINNED_CONFLICT,
                ErrorCode.NOT_CLAIM_HOLDER,
                ErrorCode.UNAUTHENTICATED
            ),
        execute = { env, ctx, req -> NoteHandlers.upsert(this, env, ctx, req) }
    )

/** `notes_write delete`: remove the note at (item, key). */
internal val noteDeleteOp =
    OpSpec<NoteDeleteRequest, NoteWriteOutcome>(
        name = "delete",
        doc = "Delete the note at (item, key). A missing note is not_found.",
        profiles = knowledge(),
        destructive = true,
        capability = ApiCapability.WRITE_NOTES,
        request = serializer<NoteDeleteRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.VERSION_CONFLICT,
                ErrorCode.NOT_CLAIM_HOLDER,
                ErrorCode.UNAUTHENTICATED
            ),
        execute = { env, ctx, req -> NoteHandlers.delete(this, env, ctx, req) }
    )

/** The write side of the notes tools. */
internal val notesWriteTool =
    OpsToolSpec<NoteWriteOutcome>(
        name = "notes_write",
        description = "Create, replace or delete notes on work items, in batches. Every element commits on its own unless atomic is true.",
        ops = listOf(noteUpsertOp, noteDeleteOp),
        codec = NoteWriteCodec,
        element = { NoteJson.element(it) }
    )
