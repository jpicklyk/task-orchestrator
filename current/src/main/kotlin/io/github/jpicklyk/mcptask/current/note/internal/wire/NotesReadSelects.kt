package io.github.jpicklyk.mcptask.current.note.internal.wire

import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.note.NoteToolServices
import io.github.jpicklyk.mcptask.current.note.internal.app.NoteGetResult
import io.github.jpicklyk.mcptask.current.note.internal.app.NoteHandlers
import io.github.jpicklyk.mcptask.current.note.internal.app.NoteListEntry
import io.github.jpicklyk.mcptask.current.note.internal.app.NotesGetRequest
import io.github.jpicklyk.mcptask.current.note.internal.app.NotesListRequest
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.tool.SelectSpec
import io.github.jpicklyk.mcptask.current.platform.tool.knowledge
import kotlinx.serialization.serializer

/** `notes_read get`: one note with its body (or `{unchanged, version}` when the caller's `ifVersion` is current). */
internal val notesGetSelect =
    SelectSpec.Single<NoteToolServices, NotesGetRequest, NoteGetResult>(
        name = "get",
        doc =
            "Read one note of an item by key, with its body. " +
                "With ifVersion equal to the note's current version the answer is {unchanged: true, version} and no body.",
        profiles = knowledge(),
        services = NoteToolServices::class,
        request = serializer<NotesGetRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        capability = ApiCapability.READ,
        handler = { env, ctx, req -> NoteHandlers.get(env, ctx, req) },
        data = { NoteJson.getResult(it) }
    )

/** `notes_read list`: the notes of one item, metadata only. */
internal val notesListSelect =
    SelectSpec.Paged<NoteToolServices, NotesListRequest, NoteListEntry>(
        name = "list",
        doc =
            "List the notes of an item without their bodies (id, key, role, version, bodyLength, modifiedAt), " +
                "ordered by key. Read a body with get.",
        profiles = knowledge(),
        services = NoteToolServices::class,
        request = serializer<NotesListRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        capability = ApiCapability.READ,
        handler = { env, ctx, req -> NoteHandlers.list(env, ctx, req) },
        item = { NoteJson.entry(it) }
    )
