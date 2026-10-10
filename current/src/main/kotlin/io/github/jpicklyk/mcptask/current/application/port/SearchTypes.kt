package io.github.jpicklyk.mcptask.current.application.port

import java.util.UUID

// ---------------------------------------------------------------------------
// Search result types and paging constants. The SearchService
// (application.knowledge.search) produces them from SearchIndex candidates;
// the MCP tools and the REST routes serialize them.
// ---------------------------------------------------------------------------

/**
 * Number of candidate rows fetched from EACH analyzer's index before RRF fusion.
 *
 * Deliberately FIXED and independent of the requested page. A window sized from
 * `limit + offset` would make every page fuse over a different candidate set, so a
 * document could change its fused score (and therefore its absolute position) purely
 * because a later page was requested — the cause of the duplicate/skip behaviour this
 * constant replaces.
 *
 * 200 is at least twice [MAX_FTS_RESULTS], so the whole documented paging range
 * (`offset + limit <= 100`) is served from one window and no in-range result is lost.
 */
const val FTS_CANDIDATE_ROWS: Int = 200

/**
 * Hard cap on the fused result list, applied BEFORE the page slice is taken.
 *
 * Because the cap precedes the slice, [SearchResult.totalHits] is the same on every page
 * of a query and offsets at or beyond this value return an empty page.
 * [SearchResult.truncated] is the "refine the query" signal that more matches existed.
 * Also the largest `limit` a search call accepts; a larger one is rejected.
 */
const val MAX_FTS_RESULTS: Int = 100

/** Controls which analyzer(s) a search call queries. */
enum class SearchMatchMode {
    /** Query both the substring and the stemmed analyzer; fuse via RRF (k=60). Default. */
    AUTO,

    /** Query only the substring analyzer (case-insensitive substring matching). */
    SUBSTRING,

    /** Query only the stemmed analyzer (stemming / natural language). */
    TEXT,
}

/**
 * A single ranked match returned by a search call.
 *
 * @property kind        "item" for work-item hits, "note" for note body hits.
 * @property itemId      UUID of the owning work item.
 * @property noteKey     Note key (only present when [kind] == "note").
 * @property field       The field that contains a match ("title", "summary", or "body"); title wins
 *   when both item fields match.
 * @property snippet     ~32-token excerpt of [field] with `<mark>…</mark>` delimiters.
 * @property score       Descending RRF fused score (higher = more relevant).
 * @property matchedIn   Which analyzer(s) contributed to this hit ("trigram", "text").
 * @property trigramRank Raw rank from the substring analyzer (lower is better; null if not matched).
 * @property textRank    Raw rank from the stemmed analyzer (lower is better; null if not matched).
 * @property title       Title of the owning work item (the item's own title for an item hit); null
 *   when the item could not be read.
 */
data class SearchHit(
    val kind: String,
    val itemId: UUID,
    val noteKey: String? = null,
    val field: String,
    val snippet: String,
    val score: Double,
    val matchedIn: List<String>,
    val trigramRank: Double? = null,
    val textRank: Double? = null,
    val title: String? = null,
)

/**
 * Paginated result container returned by search calls.
 *
 * **Pagination contract.** Every page of a query is a slice of ONE ordered list. The
 * search service fetches a fixed [FTS_CANDIDATE_ROWS] candidates per analyzer (independent
 * of the requested offset), fuses them with RRF into a TOTAL order — fused score descending,
 * then kind, then ascending by a stable domain id (work-item id for item hits, note id for
 * note hits) — caps that list at [MAX_FTS_RESULTS], and only then applies offset and
 * limit. Successive pages therefore partition the result list with no duplicates and no
 * skips for a given database state.
 *
 * This is a deterministic total order, not a snapshot: a write between two page calls can
 * still change which rows match. Stability across a paging session is not guaranteed.
 *
 * @property hits       Ranked list of matching hits.
 * @property totalHits  Size of the capped fused list for this query: at most
 *   [MAX_FTS_RESULTS] and identical on every page of the same query, offset included.
 *   It is NOT the global database match count — when [truncated] is true, more matches
 *   existed than the cap, so refine the query or use scope filters to narrow results.
 * @property nextOffset Offset to pass for the next page, or null when exhausted. Derived
 *   from the page-invariant [totalHits], so it does not vary with the offset that
 *   produced this page. An offset at or beyond [totalHits] yields an empty page and null.
 * @property truncated  True when more than [MAX_FTS_RESULTS] fused matches existed.
 */
data class SearchResult(
    val hits: List<SearchHit>,
    val totalHits: Int,
    val nextOffset: Int?,
    val truncated: Boolean = false,
)
