package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.util.UUID

/**
 * The searchable bodies of text. Each corpus is one kind of hit (`item`, `note` or `document`).
 *
 * @property kind The hit kind on the wire.
 * @property itemFacets Whether the item-describing filters (role, tags, traits) apply to this corpus. They describe
 *   a work item, so they are honored only where the hit IS an item or belongs to one as a note; a corpus with
 *   `false` rejects them rather than ignoring them.
 */
enum class Corpus(
    val kind: String,
    val itemFacets: Boolean,
) {
    /** Work-item title, summary and description. */
    ITEM("item", true),

    /** Note bodies; every hit is owned by the note's work item. */
    NOTE("note", true),

    /** Document title and body (plan, rule and page documents); every hit is owned by the document's root item. */
    DOCUMENT("document", false),
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
 * item for [Corpus.NOTE], the document's root item for [Corpus.DOCUMENT]), all combined with AND and applied BEFORE the candidate window is taken.
 *
 * @property itemId Only content owned by this item.
 * @property ancestorId Only content owned by this item or its descendants (bounded walk).
 * @property rootIds Access scope: only content owned by an item whose ancestor chain (itself included) contains one
 *   of these ids. Null means unrestricted; an EMPTY set means nothing is visible. Ids need not be depth-0.
 * @property roles Only content whose owning item is in one of these roles. Null or empty means any role.
 * @property tagsAny The caller's tag filter: the owning item carries at least one of these tags, compared
 *   case-insensitively. Null or empty means no tag filter.
 * @property traitsAny The owning item carries at least one of these trait names, compared exactly (case-sensitive)
 *   after trimming each element and dropping empty ones. Null or effectively empty means no trait filter.
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
    val traitsAny: List<String>? = null,
    val principalTagsAny: Set<String>? = null,
)

/**
 * One ranked match from one analyzer over one corpus.
 *
 * @property id The stable id of the matched document: the work-item id ([Corpus.ITEM]) or the note id ([Corpus.NOTE]).
 * @property ownerItemId The work item that owns the document (equal to [id] for [Corpus.ITEM]).
 * @property noteKey The note key ([Corpus.NOTE] only).
 * @property rank The engine's raw score (lower is better); it orders ONE candidate list. Scores from different
 *   analyzers come from different indexes and are not calibrated against each other. Their only cross-list use is
 *   the choice `Ranker` makes of which matched candidate supplies a fused hit's `field` and `snippet` (lower raw
 *   rank, substring on a tie), a presentation heuristic inherited from 3.x that never affects score or order.
 * @property field The field that actually contains a match (`title`/`summary`/`description` for items, `body` for
 *   notes, `title`/`body` for documents); when several do, the first in the corpus's field order (title, then summary, then description).
 * @property snippet A short excerpt of [field] with `<mark>`/`</mark>` around the matched text.
 * @property document What addresses and versions the hit; non-null exactly for [Corpus.DOCUMENT] (where [id] is the
 *   document id, [ownerItemId] its root item and [noteKey] null).
 */
data class Candidate(
    val id: UUID,
    val ownerItemId: UUID,
    val noteKey: String?,
    val rank: Double,
    val field: String,
    val snippet: String,
    val document: DocumentHitMeta? = null,
)

/**
 * The document-only facts of a [Candidate], read in the same statement as the match.
 *
 * @property kind The document kind (`plan`, `rule`, `page`).
 * @property slug The slug, unique per (root, kind).
 * @property title The document title, null when it has none.
 * @property version The document version the match was read at.
 */
data class DocumentHitMeta(
    val kind: String,
    val slug: String,
    val title: String?,
    val version: Int,
)

/**
 * Full-text candidate retrieval: the storage half of search. Ranking, fusion, paging and access policy live in the
 * application's search service; this port only matches, filters and orders by the engine's own rank.
 */
interface SearchIndex {
    /**
     * The best [window] matches for [query] in [corpus] under [analyzer], in rank order (best first), restricted by
     * [filter] before the window is taken.
     *
     * Archived documents are never candidates. A corpus whose [Corpus.itemFacets] is false rejects a filter that sets
     * roles, tags or traits with [IllegalArgumentException]; it never ignores one silently.
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
