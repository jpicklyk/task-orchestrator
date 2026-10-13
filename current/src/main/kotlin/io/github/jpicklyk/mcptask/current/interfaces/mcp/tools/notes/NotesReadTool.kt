package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.notes

import io.github.jpicklyk.mcptask.current.application.api.notes.NoteGetResult
import io.github.jpicklyk.mcptask.current.application.api.notes.NoteHandlers
import io.github.jpicklyk.mcptask.current.application.api.notes.NoteListEntry
import io.github.jpicklyk.mcptask.current.application.api.notes.NotesGetRequest
import io.github.jpicklyk.mcptask.current.application.api.notes.NotesListRequest
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
import kotlinx.serialization.serializer

/** `notes_read get`: one note with its body (or `{unchanged, version}` when the caller's `ifVersion` is current). */
internal val notesGetSelect =
    SelectSpec.Single<NotesGetRequest, NoteGetResult>(
        name = "get",
        doc =
            "Read one note of an item by key, with its body. " +
                "With ifVersion equal to the note's current version the answer is {unchanged: true, version} and no body.",
        profiles = knowledge(),
        request = serializer<NotesGetRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        capability = ApiCapability.READ,
        handler = { env, ctx, req -> NoteHandlers.get(env, ctx, req) },
        data = { NoteJson.getResult(it) }
    )

/** `notes_read list`: the notes of one item, metadata only. */
internal val notesListSelect =
    SelectSpec.Paged<NotesListRequest, NoteListEntry>(
        name = "list",
        doc =
            "List the notes of an item without their bodies (id, key, role, version, bodyLength, modifiedAt), " +
                "ordered by key. Read a body with get.",
        profiles = knowledge(),
        request = serializer<NotesListRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        capability = ApiCapability.READ,
        handler = { env, ctx, req -> NoteHandlers.list(env, ctx, req) },
        item = { NoteJson.entry(it) }
    )

/** The read side of the notes tools. */
internal val notesReadTool =
    SelectToolSpec(
        name = "notes_read",
        description = "Read the notes on work items: get one note with its body, or list an item's notes without bodies.",
        selects = listOf(notesGetSelect, notesListSelect)
    )
