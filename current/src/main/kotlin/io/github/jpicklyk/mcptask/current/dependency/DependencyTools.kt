package io.github.jpicklyk.mcptask.current.dependency

import io.github.jpicklyk.mcptask.current.dependency.internal.wire.depsCreateOp
import io.github.jpicklyk.mcptask.current.dependency.internal.wire.depsDeleteOp
import io.github.jpicklyk.mcptask.current.dependency.internal.wire.depsFanInOp
import io.github.jpicklyk.mcptask.current.dependency.internal.wire.depsFanOutOp
import io.github.jpicklyk.mcptask.current.dependency.internal.wire.depsGraphSelect
import io.github.jpicklyk.mcptask.current.dependency.internal.wire.depsLinearOp
import io.github.jpicklyk.mcptask.current.dependency.internal.wire.depsListSelect
import io.github.jpicklyk.mcptask.current.platform.idempotency.IdempotencyCodec
import io.github.jpicklyk.mcptask.current.platform.tool.OpsToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.SelectToolSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val DEPS_READ = "deps_read"

const val DEPS_WRITE = "deps_write"

private object JsonObjectCodec : IdempotencyCodec<JsonObject> {
    override fun encode(value: JsonObject): JsonElement = value

    override fun decode(json: JsonElement): JsonObject = json as JsonObject
}

/** The dependency slice's contribution to the tool registry: the `deps_read` and `deps_write` tools, in the registry's order. */
object DependencyTools {
    /** The deps_read tool: dependency edges and the blocking graph. Backlinks are list with direction incoming. */
    fun read(): SelectToolSpec =
        SelectToolSpec(
            name = DEPS_READ,
            description =
                "Read dependency edges between items. select: list (edges of one item, with direction and type filters) or " +
                    "graph (the blocking graph around an item). Edge: {id, from, to, type, unblockAt?}; from blocks to.",
            selects = listOf(depsListSelect, depsGraphSelect)
        )

    /**
     * The deps_write tool: create and delete dependency edges, plus the linear, fan_out and fan_in patterns. A pattern
     * is ONE op element and one write: its edges are created together or not at all.
     */
    fun write(): OpsToolSpec<JsonObject> =
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
}
