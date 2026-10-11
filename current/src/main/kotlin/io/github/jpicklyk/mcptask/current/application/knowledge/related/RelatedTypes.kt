package io.github.jpicklyk.mcptask.current.application.knowledge.related

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import java.util.UUID

/**
 * One ranked signal of the `related` select and its weight in the fusion (code constants, plan section 3.11).
 *
 * @property wire The signal's name on the wire (`explain.ranks`, telemetry `relatedSignals`).
 * @property weight The signal's weight in the weighted RRF sum.
 */
enum class RelatedSignal(
    val wire: String,
    val weight: Double,
) {
    /** Dependency edges of an item ref, both directions. */
    EDGES("edges", 1.0),

    /** Resolved links from the ref (or its notes) and to the ref. */
    LINKS("links", 1.0),

    /** Shared tags of an item ref, rarer tags weighing more. */
    TAGS("tags", 0.5),

    /** Stemmed any-term match on the ref's most distinctive words. */
    TEXT("text", 0.5),
}

/**
 * One `related` call.
 *
 * @property ref The entity to find relatives of: an ITEM or a DOCUMENT (a NOTE ref is answered with no hits).
 * @property limit Page size, 1 to [RelatedService.MAX_LIMIT].
 * @property offset Zero-based offset into the capped fused list.
 * @property acrossRoots When false (default) every signal is bound to the ref's root; true lifts only that bound.
 * @property access What the caller may see; always applied before fusion and the cap.
 */
data class RelatedRequest(
    val ref: EntityRef,
    val limit: Int = RelatedService.DEFAULT_LIMIT,
    val offset: Int = 0,
    val acrossRoots: Boolean = false,
    val access: AccessScope,
)

/**
 * The document facts of a [RelatedHit] whose ref is a document.
 *
 * @property rootId The document's root item (with [kind] and [slug], its address).
 * @property kind The document kind wire value (`plan`, `rule`, `page`).
 * @property slug The document slug.
 */
data class RelatedDocument(
    val rootId: UUID,
    val kind: String,
    val slug: String,
)

/**
 * One fused relative.
 *
 * @property ref The related entity: an ITEM or a DOCUMENT (a note always folds to its owning item).
 * @property title The item title, or the document title (null when the document has none).
 * @property score The weighted RRF score.
 * @property reasons Why it is related, in signal order (edges, links, tags, text): `relates_to`, `blocks` (the ref
 *   blocks it), `blocked_by` (it blocks the ref), `links_to`, `linked_from`, `shared_tags:<n>`, `text`.
 * @property ranks The 1-based position of this entity in each signal list that holds it.
 * @property document The document facts when [ref] is a document, else null.
 * @property version The version read for this entity (item or document version), null when unknown.
 */
data class RelatedHit(
    val ref: EntityRef,
    val title: String?,
    val score: Double,
    val reasons: List<String>,
    val ranks: Map<RelatedSignal, Int> = emptyMap(),
    val document: RelatedDocument? = null,
    val version: Long? = null,
)

/**
 * One page of relatives.
 *
 * @property hits The page, in fused order.
 * @property total The size of the capped fused list (at most [RelatedService.MAX_RESULTS]); the same on every page.
 * @property capped True when more than [RelatedService.MAX_RESULTS] relatives existed before the cap.
 */
data class RelatedResult(
    val hits: List<RelatedHit>,
    val total: Int,
    val capped: Boolean,
)
