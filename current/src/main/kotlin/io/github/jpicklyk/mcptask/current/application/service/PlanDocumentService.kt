package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.PlanDocument
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentSummary
import io.github.jpicklyk.mcptask.current.domain.repository.PlanDocumentStashOutcome
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import java.util.UUID

/**
 * Transport-agnostic validation + persist pipeline for plan document stashes.
 *
 * Extracted the same way [ProjectConfigPushService] is: so the exact SAME pipeline can be driven
 * by both the MCP `manage_plan_documents` `stash` operation and the REST
 * `PUT /api/v1/roots/{rootId}/plans/{slug}` route
 * ([io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.planDocumentRoutes]) — both callers
 * must converge on identical DB state (and identical `contentHash`) for the same payload.
 *
 * Pipeline, in order: size cap -> root exists -> root is depth-0 ->
 * [io.github.jpicklyk.mcptask.current.domain.repository.PlanDocumentRepository.stash] (which itself
 * guards the ADOPTED-slug case). The pipeline stops at the first failing step; nothing is written
 * on failure.
 */
class PlanDocumentService(
    private val repositoryProvider: RepositoryProvider,
) {
    /**
     * Validates and persists [body] as the PENDING document at `(rootItemId, slug)`. See
     * [PlanDocumentStashResult] for the possible outcomes.
     *
     * The 64KB body cap mirrors `ManageNotesTool`'s `bodyFromFile` cap
     * ([io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool]) — kept
     * code-side (not a SQL CHECK) for consistency with how note bodies are capped elsewhere.
     */
    suspend fun stash(
        rootItemId: UUID,
        slug: String,
        body: String,
    ): PlanDocumentStashResult {
        val sizeBytes = body.toByteArray(Charsets.UTF_8).size
        if (sizeBytes > MAX_BODY_BYTES) {
            return PlanDocumentStashResult.TooLarge(sizeBytes, MAX_BODY_BYTES)
        }

        val item =
            when (val itemResult = repositoryProvider.workItemRepository().getById(rootItemId)) {
                is Result.Success -> itemResult.data
                is Result.Error -> return PlanDocumentStashResult.NotFound(rootItemId)
            }

        if (item.depth != 0) {
            return PlanDocumentStashResult.NotDepthZero(rootItemId, item.depth)
        }

        return when (val result = repositoryProvider.planDocumentRepository().stash(rootItemId, slug, body)) {
            is Result.Success ->
                when (val outcome = result.data) {
                    is PlanDocumentStashOutcome.Stored -> PlanDocumentStashResult.Success(outcome.document)
                    is PlanDocumentStashOutcome.AdoptedConflict -> PlanDocumentStashResult.AdoptedConflict(outcome.existing)
                }
            is Result.Error -> PlanDocumentStashResult.RepositoryError(result.error.message)
        }
    }

    /** Reads back the full stored document (including body) at `(rootItemId, slug)`, or a null payload when none exists. */
    suspend fun get(
        rootItemId: UUID,
        slug: String,
    ): Result<PlanDocument?> = repositoryProvider.planDocumentRepository().get(rootItemId, slug)

    /** Lists metadata-only summaries (no body) for every document under [rootItemId], optionally filtered by [status]. */
    suspend fun list(
        rootItemId: UUID,
        status: PlanDocumentStatus? = null,
    ): Result<List<PlanDocumentSummary>> = repositoryProvider.planDocumentRepository().list(rootItemId, status)

    /**
     * Computes the SHA-256 hex digest [body] would be stored under — the exact algorithm [stash]
     * uses when persisting. Exposed as a pure, non-persisting method for dual-ingestion parity
     * checks (REST PUT vs MCP stash) without requiring a round-trip write.
     */
    fun computeContentHash(body: String): String = repositoryProvider.planDocumentRepository().computeContentHash(body)

    companion object {
        /** `body` size limit for `stash`, in KiB — see [MAX_BODY_BYTES]. */
        private const val MAX_BODY_KIB = 64

        /**
         * Hard cap on a stashed document body's UTF-8 byte size (CWE-770: uncontrolled resource
         * consumption), mirroring `ManageNotesTool.MAX_BODY_FILE_BYTES`. Enforced before any
         * repository call.
         */
        const val MAX_BODY_BYTES = MAX_BODY_KIB * 1024
    }
}

/** Outcome of [PlanDocumentService.stash]. */
sealed class PlanDocumentStashResult {
    /** The document was stored (inserted, or overwritten an existing PENDING row). */
    data class Success(
        val document: PlanDocument,
    ) : PlanDocumentStashResult()

    /** No WorkItem exists for [rootItemId]. */
    data class NotFound(
        val rootItemId: UUID,
    ) : PlanDocumentStashResult()

    /** [rootItemId] resolved to a WorkItem, but it is not depth-0 (plan documents anchor to project roots only). */
    data class NotDepthZero(
        val rootItemId: UUID,
        val depth: Int,
    ) : PlanDocumentStashResult()

    /** `body` exceeded [PlanDocumentService.MAX_BODY_BYTES]; rejected before any repository call. */
    data class TooLarge(
        val sizeBytes: Int,
        val maxBytes: Int,
    ) : PlanDocumentStashResult()

    /**
     * The target slug already holds an ADOPTED document — adoption is a one-way transition, so the
     * stash is rejected and nothing is written. [existing] is the current (unmodified) row.
     */
    data class AdoptedConflict(
        val existing: PlanDocument,
    ) : PlanDocumentStashResult()

    /** The repository call itself failed. */
    data class RepositoryError(
        val message: String,
    ) : PlanDocumentStashResult()
}
