package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.Note
import java.util.UUID

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
     * Full-text search on note bodies using the V7 FTS5 virtual tables.
     *
     * @param sanitizedFtsQuery FTS5 query string, already sanitized by the caller (QueryNotesTool).
     * @param matchMode Which FTS table(s) to query.
     * @param scope     Optional structural scope filters. [SearchScope.itemId] narrows to notes on
     *   that specific item; [SearchScope.ancestorId] narrows to notes whose item_id is in the subtree.
     * @param limit     Maximum hits to return (enforced at 100; default 20).
     * @param offset    Zero-based page offset.
     */
    suspend fun ftsSearch(
        sanitizedFtsQuery: String,
        matchMode: SearchMatchMode = SearchMatchMode.AUTO,
        scope: SearchScope? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): SearchResult
}
