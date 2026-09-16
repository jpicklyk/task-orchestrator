package io.github.jpicklyk.mcptask.current.domain.repository

import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.util.UUID

// ---------------------------------------------------------------------------
// FTS5 search types — shared by WorkItemRepository.ftsSearch and
// NoteRepository.ftsSearch. Defined in the domain layer (not infrastructure)
// so the repository interfaces can reference them without depending on the
// concrete SQLite implementation. RRF fusion is delegated to RrfFusion
// (application.service.search layer). BacklinkRow is in domain.model to
// keep the domain boundary clean.
// ---------------------------------------------------------------------------

/**
 * Number of candidate rows fetched from EACH FTS5 virtual table before RRF fusion.
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
 * Also the upper bound applied to the `limit` parameter of a search call.
 */
const val MAX_FTS_RESULTS: Int = 100

/** Controls which FTS5 virtual table(s) are queried during a search call. */
enum class SearchMatchMode {
    /** Query both trigram and text tables; fuse via RRF (k=60). Default. */
    AUTO,

    /** Query only the trigram table (substring / case-insensitive matching). */
    SUBSTRING,

    /** Query only the porter+unicode61 text table (stemming / natural language). */
    TEXT,
}

/**
 * Structural scope filters applied on top of the FTS5 full-text match.
 *
 * @property itemId     Narrow to a single work item (only content produced by that item).
 * @property ancestorId Narrow to a subtree rooted at this item (recursive CTE). Singular form;
 *   takes precedence when both [ancestorId] and [ancestorIds] are set.
 * @property ancestorIds Narrow to descendants of ANY of these roots (multi-root, additive OR).
 *   Only used when [ancestorId] is null. Null means no subtree filter (unrestricted). An empty
 *   set means "no roots match" and results in an always-false WHERE clause (no hits).
 * @property tags      OR-match any of the supplied tags on the work item.
 * @property role      Exact role filter on the work item.
 */
data class SearchScope(
    val itemId: UUID? = null,
    val ancestorId: UUID? = null,
    val ancestorIds: Set<UUID>? = null,
    val tags: List<String>? = null,
    val role: Role? = null,
)

/**
 * A single ranked match returned by a search call.
 *
 * @property kind        "item" for work-item hits, "note" for note body hits.
 * @property itemId      UUID of the owning work item.
 * @property noteKey     Note key (only present when [kind] == "note").
 * @property field       Which field matched ("title", "summary", or "body").
 * @property snippet     ~32-token excerpt with `<mark>…</mark>` delimiters.
 * @property score       Descending RRF fused score (higher = more relevant).
 * @property matchedIn   Which FTS table(s) contributed to this hit.
 * @property trigramRank Raw BM25 rank from the trigram table (lower is better; null if not matched).
 * @property textRank    Raw BM25 rank from the text table (lower is better; null if not matched).
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
)

/**
 * Paginated result container returned by search calls.
 *
 * **Pagination contract.** Every page of a query is a slice of ONE ordered list. The
 * repository fetches a fixed [FTS_CANDIDATE_ROWS] rows per FTS table (independent of the
 * requested offset), fuses them with RRF into a TOTAL order — fused score descending,
 * ties broken ascending by a stable domain id (work-item id for item hits, note id for
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
 *   from the page-invariant [totalHits], so it no longer varies with the offset that
 *   produced this page. An offset at or beyond [totalHits] yields an empty page and null.
 * @property truncated  True when more than [MAX_FTS_RESULTS] fused matches existed.
 */
data class SearchResult(
    val hits: List<SearchHit>,
    val totalHits: Int,
    val nextOffset: Int?,
    val truncated: Boolean = false,
)
