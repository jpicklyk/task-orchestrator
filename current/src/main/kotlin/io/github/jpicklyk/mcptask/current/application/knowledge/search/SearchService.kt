package io.github.jpicklyk.mcptask.current.application.knowledge.search

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
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
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
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
 * @property corpora What to search: one or more corpora, queried in the same read unit and fused once (non-empty).
 * @property matchMode Which analyzer(s) to query: AUTO = both, fused.
 * @property itemId Only content owned by this item.
 * @property ancestorId Only content owned by this item's subtree (itself included).
 * @property roles Only content whose owning item is in one of these roles; null or empty = any.
 * @property tags The caller's tag filter (any of, case-insensitive); null or empty = none.
 * @property traits Only content whose owning item carries one of these trait names (exact); null or empty = none.
 * @property limit Page size, 1 to [MAX_FTS_RESULTS].
 * @property offset Zero-based offset into the capped result list.
 * @property access What the caller may see; applied before ranking and capping.
 */
data class SearchRequest(
    val query: String,
    val corpora: Set<Corpus>,
    val matchMode: SearchMatchMode = SearchMatchMode.AUTO,
    val itemId: UUID? = null,
    val ancestorId: UUID? = null,
    val roles: Set<Role>? = null,
    val tags: List<String>? = null,
    val traits: List<String>? = null,
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
 * Candidate retrieval, ranking, the cap/slice and the title hydration run inside ONE [UnitOfWork.read], so the
 * index statements share a single reader connection and snapshot instead of each opening its own.
 *
 * Paging follows the [SearchResult] contract: a fixed [FTS_CANDIDATE_ROWS] window per analyzer independent of the
 * offset, a total order, the cap before the slice, so `totalHits` is the same on every page.
 *
 * Telemetry: the call's `request_shape` gains a hash of the normalized terms, the term count and the match mode;
 * its result count is `totalHits`. The query text itself is never recorded.
 */
class SearchService(
    private val index: SearchIndex,
    private val unitOfWork: UnitOfWork,
) {
    /**
     * Runs [request].
     *
     * @throws SearchValidationException for an out-of-range limit or offset, a query containing a control character,
     *   an empty query, or a substring-only
     *   query with no term of at least [QueryTokenizer.SUBSTRING_MIN_TERM_LENGTH] characters.
     */
    suspend fun search(request: SearchRequest): SearchResult {
        if (request.corpora.isEmpty()) throw SearchValidationException(EMPTY_CORPORA_MESSAGE)
        if (request.limit < 1) throw SearchValidationException("limit must be at least 1")
        if (request.limit > MAX_FTS_RESULTS) throw SearchValidationException(limitTooLargeMessage(request.limit))
        if (request.offset < 0) throw SearchValidationException("offset must be non-negative")

        QueryTokenizer.controlCharacterViolation(request.query)?.let { throw SearchValidationException(it) }

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
                traitsAny = request.traits?.takeIf { it.isNotEmpty() },
                principalTagsAny = request.access.tagsInclude.takeIf { it.isNotEmpty() },
            )
        // A stable corpus order (declaration order), whatever order the caller's set iterates in.
        val corpora = Corpus.entries.filter { it in request.corpora }
        val snapshot =
            unitOfWork.read {
                // Every (corpus, analyzer) list is retrieved in this one read unit and fused ONCE, so hits of
                // different corpora share one ranking, one cap and one page.
                val lists = LinkedHashMap<Corpus, Map<Analyzer, List<Candidate>>>()
                for (corpus in corpora) {
                    val perAnalyzer = LinkedHashMap<Analyzer, List<Candidate>>()
                    for (analyzer in analyzers(request.matchMode)) {
                        perAnalyzer[analyzer] = index.candidates(corpus, textQuery, analyzer, filter, FTS_CANDIDATE_ROWS)
                    }
                    lists[corpus] = perAnalyzer
                }
                val ranked = Ranker.rank(lists)

                // Cap BEFORE slicing so totalHits (and the nextOffset derived from it) describe the whole query,
                // not the requested page: identical at every offset.
                val truncated = ranked.size > MAX_FTS_RESULTS
                val capped = if (truncated) ranked.take(MAX_FTS_RESULTS) else ranked
                val page = capped.drop(request.offset).take(request.limit)
                val titles =
                    if (page.isEmpty()) {
                        emptyMap()
                    } else {
                        index.titles(page.mapTo(LinkedHashSet()) { it.primary.ownerItemId })
                    }
                SearchSnapshot(page, capped.size, truncated, titles)
            }
        val page = snapshot.page
        val totalHits = snapshot.totalHits
        val truncated = snapshot.truncated
        val titles = snapshot.titles
        recordCallResultCounts(totalHits, null)
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

    private class SearchSnapshot(
        val page: List<RankedHit>,
        val totalHits: Int,
        val truncated: Boolean,
        val titles: Map<UUID, String>,
    )

    companion object {
        const val EMPTY_QUERY_MESSAGE = "Search query is empty. Provide at least one search term."

        /** The rejection message for a request naming no corpus. */
        const val EMPTY_CORPORA_MESSAGE = "corpora must name at least one corpus"

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
