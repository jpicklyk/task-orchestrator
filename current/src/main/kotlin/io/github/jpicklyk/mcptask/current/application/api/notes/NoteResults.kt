package io.github.jpicklyk.mcptask.current.application.api.notes

import io.github.jpicklyk.mcptask.current.application.service.NoteLengthWarning
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import java.time.Instant
import java.util.UUID

/** A note's owner as a view shows it: the owning principal's audited [id] (notes.owner_id) and its [seat] (notes.owner_seat), when owned under a seat token. */
data class NoteOwner(
    val id: String,
    val seat: String? = null
)

/**
 * A note as `notes_read get` shows it. [actor] and [verification] are already filtered by the call's attribution
 * visibility (null when hidden or absent). Proof evidence (the proof hash and verified claims) is never part of a view:
 * [VerificationResult] is handed over whole, but the wire mapping reads only its status, verifier and reason.
 */
data class NoteView(
    val id: UUID,
    val key: String,
    val role: String,
    val version: Long,
    val body: String,
    val modifiedAt: Instant,
    val actor: ActorClaim?,
    val verification: VerificationResult?,
    val owner: NoteOwner? = null
)

/** What `notes_read get` answers: the note, or that the caller's `ifVersion` is still current. */
sealed interface NoteGetResult {
    data class Found(
        val note: NoteView
    ) : NoteGetResult

    data class Unchanged(
        val version: Long
    ) : NoteGetResult
}

/** A note as `notes_read list` shows it: no body, a [shortId] of the first [SHORT_ID_LENGTH] hex characters of its id. */
data class NoteListEntry(
    val shortId: String,
    val key: String,
    val role: String,
    val version: Long,
    val bodyLength: Int,
    val modifiedAt: Instant,
    val actor: ActorClaim?,
    val verification: VerificationResult?,
    val owner: NoteOwner? = null
) {
    companion object {
        const val SHORT_ID_LENGTH = 8
    }
}

/** What one `notes_write` op answered: the single result sum type of the tool. */
sealed interface NoteWriteOutcome {
    /** The note [id] now stands at [version]; [created] is true when this write created it. */
    data class Upserted(
        val id: UUID,
        val version: Long,
        val created: Boolean,
        val warning: NoteLengthWarning?
    ) : NoteWriteOutcome

    /** The note [id] was deleted. */
    data class Deleted(
        val id: UUID
    ) : NoteWriteOutcome
}
