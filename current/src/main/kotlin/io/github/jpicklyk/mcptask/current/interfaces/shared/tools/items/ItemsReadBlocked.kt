package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.port.PageRequest
import io.github.jpicklyk.mcptask.current.application.read.BlockType
import io.github.jpicklyk.mcptask.current.application.read.BlockedEntry
import io.github.jpicklyk.mcptask.current.application.read.BlockedScope
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallResultCounts
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * `items_read` select `blocked` (orchestration profile): one page of the blocked items the caller may see, each with
 * its `blockType` and every blocking dependency into it.
 *
 * Blocked is defined once (AR-65): role `blocked` (`explicit`), or an open item (queue, work, review) with at least one
 * unsatisfied blocking dependency (`dependency`); never terminal. Whether an item is blocked and whether a blocker is
 * satisfied are decided on the true rows, but under a restricted scope a blocker outside the scope is omitted from
 * `blockers` (decision D-e) while its item stays listed. Default limit 50, max 200 (D-d).
 */
object ItemsReadBlocked {
    /** The `fields` names this select accepts. */
    val FIELDS: List<String> = listOf("summary", "ancestors")

    private const val MAX_LIMIT = 200

    @Serializable
    data class Request(
        @Doc(ItemsReadDocs.PARENT_ID) val parentId: IdRef? = null,
        @Doc(ItemsReadDocs.ANCESTOR_ID) val ancestorId: IdRef? = null,
        @Doc(ItemsReadDocs.LIMIT) val limit: Int? = null,
        @Doc(ItemsReadDocs.CURSOR) val cursor: String? = null,
        @Doc(ItemsReadDocs.FIELDS) val fields: List<String>? = null,
    )

    val select =
        SelectSpec.Single<Request, JsonObject>(
            name = "blocked",
            doc =
                "A page of the blocked items (default limit 50, max 200): role blocked (blockType explicit) or open with an " +
                    "unsatisfied blocking dependency (dependency), each with its blockers (id, title, role, threshold, " +
                    "satisfied). fields: summary, ancestors.",
            profiles = orchestration() + director(),
            request = serializer<Request>(),
            errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN, ErrorCode.INVALID_CURSOR),
            handler = handler@{ env, ctx, req ->
                acceptFields(req.fields, FIELDS)?.let { return@handler Outcome.Err(it) }
                val page =
                    when (val p = pageRequest(req.limit, req.cursor, PageRequest.DEFAULT_LIMIT, MAX_LIMIT)) {
                        is Outcome.Err -> return@handler p
                        is Outcome.Ok -> p.value
                    }
                val parentId =
                    when (val r = resolveOptionalId(env, ctx, req.parentId, "parentId")) {
                        is Outcome.Err -> return@handler r
                        is Outcome.Ok -> r.value
                    }
                val ancestorId =
                    when (val r = resolveOptionalId(env, ctx, req.ancestorId, "ancestorId")) {
                        is Outcome.Err -> return@handler r
                        is Outcome.Ok -> r.value
                    }
                val blocked = env.workViewQueries.blocked(BlockedScope(parentId, ancestorId), page, ctx.scope)
                recordCallResultCounts(blocked.total, null)
                val wanted = req.fields.orEmpty().toSet()
                val chains = ancestorChains(env, blocked.items.map { it.item }, "ancestors" in wanted)
                Outcome.Ok(
                    buildJsonObject {
                        putPage(blocked) { entry ->
                            val view = entryView(entry, "summary" in wanted)
                            if ("ancestors" in wanted) withAncestors(view, entry.item, chains, ctx.scope) else view
                        }
                    }
                )
            },
            data = { it }
        )

    /** The compact view of the blocked item plus `blockType` and `blockers`. */
    private fun entryView(
        entry: BlockedEntry,
        summary: Boolean
    ): JsonObject {
        var view = ItemView.compact(entry.item, summary = summary)
        view = ItemView.with(view, "blockType", JsonPrimitive(if (entry.blockType == BlockType.EXPLICIT) "explicit" else "dependency"))
        entry.reason?.let { view = ItemView.with(view, "reason", JsonPrimitive(it.wire)) }
        entry.decisionId?.let { view = ItemView.with(view, "decisionId", JsonPrimitive(it.toString())) }
        entry.resumable?.let { view = ItemView.with(view, "resumable", JsonPrimitive(it)) }
        if (entry.blockers.isNotEmpty()) {
            val blockers =
                entry.blockers.map { blocker ->
                    buildJsonObject {
                        put("id", blocker.blockerId.toString())
                        blocker.title?.let { put("title", it) }
                        blocker.role?.let { put("role", it.name.lowercase()) }
                        put("threshold", blocker.threshold.name.lowercase())
                        put("satisfied", blocker.satisfied)
                    }
                }
            view = ItemView.with(view, "blockers", JsonArray(blockers))
        }
        return view
    }
}
