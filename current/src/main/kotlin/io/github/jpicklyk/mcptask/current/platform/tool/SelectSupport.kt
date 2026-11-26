package io.github.jpicklyk.mcptask.current.platform.tool

import io.github.jpicklyk.mcptask.current.model.error.DomainError
import io.github.jpicklyk.mcptask.current.model.error.EntityKind
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.platform.call.ScopedIds
import io.github.jpicklyk.mcptask.current.platform.calltelemetry.recordCallFields
import io.github.jpicklyk.mcptask.current.platform.port.AccessScope
import io.github.jpicklyk.mcptask.current.platform.port.CursorCodec
import io.github.jpicklyk.mcptask.current.platform.port.ListPage
import io.github.jpicklyk.mcptask.current.platform.port.PageRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

// ---- shared select helpers: the kit every `items_read` select (and a slice's select) builds on ------------------

/** The JSON pointer of the top-level request field [name]. */
internal fun pointer(name: String): String = "/$name"

/** [ScopedIds.denied]. */
internal fun scopeDenied(scope: AccessScope): DomainError = ScopedIds.denied(scope)

/**
 * The page a select reads: [limit] (default [default], `invalid_request` on `/limit` outside `1..max`) and [cursor]
 * (`invalid_cursor` when it does not decode).
 */
internal fun pageRequest(
    limit: Int?,
    cursor: String?,
    default: Int,
    max: Int
): Outcome<PageRequest> {
    val effective = limit ?: default
    if (effective !in 1..max) return Outcome.Err(invalidField(pointer("limit"), "must be between 1 and $max", effective.toString()))
    val offset =
        if (cursor == null) {
            0
        } else {
            when (val decoded = CursorCodec.decode(cursor)) {
                is Outcome.Err -> return decoded
                is Outcome.Ok -> decoded.value
            }
        }
    return Outcome.Ok(PageRequest(effective, offset))
}

/**
 * [unknownFields], and, when the names are all accepted, notes them on the call's telemetry (`request_shape.fields`).
 * A select calls this once, in place of [unknownFields].
 */
internal suspend fun acceptFields(
    fields: List<String>?,
    allowed: List<String>
): DomainError? {
    val error = unknownFields(fields, allowed)
    if (error == null && !fields.isNullOrEmpty()) recordCallFields(fields)
    return error
}

/** `invalid_request` on `/fields` for any name of [fields] outside [allowed]; null when all are allowed. */
internal fun unknownFields(
    fields: List<String>?,
    allowed: List<String>
): DomainError? {
    val bad = fields?.firstOrNull { it !in allowed } ?: return null
    return invalidField(pointer("fields"), "must contain only: ${allowed.joinToString(", ")}", bad)
}

/**
 * A [ListPage] of [items] read at [page] out of [total], built by [ListPage.of] (envelope amendment U22: `truncated` and
 * `next` mean "items exist after this page", so the last page carries no cursor). Page and count share one read unit, so
 * `total >= items.size`; the max only guards the [ListPage] invariant.
 */
internal fun <T> pageOf(
    items: List<T>,
    total: Int,
    page: PageRequest,
    capped: Boolean = false
): ListPage<T> = ListPage.of(items, total = maxOf(total, items.size), page = page, capped = capped)

/** `{items, page}` members of [page] (the encoder's list data shape), for a select whose data carries more. */
internal fun <T> JsonObjectBuilder.putPage(
    page: ListPage<T>,
    item: (T) -> JsonElement
) {
    put("items", JsonArray(page.items.map(item)))
    put(
        "page",
        buildJsonObject {
            put("total", page.total)
            put("truncated", page.truncated)
            if (page.capped) put("capped", true)
            page.next?.let { put("next", it) }
        }
    )
}

/** Role counts as `{role: n}`, zero roles omitted, in role order. */
internal fun countsJson(counts: Map<Role, Int>): JsonObject =
    buildJsonObject {
        for (role in Role.entries) {
            val n = counts[role] ?: 0
            if (n > 0) put(role.name.lowercase(), n)
        }
    }

/** `not_found` for an item id that does not exist (visible only to an unrestricted caller, see [resolveTarget]). */
internal fun itemNotFound(id: UUID): DomainError =
    DomainError(
        code = ErrorCode.NOT_FOUND,
        message = "No item matches $id.",
        detail = ErrorDetail.NotFound(EntityKind.ITEM, id.toString()),
        fixArgs = mapOf("kind" to "item", "id" to id.toString())
    )
