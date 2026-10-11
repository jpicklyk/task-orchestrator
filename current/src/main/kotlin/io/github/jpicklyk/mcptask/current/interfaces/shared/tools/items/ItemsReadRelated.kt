package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.knowledge.related.RelatedHit
import io.github.jpicklyk.mcptask.current.application.knowledge.related.RelatedRequest
import io.github.jpicklyk.mcptask.current.application.knowledge.related.RelatedService
import io.github.jpicklyk.mcptask.current.application.knowledge.related.RelatedSignal
import io.github.jpicklyk.mcptask.current.application.knowledge.search.Ranker
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * `items_read` select `related`: the items and documents related to one item, fused from four signals (dependency
 * edges, links, shared tags, similar text) by [RelatedService]. The item id is resolved and scope-checked like `get`
 * (D2: `forbidden`, `not_found`, `ambiguous_id`). Every signal is bound to the item's root unless `acrossRoots` is
 * true; the caller's scope always applies before fusion, so out-of-scope relatives are absent and uncounted.
 * `page.total` is the capped relative count; `page.capped` says more existed than the cap.
 */
object ItemsReadRelated {
    @Serializable
    data class Request(
        @Doc(ItemsReadDocs.ITEM_ID) val itemId: IdRef,
        @Doc("Look beyond the item's own root (every signal); default false. The caller's scope still applies.")
        val acrossRoots: Boolean? = null,
        @Doc(ItemsReadDocs.EXPLAIN) val explain: Boolean? = null,
        @Doc(ItemsReadDocs.LIMIT) val limit: Int? = null,
        @Doc(ItemsReadDocs.CURSOR) val cursor: String? = null,
    )

    val select =
        SelectSpec.Paged<Request, JsonObject>(
            name = "related",
            doc =
                "Items and documents related to an item, ranked by dependencies, links, shared tags and similar text; " +
                    "each hit names its reasons.",
            request = serializer<Request>(),
            errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN, ErrorCode.INVALID_CURSOR),
            handler = handler@{ env, ctx, req ->
                val page =
                    when (val p = pageRequest(req.limit, req.cursor, RelatedService.DEFAULT_LIMIT, RelatedService.MAX_LIMIT)) {
                        is Outcome.Err -> return@handler p
                        is Outcome.Ok -> p.value
                    }
                val itemId =
                    when (val r = resolveTarget(env, ctx, req.itemId, "itemId")) {
                        is Outcome.Err -> return@handler r
                        is Outcome.Ok -> r.value
                    }
                if (env.workItemRepository().getById(itemId) == null) return@handler Outcome.Err(itemNotFound(itemId))
                val result =
                    env.relatedService.related(
                        RelatedRequest(
                            ref = EntityRef(RefKind.ITEM, itemId),
                            limit = page.limit,
                            offset = page.offset,
                            acrossRoots = req.acrossRoots ?: false,
                            access = ctx.scope,
                        )
                    )
                val explain = req.explain ?: false
                Outcome.Ok(pageOf(result.hits.map { hitView(it, explain) }, result.total, page, capped = result.capped))
            },
            item = { it }
        )

    private fun hitView(
        hit: RelatedHit,
        explain: Boolean
    ): JsonObject =
        buildJsonObject {
            val document = hit.document
            if (document != null) {
                put("kind", "document")
                // A document is addressed by (rootId, docKind, slug), the same key search hits and docs_read use.
                put("rootId", ItemView.shortId(document.rootId))
                put("docKind", document.kind)
                put("slug", document.slug)
            } else {
                put("kind", "item")
                put("itemId", ItemView.shortId(hit.ref.id))
            }
            hit.title?.let { put("title", it) }
            put("score", hit.score)
            put("reasons", JsonArray(hit.reasons.map { JsonPrimitive(it) }))
            if (explain) {
                put(
                    "explain",
                    buildJsonObject {
                        put(
                            "ranks",
                            buildJsonObject {
                                for (signal in RelatedSignal.entries) hit.ranks[signal]?.let { put(signal.wire, it) }
                            }
                        )
                        put(
                            "weights",
                            buildJsonObject { for (signal in RelatedSignal.entries) put(signal.wire, signal.weight) }
                        )
                        put("rrfK", Ranker.K)
                    }
                )
            }
        }
}
