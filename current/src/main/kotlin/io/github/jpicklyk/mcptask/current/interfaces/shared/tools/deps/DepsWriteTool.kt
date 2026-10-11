package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.deps

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCodec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val DEPS_WRITE = "deps_write"

private object JsonObjectCodec : IdempotencyCodec<JsonObject> {
    override fun encode(value: JsonObject): JsonElement = value

    override fun decode(json: JsonElement): JsonObject = json as JsonObject
}

/**
 * The deps_write tool: create and delete dependency edges, plus the linear, fan_out and fan_in patterns. A pattern
 * is ONE op element and one write: its edges are created together or not at all.
 */
fun depsWriteTool(): OpsToolSpec<JsonObject> =
    OpsToolSpec(
        name = DEPS_WRITE,
        description =
            "Create and delete dependency edges between items: create, delete, and the patterns linear, fan_out and fan_in " +
                "(one element, all edges or none). Each op needs the manage-dependencies capability and sees only items in " +
                "scope. Result: an edge {id, from, to, type, unblockAt?}, {created: [edge]} or {deleted: n}.",
        ops = listOf(depsCreateOp, depsDeleteOp, depsLinearOp, depsFanOutOp, depsFanInOp),
        codec = JsonObjectCodec,
        element = { it }
    )
