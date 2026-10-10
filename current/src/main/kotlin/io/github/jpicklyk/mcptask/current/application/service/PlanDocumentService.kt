package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService
import io.github.jpicklyk.mcptask.current.application.knowledge.document.PlanSlugs
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.UnitResult
import io.github.jpicklyk.mcptask.current.application.support.writeUnit
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocument
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentSummary
import java.util.UUID

/**
 * The 3.x plan-document surface, kept byte-for-byte for `manage_plan_documents`, the REST plan routes and
 * `create_work_tree` adoption, and delegating every write to the knowledge [DocumentService].
 *
 * Extracted the same way [ProjectConfigPushService] is: so the exact SAME pipeline can be driven
 * by both the MCP `manage_plan_documents` `stash` operation and the REST
 * `PUT /api/v1/roots/{rootId}/plans/{slug}` route
 * ([io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.planDocumentRoutes]) — both callers
 * must converge on identical DB state (and identical `contentHash`) for the same payload.
 *
 * A 3.x slug maps to a document address through [PlanSlugs] (`rule/<key>` is the RULE document `<key>`, every other
 * slug the PLAN document of the same slug). Pipeline for [stash], in order: size cap -> root exists -> root is
 * depth-0 -> [DocumentService.write] (unconditional, which normalizes CRLF to LF, appends a version, records
 * `document.created` or `document.updated`, and refuses an adopted document). The pipeline stops at the first failing
 * step; nothing is written on failure.
 */
class PlanDocumentService(
    private val repositoryProvider: RepositoryProvider,
    /** The transaction boundary: the root check and the stash run in ONE write unit. */
    private val unitOfWork: UnitOfWork,
) {
    private val documents = DocumentService(repositoryProvider, unitOfWork)

    /**
     * Validates and persists [body] as the PENDING document at `(rootItemId, slug)`. See
     * [PlanDocumentStashResult] for the possible outcomes.
     *
     * The 64KB body cap mirrors `ManageNotesTool`'s `bodyFromFile` cap
     * ([io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool]) — kept
     * code-side (not a SQL CHECK) for consistency with how note bodies are capped elsewhere. A
     * `slug` starting with [RuleService.RULE_SLUG_PREFIX] (`rule/`) is capped tighter, at
     * [RuleService.MAX_RULE_BODY_BYTES] — the single enforcement point for both `manage_plan_documents`
     * `stash` and the REST PUT route, so `query_rules`/the `rules` REST routes never need their own
     * cap (A3, 840e700a).
     */
    suspend fun stash(
        rootItemId: UUID,
        slug: String,
        body: String,
    ): PlanDocumentStashResult {
        val sizeBytes = body.toByteArray(Charsets.UTF_8).size
        val maxBytes = if (slug.startsWith(RuleService.RULE_SLUG_PREFIX)) RuleService.MAX_RULE_BODY_BYTES else MAX_BODY_BYTES
        if (sizeBytes > maxBytes) {
            return PlanDocumentStashResult.TooLarge(sizeBytes, maxBytes)
        }
        val address = PlanSlugs.toKindAndSlug(slug)

        return unitOfWork.writeUnit(
            "PlanDocumentService.stash",
            onFault = { PlanDocumentStashResult.RepositoryError(it) }
        ) {
            val item =
                repositoryProvider.workItemRepository().getById(rootItemId)
                    ?: return@writeUnit UnitResult.Rollback(PlanDocumentStashResult.NotFound(rootItemId))

            if (item.depth != 0) {
                return@writeUnit UnitResult.Rollback(PlanDocumentStashResult.NotDepthZero(rootItemId, item.depth))
            }

            when (val written = documents.write(rootItemId, address.kind, address.slug, body)) {
                is Outcome.Ok -> {
                    val stored =
                        repositoryProvider.planDocumentRepository().get(rootItemId, slug)
                            ?: return@writeUnit UnitResult.Rollback(PlanDocumentStashResult.RepositoryError(vanished(slug)))
                    UnitResult.Commit(PlanDocumentStashResult.Success(stored))
                }
                is Outcome.Err -> {
                    val existing =
                        written.error
                            .takeIf { it.code == ErrorCode.DUPLICATE }
                            ?.let { repositoryProvider.planDocumentRepository().get(rootItemId, slug) }
                    if (existing != null) {
                        UnitResult.Rollback(PlanDocumentStashResult.AdoptedConflict(existing))
                    } else {
                        UnitResult.Rollback(PlanDocumentStashResult.RepositoryError(written.error))
                    }
                }
            }
        }
    }

    /**
     * Records that [adoptedByItemId] adopted the PENDING document at `(rootItemId, slug)` (an `adopted_by` link, and
     * `document.adopted`), JOINING the caller's unit (an atomic composite such as `create_work_tree`, which adopts as its
     * last step so the adoption commits or rolls back with the tree). A document already adopted (a concurrent adopt
     * won) is `duplicate`; a vanished one is `not_found`.
     */
    suspend fun adoptInUnit(
        rootItemId: UUID,
        slug: String,
        adoptedByItemId: UUID,
    ): Outcome<PlanDocument> =
        unitOfWork.write("PlanDocumentService.adoptInUnit") {
            val address = PlanSlugs.toKindAndSlug(slug)
            val adopted = documents.adopt(rootItemId, address.slug, adoptedByItemId, address.kind)
            val current = repositoryProvider.planDocumentRepository().get(rootItemId, slug)
            when {
                adopted is Outcome.Ok && current != null -> Outcome.Ok(current)
                adopted is Outcome.Err && adopted.error.code == ErrorCode.DUPLICATE && current != null ->
                    Outcome.Err(
                        DomainError(
                            code = ErrorCode.DUPLICATE,
                            message =
                                "Plan document '$slug' (root $rootItemId) was concurrently adopted by item " +
                                    "${current.adoptedByItemId}; aborting work tree creation",
                            detail =
                                ErrorDetail.Duplicate(
                                    kind = EntityKind.DOCUMENT,
                                    id = slug,
                                    existingId = current.id.toString()
                                ),
                            fixArgs = mapOf("kind" to "document", "existingId" to current.id.toString())
                        )
                    )
                adopted is Outcome.Err && adopted.error.code != ErrorCode.NOT_FOUND -> Outcome.Err(adopted.error)
                else ->
                    Outcome.Err(
                        DomainError(
                            code = ErrorCode.NOT_FOUND,
                            message = "Plan document '$slug' (root $rootItemId) no longer exists; aborting work tree creation",
                            detail = ErrorDetail.NotFound(EntityKind.DOCUMENT, slug),
                            fixArgs = mapOf("kind" to "document", "id" to slug)
                        )
                    )
            }
        }

    /** Reads back the full stored document (including body) at `(rootItemId, slug)`, or a null payload when none exists. */
    suspend fun get(
        rootItemId: UUID,
        slug: String,
    ): PlanDocument? = repositoryProvider.planDocumentRepository().get(rootItemId, slug)

    /** Lists metadata-only summaries (no body) for every document under [rootItemId], optionally filtered by [status]. */
    suspend fun list(
        rootItemId: UUID,
        status: PlanDocumentStatus? = null,
    ): List<PlanDocumentSummary> = repositoryProvider.planDocumentRepository().list(rootItemId, status)

    /**
     * Computes the SHA-256 hex digest [body] would be stored under — the exact algorithm [stash]
     * uses when persisting (CRLF normalized to LF first, then hashed). Exposed as a pure,
     * non-persisting method for dual-ingestion parity checks (REST PUT vs MCP stash) without
     * requiring a round-trip write.
     */
    fun computeContentHash(body: String): String = DocumentService.contentHash(DocumentService.normalize(body))

    private fun vanished(slug: String): DomainError =
        DomainError(
            code = ErrorCode.INTERNAL,
            message = "Plan document '$slug' was written but could not be read back in the same unit"
        )

    companion object {
        /**
         * Hard cap on a stashed document body's UTF-8 byte size (CWE-770: uncontrolled resource
         * consumption), mirroring `ManageNotesTool.MAX_BODY_FILE_BYTES`. Enforced before any
         * repository call.
         */
        const val MAX_BODY_BYTES = DocumentService.MAX_PLAN_BODY_BYTES
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
        val error: DomainError,
    ) : PlanDocumentStashResult() {
        /** The 3.x store message (the innermost SQL text), unchanged on the wire. */
        val message: String get() = LegacyFaults.message(error)
    }
}
