@file:OptIn(StoreWrite::class)

package io.github.jpicklyk.mcptask.current.application.knowledge.document

import io.github.jpicklyk.mcptask.current.application.knowledge.link.LinkService
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.StoreWrite
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.DocumentSummary
import io.github.jpicklyk.mcptask.current.domain.model.DocumentVersion
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.Link
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import java.security.MessageDigest
import java.util.UUID

/**
 * The knowledge kernel's document service: every document write runs here, in ONE write unit per operation, and
 * records its `document.*` event in that same unit.
 *
 * Write pipeline ([write]), in order: size cap (UTF-8 bytes of the body as given: [MAX_PLAN_BODY_BYTES],
 * or [MAX_RULE_BODY_BYTES] for [DocumentKind.RULE]) -> `expectedVersion` sign -> root exists -> root is
 * depth 0 -> CRLF to LF -> state checks (archived, adopted) -> compare-and-set. The content hash is the SHA-256 hex of
 * the NORMALIZED body's UTF-8 bytes (the algorithm 3.x used, applied after normalization).
 *
 * `expectedVersion` semantics: null writes unconditionally (create, or overwrite the current version); 0 means
 * "must create" (a present document is a `version_conflict`); n means "the current version must be n".
 *
 * Every write appends a `document_versions` row (version 1 included); `documents` holds the current body.
 */
class DocumentService(
    private val repositoryProvider: RepositoryProvider,
    /** The transaction boundary; each write operation is one unit (joining an ambient unit when there is one). */
    private val unitOfWork: UnitOfWork,
) {
    private val links = LinkService(repositoryProvider, unitOfWork)

    /**
     * Creates or rewrites the document at `(rootId, kind, slug)`. Returns the stored document (its new current
     * version). Errors: `payload_too_large`, `invalid_request` (negative `expectedVersion`, root not depth 0, archived
     * document), `not_found` (root), `version_conflict`, `duplicate` (the document is adopted and therefore frozen).
     *
     * [title] null keeps the current title on a rewrite.
     */
    suspend fun write(
        rootId: UUID,
        kind: DocumentKind,
        slug: String,
        body: String,
        title: String? = null,
        expectedVersion: Int? = null,
        actorId: String? = null,
    ): Outcome<Document> {
        val sizeBytes = body.toByteArray(Charsets.UTF_8).size
        val maxBytes = maxBodyBytes(kind)
        if (sizeBytes > maxBytes) return Outcome.Err(payloadTooLarge(maxBytes, sizeBytes))
        if (expectedVersion != null && expectedVersion < 0) {
            return Outcome.Err(invalidRequest(FIELD_EXPECTED_VERSION, "expectedVersion must be 0 or greater", expectedVersion.toString()))
        }

        return unitOfWork.write("DocumentService.write") {
            checkRoot(rootId)?.let { return@write Outcome.Err(it) }

            val normalized = normalize(body)
            val hash = contentHash(normalized)
            val documents = repositoryProvider.documentStore()
            val existing = documents.get(rootId, kind, slug)

            if (existing == null) {
                if (expectedVersion != null && expectedVersion != 0) {
                    return@write Outcome.Err(versionConflict(slug, expectedVersion, 0))
                }
                val created =
                    documents.insert(
                        Document(
                            id = UUID.randomUUID(),
                            rootItemId = rootId,
                            kind = kind,
                            slug = slug,
                            title = title,
                            body = normalized,
                            contentHash = hash,
                            version = 1,
                            archivedAt = null,
                            createdAt = now,
                            modifiedAt = now
                        )
                    )
                documents.appendVersion(versionRow(created.id, 1, normalized, hash, actorId))
                events.record(DomainEvent.DocumentCreated(created.id, rootId, kind, slug, created.version))
                links.syncDocument(created, now)
                links.onDocumentCreated(created)
                return@write Outcome.Ok(created)
            }

            stateViolation(existing)?.let { return@write Outcome.Err(it) }
            if (expectedVersion != null && expectedVersion != existing.version) {
                return@write Outcome.Err(versionConflict(slug, expectedVersion, existing.version))
            }

            val next = existing.version + 1
            val swapped = documents.updateCurrent(existing.id, existing.version, normalized, hash, title ?: existing.title, now)
            if (!swapped) {
                val actual = documents.getById(existing.id)?.version ?: next
                return@write Outcome.Err(versionConflict(slug, existing.version, if (actual == existing.version) next else actual))
            }
            documents.appendVersion(versionRow(existing.id, next, normalized, hash, actorId))
            events.record(DomainEvent.DocumentUpdated(existing.id, rootId, kind, slug, next))
            val stored = documents.getById(existing.id) ?: existing.copy(body = normalized, contentHash = hash, version = next)
            links.syncDocument(stored, now)
            Outcome.Ok(stored)
        }
    }

    /** The document at `(rootId, kind, slug)` including its current body, or null when none exists. */
    suspend fun get(
        rootId: UUID,
        kind: DocumentKind,
        slug: String,
    ): Document? = repositoryProvider.documentStore().get(rootId, kind, slug)

    /**
     * Metadata-only summaries of the documents under [rootId] (one documents query), restricted to [kind] when it is
     * non-null and excluding archived documents unless [includeArchived]. Ordered by `(kind, slug)`.
     */
    suspend fun list(
        rootId: UUID,
        kind: DocumentKind? = null,
        includeArchived: Boolean = false,
    ): List<DocumentSummary> = repositoryProvider.documentStore().list(rootId, kind, includeArchived)

    /** Every version of document [documentId], oldest first (empty when the document does not exist). */
    suspend fun history(documentId: UUID): List<DocumentVersion> = repositoryProvider.documentStore().listVersions(documentId)

    /** Version [version] of document [documentId], or null when there is none. */
    suspend fun getVersion(
        documentId: UUID,
        version: Int,
    ): DocumentVersion? = repositoryProvider.documentStore().getVersion(documentId, version)

    /**
     * Archives the document at `(rootId, kind, slug)` (sets `archived_at`; the version is unchanged) and records
     * `document.archived`. Archiving an archived document is a no-op that records nothing. Errors: `not_found`,
     * `version_conflict` (when [expectedVersion] is given and differs).
     */
    suspend fun archive(
        rootId: UUID,
        kind: DocumentKind,
        slug: String,
        expectedVersion: Int? = null,
    ): Outcome<Document> = setArchived(rootId, kind, slug, expectedVersion, archive = true)

    /** Clears `archived_at` and records `document.updated`; unarchiving a live document is a no-op. Errors as [archive]. */
    suspend fun unarchive(
        rootId: UUID,
        kind: DocumentKind,
        slug: String,
        expectedVersion: Int? = null,
    ): Outcome<Document> = setArchived(rootId, kind, slug, expectedVersion, archive = false)

    /**
     * Deletes the document at `(rootId, kind, slug)`: its versions (by FK cascade) and its outbound links; inbound
     * links that kept their text become dangling (and resolve again if the document is re-created), the others are
     * deleted. Then records `document.deleted`. An adopted document is refused with `invalid_request` (its adoption is provenance).
     * Returns the deleted document. Errors: `not_found`, `invalid_request`.
     */
    suspend fun delete(
        rootId: UUID,
        kind: DocumentKind,
        slug: String,
    ): Outcome<Document> =
        unitOfWork.write("DocumentService.delete") {
            val documents = repositoryProvider.documentStore()
            val existing = documents.get(rootId, kind, slug) ?: return@write Outcome.Err(documentNotFound(slug))
            if (adoptionOf(existing) != null) {
                return@write Outcome.Err(invalidRequest(FIELD_SLUG, REASON_ADOPTED, slug))
            }
            links.onDocumentDeleted(existing)
            documents.delete(existing.id)
            events.record(DomainEvent.DocumentDeleted(existing.id, rootId, kind, slug, existing.version))
            Outcome.Ok(existing)
        }

    /**
     * Records that [adoptedByItemId] adopted the PLAN document [slug] under [rootId]: inserts the `adopted_by` link
     * (document -> item) and records `document.adopted`, in one write unit (joining the caller's unit, so a composite
     * such as `items_write` `create_tree` commits or rolls the adoption back with its tree). Returns the (unchanged) document.
     * Errors: `invalid_request` ([kind] is not PLAN), `not_found` (no such document), `duplicate` (already adopted).
     */
    suspend fun adopt(
        rootId: UUID,
        slug: String,
        adoptedByItemId: UUID,
        kind: DocumentKind = DocumentKind.PLAN,
    ): Outcome<Document> {
        if (kind != DocumentKind.PLAN) {
            return Outcome.Err(invalidRequest(FIELD_KIND, "only plan documents can be adopted", kind.toDbValue()))
        }
        return unitOfWork.write("DocumentService.adopt") {
            val existing =
                repositoryProvider.documentStore().get(rootId, kind, slug)
                    ?: return@write Outcome.Err(documentNotFound(slug))
            if (adoptionOf(existing) != null) return@write Outcome.Err(alreadyAdopted(existing))
            repositoryProvider.linkStore().insert(
                Link(
                    id = UUID.randomUUID(),
                    rootItemId = rootId,
                    from = EntityRef(RefKind.DOCUMENT, existing.id),
                    to = EntityRef(RefKind.ITEM, adoptedByItemId),
                    toRef = null,
                    relation = LinkRelation.ADOPTED_BY,
                    anchor = null,
                    createdAt = now
                )
            )
            events.record(DomainEvent.DocumentAdopted(existing.id, rootId, slug, adoptedByItemId))
            Outcome.Ok(existing)
        }
    }

    /** The `adopted_by` link of [document], or null when it is not adopted. */
    suspend fun adoptionOf(document: Document): Link? =
        repositoryProvider
            .linkStore()
            .findFrom(EntityRef(RefKind.DOCUMENT, document.id))
            .firstOrNull { it.relation == LinkRelation.ADOPTED_BY }

    private suspend fun setArchived(
        rootId: UUID,
        kind: DocumentKind,
        slug: String,
        expectedVersion: Int?,
        archive: Boolean,
    ): Outcome<Document> =
        unitOfWork.write(if (archive) "DocumentService.archive" else "DocumentService.unarchive") {
            val documents = repositoryProvider.documentStore()
            val existing = documents.get(rootId, kind, slug) ?: return@write Outcome.Err(documentNotFound(slug))
            if (expectedVersion != null && expectedVersion != existing.version) {
                return@write Outcome.Err(versionConflict(slug, expectedVersion, existing.version))
            }
            if ((existing.archivedAt != null) == archive) return@write Outcome.Ok(existing)
            documents.setArchived(existing.id, if (archive) now else null)
            val event =
                if (archive) {
                    DomainEvent.DocumentArchived(existing.id, rootId, kind, slug, existing.version)
                } else {
                    DomainEvent.DocumentUpdated(existing.id, rootId, kind, slug, existing.version)
                }
            events.record(event)
            Outcome.Ok(documents.getById(existing.id) ?: existing)
        }

    /** `not_found` (ROOT) or `invalid_request` (not depth 0) for [rootId], or null when it is a valid project root. */
    private suspend fun checkRoot(rootId: UUID): DomainError? {
        val item = repositoryProvider.workItemRepository().getById(rootId) ?: return rootNotFound(rootId)
        if (item.depth != 0) {
            return invalidRequest(FIELD_ROOT_ID, "documents anchor to depth-0 project roots only (depth ${item.depth})", rootId.toString())
        }
        return null
    }

    /** The state that forbids rewriting [existing]: archived (`invalid_request`) or adopted (`duplicate`). */
    private suspend fun stateViolation(existing: Document): DomainError? {
        if (existing.archivedAt != null) return invalidRequest(FIELD_SLUG, REASON_ARCHIVED, existing.slug)
        if (adoptionOf(existing) != null) return alreadyAdopted(existing)
        return null
    }

    private fun WriteScope.versionRow(
        documentId: UUID,
        version: Int,
        body: String,
        hash: String,
        actorId: String?,
    ) = DocumentVersion(UUID.randomUUID(), documentId, version, body, hash, actorId, now)

    companion object {
        const val FIELD_SLUG = "slug"
        const val FIELD_KIND = "kind"
        const val FIELD_ROOT_ID = "rootId"
        const val FIELD_EXPECTED_VERSION = "expectedVersion"

        /** `invalid_request` reason for a write to an archived document. */
        const val REASON_ARCHIVED = "archived"

        /** `invalid_request` reason for deleting an adopted document. */
        const val REASON_ADOPTED = "adopted"

        /**
         * Hard cap on a plan (or page) document body's UTF-8 byte size (CWE-770: uncontrolled resource consumption),
         * 64 KiB. `PlanDocumentService.MAX_BODY_BYTES` re-exports it.
         */
        const val MAX_PLAN_BODY_BYTES = 64 * 1024

        /** Hard cap on a rule document body's UTF-8 byte size. `RuleService.MAX_RULE_BODY_BYTES` re-exports it. */
        const val MAX_RULE_BODY_BYTES = 16384

        /** The body cap for [kind], in UTF-8 bytes. */
        fun maxBodyBytes(kind: DocumentKind): Int = if (kind == DocumentKind.RULE) MAX_RULE_BODY_BYTES else MAX_PLAN_BODY_BYTES

        /** CRLF to LF; nothing else is touched (a lone CR stays). */
        fun normalize(body: String): String = body.replace("\r\n", "\n")

        /** SHA-256 hex (lowercase) of [body]'s UTF-8 bytes. */
        fun contentHash(body: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(body.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

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

        private fun payloadTooLarge(
            max: Int,
            actual: Int,
        ): DomainError =
            DomainError(
                code = ErrorCode.PAYLOAD_TOO_LARGE,
                message = "Document body is $actual bytes, exceeds the $max byte cap",
                detail = ErrorDetail.PayloadTooLarge(max.toLong(), actual.toLong()),
                fixArgs = mapOf("max" to max.toString())
            )

        private fun rootNotFound(rootId: UUID): DomainError =
            DomainError(
                code = ErrorCode.NOT_FOUND,
                message = "Root item $rootId not found",
                detail = ErrorDetail.NotFound(EntityKind.ROOT, rootId.toString()),
                fixArgs = mapOf("kind" to "root", "id" to rootId.toString())
            )

        private fun documentNotFound(slug: String): DomainError =
            DomainError(
                code = ErrorCode.NOT_FOUND,
                message = "Document '$slug' not found",
                detail = ErrorDetail.NotFound(EntityKind.DOCUMENT, slug),
                fixArgs = mapOf("kind" to "document", "id" to slug)
            )

        private fun versionConflict(
            slug: String,
            expected: Int,
            actual: Int,
        ): DomainError =
            DomainError(
                code = ErrorCode.VERSION_CONFLICT,
                message = "Document '$slug' is at version $actual, not $expected",
                detail = ErrorDetail.VersionConflict(EntityKind.DOCUMENT, slug, expected.toLong(), actual.toLong()),
                fixArgs = mapOf("kind" to "document", "id" to slug, "actual" to actual.toString())
            )

        private fun alreadyAdopted(document: Document): DomainError =
            DomainError(
                code = ErrorCode.DUPLICATE,
                message = "Document '${document.slug}' is adopted and can no longer be changed",
                detail = ErrorDetail.Duplicate(kind = EntityKind.DOCUMENT, id = document.slug, existingId = document.id.toString()),
                fixArgs = mapOf("kind" to "document", "existingId" to document.id.toString())
            )
    }
}
