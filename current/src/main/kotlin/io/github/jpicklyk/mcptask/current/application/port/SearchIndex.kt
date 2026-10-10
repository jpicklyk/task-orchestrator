package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.util.UUID

/** The searchable bodies of text. Each corpus is one kind of hit (`item` or `note`). */
enum class Corpus(
    val kind: String
) {
    /** Work-item title and summary. */
    ITEM("item"),

    /** Note bodies; every hit is owned by the note's work item. */
    NOTE("note"),
}

/**
 * How the terms of a [TextQuery] are matched. Dialect-free: the adapter maps each analyzer onto its own index.
 *
 * - [SUBSTRING]: case-insensitive substring matching; a term shorter than 3 characters never matches.
 * - [STEMMED]: word matching with stemming (`run` finds `running`).
 */
enum class Analyzer {
    SUBSTRING,
    STEMMED,
}

/**
 * The terms of one search, matched with AND semantics. A term is plain text: it carries no query syntax, so
 * operators, quotes and wildcards in a term are matched literally. The adapter owns any quoting its engine needs.
 */
data class TextQuery(
    val terms: List<String>
) {
    init {
        require(terms.isNotEmpty()) { "a text query needs at least one term" }
        require(terms.none { it.isEmpty() }) { "a text query term must not be empty" }
    }
}

/**
 * Structural filters applied to the item that owns each candidate (the item itself for [Corpus.ITEM], the note's
 * item for [Corpus.NOTE]), all combined with AND and applied BEFORE the candidate window is taken.
 *
 * @property itemId Only content owned by this item.
 * @property ancestorId Only content owned by this item or its descendants (bounded walk).
 * @property rootIds Access scope: only content owned by an item whose ancestor chain (itself included) contains one
 *   of these ids. Null means unrestricted; an EMPTY set means nothing is visible. Ids need not be depth-0.
 * @property roles Only content whose owning item is in one of these roles. Null or empty means any role.
 * @property tagsAny The caller's tag filter: the owning item carries at least one of these tags, compared
 *   case-insensitively. Null or empty means no tag filter.
 * @property principalTagsAny Access scope tag allowlist: the owning item carries at least one of these tags, compared
 *   exactly (case-sensitive, after trimming each element of the item's comma-separated tag list). Null or empty
 *   means no tag constraint.
 */
data class ScopeFilter(
    val itemId: UUID? = null,
    val ancestorId: UUID? = null,
    val rootIds: Set<UUID>? = null,
    val roles: Set<Role>? = null,
    val tagsAny: List<String>? = null,
    val principalTagsAny: Set<String>? = null,
)

/**
 * One ranked match from one analyzer over one corpus.
 *
 * @property id The stable id of the matched document: the work-item id ([Corpus.ITEM]) or the note id ([Corpus.NOTE]).
 * @property ownerItemId The work item that owns the document (equal to [id] for [Corpus.ITEM]).
 * @property noteKey The note key ([Corpus.NOTE] only).
 * @property rank The engine's raw relevance rank (lower is better). Comparable only within one candidate list.
 * @property field The field that actually contains a match (`title`/`summary` for items, `body` for notes); when
 *   several do, the first in the corpus's field order (title before summary).
 * @property snippet A short excerpt of [field] with `<mark>`/`</mark>` around the matched text.
 */
data class Candidate(
    val id: UUID,
    val ownerItemId: UUID,
    val noteKey: String?,
    val rank: Double,
    val field: String,
    val snippet: String,
)

/**
 * Full-text candidate retrieval: the storage half of search. Ranking, fusion, paging and access policy live in the
 * application's search service; this port only matches, filters and orders by the engine's own rank.
 */
interface SearchIndex {
    /**
     * The best [window] matches for [query] in [corpus] under [analyzer], in rank order (best first), restricted by
     * [filter] before the window is taken.
     */
    suspend fun candidates(
        corpus: Corpus,
        query: TextQuery,
        analyzer: Analyzer,
        filter: ScopeFilter,
        window: Int = FTS_CANDIDATE_ROWS,
    ): List<Candidate>

    /** The title of each of [itemIds] that exists, in one read. Absent ids are omitted. */
    suspend fun titles(itemIds: Set<UUID>): Map<UUID, String>
}
