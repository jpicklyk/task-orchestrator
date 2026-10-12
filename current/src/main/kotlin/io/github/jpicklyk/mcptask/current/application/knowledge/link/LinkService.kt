@file:OptIn(StoreWrite::class)

package io.github.jpicklyk.mcptask.current.application.knowledge.link

import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.StoreWrite
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.eventRootOf
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.DocumentSummary
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.Link
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import java.time.Instant
import java.util.UUID

/**
 * The knowledge links service: keeps the `links` table in step with the bodies that name other entities.
 *
 * Every write method runs on the caller's ambient write unit (the stores join it), so a link commits or rolls back
 * with the note, document or tree that caused it; none of them records an event (the link rows ride the owning write).
 * Only [attach] and [detach] open a unit of their own.
 *
 * Relations: `[[...]]` references parsed from a body are [LinkRelation.REFERENCES] (a re-parse replaces only those),
 * an explicit document-to-item attachment is [LinkRelation.ATTACHED], and a note cut from a plan document is
 * [LinkRelation.DERIVED_FROM]. A target resolves only within the source's root; a bare slug resolves PAGE, then PLAN,
 * then RULE. An unresolved document reference is stored dangling (`to_id` NULL, text in `to_ref`) and resolved when
 * the document is created; an unresolved item reference is not stored.
 */
class LinkService(
    private val repositoryProvider: RepositoryProvider,
    private val unitOfWork: UnitOfWork,
) {
    /**
     * Re-syncs the parsed links of note [noteId] after a write. Does nothing (no query) when neither [newBody] nor
     * [previousBody] contains `[[`: a note that never had a reference has no link to add or remove.
     */
    suspend fun syncNote(
        noteId: UUID,
        rootId: UUID,
        newBody: String,
        previousBody: String?,
        now: Instant,
    ) {
        if (!newBody.contains(OPEN) && previousBody?.contains(OPEN) != true) return
        replaceParsed(EntityRef(RefKind.NOTE, noteId), rootId, LinkParser.parse(newBody), now, selfDocumentId = null)
    }

    /** Re-syncs the parsed links of [document] (its current body) after a write. */
    suspend fun syncDocument(
        document: Document,
        now: Instant,
    ) {
        replaceParsed(
            EntityRef(RefKind.DOCUMENT, document.id),
            document.rootItemId,
            LinkParser.parse(document.body),
            now,
            selfDocumentId = document.id
        )
    }

    /**
     * Resolves, after [document] was CREATED, the dangling links of its root whose text is `slug` or `<kind>:slug`.
     * A dangling row whose source already has the resolved link is dropped instead, so `idx_links_unique` holds.
     */
    suspend fun onDocumentCreated(document: Document) {
        val links = repositoryProvider.linkStore()
        val target = EntityRef(RefKind.DOCUMENT, document.id)
        val refs = listOf(document.slug, "${document.kind.toDbValue()}:${document.slug}")
        val dangling = links.findDangling(document.rootItemId, refs).filter { it.from != target }
        if (dangling.isEmpty()) return
        val taken = HashSet<Triple<EntityRef, LinkRelation, EntityRef>>()
        for (source in dangling.map { it.from }.distinct()) {
            for (existing in links.findFrom(source)) {
                existing.to?.let { taken += Triple(source, existing.relation, it) }
            }
        }
        val drop = ArrayList<UUID>()
        for (link in dangling) {
            if (taken.add(Triple(link.from, link.relation, target))) {
                links.resolve(link.id, target)
            } else {
                drop += link.id
            }
        }
        links.deleteByIds(drop)
    }

    /**
     * Cleans up after [document] was deleted: its outbound links are deleted; inbound links that kept their text
     * become dangling, inbound links without one are deleted.
     */
    suspend fun onDocumentDeleted(document: Document) = onDocumentDeleted(document.id)

    /** [onDocumentDeleted] by document id: the same cleanup, for a caller holding only a summary of the document. */
    suspend fun onDocumentDeleted(documentId: UUID) {
        val links = repositoryProvider.linkStore()
        val ref = EntityRef(RefKind.DOCUMENT, documentId)
        links.deleteFrom(ref)
        links.unresolveTo(ref)
    }

    /** Deletes every link sourced from note [noteId] (all relations). */
    suspend fun onNoteDeleted(noteId: UUID) {
        repositoryProvider.linkStore().deleteFrom(EntityRef(RefKind.NOTE, noteId))
    }

    /**
     * Cleans up after an item-delete cascade removed [itemIds] and, through the notes foreign key, [noteIds]: every
     * link sourced from a removed note or item is deleted (all relations), and so is every resolved link INTO a
     * removed item except [LinkRelation.ADOPTED_BY] (an adoption outlives the adopting item). Matches on (kind, id)
     * only, so links stamped with another root are cleaned too. Joins the ambient unit; records no event.
     */
    suspend fun onItemsDeleted(
        itemIds: Collection<UUID>,
        noteIds: Collection<UUID>,
    ) {
        if (itemIds.isEmpty() && noteIds.isEmpty()) return
        val links = repositoryProvider.linkStore()
        val items = itemIds.map { EntityRef(RefKind.ITEM, it) }
        links.deleteFromAny(noteIds.map { EntityRef(RefKind.NOTE, it) } + items)
        links.deleteToAny(items, keep = setOf(LinkRelation.ADOPTED_BY))
    }

    /**
     * Follows a reparent that moved [itemIds] (the moved item and all its descendants, carrying the notes [noteIds])
     * into the root [newRootId]: every link sourced from one of those notes or items, of any relation and resolved or
     * dangling, is restamped to [newRootId], so the old root's delete cascade no longer removes it. Targets are not
     * re-resolved: a reference into the old root stays as it is (and goes dangling if that root's document is deleted).
     * Document-sourced links keep their document's root. Joins the ambient unit; records no event.
     */
    suspend fun onItemsMoved(
        itemIds: Collection<UUID>,
        noteIds: Collection<UUID>,
        newRootId: UUID,
    ) {
        val sources = noteIds.map { EntityRef(RefKind.NOTE, it) } + itemIds.map { EntityRef(RefKind.ITEM, it) }
        repositoryProvider.linkStore().restampRoot(sources, newRootId)
    }

    /**
     * Records that note [noteId] was cut from the PLAN document [slug] of [rootId] at [anchor]: a `derived_from` link
     * note -> document with text `plan:<slug>`. Replaces an earlier `derived_from` link of the note. Does nothing when
     * the document does not exist.
     */
    suspend fun recordDerivedFrom(
        rootId: UUID,
        noteId: UUID,
        slug: String,
        anchor: String?,
        now: Instant,
    ) {
        val document = repositoryProvider.documentStore().get(rootId, DocumentKind.PLAN, slug) ?: return
        val links = repositoryProvider.linkStore()
        val source = EntityRef(RefKind.NOTE, noteId)
        links.deleteFrom(source, LinkRelation.DERIVED_FROM)
        links.insert(
            Link(
                id = UUID.randomUUID(),
                rootItemId = rootId,
                from = source,
                to = EntityRef(RefKind.DOCUMENT, document.id),
                toRef = "${DocumentKind.PLAN.toDbValue()}:$slug",
                relation = LinkRelation.DERIVED_FROM,
                anchor = anchor,
                createdAt = now
            )
        )
    }

    /**
     * Attaches item [itemId] to the document `(rootId, kind, slug)`: an `attached` link document -> item. Idempotent:
     * a second call returns the existing link. Errors: `not_found` (document or item), `invalid_request` (the item is
     * not under the document's root).
     */
    suspend fun attach(
        rootId: UUID,
        kind: DocumentKind,
        slug: String,
        itemId: UUID,
    ): Outcome<Link> =
        unitOfWork.write("LinkService.attach") {
            val document =
                repositoryProvider.documentStore().get(rootId, kind, slug)
                    ?: return@write Outcome.Err(notFound(EntityKind.DOCUMENT, slug, "Document '$slug' not found"))
            val item =
                repositoryProvider.itemStore().getById(itemId)
                    ?: return@write Outcome.Err(notFound(EntityKind.ITEM, itemId.toString(), "WorkItem '$itemId' not found"))
            if (eventRootOf(item, repositoryProvider.hierarchyStore()) != rootId) {
                return@write Outcome.Err(invalidRequest("itemId", "the item is not under the document's root", itemId.toString()))
            }
            val links = repositoryProvider.linkStore()
            val from = EntityRef(RefKind.DOCUMENT, document.id)
            val to = EntityRef(RefKind.ITEM, itemId)
            links.findFrom(from).firstOrNull { it.relation == LinkRelation.ATTACHED && it.to == to }?.let {
                return@write Outcome.Ok(it)
            }
            Outcome.Ok(
                links.insert(
                    Link(
                        id = UUID.randomUUID(),
                        rootItemId = rootId,
                        from = from,
                        to = to,
                        toRef = null,
                        relation = LinkRelation.ATTACHED,
                        anchor = null,
                        createdAt = now
                    )
                )
            )
        }

    /** Removes the attachment of [itemId] to the document `(rootId, kind, slug)`. Returns the number removed (0 or 1); never an error. */
    suspend fun detach(
        rootId: UUID,
        kind: DocumentKind,
        slug: String,
        itemId: UUID,
    ): Int {
        val outcome =
            unitOfWork.write("LinkService.detach") {
                val document = repositoryProvider.documentStore().get(rootId, kind, slug) ?: return@write Outcome.Ok(0)
                val links = repositoryProvider.linkStore()
                val to = EntityRef(RefKind.ITEM, itemId)
                val ids =
                    links
                        .findFrom(EntityRef(RefKind.DOCUMENT, document.id))
                        .filter { it.relation == LinkRelation.ATTACHED && it.to == to }
                        .map { it.id }
                Outcome.Ok(links.deleteByIds(ids))
            }
        return (outcome as? Outcome.Ok)?.value ?: 0
    }

    /** Every link whose source is [ref], oldest first. */
    suspend fun linksFrom(ref: EntityRef): List<Link> = repositoryProvider.linkStore().findFrom(ref)

    /** Every resolved link whose target is [ref], oldest first. */
    suspend fun backlinks(ref: EntityRef): List<Link> = repositoryProvider.linkStore().findTo(ref)

    private data class Desired(
        val key: String,
        val to: EntityRef?,
        val toRef: String,
        val anchor: String?,
    )

    private fun keyOf(
        to: EntityRef?,
        toRef: String?,
    ): String = if (to != null) "${to.kind.toDbValue()}:${to.id}" else "ref:$toRef"

    /** Makes the REFERENCES links of [source] exactly [targets] resolved in [rootId], changing only what differs. */
    private suspend fun replaceParsed(
        source: EntityRef,
        rootId: UUID,
        targets: List<LinkTarget>,
        now: Instant,
        selfDocumentId: UUID?,
    ) {
        val links = repositoryProvider.linkStore()
        val existing = links.findFrom(source).filter { it.relation == LinkRelation.REFERENCES }
        if (targets.isEmpty()) {
            links.deleteByIds(existing.map { it.id })
            return
        }

        val documents = resolveDocuments(rootId, targets.filterIsInstance<DocumentTarget>())
        val items = resolveItems(rootId, targets.filterIsInstance<ItemTarget>())
        val desired = LinkedHashMap<String, Desired>()
        for (target in targets) {
            val to: EntityRef? =
                when (target) {
                    is DocumentTarget -> {
                        val hit = documents[target]
                        if (hit != null && hit.id == selfDocumentId) continue
                        hit?.let { EntityRef(RefKind.DOCUMENT, it.id) }
                    }
                    is ItemTarget -> items[target]?.let { EntityRef(RefKind.ITEM, it) } ?: continue
                }
            val key = keyOf(to, target.toRef)
            desired.putIfAbsent(key, Desired(key, to, target.toRef, target.anchor))
        }

        val keep = HashSet<String>()
        val remove = ArrayList<UUID>()
        for (link in existing) {
            val key = keyOf(link.to, link.toRef)
            val want = desired[key]
            if (want != null && want.toRef == link.toRef && want.anchor == link.anchor && keep.add(key)) continue
            remove += link.id
        }
        links.deleteByIds(remove)
        for (want in desired.values) {
            if (want.key in keep) continue
            links.insert(
                Link(
                    id = UUID.randomUUID(),
                    rootItemId = rootId,
                    from = source,
                    to = want.to,
                    toRef = want.toRef,
                    relation = LinkRelation.REFERENCES,
                    anchor = want.anchor,
                    createdAt = now
                )
            )
        }
    }

    /** One batched slug lookup, then the precedence rule per target. */
    private suspend fun resolveDocuments(
        rootId: UUID,
        targets: List<DocumentTarget>,
    ): Map<DocumentTarget, DocumentSummary?> {
        if (targets.isEmpty()) return emptyMap()
        val bySlug = repositoryProvider.documentStore().findBySlugs(rootId, targets.map { it.slug }.distinct()).groupBy { it.slug }
        return targets.associateWith { target ->
            val candidates = bySlug[target.slug].orEmpty()
            if (target.kind != null) {
                candidates.firstOrNull { it.kind == target.kind }
            } else {
                BARE_SLUG_PRECEDENCE.firstNotNullOfOrNull { kind -> candidates.firstOrNull { it.kind == kind } }
            }
        }
    }

    /**
     * The id of the item each of [targets] names when it exists, is unambiguous and lies under [rootId], else null.
     * Full UUIDs are read in ONE batched id-set query and the roots of every candidate lacking a stored `rootId` in ONE
     * ancestor-chain query; a short-id prefix needs its own prefix lookup (bounded by the target cap).
     */
    private suspend fun resolveItems(
        rootId: UUID,
        targets: List<ItemTarget>,
    ): Map<ItemTarget, UUID?> {
        if (targets.isEmpty()) return emptyMap()
        val itemStore = repositoryProvider.itemStore()
        val refs = targets.associateWith { IdRef.parse(it.id)?.raw }
        val fullIds =
            refs.values
                .filterNotNull()
                .filter { it.length == UUID_TEXT_LENGTH }
                .map { UUID.fromString(it) }
                .toSet()
        val byId = if (fullIds.isEmpty()) emptyMap() else itemStore.findByIdSet(fullIds).associateBy { it.id }
        val candidates =
            refs.mapValues { (_, raw) ->
                when {
                    raw == null -> null
                    raw.length == UUID_TEXT_LENGTH -> byId[UUID.fromString(raw)]
                    else -> itemStore.findByIdPrefix(raw, 2).singleOrNull()
                }
            }
        val unrooted =
            candidates.values
                .filterNotNull()
                .filter { it.rootId == null }
                .map { it.id }
                .toSet()
        val chains = if (unrooted.isEmpty()) emptyMap() else repositoryProvider.hierarchyStore().findAncestorChains(unrooted)
        return candidates.mapValues { (_, item) ->
            item?.takeIf { (it.rootId ?: chains[it.id]?.firstOrNull()?.id ?: it.id) == rootId }?.id
        }
    }

    private fun notFound(
        kind: EntityKind,
        id: String,
        message: String,
    ): DomainError =
        DomainError(
            code = ErrorCode.NOT_FOUND,
            message = message,
            detail = ErrorDetail.NotFound(kind, id),
            fixArgs = mapOf("kind" to kind.name.lowercase(), "id" to id)
        )

    private fun invalidRequest(
        field: String,
        reason: String,
        received: String?,
    ): DomainError =
        DomainError(
            code = ErrorCode.INVALID_REQUEST,
            message = "Invalid $field: $reason",
            detail = ErrorDetail.InvalidRequest(listOf(FieldViolation(field, reason, received)))
        )

    companion object {
        private const val OPEN = "[["
        private const val UUID_TEXT_LENGTH = 36
        private val BARE_SLUG_PRECEDENCE = listOf(DocumentKind.PAGE, DocumentKind.PLAN, DocumentKind.RULE)
    }
}
