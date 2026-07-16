package io.github.jpicklyk.mcptask.current.domain.repository

import io.github.jpicklyk.mcptask.current.domain.model.PlanDocument
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentSummary
import java.util.UUID

/** Outcome of [PlanDocumentRepository.stash]. */
sealed class PlanDocumentStashOutcome {
    /** The document was inserted (fresh slug) or overwritten (existing PENDING slug). */
    data class Stored(
        val document: PlanDocument
    ) : PlanDocumentStashOutcome()

    /**
     * The target `(rootItemId, slug)` already holds an ADOPTED document — adoption is a one-way
     * transition, so the stash is rejected and nothing is written. [existing] is the current
     * (unmodified) row, for callers that want to report its adopting item.
     */
    data class AdoptedConflict(
        val existing: PlanDocument
    ) : PlanDocumentStashOutcome()
}

/** Outcome of [PlanDocumentRepository.markAdopted]. */
sealed class PlanDocumentAdoptOutcome {
    /** The document transitioned PENDING -> ADOPTED and now records [document.adoptedByItemId]. */
    data class Adopted(
        val document: PlanDocument
    ) : PlanDocumentAdoptOutcome()

    /** No document exists at `(rootItemId, slug)`. */
    object NotFound : PlanDocumentAdoptOutcome()

    /** The document at `(rootItemId, slug)` is already ADOPTED (by [existing.adoptedByItemId]). */
    data class AlreadyAdopted(
        val existing: PlanDocument
    ) : PlanDocumentAdoptOutcome()
}

/**
 * Persists per-root plan documents (see [io.github.jpicklyk.mcptask.current.infrastructure.database.schema.PlanDocumentsTable]).
 *
 * `(rootItemId, slug)` is unique per root. [stash] is the sole write path for PENDING content —
 * re-stashing an existing PENDING slug overwrites it in place; re-stashing an ADOPTED slug is
 * rejected (see [PlanDocumentStashOutcome.AdoptedConflict]). [markAdopted] is the one-way
 * PENDING -> ADOPTED transition, called by the work-item materialization path (not built by this
 * store — see `PlanDocumentService` for the surface this repository backs).
 */
interface PlanDocumentRepository {
    /**
     * Inserts or overwrites the PENDING document at `(rootItemId, slug)` with [body], computing and
     * storing a SHA-256 fingerprint (via [computeContentHash]). A slug already in ADOPTED status is
     * left untouched and reported as [PlanDocumentStashOutcome.AdoptedConflict] instead.
     */
    suspend fun stash(
        rootItemId: UUID,
        slug: String,
        body: String
    ): Result<PlanDocumentStashOutcome>

    /** Returns the full stored document (including body) for `(rootItemId, slug)`, or null if none exists. */
    suspend fun get(
        rootItemId: UUID,
        slug: String
    ): Result<PlanDocument?>

    /**
     * Returns metadata-only summaries (no body) for every document under [rootItemId], optionally
     * filtered to a single [status]. Ordered by `slug` ascending.
     */
    suspend fun list(
        rootItemId: UUID,
        status: PlanDocumentStatus? = null
    ): Result<List<PlanDocumentSummary>>

    /**
     * Transitions the document at `(rootItemId, slug)` from PENDING to ADOPTED, recording
     * [adoptedByItemId]. A no-op transition (already ADOPTED) is reported via
     * [PlanDocumentAdoptOutcome.AlreadyAdopted] rather than silently re-adopting under a different
     * item.
     */
    suspend fun markAdopted(
        rootItemId: UUID,
        slug: String,
        adoptedByItemId: UUID
    ): Result<PlanDocumentAdoptOutcome>

    /**
     * Computes the SHA-256 hex digest of [body]'s UTF-8 bytes — the exact algorithm [stash] uses
     * when persisting. Exposed as a pure, non-persisting method so callers (notably dual-ingestion
     * parity checks between the REST PUT and MCP stash paths) can verify identical bytes landed
     * without duplicating the hash algorithm.
     */
    fun computeContentHash(body: String): String
}
