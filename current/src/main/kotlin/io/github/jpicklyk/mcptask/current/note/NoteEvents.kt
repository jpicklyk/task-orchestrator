package io.github.jpicklyk.mcptask.current.note

import io.github.jpicklyk.mcptask.current.model.event.DeleteCause
import io.github.jpicklyk.mcptask.current.model.event.DomainEvent
import io.github.jpicklyk.mcptask.current.model.note.Note
import java.util.UUID

/*
 * The typed rows the note write services record. Each service records its own rows through
 * `WriteScope.events`, inside its own unit; these builders keep the payload shapes in one place.
 */

/**
 * `note.upserted` for [note] under [root]; the principal is the note's own actor claim and verification.
 * [bodyFromFile] is the caller-supplied path when the body came from a file (null for an inline body).
 */
internal fun noteUpsertedEvent(
    note: Note,
    root: UUID,
    bodyFromFile: String? = null
): DomainEvent =
    DomainEvent.NoteUpserted(
        entityId = note.id,
        rootId = root,
        itemId = note.itemId,
        key = note.key,
        role = note.role,
        bodyLength = note.body.length,
        actor = note.actorClaim,
        verification = note.verification,
        bodyFromFile = bodyFromFile
    )

/**
 * `note.ownership_transferred` for [note] under [root]: owner [from] (null for an unowned note, whose pre-4.0 author is
 * [legacyActor]) gave the note up to [to].
 */
internal fun noteOwnershipTransferredEvent(
    note: Note,
    root: UUID,
    from: String?,
    to: String,
    legacyActor: String? = null
): DomainEvent = DomainEvent.NoteOwnershipTransferred(note.id, root, note.itemId, note.key, from, to, legacyActor)

/** `note.deleted` for [note] under [root]. */
internal fun noteDeletedEvent(
    note: Note,
    root: UUID,
    cause: DeleteCause
): DomainEvent = DomainEvent.NoteDeleted(note.id, root, note.itemId, note.key, note.role, cause)

/** `note.deleted` for a note known only by its [NoteRef] (a cascade delete pre-read). */
internal fun noteDeletedEvent(
    ref: NoteRef,
    root: UUID,
    cause: DeleteCause
): DomainEvent = DomainEvent.NoteDeleted(ref.id, root, ref.itemId, ref.key, ref.role, cause)
