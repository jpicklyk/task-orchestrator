package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.knowledge.search.Ranker
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchRequest
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchValidationException
import io.github.jpicklyk.mcptask.current.application.port.MAX_FTS_RESULTS
import io.github.jpicklyk.mcptask.current.application.port.SearchHit
import io.github.jpicklyk.mcptask.current.application.port.SearchMatchMode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * `items_read` select `search`: full-text search over item content, note bodies and document title/body through the
 * shared search service (every kind the filters apply to is fused in one ranking unless `kinds` narrows them; role,
 * tags and traits describe items, so they apply to item and note content only). The caller's scope filters candidates
 * before ranking and capping. `page.total` is the capped hit count; `page.capped` says more matches existed than the
 * cap. Filter ids (`itemId`, `ancestorId`) are resolved under D2 and not scope-checked.
 */
object ItemsReadSearch {
    @Serializable
    data class Request(
        @Doc("Search terms, all must match; matched literally.")
        val query: String,
        @Doc(
            "item (title, summary, description), note (bodies) and/or document (title, body); default every kind the " +
                "filters apply to. role, tags and traits apply to item and note only: with any of them the default is " +
                "item and note, and naming document too is invalid."
        )
        val kinds: List<KindWire>? = null,
        @Doc("auto (default: both, fused), substring (terms of 3+ characters), text (stemmed).")
        val matchMode: MatchModeWire? = null,
        @Doc(ItemsReadDocs.ITEM_ID) val itemId: IdRef? = null,
        @Doc(ItemsReadDocs.ANCESTOR_ID) val ancestorId: IdRef? = null,
        @Doc(ItemsReadDocs.ROLE) val role: RoleWire? = null,
        @Doc(ItemsReadDocs.TAGS) val tags: List<String>? = null,
        @Doc(ItemsReadDocs.TRAITS) val traits: List<String>? = null,
        @Doc("Add a highlighted snippet to each hit; default true.") val snippet: Boolean? = null,
        @Doc("Add per-analyzer ranks to each hit; default false.") val explain: Boolean? = null,
        @Doc(ItemsReadDocs.LIMIT) val limit: Int? = null,
        @Doc(ItemsReadDocs.CURSOR) val cursor: String? = null,
    )

    val select =
        SelectSpec.Paged<Request, JsonObject>(
            name = "search",
            doc = "Ranked full-text hits over item content, note bodies and documents the caller may see.",
            request = serializer<Request>(),
            errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN, ErrorCode.INVALID_CURSOR),
            handler = handler@{ env, ctx, req ->
                if (req.kinds != null && req.kinds.isEmpty()) {
                    return@handler Outcome.Err(invalidField(pointer("kinds"), "must not be empty"))
                }
                val page =
                    when (val p = pageRequest(req.limit, req.cursor, SearchRequest.DEFAULT_LIMIT, MAX_FTS_RESULTS)) {
                        is Outcome.Err -> return@handler p
                        is Outcome.Ok -> p.value
                    }
                val itemId =
                    when (val r = resolveOptionalId(env, ctx, req.itemId, "itemId")) {
                        is Outcome.Err -> return@handler r
                        is Outcome.Ok -> r.value
                    }
                val ancestorId =
                    when (val r = resolveOptionalId(env, ctx, req.ancestorId, "ancestorId")) {
                        is Outcome.Err -> return@handler r
                        is Outcome.Ok -> r.value
                    }
                val corpora = req.kinds?.map { it.corpus }?.toSet()
                val request =
                    SearchRequest(
                        query = req.query,
                        corpora = corpora,
                        matchMode = req.matchMode?.mode ?: SearchMatchMode.AUTO,
                        itemId = itemId,
                        ancestorId = ancestorId,
                        roles = req.role?.let { setOf(it.role) },
                        tags = req.tags,
                        traits = req.traits,
                        limit = page.limit,
                        offset = page.offset,
                        access = ctx.scope,
                        snippets = req.snippet ?: true,
                    )
                val result =
                    try {
                        env.searchService.search(request)
                    } catch (e: SearchValidationException) {
                        return@handler Outcome.Err(invalidField(pointer(e.field), e.message ?: "is not a valid search"))
                    }
                val snippet = req.snippet ?: true
                val explain = req.explain ?: false
                val hits = result.hits.map { hitView(it, snippet, explain) }
                Outcome.Ok(pageOf(hits, result.totalHits, page, capped = result.truncated))
            },
            item = { it }
        )

    private fun hitView(
        hit: SearchHit,
        snippet: Boolean,
        explain: Boolean
    ): JsonObject =
        buildJsonObject {
            put("kind", hit.kind)
            if (hit.documentKind != null) {
                // A document is addressed by (rootId, docKind, slug), the same key docs_read takes.
                put("rootId", ItemView.shortId(hit.itemId))
                put("docKind", hit.documentKind)
                hit.slug?.let { put("slug", it) }
            } else {
                put("itemId", ItemView.shortId(hit.itemId))
            }
            hit.noteKey?.let { put("noteKey", it) }
            hit.title?.let { put("title", it) }
            put("field", hit.field)
            if (snippet) put("snippet", hit.snippet)
            put("score", hit.score)
            put("matchedIn", JsonArray(hit.matchedIn.map { JsonPrimitive(it) }))
            if (explain) {
                put(
                    "explain",
                    buildJsonObject {
                        hit.trigramRank?.let { put("trigramRank", it) }
                        hit.textRank?.let { put("textRank", it) }
                        put("rrfK", Ranker.K)
                    }
                )
            }
        }
}
