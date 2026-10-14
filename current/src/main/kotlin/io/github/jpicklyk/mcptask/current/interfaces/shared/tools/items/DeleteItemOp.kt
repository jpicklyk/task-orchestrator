package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.ClaimHolder
import io.github.jpicklyk.mcptask.current.application.service.FenceGuard
import io.github.jpicklyk.mcptask.current.application.service.ItemCommandErrors
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

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

internal fun deleteItemOp(): OpSpec<DeleteItemRequest, ItemWriteResult> =
    OpSpec(
        name = DeleteItemOp.NAME,
        doc = "Delete an item; a parent needs recursive.",
        destructive = true,
        capability = ApiCapability.WRITE_ITEMS,
        profiles = knowledge(),
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

private suspend fun WriteScope.deleteItem(
    env: ToolExecutionContext,
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
    val caller =
        when (val resolved = ClaimHolder.resolve(ctx.principal.claim, ctx.principal.verification, env.degradedModePolicy)) {
            is Outcome.Err -> return resolved
            is Outcome.Ok -> resolved.value
        }
    // The fence precedes the version compare (lookup -> fence -> version): a non-holder learns nothing from a conflict.
    // The service re-checks it, with the subtree, in the same unit.
    FenceGuard.check(existing, caller, now).let { if (it is Outcome.Err) return it }
    val expected = req.expectedVersion
    if (expected != null && expected != existing.version) {
        return Outcome.Err(ItemCommandErrors.versionConflict(existing.id, expected, existing.version))
    }
    return when (val deleted = env.itemCommandService.delete(id, req.recursive, caller)) {
        is Outcome.Err -> deleted
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
