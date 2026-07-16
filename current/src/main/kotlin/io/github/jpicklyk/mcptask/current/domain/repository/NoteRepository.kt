package io.github.jpicklyk.mcptask.current.domain.repository

import io.github.jpicklyk.mcptask.current.domain.model.Note
import java.util.UUID

interface NoteRepository {
    suspend fun getById(id: UUID): Result<Note>

    suspend fun upsert(note: Note): Result<Note>

    suspend fun delete(id: UUID): Result<Boolean>

    suspend fun deleteByItemId(itemId: UUID): Result<Int>

    suspend fun findByItemId(
        itemId: UUID,
        role: String? = null
    ): Result<List<Note>>

    suspend fun findByItemIdAndKey(
        itemId: UUID,
        key: String
    ): Result<Note?>

    suspend fun findByItemIds(itemIds: Set<UUID>): Result<Map<UUID, List<Note>>>

    /**
     * Full-text search on note bodies using the V7 FTS5 virtual tables.
     *
     * **H2 (test environment):** FTS5 is SQLite-only. Implementations return an empty
     * [SearchResult] immediately when the current dialect is H2.
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
