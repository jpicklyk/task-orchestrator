package io.github.jpicklyk.mcptask.current.lifecycle

import io.github.jpicklyk.mcptask.current.lifecycle.internal.wire.ItemsReadBlocked
import io.github.jpicklyk.mcptask.current.lifecycle.internal.wire.ItemsReadContext
import io.github.jpicklyk.mcptask.current.lifecycle.internal.wire.ItemsReadNext
import io.github.jpicklyk.mcptask.current.lifecycle.internal.wire.ItemsReadSchema
import io.github.jpicklyk.mcptask.current.lifecycle.internal.wire.claimOp
import io.github.jpicklyk.mcptask.current.lifecycle.internal.wire.heartbeatOp
import io.github.jpicklyk.mcptask.current.lifecycle.internal.wire.releaseOp
import io.github.jpicklyk.mcptask.current.lifecycle.internal.wire.transitionOp
import io.github.jpicklyk.mcptask.current.platform.idempotency.JsonElementCodec
import io.github.jpicklyk.mcptask.current.platform.tool.OpsToolSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/** The lifecycle slice's contribution to the tool registry: the `advance` tool and four `items_read` selects. */
object LifecycleTools {
    /** The `advance` tool name. */
    const val ADVANCE_TOOL = "advance"

    /**
     * The `advance` write tool (plan section 4.1, Q13): phase transitions and claim ownership in one `ops[]` call.
     * It replaces `advance_item`, `claim_item` and `POST /items/{id}/advance`. Each op lives in its own file under
     * `internal/wire/`.
     */
    fun advance(): OpsToolSpec<JsonElement> =
        OpsToolSpec(
            name = ADVANCE_TOOL,
            description =
                "Move items through their phases and manage who owns them. Each ops[] element has an op: transition " +
                    "(apply a trigger), claim (take an item by id or by selector), release, or heartbeat (extend a held claim). " +
                    "A transition on an item claimed by someone else is not_claim_holder.",
            ops = listOf(transitionOp, claimOp, releaseOp, heartbeatOp),
            codec = JsonElementCodec,
            element = { it.jsonObject }
        )

    /** The `items_read` `schema` select. */
    val itemsReadSchema = ItemsReadSchema.select

    /** The `items_read` `context` select. */
    val itemsReadContext = ItemsReadContext.select

    /** The `items_read` `next` select. */
    val itemsReadNext = ItemsReadNext.select

    /** The `items_read` `blocked` select. */
    val itemsReadBlocked = ItemsReadBlocked.select
}
