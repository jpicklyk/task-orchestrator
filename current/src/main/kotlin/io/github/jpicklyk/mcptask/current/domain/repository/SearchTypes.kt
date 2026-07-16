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
 * @property hits       Ranked list of matching hits.
 * @property totalHits  Total hits in the in-memory RRF-fused list for this call.
 *   The repository fetches up to `effectiveLimit + offset + 1` rows per FTS table,
 *   so for large result sets the true database total may exceed [totalHits]. This is
 *   the page-bounded count, not the global match count. When [truncated] is true,
 *   refine the query or use scope filters to narrow results.
 * @property nextOffset Offset to pass for the next page, or null when exhausted.
 * @property truncated  True when totalHits exceeds the hard cap of 100.
 */
data class SearchResult(
    val hits: List<SearchHit>,
    val totalHits: Int,
    val nextOffset: Int?,
    val truncated: Boolean = false,
)
