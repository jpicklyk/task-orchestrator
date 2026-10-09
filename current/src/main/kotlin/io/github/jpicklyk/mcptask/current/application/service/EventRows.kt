package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.NoteRef
import io.github.jpicklyk.mcptask.current.domain.event.DeleteCause
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.util.UUID

/*
 * The typed rows the write services record (plan section 3.7). Each service records its own rows through
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

/** `dependency.added` for [dep] under [root] (the from item's root). */
internal fun dependencyAddedEvent(
    dep: Dependency,
    root: UUID
): DomainEvent = DomainEvent.DependencyAdded(dep.id, root, dep.fromItemId, dep.toItemId, dep.type.name.lowercase(), dep.unblockAt)

/** `dependency.removed` for [dep] under [root]. */
internal fun dependencyRemovedEvent(
    dep: Dependency,
    root: UUID,
    cause: DeleteCause
): DomainEvent = DomainEvent.DependencyRemoved(dep.id, root, dep.fromItemId, dep.toItemId, dep.type.name.lowercase(), dep.unblockAt, cause)

/**
 * The item fields whose change an `item.updated` row reports (bookkeeping fields excluded: version, timestamps,
 * role, which a transition row covers). `["*"]` when there is no prior row.
 */
internal fun itemChangedFields(
    old: WorkItem?,
    new: WorkItem
): List<String> {
    if (old == null) return listOf("*")
    return buildList {
        if (old.title != new.title) add("title")
        if (old.description != new.description) add("description")
        if (old.summary != new.summary) add("summary")
        if (old.statusLabel != new.statusLabel) add("statusLabel")
        if (old.previousRole != new.previousRole) add("previousRole")
        if (old.priority != new.priority) add("priority")
        if (old.complexity != new.complexity) add("complexity")
        if (old.requiresVerification != new.requiresVerification) add("requiresVerification")
        if (old.rootId != new.rootId) add("rootId")
        if (old.depth != new.depth) add("depth")
        if (old.metadata != new.metadata) add("metadata")
        if (old.tags != new.tags) add("tags")
        if (old.type != new.type) add("type")
        if (old.properties != new.properties) add("properties")
        if (old.claimedBy != new.claimedBy) add("claimedBy")
        if (old.claimExpiresAt != new.claimExpiresAt) add("claimExpiresAt")
    }
}
