package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.port.WorkflowStoreProvider
import io.github.jpicklyk.mcptask.current.item.ItemView
import io.github.jpicklyk.mcptask.current.item.checkItemScope
import io.github.jpicklyk.mcptask.current.item.resolveItemId
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.request.Doc
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.orchestration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/** An `items_write` `repin` element: move one item's config pin to the current config heads. */
@Serializable
data class RepinItemRequest(
    @Doc(ItemDocs.ID) val id: IdRef,
    @Doc(ItemDocs.EXPECTED_VERSION) val expectedVersion: Long? = null
)

/** The wire name of the repin op. The op is orchestration-only: the knowledge profile neither lists nor accepts it. */
internal object RepinItemOp {
    const val NAME = "repin"
}

internal fun repinItemOp(): OpSpec<WorkflowToolServices, RepinItemRequest, ItemWriteResult, WorkflowStoreProvider> =
    OpSpec(
        name = RepinItemOp.NAME,
        doc = "Move the item's config pin to the current config heads.",
        profiles = orchestration(),
        capability = ApiCapability.WRITE_ITEMS,
        services = WorkflowToolServices::class,
        stores = WorkflowStoreProvider::class,
        request = serializer<RepinItemRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.VERSION_CONFLICT,
                ErrorCode.INVALID_TRANSITION,
                ErrorCode.FORBIDDEN,
                ErrorCode.UNAVAILABLE
            ),
        execute = { env, ctx, req -> itemWriteBody(ctx) { repinItem(env, ctx, req) } }
    )

private suspend fun WriteScope<WorkflowStoreProvider>.repinItem(
    env: WorkflowToolServices,
    ctx: CallContext,
    req: RepinItemRequest
): Outcome<ItemWriteResult> {
    // Scope first (a missing item under a restricted scope is forbidden), then existence (the service reports it).
    val id =
        when (val r = resolveItemId(env, ctx.scope, req.id, "id")) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
    (checkItemScope(env, ctx, id) as? Outcome.Err)?.let { return it }

    return when (val repinned = env.itemCommandService.repin(id, req.expectedVersion)) {
        is Outcome.Err -> repinned
        is Outcome.Ok -> {
            val item = repinned.value.item
            Outcome.Ok(
                ItemWriteResult(
                    id = item.id,
                    version = item.version,
                    payload =
                        buildJsonObject {
                            put("repinned", repinned.value.repinned)
                            env.schemaPinService.view(item)?.let { put("schemaVersion", ItemView.schemaVersion(it)) }
                        }
                )
            )
        }
    }
}
