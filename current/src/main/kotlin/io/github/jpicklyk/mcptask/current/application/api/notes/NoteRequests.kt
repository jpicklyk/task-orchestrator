package io.github.jpicklyk.mcptask.current.application.api.notes

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import kotlinx.serialization.Serializable

/**
 * The one documentation text of each note field that appears in more than one request class of `notes_read` or
 * `notes_write`: the same field name must generate the same schema in every select/op of a tool.
 */
object NoteDocs {
    const val ITEM_ID = "Id of the work item that owns the note: a full UUID or 8 to 32 hex characters of one."
    const val KEY = "Note key, unique per item (1 to 200 characters, case-sensitive)."
    const val ROLE = "Note role: queue, work or review (case-insensitive)."
    const val LIMIT = "Page size, 1 to 200 (default 50)."
    const val CURSOR = "Opaque cursor from the previous page's page.next."
    const val EXPECTED_VERSION =
        "Guard on the note's version: omit to write unconditionally, 0 to require that the note does not exist, " +
            "n to require that its current version is n. Anything else is version_conflict and nothing is written."
}

/** `notes_read get`: one note of one item, with its body. */
@Serializable
data class NotesGetRequest(
    @Doc(NoteDocs.ITEM_ID) val itemId: IdRef,
    @Doc(NoteDocs.KEY) val key: String,
    @Doc(
        "The version the caller already holds (at least 1). When it equals the current version " +
            "the answer is {unchanged: true, version} with no body."
    )
    val ifVersion: Long? = null
)

/** `notes_read list`: the notes of one item, metadata only (never a body). */
@Serializable
data class NotesListRequest(
    @Doc(NoteDocs.ITEM_ID) val itemId: IdRef,
    @Doc("Only notes of this role: queue, work or review (case-insensitive).") val role: String? = null,
    @Doc("Only notes with one of these keys. An empty list matches nothing.") val keys: List<String>? = null,
    @Doc(NoteDocs.LIMIT) val limit: Int? = null,
    @Doc(NoteDocs.CURSOR) val cursor: String? = null
)

/** `notes_write upsert`: create the note or replace the existing one in place. */
@Serializable
data class NoteUpsertRequest(
    @Doc(NoteDocs.ITEM_ID) val itemId: IdRef,
    @Doc(NoteDocs.KEY) val key: String,
    @Doc(NoteDocs.ROLE) val role: String,
    @Doc("Inline note text (may be empty). Exactly one of body and bodyFromFile is required.") val body: String? = null,
    @Doc(
        "Server-side path whose text becomes the body. Exactly one of body and bodyFromFile is required. " +
            "Resolved strictly inside the agent config root: no absolute path, no '..', no symlink out of it; " +
            "a regular UTF-8 file of at most 65536 bytes; CRLF becomes LF. " +
            "An idempotency replay does not re-read the file."
    )
    val bodyFromFile: String? = null,
    @Doc(NoteDocs.EXPECTED_VERSION) val expectedVersion: Long? = null,
    @Doc("Admin only: take over a note owned by another principal. Never with a seat token.") val takeover: Boolean? = null
)

/** `notes_write delete`: remove one note. */
@Serializable
data class NoteDeleteRequest(
    @Doc(NoteDocs.ITEM_ID) val itemId: IdRef,
    @Doc(NoteDocs.KEY) val key: String,
    @Doc(NoteDocs.EXPECTED_VERSION) val expectedVersion: Long? = null
)
