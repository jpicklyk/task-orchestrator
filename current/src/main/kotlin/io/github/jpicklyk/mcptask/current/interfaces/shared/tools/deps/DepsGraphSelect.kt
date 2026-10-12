package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.deps

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.api.PageRequest
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.deps.DepsGraphRequest
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.graph.DependencyEdges
import io.github.jpicklyk.mcptask.current.domain.graph.TopoOrder
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.util.UUID

/** `deps_read graph`: the blocking graph reachable from one item, blockers first. */
internal val depsGraphSelect =
    SelectSpec.Paged<DepsGraphRequest, JsonObject>(
        name = "graph",
        doc =
            "The blocking graph around one item: every item connected to it by blocks edges in either direction, blockers " +
                "first. level is the longest blocker path from an item with no blocker; cyclic items come last. relates_to " +
                "edges are ignored. Items outside the scope of the call are neither listed nor traversed. page.capped means " +
                "the walk stopped at ${DepsSupport.MAX_GRAPH_NODES} items.",
        profile = ToolProfile.KNOWLEDGE,
        request = serializer<DepsGraphRequest>(),
        errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
        handler = { env, ctx, req -> graph(env, ctx, req) },
        item = { it }
    )

private suspend fun graph(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: DepsGraphRequest
): Outcome<ListPage<JsonObject>> {
    val root =
        when (val r = DepsSupport.resolve(env, req.item, "/item")) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val page =
        when (val p = PageRequest.from(req.limit, req.cursor)) {
            is Outcome.Ok -> p.value
            is Outcome.Err -> return p
        }
    return env.unitOfWork.read {
        val items = stores.workItemRepository()
        val deps = stores.dependencyRepository()
        when (val guard = ScopeGuard.check(ctx.scope, root, items)) {
            is Outcome.Err -> return@read guard
            is Outcome.Ok -> Unit
        }
        if (items.getById(root) == null) return@read Outcome.Err(DepsSupport.notFound(EntityKind.ITEM, root.toString()))

        val visited = LinkedHashSet<UUID>().apply { add(root) }
        val seen = LinkedHashMap<UUID, Dependency>()
        var capped = false
        var frontier: List<UUID> = listOf(root)
        while (frontier.isNotEmpty() && !capped) {
            val discovered = LinkedHashSet<UUID>()
            for ((node, rows) in deps.findByItemIds(frontier.toSet())) {
                for (row in rows) {
                    val dep = row.normalized()
                    if (dep.type != DependencyType.BLOCKS) continue
                    seen[dep.id] = dep
                    val other = if (dep.fromItemId == node) dep.toItemId else dep.fromItemId
                    if (other !in visited) discovered += other
                }
            }
            val admitted = ScopeGuard.admitted(ctx.scope, discovered, items)
            val next = ArrayList<UUID>()
            for (id in discovered.sorted()) {
                if (id !in admitted) continue
                if (visited.size >= DepsSupport.MAX_GRAPH_NODES) {
                    capped = true
                    break
                }
                visited += id
                next += id
            }
            frontier = next
        }

        val nodes = visited.sorted()
        val edges = DependencyEdges.normalize(seen.values.filter { it.fromItemId in visited && it.toItemId in visited }).blocking
        val topo = TopoOrder.order(nodes, edges)
        val blockersOf = edges.groupBy({ it.blocked }, { it.blocker })
        val level = HashMap<UUID, Int>()
        for (id in topo.ordered) {
            level[id] = (blockersOf[id].orEmpty().mapNotNull { level[it] }.maxOrNull() ?: -1) + 1
        }
        val ordered =
            topo.ordered.map { id ->
                buildJsonObject {
                    put("id", id.toString())
                    put("level", level.getValue(id))
                }
            }
        val cyclic =
            topo.cyclic.map { id ->
                buildJsonObject {
                    put("id", id.toString())
                    put("cyclic", true)
                }
            }
        Outcome.Ok(pageOfAll(ordered + cyclic, page, capped))
    }
}
