package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.DocumentSummary
import io.github.jpicklyk.mcptask.current.domain.model.DocumentVersion
import java.time.Instant
import java.util.UUID

/**
 * Persists versioned documents (`documents`) and their append-only history (`document_versions`).
 *
 * `(rootItemId, kind, slug)` is unique. The store is a plain persistence port: it never validates, normalizes
 * or hashes a body and records no events. Writes belong to the knowledge document service (and the 3.x
 * plan-document facade store), which records the matching events in the same unit.
 */
interface DocumentStore {
    /** The document at `(rootItemId, kind, slug)`, including its current body, or null when none exists. */
    suspend fun get(
        rootItemId: UUID,
        kind: DocumentKind,
        slug: String
    ): Document?

    /** The document with identity [id], or null when none exists. */
    suspend fun getById(id: UUID): Document?

    /**
     * Metadata-only summaries (no body) of the documents under [rootItemId], restricted to [kind] when it is non-null,
     * and excluding archived documents unless [includeArchived]. Ordered by `(kind, slug)` ascending.
     */
    suspend fun list(
        rootItemId: UUID,
        kind: DocumentKind?,
        includeArchived: Boolean
    ): List<DocumentSummary>

    /**
     * Metadata-only summaries (no body, archived included) of the documents under [rootItemId] whose slug is one of
     * [slugs], of any kind, in one batched query. Empty [slugs] returns an empty list without a query.
     */
    suspend fun findBySlugs(
        rootItemId: UUID,
        slugs: Collection<String>
    ): List<DocumentSummary>

    /**
     * Metadata-only summaries (no body, archived included) of the documents whose id is in [ids], in no particular
     * order; absent ids are omitted. The default reads document by document; the SQLite store overrides it with
     * batched reads. Empty [ids] returns an empty list without a query.
     */
    suspend fun findSummariesByIds(ids: Set<UUID>): List<DocumentSummary> =
        ids.mapNotNull { id ->
            getById(id)?.let {
                DocumentSummary(
                    it.id,
                    it.rootItemId,
                    it.kind,
                    it.slug,
                    it.title,
                    it.contentHash,
                    it.version,
                    it.archivedAt,
                    it.createdAt,
                    it.modifiedAt
                )
            }
        }

    /**
     * Metadata-only summaries (no body, archived included) of every document whose `root_item_id` is one of
     * [rootItemIds], of any kind, in batched queries (never one per id), ordered by `(root_item_id, kind, slug)`. Empty
     * [rootItemIds] returns an empty list without a query.
     */
    suspend fun findByRootItemIds(rootItemIds: Collection<UUID>): List<DocumentSummary>

    /** Inserts [document] as given (its id, version and timestamps included) and returns the stored row. */
    @StoreWrite
    suspend fun insert(document: Document): Document

    /**
     * Compare-and-set update of the current body: sets body, hash, title, `version = expectedVersion + 1` and
     * `modified_at = now` on the row with [id] only when its stored version still equals [expectedVersion]
     * (one `UPDATE ... WHERE id = ? AND version = ?`). Returns true when exactly one row was updated.
     */
    @StoreWrite
    suspend fun updateCurrent(
        id: UUID,
        expectedVersion: Int,
        body: String,
        contentHash: String,
        title: String?,
        now: Instant
    ): Boolean

    /** Appends one history row. `(documentId, version)` is unique. */
    @StoreWrite
    suspend fun appendVersion(version: DocumentVersion)

    /** Every version of document [id], oldest first. */
    suspend fun listVersions(id: UUID): List<DocumentVersion>

    /** Version [version] of document [id], or null when there is none. */
    suspend fun getVersion(
        id: UUID,
        version: Int
    ): DocumentVersion?

    /** Sets (non-null [at]) or clears (null) `archived_at` on document [id]. Returns true when a row was updated. */
    @StoreWrite
    suspend fun setArchived(
        id: UUID,
        at: Instant?
    ): Boolean

    /** Deletes document [id] (its versions cascade by foreign key). Returns true when a row was deleted. */
    @StoreWrite
    suspend fun delete(id: UUID): Boolean
}
