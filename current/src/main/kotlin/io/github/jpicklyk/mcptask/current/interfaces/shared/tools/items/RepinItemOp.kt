package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
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

internal fun repinItemOp(): OpSpec<RepinItemRequest, ItemWriteResult> =
    OpSpec(
        name = RepinItemOp.NAME,
        doc = "Move an item's config pin to the current config; repinned is false when it was current.",
        profile = ToolProfile.ORCHESTRATION,
        capability = ApiCapability.WRITE_ITEMS,
        request = serializer<RepinItemRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.VERSION_CONFLICT,
                ErrorCode.FORBIDDEN,
                ErrorCode.UNAVAILABLE
            ),
        execute = { env, ctx, req -> itemWriteBody(ctx) { repinItem(env, ctx, req) } }
    )

private suspend fun WriteScope.repinItem(
    env: ToolExecutionContext,
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
