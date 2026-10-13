package io.github.jpicklyk.mcptask.current.application.knowledge.related

import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.knowledge.search.Ranker
import io.github.jpicklyk.mcptask.current.application.knowledge.search.RrfFusion
import io.github.jpicklyk.mcptask.current.application.port.Analyzer
import io.github.jpicklyk.mcptask.current.application.port.Candidate
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.FTS_CANDIDATE_ROWS
import io.github.jpicklyk.mcptask.current.application.port.ReadScope
import io.github.jpicklyk.mcptask.current.application.port.ScopeFilter
import io.github.jpicklyk.mcptask.current.application.port.SearchIndex
import io.github.jpicklyk.mcptask.current.application.port.TextOp
import io.github.jpicklyk.mcptask.current.application.port.TextQuery
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.telemetry.CallTelemetry
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallHitSignals
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallRelatedSignals
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallResultCounts
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallRoot
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallTargetVersion
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.DocumentSummary
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.util.UUID

/**
 * The `related` read: four ranked signal lists about one ref, fused with weighted Reciprocal Rank Fusion.
 *
 * Signals (each list is windowed at [FTS_CANDIDATE_ROWS] after scope filtering; a position is 1-based):
 * - [RelatedSignal.EDGES] (item ref): dependency counterparts, both directions. RELATES_TO counterparts first, then
 *   blocking ones, each group by id. Reasons `relates_to`, `blocks` (the ref blocks it), `blocked_by` (it blocks the ref).
 * - [RelatedSignal.LINKS]: resolved links whose source is the ref or (item ref) one of its notes (`links_to`) and links
 *   whose target is the ref (`linked_from`), any relation. A note end folds to its owning item. Ordered by the number
 *   of distinct link rows to the entity descending, then kind, then id. Dangling links, a missing item or document and
 *   an archived document are excluded.
 * - [RelatedSignal.TAGS] (item ref, item results): shared tags, each worth `1/df` where `df` counts the in-scope items
 *   (ref excluded) carrying it; ordered by that sum descending, then the shared count descending, then id. Reason
 *   `shared_tags:<n>`.
 * - [RelatedSignal.TEXT]: the ref's most distinctive words ([SearchIndex.similarTerms]) matched ANY-term on the
 *   stemmed analyzer over items, notes and documents, fused across corpora by [Ranker]; a note hit folds to its item and
 *   the first position wins. No usable word gives an empty list. Reason `text`.
 *
 * Fusion: `score = sum over signals of weight / (Ranker.K + position)` ([RelatedSignal.weight]); order score
 * descending, then kind (`document` before `item`), then id ascending (canonical string form). The fused list is capped
 * at [MAX_RESULTS] before the page is sliced, so `total` is the same on every page. The ref itself (also reached
 * through its own notes) is never returned.
 *
 * Scope: by default every signal is bound to the ref's root (its `rootId`, else the top of its ancestor chain, else
 * itself; a document's root item); [RelatedRequest.acrossRoots] lifts only that bound. [RelatedRequest.access] always
 * applies before fusion and the cap (text and tags in the index statement, edges and links through one batched
 * [ScopeGuard.admitted]); a document is visible exactly when its root item is. Out-of-scope relatives are dropped,
 * never returned by id. A ref the caller may not see, or that does not exist, yields an empty result.
 *
 * Everything runs in ONE [UnitOfWork.read]. Telemetry: `request_shape.relatedSignals` counts, per signal, the hits of
 * the returned page it contributed to; `hit_signals` lists each page hit (id, kind) with the signals that found it; each
 * page hit records its id and version as a target version; the result count
 * is `total`.
 */
class RelatedService(
    private val index: SearchIndex,
    private val unitOfWork: UnitOfWork,
) {
    /** A related entity: an item or a document. */
    private data class Key(
        val kind: RefKind,
        val id: UUID,
    )

    /** The facts of a document relative, read while its signal was built. */
    private data class DocFacts(
        val document: RelatedDocument,
        val title: String?,
        val version: Long,
    )

    /**
     * Runs [request].
     *
     * @throws IllegalArgumentException when the limit is outside `1..`[MAX_LIMIT] or the offset is negative.
     */
    suspend fun related(request: RelatedRequest): RelatedResult {
        require(request.limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        require(request.offset >= 0) { "offset must be non-negative" }
        val result = unitOfWork.read { compute(request) }
        val counts = LinkedHashMap<String, Int>()
        for (signal in RelatedSignal.entries) counts[signal.wire] = result.hits.count { signal in it.ranks }
        recordCallRelatedSignals(counts)
        recordCallHitSignals(
            result.hits.map { hit ->
                CallTelemetry.HitSignals(
                    id = hit.ref.id,
                    kind = hit.ref.kind.toDbValue(),
                    signals = RelatedSignal.entries.filter { it in hit.ranks }.map { it.wire },
                )
            },
        )
        recordCallResultCounts(result.total, null)
        for (hit in result.hits) hit.version?.let { recordCallTargetVersion(hit.ref.id, it) }
        return result
    }

    private suspend fun ReadScope.compute(request: RelatedRequest): RelatedResult {
        val repo = stores.workItemRepository()
        val ref = request.ref
        val access = request.access
        val refItem: WorkItem?
        val root: UUID
        when (ref.kind) {
            RefKind.ITEM -> {
                refItem = repo.getById(ref.id) ?: return EMPTY
                root = rootsOf(listOf(refItem), repo).getValue(refItem.id)
                if (refItem.id !in ScopeGuard.admitted(access, setOf(refItem.id), repo)) return EMPTY
            }
            RefKind.DOCUMENT -> {
                val document = stores.documentStore().getById(ref.id)?.takeIf { it.archivedAt == null } ?: return EMPTY
                refItem = null
                root = document.rootItemId
                if (root !in ScopeGuard.admitted(access, setOf(root), repo)) return EMPTY
            }
            RefKind.NOTE -> return EMPTY
        }
        // The reference's own root, already resolved above; the hits' roots are not looked up.
        recordCallRoot(root)
        val self = Key(ref.kind, ref.id)
        val bound = if (request.acrossRoots) null else root
        val filter =
            ScopeFilter(
                ancestorId = bound,
                rootIds = access.rootIds,
                principalTagsAny = access.tagsInclude.takeIf { it.isNotEmpty() },
            )

        val reasons = HashMap<Key, MutableList<String>>()
        val docFacts = HashMap<Key, DocFacts>()

        // ---- graph signals (edges, links): gathered, then existence- and scope-filtered in batched reads ----------
        val edgeReasons = LinkedHashMap<UUID, MutableSet<String>>()
        val relates = sortedSetOf<String>()
        val blocking = sortedSetOf<String>()
        if (refItem != null) {
            for (dep in stores.dependencyRepository().findByItemId(refItem.id)) {
                val other = if (dep.fromItemId == refItem.id) dep.toItemId else dep.fromItemId
                if (other == refItem.id) continue
                val reason =
                    if (dep.type == DependencyType.RELATES_TO) {
                        relates += other.toString()
                        REASON_RELATES_TO
                    } else {
                        blocking += other.toString()
                        if (dep.blockerId() == refItem.id) REASON_BLOCKS else REASON_BLOCKED_BY
                    }
                edgeReasons.getOrPut(other) { LinkedHashSet() } += reason
            }
        }
        val edgeOrder = (relates.toList() + (blocking - relates).toList()).map { UUID.fromString(it) }

        val sources = mutableListOf(ref)
        if (refItem != null) {
            stores
                .noteRepository()
                .findRefsByItemIds(setOf(refItem.id))[refItem.id]
                .orEmpty()
                .forEach { sources += EntityRef(RefKind.NOTE, it.id) }
        }
        val linkStore = stores.linkStore()
        val outgoing = linkStore.findFromAll(sources).mapNotNull { it.to }
        val incoming = linkStore.findTo(ref).map { it.from }
        val noteIds = (outgoing + incoming).filter { it.kind == RefKind.NOTE }.mapTo(HashSet()) { it.id }
        val noteOwners = if (noteIds.isEmpty()) emptyMap() else stores.noteRepository().ownersOf(noteIds)

        fun fold(end: EntityRef): Key? =
            when (end.kind) {
                RefKind.ITEM -> Key(RefKind.ITEM, end.id)
                RefKind.DOCUMENT -> Key(RefKind.DOCUMENT, end.id)
                RefKind.NOTE -> noteOwners[end.id]?.let { Key(RefKind.ITEM, it) }
            }
        val linkCounts = HashMap<Key, Int>()
        val linkReasons = HashMap<Key, MutableSet<String>>()
        for ((ends, reason) in listOf(outgoing to REASON_LINKS_TO, incoming to REASON_LINKED_FROM)) {
            for (end in ends) {
                val key = fold(end) ?: continue
                if (key == self) continue
                linkCounts[key] = (linkCounts[key] ?: 0) + 1
                linkReasons.getOrPut(key) { LinkedHashSet() } += reason
            }
        }

        val graphItemIds = edgeReasons.keys + linkCounts.keys.filter { it.kind == RefKind.ITEM }.map { it.id }
        val graphItems = if (graphItemIds.isEmpty()) emptyMap() else repo.findByIds(graphItemIds.toSet()).associateBy { it.id }
        val linkDocIds = linkCounts.keys.filter { it.kind == RefKind.DOCUMENT }.mapTo(HashSet()) { it.id }
        val linkDocs: Map<UUID, DocumentSummary> =
            if (linkDocIds.isEmpty()) {
                emptyMap()
            } else {
                stores
                    .documentStore()
                    .findSummariesByIds(linkDocIds)
                    .filter { it.archivedAt == null }
                    .associateBy { it.id }
            }
        val itemRoots = rootsOf(graphItems.values, repo)
        val admitted =
            ScopeGuard.admitted(access, (graphItems.keys + linkDocs.values.map { it.rootItemId }).toSet(), repo)

        fun visible(key: Key): Boolean =
            when (key.kind) {
                RefKind.ITEM -> {
                    val itemRoot = itemRoots[key.id]
                    itemRoot != null && (bound == null || itemRoot == bound) && key.id in admitted
                }
                RefKind.DOCUMENT -> {
                    val doc = linkDocs[key.id]
                    doc != null && (bound == null || doc.rootItemId == bound) && doc.rootItemId in admitted
                }
                RefKind.NOTE -> false
            }

        val edges =
            edgeOrder
                .map { Key(RefKind.ITEM, it) }
                .filter { visible(it) }
                .take(FTS_CANDIDATE_ROWS)
        for (key in edges) {
            reasons.getOrPut(key) { mutableListOf() } +=
                edgeReasons.getValue(key.id).sortedBy { EDGE_REASON_ORDER.indexOf(it) }
        }

        val links =
            linkCounts.keys
                .filter { visible(it) }
                .sortedWith(compareByDescending<Key> { linkCounts.getValue(it) }.then(KEY_ORDER))
                .take(FTS_CANDIDATE_ROWS)
        for (key in links) {
            reasons.getOrPut(key) { mutableListOf() } += linkReasons.getValue(key).sortedBy { LINK_REASON_ORDER.indexOf(it) }
            linkDocs[key.id]?.takeIf { key.kind == RefKind.DOCUMENT }?.let { doc ->
                docFacts[key] = DocFacts(RelatedDocument(doc.rootItemId, doc.kind.toDbValue(), doc.slug), doc.title, doc.version.toLong())
            }
        }

        // ---- tags: scope pushed into the index statement; df over the in-scope items (ref excluded) ---------------
        val tags = if (refItem != null) tagSignal(refItem, filter) else emptyList()
        for ((key, shared) in tags) reasons.getOrPut(key) { mutableListOf() } += "$REASON_SHARED_TAGS_PREFIX$shared"

        // ---- text: ANY-term stemmed match over every corpus, notes folded to their item ---------------------------
        val text = textSignal(ref, self, filter, docFacts)
        for (key in text) reasons.getOrPut(key) { mutableListOf() } += REASON_TEXT

        // ---- weighted fusion, cap, page --------------------------------------------------------------------------
        val lists: Map<RelatedSignal, List<Key>> =
            linkedMapOf(
                RelatedSignal.EDGES to edges,
                RelatedSignal.LINKS to links,
                RelatedSignal.TAGS to tags.map { it.first },
                RelatedSignal.TEXT to text,
            )
        val positions = lists.mapValues { (_, keys) -> keys.withIndex().associate { it.value to it.index + 1 } }
        val scores = RrfFusion.fuseWeighted(positions.map { (signal, ranks) -> ranks to signal.weight }, Ranker.K.toDouble())
        val ordered =
            scores.keys.sortedWith(compareByDescending<Key> { scores.getValue(it) }.then(KEY_ORDER))
        val capped = ordered.size > MAX_RESULTS
        val kept = if (capped) ordered.take(MAX_RESULTS) else ordered
        val page = kept.drop(request.offset).take(request.limit)

        val pageItemIds = page.filter { it.kind == RefKind.ITEM }.mapTo(HashSet()) { it.id }
        val pageItems = if (pageItemIds.isEmpty()) emptyMap() else repo.findByIds(pageItemIds).associateBy { it.id }
        val hits =
            page.map { key ->
                val ranks = LinkedHashMap<RelatedSignal, Int>()
                for ((signal, at) in positions) at[key]?.let { ranks[signal] = it }
                val facts = docFacts[key]
                val item = pageItems[key.id].takeIf { key.kind == RefKind.ITEM }
                RelatedHit(
                    ref = EntityRef(key.kind, key.id),
                    title = facts?.title ?: item?.title,
                    score = scores.getValue(key),
                    reasons = reasons[key].orEmpty().toList(),
                    ranks = ranks,
                    document = facts?.document,
                    version = facts?.version ?: item?.version,
                )
            }
        return RelatedResult(hits = hits, total = kept.size, capped = capped)
    }

    /**
     * The tag signal of [refItem]: `(key, shared tag count)` in signal order. Membership matches the index's: the
     * candidate's lower-cased comma-separated list split on `,` holds the (trimmed, lower-cased) ref tag exactly.
     */
    private suspend fun tagSignal(
        refItem: WorkItem,
        filter: ScopeFilter,
    ): List<Pair<Key, Int>> {
        val refTags =
            refItem.tags
                ?.split(",")
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                .orEmpty()
        if (refTags.isEmpty()) return emptyList()
        val wanted = refTags.toSet()
        val shared =
            index
                .taggedItems(refTags, filter)
                .filterKeys { it != refItem.id }
                .mapValues { (_, tags) ->
                    tags
                        .orEmpty()
                        .lowercase()
                        .split(",")
                        .filterTo(HashSet()) { it in wanted }
                }.filterValues { it.isNotEmpty() }
        val df = HashMap<String, Int>()
        for (set in shared.values) for (t in set) df[t] = (df[t] ?: 0) + 1
        val score = shared.mapValues { (_, set) -> set.sumOf { 1.0 / df.getValue(it) } }
        return shared.keys
            .sortedWith(
                compareByDescending<UUID> { score.getValue(it) }
                    .thenByDescending { shared.getValue(it).size }
                    .thenBy { it.toString() },
            ).take(FTS_CANDIDATE_ROWS)
            .map { Key(RefKind.ITEM, it) to shared.getValue(it).size }
    }

    /** The text signal of [ref], in signal order; document facts of document hits are recorded in [docFacts]. */
    private suspend fun textSignal(
        ref: EntityRef,
        self: Key,
        filter: ScopeFilter,
        docFacts: MutableMap<Key, DocFacts>,
    ): List<Key> {
        val terms = index.similarTerms(ref, SimilarTerms.DEFAULT_MAX)
        if (terms.isEmpty()) return emptyList()
        val query = TextQuery(terms, TextOp.ANY)
        val lists = LinkedHashMap<Corpus, Map<Analyzer, List<Candidate>>>()
        for (corpus in Corpus.entries) {
            lists[corpus] = mapOf(Analyzer.STEMMED to index.rankedMatches(corpus, query, Analyzer.STEMMED, filter, FTS_CANDIDATE_ROWS))
        }
        val out = LinkedHashSet<Key>()
        for (hit in Ranker.rank(lists)) {
            if (out.size >= FTS_CANDIDATE_ROWS) break
            val candidate = hit.primary
            val key =
                when (hit.corpus) {
                    Corpus.ITEM -> Key(RefKind.ITEM, candidate.id)
                    Corpus.NOTE -> Key(RefKind.ITEM, candidate.ownerItemId)
                    Corpus.DOCUMENT -> Key(RefKind.DOCUMENT, candidate.id)
                }
            if (key == self || key in out) continue
            out += key
            val meta = candidate.document
            if (meta != null && key !in docFacts) {
                docFacts[key] = DocFacts(RelatedDocument(candidate.ownerItemId, meta.kind, meta.slug), meta.title, meta.version.toLong())
            }
        }
        return out.toList()
    }

    /**
     * The root of each of [items]: its `rootId`, else itself when it has no parent, else the top of its ancestor chain
     * (one batched read for every unstamped item), else itself.
     */
    private suspend fun rootsOf(
        items: Collection<WorkItem>,
        repo: WorkItemRepository,
    ): Map<UUID, UUID> {
        val out = HashMap<UUID, UUID>()
        val unstamped = HashSet<UUID>()
        for (item in items) {
            when {
                item.rootId != null -> out[item.id] = item.rootId
                item.parentId == null -> out[item.id] = item.id
                else -> unstamped += item.id
            }
        }
        if (unstamped.isNotEmpty()) {
            val chains = repo.findAncestorChains(unstamped)
            for (id in unstamped) out[id] = chains[id]?.firstOrNull()?.id ?: id
        }
        return out
    }

    companion object {
        /** Page size when the caller names none. */
        const val DEFAULT_LIMIT = 10

        /** Largest page size, and the cap on the fused list. */
        const val MAX_LIMIT = 50

        /** The cap on the fused list, applied before the page is sliced. */
        const val MAX_RESULTS = 50

        const val REASON_RELATES_TO = "relates_to"
        const val REASON_BLOCKS = "blocks"
        const val REASON_BLOCKED_BY = "blocked_by"
        const val REASON_LINKS_TO = "links_to"
        const val REASON_LINKED_FROM = "linked_from"
        const val REASON_SHARED_TAGS_PREFIX = "shared_tags:"
        const val REASON_TEXT = "text"

        private val EDGE_REASON_ORDER = listOf(REASON_RELATES_TO, REASON_BLOCKS, REASON_BLOCKED_BY)
        private val LINK_REASON_ORDER = listOf(REASON_LINKS_TO, REASON_LINKED_FROM)

        /** Kind (`document` before `item`), then id ascending in canonical string form. */
        private val KEY_ORDER: Comparator<Key> = compareBy<Key> { it.kind.toDbValue() }.thenBy { it.id.toString() }

        private val EMPTY = RelatedResult(emptyList(), 0, false)
    }
}
