package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.ScopedIds
import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.AdvanceErrors
import io.github.jpicklyk.mcptask.current.application.service.ClaimHolder
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.lifecycle.NONE_ROLE_WIRE
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.util.UUID

/**
 * Shared steps of the `advance` ops (task-scope Q13): the principal, the item resolution and scope check, and the
 * catalog errors the claim ops map their store results to.
 *
 * Field pointers an op reports are relative to its `ops[]` element (`/ttlSeconds`); the dispatcher re-roots them
 * under `/ops/<index>`.
 */
internal object AdvanceSupport {
    /**
     * The holder of a claim, release or heartbeat, and the caller every fenced write passes: [ClaimHolder.resolve] over
     * the audited claim of the call's principal (`api:<id>` for an authenticated caller, `local` for the
     * unauthenticated local principal) and the seat assignment of its seat token (null without one). The principal
     * always exists and there is no policy to reject it, so this cannot fail.
     */
    fun holder(ctx: CallContext): ClaimHolder = ClaimHolder.resolve(ctx.principal.claim, ctx.seat?.assignmentId)

    /** Resolves [ref] to an item id the call's scope admits ([ScopedIds.resolveTarget]): never an existence oracle. */
    suspend fun resolveInScope(
        stores: RepositoryProvider,
        ctx: CallContext,
        ref: IdRef,
        field: String = "/itemId"
    ): Outcome<UUID> = ScopedIds.resolveTarget(stores.workItemRepository(), ctx.scope, ref, field)

    suspend fun loadItem(
        stores: RepositoryProvider,
        id: UUID
    ): Outcome<WorkItem> = stores.workItemRepository().getById(id)?.let { Outcome.Ok(it) } ?: Outcome.Err(itemNotFound(id))

    fun itemNotFound(id: UUID): DomainError = AdvanceErrors.itemNotFound(id)

    fun notClaimHolder(id: UUID): DomainError =
        DomainError(
            code = ErrorCode.NOT_CLAIM_HOLDER,
            message = "Item $id is not claimed by the caller.",
            detail = ErrorDetail.NotClaimHolder(id),
            fixArgs = mapOf("itemId" to id.toString()),
        )

    fun claimHeld(held: ClaimResult.AlreadyClaimed): DomainError =
        DomainError(
            code = ErrorCode.CLAIM_HELD,
            message = "Item ${held.itemId} is claimed by another actor.",
            detail = ErrorDetail.ClaimHeld(held.itemId, held.expiresAt, held.retryAfterMs),
            fixArgs = mapOf("itemId" to held.itemId.toString()),
        )

    /** A claim of a terminal item: the `invalid_transition` of the `claim` trigger from `terminal`, nothing allowed. */
    fun claimTerminal(id: UUID): DomainError =
        DomainError(
            code = ErrorCode.INVALID_TRANSITION,
            message = "Item $id is terminal and cannot be claimed.",
            detail = ErrorDetail.InvalidTransition(id, Role.TERMINAL.name.lowercase(), "claim", emptyList()),
            fixArgs = mapOf("itemId" to id.toString(), "trigger" to "claim", "fromRole" to Role.TERMINAL.name.lowercase()),
        )

    /** A claim of a lifecycle-free item: the `invalid_transition` of the `claim` trigger from `none`, nothing allowed. */
    fun claimLifecycleFree(id: UUID): DomainError =
        DomainError(
            code = ErrorCode.INVALID_TRANSITION,
            message = "Item $id is lifecycle-free and cannot be claimed.",
            detail = ErrorDetail.InvalidTransition(id, NONE_ROLE_WIRE, "claim", emptyList()),
            fixArgs = mapOf("itemId" to id.toString(), "trigger" to "claim", "fromRole" to NONE_ROLE_WIRE),
        )

    /** A per-root config read failed before anything was written: the element is shed with a retry hint. */
    fun configUnavailable(message: String?): DomainError =
        DomainError(
            code = ErrorCode.UNAVAILABLE,
            message = message?.takeIf { it.isNotBlank() } ?: "The project configuration is temporarily unavailable.",
            detail = ErrorDetail.Unavailable(CONFIG_RETRY_AFTER_MS),
        )

    /**
     * [error] with every `invalid_request` field pointer rooted at `/` (the services name fields without a leading
     * slash), so the dispatcher can place it under the element.
     */
    fun rooted(error: DomainError): DomainError {
        val detail = error.detail as? ErrorDetail.InvalidRequest ?: return error
        if (detail.fields.all { it.field.startsWith("/") }) return error
        return error.copy(
            detail = ErrorDetail.InvalidRequest(detail.fields.map { if (it.field.startsWith("/")) it else it.copy(field = "/${it.field}") })
        )
    }

    /** The trigger for the parsed wire name. */
    fun userTrigger(wire: String): Trigger.User = checkNotNull(Trigger.User.parse(wire)) { "unknown trigger $wire" }

    const val CONFIG_RETRY_AFTER_MS = 1000L
}
