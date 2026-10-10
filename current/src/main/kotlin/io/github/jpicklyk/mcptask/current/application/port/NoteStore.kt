package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.Note
import java.util.UUID

/** A note's identity without its body ([NoteStore.findRefsByItemIds]). */
data class NoteRef(
    val id: UUID,
    val itemId: UUID,
    val key: String,
    val role: String
)

interface NoteStore {
    suspend fun getById(id: UUID): Note?

    suspend fun upsert(note: Note): Note

    suspend fun delete(id: UUID): Boolean

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
     * The identity of every note on [itemIds] (id, item, key, role), grouped by item, WITHOUT the bodies: what a
     * cascade audit needs before a bulk delete. The default derives it from [findByItemIds]; the SQLite store
     * overrides it with a column-narrow read.
     */
    suspend fun findRefsByItemIds(itemIds: Set<UUID>): Map<UUID, List<NoteRef>> =
        findByItemIds(itemIds).mapValues { (_, notes) -> notes.map { NoteRef(it.id, it.itemId, it.key, it.role) } }
}
