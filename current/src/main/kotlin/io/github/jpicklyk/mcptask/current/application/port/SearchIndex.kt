package io.github.jpicklyk.mcptask.current.application.port

/** Full-text search over work items (the V7 FTS5 virtual tables). */
interface SearchIndex {
    /**
     * Full-text search on work items using the V7 FTS5 virtual tables.
     *
     * @param sanitizedFtsQuery FTS5 query string. Callers (QueryItemsTool / FtsQuerySanitizer)
     *   are responsible for sanitizing user input before calling this method. Passing raw user
     *   input may cause FTS5 syntax errors.
     * @param matchMode Which FTS table(s) to query.
     * @param scope     Optional structural scope filters (subtree, tags, role).
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
