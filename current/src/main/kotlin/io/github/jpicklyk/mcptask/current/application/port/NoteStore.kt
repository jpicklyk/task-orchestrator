package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import java.time.Instant
import java.util.UUID

/** A note's identity without its body ([NoteStore.findRefsByItemIds]). */
data class NoteRef(
    val id: UUID,
    val itemId: UUID,
    val key: String,
    val role: String
)

/**
 * A note without its body ([NoteStore.findSummariesByItemId]): what a list needs. [bodyLength] is the body's length
 * in Unicode code points, as SQLite's `LENGTH` counts it (a supplementary character is one, not two UTF-16 chars).
 */
data class NoteSummary(
    val id: UUID,
    val itemId: UUID,
    val key: String,
    val role: String,
    val version: Long,
    val ownerId: String?,
    val bodyLength: Int,
    val modifiedAt: Instant,
    val actorClaim: ActorClaim?,
    val verification: VerificationResult?,
    /** The owner's seat ([io.github.jpicklyk.mcptask.current.domain.model.Note.seat]); null for a principal-level owner. */
    val ownerSeat: String? = null
)

interface NoteStore {
    suspend fun getById(id: UUID): Note?

    /**
     * Inserts the note, or updates the existing `(itemId, key)` row in place (its id and creation time are kept).
     * The store owns [Note.version]: a new row is stored with version 1 and an update goes up by one, whatever
     * version [note] carries. Returns the stored row, version included.
     */
    @StoreWrite
    suspend fun upsert(note: Note): Note

    @StoreWrite
    suspend fun delete(id: UUID): Boolean

    @StoreWrite
    suspend fun deleteByItemId(itemId: UUID): Int

    suspend fun findByItemId(
        itemId: UUID,
        role: String? = null
    ): List<Note>

    suspend fun findByItemIdAndKey(
        itemId: UUID,
        key: String
    ): Note?

    suspend fun findByItemIds(itemIds: Set<UUID>): Map<UUID, List<Note>>

    /**
     * The owning work item of each note in [noteIds] that exists; absent ids are omitted. The default reads note by
     * note; the SQLite store overrides it with one column-narrow read per chunk.
     */
    suspend fun ownersOf(noteIds: Set<UUID>): Map<UUID, UUID> = noteIds.mapNotNull { id -> getById(id)?.let { id to it.itemId } }.toMap()

    /**
     * The identity of every note on [itemIds] (id, item, key, role), grouped by item, WITHOUT the bodies: what a
     * cascade audit needs before a bulk delete. The default derives it from [findByItemIds]; the SQLite store
     * overrides it with a column-narrow read.
     */
    suspend fun findRefsByItemIds(itemIds: Set<UUID>): Map<UUID, List<NoteRef>> =
        findByItemIds(itemIds).mapValues { (_, notes) -> notes.map { NoteRef(it.id, it.itemId, it.key, it.role) } }

    /**
     * The notes on [itemId] without their bodies, optionally only those of [role] and only those whose key is in
     * [keys] (an empty set matches nothing). Unordered. The default derives it from [findByItemId]; the SQLite store
     * overrides it with a column-narrow read that never loads the body column.
     */
    suspend fun findSummariesByItemId(
        itemId: UUID,
        role: String? = null,
        keys: Set<String>? = null
    ): List<NoteSummary> =
        findByItemId(itemId, role)
            .filter { keys == null || it.key in keys }
            .map {
                NoteSummary(
                    it.id,
                    it.itemId,
                    it.key,
                    it.role,
                    it.version,
                    it.ownerId,
                    it.body.codePointCount(0, it.body.length),
                    it.modifiedAt,
                    it.actorClaim,
                    it.verification,
                    it.seat
                )
            }
}
