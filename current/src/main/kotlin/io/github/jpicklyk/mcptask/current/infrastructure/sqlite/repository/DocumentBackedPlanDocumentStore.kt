package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.knowledge.document.PlanSlugs
import io.github.jpicklyk.mcptask.current.application.port.DocumentStore
import io.github.jpicklyk.mcptask.current.application.port.LinkStore
import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentAdoptOutcome
import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentStashOutcome
import io.github.jpicklyk.mcptask.current.application.port.PlanDocumentStore
import io.github.jpicklyk.mcptask.current.application.port.StoreWrite
import io.github.jpicklyk.mcptask.current.application.support.sha256Hex
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.DocumentVersion
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.Link
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocument
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentSummary
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import java.time.Instant
import java.util.UUID

/**
 * The 3.x [PlanDocumentStore] port, served from the 4.0 `documents` / `document_versions` / `links` tables.
 *
 * - A slug maps to a document address through [PlanSlugs]: `rule/<key>` is the RULE document `<key>`, every other
 *   slug the PLAN document with the same slug. Only PLAN and RULE documents are visible through this view.
 * - Status is derived: ADOPTED iff an `adopted_by` link runs from the document; [PlanDocument.adoptedByItemId] is that
 *   link's target, kept as provenance even after the adopting item is deleted.
 * - [stash] writes the body as given (no normalization; the knowledge document service normalizes) and appends a
 *   version row; [markAdopted] inserts the `adopted_by` link. Neither records events: the services do.
 * - [list] is two queries whatever the number of documents: one documents select and one batched links select.
 *
 * Each write runs in one `writeTx`, joining the ambient unit when there is one (so `items_write` `create_tree` adopts inside
 * its own unit).
 */
class DocumentBackedPlanDocumentStore(
    private val databaseManager: DatabaseManager,
    private val documents: DocumentStore,
    private val links: LinkStore
) : PlanDocumentStore {
    @StoreWrite
    override suspend fun stash(
        rootItemId: UUID,
        slug: String,
        body: String
    ): PlanDocumentStashOutcome =
        databaseManager.writeTx("PlanDocumentStore.stash") {
            val address = PlanSlugs.toKindAndSlug(slug)
            val existing = documents.get(rootItemId, address.kind, address.slug)
            val hash = computeContentHash(body)
            val now = Instant.now()

            if (existing == null) {
                val created =
                    documents.insert(
                        Document(
                            id = UUID.randomUUID(),
                            rootItemId = rootItemId,
                            kind = address.kind,
                            slug = address.slug,
                            title = null,
                            body = body,
                            contentHash = hash,
                            version = 1,
                            archivedAt = null,
                            createdAt = now,
                            modifiedAt = now
                        )
                    )
                documents.appendVersion(DocumentVersion(UUID.randomUUID(), created.id, 1, body, hash, null, now))
                return@writeTx PlanDocumentStashOutcome.Stored(view(created, null))
            }

            val adoption = adoptionOf(existing.id)
            if (adoption != null) {
                return@writeTx PlanDocumentStashOutcome.AdoptedConflict(view(existing, adoption))
            }

            val next = existing.version + 1
            check(documents.updateCurrent(existing.id, existing.version, body, hash, existing.title, now)) {
                "plan document '$slug' changed concurrently inside its own write"
            }
            documents.appendVersion(DocumentVersion(UUID.randomUUID(), existing.id, next, body, hash, null, now))
            val stored = documents.getById(existing.id) ?: error("plan document '$slug' vanished inside its own write")
            PlanDocumentStashOutcome.Stored(view(stored, null))
        }

    override suspend fun get(
        rootItemId: UUID,
        slug: String
    ): PlanDocument? {
        val address = PlanSlugs.toKindAndSlug(slug)
        val document = documents.get(rootItemId, address.kind, address.slug) ?: return null
        return view(document, adoptionOf(document.id))
    }

    override suspend fun list(
        rootItemId: UUID,
        status: PlanDocumentStatus?
    ): List<PlanDocumentSummary> {
        val summaries =
            documents
                .list(rootItemId, kind = null, includeArchived = true)
                .filter { it.kind in VISIBLE_KINDS }
        if (summaries.isEmpty()) return emptyList()
        val adopters =
            links
                .findToAny(summaries.map { EntityRef(RefKind.DOCUMENT, it.id) }, LinkRelation.ADOPTED_BY)
                .groupBy { it.from.id }
                .mapValues { (_, adoptions) -> adoptions.first() }
        return summaries
            .map { summary ->
                val adoption = adopters[summary.id]
                PlanDocumentSummary(
                    id = summary.id,
                    rootItemId = summary.rootItemId,
                    slug = PlanSlugs.fromKindAndSlug(summary.kind, summary.slug),
                    contentHash = summary.contentHash,
                    status = statusOf(adoption),
                    adoptedByItemId = adoption?.to?.id,
                    createdAt = summary.createdAt,
                    modifiedAt = summary.modifiedAt
                )
            }.filter { status == null || it.status == status }
            .sortedBy { it.slug }
    }

    @StoreWrite
    override suspend fun markAdopted(
        rootItemId: UUID,
        slug: String,
        adoptedByItemId: UUID
    ): PlanDocumentAdoptOutcome =
        databaseManager.writeTx("PlanDocumentStore.markAdopted") {
            val address = PlanSlugs.toKindAndSlug(slug)
            val existing =
                documents.get(rootItemId, address.kind, address.slug)
                    ?: return@writeTx PlanDocumentAdoptOutcome.NotFound
            val adoption = adoptionOf(existing.id)
            if (adoption != null) return@writeTx PlanDocumentAdoptOutcome.AlreadyAdopted(view(existing, adoption))
            val link =
                links.insert(
                    Link(
                        id = UUID.randomUUID(),
                        rootItemId = rootItemId,
                        from = EntityRef(RefKind.DOCUMENT, existing.id),
                        to = EntityRef(RefKind.ITEM, adoptedByItemId),
                        toRef = null,
                        relation = LinkRelation.ADOPTED_BY,
                        anchor = null,
                        createdAt = Instant.now()
                    )
                )
            PlanDocumentAdoptOutcome.Adopted(view(existing, link))
        }

    override fun computeContentHash(body: String): String = sha256Hex(body.toByteArray(Charsets.UTF_8))

    private suspend fun adoptionOf(documentId: UUID): Link? =
        links.findFrom(EntityRef(RefKind.DOCUMENT, documentId)).firstOrNull { it.relation == LinkRelation.ADOPTED_BY }

    private fun view(
        document: Document,
        adoption: Link?
    ): PlanDocument =
        PlanDocument(
            id = document.id,
            rootItemId = document.rootItemId,
            slug = PlanSlugs.fromKindAndSlug(document.kind, document.slug),
            body = document.body,
            contentHash = document.contentHash,
            status = statusOf(adoption),
            adoptedByItemId = adoption?.to?.id,
            createdAt = document.createdAt,
            modifiedAt = document.modifiedAt
        )

    private fun statusOf(adoption: Link?): PlanDocumentStatus =
        when (adoption) {
            null -> PlanDocumentStatus.PENDING
            else -> PlanDocumentStatus.ADOPTED
        }

    private companion object {
        /** The kinds the 3.x plan-document view can address. */
        val VISIBLE_KINDS = setOf(DocumentKind.PLAN, DocumentKind.RULE)
    }
}
