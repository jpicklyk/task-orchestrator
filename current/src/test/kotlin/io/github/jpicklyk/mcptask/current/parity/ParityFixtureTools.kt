package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorFixtures
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.findByFilters
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.util.UUID

/**
 * Test-only 4.0 tools that read and rename REAL work items through [ScopeGuard] (task-scope D2: scope is enforced inside the
 * handler, capability by the dispatcher). They are the registry the parity harness runs while the production registry is empty.
 *
 *  - `pf_items` select tool: `get` (one item, scope-guarded), `list` (every item the caller's scope admits; out-of-scope items
 *    are filtered, not refused).
 *  - `pf_edit` ops tool: `rename` (needs `write-items`), `retag` (needs `write-notes`); both scope-guarded.
 *
 * [divergentRegistry] holds two deliberately surface-dependent tools used only to prove the harness is not vacuous: `pf_surface`
 * echoes `ctx.surface` in its body, `pf_stamp` writes `ctx.surface` into a title (same body on both surfaces, different
 * database).
 */
class ParityFixtureTools {
    companion object {
        const val ITEMS = "pf_items"
        const val EDIT = "pf_edit"
        const val SURFACE = "pf_surface"
        const val STAMP = "pf_stamp"

        private fun parseUuid(raw: String): UUID? = runCatching { UUID.fromString(raw) }.getOrNull()

        private fun view(
            id: UUID,
            title: String
        ): JsonObject =
            buildJsonObject {
                put("id", id.toString())
                put("title", title)
            }
    }

    @Serializable
    data class GetReq(
        @Doc("Item id.") val id: IdRef
    )

    @Serializable
    data class ListReq(
        @Doc("Maximum rows.") val limit: Int = 50
    )

    @Serializable
    data class RenameReq(
        @Doc("Item id.") val id: IdRef,
        @Doc("New title.") val title: String
    )

    @Serializable
    data class RetagReq(
        @Doc("Item id.") val id: IdRef,
        @Doc("New tags.") val tags: String
    )

    @Serializable
    data class EchoReq(
        @Doc("Echo marker.") val marker: String = "x"
    )

    @Serializable
    data class StampReq(
        @Doc("Item id.") val id: IdRef
    )

    val getSelect =
        SelectSpec.Single<GetReq, JsonObject>(
            name = "get",
            doc = "Read one item by id.",
            profiles = knowledge(),
            request = serializer<GetReq>(),
            errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.FORBIDDEN),
            handler = { env, ctx, req ->
                val id = parseUuid(req.id.raw)
                if (id == null) {
                    Outcome.Err(ErrorFixtures.error(ErrorCode.NOT_FOUND))
                } else {
                    val repo = env.repositoryProvider.workItemRepository()
                    when (val guard = ScopeGuard.check(ctx.scope, id, repo)) {
                        is Outcome.Err -> Outcome.Err(guard.error)
                        is Outcome.Ok -> {
                            val item = repo.getById(id)
                            if (item == null) {
                                Outcome.Err(ErrorFixtures.error(ErrorCode.NOT_FOUND))
                            } else {
                                Outcome.Ok(view(item.id, item.title))
                            }
                        }
                    }
                }
            },
            data = { it }
        )

    val listSelect =
        SelectSpec.Paged<ListReq, JsonObject>(
            name = "list",
            doc = "List the items visible to the caller.",
            profiles = knowledge(),
            request = serializer<ListReq>(),
            handler = { env, ctx, req ->
                val repo = env.repositoryProvider.workItemRepository()
                val visible =
                    repo
                        .findByFilters()
                        .items
                        .sortedBy { it.title }
                        .filter { ScopeGuard.check(ctx.scope, it.id, repo) is Outcome.Ok }
                        .take(req.limit)
                        .map { view(it.id, it.title) }
                Outcome.Ok(ListPage(visible, total = visible.size, truncated = false))
            },
            item = { it }
        )

    val renameOp =
        OpSpec<RenameReq, String>(
            name = "rename",
            doc = "Rename an item.",
            capability = ApiCapability.WRITE_ITEMS,
            profiles = knowledge(),
            request = serializer<RenameReq>(),
            errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.FORBIDDEN),
            execute = { _, ctx, req ->
                val id = parseUuid(req.id.raw)
                if (id == null) {
                    Outcome.Err(ErrorFixtures.error(ErrorCode.NOT_FOUND))
                } else {
                    val repo = stores.workItemRepository()
                    when (val guard = ScopeGuard.check(ctx.scope, id, repo)) {
                        is Outcome.Err -> Outcome.Err(guard.error)
                        is Outcome.Ok -> {
                            val item = repo.getById(id)
                            if (item == null) {
                                Outcome.Err(ErrorFixtures.error(ErrorCode.NOT_FOUND))
                            } else {
                                repo.update(item.copy(title = req.title))
                                Outcome.Ok("renamed:${req.id.raw}")
                            }
                        }
                    }
                }
            }
        )

    val retagOp =
        OpSpec<RetagReq, String>(
            name = "retag",
            doc = "Replace an item's tags.",
            capability = ApiCapability.WRITE_NOTES,
            profiles = knowledge(),
            request = serializer<RetagReq>(),
            errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.FORBIDDEN),
            execute = { _, ctx, req ->
                val id = parseUuid(req.id.raw)
                if (id == null) {
                    Outcome.Err(ErrorFixtures.error(ErrorCode.NOT_FOUND))
                } else {
                    val repo = stores.workItemRepository()
                    when (val guard = ScopeGuard.check(ctx.scope, id, repo)) {
                        is Outcome.Err -> Outcome.Err(guard.error)
                        is Outcome.Ok -> {
                            val item = repo.getById(id)
                            if (item == null) {
                                Outcome.Err(ErrorFixtures.error(ErrorCode.NOT_FOUND))
                            } else {
                                repo.update(item.copy(tags = req.tags))
                                Outcome.Ok("retagged:${req.id.raw}")
                            }
                        }
                    }
                }
            }
        )

    val itemsTool = SelectToolSpec(ITEMS, "Parity fixture read tool over real items.", listOf(getSelect, listSelect))

    val editTool =
        OpsToolSpec<String>(
            name = EDIT,
            description = "Parity fixture write tool over real items.",
            ops = listOf(renameOp, retagOp),
            codec = FixtureTools.StringCodec,
            element = { buildJsonObject { put("id", it) } }
        )

    /** The parity-clean registry: every member must be covered by the parity catalog. */
    val registry: ToolRegistry get() = ToolRegistry(listOf(itemsTool, editTool))

    private val echoSelect =
        SelectSpec.Single<EchoReq, String>(
            name = "echo",
            doc = "Echo the calling surface.",
            profiles = knowledge(),
            request = serializer<EchoReq>(),
            handler = { _, ctx, _ -> Outcome.Ok(ctx.surface) },
            data = { JsonPrimitive(it) }
        )

    private val stampOp =
        OpSpec<StampReq, String>(
            name = "stamp",
            doc = "Write the calling surface into an item's title.",
            capability = ApiCapability.WRITE_ITEMS,
            profiles = knowledge(),
            request = serializer<StampReq>(),
            errors = setOf(ErrorCode.NOT_FOUND),
            execute = { _, ctx, req ->
                val id = parseUuid(req.id.raw)
                val repo = stores.workItemRepository()
                val item = id?.let { repo.getById(it) }
                if (item == null) {
                    Outcome.Err(ErrorFixtures.error(ErrorCode.NOT_FOUND))
                } else {
                    repo.update(item.copy(title = "stamp:${ctx.surface}"))
                    Outcome.Ok("stamped")
                }
            }
        )

    private val surfaceTool = SelectToolSpec(SURFACE, "Surface-dependent fixture read tool.", listOf(echoSelect))

    private val stampTool =
        OpsToolSpec<String>(
            name = STAMP,
            description = "Surface-dependent fixture write tool.",
            ops = listOf(stampOp),
            codec = FixtureTools.StringCodec,
            element = { buildJsonObject { put("id", it) } }
        )

    /** Surface-dependent tools: running them through the harness MUST fail (non-vacuity). */
    val divergentRegistry: ToolRegistry get() = ToolRegistry(listOf(surfaceTool, stampTool))
}
