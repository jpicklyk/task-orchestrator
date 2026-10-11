package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools

import io.github.jpicklyk.mcptask.current.application.service.JsonElementCodec
import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance.claimOp
import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance.heartbeatOp
import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance.releaseOp
import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance.transitionOp
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/** The `advance` tool name. */
const val ADVANCE_TOOL = "advance"

/**
 * The `advance` write tool (plan section 4.1, Q13): phase transitions and claim ownership in one `ops[]` call.
 * It replaces `advance_item`, `claim_item` and `POST /items/{id}/advance`. Each op lives in its own file under
 * `advance/`.
 */
fun advanceTool(): OpsToolSpec<JsonElement> =
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
