package io.github.jpicklyk.mcptask.current.note.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.note.NoteStoreProvider
import io.github.jpicklyk.mcptask.current.note.NoteToolServices
import io.github.jpicklyk.mcptask.current.note.internal.app.NoteDeleteRequest
import io.github.jpicklyk.mcptask.current.note.internal.app.NoteHandlers
import io.github.jpicklyk.mcptask.current.note.internal.app.NoteUpsertRequest
import io.github.jpicklyk.mcptask.current.note.internal.app.NoteWriteOutcome
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.knowledge
import kotlinx.serialization.serializer

/** `notes_write upsert`: create or replace the note at (item, key). */
internal val noteUpsertOp =
    OpSpec<NoteToolServices, NoteUpsertRequest, NoteWriteOutcome, NoteStoreProvider>(
        name = "upsert",
        doc =
            "Create the note at (item, key) or replace it in place; its version goes up by one on every write. " +
                "Give exactly one of body and bodyFromFile. A body over the schema's maxLength for the key is accepted " +
                "with a warning, or rejected, depending on the server's note limits mode. A key the current schema requires but " +
                "the item's pinned schema lacks is schema_pinned_conflict: repin the item first.",
        profiles = knowledge(),
        destructive = false,
        capability = ApiCapability.WRITE_NOTES,
        services = NoteToolServices::class,
        stores = NoteStoreProvider::class,
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
                ErrorCode.NOTE_OWNED_BY_OTHER,
                ErrorCode.UNAUTHENTICATED
            ),
        execute = { env, ctx, req -> NoteHandlers.upsert(this, env, ctx, req) }
    )

/** `notes_write delete`: remove the note at (item, key). */
internal val noteDeleteOp =
    OpSpec<NoteToolServices, NoteDeleteRequest, NoteWriteOutcome, NoteStoreProvider>(
        name = "delete",
        doc = "Delete the note at (item, key). A missing note is not_found.",
        profiles = knowledge(),
        destructive = true,
        capability = ApiCapability.WRITE_NOTES,
        services = NoteToolServices::class,
        stores = NoteStoreProvider::class,
        request = serializer<NoteDeleteRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.VERSION_CONFLICT,
                ErrorCode.NOT_CLAIM_HOLDER,
                ErrorCode.NOTE_OWNED_BY_OTHER,
                ErrorCode.UNAUTHENTICATED
            ),
        execute = { env, ctx, req -> NoteHandlers.delete(this, env, ctx, req) }
    )
