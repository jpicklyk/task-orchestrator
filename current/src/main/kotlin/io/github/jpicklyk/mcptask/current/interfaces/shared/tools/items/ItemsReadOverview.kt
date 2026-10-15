package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.PageRequest
import io.github.jpicklyk.mcptask.current.application.port.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.application.read.OverviewEntry
import io.github.jpicklyk.mcptask.current.application.read.OverviewRequest
import io.github.jpicklyk.mcptask.current.application.read.OverviewView
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallResultCounts
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.BlockedReason
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidFields
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * `items_read` select `overview`, over the read model's overview view (a bounded number of statements whatever the
 * number of items):
 *
 * - global (no id): `{items, page}`, the parentless items the caller may see, each with direct `childCounts` and a
 *   `claimSummary` (counts only), plus `children` with `includeChildren`;
 * - scoped (`itemId`): `{item, childCounts, items, page}`, the item's full view and a page of its children;
 * - anchored (`anchorId`): `{anchor: {id, title}, items, page}`, the anchor's children with full-subtree counts.
 *
 * Target ids are resolved under D2 and scope-checked. Under a restricted scope every list and count is visible-only
 * (D4). Zero-count roles are omitted.
 */
object ItemsReadOverview {
    @Serializable
    data class Request(
        @Doc(ItemsReadDocs.ITEM_ID) val itemId: IdRef? = null,
        @Doc("List this item's children with subtree role counts (UUID or 8-32 hex).")
        val anchorId: IdRef? = null,
        @Doc("Drop terminal items without open work below; default false.")
        val excludeTerminal: Boolean? = null,
        @Doc("Global only: add each item's children; default false.")
        val includeChildren: Boolean? = null,
        @Doc(ItemsReadDocs.LIMIT) val limit: Int? = null,
        @Doc(ItemsReadDocs.CURSOR) val cursor: String? = null,
    )

    val select =
        SelectSpec.Single<Request, JsonObject>(
            name = "overview",
            doc = "A hierarchy summary: global, one item's children (itemId) or an anchor's children (anchorId).",
            profiles = knowledge() + director(),
            request = serializer<Request>(),
            errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN, ErrorCode.INVALID_CURSOR),
            handler = handler@{ env, ctx, req ->
                val faults = ArrayList<FieldViolation>()
                if (req.itemId != null && req.anchorId != null) {
                    faults.add(FieldViolation(pointer("anchorId"), "is not allowed with itemId"))
                }
                if (req.includeChildren != null && (req.itemId != null || req.anchorId != null)) {
                    faults.add(FieldViolation(pointer("includeChildren"), "is allowed only in the global overview"))
                }
                if (faults.isNotEmpty()) return@handler Outcome.Err(invalidFields(faults))
                val page =
                    when (val p = pageRequest(req.limit, req.cursor, PageRequest.DEFAULT_LIMIT, PageRequest.MAX_LIMIT)) {
                        is Outcome.Err -> return@handler p
                        is Outcome.Ok -> p.value
                    }
                val itemId =
                    if (req.itemId == null) {
                        null
                    } else {
                        when (val r = resolveTarget(env, ctx, req.itemId, "itemId")) {
                            is Outcome.Err -> return@handler r
                            is Outcome.Ok -> r.value
                        }
                    }
                val anchorId =
                    if (req.anchorId == null) {
                        null
                    } else {
                        when (val r = resolveTarget(env, ctx, req.anchorId, "anchorId")) {
                            is Outcome.Err -> return@handler r
                            is Outcome.Ok -> r.value
                        }
                    }
                val view =
                    env.workViewQueries.overview(
                        OverviewRequest(
                            itemId = itemId,
                            anchorId = anchorId,
                            page = page,
                            excludeTerminal = req.excludeTerminal ?: false,
                            includeChildren = req.includeChildren ?: false,
                            scope = ctx.scope,
                        )
                    )
                when (view) {
                    is OverviewView.NotFound -> Outcome.Err(itemNotFound(view.id))
                    is OverviewView.Global -> {
                        recordCallResultCounts(view.roots.total, null)
                        Outcome.Ok(buildJsonObject { putPage(view.roots) { entryView(it) } })
                    }
                    is OverviewView.Scoped -> {
                        recordCallResultCounts(view.children.total, null)
                        Outcome.Ok(
                            buildJsonObject {
                                put("item", ItemView.full(view.item, pin = env.schemaPinService.view(view.item)))
                                put("childCounts", countsJson(view.childCounts))
                                blockedByReasonJson(view.blockedByReason)?.let { put("blockedByReason", it) }
                                putPage(view.children) { entryView(it) }
                            }
                        )
                    }
                    is OverviewView.Anchored -> {
                        recordCallResultCounts(view.items.total, null)
                        Outcome.Ok(
                            buildJsonObject {
                                put(
                                    "anchor",
                                    buildJsonObject {
                                        put("id", view.anchor.id.toString())
                                        put("title", view.anchor.title)
                                    }
                                )
                                putPage(view.items) { entryView(it) }
                            }
                        )
                    }
                }
            },
            data = { it }
        )

    /** A listed entry: the compact view plus `childCounts`, and the global-only `claimSummary` / `children`. */
    private fun entryView(entry: OverviewEntry): JsonObject {
        var view = ItemView.with(ItemView.compact(entry.item), "childCounts", countsJson(entry.childCounts))
        blockedByReasonJson(entry.blockedByReason)?.let { view = ItemView.with(view, "blockedByReason", it) }
        entry.claimSummary?.let { view = ItemView.with(view, "claimSummary", claimJson(it)) }
        entry.children?.let { children -> view = ItemView.with(view, "children", JsonArray(children.map { entryView(it) })) }
        return view
    }

    /** The BLOCKED children by typed reason in enum order, zero counts omitted; null (member omitted) when none. */
    private fun blockedByReasonJson(counts: Map<BlockedReason, Int>): JsonObject? {
        val nonZero = BlockedReason.entries.filter { (counts[it] ?: 0) > 0 }
        if (nonZero.isEmpty()) return null
        return buildJsonObject { nonZero.forEach { put(it.wire, counts.getValue(it)) } }
    }

    private fun claimJson(counts: ClaimStatusCounts): JsonObject =
        buildJsonObject {
            put("active", counts.active)
            put("expired", counts.expired)
            put("unclaimed", counts.unclaimed)
        }
}
