package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.port.WorkflowStoreProvider
import io.github.jpicklyk.mcptask.current.application.service.ItemCommandErrors
import io.github.jpicklyk.mcptask.current.claim.FenceGuard
import io.github.jpicklyk.mcptask.current.model.claim.ClaimHolder
import io.github.jpicklyk.mcptask.current.model.error.DomainError
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.schema.SeatCaller
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.call.of
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.request.Doc
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import io.github.jpicklyk.mcptask.current.platform.support.ScopeGuard
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.knowledge
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import java.util.UUID

/** An `items_write` `delete` element: one item, or with [recursive] its whole subtree. */
@Serializable
data class DeleteItemRequest(
    @Doc(ItemDocs.ID) val id: IdRef,
    @Doc(ItemDocs.RECURSIVE) val recursive: Boolean = false,
    @Doc(ItemDocs.EXPECTED_VERSION) val expectedVersion: Long? = null
)

/** The wire name of the $(Delete delete[1]) op. */
internal object DeleteItemOp {
    const val NAME = "delete"
}

internal fun deleteItemOp(): OpSpec<WorkflowToolServices, DeleteItemRequest, ItemWriteResult, WorkflowStoreProvider> =
    OpSpec(
        name = DeleteItemOp.NAME,
        doc = "Delete an item; a parent needs recursive.",
        destructive = true,
        capability = ApiCapability.WRITE_ITEMS,
        profiles = knowledge(),
        services = WorkflowToolServices::class,
        stores = WorkflowStoreProvider::class,
        request = serializer<DeleteItemRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.VERSION_CONFLICT,
                ErrorCode.INVALID_TRANSITION,
                ErrorCode.FORBIDDEN,
                ErrorCode.UNAVAILABLE,
                ErrorCode.NOT_CLAIM_HOLDER,
                ErrorCode.UNAUTHENTICATED
            ),
        execute = { env, ctx, req -> itemWriteBody(ctx) { deleteItem(env, ctx, req) } }
    )

private suspend fun WriteScope<WorkflowStoreProvider>.deleteItem(
    env: WorkflowToolServices,
    ctx: CallContext,
    req: DeleteItemRequest
): Outcome<ItemWriteResult> {
    val id =
        when (val r = resolveItemId(env, ctx.scope, req.id, "id")) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
    (checkItemScope(env, ctx, id) as? Outcome.Err)?.let { return it }

    val repo = env.workItemRepository()
    val existing = repo.getById(id) ?: return Outcome.Err(ItemCommandErrors.itemNotFound(id))
    val caller = ClaimHolder.resolve(ctx.principal.claim, ctx.seat?.assignmentId)
    // The fence precedes the version compare (lookup -> fence -> version): a non-holder learns nothing from a conflict.
    // The service re-checks it, with the subtree, in the same unit.
    FenceGuard.check(existing, caller, now).let { if (it is Outcome.Err) return it }
    val expected = req.expectedVersion
    if (expected != null && expected != existing.version) {
        return Outcome.Err(ItemCommandErrors.versionConflict(existing.id, expected, existing.version))
    }
    return when (val deleted = env.itemCommandService.delete(id, req.recursive, caller, SeatCaller.of(ctx))) {
        is Outcome.Err -> Outcome.Err(scopedHeldRefusal(deleted.error, id, ctx, repo))
        is Outcome.Ok ->
            Outcome.Ok(
                ItemWriteResult(
                    id = deleted.value.id,
                    deleted = 1 + deleted.value.descendantsDeleted,
                    cascade = cascadeSummaries(deleted.value.cascadeEvents, ctx, repo)
                )
            )
    }
}

/**
 * A recursive delete refused on a held DESCENDANT names that descendant only when the call's scope admits it
 * ([ScopeGuard]): a tag scope can admit an item without admitting its descendants, and the refusal must not name an
 * item the caller cannot read. Otherwise it is the same `not_claim_holder` naming the deleted item [id] itself (the
 * detail's `itemId` is required, so it is never omitted). Any other error passes through unchanged.
 */
private suspend fun scopedHeldRefusal(
    error: DomainError,
    id: UUID,
    ctx: CallContext,
    repo: WorkItemRepository
): DomainError {
    val held = (error.detail as? ErrorDetail.NotClaimHolder)?.itemId ?: return error
    if (held == id) return error
    return when (ScopeGuard.check(ctx.scope, held, repo)) {
        is Outcome.Ok -> error
        is Outcome.Err -> FenceGuard.notClaimHolder(id)
    }
}
