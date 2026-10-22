package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallRoot
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallTargetVersion
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * `items_read` select `get`: one item by id, as [ItemView.full].
 *
 * The view carries the item's pin (`schemaVersion`, and `stale: true` when either pinned config version is no longer
 * the head) when it is pinned.
 *
 * Order: resolve the id under the caller's scope (D2), then [ScopeGuard][io.github.jpicklyk.mcptask.current.application.support.ScopeGuard]
 * (so an out-of-scope id is `forbidden` before any not-found or `ifVersion` answer), then read. `ifVersion` equal to
 * the stored version answers `{unchanged: true, version}` instead of the view. `fields` adds the three timestamps
 * and/or `ancestors` (compact, root-first, scope-truncated: D3).
 */
object ItemsReadGet {
    /** The `fields` names this select accepts. */
    val FIELDS: List<String> = listOf("createdAt", "modifiedAt", "roleChangedAt", "ancestors")

    @Serializable
    data class Request(
        @Doc(ItemsReadDocs.ITEM_ID) val itemId: IdRef,
        @Doc(ItemsReadDocs.FIELDS) val fields: List<String>? = null,
        @Doc("Answer {unchanged: true, version} instead of the item when its version equals this value.")
        val ifVersion: Long? = null,
    )

    val select =
        SelectSpec.Single<Request, JsonObject>(
            name = "get",
            doc = "One item by id: the full view; fields adds timestamps or ancestors; ifVersion short-circuits an unchanged item.",
            profiles = knowledge() + director(),
            request = serializer<Request>(),
            errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN),
            handler = { env, ctx, req ->
                val fieldsError = acceptFields(req.fields, FIELDS)
                if (fieldsError != null) {
                    Outcome.Err(fieldsError)
                } else {
                    when (val target = resolveTarget(env, ctx, req.itemId, "itemId")) {
                        is Outcome.Err -> target
                        is Outcome.Ok -> {
                            val repo = env.workItemRepository()
                            val item = repo.getById(target.value)
                            if (item == null) {
                                Outcome.Err(itemNotFound(target.value))
                            } else {
                                recordCallTargetVersion(item.id, item.version)
                                recordCallRoot(item.rootId ?: item.id.takeIf { item.parentId == null })
                                if (req.ifVersion != null && req.ifVersion == item.version) {
                                    Outcome.Ok(
                                        buildJsonObject {
                                            put("unchanged", true)
                                            put("version", item.version)
                                        }
                                    )
                                } else {
                                    val wanted = req.fields.orEmpty().toSet()
                                    var view = ItemView.full(item, pin = env.schemaPinService.view(item))
                                    for (name in listOf("createdAt", "modifiedAt", "roleChangedAt")) {
                                        if (name in wanted) view = ItemView.with(view, name, timestamp(item, name))
                                    }
                                    if ("ancestors" in wanted) {
                                        val chain = repo.findAncestorChains(setOf(item.id))[item.id] ?: emptyList()
                                        val visible = ItemView.visibleAncestors(chain, ctx.scope)
                                        view = ItemView.with(view, "ancestors", JsonArray(visible.map { ItemView.compact(it) }))
                                    }
                                    Outcome.Ok(view)
                                }
                            }
                        }
                    }
                }
            },
            data = { it }
        )

    private fun timestamp(
        item: io.github.jpicklyk.mcptask.current.domain.model.WorkItem,
        name: String
    ): kotlinx.serialization.json.JsonPrimitive =
        kotlinx.serialization.json.JsonPrimitive(
            when (name) {
                "createdAt" -> item.createdAt.toString()
                "modifiedAt" -> item.modifiedAt.toString()
                else -> item.roleChangedAt.toString()
            }
        )
}
