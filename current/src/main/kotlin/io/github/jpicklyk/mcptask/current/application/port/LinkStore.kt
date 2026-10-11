package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.Link
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation
import java.util.UUID

/**
 * Persists polymorphic links (`links`). Link ends carry no foreign key: a link outlives the entities it names, and
 * only a root delete removes it by cascade. A plain persistence port: it records no events.
 */
interface LinkStore {
    /** Inserts [link] as given and returns it. A resolved duplicate (same ends and relation) is a unique violation. */
    @StoreWrite
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

    /**
     * Every link, of any relation and resolved or dangling, whose SOURCE is one of [refs], in batched statements (one
     * per ref kind and chunk, never one per ref), oldest first. Empty [refs] returns an empty list without a query.
     */
    suspend fun findFromAll(refs: Collection<EntityRef>): List<Link>

    /**
     * Deletes the links whose source is [ref]: every relation when [relation] is null, else only that relation.
     * Returns the number deleted.
     */
    @StoreWrite
    suspend fun deleteFrom(
        ref: EntityRef,
        relation: LinkRelation? = null
    ): Int

    /** Deletes the links with the given [ids] in batched statements. Empty [ids] runs no query. Returns the number deleted. */
    @StoreWrite
    suspend fun deleteByIds(ids: Collection<UUID>): Int

    /**
     * Every dangling link (no resolved target) under [rootItemId] whose `to_ref` is one of [toRefs], in one batched
     * query, oldest first. Empty [toRefs] returns an empty list without a query.
     */
    suspend fun findDangling(
        rootItemId: UUID,
        toRefs: Collection<String>
    ): List<Link>

    /** Resolves the dangling link [id] to [target] (sets its target kind and id). Returns true when a row was updated. */
    @StoreWrite
    suspend fun resolve(
        id: UUID,
        target: EntityRef
    ): Boolean

    /**
     * Detaches [ref] as a link target: each link whose target is [ref] and that carries a `to_ref` becomes dangling
     * (target id cleared, text kept); each one without a `to_ref` is deleted. Returns the number of links changed or
     * deleted.
     */
    @StoreWrite
    suspend fun unresolveTo(ref: EntityRef): Int
}
