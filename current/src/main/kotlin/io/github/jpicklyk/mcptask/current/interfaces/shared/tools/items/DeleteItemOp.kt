package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.ItemCommandErrors
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
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
        profile = ToolProfile.KNOWLEDGE,
        request = serializer<DeleteItemRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.VERSION_CONFLICT,
                ErrorCode.INVALID_TRANSITION,
                ErrorCode.FORBIDDEN,
                ErrorCode.UNAVAILABLE
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

    val existing = env.workItemRepository().getById(id) ?: return Outcome.Err(ItemCommandErrors.itemNotFound(id))
    val expected = req.expectedVersion
    if (expected != null && expected != existing.version) {
        return Outcome.Err(ItemCommandErrors.versionConflict(existing.id, expected, existing.version))
    }
    return when (val deleted = env.itemCommandService.delete(id, req.recursive)) {
        is Outcome.Err -> deleted
        is Outcome.Ok ->
            Outcome.Ok(
                ItemWriteResult(
                    id = deleted.value.id,
                    deleted = 1 + deleted.value.descendantsDeleted,
                    cascade = cascadeSummaries(deleted.value.cascadeEvents)
                )
            )
    }
}
