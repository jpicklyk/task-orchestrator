package io.github.jpicklyk.mcptask.current.application.knowledge.search

import io.github.jpicklyk.mcptask.current.application.port.Analyzer
import io.github.jpicklyk.mcptask.current.application.port.Candidate
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.FTS_CANDIDATE_ROWS
import io.github.jpicklyk.mcptask.current.application.port.MAX_FTS_RESULTS
import io.github.jpicklyk.mcptask.current.application.port.ScopeFilter
import io.github.jpicklyk.mcptask.current.application.port.SearchHit
import io.github.jpicklyk.mcptask.current.application.port.SearchIndex
import io.github.jpicklyk.mcptask.current.application.port.SearchMatchMode
import io.github.jpicklyk.mcptask.current.application.port.SearchResult
import io.github.jpicklyk.mcptask.current.application.port.TextQuery
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallResultCounts
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallSearchShape
import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * One search call.
 *
 * @property query The raw user text; split into literal terms by [QueryTokenizer].
 * @property corpus What to search.
 * @property matchMode Which analyzer(s) to query: AUTO = both, fused.
 * @property itemId Only content owned by this item.
 * @property ancestorId Only content owned by this item's subtree (itself included).
 * @property roles Only content whose owning item is in one of these roles; null or empty = any.
 * @property tags The caller's tag filter (any of, case-insensitive); null or empty = none.
 * @property limit Page size, 1 to [MAX_FTS_RESULTS].
 * @property offset Zero-based offset into the capped result list.
 * @property access What the caller may see; applied before ranking and capping.
 */
data class SearchRequest(
    val query: String,
    val corpus: Corpus,
    val matchMode: SearchMatchMode = SearchMatchMode.AUTO,
    val itemId: UUID? = null,
    val ancestorId: UUID? = null,
    val roles: Set<Role>? = null,
    val tags: List<String>? = null,
    val limit: Int = DEFAULT_LIMIT,
    val offset: Int = 0,
    val access: AccessScope,
) {
    companion object {
        /** Page size when the caller names none (MCP tools). */
        const val DEFAULT_LIMIT = 20
    }
}

/** A search request the service refuses; the message is safe to return to the caller. */
class SearchValidationException(
    message: String
) : IllegalArgumentException(message)

/**
 * The search pipeline shared by every transport: validate, tokenize, plan (corpus by analyzer), retrieve
 * candidates, fuse in [Ranker], order, cap at [MAX_FTS_RESULTS], slice the page, and hydrate owning-item titles in
 * one read.
 *
 * Paging follows the [SearchResult] contract: a fixed [FTS_CANDIDATE_ROWS] window per analyzer independent of the
 * offset, a total order, the cap before the slice, so `totalHits` is the same on every page.
 *
 * Telemetry: the call's `request_shape` gains a hash of the normalized terms, the term count and the match mode;
 * its result count is `totalHits`. The query text itself is never recorded.
 */
class SearchService(
    private val index: SearchIndex,
) {
    /**
     * Runs [request].
     *
     * @throws SearchValidationException for an out-of-range limit or offset, an empty query, or a substring-only
     *   query with no term of at least [QueryTokenizer.SUBSTRING_MIN_TERM_LENGTH] characters.
     */
    suspend fun search(request: SearchRequest): SearchResult {
        if (request.limit < 1) throw SearchValidationException("limit must be at least 1")
        if (request.limit > MAX_FTS_RESULTS) throw SearchValidationException(limitTooLargeMessage(request.limit))
        if (request.offset < 0) throw SearchValidationException("offset must be non-negative")

        val terms = QueryTokenizer.tokenize(request.query)
        if (terms.isEmpty()) throw SearchValidationException(EMPTY_QUERY_MESSAGE)
        recordCallSearchShape(queryHash(terms), terms.size, request.matchMode.name.lowercase(Locale.ROOT))
        if (request.matchMode == SearchMatchMode.SUBSTRING) {
            QueryTokenizer.substringViolation(terms)?.let { throw SearchValidationException(it) }
        }

        val textQuery = TextQuery(terms)
        val filter =
            ScopeFilter(
                itemId = request.itemId,
                ancestorId = request.ancestorId,
                rootIds = request.access.rootIds,
                roles = request.roles?.takeIf { it.isNotEmpty() },
                tagsAny = request.tags?.takeIf { it.isNotEmpty() },
                principalTagsAny = request.access.tagsInclude.takeIf { it.isNotEmpty() },
            )
        val perAnalyzer = LinkedHashMap<Analyzer, List<Candidate>>()
        for (analyzer in analyzers(request.matchMode)) {
            perAnalyzer[analyzer] = index.candidates(request.corpus, textQuery, analyzer, filter, FTS_CANDIDATE_ROWS)
        }
        val ranked = Ranker.rank(mapOf(request.corpus to perAnalyzer))

        // Cap BEFORE slicing so totalHits (and the nextOffset derived from it) describe the whole query, not the
        // requested page: identical at every offset.
        val truncated = ranked.size > MAX_FTS_RESULTS
        val capped = if (truncated) ranked.take(MAX_FTS_RESULTS) else ranked
        val totalHits = capped.size
        val page = capped.drop(request.offset).take(request.limit)
        recordCallResultCounts(totalHits, null)

        val titles =
            if (page.isEmpty()) emptyMap() else index.titles(page.mapTo(LinkedHashSet()) { it.primary.ownerItemId })
        val hits =
            page.map { hit ->
                SearchHit(
                    kind = hit.corpus.kind,
                    itemId = hit.primary.ownerItemId,
                    noteKey = hit.primary.noteKey,
                    field = hit.primary.field,
                    snippet = hit.primary.snippet,
                    score = hit.score,
                    matchedIn = hit.matchedIn,
                    trigramRank = hit.trigramRank,
                    textRank = hit.textRank,
                    title = titles[hit.primary.ownerItemId],
                )
            }
        val nextOffset = if (request.offset + request.limit < totalHits) request.offset + request.limit else null
        return SearchResult(hits = hits, totalHits = totalHits, nextOffset = nextOffset, truncated = truncated)
    }

    companion object {
        const val EMPTY_QUERY_MESSAGE = "Search query is empty. Provide at least one search term."

        /** Hex characters of the SHA-256 digest kept in `queryHash`. */
        const val QUERY_HASH_HEX_LENGTH = 16

        /** The rejection message for a `limit` above [MAX_FTS_RESULTS]. */
        fun limitTooLargeMessage(limit: Int): String = "limit must be at most $MAX_FTS_RESULTS for a search (got $limit)"

        /** The analyzers [mode] queries, substring first. */
        fun analyzers(mode: SearchMatchMode): List<Analyzer> =
            when (mode) {
                SearchMatchMode.AUTO -> listOf(Analyzer.SUBSTRING, Analyzer.STEMMED)
                SearchMatchMode.SUBSTRING -> listOf(Analyzer.SUBSTRING)
                SearchMatchMode.TEXT -> listOf(Analyzer.STEMMED)
            }

        /**
         * The first [QUERY_HASH_HEX_LENGTH] lowercase hex characters of the unsalted SHA-256 of [terms], each
         * lowercased (root locale) and joined by single spaces. Groups repeats of one query without storing its
         * text; a short or common query can be recovered by hashing candidate words.
         */
        fun queryHash(terms: List<String>): String {
            val normalized = terms.joinToString(" ") { it.lowercase(Locale.ROOT) }
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(QUERY_HASH_HEX_LENGTH)
        }
    }
}
