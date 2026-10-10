package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.Link
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation

/**
 * Persists polymorphic links (`links`). Link ends carry no foreign key: a link outlives the entities it names, and
 * only a root delete removes it by cascade. A plain persistence port: it records no events.
 */
interface LinkStore {
    /** Inserts [link] as given and returns it. A resolved duplicate (same ends and relation) is a unique violation. */
    suspend fun insert(link: Link): Link

    /** Every link whose source is [ref], oldest first. */
    suspend fun findFrom(ref: EntityRef): List<Link>

    /** Every resolved link whose target is [ref], oldest first. */
    suspend fun findTo(ref: EntityRef): List<Link>

    /**
     * Every link of [relation] whose SOURCE is one of [refs], in one batched query (never one query per ref), oldest
     * first. Used to derive per-document state (for example adoption) for a whole listing at once. Empty [refs]
     * returns an empty list without a query.
     */
    suspend fun findToAny(
        refs: List<EntityRef>,
        relation: LinkRelation
    ): List<Link>

    /** Deletes every link with [ref] at either end. Returns the number of deleted links. */
    suspend fun deleteTouching(ref: EntityRef): Int
}
