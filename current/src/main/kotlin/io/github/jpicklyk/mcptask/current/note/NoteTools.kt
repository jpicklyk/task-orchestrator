package io.github.jpicklyk.mcptask.current.note

import io.github.jpicklyk.mcptask.current.note.internal.app.NoteWriteOutcome
import io.github.jpicklyk.mcptask.current.note.internal.wire.NoteJson
import io.github.jpicklyk.mcptask.current.note.internal.wire.NoteWriteCodec
import io.github.jpicklyk.mcptask.current.note.internal.wire.noteDeleteOp
import io.github.jpicklyk.mcptask.current.note.internal.wire.noteUpsertOp
import io.github.jpicklyk.mcptask.current.note.internal.wire.notesGetSelect
import io.github.jpicklyk.mcptask.current.note.internal.wire.notesListSelect
import io.github.jpicklyk.mcptask.current.platform.tool.OpsToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.SelectToolSpec

/** The note slice's contribution to the tool registry: the `notes_read` and `notes_write` tools, in the registry's order. */
object NoteTools {
    /** The read side of the notes tools. */
    fun read(): SelectToolSpec =
        SelectToolSpec(
            name = "notes_read",
            description = "Read the notes on work items: get one note with its body, or list an item's notes without bodies.",
            selects = listOf(notesGetSelect, notesListSelect)
        )

    /** The write side of the notes tools. */
    fun write(): OpsToolSpec<NoteWriteOutcome> =
        OpsToolSpec(
            name = "notes_write",
            description =
                "Create, replace or delete notes on work items, in batches. " +
                    "Every element commits on its own unless atomic is true.",
            ops = listOf(noteUpsertOp, noteDeleteOp),
            codec = NoteWriteCodec,
            element = { NoteJson.element(it) }
        )
}
